package com.opencloudgaming.opennow

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GfnMediaConnectionInfoTest {

    @Test
    fun prefersBundleUsage17OverVideoUsage2() {
        val connections = listOf(
            buildJsonObject {
                put("usage", 2)
                put("ip", "192.168.1.100")
                put("port", 47998)
            },
            buildJsonObject {
                put("usage", 17)
                put("ip", "192.168.1.101")
                put("port", 47999)
            },
        )

        val resolved = GfnSessionRepository.resolveMediaConnectionInfo(connections, "10.0.0.1")
        assertNotNull(resolved)
        assertEquals("192.168.1.101", resolved?.ip)
        assertEquals(47999, resolved?.port)
    }

    @Test
    fun fallsBackToVideoUsage2WhenUsage17Missing() {
        val connections = listOf(
            buildJsonObject {
                put("usage", 2)
                put("ip", "192.168.1.100")
                put("port", 47998)
            },
            buildJsonObject {
                put("usage", 14)
                put("ip", "10.0.0.1")
                put("port", 443)
            },
        )

        val resolved = GfnSessionRepository.resolveMediaConnectionInfo(connections, "10.0.0.1")
        assertNotNull(resolved)
        assertEquals("192.168.1.100", resolved?.ip)
        assertEquals(47998, resolved?.port)
    }

    @Test
    fun fallsBackToUsage14WithHighestPortWhenBundleAndVideoMissing() {
        val connections = listOf(
            buildJsonObject {
                put("usage", 14)
                put("ip", "10.0.0.1")
                put("port", 443)
            },
            buildJsonObject {
                put("usage", 14)
                put("ip", "10.0.0.2")
                put("port", 8443)
            },
        )

        val resolved = GfnSessionRepository.resolveMediaConnectionInfo(connections, "10.0.0.1")
        assertNotNull(resolved)
        assertEquals("10.0.0.2", resolved?.ip)
        assertEquals(8443, resolved?.port)
    }

    @Test
    fun resolvesHostAndPortFromResourcePath() {
        val connections = listOf(
            buildJsonObject {
                put("usage", 17)
                put("resourcePath", "rtsps://stream.ams.geforcenow.com:49000/nvst")
            },
        )

        val resolved = GfnSessionRepository.resolveMediaConnectionInfo(connections, "10.0.0.1")
        assertNotNull(resolved)
        assertEquals("stream.ams.geforcenow.com", resolved?.ip)
        assertEquals(49000, resolved?.port)
    }

    @Test
    fun returnsNullWhenNoMatchingUsagesOrEmpty() {
        assertNull(GfnSessionRepository.resolveMediaConnectionInfo(emptyList(), "10.0.0.1"))

        val unmatched = listOf(
            buildJsonObject {
                put("usage", 99)
                put("ip", "10.0.0.1")
                put("port", 1234)
            },
        )
        assertNull(GfnSessionRepository.resolveMediaConnectionInfo(unmatched, "10.0.0.1"))
    }
}
