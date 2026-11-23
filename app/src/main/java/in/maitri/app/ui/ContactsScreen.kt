package `in`.maitri.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.maitri.app.data.*
import `in`.maitri.app.state.SafetyViewModel
import `in`.maitri.app.ui.theme.*

private val STANDARD_RELATIONSHIP_LABELS = listOf(
    "Parent", "Spouse", "Family", "Sibling", "Friend", "Neighbor", "Colleague", "Other"
)

@Composable
fun Contacts(items: List<EmergencyContact>, vm: SafetyViewModel) {
    val colors = MaterialTheme.maitriColors
    val context = LocalContext.current

    // State for manual add / edit
    var editing by rememberSaveable { mutableStateOf(false) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var editName by rememberSaveable { mutableStateOf("") }
    var editPhone by rememberSaveable { mutableStateOf("") }
    var editLabel by rememberSaveable { mutableStateOf("Family") }
    var editIsPrimary by rememberSaveable { mutableStateOf(false) }
    var editNotifyOnSos by rememberSaveable { mutableStateOf(true) }
    var editIncludeLocationLink by rememberSaveable { mutableStateOf(true) }
    var editNotifyOnClosure by rememberSaveable { mutableStateOf(true) }
    var editAllowLiveTracking by rememberSaveable { mutableStateOf(true) }
    var editPreferredChannel by rememberSaveable { mutableStateOf(EmergencyContact.CHANNEL_SMS) }
    var editError by rememberSaveable { mutableStateOf<String?>(null) }

    // State for contact removal
    var removingId by rememberSaveable { mutableStateOf<String?>(null) }

    // State for phone contact picking flow
    var multiPhoneContact by remember { mutableStateOf<PickedContactData?>(null) }
    var isConfirmingPickedContact by rememberSaveable { mutableStateOf(false) }
    var confirmingName by rememberSaveable { mutableStateOf("") }
    var confirmingPhone by rememberSaveable { mutableStateOf("") }
    var confirmingLabel by rememberSaveable { mutableStateOf("Family") }
    var confirmingIsPrimary by rememberSaveable { mutableStateOf(false) }
    var confirmingNotifyOnSos by rememberSaveable { mutableStateOf(true) }
    var confirmingIncludeLocationLink by rememberSaveable { mutableStateOf(true) }
    var confirmingNotifyOnClosure by rememberSaveable { mutableStateOf(true) }
    var confirmingAllowLiveTracking by rememberSaveable { mutableStateOf(true) }
    var confirmingPreferredChannel by rememberSaveable { mutableStateOf(EmergencyContact.CHANNEL_SMS) }
    var confirmingError by rememberSaveable { mutableStateOf<String?>(null) }

    // State for simulated alert testing
    var simulatedAlertContact by remember { mutableStateOf<EmergencyContact?>(null) }

    // General user error / info dialog
    var alertMessage by rememberSaveable { mutableStateOf<String?>(null) }

    // Native Contact Picker Activity Result Launcher
    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                val contactData = ContactPickerHelper.resolvePickedContact(context, uri)
                if (contactData == null) {
                    alertMessage = "Unable to read contact details from the phone. Please check permissions or enter contact manually."
                } else if (contactData.phoneNumbers.isEmpty()) {
                    val namePart = if (contactData.name.isNotBlank() && contactData.name != "Contact") " for \"${contactData.name}\"" else ""
                    alertMessage = "No phone numbers found$namePart. Please select a contact with a saved phone number or add manually."
                } else if (contactData.phoneNumbers.size == 1) {
                    // Exactly one number: direct to confirmation dialog
                    confirmingName = contactData.name
                    confirmingPhone = contactData.phoneNumbers.first().number
                    confirmingLabel = contactData.phoneNumbers.first().typeLabel.ifBlank { "Family" }
                    confirmingIsPrimary = items.isEmpty()
                    confirmingNotifyOnSos = true
                    confirmingIncludeLocationLink = true
                    confirmingNotifyOnClosure = true
                    confirmingAllowLiveTracking = true
                    confirmingPreferredChannel = EmergencyContact.CHANNEL_SMS
                    confirmingError = null
                    isConfirmingPickedContact = true
                } else {
                    // Multiple numbers: ask user which one to use
                    multiPhoneContact = contactData
                }
            } else {
                alertMessage = "Unable to read contact details from the phone."
            }
        }
    }

    fun launchPhoneContactPicker() {
        // Targets Phone.CONTENT_URI directly for scoped phone-number row selection
        val phonePickerIntent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        try {
            contactPickerLauncher.launch(phonePickerIntent)
        } catch (e: Exception) {
            // Fallback for custom ROMs: general contact picker
            try {
                contactPickerLauncher.launch(Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI))
            } catch (e2: Exception) {
                alertMessage = "Could not open contacts picker: ${e2.message}"
            }
        }
    }

    fun openManualEditor(item: EmergencyContact?) {
        editId = item?.id
        editName = item?.displayName.orEmpty()
        editPhone = item?.phoneNumber.orEmpty()
        editLabel = item?.label ?: "Family"
        editIsPrimary = item?.isPrimary ?: items.isEmpty()
        editNotifyOnSos = item?.notifyOnSos ?: true
        editIncludeLocationLink = item?.includeLocationLink ?: true
        editNotifyOnClosure = item?.notifyOnClosure ?: true
        editAllowLiveTracking = item?.allowLiveTracking ?: true
        editPreferredChannel = item?.preferredChannel ?: EmergencyContact.CHANNEL_SMS
        editError = null
        editing = true
    }

    val primaryContact = items.find { it.isPrimary }
    val secondaryContacts = items.filter { !it.isPrimary }

    Page(
        title = "Emergency Contacts",
        subtitle = "Trusted Circle · SOS Alert Recipients"
    ) {
        // Informational Privacy & Architecture Card
        MaitriCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.info.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContactPhone,
                        contentDescription = null,
                        tint = colors.info,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Your Trusted Network",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                        StatusBadge(
                            text = "${items.size} SAVED",
                            color = if (items.isNotEmpty()) colors.success else colors.textTertiary
                        )
                    }
                    Text(
                        text = "Contacts stay securely on this device. When an SOS is triggered, your primary and trusted circle are prioritized for instant rescue alerting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }
        }

        // Action Buttons: Add from Phone Contacts & Add Manually (Responsive & Single-line guaranteed)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isNarrowOrLargeFont = maxWidth < 330.dp || LocalDensity.current.fontScale > 1.15f
            if (isNarrowOrLargeFont) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { launchPhoneContactPicker() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.info,
                            contentColor = Color.White
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Contacts,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "From Phone",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { openManualEditor(null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, colors.cardBorder),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = colors.cardBackground,
                            contentColor = colors.textPrimary
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PersonAdd,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = colors.textPrimary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Add Manually",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { launchPhoneContactPicker() },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.info,
                            contentColor = Color.White
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Contacts,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "From Phone",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { openManualEditor(null) },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, colors.cardBorder),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = colors.cardBackground,
                            contentColor = colors.textPrimary
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PersonAdd,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = colors.textPrimary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Add Manually",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Empty State or Contacts List
        if (items.isEmpty()) {
            MaitriCard(modifier = Modifier.padding(vertical = 12.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(colors.cardBackgroundSecondary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GroupAdd,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    Text(
                        text = "No emergency contacts added yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Choose close family or friends from your phone contacts or add them manually. They will receive your location during emergencies.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }
        } else {
            // 1. Primary Contact Featured Card
            if (primaryContact != null) {
                Text(
                    text = "PRIMARY EMERGENCY CONTACT",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.emergency,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )

                key(primaryContact.id) {
                    ContactCard(
                        contact = primaryContact,
                        colors = colors,
                        isPrimary = true,
                        onCall = {
                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${primaryContact.phoneNumber}"))
                            context.startActivity(intent)
                        },
                        onTestAlert = { simulatedAlertContact = primaryContact },
                        onEdit = { openManualEditor(primaryContact) },
                        onRemove = { removingId = primaryContact.id },
                        onMakePrimary = null
                    )
                }
            }

            // 2. Secondary Contacts List
            if (secondaryContacts.isNotEmpty()) {
                Text(
                    text = "ADDITIONAL TRUSTED CONTACTS (${secondaryContacts.size})",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )

                secondaryContacts.forEach { contact ->
                    key(contact.id) {
                        ContactCard(
                            contact = contact,
                            colors = colors,
                            isPrimary = false,
                            onCall = {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${contact.phoneNumber}"))
                                context.startActivity(intent)
                            },
                            onTestAlert = { simulatedAlertContact = contact },
                            onEdit = { openManualEditor(contact) },
                            onRemove = { removingId = contact.id },
                            onMakePrimary = { vm.setPrimary(contact.id) }
                        )
                    }
                }
            }
        }
    }

    // Dialog 1: Picked Contact Multiple Phone Numbers Chooser
    multiPhoneContact?.let { contact ->
        AlertDialog(
            onDismissRequest = { multiPhoneContact = null },
            icon = {
                Icon(Icons.Rounded.PhoneIphone, contentDescription = null, tint = colors.info, modifier = Modifier.size(32.dp))
            },
            title = {
                Text("Select Phone Number", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "\"${contact.name}\" has multiple saved numbers. Select which one should be used for SOS notifications:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                    contact.phoneNumbers.forEach { entry ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    confirmingName = contact.name
                                    confirmingPhone = entry.number
                                    confirmingLabel = entry.typeLabel.ifBlank { "Family" }
                                    confirmingIsPrimary = items.isEmpty()
                                    confirmingError = null
                                    multiPhoneContact = null
                                    isConfirmingPickedContact = true
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = colors.cardBackgroundSecondary,
                            border = BorderStroke(1.dp, colors.cardBorder)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(entry.number, fontWeight = FontWeight.Bold, color = colors.textPrimary)
                                    Text(entry.typeLabel, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                                }
                                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = colors.info)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { multiPhoneContact = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog 2: Contact Confirmation Screen (After picking from phone)
    if (isConfirmingPickedContact) {
        AlertDialog(
            onDismissRequest = { isConfirmingPickedContact = false },
            title = {
                Text("Confirm Emergency Contact", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Review contact details before saving. This contact will be prioritized for rescue alerts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )

                    OutlinedTextField(
                        value = confirmingName,
                        onValueChange = { confirmingName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Contact Name") },
                        leadingIcon = {
                            Icon(Icons.Rounded.Person, contentDescription = null, tint = colors.textSecondary)
                        },
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = confirmingPhone,
                        onValueChange = { confirmingPhone = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Phone Number") },
                        leadingIcon = {
                            Icon(Icons.Rounded.Phone, contentDescription = null, tint = colors.textSecondary)
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Phone),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true
                    )

                    Text(
                        text = "Relationship / Label",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )

                    RelationshipLabelRow(
                        selected = confirmingLabel,
                        onSelect = { confirmingLabel = it }
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Primary Contact", fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                            Text("First recipient prioritized in SOS", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        }
                        Switch(
                            checked = confirmingIsPrimary,
                            onCheckedChange = { confirmingIsPrimary = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = colors.success)
                        )
                    }

                    HorizontalDivider(color = colors.cardBorder)

                    Text(
                        text = "Emergency Notification Preferences",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Alert on SOS Trigger", fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                            Text("Automatically dispatch urgent notification", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        }
                        Switch(
                            checked = confirmingNotifyOnSos,
                            onCheckedChange = { confirmingNotifyOnSos = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = colors.emergency)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Include Live Location Link", fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                            Text("Attach coordinates link in alert text", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        }
                        Switch(
                            checked = confirmingIncludeLocationLink,
                            onCheckedChange = { confirmingIncludeLocationLink = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = colors.info)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Allow Live Incident Tracking", fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                            Text("Grant temporary live tracking access", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        }
                        Switch(
                            checked = confirmingAllowLiveTracking,
                            onCheckedChange = { confirmingAllowLiveTracking = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = colors.info)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Safe Resolution Notification", fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                            Text("Notify contact when SOS is resolved", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        }
                        Switch(
                            checked = confirmingNotifyOnClosure,
                            onCheckedChange = { confirmingNotifyOnClosure = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = colors.success)
                        )
                    }

                    confirmingError?.let {
                        Text(it, color = colors.emergency, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val err = vm.addOrUpdateContact(
                            id = null,
                            name = confirmingName,
                            phone = confirmingPhone,
                            label = confirmingLabel,
                            isPrimary = confirmingIsPrimary,
                            source = EmergencyContact.SOURCE_PHONE_CONTACTS,
                            notifyOnSos = confirmingNotifyOnSos,
                            includeLocationLink = confirmingIncludeLocationLink,
                            notifyOnClosure = confirmingNotifyOnClosure,
                            allowLiveTracking = confirmingAllowLiveTracking,
                            preferredChannel = confirmingPreferredChannel
                        )
                        if (err != null) {
                            confirmingError = err
                        } else {
                            isConfirmingPickedContact = false
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Save Contact", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { isConfirmingPickedContact = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog 3: Manual Add / Edit Contact Dialog
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = {
                Text(
                    text = if (editId == null) "Add Emergency Contact" else "Edit Contact",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Full Name") },
                        leadingIcon = {
                            Icon(Icons.Rounded.Person, contentDescription = null, tint = colors.textSecondary)
                        },
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = editPhone,
                        onValueChange = { editPhone = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Phone Number") },
                        leadingIcon = {
                            Icon(Icons.Rounded.Phone, contentDescription = null, tint = colors.textSecondary)
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Phone),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true
                    )

                    Text(
                        text = "Relationship / Label",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )

                    RelationshipLabelRow(
                        selected = editLabel,
                        onSelect = { editLabel = it }
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Set as Primary", fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                            Text("Prioritized first when SOS triggers", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        }
                        Switch(
                            checked = editIsPrimary,
                            onCheckedChange = { editIsPrimary = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = colors.success)
                        )
                    }

                    HorizontalDivider(color = colors.cardBorder)

                    Text(
                        text = "Emergency Notification Preferences",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Alert on SOS Trigger", fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                            Text("Automatically dispatch urgent notification", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        }
                        Switch(
                            checked = editNotifyOnSos,
                            onCheckedChange = { editNotifyOnSos = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = colors.emergency)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Include Live Location Link", fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                            Text("Attach coordinates link in alert text", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        }
                        Switch(
                            checked = editIncludeLocationLink,
                            onCheckedChange = { editIncludeLocationLink = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = colors.info)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Allow Live Incident Tracking", fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                            Text("Grant temporary live tracking access", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        }
                        Switch(
                            checked = editAllowLiveTracking,
                            onCheckedChange = { editAllowLiveTracking = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = colors.info)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Safe Resolution Notification", fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                            Text("Notify contact when SOS is resolved", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        }
                        Switch(
                            checked = editNotifyOnClosure,
                            onCheckedChange = { editNotifyOnClosure = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = colors.success)
                        )
                    }

                    editError?.let {
                        Text(
                            text = it,
                            color = colors.emergency,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val err = vm.addOrUpdateContact(
                            id = editId,
                            name = editName,
                            phone = editPhone,
                            label = editLabel,
                            isPrimary = editIsPrimary,
                            source = EmergencyContact.SOURCE_MANUAL,
                            notifyOnSos = editNotifyOnSos,
                            includeLocationLink = editIncludeLocationLink,
                            notifyOnClosure = editNotifyOnClosure,
                            allowLiveTracking = editAllowLiveTracking,
                            preferredChannel = editPreferredChannel
                        )
                        if (err != null) {
                            editError = err
                        } else {
                            editing = false
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Save Contact", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog 4: Remove Confirmation Dialog
    removingId?.let { key ->
        val removingContact = items.find { it.id == key }
        AlertDialog(
            onDismissRequest = { removingId = null },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.DeleteForever,
                    contentDescription = null,
                    tint = colors.emergency,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text("Remove Contact?", fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    text = "Are you sure you want to remove \"${removingContact?.displayName ?: "this contact"}\" from your trusted emergency circle? ${if (removingContact?.isPrimary == true) "Another contact will automatically become your primary contact." else ""}"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.remove(key)
                        removingId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.emergency),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Remove", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { removingId = null }) {
                    Text("Keep")
                }
            }
        )
    }

    // Dialog 5: General Error Alert
    alertMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { alertMessage = null },
            icon = {
                Icon(Icons.Rounded.Info, contentDescription = null, tint = colors.warning, modifier = Modifier.size(32.dp))
            },
            title = {
                Text("Notice", fontWeight = FontWeight.Bold)
            },
            text = {
                Text(msg)
            },
            confirmButton = {
                Button(onClick = { alertMessage = null }) {
                    Text("OK")
                }
            }
        )
    }

    // Dialog 6: Simulated Alert Testing Dialog
    simulatedAlertContact?.let { contact ->
        val simulatedMsg = `in`.maitri.app.notification.SosContactNotifierHelper.formatEmergencyMessage(
            contactName = contact.displayName,
            latitude = 28.6139,
            longitude = 77.2090,
            recordedAtMillis = System.currentTimeMillis(),
            includeLocation = contact.includeLocationLink
        )
        AlertDialog(
            onDismissRequest = { simulatedAlertContact = null },
            icon = {
                Icon(Icons.Rounded.Security, contentDescription = null, tint = colors.emergency, modifier = Modifier.size(32.dp))
            },
            title = {
                Text("Simulated Emergency Alert", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Recipient: ", fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                        Text(contact.displayName, color = colors.textPrimary)
                        Spacer(Modifier.width(6.dp))
                        Text("(${EmergencyContact.maskPhone(contact.phoneNumber)})", color = colors.textSecondary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Status: ", fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                        StatusBadge(text = "PROVIDER_ACCEPTED (TEST)", color = colors.success)
                    }
                    Text("Message Preview:", fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = colors.cardBackgroundSecondary,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = simulatedMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Text(
                        text = "• Safety isolation verified: zero carrier SMS charges. Emergency services NOT contacted during simulation.\n• Native SMS Composer available for optional manual verification.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val smsIntent = `in`.maitri.app.notification.SosContactNotifierHelper.createSmsComposerIntent(
                            contact.phoneNumber,
                            simulatedMsg
                        )
                        context.startActivity(smsIntent)
                        simulatedAlertContact = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.info)
                ) {
                    Text("Open SMS Composer", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { simulatedAlertContact = null }) {
                    Text("Dismiss")
                }
            }
        )
    }
}

/**
 * Reusable Liquid Glass card for an emergency contact item.
 */
@Composable
private fun ContactCard(
    contact: EmergencyContact,
    colors: MaitriColors,
    isPrimary: Boolean,
    onCall: () -> Unit,
    onTestAlert: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onMakePrimary: (() -> Unit)?
) {
    MaitriCard(
        backgroundColor = if (isPrimary) colors.emergencyContainer.copy(alpha = 0.5f) else null,
        borderColor = if (isPrimary) colors.emergency.copy(alpha = 0.45f) else null
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar Initial with primary accent border if primary
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (isPrimary) {
                            Brush.linearGradient(listOf(colors.emergency, Color(0xFFE11D48)))
                        } else {
                            Brush.linearGradient(listOf(colors.info, Color(0xFF6366F1)))
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = contact.displayName.take(1).uppercase().ifBlank { "C" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = contact.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                    if (isPrimary) {
                        Spacer(Modifier.width(6.dp))
                        StatusBadge(
                            text = "PRIMARY",
                            color = colors.emergency
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = contact.phoneNumber,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "· ${contact.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary
                    )
                }

                // Preference Badges
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (contact.notifyOnSos) {
                        StatusBadge(text = "SOS", color = colors.emergency)
                    }
                    if (contact.allowLiveTracking) {
                        StatusBadge(text = "TRACK", color = colors.info)
                    }
                    if (contact.notifyOnClosure) {
                        StatusBadge(text = "SAFE", color = colors.success)
                    }
                }
            }

            // Test Alert Button
            GlassIconButton(
                icon = Icons.Rounded.NotificationsActive,
                contentDescription = "Test Alert for ${contact.displayName}",
                onClick = onTestAlert,
                tint = colors.warning,
                size = 36.dp,
                shape = CircleShape
            )

            Spacer(Modifier.width(4.dp))

            // Quick Call via system dialer
            GlassIconButton(
                icon = Icons.Rounded.Call,
                contentDescription = "Call ${contact.displayName}",
                onClick = onCall,
                tint = colors.success,
                size = 36.dp,
                shape = CircleShape
            )

            // Optional "Make Primary" star button for secondary contacts
            if (onMakePrimary != null) {
                Spacer(Modifier.width(4.dp))
                GlassIconButton(
                    icon = Icons.Rounded.StarBorder,
                    contentDescription = "Set as Primary",
                    onClick = onMakePrimary,
                    tint = colors.warning,
                    size = 36.dp,
                    shape = CircleShape
                )
            }

            Spacer(Modifier.width(4.dp))

            // Edit
            GlassIconButton(
                icon = Icons.Rounded.Edit,
                contentDescription = "Edit",
                onClick = onEdit,
                tint = colors.textPrimary,
                size = 36.dp,
                shape = CircleShape
            )

            Spacer(Modifier.width(4.dp))

            // Remove
            GlassIconButton(
                icon = Icons.Rounded.DeleteOutline,
                contentDescription = "Remove",
                onClick = onRemove,
                tint = colors.emergency,
                size = 36.dp,
                shape = CircleShape
            )
        }
    }
}

/**
 * Horizontally scrollable chip row for picking relationship / category label.
 */
@Composable
private fun RelationshipLabelRow(
    selected: String,
    onSelect: (String) -> Unit
) {
    val colors = MaterialTheme.maitriColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        STANDARD_RELATIONSHIP_LABELS.forEach { label ->
            val isSelected = label.equals(selected, ignoreCase = true)
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onSelect(label) },
                shape = RoundedCornerShape(20.dp),
                color = if (isSelected) colors.info.copy(alpha = 0.2f) else colors.cardBackgroundSecondary,
                border = BorderStroke(
                    1.dp,
                    if (isSelected) colors.info else colors.cardBorder
                )
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) colors.info else colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}
