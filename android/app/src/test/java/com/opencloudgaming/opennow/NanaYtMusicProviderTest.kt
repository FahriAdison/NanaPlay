package com.opencloudgaming.opennow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

class NanaYtMusicProviderTest {

    private fun buildAudioStream(
        id: String,
        url: String,
        format: MediaFormat?,
        bitrate: Int,
        delivery: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP,
        isUrl: Boolean = true,
        trackType: AudioTrackType? = null,
    ): AudioStream = AudioStream.Builder()
        .setId(id)
        .setContent(url, isUrl)
        .setMediaFormat(format)
        .setDeliveryMethod(delivery)
        .setAverageBitrate(bitrate)
        .apply {
            if (trackType != null) {
                setAudioTrackType(trackType)
            }
        }
        .build()

    @Test
    fun selectBestAudioStream_prefersM4aOverOpusAndHighestBitrate() {
        val opusHigh = buildAudioStream(
            id = "251",
            url = "https://rr1.googlevideo.com/videoplayback?itag=251",
            format = MediaFormat.WEBMA_OPUS,
            bitrate = 160,
        )
        val m4aLow = buildAudioStream(
            id = "139",
            url = "https://rr1.googlevideo.com/videoplayback?itag=139",
            format = MediaFormat.M4A,
            bitrate = 48,
        )
        val m4aBest = buildAudioStream(
            id = "140",
            url = "https://rr1.googlevideo.com/videoplayback?itag=140",
            format = MediaFormat.M4A,
            bitrate = 128,
        )
        val selected = NanaYtMusicProvider.selectBestAudioStream(listOf(opusHigh, m4aLow, m4aBest))
        assertNotNull(selected)
        assertEquals("140", selected?.id)
    }

    @Test
    fun selectBestAudioStream_fallsBackToOpusWhenNoM4aAndSkipsNonProgressiveOrDubbed() {
        val hls = buildAudioStream(
            id = "91",
            url = "https://manifest.googlevideo.com/playlist.m3u8",
            format = MediaFormat.M4A,
            bitrate = 256,
            delivery = DeliveryMethod.HLS,
        )
        val nonUrlDash = buildAudioStream(
            id = "dash",
            url = "<MPD></MPD>",
            format = MediaFormat.M4A,
            bitrate = 256,
            delivery = DeliveryMethod.DASH,
            isUrl = false,
        )
        val dubbedM4a = buildAudioStream(
            id = "140-dub",
            url = "https://rr1.googlevideo.com/dubbed",
            format = MediaFormat.M4A,
            bitrate = 128,
            trackType = AudioTrackType.DUBBED,
        )
        val originalOpus = buildAudioStream(
            id = "251-orig",
            url = "https://rr1.googlevideo.com/opus",
            format = MediaFormat.WEBMA_OPUS,
            bitrate = 160,
            trackType = AudioTrackType.ORIGINAL,
        )
        val selected = NanaYtMusicProvider.selectBestAudioStream(listOf(hls, nonUrlDash, dubbedM4a, originalOpus))
        assertEquals("251-orig", selected?.id)
    }

    @Test
    fun selectBestAudioStream_returnsNullWhenNoPlayableProgressiveHttpStream() {
        val torrent = buildAudioStream(
            id = "t1",
            url = "magnet:?xt=urn:btih:1234",
            format = MediaFormat.MP3,
            bitrate = 320,
            delivery = DeliveryMethod.TORRENT,
        )
        assertNull(NanaYtMusicProvider.selectBestAudioStream(listOf(torrent)))
    }

    @Test
    fun isStreamUrlNearExpiry_checksExpireQueryParam() {
        val now = 1_700_000_000L
        assertTrue(NanaYtMusicProvider.isStreamUrlNearExpiry("https://rr.googlevideo.com/videoplayback?expire=1700000030&itag=140", now))
        assertFalse(NanaYtMusicProvider.isStreamUrlNearExpiry("https://rr.googlevideo.com/videoplayback?expire=1700005000&itag=140", now))
        assertFalse(NanaYtMusicProvider.isStreamUrlNearExpiry("https://aac.saavncdn.com/track.mp4", now))
    }

    @Test
    fun searchWithFallback_usesYoutubeMusicFirstWithoutCallingJioSaavn(): Unit = runBlocking {
        val ytTrack = NanaOnlineTrack(
            saavnId = "ytm:abc12345678",
            title = "Night Drive",
            artist = "Synth Artist",
            durationText = "3:45",
            thumbnailUrl = "https://i.ytimg.com/vi/abc12345678/hqdefault.jpg",
            playCountText = "1M plays",
            source = NanaOnlineMusicSource.YOUTUBE_MUSIC,
            streamPageUrl = "https://www.youtube.com/watch?v=abc12345678",
        )
        var saavnCalled = false
        val result = NanaMusicOnlineSearch.searchWithFallback(
            query = "Night Drive",
            ytSearch = { _, _, _ -> listOf(ytTrack) },
            saavnSearch = { _, _ ->
                saavnCalled = true
                emptyList()
            },
        )
        assertFalse(saavnCalled)
        assertFalse(result.usedFallback)
        assertEquals(NanaOnlineMusicSource.YOUTUBE_MUSIC, result.source)
        assertEquals(1, result.tracks.size)
    }

    @Test
    fun searchWithFallback_fallsBackToJioSaavnOnYoutubeErrorOrEmpty(): Unit = runBlocking {
        val saavnTrack = NanaOnlineTrack(
            saavnId = "saavn123",
            title = "Night Drive",
            artist = "Saavn Artist",
            durationText = "3:40",
            thumbnailUrl = null,
            playCountText = null,
            source = NanaOnlineMusicSource.JIOSAAVN,
        )
        val onError = NanaMusicOnlineSearch.searchWithFallback(
            query = "Night Drive",
            ytSearch = { _, _, _ -> throw RuntimeException("HTTP 429") },
            saavnSearch = { _, _ -> listOf(saavnTrack) },
        )
        assertTrue(onError.usedFallback)
        assertEquals(NanaOnlineMusicSource.JIOSAAVN, onError.source)
        assertEquals(listOf(saavnTrack), onError.tracks)

        val onEmpty = NanaMusicOnlineSearch.searchWithFallback(
            query = "Night Drive",
            ytSearch = { _, _, _ -> emptyList() },
            saavnSearch = { _, _ -> listOf(saavnTrack) },
        )
        assertTrue(onEmpty.usedFallback)
        assertEquals(NanaOnlineMusicSource.JIOSAAVN, onEmpty.source)
        assertEquals(listOf(saavnTrack), onEmpty.tracks)
    }

    @Test(expected = CancellationException::class)
    fun searchWithFallback_rethrowsCancellationException(): Unit = runBlocking {
        NanaMusicOnlineSearch.searchWithFallback(
            query = "Night Drive",
            ytSearch = { _, _, _ -> throw CancellationException("cancelled") },
            saavnSearch = { _, _ -> error("Should not run fallback after cancellation") },
        )
    }

    @Test
    fun searchWithFallback_forwardsSelectedFilter(): Unit = runBlocking {
        var capturedFilter: NanaMusicSearchFilter? = null
        NanaMusicOnlineSearch.searchWithFallback(
            query = "lofi study mix",
            filter = NanaMusicSearchFilter.LONG_MIXES,
            ytSearch = { _, filter, _ ->
                capturedFilter = filter
                listOf(
                    NanaOnlineTrack(
                        saavnId = "ytm:mix12345678",
                        title = "Lofi Mix 1 Hour",
                        artist = "Chill Beats",
                        durationText = "1:02:15",
                        thumbnailUrl = null,
                        playCountText = null,
                        source = NanaOnlineMusicSource.YOUTUBE_MUSIC,
                        durationSeconds = 3735L,
                    ),
                )
            },
            saavnSearch = { _, _ -> emptyList() },
        )
        assertEquals(NanaMusicSearchFilter.LONG_MIXES, capturedFilter)
    }

    @Test
    fun formatDurations_supportsHoursMinutesSeconds() {
        assertEquals("", formatOnlineMusicDuration(0L))
        assertEquals("0:00", formatPlaybackDurationMs(0L))
        assertEquals("3:45", formatOnlineMusicDuration(225L))
        assertEquals("1:22:18", formatOnlineMusicDuration(4938L))
        assertEquals("1:22:18", formatPlaybackDurationMs(4_938_900L))
    }

    @Test
    fun filterVideoTracks_excludesShortsAndZeroDuration() {
        fun videoTrack(id: String, sec: Long) = NanaOnlineTrack(
            saavnId = id,
            title = id,
            artist = "Uploader",
            durationText = formatOnlineMusicDuration(sec),
            thumbnailUrl = null,
            playCountText = null,
            source = NanaOnlineMusicSource.YOUTUBE_MUSIC,
            durationSeconds = sec,
        )
        val filtered = NanaYtMusicProvider.filterVideoTracks(
            listOf(
                videoTrack("ytm:live0", 0L),
                videoTrack("ytm:short1", 29L),
                videoTrack("ytm:short2", 44L),
                videoTrack("ytm:valid45", 45L),
                videoTrack("ytm:mix620", 620L),
            ),
        )
        assertEquals(listOf("ytm:valid45", "ytm:mix620"), filtered.map { it.saavnId })
    }

    @Test
    fun deduplicateAndInterleaveTracks_and_prioritizeLongMixes_workCleanly() {
        fun onlineTrack(id: String, sec: Long) = NanaOnlineTrack(
            saavnId = id,
            title = id,
            artist = "Artist",
            durationText = formatOnlineMusicDuration(sec),
            thumbnailUrl = null,
            playCountText = null,
            source = NanaOnlineMusicSource.YOUTUBE_MUSIC,
            durationSeconds = sec,
        )
        val songs = listOf(onlineTrack("ytm:s1", 200), onlineTrack("ytm:s2", 210), onlineTrack("ytm:s3", 190))
        val videos = listOf(onlineTrack("ytm:s2", 210), onlineTrack("ytm:v1", 3600), onlineTrack("ytm:v2", 900))
        val interleaved = NanaYtMusicProvider.deduplicateAndInterleaveTracks(songs, videos, limit = 5)
        assertEquals(
            listOf("ytm:s1", "ytm:v1", "ytm:s2", "ytm:v2", "ytm:s3"),
            interleaved.map { it.saavnId },
        )

        val prioritized = NanaYtMusicProvider.prioritizeLongMixes(
            listOf(
                onlineTrack("ytm:short", 30),
                onlineTrack("ytm:regular", 240),
                onlineTrack("ytm:mix30m", 1800),
                onlineTrack("ytm:mix80m", 4800),
            ),
            limit = 5,
        )
        assertEquals(
            listOf("ytm:mix80m", "ytm:mix30m", "ytm:regular"),
            prioritized.map { it.saavnId },
        )
    }

    @Test
    fun extractRelatedTracks_filtersLiveSelfAndDuplicates() {
        val selfItem = StreamInfoItem(0, "https://www.youtube.com/watch?v=self1111111", "Self", StreamType.AUDIO_STREAM).apply {
            duration = 200L
            uploaderName = "A"
        }
        val liveItem = StreamInfoItem(0, "https://www.youtube.com/watch?v=live1111111", "Live", StreamType.LIVE_STREAM).apply {
            duration = 0L
            uploaderName = "B"
        }
        val mixItem = StreamInfoItem(0, "https://www.youtube.com/watch?v=mixx1111111", "Mix 1h", StreamType.VIDEO_STREAM).apply {
            duration = 3700L
            uploaderName = "DJ Test"
            viewCount = 12_500L
        }
        val dupMix = StreamInfoItem(0, "https://www.youtube.com/watch?v=mixx1111111", "Mix 1h dup", StreamType.VIDEO_STREAM).apply {
            duration = 3700L
            uploaderName = "DJ Test"
        }
        val songItem = StreamInfoItem(0, "https://www.youtube.com/watch?v=song1111111", "Track 2", StreamType.VIDEO_STREAM).apply {
            duration = 215L
            uploaderName = "Artist 2"
        }

        val related = NanaYtMusicProvider.extractRelatedTracks(
            items = listOf(selfItem, liveItem, mixItem, dupMix, songItem),
            excludeTrackId = "ytm:self1111111",
            limit = 5,
        )
        assertEquals(2, related.size)
        assertEquals("ytm:mixx1111111", related[0].saavnId)
        assertEquals("1:01:40", related[0].durationText)
        assertEquals(3700L, related[0].durationSeconds)
        assertEquals("ytm:song1111111", related[1].saavnId)
    }
}
