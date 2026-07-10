package app.electronicmuyu.android.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "muyu_prefs")

class LocalDataStore(private val context: Context) {

    companion object {
        private val KEY_MERI_COUNT = intPreferencesKey("meri_count")
        private val KEY_RECEIVED_COUNT = intPreferencesKey("received_count")
        private val KEY_SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        private val KEY_VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
        private val KEY_NOTIFICATION_ENABLED = booleanPreferencesKey("notification_enabled")
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_WS_URL = stringPreferencesKey("ws_url")
        private val KEY_ROOM_ID = stringPreferencesKey("room_id")
        private val SENSITIVE_QUERY_KEYS = setOf(
            "token",
            "session",
            "session_token",
            "auth",
            "authorization",
            "api_key",
            "apikey"
        )
    }

    val meriCount: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_MERI_COUNT] ?: 0
    }

    val receivedCount: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_RECEIVED_COUNT] ?: 0
    }

    val soundEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SOUND_ENABLED] ?: true
    }

    val vibrationEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_VIBRATION_ENABLED] ?: true
    }

    val notificationEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_NOTIFICATION_ENABLED] ?: false
    }

    val wsUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_WS_URL] ?: ""
    }

    val roomId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_ROOM_ID] ?: ""
    }

    suspend fun getOrCreateDeviceId(createId: () -> String): String {
        var resolvedId = ""
        context.dataStore.edit { prefs ->
            val savedId = prefs[KEY_DEVICE_ID].orEmpty()
            resolvedId = savedId.ifEmpty { createId() }
            if (savedId.isEmpty()) {
                prefs[KEY_DEVICE_ID] = resolvedId
            }
        }
        return resolvedId
    }

    suspend fun incrementMeriCount(): Int {
        var updatedCount = 0
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_MERI_COUNT] ?: 0
            updatedCount = incrementWithoutOverflow(current)
            prefs[KEY_MERI_COUNT] = updatedCount
        }
        return updatedCount
    }

    suspend fun incrementReceivedCount(): Int {
        var updatedCount = 0
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_RECEIVED_COUNT] ?: 0
            updatedCount = incrementWithoutOverflow(current)
            prefs[KEY_RECEIVED_COUNT] = updatedCount
        }
        return updatedCount
    }

    suspend fun setMeriCount(count: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MERI_COUNT] = count.coerceAtLeast(0)
        }
    }

    suspend fun setReceivedCount(count: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_RECEIVED_COUNT] = count.coerceAtLeast(0)
        }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SOUND_ENABLED] = enabled
        }
    }

    suspend fun setVibrationEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_VIBRATION_ENABLED] = enabled
        }
    }

    suspend fun setNotificationEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_NOTIFICATION_ENABLED] = enabled
        }
    }

    /**
     * 服务器地址和房间 ID 必须作为同一份配置原子写入，避免进程在两次 edit 之间
     * 被终止时留下 URL 与 room 不匹配的半保存状态。
     * 当前版本没有 Keystore 凭据配置，因此禁止把 token/session 等秘密写入普通 DataStore。
     */
    suspend fun setConnectionConfig(url: String, roomId: String) {
        val queryNames = Uri.parse(url).queryParameterNames
        require(queryNames.none { it.lowercase() in SENSITIVE_QUERY_KEYS }) {
            "Sensitive connection credentials must not be stored in plain DataStore"
        }

        context.dataStore.edit { prefs ->
            prefs[KEY_WS_URL] = url
            prefs[KEY_ROOM_ID] = roomId
        }
    }

    suspend fun resetConnectionConfig() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_WS_URL)
            prefs.remove(KEY_ROOM_ID)
        }
    }

    suspend fun clearAllCounts() {
        context.dataStore.edit { prefs ->
            prefs[KEY_MERI_COUNT] = 0
            prefs[KEY_RECEIVED_COUNT] = 0
        }
    }

    private fun incrementWithoutOverflow(value: Int): Int {
        return if (value >= Int.MAX_VALUE) Int.MAX_VALUE else value + 1
    }
}
