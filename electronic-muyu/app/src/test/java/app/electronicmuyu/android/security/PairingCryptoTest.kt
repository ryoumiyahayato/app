package app.electronicmuyu.android.security

import app.electronicmuyu.android.pairing.PairingDevice
import app.electronicmuyu.android.pairing.PairingTranscript
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCryptoTest {
    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun ecdhProducesTheSameSharedSecretOnBothDevices() {
        val first = PairingCrypto.generateKeyPair()
        val second = PairingCrypto.generateKeyPair()
        assertArrayEquals(
            PairingCrypto.sharedSecret(first.private, second.public),
            PairingCrypto.sharedSecret(second.private, first.public)
        )
    }

    @Test
    fun hkdfMatchesRfc5869Sha256CaseOne() {
        val output = PairingCrypto.hkdfSha256(
            ByteArray(22) { 0x0b },
            hex("000102030405060708090a0b0c"),
            hex("f0f1f2f3f4f5f6f7f8f9"),
            42
        )
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865"),
            output
        )
    }

    @Test
    fun transcriptAndSasMatchWorkerVector() {
        val transcript = PairingTranscript(
            inviteId = "FSY3SFlqe4ydrr_Q4fIDFA",
            pairId = "GCk6S1xtfo-gscLT5PUGFw",
            expiresAt = Long.MAX_VALUE,
            inviter = PairingDevice(
                "FSY3SFlqe4ydrr_Q4fIDFA", "A",
                "Fic4SVprfI2er8DR4vMEFSY3SFlqe4ydrr_Q4fIDFCU2R1hpeoucrb7P4PECEyQ1RldoeYqbrL3O3_ABEiM0RVZneImaq7zN3u8AESIzRFVmd4iZqrvM3e7_EA"
            ),
            joiner = PairingDevice(
                "GCk6S1xtfo-gscLT5PUGFw", "B",
                "Fyg5SltsfY6fsMHS4_QFFic4SVprfI2er8DR4vMEFSY3SFlqe4ydrr_Q4fIDFCU2R1hpeoucrb7P4PECEyQ1RldoeYqbrL3O3_ABEiM0RVZneImaq7zN3u8AEQ"
            )
        )
        assertEquals(235, PairingCrypto.canonicalTranscript(transcript).size)
        assertEquals("359919", PairingCrypto.computeSas(transcript))
    }

    @Test
    fun aesGcmRoundTripsAndRejectsModifiedAad() {
        val key = ByteArray(32) { (it * 3).toByte() }
        val pairId = Base64Url.encode(ByteArray(16) { it.toByte() })
        val sender = Base64Url.encode(ByteArray(16) { (it + 20).toByte() })
        val encrypted = PairingCrypto.encryptTap(key, pairId, sender, 7, 123456789L)
        assertEquals(123456789L, PairingCrypto.decryptTap(key, encrypted))
        var failed = false
        try {
            PairingCrypto.decryptTap(key, encrypted.copy(counter = 8))
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun replayWindowAcceptsOnlyStrictlyIncreasingCounters() {
        val window = ReplayWindow()
        assertTrue(window.accept(1))
        assertFalse(window.accept(1))
        assertFalse(window.accept(0))
        assertTrue(window.accept(3))
        assertFalse(window.accept(2))
    }
}
