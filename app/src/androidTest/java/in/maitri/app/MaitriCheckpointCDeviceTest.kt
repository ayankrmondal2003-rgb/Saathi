package `in`.maitri.app

import android.app.KeyguardManager
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import `in`.maitri.app.data.DemoState
import `in`.maitri.app.data.ProfileLocalStore
import `in`.maitri.app.data.Responder
import `in`.maitri.app.data.UserProfile
import `in`.maitri.app.ui.*
import `in`.maitri.app.ui.theme.LocalThemeController
import `in`.maitri.app.ui.theme.ThemeController
import `in`.maitri.app.ui.theme.ThemeMode
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.File

/** Screens 12-14 on device. Never contacts the backend: no Firebase session exists, so requests stop in the client. */
class MaitriCheckpointCDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val strings get() = MaitriLocale.localized(context).resources

    private fun nodes(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { yieldAll(nodes(it)) }
    }
    private fun find(timeout: Long = 8000, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) {
            instrumentation.uiAutomation.rootInActiveWindow?.let { nodes(it).firstOrNull(predicate) }?.let { return it }
            SystemClock.sleep(50)
        }
        return null
    }
    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean) = find(predicate = predicate) ?: error(
        "Expected Maitri control unavailable. Visible: " + instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
            nodes(root).mapNotNull { it.text?.toString() }.joinToString(" | ") })
    private fun AccessibilityNodeInfo.says(value: String) = text?.toString() == value ||
        text?.toString()?.split(", ")?.contains(value) == true
    private fun text(id: Int) = node { it.says(strings.getString(id)) }
    private fun clickable(node: AccessibilityNodeInfo) = generateSequence(node) { it.parent }.first { it.isClickable }
    private fun click(id: Int) {
        assertTrue(clickable(text(id)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun type(label: Int, value: String) {
        // Compose places a text field's contentDescription on a child node; the editable node is its ancestor.
        val labelled = node { it.contentDescription?.toString() == strings.getString(label) }
        val field = generateSequence(labelled) { it.parent }.first { it.isEditable }
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
        instrumentation.waitForIdleSync()
        hideKeyboard()
    }
    private var current: ActivityScenario<ComponentActivity>? = null
    /** The PIN screen focuses its first field; hide the IME so lower board text stays on screen and in the tree. */
    private fun hideKeyboard() {
        current?.onActivity { activity ->
            activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        SystemClock.sleep(400)
        instrumentation.waitForIdleSync()
    }
    private fun unlocked() = assumeFalse("Unlock device for interactive tests",
        context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)

    private fun themed(scenario: ActivityScenario<ComponentActivity>, dark: Boolean, preview: Boolean = false,
                       content: @androidx.compose.runtime.Composable () -> Unit) {
        current = scenario
        scenario.onActivity { activity ->
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.setContent {
                val controller = remember(dark) {
                    ThemeController(object : android.content.ContextWrapper(activity) {
                        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("test_c_$name", mode)
                    }).apply { setMode(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) }
                }
                CompositionLocalProvider(LocalThemeController provides controller, LocalInspectionMode provides preview) {
                    // setContent reuses the composition; a new key gives each theme pass fresh screen state.
                    androidx.compose.runtime.key(dark, preview, System.nanoTime()) { content() }
                }
            }
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        hideKeyboard()
    }

    @Test fun permissionsShowLiveDeviceStateAndExplainUndeclaredAccess() {
        unlocked()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                themed(scenario, dark) { MaitriPermissions(onBack = {}) }
                // perm_hint now lives in the "Keep Maitri alerts on time" dialog that replaced the footer hint.
                text(R.string.perm_banner); text(R.string.perm_open)
                node { it.text?.toString() == tr("Keep Maitri alerts on time ›") }
                var expected = emptyMap<PermissionRow, AccessState>()
                scenario.onActivity { activity -> expected = PermissionRow.entries.associateWith { readAccess(activity, it) } }
                for (row in PermissionRow.entries) {
                    text(row.title); text(row.detail)
                    val label = strings.getString(expected.getValue(row).label)
                    // The row container merges title and status, so the status must sit inside the same clickable row.
                    val rowNode = clickable(text(row.title))
                    assertTrue("${row.name} shows $label", nodes(rowNode).any { it.says(label) })
                }
                assertEquals(AccessState.NOT_NEEDED, expected[PermissionRow.CONTACTS])
                click(R.string.perm_contacts)
                text(R.string.perm_contacts_info)
                click(R.string.settings_close)
            }
        }
    }

    @Test fun safetyPinEntryEnforcesStrengthAndConfirmationInBothThemes() {
        unlocked()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                // Inspection mode starts in enrollment without a server status request; uid = null blocks submission.
                themed(scenario, dark, preview = true) { MaitriPin(uid = null, onBack = {}) }
                text(R.string.pin_title); text(R.string.pin_banner_setup); text(R.string.pin_tip)
                text(R.string.pin_hint_setup)
                assertFalse(clickable(text(R.string.pin_save)).isEnabled)
                type(R.string.pin_enter, "123456"); type(R.string.pin_confirm, "123456")
                text(R.string.pin_weak_sequence)
                type(R.string.pin_enter, "111111"); text(R.string.pin_weak_repeated)
                type(R.string.pin_enter, "839162"); type(R.string.pin_confirm, "839163"); text(R.string.pin_mismatch)
                assertFalse(clickable(text(R.string.pin_save)).isEnabled)
                type(R.string.pin_confirm, "839162"); text(R.string.pin_ready)
                assertTrue(clickable(text(R.string.pin_save)).isEnabled)
                // Digits are masked until Show PIN is enabled.
                assertNull(find(500) { it.text?.toString() == "8" })
                click(R.string.pin_show)
                node { it.text?.toString() == "8" }
            }
        }
    }

    @Test fun editProfileValidatesAndRefusesToSaveWithoutAccountSession() {
        unlocked()
        val user = UserProfile("checkpoint-c-device", "Test User", "test@example.org", null, null, Responder())
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                themed(scenario, dark) { MaitriEditProfile(user = user, onBack = {}) }
                for (id in listOf(R.string.edit_title, R.string.edit_photo, R.string.edit_change, R.string.edit_name,
                    R.string.edit_phone, R.string.edit_email, R.string.edit_city, R.string.edit_notes)) text(id)
                node { it.text?.toString() == "test@example.org" }
                text(R.string.edit_helper)
                type(R.string.edit_phone, "12")
                click(R.string.edit_save)
                text(R.string.edit_phone_invalid)
                type(R.string.edit_phone, "+91 98765 43210")
                type(R.string.edit_city, "Pune")
                click(R.string.edit_save)
                text(R.string.edit_session)
            }
        }
    }

    /** Saves screens 12-14 in both themes for side-by-side review against design-reference/crops. */
    @Test fun captureScreensForVisualReview() {
        unlocked()
        val out = File(context.getExternalFilesDir(null), "checkpoint-c").apply { mkdirs() }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                val screens: List<Pair<String, @androidx.compose.runtime.Composable () -> Unit>> = listOf(
                    "12" to { MaitriPermissions(onBack = {}) },
                    "13" to { MaitriPin(uid = null, onBack = {}) },
                    "14" to { MaitriEditProfile(user = null, onBack = {}) })
                for ((screen, content) in screens) {
                    themed(scenario, dark, preview = screen != "12") { content() }
                    text(mapOf("12" to R.string.perm_banner, "13" to R.string.pin_title, "14" to R.string.edit_title).getValue(screen))
                    SystemClock.sleep(600)
                    val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    File(out, "$theme-$screen.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
    }

    @Test fun medicalNotesAreEncryptedOnDevice() {
        val store = ProfileLocalStore(context)
        val uid = "checkpoint-c-notes"
        val secret = "Asthma; inhaler in left pocket"
        try {
            store.writeMedicalNotes(uid, secret)
            assertEquals(secret, store.readMedicalNotes(uid))
            val files = File(context.noBackupFilesDir, "profile").listFiles().orEmpty().filter { it.name.endsWith(".notes") }
            assertTrue(files.isNotEmpty())
            files.forEach { assertFalse(String(it.readBytes(), Charsets.ISO_8859_1).contains("inhaler")) }
            // Each account has its own file; ciphertext is also bound to the uid as GCM associated data.
            assertEquals("", store.readMedicalNotes("checkpoint-c-other"))
        } finally {
            store.writeMedicalNotes(uid, "")
        }
        assertEquals("", store.readMedicalNotes(uid))
    }

    @Test fun responderTabRendersTheDashboardAndFailsClosedWithoutASession() {
        unlocked()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val user = UserProfile("test", "Test User", "test@example.org", null, null, Responder())
                val vm = `in`.maitri.app.state.SafetyViewModel(activity.application)
                activity.setContent {
                    `in`.maitri.app.ui.theme.MaitriTheme {
                        `in`.maitri.app.navigation.MainGraph(DemoState(user = user), vm, {}, false)
                    }
                }
            }
            assertTrue(clickable(node { it.text?.toString() == "Responder" }).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            node { it.text?.toString() == "Community responder" }
            node { it.text?.toString() == "Invitations" }
            // The account token does not match uid "test", so nothing loads and no invitation is invented.
            node { it.text?.toString() == "Could not refresh responder information" }
            assertNull(find(1500) { it.text?.toString() == "Open invitation" })
        }
    }

    @Test fun profileRowsOpenScreensTwelveToFourteenInTheProductionGraph() {
        unlocked()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val user = UserProfile("test", "Test User", "test@example.org", null, null, Responder())
                val vm = `in`.maitri.app.state.SafetyViewModel(activity.application)
                activity.setContent {
                    `in`.maitri.app.ui.theme.MaitriTheme {
                        `in`.maitri.app.navigation.MainGraph(DemoState(user = user), vm, {}, false)
                    }
                }
            }
            fun back() { scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; instrumentation.waitForIdleSync() }
            assertTrue(clickable(node { it.text?.toString() == "Profile" }).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            click(R.string.settings_permissions); text(R.string.perm_banner); back()
            click(R.string.settings_pins); text(R.string.pin_title)
            // Without a signed-in session the status request fails closed and offers Retry.
            text(R.string.pin_retry); back()
            click(R.string.settings_edit); text(R.string.edit_title); back()
            text(R.string.settings_edit)
        }
    }
}
