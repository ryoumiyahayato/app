package app.electronicmuyu.android.network

import app.electronicmuyu.android.pairing.PAIRING_PROTOCOL_VERSION
import app.electronicmuyu.android.pairing.PairingDevice
import app.electronicmuyu.android.pairing.PairingTranscript
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class CreatedInvite(val ownerSessionToken: String, val expiresAt: Long)
data class PairingStatus(
    val status: String,
    val expiresAt: Long,
    val transcript: PairingTranscript?,
    val inviterConfirmed: Boolean,
    val joinerConfirmed: Boolean,
    val joinSessionToken: String? = null
)

class PairingApi(private val relayBaseUrl: String, private val client: OkHttpClient = OkHttpClient()) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun createInvite(
        inviteId: String,
        inviteSecretHash: String,
        deviceId: String,
        deviceName: String,
        publicKey: String
    ): CreatedInvite = request("/v1/invites", "POST", JSONObject().apply {
        put("version", PAIRING_PROTOCOL_VERSION)
        put("inviteId", inviteId)
        put("inviteSecretHash", inviteSecretHash)
        put("inviterDeviceId", deviceId)
        put("inviterDeviceName", deviceName.take(64))
        put("inviterPublicKey", publicKey)
    }) { json ->
        CreatedInvite(json.getString("ownerSessionToken"), json.getLong("expiresAt"))
    }

    suspend fun joinInvite(
        inviteId: String,
        inviteSecret: String,
        deviceId: String,
        deviceName: String,
        publicKey: String
    ): PairingStatus = request("/v1/invites/$inviteId/join", "POST", JSONObject().apply {
        put("version", PAIRING_PROTOCOL_VERSION)
        put("inviteSecret", inviteSecret)
        put("joinerDeviceId", deviceId)
        put("joinerDeviceName", deviceName.take(64))
        put("joinerPublicKey", publicKey)
    }, ::parseStatus)

    suspend fun statusOrConfirm(
        inviteId: String,
        deviceId: String,
        sessionToken: String,
        decision: String,
        accessTokenHash: String? = null
    ): PairingStatus = request("/v1/invites/$inviteId/confirm", "POST", JSONObject().apply {
        put("version", PAIRING_PROTOCOL_VERSION)
        put("deviceId", deviceId)
        put("sessionToken", sessionToken)
        put("decision", decision)
        if (decision == "confirm") put("accessTokenHash", requireNotNull(accessTokenHash))
    }, ::parseStatus)

    suspend fun cancelInvite(inviteId: String, deviceId: String, sessionToken: String) {
        request("/v1/invites/$inviteId/cancel", "POST", JSONObject().apply {
            put("version", PAIRING_PROTOCOL_VERSION)
            put("deviceId", deviceId)
            put("sessionToken", sessionToken)
        }) { Unit }
    }

    suspend fun revokePair(pairId: String, deviceId: String, token: String) {
        request("/v1/pairs/$pairId/devices/$deviceId", "DELETE", JSONObject().apply {
            put("version", PAIRING_PROTOCOL_VERSION)
            put("token", token)
        }) { Unit }
    }

    fun socketUrl(pairId: String): String {
        val uri = URI(relayBaseUrl)
        val scheme = if (uri.scheme.equals("https", true)) "wss" else "ws"
        return URI(scheme, uri.userInfo, uri.host, uri.port, "/v1/socket", "pair=$pairId", null).toString()
    }

    private suspend fun <T> request(
        path: String,
        method: String,
        body: JSONObject,
        decode: (JSONObject) -> T
    ): T = withContext(Dispatchers.IO) {
        val requestBody = body.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(relayBaseUrl.trimEnd('/') + path)
            .method(method, requestBody)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            val contentLength = response.body?.contentLength() ?: 0L
            if (contentLength > MAX_RESPONSE_BYTES) error("pairing service response too large")
            val raw = response.body?.string().orEmpty()
            if (raw.toByteArray().size > MAX_RESPONSE_BYTES) error("pairing service response too large")
            val json = try { JSONObject(raw) } catch (_: Exception) {
                error("pairing service returned an invalid response")
            }
            if (!response.isSuccessful || json.optBoolean("ok") != true) {
                throw PairingApiException(response.code, json.optString("error", "request_failed"))
            }
            decode(json)
        }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 64 * 1024

        fun validateRelayBaseUrl(value: String, allowInsecureLoopback: Boolean): String? = try {
            val uri = URI(value.trim())
            val validScheme = uri.scheme.equals("https", true) ||
                (allowInsecureLoopback && uri.scheme.equals("http", true) &&
                    (uri.host == "127.0.0.1" || uri.host == "localhost" || uri.host == "10.0.2.2"))
            if (!validScheme || uri.host.isNullOrBlank() || uri.host.endsWith(".invalid", true) ||
                uri.rawUserInfo != null || uri.port == 0 || uri.port > 65_535 ||
                uri.rawQuery != null || uri.rawFragment != null || uri.path !in listOf("", "/")
            ) null else value.trim().trimEnd('/')
        } catch (_: Exception) {
            null
        }

        private fun parseStatus(json: JSONObject): PairingStatus {
            val confirmations = json.getJSONObject("confirmations")
            val transcript = json.optJSONObject("transcript")?.let(::parseTranscript)
            return PairingStatus(
                status = json.getString("status"),
                expiresAt = json.getLong("expiresAt"),
                transcript = transcript,
                inviterConfirmed = confirmations.optBoolean("inviter"),
                joinerConfirmed = confirmations.optBoolean("joiner"),
                joinSessionToken = json.optString("joinSessionToken").takeIf(String::isNotBlank)
            )
        }

        private fun parseTranscript(json: JSONObject): PairingTranscript {
            fun device(name: String): PairingDevice = json.getJSONObject(name).let {
                PairingDevice(it.getString("deviceId"), it.getString("deviceName"), it.getString("publicKey"))
            }
            return PairingTranscript(
                inviteId = json.getString("inviteId"),
                pairId = json.getString("pairId"),
                expiresAt = json.getLong("expiresAt"),
                inviter = device("inviter"),
                joiner = device("joiner")
            )
        }
    }
}

class PairingApiException(val status: Int, val errorCode: String) :
    Exception("pairing request failed ($status/$errorCode)")
