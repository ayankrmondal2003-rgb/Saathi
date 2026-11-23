package com.example.surakshanet.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.surakshanet.R

enum class VolunteerDocType(val title: String, val subtitle: String, val icon: ImageVector) {
    GOVT_ID("Government issued identity proof", "Upload document", Icons.Rounded.Badge),
    ADDRESS_PROOF("Address proof", "Upload document", Icons.Rounded.Home),
    POLICE_CERT("Police character certificate", "Upload document", Icons.Rounded.Security),
    PHOTO("Recent photograph", "Upload photo", Icons.Rounded.Image),
    DECLARATION("Signed volunteer declaration", "Upload document", Icons.Rounded.Description)
}

data class SelectedDocument(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long
)

/**
 * Screen 04 — Volunteer Verification.
 * Strict SVG fidelity matching design-reference/crops/light-04.svg and dark-04.svg.
 */
@Composable
fun MaitriVolunteer(
    onSkip: () -> Unit,
    onSubmitSuccess: () -> Unit = {}
) {
    val p = maitriPalette()
    val context = LocalContext.current

    var selectedIntent by rememberSaveable { mutableStateOf("HELP") } // "HELP" or "NOT_NOW"
    var consentChecked by rememberSaveable { mutableStateOf(false) }

    // Map of doc type to selected document info (local selection only)
    var selectedDocs by remember { mutableStateOf(mapOf<VolunteerDocType, SelectedDocument>()) }
    var activePickingDocType by remember { mutableStateOf<VolunteerDocType?>(null) }

    var statusMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var showBlockedStorageDialog by rememberSaveable { mutableStateOf(false) }

    val docPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val docType = activePickingDocType
        if (uri != null && docType != null) {
            val cursor = runCatching { context.contentResolver.query(uri, null, null, null, null) }.getOrNull()
            var displayName = "document_${docType.name.lowercase()}"
            var sizeBytes = 0L
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIdx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (nameIdx >= 0) displayName = it.getString(nameIdx) ?: displayName
                    if (sizeIdx >= 0) sizeBytes = it.getLong(sizeIdx)
                }
            }
            if (sizeBytes > 10 * 1024 * 1024) {
                statusMessage = "File exceeds maximum size of 10 MB."
            } else {
                selectedDocs = selectedDocs + (docType to SelectedDocument(uri, displayName, sizeBytes))
                statusMessage = null
            }
        }
        activePickingDocType = null
    }

    MaitriVolunteerContent(
        p = p,
        selectedIntent = selectedIntent,
        onIntentSelect = { intent ->
            selectedIntent = intent
            if (intent == "NOT_NOW") {
                onSkip()
            }
        },
        selectedDocs = selectedDocs,
        onPickDoc = { docType ->
            activePickingDocType = docType
            val mime = if (docType == VolunteerDocType.PHOTO) "image/*" else "*/*"
            docPickerLauncher.launch(mime)
        },
        onRemoveDoc = { docType ->
            selectedDocs = selectedDocs - docType
        },
        consentChecked = consentChecked,
        onConsentToggle = { consentChecked = !consentChecked },
        onSubmit = {
            if (!consentChecked) {
                statusMessage = "Please accept the volunteer declaration to continue."
                return@MaitriVolunteerContent
            }
            val missing = VolunteerDocType.entries.filter { it !in selectedDocs }
            if (missing.isNotEmpty()) {
                statusMessage = "Please attach all 5 required verification documents."
                return@MaitriVolunteerContent
            }
            // Per Security Policy 2.3: Live identity documents must not be collected
            // until private, encrypted, short-lived signed-url object storage is configured.
            showBlockedStorageDialog = true
        },
        statusMessage = statusMessage
    )

    if (showBlockedStorageDialog) {
        AlertDialog(
            onDismissRequest = { showBlockedStorageDialog = false },
            containerColor = p.surface,
            titleContentColor = p.primary,
            textContentColor = p.secondary,
            title = { Text("Secure Storage Notice", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Live volunteer document upload is BLOCKED pending verified private object storage infrastructure."
                    )
                    Text(
                        "Your 5 verification documents are validated locally. Real identity documents are protected and will only be collected once encrypted server-side reviewer storage is provisioned.",
                        fontSize = 12.sp,
                        color = p.secondary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBlockedStorageDialog = false
                        onSubmitSuccess()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = Color.White)
                ) { Text("Understood") }
            }
        )
    }
}

@Composable
internal fun MaitriVolunteerContent(
    p: MaitriPalette,
    selectedIntent: String,
    onIntentSelect: (String) -> Unit,
    selectedDocs: Map<VolunteerDocType, SelectedDocument>,
    onPickDoc: (VolunteerDocType) -> Unit,
    onRemoveDoc: (VolunteerDocType) -> Unit,
    consentChecked: Boolean,
    onConsentToggle: () -> Unit,
    onSubmit: () -> Unit,
    statusMessage: String?
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background)
            .safeDrawingPadding()
    ) {
        val contentHeight = maxOf(maxHeight, 766.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = contentHeight)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(14.dp))

            // Header: Logo mark + Wordmark
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.maitri_logo),
                    contentDescription = null,
                    modifier = Modifier.size(38.dp)
                )
                Spacer(Modifier.width(10.dp))
                Image(
                    painter = painterResource(if (p.dark) R.drawable.maitri_wordmark_dark else R.drawable.maitri_wordmark_light),
                    contentDescription = stringResource(R.string.maitri_brand),
                    modifier = Modifier.height(24.dp)
                )
            }

            Spacer(Modifier.height(28.dp))

            // Title & Subtitle
            Text(
                text = "Be there for someone.",
                color = p.primary,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Join maitri as a volunteer and help your\ncommunity feel safer.",
                color = p.secondary,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(20.dp))

            // Selection Row: "Yes, I'd like to help" (223dp) + "Not now" (106dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val isHelp = selectedIntent == "HELP"
                // Yes, I'd like to help
                Row(
                    modifier = Modifier
                        .weight(2.1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (isHelp) p.card else p.surface)
                        .border(
                            BorderStroke(1.dp, if (isHelp) p.accent else Color.Transparent),
                            RoundedCornerShape(24.dp)
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onIntentSelect("HELP") }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = if (isHelp) p.accent else p.secondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Yes, I'd like to help",
                        color = if (isHelp) p.accent else p.secondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Not now
                val isNotNow = selectedIntent == "NOT_NOW"
                Box(
                    modifier = Modifier
                        .weight(1.0f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (isNotNow) p.card else p.surface)
                        .border(
                            BorderStroke(1.dp, if (isNotNow) p.accent else Color.Transparent),
                            RoundedCornerShape(24.dp)
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onIntentSelect("NOT_NOW") },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Not now",
                        color = if (isNotNow) p.accent else p.secondary,
                        fontSize = 13.sp,
                        fontWeight = if (isNotNow) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Section: Required documents
            Text(
                text = "Volunteer verification",
                color = p.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "All 5 documents are required.",
                color = p.secondary,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(14.dp))

            // 5 Documents Container Card (342 x 296, rx 20)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(p.surface)
            ) {
                VolunteerDocType.entries.forEachIndexed { index, docType ->
                    val doc = selectedDocs[docType]
                    val isUploaded = doc != null

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp)
                            .clickable { onPickDoc(docType) }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = docType.icon,
                            contentDescription = null,
                            tint = if (isUploaded) p.accent else p.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = docType.title,
                                color = p.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = if (isUploaded) doc.name else docType.subtitle,
                                color = if (isUploaded) p.accent else p.secondary,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(p.card),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isUploaded) Icons.Rounded.Check else Icons.Rounded.FileUpload,
                                contentDescription = "Upload",
                                tint = p.accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (index < VolunteerDocType.entries.lastIndex) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .padding(horizontal = 14.dp)
                                .background(p.divider)
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Consent declaration checkbox row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onConsentToggle() },
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(17.dp)
                        .clip(RoundedCornerShape(4.5.dp))
                        .background(if (consentChecked) p.accent else Color.Transparent)
                        .border(
                            BorderStroke(1.dp, if (consentChecked) p.accent else p.secondary),
                            RoundedCornerShape(4.5.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (consentChecked) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "I consent to document verification and agree to\nthe Volunteer Declaration.",
                    color = p.secondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(14.dp))
            // Counts documents actually selected on this device; nothing is uploaded until secure storage exists.
            Text(
                text = "${selectedDocs.size} of ${VolunteerDocType.entries.size} documents selected",
                color = p.secondary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            if (statusMessage != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = statusMessage,
                    color = p.accent,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                )
            }

            Spacer(Modifier.height(20.dp))

            // Action Button: "Submit for review" (342 x 48, rx 24)
            val allDocsAttached = selectedDocs.size == VolunteerDocType.entries.size
            val readyToSubmit = allDocsAttached && consentChecked

            Button(
                onClick = onSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (readyToSubmit) p.accent else p.card,
                    contentColor = if (readyToSubmit) (if (p.dark) p.background else Color.White) else p.accent
                ),
                elevation = null
            ) {
                Text(
                    text = "Submit for review",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(24.dp))

            // The SVG frame's home indicator belongs to the device chrome, not the app.
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Preview(name = "Maitri 04 Light", widthDp = 390, heightDp = 844)
@Composable
private fun MaitriVolunteerLightPreview() {
    MaitriVolunteerContent(
        p = MaitriPalette(dark = false),
        selectedIntent = "HELP",
        onIntentSelect = {},
        selectedDocs = emptyMap(),
        onPickDoc = {},
        onRemoveDoc = {},
        consentChecked = false,
        onConsentToggle = {},
        onSubmit = {},
        statusMessage = null
    )
}

@Preview(name = "Maitri 04 Dark", widthDp = 390, heightDp = 844)
@Composable
private fun MaitriVolunteerDarkPreview() {
    MaitriVolunteerContent(
        p = MaitriPalette(dark = true),
        selectedIntent = "HELP",
        onIntentSelect = {},
        selectedDocs = emptyMap(),
        onPickDoc = {},
        onRemoveDoc = {},
        consentChecked = false,
        onConsentToggle = {},
        onSubmit = {},
        statusMessage = null
    )
}
