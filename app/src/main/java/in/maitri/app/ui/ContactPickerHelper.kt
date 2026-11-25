package `in`.maitri.app.ui

import `in`.maitri.app.tr

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract

/**
 * Phone number item with its type label (Mobile, Home, Work, etc.).
 */
data class ContactPhoneEntry(
    val number: String,
    val typeLabel: String
)

/**
 * Resolved contact metadata retrieved from native Android Contacts Provider via scoped URI.
 */
data class PickedContactData(
    val name: String,
    val phoneNumbers: List<ContactPhoneEntry>
)

object ContactPickerHelper {

    /** Opens the system picker for one phone number; returns its data-row URI, readable through the picker's grant. */
    object PickPhoneNumber : androidx.activity.result.contract.ActivityResultContract<Unit?, Uri?>() {
        override fun createIntent(context: Context, input: Unit?): android.content.Intent =
            android.content.Intent(android.content.Intent.ACTION_PICK)
                .setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE)
        override fun parseResult(resultCode: Int, intent: android.content.Intent?): Uri? =
            intent?.data?.takeIf { resultCode == android.app.Activity.RESULT_OK }
    }

    /**
     * Resolves a scoped contact URI returned from the native contact or phone picker into
     * display name and list of available phone numbers without requiring broad READ_CONTACTS.
     *
     * Handles:
     * 1. CommonDataKinds.Phone URIs returned by ACTION_PICK on Phone.CONTENT_URI (e.g. content://.../data/123)
     * 2. Contacts URIs returned by ACTION_PICK on Contacts.CONTENT_URI (e.g. content://.../contacts/123)
     *    by safely querying the granted sub-directory (Uri.withAppendedPath(uri, "data/phones"))
     * 3. Contacts with single or multiple phone numbers
     * 4. Contacts without any phone numbers
     */
    fun resolvePickedContact(context: Context, uri: Uri): PickedContactData? {
        val contentResolver = context.contentResolver
        val phoneEntries = mutableListOf<ContactPhoneEntry>()
        var resolvedName: String? = null

        // --- STRATEGY 1: Scoped CommonDataKinds.Phone row query (Primary) ---
        // Used when picker was opened with ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        try {
            val phoneCursor: Cursor? = contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL
                ),
                null,
                null,
                null
            )

            phoneCursor?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val typeIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                    val labelIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)

                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        resolvedName = cursor.getString(nameIdx)?.trim()
                    }

                    if (numIdx >= 0 && !cursor.isNull(numIdx)) {
                        val number = cursor.getString(numIdx)?.trim().orEmpty()
                        val type = if (typeIdx >= 0 && !cursor.isNull(typeIdx)) cursor.getInt(typeIdx) else ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                        val label = if (labelIdx >= 0 && !cursor.isNull(labelIdx)) cursor.getString(labelIdx) else null
                        val typeLabel = formatPhoneType(type, label)

                        if (number.isNotBlank()) {
                            phoneEntries.add(ContactPhoneEntry(number, typeLabel))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore - uri might be a Contacts URI rather than a Phone data row URI
        }

        // If Strategy 1 succeeded and found a phone number, we have the user's selected phone number.
        if (phoneEntries.isNotEmpty()) {
            return PickedContactData(
                name = resolvedName?.ifBlank { tr("Contact") } ?: tr("Contact"),
                phoneNumbers = phoneEntries
            )
        }

        // --- STRATEGY 2: ContactsContract.Contacts URI resolution ---
        // Used if picker returned a general contact URI (e.g. content://.../contacts/123)
        // 2a. Query display name directly from the contact URI
        try {
            val contactCursor: Cursor? = contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.Contacts.DISPLAY_NAME,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
                ),
                null,
                null,
                null
            )

            contactCursor?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                    val primaryNameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)

                    resolvedName = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        cursor.getString(nameIdx)?.trim()
                    } else if (primaryNameIdx >= 0 && !cursor.isNull(primaryNameIdx)) {
                        cursor.getString(primaryNameIdx)?.trim()
                    } else {
                        null
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore
        }

        // 2b. Query child sub-path: <contact_uri>/data with MIMETYPE selection
        try {
            val dataUri = Uri.withAppendedPath(uri, ContactsContract.Contacts.Data.CONTENT_DIRECTORY)
            val dataCursor: Cursor? = contentResolver.query(
                dataUri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                ),
                "${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE),
                null
            )

            dataCursor?.use { cursor ->
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                val labelIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    if (resolvedName.isNullOrBlank() && nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        resolvedName = cursor.getString(nameIdx)?.trim()
                    }
                    if (numIdx >= 0 && !cursor.isNull(numIdx)) {
                        val number = cursor.getString(numIdx)?.trim().orEmpty()
                        val type = if (typeIdx >= 0 && !cursor.isNull(typeIdx)) cursor.getInt(typeIdx) else ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                        val label = if (labelIdx >= 0 && !cursor.isNull(labelIdx)) cursor.getString(labelIdx) else null
                        val typeLabel = formatPhoneType(type, label)

                        if (number.isNotBlank() && phoneEntries.none { it.number == number }) {
                            phoneEntries.add(ContactPhoneEntry(number, typeLabel))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore
        }

        // If neither name nor phone could be read, the URI was inaccessible or completely invalid
        if (resolvedName == null && phoneEntries.isEmpty()) {
            return null
        }

        return PickedContactData(
            name = resolvedName?.ifBlank { tr("Contact") } ?: tr("Contact"),
            phoneNumbers = phoneEntries
        )
    }

    private fun formatPhoneType(type: Int, customLabel: String?): String {
        return when (type) {
            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> tr("Mobile")
            ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> tr("Home")
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> tr("Work")
            ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> tr("Main")
            ContactsContract.CommonDataKinds.Phone.TYPE_OTHER -> tr("Other")
            ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM -> customLabel?.ifBlank { tr("Custom") } ?: tr("Custom")
            else -> tr("Phone")
        }
    }
}
