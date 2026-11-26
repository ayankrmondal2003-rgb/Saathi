package `in`.maitri.app.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Whether the screen around a map has finished its enter transition. A map is drawn on its own surface, which doesn't
 * follow the slide and fade of a screen transition (or a clip): created mid-transition it can end up offset from its
 * card, uncovering the design's illustrative map underneath and spilling past the rounded corners. Maps are therefore
 * created only once the screen has settled. Screens outside a NavHost (dialogs, previews, tests) are always settled.
 */
internal val LocalScreenSettled = compositionLocalOf { true }

/** Wraps a NavHost destination that shows a map: [LocalScreenSettled] turns true once its enter transition is over. */
@Composable
internal fun AnimatedContentScope.SettledScreen(content: @Composable () -> Unit) {
    var settled by remember { mutableStateOf(false) }
    if (!settled && transition.currentState == EnterExitState.Visible && transition.targetState == EnterExitState.Visible)
        SideEffect { settled = true }
    CompositionLocalProvider(LocalScreenSettled provides settled) { content() }
}

/**
 * Every map in Maitri. The caller gives a fixed size (the card it sits in); the map fills exactly that, clipped to
 * [shape], on an opaque card, so nothing drawn under it (such as the illustrative map in the 06/07/08 artwork) can
 * show at its edges. The rounded corners are also painted over with [surround], the page colour around the card,
 * because a map surface ignores Compose clipping on some Android versions.
 */
@Composable
internal fun MaitriMapCard(markers: List<MapMarker>, modifier: Modifier, emptyLabel: String = "",
                           accuracyMeters: Double? = null, rangeMeters: Double? = null, alwaysShowAccuracy: Boolean = false,
                           surround: Color = maitriPalette().background, shape: Shape = RoundedCornerShape(24.dp)) {
    val p = maitriPalette()
    val settled = LocalScreenSettled.current
    Box(modifier.testTag(MAP_CARD_TAG)) {
        Box(Modifier.fillMaxSize().clip(shape).background(p.card), contentAlignment = Alignment.Center) {
            if (settled) MaitriMap(markers, emptyLabel, Modifier.fillMaxSize(), accuracyMeters, rangeMeters, alwaysShowAccuracy)
            else MaitriLoader(logoSize = 40.dp)
        }
        // Outside the clip, so it can cover whatever a map surface draws past the curve.
        CornerMask(shape, surround)
    }
}

internal const val MAP_CARD_TAG = "maitri-map-card"

/** Paints [color] everywhere outside [shape]: the card's corners, whatever the map below draws. */
@Composable
private fun CornerMask(shape: Shape, color: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val outline = shape.createOutline(size, layoutDirection, this)
        val mask = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, size.width, size.height))
            addOutline(outline)
        }
        drawPath(mask, color)
    }
}
