package `in`.maitri.app

import `in`.maitri.app.network.invitationFailure
import `in`.maitri.app.network.staleResponderFix
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.File

/** Checkpoint A wiring: screen 06 decisions, the Responder tab and recoverable GPS conflicts. */
class MaitriCheckpointATest {
    private val ui = "src/main/java/in/maitri/app/ui"
    private fun http(code: Int, detail: String) = HttpException(Response.error<Any>(code,
        """{"detail":"$detail"}""".toResponseBody("application/json".toMediaType())))

    @Test fun incomingScreenUsesServerConfirmedDecisionsAndServerReasons() {
        val source = File("$ui/MaitriIncoming.kt").readText()
        assertTrue(source.contains("confirmedInvitationResponse(decision)"))
        assertTrue(source.contains("invitationFailure(e)"))
        // The previous local mapping blamed location freshness for every 403 and "already answered" for every 409.
        assertFalse(source.contains("Responder location must be fresh"))
        assertFalse(source.contains("Invitation already answered."))
    }

    @Test fun forbiddenAndConflictReasonsStayDistinct() {
        val radius = invitationFailure(http(403, "Fresh matching responder location within the active radius required"))
        val unavailable = invitationFailure(http(403, "Responder unavailable"))
        val inactive = invitationFailure(http(409, "Incident is inactive"))
        val answered = invitationFailure(http(409, "Invitation already answered"))
        assertEquals(4, setOf(radius, unavailable, inactive, answered).size)
        assertTrue(unavailable.contains("availability"))
        assertTrue(inactive.contains("no longer active"))
        assertTrue(invitationFailure(http(403, "Volunteer approval required")).contains("must be approved"))
    }

    @Test fun onlyTheMarkedMonotonicConflictIsRecoverable() {
        assertTrue(staleResponderFix(409, "STALE_OR_DUPLICATE_LOCATION"))
        assertFalse(staleResponderFix(409, null))
        assertFalse(staleResponderFix(422, "STALE_OR_DUPLICATE_LOCATION"))
        // The dashboard advances past the rejected timestamp and keeps collecting fixes.
        val dashboard = File("$ui/TestResponderDashboard.kt").readText()
        assertTrue(dashboard.contains("lastSubmittedTimestamp = maxOf(lastSubmittedTimestamp, fix.timestampMillis)"))
    }

    @Test fun responderTabAlwaysRendersAScreen() {
        val graph = File("src/main/java/in/maitri/app/navigation/SafetyApp.kt").readText()
        assertTrue(graph.contains("} else ResponderUnavailable()"))
        assertFalse("Toast durations use named constants", Regex("""Toast\.makeText\([^)]*, 0\)""").containsMatchIn(graph))
    }

    @Test fun emergencyArtworkKeepsOnlyStaticCopy() {
        // Screens 06-08 paint no text at all from artwork: titles, buttons and tabs are native (ArtText), so they
        // translate and scale with large text, and sample names, distances and ETAs from the design can't appear.
        // The Responder tab no longer uses artwork: its header and live map are native (ResponderHeader, ResponderMapCard).
        val expected = mapOf("06" to 0, "07" to 0, "08" to 0)
        for (theme in listOf("light", "dark")) for ((screen, count) in expected) {
            val xml = File("src/main/res/drawable/maitri_${theme}_$screen.xml").readText()
            val glyphs = Regex("""pathData="([^"]*)"""").findAll(xml).count { it.groupValues[1].length >= 1000 }
            assertEquals("$theme-$screen glyph paths", count, glyphs)
        }
    }
}
