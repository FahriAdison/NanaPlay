package com.opencloudgaming.opennow

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection

class RemoteIceGuardTest {

    @Test
    fun a_midPresent_indexAbsent_addedLineMinusOne() {
        val payload = buildJsonObject {
            put("candidate", "candidate:1 1 UDP 1 1.1.1.1 1 typ host")
            put("sdpMid", "0")
        }
        val parsed = parseRemoteIceCandidate(payload)
        assertTrue(parsed != null)
        assertEquals(-1, parsed!!.sdpMLineIndex)
        assertTrue(shouldAddRemoteIceCandidate(parsed, true, null))
    }

    @Test
    fun b_midBlank_indexZeroExplicit_addedLineZero() {
        val payload = buildJsonObject {
            put("candidate", "candidate:1 1 UDP 1 1.1.1.1 1 typ host")
            put("sdpMLineIndex", 0)
        }
        val parsed = parseRemoteIceCandidate(payload)!!
        assertEquals(0, parsed.sdpMLineIndex)
        assertTrue(shouldAddRemoteIceCandidate(parsed, true, null))
    }

    @Test
    fun c_midBlank_indexAbsent_skipped() {
        val payload = buildJsonObject {
            put("candidate", "candidate:1 1 UDP 1 1.1.1.1 1 typ host")
        }
        val parsed = parseRemoteIceCandidate(payload)!!
        assertEquals(-1, parsed.sdpMLineIndex)
        assertFalse(shouldAddRemoteIceCandidate(parsed, true, null))
    }

    @Test
    fun d_blankSdp_skipped() {
        for (sdp in listOf("", "   ")) {
            val payload = buildJsonObject {
                put("candidate", sdp)
                put("sdpMid", "0")
            }
            // candidate field non-null but blank -> parsed non-null, guard must reject
            val parsed = requireNotNull(parseRemoteIceCandidate(payload)) { "blank sdp [$sdp] must still parse" }
            assertFalse("blank sdp [$sdp] must be skipped", shouldAddRemoteIceCandidate(parsed, true, null))
        }
        // end-of-candidates style frame
        val eoc = buildJsonObject {
            put("candidate", "")
            put("sdpMid", "0")
        }
        val parsedEoc = requireNotNull(parseRemoteIceCandidate(eoc)) { "end-of-candidates frame must still parse" }
        assertFalse("end-of-candidates frame must be skipped", shouldAddRemoteIceCandidate(parsedEoc, true, null))
    }

    @Test
    fun e_lastIceStateNull_added() {
        val c = IceCandidate("0", -1, "candidate:1 1 UDP 1 1.1.1.1 1 typ host")
        assertTrue(shouldAddRemoteIceCandidate(c, true, null))
    }

    @Test
    fun f_lastIceStateClosed_skipped() {
        val c = IceCandidate("0", -1, "candidate:1 1 UDP 1 1.1.1.1 1 typ host")
        assertFalse(shouldAddRemoteIceCandidate(c, true, PeerConnection.IceConnectionState.CLOSED))
    }

    @Test
    fun g_indexAsStringZero_parsedZero_added() {
        val payload = buildJsonObject {
            put("candidate", "candidate:1 1 UDP 1 1.1.1.1 1 typ host")
            put("sdpMLineIndex", "0")
        }
        val parsed = parseRemoteIceCandidate(payload)!!
        assertEquals(0, parsed.sdpMLineIndex)
        assertTrue(shouldAddRemoteIceCandidate(parsed, true, null))
    }

    @Test
    fun h_nullPeerConnection_skippedNoNpe() {
        val c = IceCandidate("0", -1, "candidate:1 1 UDP 1 1.1.1.1 1 typ host")
        assertFalse(shouldAddRemoteIceCandidate(c, false, null))
    }

    @Test
    fun nullCandidateField_returnsNull() {
        val payload = buildJsonObject { }
        assertNull(parseRemoteIceCandidate(payload))
    }
}
