package `in`.maitri.app

import `in`.maitri.app.notification.SosRequestAlerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SosRequestAlertsTest {
    @Test fun aRequestShowsOnlyADistanceBand() {
        val (title, body) = SosRequestAlerts.texts(isTest = false, band = "Under 500 m")
        assertEquals("Someone nearby needs help", title)
        assertEquals("Under 500 m away · Accept only if you can help safely.", body)
        assertTrue("no coordinates", !Regex("""\d+\.\d{3,}""").containsMatchIn(body))
    }

    @Test fun practiceAndUnknownDistance() {
        val (title, body) = SosRequestAlerts.texts(isTest = true, band = null)
        assertEquals("Practice SOS nearby", title)
        assertEquals("Nearby · Accept only if you can help safely.", body)
    }

    @Test fun theSafetyCapIsTwoMinutes() = assertEquals(120_000L, SosRequestAlerts.CAP_MS)
}
