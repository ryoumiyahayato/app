package app.electronicmuyu.android.security

import app.electronicmuyu.android.pairing.PAIRING_PROTOCOL_VERSION
import app.electronicmuyu.android.pairing.PairingTranscript
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

data class DirectionalKeys(val sendKey: ByteArray, val receiveKey: ByteArray)

data class EncryptedTap(
    val pairId: String,
    val sender: String,
    val counter: Long,
    val iv: String,
    val ciphertext: String
) {
    fun toJson(): String = JSONObject().apply {
        put("type", "encrypted_tap")
        put("version", PAIRING_PROTOCOL_VERSION)
        put("pairId", pairId)
        put("sender", sender)
        put("counter", counter)
        put("iv", iv)
        put("ciphertext", ciphertext)
    }.toString()

    companion object {
        private val keys = setOf("type", "version", "pairId", "sender", "counter", "iv", "ciphertext")

        fun fromJson(raw: String): EncryptedTap? {
            return try {
                if (raw.toByteArray().size > 4_096) return null
                val json = JSONObject(raw)
                if (json.keys().asSequence().toSet() != keys) return null
                if (json.optString("type") != "encrypted_tap" ||
                    json.optInt("version") != PAIRING_PROTOCOL_VERSION
                ) return null
                val pairId = json.optString("pairId")
                val sender = json.optString("sender")
                val counter = json.optLong("counter", 0L)
                val iv = json.optString("iv")
                val ciphertext = json.optString("ciphertext")
                if (Base64Url.decode(pairId, 16) == null || Base64Url.decode(sender, 16) == null) return null
                if (counter <= 0 || Base64Url.decode(iv, 12) == null) return null
                val cipherBytes = Base64Url.decode(ciphertext) ?: return null
                if (cipherBytes.size !in 16..1_024) return null
                EncryptedTap(pairId, sender, counter, iv, ciphertext)
            } catch (_: Exception) {
                null
            }
        }
    }
}

object PairingCrypto {
    private val random = SecureRandom()

    fun randomBase64Url(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let(Base64Url::encode)

    fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), random)
    }.generateKeyPair()

    fun publicKey(value: String): PublicKey {
        val bytes = requireNotNull(Base64Url.decode(value, 91)) { "invalid P-256 public key" }
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }

    fun privateKey(value: String): PrivateKey {
        val bytes = requireNotNull(Base64Url.decode(value)) { "invalid private key" }
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))
    }

    fun sharedSecret(privateKey: PrivateKey, publicKey: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(publicKey, true)
            generateSecret()
        }

    fun sha256Base64Url(value: String): String {
        val bytes = requireNotNull(Base64Url.decode(value))
        return Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, size: Int = 32): ByteArray {
        require(size in 1..(255 * 32))
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val extract = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
        }.doFinal(ikm)
        val output = ByteArray(size)
        var previous = ByteArray(0)
        var offset = 0
        var block = 1
        while (offset < size) {
            previous = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(extract, "HmacSHA256"))
                update(previous)
                update(info)
                update(block.toByte())
                doFinal()
            }
            val copied = minOf(previous.size, size - offset)
            previous.copyInto(output, offset, 0, copied)
            offset += copied
            block++
        }
        extract.fill(0)
        return output
    }

    fun deriveDirectionalKeys(sharedSecret: ByteArray, pairId: String, slot: Int): DirectionalKeys {
        require(slot == 0 || slot == 1)
        val pairBytes = requireNotNull(Base64Url.decode(pairId, 16))
        val root = hkdfSha256(
            sharedSecret,
            pairBytes,
            "electronic-muyu-root-v1".toByteArray(StandardCharsets.UTF_8)
        )
        val slot0 = hkdfSha256(root, ByteArray(0), "electronic-muyu-send-slot-0-v1".toByteArray())
        val slot1 = hkdfSha256(root, ByteArray(0), "electronic-muyu-send-slot-1-v1".toByteArray())
        root.fill(0)
        return if (slot == 0) DirectionalKeys(slot0, slot1) else DirectionalKeys(slot1, slot0)
    }

    fun canonicalTranscript(transcript: PairingTranscript): ByteArray {
        val fields = listOf(
            PAIRING_PROTOCOL_VERSION.toString().toByteArray(),
            requireNotNull(Base64Url.decode(transcript.inviteId, 16)),
            requireNotNull(Base64Url.decode(transcript.inviter.publicKey, 91)),
            requireNotNull(Base64Url.decode(transcript.joiner.publicKey, 91)),
            requireNotNull(Base64Url.decode(transcript.pairId, 16))
        )
        return fields.fold(ByteArray(0)) { result, field ->
            result + ByteBuffer.allocate(4).putInt(field.size).array() + field
        }
    }

    fun computeSas(transcript: PairingTranscript): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalTranscript(transcript))
        val firstTwentyBits = ((digest[0].toInt() and 0xff) shl 12) or
            ((digest[1].toInt() and 0xff) shl 4) or
            ((digest[2].toInt() and 0xff) ushr 4)
        return (firstTwentyBits % 1_000_000).toString().padStart(6, '0')
    }

    fun aad(pairId: String, sender: String, counter: Long): ByteArray {
        require(counter > 0)
        val fields = listOf(
            PAIRING_PROTOCOL_VERSION.toString().toByteArray(),
            requireNotNull(Base64Url.decode(pairId, 16)),
            requireNotNull(Base64Url.decode(sender, 16)),
            ByteBuffer.allocate(8).putLong(counter).array()
        )
        return fields.fold(ByteArray(0)) { result, field ->
            result + ByteBuffer.allocate(4).putInt(field.size).array() + field
        }
    }

    fun encryptTap(key: ByteArray, pairId: String, sender: String, counter: Long, timestamp: Long): EncryptedTap {
        require(key.size == 32 && timestamp > 0)
        val aad = aad(pairId, sender, counter)
        val iv = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(aad).copyOf(12)
        }
        val plaintext = JSONObject().apply {
            put("type", "tap")
            put("timestamp", timestamp)
        }.toString().toByteArray(StandardCharsets.UTF_8)
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            updateAAD(aad)
            doFinal(plaintext)
        }
        return EncryptedTap(pairId, sender, counter, Base64Url.encode(iv), Base64Url.encode(ciphertext))
    }

    fun decryptTap(key: ByteArray, message: EncryptedTap): Long {
        require(key.size == 32)
        val aad = aad(message.pairId, message.sender, message.counter)
        val expectedIv = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(aad).copyOf(12)
        }
        val iv = requireNotNull(Base64Url.decode(message.iv, 12))
        require(MessageDigest.isEqual(iv, expectedIv)) { "invalid IV" }
        val plaintext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            updateAAD(aad)
            doFinal(requireNotNull(Base64Url.decode(message.ciphertext)))
        }
        val json = JSONObject(String(plaintext, StandardCharsets.UTF_8))
        require(json.keys().asSequence().toSet() == setOf("type", "timestamp"))
        require(json.getString("type") == "tap")
        return json.getLong("timestamp").also { require(it > 0) }
    }
}

class ReplayWindow(initialCounter: Long = 0L) {
    var lastAcceptedCounter: Long = initialCounter
        private set

    @Synchronized
    fun accept(counter: Long): Boolean {
        if (counter <= lastAcceptedCounter) return false
        lastAcceptedCounter = counter
        return true
    }
}
