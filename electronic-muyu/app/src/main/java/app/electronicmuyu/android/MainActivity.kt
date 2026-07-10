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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.electronicmuyu.android.notification.NotificationHelper
import app.electronicmuyu.android.ui.screen.MainScreen
import app.electronicmuyu.android.ui.screen.SettingsScreen
import app.electronicmuyu.android.ui.theme.ElectronicMuyuTheme
import app.electronicmuyu.android.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private var onPermissionResult: ((Boolean) -> Unit)? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        onPermissionResult?.invoke(isGranted)
        onPermissionResult = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onPermissionResult?.invoke(true)
            onPermissionResult = null
        }
    }
}

@Composable
fun MuyuApp(
    onRequestNotificationPermission: (callback: (Boolean) -> Unit) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
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
    val serverUrl by viewModel.serverUrl.collectAsState()
    val roomId by viewModel.roomId.collectAsState()
    val deviceIdDisplay by viewModel.deviceIdDisplay.collectAsState()

    val navController = rememberNavController()

    var notificationPermissionGranted by remember { mutableStateOf(false) }
    var notificationDeliveryStatus by remember {
        mutableStateOf(NotificationHelper.DeliveryStatus.PERMISSION_DENIED)
    }

    fun refreshNotificationState() {
        notificationPermissionGranted = NotificationHelper.hasNotificationPermission(context)
        notificationDeliveryStatus = NotificationHelper.getDeliveryStatus(context)
    }

    LaunchedEffect(Unit) {
        refreshNotificationState()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshNotificationState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
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
                notificationDeliveryStatus = notificationDeliveryStatus.label,
                connectionState = connectionState,
                wsEnabled = wsEnabled,
                lastDisconnectReason = lastDisconnectReason.label,
                lastDisconnectAtMillis = lastDisconnectAtMillis,
                isAppInForeground = isAppInForeground,
                isReconnecting = isReconnecting,
                lastReconnectResult = lastReconnectResult,
                isServiceRunning = isServiceRunning,
                foregroundNotificationText = foregroundNotificationText,
                serverUrl = serverUrl,
                roomId = roomId,
                deviceIdDisplay = deviceIdDisplay,
                onSoundToggle = { viewModel.setSoundEnabled(it) },
                onVibrationToggle = { viewModel.setVibrationEnabled(it) },
                onNotificationToggle = { enabled ->
                    if (enabled) {
                        if (NotificationHelper.hasNotificationPermission(context)) {
                            viewModel.setNotificationEnabled(true)
                            refreshNotificationState()
                        } else {
                            onRequestNotificationPermission { isGranted ->
                                viewModel.setNotificationEnabled(isGranted)
                                refreshNotificationState()
                            }
                        }
                    } else {
                        viewModel.setNotificationEnabled(false)
                        refreshNotificationState()
                    }
                },
                onOpenNotificationSettings = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                },
                onSendTestNotification = {
                    if (NotificationHelper.hasNotificationPermission(context)) {
                        NotificationHelper.createNotificationChannel(context)
                        NotificationHelper.sendMeritReminderNotification(context)
                        refreshNotificationState()
                    } else {
                        onRequestNotificationPermission { isGranted ->
                            if (isGranted) {
                                NotificationHelper.createNotificationChannel(context)
                                NotificationHelper.sendMeritReminderNotification(context)
                            } else {
                                Log.d("ElectronicMuyu", "notify skipped: POST_NOTIFICATIONS denied")
                            }
                            refreshNotificationState()
                        }
                    }
                },
                onConnect = { viewModel.startConnection() },
                onDisconnect = { viewModel.stopConnection() },
                onSaveConfig = { configuredServerUrl, configuredRoomId ->
                    viewModel.saveConnectionConfig(configuredServerUrl, configuredRoomId)
                },
                onResetDefaults = { viewModel.resetConnectionConfig() },
                onClearCounts = { viewModel.clearAllCounts() },
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
