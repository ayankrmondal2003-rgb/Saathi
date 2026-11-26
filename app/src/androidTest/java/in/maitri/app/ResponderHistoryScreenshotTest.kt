package `in`.maitri.app

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import `in`.maitri.app.network.ResponderAreaDto
import `in`.maitri.app.network.ResponderHistoryItemDto
import `in`.maitri.app.network.ResponderHistorySummaryDto
import `in`.maitri.app.ui.*
import `in`.maitri.app.ui.theme.LocalThemeController
import `in`.maitri.app.ui.theme.ThemeController
import `in`.maitri.app.ui.theme.ThemeMode
import `in`.maitri.app.ui.theme.Typography
import `in`.maitri.app.ui.theme.maitriColorScheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * The Responder tab's "Recent requests" and the Responder history screen, drawn with the same fixed requests in
 * Light and Dark, at 360 dp and 600 dp wide, with text at 100 % and 130 %. Each request is its own card with the
 * same look on both screens, and a status pill never takes two lines. No network.
 * Files land in the app's external files dir under responder-history/.
 */
class ResponderHistoryScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val now = Instant.now()

    private fun item(id: String, minutesAgo: Long, response: String, state: String, test: Boolean = false,
                     area: ResponderAreaDto? = ResponderAreaDto(450, "NE"), band: String = "Under 500 m") =
        ResponderHistoryItemDto(invitationId = "inv-$id", incidentId = "inc-$id", isTest = test,
            invitedAt = now.minusSeconds(minutesAgo * 60).toString(),
            acceptedAt = if (response in setOf("ACCEPTED", "LEFT", "CANT_HELP")) now.minusSeconds(minutesAgo * 60 - 30).toString() else null,
            closedAt = if (state == "ACTIVE") null else now.minusSeconds(minutesAgo * 60 - 600).toString(),
            myResponse = response, incidentState = state, distanceBand = band,
            area = area.takeIf { response in setOf("ACCEPTED", "LEFT", "CANT_HELP") }, durationSeconds = 480, responders = 2)

    private val items = listOf(
        item("active", 4, "ACCEPTED", "ACTIVE"),
        item("resolved", 60, "ACCEPTED", "RESOLVED", test = true),
        item("ended", 26 * 60, "ACCEPTED", "CLOSED_ORPHANED"),
        item("left", 3 * 24 * 60, "LEFT", "RESOLVED"),
        item("declined", 5 * 24 * 60, "DECLINED", "ACTIVE"),
        item("missed", 40 * 24 * 60, "EXPIRED", "CLOSED_OTHER"))

    private data class Case(val dark: Boolean, val widthDp: Int, val fontScale: Float) {
        val name get() = (if (dark) "dark" else "light") + "-${widthDp}dp-x$fontScale"
    }

    @Test fun recentRequestsAndHistoryLookTheSame() {
        val out = File(instrumentation.targetContext.getExternalFilesDir(null), "responder-history").apply { mkdirs() }
        var case by mutableStateOf(Case(false, 360, 1f))
        var screen by mutableStateOf("recent")
        compose.setContent {
            val c = case
            val activity = compose.activity
            val controller = remember(c.dark) {
                ThemeController(object : android.content.ContextWrapper(activity) {
                    override fun getSharedPreferences(name: String, m: Int) = super.getSharedPreferences("capture_$name", m)
                }).apply { setMode(if (c.dark) ThemeMode.DARK else ThemeMode.LIGHT) }
            }
            // A narrower or wider screen than the phone's, by changing the density the layout sees.
            val screenPx = LocalConfiguration.current.screenWidthDp * LocalDensity.current.density
            CompositionLocalProvider(LocalThemeController provides controller,
                LocalDensity provides Density(screenPx / c.widthDp, c.fontScale)) {
                MaterialTheme(colorScheme = maitriColorScheme(c.dark), typography = Typography) {
                    val p = maitriPalette()
                    key(c, screen) {
                        if (screen == "recent") Column(Modifier.fillMaxSize().background(p.background).statusBarsPadding()
                            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ResponderHeader(p)
                            HowRespondingWorksCard(p)
                            RecentRequestsSection(p, items, total = 12, onOpen = {}, onViewAll = {}, now = now)
                        } else ResponderHistoryBody(p, ResponderHistorySummaryDto(12, 4, 3, 2), HistoryFilter.ALL, {}, null,
                            false, {}, true, items, 30, false, rememberLazyListState(), now, {}, {})
                    }
                }
            }
        }
        val problems = mutableListOf<String>()
        for (dark in listOf(false, true)) for (width in listOf(360, 600)) for (scale in listOf(1f, 1.3f))
            for (which in listOf("recent", "history")) {
                case = Case(dark, width, scale)
                screen = which
                compose.waitForIdle()
                SystemClock.sleep(400)
                // The resolved pill: one line at every size (full or short wording).
                val pill = compose.onAllNodesWithText("Incident resolved · person marked safe", useUnmergedTree = true).fetchSemanticsNodes() +
                    compose.onAllNodesWithText("Resolved · marked safe", useUnmergedTree = true).fetchSemanticsNodes()
                // One 14 sp line at this case's density and text size; two lines would be about twice that.
                val density = compose.activity.resources.displayMetrics.widthPixels.toFloat() / width
                val lineHeightPx = 14f * 1.6f * scale * density
                pill.forEach { node ->
                    if (node.size.height > lineHeightPx) problems += "${case.name} $which: pill is ${node.size.height}px tall (two lines?)"
                }
                if (pill.isEmpty()) problems += "${case.name} $which: no resolved pill found"
                val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Screenshot failed" }
                File(out, "$which-${case.name}.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
