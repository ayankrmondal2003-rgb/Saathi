package `in`.maitri.app

import `in`.maitri.app.notification.NotificationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Notifications about one SOS share a tag, so "SOS ended" can clear the circle alert and the volunteer request. */
class IncidentNotificationTagTest {
    @Test fun oneTagPerSos() {
        val id = "c4df87f3-28d2-4fd3-8eb5-f6a462b67416"
        assertEquals("sos:$id", NotificationHelper.incidentTag(id))
        assertNull(NotificationHelper.incidentTag(null))
        assertNull(NotificationHelper.incidentTag(""))
    }
}
