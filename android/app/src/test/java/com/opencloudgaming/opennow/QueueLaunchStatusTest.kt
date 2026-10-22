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
