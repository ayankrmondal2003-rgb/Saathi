package com.example.surakshanet.ui.theme

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
        val prefs = context.getSharedPreferences("suraksha_theme_pref", Context.MODE_PRIVATE)
        val saved = prefs.getString("theme_mode", ThemeMode.SYSTEM.name)
        themeMode = runCatching { ThemeMode.valueOf(saved ?: ThemeMode.SYSTEM.name) }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun setMode(mode: ThemeMode) {
        themeMode = mode
        context.getSharedPreferences("suraksha_theme_pref", Context.MODE_PRIVATE)
            .edit()
            .putString("theme_mode", mode.name)
            .apply()
    }
}

val LocalThemeController = staticCompositionLocalOf<ThemeController> {
    error("ThemeController not provided")
}

val MaterialTheme.surakshaColors: SurakshaColors
    @Composable
    @ReadOnlyComposable
    get() = LocalSurakshaColors.current

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF818CF8), // Soft indigo
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E293B),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = AccentCyan,
    onSecondary = Color(0xFF00363F),
    secondaryContainer = Color(0xFF0E3D48),
    onSecondaryContainer = Color(0xFFA5F3FC),
    tertiary = SafetyMint,
    onTertiary = Color(0xFF003824),
    background = NeutralDarkBg,
    onBackground = TextDarkPrimary,
    surface = NeutralDarkSurface,
    onSurface = TextDarkPrimary,
    surfaceVariant = NeutralDarkSurfaceVariant,
    onSurfaceVariant = TextDarkSecondary,
    outline = NeutralDarkBorder,
    outlineVariant = Color(0xFF162544),
    error = EmergencyRose,
    onError = Color.White,
    errorContainer = EmergencyDarkBg,
    onErrorContainer = Color(0xFFFFD1DC)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1E293B), // Deep navy slate
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF1F5F9),
    onPrimaryContainer = Color(0xFF0F172A),
    secondary = AccentSky,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0369A1),
    tertiary = SafetyEmerald,
    onTertiary = Color.White,
    background = NeutralLightBg,
    onBackground = TextLightPrimary,
    surface = NeutralLightSurface,
    onSurface = TextLightPrimary,
    surfaceVariant = NeutralLightSurfaceVariant,
    onSurfaceVariant = TextLightSecondary,
    outline = NeutralLightBorder,
    outlineVariant = Color(0xFFE2E8F0),
    error = EmergencyCrimson,
    onError = Color.White,
    errorContainer = EmergencyLightBg,
    onErrorContainer = EmergencyDeep
)

@Composable
fun SurakshaNetTheme(
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
    val surakshaColors = if (isDark) DarkSurakshaColors else LightSurakshaColors

    CompositionLocalProvider(
        LocalThemeController provides controller,
        LocalSurakshaColors provides surakshaColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = Shapes,
            content = content
        )
    }
}
