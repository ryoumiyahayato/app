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
import androidx.compose.material3.HorizontalDivider
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
import app.electronicmuyu.android.model.ConnectionState
import app.electronicmuyu.android.pairing.PairMetadata
import app.electronicmuyu.android.pairing.PairingStage
import app.electronicmuyu.android.pairing.PairingUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    soundEnabled: Boolean,
    vibrationEnabled: Boolean,
    notificationEnabled: Boolean,
    notificationPermissionGranted: Boolean,
    notificationDeliveryStatus: String,
    connectionState: ConnectionState,
    partnerOnline: Boolean,
    pairing: PairingUiState,
    storedPair: PairMetadata?,
    allowRelayOverride: Boolean,
    debugRelayOverride: String,
    lastError: String,
    lastDisconnectReason: String,
    lastDisconnectAtMillis: Long?,
    isAppInForeground: Boolean,
    isReconnecting: Boolean,
    lastReconnectResult: String,
    isServiceRunning: Boolean,
    foregroundNotificationText: String,
    onSoundToggle: (Boolean) -> Unit,
    onVibrationToggle: (Boolean) -> Unit,
    onNotificationToggle: (Boolean) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onSendTestNotification: () -> Unit,
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
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(1.dp))
            PairingCard(
                pairing = pairing,
                storedPair = storedPair,
                connectionState = connectionState,
                partnerOnline = partnerOnline,
                isServiceRunning = isServiceRunning,
                onCreate = onCreateInvite,
                onScan = onScanInvite,
                onCancel = onCancelInvite,
                onRegenerate = onRegenerateInvite,
                onConfirm = onConfirmSas,
                onReject = onRejectSas,
                onRevoke = { confirmRevoke = true },
                onConnect = onConnect,
                onDisconnect = onDisconnect
            )
            if (pairing.legacyDetected) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        "旧连接方式不再安全，请重新扫码配对。旧 room 不会自动连接或转换为新凭据。",
                        Modifier.padding(16.dp)
                    )
                }
            }
            if (lastError.isNotBlank()) {
                Text(lastError, color = MaterialTheme.colorScheme.error)
            }
            PreferenceCard(
                sound = soundEnabled,
                vibration = vibrationEnabled,
                notifications = notificationEnabled,
                notificationPermission = notificationPermissionGranted,
                notificationDeliveryStatus = notificationDeliveryStatus,
                onSound = onSoundToggle,
                onVibration = onVibrationToggle,
                onNotification = onNotificationToggle,
                onOpenNotificationSettings = onOpenNotificationSettings,
                onSendTestNotification = onSendTestNotification
            )
            ConnectionDiagnosticsCard(
                connectionState = connectionState,
                partnerOnline = partnerOnline,
                lastDisconnectReason = lastDisconnectReason,
                lastDisconnectAtMillis = lastDisconnectAtMillis,
                isAppInForeground = isAppInForeground,
                isReconnecting = isReconnecting,
                lastReconnectResult = lastReconnectResult,
                isServiceRunning = isServiceRunning,
                foregroundNotificationText = foregroundNotificationText
            )
            if (allowRelayOverride) {
                DeveloperRelayCard(debugRelayOverride, onSaveDebugRelay)
            }
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("数据", fontWeight = FontWeight.Bold)
                    Text("私钥、access token 和消息密钥由 Android Keystore 包装保护，不进入普通 DataStore 或备份。")
                    OutlinedButton(onClick = onClearCounts) { Text("清空计数") }
                }
            }
            Text(
                "版本 0.7.0 · 安全扫码配对协议 v1",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text("解除安全配对？") },
            text = { Text("两台设备的当前凭据将立即失效。之后需要重新扫码配对。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRevoke = false
                    onRevokePair()
                }) { Text("解除配对") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevoke = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun PairingCard(
    pairing: PairingUiState,
    storedPair: PairMetadata?,
    connectionState: ConnectionState,
    partnerOnline: Boolean,
    isServiceRunning: Boolean,
    onCreate: () -> Unit,
    onScan: () -> Unit,
    onCancel: () -> Unit,
    onRegenerate: () -> Unit,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onRevoke: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pairing.expiresAt) {
        while (pairing.expiresAt != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "安全配对",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            when (pairing.stage) {
                PairingStage.UNPAIRED, PairingStage.FAILED -> {
                    Text(
                        if (pairing.stage == PairingStage.FAILED) pairing.message
                        else "二维码只能使用一次，并会在 2 分钟内过期。"
                    )
                    Button(onClick = onCreate, enabled = !pairing.busy) {
                        Text("创建配对二维码")
                    }
                    OutlinedButton(onClick = onScan, enabled = !pairing.busy) {
                        Text("扫描对方二维码")
                    }
                }
                PairingStage.CREATING_INVITE, PairingStage.JOINING -> {
                    Text(pairing.message.ifBlank { "正在建立安全连接" })
                }
                PairingStage.WAITING_FOR_SCAN -> {
                    pairing.qrPayload?.let {
                        PairingQrCode(
                            it,
                            Modifier
                                .size(280.dp)
                                .align(Alignment.CenterHorizontally)
                        )
                    }
                    val remaining = ((pairing.expiresAt ?: now) - now).coerceAtLeast(0) / 1_000
                    Text("剩余有效时间：${remaining} 秒")
                    Text("等待对方加入…")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onCancel) { Text("取消邀请") }
                        Button(onClick = onRegenerate) { Text("重新生成") }
                    }
                }
                PairingStage.WAITING_FOR_SAS, PairingStage.WAITING_FOR_PEER_CONFIRMATION -> {
                    Text("安全码", style = MaterialTheme.typography.labelLarge)
                    Text(
                        pairing.sas.orEmpty(),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text("请通过当面或语音核对。只有双方都确认后才会保存长期配对。")
                    if (pairing.stage == PairingStage.WAITING_FOR_SAS) {
                        Button(onClick = onConfirm, enabled = !pairing.busy) {
                            Text("代码一致")
                        }
                        OutlinedButton(onClick = onReject, enabled = !pairing.busy) {
                            Text("代码不一致")
                        }
                    } else {
                        Text("已确认，等待对方确认…")
                    }
                }
                PairingStage.PAIRED -> {
                    Text(
                        "已安全配对",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text("对方设备：${storedPair?.peerDeviceName ?: pairing.peerName.orEmpty()}")
                    Text("连接状态：${connectionLabel(connectionState, partnerOnline)}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isServiceRunning) {
                            OutlinedButton(onClick = onDisconnect) { Text("断开连接") }
                        } else {
                            Button(onClick = onConnect) { Text("连接") }
                        }
                        OutlinedButton(onClick = onRevoke) { Text("解除配对") }
                    }
                }
                PairingStage.REVOKED -> Text("配对已撤销，请重新配对")
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
    notificationDeliveryStatus: String,
    onSound: (Boolean) -> Unit,
    onVibration: (Boolean) -> Unit,
    onNotification: (Boolean) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onSendTestNotification: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("提醒反馈", fontWeight = FontWeight.Bold)
            Toggle("声音", sound, onSound)
            Toggle("振动", vibration, onVibration)
            Toggle("后台和锁屏通知", notifications, onNotification)
            HorizontalDivider()
            Text(
                if (notificationPermission) "通知权限：已授权" else "通知权限：未授权",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "实际送达状态：$notificationDeliveryStatus",
                style = MaterialTheme.typography.bodySmall,
                color = if (notificationDeliveryStatus == "通知可用") {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            OutlinedButton(
                onClick = onOpenNotificationSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("打开系统通知设置")
            }
            Button(
                onClick = onSendTestNotification,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("发送测试通知")
            }
        }
    }
}

@Composable
private fun ConnectionDiagnosticsCard(
    connectionState: ConnectionState,
    partnerOnline: Boolean,
    lastDisconnectReason: String,
    lastDisconnectAtMillis: Long?,
    isAppInForeground: Boolean,
    isReconnecting: Boolean,
    lastReconnectResult: String,
    isServiceRunning: Boolean,
    foregroundNotificationText: String
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("连接诊断", fontWeight = FontWeight.Bold)
            DiagnosticRow("安全连接状态", connectionState.name.lowercase())
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
private fun DiagnosticRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            modifier = Modifier.padding(start = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
private fun DeveloperRelayCard(current: String, onSave: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var value by remember(current) { mutableStateOf(current) }
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = { expanded = !expanded }) {
                Text("开发者 / 高级设置")
            }
            if (expanded) {
                Text("仅 Debug 构建可覆盖 relay。二维码不会携带服务器地址。")
                OutlinedTextField(
                    value,
                    { value = it },
                    label = { Text("调试 relay base URL") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = { onSave(value) }) { Text("保存调试地址") }
            }
        }
    }
}

private fun connectionLabel(state: ConnectionState, partnerOnline: Boolean): String = when (state) {
    ConnectionState.CONNECTING -> "正在连接"
    ConnectionState.AUTHENTICATING -> "正在认证"
    ConnectionState.CONNECTED -> if (partnerOnline) "已连接，对方在线" else "已连接，对方离线"
    ConnectionState.RECONNECTING -> "正在重连"
    ConnectionState.AUTHENTICATION_FAILED -> "认证失败"
    ConnectionState.REVOKED -> "配对已撤销"
    else -> "已配对但未连接"
}

private fun formatDisconnectTime(value: Long?): String {
    if (value == null) return "none"
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))
}
