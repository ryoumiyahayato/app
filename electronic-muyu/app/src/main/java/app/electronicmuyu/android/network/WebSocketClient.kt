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
 * 电子木鱼 WebSocket 客户端。
 *
 * 每个 MuyuForegroundService 实例只创建一个客户端。连接操作、回调和重连任务都串行化到
 * Service 提供的主协程作用域，并通过 connectionGeneration 隔离旧 socket 回调。
 */
class WebSocketClient(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ElectronicMuyu"
        private const val INITIAL_RETRY_DELAY_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 60_000L

        internal fun retryDelayForAttempt(attempt: Int): Long {
            val delayMs = INITIAL_RETRY_DELAY_MS * (1L shl attempt.coerceIn(0, 6))
            return delayMs.coerceAtMost(MAX_RETRY_DELAY_MS)
        }

        internal fun disconnectReasonForCloseCode(code: Int): DisconnectReason {
            return when (code) {
                4000, 4001, 4002, 4003, 1003, 1009 -> DisconnectReason.SERVER_REJECTED
                4008 -> DisconnectReason.RATE_LIMITED
                else -> DisconnectReason.SERVER_CLOSED
            }
        }

        internal fun shouldReconnect(reason: DisconnectReason): Boolean {
            return reason == DisconnectReason.NETWORK_ERROR ||
                reason == DisconnectReason.SERVER_CLOSED
        }
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
        SERVICE_START_FAILED("service_start_failed"),
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
    private var connectionGeneration = 0L
    private var currentUrl: String? = null
    private var deviceId: String = ""
    private var pairId: String = "test-room"
    private var retryAttempt = 0

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
        val sameConfig = currentUrl == url && this.deviceId == deviceId && this.pairId == pairId
        if (!force && sameConfig && currentState in setOf(
                ConnectionState.CONNECTED,
                ConnectionState.CONNECTING,
                ConnectionState.RECONNECTING
            )
        ) {
            Log.d(TAG, "connect ignored: already active state=$currentState")
            return
        }

        val request = try {
            Request.Builder().url(url).build()
        } catch (_: Exception) {
            val message = "invalid websocket URL"
            Log.e(TAG, message)
            _lastError.value = message
            _connectionState.value = ConnectionState.DISCONNECTED
            _isReconnecting.value = false
            recordDisconnect(DisconnectReason.INVALID_CONFIG)
            return
        }

        if (!force) {
            reconnectJob?.cancel()
            reconnectJob = null
            retryAttempt = 0
        }

        val previousSocket = webSocket
        val generation = ++connectionGeneration
        webSocket = null
        previousSocket?.cancel()

        this.currentUrl = url
        this.deviceId = deviceId
        this.pairId = pairId

        _lastError.value = ""
        _connectionState.value = if (force) ConnectionState.RECONNECTING else ConnectionState.CONNECTING

        Log.d(
            TAG,
            "connecting to ${safeUrlForLog(url)} deviceId=${maskDeviceId(deviceId)} roomHash=${shortHash(pairId)}"
        )

        val listener = object : WebSocketListener() {
            override fun onOpen(socket: WebSocket, response: Response) {
                scope.launch {
                    if (!isCurrentConnection(generation, socket)) return@launch
                    webSocket = socket
                    Log.d(TAG, "connected roomHash=${shortHash(this@WebSocketClient.pairId)}")
                    _lastError.value = ""
                    _connectionState.value = ConnectionState.CONNECTED
                    _isReconnecting.value = false
                    _lastReconnectResult.value = if (retryAttempt > 0) "success" else "not_needed"
                    reconnectJob = null
                    retryAttempt = 0
                }
            }

            override fun onMessage(socket: WebSocket, text: String) {
                scope.launch {
                    if (!isCurrentConnection(generation, socket)) return@launch
                    val event = TapEvent.fromJson(text)
                    if (
                        event != null &&
                        event.pairId == this@WebSocketClient.pairId &&
                        event.deviceId != this@WebSocketClient.deviceId
                    ) {
                        Log.d(TAG, "received tap from deviceId=${maskDeviceId(event.deviceId)}")
                        onTapReceived?.invoke(event)
                    } else {
                        Log.d(TAG, "received invalid, mismatched, or self tap; ignoring")
                    }
                }
            }

            override fun onClosing(socket: WebSocket, code: Int, reason: String) {
                scope.launch {
                    if (!isCurrentConnection(generation, socket)) return@launch
                    Log.d(TAG, "closing code=$code")

                    // 回送服务端原始关闭码，确保 4000/4001/4002/4008 等终止性原因
                    // 不会在握手过程中被改写为 1000，继而误判为可重连错误。
                    val acknowledged = try {
                        socket.close(code, null)
                    } catch (_: IllegalArgumentException) {
                        socket.close(1000, null)
                    }
                    if (!acknowledged) {
                        socket.cancel()
                    }
                }
            }

            override fun onClosed(socket: WebSocket, code: Int, reason: String) {
                scope.launch {
                    if (!retireCurrentConnection(generation, socket)) return@launch

                    val closeReason = disconnectReasonForCloseCode(code)
                    userMessageForCloseCode(code)?.let { _lastError.value = it }

                    recordDisconnect(closeReason)
                    Log.d(TAG, "closed code=$code disconnectReason=${closeReason.label}")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    finishOrReconnect(closeReason)
                }
            }

            override fun onFailure(socket: WebSocket, throwable: Throwable, response: Response?) {
                scope.launch {
                    if (!retireCurrentConnection(generation, socket)) return@launch

                    val closeReason = disconnectReasonForHttpStatus(response?.code)
                    val message = when (closeReason) {
                        DisconnectReason.SERVER_REJECTED ->
                            "服务器拒绝 WebSocket 握手${response?.code?.let { "（HTTP $it）" }.orEmpty()}"
                        DisconnectReason.RATE_LIMITED ->
                            "服务器限制连接频率，请稍后重试"
                        else -> sanitizeErrorMessage(throwable.message)
                    }

                    Log.e(TAG, "failure: $message")
                    _lastError.value = message
                    _connectionState.value = ConnectionState.DISCONNECTED
                    recordDisconnect(closeReason)
                    _lastReconnectResult.value = "failed: $message"
                    finishOrReconnect(closeReason)
                }
            }
        }

        val newSocket = client.newWebSocket(request, listener)
        if (generation == connectionGeneration) {
            webSocket = newSocket
        } else {
            newSocket.cancel()
        }
    }

    fun sendTap(timestamp: Long): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.d(TAG, "sendTap skipped: websocket is not connected")
            return false
        }

        val socket = webSocket ?: return false
        val event = TapEvent(
            type = "tap",
            pairId = pairId,
            deviceId = deviceId,
            timestamp = timestamp
        )
        val sent = socket.send(event.toJson())
        Log.d(TAG, "sendTap sent=$sent")
        return sent
    }

    fun disconnect(reason: DisconnectReason) {
        val socket = webSocket
        ++connectionGeneration
        webSocket = null
        reconnectJob?.cancel()
        reconnectJob = null
        retryAttempt = 0

        recordDisconnect(reason)
        _isReconnecting.value = false
        _lastReconnectResult.value = "stopped: ${reason.label}"
        if (reason == DisconnectReason.USER_ACTION) {
            _lastError.value = ""
        }

        currentUrl = null
        deviceId = ""
        pairId = "test-room"
        _connectionState.value = ConnectionState.DISCONNECTED

        Log.d(TAG, "disconnect requested: reason=${reason.label}")
        socket?.let {
            if (!it.close(1000, reason.label)) {
                it.cancel()
            }
        }
    }

    fun shutdown(reason: DisconnectReason) {
        disconnect(reason)
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun isCurrentConnection(generation: Long, socket: WebSocket): Boolean {
        return generation == connectionGeneration && (webSocket == null || webSocket === socket)
    }

    private fun retireCurrentConnection(generation: Long, socket: WebSocket): Boolean {
        if (!isCurrentConnection(generation, socket)) return false
        webSocket = null
        connectionGeneration++
        return true
    }

    private fun finishOrReconnect(reason: DisconnectReason) {
        if (shouldReconnect(reason)) {
            scheduleReconnect()
        } else {
            _isReconnecting.value = false
            _lastReconnectResult.value = "stopped: ${reason.label}"
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return

        val url = currentUrl ?: return
        val reconnectDeviceId = deviceId
        val reconnectPairId = pairId

        reconnectJob = scope.launch {
            val delayMs = retryDelayForAttempt(retryAttempt)
            retryAttempt++
            _isReconnecting.value = true
            _connectionState.value = ConnectionState.RECONNECTING
            _lastReconnectResult.value = "scheduled attempt $retryAttempt in ${delayMs}ms"
            Log.d(TAG, "scheduling reconnect attempt $retryAttempt in ${delayMs}ms")
            delay(delayMs)
            reconnectJob = null
            connect(url, reconnectDeviceId, reconnectPairId, force = true)
        }
    }

    private fun disconnectReasonForHttpStatus(statusCode: Int?): DisconnectReason {
        if (statusCode == 429) return DisconnectReason.RATE_LIMITED
        if (statusCode != null && statusCode in 400..499) {
            return DisconnectReason.SERVER_REJECTED
        }
        return DisconnectReason.NETWORK_ERROR
    }

    private fun userMessageForCloseCode(code: Int): String? {
        return when (code) {
            4000 -> "服务器拒绝连接：房间参数无效"
            4001 -> "服务器拒绝连接：鉴权失败"
            4002 -> "服务器拒绝连接：房间已满"
            4003 -> "服务器当前连接数已满"
            4008 -> "发送频率过高，请稍后重新连接"
            1003 -> "服务器仅接受文本消息"
            1009 -> "消息超过服务器允许的大小"
            else -> null
        }
    }

    private fun recordDisconnect(reason: DisconnectReason) {
        _lastDisconnectReason.value = reason
        _lastDisconnectAtMillis.value = System.currentTimeMillis()
    }

}
