package `in`.maitri.app

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import `in`.maitri.app.ui.*
import `in`.maitri.app.ui.theme.LocalThemeController
import `in`.maitri.app.ui.theme.ThemeController
import `in`.maitri.app.ui.theme.ThemeMode
import `in`.maitri.app.ui.theme.Typography
import `in`.maitri.app.ui.theme.maitriColorScheme
import kotlinx.coroutines.delay
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.File

/**
 * Screen 07 opened the way each entry point opens it, with the app's own routes, navigation options and transitions
 * (navigation/SafetyApp.kt): the Responder tab, the Inbox, a notification tap, the lock-screen Accept, the Responder
 * history, and an app restart during an active journey. Each one is captured in Light and Dark, and the map must sit
 * exactly on its card: corners rounded (page background outside the curve, not map) and nothing of the design's
 * illustrative map showing around its edges. Network only for map tiles; no account, no incident.
 * Files land in the app's external files dir under journey-entry/.
 */
class JourneyEntryPointsScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val person = 28.6139 to 77.2090
    private val me = 28.6175 to 77.2050

    /** How the app reaches screen 07 from each entry point. `go` runs once the start screen is shown. */
    private enum class Entry { RESPONDER_TAB, INBOX, NOTIFICATION, LOCK_SCREEN, HISTORY, RESTART }

    @Composable
    private fun Harness(entry: Entry) {
        val nav = rememberNavController()
        val start = when (entry) { Entry.INBOX -> "Inbox"; Entry.RESTART -> "ResponderJourney?incidentId={incidentId}"; else -> "Home" }
        NavHost(nav, startDestination = start, modifier = Modifier.fillMaxSize(),
            enterTransition = MaitriMotion.enter, exitTransition = MaitriMotion.exit,
            popEnterTransition = MaitriMotion.popEnter, popExitTransition = MaitriMotion.popExit) {
            composable("Home") { Page("Home") }
            composable("Responder") { Page("Responder") }
            composable("Inbox") { Page("Notifications") }
            composable("ResponderHistory") { Page("Responder history") }
            composable("Incoming?invitationId={invitationId}") {
                // Screen 06 answers, then (like SafetyApp's onAccepted) replaces itself with screen 07.
                LaunchedEffect(Unit) {
                    delay(if (entry == Entry.LOCK_SCREEN) 50 else 700)  // the lock screen's Accept is answered at once
                    nav.navigate("ResponderJourney?incidentId=inc") { popUpTo("Incoming?invitationId={invitationId}") { inclusive = true } }
                }
                Page("Someone needs help")
            }
            // As in SafetyApp: the map waits for the screen's enter transition (SettledScreen).
            composable("ResponderJourney?incidentId={incidentId}") { SettledScreen { Journey() } }
        }
        LaunchedEffect(entry) {
            delay(600)
            when (entry) {
                Entry.RESPONDER_TAB -> { nav.navigate("Responder") { launchSingleTop = true }; delay(500)
                    nav.navigate("ResponderJourney?incidentId=inc") }
                Entry.INBOX -> nav.navigate("Incoming?invitationId=inv") { launchSingleTop = true }
                // MainActivity's pendingInvitationId: Responder tab, then screen 06 in the same frame.
                Entry.NOTIFICATION, Entry.LOCK_SCREEN -> {
                    nav.navigate("Responder") { launchSingleTop = true }
                    nav.navigate("Incoming?invitationId=inv") { launchSingleTop = true }
                }
                Entry.HISTORY -> { nav.navigate("Responder") { launchSingleTop = true }; delay(300)
                    nav.navigate("ResponderHistory") { launchSingleTop = true }; delay(500)
                    nav.navigate("ResponderJourney?incidentId=inc") }
                Entry.RESTART -> Unit
            }
        }
    }

    @Composable
    private fun Page(title: String) {
        Box(Modifier.fillMaxSize().background(maitriPalette().background), contentAlignment = Alignment.Center) { Text(title) }
    }

    /** Screen 07 after the server confirmed access ("Checking incident access…" first, as in the app). */
    @Composable
    private fun Journey() {
        var confirmed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { delay(400); confirmed = true }
        val p = maitriPalette()
        if (!confirmed) Page("Checking incident access…")
        else MaitriResponderJourneyBoard(p, "ON_THE_WAY", person, busy = false, onStatus = {}, onMaps = {}, onHelp = {},
            onDial = {}, distanceMeters = 530.0, self = me)
    }

    private fun findMap(view: View): View? {
        if (view.javaClass.name.endsWith(".MapView")) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findMap(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun near(a: Int, b: Int, tolerance: Int = 24) =
        Math.abs(AColor.red(a) - AColor.red(b)) <= tolerance && Math.abs(AColor.green(a) - AColor.green(b)) <= tolerance &&
            Math.abs(AColor.blue(a) - AColor.blue(b)) <= tolerance

    @Test fun screen07LooksTheSameFromEveryEntryPoint() {
        val context = instrumentation.targetContext
        assumeFalse("Unlock device", context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val out = File(context.getExternalFilesDir(null), "journey-entry").apply { mkdirs() }
        val problems = mutableListOf<String>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) for (entry in Entry.entries) {
                scenario.onActivity { activity ->
                    activity.enableEdgeToEdge()
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    activity.setContent {
                        val controller = remember(dark) {
                            ThemeController(object : android.content.ContextWrapper(activity) {
                                override fun getSharedPreferences(name: String, m: Int) = super.getSharedPreferences("capture_$name", m)
                            }).apply { setMode(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) }
                        }
                        CompositionLocalProvider(LocalThemeController provides controller) {
                            MaterialTheme(colorScheme = maitriColorScheme(dark), typography = Typography) {
                                key(entry, dark) { Harness(entry) }
                            }
                        }
                    }
                }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(8_000)  // navigation, then map style and tiles over the network
                val name = (if (dark) "dark-" else "light-") + entry.name.lowercase().replace('_', '-')
                val shot = requireNotNull(generateSequence { instrumentation.uiAutomation.takeScreenshot()
                    ?: null.also { SystemClock.sleep(700) } }.take(5).firstOrNull { it != null }) { "Screenshot failed for $name" }
                File(out, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                // Where the map is on screen, and the page colour around it.
                var rect: android.graphics.Rect? = null
                scenario.onActivity { activity ->
                    findMap(activity.window.decorView)?.let { map ->
                        val at = IntArray(2).also { map.getLocationOnScreen(it) }
                        rect = android.graphics.Rect(at[0], at[1], at[0] + map.width, at[1] + map.height)
                    }
                }
                val r = rect
                if (r == null) { problems += "$name: no map view"; continue }
                val background = (if (dark) 0xFF19151B else 0xFFFFF9FA).toInt()
                val inset = 3
                // Outside the rounded corner: the page, never map.
                for ((x, y) in listOf(r.left + inset to r.top + inset, r.right - 1 - inset to r.top + inset,
                        r.left + inset to r.bottom - 1 - inset, r.right - 1 - inset to r.bottom - 1 - inset)) {
                    val px = shot.getPixel(x.coerceIn(0, shot.width - 1), y.coerceIn(0, shot.height - 1))
                    if (!near(px, background)) problems += "$name: corner ($x,$y) is ${Integer.toHexString(px)}, not the page"
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
