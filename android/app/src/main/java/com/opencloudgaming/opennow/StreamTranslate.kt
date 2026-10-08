package com.opencloudgaming.opennow

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.papahchan.nanaplay.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Tap-to-translate for the stream: captures a single frame, runs on-device OCR,
 * then on-device translation. Single-shot by design — no continuous processing,
 * so it stays light on battery and never touches the video decode path.
 */
object StreamTranslate {

    sealed interface TranslateState {
        data object Idle : TranslateState
        data object Capturing : TranslateState
        data object Recognizing : TranslateState
        data object Translating : TranslateState
        /**
         * Overlay result: [frame] is the (downscaled) bitmap OCR ran on,
         * [blocks] are translated text blocks with bounds in [frame]'s
         * coordinate space — the UI draws each translation over the
         * original text position, Google Lens style.
         */
        data class Done(
            val frame: Bitmap,
            val blocks: List<TranslatedBlock>,
            val sourceText: String,
            val translatedText: String,
        ) : TranslateState
        data class Error(val message: String) : TranslateState
    }

    /** One OCR text block with its translated text. Bounds are in frame pixels. */
    data class TranslatedBlock(
        val bounds: android.graphics.Rect,
        val text: String,
    )

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // Cache translators per target language so the model downloads only once.
    private val translators = mutableMapOf<String, com.google.mlkit.nl.translate.Translator>()

    private fun translatorFor(targetLang: String): com.google.mlkit.nl.translate.Translator {
        return translators.getOrPut(targetLang) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(targetLang)
                .build()
            Translation.getClient(options)
        }
    }

    /**
     * Downscale a bitmap so OCR stays fast on low-end phones.
     * Keeps aspect ratio, caps the long edge at [maxEdge] px.
     * Default 1920px: enough detail for small game text, still fast.
     */
    fun downscaleForOcr(bitmap: Bitmap, maxEdge: Int = 1920): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val longEdge = maxOf(w, h)
        if (longEdge <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longEdge
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, nw, nh, true)
    }

    /**
     * The Play Services thin OCR client needs working Google Play Services.
     * Without it, the recognizer throws a raw NullPointerException from inside
     * the GMS dynamite module — check first and fail with a friendly message.
     */
    fun isOcrAvailable(context: Context): Boolean {
        return runCatching {
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        }.getOrDefault(false)
    }

    /**
     * Checks if a bitmap is blank (all pixels nearly the same color).
     * PixelCopy can succeed but return a black/blank frame on some devices.
     */
    private fun isBitmapBlank(bitmap: Bitmap): Boolean {
        return runCatching {
            val w = bitmap.width
            val h = bitmap.height
            // Sample a grid of pixels across the frame.
            val stepX = (w / 10).coerceAtLeast(1)
            val stepY = (h / 10).coerceAtLeast(1)
            var firstColor = 0
            var first = true
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val c = bitmap.getPixel(x, y)
                    // Ignore alpha, compare RGB with tolerance for compression noise.
                    val rgb = c and 0x00FFFFFF
                    if (first) {
                        firstColor = rgb
                        first = false
                    } else {
                        val dr = ((rgb shr 16) and 0xFF) - ((firstColor shr 16) and 0xFF)
                        val dg = ((rgb shr 8) and 0xFF) - ((firstColor shr 8) and 0xFF)
                        val db = (rgb and 0xFF) - (firstColor and 0xFF)
                        if (kotlin.math.abs(dr) > 12 || kotlin.math.abs(dg) > 12 || kotlin.math.abs(db) > 12) {
                            return false // Found a meaningfully different pixel.
                        }
                    }
                    x += stepX
                }
                y += stepY
            }
            true
        }.getOrDefault(false)
    }

    /** Recreates the text recognizer (a stale instance can silently return empty). */
    private fun freshTextRecognizer(): com.google.mlkit.vision.text.TextRecognizer {
        runCatching { textRecognizer.close() }
        return TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Runs OCR + translation on [bitmap] off the main thread.
     * [onState] is always invoked on the main thread.
     * All failures surface as friendly [TranslateState.Error] messages —
     * never raw exception text.
     */
    fun translateFrame(
        context: Context,
        bitmap: Bitmap,
        targetLang: String,
        scope: CoroutineScope,
        onState: (TranslateState) -> Unit,
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        fun emit(state: TranslateState) {
            if (Looper.myLooper() == Looper.getMainLooper()) onState(state)
            else mainHandler.post { onState(state) }
        }
        scope.launch(Dispatchers.Default) {
            var small: Bitmap? = null
            var handedToDone = false
            try {
                // Pre-flight: Play Services must be working for the thin OCR client.
                if (!isOcrAvailable(context)) {
                    emit(TranslateState.Error(context.getString(R.string.translate_error_play_services)))
                    return@launch
                }
                emit(TranslateState.Recognizing)
                // Defense 1: PixelCopy can succeed but return a blank frame.
                if (isBitmapBlank(bitmap)) {
                    emit(TranslateState.Error(context.getString(R.string.translate_error_blank_frame)))
                    return@launch
                }
                small = downscaleForOcr(bitmap)
                var frame = small
                // First pass: OCR on the downscaled frame (fast).
                var recognizer = textRecognizer
                var visionText = runCatching {
                    com.google.android.gms.tasks.Tasks.await(
                        recognizer.process(InputImage.fromBitmap(frame, 0))
                    )
                }.getOrNull()
                var textBlocks = visionText?.textBlocks.orEmpty()
                    .filter { !it.text.isNullOrBlank() && it.boundingBox != null }
                var raw = visionText?.text?.trim().orEmpty()
                // Defense 2: a stale recognizer can silently return empty —
                // retry once with a fresh instance before falling back.
                if (raw.isEmpty() || textBlocks.isEmpty()) {
                    recognizer = freshTextRecognizer()
                    visionText = runCatching {
                        com.google.android.gms.tasks.Tasks.await(
                            recognizer.process(InputImage.fromBitmap(frame, 0))
                        )
                    }.getOrNull()
                    textBlocks = visionText?.textBlocks.orEmpty()
                        .filter { !it.text.isNullOrBlank() && it.boundingBox != null }
                    raw = visionText?.text?.trim().orEmpty()
                }
                // Second pass (fallback): if nothing found, retry at full
                // resolution — like Google Translate's "photo mode". Small
                // game text often only survives at full res.
                if ((raw.isEmpty() || textBlocks.isEmpty()) && frame !== bitmap) {
                    val fullText = runCatching {
                        com.google.android.gms.tasks.Tasks.await(
                            textRecognizer.process(InputImage.fromBitmap(bitmap, 0))
                        )
                    }.getOrNull()
                    val fullBlocks = fullText?.textBlocks.orEmpty()
                        .filter { !it.text.isNullOrBlank() && it.boundingBox != null }
                    val fullRaw = fullText?.text?.trim().orEmpty()
                    if (fullRaw.isNotEmpty() && fullBlocks.isNotEmpty()) {
                        // Full-res won: recycle the downscaled frame, Done owns bitmap.
                        runCatching { frame.recycle() }
                        small = bitmap
                        frame = bitmap
                        visionText = fullText
                        textBlocks = fullBlocks
                        raw = fullRaw
                    }
                }
                // Tasks.await() can return null when the GMS task completes empty —
                // guard before touching .text (Kotlin null-check would throw NPE).
                if (raw.isEmpty() || textBlocks.isEmpty()) {
                    emit(TranslateState.Error(context.getString(R.string.translate_error_no_text)))
                    return@launch
                }
                emit(TranslateState.Translating)
                val translator = translatorFor(targetLang)
                // Download the language model on first use (needs network once).
                // NOTE: downloadModelIfNeeded() returns Task<Void> — Tasks.await()
                // returns null on SUCCESS (Void has no instance), so check the
                // exception, not the result.
                val downloadResult = runCatching {
                    com.google.android.gms.tasks.Tasks.await(
                        translator.downloadModelIfNeeded()
                    )
                }
                if (downloadResult.isFailure) {
                    emit(TranslateState.Error(context.getString(R.string.translate_error_download_failed)))
                    return@launch
                }
                // Translate per text block so the UI can overlay each translation
                // on the original text position (Google Lens style).
                val translatedBlocks = textBlocks.mapNotNull { block ->
                    val box = block.boundingBox ?: return@mapNotNull null
                    // Skip tiny noise blocks.
                    if (box.width() < 8 || box.height() < 8) return@mapNotNull null
                    val t = runCatching {
                        com.google.android.gms.tasks.Tasks.await(
                            translator.translate(block.text)
                        )
                    }.getOrNull()?.trim()
                    if (t.isNullOrEmpty()) null
                    else TranslatedBlock(android.graphics.Rect(box), t)
                }
                if (translatedBlocks.isEmpty()) {
                    emit(TranslateState.Error(context.getString(R.string.translate_error_failed)))
                    return@launch
                }
                val translatedFull = translatedBlocks.joinToString("\n") { it.text }
                // Done owns `frame` now (for the overlay) — don't recycle below.
                handedToDone = true
                emit(TranslateState.Done(frame, translatedBlocks, raw, translatedFull))
            } catch (e: Exception) {
                // Never leak raw exception text (e.g. NPE internals) to the UI.
                val msg = when (e) {
                    is NullPointerException ->
                        context.getString(R.string.translate_error_ocr_failed)
                    else ->
                        context.getString(R.string.translate_error_failed)
                }
                emit(TranslateState.Error(msg))
            } finally {
                // Recycle bitmaps the UI doesn't own. Done owns `small`;
                // the original full-size bitmap is always recyclable here
                // (it's either === small and owned by Done, or garbage).
                val s = small
                if (!handedToDone && s != null && s !== bitmap) {
                    runCatching { s.recycle() }
                }
                if (!handedToDone || s !== bitmap) {
                    runCatching { bitmap.recycle() }
                }
            }
        }
    }

    fun close() {
        runCatching { textRecognizer.close() }
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
    }

    /** Language codes offered in the UI. */
    val targetLanguages = listOf(
        TranslateLanguage.INDONESIAN to "Indonesian",
        TranslateLanguage.ENGLISH to "English",
        TranslateLanguage.JAPANESE to "Japanese",
        TranslateLanguage.KOREAN to "Korean",
        TranslateLanguage.CHINESE to "Chinese",
        TranslateLanguage.SPANISH to "Spanish",
        TranslateLanguage.FRENCH to "French",
        TranslateLanguage.GERMAN to "German",
        TranslateLanguage.PORTUGUESE to "Portuguese",
        TranslateLanguage.ARABIC to "Arabic",
    )
}
