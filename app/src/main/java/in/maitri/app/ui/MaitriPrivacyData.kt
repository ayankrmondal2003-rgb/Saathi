package `in`.maitri.app.ui

import `in`.maitri.app.tr

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import `in`.maitri.app.network.BackendClient
import `in`.maitri.app.network.ExpectedAccount
import `in`.maitri.app.network.MaitriLinks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.File

internal fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal fun openLink(context: Context, url: String) =
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

/** "Email support" with the app and device versions filled in, and nothing personal. */
internal fun supportEmailIntent(subject: String, versionName: String = `in`.maitri.app.BuildConfig.VERSION_NAME): Intent =
    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + MaitriLinks.SUPPORT_EMAIL)).apply {
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, "\n\n---\nMaitri $versionName · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}")
    }

/** Words for a refused account deletion. */
internal fun deleteErrorMessage(error: Throwable): String {
    val code = (error as? HttpException)?.let { e ->
        runCatching {
            com.google.gson.JsonParser.parseString(e.response()?.errorBody()?.string()).asJsonObject["detail"].asJsonObject["code"].asString
        }.getOrNull()
    }
    return when (code) {
        "SOS_OPEN" -> tr("End your SOS with your Safety PIN first, then delete your account.")
        "REAUTH_REQUIRED" -> tr("Sign in again to confirm, then try once more.")
        "STORAGE_UNAVAILABLE", "STORAGE_NOT_CONFIGURED", "IDENTITY_UNAVAILABLE" -> tr("Maitri couldn't finish deleting right now. Nothing was deleted; try again later.")
        else -> if (error is HttpException) tr("Your account couldn't be deleted. Try again.") else tr("Couldn't reach Maitri. Check your connection.")
    }
}

/** Screen 09 › Privacy & data: export, delete, and the policy pages. */
@Composable
fun MaitriPrivacyData(onBack: () -> Unit, onDeleted: () -> Unit) {
    val p = maitriPalette()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user = remember { runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull() }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var askPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }

    fun export() {
        val uid = user?.uid ?: return
        busy = true; message = null
        scope.launch {
            try {
                val bytes = BackendClient.privacy.export(ExpectedAccount(uid)).bytes()
                val file = withContext(Dispatchers.IO) {
                    File(File(context.cacheDir, "shared").apply { mkdirs() }, "maitri-data.json").also { it.writeBytes(bytes) }
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Save your Maitri data"))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = tr("Couldn't download your data. Check your connection.") }
            finally { busy = false }
        }
    }
    suspend fun reauthenticate(u: FirebaseUser) {
        val google = u.providerData.any { it.providerId == GoogleAuthProvider.PROVIDER_ID }
        val credential = if (google) {
            val activity = context.findActivity() ?: throw IllegalStateException("No window")
            GoogleAuthProvider.getCredential(`in`.maitri.app.data.requestGoogleIdToken(activity), null)
        } else EmailAuthProvider.getCredential(u.email.orEmpty(), password)
        u.reauthenticate(credential).await()
        u.getIdToken(true).await()  // a fresh token carries the new sign-in time the server checks
    }
    fun delete() {
        val u = user ?: return
        busy = true; message = null
        scope.launch {
            try {
                reauthenticate(u)
                val response = BackendClient.privacy.deleteAccount(ExpectedAccount(u.uid))
                if (!response.isSuccessful) throw HttpException(response)
                // Nothing of this account stays on the phone either.
                withContext(Dispatchers.IO) {
                    File(context.filesDir, "sos-media").deleteRecursively()
                    File(context.cacheDir, "shared").deleteRecursively()
                    File(context.cacheDir, "captures").deleteRecursively()
                }
                runCatching { FirebaseAuth.getInstance().signOut() }
                onDeleted()
            } catch (e: CancellationException) { throw e }
            catch (e: com.google.firebase.auth.FirebaseAuthInvalidCredentialsException) { message = tr("That password isn't right.") }
            catch (e: `in`.maitri.app.data.GoogleSignInUnavailable) { message = e.userMessage }
            catch (e: Exception) { message = deleteErrorMessage(e) }
            finally { busy = false; password = "" }
        }
    }

    Column(Modifier.fillMaxSize().background(p.background).statusBarsPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp).background(p.surface, CircleShape)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back"), tint = p.primary)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(tr("PROFILE & SETTINGS"), color = p.accent, fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.SemiBold)
                Text(tr("Privacy & data"), color = p.primary, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(20.dp))
        Column(Modifier.fillMaxWidth().background(p.card, RoundedCornerShape(24.dp)).padding(18.dp)) {
            Text(tr("Stay in control of your information."), color = p.primary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(tr("Maitri keeps SOS locations for 7 days after an SOS ends and SOS photos and voice notes for 30 days. ") +
                tr("Medical notes are encrypted and shown only to a responder helping you."), color = p.secondary, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.height(16.dp))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent)
        message?.let { Text(it, color = p.accent, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)) }
        PrivacyRow(p, tr("Export my data"), tr("Download everything Maitri keeps about you, as a file")) { if (!busy) export() }
        // Crash reports (release builds): stack trace, app version and phone model only.
        var crashReports by remember { mutableStateOf(`in`.maitri.app.CrashReports.enabled(context)) }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(p.surface, RoundedCornerShape(18.dp))
            .clickable { crashReports = !crashReports; `in`.maitri.app.CrashReports.set(context, crashReports) }
            .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Send crash reports"), color = p.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(tr("Helps fix problems. Only the error, app version and phone model, never your location or details."),
                    color = p.secondary, fontSize = 11.sp)
            }
            Switch(checked = crashReports, onCheckedChange = { crashReports = it; `in`.maitri.app.CrashReports.set(context, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = p.accent))
        }
        PrivacyRow(p, tr("Privacy policy"), tr("What we collect, why, and who can see it")) { openLink(context, MaitriLinks.PRIVACY) }
        PrivacyRow(p, tr("Terms of service"), tr("How Maitri works during the beta")) { openLink(context, MaitriLinks.TERMS) }
        PrivacyRow(p, tr("Permissions"), tr("Location, notifications and microphone")) {
            context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)))
        }
        PrivacyRow(p, tr("Delete my account"), tr("Remove your account and data from Maitri"), danger = true) { if (!busy) confirmDelete = true }
        Spacer(Modifier.height(LocalFloatingNavBarPadding.current))
    }

    if (confirmDelete) AlertDialog(modifier = Modifier.dialogEnter(), onDismissRequest = { confirmDelete = false },
        containerColor = p.surface, titleContentColor = p.primary, textContentColor = p.secondary,
        title = { Text(tr("Delete your account?")) },
        text = { Text(tr("This deletes your profile, circle, contacts, SOS history, photos, voice notes, volunteer documents and ") +
            tr("your sign-in. It can't be undone. You'll be asked to sign in again to confirm.")) },
        confirmButton = { TextButton(onClick = {
            confirmDelete = false
            val google = user?.providerData?.any { it.providerId == GoogleAuthProvider.PROVIDER_ID } == true
            if (google) delete() else askPassword = true
        }) { Text(tr("Delete"), color = p.accent, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel"), color = p.secondary) } })
    if (askPassword) AlertDialog(modifier = Modifier.dialogEnter(), onDismissRequest = { askPassword = false },
        containerColor = p.surface, titleContentColor = p.primary, textContentColor = p.secondary,
        title = { Text(tr("Confirm it's you")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Enter the password for {0}.", user?.email.orEmpty()))
            OutlinedTextField(password, { password = it }, singleLine = true, label = { Text(tr("Password")) },
                visualTransformation = PasswordVisualTransformation())
        } },
        confirmButton = { TextButton(enabled = password.isNotBlank(), onClick = { askPassword = false; delete() }) {
            Text(tr("Delete my account"), color = p.accent) } },
        dismissButton = { TextButton(onClick = { askPassword = false; password = "" }) { Text(tr("Cancel"), color = p.secondary) } })
}

@Composable
private fun PrivacyRow(p: MaitriPalette, title: String, detail: String, danger: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(p.surface, RoundedCornerShape(18.dp))
        .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(title, color = if (danger) p.accent else p.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(detail, color = p.secondary, fontSize = 11.sp)
    }
}
