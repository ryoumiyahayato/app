package app.electronicmuyu.android.model

import org.json.JSONObject

private const val MAX_PAIR_ID_LENGTH = 64
private const val MAX_DEVICE_ID_LENGTH = 128

data class TapEvent(
    val type: String,
    val pairId: String,
    val deviceId: String,
    val timestamp: Long
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("type", type)
            put("pairId", pairId)
            put("deviceId", deviceId)
            put("timestamp", timestamp)
        }.toString()
    }

    companion object {
        fun fromJson(json: String): TapEvent? {
            return try {
                val obj = JSONObject(json)
                if (obj.optString("type") != "tap") return null

                val pairId = obj.optString("pairId")
                val deviceId = obj.optString("deviceId")
                val timestampValue = obj.opt("timestamp") as? Number ?: return null
                val timestamp = timestampValue.toLong()

                if (!isValidIdentifier(pairId, MAX_PAIR_ID_LENGTH)) return null
                if (!isValidIdentifier(deviceId, MAX_DEVICE_ID_LENGTH)) return null
                if (timestamp <= 0L) return null

                TapEvent(
                    type = "tap",
                    pairId = pairId,
                    deviceId = deviceId,
                    timestamp = timestamp
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun isValidIdentifier(value: String, maxLength: Int): Boolean {
            return value.isNotEmpty() &&
                value.length <= maxLength &&
                value.none { it.code in 0..31 || it.code == 127 }
        }
    }
}
