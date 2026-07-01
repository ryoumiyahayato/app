package app.electronicmuyu.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF8B4513),        // SaddleBrown - wooden feel
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD4A0),
    onPrimaryContainer = Color(0xFF3B2000),
    secondary = Color(0xFFA0522D),       // Sienna
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD7B3),
    onSecondaryContainer = Color(0xFF3B1B00),
    tertiary = Color(0xFFDAA520),        // Goldenrod - merit/gold feel
    onTertiary = Color.White,
    background = Color(0xFFFFF8F0),      // Warm off-white
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFF8F0),
    onSurface = Color(0xFF1C1B1F),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFB74D),         // Orange-ish for dark mode
    onPrimary = Color(0xFF3B2000),
    primaryContainer = Color(0xFF6B3A00),
    onPrimaryContainer = Color(0xFFFFD4A0),
    secondary = Color(0xFFFFAB91),
    onSecondary = Color(0xFF3B1B00),
    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
)

@Composable
fun ElectronicMuyuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}