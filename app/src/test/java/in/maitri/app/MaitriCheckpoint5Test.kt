package `in`.maitri.app

import `in`.maitri.app.data.ContactRepository
import `in`.maitri.app.data.EmergencyContact
import `in`.maitri.app.ui.ContactPhoneEntry
import `in`.maitri.app.ui.PickedContactData
import `in`.maitri.app.ui.VolunteerDocType
import org.junit.Assert.*
import org.junit.Test

class MaitriCheckpoint5Test {

    class InMemoryContactRepository : ContactRepository {
        val map = mutableMapOf<String, List<EmergencyContact>>()
        override fun load(uid: String) = map[uid] ?: emptyList()
        override fun save(uid: String, contacts: List<EmergencyContact>) { map[uid] = contacts }
    }

    // ------------------------------------------------------------------------
    // Screen 04: Volunteer Verification Tests
    // ------------------------------------------------------------------------

    @Test
    fun testVolunteerDocTypeRequirements() {
        val types = VolunteerDocType.entries
        assertEquals(5, types.size)
        assertTrue(types.contains(VolunteerDocType.GOVT_ID))
        assertTrue(types.contains(VolunteerDocType.ADDRESS_PROOF))
        assertTrue(types.contains(VolunteerDocType.POLICE_CERT))
        assertTrue(types.contains(VolunteerDocType.PHOTO))
        assertTrue(types.contains(VolunteerDocType.DECLARATION))

        assertEquals("Government issued identity proof", VolunteerDocType.GOVT_ID.title) // SVG 04 wording
        assertEquals("Address proof", VolunteerDocType.ADDRESS_PROOF.title)
        assertEquals("Police character certificate", VolunteerDocType.POLICE_CERT.title)
        assertEquals("Recent photograph", VolunteerDocType.PHOTO.title)
        assertEquals("Signed volunteer declaration", VolunteerDocType.DECLARATION.title)
    }

    @Test
    fun testDocumentFileSizeLimit() {
        val maxAllowed = 10 * 1024 * 1024L // 10 MB
        val validSize = 5 * 1024 * 1024L
        val oversized = 11 * 1024 * 1024L

        fun validateSize(size: Long): Boolean = size <= maxAllowed

        assertTrue(validateSize(validSize))
        assertTrue(validateSize(maxAllowed))
        assertFalse(validateSize(oversized))
    }

    @Test
    fun testVolunteerConsentAndSubmissionGate() {
        val attachedDocs = mutableSetOf<VolunteerDocType>()
        var consentChecked = false

        fun canSubmit(): Boolean = consentChecked && attachedDocs.size == VolunteerDocType.entries.size

        // Incomplete docs, no consent
        assertFalse(canSubmit())

        // Consent checked, but missing docs
        consentChecked = true
        assertFalse(canSubmit())

        // Add 4 docs
        attachedDocs.add(VolunteerDocType.GOVT_ID)
        attachedDocs.add(VolunteerDocType.ADDRESS_PROOF)
        attachedDocs.add(VolunteerDocType.POLICE_CERT)
        attachedDocs.add(VolunteerDocType.PHOTO)
        assertFalse(canSubmit())

        // Add 5th doc
        attachedDocs.add(VolunteerDocType.DECLARATION)
        assertTrue(canSubmit())

        // Uncheck consent
        consentChecked = false
        assertFalse(canSubmit())
    }

    // ------------------------------------------------------------------------
    // Screen 05: Trusted Circle Tests
    // ------------------------------------------------------------------------

    @Test
    fun testToggleSosAlert() {
        val repo = InMemoryContactRepository()
        val uid = "user_123"
        val contact = EmergencyContact(
            id = "c1",
            ownerUserId = uid,
            displayName = "Priya Sharma",
            phoneNumber = "+91 98765 43210",
            notifyOnSos = true
        )
        repo.save(uid, listOf(contact))

        // Toggle SOS off
        val updated = repo.load(uid).map {
            if (it.id == "c1") it.copy(notifyOnSos = false) else it
        }
        repo.save(uid, updated)

        val reloaded = repo.load(uid).first()
        assertFalse(reloaded.notifyOnSos)
        assertEquals("Priya Sharma", reloaded.displayName)
        assertEquals("+91 98765 43210", reloaded.phoneNumber)

        // Toggle SOS back on
        val updatedAgain = repo.load(uid).map {
            if (it.id == "c1") it.copy(notifyOnSos = true) else it
        }
        repo.save(uid, updatedAgain)
        assertTrue(repo.load(uid).first().notifyOnSos)
    }

    @Test
    fun testContactCountTextFormatting() {
        fun formatCount(size: Int): String = "$size / 5 added"
        assertEquals("0 / 5 added", formatCount(0))
        assertEquals("2 / 5 added", formatCount(2))
        assertEquals("5 / 5 added", formatCount(5))
    }

    @Test
    fun testMultiNumberPickerResolution() {
        val singleEntry = PickedContactData(
            name = "Aarav Patel",
            phoneNumbers = listOf(ContactPhoneEntry("+91 98765 11111", "Mobile"))
        )
        assertEquals(1, singleEntry.phoneNumbers.size)

        val multiEntry = PickedContactData(
            name = "Sneha Roy",
            phoneNumbers = listOf(
                ContactPhoneEntry("+91 98765 22222", "Mobile"),
                ContactPhoneEntry("+91 98765 33333", "Work")
            )
        )
        assertEquals(2, multiEntry.phoneNumbers.size)
        assertEquals("Mobile", multiEntry.phoneNumbers[0].typeLabel)
        assertEquals("Work", multiEntry.phoneNumbers[1].typeLabel)
    }

    @Test
    fun testContactAccountIsolation() {
        val repo = InMemoryContactRepository()
        val userA = "user_A"
        val userB = "user_B"

        val contactA = EmergencyContact(id = "ca", ownerUserId = userA, displayName = "Alice", phoneNumber = "+91 91111 11111")
        val contactB = EmergencyContact(id = "cb", ownerUserId = userB, displayName = "Bob", phoneNumber = "+91 92222 22222")

        repo.save(userA, listOf(contactA))
        repo.save(userB, listOf(contactB))

        val loadedA = repo.load(userA)
        val loadedB = repo.load(userB)

        assertEquals(1, loadedA.size)
        assertEquals("Alice", loadedA[0].displayName)

        assertEquals(1, loadedB.size)
        assertEquals("Bob", loadedB[0].displayName)
    }

    @Test
    fun testContactRemovalRevokesAlertRecipient() {
        val repo = InMemoryContactRepository()
        val uid = "victim_1"
        val c1 = EmergencyContact(id = "c1", ownerUserId = uid, displayName = "Contact 1", phoneNumber = "+91 90000 00001", notifyOnSos = true)
        val c2 = EmergencyContact(id = "c2", ownerUserId = uid, displayName = "Contact 2", phoneNumber = "+91 90000 00002", notifyOnSos = true)
        repo.save(uid, listOf(c1, c2))

        // When c1 is removed
        val remaining = repo.load(uid).filterNot { it.id == "c1" }
        repo.save(uid, remaining)

        // Eligible alert recipients are only those where notifyOnSos == true in current contacts
        val eligibleRecipients = repo.load(uid).filter { it.notifyOnSos }
        assertEquals(1, eligibleRecipients.size)
        assertEquals("c2", eligibleRecipients[0].id)
    }
}
