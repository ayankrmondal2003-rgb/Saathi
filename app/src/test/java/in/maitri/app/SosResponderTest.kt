package `in`.maitri.app

import `in`.maitri.app.notification.AlertVibration
import `in`.maitri.app.ui.batteryTips
import `in`.maitri.app.ui.responderStatusLabel
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** P5: alert vibration, responder wording, battery tips, and permissions the app does (not) ask for. */
class SosResponderTest {
    @Test fun alertPatternRepeatsTheChosenPatternToAboutFiveSeconds() {
        val gentle = longArrayOf(0, 150, 150, 150, 150, 150)
        val alert = AlertVibration.repeatTo(gentle, 5_000)
        assertEquals(0L, alert[0])
        assertTrue(alert.sum() in 5_000L..6_500L)
        assertEquals(150L, alert[1])
    }

    @Test fun responderStatusesReadNaturally() {
        assertEquals("On the way", responderStatusLabel("ON_THE_WAY"))
        assertEquals("With you", responderStatusLabel("HELPING"))
        assertEquals("Can't help", responderStatusLabel("UNABLE_TO_HELP"))
    }

    @Test fun batteryTipsCoverTheCommonBrands() {
        assertTrue(batteryTips("samsung").any { it.contains("Never sleeping apps") })
        assertTrue(batteryTips("Xiaomi").any { it.contains("Autostart") })
        assertTrue(batteryTips("OnePlus").any { it.contains("background activity") })
        assertTrue(batteryTips("vivo").any { it.contains("Background power") })
        assertEquals(1, batteryTips("Google").size)
    }

    @Test fun manifestAsksOnlyForWhatTheAppUses() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.RECORD_AUDIO"))
        assertTrue(manifest.contains("android.permission.USE_FULL_SCREEN_INTENT"))
        // Photos go through the camera app and contacts through the picker. Background location is requested only for
        // volunteers who are Available and choose "Allow all the time" (ResponderLocation), never for anything else.
        assertFalse(manifest.contains("android.permission.CAMERA\""))
        assertFalse(manifest.contains("READ_CONTACTS"))
        assertTrue(manifest.contains("ACCESS_BACKGROUND_LOCATION"))
        val usedBy = File("src/main/java").walkTopDown().filter { it.extension == "kt" && "ACCESS_BACKGROUND_LOCATION" in it.readText() }
            .map { it.name }.toSet()
        assertEquals(setOf("ResponderLocation.kt", "TestResponderDashboard.kt"), usedBy)
    }
}
