package `in`.maitri.app.network

import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class CircleSharingTest {
    private val gson = Gson()

    @Test fun checkInOmitsLocationAndUsesSeparateContract() {
        val json = JsonParser.parseString(gson.toJson(CircleShareRequest("CHECK_IN", listOf("contact"), true))).asJsonObject
        assertEquals("CHECK_IN", json["kind"].asString)
        assertTrue(json["consent"].asBoolean)
        assertFalse(json.has("location"))
        assertFalse(json.has("incident_id"))
        assertEquals("contact", json["contact_ids"].asJsonArray[0].asString)
    }

    @Test fun tripFixPreservesMeasuredTimestampAndMockFlag() {
        val request = CircleShareRequest("TRIP", listOf("contact"), true,
            FixDto(22.57, 88.36, "2026-11-23T00:00:00Z", false, 8.0))
        val json = JsonParser.parseString(gson.toJson(request)).asJsonObject
        assertEquals(30, json["duration_minutes"].asInt)
        assertEquals("2026-11-23T00:00:00Z", json["location"].asJsonObject["recorded_at"].asString)
        assertFalse(json["location"].asJsonObject["is_mock"].asBoolean)
    }

    @Test fun inboxAvailabilityNeverClaimsDelivery() {
        assertEquals("Available in recipient’s app — not confirmed opened", circleReceiptLabel("AVAILABLE_IN_APP"))
        assertEquals("Opened in recipient’s app", circleReceiptLabel("OPENED"))
        assertEquals("Notification status unknown", circleReceiptLabel("FCM_ACCEPTED"))
        assertEquals("Notification status unknown", circleReceiptLabel("PENDING"))
    }

    @Test fun errorsDoNotExposeServerBodiesOrClaimSuccess() {
        for (code in listOf(400, 401, 403, 404, 409, 422, 429, 500, 503)) {
            val error = HttpException(Response.error<Unit>(code, "private-token-and-location".toResponseBody()))
            val message = circleSharingError(error)
            assertFalse(message.contains("private-token"))
            assertFalse(message.startsWith("Success", ignoreCase = true))
        }
        assertTrue(circleSharingError(IOException("sensitive connection string")).contains("unconfirmed"))
        assertFalse(circleSharingError(IOException("sensitive connection string")).contains("sensitive"))
    }

    @Test fun stoppedResponseHasNoAssumedLocation() {
        val dto = gson.fromJson("""{"id":"session","kind":"TRIP","status":"STOPPED","created_at":"time",
            "expires_at":"later","latest_fix":null,"fix_received_at":null,"is_owner":true,"recipients":[]}""", CircleSessionDto::class.java)
        assertNull(dto.fix)
        assertNull(dto.syncedAt)
        assertEquals("STOPPED", dto.status)
    }

    @Test fun pairingRequestDtoSerialization() {
        val req = PairingRequestDto("trusted@example.com", true)
        val json = JsonParser.parseString(gson.toJson(req)).asJsonObject
        assertEquals("trusted@example.com", json["owner_email"].asString)
        assertTrue(json["consent"].asBoolean)
    }

    @Test fun bindCircleDtoSerialization() {
        val bind = BindCircleDto("contact-uuid-1", "secret-pairing-code")
        val json = JsonParser.parseString(gson.toJson(bind)).asJsonObject
        assertEquals("contact-uuid-1", json["contact_id"].asString)
        assertEquals("secret-pairing-code", json["code"].asString)
    }

    @Test fun activeSessionWithRecipientsDeserialization() {
        val json = """{
            "id": "sess-42",
            "kind": "TRIP",
            "status": "ACTIVE",
            "created_at": "2026-11-23T01:00:00Z",
            "expires_at": "2026-11-23T01:30:00Z",
            "latest_fix": {
                "latitude": 22.5726,
                "longitude": 88.3639,
                "recorded_at": "2026-11-23T01:05:00Z",
                "is_mock": false,
                "accuracy_meters": 4.5
            },
            "fix_received_at": "2026-11-23T01:05:01Z",
            "is_owner": true,
            "recipients": [
                {
                    "contact_id": "contact-1",
                    "notification_status": "AVAILABLE_IN_APP",
                    "opened_at": null,
                    "revoked": false
                },
                {
                    "contact_id": "contact-2",
                    "notification_status": "OPENED",
                    "opened_at": "2026-11-23T01:10:00Z",
                    "revoked": false
                }
            ]
        }"""
        val session = gson.fromJson(json, CircleSessionDto::class.java)
        assertEquals("sess-42", session.id)
        assertEquals("TRIP", session.kind)
        assertEquals("ACTIVE", session.status)
        assertTrue(session.owner)
        assertNotNull(session.fix)
        assertEquals(22.5726, session.fix!!.latitude, 0.0001)
        assertEquals(2, session.recipients.size)
        assertEquals("AVAILABLE_IN_APP", session.recipients[0].status)
        assertNull(session.recipients[0].openedAt)
        assertFalse(session.recipients[0].revoked)
        assertEquals("OPENED", session.recipients[1].status)
        assertEquals("2026-11-23T01:10:00Z", session.recipients[1].openedAt)
    }
}
