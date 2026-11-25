package `in`.maitri.app.ui

import `in`.maitri.app.tr

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
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
import kotlinx.coroutines.launch
import `in`.maitri.app.R
import `in`.maitri.app.data.EmergencyContact
import `in`.maitri.app.state.SafetyViewModel

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
    val uid = remember { runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid }.getOrNull() }
    var linked by remember { mutableStateOf(`in`.maitri.app.startup.StartupCache.get<List<`in`.maitri.app.network.CircleLinkDto>>(uid,
        `in`.maitri.app.startup.StartupCache.LINKS).orEmpty().filter { it.owner }.map { it.contactId }.toSet()) }
    var memberships by remember { mutableStateOf(`in`.maitri.app.startup.StartupCache.get<List<`in`.maitri.app.network.MembershipDto>>(uid,
        `in`.maitri.app.startup.StartupCache.MEMBERSHIPS).orEmpty()) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(uid, refresh, contacts.size) {
        val account = uid?.let { `in`.maitri.app.network.ExpectedAccount(it) } ?: return@LaunchedEffect
        try {
            val links = `in`.maitri.app.network.BackendClient.api.circleLinks(account)
            linked = links.filter { it.owner }.map { it.contactId }.toSet()
            `in`.maitri.app.startup.StartupCache.put(uid, `in`.maitri.app.startup.StartupCache.LINKS, links)
            memberships = `in`.maitri.app.network.BackendClient.circle.memberships(account)
            `in`.maitri.app.startup.StartupCache.put(uid, `in`.maitri.app.startup.StartupCache.MEMBERSHIPS, memberships)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
    }
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
        },
        linkedContactIds = linked,
        memberships = memberships,
        uid = uid,
        // Adding someone back creates a contact on the server: reload the circle so it appears in the list.
        onLinked = { refresh++; vm?.refreshContacts() }
    )
}

@Composable
internal fun MaitriCircleContent(
    contacts: List<EmergencyContact>,
    p: MaitriPalette,
    onAddOrUpdateContact: (id: String?, name: String, phone: String, label: String, isPrimary: Boolean, notifyOnSos: Boolean) -> String?,
    onRemoveContact: (String) -> Unit,
    onToggleSosAlert: (String, Boolean) -> Unit,
    linkedContactIds: Set<String> = emptySet(),
    memberships: List<`in`.maitri.app.network.MembershipDto> = emptyList(),
    uid: String? = null,
    onLinked: () -> Unit = {}
) {
    val isDark = p.dark
    var inviting by remember { mutableStateOf<EmergencyContact?>(null) }
    var enteringCode by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf<EmergencyContact?>(null) }
    var deletingContact by remember { mutableStateOf<EmergencyContact?>(null) }

    // Multi-number picker dialog state when importing from phone
    var multiNumberOptions by remember { mutableStateOf<Pair<String, List<ContactPhoneEntry>>?>(null) }

    // Android's phone-number picker: the user picks one number, and Maitri may read just that row (no READ_CONTACTS).
    // The plain contact picker (PickContact) only grants the contact itself, so its numbers can't be read.
    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ContactPickerHelper.PickPhoneNumber
    ) { uri: Uri? ->
        if (uri != null) {
            val resolved = ContactPickerHelper.resolvePickedContact(context, uri)
            if (resolved == null || resolved.phoneNumbers.isEmpty()) {
                Toast.makeText(context, tr("No phone number found for selected contact"), Toast.LENGTH_SHORT).show()
            } else if (resolved.phoneNumbers.size == 1) {
                val single = resolved.phoneNumbers.first()
                val err = onAddOrUpdateContact(null, resolved.name, single.number, single.typeLabel.ifBlank { tr("Mobile") }, false, true)
                if (err != null) {
                    Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, tr("Added {0} to Trusted Circle", resolved.name), Toast.LENGTH_SHORT).show()
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
                        text = tr("YOUR PEOPLE, CLOSE BY"),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.6.sp,
                        color = p.accent
                    )
                    Text(
                        text = tr("Your circle"),
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
                        text = tr("Keep your people close"),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = p.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = designBreaks(tr("These contacts receive your SOS and\nlive location when you ask for help.")),
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
                    text = tr("Saved contacts"),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = p.primary
                )
                Text(
                    text = if (contacts.size == 1) tr("1 person") else tr("{0} people", contacts.size),
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
                            text = tr("No trusted contacts yet"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = p.primary
                        )
                        Text(
                            text = tr("Add at least one person who will be automatically alerted when SOS is triggered."),
                            fontSize = 11.sp,
                            color = p.secondary,
                            textAlign = TextAlign.Center,
                            lineHeight = 15.sp
                        )
                    }
                }
            } else {
                contacts.forEachIndexed { index, contact ->
                    Box(Modifier.fadeInOnFirstLoad(index)) { ContactCardItem(
                        contact = contact,
                        palette = p,
                        onEdit = { editingContact = contact },
                        onRemove = { deletingContact = contact },
                        onToggleSos = { enabled -> onToggleSosAlert(contact.id, enabled) },
                        onMaitri = if (uid == null) null else contact.id in linkedContactIds,
                        onInvite = { inviting = contact }
                    ) }
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
                            .size(36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // The design's outline shield-check, without a backing circle.
                        Icon(
                            painter = painterResource(R.drawable.maitri_shield_check),
                            contentDescription = null,
                            tint = p.accent,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tr("Choose who gets your SOS"),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = p.primary
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = designBreaks(tr("Turn alerts on for the people you want\nto notify in an emergency.")),
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
                    .pressScale()
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
                        text = tr("Add contact"),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isDark) Color(0xFF40303A) else Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Import from phone contacts link
            Text(
                text = tr("Import from phone contacts"),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = p.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { contactPickerLauncher.launch(null) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
            if (uid != null) {
                Text(
                    text = tr("Enter a code from a friend"),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = p.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { enteringCode = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
                if (memberships.isNotEmpty()) CirclesYouAreIn(memberships, uid, p, onLinked)
            }
        }
    }
    inviting?.let { contact -> if (uid != null) CircleInviteDialog(uid, contact, p) { inviting = null; onLinked() } }
    if (enteringCode && uid != null) EnterCircleCodeDialog(uid, p) { enteringCode = false; onLinked() }

    // Add Contact Dialog
    if (showAddDialog) {
        ContactFormDialog(
            title = tr("Add trusted contact"),
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
            title = tr("Edit contact"),
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
        AlertDialog(modifier = Modifier.dialogEnter(), 
            onDismissRequest = { deletingContact = null },
            title = {
                Text(
                    text = tr("Remove trusted contact?"),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = p.primary
                )
            },
            text = {
                Text(
                    text = tr("Are you sure you want to remove {0}? They will no longer receive emergency alerts or trip updates.", contact.displayName),
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
                        Toast.makeText(context, tr("Removed {0}", contact.displayName), Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(tr("Remove"), color = p.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingContact = null }) {
                    Text(tr("Cancel"), color = p.secondary)
                }
            },
            containerColor = p.surface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Multi-number Selection Dialog (for phone import)
    multiNumberOptions?.let { (contactName, numbers) ->
        AlertDialog(modifier = Modifier.dialogEnter(), 
            onDismissRequest = { multiNumberOptions = null },
            title = {
                Text(
                    text = tr("Select phone number"),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = p.primary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = tr("{0} has multiple phone numbers. Choose which one to add:", contactName),
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
                                        entry.typeLabel.ifBlank { tr("Mobile") },
                                        false,
                                        true
                                    )
                                    if (err != null) {
                                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, tr("Added {0}", contactName), Toast.LENGTH_SHORT).show()
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
                                    text = entry.typeLabel.ifBlank { tr("Phone") },
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
                    Text(tr("Cancel"), color = p.secondary)
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
    onToggleSos: (Boolean) -> Unit,
    onMaitri: Boolean? = null,
    onInvite: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(palette.surface)
            // 342 x 96 in the design: no outline, edit button 12 from the top, toggle row 19 below it.
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp)
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
                        FitText(contact.displayName, Modifier, palette.primary, 16f, weight = FontWeight.Medium)
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    FitText(if (contact.isPrimary) tr("{0} · Primary contact", contact.label) else contact.label,
                        Modifier, palette.secondary, 11f)
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
                        painter = painterResource(R.drawable.maitri_pencil),
                        contentDescription = tr("Edit contact"),
                        tint = palette.accent,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(17.dp))

            // Bottom Row: SOS Switch (32x18dp) + SOS alert: ON/OFF label | Remove action
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: Custom Mini Toggle Switch matching SVG (32x18dp, rx=9dp)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // Takes the space left by Invite/Remove, so those never break mid-word with large text.
                    modifier = Modifier
                        .weight(1f, fill = false)
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

                    FitText(if (contact.notifyOnSos) tr("SOS alerts on") else tr("SOS alerts off"), Modifier,
                        palette.secondary, 10f)
                }

                // Middle: whether SOS alerts reach this person as a push (linked Maitri account) or by your SMS.
                if (onMaitri != null) Text(
                    text = if (onMaitri) tr("On Maitri") else tr("Invite"),
                    fontSize = 11.sp,
                    fontWeight = if (onMaitri) FontWeight.Medium else FontWeight.SemiBold,
                    color = if (onMaitri) palette.secondary else palette.accent,
                    maxLines = 1, softWrap = false,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .then(if (onMaitri) Modifier else Modifier.clickable(onClickLabel = tr("Invite {0} to Maitri", contact.displayName), onClick = onInvite))
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )

                // Right: Remove button
                Text(
                    text = tr("Remove"),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.accent,
                    maxLines = 1, softWrap = false,
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
    var label by rememberSaveable { mutableStateOf(existing?.label ?: tr("Sister")) }
    var isPrimary by rememberSaveable { mutableStateOf(existing?.isPrimary ?: false) }
    var notifySos by rememberSaveable { mutableStateOf(existing?.notifyOnSos ?: true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val relationships = listOf(tr("Sister"), tr("Mother"), tr("Father"), tr("Brother"), tr("Spouse"), tr("Friend"), tr("Neighbor"), tr("Other"))

    AlertDialog(modifier = Modifier.dialogEnter(), 
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
                    label = { Text(tr("Contact name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it; errorMessage = null },
                    label = { Text(tr("Phone number")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Text(
                    text = tr("Relationship label"),
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
                                color = if (selected) palette.onAccent else palette.primary
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
                        text = tr("Alert during SOS emergencies"),
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
                        text = tr("Set as primary contact"),
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
                Text(tr("Save"), color = palette.onAccent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("Cancel"), color = palette.secondary)
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

/** Owner side: a single-use code that links this contact's Maitri account, sent any way the owner likes. */
@Composable
private fun CircleInviteDialog(uid: String, contact: EmergencyContact, p: MaitriPalette, dismiss: () -> Unit) {
    val context = LocalContext.current
    var invite by remember { mutableStateOf<`in`.maitri.app.network.CircleInviteDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(contact.id) {
        try { invite = `in`.maitri.app.network.BackendClient.circle.invite(
            `in`.maitri.app.network.CircleInviteRequest(contact.id), `in`.maitri.app.network.ExpectedAccount(uid)) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { error = tr("Couldn't create a code. Check your connection and that {0} is saved.", contact.displayName) }
    }
    AlertDialog(modifier = Modifier.dialogEnter(), onDismissRequest = dismiss, containerColor = p.surface, titleContentColor = p.primary, textContentColor = p.secondary,
        title = { Text(tr("Invite {0}", contact.displayName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Send this code to {0}. Once they enter it in Maitri, your SOS alerts reach them as a notification with your live location.", contact.displayName))
                error?.let { Text(it, color = p.accent) }
                invite?.let {
                    Text(it.code, color = p.primary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Text(tr("Valid for 24 hours, once."), fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                } ?: if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent) else Unit
            }
        },
        confirmButton = {
            TextButton(enabled = invite != null, onClick = {
                invite?.let { context.startActivity(`in`.maitri.app.network.shareTextIntent(`in`.maitri.app.network.inviteText(it.code))) }
            }) { Text(tr("Send code"), color = p.accent) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(tr("Done"), color = p.secondary) } })
}

/** Friend side: entering a code joins that person's circle. */
@Composable
private fun EnterCircleCodeDialog(uid: String, p: MaitriPalette, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(modifier = Modifier.dialogEnter(), onDismissRequest = dismiss, containerColor = p.surface, titleContentColor = p.primary, textContentColor = p.secondary,
        title = { Text(tr("Enter a code")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Enter the code a friend sent you. You'll get their SOS alerts and their live location while an SOS is active."))
                OutlinedTextField(code, { code = it.uppercase().take(12) }, singleLine = true, label = { Text(tr("Code")) },
                    placeholder = { Text(tr("ABCD-2345")) }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = p.accent) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && code.count { it.isLetterOrDigit() } == 8, onClick = {
                busy = true
                scope.launch {
                    try {
                        val result = `in`.maitri.app.network.BackendClient.circle.accept(
                            `in`.maitri.app.network.AcceptInviteRequest(code), `in`.maitri.app.network.ExpectedAccount(uid))
                        Toast.makeText(context, result.ownerName?.let { tr("You're now in {0}'s circle", it) } ?: tr("You're now in their circle"), Toast.LENGTH_LONG).show()
                        dismiss()
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: retrofit2.HttpException) {
                        error = when (e.code()) {
                            404 -> tr("That code isn't valid or has expired. Ask for a new one.")
                            422 -> tr("This is your own code. Send it to the person you added.")
                            429 -> tr("Too many codes tried. Try again in an hour.")
                            else -> tr("Couldn't join right now. Try again.")
                        }
                    } catch (_: Exception) { error = tr("Couldn't reach Maitri. Check your connection.") }
                    finally { busy = false }
                }
            }) { Text(tr("Join"), color = p.accent) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(tr("Cancel"), color = p.secondary) } })
}
