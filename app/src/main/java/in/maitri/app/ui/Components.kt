package `in`.maitri.app.ui

import `in`.maitri.app.tr

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
import `in`.maitri.app.ui.theme.*
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
    colors: MaitriColors,
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
    val colors = MaterialTheme.maitriColors
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
    val colors = MaterialTheme.maitriColors
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
    val colors = MaterialTheme.maitriColors
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
    color: Color = MaterialTheme.maitriColors.success,
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
    val colors = MaterialTheme.maitriColors

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
                    contentDescription = tr("Theme: {0}", themeController.themeMode.name),
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

@Composable
fun MaitriTopBar(
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
        MaitriTopBar(
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
fun MaitriCard(
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
    val colors = MaterialTheme.maitriColors
    MaitriCard(modifier = modifier) {
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
fun StatusBadge(
    text: String,
    color: Color = MaterialTheme.maitriColors.success,
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
    val colors = MaterialTheme.maitriColors

    Button(
        onClick = click,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isEmergency) colors.emergency else MaterialTheme.colorScheme.primary,
            contentColor = if (isEmergency) Color.White else MaterialTheme.colorScheme.onPrimary
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
fun MaitriOutlinedButton(
    label: String,
    click: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val colors = MaterialTheme.maitriColors
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

