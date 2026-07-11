package app.electronicmuyu.android.pairing

import app.electronicmuyu.android.security.Base64Url
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteQrPayloadTest {
    private fun value(size: Int, seed: Int) = Base64Url.encode(ByteArray(size) { (it + seed).toByte() })

    @Test
    fun roundTripAcceptsValidNonExpiredOneTimeInvite() {
        val now = 1_000_000L
        val payload = InviteQrPayload(value(16, 1), value(32, 2), value(91, 3), now + 120_000)
        assertEquals(payload, InviteQrPayload.decode(payload.encode(), now))
    }

    @Test
    fun rejectsExpiredUnknownOversizedAndMalformedPayloads() {
        val now = 1_000_000L
        val expired = InviteQrPayload(value(16, 1), value(32, 2), value(91, 3), now)
        assertNull(InviteQrPayload.decode(expired.encode(), now))
        val unknown = JSONObject(expired.copy(expiresAt = now + 1).encode()).put("url", "https://evil.example")
        assertNull(InviteQrPayload.decode(unknown.toString(), now))
        assertNull(InviteQrPayload.decode("x".repeat(2_049), now))
        assertNull(InviteQrPayload.decode("https://example.com", now))
    }
}
