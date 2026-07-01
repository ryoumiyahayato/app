package app.electronicmuyu.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.electronicmuyu.android.ui.screen.MainScreen
import app.electronicmuyu.android.ui.screen.SettingsScreen
import app.electronicmuyu.android.ui.theme.ElectronicMuyuTheme
import app.electronicmuyu.android.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ElectronicMuyuTheme {
                MuyuApp()
            }
        }
    }
}

@Composable
fun MuyuApp() {
    val viewModel: MainViewModel = viewModel()
    val navController = rememberNavController()

    val meriCount by viewModel.meriCount.collectAsState()
    val receivedCount by viewModel.receivedCount.collectAsState()
    val soundEnabled by viewModel.soundEnabled.collectAsState()
    val vibrationEnabled by viewModel.vibrationEnabled.collectAsState()

    NavHost(
        navController = navController,
        startDestination = "main"
    ) {
        composable("main") {
            MainScreen(
                meriCount = meriCount,
                receivedCount = receivedCount,
                onWoodfishTap = { viewModel.onWoodfishTap() },
                onNavigateToSettings = { navController.navigate("settings") }
            )
        }
        composable("settings") {
            SettingsScreen(
                meriCount = meriCount,
                receivedCount = receivedCount,
                soundEnabled = soundEnabled,
                vibrationEnabled = vibrationEnabled,
                onSoundEnabledChange = { viewModel.setSoundEnabled(it) },
                onVibrationEnabledChange = { viewModel.setVibrationEnabled(it) },
                onClearCounts = { viewModel.clearCounts() },
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}