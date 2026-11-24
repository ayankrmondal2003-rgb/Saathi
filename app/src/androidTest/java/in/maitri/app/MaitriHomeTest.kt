package `in`.maitri.app

import android.app.KeyguardManager
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import `in`.maitri.app.data.DemoState
import `in`.maitri.app.ui.*
import `in`.maitri.app.ui.theme.MaitriTheme
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Isolated UI callbacks only: never creates a remote incident or sends notifications. */
class MaitriHomeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun nodes(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { yieldAll(nodes(it)) }
    }
    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val until = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < until) {
            instrumentation.uiAutomation.rootInActiveWindow?.let { nodes(it).firstOrNull(predicate) }?.let { return it }
            SystemClock.sleep(50)
        }
        error("Expected Maitri control unavailable")
    }
    private fun click(text: String) {
        val control = node { it.text?.toString() == text }
        assertTrue(generateSequence(control) { it.parent }.first { it.isClickable }
            .performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun unlocked() = assumeFalse("Unlock device for interactive tests",
        instrumentation.targetContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked)

    @Test fun threeSecondHoldCancelsAndCompletesExactlyOnce() {
        unlocked()
        val completions = AtomicInteger()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaitriTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        MaitriHoldButton(MaitriPalette(false), onComplete = { completions.incrementAndGet() })
                    }
                }
            } }
            val bounds = Rect().also(node { it.contentDescription?.contains("Hold SOS") == true }::getBoundsInScreen)
            var downTime = SystemClock.uptimeMillis()
            fun touch(action: Int) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { scenario.onActivity { activity ->
                    val origin = IntArray(2)
                    activity.window.decorView.getLocationOnScreen(origin)
                    event.offsetLocation(-origin[0].toFloat(), -origin[1].toFloat())
                    activity.dispatchTouchEvent(event)
                } } finally { event.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN)
            SystemClock.sleep(2200)
            assertEquals("Must not complete at legacy two-second threshold", 0, completions.get())
            touch(MotionEvent.ACTION_CANCEL)
            SystemClock.sleep(1100)
            assertEquals("Cancelled hold must not complete later", 0, completions.get())
            downTime = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN)
            try {
                SystemClock.sleep(2200)
                assertEquals(0, completions.get())
                val until = downTime + 5000
                while (completions.get() == 0 && SystemClock.uptimeMillis() < until) SystemClock.sleep(40)
                assertEquals(1, completions.get())
                SystemClock.sleep(300)
            } finally { touch(MotionEvent.ACTION_UP) }
            instrumentation.waitForIdleSync()
            assertEquals("Release must not invoke a second callback", 1, completions.get())
        }
    }

    @Test fun accessibleSosAsksFirstAndThenSharesWithTheServer() {
        unlocked()
        val starts = AtomicInteger()
        var shared = false
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaitriTheme { MaitriHome(DemoState(), { shared = it; starts.incrementAndGet() }, {}) }
            } }
            assertTrue(node { it.contentDescription?.contains("Hold SOS") == true }
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
            node { it.text?.toString() == "Send SOS now?" }
            assertEquals(0, starts.get())
            click("Cancel")
            assertEquals(0, starts.get())
            assertTrue(node { it.contentDescription?.contains("Hold SOS") == true }
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
            click("Send SOS")
            assertEquals(1, starts.get())
            assertTrue("Every SOS goes to the server", shared)
        }
    }

    @Test fun navigationOrderAndCircleRouteArePreserved() {
        unlocked()
        var selected = ""
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaitriTheme { MaitriBottomBar("Home", { selected = it }) }
            } }
            val labels = listOf("Home", "Circle", "Responder", "Profile")
            val centers = labels.map { label ->
                Rect().also(node { it.text?.toString() == label }::getBoundsInScreen).centerX()
            }
            assertTrue(centers.zipWithNext().all { (a, b) -> a < b })
            click("Circle")
            assertEquals("Contacts", selected)
        }
    }

    @Test fun emergencyArtworkRendersAndIncomingDecisionsRemainIndependent() {
        unlocked()
        val accepts = AtomicInteger()
        val declines = AtomicInteger()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                scenario.onActivity { activity ->
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    // setTurnScreenOn is API 27+; the window flag does the same on Android 8.0.
                    if (android.os.Build.VERSION.SDK_INT >= 27) activity.setTurnScreenOn(true)
                    else @Suppress("DEPRECATION") activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                    val assets = if (dark) listOf(R.drawable.maitri_dark_06, R.drawable.maitri_dark_07, R.drawable.maitri_dark_08)
                    else listOf(R.drawable.maitri_light_06, R.drawable.maitri_light_07, R.drawable.maitri_light_08)
                    assets.forEach { id ->
                        val art = requireNotNull(activity.getDrawable(id))
                        val bitmap = android.graphics.Bitmap.createBitmap(390, 816, android.graphics.Bitmap.Config.ARGB_8888)
                        art.setBounds(0, 0, bitmap.width, bitmap.height)
                        art.draw(android.graphics.Canvas(bitmap))
                        assertTrue(bitmap.getPixel(195, 200) != 0)
                        bitmap.recycle()
                    }
                    activity.setContent {
                        MaitriTheme {
                            MaitriIncomingContent(MaitriPalette(dark), "Nearby emergency", "Distance unavailable",
                                "ETA unavailable", false, null, { accepts.incrementAndGet() }, { declines.incrementAndGet() })
                        }
                    }
                }
                for (label in listOf("Accept", "Decline")) {
                    val action = node { it.contentDescription?.toString() == label }
                    assertTrue(action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    instrumentation.waitForIdleSync()
                }
            }
        }
        assertEquals(2, accepts.get())
        assertEquals(2, declines.get())
    }

    @Test fun profileRowsDispatchTheirOwnDestinationsInBothThemes() {
        unlocked()
        val context = instrumentation.targetContext
        val strings = MaitriLocale.localized(context).resources
        val actual = java.util.concurrent.atomic.AtomicReference("")
        val routes = mapOf(R.string.settings_edit to "EditProfile", R.string.settings_permissions to "Permissions",
            R.string.settings_vibrations to "TestVibration", R.string.settings_pins to "SafetyPin",
            R.string.settings_language to "Language")
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                scenario.onActivity { activity ->
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    activity.setContent {
                        val controller = androidx.compose.runtime.remember(dark) {
                            val testContext = object : android.content.ContextWrapper(activity) {
                                override fun getSharedPreferences(name: String, mode: Int) =
                                    super.getSharedPreferences("test_profile_" + name, mode)
                            }
                            `in`.maitri.app.ui.theme.ThemeController(testContext).apply {
                                setMode(if (dark) `in`.maitri.app.ui.theme.ThemeMode.DARK else `in`.maitri.app.ui.theme.ThemeMode.LIGHT)
                            }
                        }
                        androidx.compose.runtime.CompositionLocalProvider(
                            `in`.maitri.app.ui.theme.LocalThemeController provides controller
                        ) { MaitriSettings(null, actual::set, {}) }
                    }
                }
                for ((id, destination) in routes) {
                    actual.set("")
                    click(strings.getString(id))
                    assertEquals(destination, actual.get())
                }
                click(strings.getString(R.string.settings_themes))
                for (id in listOf(R.string.settings_light, R.string.settings_dark, R.string.settings_system)) {
                    node { it.text?.toString() == strings.getString(id) }
                }
                click(strings.getString(R.string.settings_close))
            }
        }
    }

    @Test fun translatedSettingsResolveForEverySavedLanguage() {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("maitri_language_prefs", 0)
        val oldCode = prefs.getString("selected_language", null)
        val manager = if (android.os.Build.VERSION.SDK_INT >= 33)
            context.getSystemService(android.app.LocaleManager::class.java) else null
        val oldLocales = if (android.os.Build.VERSION.SDK_INT >= 33) manager?.applicationLocales else null
        try {
            val expected = mapOf("en" to "Language", "hi" to "भाषा", "bn" to "ভাষা",
                "mr" to "भाषा", "ta" to "மொழி", "te" to "భాష")
            for ((code, title) in expected) {
                MaitriLocale.save(context, code)
                assertEquals(code, prefs.getString("selected_language", null))
                val fresh = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration))
                assertEquals(code, MaitriLocale.code(fresh))
                val localized = MaitriLocale.localized(fresh)
                assertEquals(title, localized.getString(R.string.settings_language))
                if (code != "en") {
                    assertNotEquals("Save language", localized.getString(R.string.settings_save))
                    assertNotEquals("Gentle", localized.getString(R.string.settings_gentle))
                }
            }
        } finally {
            prefs.edit().putString("selected_language", oldCode).commit()
            if (android.os.Build.VERSION.SDK_INT >= 33 && oldLocales != null) manager?.applicationLocales = oldLocales
        }
    }

    @Test fun vibrationControlsPlayAllThreePatterns() {
        unlocked()
        val context = instrumentation.targetContext
        @Suppress("DEPRECATION")
        val motor = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
        org.junit.Assume.assumeTrue(motor.hasVibrator())
        val strings = MaitriLocale.localized(context).resources
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.setContent { MaitriTheme { MaitriVibration(onBack = {}) } }
            }
            for (id in listOf(R.string.settings_gentle, R.string.settings_steady, R.string.settings_strong)) {
                click(strings.getString(id))
                val button = node { candidate ->
                    candidate.text?.toString() == strings.getString(R.string.settings_vibration) &&
                        generateSequence(candidate) { it.parent }.any { it.isClickable }
                }
                assertTrue(generateSequence(button) { it.parent }.first { it.isClickable }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                node { it.text?.toString() == strings.getString(R.string.settings_playing) }
                node { it.text?.toString() == strings.getString(R.string.settings_ready) }
            }
        }
    }

    @Test fun profileEditAndBackUseTheProductionGraph() {
        unlocked()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val user = `in`.maitri.app.data.UserProfile("test", "Test User", "test@example.org",
                    null, null, `in`.maitri.app.data.Responder())
                val vm = `in`.maitri.app.state.SafetyViewModel(activity.application)
                activity.setContent {
                    MaitriTheme {
                        `in`.maitri.app.navigation.MainGraph(DemoState(user = user), vm, {}, false)
                    }
                }
            }
            click("Profile")
            click("Edit")
            node { it.text?.toString() == "Full name" }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            node { it.text?.toString() == "Edit" }
        }
    }
}
