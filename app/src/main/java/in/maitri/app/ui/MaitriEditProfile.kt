package `in`.maitri.app.ui

import `in`.maitri.app.tr

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import `in`.maitri.app.R
import `in`.maitri.app.R.string as S
import `in`.maitri.app.data.ProfileLocalStore
import `in`.maitri.app.data.ProfileRules
import `in`.maitri.app.data.UserProfile
import `in`.maitri.app.network.BackendClient
import `in`.maitri.app.network.ExpectedAccount
import `in`.maitri.app.network.MedicalNotesDto
import `in`.maitri.app.network.ProfileDetailsRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException

/** First failed rule for the screen 14 form, or null when it can be saved. */
internal fun profileFormError(name: String, phone: String, city: String, notes: String): Int? = when {
    !ProfileRules.nameValid(name) -> S.edit_name_required
    !ProfileRules.phoneValid(phone) -> S.edit_phone_invalid
    !ProfileRules.cityValid(city) -> S.edit_city_invalid
    !ProfileRules.notesValid(notes) -> S.edit_notes_invalid
    else -> null
}

/** Screen 14. Contact details and the photo are saved to the account; medical notes stay on this device (P6 moves them). */
@Composable
fun MaitriEditProfile(user: UserProfile?, onBack: () -> Unit, modifier: Modifier = Modifier,
                      onProfileUpdated: (UserProfile) -> Unit = {}) {
    val p = maitriPalette()
    val context = LocalContext.current
    val strings = settingsResources()
    val scope = rememberCoroutineScope()
    val preview = LocalInspectionMode.current
    val uid = user?.uid.orEmpty()
    val store = remember(context) { ProfileLocalStore(context) }

    var name by remember(uid) { mutableStateOf(user?.name.orEmpty()) }
    var phone by remember(uid) { mutableStateOf(user?.phone.orEmpty()) }
    var city by remember(uid) { mutableStateOf("") }
    var notes by remember(uid) { mutableStateOf("") }
    var sharePhone by remember(uid) { mutableStateOf(false) }
    var notesNote by remember { mutableStateOf<String?>(null) }
    var changeEmail by remember { mutableStateOf(false) }
    var dirty by remember(uid) { mutableStateOf(false) }
    var loading by remember(uid) { mutableStateOf(!preview && uid.isNotBlank()) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var photo by remember(uid) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(uid) {
        if (preview || uid.isBlank()) return@LaunchedEffect
        // Medical notes live on the server (encrypted) so a responder can see them during an SOS. A copy left on
        // this phone by an older version is moved there once, then removed from the phone.
        val local = withContext(Dispatchers.IO) { runCatching { store.readMedicalNotes(uid) }.getOrDefault("") }
        try {
            val account = ExpectedAccount(uid)
            var server = BackendClient.privacy.notes(account).notes
            if (server.isBlank() && local.isNotBlank()) server = BackendClient.privacy.saveNotes(MedicalNotesDto(local), account).notes
            if (local.isNotBlank()) withContext(Dispatchers.IO) { runCatching { store.writeMedicalNotes(uid, "") } }
            if (!dirty) notes = server
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            if (!dirty) notes = local
        }
        photo = withContext(Dispatchers.IO) {
            store.photo(uid)?.let { file -> runCatching { decodeForSize(file.path, 160)?.asImageBitmap() }.getOrNull() }
        }
        // No copy on this phone yet: fetch the account's photo, if one was set (e.g. on another phone).
        if (photo == null) try {
            BackendClient.files.profilePhoto(ExpectedAccount(uid)).downloadUrl?.let { url ->
                val bytes = `in`.maitri.app.network.Uploads.download(url)
                photo = withContext(Dispatchers.IO) {
                    decodeForSize(store.savePhotoBytes(uid, bytes).path, 160)?.asImageBitmap()
                }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        try {
            val server = BackendClient.api.profile(ExpectedAccount(uid))
            if (!dirty) {
                name = server.name
                phone = server.phoneNumber.orEmpty()
                city = server.city.orEmpty()
                sharePhone = server.sharePhoneInSos
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            // Keep the locally known name and phone; saving will surface any server problem.
        } finally { loading = false }
    }

    var choosePhoto by remember { mutableStateOf(false) }
    var photoNote by remember { mutableStateOf<String?>(null) }
    fun usePhoto(uri: android.net.Uri) {
        if (uid.isBlank()) return
        scope.launch {
            val file = withContext(Dispatchers.IO) { runCatching { store.savePhoto(context, uid, uri) }.getOrNull() }
            val image = file?.let { withContext(Dispatchers.IO) { runCatching { decodeForSize(it.path, 160)?.asImageBitmap() }.getOrNull() } }
            if (image == null || file == null) { message = S.edit_photo_failed; return@launch }
            photo = image
            photoNote = tr("Uploading photo…")
            try {
                val account = ExpectedAccount(uid)
                val id = `in`.maitri.app.network.Uploads.upload(withContext(Dispatchers.IO) { file.readBytes() },
                    "image/jpeg", "PROFILE_PHOTO", account)
                BackendClient.files.setProfilePhoto(`in`.maitri.app.network.PhotoRequestDto(id), account)
                photoNote = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { photoNote = tr("Photo saved on this phone only. ") + `in`.maitri.app.network.uploadErrorMessage(e) }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) usePhoto(uri)
    }
    var captureUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        captureUri?.takeIf { saved }?.let(::usePhoto)
    }
    if (choosePhoto) androidx.compose.material3.AlertDialog(onDismissRequest = { choosePhoto = false },
        containerColor = p.surface, titleContentColor = p.primary, textContentColor = p.secondary,
        title = { Text(strings.getString(S.edit_photo)) },
        text = { Text(tr("Cropped to a square. Only you and responders helping you during an SOS can see it.")) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = {
            choosePhoto = false
            val dir = java.io.File(context.cacheDir, "captures").apply { mkdirs() }
            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files",
                java.io.File(dir, "profile-${System.currentTimeMillis()}.jpg"))
            captureUri = uri
            camera.launch(uri)
        }) { Text(tr("Take photo"), color = p.accent) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = {
            choosePhoto = false
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) { Text(tr("Choose from gallery"), color = p.accent) } })

    fun edit(update: () -> Unit) { update(); dirty = true; message = null }

    fun save() {
        profileFormError(name, phone, city, notes)?.let { message = it; return }
        if (uid.isBlank()) { message = S.edit_session; return }
        saving = true; message = null
        scope.launch {
            try {
                val saved = BackendClient.api.profileDetails(ProfileDetailsRequest(name.trim(), phone.trim(), city.trim(), sharePhone), ExpectedAccount(uid))
                onProfileUpdated(requireNotNull(user).copy(name = saved.name, phone = saved.phoneNumber))
                val notesSaved = try {
                    BackendClient.privacy.saveNotes(MedicalNotesDto(notes.trim()), ExpectedAccount(uid)); true
                } catch (e: CancellationException) { throw e } catch (_: Exception) {
                    // Not lost: kept on this phone and moved to the server next time this screen opens.
                    withContext(Dispatchers.IO) { runCatching { store.writeMedicalNotes(uid, notes) } }
                    false
                }
                if (!notesSaved) { notesNote = tr("Medical notes saved on this phone only for now."); return@launch }
                android.widget.Toast.makeText(context, strings.getString(S.edit_saved), android.widget.Toast.LENGTH_LONG).show()
                onBack()
            } catch (e: CancellationException) { throw e } catch (e: HttpException) {
                message = if (e.code() == 401 || e.code() == 403) S.edit_session
                    else if (e.code() == 422) S.edit_phone_invalid.takeIf { !ProfileRules.phoneValid(phone) } ?: S.edit_save_failed
                    else S.edit_save_failed
            } catch (e: IOException) {
                message = if (e.message == "Account changed" || e.message == "Sign in required") S.edit_session else S.edit_save_failed
            } catch (e: Exception) {
                message = S.edit_save_failed
            } finally { saving = false }
        }
    }

    if (changeEmail) ChangeEmailDialog(p) { changeEmail = false }
    val busy = rememberDelayedBusy(loading, delayMillis = 0)
    Box(modifier.fillMaxSize()) {
    SettingsBoard(if (p.dark) R.drawable.maitri_dark_14 else R.drawable.maitri_light_14, 788, Modifier.blurWhenBusy(busy)) {
        SettingsHeader(strings.getString(S.edit_title), strings.getString(S.settings_profile), strings.getString(S.settings_back), onBack)

        photo?.let {
            Image(it, null, settingsSlot(34, 160, 40, 40).clip(CircleShape).background(p.surface), contentScale = ContentScale.Crop)
        }
        SettingsText(strings.getString(S.edit_photo), 82, 157, 210, 24, 15, p.primary, true)
        SettingsText(photoNote ?: strings.getString(S.edit_photo_sub), 82, 184, 210, 18, 10,
            if (photoNote != null) p.accent else p.secondary, maxLines = 1)
        SettingsText(strings.getString(S.edit_change), 286, 160, 78, 40, 12, p.accent, bold = true, center = true, action = {
            if (!preview) choosePhoto = true
        })

        SettingsText(strings.getString(S.edit_name), 24, 238, 300, 20, 11, p.primary)
        ProfileField(name, { edit { name = it } }, 261, 42, S.edit_name_hint, S.edit_name, "profile_name",
            KeyboardOptions(KeyboardCapitalization.Words, keyboardType = KeyboardType.Text, imeAction = ImeAction.Next))
        SettingsText(strings.getString(S.edit_phone), 24, 306, 300, 20, 11, p.primary)
        ProfileField(phone, { edit { phone = it } }, 329, 42, S.edit_phone_hint, S.edit_phone, "profile_phone",
            KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next))
        SettingsText(strings.getString(S.edit_email), 24, 374, 300, 20, 11, p.primary)
        // The email is the sign-in identity: it changes only after the new address is verified (Firebase).
        // The field itself is the "Change email" button, so screen readers still hear the current address.
        ProfileField(user?.email.orEmpty(), {}, 397, 42, S.edit_email_hint, S.edit_email, "profile_email",
            KeyboardOptions(keyboardType = KeyboardType.Email), readOnly = true,
            onClick = if (preview) null else ({ changeEmail = true }), clickLabel = tr("Change email"))
        SettingsText(strings.getString(S.edit_city), 24, 442, 300, 20, 11, p.primary)
        ProfileField(city, { edit { city = it } }, 465, 42, S.edit_city_hint, S.edit_city, "profile_city",
            KeyboardOptions(KeyboardCapitalization.Words, imeAction = ImeAction.Next))
        SettingsText(strings.getString(S.edit_notes), 24, 535, 170, 20, 12, p.primary)
        // One-line consent note: who can read them.
        SettingsText(notesNote ?: tr("Seen only by a responder helping you"), 190, 537, 176, 20, 10,
            if (notesNote != null) p.accent else p.secondary, maxLines = 1)
        ProfileField(notes, { if (it.length <= ProfileLocalStore.MEDICAL_NOTES_LIMIT) edit { notes = it } }, 564, 72,
            S.edit_notes_hint, S.edit_notes, "profile_notes",
            KeyboardOptions(KeyboardCapitalization.Sentences, imeAction = ImeAction.Default), single = false)

        // Consent for screen 07's "Call": accepted responders may call this number during an active SOS.
        SettingsText((if (sharePhone) "☑ " else "☐ ") + tr("Let responders who accept my SOS call this number"),
            24, 648, 342, 40, 11, if (sharePhone) p.primary else p.secondary, line = 15,
            action = { edit { sharePhone = !sharePhone } })
        val helper = message ?: if (loading) S.edit_loading else S.edit_helper
        SettingsText(strings.getString(helper), 36, 703, 318, 34, 11, if (message != null && message != S.edit_saved) p.accent else p.secondary,
            center = true, line = 15)
        Box(settingsSlot(24, 744, 342, 52).testTag("profile_save").clickable(enabled = !saving && !loading, role = Role.Button, onClick = ::save),
            contentAlignment = Alignment.Center) {
            val onAccent = if (p.dark) p.background else Color.White
            if (saving) CircularProgressIndicator(Modifier.size(20.dp).semantics { contentDescription = strings.getString(S.edit_saving) }, color = onAccent, strokeWidth = 2.dp)
            else Text(strings.getString(S.edit_save), color = onAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    MaitriBusyOverlay(busy, strings.getString(S.edit_loading))
    }
}

/** Native input placed over a field background from the crop (342 wide, text inset 14). */
@Composable
private fun ProfileField(value: String, onValue: (String) -> Unit, y: Int, height: Int, hint: Int, label: Int, tag: String,
                         keyboard: KeyboardOptions, readOnly: Boolean = false, single: Boolean = true,
                         onClick: (() -> Unit)? = null, clickLabel: String? = null) {
    val p = maitriPalette()
    val strings = settingsResources()
    val description = strings.getString(label)
    val readOnlyNote = strings.getString(S.edit_email_readonly)
    val slot = settingsSlot(24, y, 342, height).testTag(tag)
    // A disabled field leaves taps to the click handler; its text stays in the accessibility tree.
    BasicTextField(value, onValue, (if (onClick != null) slot.clickable(role = Role.Button, onClickLabel = clickLabel,
            onClick = onClick) else slot).semantics {
            contentDescription = description
            if (readOnly) stateDescription = readOnlyNote
        },
        enabled = onClick == null, readOnly = readOnly, singleLine = single, maxLines = if (single) 1 else 3, keyboardOptions = keyboard,
        textStyle = TextStyle(fontFamily = `in`.maitri.app.ui.theme.MaitriFont, color = if (readOnly) p.secondary else p.primary, fontSize = 13.sp, lineHeight = 17.sp),
        cursorBrush = SolidColor(p.accent),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = if (single) 0.dp else 13.dp),
                contentAlignment = if (single) Alignment.CenterStart else Alignment.TopStart) {
                if (value.isEmpty()) Text(strings.getString(hint), color = p.secondary, fontSize = 13.sp, lineHeight = 17.sp)
                inner()
            }
        })
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun MaitriEditProfileLightPreview() {
    `in`.maitri.app.ui.theme.MaitriTheme { MaitriEditProfile(user = null, onBack = {}) }
}

@Preview(widthDp = 390, heightDp = 844, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MaitriEditProfileDarkPreview() {
    `in`.maitri.app.ui.theme.MaitriTheme { MaitriEditProfile(user = null, onBack = {}) }
}

/** Firebase "verify before update": the address changes only after the link sent to it is opened. */
@Composable
private fun ChangeEmailDialog(p: MaitriPalette, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val user = remember { runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser }.getOrNull() }
    val google = user?.providerData?.any { it.providerId == com.google.firebase.auth.GoogleAuthProvider.PROVIDER_ID } == true
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(onDismissRequest = dismiss,
        containerColor = p.surface, titleContentColor = p.primary, textContentColor = p.secondary,
        title = { Text(tr("Change email")) },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                if (google) Text(tr("You sign in with Google, so your email comes from your Google account. To use another ") +
                    tr("address, sign out and sign in with that Google account."))
                else {
                    Text(tr("We'll send a link to the new address. Your email changes when you open it."))
                    androidx.compose.material3.OutlinedTextField(email, { email = it.trim() }, singleLine = true,
                        label = { Text(tr("New email")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    androidx.compose.material3.OutlinedTextField(password, { password = it }, singleLine = true,
                        label = { Text(tr("Current password")) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                }
                result?.let { Text(it, color = p.accent) }
            }
        },
        confirmButton = {
            if (!google) androidx.compose.material3.TextButton(
                enabled = !busy && android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() && password.isNotBlank(),
                onClick = {
                    val u = user ?: return@TextButton
                    busy = true
                    scope.launch {
                        result = try {
                            u.reauthenticate(com.google.firebase.auth.EmailAuthProvider.getCredential(u.email.orEmpty(), password)).await()
                            u.verifyBeforeUpdateEmail(email).await()
                            tr("Check {0} for the link. Until you open it, you still sign in with your current email.", email)
                        } catch (e: CancellationException) { throw e }
                        catch (_: com.google.firebase.auth.FirebaseAuthInvalidCredentialsException) { tr("That password isn't right, or the email isn't valid.") }
                        catch (_: com.google.firebase.auth.FirebaseAuthUserCollisionException) { tr("That email already has a Maitri account.") }
                        catch (_: Exception) { tr("Couldn't send the link. Check your connection and try again.") }
                        busy = false
                        password = ""
                    }
                }) { Text(tr("Send link"), color = p.accent) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = dismiss) { Text(tr("Close"), color = p.secondary) } })
}

/** Decodes an image file at about [targetPx] on its longer side (the profile photo shows at 40 dp), not full size. */
internal fun decodeForSize(path: String, targetPx: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}
