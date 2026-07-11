from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "electronic-muyu" / "app"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8", newline="\n")


view_model = APP / "src/main/java/app/electronicmuyu/android/viewmodel/MainViewModel.kt"
replace_once(
    view_model,
    '''        if (connectionState.value == ConnectionState.CONNECTED) {
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
''',
    '''        if (_storedPair.value != null && MuyuConnectionRepository.isServiceRunning.value) {
            val context = getApplication<Application>()
            try {
                context.startService(
                    Intent(context, MuyuForegroundService::class.java).apply {
                        action = MuyuForegroundService.ACTION_SEND_TAP
                        putExtra(MuyuForegroundService.EXTRA_TIMESTAMP, timestamp)
                    }
                )
            } catch (_: Exception) {
                MuyuConnectionRepository.setLastError("提醒未发送：安全连接服务不可用")
            }
        }
'''
)

service = APP / "src/main/java/app/electronicmuyu/android/service/MuyuForegroundService.kt"
replace_once(service, "import android.os.IBinder\n", "import android.os.IBinder\nimport android.os.SystemClock\n")
replace_once(
    service,
    '''import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
''',
    '''import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
'''
)
replace_once(
    service,
    '''    private var stopReason: WebSocketClient.DisconnectReason? = null
    private var activeStartId = 0
''',
    '''    private var stopReason: WebSocketClient.DisconnectReason? = null
    private var activeStartId = 0
    private val pendingTaps = PendingTapQueue()
    private var pendingTapFlushJob: Job? = null
    private var pendingTapExpiryJob: Job? = null
'''
)
replace_once(
    service,
    '''                MuyuConnectionRepository.setConnectionState(state)
                if (foregroundStarted) updateForegroundNotification(state)
''',
    '''                MuyuConnectionRepository.setConnectionState(state)
                if (foregroundStarted) updateForegroundNotification(state)
                if (state == ConnectionState.CONNECTED) schedulePendingTapFlush()
'''
)
replace_once(
    service,
    '''            ACTION_SEND_TAP -> {
                if (
                    foregroundStarted &&
                    MuyuConnectionRepository.connectionState.value == ConnectionState.CONNECTED
                ) {
                    val timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
                    serviceScope.launch { sendEncryptedTap(timestamp) }
                } else {
                    Log.d(TAG, "send tap ignored: secure service is not connected")
                    if (!foregroundStarted) stopSelfResult(startId)
                }
            }
''',
    '''            ACTION_SEND_TAP -> {
                val timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
                if (foregroundStarted) {
                    enqueuePendingTap(timestamp)
                } else {
                    Log.d(TAG, "send tap ignored: secure service is not running")
                    MuyuConnectionRepository.setLastError("提醒未发送：安全连接服务未运行")
                    stopSelfResult(startId)
                }
            }
'''
)
replace_once(
    service,
    '''    private suspend fun sendEncryptedTap(timestamp: Long) {
        if (MuyuConnectionRepository.connectionState.value != ConnectionState.CONNECTED) return
        val stored = localDataStore.storedPair.first() ?: return
        val key = sendKey ?: return
        try {
            val counter = localDataStore.nextSendCounter()
            val message = PairingCrypto.encryptTap(
                key,
                stored.metadata.pairId,
                stored.metadata.deviceId,
                counter,
                timestamp
            )
            if (!wsClient.sendEncryptedTap(message)) {
                MuyuConnectionRepository.setLastError("提醒未发送：安全连接不可用")
            }
        } catch (error: Exception) {
            Log.e(TAG, "encrypted tap send failed", error)
            MuyuConnectionRepository.setLastError("提醒加密或发送失败")
        }
    }

''',
    '''    private suspend fun sendEncryptedTap(timestamp: Long): Boolean {
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

'''
)
replace_once(
    service,
    '''        if (stopReason != null) return
        stopReason = reason
        wsClient.disconnect(reason)
''',
    '''        if (stopReason != null) return
        stopReason = reason
        clearPendingTaps()
        wsClient.disconnect(reason)
'''
)
replace_once(
    service,
    '''    override fun onDestroy() {
        wsClient.shutdown(stopReason ?: WebSocketClient.DisconnectReason.SERVICE_DESTROYED)
''',
    '''    override fun onDestroy() {
        clearPendingTaps()
        wsClient.shutdown(stopReason ?: WebSocketClient.DisconnectReason.SERVICE_DESTROYED)
'''
)

manual = ROOT / "electronic-muyu" / "SECURE_PAIRING_MANUAL_TEST.md"
replace_once(
    manual,
    '''- [ ] 飞行模式 2 分钟后恢复，退避没有形成高频循环。
- [ ] 模拟 4408/HTTP 429 后至少冷却 15 秒并自动恢复，不需要用户重新点连接。
''',
    '''- [ ] 飞行模式 2 分钟后恢复，退避没有形成高频循环。
- [ ] 连接处于“正在重连”时快速点击 10 次；10 秒内恢复后按原顺序全部送达，不丢前几次。
- [ ] 断网超过 10 秒时，暂存提醒被丢弃并明确显示“部分提醒未发送”。
- [ ] 模拟 4408/HTTP 429 后至少冷却 15 秒并自动恢复，不需要用户重新点连接。
'''
)

print("pending tap queue integration applied")
