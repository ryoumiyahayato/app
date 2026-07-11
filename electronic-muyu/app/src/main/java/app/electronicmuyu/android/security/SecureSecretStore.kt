package app.electronicmuyu.android.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.electronicmuyu.android.pairing.PAIRING_PROTOCOL_VERSION
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

class SecureSecretStore {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return JSONObject().apply {
            put("v", PAIRING_PROTOCOL_VERSION)
            put("iv", Base64Url.encode(cipher.iv))
            put("ciphertext", Base64Url.encode(ciphertext))
        }.toString()
    }

    fun decrypt(blob: String): String {
        val json = JSONObject(blob)
        require(json.keys().asSequence().toSet() == setOf("v", "iv", "ciphertext"))
        require(json.optInt("v") == PAIRING_PROTOCOL_VERSION)
        val iv = requireNotNull(Base64Url.decode(json.getString("iv"), 12))
        val ciphertext = requireNotNull(Base64Url.decode(json.getString("ciphertext")))
        val key = keyStore.getKey(ALIAS, null) as? SecretKey
            ?: error("pairing wrapping key unavailable")
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            updateAAD(AAD)
            String(doFinal(ciphertext), Charsets.UTF_8)
        }
    }

    fun deleteKey() {
        if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "electronic_muyu_pairing_wrap_v1"
        private val AAD = "electronic-muyu-pair-secrets-v1".toByteArray(Charsets.UTF_8)
    }
}
