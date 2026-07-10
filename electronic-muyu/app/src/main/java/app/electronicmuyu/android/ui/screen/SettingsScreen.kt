package app.electronicmuyu.android.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.electronicmuyu.android.data.LocalDataStore
import app.electronicmuyu.android.model.ConnectionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private const val MAX_ROOM_ID_LENGTH = 64
private const val MAX_SERVER_URL_LENGTH = 2048
private const val DEFAULT_SERVER_URL = "ws://192.168.96.33:8443"
private const val DEFAULT_ROOM_ID = "test-room"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    soundEnabled: Boolean,
    vibrationEnabled: Boolean,
    notificationEnabled: Boolean,
    notificationPermissionGranted: Boolean,
    notificationDeliveryStatus: String,
    connectionState: ConnectionState,
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
    onOpenNotificationSettings: () -> Unit,
    onSendTestNotification: () -> Unit = {},
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSaveConfig: suspend (serverUrl: String, roomId: String) -> Boolean,
    onResetDefaults: suspend () -> Boolean,
    onClearCounts: () -> Unit,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
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
            SectionTitle("通知")
            Spacer(modifier = Modifier.height(8.dp))

            SettingSwitchRow(
                label = "通知提醒",
                checked = notificationEnabled,
                onCheckedChange = onNotificationToggle
            )

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (notificationPermissionGranted) {
                    "通知权限：已授权"
                } else {
                    "通知权限：未授权"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (notificationPermissionGranted) {
                    Color(0xFF4CAF50)
                } else {
                    Color(0xFFF44336)
                }
            )
            Text(
                text = "实际状态：$notificationDeliveryStatus",
                style = MaterialTheme.typography.bodySmall,
                color = if (notificationDeliveryStatus == "通知可用") {
                    Color(0xFF4CAF50)
                } else {
                    Color(0xFFF44336)
                }
            )

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onOpenNotificationSettings,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("打开系统通知设置")
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onSendTestNotification,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("发送测试通知")
            }
        }
    }
}

@Composable
private fun ConnectionConfigCard(
    serverUrl: String,
    roomId: String,
    deviceIdDisplay: String,
    onSaveConfig: suspend (serverUrl: String, roomId: String) -> Boolean,
    onResetDefaults: suspend () -> Boolean
) {
    val coroutineScope = rememberCoroutineScope()
    var localServerUrl by remember(serverUrl) { mutableStateOf(serverUrl) }
    var localRoomId by remember(roomId) { mutableStateOf(roomId) }
    var serverUrlError by remember { mutableStateOf<String?>(null) }
    var roomIdError by remember { mutableStateOf<String?>(null) }
    var savedMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

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
            SectionTitle("连接配置")
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = localServerUrl,
                onValueChange = {
                    localServerUrl = it
                    serverUrlError = null
                    savedMessage = null
                },
                enabled = !isSaving,
                label = { Text("服务器地址") },
                placeholder = { Text(DEFAULT_SERVER_URL) },
                singleLine = true,
                isError = serverUrlError != null,
                supportingText = serverUrlError?.let { error -> { Text(error) } },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = localRoomId,
                onValueChange = {
                    localRoomId = it
                    roomIdError = null
                    savedMessage = null
                },
                enabled = !isSaving,
                label = { Text("房间 ID") },
                placeholder = { Text(DEFAULT_ROOM_ID) },
                singleLine = true,
                isError = roomIdError != null,
                supportingText = roomIdError?.let { error -> { Text(error) } },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "设备 ID: $deviceIdDisplay",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    val normalizedServerUrl = localServerUrl.trim()
                    val normalizedRoomId = localRoomId.trim()
                    serverUrlError = validateServerUrl(normalizedServerUrl)
                    roomIdError = validateRoomId(normalizedRoomId)
                    savedMessage = null

                    if (serverUrlError == null && roomIdError == null) {
                        coroutineScope.launch {
                            isSaving = true
                            try {
                                if (onSaveConfig(normalizedServerUrl, normalizedRoomId)) {
                                    localServerUrl = normalizedServerUrl
                                    localRoomId = normalizedRoomId
                                    savedMessage = "配置已保存"
                                } else {
                                    serverUrlError = "配置保存失败，请检查地址或稍后重试"
                                }
                            } finally {
                                isSaving = false
                            }
                        }
                    }
                },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (isSaving) "正在保存…" else "保存配置")
            }

            savedMessage?.let { message ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF4CAF50)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "修改配置后，请断开并重新连接以生效",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Text(
                text = "ws:// 仅用于本地或短期测试；长期公网使用应切换为 wss://",
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
        }
    }
}

@Composable
private fun ConnectionCard(
    connectionState: ConnectionState,
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
            SectionTitle("连接")
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("连接状态", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = connectionDisplayName(connectionState),
                    style = MaterialTheme.typography.bodyMedium,
                    color = connectionDisplayColor(connectionState)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (wsEnabled) {
                Button(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("断开连接")
                }
            } else {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("连接服务")
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
private fun AboutCard() {
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
            SectionTitle("关于")
            Spacer(modifier = Modifier.height(8.dp))
            Text("电子木鱼", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "版本 0.6.0 — 连接配置 MVP",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "双人极简提醒器",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "点一下木鱼，功德 +1，音效 + 震动",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
            uri.host.isNullOrBlank() -> "服务器地址必须包含主机名或 IP"
            !uri.encodedUserInfo.isNullOrEmpty() -> "服务器地址不能包含用户名或密码"
            uri.fragment != null -> "服务器地址不能包含 #fragment"
            !LocalDataStore.canStoreConnectionUrl(serverUrl) ->
                "服务器地址不能包含 token、session、密码等敏感凭据"
            uri.port == 0 || uri.port > 65535 -> "服务器端口必须为 1-65535"
            else -> null
        }
    } catch (_: Exception) {
        "服务器地址格式无效"
    }
}

private fun validateRoomId(roomId: String): String? {
    return when {
        roomId.isBlank() -> "房间 ID 不能为空"
        roomId.length > MAX_ROOM_ID_LENGTH -> "房间 ID 不能超过 $MAX_ROOM_ID_LENGTH 个字符"
        roomId.any { it.code in 0..31 || it.code == 127 } -> "房间 ID 不能包含控制字符"
        else -> null
    }
}

private fun connectionDisplayName(state: ConnectionState): String {
    return when (state) {
        ConnectionState.CONNECTED -> "已连接"
        ConnectionState.CONNECTING -> "连接中"
        ConnectionState.RECONNECTING -> "重连中"
        ConnectionState.DISCONNECTED -> "未连接"
        ConnectionState.UNPAIRED -> "未配对"
        ConnectionState.PAIRING -> "配对中"
        ConnectionState.PAIRED -> "已配对"
        ConnectionState.PAIR_FAILED -> "配对失败"
        ConnectionState.CONNECTION_FAILED -> "连接失败"
        ConnectionState.PARTNER_OFFLINE -> "对方离线"
    }
}

private fun connectionDisplayColor(state: ConnectionState): Color {
    return when (state) {
        ConnectionState.CONNECTED -> Color(0xFF4CAF50)
        ConnectionState.CONNECTING -> Color(0xFFFFC107)
        ConnectionState.RECONNECTING -> Color(0xFFFFC107)
        ConnectionState.DISCONNECTED -> Color(0xFF9E9E9E)
        ConnectionState.UNPAIRED -> Color(0xFFFF9800)
        ConnectionState.PAIRING -> Color(0xFFFFC107)
        ConnectionState.PAIRED -> Color(0xFF4CAF50)
        ConnectionState.PAIR_FAILED -> Color(0xFFF44336)
        ConnectionState.CONNECTION_FAILED -> Color(0xFFF44336)
        ConnectionState.PARTNER_OFFLINE -> Color(0xFFFF9800)
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

private fun connectionDebugStateName(state: ConnectionState): String {
    return when (state) {
        ConnectionState.CONNECTING -> "connecting"
        ConnectionState.CONNECTED -> "connected"
        ConnectionState.RECONNECTING -> "reconnecting"
        ConnectionState.DISCONNECTED,
        ConnectionState.UNPAIRED,
        ConnectionState.PAIRING,
        ConnectionState.PAIRED,
        ConnectionState.PAIR_FAILED,
        ConnectionState.CONNECTION_FAILED,
        ConnectionState.PARTNER_OFFLINE -> "disconnected"
    }
}

private fun formatDisconnectTime(millis: Long?): String {
    if (millis == null) return "none"
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))
}
