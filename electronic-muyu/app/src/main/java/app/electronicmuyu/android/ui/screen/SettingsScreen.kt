package app.electronicmuyu.android.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.electronicmuyu.android.BuildConfig
import app.electronicmuyu.android.data.LocalDataStore
import app.electronicmuyu.android.model.ConnectionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private const val MAX_ROOM_ID_LENGTH = 64
private const val MAX_SERVER_URL_LENGTH = 2048
private val DEFAULT_SERVER_URL = if (BuildConfig.DEBUG) "ws://10.0.2.2:8443" else ""
private const val DEFAULT_ROOM_ID = "test-room"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    soundEnabled: Boolean,
    vibrationEnabled: Boolean,
    notificationEnabled: Boolean,
    notificationPermissionGranted: Boolean,
    connectionState: ConnectionState,
    partnerOnline: Boolean,
    wsEnabled: Boolean,
    lastDisconnectReason: String,
    lastDisconnectAtMillis: Long?,
    isAppInForeground: Boolean,
    isReconnecting: Boolean,
    lastReconnectResult: String,
    isServiceRunning: Boolean,
    foregroundNotificationText: String,
    serverUrl: String,
    roomId: String,
    deviceIdDisplay: String,
    onSoundToggle: (Boolean) -> Unit,
    onVibrationToggle: (Boolean) -> Unit,
    onNotificationToggle: (Boolean) -> Unit,
    onCreateInvite: () -> Unit,
    onScanInvite: () -> Unit,
    onCancelInvite: () -> Unit,
    onRegenerateInvite: () -> Unit,
    onConfirmSas: () -> Unit,
    onRejectSas: () -> Unit,
    onRevokePair: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSaveDebugRelay: (String) -> Unit,
    onClearCounts: () -> Unit,
    onNavigateBack: () -> Unit
) {
    var confirmRevoke by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            FeedbackCard(
                soundEnabled = soundEnabled,
                vibrationEnabled = vibrationEnabled,
                onSoundToggle = onSoundToggle,
                onVibrationToggle = onVibrationToggle
            )

            Spacer(modifier = Modifier.height(16.dp))

            NotificationCard(
                notificationEnabled = notificationEnabled,
                notificationPermissionGranted = notificationPermissionGranted,
                notificationDeliveryStatus = notificationDeliveryStatus,
                onNotificationToggle = onNotificationToggle,
                onOpenNotificationSettings = onOpenNotificationSettings,
                onSendTestNotification = onSendTestNotification
            )

            Spacer(modifier = Modifier.height(16.dp))

            ConnectionConfigCard(
                serverUrl = serverUrl,
                roomId = roomId,
                deviceIdDisplay = deviceIdDisplay,
                onSaveConfig = onSaveConfig,
                onResetDefaults = onResetDefaults
            )

            Spacer(modifier = Modifier.height(16.dp))

            ConnectionCard(
                connectionState = connectionState,
                partnerOnline = partnerOnline,
                wsEnabled = wsEnabled,
                lastDisconnectReason = lastDisconnectReason,
                lastDisconnectAtMillis = lastDisconnectAtMillis,
                isAppInForeground = isAppInForeground,
                isReconnecting = isReconnecting,
                lastReconnectResult = lastReconnectResult,
                isServiceRunning = isServiceRunning,
                foregroundNotificationText = foregroundNotificationText,
                onConnect = onConnect,
                onDisconnect = onDisconnect
            )

            Spacer(modifier = Modifier.height(16.dp))

            ActionsCard(onClearCounts = onClearCounts)

            Spacer(modifier = Modifier.height(16.dp))

            AboutCard()

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun FeedbackCard(
    soundEnabled: Boolean,
    vibrationEnabled: Boolean,
    onSoundToggle: (Boolean) -> Unit,
    onVibrationToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            SectionTitle("反馈")
            Spacer(modifier = Modifier.height(8.dp))

            SettingSwitchRow(
                label = "声音",
                checked = soundEnabled,
                onCheckedChange = onSoundToggle
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingSwitchRow(
                label = "震动",
                checked = vibrationEnabled,
                onCheckedChange = onVibrationToggle
            )
        }
    }
}

@Composable
private fun NotificationCard(
    notificationEnabled: Boolean,
    notificationPermissionGranted: Boolean,
    notificationDeliveryStatus: String,
    onNotificationToggle: (Boolean) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onSendTestNotification: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Spacer(Modifier.height(1.dp))
            PairingCard(
                pairing, storedPair, connectionState,
                onCreateInvite, onScanInvite, onCancelInvite, onRegenerateInvite,
                onConfirmSas, onRejectSas,
                onRevoke = { confirmRevoke = true }, onConnect, onDisconnect
            )
            if (pairing.legacyDetected) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text("旧连接方式不再安全，请重新扫码配对。旧 room 不会自动连接或转换为新凭据。", Modifier.padding(16.dp))
                }
            }
            if (lastError.isNotBlank()) {
                Text(lastError, color = MaterialTheme.colorScheme.error)
            }
            PreferenceCard(
                soundEnabled,
                vibrationEnabled,
                notificationEnabled,
                notificationPermissionGranted,
                onSoundToggle,
                onVibrationToggle,
                onNotificationToggle
            )
            if (allowRelayOverride) {
                DeveloperRelayCard(debugRelayOverride, onSaveDebugRelay)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "修改配置后，请断开并重新连接以生效",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Text(
                text = if (BuildConfig.DEBUG) "Debug 可使用 ws://；正式版本仅允许 wss://" else "正式版本仅允许 wss:// 加密连接",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    coroutineScope.launch {
                        isSaving = true
                        savedMessage = null
                        try {
                            if (onResetDefaults()) {
                                localServerUrl = DEFAULT_SERVER_URL
                                localRoomId = DEFAULT_ROOM_ID
                                serverUrlError = null
                                roomIdError = null
                                savedMessage = "已恢复默认配置"
                            } else {
                                serverUrlError = "恢复默认配置失败，请稍后重试"
                            }
                        } finally {
                            isSaving = false
                        }
                    }
                },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("恢复默认")
            }
            Text("版本 0.7.0 · 安全扫码配对协议 v1", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text("解除安全配对？") },
            text = { Text("两台设备的当前凭据将立即失效。之后需要重新扫码配对。") },
            confirmButton = {
                TextButton(onClick = { confirmRevoke = false; onRevokePair() }) { Text("解除配对") }
            },
            dismissButton = { TextButton(onClick = { confirmRevoke = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun PairingCard(
    pairing: PairingUiState,
    storedPair: PairMetadata?,
    connectionState: ConnectionState,
    partnerOnline: Boolean,
    wsEnabled: Boolean,
    lastDisconnectReason: String,
    lastDisconnectAtMillis: Long?,
    isAppInForeground: Boolean,
    isReconnecting: Boolean,
    lastReconnectResult: String,
    isServiceRunning: Boolean,
    foregroundNotificationText: String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pairing.expiresAt) {
        while (pairing.expiresAt != null) { now = System.currentTimeMillis(); delay(1_000) }
    }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("安全配对", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            when (pairing.stage) {
                PairingStage.UNPAIRED, PairingStage.FAILED -> {
                    Text(if (pairing.stage == PairingStage.FAILED) pairing.message else "二维码只能使用一次，并会在 2 分钟内过期。")
                    Button(onClick = onCreate, enabled = !pairing.busy) { Text("创建配对二维码") }
                    OutlinedButton(onClick = onScan, enabled = !pairing.busy) { Text("扫描对方二维码") }
                }
                PairingStage.CREATING_INVITE, PairingStage.JOINING -> Text(pairing.message.ifBlank { "正在建立安全连接" })
                PairingStage.WAITING_FOR_SCAN -> {
                    pairing.qrPayload?.let { PairingQrCode(it, Modifier.size(280.dp).align(Alignment.CenterHorizontally)) }
                    val remaining = ((pairing.expiresAt ?: now) - now).coerceAtLeast(0) / 1_000
                    Text("剩余有效时间：${remaining} 秒")
                    Text("等待对方加入…")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onCancel) { Text("取消邀请") }
                        Button(onClick = onRegenerate) { Text("重新生成") }
                    }
                }
            }

            if (isReconnecting) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "断线自动重连中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Text(
                text = "连接诊断",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            DiagnosticRow("WebSocket 状态", connectionDebugStateName(connectionState))
            DiagnosticRow("对方在线", partnerOnline.toString())
            DiagnosticRow("最近断开原因", lastDisconnectReason)
            DiagnosticRow("最近断开时间", formatDisconnectTime(lastDisconnectAtMillis))
            DiagnosticRow("App 前后台", if (isAppInForeground) "foreground" else "background")
            DiagnosticRow("正在自动重连", isReconnecting.toString())
            DiagnosticRow("最近重连结果", lastReconnectResult)
            DiagnosticRow("Foreground Service", if (isServiceRunning) "running" else "stopped")
            DiagnosticRow("常驻通知状态", foregroundNotificationText)
        }
    }
}

@Composable
private fun ActionsCard(onClearCounts: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            SectionTitle("操作")
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onClearCounts,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("清空本机计数（功德 + 收到提醒）")
            }
        }
    }
}

@Composable
private fun PreferenceCard(
    sound: Boolean,
    vibration: Boolean,
    notifications: Boolean,
    notificationPermission: Boolean,
    onSound: (Boolean) -> Unit,
    onVibration: (Boolean) -> Unit,
    onNotification: (Boolean) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("提醒反馈", fontWeight = FontWeight.Bold)
            Toggle("声音", sound, onSound)
            Toggle("振动", vibration, onVibration)
            Toggle("后台和锁屏通知", notifications, onNotification)
            if (!notificationPermission) Text("系统通知权限尚未授予", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
}

private fun validateServerUrl(serverUrl: String): String? {
    if (serverUrl.isBlank()) return "服务器地址不能为空"
    if (serverUrl.length > MAX_SERVER_URL_LENGTH) {
        return "服务器地址不能超过 $MAX_SERVER_URL_LENGTH 个字符"
    }

    return try {
        val uri = serverUrl.toUri()
        val scheme = uri.scheme?.lowercase()
        when {
            scheme != "ws" && scheme != "wss" -> "必须以 ws:// 或 wss:// 开头"
            !BuildConfig.DEBUG && scheme != "wss" -> "正式版本仅允许 wss://"
            uri.host.isNullOrBlank() -> "服务器地址必须包含主机名或 IP"
            !uri.encodedUserInfo.isNullOrEmpty() -> "服务器地址不能包含用户名或密码"
            uri.fragment != null -> "服务器地址不能包含 #fragment"
            !LocalDataStore.canStoreConnectionUrl(serverUrl) ->
                "服务器地址不能包含 token、session、密码等敏感凭据"
            uri.port == 0 || uri.port > 65535 -> "服务器端口必须为 1-65535"
            else -> null
        }
    }
}

private fun connectionLabel(state: ConnectionState): String = when (state) {
    ConnectionState.CONNECTING -> "正在连接"
    ConnectionState.AUTHENTICATING -> "正在认证"
    ConnectionState.CONNECTED -> "已连接"
    ConnectionState.RECONNECTING -> "正在重连"
    ConnectionState.AUTHENTICATION_FAILED -> "认证失败"
    ConnectionState.REVOKED -> "配对已撤销"
    else -> "已配对但未连接"
}
