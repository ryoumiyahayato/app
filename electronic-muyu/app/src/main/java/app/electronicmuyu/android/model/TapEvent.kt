package app.electronicmuyu.android.model

import org.json.JSONObject

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
                TapEvent(
                    type = obj.getString("type"),
                    pairId = obj.getString("pairId"),
                    deviceId = obj.getString("deviceId"),
                    timestamp = obj.getLong("timestamp")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}