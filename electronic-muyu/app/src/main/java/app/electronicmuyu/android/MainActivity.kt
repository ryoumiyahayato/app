package app.electronicmuyu.android

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import app.electronicmuyu.android.notification.NotificationHelper
import app.electronicmuyu.android.ui.screen.MainScreen
import app.electronicmuyu.android.ui.screen.SettingsScreen
import app.electronicmuyu.android.ui.theme.ElectronicMuyuTheme
import app.electronicmuyu.android.viewmodel.MainViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

class MainActivity : ComponentActivity() {

    // 通知权限申请结果回调
    private var onPermissionResult: ((Boolean) -> Unit)? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        onPermissionResult?.invoke(isGranted)
        onPermissionResult = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 创建通知渠道（应用启动时即可创建，不弹权限框）
        NotificationHelper.createNotificationChannel(this)

        enableEdgeToEdge()
        setContent {
            ElectronicMuyuTheme {
                MuyuApp(onRequestNotificationPermission = { callback ->
                    onPermissionResult = callback
                    requestNotificationPermission()
                })
            }
        }
    }

    /**
     * 请求通知权限（Android 13+）
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Android 12 及以下无运行时权限，直接回调成功
            onPermissionResult?.invoke(true)
            onPermissionResult = null
        }
    }

    /**
     * 打开应用通知设置页
     */
    fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }
}

@Composable
fun MuyuApp(
    onRequestNotificationPermission: (callback: (Boolean) -> Unit) -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel()
    val meriCount by viewModel.meriCount.collectAsState()
    val receivedCount by viewModel.receivedCount.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val lastReceivedEvent by viewModel.lastReceivedEvent.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val wsEnabled by viewModel.wsEnabled.collectAsState()
    val notificationEnabled by viewModel.notificationEnabled.collectAsState()
    val lastDisconnectReason by viewModel.lastDisconnectReason.collectAsState()
    val lastDisconnectAtMillis by viewModel.lastDisconnectAtMillis.collectAsState()
    val isAppInForeground by viewModel.isAppInForeground.collectAsState()
    val isReconnecting by viewModel.isReconnecting.collectAsState()
    val lastReconnectResult by viewModel.lastReconnectResult.collectAsState()
    val isServiceRunning by viewModel.isServiceRunning.collectAsState()
    val foregroundNotificationText by viewModel.foregroundNotificationText.collectAsState()

    val navController = rememberNavController()

    // 当前通知权限状态（实时查询系统）
    var notificationPermissionGranted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        notificationPermissionGranted = viewModel.checkNotificationPermissionState()
    }

    NavHost(navController = navController, startDestination = "main") {
        composable("main") {
            MainScreen(
                meriCount = meriCount,
                receivedCount = receivedCount,
                connectionState = connectionState,
                lastReceivedEvent = lastReceivedEvent,
                lastError = lastError,
                wsEnabled = wsEnabled,
                lastDisconnectReason = lastDisconnectReason,
                onWoodfishTap = { viewModel.onTap() },
                onConnect = { viewModel.startConnection() },
                onDisconnect = { viewModel.stopConnection() },
                onNavigateToSettings = { navController.navigate("settings") },
                onReceivedEventShown = { viewModel.dismissReceivedEvent() }
            )
        }
        composable("settings") {
            SettingsScreen(
                soundEnabled = viewModel.soundEnabled.collectAsState().value,
                vibrationEnabled = viewModel.vibrationEnabled.collectAsState().value,
                notificationEnabled = notificationEnabled,
                notificationPermissionGranted = notificationPermissionGranted,
                connectionState = connectionState,
                wsEnabled = wsEnabled,
                lastDisconnectReason = lastDisconnectReason.label,
                lastDisconnectAtMillis = lastDisconnectAtMillis,
                isAppInForeground = isAppInForeground,
                isReconnecting = isReconnecting,
                lastReconnectResult = lastReconnectResult,
                isServiceRunning = isServiceRunning,
                foregroundNotificationText = foregroundNotificationText,
                onSoundToggle = { viewModel.setSoundEnabled(it) },
                onVibrationToggle = { viewModel.setVibrationEnabled(it) },
                onNotificationToggle = { enabled ->
                    if (enabled) {
                        // 用户尝试开启通知
                        if (viewModel.checkNotificationPermissionState()) {
                            // 已有权限，直接开启
                            viewModel.setNotificationEnabled(true)
                            notificationPermissionGranted = true
                        } else {
                            // 无权限，发起申请
                            onRequestNotificationPermission { isGranted ->
                                notificationPermissionGranted = isGranted
                                if (isGranted) {
                                    viewModel.setNotificationEnabled(true)
                                }
                            }
                        }
                    } else {
                        // 用户关闭通知
                        viewModel.setNotificationEnabled(false)
                    }
                },
                onOpenNotificationSettings = { /* 暂不实现复杂设置页 */ },
                onSendTestNotification = {
                    if (viewModel.checkNotificationPermissionState()) {
                        notificationPermissionGranted = true
                        NotificationHelper.createNotificationChannel(context)
                        NotificationHelper.sendMeritReminderNotification(context)
                    } else {
                        onRequestNotificationPermission { isGranted ->
                            notificationPermissionGranted = isGranted
                            if (isGranted) {
                                NotificationHelper.createNotificationChannel(context)
                                NotificationHelper.sendMeritReminderNotification(context)
                            } else {
                                Log.d("ElectronicMuyu", "notify skipped: POST_NOTIFICATIONS denied")
                            }
                        }
                    }
                },
                onConnect = { viewModel.startConnection() },
                onDisconnect = { viewModel.stopConnection() },
                onClearCounts = { viewModel.clearAllCounts() },
                onNavigateBack = { navController.popBackStack() }
            )

            // 每次进入设置页刷新通知权限状态
            LaunchedEffect(Unit) {
                notificationPermissionGranted = viewModel.checkNotificationPermissionState()
            }
        }
    }
}



