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
            ytSearch = { _, _ -> listOf(ytTrack) },
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
            ytSearch = { _, _ -> throw RuntimeException("HTTP 429") },
            saavnSearch = { _, _ -> listOf(saavnTrack) },
        )
        assertTrue(onError.usedFallback)
        assertEquals(NanaOnlineMusicSource.JIOSAAVN, onError.source)
        assertEquals(listOf(saavnTrack), onError.tracks)

        val onEmpty = NanaMusicOnlineSearch.searchWithFallback(
            query = "Night Drive",
            ytSearch = { _, _ -> emptyList() },
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
            ytSearch = { _, _ -> throw CancellationException("cancelled") },
            saavnSearch = { _, _ -> error("Should not run fallback after cancellation") },
        )
    }
}
