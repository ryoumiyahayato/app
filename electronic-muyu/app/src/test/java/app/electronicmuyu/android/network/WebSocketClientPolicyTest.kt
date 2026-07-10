package app.electronicmuyu.android.network

import app.electronicmuyu.android.network.WebSocketClient.DisconnectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        listOf(4000, 4001, 4002, 4003, 1003, 1009).forEach { code ->
            val reason = WebSocketClient.disconnectReasonForCloseCode(code)
            assertEquals(DisconnectReason.SERVER_REJECTED, reason)
            assertFalse(WebSocketClient.shouldReconnect(reason))
        }

        val rateLimited = WebSocketClient.disconnectReasonForCloseCode(4008)
        assertEquals(DisconnectReason.RATE_LIMITED, rateLimited)
        assertFalse(WebSocketClient.shouldReconnect(rateLimited))
    }

    @Test
    fun restartAndNetworkFailuresRemainRetryable() {
        val restarting = WebSocketClient.disconnectReasonForCloseCode(1012)

        assertEquals(DisconnectReason.SERVER_CLOSED, restarting)
        assertTrue(WebSocketClient.shouldReconnect(restarting))
        assertTrue(WebSocketClient.shouldReconnect(DisconnectReason.NETWORK_ERROR))
        assertFalse(WebSocketClient.shouldReconnect(DisconnectReason.USER_ACTION))
    }
}
