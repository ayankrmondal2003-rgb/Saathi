package `in`.maitri.app.ui

import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.foundation.clickable

import androidx.compose.foundation.background

import androidx.compose.ui.draw.blur

import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.maitri.app.R
import `in`.maitri.app.tr
import kotlinx.coroutines.delay

/** Waits shorter than this show nothing, so a fast load never flickers a loader. */
internal const val LOADER_DELAY_MS = 300L

/** True when the system "Remove animations" setting (animator scale 0) is on. */
@Composable
internal fun reduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * Maitri's loading animation: the screen-01 logo breathing (0.92 <-> 1.0) with two soft pink rings fading outward like
 * the SOS beacon, on a 1.2 s loop. Shown only after [delayMillis]; a static logo when animations are removed.
 * [message] (the existing status text) sits under it. Never wrap the SOS or Call 112 button in a loader.
 */
@Composable
internal fun MaitriLoader(modifier: Modifier = Modifier, message: String? = null, logoSize: Dp = 72.dp,
                          delayMillis: Long = LOADER_DELAY_MS, animate: Boolean = !reduceMotion()) {
    var visible by remember { mutableStateOf(delayMillis <= 0) }
    LaunchedEffect(delayMillis) { if (delayMillis > 0) { delay(delayMillis); visible = true } }
    if (!visible) { Box(modifier); return }
    val p = maitriPalette()
    val label = message ?: tr("Loading…")
    Column(modifier.semantics { contentDescription = label; liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(logoSize * 2), contentAlignment = Alignment.Center) {
            if (animate) {
                val loop = rememberInfiniteTransition(label = "maitriLoader")
                val wave by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "ring")
                val breath by loop.animateFloat(0.92f, 1f,
                    infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
                // Soft pink in both themes: the accent at low alpha reads as blush on light and as a glow on dark.
                val ring = p.accent
                Canvas(Modifier.matchParentSize()) {
                    val base = size.minDimension / 4f
                    for (offset in listOf(0f, 0.5f)) {
                        val t = (wave + offset) % 1f
                        drawCircle(ring.copy(alpha = (1f - t) * if (p.dark) 0.45f else 0.35f),
                            radius = base * (1f + t), style = Stroke(width = (if (p.dark) 3f else 4f) * density))
                    }
                }
                Image(painterResource(R.drawable.maitri_logo), null, Modifier.size(logoSize).scale(breath))
            } else Image(painterResource(R.drawable.maitri_logo), null, Modifier.size(logoSize))
        }
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(message, color = p.secondary, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp))
        }
    }
}

/**
 * Whether a busy overlay shows: after [delayMillis] of [busy] (0 = from the first frame, for a screen that opens
 * loading), and then for at least [minVisibleMillis], so a fast load doesn't flash it.
 */
@Composable
internal fun rememberDelayedBusy(busy: Boolean, delayMillis: Long = LOADER_DELAY_MS, minVisibleMillis: Long = 400): Boolean {
    var shown by remember { mutableStateOf(busy && delayMillis <= 0) }
    var shownAt by remember { mutableLongStateOf(if (shown) android.os.SystemClock.uptimeMillis() else 0L) }
    LaunchedEffect(busy) {
        if (busy) {
            if (!shown) { delay(delayMillis); shown = true; shownAt = android.os.SystemClock.uptimeMillis() }
        } else if (shown) {
            delay((minVisibleMillis - (android.os.SystemClock.uptimeMillis() - shownAt)).coerceAtLeast(0))
            shown = false
        }
    }
    return shown
}

/** Blurs the page behind a busy overlay (Android 12+; older phones get the overlay's tint only). */
internal fun Modifier.blurWhenBusy(busy: Boolean): Modifier =
    if (busy) this.blur(14.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded) else this

/**
 * Loading over a page: the page stays visible but blurred, and only the Maitri logo beats on top (two quick beats,
 * then a rest, about once a second; static with "Remove animations"). Taps on the page are blocked while it shows.
 */
@Composable
internal fun MaitriBusyOverlay(visible: Boolean, label: String = tr("Loading…"), animate: Boolean = !reduceMotion()) {
    if (!visible) return
    val p = maitriPalette()
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) { fade.animateTo(1f, tween(180)) }
    val tint = if (android.os.Build.VERSION.SDK_INT >= 31) 0.28f else 0.75f
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(p.background.copy(alpha = tint))
        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null) { }
        .semantics { contentDescription = label; liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center) {
        val beat = if (animate) rememberInfiniteTransition(label = "heartbeat").animateFloat(1f, 1f,
            infiniteRepeatable(keyframes {
                durationMillis = 1100
                1f at 0
                1.14f at 120 using FastOutSlowInEasing
                1f at 260
                1.08f at 380 using FastOutSlowInEasing
                1f at 520
            }), label = "beat").value else 1f
        Image(painterResource(R.drawable.maitri_logo), null, Modifier.size(88.dp).scale(beat))
    }
}
