package app.electronicmuyu.android.data

import android.content.Context
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

    suspend fun setWsUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_WS_URL] = url
        }
    }

    suspend fun setRoomId(roomId: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ROOM_ID] = roomId
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
