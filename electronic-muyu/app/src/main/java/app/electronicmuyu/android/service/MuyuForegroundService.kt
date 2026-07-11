package app.electronicmuyu.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import app.electronicmuyu.android.MainActivity
import app.electronicmuyu.android.R
import app.electronicmuyu.android.data.LocalDataStore
import app.electronicmuyu.android.model.ConnectionState
import app.electronicmuyu.android.network.PairingApi
import app.electronicmuyu.android.network.RelayConfiguration
import app.electronicmuyu.android.network.SecureSocketCredentials
import app.electronicmuyu.android.network.WebSocketClient
import app.electronicmuyu.android.notification.NotificationHelper
import app.electronicmuyu.android.pairing.PairSecrets
import app.electronicmuyu.android.security.Base64Url
import app.electronicmuyu.android.security.EncryptedTap
import app.electronicmuyu.android.security.PairingCrypto
import app.electronicmuyu.android.security.SecureSecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MuyuForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var localDataStore: LocalDataStore
    private lateinit var secretStore: SecureSecretStore
    private lateinit var wsClient: WebSocketClient
    private var sendKey: ByteArray? = null
    private var receiveKey: ByteArray? = null
    private var foregroundStarted = false
    private var stopReason: WebSocketClient.DisconnectReason? = null
    private var activeStartId = 0
    private val pendingTaps = PendingTapQueue()
    private var pendingTapFlushJob: Job? = null
    private var pendingTapExpiryJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        localDataStore = LocalDataStore(applicationContext)
        secretStore = SecureSecretStore()
        wsClient = WebSocketClient(serviceScope)
        NotificationHelper.createNotificationChannel(applicationContext)
        createConnectionNotificationChannel()

        wsClient.onEncryptedTapReceived = { message ->
            serviceScope.launch { decryptAndDeliver(message) }
        }
        wsClient.onPairRevoked = {
            serviceScope.launch {
                try {
                    clearRevokedPair()
                } catch (error: Exception) {
                    Log.e(TAG, "failed to clear revoked local pair", error)
                    MuyuConnectionRepository.setLastError("配对已撤销，但本机清理不完整；请重新安装后再配对")
                } finally {
                    disconnectAndStop(WebSocketClient.DisconnectReason.PAIR_REVOKED)
                }
            }
        }
        serviceScope.launch {
            wsClient.connectionState.collectLatest { state ->
                MuyuConnectionRepository.setConnectionState(state)
                if (foregroundStarted) updateForegroundNotification(state)
                if (state == ConnectionState.CONNECTED) schedulePendingTapFlush()
            }
        }
        serviceScope.launch {
            wsClient.lastError.collectLatest(MuyuConnectionRepository::setLastError)
        }
        serviceScope.launch {
            wsClient.partnerOnline.collectLatest { isOnline ->
                MuyuConnectionRepository.setPartnerOnline(isOnline)
                if (foregroundStarted) {
                    updateForegroundNotification(wsClient.connectionState.value)
                }
            }
        }
        serviceScope.launch {
            wsClient.lastDisconnectReason.collectLatest { reason ->
                MuyuConnectionRepository.setDisconnectReason(reason, wsClient.lastDisconnectAtMillis.value)
                if (stopReason == null && foregroundStarted && isTerminalReason(reason)) {
                    disconnectAndStop(reason)
                }
            }
        }
        serviceScope.launch {
            wsClient.lastDisconnectAtMillis.collectLatest { at ->
                MuyuConnectionRepository.setDisconnectReason(wsClient.lastDisconnectReason.value, at)
            }
        }
        serviceScope.launch {
            wsClient.isReconnecting.collectLatest(MuyuConnectionRepository::setReconnecting)
        }
        serviceScope.launch {
            wsClient.lastReconnectResult.collectLatest(MuyuConnectionRepository::setLastReconnectResult)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activeStartId = maxOf(activeStartId, startId)
        when (intent?.action) {
            ACTION_START_CONNECT -> startSecureConnection(startId)
            ACTION_SEND_TAP -> {
                val timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
                if (foregroundStarted) {
                    enqueuePendingTap(timestamp)
                } else {
                    Log.d(TAG, "send tap ignored: secure service is not running")
                    MuyuConnectionRepository.setLastError("提醒未发送：安全连接服务未运行")
                    stopSelfResult(startId)
                }
            }
            ACTION_DISCONNECT -> disconnectAndStop(
                WebSocketClient.DisconnectReason.USER_ACTION,
                startId
            )
            ACTION_REFRESH_NOTIFICATION -> if (foregroundStarted) {
                updateForegroundNotification(MuyuConnectionRepository.connectionState.value)
            }
            else -> stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun startSecureConnection(startId: Int) {
        if (!foregroundStarted) {
            MuyuConnectionRepository.setServiceRunning(true)
            MuyuConnectionRepository.setPartnerOnline(false)
            try {
                startForeground(
                    NOTIFICATION_ID,
                    buildConnectionNotification(ConnectionState.CONNECTING)
                )
                foregroundStarted = true
            } catch (error: Exception) {
                Log.e(TAG, "failed to start foreground service", error)
                MuyuConnectionRepository.setLastError("无法启动前台连接通知")
                MuyuConnectionRepository.setServiceRunning(false)
                MuyuConnectionRepository.setReconnecting(false)
                MuyuConnectionRepository.setPartnerOnline(false)
                MuyuConnectionRepository.setConnectionState(ConnectionState.DISCONNECTED)
                MuyuConnectionRepository.setForegroundNotificationText("电子木鱼未连接")
                MuyuConnectionRepository.setDisconnectReason(
                    WebSocketClient.DisconnectReason.SERVICE_START_FAILED,
                    System.currentTimeMillis()
                )
                stopReason = WebSocketClient.DisconnectReason.SERVICE_START_FAILED
                stopSelfResult(startId)
                return
            }
        }
        serviceScope.launch { connectStoredPair() }
    }

    private suspend fun connectStoredPair() {
        val stored = localDataStore.storedPair.first()
        val relay = RelayConfiguration.resolve(localDataStore)
        if (stored == null || relay == null) {
            MuyuConnectionRepository.setLastError(
                if (stored == null) "尚未安全配对" else "未配置有效的生产 relay 地址"
            )
            disconnectAndStop(WebSocketClient.DisconnectReason.INVALID_CONFIG)
            return
        }
        val secrets = try {
            PairSecrets.fromJson(secretStore.decrypt(stored.encryptedSecrets))
        } catch (error: Exception) {
            Log.e(TAG, "secure pairing material unavailable", error)
            null
        }
        if (secrets == null) {
            MuyuConnectionRepository.setLastError("安全配对材料不可用，请重新配对")
            disconnectAndStop(WebSocketClient.DisconnectReason.INVALID_CONFIG)
            return
        }
        val send = Base64Url.decode(secrets.sendKey, 32)
        val receive = Base64Url.decode(secrets.receiveKey, 32)
        if (send == null || receive == null) {
            MuyuConnectionRepository.setLastError("安全配对材料损坏，请重新配对")
            disconnectAndStop(WebSocketClient.DisconnectReason.INVALID_CONFIG)
            return
        }
        sendKey?.fill(0)
        receiveKey?.fill(0)
        sendKey = send
        receiveKey = receive
        val metadata = stored.metadata
        val api = PairingApi(relay)
        stopReason = null
        wsClient.connect(
            SecureSocketCredentials(
                url = api.socketUrl(metadata.pairId),
                pairId = metadata.pairId,
                deviceId = metadata.deviceId,
                peerDeviceId = metadata.peerDeviceId,
                accessToken = secrets.accessToken,
                expectedSlot = metadata.slot
            )
        )
    }

    private suspend fun sendEncryptedTap(timestamp: Long): Boolean {
        if (MuyuConnectionRepository.connectionState.value != ConnectionState.CONNECTED) return false
        val stored = localDataStore.storedPair.first() ?: return false
        val key = sendKey ?: return false
        return try {
            val counter = localDataStore.nextSendCounter()
            val message = PairingCrypto.encryptTap(
                key,
                stored.metadata.pairId,
                stored.metadata.deviceId,
                counter,
                timestamp
            )
            wsClient.sendEncryptedTap(message)
        } catch (error: Exception) {
            Log.e(TAG, "encrypted tap send failed", error)
            false
        }
    }

    private fun enqueuePendingTap(timestamp: Long) {
        val result = pendingTaps.offer(timestamp, SystemClock.elapsedRealtime())
        reportDroppedPendingTaps(result.expiredCount, result.overflowCount)
        schedulePendingTapExpiryCheck()
        if (MuyuConnectionRepository.connectionState.value == ConnectionState.CONNECTED) {
            schedulePendingTapFlush()
        }
    }

    private fun schedulePendingTapFlush() {
        if (pendingTapFlushJob?.isActive == true || pendingTaps.isEmpty()) return
        pendingTapFlushJob = serviceScope.launch {
            try {
                while (MuyuConnectionRepository.connectionState.value == ConnectionState.CONNECTED) {
                    val next = pendingTaps.poll(SystemClock.elapsedRealtime())
                    reportDroppedPendingTaps(next.expiredCount, 0)
                    val tap = next.tap ?: break
                    if (!sendEncryptedTap(tap.timestampMillis)) {
                        pendingTaps.addFirst(tap)
                        break
                    }
                }
            } finally {
                pendingTapFlushJob = null
                schedulePendingTapExpiryCheck()
            }
        }
    }

    private fun schedulePendingTapExpiryCheck() {
        pendingTapExpiryJob?.cancel()
        pendingTapExpiryJob = null
        val expiresAt = pendingTaps.nextExpiryAtMillis() ?: return
        pendingTapExpiryJob = serviceScope.launch {
            delay((expiresAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            pendingTapExpiryJob = null
            val expired = pendingTaps.discardExpired(SystemClock.elapsedRealtime())
            reportDroppedPendingTaps(expired, 0)
            schedulePendingTapExpiryCheck()
        }
    }

    private fun reportDroppedPendingTaps(expiredCount: Int, overflowCount: Int) {
        val dropped = expiredCount + overflowCount
        if (dropped == 0) return
        val reason = if (overflowCount > 0) "发送队列已满" else "连接在 10 秒内未恢复"
        MuyuConnectionRepository.setLastError("部分提醒未发送：$reason（$dropped 次）")
    }

    private fun clearPendingTaps() {
        pendingTapFlushJob?.cancel()
        pendingTapFlushJob = null
        pendingTapExpiryJob?.cancel()
        pendingTapExpiryJob = null
        pendingTaps.clear()
    }

    private suspend fun decryptAndDeliver(message: EncryptedTap) {
        val key = receiveKey ?: return
        val timestamp = try {
            PairingCrypto.decryptTap(key, message)
        } catch (_: Exception) {
            Log.d(TAG, "ciphertext authentication failed")
            return
        }
        // Persist replay state only after AES-GCM authentication succeeds, so forged high counters
        // cannot advance the receiver window and suppress legitimate reminders.
        if (!localDataStore.acceptRemoteCounter(message.counter)) {
            Log.d(TAG, "replayed encrypted tap rejected")
            return
        }
        handleRemoteTap(timestamp)
    }

    private suspend fun handleRemoteTap(timestamp: Long) {
        val receivedAt = System.currentTimeMillis()
        val uiForeground = MuyuConnectionRepository.uiForeground.value
        MuyuConnectionRepository.recordReceivedTap(receivedAt, uiForeground)
        try {
            localDataStore.incrementReceivedCount()
        } catch (error: Exception) {
            Log.e(TAG, "failed to persist received count", error)
            MuyuConnectionRepository.setLastError("收到提醒，但计数保存失败")
        }
        if (!uiForeground && localDataStore.notificationEnabled.first()) {
            if (!NotificationHelper.sendMeritReminderNotification(applicationContext)) {
                MuyuConnectionRepository.setLastError("收到提醒，但系统通知未能送达")
            }
        }
        Log.d(TAG, "authenticated tap delivered ageMs=${(receivedAt - timestamp).coerceAtLeast(0)}")
    }

    private suspend fun clearRevokedPair() {
        sendKey?.fill(0)
        receiveKey?.fill(0)
        sendKey = null
        receiveKey = null
        try {
            localDataStore.clearSecurePair()
        } finally {
            secretStore.deleteKey()
        }
    }

    private fun disconnectAndStop(
        reason: WebSocketClient.DisconnectReason,
        startId: Int = activeStartId
    ) {
        if (stopReason != null) return
        stopReason = reason
        clearPendingTaps()
        wsClient.disconnect(reason)
        MuyuConnectionRepository.setServiceRunning(false)
        MuyuConnectionRepository.setReconnecting(false)
        MuyuConnectionRepository.setPartnerOnline(false)
        if (MuyuConnectionRepository.connectionState.value != ConnectionState.REVOKED) {
            MuyuConnectionRepository.setConnectionState(ConnectionState.DISCONNECTED)
        }
        MuyuConnectionRepository.setForegroundNotificationText("电子木鱼未连接")
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelfResult(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        clearPendingTaps()
        wsClient.shutdown(stopReason ?: WebSocketClient.DisconnectReason.SERVICE_DESTROYED)
        sendKey?.fill(0)
        receiveKey?.fill(0)
        sendKey = null
        receiveKey = null
        foregroundStarted = false
        MuyuConnectionRepository.setServiceRunning(false)
        MuyuConnectionRepository.setReconnecting(false)
        MuyuConnectionRepository.setPartnerOnline(false)
        if (MuyuConnectionRepository.connectionState.value != ConnectionState.REVOKED) {
            MuyuConnectionRepository.setConnectionState(ConnectionState.DISCONNECTED)
        }
        MuyuConnectionRepository.setForegroundNotificationText("电子木鱼未连接")
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.d(TAG, "foreground service timeout startId=$startId type=$fgsType")
        disconnectAndStop(WebSocketClient.DisconnectReason.SERVICE_TIMEOUT, startId)
    }

    private fun isTerminalReason(reason: WebSocketClient.DisconnectReason): Boolean = reason in setOf(
        WebSocketClient.DisconnectReason.INVALID_CONFIG,
        WebSocketClient.DisconnectReason.SERVER_REJECTED,
        WebSocketClient.DisconnectReason.AUTHENTICATION_FAILED
    )

    private fun createConnectionNotificationChannel() {
        val channel = NotificationChannel(
            CONNECTION_CHANNEL_ID,
            "木鱼安全连接",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "维持两台已配对设备之间的加密提醒连接" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun updateForegroundNotification(state: ConnectionState) {
        val text = foregroundText(state)
        MuyuConnectionRepository.setForegroundNotificationText(text)
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildConnectionNotification(state))
        } catch (error: Exception) {
            Log.e(TAG, "failed to update foreground notification", error)
            MuyuConnectionRepository.setLastError("安全连接仍在运行，但常驻通知更新失败")
        }
    }

    private fun buildConnectionNotification(state: ConnectionState): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CONNECTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_muyu)
            .setContentTitle("电子木鱼")
            .setContentText(foregroundText(state))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun foregroundText(state: ConnectionState): String = when (state) {
        ConnectionState.CONNECTING -> "正在建立安全连接"
        ConnectionState.AUTHENTICATING -> "正在认证已配对设备"
        ConnectionState.CONNECTED -> if (MuyuConnectionRepository.partnerOnline.value) {
            "已安全连接，对方在线"
        } else {
            "已安全连接，对方离线"
        }
        ConnectionState.RECONNECTING -> "网络变化，正在安全重连"
        ConnectionState.AUTHENTICATION_FAILED -> "设备认证失败"
        ConnectionState.REVOKED -> "安全配对已撤销"
        else -> "电子木鱼未连接"
    }

    companion object {
        const val ACTION_START_CONNECT = "app.electronicmuyu.android.action.START_CONNECT"
        const val ACTION_DISCONNECT = "app.electronicmuyu.android.action.DISCONNECT"
        const val ACTION_SEND_TAP = "app.electronicmuyu.android.action.SEND_TAP"
        const val ACTION_REFRESH_NOTIFICATION = "app.electronicmuyu.android.action.REFRESH_NOTIFICATION"
        const val EXTRA_TIMESTAMP = "timestamp"
        const val CONNECTION_CHANNEL_ID = "muyu_connection"
        private const val NOTIFICATION_ID = 2001
        private const val TAG = "ElectronicMuyu"
    }
}
