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
import app.electronicmuyu.android.service.MuyuConnectionRepository
import app.electronicmuyu.android.ui.screen.MainScreen
import app.electronicmuyu.android.ui.screen.QrScannerScreen
import app.electronicmuyu.android.ui.screen.SettingsScreen
import app.electronicmuyu.android.ui.theme.ElectronicMuyuTheme
import app.electronicmuyu.android.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private var notificationPermissionResult: ((Boolean) -> Unit)? = null
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionResult?.invoke(granted)
        notificationPermissionResult = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.createNotificationChannel(this)
        enableEdgeToEdge()
        setContent {
            ElectronicMuyuTheme {
                MuyuApp { callback ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionResult = callback
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        callback(true)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        MuyuConnectionRepository.setUiForeground(true)
        MuyuConnectionRepository.setAppForeground(true)
    }

    override fun onStop() {
        MuyuConnectionRepository.setUiForeground(false)
        MuyuConnectionRepository.setAppForeground(false)
        super.onStop()
    }
}

@Composable
fun MuyuApp(onRequestNotificationPermission: ((Boolean) -> Unit) -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModel: MainViewModel = viewModel()
    val meriCount by viewModel.meriCount.collectAsState()
    val receivedCount by viewModel.receivedCount.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val lastReceivedEvent by viewModel.lastReceivedEvent.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val partnerOnline by MuyuConnectionRepository.partnerOnline.collectAsState()
    val serviceRunning by viewModel.isServiceRunning.collectAsState()
    val soundEnabled by viewModel.soundEnabled.collectAsState()
    val vibrationEnabled by viewModel.vibrationEnabled.collectAsState()
    val notificationEnabled by viewModel.notificationEnabled.collectAsState()
    val lastDisconnectReason by viewModel.lastDisconnectReason.collectAsState()
    val lastDisconnectAtMillis by viewModel.lastDisconnectAtMillis.collectAsState()
    val isAppInForeground by viewModel.isAppInForeground.collectAsState()
    val isReconnecting by viewModel.isReconnecting.collectAsState()
    val lastReconnectResult by viewModel.lastReconnectResult.collectAsState()
    val foregroundNotificationText by viewModel.foregroundNotificationText.collectAsState()
    val pairing by viewModel.pairingUiState.collectAsState()
    val storedPair by viewModel.storedPair.collectAsState()
    val debugRelay by viewModel.debugRelayOverride.collectAsState()
    val navController = rememberNavController()
    var notificationPermission by remember { mutableStateOf(false) }
    var notificationDeliveryStatus by remember {
        mutableStateOf(NotificationHelper.DeliveryStatus.PERMISSION_DENIED)
    }

    fun refreshNotificationState() {
        notificationPermission = NotificationHelper.hasNotificationPermission(context)
        notificationDeliveryStatus = NotificationHelper.getDeliveryStatus(context)
    }

    LaunchedEffect(Unit) { refreshNotificationState() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshNotificationState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    NavHost(navController, startDestination = "main") {
        composable("main") {
            MainScreen(
                meriCount = meriCount,
                receivedCount = receivedCount,
                connectionState = connectionState,
                lastReceivedEvent = lastReceivedEvent,
                lastError = lastError,
                wsEnabled = serviceRunning,
                lastDisconnectReason = lastDisconnectReason,
                partnerOnline = partnerOnline,
                onWoodfishTap = viewModel::onTap,
                onConnect = {
                    if (storedPair == null) {
                        navController.navigate("settings")
                    } else {
                        viewModel.startConnection()
                    }
                },
                onDisconnect = viewModel::stopConnection,
                onNavigateToSettings = { navController.navigate("settings") },
                onReceivedEventShown = viewModel::dismissReceivedEvent
            )
        }
        composable("settings") {
            SettingsScreen(
                soundEnabled = soundEnabled,
                vibrationEnabled = vibrationEnabled,
                notificationEnabled = notificationEnabled,
                notificationPermissionGranted = notificationPermission,
                notificationDeliveryStatus = notificationDeliveryStatus.label,
                connectionState = connectionState,
                partnerOnline = partnerOnline,
                pairing = pairing,
                storedPair = storedPair,
                allowRelayOverride = viewModel.allowRelayOverride,
                debugRelayOverride = debugRelay,
                lastError = lastError,
                lastDisconnectReason = lastDisconnectReason.label,
                lastDisconnectAtMillis = lastDisconnectAtMillis,
                isAppInForeground = isAppInForeground,
                isReconnecting = isReconnecting,
                lastReconnectResult = lastReconnectResult,
                isServiceRunning = serviceRunning,
                foregroundNotificationText = foregroundNotificationText,
                onSoundToggle = viewModel::setSoundEnabled,
                onVibrationToggle = viewModel::setVibrationEnabled,
                onNotificationToggle = { enabled ->
                    if (!enabled || NotificationHelper.hasNotificationPermission(context)) {
                        viewModel.setNotificationEnabled(enabled)
                        refreshNotificationState()
                    } else {
                        onRequestNotificationPermission { granted ->
                            viewModel.setNotificationEnabled(granted)
                            refreshNotificationState()
                        }
                    }
                },
                onOpenNotificationSettings = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                },
                onSendTestNotification = {
                    fun sendTest() {
                        NotificationHelper.createNotificationChannel(context)
                        if (!NotificationHelper.sendMeritReminderNotification(context)) {
                            Log.d("ElectronicMuyu", "test notification was not deliverable")
                        }
                        refreshNotificationState()
                    }
                    if (NotificationHelper.hasNotificationPermission(context)) {
                        sendTest()
                    } else {
                        onRequestNotificationPermission { granted ->
                            if (granted) sendTest() else refreshNotificationState()
                        }
                    }
                },
                onCreateInvite = viewModel::createInvite,
                onScanInvite = { navController.navigate("scanner") },
                onCancelInvite = viewModel::cancelInvite,
                onRegenerateInvite = viewModel::regenerateInvite,
                onConfirmSas = viewModel::confirmSas,
                onRejectSas = viewModel::rejectSas,
                onRevokePair = viewModel::revokePairing,
                onConnect = viewModel::startConnection,
                onDisconnect = viewModel::stopConnection,
                onSaveDebugRelay = viewModel::saveDebugRelayOverride,
                onClearCounts = viewModel::clearAllCounts,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("scanner") {
            QrScannerScreen(
                onScanned = { raw ->
                    navController.popBackStack()
                    viewModel.acceptScannedQr(raw)
                },
                onCancel = { navController.popBackStack() }
            )
        }
    }
}
