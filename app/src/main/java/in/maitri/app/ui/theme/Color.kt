package `in`.maitri.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Legacy token aliases for backwards compatibility
val Navy = Color(0xFF0A1128)
val Emergency = Color(0xFFE11D48)
val Mint = Color(0xFF10B981)

// Primary Brand Palette
val BrandNavy900 = Color(0xFF070B18)
val BrandNavy800 = Color(0xFF0A1128)
val BrandNavy700 = Color(0xFF111E38)
val BrandNavy600 = Color(0xFF18284B)
val BrandNavy500 = Color(0xFF223764)
val BrandNavy400 = Color(0xFF2F4C87)

// Emergency Palette
val EmergencyCrimson = Color(0xFFE11D48)
val EmergencyRose = Color(0xFFF43F5E)
val EmergencyDeep = Color(0xFF9F1239)
val EmergencyDarkBg = Color(0xFF2E0D17)
val EmergencyLightBg = Color(0xFFFFF1F2)
val EmergencyGlow = Color(0x59E11D48)

// Safety & Readiness Palette
val SafetyEmerald = Color(0xFF10B981)
val SafetyMint = Color(0xFF34D399)
val SafetyDeepGreen = Color(0xFF047857)
val SafetyDarkBg = Color(0xFF0B261D)
val SafetyLightBg = Color(0xFFECFDF5)
val SafetyGlow = Color(0x4D10B981)

// Warning & Alert Palette
val WarningAmber = Color(0xFFF59E0B)
val WarningDarkBg = Color(0xFF2E1F0A)
val WarningLightBg = Color(0xFFFFFBEB)
val WarningGlow = Color(0x4DF59E0B)

// Accent & Info Palette
val AccentCyan = Color(0xFF06B6D4)
val AccentSky = Color(0xFF0284C7)
val AccentIndigo = Color(0xFF6366F1)
val AccentPurple = Color(0xFF8B5CF6)

// Neutral Palette - Light
val NeutralLightBg = Color(0xFFF6F8FC)
val NeutralLightSurface = Color(0xFFFFFFFF)
val NeutralLightSurfaceVariant = Color(0xFFF1F5F9)
val NeutralLightBorder = Color(0xFFE2E8F0)
val NeutralLightBorderFocused = Color(0xFFCBD5E1)
val TextLightPrimary = Color(0xFF0F172A)
val TextLightSecondary = Color(0xFF475569)
val TextLightTertiary = Color(0xFF52647A)

// Neutral Palette - Dark
val NeutralDarkBg = Color(0xFF060B18)
val NeutralDarkSurface = Color(0xFF0E1832)
val NeutralDarkSurfaceVariant = Color(0xFF142244)
val NeutralDarkBorder = Color(0xFF1E325C)
val NeutralDarkBorderFocused = Color(0xFF2D4982)
val TextDarkPrimary = Color(0xFFF8FAFC)
val TextDarkSecondary = Color(0xFF94A3B8)
val TextDarkTertiary = Color(0xFFA1AEC1)

@Immutable
data class MaitriColors(
    val emergency: Color,
    val emergencyContainer: Color,
    val onEmergencyContainer: Color,
    val emergencyGradientStart: Color,
    val emergencyGradientEnd: Color,
    val emergencyGlow: Color,

    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val successGlow: Color,

    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,

    val info: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,

    val cardBackground: Color,
    val cardBackgroundSecondary: Color,
    val cardBorder: Color,

    val mapBackground: Color,
    val mapGrid: Color,
    val radarOverlay: Color,

    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,

    val isDark: Boolean,

    // Liquid Glass Design System Tokens
    val glassSurface: Color = Color.Unspecified,
    val glassSurfaceElevated: Color = Color.Unspecified,
    val glassSurfaceSubtle: Color = Color.Unspecified,
    val glassBorder: Color = Color.Unspecified,
    val glassBorderSpecular: Color = Color.Unspecified,
    val glassHighlight: Color = Color.Unspecified,
    val glassShadow: Color = Color.Unspecified,
    val glassBottomNav: Color = Color.Unspecified
)

val DarkMaitriColors = MaitriColors(
    emergency = EmergencyCrimson,
    emergencyContainer = EmergencyDarkBg,
    onEmergencyContainer = Color(0xFFFFD1DC),
    emergencyGradientStart = EmergencyRose,
    emergencyGradientEnd = EmergencyDeep,
    emergencyGlow = EmergencyGlow,

    success = SafetyEmerald,
    successContainer = SafetyDarkBg,
    onSuccessContainer = Color(0xFFA7F3D0),
    successGlow = SafetyGlow,

    warning = WarningAmber,
    warningContainer = WarningDarkBg,
    onWarningContainer = Color(0xFFFDE68A),

    info = AccentCyan,
    infoContainer = Color(0xFF082F3B),
    onInfoContainer = Color(0xFFA5F3FC),

    // Neutrals follow the Maitri palette (MaitriPalette in MaitriHome.kt), not the old navy/slate set.
    cardBackground = Color(0xFF28212A),
    cardBackgroundSecondary = Color(0xFF36232F),
    cardBorder = Color(0xFF48323F),

    mapBackground = Color(0xFF0A1326),
    mapGrid = Color(0x1A6366F1),
    radarOverlay = Color(0x26E11D48),

    textPrimary = Color(0xFFFAEFF5),
    textSecondary = Color(0xFFC3ADBA),
    textTertiary = Color(0xFFC3ADBA),

    isDark = true,

    // Liquid Glass Tokens - Dark Glass (Midnight Navy Frost & Specular Refraction)
    glassSurface = Color(0xCC0E1832),
    glassSurfaceElevated = Color(0xEB1B2D48),
    glassSurfaceSubtle = Color(0x660E1832),
    glassBorder = Color(0x33FFFFFF),
    glassBorderSpecular = Color(0x46FFFFFF),
    glassHighlight = Color(0x12FFFFFF),
    glassShadow = Color(0x66000000),
    glassBottomNav = Color(0xE71A293D)
)

val LightMaitriColors = MaitriColors(
    emergency = EmergencyCrimson,
    emergencyContainer = EmergencyLightBg,
    onEmergencyContainer = EmergencyDeep,
    emergencyGradientStart = EmergencyRose,
    emergencyGradientEnd = EmergencyCrimson,
    emergencyGlow = Color(0x33E11D48),

    success = Color(0xFF006C5B),
    successContainer = SafetyLightBg,
    onSuccessContainer = SafetyDeepGreen,
    successGlow = Color(0x3310B981),

    warning = WarningAmber,
    warningContainer = WarningLightBg,
    onWarningContainer = Color(0xFFB45309),

    info = Color(0xFF165C87),
    infoContainer = Color(0xFFF0F9FF),
    onInfoContainer = Color(0xFF0369A1),

    cardBackground = Color(0xFFFFFFFF),
    cardBackgroundSecondary = Color(0xFFF9DCE6),
    cardBorder = Color(0xFFF2DCE3),

    mapBackground = Color(0xFFE8EEF5),
    mapGrid = Color(0x1F1E293B),
    radarOverlay = Color(0x1FE11D48),

    textPrimary = Color(0xFF40303A),
    textSecondary = Color(0xFF725C66),
    textTertiary = Color(0xFF725C66),

    isDark = false,

    // Liquid Glass Tokens - Light Glass (Frosted White & Specular Rim)
    glassSurface = Color(0xE6EDF5FC),
    glassSurfaceElevated = Color(0xFAFFFFFF),
    glassSurfaceSubtle = Color(0x80F1F5F9),
    glassBorder = Color(0x40CBD5E1),
    glassBorderSpecular = Color(0x99FFFFFF),
    glassHighlight = Color(0x40FFFFFF),
    glassShadow = Color(0x180F172A),
    glassBottomNav = Color(0xEBFFFFFF)
)

val LocalMaitriColors = staticCompositionLocalOf { DarkMaitriColors }
