package app.electronicmuyu.android.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.electronicmuyu.android.model.ConnectionState
import app.electronicmuyu.android.network.WebSocketClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    meriCount: Int,
    receivedCount: Int,
    connectionState: ConnectionState,
    lastReceivedEvent: Long?,
    lastError: String,
    wsEnabled: Boolean,
    lastDisconnectReason: WebSocketClient.DisconnectReason,
    partnerOnline: Boolean,
    onWoodfishTap: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onReceivedEventShown: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(lastReceivedEvent) {
        if (lastReceivedEvent != null) {
            snackbarHostState.showSnackbar("对方敲了一下木鱼")
            onReceivedEventShown()
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "电子木鱼",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "设置"
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
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            ConnectionIndicator(state = connectionState, partnerOnline = partnerOnline)

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "功德",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "$meriCount",
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(32.dp))

            WoodfishButton(onTap = onWoodfishTap)

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "收到提醒：$receivedCount 次",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            val hintText = when (connectionState) {
                ConnectionState.CONNECTED -> if (partnerOnline) {
                    "已安全连接，对方在线 — 敲木鱼将实时发送提醒"
                } else {
                    "已安全连接，但对方离线 — 当前提醒不会补发"
                }
                ConnectionState.CONNECTING -> "正在建立安全连接…"
                ConnectionState.AUTHENTICATING -> "正在认证已配对设备…"
                ConnectionState.RECONNECTING -> "网络变化，正在安全重连…"
                ConnectionState.DISCONNECTED -> when (lastDisconnectReason) {
                    WebSocketClient.DisconnectReason.NETWORK_ERROR,
                    WebSocketClient.DisconnectReason.SERVER_CLOSED,
                    WebSocketClient.DisconnectReason.RATE_LIMITED -> "连接已断开，正在等待或可手动重连"
                    else -> "未连接"
                }
                ConnectionState.REVOKED -> "安全配对已撤销"
                ConnectionState.AUTHENTICATION_FAILED -> "设备认证失败，请重新配对"
                else -> "未连接 — 点按木鱼仅增加本机功德"
            }
            Text(
                text = hintText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (wsEnabled) {
                Button(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("断开连接")
                }
            } else {
                Button(onClick = onConnect) {
                    Text("连接服务")
                }
            }

            if (lastError.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "提示：$lastError",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun WoodfishButton(onTap: () -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = tween(durationMillis = 150),
        finishedListener = { isPressed = false },
        label = "woodfishScale"
    )

    Button(
        onClick = {
            isPressed = true
            onTap()
        },
        modifier = Modifier
            .size(200.dp)
            .scale(scale),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 8.dp,
            pressedElevation = 2.dp
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "功德 +1",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "敲一下",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun ConnectionIndicator(state: ConnectionState, partnerOnline: Boolean) {
    val (label, color) = when (state) {
        ConnectionState.CONNECTED -> if (partnerOnline) {
            "对方在线" to Color(0xFF4CAF50)
        } else {
            "对方离线" to Color(0xFFFF9800)
        }
        ConnectionState.CONNECTING -> "连接中" to Color(0xFFFFC107)
        ConnectionState.AUTHENTICATING -> "认证中" to Color(0xFFFFC107)
        ConnectionState.RECONNECTING -> "重连中" to Color(0xFFFFC107)
        ConnectionState.DISCONNECTED -> "未连接" to Color(0xFF9E9E9E)
        ConnectionState.UNPAIRED -> "未配对" to Color(0xFFFF9800)
        ConnectionState.PAIRING -> "配对中" to Color(0xFFFFC107)
        ConnectionState.PAIRED -> "已配对" to Color(0xFF4CAF50)
        ConnectionState.PAIR_FAILED -> "配对失败" to Color(0xFFF44336)
        ConnectionState.CONNECTION_FAILED -> "连接失败" to Color(0xFFF44336)
        ConnectionState.PARTNER_OFFLINE -> "对方离线" to Color(0xFFFF9800)
        ConnectionState.AUTHENTICATION_FAILED -> "认证失败" to Color(0xFFF44336)
        ConnectionState.REVOKED -> "配对已撤销" to Color(0xFFF44336)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
