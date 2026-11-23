package com.example.surakshanet.ui

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
import com.example.surakshanet.R
import com.example.surakshanet.network.BackendClient
import com.example.surakshanet.network.DecisionDto
import com.example.surakshanet.network.ExpectedAccount
import com.google.firebase.auth.FirebaseAuth
import com.example.surakshanet.network.canOpenIncoming
import com.example.surakshanet.network.confirmedInvitationResponse
import com.example.surakshanet.network.invitationFailure
import kotlinx.coroutines.launch

/**
 * Screen 06 — Incoming SOS Request Dialog.
 * Strict SVG fidelity matching design-reference/crops/light-06.svg and dark-06.svg.
 */
@Composable
fun MaitriIncoming(
    invitation: com.example.surakshanet.network.InvitationDto,
    uid: String,
    approxLocality: String = "Nearby emergency",
    approxDistance: String = "Distance unavailable",
    estimatedEta: String = "Arrival time unavailable",
    onAccepted: (incidentId: String) -> Unit,
    onDeclined: () -> Unit
) {
    val p = maitriPalette()
    val scope = rememberCoroutineScope()
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun submitDecision(decision: String) {
        if (isSubmitting) return
        if (!invitation.canOpenIncoming()) {
            errorMessage = "Invalid invitation ID."
            return
        }

        val currentUser = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
        if (currentUser?.uid != uid) {
            errorMessage = "Sign in required to respond to SOS requests."
            return
        }

        isSubmitting = true
        errorMessage = null

        scope.launch {
            try {
                // Only a server-confirmed decision changes screens; EXPIRED is reported, never treated as success.
                val status = confirmedInvitationResponse(decision) {
                    BackendClient.api.respond(invitation.id, DecisionDto(decision), ExpectedAccount(uid))
                }
                when {
                    status == "EXPIRED" -> errorMessage = "This emergency invitation has expired."
                    decision == "ACCEPTED" -> onAccepted(invitation.incidentId)
                    else -> onDeclined()
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

    MaitriIncomingContent(
        p = p,
        approxLocality = approxLocality,
        approxDistance = approxDistance,
        estimatedEta = estimatedEta,
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
        "Someone needs help. Only accept if you can respond safely.", 816) {
        BoardLabel(86, 410, 252, approxLocality, p.primary, 15)
        BoardLabel(86, 434, 252, "Location private until accepted", p.secondary)
        BoardLabel(54, 466, 154, estimatedEta, p.accent)
        BoardLabel(215, 466, 132, approxDistance, p.primary)
        BoardAction(40, 548, 148, 48, "Accept", !isSubmitting, onAccept)
        BoardAction(202, 548, 148, 48, "Decline", !isSubmitting, onDecline)
    }
    if (errorMessage != null) androidx.compose.material3.AlertDialog(
        onDismissRequest = dismissError, title = { Text("Invitation update") },
        text = { Text(errorMessage) }, confirmButton = {
            androidx.compose.material3.TextButton(onClick = dismissError) { Text("Close") }
        })
}
@Preview(name = "Screen 06 — Incoming SOS Light", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun MaitriIncomingLightPreview() {
    MaitriIncomingContent(
        p = MaitriPalette(dark = false),
        approxLocality = "Nearby emergency",
        approxDistance = "Distance unavailable",
        estimatedEta = "ETA unavailable",
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
        approxLocality = "Nearby emergency",
        approxDistance = "Distance unavailable",
        estimatedEta = "ETA unavailable",
        isSubmitting = false,
        errorMessage = null,
        onAccept = {},
        onDecline = {}
    )
}
