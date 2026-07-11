package app.electronicmuyu.android.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Base64UrlTest {
    @Test
    fun decoderRequiresCanonicalUnpaddedUrlSafeEncoding() {
        val bytes = ByteArray(16) { it.toByte() }
        val encoded = Base64Url.encode(bytes)
        assertArrayEquals(bytes, Base64Url.decode(encoded, 16))
        assertNull(Base64Url.decode("$encoded=", 16))
        assertNull(Base64Url.decode("+/not-safe"))
        assertNull(Base64Url.decode(encoded, 32))
    }
}
