package `in`.maitri.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.maitri.app.R
import `in`.maitri.app.tr
import kotlin.math.PI
import kotlin.math.sin

/**
 * "Getting things ready" (design-reference/loading-light.png, 390 x 844 frame, 1 unit = 1 dp): the logo tile in a soft
 * pink circle that pulses outward like the SOS beacon while the logo breathes (0.94 <-> 1.0), the wordmark, the title,
 * three accent dots in a ~900 ms wave, and the footer. Static when the system "Remove animations" setting is on.
 */
@Composable
internal fun MaitriStartupScreen(animate: Boolean = !reduceMotion()) {
    val p = maitriPalette()
    val title = tr("Getting things ready")
    BoxWithConstraints(Modifier.fillMaxSize().background(p.background)
        .semantics { contentDescription = title; liveRegion = LiveRegionMode.Polite }) {
        // Vertical positions follow the design frame, scaled to this screen's height (horizontal is centred).
        val scale = maxHeight / 844.dp
        fun y(design: Float): Dp = (design * scale).dp
        val loop = if (animate) rememberInfiniteTransition(label = "startup") else null
        val breath = loop?.animateFloat(0.94f, 1f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
        val pulse = loop?.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearOutSlowInEasing)), label = "pulse")
        val wave = loop?.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "dots")

        // Soft pink circle (186 dp, centred at y 313) with the beacon ring growing out of it.
        Box(Modifier.align(Alignment.TopCenter).offset(y = y(313f) - 130.dp).size(260.dp), contentAlignment = Alignment.Center) {
            if (pulse != null) {
                val t = pulse.value
                Canvas(Modifier.matchParentSize()) {
                    val base = 93.dp.toPx()
                    drawCircle(p.card.copy(alpha = (1f - t) * 0.9f), radius = base + (size.minDimension / 2 - base) * t)
                }
            }
            Box(Modifier.size(186.dp).background(p.subtle, CircleShape))
            Image(painterResource(R.drawable.maitri_logo), null, Modifier.size(82.dp).scale(breath?.value ?: 1f))
        }
        // Wordmark, title, line, dots.
        Column(Modifier.align(Alignment.TopCenter).offset(y = y(436f)), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(if (p.dark) R.drawable.maitri_wordmark_dark else R.drawable.maitri_wordmark_light),
                null, Modifier.size(96.dp, 28.dp))
            Spacer(Modifier.height(y(30f)))
            Text(title, color = p.primary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(y(10f)))
            Text(tr("A moment, while we prepare your space."), color = p.secondary, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp))
            Spacer(Modifier.height(y(30f)))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(16.dp)) {
                repeat(3) { i ->
                    // Each dot rises and brightens a third of a cycle after the one before it.
                    val phase = wave?.let { ((it.value - i / 6f) % 1f + 1f) % 1f }
                    val lift = phase?.let { sin(it * 2 * PI).toFloat().coerceAtLeast(0f) } ?: 0f
                    Box(Modifier.offset(y = (-4 * lift).dp).size(6.dp).alpha(0.45f + 0.55f * (if (phase == null) 1f else lift))
                        .background(p.accent, CircleShape))
                }
            }
        }
        Text(stringResource(R.string.maitri_supporting), Modifier.align(Alignment.TopCenter).offset(y = y(733f)),
            color = p.secondary, fontSize = 12.sp)
    }
}
