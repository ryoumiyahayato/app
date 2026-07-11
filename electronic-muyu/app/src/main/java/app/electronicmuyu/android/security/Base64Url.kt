package app.electronicmuyu.android.security

import java.util.Base64

object Base64Url {
    private val pattern = Regex("^[A-Za-z0-9_-]+$")

    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decode(value: String, expectedBytes: Int? = null): ByteArray? {
        if (value.isEmpty() || !pattern.matches(value) || value.contains('=')) return null
        return try {
            val bytes = Base64.getUrlDecoder().decode(value)
            if (expectedBytes != null && bytes.size != expectedBytes) return null
            if (encode(bytes) != value) return null
            bytes
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
