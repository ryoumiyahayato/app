package app.electronicmuyu.android.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.electronicmuyu.android.BuildConfig
import app.electronicmuyu.android.audio.SoundManager
import app.electronicmuyu.android.data.LocalDataStore
import app.electronicmuyu.android.model.ConnectionState
import app.electronicmuyu.android.network.PairingApi
import app.electronicmuyu.android.network.PairingApiException
import app.electronicmuyu.android.network.RelayConfiguration
import app.electronicmuyu.android.network.WebSocketClient
import app.electronicmuyu.android.notification.NotificationHelper
import app.electronicmuyu.android.pairing.InviteQrPayload
import app.electronicmuyu.android.pairing.PairMetadata
import app.electronicmuyu.android.pairing.PairSecrets
import app.electronicmuyu.android.pairing.PairingStage
import app.electronicmuyu.android.pairing.PairingTranscript
import app.electronicmuyu.android.pairing.PairingUiState
import app.electronicmuyu.android.security.Base64Url
import app.electronicmuyu.android.security.PairingCrypto
import app.electronicmuyu.android.security.SecureSecretStore
import app.electronicmuyu.android.service.MuyuConnectionRepository
import app.electronicmuyu.android.service.MuyuForegroundService
import app.electronicmuyu.android.vibration.VibrationManager
import java.security.KeyPair
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private enum class Role { INVITER, JOINER }
    private data class PendingPairing(
        val role: Role,
        val inviteId: String,
        val sessionToken: String,
        val keyPair: KeyPair,
        val qrInvite: InviteQrPayload? = null,
        var transcript: PairingTranscript? = null,
        var accessToken: String? = null,
        var localConfirmed: Boolean = false
    )

    private val dataStore = LocalDataStore(application)
    private val secretStore = SecureSecretStore()
    private val soundManager = SoundManager(application)
    private val vibrationManager = VibrationManager(application)
    private val pairingHttpClient = OkHttpClient()
    private var pending: PendingPairing? = null
    private var pollingJob: Job? = null
    private var autoConnectAttempted = false

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
    val partnerOnline: StateFlow<Boolean> = MuyuConnectionRepository.partnerOnline

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
        val DEFAULT_SERVER_URL = if (BuildConfig.DEBUG) "ws://10.0.2.2:8443" else ""
        const val DEFAULT_ROOM_ID = "test-room"
        private const val MAX_ROOM_ID_LENGTH = 64
        private const val MAX_SERVER_URL_LENGTH = 2048
        private const val MAX_UI_EVENT_AGE_MS = 10_000L
    }

    init {
        viewModelScope.launch {
            try { dataStore.prepareLegacyMigration() } catch (_: Exception) {
                MuyuConnectionRepository.setLastError("旧连接配置安全检查失败")
            }
        }
        viewModelScope.launch { dataStore.meriCount.collect { _meriCount.value = it } }
        viewModelScope.launch { dataStore.receivedCount.collect { _receivedCount.value = it } }
        viewModelScope.launch { dataStore.soundEnabled.collect { _soundEnabled.value = it } }
        viewModelScope.launch { dataStore.vibrationEnabled.collect { _vibrationEnabled.value = it } }
        viewModelScope.launch { dataStore.notificationEnabled.collect { _notificationEnabled.value = it } }
        viewModelScope.launch { dataStore.debugRelayOverride.collect { _debugRelayOverride.value = it } }
        viewModelScope.launch {
            combine(dataStore.storedPair, dataStore.legacyConfigurationDetected) { pair, legacy ->
                pair to legacy
            }.collect { (pair, legacy) ->
                _storedPair.value = pair?.metadata
                if (pending == null) {
                    _pairingUiState.value = if (pair == null) {
                        PairingUiState(
                            stage = PairingStage.UNPAIRED,
                            legacyDetected = legacy,
                            message = if (legacy) "旧连接方式不再安全，请重新扫码配对。" else ""
                        )
                    } else {
                        PairingUiState(
                            stage = PairingStage.PAIRED,
                            peerName = pair.metadata.peerDeviceName,
                            legacyDetected = false
                        )
                    }
                }
                if (pair != null && !autoConnectAttempted) {
                    autoConnectAttempted = true
                    startConnection()
                }
            }
        }
        viewModelScope.launch {
            combine(
                MuyuConnectionRepository.pendingReceivedTapUiEvents,
                MuyuConnectionRepository.uiForeground
            ) { events, foreground -> events to foreground }.collect { (events, foreground) ->
                if (!foreground || events.isEmpty()) return@collect
                val ids = events.map { it.id }
                try {
                    val now = System.currentTimeMillis()
                    events.forEach { event ->
                        if (now - event.receivedAtMillis in 0L..10_000L) {
                            _lastReceivedEvent.value = event.id
                            playRemoteFeedback()
                        }
                    }
                } finally {
                    MuyuConnectionRepository.consumeReceivedTapUiEvents(ids)
                }
            }
        }
    }

    fun createInvite() {
        if (_storedPair.value != null || pending != null) return
        viewModelScope.launch {
            _pairingUiState.value = PairingUiState(PairingStage.CREATING_INVITE, busy = true)
            try {
                val api = requireApi()
                val deviceId = resolveDeviceId()
                val keyPair = PairingCrypto.generateKeyPair()
                val inviteId = PairingCrypto.randomBase64Url(16)
                val inviteSecret = PairingCrypto.randomBase64Url(32)
                val publicKey = Base64Url.encode(keyPair.public.encoded)
                val created = api.createInvite(
                    inviteId,
                    PairingCrypto.sha256Base64Url(inviteSecret),
                    deviceId,
                    deviceName(),
                    publicKey
                )
                val qr = InviteQrPayload(inviteId, inviteSecret, publicKey, created.expiresAt)
                pending = PendingPairing(Role.INVITER, inviteId, created.ownerSessionToken, keyPair, qr)
                _pairingUiState.value = PairingUiState(
                    stage = PairingStage.WAITING_FOR_SCAN,
                    qrPayload = qr.encode(),
                    expiresAt = created.expiresAt,
                    message = "等待对方扫描"
                )
                startPolling()
            } catch (error: Exception) {
                failPairing(userFacing(error))
            }
        }
    }

    fun acceptScannedQr(raw: String) {
        if (_storedPair.value != null || pending != null) return
        val qr = InviteQrPayload.decode(raw)
        if (qr == null) {
            failPairing("二维码无效、已过期或不是电子木鱼配对码")
            return
        }
        viewModelScope.launch {
            _pairingUiState.value = PairingUiState(PairingStage.JOINING, busy = true, message = "正在建立安全连接")
            try {
                val api = requireApi()
                val deviceId = resolveDeviceId()
                val keyPair = PairingCrypto.generateKeyPair()
                val joined = api.joinInvite(
                    qr.inviteId,
                    qr.inviteSecret,
                    deviceId,
                    deviceName(),
                    Base64Url.encode(keyPair.public.encoded)
                )
                val transcript = requireNotNull(joined.transcript)
                require(transcript.inviteId == qr.inviteId &&
                    transcript.inviter.publicKey == qr.inviterPublicKey
                ) { "pairing transcript mismatch" }
                pending = PendingPairing(
                    Role.JOINER,
                    qr.inviteId,
                    requireNotNull(joined.joinSessionToken),
                    keyPair,
                    qr,
                    transcript
                )
                showSas(transcript)
            } catch (error: Exception) {
                failPairing(userFacing(error))
            }
        }
    }

    fun confirmSas() {
        val active = pending ?: return
        val transcript = active.transcript ?: return
        if (active.localConfirmed) return
        viewModelScope.launch {
            _pairingUiState.value = _pairingUiState.value.copy(busy = true)
            try {
                val token = active.accessToken ?: PairingCrypto.randomBase64Url(32).also {
                    active.accessToken = it
                }
                val status = requireApi().statusOrConfirm(
                    active.inviteId,
                    localDevice(active, transcript).deviceId,
                    active.sessionToken,
                    "confirm",
                    PairingCrypto.sha256Base64Url(token)
                )
                active.localConfirmed = true
                if (status.status == "paired") finalizePairing(active, requireNotNull(status.transcript))
                else {
                    _pairingUiState.value = _pairingUiState.value.copy(
                        stage = PairingStage.WAITING_FOR_PEER_CONFIRMATION,
                        busy = false,
                        message = "已确认，等待对方确认"
                    )
                    startPolling()
                }
            } catch (error: Exception) {
                failPairing(userFacing(error))
            }
        }
    }

    fun rejectSas() {
        val active = pending ?: return
        val transcript = active.transcript
        viewModelScope.launch {
            try {
                if (transcript != null) requireApi().statusOrConfirm(
                    active.inviteId,
                    localDevice(active, transcript).deviceId,
                    active.sessionToken,
                    "reject"
                )
            } catch (_: Exception) {
                // Local temporary material is cleared even if the rejection request cannot be delivered.
            } finally {
                clearPending()
                failPairing("安全码不一致，临时配对已清除")
            }
        }
    }

    fun cancelInvite() {
        val active = pending ?: return
        viewModelScope.launch {
            try {
                if (active.role == Role.INVITER) requireApi().cancelInvite(
                    active.inviteId,
                    resolveDeviceId(),
                    active.sessionToken
                )
            } catch (_: Exception) {
                // Clearing local one-time material is always safe.
            } finally {
                clearPending()
                _pairingUiState.value = PairingUiState(PairingStage.UNPAIRED)
            }
        }
    }

    fun regenerateInvite() {
        cancelInvite()
        viewModelScope.launch {
            while (pending != null) delay(25)
            createInvite()
        }
    }

    fun revokePairing() {
        val metadata = _storedPair.value ?: return
        viewModelScope.launch {
            _pairingUiState.value = PairingUiState(PairingStage.PAIRED, peerName = metadata.peerDeviceName, busy = true)
            try {
                val stored = dataStore.storedPair.first() ?: error("pair unavailable")
                val secrets = PairSecrets.fromJson(secretStore.decrypt(stored.encryptedSecrets))
                    ?: error("pair secrets unavailable")
                try {
                    requireApi().revokePair(metadata.pairId, metadata.deviceId, secrets.accessToken)
                } catch (error: PairingApiException) {
                    if (error.status !in setOf(404, 410)) throw error
                }
                stopConnection()
                dataStore.clearSecurePair()
                secretStore.deleteKey()
                _storedPair.value = null
                _pairingUiState.value = PairingUiState(PairingStage.UNPAIRED)
            } catch (error: Exception) {
                _pairingUiState.value = PairingUiState(
                    PairingStage.PAIRED,
                    peerName = metadata.peerDeviceName,
                    message = "解除配对失败；本机凭据已保留，避免产生无法撤销的孤立凭据"
                )
            }
        }
    }

    fun saveDebugRelayOverride(value: String) {
        if (!BuildConfig.ALLOW_RELAY_OVERRIDE) return
        viewModelScope.launch {
            val normalized = value.trim()
            if (normalized.isNotEmpty() && PairingApi.validateRelayBaseUrl(normalized, true) == null) {
                MuyuConnectionRepository.setLastError("调试 relay 必须是 HTTPS，或本机/模拟器回环 HTTP")
                return@launch
            }
            dataStore.setDebugRelayOverride(normalized.ifEmpty { null })
            _debugRelayOverride.value = normalized
        }
    }

    fun startConnection() {
        if (_storedPair.value == null || MuyuConnectionRepository.isServiceRunning.value) return
        val context = getApplication<Application>()
        val intent = Intent(context, MuyuForegroundService::class.java).apply {
            action = MuyuForegroundService.ACTION_START_CONNECT
        }
        try { ContextCompat.startForegroundService(context, intent) } catch (_: Exception) {
            MuyuConnectionRepository.setLastError("无法启动安全连接服务")
        }
    }

    fun stopConnection() {
        val context = getApplication<Application>()
        try {
            context.startService(Intent(context, MuyuForegroundService::class.java).apply {
                action = MuyuForegroundService.ACTION_DISCONNECT
            })
        } catch (_: Exception) {
            MuyuConnectionRepository.setServiceRunning(false)
            MuyuConnectionRepository.setConnectionState(ConnectionState.DISCONNECTED)
        }
    }

    fun onTap() {
        val timestamp = System.currentTimeMillis()
        playSoundAndVibrate()
        viewModelScope.launch {
            try { dataStore.incrementMeriCount() } catch (_: Exception) {
                MuyuConnectionRepository.setLastError("本机功德计数保存失败")
            }
        }
        if (connectionState.value == ConnectionState.CONNECTED) {
            try {
                getApplication<Application>().startService(
                    Intent(getApplication(), MuyuForegroundService::class.java).apply {
                        action = MuyuForegroundService.ACTION_SEND_TAP
                        putExtra(MuyuForegroundService.EXTRA_TIMESTAMP, timestamp)
                    }
                )
            } catch (_: Exception) {
                MuyuConnectionRepository.setLastError("提醒未发送：安全连接服务不可用")
            }
        }
    }

    fun setSoundEnabled(value: Boolean) = viewModelScope.launch {
        try { dataStore.setSoundEnabled(value) } catch (_: Exception) {
            MuyuConnectionRepository.setLastError("声音设置保存失败")
        }
    }
    fun setVibrationEnabled(value: Boolean) = viewModelScope.launch {
        try { dataStore.setVibrationEnabled(value) } catch (_: Exception) {
            MuyuConnectionRepository.setLastError("振动设置保存失败")
        }
    }
    fun setNotificationEnabled(value: Boolean) = viewModelScope.launch {
        try { dataStore.setNotificationEnabled(value) } catch (_: Exception) {
            MuyuConnectionRepository.setLastError("通知设置保存失败")
        }
    }
    fun clearAllCounts() = viewModelScope.launch {
        dataStore.clearAllCounts()
        MuyuConnectionRepository.clearPendingReceivedTapUiEvents()
        _lastReceivedEvent.value = null
    }
    fun dismissReceivedEvent() { _lastReceivedEvent.value = null }
    fun checkNotificationPermissionState(): Boolean = NotificationHelper.hasNotificationPermission(getApplication())

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                delay(1_000)
                val active = pending ?: return@launch
                val current = _pairingUiState.value
                if (current.expiresAt?.let { System.currentTimeMillis() >= it } == true) {
                    clearPending()
                    failPairing("邀请已过期，请重新生成")
                    return@launch
                }
                try {
                    val transcript = active.transcript
                    val localId = transcript?.let { localDevice(active, it).deviceId } ?: resolveDeviceId()
                    val status = requireApi().statusOrConfirm(
                        active.inviteId, localId, active.sessionToken, "status"
                    )
                    status.transcript?.let {
                        if (active.transcript == null) {
                            active.transcript = it
                            showSas(it)
                        }
                    }
                    if (status.status == "paired" && active.localConfirmed) {
                        finalizePairing(active, requireNotNull(status.transcript))
                        return@launch
                    }
                } catch (error: PairingApiException) {
                    if (error.status in setOf(404, 409, 410)) {
                        clearPending()
                        failPairing(userFacing(error))
                        return@launch
                    }
                } catch (_: Exception) {
                    // Transient polling failures keep the bounded invitation alive until expiry.
                }
            }
        }
    }

    private fun showSas(transcript: PairingTranscript) {
        val active = pending ?: return
        validateTranscript(active, transcript)
        _pairingUiState.value = PairingUiState(
            stage = PairingStage.WAITING_FOR_SAS,
            expiresAt = transcript.expiresAt,
            sas = PairingCrypto.computeSas(transcript),
            peerName = peerDevice(active, transcript).deviceName,
            message = "请通过当面或语音核对"
        )
    }

    private suspend fun finalizePairing(active: PendingPairing, transcript: PairingTranscript) {
        validateTranscript(active, transcript)
        val token = requireNotNull(active.accessToken)
        val peer = peerDevice(active, transcript)
        val local = localDevice(active, transcript)
        val slot = if (active.role == Role.INVITER) 0 else 1
        val shared = PairingCrypto.sharedSecret(active.keyPair.private, PairingCrypto.publicKey(peer.publicKey))
        val directional = PairingCrypto.deriveDirectionalKeys(shared, transcript.pairId, slot)
        shared.fill(0)
        val secrets = PairSecrets(
            privateKeyPkcs8 = Base64Url.encode(active.keyPair.private.encoded),
            accessToken = token,
            sendKey = Base64Url.encode(directional.sendKey),
            receiveKey = Base64Url.encode(directional.receiveKey)
        )
        val encrypted = secretStore.encrypt(secrets.toJson())
        directional.sendKey.fill(0)
        directional.receiveKey.fill(0)
        val metadata = PairMetadata(
                pairId = transcript.pairId,
                deviceId = local.deviceId,
                peerDeviceId = peer.deviceId,
                peerDeviceName = peer.deviceName,
                slot = slot,
                createdAt = System.currentTimeMillis()
            )
        dataStore.saveSecurePair(metadata, encrypted)
        _storedPair.value = metadata
        clearPending()
        _pairingUiState.value = PairingUiState(PairingStage.PAIRED, peerName = peer.deviceName)
        startConnection()
    }

    private fun validateTranscript(active: PendingPairing, transcript: PairingTranscript) {
        require(transcript.inviteId == active.inviteId)
        require(Base64Url.decode(transcript.pairId, 16) != null)
        require(transcript.expiresAt > System.currentTimeMillis())
        val local = localDevice(active, transcript)
        require(local.publicKey == Base64Url.encode(active.keyPair.public.encoded))
        require(transcript.inviter.deviceId != transcript.joiner.deviceId)
    }

        return try {
            val parsedUri = trimmedServerUrl.toUri()
            val scheme = parsedUri.scheme?.lowercase()
            if (
                (scheme != "ws" && scheme != "wss") ||
                (!BuildConfig.DEBUG && scheme != "wss") ||
                parsedUri.host.isNullOrBlank() ||
                !parsedUri.encodedUserInfo.isNullOrEmpty() ||
                parsedUri.fragment != null ||
                !LocalDataStore.canStoreConnectionUrl(trimmedServerUrl)
            ) {
                return null
            }

    private suspend fun resolveDeviceId(): String = dataStore.getOrCreateDeviceId {
        PairingCrypto.randomBase64Url(16)
    }
    private fun deviceName(): String = Build.MODEL
        .filterNot { it.code in 0..31 || it.code == 127 }
        .trim()
        .take(64)
        .ifEmpty { "Android 设备" }
    private suspend fun requireApi(): PairingApi = PairingApi(
        RelayConfiguration.resolve(dataStore) ?: error("relay unavailable"),
        pairingHttpClient
    )
    private fun userFacing(error: Exception): String = when (error) {
        is PairingApiException -> when (error.errorCode) {
            "invite_expired" -> "邀请已过期"
            "invite_already_redeemed", "invite_already_used" -> "邀请已被使用"
            "invalid_invite_secret" -> "配对二维码无效"
            "pairing_rejected" -> "对方拒绝了安全码"
            "invite_cancelled" -> "邀请已取消"
            "rate_limited" -> "操作过于频繁，请稍后重试"
            else -> "配对服务暂时不可用"
        }
        else -> if (error.message == "relay unavailable") "未配置有效的 relay 地址" else "无法完成安全配对"
    }
    private fun failPairing(message: String) {
        _pairingUiState.value = PairingUiState(PairingStage.FAILED, message = message)
        MuyuConnectionRepository.setLastError(message)
    }
    private fun clearPending() {
        pollingJob?.cancel()
        pollingJob = null
        pending = null
    }
    private fun playSoundAndVibrate() {
        if (_soundEnabled.value) soundManager.playMuyuHit()
        if (_vibrationEnabled.value) vibrationManager.shortTap()
    }

    private fun playRemoteFeedback() {
        if (_soundEnabled.value) {
            soundManager.playNotificationTap()
        }
        if (_vibrationEnabled.value) {
            vibrationManager.notificationVibrate()
        }
    }

    override fun onCleared() {
        clearPending()
        soundManager.release()
        pairingHttpClient.dispatcher.cancelAll()
        pairingHttpClient.connectionPool.evictAll()
        pairingHttpClient.dispatcher.executorService.shutdown()
        super.onCleared()
    }
}
