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

data class SecureSocketCredentials(
    val url: String,
    val pairId: String,
    val deviceId: String,
    val peerDeviceId: String,
    val accessToken: String,
    val expectedSlot: Int
)

class WebSocketClient(private val scope: CoroutineScope) {
    companion object {
        private const val TAG = "ElectronicMuyu"
        private const val INITIAL_RETRY_DELAY_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 60_000L

        internal fun retryDelayForAttempt(attempt: Int): Long {
            val delayMs = INITIAL_RETRY_DELAY_MS * (1L shl attempt.coerceIn(0, 6))
            return delayMs.coerceAtMost(MAX_RETRY_DELAY_MS)
        }

        internal fun jitteredRetryDelay(attempt: Int, unit: Double = Random.nextDouble()): Long {
            val factor = 0.75 + unit.coerceIn(0.0, 1.0) * 0.5
            return (retryDelayForAttempt(attempt) * factor).toLong().coerceAtLeast(250L)
        }

        internal fun disconnectReasonForCloseCode(code: Int): DisconnectReason = when (code) {
            4401, 4410 -> DisconnectReason.AUTHENTICATION_FAILED
            4403 -> DisconnectReason.PAIR_REVOKED
            4408 -> DisconnectReason.RATE_LIMITED
            4400, 4409, 4414, 1003, 1009 -> DisconnectReason.SERVER_REJECTED
            else -> DisconnectReason.SERVER_CLOSED
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
        _connectionState.value = if (force) ConnectionState.RECONNECTING else ConnectionState.CONNECTING
        Log.d(TAG, "secure socket connecting pairHash=${shortHash(credentials.pairId)}")

        val listener = object : WebSocketListener() {
            override fun onOpen(socket: WebSocket, response: Response) {
                scope.launch {
                    if (!isCurrent(currentGeneration, socket)) return@launch
                    webSocket = socket
                    _connectionState.value = ConnectionState.AUTHENTICATING
                    val auth = JSONObject().apply {
                        put("type", "auth")
                        put("version", PAIRING_PROTOCOL_VERSION)
                        put("pairId", credentials.pairId)
                        put("deviceId", credentials.deviceId)
                        put("token", credentials.accessToken)
                    }
                    if (!socket.send(auth.toString())) {
                        socket.cancel()
                        failTerminal(DisconnectReason.NETWORK_ERROR, "连接认证发送失败")
                    }
                }
            }

            override fun onMessage(socket: WebSocket, text: String) {
                scope.launch {
                    if (!isCurrent(currentGeneration, socket)) return@launch
                    if (!authenticated) {
                        if (!isValidAuthOk(text, credentials.expectedSlot)) {
                            socket.close(4401, "authentication failed")
                            return@launch
                        }
                        authenticated = true
                        retryAttempt = 0
                        reconnectJob = null
                        _isReconnecting.value = false
                        _lastReconnectResult.value = "success"
                        _connectionState.value = ConnectionState.CONNECTED
                        Log.d(TAG, "secure socket authenticated pairHash=${shortHash(credentials.pairId)}")
                        return@launch
                    }
                    val message = EncryptedTap.fromJson(text)
                    if (message == null || message.pairId != credentials.pairId ||
                        message.sender != credentials.peerDeviceId
                    ) {
                        Log.d(TAG, "rejected invalid encrypted envelope")
                        return@launch
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

    private fun finish(reason: DisconnectReason, message: String) {
        recordDisconnect(reason)
        authenticated = false
        _lastError.value = message
        _connectionState.value = when (reason) {
            DisconnectReason.AUTHENTICATION_FAILED -> ConnectionState.AUTHENTICATION_FAILED
            DisconnectReason.PAIR_REVOKED -> ConnectionState.REVOKED
            else -> ConnectionState.DISCONNECTED
        }
        if (shouldReconnect(reason)) scheduleReconnect() else {
            _isReconnecting.value = false
            _lastReconnectResult.value = "stopped: ${reason.label}"
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        val saved = credentials ?: return
        reconnectJob = scope.launch {
            val delayMs = jitteredRetryDelay(retryAttempt++)
            _isReconnecting.value = true
            _connectionState.value = ConnectionState.RECONNECTING
            _lastReconnectResult.value = "scheduled in ${delayMs}ms"
            delay(delayMs)
            reconnectJob = null
            connect(saved, force = true)
        }
    }

    private fun failTerminal(reason: DisconnectReason, message: String) {
        _lastError.value = message
        _connectionState.value = ConnectionState.DISCONNECTED
        _isReconnecting.value = false
        recordDisconnect(reason)
    }

    private fun isCurrent(value: Long, socket: WebSocket): Boolean =
        value == generation && (webSocket == null || webSocket === socket)

    private fun retire(value: Long, socket: WebSocket): Boolean {
        if (!isCurrent(value, socket)) return false
        webSocket = null
        generation++
        return true
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
