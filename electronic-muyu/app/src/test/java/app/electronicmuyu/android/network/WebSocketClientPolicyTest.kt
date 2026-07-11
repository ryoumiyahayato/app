package app.electronicmuyu.android.network

import app.electronicmuyu.android.network.WebSocketClient.DisconnectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSocketClientPolicyTest {

    @Test
    fun retryDelayUsesExpectedExponentialBackoffAndCapsAtSixtySeconds() {
        val delays = (0..8).map { WebSocketClient.retryDelayForAttempt(it) }

        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L),
            delays
        )
        assertEquals(1_000L, WebSocketClient.retryDelayForAttempt(-1))
    }

    @Test
    fun terminalServerCloseCodesDoNotReconnect() {
        listOf(4400, 4409, 4414, 1003, 1009).forEach { code ->
            val reason = WebSocketClient.disconnectReasonForCloseCode(code)
            assertEquals(DisconnectReason.SERVER_REJECTED, reason)
            assertFalse(WebSocketClient.shouldReconnect(reason))
        }
    }

    @Test
    fun authenticationAndRevocationAreTerminal() {
        assertEquals(
            DisconnectReason.AUTHENTICATION_FAILED,
            WebSocketClient.disconnectReasonForCloseCode(4401)
        )
        assertEquals(
            DisconnectReason.AUTHENTICATION_FAILED,
            WebSocketClient.disconnectReasonForCloseCode(4410)
        )
        assertEquals(
            DisconnectReason.PAIR_REVOKED,
            WebSocketClient.disconnectReasonForCloseCode(4403)
        )
        assertFalse(WebSocketClient.shouldReconnect(DisconnectReason.AUTHENTICATION_FAILED))
        assertFalse(WebSocketClient.shouldReconnect(DisconnectReason.PAIR_REVOKED))
    }

    @Test
    fun reconnectJitterStaysWithinTwentyFivePercentEnvelope() {
        assertEquals(750L, WebSocketClient.jitteredRetryDelay(0, 0.0))
        assertEquals(1_250L, WebSocketClient.jitteredRetryDelay(0, 1.0))
    }

    @Test
    fun rateLimitRestartAndNetworkFailuresRemainRetryable() {
        val rateLimited = WebSocketClient.disconnectReasonForCloseCode(4408)
        val restarting = WebSocketClient.disconnectReasonForCloseCode(1012)

        assertEquals(DisconnectReason.RATE_LIMITED, rateLimited)
        assertTrue(WebSocketClient.shouldReconnect(rateLimited))
        assertEquals(DisconnectReason.SERVER_CLOSED, restarting)
        assertTrue(WebSocketClient.shouldReconnect(restarting))
        assertTrue(WebSocketClient.shouldReconnect(DisconnectReason.NETWORK_ERROR))
        assertFalse(WebSocketClient.shouldReconnect(DisconnectReason.USER_ACTION))
    }

    @Test
    fun peerStateRequiresExactVersionedPayload() {
        assertEquals(
            true,
            WebSocketClient.peerOnlineFromMessage(
                """{"type":"peer_state","version":1,"peerOnline":true,"timestamp":123}"""
            )
        )
        assertEquals(
            false,
            WebSocketClient.peerOnlineFromMessage(
                """{"type":"peer_state","version":1,"peerOnline":false,"timestamp":123}"""
            )
        )
        assertNull(
            WebSocketClient.peerOnlineFromMessage(
                """{"type":"peer_state","version":1,"peerOnline":true,"timestamp":123,"extra":1}"""
            )
        )
        assertNull(
            WebSocketClient.peerOnlineFromMessage(
                """{"type":"peer_state","version":2,"peerOnline":true,"timestamp":123}"""
            )
        )
        assertNull(WebSocketClient.peerOnlineFromMessage("not-json"))
    }
}
