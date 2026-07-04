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


    val deviceId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEVICE_ID] ?: ""
    }

    val wsUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_WS_URL] ?: ""
    }

    suspend fun setMeriCount(count: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MERI_COUNT] = count
        }
    }

    suspend fun setReceivedCount(count: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_RECEIVED_COUNT] = count
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


    suspend fun setDeviceId(id: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEVICE_ID] = id
        }
    }

    suspend fun setWsUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_WS_URL] = url
        }
    }

    suspend fun clearAllCounts() {
        context.dataStore.edit { prefs ->
            prefs[KEY_MERI_COUNT] = 0
            prefs[KEY_RECEIVED_COUNT] = 0
        }
    }
}
