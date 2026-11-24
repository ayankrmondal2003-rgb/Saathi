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

@Composable
internal fun BoardLabel(x: Int, y: Int, width: Int, label: String, color: Color, size: Int = 12) {
    Text(label, Modifier.offset(x.dp, (y - 28).dp).width(width.dp), color = color,
        fontSize = size.sp, lineHeight = (size + 3).sp, maxLines = 2,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
}

@Composable
internal fun BoardAction(x: Int, y: Int, width: Int, height: Int, label: String,
                         enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.offset(x.dp, (y - 28).dp).size(width.dp, height.dp)
        .semantics { contentDescription = label }.clickable(enabled = enabled, role = Role.Button, onClick = onClick))
}
