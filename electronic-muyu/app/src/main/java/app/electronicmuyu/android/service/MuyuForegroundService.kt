package app.electronicmuyu.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import app.electronicmuyu.android.MainActivity
import app.electronicmuyu.android.R
import app.electronicmuyu.android.data.LocalDataStore
import app.electronicmuyu.android.model.ConnectionState
import app.electronicmuyu.android.network.WebSocketClient
import app.electronicmuyu.android.notification.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MuyuForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var localDataStore: LocalDataStore
    private lateinit var wsClient: WebSocketClient

    private var currentServerUrl = ""
    private var currentPairId = ""
    private var currentDeviceId = ""
    private var stopReason: WebSocketClient.DisconnectReason? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        localDataStore = LocalDataStore(applicationContext)
        wsClient = WebSocketClient(serviceScope)
        NotificationHelper.createNotificationChannel(applicationContext)
        createConnectionNotificationChannel()

        wsClient.onTapReceived = {
            serviceScope.launch {
                handleRemoteTap()
            }
        }

        serviceScope.launch {
            wsClient.connectionState.collectLatest { state ->
                MuyuConnectionRepository.setConnectionState(state)
                if (foregroundStarted) {
                    updateForegroundNotification(state)
                }
            }
        }
        serviceScope.launch {
            wsClient.lastError.collectLatest { error ->
                MuyuConnectionRepository.setLastError(error)
            }
        }
        serviceScope.launch {
            wsClient.lastDisconnectReason.collectLatest { reason ->
                MuyuConnectionRepository.setDisconnectReason(
                    reason = reason,
                    atMillis = wsClient.lastDisconnectAtMillis.value
                )
                if (stopReason == null && foregroundStarted && isTerminalReason(reason)) {
                    disconnectAndStop(reason)
                }
            }
        }
        serviceScope.launch {
            wsClient.lastDisconnectAtMillis.collectLatest { atMillis ->
                MuyuConnectionRepository.setDisconnectReason(
                    reason = wsClient.lastDisconnectReason.value,
                    atMillis = atMillis
                )
            }
        }
        serviceScope.launch {
            wsClient.isReconnecting.collectLatest {
                MuyuConnectionRepository.setReconnecting(it)
            }
        }
        serviceScope.launch {
            wsClient.lastReconnectResult.collectLatest {
                MuyuConnectionRepository.setLastReconnectResult(it)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CONNECT -> {
                readConnectionExtras(intent)
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        buildConnectionNotification(ConnectionState.CONNECTING)
                    )
                } catch (error: Exception) {
                    Log.e(TAG, "failed to start foreground connection service", error)
                    MuyuConnectionRepository.setLastError("无法启动前台连接通知")
                    MuyuConnectionRepository.setDisconnectReason(
                        WebSocketClient.DisconnectReason.SERVICE_START_FAILED,
                        System.currentTimeMillis()
                    )
                    stopReason = WebSocketClient.DisconnectReason.SERVICE_START_FAILED
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                foregroundStarted = true
                MuyuConnectionRepository.setServiceRunning(true)
                connectIfConfigValid()
            }

            ACTION_SEND_TAP -> {
                if (
                    foregroundStarted &&
                    MuyuConnectionRepository.connectionState.value == ConnectionState.CONNECTED
                ) {
                    val timestamp = intent.getLongExtra(
                        EXTRA_TIMESTAMP,
                        System.currentTimeMillis()
                    )
                    if (!wsClient.sendTap(timestamp)) {
                        MuyuConnectionRepository.setLastError("提醒发送失败，请检查连接状态")
                    }
                } else {
                    Log.d(TAG, "send tap ignored: service is not connected")
                }
            }

            ACTION_DISCONNECT -> {
                disconnectAndStop(WebSocketClient.DisconnectReason.USER_ACTION)
            }

            ACTION_REFRESH_NOTIFICATION -> {
                if (foregroundStarted) {
                    updateForegroundNotification(MuyuConnectionRepository.connectionState.value)
                }
            }

            else -> {
                Log.d(TAG, "service started without a supported action; stopping")
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val finalReason = stopReason ?: WebSocketClient.DisconnectReason.SERVICE_DESTROYED
        wsClient.shutdown(finalReason)
        foregroundStarted = false
        MuyuConnectionRepository.setServiceRunning(false)
        MuyuConnectionRepository.setReconnecting(false)
        MuyuConnectionRepository.setConnectionState(ConnectionState.DISCONNECTED)
        MuyuConnectionRepository.setForegroundNotificationText("电子木鱼未连接")
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.d(TAG, "foreground service timeout startId=$startId type=$fgsType")
        disconnectAndStop(WebSocketClient.DisconnectReason.SERVICE_TIMEOUT)
    }

    private fun readConnectionExtras(intent: Intent) {
        currentServerUrl = intent.getStringExtra(EXTRA_SERVER_URL).orEmpty()
        currentPairId = intent.getStringExtra(EXTRA_PAIR_ID).orEmpty()
        currentDeviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()
    }

    private fun connectIfConfigValid() {
        if (
            currentDeviceId.isBlank() ||
            currentServerUrl.isBlank() ||
            currentPairId.isBlank()
        ) {
            Log.e(TAG, "invalid foreground service websocket config")
            MuyuConnectionRepository.setLastError("连接配置无效")
            disconnectAndStop(WebSocketClient.DisconnectReason.INVALID_CONFIG)
            return
        }

        stopReason = null
        wsClient.connect(
            url = currentServerUrl,
            deviceId = currentDeviceId,
            pairId = currentPairId
        )
    }

    private suspend fun handleRemoteTap() {
        val receivedAtMillis = System.currentTimeMillis()
        val appInForeground = MuyuConnectionRepository.appForeground.value

        // 先记录 UI 事件，避免 DataStore I/O 延迟前台声音、震动和 Snackbar。
        MuyuConnectionRepository.recordReceivedTap(
            receivedAtMillis = receivedAtMillis,
            enqueueForForegroundUi = appInForeground
        )

        try {
            localDataStore.incrementReceivedCount()
        } catch (error: Exception) {
            Log.e(TAG, "failed to persist received count", error)
            MuyuConnectionRepository.setLastError("收到提醒，但计数保存失败")
        }

        if (appInForeground) {
            Log.d(TAG, "foreground tap received, queued for in-app feedback")
            return
        }

        val notificationEnabled = try {
            localDataStore.notificationEnabled.first()
        } catch (error: Exception) {
            Log.e(TAG, "failed to read notification preference", error)
            false
        }

        if (notificationEnabled) {
            Log.d(TAG, "background tap received, sending notification")
            val sent = NotificationHelper.sendMeritReminderNotification(applicationContext)
            if (!sent) {
                MuyuConnectionRepository.setLastError("收到提醒，但系统通知未能送达")
            }
        } else {
            Log.d(TAG, "background tap received while notification preference is disabled")
        }
    }

    private fun disconnectAndStop(reason: WebSocketClient.DisconnectReason) {
        if (stopReason != null) return
        stopReason = reason
        wsClient.disconnect(reason)
        MuyuConnectionRepository.setServiceRunning(false)
        MuyuConnectionRepository.setReconnecting(false)
        MuyuConnectionRepository.setConnectionState(ConnectionState.DISCONNECTED)
        MuyuConnectionRepository.setForegroundNotificationText("电子木鱼未连接")
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    private fun isTerminalReason(reason: WebSocketClient.DisconnectReason): Boolean {
        return reason == WebSocketClient.DisconnectReason.INVALID_CONFIG ||
            reason == WebSocketClient.DisconnectReason.SERVER_REJECTED ||
            reason == WebSocketClient.DisconnectReason.RATE_LIMITED
    }

    private fun createConnectionNotificationChannel() {
        val channel = NotificationChannel(
            CONNECTION_CHANNEL_ID,
            CONNECTION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "电子木鱼 WebSocket 连接状态"
        }
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun updateForegroundNotification(state: ConnectionState) {
        val text = foregroundTextForState(state)
        MuyuConnectionRepository.setForegroundNotificationText(text)
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            notificationManager.notify(NOTIFICATION_ID, buildConnectionNotification(state))
        } catch (error: Exception) {
            // 常驻通知更新失败不应中断仍然健康的 WebSocket。
            Log.e(TAG, "failed to update foreground notification", error)
            MuyuConnectionRepository.setLastError("连接仍在运行，但常驻通知更新失败")
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
            .setContentText(foregroundTextForState(state))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun foregroundTextForState(state: ConnectionState): String {
        return when (state) {
            ConnectionState.CONNECTING -> "电子木鱼正在连接"
            ConnectionState.CONNECTED -> "电子木鱼已连接"
            ConnectionState.RECONNECTING -> "电子木鱼正在重连"
            else -> "电子木鱼未连接"
        }
    }

    companion object {
        const val ACTION_START_CONNECT = "app.electronicmuyu.android.action.START_CONNECT"
        const val ACTION_DISCONNECT = "app.electronicmuyu.android.action.DISCONNECT"
        const val ACTION_SEND_TAP = "app.electronicmuyu.android.action.SEND_TAP"
        const val ACTION_REFRESH_NOTIFICATION = "app.electronicmuyu.android.action.REFRESH_NOTIFICATION"

        const val EXTRA_SERVER_URL = "serverUrl"
        const val EXTRA_PAIR_ID = "pairId"
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_TIMESTAMP = "timestamp"

        const val CONNECTION_CHANNEL_ID = "muyu_connection"
        private const val CONNECTION_CHANNEL_NAME = "木鱼连接"
        private const val NOTIFICATION_ID = 2001
        private const val TAG = "ElectronicMuyu"
    }
}
