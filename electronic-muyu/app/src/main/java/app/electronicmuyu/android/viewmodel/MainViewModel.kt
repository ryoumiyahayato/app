package app.electronicmuyu.android.viewmodel

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.Request
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
    private var connectionStartPending = false
    private var connectionRequestGeneration = 0L

    companion object {
        const val DEFAULT_SERVER_URL = "ws://192.168.96.33:8443"
        const val DEFAULT_ROOM_ID = "test-room"
        private const val MAX_ROOM_ID_LENGTH = 64
        private const val MAX_SERVER_URL_LENGTH = 2048
        private const val MAX_UI_EVENT_AGE_MS = 10_000L
    }

    init {
        viewModelScope.launch {
            try {
                if (localDataStore.purgeUnsafeConnectionConfig()) {
                    MuyuConnectionRepository.setLastError("已移除不安全的旧连接配置")
                }
            } catch (_: Exception) {
                MuyuConnectionRepository.setLastError("旧连接配置安全检查失败")
            }
        }
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
            try {
                resolveDeviceId()
            } catch (_: Exception) {
                MuyuConnectionRepository.setLastError("无法初始化设备标识")
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
            combine(
                MuyuConnectionRepository.pendingReceivedTapUiEvents,
                MuyuConnectionRepository.uiForeground
            ) { events, isUiForeground ->
                events to isUiForeground
            }.collect { (events, isUiForeground) ->
                if (events.isEmpty() || !isUiForeground) return@collect

                val eventIds = events.map { it.id }
                try {
                    val now = System.currentTimeMillis()
                    events.forEach { event ->
                        val age = now - event.receivedAtMillis
                        if (age in 0L..MAX_UI_EVENT_AGE_MS) {
                            _lastReceivedTime.value = event.receivedAtMillis
                            _lastReceivedEvent.value = event.id
                            playSoundAndVibrate()
                        }
                    }
                } finally {
                    MuyuConnectionRepository.consumeReceivedTapUiEvents(eventIds)
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
        if (
            connectionStartPending ||
            _wsEnabled.value ||
            MuyuConnectionRepository.isServiceRunning.value
        ) {
            return
        }

        val requestGeneration = ++connectionRequestGeneration
        connectionStartPending = true
        MuyuConnectionRepository.setLastError("")
        MuyuConnectionRepository.setConnectionState(ConnectionState.CONNECTING)

        val currentDeviceId = _deviceId.value
        if (currentDeviceId.isNotBlank()) {
            startConnectionWithDeviceId(currentDeviceId, requestGeneration)
            connectionStartPending = false
            return
        }

        viewModelScope.launch {
            try {
                val resolvedDeviceId = resolveDeviceId()
                if (requestGeneration == connectionRequestGeneration) {
                    startConnectionWithDeviceId(resolvedDeviceId, requestGeneration)
                }
            } catch (_: Exception) {
                if (requestGeneration == connectionRequestGeneration) {
                    failConnectionStart("无法初始化设备标识")
                }
            } finally {
                if (requestGeneration == connectionRequestGeneration) {
                    connectionStartPending = false
                }
            }
        }
    }

    private fun startConnectionWithDeviceId(
        deviceIdValue: String,
        requestGeneration: Long
    ) {
        if (requestGeneration != connectionRequestGeneration) return

        val roomIdValue = _roomId.value.trim()
        val fullUrl = buildWebSocketUrl(_serverUrl.value, roomIdValue)

        if (deviceIdValue.isBlank() || fullUrl == null) {
            failConnectionStart("连接配置无效，请检查服务器地址和房间 ID")
            return
        }

        MuyuConnectionRepository.setLastError("")
        MuyuConnectionRepository.setConnectionState(ConnectionState.CONNECTING)

        val context = getApplication<Application>()
        val intent = Intent(context, MuyuForegroundService::class.java).apply {
            action = MuyuForegroundService.ACTION_START_CONNECT
            putExtra(MuyuForegroundService.EXTRA_SERVER_URL, fullUrl)
            putExtra(MuyuForegroundService.EXTRA_PAIR_ID, roomIdValue)
            putExtra(MuyuForegroundService.EXTRA_DEVICE_ID, deviceIdValue)
        }

        try {
            ContextCompat.startForegroundService(context, intent)
            if (requestGeneration == connectionRequestGeneration) {
                _wsEnabled.value = true
            }
        } catch (_: Exception) {
            if (requestGeneration == connectionRequestGeneration) {
                failConnectionStart("无法启动连接服务")
            }
        }
    }

    private fun failConnectionStart(message: String) {
        _wsEnabled.value = false
        MuyuConnectionRepository.setServiceRunning(false)
        MuyuConnectionRepository.setReconnecting(false)
        MuyuConnectionRepository.setLastError(message)
        MuyuConnectionRepository.setDisconnectReason(
            WebSocketClient.DisconnectReason.INVALID_CONFIG,
            System.currentTimeMillis()
        )
        MuyuConnectionRepository.setConnectionState(ConnectionState.DISCONNECTED)
        MuyuConnectionRepository.setForegroundNotificationText("电子木鱼未连接")
    }

    private suspend fun resolveDeviceId(): String {
        val resolvedId = localDataStore.getOrCreateDeviceId { UUID.randomUUID().toString() }
        _deviceId.value = resolvedId
        _deviceIdDisplay.value = resolvedId.take(8)
        return resolvedId
    }

    fun stopConnection() {
        connectionRequestGeneration++
        connectionStartPending = false
        _wsEnabled.value = false
        if (!MuyuConnectionRepository.isServiceRunning.value) {
            MuyuConnectionRepository.setReconnecting(false)
            MuyuConnectionRepository.setLastReconnectResult("stopped: user_action")
            MuyuConnectionRepository.setDisconnectReason(
                WebSocketClient.DisconnectReason.USER_ACTION,
                System.currentTimeMillis()
            )
            MuyuConnectionRepository.setConnectionState(ConnectionState.DISCONNECTED)
            MuyuConnectionRepository.setForegroundNotificationText("电子木鱼未连接")
            return
        }

        val context = getApplication<Application>()
        val intent = Intent(context, MuyuForegroundService::class.java).apply {
            action = MuyuForegroundService.ACTION_DISCONNECT
        }
        try {
            context.startService(intent)
        } catch (_: Exception) {
            MuyuConnectionRepository.setServiceRunning(false)
            MuyuConnectionRepository.setReconnecting(false)
            MuyuConnectionRepository.setConnectionState(ConnectionState.DISCONNECTED)
            MuyuConnectionRepository.setForegroundNotificationText("电子木鱼未连接")
        }
    }

    fun onTap() {
        val timestamp = System.currentTimeMillis()
        _lastTappedTime.value = timestamp
        playSoundAndVibrate()

        viewModelScope.launch {
            try {
                localDataStore.incrementMeriCount()
            } catch (_: Exception) {
                MuyuConnectionRepository.setLastError("本机功德计数保存失败")
            }
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
            try {
                context.startService(intent)
            } catch (_: Exception) {
                MuyuConnectionRepository.setLastError("提醒未发送：连接服务不可用")
                MuyuConnectionRepository.setServiceRunning(false)
                MuyuConnectionRepository.setConnectionState(ConnectionState.DISCONNECTED)
            }
        }
    }

    fun setSoundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                localDataStore.setSoundEnabled(enabled)
                _soundEnabled.value = enabled
            } catch (_: Exception) {
                MuyuConnectionRepository.setLastError("声音设置保存失败")
            }
        }
    }

    fun setVibrationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                localDataStore.setVibrationEnabled(enabled)
                _vibrationEnabled.value = enabled
            } catch (_: Exception) {
                MuyuConnectionRepository.setLastError("震动设置保存失败")
            }
        }
    }

    fun setNotificationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                localDataStore.setNotificationEnabled(enabled)
                _notificationEnabled.value = enabled
            } catch (_: Exception) {
                MuyuConnectionRepository.setLastError("通知设置保存失败")
            }
        }
    }

    suspend fun saveConnectionConfig(serverUrl: String, roomId: String): Boolean {
        val normalizedUrl = serverUrl.trim()
        val normalizedRoomId = roomId.trim()
        if (buildWebSocketUrl(normalizedUrl, normalizedRoomId) == null) {
            MuyuConnectionRepository.setLastError("连接配置无效，未保存")
            return false
        }

        return try {
            localDataStore.setConnectionConfig(normalizedUrl, normalizedRoomId)
            _serverUrl.value = normalizedUrl
            _roomId.value = normalizedRoomId
            MuyuConnectionRepository.setLastError("")
            true
        } catch (_: Exception) {
            MuyuConnectionRepository.setLastError("连接配置保存失败")
            false
        }
    }

    suspend fun resetConnectionConfig(): Boolean {
        return try {
            localDataStore.resetConnectionConfig()
            _serverUrl.value = DEFAULT_SERVER_URL
            _roomId.value = DEFAULT_ROOM_ID
            MuyuConnectionRepository.setLastError("")
            true
        } catch (_: Exception) {
            MuyuConnectionRepository.setLastError("默认连接配置恢复失败")
            false
        }
    }

    fun clearAllCounts() {
        viewModelScope.launch {
            try {
                localDataStore.clearAllCounts()
                MuyuConnectionRepository.clearPendingReceivedTapUiEvents()
                _lastTappedTime.value = null
                _lastReceivedTime.value = null
                _lastReceivedEvent.value = null
            } catch (_: Exception) {
                MuyuConnectionRepository.setLastError("计数清空失败")
            }
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
        if (
            trimmedServerUrl.isEmpty() ||
            trimmedServerUrl.length > MAX_SERVER_URL_LENGTH ||
            !isValidRoomId(trimmedRoomId)
        ) {
            return null
        }

        return try {
            val parsedUri = trimmedServerUrl.toUri()
            val scheme = parsedUri.scheme?.lowercase()
            if (
                (scheme != "ws" && scheme != "wss") ||
                parsedUri.host.isNullOrBlank() ||
                !parsedUri.encodedUserInfo.isNullOrEmpty() ||
                parsedUri.fragment != null ||
                !LocalDataStore.canStoreConnectionUrl(trimmedServerUrl)
            ) {
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
            val fullUrl = builder.build().toString()

            Request.Builder().url(fullUrl).build()
            fullUrl
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
        connectionRequestGeneration++
        soundManager.release()
        super.onCleared()
    }
}
