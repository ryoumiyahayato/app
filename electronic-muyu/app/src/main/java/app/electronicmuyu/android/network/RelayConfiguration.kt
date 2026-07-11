package app.electronicmuyu.android.network

import app.electronicmuyu.android.BuildConfig
import app.electronicmuyu.android.data.LocalDataStore
import kotlinx.coroutines.flow.first

object RelayConfiguration {
    suspend fun resolve(dataStore: LocalDataStore): String? {
        if (BuildConfig.ALLOW_RELAY_OVERRIDE) {
            val override = dataStore.debugRelayOverride.first()
            PairingApi.validateRelayBaseUrl(override, allowInsecureLoopback = true)?.let { return it }
        }
        return PairingApi.validateRelayBaseUrl(
            BuildConfig.RELAY_BASE_URL,
            allowInsecureLoopback = false
        )
    }
}
