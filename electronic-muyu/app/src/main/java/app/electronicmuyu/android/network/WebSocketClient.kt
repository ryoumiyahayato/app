package app.electronicmuyu.android.network

import android.util.Log
import app.electronicmuyu.android.model.ConnectionState
import app.electronicmuyu.android.pairing.PAIRING_PROTOCOL_VERSION
import app.electronicmuyu.android.security.EncryptedTap
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.random.Random
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
import org.json.JSONObject
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
        private const val RATE_LIMIT_RETRY_DELAY_MS = 15_000L

        internal fun retryDelayForAttempt(attempt: Int): Long {
            val delayMs = INITIAL_RETRY_DELAY_MS * (1L shl attempt.coerceIn(0, 6))
            return delayMs.coerceAtMost(MAX_RETRY_DELAY_MS)
        }

        internal fun disconnectReasonForCloseCode(code: Int): DisconnectReason {
            return when (code) {
                4000, 4001, 4002, 4003, 4004, 1003, 1009 -> DisconnectReason.SERVER_REJECTED
                4008 -> DisconnectReason.RATE_LIMITED
                else -> DisconnectReason.SERVER_CLOSED
            }
        }

        internal fun shouldReconnect(reason: DisconnectReason): Boolean {
            return reason == DisconnectReason.NETWORK_ERROR ||
                reason == DisconnectReason.SERVER_CLOSED ||
                reason == DisconnectReason.RATE_LIMITED
        }

        internal fun shouldReconnect(reason: DisconnectReason): Boolean =
            reason == DisconnectReason.NETWORK_ERROR || reason == DisconnectReason.SERVER_CLOSED
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
        AUTHENTICATION_FAILED("authentication_failed"),
        PAIR_REVOKED("pair_revoked"),
        RATE_LIMITED("rate_limited"),
        SERVICE_START_FAILED("service_start_failed"),
        SERVICE_TIMEOUT("service_timeout"),
        SERVICE_DESTROYED("service_destroyed"),
        INVALID_CONFIG("invalid_config"),
        UNKNOWN("unknown")
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var generation = 0L
    private var credentials: SecureSocketCredentials? = null
    private var retryAttempt = 0
    private var authenticated = false

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _partnerOnline = MutableStateFlow(false)
    val partnerOnline: StateFlow<Boolean> = _partnerOnline.asStateFlow()

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

    var onEncryptedTapReceived: ((EncryptedTap) -> Unit)? = null
    var onPairRevoked: (() -> Unit)? = null

    fun connect(credentials: SecureSocketCredentials, force: Boolean = false) {
        val same = this.credentials == credentials
        if (!force && same && _connectionState.value in setOf(
                ConnectionState.CONNECTING,
                ConnectionState.AUTHENTICATING,
                ConnectionState.CONNECTED,
                ConnectionState.RECONNECTING
            )
        ) return

        val request = try { Request.Builder().url(credentials.url).build() } catch (_: Exception) {
            failTerminal(DisconnectReason.INVALID_CONFIG, "安全连接地址无效")
            return
        }
        if (!force) {
            reconnectJob?.cancel()
            retryAttempt = 0
        }
        val previous = webSocket
        val currentGeneration = ++generation
        webSocket = null
        previous?.cancel()
        this.credentials = credentials
        authenticated = false
        _lastError.value = ""
        _partnerOnline.value = false
        _connectionState.value = if (force) ConnectionState.RECONNECTING else ConnectionState.CONNECTING
        Log.d(TAG, "secure socket connecting pairHash=${shortHash(credentials.pairId)}")

        val listener = object : WebSocketListener() {
            override fun onOpen(socket: WebSocket, response: Response) {
                scope.launch {
                    if (!isCurrent(currentGeneration, socket)) return@launch
                    webSocket = socket
                    if (!sendHello(socket)) {
                        _lastError.value = "连接握手发送失败"
                        socket.close(1011, "hello send failed")
                        return@launch
                    }
                    Log.d(TAG, "websocket open; hello sent roomHash=${shortHash(this@WebSocketClient.pairId)}")
                }
            }

            override fun onMessage(socket: WebSocket, text: String) {
                scope.launch {
                    if (!isCurrentConnection(generation, socket)) return@launch

                    val objectMessage = try {
                        JSONObject(text)
                    } catch (_: Exception) {
                        null
                    }
                    when (objectMessage?.optString("type")) {
                        "room_state" -> {
                            if (objectMessage.optString("pairId") != this@WebSocketClient.pairId) {
                                Log.d(TAG, "received mismatched room_state; ignoring")
                                return@launch
                            }
                            completeHandshake(objectMessage.optBoolean("peerOnline", false))
                            return@launch
                        }
                        "room_info" -> {
                            // Compatibility with one deployment generation while both relays roll forward.
                            completeHandshake(objectMessage.optInt("connections", 1) >= 2)
                            return@launch
                        }
                    }

                    val event = TapEvent.fromJson(text)
                    if (
                        event != null &&
                        event.pairId == this@WebSocketClient.pairId &&
                        event.deviceId != this@WebSocketClient.deviceId
                    ) {
                        Log.d(TAG, "received tap from deviceId=${maskDeviceId(event.deviceId)}")
                        onTapReceived?.invoke(event)
                    } else if (objectMessage?.optString("type") != "hello_required") {
                        Log.d(TAG, "received invalid, mismatched, or self message; ignoring")
                    }
                    onEncryptedTapReceived?.invoke(message)
                }
            }

            override fun onClosing(socket: WebSocket, code: Int, reason: String) {
                scope.launch {
                    if (!isCurrent(currentGeneration, socket)) return@launch
                    try { socket.close(code, null) } catch (_: IllegalArgumentException) {
                        socket.close(1000, null)
                    }
                }
            }

            override fun onClosed(socket: WebSocket, code: Int, reason: String) {
                scope.launch {
                    if (!retire(currentGeneration, socket)) return@launch
                    val disconnectReason = disconnectReasonForCloseCode(code)
                    if (disconnectReason == DisconnectReason.PAIR_REVOKED) onPairRevoked?.invoke()
                    finish(disconnectReason, userMessage(disconnectReason))
                }
            }

            override fun onFailure(socket: WebSocket, throwable: Throwable, response: Response?) {
                scope.launch {
                    if (!retire(currentGeneration, socket)) return@launch
                    val reason = when {
                        response?.code == 429 -> DisconnectReason.RATE_LIMITED
                        response?.code?.let { it in 400..499 } == true -> DisconnectReason.SERVER_REJECTED
                        else -> DisconnectReason.NETWORK_ERROR
                    }
                    finish(reason, if (reason == DisconnectReason.NETWORK_ERROR) "安全连接暂时不可用" else "服务器拒绝安全连接")
                }
            }
        }
        client.newWebSocket(request, listener).also {
            if (currentGeneration == generation) webSocket = it else it.cancel()
        }
    }

    fun sendEncryptedTap(message: EncryptedTap): Boolean {
        if (!authenticated || _connectionState.value != ConnectionState.CONNECTED) return false
        return webSocket?.send(message.toJson()) == true
    }

    fun disconnect(reason: DisconnectReason) {
        val socket = webSocket
        ++generation
        webSocket = null
        reconnectJob?.cancel()
        reconnectJob = null
        retryAttempt = 0
        authenticated = false
        credentials = null
        recordDisconnect(reason)
        _isReconnecting.value = false
        _partnerOnline.value = false
        _lastReconnectResult.value = "stopped: ${reason.label}"
        _connectionState.value = if (reason == DisconnectReason.PAIR_REVOKED) ConnectionState.REVOKED else ConnectionState.DISCONNECTED
        socket?.let { if (!it.close(1000, reason.label)) it.cancel() }
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
        _partnerOnline.value = false
        connectionGeneration++
        return true
    }

    private fun finishOrReconnect(reason: DisconnectReason) {
        if (shouldReconnect(reason)) {
            scheduleReconnect(reason)
        } else {
            _isReconnecting.value = false
            _lastReconnectResult.value = "stopped: ${reason.label}"
        }
    }

    private fun scheduleReconnect(reason: DisconnectReason) {
        if (reconnectJob?.isActive == true) return
        val saved = credentials ?: return
        reconnectJob = scope.launch {
            val normalDelay = retryDelayForAttempt(retryAttempt)
            val delayMs = if (reason == DisconnectReason.RATE_LIMITED) {
                maxOf(normalDelay, RATE_LIMIT_RETRY_DELAY_MS)
            } else {
                normalDelay
            }
            retryAttempt++
            _isReconnecting.value = true
            _connectionState.value = ConnectionState.RECONNECTING
            _lastReconnectResult.value = "scheduled in ${delayMs}ms"
            delay(delayMs)
            reconnectJob = null
            connect(saved, force = true)
        }
    }

    private fun sendHello(socket: WebSocket): Boolean {
        val payload = JSONObject().apply {
            put("type", "hello")
            put("pairId", pairId)
            put("deviceId", deviceId)
            put("protocolVersion", 2)
        }.toString()
        return socket.send(payload)
    }

    private fun completeHandshake(peerOnline: Boolean) {
        _partnerOnline.value = peerOnline
        _lastError.value = ""
        _connectionState.value = ConnectionState.CONNECTED
        _isReconnecting.value = false
        _lastReconnectResult.value = if (retryAttempt > 0) "success" else "not_needed"
        reconnectJob = null
        retryAttempt = 0
        Log.d(TAG, "handshake complete peerOnline=$peerOnline roomHash=${shortHash(pairId)}")
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
            4004 -> "当前连接已被同一设备的新连接替换"
            4008 -> "发送频率过高，稍后将自动重连"
            1003 -> "服务器仅接受文本消息"
            1009 -> "消息超过服务器允许的大小"
            else -> null
        }
    }

    private fun recordDisconnect(reason: DisconnectReason) {
        _lastDisconnectReason.value = reason
        _lastDisconnectAtMillis.value = System.currentTimeMillis()
    }

    private fun isValidAuthOk(raw: String, expectedSlot: Int): Boolean = try {
        val json = JSONObject(raw)
        json.keys().asSequence().toSet() == setOf("type", "version", "slot", "timestamp") &&
            json.optString("type") == "auth_ok" &&
            json.optInt("version") == PAIRING_PROTOCOL_VERSION &&
            json.optInt("slot", -1) == expectedSlot &&
            json.optLong("timestamp", 0L) > 0
    } catch (_: Exception) { false }

    private fun userMessage(reason: DisconnectReason): String = when (reason) {
        DisconnectReason.AUTHENTICATION_FAILED -> "设备认证失败，请重新配对"
        DisconnectReason.PAIR_REVOKED -> "安全配对已撤销"
        DisconnectReason.RATE_LIMITED -> "发送过于频繁，请稍后重试"
        DisconnectReason.SERVER_REJECTED -> "服务器拒绝了安全连接"
        else -> "安全连接已断开"
    }

    private fun shortHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(4)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
