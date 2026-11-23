package com.opencloudgaming.opennow

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamSignalingFailureTest {
    @Test
    fun staleSignalingEndpointRequestsSessionRecovery() {
        assertEquals(
            SignalingFailureDisposition.RecoverSession,
            signalingFailureDisposition("Expected HTTP 101 response but was '404 Not Found' http=404"),
        )
    }

    @Test
    fun goneSessionAndNormalServerCloseRemainTerminal() {
        assertEquals(
            SignalingFailureDisposition.SessionEnded,
            signalingFailureDisposition("http=410 Gone"),
        )
        assertEquals(
            SignalingFailureDisposition.SessionEnded,
            signalingFailureDisposition("code=1000", normalClosureMeansSessionEnded = true),
        )
    }

    @Test
    fun transientSignalingFailureRetriesTransport() {
        assertEquals(
            SignalingFailureDisposition.RetryTransport,
            signalingFailureDisposition("socket timeout"),
        )
    }

    @Test
    fun peerRemovedRequestsSessionRecovery() {
        assertEquals(
            SignalingFailureDisposition.RecoverSession,
            signalingFailureDisposition("Signaling peerRemoved"),
        )
    }

    @Test
    fun signalingHeartbeatReplyGeneratesReplyForHeartbeatMessage() {
        val hbMessage = buildJsonObject {
            put("hb", JsonPrimitive(1))
        }
        val nonHbMessage = buildJsonObject {
            put("type", JsonPrimitive("offer"))
        }
        assertEquals("""{"hb":1}""", signalingHeartbeatReply(hbMessage))
        assertNull(signalingHeartbeatReply(nonHbMessage))
    }

    @Test
    fun buildGfnSignInUrlHandlesFreshAndReconnectModes() {
        val fresh = buildGfnSignInUrl(
            signalingUrl = "wss://stream.example.test/nvst/",
            signalingServer = "stream.example.test:443",
            peerName = "peer-1",
            sessionId = "sess-1",
            reconnect = false,
        )
        assertEquals("wss://stream.example.test/nvst/sign_in?peer_id=peer-1&version=3.0&peer_role=1&pairing_id=sess-1", fresh)

        val reconnected = buildGfnSignInUrl(
            signalingUrl = "wss://stream.example.test/nvst/",
            signalingServer = "stream.example.test:443",
            peerName = "peer-1",
            sessionId = "sess-1",
            reconnect = true,
        )
        assertEquals("wss://stream.example.test/nvst/sign_in?peer_id=peer-1&version=3.0&peer_role=1&pairing_id=sess-1&reconnect=1", reconnected)
    }
}
