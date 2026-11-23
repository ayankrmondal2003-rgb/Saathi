package com.example.surakshanet.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.surakshanet.R
import com.example.surakshanet.data.EmergencyContact
import com.example.surakshanet.state.SafetyViewModel

/**
 * Screen 05 — Trusted Circle.
 * Strict SVG fidelity matching design-reference/crops/light-05.svg and dark-05.svg.
 */
@Composable
fun MaitriCircle(
    contacts: List<EmergencyContact>,
    vm: SafetyViewModel? = null,
    onAddOrUpdateContact: ((id: String?, name: String, phone: String, label: String, isPrimary: Boolean, notifyOnSos: Boolean) -> String?)? = null,
    onRemoveContact: ((String) -> Unit)? = null,
    onToggleSosAlert: ((String, Boolean) -> Unit)? = null,
    message: String? = null
) {
    val p = maitriPalette()
    val context = LocalContext.current
    LaunchedEffect(message) {
        if (message != null) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            vm?.clearContactsMessage()
        }
    }
    MaitriCircleContent(
        contacts = contacts,
        p = p,
        onAddOrUpdateContact = onAddOrUpdateContact ?: { id: String?, name: String, phone: String, label: String, primary: Boolean, sos: Boolean ->
            vm?.addOrUpdateContact(
                id = id,
                name = name,
                phone = phone,
                label = label,
                isPrimary = primary,
                source = EmergencyContact.SOURCE_MANUAL,
                notifyOnSos = sos
            )
        },
        onRemoveContact = onRemoveContact ?: { id: String ->
            vm?.remove(id)
            Unit
        },
        onToggleSosAlert = onToggleSosAlert ?: { id: String, enabled: Boolean ->
            vm?.toggleSosAlert(id, enabled)
            Unit
        }
    )
}

@Composable
internal fun MaitriCircleContent(
    contacts: List<EmergencyContact>,
    p: MaitriPalette,
    onAddOrUpdateContact: (id: String?, name: String, phone: String, label: String, isPrimary: Boolean, notifyOnSos: Boolean) -> String?,
    onRemoveContact: (String) -> Unit,
    onToggleSosAlert: (String, Boolean) -> Unit
) {
    val isDark = p.dark
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf<EmergencyContact?>(null) }
    var deletingContact by remember { mutableStateOf<EmergencyContact?>(null) }

    // Multi-number picker dialog state when importing from phone
    var multiNumberOptions by remember { mutableStateOf<Pair<String, List<ContactPhoneEntry>>?>(null) }

    // Pick contact from system contacts provider (does not require broad READ_CONTACTS)
    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { uri: Uri? ->
        if (uri != null) {
            val resolved = ContactPickerHelper.resolvePickedContact(context, uri)
            if (resolved == null || resolved.phoneNumbers.isEmpty()) {
                Toast.makeText(context, "No phone number found for selected contact", Toast.LENGTH_SHORT).show()
            } else if (resolved.phoneNumbers.size == 1) {
                val single = resolved.phoneNumbers.first()
                val err = onAddOrUpdateContact(null, resolved.name, single.number, single.typeLabel.ifBlank { "Mobile" }, false, true)
                if (err != null) {
                    Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "Added ${resolved.name} to Trusted Circle", Toast.LENGTH_SHORT).show()
                }
            } else {
                multiNumberOptions = Pair(resolved.name, resolved.phoneNumbers)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background)
    ) {
        Column(
            // Same insets as screen 02: below the status bar (edge-to-edge) and above the floating dock.
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp)
                .padding(top = 22.dp, bottom = LocalFloatingNavBarPadding.current.coerceAtLeast(120.dp)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header: Logo + YOUR TRUSTED CIRCLE
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.maitri_logo),
                    contentDescription = null,
                    modifier = Modifier.size(38.dp)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = "YOUR PEOPLE, CLOSE BY",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.6.sp,
                        color = p.accent
                    )
                    Text(
                        text = "Your circle",
                        fontSize = 25.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = p.primary
                    )
                }
            }

            // Banner Card (342x88dp, rx=22dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(p.card)
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Column {
                    Text(
                        text = "Keep your people close",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = p.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Save the people you'd reach when you need help.\nSMS alerts are not connected yet.",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        color = p.secondary,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section Header: "TRUSTED CONTACTS" + dynamic count
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Saved contacts",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = p.primary
                )
                Text(
                    text = if (contacts.size == 1) "1 person" else "${contacts.size} people",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = p.secondary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Contact Cards List or Empty State
            if (contacts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(p.surface)
                        .border(1.dp, p.divider, RoundedCornerShape(20.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(p.card),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PersonAdd,
                                contentDescription = null,
                                tint = p.accent,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Text(
                            text = "No trusted contacts yet",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = p.primary
                        )
                        Text(
                            text = "Add at least one person who will be automatically alerted when SOS is triggered.",
                            fontSize = 11.sp,
                            color = p.secondary,
                            textAlign = TextAlign.Center,
                            lineHeight = 15.sp
                        )
                    }
                }
            } else {
                contacts.forEach { contact ->
                    ContactCardItem(
                        contact = contact,
                        palette = p,
                        onEdit = { editingContact = contact },
                        onRemove = { deletingContact = contact },
                        onToggleSos = { enabled -> onToggleSosAlert(contact.id, enabled) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tip Card (342x100dp, rx=22dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(p.surface)
                    .border(1.dp, p.divider, RoundedCornerShape(22.dp))
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(p.card),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = p.accent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Choose who gets your SOS",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = p.primary
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "Turn alerts on for the people you want\nto notify in an emergency.",
                            fontSize = 11.sp,
                            color = p.secondary,
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Add Trusted Contact Button (342x52dp, rx=26dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(p.accent)
                    .clickable { showAddDialog = true },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        tint = if (isDark) Color(0xFF40303A) else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Add contact",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isDark) Color(0xFF40303A) else Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Import from phone contacts link
            Text(
                text = "Import from phone contacts",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = p.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { contactPickerLauncher.launch(null) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }

    // Add Contact Dialog
    if (showAddDialog) {
        ContactFormDialog(
            title = "Add trusted contact",
            existing = null,
            palette = p,
            onDismiss = { showAddDialog = false },
            onSave = { name, phone, label, isPrimary, notifySos ->
                val err = onAddOrUpdateContact(null, name, phone, label, isPrimary, notifySos)
                if (err != null) {
                    err
                } else {
                    showAddDialog = false
                    null
                }
            }
        )
    }

    // Edit Contact Dialog
    editingContact?.let { contact ->
        ContactFormDialog(
            title = "Edit contact",
            existing = contact,
            palette = p,
            onDismiss = { editingContact = null },
            onSave = { name, phone, label, isPrimary, notifySos ->
                val err = onAddOrUpdateContact(contact.id, name, phone, label, isPrimary, notifySos)
                if (err != null) {
                    err
                } else {
                    editingContact = null
                    null
                }
            }
        )
    }

    // Remove Confirmation Dialog
    deletingContact?.let { contact ->
        AlertDialog(
            onDismissRequest = { deletingContact = null },
            title = {
                Text(
                    text = "Remove trusted contact?",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = p.primary
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to remove ${contact.displayName}? They will no longer receive emergency alerts or trip updates.",
                    fontSize = 13.sp,
                    color = p.secondary,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveContact(contact.id)
                        deletingContact = null
                        Toast.makeText(context, "Removed ${contact.displayName}", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Remove", color = p.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingContact = null }) {
                    Text("Cancel", color = p.secondary)
                }
            },
            containerColor = p.surface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Multi-number Selection Dialog (for phone import)
    multiNumberOptions?.let { (contactName, numbers) ->
        AlertDialog(
            onDismissRequest = { multiNumberOptions = null },
            title = {
                Text(
                    text = "Select phone number",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = p.primary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "$contactName has multiple phone numbers. Choose which one to add:",
                        fontSize = 12.sp,
                        color = p.secondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    numbers.forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(p.card)
                                .clickable {
                                    val err = onAddOrUpdateContact(
                                        null,
                                        contactName,
                                        entry.number,
                                        entry.typeLabel.ifBlank { "Mobile" },
                                        false,
                                        true
                                    )
                                    if (err != null) {
                                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Added $contactName", Toast.LENGTH_SHORT).show()
                                    }
                                    multiNumberOptions = null
                                }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = entry.number,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = p.primary
                                )
                                Text(
                                    text = entry.typeLabel.ifBlank { "Phone" },
                                    fontSize = 11.sp,
                                    color = p.secondary
                                )
                            }
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = p.accent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { multiNumberOptions = null }) {
                    Text("Cancel", color = p.secondary)
                }
            },
            containerColor = p.surface,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

/**
 * Individual contact card item strictly matching 342x96dp card in light-05.svg / dark-05.svg.
 */
@Composable
private fun ContactCardItem(
    contact: EmergencyContact,
    palette: MaitriPalette,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onToggleSos: (Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(palette.surface)
            .border(1.dp, palette.divider, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column {
            // Top Row: Name + Primary badge + Edit Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = contact.displayName,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (contact.isPrimary) "${contact.label} · Primary contact" else contact.label,
                        fontSize = 11.sp,
                        color = palette.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Edit Button (44x36dp, rx=18dp)
                Box(
                    modifier = Modifier
                        .size(width = 44.dp, height = 36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(palette.card)
                        .clickable(onClick = onEdit),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "Edit contact",
                        tint = palette.accent,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Bottom Row: SOS Switch (32x18dp) + SOS alert: ON/OFF label | Remove action
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: Custom Mini Toggle Switch matching SVG (32x18dp, rx=9dp)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onToggleSos(!contact.notifyOnSos) }
                        .padding(vertical = 2.dp, horizontal = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 32.dp, height = 18.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (contact.notifyOnSos) palette.accent else palette.secondary.copy(alpha = 0.35f))
                            .padding(2.dp),
                        contentAlignment = if (contact.notifyOnSos) Alignment.CenterEnd else Alignment.CenterStart
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = if (contact.notifyOnSos) "SOS alerts on" else "SOS alerts off",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (contact.notifyOnSos) palette.primary else palette.secondary
                    )
                }

                // Right: Remove button
                Text(
                    text = "Remove",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onRemove)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * Add / Edit Contact Modal Dialog.
 */
@Composable
private fun ContactFormDialog(
    title: String,
    existing: EmergencyContact?,
    palette: MaitriPalette,
    onDismiss: () -> Unit,
    onSave: (name: String, phone: String, label: String, isPrimary: Boolean, notifySos: Boolean) -> String?
) {
    var name by rememberSaveable { mutableStateOf(existing?.displayName.orEmpty()) }
    var phone by rememberSaveable { mutableStateOf(existing?.phoneNumber.orEmpty()) }
    var label by rememberSaveable { mutableStateOf(existing?.label ?: "Sister") }
    var isPrimary by rememberSaveable { mutableStateOf(existing?.isPrimary ?: false) }
    var notifySos by rememberSaveable { mutableStateOf(existing?.notifyOnSos ?: true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val relationships = listOf("Sister", "Mother", "Father", "Brother", "Spouse", "Friend", "Neighbor", "Other")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = palette.primary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (errorMessage != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.card)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = errorMessage!!,
                            fontSize = 11.sp,
                            color = palette.accent,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; errorMessage = null },
                    label = { Text("Contact name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it; errorMessage = null },
                    label = { Text("Phone number") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Text(
                    text = "Relationship label",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.secondary
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    relationships.forEach { rel ->
                        val selected = label == rel
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) palette.accent else palette.card)
                                .clickable { label = rel }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = rel,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) Color.White else palette.primary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { notifySos = !notifySos }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = notifySos,
                        onCheckedChange = { notifySos = it },
                        colors = CheckboxDefaults.colors(checkedColor = palette.accent)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Alert during SOS emergencies",
                        fontSize = 12.sp,
                        color = palette.primary
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isPrimary = !isPrimary }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isPrimary,
                        onCheckedChange = { isPrimary = it },
                        colors = CheckboxDefaults.colors(checkedColor = palette.accent)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Set as primary contact",
                        fontSize = 12.sp,
                        color = palette.primary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val err = onSave(name, phone, label, isPrimary, notifySos)
                    if (err != null) {
                        errorMessage = err
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = palette.accent),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Save", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = palette.secondary)
            }
        },
        containerColor = palette.surface,
        shape = RoundedCornerShape(20.dp)
    )
}

// ---------------------------------------------------------------------------
// Previews for Screen 05 (Light & Dark)
// ---------------------------------------------------------------------------

private val samplePreviewContacts = listOf(
    EmergencyContact(
        id = "c1",
        displayName = "Priya Sharma",
        phoneNumber = "+91 98765 43210",
        label = "Sister",
        isPrimary = true,
        notifyOnSos = true
    ),
    EmergencyContact(
        id = "c2",
        displayName = "Rajesh Verma",
        phoneNumber = "+91 98123 45678",
        label = "Brother",
        isPrimary = false,
        notifyOnSos = true
    )
)

@Preview(name = "Screen 05 — Trusted Circle Light", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun MaitriCircleLightPreview() {
    MaitriCircleContent(
        contacts = samplePreviewContacts,
        p = MaitriPalette(dark = false),
        onAddOrUpdateContact = { _, _, _, _, _, _ -> null },
        onRemoveContact = {},
        onToggleSosAlert = { _, _ -> }
    )
}

@Preview(name = "Screen 05 — Trusted Circle Dark", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun MaitriCircleDarkPreview() {
    MaitriCircleContent(
        contacts = samplePreviewContacts,
        p = MaitriPalette(dark = true),
        onAddOrUpdateContact = { _, _, _, _, _, _ -> null },
        onRemoveContact = {},
        onToggleSosAlert = { _, _ -> }
    )
}
