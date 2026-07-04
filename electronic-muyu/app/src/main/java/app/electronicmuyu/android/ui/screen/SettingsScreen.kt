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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.electronicmuyu.android.model.ConnectionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    soundEnabled: Boolean,
    vibrationEnabled: Boolean,
    notificationEnabled: Boolean,
    notificationPermissionGranted: Boolean,
    connectionState: ConnectionState,
    wsEnabled: Boolean,
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
    onSendTestNotification: () -> Unit = {},
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
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

            // Sound & Vibration section
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
                    Text(
                        text = "反馈",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Sound toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "声音",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Switch(
                            checked = soundEnabled,
                            onCheckedChange = onSoundToggle
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // Vibration toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "震动",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Switch(
                            checked = vibrationEnabled,
                            onCheckedChange = onVibrationToggle
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Notification section (Phase 4A)
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
                    Text(
                        text = "通知",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Notification toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "通知提醒",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Switch(
                            checked = notificationEnabled,
                            onCheckedChange = onNotificationToggle
                        )
                    }

                    // Permission status hint
                    Spacer(modifier = Modifier.height(4.dp))
                    if (notificationPermissionGranted) {
                        Text(
                            text = "通知权限：已授权",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF4CAF50)
                        )
                    } else {
                        Text(
                            text = "通知权限：未授权",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFF44336)
                        )
                    }

                    // Debug 调试按钮：阶段 4A 真机排查通知链路时固定显示。
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onSendTestNotification,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("发送测试通知")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Connection section (Phase 3)
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
                    Text(
                        text = "连接",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Connection state display
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "连接状态",
                            style = MaterialTheme.typography.bodyLarge
                        )
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

                    if (connectionState == ConnectionState.DISCONNECTED) {
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

            Spacer(modifier = Modifier.height(16.dp))

            // Actions section
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
                    Text(
                        text = "操作",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
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

            Spacer(modifier = Modifier.height(16.dp))

            // About section
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
                    Text(
                        text = "关于",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "电子木鱼",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "版本 0.5.1 — 前台服务连接 MVP",
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

            Spacer(modifier = Modifier.height(32.dp))
        }
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
