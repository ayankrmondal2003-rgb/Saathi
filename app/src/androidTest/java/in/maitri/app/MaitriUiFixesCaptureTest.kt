package `in`.maitri.app

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import `in`.maitri.app.ui.*
import `in`.maitri.app.ui.theme.LocalThemeController
import `in`.maitri.app.ui.theme.ThemeController
import `in`.maitri.app.ui.theme.ThemeMode
import `in`.maitri.app.ui.theme.Typography
import `in`.maitri.app.ui.theme.maitriColorScheme
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.File

/**
 * Screenshots of the UI fixes in Light and Dark, for review next to design-reference/crops:
 * the Responder tab in all four volunteer states with the live map, the Themes dialog with each option selected, the
 * bottom bar over scrolled content, and MaitriLoader. Network only for map tiles; no account, no incident.
 * Files land in the app's external files dir under ui-fixes/.
 */
class MaitriUiFixesCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** The Responder tab's layout with fixed data: header, status badge, live map (synthetic Delhi fix), settings. */
    @Composable
    private fun ResponderState(status: String, available: Boolean) {
        val p = maitriPalette()
        Box(Modifier.fillMaxSize().background(p.background)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 116.dp)) {
                ResponderHeader(p)
                VolunteerStatusBadge(p, status, "2026-11-20T10:30:00+05:30",
                    "The police certificate is older than six months. Please upload a recent one.", {},
                    Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.height(12.dp))
                MaitriMap(listOf(MapMarker(28.6139, 77.2090, MarkerKind.YOU)), "", Modifier.fillMaxWidth()
                    .padding(horizontal = 24.dp).height(300.dp), accuracyMeters = 35.0,
                    rangeMeters = if (available) RESPONDER_RANGE_METERS else null, alwaysShowAccuracy = true)
                Spacer(Modifier.height(12.dp))
                ResponderCard(p) { ToggleRow(p, "Community responder", "Let Maitri ask you to help people near you.", true, true) {} }
                Spacer(Modifier.height(12.dp))
                ResponderCard(p) { ToggleRow(p, "Available for nearby requests", "Get SOS requests from within 1 km of you.", available, true) {} }
                Spacer(Modifier.height(12.dp))
                RequestAlertsCard(p, listOf(AlertCheck("Notifications", true, "Maitri can alert you."),
                    AlertCheck("Lock-screen alert", available, if (available) "Requests can wake your screen."
                        else "Requests wait in the notification shade while the screen is off.", "Fix") {},
                    AlertCheck("Battery", !available, "Battery saving can delay requests.", "Fix") {}))
                Spacer(Modifier.height(12.dp))
                HowRespondingWorksCard(p)
            }
            MaitriBottomBar("Responder", {}, Modifier.align(Alignment.BottomCenter))
        }
    }

    private fun cases(): List<Triple<String, ThemeMode?, @Composable () -> Unit>> = listOf(
        Triple("responder-approved", null, @Composable { ResponderState("APPROVED", available = true) }),
        Triple("responder-pending", null, @Composable { ResponderState("UNDER_REVIEW", available = false) }),
        Triple("responder-rejected", null, @Composable { ResponderState("REJECTED", available = false) }),
        Triple("responder-not-applied", null, @Composable { ResponderState("NOT_STARTED", available = true) }),
        Triple("themes-system", ThemeMode.SYSTEM, @Composable { ThemesOver() }),
        Triple("themes-light", ThemeMode.LIGHT, @Composable { ThemesOver() }),
        Triple("themes-dark", ThemeMode.DARK, @Composable { ThemesOver() }),
        Triple("bottom-bar", null, @Composable { BottomBarOverContent() }),
        // "Circles you're in" with each add-back state (not added, request waiting, mutual).
        Triple("circles-you-are-in", null, @Composable {
            Box(Modifier.fillMaxSize().background(maitriPalette().background).statusBarsPadding().padding(24.dp)) {
                CirclesYouAreIn(listOf(
                    `in`.maitri.app.network.MembershipDto("l1", "Test Owner A", "2026-11-20T10:30:00+05:30", null),
                    `in`.maitri.app.network.MembershipDto("l2", "Test Owner B", "2026-11-21T09:00:00+05:30", "REQUESTED"),
                    `in`.maitri.app.network.MembershipDto("l3", "Test Owner C", "2026-11-22T18:00:00+05:30", "LINKED")),
                    null, maitriPalette()) {}
            }
        }),
        Triple("startup", null, @Composable { MaitriStartupScreen() }),
        Triple("startup-static", null, @Composable { MaitriStartupScreen(animate = false) }),
        Triple("loader", null, @Composable {
            Box(Modifier.fillMaxSize().background(maitriPalette().background), contentAlignment = Alignment.Center) {
                MaitriLoader(message = "Connecting to Maitri…", delayMillis = 0)
            }
        }),
        Triple("loader-static", null, @Composable {
            Box(Modifier.fillMaxSize().background(maitriPalette().background), contentAlignment = Alignment.Center) {
                MaitriLoader(message = "Remove animations is on", delayMillis = 0, animate = false)
            }
        }))

    @Composable
    private fun ThemesOver() {
        Box(Modifier.fillMaxSize().background(maitriPalette().background)) {
            ThemeDialog(mapOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")) {}
        }
    }

    /** Page content scrolled under the bar: the divider must be one full-width line with no gap above the tabs. */
    @Composable
    private fun BottomBarOverContent() {
        val p = maitriPalette()
        Box(Modifier.fillMaxSize().background(p.background)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(14) { Box(Modifier.fillMaxWidth().height(64.dp).background(p.card, RoundedCornerShape(18.dp))) {
                    Text("Row ${it + 1}", Modifier.align(Alignment.CenterStart).padding(16.dp), color = p.primary)
                } }
            }
            MaitriBottomBar("Home", {}, Modifier.align(Alignment.BottomCenter))
        }
    }

    @Test fun captureUiFixesInBothThemes() {
        val context = instrumentation.targetContext
        assumeFalse("Unlock device", context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val out = File(context.getExternalFilesDir(null), "ui-fixes").apply { mkdirs() }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) for ((name, mode, content) in cases()) {
                scenario.onActivity { activity ->
                    activity.enableEdgeToEdge()
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    activity.setContent {
                        val controller = remember(dark, mode) {
                            ThemeController(object : android.content.ContextWrapper(activity) {
                                override fun getSharedPreferences(name: String, m: Int) = super.getSharedPreferences("capture_$name", m)
                            }).apply { setMode(mode ?: if (dark) ThemeMode.DARK else ThemeMode.LIGHT) }
                        }
                        // The page behind the Themes dialog follows the capture's theme; the dialog shows `mode` selected.
                        val pageDark = if (mode == null || mode == ThemeMode.SYSTEM) dark else mode == ThemeMode.DARK
                        CompositionLocalProvider(LocalThemeController provides controller) {
                            MaterialTheme(colorScheme = maitriColorScheme(pageDark), typography = Typography) {
                                CompositionLocalProvider(LocalContentColor provides maitriColorScheme(pageDark).onBackground) {
                                    key(name, dark) { content() }
                                }
                            }
                        }
                    }
                }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(if (name.startsWith("responder")) 8000 else 1500)  // map style and tiles load over the network
                val shot = requireNotNull(generateSequence { instrumentation.uiAutomation.takeScreenshot()
                    ?: null.also { SystemClock.sleep(700) } }.take(5).firstOrNull { it != null }) { "Screenshot failed for $name" }
                val theme = when (mode) { null -> if (dark) "dark" else "light"; else -> if (dark) "pagedark" else "pagelight" }
                if (mode != null && mode != ThemeMode.SYSTEM && (mode == ThemeMode.DARK) != dark) continue  // one page theme per fixed mode
                File(out, "$theme-$name.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
