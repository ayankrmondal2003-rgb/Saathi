package `in`.maitri.app.ui

import `in`.maitri.app.tr
import `in`.maitri.app.trKey

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
import `in`.maitri.app.R
import kotlinx.coroutines.launch

/** Icons are the design's outline drawings (24dp boxes at the design's scale). */
enum class VolunteerDocType(val title: String, val subtitle: String, val icon: Int) {
    GOVT_ID(trKey("Government issued identity proof"), trKey("Upload document"), R.drawable.maitri_id_card),
    ADDRESS_PROOF(trKey("Address proof"), trKey("Upload document"), R.drawable.maitri_house),
    POLICE_CERT(trKey("Police character certificate"), trKey("Upload document"), R.drawable.maitri_shield_outline),
    PHOTO(trKey("Recent photograph"), trKey("Upload photo"), R.drawable.maitri_image),
    DECLARATION(trKey("Signed volunteer declaration"), trKey("Upload document"), R.drawable.maitri_document)
}

enum class DocState { EMPTY, UPLOADING, UPLOADED, FAILED }
data class DocUi(val state: DocState = DocState.EMPTY, val fileId: String? = null)

/** What the screen says about the application once it has left the draft stage (null while drafting). */
internal fun applicationBanner(status: String?, note: String?): Pair<String, String>? = when (status) {
    "SUBMITTED", "UNDER_REVIEW" -> tr("Application under review") to tr("A Maitri reviewer is checking your documents. You'll be told in your notifications.")
    "APPROVED" -> tr("You're an approved volunteer") to tr("Turn on \"Available for nearby requests\" on the Responder tab when you can help.")
    "REQUIRES_CORRECTION" -> tr("Changes needed") to (note ?: tr("Update your documents and submit again."))
    "REJECTED" -> tr("Application not approved") to (note ?: tr("You can update your documents and apply again."))
    else -> null
}

internal fun applicationLocked(status: String?) = status in setOf("SUBMITTED", "UNDER_REVIEW", "APPROVED")

/**
 * Screen 04 — Volunteer Verification.
 * Strict SVG fidelity matching design-reference/crops/light-04.svg and dark-04.svg. Documents go to private R2
 * storage through presigned URLs; the counter shows only uploads the server confirmed.
 */
@Composable
fun MaitriVolunteer(
    onSkip: () -> Unit,
    onSubmitSuccess: () -> Unit = {}
) {
    val p = maitriPalette()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uid = remember { runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid }.getOrNull() }
    val account = remember(uid) { uid?.let { `in`.maitri.app.network.ExpectedAccount(it) } }

    var selectedIntent by rememberSaveable { mutableStateOf("HELP") }
    var consentChecked by rememberSaveable { mutableStateOf(false) }
    var docs by remember { mutableStateOf(VolunteerDocType.entries.associateWith { DocUi() }) }
    var status by remember { mutableStateOf(`in`.maitri.app.startup.StartupCache.get<`in`.maitri.app.network.VolunteerStatusDto>(uid,
        `in`.maitri.app.startup.StartupCache.VOLUNTEER)) }
    var statusMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingSource by remember { mutableStateOf<VolunteerDocType?>(null) }
    var managing by remember { mutableStateOf<VolunteerDocType?>(null) }
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    var captureUri by rememberSaveable { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val a = account ?: return
        try {
            val s = `in`.maitri.app.network.BackendClient.api.volunteerStatus(a)
            `in`.maitri.app.startup.StartupCache.put(uid, `in`.maitri.app.startup.StartupCache.VOLUNTEER, s)
            status = s
            consentChecked = s.consentDeclared
            val uploaded = s.documents.orEmpty().associate { it.docType to it.fileId }
            docs = VolunteerDocType.entries.associateWith { type ->
                uploaded[type.name]?.let { DocUi(DocState.UPLOADED, it) }
                    ?: docs[type]?.takeIf { it.state == DocState.UPLOADING || it.state == DocState.FAILED } ?: DocUi()
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { statusMessage = tr("Couldn't load your application. Check your connection.") }
    }
    LaunchedEffect(uid) { refresh() }

    fun upload(type: VolunteerDocType, uri: Uri) {
        val a = account ?: return
        docs = docs + (type to DocUi(DocState.UPLOADING))
        statusMessage = null
        scope.launch {
            try {
                val (bytes, mime) = `in`.maitri.app.network.Uploads.read(context, uri)
                val previous = status?.documents?.firstOrNull { it.docType == type.name }?.fileId
                val id = `in`.maitri.app.network.Uploads.upload(bytes, mime, "VOLUNTEER_DOC", a, docType = type.name)
                // A replaced document is deleted, so only one copy of each is ever kept.
                if (previous != null && previous != id) runCatching { `in`.maitri.app.network.BackendClient.files.delete(previous, a) }
                docs = docs + (type to DocUi(DocState.UPLOADED, id))
                refresh()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                docs = docs + (type to DocUi(DocState.FAILED))
                statusMessage = `in`.maitri.app.network.uploadErrorMessage(e)
            }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val type = target?.let { VolunteerDocType.valueOf(it) }
        if (uri != null && type != null) upload(type, uri)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val type = target?.let { VolunteerDocType.valueOf(it) }
        val uri = captureUri?.let(Uri::parse)
        if (saved && type != null && uri != null) upload(type, uri)
    }
    fun takePhoto(type: VolunteerDocType) {
        val dir = java.io.File(context.cacheDir, "captures").apply { mkdirs() }
        val file = java.io.File(dir, "doc-${System.currentTimeMillis()}.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
        target = type.name
        captureUri = uri.toString()
        camera.launch(uri)
    }
    fun openTemplate() {
        val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        val file = java.io.File(dir, tr("Maitri volunteer declaration.pdf"))
        context.assets.open("volunteer-declaration.pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val view = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { context.startActivity(view) }
        catch (_: android.content.ActivityNotFoundException) {
            context.startActivity(android.content.Intent.createChooser(send, tr("Save or print the declaration")))
        }
    }
    val locked = applicationLocked(status?.status)

    if (status == null && statusMessage == null && account != null) {
        Box(Modifier.fillMaxSize().background(p.background), contentAlignment = Alignment.Center) {
            MaitriLoader(message = tr("Checking your application…"))
        }
        return
    }
    MaitriVolunteerContent(
        p = p,
        selectedIntent = selectedIntent,
        onIntentSelect = { intent ->
            selectedIntent = intent
            account?.let { a -> scope.launch { runCatching { `in`.maitri.app.network.BackendClient.api.setVolunteerIntent(
                `in`.maitri.app.network.VolunteerIntentRequestDto(intent), a) } } }
            if (intent == "NOT_NOW") onSkip()
        },
        docs = docs,
        onPickDoc = { type ->
            when {
                locked -> statusMessage = tr("Your application is submitted, so documents can't change now.")
                docs[type]?.state == DocState.UPLOADING -> Unit
                docs[type]?.state == DocState.UPLOADED -> managing = type
                else -> choosingSource = type
            }
        },
        onRemoveDoc = { },
        consentChecked = consentChecked,
        onConsentToggle = {
            if (!locked) {
                consentChecked = !consentChecked
                val value = consentChecked
                account?.let { a -> scope.launch { runCatching { `in`.maitri.app.network.BackendClient.api.setVolunteerConsent(
                    `in`.maitri.app.network.VolunteerConsentRequestDto(value), a) } } }
            }
        },
        onSubmit = {
            val a = account
            when {
                locked -> Unit
                a == null -> statusMessage = tr("Sign in to apply.")
                !consentChecked -> statusMessage = tr("Please accept the volunteer declaration to continue.")
                docs.values.any { it.state != DocState.UPLOADED } -> statusMessage = tr("Upload all 5 documents first.")
                !submitting -> {
                    submitting = true
                    scope.launch {
                        try {
                            `in`.maitri.app.network.BackendClient.api.submitVolunteerApplication(a)
                            refresh()
                            onSubmitSuccess()
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { statusMessage = tr("Couldn't submit right now. Try again.") }
                        finally { submitting = false }
                    }
                }
            }
        },
        statusMessage = statusMessage,
        onTemplate = ::openTemplate,
        banner = applicationBanner(status?.status, status?.reviewerNotes),
        locked = locked
    )

    choosingSource?.let { type ->
        AlertDialog(modifier = Modifier.dialogEnter(), onDismissRequest = { choosingSource = null }, containerColor = p.surface,
            titleContentColor = p.primary, textContentColor = p.secondary,
            title = { Text(type.title) },
            text = { Text(tr("PDF, JPG or PNG, up to 10 MB. Only Maitri reviewers can open it.")) },
            confirmButton = { TextButton(onClick = { choosingSource = null; takePhoto(type) }) { Text(tr("Take photo"), color = p.accent) } },
            dismissButton = { TextButton(onClick = {
                choosingSource = null
                target = type.name
                filePicker.launch(arrayOf("application/pdf", "image/jpeg", "image/png"))
            }) { Text(tr("Choose file"), color = p.accent) } })
    }
    managing?.let { type ->
        AlertDialog(modifier = Modifier.dialogEnter(), onDismissRequest = { managing = null }, containerColor = p.surface,
            titleContentColor = p.primary, textContentColor = p.secondary,
            title = { Text(type.title) },
            text = { Text(tr("Uploaded. Replace it with a new file, or remove it.")) },
            confirmButton = { TextButton(onClick = { managing = null; choosingSource = type }) { Text(tr("Replace"), color = p.accent) } },
            dismissButton = { TextButton(onClick = {
                managing = null
                val id = docs[type]?.fileId
                val a = account
                if (id != null && a != null) scope.launch {
                    try { `in`.maitri.app.network.BackendClient.files.delete(id, a); refresh() }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { statusMessage = `in`.maitri.app.network.uploadErrorMessage(e) }
                }
            }) { Text(tr("Remove"), color = p.secondary) } })
    }
}

@Composable
internal fun MaitriVolunteerContent(
    p: MaitriPalette,
    selectedIntent: String,
    onIntentSelect: (String) -> Unit,
    docs: Map<VolunteerDocType, DocUi>,
    onPickDoc: (VolunteerDocType) -> Unit,
    onRemoveDoc: (VolunteerDocType) -> Unit,
    consentChecked: Boolean,
    onConsentToggle: () -> Unit,
    onSubmit: () -> Unit,
    statusMessage: String?,
    onTemplate: () -> Unit = {},
    banner: Pair<String, String>? = null,
    locked: Boolean = false
) {
    val uploadedCount = docs.values.count { it.state == DocState.UPLOADED }
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
                text = tr("Be there for someone."),
                color = p.primary,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = designBreaks(tr("Join maitri as a volunteer and help your\ncommunity feel safer.")),
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
                        painter = painterResource(R.drawable.maitri_check),
                        contentDescription = null,
                        tint = if (isHelp) p.accent else p.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    FitText(tr("Yes, I'd like to help"), Modifier, if (isHelp) p.accent else p.secondary, 13f,
                        weight = FontWeight.SemiBold)
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
                        text = tr("Not now"),
                        color = if (isNotNow) p.accent else p.secondary,
                        fontSize = 13.sp,
                        fontWeight = if (isNotNow) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Section: Required documents
            Text(
                text = tr("Volunteer verification"),
                color = p.primary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = tr("All 5 documents are required."),
                color = p.secondary,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(14.dp))

            banner?.let { (title, body) ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.card).padding(16.dp)) {
                    Text(title, color = p.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(body, color = p.secondary, fontSize = 12.sp, lineHeight = 16.sp)
                }
                Spacer(Modifier.height(12.dp))
            }

            // 5 Documents Container Card (342 x 296, rx 20)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(p.surface)
            ) {
                VolunteerDocType.entries.forEachIndexed { index, docType ->
                    val doc = docs[docType] ?: DocUi()
                    val isUploaded = doc.state == DocState.UPLOADED

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 58.dp)
                            .clickable { onPickDoc(docType) }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(docType.icon),
                            contentDescription = null,
                            tint = p.accent,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            FitText(tr(docType.title), Modifier, p.primary, 12.5f, weight = FontWeight.Medium)
                            Spacer(Modifier.height(2.dp))
                            FitText(
                                text = when (doc.state) {
                                    DocState.UPLOADED -> if (locked) tr("Uploaded") else tr("Uploaded · tap to replace or remove")
                                    DocState.UPLOADING -> tr("Uploading…")
                                    DocState.FAILED -> tr("Upload failed · tap to retry")
                                    DocState.EMPTY -> tr(docType.subtitle)
                                },
                                modifier = Modifier, color = if (doc.state == DocState.EMPTY) p.secondary else p.accent, size = 10f
                            )
                            if (docType == VolunteerDocType.DECLARATION) Text(
                                text = tr("Download template"),
                                color = p.accent,
                                fontSize = 10.sp,
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                modifier = Modifier.clickable(onClickLabel = tr("Download the declaration template"), onClick = onTemplate)
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
                            if (doc.state == DocState.UPLOADING) CircularProgressIndicator(Modifier.size(16.dp), color = p.accent, strokeWidth = 2.dp)
                            else Icon(
                                painter = when (doc.state) {
                                    DocState.UPLOADED -> painterResource(R.drawable.maitri_check)
                                    DocState.FAILED -> androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Rounded.Refresh)
                                    else -> painterResource(R.drawable.maitri_upload)
                                },
                                contentDescription = when (doc.state) {
                                    DocState.UPLOADED -> tr("Uploaded")
                                    DocState.FAILED -> tr("Retry upload")
                                    else -> tr("Upload")
                                },
                                tint = p.accent,
                                modifier = Modifier.size(if (doc.state == DocState.FAILED) 16.dp else 24.dp)
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
                            tint = p.onAccent,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = designBreaks(tr("I consent to document verification and agree to\nthe Volunteer Declaration.")),
                    color = p.secondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(14.dp))
            // Counts only uploads the server confirmed.
            Text(
                text = tr("{0} of {1} documents uploaded", uploadedCount, VolunteerDocType.entries.size),
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
            val allDocsAttached = uploadedCount == VolunteerDocType.entries.size
            val readyToSubmit = allDocsAttached && consentChecked && !locked

            Button(
                onClick = onSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (readyToSubmit) p.accent else p.card,
                    // Not ready yet: greyed out, as in the design.
                    contentColor = if (readyToSubmit) (if (p.dark) p.background else Color.White) else p.secondary
                ),
                elevation = null
            ) {
                Text(
                    text = if (locked) tr("Submitted") else tr("Submit for review"),
                    fontSize = 15.sp,
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
        docs = emptyMap(),
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
        docs = emptyMap(),
        onPickDoc = {},
        onRemoveDoc = {},
        consentChecked = false,
        onConsentToggle = {},
        onSubmit = {},
        statusMessage = null
    )
}
