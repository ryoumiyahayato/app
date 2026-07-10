package app.electronicmuyu.android.service

import app.electronicmuyu.android.model.ConnectionState
import app.electronicmuyu.android.network.WebSocketClient
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ReceivedTapUiEvent(
    val id: Long,
    val receivedAtMillis: Long
)

object MuyuConnectionRepository {
    // 覆盖 relay 默认允许的 60 条/10 秒窗口并保留少量余量。
    private const val MAX_PENDING_UI_EVENTS = 64

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

    private val _appForeground = MutableStateFlow(false)
    val appForeground: StateFlow<Boolean> = _appForeground.asStateFlow()

    private val _foregroundNotificationText = MutableStateFlow("电子木鱼未连接")
    val foregroundNotificationText: StateFlow<String> =
        _foregroundNotificationText.asStateFlow()

    private val eventSequence = AtomicLong(0L)
    private val _pendingReceivedTapUiEvents =
        MutableStateFlow<List<ReceivedTapUiEvent>>(emptyList())
    val pendingReceivedTapUiEvents: StateFlow<List<ReceivedTapUiEvent>> =
        _pendingReceivedTapUiEvents.asStateFlow()

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

    fun recordReceivedTap(receivedAtMillis: Long, enqueueForForegroundUi: Boolean) {
        _lastTapReceivedAtMillis.value = receivedAtMillis
        if (!enqueueForForegroundUi) return

        val event = ReceivedTapUiEvent(
            id = eventSequence.incrementAndGet(),
            receivedAtMillis = receivedAtMillis
        )
        _pendingReceivedTapUiEvents.update { current ->
            (current + event).takeLast(MAX_PENDING_UI_EVENTS)
        }
    }

    fun consumeReceivedTapUiEvents(eventIds: Collection<Long>) {
        if (eventIds.isEmpty()) return
        val consumedIds = eventIds.toHashSet()
        _pendingReceivedTapUiEvents.update { current ->
            current.filterNot { it.id in consumedIds }
        }
    }

    fun clearPendingReceivedTapUiEvents() {
        _pendingReceivedTapUiEvents.value = emptyList()
        _lastTapReceivedAtMillis.value = null
    }
}
