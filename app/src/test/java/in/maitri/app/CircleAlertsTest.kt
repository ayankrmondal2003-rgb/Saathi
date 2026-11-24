package `in`.maitri.app

import `in`.maitri.app.network.CircleSessionDto
import `in`.maitri.app.network.InboxItemDto
import `in`.maitri.app.network.circleStatusLabel
import `in`.maitri.app.network.circleStatusReached
import `in`.maitri.app.network.inviteText
import `in`.maitri.app.network.sosSmsText
import `in`.maitri.app.ui.InboxAction
import `in`.maitri.app.ui.inboxAction
import `in`.maitri.app.ui.minutesLabel
import `in`.maitri.app.ui.ownOpenSession
import `in`.maitri.app.ui.relativeTime
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** P3: circle alert wording, inbox routing and the sharing dialog's session choice. */
class CircleAlertsTest {
    private fun item(kind: String, data: Map<String, String> = emptyMap(), title: String = "t") =
        InboxItemDto("id-1", kind, title, "b", data, "2026-11-24T10:00:00+00:00", false)

    @Test fun statusLabelsNeverClaimAnSmsWasSent() {
        assertEquals("SMS prepared", circleStatusLabel("SMS_PREPARED"))
        assertEquals("Push delivered", circleStatusLabel("DELIVERED"))
        assertEquals("Opened", circleStatusLabel("OPENED"))
        assertEquals("Not reached", circleStatusLabel("NOT_REACHED"))
        assertEquals("Not reached", circleStatusLabel("SOMETHING_NEW"))
        listOf("SMS_PREPARED", "NOT_REACHED", "SENDING", "NOT_REQUESTED").forEach {
            assertFalse(circleStatusLabel(it).contains("Sent", ignoreCase = false) && it.startsWith("SMS"))
            assertFalse(it, circleStatusReached(it))
        }
        assertTrue(circleStatusReached("OPENED") && circleStatusReached("DELIVERED") && circleStatusReached("PUSH_SENT"))
    }

    @Test fun smsTextCarriesTheLinkAnd112() {
        val text = sosSmsText("https://example.invalid/l/abc")
        assertTrue(text.contains("https://example.invalid/l/abc") && text.contains("112"))
        assertTrue(sosSmsText(null).contains("112"))
        assertTrue(inviteText("ABCD-2345").contains("ABCD-2345"))
    }

    @Test fun inboxItemsOpenTheRightThing() {
        assertEquals(InboxAction.OpenInvitation("inv"), inboxAction(item("SOS_REQUEST", mapOf("invitation_id" to "inv"))))
        assertEquals(InboxAction.OpenLink("https://x.invalid/l/t"), inboxAction(item("CIRCLE_SOS", mapOf("url" to "https://x.invalid/l/t"))))
        // With the incident id, the circle member gets the live location and the SOS voice notes and photos.
        assertEquals(InboxAction.ViewCircleSos("inc", "https://x.invalid/l/t"),
            inboxAction(item("CIRCLE_SOS", mapOf("url" to "https://x.invalid/l/t", "incident_id" to "inc"))))
        assertEquals("ended: media only, no live link", InboxAction.ViewCircleSos("inc", null),
            inboxAction(item("CIRCLE_SOS_ENDED", mapOf("incident_id" to "inc"))))
        assertEquals(InboxAction.ViewCircleSos("inc", null),
            inboxAction(item("CIRCLE_SOS", mapOf("url" to "intent://evil", "incident_id" to "inc"))))
        assertEquals("only web links open", InboxAction.None, inboxAction(item("CIRCLE_SOS", mapOf("url" to "intent://evil"))))
        assertEquals(InboxAction.AnswerCircleRequest("r"), inboxAction(item("CIRCLE_REQUEST", mapOf("request_id" to "r"))))
        assertEquals(InboxAction.OpenCheckIn, inboxAction(item("CHECKIN_REMINDER")))
        assertEquals(InboxAction.OpenCheckIn, inboxAction(item("CHECKIN_MISSED", title = "You missed your check-in")))
        assertEquals(InboxAction.ViewSession("s"), inboxAction(item("CHECKIN_MISSED", mapOf("session_id" to "s"), "Asha missed a check-in")))
        assertEquals(InboxAction.ViewSession("s"), inboxAction(item("TRIP_STARTED", mapOf("session_id" to "s"))))
        assertEquals(InboxAction.None, inboxAction(item("CIRCLE_LINKED")))
    }

    @Test fun relativeTimes() {
        val now = Instant.parse("2026-11-24T12:00:00Z")
        assertEquals("Just now", relativeTime("2026-11-24T11:59:40+00:00", now))
        assertEquals("5 min ago", relativeTime("2026-11-24T11:55:00+00:00", now))
        assertEquals("2 h ago", relativeTime("2026-11-24T10:00:00+00:00", now))
        assertEquals("", relativeTime("not a date", now))
        assertEquals("30 min", minutesLabel(30))
        assertEquals("2 h", minutesLabel(120))
    }

    @Test fun onlyTheOwnersOpenSessionOfThatKindCounts() {
        fun s(kind: String, status: String, owner: Boolean) =
            CircleSessionDto("$kind-$status-$owner", kind, status, "c", "e", null, null, owner, emptyList())
        val list = listOf(s("TRIP", "ACTIVE", false), s("CHECK_IN", "STOPPED", true), s("CHECK_IN", "ACTIVE", true))
        assertEquals("CHECK_IN-ACTIVE-true", ownOpenSession(list, "CHECK_IN")?.id)
        assertNull("someone else's trip is not yours", ownOpenSession(list, "TRIP"))
    }
}
