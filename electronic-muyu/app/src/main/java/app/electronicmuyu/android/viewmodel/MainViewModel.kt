package app.electronicmuyu.android.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.electronicmuyu.android.audio.SoundManager
import app.electronicmuyu.android.data.LocalDataStore
import app.electronicmuyu.android.vibration.VibrationManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStore = LocalDataStore(application)
    val soundManager = SoundManager(application)
    val vibrationManager = VibrationManager(application)

    val meriCount: StateFlow<Int> = dataStore.meriCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val receivedCount: StateFlow<Int> = dataStore.receivedCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val soundEnabled: StateFlow<Boolean> = dataStore.soundEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val vibrationEnabled: StateFlow<Boolean> = dataStore.vibrationEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun onWoodfishTap() {
        viewModelScope.launch {
            dataStore.incrementMeriCount()
            if (soundEnabled.value) {
                soundManager.playMuyuHit()
            }
            if (vibrationEnabled.value) {
                vibrationManager.shortTap()
            }
        }
    }

    fun onReceiveTap() {
        viewModelScope.launch {
            dataStore.incrementReceivedCount()
            if (soundEnabled.value) {
                soundManager.playNotificationTap()
            }
            if (vibrationEnabled.value) {
                vibrationManager.notificationVibrate()
            }
        }
    }

    fun setSoundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setSoundEnabled(enabled)
        }
    }

    fun setVibrationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setVibrationEnabled(enabled)
        }
    }

    fun clearCounts() {
        viewModelScope.launch {
            dataStore.clearAllCounts()
        }
    }

    override fun onCleared() {
        super.onCleared()
        soundManager.release()
    }
}