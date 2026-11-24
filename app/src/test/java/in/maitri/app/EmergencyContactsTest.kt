package `in`.maitri.app

import `in`.maitri.app.data.ContactRepository
import `in`.maitri.app.data.EmergencyContact
import `in`.maitri.app.notification.ContactNotificationStatus
import `in`.maitri.app.notification.MockSosContactNotifier
import `in`.maitri.app.ui.ContactPhoneEntry
import `in`.maitri.app.ui.PickedContactData
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class EmergencyContactsTest {

    // In-memory fake repository for account-isolated persistence tests
    class FakeContactRepository : ContactRepository {
        private val storage = mutableMapOf<String, List<EmergencyContact>>()

        override fun load(uid: String): List<EmergencyContact> {
            val list = storage[uid] ?: emptyList()
            return if (list.isNotEmpty() && list.none { it.isPrimary }) {
                list.mapIndexed { index, contact ->
                    if (index == 0) contact.copy(isPrimary = true) else contact
                }
            } else {
                list
            }
        }

        override fun save(uid: String, contacts: List<EmergencyContact>) {
            storage[uid] = contacts
        }
    }

    @Test
    fun testNormalizePhone() {
        assertEquals("+919876543210", EmergencyContact.normalizePhone("+91 98765-43210"))
        assertEquals("+15551234567", EmergencyContact.normalizePhone("+1 (555) 123-4567"))
        assertEquals("9876543210", EmergencyContact.normalizePhone("98765 43210"))
        assertEquals("01123456789", EmergencyContact.normalizePhone("011-2345-6789"))
    }

    @Test
    fun testManualValidation() {
        // Name validation
        assertNotNull(EmergencyContact.validateName(""))
        assertNotNull(EmergencyContact.validateName("   "))
        assertNull(EmergencyContact.validateName("Priya Sharma"))

        // Phone validation
        assertNotNull(EmergencyContact.validatePhone(""))
        assertNotNull(EmergencyContact.validatePhone("abc123456"))
        assertNotNull(EmergencyContact.validatePhone("12345")) // Too short (< 7 digits)
        assertNotNull(EmergencyContact.validatePhone("1234567890123456")) // Too long (> 15 digits)

        // Valid formats
        assertNull(EmergencyContact.validatePhone("+91 98765 43210"))
        assertNull(EmergencyContact.validatePhone("9876543210"))
        assertNull(EmergencyContact.validatePhone("+1 (555) 000-1234"))
    }

    @Test
    fun testPrimaryAssignmentAndDemotion() {
        val repo = FakeContactRepository()
        val uid = "user_alpha"

        // First contact should automatically be primary
        val c1 = EmergencyContact(
            id = "c1",
            ownerUserId = uid,
            displayName = "Mother",
            phoneNumber = "+91 98765 00001",
            isPrimary = true,
            label = "Parent"
        )
        repo.save(uid, listOf(c1))

        var loaded = repo.load(uid)
        assertEquals(1, loaded.size)
        assertTrue(loaded.first().isPrimary)

        // Adding second contact marked as primary should demote first
        val c2 = EmergencyContact(
            id = "c2",
            ownerUserId = uid,
            displayName = "Sister",
            phoneNumber = "+91 98765 00002",
            isPrimary = true,
            label = "Sibling"
        )
        val updated = listOf(c1.copy(isPrimary = false), c2)
        repo.save(uid, updated)

        loaded = repo.load(uid)
        assertEquals(2, loaded.size)
        assertFalse(loaded.first { it.id == "c1" }.isPrimary)
        assertTrue(loaded.first { it.id == "c2" }.isPrimary)

        // Verify exactly one primary exists
        assertEquals(1, loaded.count { it.isPrimary })
    }

    @Test
    fun testPrimaryDeletionReassignment() {
        val repo = FakeContactRepository()
        val uid = "user_beta"

        val c1 = EmergencyContact(id = "c1", ownerUserId = uid, displayName = "Contact 1", phoneNumber = "+91 90000 00001", isPrimary = true)
        val c2 = EmergencyContact(id = "c2", ownerUserId = uid, displayName = "Contact 2", phoneNumber = "+91 90000 00002", isPrimary = false)
        val c3 = EmergencyContact(id = "c3", ownerUserId = uid, displayName = "Contact 3", phoneNumber = "+91 90000 00003", isPrimary = false)

        repo.save(uid, listOf(c1, c2, c3))

        // Remove primary contact c1
        val remaining = listOf(c2, c3)
        repo.save(uid, remaining)

        val loaded = repo.load(uid)
        assertEquals(2, loaded.size)
        // c2 should be promoted to primary automatically
        assertTrue(loaded.first { it.id == "c2" }.isPrimary)
        assertFalse(loaded.first { it.id == "c3" }.isPrimary)
        assertEquals(1, loaded.count { it.isPrimary })
    }

    @Test
    fun testAccountSpecificIsolation() {
        val repo = FakeContactRepository()
        val user1 = "uid_alice"
        val user2 = "uid_bob"

        val cAlice = EmergencyContact(id = "ca1", ownerUserId = user1, displayName = "Alice Doctor", phoneNumber = "+91 91111 11111", isPrimary = true)
        val cBob = EmergencyContact(id = "cb1", ownerUserId = user2, displayName = "Bob Lawyer", phoneNumber = "+91 92222 22222", isPrimary = true)

        repo.save(user1, listOf(cAlice))
        repo.save(user2, listOf(cBob))

        // Alice cannot see Bob's contacts
        val aliceContacts = repo.load(user1)
        assertEquals(1, aliceContacts.size)
        assertEquals("Alice Doctor", aliceContacts.first().displayName)

        // Bob cannot see Alice's contacts
        val bobContacts = repo.load(user2)
        assertEquals(1, bobContacts.size)
        assertEquals("Bob Lawyer", bobContacts.first().displayName)
    }

    @Test
    fun testMultiplePhoneNumbersHandling() {
        val contactData = PickedContactData(
            name = "Rohan Mehta",
            phoneNumbers = listOf(
                ContactPhoneEntry("+91 98111 22233", "Mobile"),
                ContactPhoneEntry("+91 11 2345 6789", "Home"),
                ContactPhoneEntry("+91 98222 33344", "Work")
            )
        )

        assertEquals("Rohan Mehta", contactData.name)
        assertEquals(3, contactData.phoneNumbers.size)
        assertEquals("Mobile", contactData.phoneNumbers[0].typeLabel)
        assertEquals("Home", contactData.phoneNumbers[1].typeLabel)
        assertEquals("Work", contactData.phoneNumbers[2].typeLabel)
    }

    @Test
    fun testSosContactNotifierSafetyIsolation() = runTest {
        val notifier = MockSosContactNotifier()
        val contacts = listOf(
            EmergencyContact("c1", "Priya Mom", "+91 98765 43210").copy(isPrimary = true),
            EmergencyContact("c2", "Priya Dad", "+91 98765 43211")
        )

        val result = notifier.notifyEmergencyContacts(
            incidentId = "inc-test-123",
            contacts = contacts,
            latitude = 28.6139,
            longitude = 77.2090
        )

        assertEquals(ContactNotificationStatus.NOT_IMPLEMENTED, result.status)
        assertEquals(2, result.contactCount)
        assertEquals("Priya Mom", result.primaryContactName)
        assertTrue(result.message.contains("NOT IMPLEMENTED"))
    }

    @Test
    fun testEmergencyMessageFormattingWithLocation() {
        val msg = `in`.maitri.app.notification.SosContactNotifierHelper.formatEmergencyMessage(
            contactName = "Mom",
            latitude = 28.613933,
            longitude = 77.209022,
            recordedAtMillis = 1700000000000L,
            includeLocation = true
        )
        assertTrue(msg.contains("Maitri SOS: I may need urgent help."))
        assertTrue(msg.contains("https://maps.google.com/?q=28.613933,77.209022"))
        assertTrue(msg.contains("Location recorded at:"))
        assertTrue(msg.contains("112"))
    }

    @Test
    fun testEmergencyMessageFormattingWithoutLocation() {
        val msgNullLoc = `in`.maitri.app.notification.SosContactNotifierHelper.formatEmergencyMessage(
            contactName = "Mom",
            latitude = null,
            longitude = null,
            includeLocation = true
        )
        assertEquals(
            "Maitri SOS: I may need urgent help. Location is currently unavailable. If you believe I am in immediate danger, contact emergency services (112).",
            msgNullLoc
        )

        val msgOptOut = `in`.maitri.app.notification.SosContactNotifierHelper.formatEmergencyMessage(
            contactName = "Mom",
            latitude = 28.6139,
            longitude = 77.2090,
            includeLocation = false
        )
        assertEquals(
            "Maitri SOS: I may need urgent help. Location is currently unavailable. If you believe I am in immediate danger, contact emergency services (112).",
            msgOptOut
        )
    }

    @Test
    fun testPhoneMasking() {
        assertEquals("+91*****3210", EmergencyContact.maskPhone("+91 98765 43210"))
        assertEquals("+15*****3456", EmergencyContact.maskPhone("+1 (555) 012-3456"))
        assertEquals("****", EmergencyContact.maskPhone("123"))
    }

    @Test
    fun testContactNotificationPreferencesDefaultValues() {
        val contact = EmergencyContact(
            id = "test-1",
            displayName = "Ananya",
            phoneNumber = "+91 98765 11111"
        )
        assertTrue(contact.notifyOnSos)
        assertTrue(contact.includeLocationLink)
        assertTrue(contact.notifyOnClosure)
        assertTrue(contact.allowLiveTracking)
        assertEquals(EmergencyContact.CHANNEL_SMS, contact.preferredChannel)
    }

    @Test
    fun testBuildContactAlertStatuses() {
        val c1 = EmergencyContact("c1", "Friend 1", "+91 98765 00001").copy(
            isPrimary = true,
            notifyOnSos = true,
            includeLocationLink = true,
            allowLiveTracking = true
        )
        val c2 = EmergencyContact("c2", "Friend 2", "+91 98765 00002").copy(
            isPrimary = false,
            notifyOnSos = false,
            includeLocationLink = false,
            allowLiveTracking = false
        )

        val statuses = `in`.maitri.app.notification.SosContactNotifierHelper.buildContactAlertStatuses(
            contacts = listOf(c1, c2),
            latitude = 28.6139,
            longitude = 77.2090,
            isSimulated = true
        )

        assertEquals(2, statuses.size)
        val s1 = statuses.find { it.contactId == "c1" }!!
        val s2 = statuses.find { it.contactId == "c2" }!!

        assertEquals(`in`.maitri.app.data.ContactDeliveryStatus.PROVIDER_ACCEPTED, s1.deliveryStatus)
        assertTrue(s1.locationDisclosed)
        assertTrue(s1.trackingGranted)
        assertTrue(s1.isPrimary)

        assertEquals(`in`.maitri.app.data.ContactDeliveryStatus.NOT_REQUESTED, s2.deliveryStatus)
        assertFalse(s2.locationDisclosed)
        assertFalse(s2.trackingGranted)
        assertFalse(s2.isPrimary)
    }

    @Test
    fun testSmsComposerAndEmergencyDialerIntents() {
        val smsIntent = `in`.maitri.app.notification.SosContactNotifierHelper.createSmsComposerIntent(
            phoneNumber = "+91 98765 43210",
            message = "Maitri SOS test message"
        )
        assertNotNull(smsIntent)

        val dialIntent = `in`.maitri.app.notification.SosContactNotifierHelper.createEmergencyDialerIntent("112")
        assertNotNull(dialIntent)
    }

    @Test
    fun testPickedContactDataSingleNumberFlow() {
        val pickedData = PickedContactData(
            name = "Rohan Kumar",
            phoneNumbers = listOf(ContactPhoneEntry("+919876543210", "Mobile"))
        )
        assertEquals("Rohan Kumar", pickedData.name)
        assertEquals(1, pickedData.phoneNumbers.size)
        assertEquals("+919876543210", pickedData.phoneNumbers.first().number)
        assertEquals("Mobile", pickedData.phoneNumbers.first().typeLabel)
    }

    @Test
    fun testPickedContactDataMultipleNumbersFlow() {
        val pickedData = PickedContactData(
            name = "Dr. Anita Desai",
            phoneNumbers = listOf(
                ContactPhoneEntry("+919876500001", "Mobile"),
                ContactPhoneEntry("+919876500002", "Work")
            )
        )
        assertEquals(2, pickedData.phoneNumbers.size)
        assertEquals("Mobile", pickedData.phoneNumbers[0].typeLabel)
        assertEquals("Work", pickedData.phoneNumbers[1].typeLabel)
    }

    @Test
    fun testPickedContactDataEmptyNumbersFlow() {
        val pickedData = PickedContactData(
            name = "Contact Without Phone",
            phoneNumbers = emptyList()
        )
        assertTrue(pickedData.phoneNumbers.isEmpty())
    }
}

