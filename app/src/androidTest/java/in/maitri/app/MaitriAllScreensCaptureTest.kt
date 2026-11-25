package `in`.maitri.app

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import `in`.maitri.app.data.EmergencyContact
import `in`.maitri.app.data.SosIncident
import `in`.maitri.app.ui.*
import `in`.maitri.app.ui.theme.LocalThemeController
import `in`.maitri.app.ui.theme.ThemeController
import `in`.maitri.app.ui.theme.ThemeMode
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.File

/**
 * Captures screens 01-14 in Light and Dark for side-by-side review against design-reference/crops, and again at
 * 150% text ("large-light-02.png", ...) the way phones with a big font setting see them.
 * Network-free: stateless boards or screens whose loaders fail closed without a session. Never creates an incident.
 */
class MaitriAllScreensCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Composable
    private fun Docked(route: String, content: @Composable () -> Unit) {
        // Mirrors MainGraph: dock floats at the bottom and content reserves its height.
        CompositionLocalProvider(LocalFloatingNavBarPadding provides 116.dp) {
            Box(Modifier.fillMaxSize()) {
                content()
                MaitriBottomBar(route, {}, Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    private fun screens(p: () -> MaitriPalette): List<Pair<String, @Composable () -> Unit>> = listOf(
        "01" to { MaitriWelcomeContent(p().dark) {} },
        "02" to { Docked("Home") { MaitriHomeContent(p(), 2, false, {}, {}, {}, {}, {}) } },
        "03" to { MaitriLoginContent(p(), 0, {}, "", {}, "", {}, false, {}, {}, {}, false, null) },
        "04" to { MaitriVolunteerContent(p(), "HELP", {}, emptyMap(), {}, {}, false, {}, {}, null) },
        "05" to { Docked("Contacts") { MaitriCircleContent(listOf(
            EmergencyContact("c1", displayName = "Test Contact A", phoneNumber = "+91 90000 00001", isPrimary = true),
            EmergencyContact("c2", displayName = "Test Contact B", phoneNumber = "+91 90000 00002")),
            p(), { _, _, _, _, _, _ -> null }, {}, { _, _ -> },
            linkedContactIds = setOf("c1"), uid = "capture-no-session") } },
        "06" to { MaitriIncomingContent(p(), "Nearby emergency", "Distance unavailable", "Arrival time unavailable",
            false, null, {}, {}) },
        // Synthetic test coordinate (New Delhi), as used by the backend suite; renders the live map with both markers.
        "07" to { MaitriResponderJourneyBoard(p(), "ON_THE_WAY", 28.6139 to 77.2090, false, {}, {}, {}, {}, distanceMeters = 650.0,
            self = 28.6080 to 77.2050) },
        "08" to { MaitriActiveSos(SosIncident(SystemClock.elapsedRealtime()), "capture-no-session", "capture-no-incident", {}, {}) },
        "09" to { Docked("Profile") { MaitriSettings(null, {}, {}) } },
        "10" to { MaitriLanguage(onBack = {}) },
        "11" to { MaitriVibration(onBack = {}) },
        "12" to { MaitriPermissions(onBack = {}) },
        "13" to { CompositionLocalProvider(LocalInspectionMode provides true) { MaitriPin(uid = null, onBack = {}) } },
        "14" to { MaitriEditProfile(user = null, onBack = {}) })

    @Test fun captureAllScreensInBothThemes() = capture(fontScale = null, prefix = "")

    /** Settings → Font size at 150% (the largest common setting): no clipped, broken or hidden text. */
    @Test fun captureAllScreensWithLargeText() = capture(fontScale = 1.5f, prefix = "large-")

    private fun capture(fontScale: Float?, prefix: String) {
        val context = instrumentation.targetContext
        assumeFalse("Unlock device", context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val out = File(context.getExternalFilesDir(null), "checkpoint-d").apply { mkdirs() }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                for ((screen, content) in screens { MaitriPalette(dark) }) {
                    scenario.onActivity { activity ->
                        // Match MainActivity, which draws edge-to-edge; otherwise status-bar insets read as zero.
                        activity.enableEdgeToEdge()
                        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        activity.setContent {
                            val controller = remember(dark) {
                                ThemeController(object : android.content.ContextWrapper(activity) {
                                    override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("capture_$name", mode)
                                }).apply { setMode(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) }
                            }
                            val density = androidx.compose.ui.platform.LocalDensity.current
                            CompositionLocalProvider(LocalThemeController provides controller,
                                androidx.compose.ui.platform.LocalDensity provides
                                    androidx.compose.ui.unit.Density(density.density, fontScale ?: density.fontScale)) {
                                androidx.compose.material3.MaterialTheme(colorScheme = `in`.maitri.app.ui.theme.maitriColorScheme(dark),
                                    typography = `in`.maitri.app.ui.theme.Typography) {
                                    CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides
                                        `in`.maitri.app.ui.theme.maitriColorScheme(dark).onBackground) {
                                        key(screen, dark) { content() }
                                    }
                                }
                            }
                        }
                    }
                    instrumentation.waitForIdleSync()
                    // Map screens fetch the style and vector tiles over the network.
                    SystemClock.sleep(if (screen == "07" || screen == "08") 8000 else 1200)
                    scenario.onActivity { activity ->
                        activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                            .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                    }
                    SystemClock.sleep(500)
                    // The emulator's screenshot service occasionally returns null under load; retry briefly.
                    val shot = requireNotNull(generateSequence { instrumentation.uiAutomation.takeScreenshot()
                        ?: null.also { SystemClock.sleep(700) } }.take(5).firstOrNull { it != null }
                        ?: instrumentation.uiAutomation.takeScreenshot()) { "Screenshot failed for $screen" }
                    File(out, "$prefix${if (dark) "dark" else "light"}-$screen.png").outputStream()
                        .use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
    }
}
