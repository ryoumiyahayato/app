package app.electronicmuyu.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import app.electronicmuyu.android.pairing.PairMetadata
import app.electronicmuyu.android.security.Base64Url

private val Context.dataStore by preferencesDataStore(name = "muyu_prefs")

class LocalDataStore(private val context: Context) {

    data class StoredPair(
        val metadata: PairMetadata,
        val encryptedSecrets: String,
        val sendCounter: Long,
        val remoteCounter: Long
    )

    companion object {
        private val KEY_MERI_COUNT = intPreferencesKey("meri_count")
        private val KEY_RECEIVED_COUNT = intPreferencesKey("received_count")
        private val KEY_SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        private val KEY_VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
        private val KEY_NOTIFICATION_ENABLED = booleanPreferencesKey("notification_enabled")
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_WS_URL = stringPreferencesKey("ws_url")
        private val KEY_ROOM_ID = stringPreferencesKey("room_id")
        private val KEY_PAIR_METADATA = stringPreferencesKey("secure_pair_metadata_v1")
        private val KEY_PAIR_SECRETS = stringPreferencesKey("secure_pair_secrets_v1")
        private val KEY_SEND_COUNTER = longPreferencesKey("secure_pair_send_counter_v1")
        private val KEY_REMOTE_COUNTER = longPreferencesKey("secure_pair_remote_counter_v1")
        private val KEY_DEBUG_RELAY_OVERRIDE = stringPreferencesKey("debug_relay_override")
        private val KEY_LEGACY_MIGRATION_REQUIRED = booleanPreferencesKey("legacy_migration_required")
        private const val MAX_SAFE_JSON_COUNTER = 9_007_199_254_740_991L
        fun canStoreConnectionUrl(url: String): Boolean {
            return ConnectionUrlPolicy.isAllowedForPlainStorage(url)
        }
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

    val legacyConfigurationDetected: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_LEGACY_MIGRATION_REQUIRED] == true || LegacyMigrationPolicy.action(
            prefs[KEY_WS_URL],
            prefs[KEY_ROOM_ID],
            securePairExists = prefs[KEY_PAIR_METADATA] != null && prefs[KEY_PAIR_SECRETS] != null
        ) == LegacyMigrationAction.WARN_AND_REQUIRE_NEW_PAIRING
    }

    val storedPair: Flow<StoredPair?> = context.dataStore.data.map { prefs ->
        val metadata = prefs[KEY_PAIR_METADATA]?.let(PairMetadata::fromJson)
        val encryptedSecrets = prefs[KEY_PAIR_SECRETS]
        if (metadata == null || encryptedSecrets.isNullOrBlank()) null else StoredPair(
            metadata = metadata,
            encryptedSecrets = encryptedSecrets,
            sendCounter = prefs[KEY_SEND_COUNTER] ?: 0L,
            remoteCounter = prefs[KEY_REMOTE_COUNTER] ?: 0L
        )
    }

    val debugRelayOverride: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEBUG_RELAY_OVERRIDE].orEmpty()
    }

    suspend fun saveSecurePair(metadata: PairMetadata, encryptedSecrets: String) {
        require(encryptedSecrets.isNotBlank())
        context.dataStore.edit { prefs ->
            prefs[KEY_PAIR_METADATA] = metadata.toJson()
            prefs[KEY_PAIR_SECRETS] = encryptedSecrets
            prefs[KEY_SEND_COUNTER] = 0L
            prefs[KEY_REMOTE_COUNTER] = 0L
            // A completed secure pairing atomically retires all legacy connection material.
            prefs.remove(KEY_WS_URL)
            prefs.remove(KEY_ROOM_ID)
            prefs.remove(KEY_LEGACY_MIGRATION_REQUIRED)
        }
    }

    suspend fun nextSendCounter(): Long {
        var next = 0L
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_SEND_COUNTER] ?: 0L
            check(current < MAX_SAFE_JSON_COUNTER) { "send counter exhausted" }
            next = current + 1L
            prefs[KEY_SEND_COUNTER] = next
        }
        return next
    }

    suspend fun acceptRemoteCounter(counter: Long): Boolean {
        if (counter <= 0L) return false
        var accepted = false
        context.dataStore.edit { prefs ->
            val previous = prefs[KEY_REMOTE_COUNTER] ?: 0L
            if (counter > previous) {
                prefs[KEY_REMOTE_COUNTER] = counter
                accepted = true
            }
        }
        return accepted
    }

    suspend fun clearSecurePair() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_PAIR_METADATA)
            prefs.remove(KEY_PAIR_SECRETS)
            prefs.remove(KEY_SEND_COUNTER)
            prefs.remove(KEY_REMOTE_COUNTER)
        }
    }

    suspend fun setDebugRelayOverride(url: String?) {
        context.dataStore.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(KEY_DEBUG_RELAY_OVERRIDE)
            else prefs[KEY_DEBUG_RELAY_OVERRIDE] = url
        }
    }

    suspend fun getOrCreateDeviceId(createId: () -> String): String {
        var resolvedId = ""
        context.dataStore.edit { prefs ->
            val savedId = prefs[KEY_DEVICE_ID].orEmpty()
            resolvedId = savedId.takeIf { Base64Url.decode(it, 16) != null } ?: createId()
            if (savedId != resolvedId) {
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

    suspend fun prepareLegacyMigration(): Boolean {
        var detected = false
        context.dataStore.edit { prefs ->
            val savedUrl = prefs[KEY_WS_URL]
            detected = !savedUrl.isNullOrBlank() || !prefs[KEY_ROOM_ID].isNullOrBlank() ||
                prefs[KEY_LEGACY_MIGRATION_REQUIRED] == true
            if (detected) prefs[KEY_LEGACY_MIGRATION_REQUIRED] = true
            // Never retain credentials embedded by an old/debug build in ordinary DataStore.
            if (savedUrl != null && !canStoreConnectionUrl(savedUrl)) {
                prefs.remove(KEY_WS_URL)
                prefs.remove(KEY_ROOM_ID)
            }
        }
        return detected
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
