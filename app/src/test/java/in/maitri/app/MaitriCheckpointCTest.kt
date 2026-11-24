package `in`.maitri.app

import `in`.maitri.app.R.string as S
import `in`.maitri.app.data.ProfileRules
import `in`.maitri.app.network.*
import `in`.maitri.app.ui.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.File
import java.io.IOException

/** Checkpoint C: screens 12 Permissions, 13 Safety PIN and 14 Edit profile. */
class MaitriCheckpointCTest {
    private fun http(code: Int, detail: String) = HttpException(Response.error<Any>(code,
        """{"detail":"$detail"}""".toResponseBody("application/json".toMediaType())))

    // Screen 12 ----------------------------------------------------------------

    @Test
    fun accessStateReflectsPlatformState() {
        assertEquals(AccessState.NOT_NEEDED, accessState(declared = false, granted = false, asked = false, rationale = false))
        assertEquals(AccessState.ALLOWED, accessState(declared = true, granted = true, asked = true, rationale = false))
        assertEquals(AccessState.ASK, accessState(declared = true, granted = false, asked = false, rationale = false))
        assertEquals(AccessState.ASK, accessState(declared = true, granted = false, asked = true, rationale = true))
        assertEquals(AccessState.BLOCKED, accessState(declared = true, granted = false, asked = true, rationale = false))
        assertEquals(AccessState.APPROXIMATE, accessState(declared = true, granted = false, asked = true, rationale = false, approximate = true))
        // A granted notification permission with notifications switched off is still not delivered.
        assertEquals(AccessState.BLOCKED, accessState(declared = true, granted = true, asked = true, rationale = false, systemEnabled = false))
    }

    @Test
    fun permissionRowsMatchCropOrderAndLabels() {
        assertEquals(listOf("LOCATION", "NOTIFICATIONS", "CONTACTS", "MICROPHONE", "CAMERA"), PermissionRow.entries.map { it.name })
        assertEquals(S.perm_allowed, AccessState.ALLOWED.label)
        assertEquals(S.perm_ask, AccessState.ASK.label)
        assertEquals(S.perm_not_allowed, AccessState.BLOCKED.label)
        // Rows for permissions Maitri never declares explain the system picker / external app instead.
        listOf(PermissionRow.CONTACTS, PermissionRow.MICROPHONE, PermissionRow.CAMERA).forEach { assertNotNull(it.info) }
    }

    @Test
    fun manifestDoesNotDeclareUnusedSensitivePermissions() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        // RECORD_AUDIO is used since P5 (SOS voice notes); contacts and camera still go through system pickers/apps.
        listOf("READ_CONTACTS", "android.permission.CAMERA\"").forEach { assertFalse(it, manifest.contains(it)) }
        listOf("ACCESS_FINE_LOCATION", "POST_NOTIFICATIONS", "RECORD_AUDIO").forEach { assertTrue(it, manifest.contains(it)) }
    }

    // Screen 13 ----------------------------------------------------------------

    @Test
    fun pinWeaknessMirrorsServerRules() {
        assertEquals(PinWeakness.LENGTH, pinWeakness("12345"))
        assertEquals(PinWeakness.LENGTH, pinWeakness("12a456"))
        assertEquals(PinWeakness.REPEATED, pinWeakness("111111"))
        listOf("012345", "123456", "456789").forEach { assertEquals(it, PinWeakness.SEQUENTIAL, pinWeakness(it)) }
        listOf("543210", "654321", "987654").forEach { assertEquals(it, PinWeakness.REVERSE, pinWeakness(it)) }
        listOf("839162", "520847", "406183", "112233").forEach { assertNull(it, pinWeakness(it)) }
    }

    @Test
    fun pinEntryHintGatesSubmission() {
        assertEquals(S.pin_hint_setup, pinEntryHint("8391", ""))
        assertEquals(S.pin_weak_repeated, pinEntryHint("222222", "222222"))
        assertEquals(S.pin_weak_sequence, pinEntryHint("123456", "123456"))
        assertEquals(S.pin_mismatch, pinEntryHint("839162", "839163"))
        assertNull(pinEntryHint("839162", "839162"))
    }

    @Test
    fun pinModeFollowsServerEnrollment() {
        assertEquals(PinMode.SETUP, pinModeFor(SafetyPinStatusDto(false, false, 0, false)))
        assertEquals(PinMode.CHANGE_CURRENT, pinModeFor(SafetyPinStatusDto(true, false, 0, false)))
        assertEquals(PinMode.CHANGE_CURRENT, pinModeFor(SafetyPinStatusDto(true, false, 5, true, retryAfterSeconds = 60)))
    }

    @Test
    fun pinFailureExplainsChangeRecoveryAndClosureErrors() {
        assertEquals("Current PIN is incorrect. 4 attempt(s) remaining.", pinFailure(http(403, "Current PIN is incorrect. 4 attempt(s) remaining.")))
        assertEquals("New PIN must be different from current PIN.", pinFailure(http(422, "New PIN must be different from current PIN.")))
        assertEquals("No Safety PIN is set yet. Create one first.", pinFailure(http(404, "No existing Safety PIN configured.")))
        assertEquals("Wait one minute after a PIN change before recovering again.",
            pinFailure(http(429, "Wait one minute after a PIN change before recovering again.")))
        assertEquals("Verify your account password again to recover your PIN.", pinFailure(http(403, "Sign in again to recover your Safety PIN")))
        assertEquals("PIN already enrolled. Use PIN change or recovery.", pinFailure(http(409, "PIN already enrolled. Use PIN change or recovery.")))
        // Closure without enrollment routes the SOS dialog to screen 13.
        assertEquals(MISSING_PRIMARY_PIN, pinFailure(http(400, "Safety PIN not enrolled. Configure your Safety PIN in settings first.")))
        assertEquals("Incorrect Safety PIN. 2 attempt(s) remaining.", pinFailure(http(403, "Incorrect Safety PIN. 2 attempt(s) remaining.")))
        assertTrue(pinFailure(IOException("offline")).contains("unconfirmed"))
    }

    @Test
    fun pinRequestsSerializeServerContract() {
        val gson = Gson()
        assertEquals(setOf("current_pin", "new_pin", "confirm_pin", "credential_type"),
            JsonParser.parseString(gson.toJson(SafetyPinChangeDto("839162", "520847", "520847"))).asJsonObject.keySet())
        assertEquals(setOf("new_pin", "confirm_pin"),
            JsonParser.parseString(gson.toJson(SafetyPinRecoveryDto("406183", "406183"))).asJsonObject.keySet())
    }

    // Screen 14 ----------------------------------------------------------------

    @Test
    fun profileRulesMatchServerValidation() {
        assertTrue(ProfileRules.phoneValid(""))
        assertTrue(ProfileRules.phoneValid("+91 98765 43210"))
        assertTrue(ProfileRules.phoneValid("020 2345-6789"))
        assertFalse(ProfileRules.phoneValid("(020) 2345-6789"))
        assertFalse(ProfileRules.phoneValid("12"))
        assertFalse(ProfileRules.phoneValid("call me"))
        assertFalse(ProfileRules.phoneValid("+1234567890123456"))
        assertFalse(ProfileRules.nameValid("   "))
        assertFalse(ProfileRules.cityValid("x".repeat(129)))
        assertFalse(ProfileRules.cityValid("Pune\u0007"))
        assertFalse(ProfileRules.notesValid("n".repeat(1001)))
    }

    @Test
    fun profileFormReportsFirstProblem() {
        assertEquals(S.edit_name_required, profileFormError("", "12", "", ""))
        assertEquals(S.edit_phone_invalid, profileFormError("Asha", "12", "", ""))
        assertEquals(S.edit_city_invalid, profileFormError("Asha", "", "c".repeat(200), ""))
        assertEquals(S.edit_notes_invalid, profileFormError("Asha", "", "Pune", "n".repeat(1001)))
        assertNull(profileFormError("Asha", "+91 98765 43210", "Pune", "Asthma; inhaler in bag"))
    }

    @Test
    fun profileUpdateNeverSendsResponderFlagsOrMedicalNotes() {
        val keys = JsonParser.parseString(Gson().toJson(ProfileDetailsRequest("Asha", "+91 98765 43210", "Pune"))).asJsonObject.keySet()
        assertEquals(setOf("display_name", "phone_number", "city"), keys)
        val dto = Gson().fromJson("""{"display_name":"Asha","email":"a@example.invalid","responder_opt_in":true,
            "responder_available":false,"phone_number":"+91 98765 43210","city":"Pune"}""", ProfileDto::class.java)
        assertEquals("+91 98765 43210", dto.phoneNumber)
        assertEquals("Pune", dto.city)
    }

    // Assets -------------------------------------------------------------------

    @Test
    fun screenBackgroundsExistForBothThemesWithoutOutlinedText() {
        for (theme in listOf("light", "dark")) for (screen in listOf("12", "13", "14")) {
            val file = File("src/main/res/drawable/maitri_${theme}_$screen.xml")
            assertTrue(file.path, file.exists())
            val xml = file.readText()
            assertTrue(xml.contains("android:viewportWidth=\"390\""))
            // Outlined glyph paths are thousands of characters long; text must be native and localizable.
            assertTrue(file.path, Regex("""pathData="([^"]*)"""").findAll(xml).all { it.groupValues[1].length < 1000 })
        }
        // Screen 13's PIN boxes, checkbox and Save button are native, stateful controls rather than artwork.
        assertEquals(9, Regex("<path ").findAll(File("src/main/res/drawable/maitri_light_13.xml").readText()).count())
    }

    @Test
    fun everyLocaleTranslatesEveryScreenString() {
        fun keys(dir: String) = Regex("""name="([^"]+)"""").findAll(File("src/main/res/$dir/maitri_screens_12_14.xml").readText())
            .map { it.groupValues[1] }.toSet()
        val english = keys("values")
        assertTrue(english.size >= 90)
        listOf("hi", "bn", "mr", "ta", "te").forEach { assertEquals(it, english, keys("values-$it")) }
    }
}
