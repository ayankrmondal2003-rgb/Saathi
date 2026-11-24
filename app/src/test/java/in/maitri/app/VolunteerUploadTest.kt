package `in`.maitri.app

import `in`.maitri.app.ui.applicationBanner
import `in`.maitri.app.ui.applicationLocked
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** P4: application states shown on screen 04, and the bundled declaration template. */
class VolunteerUploadTest {
    @Test fun bannersFollowTheReviewOutcome() {
        assertNull(applicationBanner("DRAFT", null))
        assertNull(applicationBanner(null, null))
        assertEquals("Application under review", applicationBanner("UNDER_REVIEW", null)?.first)
        assertEquals("Police certificate is unreadable", applicationBanner("REQUIRES_CORRECTION", "Police certificate is unreadable")?.second)
        assertTrue(applicationBanner("APPROVED", null)!!.second.contains("Available for nearby requests"))
    }

    @Test fun submittedApplicationsCantChangeButRejectedOnesCan() {
        listOf("SUBMITTED", "UNDER_REVIEW", "APPROVED").forEach { assertTrue(it, applicationLocked(it)) }
        listOf("DRAFT", "REJECTED", "REQUIRES_CORRECTION", null).forEach { assertFalse(it.toString(), applicationLocked(it)) }
    }

    @Test fun declarationTemplateIsABundledPdf() {
        val pdf = File("src/main/assets/volunteer-declaration.pdf").readBytes()
        assertEquals("%PDF", String(pdf.copyOfRange(0, 4)))
        assertTrue(pdf.size in 1000..200_000)
    }
}
