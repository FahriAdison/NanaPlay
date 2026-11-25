package com.opencloudgaming.opennow

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NanaMusicAutoPlayTest {

    private fun track(id: String, durationSec: Long = 180L) = NanaOnlineTrack(
        saavnId = id,
        title = "Title $id",
        artist = "Artist $id",
        durationText = formatOnlineMusicDuration(durationSec),
        thumbnailUrl = null,
        playCountText = null,
        source = NanaOnlineMusicSource.YOUTUBE_MUSIC,
        streamPageUrl = "https://www.youtube.com/watch?v=${id.removePrefix("ytm:")}",
        durationSeconds = durationSec,
    )

    @Test
    fun computeNextPlaybackAction_respectsRepeatOneAndRepeatAll() {
        assertEquals(
            NanaAutoNextAction.LoopCurrent,
            computeNextPlaybackAction(
                tracksSize = 3,
                currentIndex = 1,
                repeatMode = NanaRepeatMode.ONE,
                shuffleEnabled = false,
                autoPlayEnabled = true,
                canFetchRelated = true,
            ),
        )
        assertEquals(
            NanaAutoNextAction.PlayQueueIndex(0),
            computeNextPlaybackAction(
                tracksSize = 3,
                currentIndex = 2,
                repeatMode = NanaRepeatMode.ALL,
                shuffleEnabled = false,
                autoPlayEnabled = false,
                canFetchRelated = false,
            ),
        )
    }

    @Test
    fun computeNextPlaybackAction_shuffleAvoidsRepeatingCurrentTrack() {
        val action = computeNextPlaybackAction(
            tracksSize = 3,
            currentIndex = 1,
            repeatMode = NanaRepeatMode.ALL,
            shuffleEnabled = true,
            autoPlayEnabled = true,
            canFetchRelated = true,
            randomIndexProvider = { 1 },
        )
        assertEquals(NanaAutoNextAction.PlayQueueIndex(2), action)
    }

    @Test
    fun computeNextPlaybackAction_fetchesRelatedOnlyAtQueueEndWhenAutoPlayAndOnlineEnabled() {
        assertEquals(
            NanaAutoNextAction.PlayQueueIndex(2),
            computeNextPlaybackAction(
                tracksSize = 4,
                currentIndex = 1,
                repeatMode = NanaRepeatMode.OFF,
                shuffleEnabled = false,
                autoPlayEnabled = true,
                canFetchRelated = true,
            ),
        )
        assertEquals(
            NanaAutoNextAction.FetchRelated,
            computeNextPlaybackAction(
                tracksSize = 1,
                currentIndex = 0,
                repeatMode = NanaRepeatMode.OFF,
                shuffleEnabled = false,
                autoPlayEnabled = true,
                canFetchRelated = true,
            ),
        )
        assertEquals(
            NanaAutoNextAction.Stop,
            computeNextPlaybackAction(
                tracksSize = 1,
                currentIndex = 0,
                repeatMode = NanaRepeatMode.OFF,
                shuffleEnabled = false,
                autoPlayEnabled = false,
                canFetchRelated = true,
            ),
        )
        assertEquals(
            NanaAutoNextAction.Stop,
            computeNextPlaybackAction(
                tracksSize = 2,
                currentIndex = 1,
                repeatMode = NanaRepeatMode.OFF,
                shuffleEnabled = false,
                autoPlayEnabled = true,
                canFetchRelated = false,
            ),
        )
    }

    @Test
    fun selectOnlineQueueOrderAndMergePreservesLocalTracks() {
        val s1 = track("ytm:1")
        val s2 = track("ytm:2")
        val s3 = track("ytm:3")
        val s4 = track("ytm:4")
        val ordered = selectOnlineQueueOrder(
            selected = s2,
            searchResults = listOf(s1, s2, s3, s4, s3),
        )
        assertEquals(listOf(s2, s3, s4), ordered)

        val localA = "local:a" to false
        val localB = "local:b" to false
        val oldOnline = "ytm:old" to true
        val newOnline = ordered.map { it.saavnId to true }
        val (merged, selectedIdx) = mergePreservingLocalTracks(
            currentQueue = listOf(localA, oldOnline, localB),
            isOnline = { it.second },
            newOnlineTracks = newOnline,
        )
        assertEquals(2, selectedIdx)
        assertEquals(
            listOf("local:a", "local:b", "ytm:2", "ytm:3", "ytm:4"),
            merged.map { it.first },
        )
    }

    @Test
    fun pickNextRelatedTrack_skipsCurrentQueueAndRecentlyPlayed() {
        val c1 = track("ytm:1")
        val c2 = track("ytm:2")
        val c3 = track("ytm:3")
        val c4 = track("ytm:4")

        val chosen = pickNextRelatedTrack(
            candidates = listOf(c1, c2, c3, c4),
            existingOnlineIds = setOf("ytm:2"),
            recentAutoPlayIds = listOf("ytm:3"),
            currentTrackId = "ytm:1",
        )
        assertEquals("ytm:4", chosen?.saavnId)

        val fallbackOnlyRecent = pickNextRelatedTrack(
            candidates = listOf(c1, c3),
            existingOnlineIds = emptySet(),
            recentAutoPlayIds = listOf("ytm:3"),
            currentTrackId = "ytm:1",
        )
        assertEquals("ytm:3", fallbackOnlyRecent?.saavnId)
    }

    @Test
    fun shouldClearPlayRequestedOnPause_preservesPlaybackRequestOnTransientAudioFocusOrCall() {
        assertFalse(
            shouldClearPlayRequestedOnPause(
                pausedForCall = false,
                playbackSuppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS,
            ),
        )
        assertFalse(
            shouldClearPlayRequestedOnPause(
                pausedForCall = true,
                playbackSuppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            ),
        )
        assertTrue(
            shouldClearPlayRequestedOnPause(
                pausedForCall = false,
                playbackSuppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            ),
        )
    }

    @Test
    fun isOnlineTrackMatch_matchesCanonicalYoutubeIdUrlAndFallbackIdWithoutRawStreamUrl() {
        val searchTrack = track("ytm:dQw4w9WgXcQ")
        assertTrue(
            isOnlineTrackMatch(
                searchTrack = searchTrack,
                currentIsOnline = true,
                currentSource = NanaOnlineMusicSource.YOUTUBE_MUSIC,
                currentId = "ytm:other",
                currentOnlineUrl = "https://youtu.be/dQw4w9WgXcQ?si=test",
                currentUriString = "https://rr1.googlevideo.com/videoplayback?expire=9999999999",
            ),
        )
        assertTrue(
            isOnlineTrackMatch(
                searchTrack = searchTrack,
                currentIsOnline = true,
                currentSource = NanaOnlineMusicSource.YOUTUBE_MUSIC,
                currentId = "ytm:dQw4w9WgXcQ",
                currentOnlineUrl = null,
                currentUriString = "https://rr2.googlevideo.com/videoplayback?expire=1234",
            ),
        )
        assertFalse(
            isOnlineTrackMatch(
                searchTrack = searchTrack,
                currentIsOnline = false,
                currentSource = NanaOnlineMusicSource.YOUTUBE_MUSIC,
                currentId = "ytm:dQw4w9WgXcQ",
                currentOnlineUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                currentUriString = "content://media/external/audio/1",
            ),
        )

        val saavnSearch = NanaOnlineTrack(
            saavnId = "saavn99",
            title = "Test",
            artist = "Artist",
            durationText = "3:00",
            thumbnailUrl = null,
            playCountText = null,
            source = NanaOnlineMusicSource.JIOSAAVN,
        )
        assertTrue(
            isOnlineTrackMatch(
                searchTrack = saavnSearch,
                currentIsOnline = true,
                currentSource = NanaOnlineMusicSource.JIOSAAVN,
                currentId = null,
                currentOnlineUrl = null,
                currentUriString = "nana-online://track/saavn99",
            ),
        )
        assertFalse(
            isOnlineTrackMatch(
                searchTrack = saavnSearch,
                currentIsOnline = true,
                currentSource = NanaOnlineMusicSource.JIOSAAVN,
                currentId = "saavn100",
                currentOnlineUrl = null,
                currentUriString = "https://aac.saavncdn.com/song.mp4",
            ),
        )
    }
}
