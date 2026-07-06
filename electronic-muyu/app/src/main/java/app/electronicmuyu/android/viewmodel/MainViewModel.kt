package app.electronicmuyu.android.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import app.electronicmuyu.android.audio.SoundManager
import app.electronicmuyu.android.data.LocalDataStore
import app.electronicmuyu.android.model.ConnectionState
import app.electronicmuyu.android.network.WebSocketClient
import app.electronicmuyu.android.notification.NotificationHelper
import app.electronicmuyu.android.service.MuyuConnectionRepository
import app.electronicmuyu.android.service.MuyuForegroundService
import app.electronicmuyu.android.vibration.VibrationManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val localDataStore = LocalDataStore(application)

    private val _meriCount = MutableStateFlow(0)
    val meriCount: StateFlow<Int> = _meriCount.asStateFlow()

    private val _receivedCount = MutableStateFlow(0)
    val receivedCount: StateFlow<Int> = _receivedCount.asStateFlow()

    private val _soundEnabled = MutableStateFlow(true)
    val soundEnabled: StateFlow<Boolean> = _soundEnabled.asStateFlow()

    private val _vibrationEnabled = MutableStateFlow(true)
    val vibrationEnabled: StateFlow<Boolean> = _vibrationEnabled.asStateFlow()

    private val _notificationEnabled = MutableStateFlow(false)
    val notificationEnabled: StateFlow<Boolean> = _notificationEnabled.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = MuyuConnectionRepository.connectionState

    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError.asStateFlow()

    private val _deviceId = MutableStateFlow("")
    val deviceId: StateFlow<String> = _deviceId.asStateFlow()

    private val _deviceIdDisplay = MutableStateFlow("")
    val deviceIdDisplay: StateFlow<String> = _deviceIdDisplay.asStateFlow()

    private val _serverUrl = MutableStateFlow(DEFAULT_SERVER_URL)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _roomId = MutableStateFlow(DEFAULT_ROOM_ID)
    val roomId: StateFlow<String> = _roomId.asStateFlow()

    private val _lastTappedTime = MutableStateFlow<Long?>(null)
    val lastTappedTime: StateFlow<Long?> = _lastTappedTime.asStateFlow()

    private val _lastReceivedTime = MutableStateFlow<Long?>(null)
    val lastReceivedTime: StateFlow<Long?> = _lastReceivedTime.asStateFlow()

    private val _lastReceivedEvent = MutableStateFlow<Long?>(null)
    val lastReceivedEvent: StateFlow<Long?> = _lastReceivedEvent.asStateFlow()

    private val _wsEnabled = MutableStateFlow(false)
    val wsEnabled: StateFlow<Boolean> = _wsEnabled.asStateFlow()

    val lastDisconnectReason: StateFlow<WebSocketClient.DisconnectReason> =
        MuyuConnectionRepository.lastDisconnectReason
    val lastDisconnectAtMillis: StateFlow<Long?> = MuyuConnectionRepository.lastDisconnectAtMillis
    val isReconnecting: StateFlow<Boolean> = MuyuConnectionRepository.isReconnecting
    val lastReconnectResult: StateFlow<String> = MuyuConnectionRepository.lastReconnectResult
    val isServiceRunning: StateFlow<Boolean> = MuyuConnectionRepository.isServiceRunning
    val foregroundNotificationText: StateFlow<String> =
        MuyuConnectionRepository.foregroundNotificationText

    private val _isRequestingNotificationPermission = MutableStateFlow(false)
    val isRequestingNotificationPermission: StateFlow<Boolean> =
        _isRequestingNotificationPermission.asStateFlow()

    val soundManager = SoundManager(application)
    val vibrationManager = VibrationManager(application)

    private val _isAppInForeground = MutableStateFlow(true)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    companion object {
        const val DEFAULT_SERVER_URL = "ws://192.168.96.33:8443"
        const val DEFAULT_ROOM_ID = "test-room"
        private const val TAG = "ElectronicMuyu"
    }

    private val processLifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START,
            Lifecycle.Event.ON_RESUME -> {
                _isAppInForeground.value = true
                MuyuConnectionRepository.setAppForeground(true)
                Log.d(TAG, "foreground=true")
            }
            Lifecycle.Event.ON_STOP -> {
                _isAppInForeground.value = false
                MuyuConnectionRepository.setAppForeground(false)
                Log.d(TAG, "foreground=false")
            }
            else -> Unit
        }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)

        viewModelScope.launch {
            localDataStore.meriCount.collect { _meriCount.value = it }
        }
        viewModelScope.launch {
            localDataStore.receivedCount.collect { _receivedCount.value = it }
        }
        viewModelScope.launch {
            localDataStore.soundEnabled.collect { _soundEnabled.value = it }
        }
        viewModelScope.launch {
            localDataStore.vibrationEnabled.collect { _vibrationEnabled.value = it }
        }
        viewModelScope.launch {
            localDataStore.notificationEnabled.collect { _notificationEnabled.value = it }
        }
        viewModelScope.launch {
            localDataStore.deviceId.collect { savedId ->
                if (savedId.isEmpty()) {
                    val newId = UUID.randomUUID().toString()
                    _deviceId.value = newId
                    _deviceIdDisplay.value = newId.take(8)
                    localDataStore.setDeviceId(newId)
                } else {
                    _deviceId.value = savedId
                    _deviceIdDisplay.value = savedId.take(8)
                }
            }
        }
        viewModelScope.launch {
            localDataStore.wsUrl.collect { savedUrl ->
                _serverUrl.value = savedUrl.ifEmpty { DEFAULT_SERVER_URL }
            }
        }
        viewModelScope.launch {
            localDataStore.roomId.collect { savedRoomId ->
                _roomId.value = savedRoomId.ifEmpty { DEFAULT_ROOM_ID }
            }
        }
        viewModelScope.launch {
            MuyuConnectionRepository.receivedTapEvents.collect { timestamp ->
                _lastReceivedTime.value = timestamp
                if (MuyuConnectionRepository.appForeground.value) {
                    _lastReceivedEvent.value = timestamp
                    playSoundAndVibrate()
                }
            }
        }
        viewModelScope.launch {
            MuyuConnectionRepository.isServiceRunning.collect { isRunning ->
                if (!isRunning) {
                    _wsEnabled.value = false
                }
            }
        }
    }

    fun startConnection() {
        val deviceIdVal = _deviceId.value
        if (deviceIdVal.isEmpty()) {
            MuyuConnectionRepository.setDisconnectReason(
                WebSocketClient.DisconnectReason.INVALID_CONFIG,
                System.currentTimeMillis()
            )
            return
        }

        val serverUrlVal = _serverUrl.value
        val roomIdVal = _roomId.value
        val fullUrl = "${serverUrlVal}?room=${roomIdVal}"

        _wsEnabled.value = true
        MuyuConnectionRepository.setConnectionState(ConnectionState.CONNECTING)

        val context = getApplication<Application>()
        val intent = Intent(context, MuyuForegroundService::class.java).apply {
            action = MuyuForegroundService.ACTION_START_CONNECT
            putExtra(MuyuForegroundService.EXTRA_SERVER_URL, fullUrl)
            putExtra(MuyuForegroundService.EXTRA_PAIR_ID, roomIdVal)
            putExtra(MuyuForegroundService.EXTRA_DEVICE_ID, deviceIdVal)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopConnection() {
        _wsEnabled.value = false
        val context = getApplication<Application>()
        val intent = Intent(context, MuyuForegroundService::class.java).apply {
            action = MuyuForegroundService.ACTION_DISCONNECT
        }
        context.startService(intent)
    }

    fun onTap() {
        val newCount = _meriCount.value + 1
        _meriCount.value = newCount
        _lastTappedTime.value = System.currentTimeMillis()

        viewModelScope.launch {
            localDataStore.setMeriCount(newCount)
        }

        playSoundAndVibrate()

        if (MuyuConnectionRepository.isServiceRunning.value) {
            val context = getApplication<Application>()
            val intent = Intent(context, MuyuForegroundService::class.java).apply {
                action = MuyuForegroundService.ACTION_SEND_TAP
                putExtra(MuyuForegroundService.EXTRA_TIMESTAMP, System.currentTimeMillis())
            }
            context.startService(intent)
        }
    }

    fun setSoundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            _soundEnabled.value = enabled
            localDataStore.setSoundEnabled(enabled)
        }
    }

    fun setVibrationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            _vibrationEnabled.value = enabled
            localDataStore.setVibrationEnabled(enabled)
        }
    }

    fun setNotificationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            _notificationEnabled.value = enabled
            localDataStore.setNotificationEnabled(enabled)
        }
    }

    fun saveConnectionConfig(serverUrl: String, roomId: String) {
        viewModelScope.launch {
            localDataStore.setWsUrl(serverUrl)
            localDataStore.setRoomId(roomId)
        }
    }

    fun resetConnectionConfig() {
        viewModelScope.launch {
            localDataStore.setWsUrl("")
            localDataStore.setRoomId("")
        }
    }

    fun clearAllCounts() {
        viewModelScope.launch {
            _meriCount.value = 0
            _receivedCount.value = 0
            _lastTappedTime.value = null
            _lastReceivedTime.value = null
            _lastReceivedEvent.value = null
            localDataStore.clearAllCounts()
        }
    }

    fun dismissReceivedEvent() {
        _lastReceivedEvent.value = null
    }

    fun checkNotificationPermissionState(): Boolean {
        return NotificationHelper.hasNotificationPermission(getApplication())
    }

    private fun playSoundAndVibrate() {
        if (_soundEnabled.value) {
            soundManager.playMuyuHit()
        }
        if (_vibrationEnabled.value) {
            vibrationManager.shortTap()
        }
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        super.onCleared()
    }
}
