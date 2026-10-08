package com.opencloudgaming.opennow

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        data class Done(val sourceText: String, val translatedText: String) : TranslateState
        data class Error(val message: String) : TranslateState
    }

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
     */
    fun downscaleForOcr(bitmap: Bitmap, maxEdge: Int = 1024): Bitmap {
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
     * Runs OCR + translation on [bitmap] off the main thread.
     * [onState] is always invoked on the main thread.
     */
    fun translateFrame(
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
            try {
                emit(TranslateState.Recognizing)
                val small = downscaleForOcr(bitmap)
                val image = InputImage.fromBitmap(small, 0)
                val visionText = com.google.android.gms.tasks.Tasks.await(
                    textRecognizer.process(image)
                )
                val raw = visionText.text.trim()
                if (small !== bitmap) small.recycle()
                if (raw.isEmpty()) {
                    emit(TranslateState.Error("No text found in this frame"))
                    return@launch
                }
                emit(TranslateState.Translating)
                val translator = translatorFor(targetLang)
                // Download the language model on first use (needs network once).
                com.google.android.gms.tasks.Tasks.await(
                    translator.downloadModelIfNeeded()
                )
                val translated = com.google.android.gms.tasks.Tasks.await(
                    translator.translate(raw)
                )
                emit(TranslateState.Done(raw, translated))
            } catch (e: Exception) {
                emit(TranslateState.Error(e.message ?: "Translation failed"))
            } finally {
                withContext(Dispatchers.Main) {
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
