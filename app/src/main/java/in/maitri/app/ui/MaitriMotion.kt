package `in`.maitri.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.delay

/**
 * One motion system for the app. Durations stay short; with the system "Remove animations" setting, Compose skips
 * these animations (it follows the animator duration scale) and the loaders show static artwork.
 * - tab switches: crossfade, 200 ms
 * - opening a screen: slide + fade, 250 ms, emphasized easing (back reverses it)
 * - dialogs: fade + scale in from 0.95, 180 ms
 * - cards and buttons: press scale to 0.97 with spring feedback
 * - list items: gentle fade-in on first load
 */
object MaitriMotion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val TAB_MS = 200
    const val SCREEN_MS = 250
    const val DIALOG_MS = 180
    val TABS = setOf("Home", "Contacts", "Responder", "Profile")

    private fun tabSwitch(from: NavBackStackEntry, to: NavBackStackEntry) =
        from.destination.route in TABS && to.destination.route in TABS

    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (tabSwitch(initialState, targetState)) fadeIn(tween(TAB_MS, easing = FastOutSlowInEasing))
        else slideInHorizontally(tween(SCREEN_MS, easing = Emphasized)) { it / 8 } + fadeIn(tween(SCREEN_MS, easing = Emphasized))
    }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (tabSwitch(initialState, targetState)) fadeOut(tween(TAB_MS, easing = FastOutSlowInEasing))
        else slideOutHorizontally(tween(SCREEN_MS, easing = Emphasized)) { -it / 16 } + fadeOut(tween(SCREEN_MS / 2))
    }
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (tabSwitch(initialState, targetState)) fadeIn(tween(TAB_MS, easing = FastOutSlowInEasing))
        else slideInHorizontally(tween(SCREEN_MS, easing = Emphasized)) { -it / 16 } + fadeIn(tween(SCREEN_MS, easing = Emphasized))
    }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (tabSwitch(initialState, targetState)) fadeOut(tween(TAB_MS, easing = FastOutSlowInEasing))
        else slideOutHorizontally(tween(SCREEN_MS, easing = Emphasized)) { it / 8 } + fadeOut(tween(SCREEN_MS / 2))
    }
}

/** Cards and buttons: scale to 0.97 while pressed, springing back. Doesn't consume the touch, so clicks still work. */
fun Modifier.pressScale(): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "press")
    this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            pressed = true
            waitForUpOrCancellation()
            pressed = false
        }
    }.graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Dialogs: fade and scale in from 0.95 over 180 ms. */
fun Modifier.dialogEnter(): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(MaitriMotion.DIALOG_MS, easing = MaitriMotion.Emphasized)) }
    graphicsLayer {
        alpha = progress.value
        val s = 0.95f + 0.05f * progress.value
        scaleX = s; scaleY = s
    }
}

/** List items: fade in on first load, each a little after the one before (capped, so long lists don't wait). */
fun Modifier.fadeInOnFirstLoad(index: Int): Modifier = composed {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index.coerceAtMost(5) * 30).toLong())
        alpha.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
    }
    graphicsLayer { this.alpha = alpha.value }
}
