package app.electronicmuyu.android.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingApiPolicyTest {
    @Test
    fun productionRelayRequiresOriginOnlyHttpsUrl() {
        assertEquals(
            "https://relay.example.com",
            PairingApi.validateRelayBaseUrl("https://relay.example.com/", false)
        )
        assertNull(PairingApi.validateRelayBaseUrl("http://relay.example.com", false))
        assertNull(PairingApi.validateRelayBaseUrl("https://user@relay.example.com", false))
        assertNull(PairingApi.validateRelayBaseUrl("https://relay.example.com?token=secret", false))
        assertNull(PairingApi.validateRelayBaseUrl("https://relay.invalid", false))
    }

    @Test
    fun debugAllowsOnlyExplicitLoopbackHttp() {
        assertEquals("http://10.0.2.2:8787", PairingApi.validateRelayBaseUrl("http://10.0.2.2:8787", true))
        assertNull(PairingApi.validateRelayBaseUrl("http://192.168.1.2:8787", true))
    }
}
