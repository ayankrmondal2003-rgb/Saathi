package `in`.maitri.app.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

const val PACKAGE = "in.maitri.app"

/** Waits for Home after the startup screen (the bottom tabs are there). */
fun MacrobenchmarkScope.waitForHome() {
    device.wait(Until.hasObject(By.text("Home")), 8_000)
    device.waitForIdle()
}

/**
 * Taps a bottom tab by position: the dock has four equal tabs at about 90.5% of the screen height (the labels
 * report zero-size bounds to UiAutomator, so they can't be clicked by text).
 */
private fun MacrobenchmarkScope.tab(label: String) {
    val index = listOf("Home", "Circle", "Responder", "Profile").indexOf(label)
    device.click(device.displayWidth * (index * 2 + 1) / 8, (device.displayHeight * 0.905).toInt())
    Thread.sleep(600)
    device.waitForIdle()
}

/** Home <-> each tab, scrolling each page: the journey for the profile and the frame benchmark. */
fun MacrobenchmarkScope.switchTabsAndScroll() {
    for (label in listOf("Circle", "Responder", "Profile", "Home")) {
        tab(label)
        device.findObject(By.scrollable(true))?.let { list ->
            list.setGestureMargin(device.displayWidth / 5)
            list.fling(Direction.DOWN)
            device.waitForIdle()
            list.fling(Direction.UP)
            device.waitForIdle()
        }
    }
}

/**
 * The SOS hold ring for about 1.2 s, released well before 3 s: the animation and haptics run, but no SOS starts.
 * Screen 08 (an open SOS) is not exercised, because that would send a real alert.
 */
fun MacrobenchmarkScope.partialSosHold() {
    val sos = device.findObject(By.desc("Hold SOS for three seconds")) ?: return
    val c = sos.visibleCenter
    device.swipe(c.x, c.y, c.x, c.y, 240)   // 240 steps x ~5 ms = ~1.2 s press
    device.waitForIdle()
}
