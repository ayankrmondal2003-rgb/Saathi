package `in`.maitri.app.ui

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
