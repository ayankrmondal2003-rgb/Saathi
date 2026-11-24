package `in`.maitri.app

import `in`.maitri.app.network.contactDeliveryLabel
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Checkpoint D: approved SVG copy restored, no device-chrome artwork, and no overstated delivery. */
class MaitriCheckpointDTest {
    private fun ui(name: String) = File("src/main/java/in/maitri/app/ui/$name.kt").readText()

    @Test fun contactDeliveryNeverReadsAsDeliveredWithoutConfirmation() {
        assertEquals("Not sent · SMS not connected", contactDeliveryLabel("NO_SMS_PROVIDER"))
        assertEquals("Not sent", contactDeliveryLabel("NOT_SENT"))
        assertEquals("Alerts off", contactDeliveryLabel("NOT_REQUESTED"))
        assertEquals("Delivery unconfirmed", contactDeliveryLabel("PROVIDER_ACCEPTED"))
        assertEquals("Delivery unconfirmed", contactDeliveryLabel("SOMETHING_NEW"))
        assertEquals("Sent to app", contactDeliveryLabel("FCM_ACCEPTED"))
    }

    @Test fun screensDoNotDrawTheReferenceFramesHomeIndicator() {
        for (name in listOf("MaitriLogin", "MaitriVolunteer")) assertFalse(name, ui(name).contains(".width(134.dp)"))
    }

    @Test fun approvedCopyIsRestored() {
        val login = ui("MaitriLogin")
        listOf("A little peace of mind starts here.", "or use email & password").forEach { assertTrue(it, login.contains(it)) }
        val volunteer = ui("MaitriVolunteer")
        listOf("Be there for someone.", "Volunteer verification", "All 5 documents are required.",
            "I consent to document verification").forEach { assertTrue(it, volunteer.contains(it)) }
        // Documents go to private storage; the counter counts only uploads the server confirmed.
        assertTrue(volunteer.contains("documents uploaded"))
        assertTrue(volunteer.contains("Download template"))
        val circle = ui("MaitriCircle")
        listOf("YOUR PEOPLE, CLOSE BY", "Your circle", "Saved contacts", "SOS alerts on", "Choose who gets your SOS",
            "\"Add contact\"").forEach { assertTrue(it, circle.contains(it)) }
        // No SMS provider: the circle must not promise automatic SMS delivery. Contacts get a push (linked app
        // users) or the SMS the owner sends from their own phone, so the design's copy is accurate again.
        assertFalse(circle.contains("receives immediate SMS alerts"))
        assertTrue(circle.contains("These contacts receive your SOS and\\nlive location when you ask for help."))
    }

    @Test fun incomingSosKeepsTheResponderDockSelected() {
        val graph = File("src/main/java/in/maitri/app/navigation/SafetyApp.kt").readText()
        assertTrue(graph.contains("route?.startsWith(\"Incoming?\") == true -> \"Responder\""))
    }
}
