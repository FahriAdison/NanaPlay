package com.opencloudgaming.opennow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionAssignmentTest {
    @Test
    fun standardCloudMatchSessionControlBaseUrlAcceptsValidHosts() {
        assertEquals(
            "https://np-dal-02.cloudmatchbeta.nvidiagrid.net",
            standardCloudMatchSessionControlBaseUrl("np-dal-02.cloudmatchbeta.nvidiagrid.net", 443),
        )
        assertEquals(
            "https://np-ams-06.cloudmatch.nvidiagrid.net",
            standardCloudMatchSessionControlBaseUrl("np-ams-06.cloudmatch.nvidiagrid.net", 443),
        )
        assertEquals(
            "https://np-lax-03.cloudmatchbeta.nvidiagrid.net",
            standardCloudMatchSessionControlBaseUrl(" np-lax-03.cloudmatchbeta.nvidiagrid.net. ", 443),
        )
    }

    @Test
    fun standardCloudMatchSessionControlBaseUrlRejectsInvalidHostsAndPorts() {
        assertNull(standardCloudMatchSessionControlBaseUrl("np-dal-02.cloudmatchbeta.nvidiagrid.net", 8443))
        assertNull(standardCloudMatchSessionControlBaseUrl("np-dal-02.cloudmatchbeta.nvidiagrid.net", 80))
        assertNull(standardCloudMatchSessionControlBaseUrl("evil.nvidiagrid.net", 443))
        assertNull(standardCloudMatchSessionControlBaseUrl("cloudmatchbeta.nvidiagrid.net", 443))
        assertNull(standardCloudMatchSessionControlBaseUrl("example.com", 443))
        assertNull(standardCloudMatchSessionControlBaseUrl(null, 443))
        assertNull(standardCloudMatchSessionControlBaseUrl("", 443))
    }

    @Test
    fun sessionControlPollBaseUrlResolvesOnlyWhenLaunchHostIsNvidia() {
        val validSession = SessionInfo(
            sessionId = "s-1",
            status = 1,
            streamingBaseUrl = "https://cloudmatchbeta.nvidiagrid.net",
            sessionControlBaseUrl = "https://np-dal-02.cloudmatchbeta.nvidiagrid.net",
            serverIp = "1.2.3.4",
            signalingServer = "1.2.3.4:443",
            signalingUrl = "wss://1.2.3.4:443/nvst/",
        )
        assertEquals(
            "https://np-dal-02.cloudmatchbeta.nvidiagrid.net",
            validSession.sessionControlPollBaseUrl(),
        )

        val allianceSession = validSession.copy(
            streamingBaseUrl = "https://gfn.starhub.com",
        )
        assertNull(allianceSession.sessionControlPollBaseUrl())

        val missingControlSession = validSession.copy(
            sessionControlBaseUrl = null,
        )
        assertNull(missingControlSession.sessionControlPollBaseUrl())

        val nonStandardControlSession = validSession.copy(
            sessionControlBaseUrl = "https://other.domain.com",
        )
        assertNull(nonStandardControlSession.sessionControlPollBaseUrl())
    }
}
