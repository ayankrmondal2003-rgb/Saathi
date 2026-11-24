package `in`.maitri.app.data

data class UserProfile(val uid: String, val name: String, val email: String, val phone: String?,
                       val createdAt: Long?, val responder: Responder) {
    val complete get() = name.isNotBlank() && email.isNotBlank()
}
data class EmergencyContact(
    val id: String,
    val ownerUserId: String = "",
    val displayName: String,
    val phoneNumber: String,
    val normalizedPhoneNumber: String = normalizePhone(phoneNumber),
    val label: String = "Emergency Contact",
    val isPrimary: Boolean = false,
    val isVerified: Boolean = false,
    val source: String = SOURCE_MANUAL,
    val notifyOnSos: Boolean = true,
    val includeLocationLink: Boolean = true,
    val notifyOnClosure: Boolean = true,
    val allowLiveTracking: Boolean = true,
    val preferredChannel: String = CHANNEL_SMS,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    // Backward compatibility aliases
    val name: String get() = displayName
    val phone: String get() = phoneNumber

    // Secondary constructor for backward compatibility
    constructor(id: String, name: String, phone: String) : this(
        id = id,
        ownerUserId = "",
        displayName = name,
        phoneNumber = phone,
        normalizedPhoneNumber = normalizePhone(phone)
    )

    companion object {
        const val SOURCE_MANUAL = "MANUAL"
        const val SOURCE_PHONE_CONTACTS = "PHONE_CONTACTS"
        const val CHANNEL_SMS = "SMS"
        const val CHANNEL_APP = "APP"

        fun normalizePhone(raw: String): String {
            val trimmed = raw.trim()
            val hasPlus = trimmed.startsWith("+")
            val digits = trimmed.filter { it.isDigit() }
            return if (hasPlus) "+$digits" else digits
        }

        fun maskPhone(phone: String): String {
            val norm = normalizePhone(phone)
            return if (norm.length <= 4) "****" else "${norm.take(3)}*****${norm.takeLast(4)}"
        }

        fun validateName(name: String): String? {
            return if (name.isBlank()) "Contact name cannot be blank." else null
        }

        fun validatePhone(phone: String): String? {
            val trimmed = phone.trim()
            if (trimmed.isBlank()) return "Phone number cannot be blank."
            if (!trimmed.matches(Regex("[+0-9() .-]+"))) {
                return "Phone number contains invalid characters."
            }
            val digitCount = trimmed.count { it.isDigit() }
            if (digitCount !in 7..15) {
                return "Phone number must contain between 7 and 15 digits."
            }
            return null
        }
    }
}

enum class ContactDeliveryStatus(val label: String) {
    NOT_REQUESTED("Not Requested"),
    QUEUED("Queued"),
    SENDING("Sending"),
    PROVIDER_ACCEPTED("Provider Accepted"),
    DELIVERED_WHEN_CONFIRMED("Delivered"),
    FAILED("Failed"),
    UNKNOWN("Unknown")
}

data class ContactAlertStatus(
    val contactId: String,
    val displayName: String,
    val maskedPhone: String,
    val isPrimary: Boolean,
    val deliveryStatus: ContactDeliveryStatus,
    val messagePreview: String,
    val locationDisclosed: Boolean,
    val trackingGranted: Boolean,
    val updatedAt: Long = System.currentTimeMillis()
)

enum class IncidentStatus { ACTIVE, ENDED }
data class SosIncident(val started: Long, val status: IncidentStatus = IncidentStatus.ACTIVE)
enum class Response { PENDING, ACCEPTED, DECLINED }
data class Responder(val enabled: Boolean = false, val available: Boolean = false, val response: Response = Response.PENDING)
data class DemoState(
    val user: UserProfile? = null,
    val contacts: List<EmergencyContact> = emptyList(),
    val incident: SosIncident? = null,
    val responder: Responder = Responder(),
    /** Result of the last circle sync with the server, shown once on screen 05. */
    val contactsMessage: String? = null
)
