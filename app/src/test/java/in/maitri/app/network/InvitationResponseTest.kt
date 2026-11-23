package `in`.maitri.app.network

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.util.UUID

class InvitationResponseTest {
    @Test fun onlyGenuineInvitationsNavigate() {
        val invitation = UUID.randomUUID().toString()
        val incident = UUID.randomUUID().toString()
        val event = "RESPONDER_INVITATION_CREATED"
        assertEquals(invitation, invitationFromAlert(event, invitation, incident))
        for (invalid in listOf(null, "", "test-example", "1-1-1-1-1", incident))
            assertNull(invitationFromAlert(event, invalid, incident))
        assertNull(invitationFromAlert("TEST_ALERT", invitation, incident))
        assertNull(invitationFromAlert(event, invitation, null))
        assertEquals("""{"decision":"ACCEPTED"}""", Gson().toJson(DecisionDto("ACCEPTED")))
    }

    @Test fun responsesRequireMatchingServerConfirmation() = kotlinx.coroutines.test.runTest {
        assertEquals("ACCEPTED", confirmedInvitationResponse("ACCEPTED") { StatusDto("ACCEPTED") })
        assertEquals("DECLINED", confirmedInvitationResponse("DECLINED") { StatusDto("DECLINED") })
        assertEquals("EXPIRED", confirmedInvitationResponse("ACCEPTED") { StatusDto("EXPIRED") })
        assertTrue(runCatching {
            confirmedInvitationResponse("DECLINED") { throw java.io.IOException() }
        }.isFailure)
        assertTrue(runCatching {
            confirmedInvitationResponse("ACCEPTED") { StatusDto("DECLINED") }
        }.isFailure)
    }

    @Test fun validationMessagesExcludeInputAndDistinguishConflicts() {
        fun message(code: Int, detail: String, debug: Boolean = false): String {
            val body = """{"detail":$detail}""".toResponseBody("application/json".toMediaType())
            return invitationFailure(HttpException(Response.error<Unit>(code, body)), debug)
        }
        assertEquals("Invalid invitation identifier. Refresh your inbox.",
            message(422, """[{"loc":["path","invitation_id"],"type":"uuid_parsing","input":"private"}]"""))
        assertTrue(message(409, "\"" + "Invitation already answered" + "\"").contains("already answered"))
        assertTrue(message(409, "\"" + "Incident is inactive" + "\"").contains("no longer active"))
        val debugMessage = message(422,
            """[{"loc":["path","invitation_id"],"type":"uuid_parsing","input":"private","msg":"private"}]""",
            debug = true)
        assertTrue(debugMessage.contains("path.invitation_id (uuid_parsing)"))
        assertFalse(debugMessage.contains("private"))
        assertTrue(message(400, "null").contains("Invalid"))
        assertTrue(message(401, "null").contains("Sign in"))
        assertTrue(message(403, "null").contains("ineligible"))
        assertTrue(message(404, "null").contains("unavailable"))
        assertTrue(message(503, "null").contains("unavailable"))
        assertTrue(invitationFailure(java.io.IOException()).contains("Reconnect"))
    }
}
