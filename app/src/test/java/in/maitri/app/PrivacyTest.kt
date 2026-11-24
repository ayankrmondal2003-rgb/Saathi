package `in`.maitri.app

import `in`.maitri.app.network.MaitriLinks
import `in`.maitri.app.ui.betaSections
import `in`.maitri.app.ui.deleteErrorMessage
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

/** P6: the policy pages, the app and the server say the same thing about retention and contact. */
class PrivacyTest {
    private val site = File("../docs/site")

    @Test fun linksPointAtThePublishedPages() {
        listOf(MaitriLinks.PRIVACY, MaitriLinks.TERMS, MaitriLinks.DELETE_ACCOUNT).forEach {
            assertTrue(it, it.startsWith("https://"))
            assertTrue(it, File(site, it.substringAfterLast('/')).isFile)
        }
    }

    @Test fun policyMatchesWhatTheServerDoes() {
        val policy = File(site, "privacy.html").readText()
        val config = File("../backend/app/config.py").readText()
        assertTrue(config.contains("closed_location_hours: int = Field(168"))
        assertTrue(policy.contains("erased 7 days after the SOS ends"))
        assertTrue(config.contains("sos_media_days: int = Field(30"))
        assertTrue(policy.contains("deleted 30 days after"))
        assertTrue(policy.contains(MaitriLinks.SUPPORT_EMAIL))
        assertTrue(policy.contains("OpenFreeMap"))
        assertTrue(File(site, "delete-account.html").readText().contains("Delete my account"))
    }

    @Test fun betaInfoPutsTheEmergencyNumberFirst() {
        assertTrue(betaSections().first().second.contains("112"))
        assertTrue(betaSections().any { it.first == "Testing an SOS safely" && it.second.contains("Safety PIN") })
    }

    @Test fun deleteErrorsAreInWords() {
        val text = deleteErrorMessage(IOException("offline"))
        assertFalse(Regex("\\b[45]\\d\\d\\b").containsMatchIn(text))
        assertTrue(text.contains("connection"))
    }
}
