package `in`.maitri.app.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

class ThemeController(
    private val context: Context,
    initialMode: ThemeMode = ThemeMode.SYSTEM
) {
    var themeMode by mutableStateOf(initialMode)
        private set

    init {
        val prefs = context.getSharedPreferences("maitri_theme_pref", Context.MODE_PRIVATE)
        val saved = prefs.getString("theme_mode", ThemeMode.SYSTEM.name)
        themeMode = runCatching { ThemeMode.valueOf(saved ?: ThemeMode.SYSTEM.name) }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun setMode(mode: ThemeMode) {
        themeMode = mode
        context.getSharedPreferences("maitri_theme_pref", Context.MODE_PRIVATE)
            .edit()
            .putString("theme_mode", mode.name)
            .apply()
    }
}

val LocalThemeController = staticCompositionLocalOf<ThemeController> {
    error("ThemeController not provided")
}

val MaterialTheme.maitriColors: MaitriColors
    @Composable
    @ReadOnlyComposable
    get() = LocalMaitriColors.current

// Material defaults (switches, radios, checkboxes, text buttons, dialogs, progress) take the Maitri palette, so no
// component falls back to Material purple. Values match MaitriPalette in MaitriHome.kt (from the design boards).
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFF9CBE),            // accent (pink)
    onPrimary = Color(0xFF19151B),
    primaryContainer = Color(0xFF36232F),   // card
    onPrimaryContainer = Color(0xFFFAEFF5),
    inversePrimary = Color(0xFFB82055),
    secondary = Color(0xFFFF9CBE),
    onSecondary = Color(0xFF19151B),
    secondaryContainer = Color(0xFF36232F),
    onSecondaryContainer = Color(0xFFFAEFF5),
    tertiary = Color(0xFFFF9CBE),
    onTertiary = Color(0xFF19151B),
    tertiaryContainer = Color(0xFF36232F),
    onTertiaryContainer = Color(0xFFFAEFF5),
    background = Color(0xFF19151B),
    onBackground = Color(0xFFFAEFF5),
    surface = Color(0xFF28212A),
    onSurface = Color(0xFFFAEFF5),
    surfaceVariant = Color(0xFF36232F),
    onSurfaceVariant = Color(0xFFC3ADBA),
    surfaceTint = Color(0xFF28212A),
    surfaceBright = Color(0xFF36232F),
    surfaceDim = Color(0xFF19151B),
    surfaceContainerLowest = Color(0xFF19151B),
    surfaceContainerLow = Color(0xFF221B24),
    surfaceContainer = Color(0xFF28212A),
    surfaceContainerHigh = Color(0xFF28212A),
    surfaceContainerHighest = Color(0xFF48323F),
    inverseSurface = Color(0xFFFAEFF5),
    inverseOnSurface = Color(0xFF40303A),
    outline = Color(0xFF9B8592),
    outlineVariant = Color(0xFF48323F),     // divider
    scrim = Color(0xFF000000),
    error = Color(0xFFFF9CBE),
    onError = Color(0xFF19151B),
    errorContainer = EmergencyDarkBg,
    onErrorContainer = Color(0xFFFFD1DC)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFB82055),            // accent (raspberry)
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF9DCE6),   // card (maitri_blush)
    onPrimaryContainer = Color(0xFF40303A),
    inversePrimary = Color(0xFFFF9CBE),
    secondary = Color(0xFFB82055),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF9DCE6),
    onSecondaryContainer = Color(0xFF40303A),
    tertiary = Color(0xFFB82055),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF9DCE6),
    onTertiaryContainer = Color(0xFF40303A),
    background = Color(0xFFFFF9FA),
    onBackground = Color(0xFF40303A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF40303A),
    surfaceVariant = Color(0xFFF9DCE6),
    onSurfaceVariant = Color(0xFF725C66),
    surfaceTint = Color(0xFFFFFFFF),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFF2DCE3),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF9FA),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFF2DCE3),
    inverseSurface = Color(0xFF40303A),
    inverseOnSurface = Color(0xFFFFF9FA),
    outline = Color(0xFF9C8590),
    outlineVariant = Color(0xFFF2DCE3),     // divider
    scrim = Color(0xFF000000),
    error = Color(0xFFB82055),
    onError = Color.White,
    errorContainer = EmergencyLightBg,
    onErrorContainer = EmergencyDeep
)

/** The Material colour scheme for a theme, for screenshot tests that render screens outside MainActivity. */
fun maitriColorScheme(dark: Boolean): ColorScheme = if (dark) DarkColorScheme else LightColorScheme

@Composable
fun MaitriTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val controller = remember { ThemeController(context.applicationContext) }
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (controller.themeMode) {
        ThemeMode.SYSTEM -> isSystemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme
    val maitriColors = if (isDark) DarkMaitriColors else LightMaitriColors

    CompositionLocalProvider(
        LocalThemeController provides controller,
        LocalMaitriColors provides maitriColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = Shapes
        ) {
            // Text and icons outside a Material Surface otherwise default to black, which is unreadable in dark mode
            // (the Responder tab's labels). The theme's on-background colour is the default for every screen.
            CompositionLocalProvider(LocalContentColor provides colorScheme.onBackground, content = content)
        }
    }
}
