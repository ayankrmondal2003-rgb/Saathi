package `in`.maitri.app.network

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class PinResolutionTest {
    private fun message(code: Int, detail: String): String {
        val body = """{"detail":"$detail"}""".toResponseBody("application/json".toMediaType())
        return pinFailure(HttpException(Response.error<Unit>(code, body)))
    }

    @Test fun distinguishesEnrollmentOwnershipAndFailedAttempts() {
        assertEquals(MISSING_PRIMARY_PIN, message(400, "Safety PIN not enrolled. Configure your Safety PIN in settings first."))
        assertEquals("Only the incident owner can resolve the incident.", message(403, "Only the incident owner can resolve the incident."))
        assertEquals("Incorrect Safety PIN. 3 attempt(s) remaining.", message(403, "Incorrect Safety PIN. 3 attempt(s) remaining."))
        assertTrue(message(429, "").contains("cooldown"))
        assertFalse(message(422, "private-input").contains("private-input"))
    }

    @Test fun requestUsesServerSchemaAndErrorsDistinguishRequiredAction() {
        val json = com.google.gson.Gson().toJsonTree(IncidentResolutionRequestDto("654028")).asJsonObject
        assertEquals(setOf("pin", "reason"), json.keySet())
        assertEquals("654028", json["pin"].asString)
        assertEquals("USER_RESOLVED", json["reason"].asString)
        assertTrue(message(401, "Invalid session").contains("Sign in"))
        assertTrue(message(403, "Sign in again to recover your Safety PIN").contains("password"))
        assertTrue(message(409, "").contains("incident state"))
        assertTrue(message(422, "").contains("Invalid PIN request"))
        assertEquals("Safety PIN verification is rate-limited. Retry in 83 seconds.",
            message(429, "Safety PIN verification is rate-limited. Retry in 83 seconds."))
    }

    @Test fun pinStatusRequiresServerEvidence() {
        val configured = SafetyPinStatusDto(true, false, 0, false)
        assertEquals(PrimaryPinState.UNKNOWN, primaryPinState(null))
        assertEquals(PrimaryPinState.NOT_CONFIGURED, primaryPinState(configured.copy(hasPrimaryPin = false)))
        assertEquals(PrimaryPinState.CONFIGURED, primaryPinState(configured))
        assertEquals(PrimaryPinState.LOCKED, primaryPinState(configured.copy(isLocked = true)))
        assertEquals(PrimaryPinState.RECOVERY_REQUIRED, primaryPinState(configured, recovering = true))
    }

    @Test fun networkFailureCannotConfirmClosure() = runTest {
        val result = runCatching { resolveWithPin("incident") { throw IOException() } }
        assertTrue(result.exceptionOrNull() is IOException)
        assertTrue(pinFailure(IOException()).contains("unconfirmed"))
    }

    @Test fun requiresMatchingClosedIncident() = runTest {
        val response = IncidentResolutionResponseDto("id", "CLOSED", "CONFIRMED", "closed")
        assertEquals(response, resolveWithPin("id") { response })
        assertTrue(runCatching { resolveWithPin("other") { response } }.isFailure)
        assertTrue(runCatching { resolveWithPin("id") { response.copy(status = "ACTIVE") } }.isFailure)
    }
}
