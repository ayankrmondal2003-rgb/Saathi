package `in`.maitri.app.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

interface ContactRepository {
    fun load(uid: String): List<EmergencyContact>
    fun save(uid: String, contacts: List<EmergencyContact>)
}

class LocalContactRepository(private val store: SharedPreferences) : ContactRepository {
    override fun load(uid: String): List<EmergencyContact> {
        val raw = store.getString(uid, "[]") ?: "[]"
        val items = JSONArray(raw)
        val list = mutableListOf<EmergencyContact>()
        for (i in 0 until items.length()) {
            val row = items.getJSONObject(i)
            val id = row.getString("id")
            val displayName = row.optString("displayName", row.optString("name", ""))
            val phoneNumber = row.optString("phoneNumber", row.optString("phone", ""))
            val normalized = row.optString("normalizedPhoneNumber", EmergencyContact.normalizePhone(phoneNumber))
            val label = row.optString("label", "Emergency Contact")
            val isPrimary = row.optBoolean("isPrimary", i == 0)
            val isVerified = row.optBoolean("isVerified", false)
            val source = row.optString("source", EmergencyContact.SOURCE_MANUAL)
            val notifyOnSos = row.optBoolean("notifyOnSos", true)
            val includeLocationLink = row.optBoolean("includeLocationLink", true)
            val notifyOnClosure = row.optBoolean("notifyOnClosure", true)
            val allowLiveTracking = row.optBoolean("allowLiveTracking", true)
            val preferredChannel = row.optString("preferredChannel", EmergencyContact.CHANNEL_SMS)
            val createdAt = row.optLong("createdAt", System.currentTimeMillis())
            val updatedAt = row.optLong("updatedAt", System.currentTimeMillis())

            list.add(
                EmergencyContact(
                    id = id,
                    ownerUserId = uid,
                    displayName = displayName,
                    phoneNumber = phoneNumber,
                    normalizedPhoneNumber = normalized,
                    label = label,
                    isPrimary = isPrimary,
                    isVerified = isVerified,
                    source = source,
                    notifyOnSos = notifyOnSos,
                    includeLocationLink = includeLocationLink,
                    notifyOnClosure = notifyOnClosure,
                    allowLiveTracking = allowLiveTracking,
                    preferredChannel = preferredChannel,
                    createdAt = createdAt,
                    updatedAt = updatedAt
                )
            )
        }
        // Ensure exactly one primary contact if list is non-empty
        return if (list.isNotEmpty() && list.none { it.isPrimary }) {
            list.mapIndexed { index, contact ->
                if (index == 0) contact.copy(isPrimary = true) else contact
            }
        } else {
            list
        }
    }

    override fun save(uid: String, contacts: List<EmergencyContact>) {
        val rows = JSONArray()
        contacts.forEach { contact ->
            rows.put(
                JSONObject().apply {
                    put("id", contact.id)
                    put("ownerUserId", uid)
                    put("displayName", contact.displayName)
                    put("phoneNumber", contact.phoneNumber)
                    put("normalizedPhoneNumber", contact.normalizedPhoneNumber)
                    put("label", contact.label)
                    put("isPrimary", contact.isPrimary)
                    put("isVerified", contact.isVerified)
                    put("source", contact.source)
                    put("notifyOnSos", contact.notifyOnSos)
                    put("includeLocationLink", contact.includeLocationLink)
                    put("notifyOnClosure", contact.notifyOnClosure)
                    put("allowLiveTracking", contact.allowLiveTracking)
                    put("preferredChannel", contact.preferredChannel)
                    put("createdAt", contact.createdAt)
                    put("updatedAt", contact.updatedAt)
                    // Legacy keys for backward compatibility
                    put("name", contact.displayName)
                    put("phone", contact.phoneNumber)
                }
            )
        }
        store.edit().putString(uid, rows.toString()).apply()
    }
}
