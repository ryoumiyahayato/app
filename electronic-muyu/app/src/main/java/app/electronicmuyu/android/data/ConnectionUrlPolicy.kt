package app.electronicmuyu.android.data

import app.electronicmuyu.android.BuildConfig
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Rules for URLs that may be persisted in unencrypted Preferences DataStore. */
object ConnectionUrlPolicy {
    private val sensitiveQueryKeys = setOf(
        "token",
        "access_token",
        "id_token",
        "session",
        "session_token",
        "auth",
        "authorization",
        "api_key",
        "apikey",
        "client_secret",
        "credential",
        "jwt",
        "password",
        "passwd",
        "private_key",
        "secret"
    )

    fun isAllowedForPlainStorage(url: String): Boolean {
        return try {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase()
            val schemeAllowed = scheme == "wss" || (BuildConfig.DEBUG && scheme == "ws")
            schemeAllowed &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo.isNullOrEmpty() &&
                uri.rawFragment == null &&
                uri.port != 0 &&
                uri.port <= 65_535 &&
                queryNames(uri.rawQuery).none { it.lowercase() in sensitiveQueryKeys }
        } catch (_: Exception) {
            false
        }
    }

    private fun queryNames(rawQuery: String?): Sequence<String> {
        if (rawQuery.isNullOrEmpty()) return emptySequence()
        return rawQuery
            .splitToSequence('&')
            .map { parameter -> parameter.substringBefore('=') }
            .map { rawName ->
                URLDecoder.decode(rawName, StandardCharsets.UTF_8.name())
            }
    }
}
