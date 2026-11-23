package `in`.maitri.app

import `in`.maitri.app.network.*
import org.junit.Assert.*
import org.junit.Test

class ResponderSessionTest {
    @Test fun onlyTheMonotonicConflictSkipsTheRejectedTimestamp() {
        assertTrue(staleResponderFix(409, "STALE_OR_DUPLICATE_LOCATION"))
        assertFalse(staleResponderFix(409, null))
        assertFalse(staleResponderFix(403, "STALE_OR_DUPLICATE_LOCATION"))
    }

    @Test fun journeyRequiresActiveMembershipForTheRequestedIncident() {
        val membership = RequesterMembershipDto("RESPONDER", "AUTHORIZED", "ACCEPTED")
        val active = IncidentSnapshotDto("one", "ACTIVE", true, "", null, ResponderSummaryDto(1, 1, 1), emptyList(), membership)
        assertTrue(active.authorizesJourney("one"))
        assertFalse(active.authorizesJourney("two"))
        assertFalse(active.copy(status = "CLOSED").authorizesJourney("one"))
        assertFalse(active.copy(requesterMembership = null).authorizesJourney("one"))
        assertFalse(active.copy(requesterMembership = membership.copy(status = "REVOKED")).authorizesJourney("one"))
        assertFalse(active.copy(requesterMembership = membership.copy(role = "OWNER")).authorizesJourney("one"))
    }
}
