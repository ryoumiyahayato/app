package app.electronicmuyu.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "muyu_prefs")

class LocalDataStore(private val context: Context) {

    companion object {
        private val MERI_COUNT = intPreferencesKey("meri_count")
        private val RECEIVED_COUNT = intPreferencesKey("received_count")
        private val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        private val VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
    }

    val meriCount: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[MERI_COUNT] ?: 0
    }

    val receivedCount: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[RECEIVED_COUNT] ?: 0
    }

    val soundEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SOUND_ENABLED] ?: true
    }

    val vibrationEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[VIBRATION_ENABLED] ?: true
    }

    suspend fun incrementMeriCount() {
        context.dataStore.edit { prefs ->
            val current = prefs[MERI_COUNT] ?: 0
            prefs[MERI_COUNT] = current + 1
        }
    }

    suspend fun incrementReceivedCount() {
        context.dataStore.edit { prefs ->
            val current = prefs[RECEIVED_COUNT] ?: 0
            prefs[RECEIVED_COUNT] = current + 1
        }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[SOUND_ENABLED] = enabled
        }
    }

    suspend fun setVibrationEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[VIBRATION_ENABLED] = enabled
        }
    }

    suspend fun clearAllCounts() {
        context.dataStore.edit { prefs ->
            prefs[MERI_COUNT] = 0
            prefs[RECEIVED_COUNT] = 0
        }
    }
}