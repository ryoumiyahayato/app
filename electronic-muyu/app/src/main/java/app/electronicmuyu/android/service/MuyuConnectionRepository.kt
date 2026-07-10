package app.electronicmuyu.android.service

import app.electronicmuyu.android.model.ConnectionState
import app.electronicmuyu.android.network.WebSocketClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object MuyuConnectionRepository {
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _lastDisconnectReason =
        MutableStateFlow(WebSocketClient.DisconnectReason.UNKNOWN)
    val lastDisconnectReason: StateFlow<WebSocketClient.DisconnectReason> =
        _lastDisconnectReason.asStateFlow()

    private val _lastDisconnectAtMillis = MutableStateFlow<Long?>(null)
    val lastDisconnectAtMillis: StateFlow<Long?> = _lastDisconnectAtMillis.asStateFlow()

    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError.asStateFlow()

    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    private val _lastReconnectResult = MutableStateFlow("none")
    val lastReconnectResult: StateFlow<String> = _lastReconnectResult.asStateFlow()

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _lastTapReceivedAtMillis = MutableStateFlow<Long?>(null)
    val lastTapReceivedAtMillis: StateFlow<Long?> = _lastTapReceivedAtMillis.asStateFlow()

    // 安全默认值为后台，Application.onStart 后才切换为前台。
    private val _appForeground = MutableStateFlow(false)
    val appForeground: StateFlow<Boolean> = _appForeground.asStateFlow()

    private val _foregroundNotificationText = MutableStateFlow("电子木鱼未连接")
    val foregroundNotificationText: StateFlow<String> =
        _foregroundNotificationText.asStateFlow()

    private val _receivedTapEvents = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    val receivedTapEvents: SharedFlow<Long> = _receivedTapEvents.asSharedFlow()

    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    fun setDisconnectReason(reason: WebSocketClient.DisconnectReason, atMillis: Long?) {
        _lastDisconnectReason.value = reason
        _lastDisconnectAtMillis.value = atMillis
    }

    fun setLastError(error: String) {
        _lastError.value = error
    }

    fun setReconnecting(isReconnecting: Boolean) {
        _isReconnecting.value = isReconnecting
    }

    fun setLastReconnectResult(result: String) {
        _lastReconnectResult.value = result
    }

    fun setServiceRunning(isRunning: Boolean) {
        _isServiceRunning.value = isRunning
    }

    fun setAppForeground(isForeground: Boolean) {
        _appForeground.value = isForeground
    }

    fun setForegroundNotificationText(text: String) {
        _foregroundNotificationText.value = text
    }

    fun emitReceivedTap(atMillis: Long) {
        _lastTapReceivedAtMillis.value = atMillis
        _receivedTapEvents.tryEmit(atMillis)
    }
}
