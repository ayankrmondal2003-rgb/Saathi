package `in`.maitri.app

import `in`.maitri.app.data.EmergencyContact
import `in`.maitri.app.network.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/** The circle is server-backed: SOS alert dispatch reads only the server list. */
class TrustedContactSyncTest {
    private fun contact(id: String, name: String, phone: String, primary: Boolean = false, sos: Boolean = true) =
        EmergencyContact(id = id, displayName = name, phoneNumber = phone, isPrimary = primary, notifyOnSos = sos)

    private fun dto(id: String, name: String, phone: String, primary: Boolean = false) = TrustedContactDto(id, name, phone,
        phone.filter(Char::isDigit), "Emergency Contact", primary, false, "MANUAL", true, true, true, true, "SMS",
        "2026-11-23T10:00:00Z", "2026-11-23T10:00:00Z")

    private class FakeApi(var server: MutableList<TrustedContactDto>) : TrustedContactApi {
        val calls = mutableListOf<String>()
        var conflictOn: String? = null
        private var next = 0
        override suspend fun list(expected: ExpectedAccount) = server.toList().also { calls += "list" }
        override suspend fun create(body: TrustedContactWriteDto, expected: ExpectedAccount): TrustedContactDto {
            calls += "create:${body.displayName}"
            if (body.phoneNumber == conflictOn) throw HttpException(Response.error<Any>(409,
                """{"detail":"This phone number is already saved as a trusted contact."}""".toResponseBody("application/json".toMediaType())))
            val made = TrustedContactDto("srv-${next++}", body.displayName, body.phoneNumber, body.phoneNumber, body.label,
                body.isPrimary, false, "MANUAL", body.notifyOnSos, true, true, true, "SMS", "2026-11-23T10:00:00Z", "2026-11-23T10:00:00Z")
            server.add(made); return made
        }
        override suspend fun update(id: String, body: TrustedContactWriteDto, expected: ExpectedAccount): TrustedContactDto {
            calls += "update:$id"
            val i = server.indexOfFirst { it.id == id }
            server[i] = server[i].copy(displayName = body.displayName, notifyOnSos = body.notifyOnSos)
            return server[i]
        }
        override suspend fun delete(id: String, expected: ExpectedAccount): Response<Unit> {
            calls += "delete:$id"; server.removeAll { it.id == id }; return Response.success(204, Unit)
        }
        override suspend fun primary(id: String, expected: ExpectedAccount): TrustedContactDto {
            calls += "primary:$id"
            server = server.map { it.copy(isPrimary = it.id == id) }.toMutableList()
            return server.first { it.id == id }
        }
    }

    private val account = ExpectedAccount("uid-1")

    @Test fun diffFindsAddedRemovedChangedAndNewPrimary() {
        val a = contact("a", "Asha", "+91 90000 00001", primary = true)
        val b = contact("b", "Ravi", "+91 90000 00002")
        val c = contact("local-c", "Meera", "+91 90000 00003")
        val changes = contactChanges(listOf(a, b), listOf(a.copy(isPrimary = false), b.copy(notifyOnSos = false), c.copy(isPrimary = true)))
        assertEquals(listOf("local-c"), changes.added.map { it.id })
        assertEquals(listOf("b"), changes.changed.map { it.id })
        assertTrue(changes.removed.isEmpty())
        assertEquals("local-c", changes.newPrimary)
        // Pure primary moves are not "changed" field updates; they go through the primary endpoint.
        assertEquals(listOf<String>(), contactChanges(listOf(a, b), listOf(a.copy(isPrimary = false), b.copy(isPrimary = true))).changed.map { it.id })
    }

    @Test fun pushDeletesFirstAndUsesTheServerIdForANewPrimary() = runTest {
        val api = FakeApi(mutableListOf(dto("srv-a", "Asha", "+91 90000 00001", primary = true)))
        val before = listOf(contact("srv-a", "Asha", "+91 90000 00001", primary = true))
        val after = listOf(contact("local-new", "Asha", "+91 90000 00001", primary = true))  // replaced with same number
        val result = pushContactChanges(api, account, contactChanges(before, after))
        assertEquals(listOf("delete:srv-a", "create:Asha", "primary:srv-0", "list"), api.calls)
        assertEquals(listOf("srv-0"), result.map { it.id })
    }

    @Test fun firstSyncUploadsLocalContactsOnceAndSkipsDuplicates() = runTest {
        val api = FakeApi(mutableListOf())
        api.conflictOn = "+91 90000 00002"
        val result = uploadLocalContacts(api, account, listOf(contact("x", "Ravi", "+91 90000 00002"),
            contact("y", "Asha", "+91 90000 00001", primary = true)))
        assertEquals(listOf("create:Asha", "create:Ravi", "list"), api.calls)  // primary uploaded first
        assertEquals(listOf("Asha"), result.map { it.displayName })
    }

    @Test fun serverListBecomesTheLocalCircle() {
        val local = dto("srv-1", "Asha", "+91 90000 00001", primary = true).toContact("uid-1")
        assertEquals("srv-1", local.id)
        assertEquals("uid-1", local.ownerUserId)
        assertTrue(local.isPrimary && local.notifyOnSos)
        assertTrue(local.createdAt > 0)
    }

    @Test fun writeBodyMatchesTheServerSchemaExactly() {
        val keys = JsonParser.parseString(Gson().toJson(TrustedContactWriteDto.of(contact("a", "Asha", "+91 90000 00001"))))
            .asJsonObject.keySet()
        // backend/app/schemas.py TrustedContactCreate forbids unknown fields.
        assertEquals(setOf("display_name", "phone_number", "label", "is_primary", "notify_on_sos", "include_location_link",
            "notify_on_closure", "allow_live_tracking", "preferred_channel"), keys)
        assertEquals("SMS", TrustedContactWriteDto.of(contact("a", "A", "+91 90000 00001").copy(preferredChannel = "WHATSAPP")).preferredChannel)
    }

    @Test fun syncFailuresNeverImplyTheContactWillBeAlerted() {
        assertTrue(contactSyncFailure(IOException("offline")).contains("won't be used for SOS alerts"))
        val conflict = HttpException(Response.error<Any>(409,
            """{"detail":"This phone number is already saved as a trusted contact."}""".toResponseBody("application/json".toMediaType())))
        assertEquals("This phone number is already saved as a trusted contact.", contactSyncFailure(conflict))
        val forbidden = HttpException(Response.error<Any>(403, "{}".toResponseBody("application/json".toMediaType())))
        assertEquals("Sign in again to save your circle.", contactSyncFailure(forbidden))
    }
}
