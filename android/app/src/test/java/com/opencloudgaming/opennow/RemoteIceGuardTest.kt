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

    // Defensive mitigation: Uji kandidat srflx dengan raddr "invalid" (pemicu SIGABRT di India NP-BOM-01)
    @Test
    fun raddr_literalInvalid_skippedWithInvalidRaddr() {
        val sdp = "candidate:1467150205 1 udp 1677721855 1.1.1.1 5004 typ srflx raddr invalid rport 0"
        val c = IceCandidate("0", 0, sdp)
        assertEquals(CandidateValidationResult.INVALID_RADDR, evaluateRemoteIceCandidate(c, true, null))
        assertFalse(shouldAddRemoteIceCandidate(c, true, null))
    }

    // Defensive mitigation: Uji kandidat srflx dengan raddr IPv4 valid
    @Test
    fun raddr_validIpv4_added() {
        val sdp = "candidate:1467150205 1 udp 1677721855 1.1.1.1 5004 typ srflx raddr 10.0.0.1 rport 5000"
        val c = IceCandidate("0", 0, sdp)
        assertEquals(CandidateValidationResult.VALID, evaluateRemoteIceCandidate(c, true, null))
        assertTrue(shouldAddRemoteIceCandidate(c, true, null))
    }

    // Defensive mitigation: Uji kandidat srflx dengan raddr 0.0.0.0 (placeholder IP valid, wajib diterima)
    @Test
    fun raddr_placeholderAnyLocal_added() {
        val sdp = "candidate:1467150205 1 udp 1677721855 1.1.1.1 5004 typ srflx raddr 0.0.0.0 rport 0"
        val c = IceCandidate("0", 0, sdp)
        assertEquals(CandidateValidationResult.VALID, evaluateRemoteIceCandidate(c, true, null))
        assertTrue(shouldAddRemoteIceCandidate(c, true, null))
    }

    // Defensive mitigation: Uji kandidat srflx dengan raddr IPv6 valid
    @Test
    fun raddr_validIpv6_added() {
        val sdp = "candidate:1467150205 1 udp 1677721855 1.1.1.1 5004 typ srflx raddr 2001:db8::2 rport 5000"
        val c = IceCandidate("0", 0, sdp)
        assertEquals(CandidateValidationResult.VALID, evaluateRemoteIceCandidate(c, true, null))
        assertTrue(shouldAddRemoteIceCandidate(c, true, null))
    }

    // Defensive mitigation: Uji kandidat dengan connection-address invalid
    @Test
    fun connectionAddress_invalid_skippedWithInvalidAddress() {
        val sdp = "candidate:1467150205 1 udp 1677721855 invalid 5004 typ host"
        val c = IceCandidate("0", 0, sdp)
        assertEquals(CandidateValidationResult.INVALID_ADDRESS, evaluateRemoteIceCandidate(c, true, null))
        assertFalse(shouldAddRemoteIceCandidate(c, true, null))
    }

    // Defensive mitigation: Uji kandidat host dengan connection-address IPv6 valid
    @Test
    fun connectionAddress_validIpv6_added() {
        val sdp = "candidate:1467150205 1 udp 1677721855 2001:db8::1 5004 typ host"
        val c = IceCandidate("0", 0, sdp)
        assertEquals(CandidateValidationResult.VALID, evaluateRemoteIceCandidate(c, true, null))
        assertTrue(shouldAddRemoteIceCandidate(c, true, null))
    }

    // Defensive mitigation: Uji kandidat host tanpa raddr
    @Test
    fun candidateWithoutRaddr_hostCandidate_added() {
        val sdp = "candidate:1 1 UDP 1 1.1.1.1 1 typ host"
        val c = IceCandidate("0", 0, sdp)
        assertEquals(CandidateValidationResult.VALID, evaluateRemoteIceCandidate(c, true, null))
        assertTrue(shouldAddRemoteIceCandidate(c, true, null))
    }

    // Defensive mitigation: Uji raddr "invalid" disanitasi menjadi "0.0.0.0" dan lolos guard sebagai VALID
    @Test
    fun sanitizeRaddr_invalidRaddr_sanitizedToZeroIpAndPassesGuard() {
        val rawSdp = "candidate:1467150205 1 udp 1677721855 1.1.1.1 5004 typ srflx raddr invalid rport 0"
        val result = SdpTools.sanitizeCandidateRaddr(rawSdp)
        assertTrue(result.isSanitized)
        assertEquals("candidate:1467150205 1 udp 1677721855 1.1.1.1 5004 typ srflx raddr 0.0.0.0 rport 0", result.sanitizedSdp)
        val candidate = IceCandidate("0", 0, result.sanitizedSdp)
        assertEquals(CandidateValidationResult.VALID, evaluateRemoteIceCandidate(candidate, true, null))
        assertTrue(shouldAddRemoteIceCandidate(candidate, true, null))
    }

    // Defensive mitigation: Uji raddr IPv4 valid tidak diubah (sanitized = false)
    @Test
    fun sanitizeRaddr_validIpv4_unchanged() {
        val rawSdp = "candidate:1467150205 1 udp 1677721855 1.1.1.1 5004 typ srflx raddr 10.0.0.1 rport 5000"
        val result = SdpTools.sanitizeCandidateRaddr(rawSdp)
        assertFalse(result.isSanitized)
        assertEquals(rawSdp, result.sanitizedSdp)
        val candidate = IceCandidate("0", 0, result.sanitizedSdp)
        assertEquals(CandidateValidationResult.VALID, evaluateRemoteIceCandidate(candidate, true, null))
        assertTrue(shouldAddRemoteIceCandidate(candidate, true, null))
    }

    // Defensive mitigation: Uji raddr IPv6 valid tidak diubah (sanitized = false)
    @Test
    fun sanitizeRaddr_validIpv6_unchanged() {
        val rawSdp = "candidate:1467150205 1 udp 1677721855 1.1.1.1 5004 typ srflx raddr 2001:db8::2 rport 5000"
        val result = SdpTools.sanitizeCandidateRaddr(rawSdp)
        assertFalse(result.isSanitized)
        assertEquals(rawSdp, result.sanitizedSdp)
        val candidate = IceCandidate("0", 0, result.sanitizedSdp)
        assertEquals(CandidateValidationResult.VALID, evaluateRemoteIceCandidate(candidate, true, null))
        assertTrue(shouldAddRemoteIceCandidate(candidate, true, null))
    }

    // Defensive mitigation: Uji kandidat host tanpa raddr tidak diubah (sanitized = false)
    @Test
    fun sanitizeRaddr_hostCandidateWithoutRaddr_unchanged() {
        val rawSdp = "candidate:1 1 UDP 1 1.1.1.1 1 typ host"
        val result = SdpTools.sanitizeCandidateRaddr(rawSdp)
        assertFalse(result.isSanitized)
        assertEquals(rawSdp, result.sanitizedSdp)
        val candidate = IceCandidate("0", 0, result.sanitizedSdp)
        assertEquals(CandidateValidationResult.VALID, evaluateRemoteIceCandidate(candidate, true, null))
        assertTrue(shouldAddRemoteIceCandidate(candidate, true, null))
    }

    // Defensive mitigation: Uji alamat utama invalid tetap INVALID_ADDRESS meski raddr disanitasi
    @Test
    fun sanitizeRaddr_invalidPrimaryAddress_remainsInvalidAddress() {
        val rawSdp = "candidate:1467150205 1 udp 1677721855 invalid 5004 typ srflx raddr invalid rport 0"
        val result = SdpTools.sanitizeCandidateRaddr(rawSdp)
        assertTrue(result.isSanitized)
        assertEquals("candidate:1467150205 1 udp 1677721855 invalid 5004 typ srflx raddr 0.0.0.0 rport 0", result.sanitizedSdp)
        val candidate = IceCandidate("0", 0, result.sanitizedSdp)
        assertEquals(CandidateValidationResult.INVALID_ADDRESS, evaluateRemoteIceCandidate(candidate, true, null))
        assertFalse(shouldAddRemoteIceCandidate(candidate, true, null))
    }

    // Defensive mitigation: Uji penanganan raddr IPv6 berbracket dikupas oleh isValidIpAddress untuk validasi,
    // dan nilai raddr berbracket dipertahankan apa adanya pada string SDP tanpa modifikasi (sanitized = false)
    @Test
    fun sanitizeRaddr_bracketedIpv6_preservedUnchanged() {
        val rawSdp = "candidate:1467150205 1 udp 1677721855 1.1.1.1 5004 typ srflx raddr [2001:db8::1] rport 5000"
        val result = SdpTools.sanitizeCandidateRaddr(rawSdp)
        assertFalse(result.isSanitized)
        assertEquals(rawSdp, result.sanitizedSdp)
        val candidate = IceCandidate("0", 0, result.sanitizedSdp)
        assertEquals(CandidateValidationResult.VALID, evaluateRemoteIceCandidate(candidate, true, null))
        assertTrue(shouldAddRemoteIceCandidate(candidate, true, null))
    }
}
