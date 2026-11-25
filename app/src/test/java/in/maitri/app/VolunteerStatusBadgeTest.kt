package `in`.maitri.app

import `in`.maitri.app.ui.VolunteerState
import `in`.maitri.app.ui.approvalDate
import `in`.maitri.app.ui.volunteerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VolunteerStatusBadgeTest {
    @Test fun everyServerStatusHasABadge() {
        assertNull(volunteerState(null))  // still loading: no badge, no button
        assertEquals(VolunteerState.APPROVED, volunteerState("APPROVED"))
        assertEquals(VolunteerState.PENDING, volunteerState("SUBMITTED"))
        assertEquals(VolunteerState.PENDING, volunteerState("UNDER_REVIEW"))
        assertEquals(VolunteerState.REJECTED, volunteerState("REJECTED"))
        assertEquals(VolunteerState.REJECTED, volunteerState("REQUIRES_CORRECTION"))
        assertEquals(VolunteerState.NOT_APPLIED, volunteerState("NOT_STARTED"))
        assertEquals(VolunteerState.NOT_APPLIED, volunteerState("DRAFT"))
    }

    @Test fun approvalDateReadsTheServerTimestamp() {
        assertNotNull(approvalDate("2026-11-24T18:22:04.123456+00:00"))
        assertNull(approvalDate(null))
        assertNull(approvalDate("not a date"))
    }
}
