package com.opencloudgaming.opennow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueLaunchStatusTest {
    @Test
    fun seatSetupStepFiveQueuePositionIsNotDisplayed() {
        val session = session(queuePosition = 1, seatSetupStep = 5)
        val state = OpenNowUiState(
            streamStatus = "queue",
            launchPhase = "Queue",
            queuePosition = 1,
            streamSession = session,
        )

        assertNull(queueDisplayPosition(session))
        assertNull(queueDisplayPosition(state))
        assertEquals("Starting session", queueLaunchStatusText(state))
    }

    @Test
    fun seatSetupStepOneQueuePositionIsDisplayed() {
        val session = session(queuePosition = 40, seatSetupStep = 1)
        val state = OpenNowUiState(
            streamStatus = "queue",
            streamSession = session,
        )

        assertEquals(40, queueDisplayPosition(session))
        assertEquals(40, queueDisplayPosition(state))
        assertEquals("Queue #40", queueLaunchStatusText(state))
    }

    @Test
    fun queueTextNeverRendersThePlaceholderEta() {
        val session = session(queuePosition = 33, seatSetupStep = 1).copy(seatSetupEta = 30_000L)
        val state = OpenNowUiState(
            streamStatus = "queue",
            streamSession = session,
            queuePosition = 33,
        )

        assertEquals("Queue #33", queueLaunchStatusText(state))
    }

    @Test
    fun indonesianQueueTextUsesTheNanaPlayPhrase() {
        val session = session(queuePosition = 33, seatSetupStep = 1)
        val state = OpenNowUiState(
            streamStatus = "queue",
            streamSession = session,
            queuePosition = 33,
            settings = AppSettings(appLanguage = "in"),
        )

        assertEquals("Antrian #33", queueLaunchStatusText(state))
    }

    @Test
    fun highPingWarningOnlyStartsAbove150Ms() {
        assertFalse(shouldWarnHighPing(null))
        assertFalse(shouldWarnHighPing(150L))
        assertTrue(shouldWarnHighPing(151L))
    }

    @Test
    fun terminalQueueStatusesAreClassifiedSeparately() {
        assertTrue(isQueueAbandonedStatus(QUEUE_STATUS_ABANDONED))
        assertFalse(isQueueAbandonedStatus(QUEUE_STATUS_INVALID_SESSION))
        assertTrue(isQueueInvalidSessionStatus(QUEUE_STATUS_INVALID_SESSION))
        assertFalse(isQueueInvalidSessionStatus(1))
        assertEquals(3, QUEUE_INVALID_SESSION_MAX_CONSECUTIVE_POLLS)
    }

    @Test
    fun lowerOnlyQueuePositionClampsMonotonically() {
        // Initial snapshot
        assertEquals(50, lowerOnlyQueuePosition(previous = null, incoming = 50))
        // Position progresses forward (drops)
        assertEquals(40, lowerOnlyQueuePosition(previous = 50, incoming = 40))
        // Backend cluster rebalances / priority user inserts (position jumps backwards) -> clamped to lowest
        assertEquals(40, lowerOnlyQueuePosition(previous = 40, incoming = 45))
        assertEquals(35, lowerOnlyQueuePosition(previous = 40, incoming = 35))
        // Null incoming returns null
        assertNull(lowerOnlyQueuePosition(previous = 35, incoming = null))
    }

    @Test
    fun isPastQueueIdentifiesProgressionBeyondQueue() {
        assertFalse(isPastQueue(null))
        // Still queuing for rig
        assertFalse(isPastQueue(session(queuePosition = 10, seatSetupStep = 1)))
        // In queue mode: positive position keeps queue visible even if seatSetupStep is 3..5
        assertFalse(isPastQueue(session(queuePosition = 2, seatSetupStep = 3)))
        // Beyond queue when queuePosition <= 1 or null during container / rig setup
        assertTrue(isPastQueue(session(queuePosition = 1, seatSetupStep = 3)))
        assertTrue(isPastQueue(session(queuePosition = null, seatSetupStep = 4)))
        assertTrue(isPastQueue(session(queuePosition = 1, seatSetupStep = 5)))
        // Ready status
        assertTrue(isPastQueue(session(queuePosition = null, seatSetupStep = null).copy(status = 2)))
        assertTrue(isPastQueue(session(queuePosition = 1, seatSetupStep = 1).copy(status = 3)))
    }

    @Test
    fun positiveQueuePositionKeepsQueueVisibleIfSetupFieldsDisagree() {
        val session = session(queuePosition = 2, seatSetupStep = 3)
        assertFalse(isPastQueue(session))
        assertEquals(2, queueDisplayPosition(session))

        val state = OpenNowUiState(
            streamStatus = "queue",
            streamSession = session,
            queuePosition = 2,
        )
        assertEquals(2, queueDisplayPosition(state))
        assertEquals("Queue #2", queueLaunchStatusText(state))
    }

    @Test
    fun queueDisplayPositionSuppressedWhenPastQueue() {
        val pastSession = session(queuePosition = 1, seatSetupStep = 3)
        assertNull(queueDisplayPosition(pastSession))

        val state = OpenNowUiState(
            streamStatus = "queue",
            streamSession = pastSession,
            queuePosition = 1,
        )
        assertNull(queueDisplayPosition(state))

        val connectingState = OpenNowUiState(
            streamStatus = "connecting",
            streamSession = session(queuePosition = 2, seatSetupStep = 1),
            queuePosition = 2,
        )
        assertNull(queueDisplayPosition(connectingState))
    }

    private fun session(
        queuePosition: Int?,
        seatSetupStep: Int?,
    ): SessionInfo =
        SessionInfo(
            sessionId = "session",
            status = 1,
            queuePosition = queuePosition,
            seatSetupStep = seatSetupStep,
            serverIp = "server",
            signalingServer = "server:443",
            signalingUrl = "wss://server:443/nvst/",
        )
}
