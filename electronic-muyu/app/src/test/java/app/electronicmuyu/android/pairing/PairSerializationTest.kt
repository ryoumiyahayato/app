package app.electronicmuyu.android.pairing

import app.electronicmuyu.android.security.Base64Url
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairSerializationTest {
    private fun value(size: Int, seed: Int) = Base64Url.encode(ByteArray(size) { (it + seed).toByte() })

    @Test
    fun secretSerializationRoundTripsWithoutChangingFields() {
        val secrets = PairSecrets(value(67, 1), value(32, 2), value(32, 3), value(32, 4))
        assertEquals(secrets, PairSecrets.fromJson(secrets.toJson()))
        assertNull(PairSecrets.fromJson(secrets.toJson().dropLast(1)))
    }

    @Test
    fun metadataSerializationRoundTrips() {
        val metadata = PairMetadata(
            pairId = value(16, 1),
            deviceId = value(16, 2),
            peerDeviceId = value(16, 3),
            peerDeviceName = "对方设备",
            slot = 1,
            createdAt = 123
        )
        assertEquals(metadata, PairMetadata.fromJson(metadata.toJson()))
    }
}
