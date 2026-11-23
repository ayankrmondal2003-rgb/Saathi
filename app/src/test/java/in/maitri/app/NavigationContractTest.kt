package `in`.maitri.app

import `in`.maitri.app.network.InvitationDto
import `in`.maitri.app.network.canOpenIncoming
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class NavigationContractTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val pending = InvitationDto("invitation", "incident", now.plusSeconds(1).toString(), "PENDING")

    @Test fun incomingRequiresCurrentPendingInvitation() {
        assertTrue(pending.canOpenIncoming(now))
        for (invalid in listOf(pending.copy(status = "ACCEPTED"), pending.copy(status = "DECLINED"),
            pending.copy(expiresAt = now.toString()), pending.copy(expiresAt = "invalid"),
            pending.copy(id = ""), pending.copy(incidentId = ""))) {
            assertFalse(invalid.canOpenIncoming(now))
        }
    }
}
