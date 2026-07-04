package app.electronicmuyu.android.network

import android.util.Log
import app.electronicmuyu.android.model.ConnectionState
import app.electronicmuyu.android.model.TapEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * 阶段 3 WSS 客户端 — 仅用于本地开发测试
 *
 * 功能：
 * - 连接指定的 WSS URL（ws:// 本地测试）
 * - 发送 tap 事件
 * - 接收 tap 事件，通过回调通知上层
 * - 断线自动重连（指数退避，上限 60s）
 *
 * ⚠️ 正式环境必须使用 wss://
 */
class WebSocketClient(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ElectronicMuyu"
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val MAX_RETRY_DELAY_MS = 60_000L
    }

    // 断开原因分类
    enum class DisconnectReason(val label: String) {
        USER_ACTION("user_action"),
        LIFECYCLE_ON_CLEARED("lifecycle_onCleared"),
        ACTIVITY_ON_STOP("activity_onStop"),
        COMPOSABLE_DISPOSE("composable_dispose"),
        PROCESS_LIFECYCLE_STOP("process_lifecycle_stop"),
        NETWORK_ERROR("network_error"),
        SERVER_CLOSED("server_closed"),
        SERVICE_TIMEOUT("service_timeout"),
        SERVICE_DESTROYED("service_destroyed"),
        INVALID_CONFIG("invalid_config"),
        UNKNOWN("unknown")
    }

    // 日志脱敏：只显示前4后4
    private fun maskDeviceId(id: String): String {
        if (id.length < 8) return "***"
        return id.substring(0, 4) + "***" + id.substring(id.length - 4)
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // 无超时，长连接
        .pingInterval(30, TimeUnit.SECONDS)     // 30s 心跳保活
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var currentUrl: String? = null
    private var deviceId: String = ""
    private var pairId: String = "test-room"
    private var manualCloseReason: DisconnectReason? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError.asStateFlow()

    // 最近一次断开原因
    private val _lastDisconnectReason = MutableStateFlow(DisconnectReason.UNKNOWN)
    val lastDisconnectReason: StateFlow<DisconnectReason> = _lastDisconnectReason.asStateFlow()

    private val _lastDisconnectAtMillis = MutableStateFlow<Long?>(null)
    val lastDisconnectAtMillis: StateFlow<Long?> = _lastDisconnectAtMillis.asStateFlow()

    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    private val _lastReconnectResult = MutableStateFlow("none")
    val lastReconnectResult: StateFlow<String> = _lastReconnectResult.asStateFlow()

    // 收到 tap 时的回调
    var onTapReceived: ((TapEvent) -> Unit)? = null

    fun connect(url: String, deviceId: String, pairId: String = "test-room", force: Boolean = false) {
        val currentState = _connectionState.value
        if (!force &&
            (currentState == ConnectionState.CONNECTED || currentState == ConnectionState.CONNECTING || currentState == ConnectionState.RECONNECTING) &&
            currentUrl == url &&
            this.deviceId == deviceId &&
            this.pairId == pairId
        ) {
            Log.d(TAG, "connect ignored: already active state=$currentState")
            return
        }

        Log.d(TAG, "connecting to $url deviceId=${maskDeviceId(deviceId)} pairId=$pairId")

        this.currentUrl = url
        this.deviceId = deviceId
        this.pairId = pairId
        this.manualCloseReason = null

        _connectionState.value = ConnectionState.CONNECTING

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "connected pairId=$pairId")
                _connectionState.value = ConnectionState.CONNECTED
                _isReconnecting.value = false
                _lastReconnectResult.value = if (retryAttempt > 0) "success" else "not_needed"
                // 连接成功后重置重试
                reconnectJob?.cancel()
                reconnectJob = null
                retryAttempt = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = TapEvent.fromJson(text)
                if (event != null) {
                    Log.d(TAG, "received tap from deviceId=${maskDeviceId(event.deviceId)}")
                    onTapReceived?.invoke(event)
                } else {
                    Log.d(TAG, "received non-tap message, ignoring")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "closing code=$code reason=$reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val closeReason = manualCloseReason ?: DisconnectReason.SERVER_CLOSED
                recordDisconnect(closeReason)
                Log.d(TAG, "closed code=$code reason=$reason disconnectReason=${closeReason.label}")
                _connectionState.value = ConnectionState.DISCONNECTED

                if (shouldReconnect(closeReason)) {
                    Log.d(TAG, "${closeReason.label}, scheduling reconnect")
                    scheduleReconnect()
                } else {
                    _isReconnecting.value = false
                    Log.d(TAG, "${closeReason.label}, no reconnect")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val msg = t.message ?: "unknown error"
                Log.e(TAG, "failure: $msg")

                _lastError.value = msg
                _connectionState.value = ConnectionState.DISCONNECTED

                val closeReason = manualCloseReason ?: DisconnectReason.NETWORK_ERROR
                recordDisconnect(closeReason)

                if (shouldReconnect(closeReason)) {
                    _lastReconnectResult.value = "failed: $msg"
                    Log.d(TAG, "${closeReason.label}, scheduling reconnect")
                    scheduleReconnect()
                } else {
                    _isReconnecting.value = false
                    Log.d(TAG, "${closeReason.label}, no reconnect")
                }
            }
        })
    }

    fun sendTap(timestamp: Long) {
        val event = TapEvent(
            type = "tap",
            pairId = pairId,
            deviceId = deviceId,
            timestamp = timestamp
        )
        val json = event.toJson()

        val sent = webSocket?.send(json) ?: false
        Log.d(TAG, "sendTap sent=$sent timestamp=$timestamp")
    }

    fun disconnect(reason: DisconnectReason) {
        manualCloseReason = reason
        recordDisconnect(reason)
        Log.d(TAG, "disconnect requested: reason=${reason.label}")
        reconnectJob?.cancel()
        reconnectJob = null
        _isReconnecting.value = false
        _lastReconnectResult.value = "stopped: ${reason.label}"
        webSocket?.close(1000, reason.label)
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private var retryAttempt = 0

    private fun scheduleReconnect() {
        val url = currentUrl ?: return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = calculateDelay(retryAttempt)
            retryAttempt++
            _isReconnecting.value = true
            _connectionState.value = ConnectionState.RECONNECTING
            _lastReconnectResult.value = "scheduled attempt $retryAttempt in ${delayMs}ms"
            Log.d(TAG, "scheduling reconnect attempt $retryAttempt in ${delayMs}ms")
            delay(delayMs)
            connect(url, deviceId, pairId, force = true)
        }
    }

    private fun calculateDelay(attempt: Int): Long {
        val delay = INITIAL_RETRY_DELAY_MS * (1L shl attempt.coerceAtMost(6))
        return delay.coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    fun resetRetry() {
        retryAttempt = 0
    }

    private fun recordDisconnect(reason: DisconnectReason) {
        _lastDisconnectReason.value = reason
        _lastDisconnectAtMillis.value = System.currentTimeMillis()
    }

    private fun shouldReconnect(reason: DisconnectReason): Boolean {
        return reason == DisconnectReason.NETWORK_ERROR || reason == DisconnectReason.SERVER_CLOSED
    }
}
