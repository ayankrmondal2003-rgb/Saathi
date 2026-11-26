package `in`.maitri.app

import `in`.maitri.app.network.RequestStatus
import `in`.maitri.app.network.ResponderAreaDto
import `in`.maitri.app.network.ResponderHistoryEvents
import `in`.maitri.app.network.ResponderHistoryItemDto
import `in`.maitri.app.network.closedNow
import `in`.maitri.app.network.recentRequests
import `in`.maitri.app.network.status
import `in`.maitri.app.ui.areaLabel
import `in`.maitri.app.ui.historyGroup
import `in`.maitri.app.ui.requestWhen
import `in`.maitri.app.ui.responseLine
import `in`.maitri.app.ui.summaryLine
import `in`.maitri.app.network.ResponderHistorySummaryDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Responder tab "Recent requests", the History screen's cards and the live "SOS ended" change. */
class ResponderHistoryTest {
    private val now = Instant.parse("2026-11-26T12:00:00Z")
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun item(id: String, hoursAgo: Long, response: String = "ACCEPTED", state: String = "RESOLVED",
                     area: ResponderAreaDto? = null, band: String? = "Under 500 m") = ResponderHistoryItemDto(
        invitationId = "inv-$id", incidentId = "inc-$id", invitedAt = now.minusSeconds(hoursAgo * 3600).toString(),
        acceptedAt = if (response in setOf("ACCEPTED", "LEFT", "CANT_HELP")) now.minusSeconds(hoursAgo * 3600 - 60).toString() else null,
        closedAt = if (state == "ACTIVE") null else now.minusSeconds(hoursAgo * 3600 - 600).toString(),
        myResponse = response, incidentState = state, distanceBand = band, area = area, durationSeconds = 480)

    @Test fun statusPillForEveryCombination() {
        assertEquals(RequestStatus.ACTIVE, item("a", 1, "ACCEPTED", "ACTIVE").status())
        assertEquals(RequestStatus.NEW, item("b", 1, "PENDING", "ACTIVE").status())
        assertEquals(RequestStatus.RESOLVED, item("c", 1, "ACCEPTED", "RESOLVED").status())
        assertEquals(RequestStatus.ENDED, item("d", 1, "ACCEPTED", "CLOSED_ORPHANED").status())
        assertEquals(RequestStatus.ENDED, item("e", 1, "ACCEPTED", "CLOSED_OTHER").status())
        assertEquals(RequestStatus.LEFT, item("f", 1, "LEFT", "RESOLVED").status())
        assertEquals(RequestStatus.CANT_HELP, item("g", 1, "CANT_HELP", "ACTIVE").status())
        assertEquals(RequestStatus.DECLINED, item("h", 1, "DECLINED", "ACTIVE").status())
        assertEquals(RequestStatus.MISSED, item("i", 1, "EXPIRED", "RESOLVED").status())
    }

    @Test fun recentShowsAllOpenRequestsThenTheThreeLatestFinishedFromThisWeek() {
        val items = listOf(
            item("new", 0, "PENDING", "ACTIVE"),
            item("f1", 1), item("f2", 2, "DECLINED", "ACTIVE"), item("f3", 3, "EXPIRED", "CLOSED_OTHER"),
            item("f4", 4, "LEFT", "RESOLVED"),
            item("active-old", 30, "ACCEPTED", "ACTIVE"),   // still active: always shown, at the top
            item("too-old", 24 * 8))                          // finished more than 7 days ago: History only
        val recent = recentRequests(items, now).map { it.invitationId.removePrefix("inv-") }
        assertEquals(listOf("active-old", "new", "f1", "f2", "f3"), recent)
        assertTrue(recentRequests(emptyList(), now).isEmpty())
    }

    @Test fun anEndedSosChangesOpenCardsWithoutARefresh() {
        val active = item("x", 1, "ACCEPTED", "ACTIVE")
        val resolved = active.closedNow(resolved = true, at = now)
        assertEquals(RequestStatus.RESOLVED, resolved.status())
        assertEquals(now.toString(), resolved.closedAt)
        assertEquals("RESOLVED", resolved.timeline.last().type)
        assertEquals(RequestStatus.ENDED, active.closedNow(resolved = false, at = now).status())
        // A finished card never changes again.
        val done = item("y", 1, "ACCEPTED", "CLOSED_ORPHANED")
        assertEquals(done, done.closedNow(resolved = true, at = now))
        // A request still waiting for an answer becomes "Missed".
        assertEquals(RequestStatus.MISSED, item("z", 1, "PENDING", "ACTIVE").closedNow(true, now).status())
        // Pages loaded after the event are corrected too.
        ResponderHistoryEvents.emit("inc-late", resolved = true)
        val patched = ResponderHistoryEvents.patch(listOf(item("late", 1, "ACCEPTED", "ACTIVE")))
        assertEquals(RequestStatus.RESOLVED, patched.single().status())
    }

    @Test fun whereItWasIsApproximateAndNeverAPlace() {
        assertEquals("~450 m north-east of you", areaLabel(item("a", 1, area = ResponderAreaDto(450, "NE"))))
        assertEquals("~1.2 km west of you", areaLabel(item("b", 1, area = ResponderAreaDto(1200, "W"))))
        assertEquals("Under 50 m from you", areaLabel(item("c", 1, area = ResponderAreaDto(0, null))))
        assertEquals("Under 50 m from you", areaLabel(item("c2", 1, area = ResponderAreaDto(0, "S"))))
        // Declined or missed: only the band seen before answering.
        assertEquals("500 m – 1 km away", areaLabel(item("d", 1, "DECLINED", "ACTIVE", band = "500 m – 1 km")))
        assertEquals("Nearby", areaLabel(item("e", 1, "EXPIRED", band = null)))
    }

    @Test fun cardTextsAndDateGroups() {
        val today = now.toString()
        assertTrue(requestWhen(today, now, zone).startsWith("Today, "))
        assertTrue(requestWhen(now.minusSeconds(86_400).toString(), now, zone).startsWith("Yesterday, "))
        assertEquals("24 Nov, 3:10 pm", requestWhen("2026-11-24T09:40:00Z", now, zone))
        assertEquals("Today", historyGroup(today, now, zone))
        assertEquals("Yesterday", historyGroup(now.minusSeconds(86_400).toString(), now, zone))
        assertEquals("This week", historyGroup(now.minusSeconds(4 * 86_400).toString(), now, zone))
        assertEquals("October 2026", historyGroup("2026-10-30T10:00:00Z", now, zone))
        assertEquals("Accepted · 8 min", responseLine(item("a", 1), now))
        assertEquals("You declined", responseLine(item("b", 1, "DECLINED", "ACTIVE"), now))
        assertEquals("Not answered in time", responseLine(item("c", 1, "EXPIRED"), now))
        assertFalse(responseLine(item("d", 1, "LEFT"), now).isBlank())
    }

    @Test fun practiceSosesAreShownApartFromHelpGiven() {
        assertEquals("You've helped 4 times · Resolved 3", summaryLine(ResponderHistorySummaryDto(9, 4, 3, 0)))
        assertEquals("You've helped 0 times · Resolved 0 · + 5 practice", summaryLine(ResponderHistorySummaryDto(7, 0, 0, 5)))
    }
}
