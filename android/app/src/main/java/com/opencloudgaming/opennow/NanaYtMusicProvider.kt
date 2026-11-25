package com.opencloudgaming.opennow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OkHttp-backed NewPipeExtractor [Downloader] reusing NanaPlay's existing OkHttp stack.
 */
internal class NanaYtMusicDownloader(
    private val client: OkHttpClient,
) : Downloader() {

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val url = request.url()
        val dataToSend = request.dataToSend()
        val requestBody = when {
            dataToSend != null -> dataToSend.toRequestBody(null)
            method.equals("POST", ignoreCase = true) ||
                method.equals("PUT", ignoreCase = true) ||
                method.equals("PATCH", ignoreCase = true) -> ByteArray(0).toRequestBody(null)
            else -> null
        }

        val okBuilder = okhttp3.Request.Builder()
            .method(method, requestBody)
            .url(url)
            .addHeader("User-Agent", DEFAULT_USER_AGENT)

        // Apply NewPipe headers after default User-Agent so client-specific UAs override cleanly.
        for ((headerName, headerValues) in request.headers()) {
            if (headerValues.isEmpty()) continue
            okBuilder.removeHeader(headerName)
            for (value in headerValues) {
                okBuilder.addHeader(headerName, value)
            }
        }

        client.newCall(okBuilder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }
            val bodyString = response.body?.string().orEmpty()
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                bodyString,
                response.request.url.toString(),
            )
        }
    }

    private companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    }
}

data class ResolvedYtAudioStream(
    val streamUrl: String,
    val relatedTracks: List<NanaOnlineTrack> = emptyList(),
)

/**
 * YouTube Music provider powered by NewPipeExtractor.
 * Search stays metadata-only (`music_songs`); stream URLs resolve lazily on play/retry.
 */
object NanaYtMusicProvider {

    internal const val YT_MUSIC_ID_PREFIX = "ytm:"
    private const val STREAM_URL_EXPIRY_LEEWAY_SECONDS = 90L

    private val initLock = Any()

    @Volatile
    private var initialized = false

    private val http: OkHttpClient by lazy {
        defaultHttpClient().newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .build()
    }

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(initLock) {
            if (!initialized) {
                if (NewPipe.getDownloader() == null) {
                    NewPipe.init(NanaYtMusicDownloader(http))
                }
                initialized = true
            }
        }
    }

    suspend fun searchSongs(query: String, limit: Int = 50): List<NanaOnlineTrack> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty() || limit <= 0) return@withContext emptyList()
            ensureInitialized()
            val service = ServiceList.YouTube
            val queryHandler = service.searchQHFactory.fromQuery(
                trimmed,
                listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
                "",
            )
            val info = SearchInfo.getInfo(service, queryHandler)
            info.relatedItems.orEmpty()
                .asSequence()
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull(::mapSearchItemToOnlineTrack)
                .take(limit)
                .toList()
        }

    suspend fun resolveAudioStreamUrl(urlOrId: String): String? =
        resolveAudioStreamWithRelated(urlOrId, maxRelated = 0)?.streamUrl

    suspend fun resolveAudioStreamWithRelated(
        urlOrId: String,
        maxRelated: Int = 5,
    ): ResolvedYtAudioStream? =
        withContext(Dispatchers.IO) {
            val pageUrl = resolveYoutubeWatchUrl(urlOrId) ?: return@withContext null
            ensureInitialized()
            val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, pageUrl)
            val streamUrl = selectBestAudioStreamUrl(streamInfo.audioStreams.orEmpty())
                ?: return@withContext null
            val selfId = extractYoutubeVideoId(pageUrl)?.let { "$YT_MUSIC_ID_PREFIX$it" }
            val related = if (maxRelated > 0) {
                extractRelatedTracks(streamInfo.relatedItems.orEmpty(), selfId, maxRelated)
            } else {
                emptyList()
            }
            ResolvedYtAudioStream(streamUrl = streamUrl, relatedTracks = related)
        }

    suspend fun fetchRelatedSongs(urlOrId: String, limit: Int = 5): List<NanaOnlineTrack> =
        withContext(Dispatchers.IO) {
            if (limit <= 0) return@withContext emptyList()
            val pageUrl = resolveYoutubeWatchUrl(urlOrId) ?: return@withContext emptyList()
            ensureInitialized()
            val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, pageUrl)
            val selfId = extractYoutubeVideoId(pageUrl)?.let { "$YT_MUSIC_ID_PREFIX$it" }
            extractRelatedTracks(streamInfo.relatedItems.orEmpty(), selfId, limit)
        }

    internal fun extractRelatedTracks(
        items: List<InfoItem>,
        excludeTrackId: String?,
        limit: Int,
    ): List<NanaOnlineTrack> {
        if (limit <= 0) return emptyList()
        return items
            .asSequence()
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull(::mapSearchItemToOnlineTrack)
            .filter { it.saavnId != excludeTrackId }
            .distinctBy { it.saavnId }
            .take(limit)
            .toList()
    }

    internal fun mapSearchItemToOnlineTrack(item: StreamInfoItem): NanaOnlineTrack? {
        val rawUrl = item.url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val title = item.name?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (item.streamType == StreamType.LIVE_STREAM || item.streamType == StreamType.AUDIO_LIVE_STREAM) {
            return null
        }
        val videoId = extractYoutubeVideoId(rawUrl)
        val trackId = if (videoId != null) {
            "$YT_MUSIC_ID_PREFIX$videoId"
        } else {
            "$YT_MUSIC_ID_PREFIX${rawUrl.hashCode().toUInt().toString(16)}"
        }
        val artist = item.uploaderName?.trim()?.takeIf { it.isNotBlank() } ?: "YouTube Music"
        val watchUrl = resolveYoutubeWatchUrl(videoId ?: rawUrl) ?: rawUrl
        return NanaOnlineTrack(
            saavnId = trackId,
            title = title,
            artist = artist,
            durationText = formatOnlineMusicDuration(item.duration),
            thumbnailUrl = pickBestThumbnailUrl(item.thumbnails),
            playCountText = formatOnlineMusicPlayCount(item.viewCount),
            source = NanaOnlineMusicSource.YOUTUBE_MUSIC,
            streamPageUrl = watchUrl,
        )
    }

    internal fun selectBestAudioStreamUrl(streams: List<AudioStream>): String? =
        selectBestAudioStream(streams)
            ?.content
            ?.trim()
            ?.takeIf(::isHttpUrl)

    /**
     * Picks the best progressive HTTP audio stream compatible with Media3 ExoPlayer,
     * preferring M4A/MP4 then Opus/WebM, original track over dubs, and highest bitrate.
     */
    internal fun selectBestAudioStream(streams: List<AudioStream>): AudioStream? {
        return streams
            .asSequence()
            .filter { stream ->
                stream.isUrl &&
                    stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                    isHttpUrl(stream.content)
            }
            .maxWithOrNull(
                compareBy<AudioStream>(
                    { trackTypePriority(it.audioTrackType) },
                    { audioFormatPriority(it.format) },
                    { maxOf(it.averageBitrate, it.bitrate, 0) },
                ),
            )
    }

    internal fun isYoutubeMusicTrackId(id: String?): Boolean =
        id?.startsWith(YT_MUSIC_ID_PREFIX) == true

    internal fun resolveYoutubeWatchUrl(urlOrId: String?): String? {
        val trimmed = urlOrId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (isHttpUrl(trimmed)) return trimmed
        val bareId = trimmed.removePrefix(YT_MUSIC_ID_PREFIX).trim()
        if (bareId.isEmpty() || bareId.contains(' ') || bareId.contains('/')) return null
        return runCatching { YoutubeStreamLinkHandlerFactory.getInstance().getUrl(bareId) }
            .getOrNull()
            ?: "https://www.youtube.com/watch?v=$bareId"
    }

    internal fun isStreamUrlNearExpiry(
        url: String?,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
    ): Boolean {
        val raw = url?.trim()?.takeIf { it.isNotBlank() } ?: return false
        val query = raw.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return false
        for (pair in query.split('&')) {
            val eqIdx = pair.indexOf('=')
            if (eqIdx <= 0) continue
            val key = pair.substring(0, eqIdx)
            if (key == "expire") {
                val expireSec = pair.substring(eqIdx + 1).toLongOrNull() ?: return false
                return expireSec <= nowEpochSeconds + STREAM_URL_EXPIRY_LEEWAY_SECONDS
            }
        }
        return false
    }

    private fun extractYoutubeVideoId(url: String): String? =
        runCatching { YoutubeStreamLinkHandlerFactory.getInstance().getId(url) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun pickBestThumbnailUrl(images: List<Image>): String? {
        if (images.isEmpty()) return null
        val httpImages = images.filter { isHttpUrl(it.url) }
        if (httpImages.isEmpty()) return null
        // Prefer balanced ~120..544px square/landscape thumbnails to save memory on 4GB devices.
        return httpImages
            .filter { it.height in 120..544 || it.width in 120..544 }
            .maxByOrNull { maxOf(it.width, it.height) }
            ?.url
            ?: httpImages.maxByOrNull { maxOf(it.width, it.height) }?.url
            ?: httpImages.first().url
    }

    private fun trackTypePriority(trackType: AudioTrackType?): Int = when (trackType) {
        AudioTrackType.ORIGINAL -> 2
        null -> 1
        else -> 0
    }

    private fun audioFormatPriority(format: MediaFormat?): Int = when (format) {
        MediaFormat.M4A, MediaFormat.MPEG_4 -> 3
        MediaFormat.WEBMA_OPUS, MediaFormat.OPUS, MediaFormat.WEBMA -> 2
        MediaFormat.MP3, MediaFormat.OGG -> 1
        else -> 0
    }

    private fun isHttpUrl(url: String?): Boolean {
        val trimmed = url?.trim() ?: return false
        return trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("http://", ignoreCase = true)
    }
}

data class NanaOnlineSearchResult(
    val tracks: List<NanaOnlineTrack>,
    val source: NanaOnlineMusicSource?,
    val usedFallback: Boolean,
)

/**
 * Coordinates online music search: YouTube Music primary, JioSaavn automatic fallback.
 */
object NanaMusicOnlineSearch {

    suspend fun searchSongs(query: String, limit: Int = 50): List<NanaOnlineTrack> =
        searchWithFallback(query, limit).tracks

    internal suspend fun searchWithFallback(
        query: String,
        limit: Int = 50,
        ytSearch: suspend (String, Int) -> List<NanaOnlineTrack> = { q, l ->
            NanaYtMusicProvider.searchSongs(q, l)
        },
        saavnSearch: suspend (String, Int) -> List<NanaOnlineTrack> = { q, l ->
            NanaSaavnApi.searchSongs(q, l)
        },
    ): NanaOnlineSearchResult {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return NanaOnlineSearchResult(emptyList(), source = null, usedFallback = false)
        }
        var ytError: Throwable? = null
        val ytTracks = try {
            ytSearch(trimmed, limit)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            ytError = t
            emptyList()
        }
        if (ytTracks.isNotEmpty()) {
            return NanaOnlineSearchResult(
                tracks = ytTracks,
                source = NanaOnlineMusicSource.YOUTUBE_MUSIC,
                usedFallback = false,
            )
        }
        val saavnTracks = try {
            saavnSearch(trimmed, limit)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (ytError != null) t.addSuppressed(ytError)
            throw t
        }
        return NanaOnlineSearchResult(
            tracks = saavnTracks,
            source = if (saavnTracks.isNotEmpty()) NanaOnlineMusicSource.JIOSAAVN else null,
            usedFallback = saavnTracks.isNotEmpty(),
        )
    }
}
