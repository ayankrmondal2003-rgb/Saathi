package com.example.surakshanet

import android.app.KeyguardManager
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.surakshanet.data.DemoState
import com.example.surakshanet.navigation.tabs
import com.example.surakshanet.ui.GlassBottomNavigation
import com.example.surakshanet.ui.Home
import com.example.surakshanet.ui.theme.SurakshaNetTheme
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test

class HomeLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun nodes(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { yieldAll(nodes(it)) }
    }

    private fun awaitNode(label: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null) nodes(root).firstOrNull(predicate)?.let { return it }
            SystemClock.sleep(80)
        }
        val visible = instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
            nodes(root).mapNotNull { it.text ?: it.contentDescription }.joinToString(limit = 24)
        }
        error("Could not find $label. Visible controls: $visible")
    }

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo =
        generateSequence(node) { it.parent }.first { it.isClickable }

    @Test fun keypadDeletesAndSubmitsOnceAndOpensRecovery() {
        val submitted = java.util.concurrent.atomic.AtomicReference<String>()
        val recovery = java.util.concurrent.atomic.AtomicBoolean()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SurakshaNetTheme {
                        com.example.surakshanet.ui.SurakshaPinPad(
                            onPinSubmit = { submitted.set(it) }, onKeepActive = {},
                            onForgotPin = { recovery.set(true) }
                        )
                    }
                }
            }
            fun tap(label: String) {
                val node = awaitNode(label) { it.contentDescription == label || it.text == label }
                assertTrue(clickable(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
            }
            listOf("Digit 8", "Digit 2", "Delete last digit", "Digit 3", "Digit 9",
                "Digit 1", "Digit 6", "Digit 2").forEach(::tap)
            org.junit.Assert.assertEquals("839162", submitted.get())
            tap("Forgot Safety PIN?")
            assertTrue(recovery.get())
        }
    }

    @Test fun guidanceScrollsAboveDockAndQuickTapOpensConfirmation() {
        val context = instrumentation.targetContext
        assumeFalse("Interactive layout test requires an unlocked display",
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val activations = java.util.concurrent.atomic.AtomicInteger()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.enableEdgeToEdge()
                activity.setContent {
                    SurakshaNetTheme {
                        var selected by remember { mutableStateOf("Home") }
                        Scaffold(modifier = Modifier.safeDrawingPadding(), bottomBar = {
                            GlassBottomNavigation(tabs, selected, { selected = it })
                        }) { padding ->
                            Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                                Home(DemoState(), start = { activations.incrementAndGet() }, contacts = {})
                            }
                        }
                    }
                }
            }
            val click = AccessibilityNodeInfo.ACTION_CLICK
            val sos = awaitNode("SOS") { it.contentDescription?.contains("Hold SOS") == true }
            assertTrue(sos.performAction(click))
            awaitNode("SOS confirmation") { it.text?.toString() == "Activate Emergency SOS?" }
            assertTrue(clickable(awaitNode("Cancel") { it.text?.toString() == "Cancel" }).performAction(click))
            awaitNode("home") { it.contentDescription?.contains("Hold SOS") == true }
            assertTrue(activations.get() == 0)
            val root = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
            val scroll = nodes(root).firstOrNull { it.isScrollable }
            repeat(8) {
                scroll?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                instrumentation.uiAutomation.waitForIdle(100, 3000)
            }
            val details = awaitNode("guidance") { it.text?.contains("Holding the SOS button") == true }
            val tab = awaitNode("Profile tab") { it.text?.toString() == "Profile" }
            val cardBounds = Rect().also(details::getBoundsInScreen)
            val tabBounds = Rect().also(tab::getBoundsInScreen)
            assertTrue(cardBounds.height() > 0 && cardBounds.bottom <= tabBounds.top)
            assertTrue(clickable(tab).performAction(click))
            awaitNode("selected Profile tab") { it.text?.toString() == "Profile" && generateSequence(it) { node -> node.parent }.any { node -> node.isSelected } }
        }
    }

    @Test fun holdInvokesOneCallback() {
        val context = instrumentation.targetContext
        assumeFalse("Interactive layout test requires an unlocked display",
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val activations = java.util.concurrent.atomic.AtomicInteger()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SurakshaNetTheme {
                        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            com.example.surakshanet.ui.EmergencySosButton(
                                onTrigger = { activations.incrementAndGet() }, onQuickTap = {})
                        }
                    }
                }
            }
            val currentButton = awaitNode("SOS after dismissal") { it.contentDescription?.contains("Hold SOS") == true }
            val target = Rect().also(currentButton::getBoundsInScreen)
            val downTime = SystemClock.uptimeMillis()
            fun sendTouch(action: Int) {
                val event = android.view.MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    target.exactCenterX(), target.exactCenterY(), 0)
                try {
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    scenario.onActivity { activity ->
                        val origin = IntArray(2)
                        activity.window.decorView.getLocationOnScreen(origin)
                        event.offsetLocation(-origin[0].toFloat(), -origin[1].toFloat())
                        assertTrue(activity.dispatchTouchEvent(event))
                    }
                } finally {
                    event.recycle()
                }
            }
            sendTouch(android.view.MotionEvent.ACTION_DOWN)
            try {
                val deadline = downTime + 5000
                while (activations.get() == 0 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            } finally {
                sendTouch(android.view.MotionEvent.ACTION_UP)
            }
            instrumentation.waitForIdleSync()
            assertTrue("Hold must trigger the isolated callback once", activations.get() == 1)
        }
    }
}
