package `in`.maitri.app.ui

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
import `in`.maitri.app.network.ProfileDetailsRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    var dirty by remember(uid) { mutableStateOf(false) }
    var loading by remember(uid) { mutableStateOf(!preview && uid.isNotBlank()) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var photo by remember(uid) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(uid) {
        if (preview || uid.isBlank()) return@LaunchedEffect
        val savedNotes = withContext(Dispatchers.IO) { runCatching { store.readMedicalNotes(uid) } }
        savedNotes.onSuccess { if (!dirty) notes = it }.onFailure { message = S.edit_notes_failed }
        photo = withContext(Dispatchers.IO) {
            store.photo(uid)?.let { file -> runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull() }
        }
        // No copy on this phone yet: fetch the account's photo, if one was set (e.g. on another phone).
        if (photo == null) try {
            BackendClient.files.profilePhoto(ExpectedAccount(uid)).downloadUrl?.let { url ->
                val bytes = `in`.maitri.app.network.Uploads.download(url)
                photo = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(store.savePhotoBytes(uid, bytes).path)?.asImageBitmap()
                }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        try {
            val server = BackendClient.api.profile(ExpectedAccount(uid))
            if (!dirty) {
                name = server.name
                phone = server.phoneNumber.orEmpty()
                city = server.city.orEmpty()
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
            val image = file?.let { withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull() } }
            if (image == null || file == null) { message = S.edit_photo_failed; return@launch }
            photo = image
            photoNote = "Uploading photo…"
            try {
                val account = ExpectedAccount(uid)
                val id = `in`.maitri.app.network.Uploads.upload(withContext(Dispatchers.IO) { file.readBytes() },
                    "image/jpeg", "PROFILE_PHOTO", account)
                BackendClient.files.setProfilePhoto(`in`.maitri.app.network.PhotoRequestDto(id), account)
                photoNote = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { photoNote = "Photo saved on this phone only. " + `in`.maitri.app.network.uploadErrorMessage(e) }
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
        text = { Text("Cropped to a square. Only you and responders helping you during an SOS can see it.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = {
            choosePhoto = false
            val dir = java.io.File(context.cacheDir, "captures").apply { mkdirs() }
            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files",
                java.io.File(dir, "profile-${System.currentTimeMillis()}.jpg"))
            captureUri = uri
            camera.launch(uri)
        }) { Text("Take photo", color = p.accent) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = {
            choosePhoto = false
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) { Text("Choose from gallery", color = p.accent) } })

    fun edit(update: () -> Unit) { update(); dirty = true; message = null }

    fun save() {
        profileFormError(name, phone, city, notes)?.let { message = it; return }
        if (uid.isBlank()) { message = S.edit_session; return }
        saving = true; message = null
        scope.launch {
            try {
                val saved = BackendClient.api.profileDetails(ProfileDetailsRequest(name.trim(), phone.trim(), city.trim()), ExpectedAccount(uid))
                onProfileUpdated(requireNotNull(user).copy(name = saved.name, phone = saved.phoneNumber))
                val notesSaved = withContext(Dispatchers.IO) { runCatching { store.writeMedicalNotes(uid, notes) }.isSuccess }
                if (!notesSaved) { message = S.edit_notes_failed; return@launch }
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

    SettingsBoard(if (p.dark) R.drawable.maitri_dark_14 else R.drawable.maitri_light_14, 788, modifier) {
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
        ProfileField(user?.email.orEmpty(), {}, 397, 42, S.edit_email_hint, S.edit_email, "profile_email",
            KeyboardOptions(keyboardType = KeyboardType.Email), readOnly = true)
        SettingsText(strings.getString(S.edit_city), 24, 442, 300, 20, 11, p.primary)
        ProfileField(city, { edit { city = it } }, 465, 42, S.edit_city_hint, S.edit_city, "profile_city",
            KeyboardOptions(KeyboardCapitalization.Words, imeAction = ImeAction.Next))
        SettingsText(strings.getString(S.edit_notes), 24, 535, 320, 20, 12, p.primary)
        ProfileField(notes, { if (it.length <= ProfileLocalStore.MEDICAL_NOTES_LIMIT) edit { notes = it } }, 564, 72,
            S.edit_notes_hint, S.edit_notes, "profile_notes",
            KeyboardOptions(KeyboardCapitalization.Sentences, imeAction = ImeAction.Default), single = false)

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
}

/** Native input placed over a field background from the crop (342 wide, text inset 14). */
@Composable
private fun ProfileField(value: String, onValue: (String) -> Unit, y: Int, height: Int, hint: Int, label: Int, tag: String,
                         keyboard: KeyboardOptions, readOnly: Boolean = false, single: Boolean = true) {
    val p = maitriPalette()
    val strings = settingsResources()
    val description = strings.getString(label)
    val readOnlyNote = strings.getString(S.edit_email_readonly)
    BasicTextField(value, onValue, settingsSlot(24, y, 342, height).testTag(tag).semantics {
            contentDescription = description
            if (readOnly) stateDescription = readOnlyNote
        },
        readOnly = readOnly, singleLine = single, maxLines = if (single) 1 else 3, keyboardOptions = keyboard,
        textStyle = TextStyle(color = if (readOnly) p.secondary else p.primary, fontSize = 13.sp, lineHeight = 17.sp),
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
