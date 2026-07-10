package app.electronicmuyu.android.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
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
    val lastError: StateFlow<String> = MuyuConnectionRepository.lastError

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
    val isAppInForeground: StateFlow<Boolean> = MuyuConnectionRepository.appForeground

    private val soundManager = SoundManager(application)
    private val vibrationManager = VibrationManager(application)

    companion object {
        const val DEFAULT_SERVER_URL = "ws://192.168.96.33:8443"
        const val DEFAULT_ROOM_ID = "test-room"
        private const val MAX_ROOM_ID_LENGTH = 64
    }

    init {
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
            val resolvedId = localDataStore.getOrCreateDeviceId { UUID.randomUUID().toString() }
            _deviceId.value = resolvedId
            _deviceIdDisplay.value = resolvedId.take(8)
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
                _wsEnabled.value = isRunning
            }
        }
    }

    fun startConnection() {
        val deviceIdValue = _deviceId.value
        val roomIdValue = _roomId.value.trim()
        val fullUrl = buildWebSocketUrl(_serverUrl.value, roomIdValue)

        if (deviceIdValue.isBlank() || fullUrl == null) {
            _wsEnabled.value = false
            MuyuConnectionRepository.setLastError("连接配置无效，请检查服务器地址和房间 ID")
            MuyuConnectionRepository.setDisconnectReason(
                WebSocketClient.DisconnectReason.INVALID_CONFIG,
                System.currentTimeMillis()
            )
            MuyuConnectionRepository.setConnectionState(ConnectionState.DISCONNECTED)
            return
        }

        _wsEnabled.value = true
        MuyuConnectionRepository.setLastError("")
        MuyuConnectionRepository.setConnectionState(ConnectionState.CONNECTING)

        val context = getApplication<Application>()
        val intent = Intent(context, MuyuForegroundService::class.java).apply {
            action = MuyuForegroundService.ACTION_START_CONNECT
            putExtra(MuyuForegroundService.EXTRA_SERVER_URL, fullUrl)
            putExtra(MuyuForegroundService.EXTRA_PAIR_ID, roomIdValue)
            putExtra(MuyuForegroundService.EXTRA_DEVICE_ID, deviceIdValue)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopConnection() {
        _wsEnabled.value = false
        if (!MuyuConnectionRepository.isServiceRunning.value) {
            MuyuConnectionRepository.setReconnecting(false)
            MuyuConnectionRepository.setLastReconnectResult("stopped: user_action")
            MuyuConnectionRepository.setDisconnectReason(
                WebSocketClient.DisconnectReason.USER_ACTION,
                System.currentTimeMillis()
            )
            MuyuConnectionRepository.setConnectionState(ConnectionState.DISCONNECTED)
            return
        }

        val context = getApplication<Application>()
        val intent = Intent(context, MuyuForegroundService::class.java).apply {
            action = MuyuForegroundService.ACTION_DISCONNECT
        }
        context.startService(intent)
    }

    fun onTap() {
        val timestamp = System.currentTimeMillis()
        _lastTappedTime.value = timestamp
        playSoundAndVibrate()

        viewModelScope.launch {
            localDataStore.incrementMeriCount()
        }

        if (
            MuyuConnectionRepository.isServiceRunning.value &&
            MuyuConnectionRepository.connectionState.value == ConnectionState.CONNECTED
        ) {
            val context = getApplication<Application>()
            val intent = Intent(context, MuyuForegroundService::class.java).apply {
                action = MuyuForegroundService.ACTION_SEND_TAP
                putExtra(MuyuForegroundService.EXTRA_TIMESTAMP, timestamp)
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

    fun saveConnectionConfig(serverUrl: String, roomId: String): Boolean {
        val normalizedUrl = serverUrl.trim()
        val normalizedRoomId = roomId.trim()
        if (buildWebSocketUrl(normalizedUrl, normalizedRoomId) == null) {
            MuyuConnectionRepository.setLastError("连接配置无效，未保存")
            return false
        }

        viewModelScope.launch {
            localDataStore.setWsUrl(normalizedUrl)
            localDataStore.setRoomId(normalizedRoomId)
            MuyuConnectionRepository.setLastError("")
        }
        return true
    }

    fun resetConnectionConfig() {
        viewModelScope.launch {
            localDataStore.setWsUrl("")
            localDataStore.setRoomId("")
            MuyuConnectionRepository.setLastError("")
        }
    }

    fun clearAllCounts() {
        viewModelScope.launch {
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

    private fun buildWebSocketUrl(serverUrl: String, roomId: String): String? {
        val trimmedServerUrl = serverUrl.trim()
        val trimmedRoomId = roomId.trim()
        if (trimmedServerUrl.isEmpty() || !isValidRoomId(trimmedRoomId)) return null

        return try {
            val parsedUri = Uri.parse(trimmedServerUrl)
            val scheme = parsedUri.scheme?.lowercase()
            if ((scheme != "ws" && scheme != "wss") || parsedUri.host.isNullOrBlank()) {
                return null
            }

            val builder = parsedUri.buildUpon().clearQuery()
            parsedUri.queryParameterNames
                .filterNot { it == "room" }
                .forEach { name ->
                    parsedUri.getQueryParameters(name).forEach { value ->
                        builder.appendQueryParameter(name, value)
                    }
                }
            builder.appendQueryParameter("room", trimmedRoomId)
            builder.build().toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun isValidRoomId(roomId: String): Boolean {
        return roomId.isNotEmpty() &&
            roomId.length <= MAX_ROOM_ID_LENGTH &&
            roomId.none { it.code in 0..31 || it.code == 127 }
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
        soundManager.release()
        super.onCleared()
    }
}
