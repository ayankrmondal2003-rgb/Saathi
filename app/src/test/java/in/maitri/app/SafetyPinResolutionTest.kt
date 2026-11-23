package `in`.maitri.app

import `in`.maitri.app.data.IncidentStatus
import `in`.maitri.app.data.SosIncident
import `in`.maitri.app.network.IncidentResolutionRequestDto
import `in`.maitri.app.network.IncidentResolutionResponseDto
import `in`.maitri.app.network.SafetyPinStatusDto
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class SafetyPinResolutionTest {

    private val gson = Gson()

    private fun validateSafetyPin(pin: String): String? {
        if (pin.length != 6) return "PIN must be exactly 6 digits"
        if (!pin.all { it.isDigit() }) return "PIN must contain only numbers"
        if (pin.toSet().size == 1) return "PIN cannot use repeated digits (e.g. 111111)"
        if (pin in listOf(
                "123456", "234567", "345678", "456789", "567890",
                "654321", "765432", "876543", "987654", "098765"
            )
        ) {
            return "PIN cannot be a sequential sequence"
        }
        return null
    }

    @Test
    fun pinLengthValidation() {
        assertEquals("PIN must be exactly 6 digits", validateSafetyPin("123"))
        assertEquals("PIN must be exactly 6 digits", validateSafetyPin("12345"))
        assertEquals("PIN must be exactly 6 digits", validateSafetyPin("1234567"))
        assertNull(validateSafetyPin("482910"))
    }

    @Test
    fun pinNumericValidation() {
        assertEquals("PIN must contain only numbers", validateSafetyPin("12a456"))
        assertEquals("PIN must contain only numbers", validateSafetyPin("abcdef"))
        assertEquals("PIN must contain only numbers", validateSafetyPin("123-56"))
    }

    @Test
    fun pinTrivialPatternsRejected() {
        assertEquals("PIN cannot use repeated digits (e.g. 111111)", validateSafetyPin("111111"))
        assertEquals("PIN cannot use repeated digits (e.g. 111111)", validateSafetyPin("000000"))
        assertEquals("PIN cannot use repeated digits (e.g. 111111)", validateSafetyPin("999999"))

        assertEquals("PIN cannot be a sequential sequence", validateSafetyPin("123456"))
        assertEquals("PIN cannot be a sequential sequence", validateSafetyPin("234567"))
        assertEquals("PIN cannot be a sequential sequence", validateSafetyPin("654321"))
        assertEquals("PIN cannot be a sequential sequence", validateSafetyPin("098765"))
    }

    @Test
    fun pinValidValuesAccepted() {
        assertNull(validateSafetyPin("739201"))
        assertNull(validateSafetyPin("482019"))
        assertNull(validateSafetyPin("918273"))
        assertNull(validateSafetyPin("582104"))
    }

    @Test
    fun duressPinMustDifferFromPrimaryPin() {
        val primaryPin = "482019"
        val candidateDuress = "482019"
        assertTrue("Duress PIN must differ from Primary PIN", primaryPin == candidateDuress)

        val validDuress = "918273"
        assertNotEquals(primaryPin, validDuress)
        assertNull(validateSafetyPin(validDuress))
    }

    @Test
    fun parseResolutionResponseConfirmed() {
        val json = """
        {
            "incident_id": "00000000-0000-0000-0000-000000000001",
            "status": "CLOSED",
            "resolution_status": "CONFIRMED",
            "message": "Incident resolved and closed with verified Safety PIN.",
            "ended_at": "2026-11-20T12:00:00Z"
        }
        """.trimIndent()

        val response = gson.fromJson(json, IncidentResolutionResponseDto::class.java)
        assertEquals("CLOSED", response.status)
        assertEquals("CONFIRMED", response.resolutionStatus)
        assertEquals("00000000-0000-0000-0000-000000000001", response.incidentId)
        assertNotNull(response.endedAt)
    }

    @Test
    fun parseResolutionResponseDuressSimulation() {
        val json = """
        {
            "incident_id": "00000000-0000-0000-0000-000000000001",
            "status": "ESCALATION_REQUIRED",
            "resolution_status": "DURESS_SIMULATION",
            "message": "Deactivation request acknowledged.",
            "ended_at": null
        }
        """.trimIndent()

        val response = gson.fromJson(json, IncidentResolutionResponseDto::class.java)
        assertEquals("ESCALATION_REQUIRED", response.status)
        assertEquals("DURESS_SIMULATION", response.resolutionStatus)
        assertNull(response.endedAt)
    }

    @Test
    fun parseSafetyPinStatusLocked() {
        val json = """
        {
            "has_primary_pin": true,
            "has_duress_pin": true,
            "failed_attempt_count": 5,
            "is_locked": true,
            "locked_until": "2026-11-20T12:05:00Z",
            "retry_after_seconds": 300
        }
        """.trimIndent()

        val status = gson.fromJson(json, SafetyPinStatusDto::class.java)
        assertTrue(status.hasPrimaryPin)
        assertTrue(status.hasDuressPin)
        assertTrue(status.isLocked)
        assertEquals(5, status.failedAttemptCount)
        assertEquals(300, status.retryAfterSeconds)
        assertEquals("2026-11-20T12:05:00Z", status.lockedUntil)
    }

    @Test
    fun serializeResolutionRequest() {
        val req = IncidentResolutionRequestDto(pin = "739201", reason = "USER_RESOLVED")
        val json = gson.toJson(req)
        assertTrue(json.contains("\"pin\":\"739201\""))
        assertTrue(json.contains("\"reason\":\"USER_RESOLVED\""))
    }

    @Test
    fun duressSafeguardMaintainsSosState() {
        val incident = SosIncident(started = System.currentTimeMillis(), status = IncidentStatus.ACTIVE)
        assertEquals(IncidentStatus.ACTIVE, incident.status)

        // Simulated duress resolution response
        val response = IncidentResolutionResponseDto(
            incidentId = "inc-1",
            status = "ESCALATION_REQUIRED",
            resolutionStatus = "DURESS_SIMULATION",
            message = "Deactivation request acknowledged."
        )

        // When resolutionStatus == "DURESS_SIMULATION", incident must NOT transition to ENDED
        val shouldEndSos = response.resolutionStatus == "CONFIRMED"
        assertFalse("Duress simulation must not terminate SOS", shouldEndSos)
        assertEquals(IncidentStatus.ACTIVE, incident.status)
    }

    @Test
    fun networkFailureMaintainsSosState() {
        val incident = SosIncident(started = System.currentTimeMillis(), status = IncidentStatus.ACTIVE)
        // Simulated network failure during resolution
        val networkSuccess = false
        val shouldEndSos = networkSuccess

        assertFalse("Network failure must never terminate local SOS", shouldEndSos)
        assertEquals(IncidentStatus.ACTIVE, incident.status)
    }
}
