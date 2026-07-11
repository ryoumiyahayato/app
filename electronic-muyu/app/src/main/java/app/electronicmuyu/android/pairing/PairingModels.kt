package app.electronicmuyu.android.pairing

import app.electronicmuyu.android.security.Base64Url
import org.json.JSONObject

const val PAIRING_PROTOCOL_VERSION = 1
const val INVITE_TTL_MS = 120_000L
private const val MAX_QR_JSON_BYTES = 2_048

data class InviteQrPayload(
    val inviteId: String,
    val inviteSecret: String,
    val inviterPublicKey: String,
    val expiresAt: Long
) {
    fun encode(): String = JSONObject().apply {
        put("v", PAIRING_PROTOCOL_VERSION)
        put("type", "electronic-muyu-pair")
        put("inviteId", inviteId)
        put("inviteSecret", inviteSecret)
        put("inviterPublicKey", inviterPublicKey)
        put("expiresAt", expiresAt)
    }.toString()

    companion object {
        private val keys = setOf(
            "v", "type", "inviteId", "inviteSecret", "inviterPublicKey", "expiresAt"
        )

        fun decode(raw: String, nowMillis: Long = System.currentTimeMillis()): InviteQrPayload? {
            if (raw.toByteArray(Charsets.UTF_8).size > MAX_QR_JSON_BYTES) return null
            return try {
                val json = JSONObject(raw)
                if (json.keys().asSequence().toSet() != keys) return null
                if (json.optInt("v", -1) != PAIRING_PROTOCOL_VERSION) return null
                if (json.optString("type") != "electronic-muyu-pair") return null
                val inviteId = json.optString("inviteId")
                val inviteSecret = json.optString("inviteSecret")
                val inviterPublicKey = json.optString("inviterPublicKey")
                val expiresAt = json.optLong("expiresAt", -1L)
                if (Base64Url.decode(inviteId, 16) == null) return null
                if (Base64Url.decode(inviteSecret, 32) == null) return null
                if (Base64Url.decode(inviterPublicKey, 91) == null) return null
                if (expiresAt <= nowMillis || expiresAt - nowMillis > INVITE_TTL_MS) return null
                InviteQrPayload(inviteId, inviteSecret, inviterPublicKey, expiresAt)
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class PairingDevice(
    val deviceId: String,
    val deviceName: String,
    val publicKey: String
)

data class PairingTranscript(
    val inviteId: String,
    val pairId: String,
    val expiresAt: Long,
    val inviter: PairingDevice,
    val joiner: PairingDevice
)

data class PairMetadata(
    val version: Int = PAIRING_PROTOCOL_VERSION,
    val pairId: String,
    val deviceId: String,
    val peerDeviceId: String,
    val peerDeviceName: String,
    val slot: Int,
    val createdAt: Long
) {
    fun toJson(): String = JSONObject().apply {
        put("version", version)
        put("pairId", pairId)
        put("deviceId", deviceId)
        put("peerDeviceId", peerDeviceId)
        put("peerDeviceName", peerDeviceName)
        put("slot", slot)
        put("createdAt", createdAt)
    }.toString()

    companion object {
        fun fromJson(raw: String): PairMetadata? {
            return try {
            val value = JSONObject(raw)
            if (value.keys().asSequence().toSet() != setOf(
                    "version", "pairId", "deviceId", "peerDeviceId",
                    "peerDeviceName", "slot", "createdAt"
                )
            ) return null
            if (value.optInt("version") != PAIRING_PROTOCOL_VERSION) return null
            val pairId = value.optString("pairId")
            val deviceId = value.optString("deviceId")
            val peerDeviceId = value.optString("peerDeviceId")
            if (Base64Url.decode(pairId, 16) == null ||
                Base64Url.decode(deviceId, 16) == null ||
                Base64Url.decode(peerDeviceId, 16) == null
            ) return null
            PairMetadata(
                pairId = pairId,
                deviceId = deviceId,
                peerDeviceId = peerDeviceId,
                peerDeviceName = value.optString("peerDeviceName").take(64),
                slot = value.optInt("slot").takeIf { it == 0 || it == 1 } ?: return null,
                createdAt = value.optLong("createdAt").takeIf { it > 0 } ?: return null
            )
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class PairSecrets(
    val privateKeyPkcs8: String,
    val accessToken: String,
    val sendKey: String,
    val receiveKey: String
) {
    fun toJson(): String = JSONObject().apply {
        put("privateKeyPkcs8", privateKeyPkcs8)
        put("accessToken", accessToken)
        put("sendKey", sendKey)
        put("receiveKey", receiveKey)
    }.toString()

    companion object {
        fun fromJson(raw: String): PairSecrets? {
            return try {
                val json = JSONObject(raw)
                val keys = json.keys().asSequence().toSet()
                if (keys != setOf("privateKeyPkcs8", "accessToken", "sendKey", "receiveKey")) return null
                val result = PairSecrets(
                privateKeyPkcs8 = json.getString("privateKeyPkcs8"),
                accessToken = json.getString("accessToken"),
                sendKey = json.getString("sendKey"),
                receiveKey = json.getString("receiveKey")
            )
                if (Base64Url.decode(result.privateKeyPkcs8) == null) return null
                if (Base64Url.decode(result.accessToken, 32) == null) return null
                if (Base64Url.decode(result.sendKey, 32) == null) return null
                if (Base64Url.decode(result.receiveKey, 32) == null) return null
                result
            } catch (_: Exception) {
                null
            }
        }
    }
}

enum class PairingStage {
    UNPAIRED,
    CREATING_INVITE,
    WAITING_FOR_SCAN,
    JOINING,
    WAITING_FOR_SAS,
    WAITING_FOR_PEER_CONFIRMATION,
    PAIRED,
    REVOKED,
    FAILED
}

data class PairingUiState(
    val stage: PairingStage = PairingStage.UNPAIRED,
    val qrPayload: String? = null,
    val expiresAt: Long? = null,
    val sas: String? = null,
    val peerName: String? = null,
    val message: String = "",
    val legacyDetected: Boolean = false,
    val busy: Boolean = false
)
