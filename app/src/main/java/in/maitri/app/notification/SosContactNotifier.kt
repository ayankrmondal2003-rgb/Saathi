package `in`.maitri.app.notification

import android.content.Intent
import android.net.Uri
import `in`.maitri.app.data.ContactAlertStatus
import `in`.maitri.app.data.ContactDeliveryStatus
import `in`.maitri.app.data.EmergencyContact
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Delivery status for emergency contact notifications during an active SOS.
 */
enum class ContactNotificationStatus {
    NOT_IMPLEMENTED, // Safety isolation maintained: carrier SMS gateway not hooked in this phase
    SENT,
    FAILED
}

/**
 * Result returned when attempting to alert emergency contacts.
 */
data class ContactNotificationResult(
    val status: ContactNotificationStatus,
    val contactCount: Int,
    val primaryContactName: String?,
    val message: String
)

/**
 * Clean application interface for dispatching emergency alert notifications
 * to the user's verified personal emergency contacts when an SOS incident begins.
 */
interface SosContactNotifier {
    suspend fun notifyEmergencyContacts(
        incidentId: String,
        contacts: List<EmergencyContact>,
        latitude: Double?,
        longitude: Double?
    ): ContactNotificationResult
}

/**
 * Helper object providing explicit intent creators for native dialer and SMS fallback,
 * message formatting, and safe alert status dispatching without requesting broad permissions.
 */
object SosContactNotifierHelper {

    /**
     * Formats standardized emergency message adhering to privacy and safety regulations.
     */
    fun formatEmergencyMessage(
        contactName: String,
        latitude: Double?,
        longitude: Double?,
        recordedAtMillis: Long? = null,
        includeLocation: Boolean = true
    ): String {
        return if (includeLocation && latitude != null && longitude != null) {
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val ts = if (recordedAtMillis != null) df.format(Date(recordedAtMillis)) else "recent"
            val link = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", latitude, longitude)
            "Maitri SOS: I may need urgent help. My last available location is: $link. Location recorded at: $ts. If you believe I am in immediate danger, contact emergency services (112)."
        } else {
            "Maitri SOS: I may need urgent help. Location is currently unavailable. If you believe I am in immediate danger, contact emergency services (112)."
        }
    }

    /**
     * Builds explicit native SMS composer intent (ACTION_SENDTO with smsto: URI).
     * Requires ZERO runtime permissions (SEND_SMS is NOT requested).
     * Requires explicit user action to review and tap send in their chosen SMS application.
     */
    fun createSmsComposerIntent(phoneNumber: String, message: String): Intent {
        val normalized = EmergencyContact.normalizePhone(phoneNumber)
        return Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$normalized")
            putExtra("sms_body", message)
            putExtra(Intent.EXTRA_TEXT, message)
        }
    }

    /**
     * Builds explicit native phone dialer intent (ACTION_DIAL with tel: URI).
     * Requires ZERO runtime permissions (CALL_PHONE is NOT requested).
     * Opens native dialer prefilled with 112 for explicit user review and tap.
     */
    fun createEmergencyDialerIntent(emergencyNumber: String = "112"): Intent {
        return Intent(Intent.ACTION_DIAL, Uri.parse("tel:$emergencyNumber"))
    }

    /**
     * Builds realistic delivery status entries for active trusted contacts.
     */
    fun buildContactAlertStatuses(
        contacts: List<EmergencyContact>,
        latitude: Double?,
        longitude: Double?,
        recordedAtMillis: Long? = null,
        isSimulated: Boolean = true
    ): List<ContactAlertStatus> {
        val now = System.currentTimeMillis()
        return contacts.map { contact ->
            val message = formatEmergencyMessage(
                contactName = contact.displayName,
                latitude = latitude,
                longitude = longitude,
                recordedAtMillis = recordedAtMillis,
                includeLocation = contact.includeLocationLink
            )
            val status = if (contact.notifyOnSos) {
                if (isSimulated) ContactDeliveryStatus.PROVIDER_ACCEPTED else ContactDeliveryStatus.QUEUED
            } else {
                ContactDeliveryStatus.NOT_REQUESTED
            }

            ContactAlertStatus(
                contactId = contact.id,
                displayName = contact.displayName,
                maskedPhone = EmergencyContact.maskPhone(contact.phoneNumber),
                isPrimary = contact.isPrimary,
                deliveryStatus = status,
                messagePreview = message,
                locationDisclosed = contact.includeLocationLink && latitude != null && longitude != null,
                trackingGranted = contact.allowLiveTracking,
                updatedAt = now
            )
        }
    }
}

/**
 * Safe mock implementation preserving safety isolation and preventing real SMS broadcast
 * during automated testing and hackathon demonstrations.
 */
class MockSosContactNotifier : SosContactNotifier {
    override suspend fun notifyEmergencyContacts(
        incidentId: String,
        contacts: List<EmergencyContact>,
        latitude: Double?,
        longitude: Double?
    ): ContactNotificationResult {
        val primary = contacts.find { it.isPrimary }
        return ContactNotificationResult(
            status = ContactNotificationStatus.NOT_IMPLEMENTED,
            contactCount = contacts.size,
            primaryContactName = primary?.displayName,
            message = "Emergency contact alerting interface prepared. Direct SMS delivery is NOT IMPLEMENTED in Phase 12 (simulated/safety isolation maintained)."
        )
    }
}
