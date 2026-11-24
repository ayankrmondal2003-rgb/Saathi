package `in`.maitri.app

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
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

    @Test fun maitriWelcomeButtonWorksInBothThemesOnSmallScreen() {
        for (dark in listOf(false, true)) {
            val starts = java.util.concurrent.atomic.AtomicInteger()
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        Box(Modifier.height(500.dp)) {
                            `in`.maitri.app.ui.MaitriWelcomeContent(dark) { starts.incrementAndGet() }
                        }
                    }
                }
                val scroll = awaitNode("welcome scroll") { it.isScrollable }
                repeat(3) {
                    scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    instrumentation.waitForIdleSync()
                }
                val button = clickable(awaitNode("Get started") { it.text?.toString() == "Get started" })
                assertTrue(button.isVisibleToUser)
                assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                org.junit.Assert.assertEquals(1, starts.get())
            }
        }
    }
}
