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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 阶段 3 WSS 客户端
 *
 * 功能：
 * - 连接指定的 WebSocket URL
 * - 发送和接收 tap
 * - 断线自动重连（指数退避，上限 60s）
 *
 * 公网长期使用必须切换到 wss://。
 */
class WebSocketClient(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ElectronicMuyu"
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val MAX_RETRY_DELAY_MS = 60_000L
    }

    enum class DisconnectReason(val label: String) {
        USER_ACTION("user_action"),
        LIFECYCLE_ON_CLEARED("lifecycle_onCleared"),
        ACTIVITY_ON_STOP("activity_onStop"),
        COMPOSABLE_DISPOSE("composable_dispose"),
        PROCESS_LIFECYCLE_STOP("process_lifecycle_stop"),
        NETWORK_ERROR("network_error"),
        SERVER_CLOSED("server_closed"),
        SERVER_REJECTED("server_rejected"),
        RATE_LIMITED("rate_limited"),
        SERVICE_TIMEOUT("service_timeout"),
        SERVICE_DESTROYED("service_destroyed"),
        INVALID_CONFIG("invalid_config"),
        UNKNOWN("unknown")
    }

    private fun maskDeviceId(id: String): String {
        if (id.length < 8) return "***"
        return id.substring(0, 4) + "***" + id.substring(id.length - 4)
    }

    private fun shortHash(value: String): String {
        if (value.isBlank()) return "none"
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.take(4).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun safeUrlForLog(url: String): String {
        return url.substringBefore('?').substringBefore('#')
    }

    private fun sanitizeErrorMessage(message: String?): String {
        val raw = message ?: "unknown error"
        return Regex("(wss?://[^?\\s#]+)(\\?[^\\s#]*)?")
            .replace(raw) { match -> "${match.groupValues[1]}?<hidden>" }
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
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

    private val _lastDisconnectReason = MutableStateFlow(DisconnectReason.UNKNOWN)
    val lastDisconnectReason: StateFlow<DisconnectReason> = _lastDisconnectReason.asStateFlow()

    private val _lastDisconnectAtMillis = MutableStateFlow<Long?>(null)
    val lastDisconnectAtMillis: StateFlow<Long?> = _lastDisconnectAtMillis.asStateFlow()

    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    private val _lastReconnectResult = MutableStateFlow("none")
    val lastReconnectResult: StateFlow<String> = _lastReconnectResult.asStateFlow()

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

        Log.d(
            TAG,
            "connecting to ${safeUrlForLog(url)} deviceId=${maskDeviceId(deviceId)} roomHash=${shortHash(pairId)}"
        )

        val request = try {
            Request.Builder()
                .url(url)
                .build()
        } catch (_: Exception) {
            val message = "invalid websocket URL"
            Log.e(TAG, message)
            _lastError.value = message
            _connectionState.value = ConnectionState.DISCONNECTED
            _isReconnecting.value = false
            recordDisconnect(DisconnectReason.INVALID_CONFIG)
            return
        }

        this.currentUrl = url
        this.deviceId = deviceId
        this.pairId = pairId
        this.manualCloseReason = null

        _lastError.value = ""
        _connectionState.value = ConnectionState.CONNECTING

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "connected roomHash=${shortHash(pairId)}")
                _lastError.value = ""
                _connectionState.value = ConnectionState.CONNECTED
                _isReconnecting.value = false
                _lastReconnectResult.value = if (retryAttempt > 0) "success" else "not_needed"
                reconnectJob?.cancel()
                reconnectJob = null
                retryAttempt = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = TapEvent.fromJson(text)
                if (event != null && event.pairId == pairId && event.deviceId != deviceId) {
                    Log.d(TAG, "received tap from deviceId=${maskDeviceId(event.deviceId)}")
                    onTapReceived?.invoke(event)
                } else {
                    Log.d(TAG, "received invalid or self tap message, ignoring")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "closing code=$code reason=$reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val closeReason = manualCloseReason ?: when (code) {
                    4000, 4001, 4002 -> DisconnectReason.SERVER_REJECTED
                    4008 -> DisconnectReason.RATE_LIMITED
                    else -> DisconnectReason.SERVER_CLOSED
                }
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
                val msg = sanitizeErrorMessage(t.message)
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
        val sent = webSocket?.send(event.toJson()) ?: false
        Log.d(TAG, "sendTap sent=$sent")
    }

    fun disconnect(reason: DisconnectReason) {
        manualCloseReason = reason
        recordDisconnect(reason)
        Log.d(TAG, "disconnect requested: reason=${reason.label}")
        reconnectJob?.cancel()
        reconnectJob = null
        _isReconnecting.value = false
        _lastReconnectResult.value = "stopped: ${reason.label}"
        if (reason == DisconnectReason.USER_ACTION) {
            _lastError.value = ""
        }
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
