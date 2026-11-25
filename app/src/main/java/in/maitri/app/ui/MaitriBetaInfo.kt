package `in`.maitri.app.ui

import `in`.maitri.app.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Help & support › Beta info: the short version of TESTER_GUIDE.md. */
internal fun betaSections() = listOf(
    tr("Maitri doesn't replace 112") to tr("Maitri alerts your circle and nearby volunteers. It never calls the police or an ambulance. If you're in danger, call 112."),
    tr("Testing an SOS safely") to tr("Tell your circle before you test. Hold SOS for 3 seconds, check that they got it, then end it on the SOS screen with your Safety PIN. You can send at most 3 SOS an hour."),
    tr("Known limits") to tr("• The first request after a quiet spell can take up to a minute while the server wakes up; your SOS keeps retrying.\n") +
        tr("• Contacts without Maitri get an SMS only when you tap Send in your messaging app.\n") +
        tr("• Missed check-ins reach only circle members who have Maitri.\n") +
        tr("• Trip sharing updates while Maitri is open.\n") +
        tr("• Volunteers only get requests after a reviewer approves them.\n") +
        tr("• Translations are machine-drafted and still being checked."),
    tr("Reporting a problem") to tr("Tap Send feedback below. It opens an email with the app and phone versions; add what happened. Please don't include passwords, PINs or other people's details."),
)

@Composable
fun MaitriBetaInfo(onBack: () -> Unit) {
    val p = maitriPalette()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(p.background).statusBarsPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp).background(p.surface, CircleShape)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back"), tint = p.primary)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(tr("HELP & SUPPORT"), color = p.accent, fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.SemiBold)
                Text(tr("Beta info"), color = p.primary, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(tr("Version {0}", `in`.maitri.app.BuildConfig.VERSION_NAME), color = p.secondary, fontSize = 12.sp)
        if (`in`.maitri.app.BuildConfig.CRASH_TEST) CrashTestButton()
        Spacer(Modifier.height(12.dp))
        betaSections().forEachIndexed { index, (title, body) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)
                .background(if (index == 0) p.card else p.surface, RoundedCornerShape(20.dp)).padding(16.dp)) {
                Text(title, color = p.primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(body, color = p.secondary, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { runCatching { context.startActivity(supportEmailIntent("Maitri beta feedback")) } },
            colors = ButtonDefaults.buttonColors(containerColor = p.accent), modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(tr("Send feedback"), fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(LocalFloatingNavBarPadding.current))
    }
}

/** Developer builds only (-PmaitriCrashTest=true): closes the app with a crash, so Crashlytics gets a report. */
@Composable
private fun CrashTestButton() {
    val context = LocalContext.current
    var confirm by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { confirm = true }) { Text("Send test crash report") }
    if (confirm) AlertDialog(modifier = Modifier.dialogEnter(), 
        onDismissRequest = { confirm = false },
        title = { Text("Send test crash report?") },
        text = {
            Text(if (`in`.maitri.app.CrashReports.enabled(context))
                "Maitri will close with a test crash. Open it again and the report reaches Crashlytics within a few minutes."
            else "Crash reports are off in Privacy & data, so this report won't be sent.")
        },
        confirmButton = {
            TextButton(onClick = { throw RuntimeException("Maitri test crash (developer build)") }) { Text("Crash now") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}
