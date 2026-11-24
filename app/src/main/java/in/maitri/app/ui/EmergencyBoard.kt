package `in`.maitri.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*

/** Uses the SVG's 390-unit width. Coordinates include the reference's 28-unit status inset. */
@Composable
internal fun EmergencyBoard(art: Int, description: String, height: Int = 729, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(maitriPalette().background).statusBarsPadding()
        .navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
        val width = maxWidth.coerceAtMost(520.dp)
        val density = LocalDensity.current
        val scaled = Density(density.density * width.value / 390f, density.fontScale)
        Column(Modifier.width(width).verticalScroll(rememberScrollState())) {
            CompositionLocalProvider(LocalDensity provides scaled) {
                Box(Modifier.fillMaxWidth().height(height.dp)) {
                    Image(painterResource(art), description, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    content()
                }
                if (height == 729) Spacer(Modifier.height(80.dp))
            }
        }
    }
}

/**
 * Text in a fixed box (the design boards). At the phone's normal text size it is exactly `size`. With larger text
 * (Settings → Font size) it grows with the user's setting as far as the box allows, then stops: it never drops below
 * the design's size at 100% and is only cut with "…" if even that doesn't fit.
 */
@Composable
internal fun FitText(text: String, modifier: Modifier, color: Color, size: Float, lineHeight: Float? = null,
                     weight: androidx.compose.ui.text.font.FontWeight? = null,
                     align: androidx.compose.ui.text.style.TextAlign? = null, maxLines: Int = 1,
                     letterSpacing: TextUnit = TextUnit.Unspecified) {
    val scale = LocalDensity.current.fontScale
    // Line height relative to the font size, so it follows the auto-sized text; null keeps Compose's default.
    Text(text, modifier, color = color, fontSize = size.sp, lineHeight = lineHeight?.let { (it / size).em } ?: TextUnit.Unspecified,
        fontWeight = weight,
        textAlign = align, letterSpacing = letterSpacing, maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        autoSize = if (scale > 1.01f) androidx.compose.foundation.text.TextAutoSize.StepBased(
            minFontSize = (size / scale).sp, maxFontSize = size.sp, stepSize = 0.25.sp) else null)
}

/**
 * Texts with a line break placed for the design's width at normal text size. With larger text that break would
 * strand a word on its own line, so it becomes a space and the text wraps naturally.
 */
@Composable
internal fun designBreaks(text: String): String =
    if (LocalDensity.current.fontScale > 1.01f) text.replace('\n', ' ') else text

@Composable
internal fun BoardLabel(x: Int, y: Int, width: Int, label: String, color: Color, size: Int = 12) {
    FitText(label, Modifier.offset(x.dp, (y - 28).dp).width(width.dp), color, size.toFloat(), size + 3f, maxLines = 2)
}

@Composable
internal fun BoardAction(x: Int, y: Int, width: Int, height: Int, label: String,
                         enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.offset(x.dp, (y - 28).dp).size(width.dp, height.dp)
        .semantics { contentDescription = label }.clickable(enabled = enabled, role = Role.Button, onClick = onClick))
}
