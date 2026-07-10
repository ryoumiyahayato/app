package app.electronicmuyu.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionUrlPolicyTest {

    @Test
    fun allowsWebSocketUrlsWithoutCredentials() {
        assertTrue(ConnectionUrlPolicy.isAllowedForPlainStorage("ws://192.168.1.2:8443"))
        assertTrue(
            ConnectionUrlPolicy.isAllowedForPlainStorage(
                "wss://relay.example.com/socket?transport=websocket"
            )
        )
    }

    @Test
    fun rejectsUserInfoFragmentsAndInvalidEndpoints() {
        assertFalse(ConnectionUrlPolicy.isAllowedForPlainStorage("wss://user@example.com"))
        assertFalse(ConnectionUrlPolicy.isAllowedForPlainStorage("wss://example.com/#secret"))
        assertFalse(ConnectionUrlPolicy.isAllowedForPlainStorage("https://example.com"))
        assertFalse(ConnectionUrlPolicy.isAllowedForPlainStorage("wss:///missing-host"))
        assertFalse(ConnectionUrlPolicy.isAllowedForPlainStorage("wss://example.com:0"))
        assertFalse(ConnectionUrlPolicy.isAllowedForPlainStorage("wss://example.com:70000"))
    }

    @Test
    fun rejectsSensitiveQueryNamesCaseInsensitively() {
        val sensitiveNames = listOf(
            "token",
            "session",
            "session_token",
            "auth",
            "authorization",
            "api_key",
            "apikey",
            "access_token",
            "client_secret",
            "password"
        )

        sensitiveNames.forEach { name ->
            assertFalse(
                name,
                ConnectionUrlPolicy.isAllowedForPlainStorage(
                    "wss://example.com/socket?${name.uppercase()}=value"
                )
            )
        }
    }

    @Test
    fun rejectsPercentEncodedSensitiveQueryNames() {
        assertFalse(
            ConnectionUrlPolicy.isAllowedForPlainStorage(
                "wss://example.com/socket?to%6ben=value"
            )
        )
    }
}
