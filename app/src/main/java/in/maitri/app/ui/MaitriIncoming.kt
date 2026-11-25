package `in`.maitri.app.ui

import `in`.maitri.app.tr

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import `in`.maitri.app.R
import `in`.maitri.app.network.BackendClient
import `in`.maitri.app.network.DecisionDto
import `in`.maitri.app.network.ExpectedAccount
import com.google.firebase.auth.FirebaseAuth
import `in`.maitri.app.network.canOpenIncoming
import `in`.maitri.app.network.confirmedInvitationResponse
import `in`.maitri.app.network.invitationFailure
import kotlinx.coroutines.launch

/**
 * Screen 06 — Incoming SOS Request Dialog.
 * Strict SVG fidelity matching design-reference/crops/light-06.svg and dark-06.svg.
 */
@Composable
fun MaitriIncoming(
    invitation: `in`.maitri.app.network.InvitationDto,
    uid: String,
    approxLocality: String = tr("Nearby emergency"),
    approxDistance: String = tr("Distance unavailable"),
    estimatedEta: String = tr("Arrival time unavailable"),
    onAccepted: (incidentId: String) -> Unit,
    onDeclined: () -> Unit
) {
    val p = maitriPalette()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun submitDecision(decision: String) {
        if (isSubmitting) return
        if (!invitation.canOpenIncoming()) {
            errorMessage = tr("Invalid invitation ID.")
            return
        }

        val currentUser = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
        if (currentUser?.uid != uid) {
            errorMessage = tr("Sign in required to respond to SOS requests.")
            return
        }

        isSubmitting = true
        errorMessage = null

        scope.launch {
            try {
                // Accepting needs a location under 2 minutes old inside the alert radius: send the current one first.
                if (decision == "ACCEPTED") `in`.maitri.app.network.ResponderLocation.reportNow(context, uid)
                // Only a server-confirmed decision changes screens; EXPIRED is reported, never treated as success.
                val status = confirmedInvitationResponse(decision) {
                    BackendClient.api.respond(invitation.id, DecisionDto(decision), ExpectedAccount(uid))
                }
                when {
                    status == "EXPIRED" -> {
                        `in`.maitri.app.notification.SosRequestAlerts.remove(context, invitation.id)
                        errorMessage = tr("This emergency invitation has expired.")
                    }
                    decision == "ACCEPTED" -> {
                        `in`.maitri.app.notification.SosRequestAlerts.answered(context, invitation.id, accepted = true)
                        onAccepted(invitation.incidentId)
                    }
                    else -> {
                        `in`.maitri.app.notification.SosRequestAlerts.answered(context, invitation.id, accepted = false)
                        onDeclined()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                // Shared mapping uses the server's detail (e.g. outside radius vs unavailable vs incident inactive).
                errorMessage = invitationFailure(e)
            } finally {
                isSubmitting = false
            }
        }
    }

    // The person is looking at the request: the alert stops ringing. Accept tapped outside the app: accept now.
    LaunchedEffect(invitation.id) {
        `in`.maitri.app.notification.SosRequestAlerts.silence(context, invitation.id)
        if (`in`.maitri.app.notification.SosRequestAlerts.consumeAutoAccept(invitation.id)) submitDecision("ACCEPTED")
    }
    // Expiry countdown; the server sends only a distance band before acceptance, never a place or coordinates.
    val expiresAt = remember(invitation.expiresAt) { runCatching { java.time.Instant.parse(invitation.expiresAt) }.getOrNull() }
    var secondsLeft by remember { mutableLongStateOf(Long.MAX_VALUE) }
    LaunchedEffect(expiresAt) {
        while (expiresAt != null) {
            secondsLeft = java.time.Duration.between(java.time.Instant.now(), expiresAt).seconds.coerceAtLeast(0)
            if (secondsLeft == 0L) break
            kotlinx.coroutines.delay(1000)
        }
    }
    val expired = expiresAt != null && secondsLeft == 0L
    MaitriIncomingContent(
        p = p,
        approxLocality = if (approxLocality == "Nearby emergency") tr("Nearby emergency") else approxLocality,
        approxDistance = invitation.distanceBand ?: approxDistance,
        estimatedEta = when {
            expired -> tr("Request expired")
            expiresAt != null -> "Answer within %d:%02d".format(secondsLeft / 60, secondsLeft % 60)
            else -> estimatedEta
        },
        isSubmitting = isSubmitting,
        errorMessage = errorMessage,
        onAccept = { submitDecision("ACCEPTED") },
        onDecline = { submitDecision("DECLINED") },
        dismissError = { errorMessage = null }
    )
}

@Composable
internal fun MaitriIncomingContent(
    p: MaitriPalette,
    approxLocality: String,
    approxDistance: String,
    estimatedEta: String,
    isSubmitting: Boolean,
    errorMessage: String?,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    dismissError: () -> Unit = {}
) {
    EmergencyBoard(if (p.dark) R.drawable.maitri_dark_06 else R.drawable.maitri_light_06,
        tr("Someone needs help. Only accept if you can respond safely."), 816) {
        // Behind the dimmed backdrop: the Responder tab's header and tab bar, as in the design.
        ArtText(tr("NEARBY SUPPORT"), 84f, 71.1f, 270f, 10f, scrimmed(p.accent, p.dark), FontWeight.SemiBold,
            letterSpacing = 1.2.sp)
        ArtText(tr("Responder"), 84f, 108f, 270f, 26f, scrimmed(p.primary, p.dark), FontWeight.SemiBold)
        listOf(tr("Home") to 67f, tr("Circle") to 152.3f, tr("Responder") to 238f, tr("Profile") to 323.5f).forEach { (label, cx) ->
            val selected = cx == 238f
            ArtText(label, cx - 45f, 807.1f, 90f, 10f, scrimmed(if (selected) p.accent else p.secondary, p.dark),
                if (selected) FontWeight.SemiBold else FontWeight.Normal, center = true)
        }
        // The request card.
        ArtText(tr("NEARBY SOS REQUEST"), 40f, 313.6f, 310f, 11f, p.accent, FontWeight.SemiBold, center = true)
        ArtText(tr("Someone needs help"), 40f, 349.7f, 310f, 21f, p.primary, FontWeight.SemiBold, center = true)
        ArtText(tr("Someone nearby is asking for help."), 40f, 375f, 310f, 12f, p.secondary, center = true)
        ArtText(tr("Only accept if you can respond safely."), 40f, 521.9f, 310f, 11f, p.secondary, center = true)
        ArtText(tr("Accept"), 40f, 577f, 148f, 14f, if (p.dark) p.background else Color.White, FontWeight.SemiBold, center = true)
        ArtText(tr("Decline"), 202f, 577.1f, 148f, 14f, p.accent, FontWeight.SemiBold, center = true)
        // The card's rows are 24 units apart: each label keeps to its row at any text size.
        BoardLabel(86, 410, 252, approxLocality, p.primary, 15, height = 26, maxLines = 1)
        BoardLabel(86, 434, 252, tr("Location private until accepted"), p.secondary, height = 18, maxLines = 1)
        BoardLabel(54, 466, 154, estimatedEta, p.accent, height = 18, maxLines = 1)
        BoardLabel(215, 466, 132, approxDistance, p.primary, height = 18, maxLines = 1)
        BoardAction(40, 548, 148, 48, tr("Accept"), !isSubmitting, onAccept)
        BoardAction(202, 548, 148, 48, tr("Decline"), !isSubmitting, onDecline)
    }
    if (errorMessage != null) androidx.compose.material3.AlertDialog(
        onDismissRequest = dismissError, title = { Text(tr("Invitation update")) },
        text = { Text(errorMessage) }, confirmButton = {
            androidx.compose.material3.TextButton(onClick = dismissError) { Text(tr("Close")) }
        })
}
@Preview(name = "Screen 06 — Incoming SOS Light", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun MaitriIncomingLightPreview() {
    MaitriIncomingContent(
        p = MaitriPalette(dark = false),
        approxLocality = tr("Nearby emergency"),
        approxDistance = tr("Distance unavailable"),
        estimatedEta = tr("ETA unavailable"),
        isSubmitting = false,
        errorMessage = null,
        onAccept = {},
        onDecline = {}
    )
}

@Preview(name = "Screen 06 — Incoming SOS Dark", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun MaitriIncomingDarkPreview() {
    MaitriIncomingContent(
        p = MaitriPalette(dark = true),
        approxLocality = tr("Nearby emergency"),
        approxDistance = tr("Distance unavailable"),
        estimatedEta = tr("ETA unavailable"),
        isSubmitting = false,
        errorMessage = null,
        onAccept = {},
        onDecline = {}
    )
}
