package com.example.surakshanet.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.surakshanet.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Data class representing a bottom navigation tab with route, title label, and icon.
 */
data class NavTab(
    val route: String,
    val label: String,
    val icon: ImageVector
)

/**
 * Core modifier providing an authentic iOS-inspired Liquid Glass appearance in Jetpack Compose.
 *
 * Implements:
 * 1. Layered translucent surface gradient fill (physical glass refraction)
 * 2. Specular rim gradient border (crisp top-left light catch fading to subtle bottom-right edge)
 * 3. 2dp top specular thickness sheen
 * 4. Ambient diffuse elevation shadow
 *
 * Runs at full 60/120fps with zero runtime overhead on all Android versions without blurring child content.
 */
fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(22.dp),
    colors: SurakshaColors,
    elevation: Dp = 4.dp,
    customSurfaceColor: Color? = null,
    specularAlpha: Float = 1f
): Modifier {
    val surfaceColor = customSurfaceColor ?: colors.glassSurface
    val specularGradient = Brush.linearGradient(
        colors = listOf(
            colors.glassBorderSpecular.copy(alpha = (colors.glassBorderSpecular.alpha * specularAlpha).coerceIn(0f, 1f)),
            colors.glassBorder,
            colors.glassBorder.copy(alpha = 0.05f)
        ),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    )

    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            spotColor = colors.glassShadow,
            ambientColor = colors.glassShadow
        )
        .clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(
                    colors.glassSurfaceElevated.takeIf { customSurfaceColor == null } ?: surfaceColor,
                    surfaceColor
                )
            )
        )
        .border(
            width = 1.dp,
            brush = specularGradient,
            shape = shape
        )
        .drawBehind {
            val sheenHeight = 0.65.dp.toPx()
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(
                        colors.glassHighlight,
                        Color.Transparent
                    ),
                    startY = 0f,
                    endY = sheenHeight * 3
                ),
                topLeft = Offset.Zero,
                size = androidx.compose.ui.geometry.Size(size.width, sheenHeight * 3)
            )
        }
}

/**
 * Liquid Glass Surface component with specular borders and translucent frosted depth.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    backgroundColor: Color? = null,
    elevation: Dp = 4.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = MaterialTheme.surakshaColors
    val baseModifier = modifier.liquidGlass(
        shape = shape,
        colors = colors,
        elevation = elevation,
        customSurfaceColor = backgroundColor
    )

    val finalModifier = if (onClick != null) {
        baseModifier.clickable(onClick = onClick)
    } else {
        baseModifier
    }

    Box(
        modifier = finalModifier,
        content = content
    )
}

/**
 * Liquid Glass Card with padded vertical column layout.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(22.dp),
    backgroundColor: Color? = null,
    borderColor: Color? = null,
    elevation: Dp = 6.dp,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = MaterialTheme.surakshaColors
    val baseModifier = modifier
        .fillMaxWidth()
        .liquidGlass(
            shape = shape,
            colors = colors,
            elevation = elevation,
            customSurfaceColor = backgroundColor
        )

    val finalModifier = if (onClick != null) {
        baseModifier.clickable(onClick = onClick)
    } else {
        baseModifier
    }

    Box(modifier = finalModifier) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

/**
 * Circular or rounded-square tactile glass icon button.
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    size: Dp = 40.dp,
    shape: Shape = RoundedCornerShape(12.dp)
) {
    val colors = MaterialTheme.surakshaColors
    Box(
        modifier = modifier
            .size(size.coerceAtLeast(48.dp))
            .liquidGlass(
                shape = shape,
                colors = colors,
                elevation = 2.dp,
                customSurfaceColor = colors.glassSurfaceSubtle
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint ?: colors.textPrimary,
            modifier = Modifier.size(size * 0.52f)
        )
    }
}

/**
 * Liquid Glass Status Chip with glowing indicator dot.
 */
@Composable
fun GlassStatusChip(
    text: String,
    icon: ImageVector? = null,
    color: Color = MaterialTheme.surakshaColors.success,
    pulsing: Boolean = false,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "glassChipPulse")
    val alpha by if (pulsing) {
        infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseAlpha"
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }

    val shape = RoundedCornerShape(50)
    Surface(
        modifier = modifier,
        shape = shape,
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0.20f))
            )
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = alpha))
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

/**
 * Liquid Glass Settings Tile for preferences and options.
 */
@Composable
fun GlassSettingsTile(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    iconTint: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.surakshaColors
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background((iconTint ?: colors.info).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint ?: colors.info,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                trailing()
            }
        }
    }
}

/**
 * Liquid Glass Top Bar with translucent frosted background and specular border.
 */
@Composable
fun GlassTopBar(
    title: String,
    subtitle: String? = null,
    showThemeToggle: Boolean = true,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    val themeController = LocalThemeController.current
    val colors = MaterialTheme.surakshaColors

    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .drawBehind {
                drawLine(
                    color = colors.glassBorder,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx()
                )
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (navigationIcon != null) {
                navigationIcon()
                Spacer(Modifier.width(8.dp))
            } else {
                BrandLogo(Modifier.size(38.dp))
                Spacer(Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }

            if (showThemeToggle) {
                GlassIconButton(
                    icon = when (themeController.themeMode) {
                        ThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto
                        ThemeMode.DARK -> Icons.Rounded.DarkMode
                        ThemeMode.LIGHT -> Icons.Rounded.LightMode
                    },
                    contentDescription = "Theme: ${themeController.themeMode.name}",
                    onClick = {
                        val nextMode = when (themeController.themeMode) {
                            ThemeMode.SYSTEM -> ThemeMode.DARK
                            ThemeMode.DARK -> ThemeMode.LIGHT
                            ThemeMode.LIGHT -> ThemeMode.SYSTEM
                        }
                        themeController.setMode(nextMode)
                    },
                    size = 38.dp
                )
            }

            if (actions != null) {
                Spacer(Modifier.width(6.dp))
                actions()
            }
        }
    }
}

/**
 * CompositionLocal providing bottom padding required by scrollable screens to clear the floating navigation dock.
 */
val LocalFloatingNavBarPadding = compositionLocalOf { 0.dp }

/**
 * Genuinely Floating Liquid Glass Navigation Dock (SurakshaFloatingNavBar).
 *
 * Implements:
 * 1. Fully rounded pill shape (32.dp / CircleShape)
 * 2. Translucent liquid glass with physical refraction fill, 45-degree specular gradient border, and top specular sheen
 * 3. Elevated ambient shadow separating dock from background content
 * 4. Animated illuminated active pill indicator with official brand accent (SurakshaNet magenta/rose)
 * 5. Scale & color transitions for active vs inactive tabs
 * 6. High-contrast accessible labels and icons with min 52.dp touch target height
 * 7. Haptic feedback on tap
 */
@Composable
fun SurakshaFloatingNavBar(
    tabs: List<NavTab>,
    currentRoute: String?,
    onTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.surakshaColors

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .liquidGlass(
                    shape = RoundedCornerShape(32.dp),
                    colors = colors,
                    elevation = 8.dp,
                    customSurfaceColor = colors.glassBottomNav
                ),
            shape = RoundedCornerShape(32.dp),
            color = Color.Transparent
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp)
                    .selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                tabs.forEach { tab ->
                    key(tab.route) {
                        FloatingNavTabItem(
                            tab = tab,
                            selected = tab.route == currentRoute,
                            colors = colors,
                            onSelect = { onTabSelected(tab.route) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Isolated, skippable tab item for the floating navigation dock.
 * Changes to selected state only invalidate the active and previous tabs, skipping all other tabs.
 */
@Composable
private fun RowScope.FloatingNavTabItem(
    tab: NavTab,
    selected: Boolean,
    colors: SurakshaColors,
    onSelect: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    // Subtle scale transition without bouncy oscillation (150ms FastOutSlowInEasing)
    val animatedScale by animateFloatAsState(
        targetValue = if (selected) 1.03f else 1.0f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "tabScale"
    )

    // Active pill background glow
    val activePillBg = remember(colors.isDark) {
        if (colors.isDark) Color(0xFFE11D48).copy(alpha = 0.20f)
        else Color(0xFFE11D48).copy(alpha = 0.10f)
    }
    val pillFill by animateColorAsState(
        targetValue = if (selected) activePillBg else Color.Transparent,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "tabPillFill"
    )

    // Accent colors matching official SurakshaNet pink/magenta
    val selectedAccent = remember(colors.isDark) {
        if (colors.isDark) Color(0xFFFF8599) else Color(0xFFE11D48)
    }
    val unselectedTint = remember(colors.isDark) {
        if (colors.isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    }

    val iconTint by animateColorAsState(
        targetValue = if (selected) selectedAccent else unselectedTint,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "iconTint"
    )
    val textTint by animateColorAsState(
        targetValue = if (selected) (if (colors.isDark) Color(0xFFFFB3C1) else Color(0xFFBE123C)) else unselectedTint,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "textTint"
    )

    val pillBorderColor = if (selected) {
        if (colors.isDark) Color(0xFFFF758F).copy(alpha = 0.35f) else Color(0xFFE11D48).copy(alpha = 0.22f)
    } else {
        Color.Transparent
    }

    Box(
        modifier = Modifier
            .weight(1f)
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .clip(RoundedCornerShape(26.dp))
            .background(pillFill)
            .border(1.dp, pillBorderColor, RoundedCornerShape(26.dp))
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = {
                    if (!selected) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect()
                    }
                }
            )
            .semantics {
                contentDescription = "${tab.label} tab, ${if (selected) "active" else "inactive"}"
            }
            .heightIn(min = 52.dp)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = iconTint
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = tab.label,
                style = MaterialTheme.typography.labelSmall,
                color = textTint,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Backward compatibility alias for SurakshaFloatingNavBar.
 */
@Composable
fun GlassBottomNavigation(
    tabs: List<NavTab>,
    currentRoute: String?,
    onTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    SurakshaFloatingNavBar(
        tabs = tabs,
        currentRoute = currentRoute,
        onTabSelected = onTabSelected,
        modifier = modifier
    )
}

@Composable
fun SurakshaTopBar(
    title: String,
    subtitle: String? = null,
    showThemeToggle: Boolean = true,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    GlassTopBar(
        title = title,
        subtitle = subtitle,
        showThemeToggle = showThemeToggle,
        navigationIcon = navigationIcon,
        actions = actions
    )
}

@Composable
fun Page(
    title: String,
    subtitle: String? = null,
    showThemeToggle: Boolean = true,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPadding = LocalFloatingNavBarPadding.current.takeIf { it > 0.dp } ?: (bottomInset + 16.dp)

    Column(Modifier.fillMaxSize()) {
        SurakshaTopBar(
            title = title,
            subtitle = subtitle,
            showThemeToggle = showThemeToggle,
            navigationIcon = navigationIcon,
            actions = actions
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            content()
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun SurakshaCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color? = null,
    borderColor: Color? = null,
    shape: RoundedCornerShape = RoundedCornerShape(22.dp),
    contentPadding: PaddingValues = PaddingValues(18.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCard(
        modifier = modifier,
        shape = shape,
        backgroundColor = backgroundColor,
        borderColor = borderColor,
        contentPadding = contentPadding,
        onClick = onClick,
        content = content
    )
}

@Composable
fun StatusCard(
    title: String,
    detail: String,
    icon: ImageVector? = null,
    badgeText: String? = null,
    badgeColor: Color? = null,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.surakshaColors
    SurakshaCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.cardBackgroundSecondary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colors.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
            }
            if (badgeText != null) {
                StatusBadge(
                    text = badgeText,
                    color = badgeColor ?: colors.info
                )
            }
        }
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary
        )
    }
}

@Composable
fun SurakshaMetricCard(
    value: String,
    label: String,
    icon: ImageVector,
    accentColor: Color = MaterialTheme.surakshaColors.info,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.surakshaColors
    GlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        elevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accentColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary
            )
        }
    }
}

@Composable
fun StatusBadge(
    text: String,
    color: Color = MaterialTheme.surakshaColors.success,
    pulsing: Boolean = false,
    modifier: Modifier = Modifier
) {
    GlassStatusChip(
        text = text,
        color = color,
        pulsing = pulsing,
        modifier = modifier
    )
}

@Composable
fun DemoBanner(label: String, active: Boolean = false) {
    val colors = MaterialTheme.surakshaColors
    val bgColor = if (active) colors.emergencyContainer else colors.glassSurfaceSubtle
    val borderColor = if (active) colors.emergency.copy(alpha = 0.4f) else colors.glassBorder
    val textColor = if (active) colors.emergency else colors.textSecondary
    val icon = if (active) Icons.Rounded.Warning else Icons.Rounded.Info

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = textColor,
                modifier = Modifier.weight(1f)
            )
            if (active) {
                StatusBadge(text = "LIVE", color = colors.emergency, pulsing = true)
            }
        }
    }
}

@Composable
fun Action(
    label: String,
    click: () -> Unit
) {
    Action(
        label = label,
        modifier = Modifier,
        enabled = true,
        icon = null,
        isEmergency = false,
        click = click
    )
}

@Composable
fun Action(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    isEmergency: Boolean = false,
    click: () -> Unit
) {
    val colors = MaterialTheme.surakshaColors

    Button(
        onClick = click,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isEmergency) colors.emergency else MaterialTheme.colorScheme.primary,
            contentColor = Color.White
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 0.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun SurakshaOutlinedButton(
    label: String,
    click: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val colors = MaterialTheme.surakshaColors
    OutlinedButton(
        onClick = click,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, colors.cardBorder),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = colors.cardBackground,
            contentColor = colors.textPrimary
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.textPrimary)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SurakshaSwitchTile(
    title: String,
    description: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val colors = MaterialTheme.surakshaColors
    SurakshaCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (checked) colors.success.copy(alpha = 0.15f)
                        else colors.cardBackgroundSecondary
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (checked) colors.success else colors.textSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = colors.success
                )
            )
        }
    }
}

@Composable
fun EmergencySosButton(
    onTrigger: () -> Unit,
    onQuickTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.surakshaColors
    val haptic = LocalHapticFeedback.current
    var isPressing by remember { mutableStateOf(false) }

    // 2-second Hold Progress
    val holdProgress by animateFloatAsState(
        targetValue = if (isPressing) 1f else 0f,
        animationSpec = tween(durationMillis = if (isPressing) 1800 else 250, easing = LinearEasing),
        label = "sosProgress",
        finishedListener = { progress ->
            if (progress >= 1f && isPressing) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                isPressing = false
                onTrigger()
            }
        }
    )

    // Press down scale animation
    val buttonPressScale by animateFloatAsState(
        targetValue = if (isPressing) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "buttonScale"
    )

    // Fade out pulsing rings when user is pressing down to keep focus on hold progress
    val pulseAlphaFactor by animateFloatAsState(
        targetValue = if (isPressing) 0f else 1f,
        animationSpec = tween(180),
        label = "pulseAlphaFactor"
    )

    // Infinite Heartbeat & Pulse Transition
    val infiniteTransition = rememberInfiniteTransition(label = "sosHeartbeatTransition")

    // Continuous 2200ms normalized cycle [0f .. 1f]
    val pulseCycle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseCycle"
    )

    // Gentle anatomical double-beat heartbeat scale (lub-dub)
    val heartbeatScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 2200
                1.000f at 0
                1.026f at 160 using FastOutSlowInEasing  // First beat (lub)
                1.004f at 320 using FastOutSlowInEasing
                1.018f at 480 using FastOutSlowInEasing  // Second beat (dub)
                1.000f at 700 using FastOutSlowInEasing  // Settle to rest
                1.000f at 2200                           // Rest period
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "heartbeatScale"
    )

    val effectiveScale = if (isPressing) buttonPressScale else heartbeatScale

    BoxWithConstraints(
        modifier = modifier
            .widthIn(max = 240.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .semantics {
                role = Role.Button
                onClick(label = "Open SOS confirmation") { onQuickTap(); true }
                contentDescription = "Hold SOS button for two seconds to activate emergency"
            },
        contentAlignment = Alignment.Center
    ) {
        val totalDiameter = maxWidth
        val coreDiameter = totalDiameter * 0.72f
        val coreRadius = coreDiameter / 2f
        val maxExpansion = (totalDiameter - coreDiameter) / 2f

        // Brand colors for the pulsating rings
        val ringColor = if (colors.isDark) Color(0xFFFF4D6D) else Color(0xFFE11D48)
        val ringBaseAlpha = if (colors.isDark) 0.65f else 0.48f

        // Pulse rings and hold-progress Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val coreRadiusPx = coreRadius.toPx()
            val maxExpansionPx = maxExpansion.toPx().coerceAtLeast(28.dp.toPx())

            // 1. Draw 3 concentric, staggered outward-moving heartbeat pulse rings
            if (pulseAlphaFactor > 0.01f) {
                val ringOffsets = floatArrayOf(0.0f, 0.33f, 0.66f)
                for (offset in ringOffsets) {
                    val phase = (pulseCycle + offset) % 1.0f
                    // Smooth cubic expansion
                    val expansionProgress = FastOutSlowInEasing.transform(phase)
                    val currentRadius = coreRadiusPx + (maxExpansionPx * expansionProgress)
                    // Gradual fade out as ring expands outward
                    val alpha = ringBaseAlpha * (1.0f - phase) * (1.0f - phase) * pulseAlphaFactor

                    if (alpha > 0.01f) {
                        // Soft glow fill between expanding wavefronts
                        drawCircle(
                            color = ringColor.copy(alpha = alpha * 0.08f),
                            radius = currentRadius,
                            center = center
                        )
                        // Crisp concentric expanding stroke
                        val strokeWidth = (2.4.dp - (1.0.dp * phase)).toPx()
                        drawCircle(
                            color = ringColor.copy(alpha = alpha),
                            radius = currentRadius,
                            center = center,
                            style = Stroke(width = strokeWidth)
                        )
                    }
                }
            }

            // 2. Static baseline ring when idle
            val baseRingAlpha = if (colors.isDark) 0.22f else 0.15f
            drawCircle(
                color = colors.emergency.copy(alpha = baseRingAlpha),
                radius = coreRadiusPx + 4.dp.toPx(),
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )

            // 3. Hold-to-activate circular progress track
            if (holdProgress > 0f) {
                val progressTrackRadius = coreRadiusPx + 4.dp.toPx()
                drawArc(
                    color = colors.emergency,
                    startAngle = -90f,
                    sweepAngle = 360f * holdProgress,
                    useCenter = false,
                    topLeft = Offset(center.x - progressTrackRadius, center.y - progressTrackRadius),
                    size = androidx.compose.ui.geometry.Size(progressTrackRadius * 2, progressTrackRadius * 2),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        // Inner Hero Circle - Solid rich red emergency core with heartbeat pulse
        Box(
            modifier = Modifier
                .size(coreDiameter)
                .graphicsLayer {
                    scaleX = effectiveScale
                    scaleY = effectiveScale
                }
                .shadow(
                    elevation = if (isPressing) 3.dp else 8.dp,
                    shape = CircleShape,
                    ambientColor = colors.emergency,
                    spotColor = colors.emergency
                )
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            colors.emergencyGradientStart,
                            colors.emergencyGradientEnd,
                            Color(0xFF7F1D1D)
                        )
                    )
                )
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.55f),
                            Color.White.copy(alpha = 0.20f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPressing = true
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            tryAwaitRelease()
                            isPressing = false
                        },
                        onTap = {
                            onQuickTap()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "SOS",
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    ),
                    color = Color.White
                )
                Text(
                    text = if (isPressing) "HOLDING..." else "HOLD 2S",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = Color.White.copy(alpha = 0.95f)
                )
            }
        }
    }
}

@Composable
fun SurakshaMapContainer(
    modifier: Modifier = Modifier,
    height: Dp = 190.dp,
    title: String = "1.0 KM EMERGENCY RADIUS",
    incidentActive: Boolean = false,
    responderCount: Int = 1
) {
    val colors = MaterialTheme.surakshaColors
    val infiniteTransition = rememberInfiniteTransition(label = "mapRadar")
    val radarPulse by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "mapPulse"
    )

    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        shape = RoundedCornerShape(24.dp),
        elevation = 6.dp
    ) {
        Box(modifier = Modifier.fillMaxSize().background(colors.mapBackground)) {
            // Simulated Vector Map Grid
            Canvas(modifier = Modifier.fillMaxSize()) {
                val step = 32.dp.toPx()
                val gridColor = colors.mapGrid
                var x = 0f
                while (x < size.width) {
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    x += step
                }
                var y = 0f
                while (y < size.height) {
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    y += step
                }

                val center = Offset(size.width * 0.45f, size.height * 0.52f)
                // Concentric Range Rings (250m, 500m, 1000m)
                drawCircle(color = gridColor, radius = 40.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
                drawCircle(color = gridColor, radius = 75.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
                drawCircle(
                    color = if (incidentActive) colors.emergency.copy(alpha = 0.35f) else gridColor,
                    radius = 110.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx())
                )

                // Radar scanning pulse if incident active
                if (incidentActive) {
                    drawCircle(
                        color = colors.emergency.copy(alpha = (1f - radarPulse) * 0.25f),
                        radius = 110.dp.toPx() * radarPulse,
                        center = center
                    )
                }

                // Victim beacon
                drawCircle(color = colors.emergency.copy(alpha = 0.25f), radius = 16.dp.toPx(), center = center)
                drawCircle(color = colors.emergency, radius = 6.dp.toPx(), center = center)
                drawCircle(color = Color.White, radius = 2.5.dp.toPx(), center = center)

                // Simulated Responder Pins
                if (incidentActive && responderCount > 0) {
                    val resp1 = Offset(center.x + 55.dp.toPx(), center.y - 35.dp.toPx())
                    drawCircle(color = colors.success.copy(alpha = 0.3f), radius = 10.dp.toPx(), center = resp1)
                    drawCircle(color = colors.success, radius = 4.dp.toPx(), center = resp1)

                    // Connecting dashed line
                    drawLine(
                        color = colors.success.copy(alpha = 0.6f),
                        start = resp1,
                        end = center,
                        strokeWidth = 2.dp.toPx()
                    )
                }
            }

            // Overlay Top Chip
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusBadge(
                    text = title,
                    color = if (incidentActive) colors.emergency else colors.info,
                    pulsing = incidentActive
                )
            }

            // Map Controls Overlay
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    color = colors.cardBackground.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, colors.cardBorder),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.MyLocation,
                            contentDescription = "My location",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Bottom Map Legend
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(8.dp),
                color = colors.cardBackground.copy(alpha = 0.85f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, colors.cardBorder.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(colors.emergency))
                        Text("You (Live)", style = MaterialTheme.typography.labelSmall, color = colors.textPrimary)
                    }
                    if (incidentActive) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(colors.success))
                            Text("Responder (En Route)", style = MaterialTheme.typography.labelSmall, color = colors.textPrimary)
                        }
                    }
                    Text("1 km zone", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                }
            }
        }
    }
}

@Composable
fun SurakshaPinPad(
    modifier: Modifier = Modifier,
    pinLength: Int = 6,
    expectedPin: String? = null,
    isVerifying: Boolean = false,
    errorMessage: String? = null,
    cooldownSeconds: Int = 0,
    onPinSubmit: ((String) -> Unit)? = null,
    onPinSuccess: (() -> Unit)? = null,
    onKeepActive: () -> Unit,
    onForgotPin: (() -> Unit)? = null
) {
    val colors = MaterialTheme.surakshaColors
    var enteredPin by remember { mutableStateOf("") }
    var attemptsLeft by remember { mutableIntStateOf(5) }
    var isError by remember { mutableStateOf(false) }
    val shakeOffset by animateFloatAsState(
        targetValue = if (isError) 10f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy),
        label = "shake"
    )

    LaunchedEffect(isError) {
        if (isError) {
            delay(400)
            isError = false
            enteredPin = ""
        }
    }

    LaunchedEffect(errorMessage) {
        if (!errorMessage.isNullOrBlank()) {
            isError = true
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(colors.cardBackgroundSecondary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                tint = colors.emergency,
                modifier = Modifier.size(28.dp)
            )
        }

        Text(
            text = "Enter Safety PIN",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary
        )

        Text(
            text = "Enter your $pinLength-digit Safety PIN to authenticate incident resolution.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        // PIN Indicator Dots
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.offset(x = shakeOffset.dp)
        ) {
            for (i in 0 until pinLength) {
                val filled = i < enteredPin.length
                val dotColor = if (isError) colors.emergency else if (filled) colors.success else colors.cardBorder
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(if (filled) dotColor else Color.Transparent)
                        .border(2.dp, dotColor, CircleShape)
                )
            }
        }

        // Lockout Cooldown Notice
        if (cooldownSeconds > 0) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.emergencyContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.HourglassTop,
                        contentDescription = null,
                        tint = colors.emergency,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Temporary Lockout: $cooldownSeconds seconds remaining",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.emergency
                    )
                }
            }
        }

        // Error message / feedback
        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.labelMedium,
                color = colors.emergency,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
        }

        // Verifying Indicator
        if (isVerifying) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = colors.emergency
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Verifying with security server...",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
        }

        // Numeric Keypad
        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("CLEAR", "0", "DEL")
        )

        val isLocked = cooldownSeconds > 0 || isVerifying

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            keys.forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    row.forEach { key ->
                        GlassSurface(
                            onClick = {
                                if (isError || isLocked) return@GlassSurface
                                when (key) {
                                    "CLEAR" -> enteredPin = ""
                                    "DEL" -> if (enteredPin.isNotEmpty()) enteredPin = enteredPin.dropLast(1)
                                    else -> {
                                        if (enteredPin.length < pinLength) {
                                            enteredPin += key
                                            if (enteredPin.length == pinLength) {
                                                if (onPinSubmit != null) {
                                                    val submitted = enteredPin
                                                    enteredPin = ""
                                                    onPinSubmit(submitted)
                                                } else if (expectedPin != null) {
                                                    if (enteredPin == expectedPin || expectedPin.isBlank()) {
                                                        onPinSuccess?.invoke()
                                                    } else {
                                                        attemptsLeft--
                                                        isError = true
                                                    }
                                                } else {
                                                    onPinSuccess?.invoke()
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.size(52.dp).semantics {
                                contentDescription = when (key) {
                                    "DEL" -> "Delete last digit"
                                    "CLEAR" -> "Clear PIN"
                                    else -> "Digit $key"
                                }
                            },
                            shape = CircleShape,
                            elevation = if (isLocked) 0.dp else 4.dp,
                            backgroundColor = if (isLocked) colors.glassSurfaceSubtle.copy(alpha = 0.25f) else colors.glassSurface
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                if (key == "DEL") {
                                    Icon(
                                        imageVector = Icons.Rounded.Backspace,
                                        contentDescription = "Delete",
                                        tint = if (isLocked) colors.textTertiary else colors.textPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else {
                                    Text(
                                        text = key,
                                        style = if (key.length > 1) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isLocked) colors.textTertiary else colors.textPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Secondary Action: Keep SOS Active
        SurakshaOutlinedButton(
            label = "Keep SOS Active",
            icon = Icons.Rounded.Shield,
            click = onKeepActive
        )

        // Forgot PIN Link
        if (onForgotPin != null) {
            TextButton(onClick = onForgotPin, enabled = !isVerifying) {
                Text(
                    text = "Forgot Safety PIN?",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.info
                )
            }
        }
    }
}
