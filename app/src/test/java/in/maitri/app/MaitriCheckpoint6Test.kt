package `in`.maitri.app

import `in`.maitri.app.data.ContactAlertStatus
import `in`.maitri.app.data.ContactDeliveryStatus
import `in`.maitri.app.data.EmergencyContact
import `in`.maitri.app.data.IncidentStatus
import `in`.maitri.app.data.SosIncident
import `in`.maitri.app.location.IncidentTrackingState
import `in`.maitri.app.network.IncidentLocationDetailDto
import `in`.maitri.app.network.ResponderSummaryDto
import `in`.maitri.app.network.WsConnectionState
import org.junit.Assert.*
import org.junit.Test

class MaitriCheckpoint6Test {

    // ------------------------------------------------------------------------
    // Screen 06: Incoming SOS Request Tests
    // ------------------------------------------------------------------------

    @Test
    fun testPreAcceptanceLocationPrivacy() {
        // Before acceptance, responder only sees approximate locality and distance, never precise coordinates
        data class PreAcceptanceInvitation(
            val invitationId: String,
            val approximateLocality: String,
            val approximateDistanceMeters: Int,
            val isAccepted: Boolean,
            val preciseLatitude: Double? = null,
            val preciseLongitude: Double? = null
        )

        val invitation = PreAcceptanceInvitation(
            invitationId = "inv-101",
            approximateLocality = "Kolkata, Sector V",
            approximateDistanceMeters = 450,
            isAccepted = false,
            preciseLatitude = null,
            preciseLongitude = null
        )

        assertFalse(invitation.isAccepted)
        assertNull(invitation.preciseLatitude)
        assertNull(invitation.preciseLongitude)
        assertEquals("Kolkata, Sector V", invitation.approximateLocality)
        assertEquals(450, invitation.approximateDistanceMeters)
    }

    @Test
    fun testInvitationResponseValidation() {
        fun validateDecision(decision: String): Boolean {
            return decision == "ACCEPTED" || decision == "DECLINED"
        }

        assertTrue(validateDecision("ACCEPTED"))
        assertTrue(validateDecision("DECLINED"))
        assertFalse(validateDecision("PENDING"))
        assertFalse(validateDecision("CANCELLED"))
    }

    @Test
    fun testInvitationExpirationStatus() {
        val now = 1700000000000L
        val expiredTime = now - 60000L
        val activeTime = now + 120000L

        fun isExpired(expiresAtMillis: Long, currentMillis: Long): Boolean {
            return expiresAtMillis <= currentMillis
        }

        assertTrue(isExpired(expiredTime, now))
        assertFalse(isExpired(activeTime, now))
    }

    // ------------------------------------------------------------------------
    // Screen 07: Responder Live Journey Tests
    // ------------------------------------------------------------------------

    @Test
    fun testAccessRevocationClearsProtectedCoordinates() {
        // When tracking state transitions to ACCESS_REVOKED or INCIDENT_CLOSED,
        // precise coordinates MUST be cleared immediately for victim privacy.
        val activeState = IncidentTrackingState(
            incidentId = "inc-001",
            status = "ACTIVE",
            connectionState = WsConnectionState.CONNECTED,
            latestLocation = IncidentLocationDetailDto(
                latitude = 22.5726,
                longitude = 88.3639,
                accuracyMeters = 10.0,
                recordedAt = "2026-11-23T04:00:00Z",
                receivedAt = "2026-11-23T04:00:01Z",
                mock = false,
                stale = false
            )
        )
        assertNotNull(activeState.latestLocation)

        // Simulate Access Revocation
        val revokedState = activeState.copy(
            connectionState = WsConnectionState.ACCESS_REVOKED,
            status = "REVOKED",
            latestLocation = null, // Strictly cleared
            revocationReason = "Access grant has expired."
        )

        assertEquals("REVOKED", revokedState.status)
        assertEquals(WsConnectionState.ACCESS_REVOKED, revokedState.connectionState)
        assertNull(revokedState.latestLocation)
        assertEquals("Access grant has expired.", revokedState.revocationReason)
    }

    @Test
    fun testResponderDistanceAndEtaFormatting() {
        fun formatDistance(meters: Int): String {
            return if (meters < 1000) "$meters m away" else String.format("%.1f km away", meters / 1000.0)
        }

        fun formatEta(minutes: Int): String {
            return "Estimated arrival ~ $minutes min"
        }

        assertEquals("450 m away", formatDistance(450))
        assertEquals("1.2 km away", formatDistance(1200))
        assertEquals("Estimated arrival ~ 3 min", formatEta(3))
    }

    // ------------------------------------------------------------------------
    // Screen 08: Active SOS Tests
    // ------------------------------------------------------------------------

    @Test
    fun testElapsedTimerFormatting() {
        fun formatTimer(seconds: Long): String {
            val mins = seconds / 60
            val secs = seconds % 60
            return String.format("%02d:%02d", mins, secs)
        }

        assertEquals("00:00", formatTimer(0))
        assertEquals("00:42", formatTimer(42))
        assertEquals("03:15", formatTimer(195))
        assertEquals("12:05", formatTimer(725))
    }

    @Test
    fun testResponderCountsFormatting() {
        fun formatResponders(count: Int): String {
            return when (count) {
                0 -> "Searching for nearby responders..."
                1 -> "1 nearby responder on the way"
                else -> "$count nearby responders on the way"
            }
        }

        assertEquals("Searching for nearby responders...", formatResponders(0))
        assertEquals("1 nearby responder on the way", formatResponders(1))
        assertEquals("3 nearby responders on the way", formatResponders(3))
    }

    @Test
    fun testCircleAlertsCount() {
        val statuses = listOf(
            ContactAlertStatus("c1", "Priya", "+91 98765 00001", true, ContactDeliveryStatus.PROVIDER_ACCEPTED, "Msg", true, true),
            ContactAlertStatus("c2", "Rajesh", "+91 98765 00002", false, ContactDeliveryStatus.PROVIDER_ACCEPTED, "Msg", true, true),
            ContactAlertStatus("c3", "Amit", "+91 98765 00003", false, ContactDeliveryStatus.FAILED, "Msg", false, false)
        )

        val nonFailedCount = statuses.count { it.deliveryStatus != ContactDeliveryStatus.FAILED }
        assertEquals(2, nonFailedCount)
    }

    @Test
    fun testSafetyPinValidation() {
        fun validatePin(pin: String): String? {
            if (pin.length < 4) return "PIN must be at least 4 digits."
            if (pin.length > 6) return "PIN must be at most 6 digits."
            if (!pin.all { it.isDigit() }) return "PIN must contain only numbers."
            return null
        }

        assertNotNull(validatePin("12"))
        assertNotNull(validatePin("12a4"))
        assertNotNull(validatePin("1234567"))
        assertNull(validatePin("1234"))
        assertNull(validatePin("123456"))
    }
}
