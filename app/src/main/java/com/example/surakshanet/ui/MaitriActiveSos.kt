package com.example.surakshanet.ui

import android.os.SystemClock
import com.example.surakshanet.R
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.surakshanet.data.ContactAlertStatus
import com.example.surakshanet.data.ContactDeliveryStatus
import com.example.surakshanet.data.EmergencyContact
import com.example.surakshanet.data.SosIncident
import com.example.surakshanet.location.LocalTrackingRepository
import com.example.surakshanet.network.BackendClient
import com.example.surakshanet.network.MISSING_PRIMARY_PIN
import com.example.surakshanet.network.pinFailure
import com.example.surakshanet.network.ExpectedAccount
import com.example.surakshanet.network.IncidentResolutionRequestDto
import com.example.surakshanet.network.IncidentSnapshotDto
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * Screen 08 — Active SOS Screen.
 * Strict SVG fidelity matching design-reference/crops/light-08.svg and dark-08.svg.
 */
@Composable
fun MaitriActiveSos(
    incident: SosIncident,
    uid: String,
    incidentId: String,
    onAccessLost: () -> Unit,
    onEndSos: () -> Unit,
    onSetPin: () -> Unit = {}
) {
    val p = maitriPalette()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val tracking = remember(context) { LocalTrackingRepository.get(context) }
    var snapshot by remember(uid, incident.started) { mutableStateOf<IncidentSnapshotDto?>(null) }
    var circleAlerted by rememberSaveable(uid, incidentId) { mutableStateOf(false) }
    var showPinDialog by rememberSaveable { mutableStateOf(false) }
    var pinValue by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var isVerifyingPin by remember { mutableStateOf(false) }

    var delivery by remember(uid, incident.started) { mutableStateOf<List<com.example.surakshanet.network.ContactDeliveryDto>?>(null) }
    var updated by remember { mutableStateOf(false) }
    fun openCapture(action: String) {
        try { context.startActivity(android.content.Intent(action)) }
        catch (_: android.content.ActivityNotFoundException) { Toast.makeText(context, "No capture app available", Toast.LENGTH_SHORT).show() }
        catch (_: SecurityException) { Toast.makeText(context, "Capture permission unavailable", Toast.LENGTH_SHORT).show() }
    }

    // Poll live incident snapshot every 5s while active
    LaunchedEffect(uid, incident.started) {
        while (true) {
            val id = tracking.sync.getActiveIncidentId(uid)
            if (id == incidentId) {
                try {
                    val confirmed = BackendClient.api.snapshot(id, ExpectedAccount(uid))
                    check(confirmed.incidentId == incidentId && tracking.sync.getActiveIncidentId(uid) == incidentId)
                    snapshot = confirmed
                    if (confirmed.status == "CLOSED") {
                        tracking.sync.clearActiveIncident(uid)
                        onEndSos()
                        break
                    }
                    if (confirmed.status != "ACTIVE" || confirmed.requesterMembership?.role != "OWNER") {
                        onAccessLost()
                        break
                    }
                    // Alert the server-side circle once the owner's incident is confirmed; retried until it succeeds.
                    if (!circleAlerted) {
                        BackendClient.api.alertCircle(id, ExpectedAccount(uid))
                        circleAlerted = true
                    }
                    delivery = BackendClient.api.contactDelivery(id, ExpectedAccount(uid))
                    updated = true
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    updated = false
                    if (e is HttpException && e.code() in listOf(401, 403, 404, 410)) {
                        onAccessLost()
                        break
                    }
                }
            } else {
                onAccessLost()
                break
            }
            delay(5000)
        }
    }

    // Elapsed timer calculation
    val elapsedSeconds by produceState(0L, incident.started) {
        while (true) {
            value = (SystemClock.elapsedRealtime() - incident.started) / 1000
            delay(500)
        }
    }
    val timerString = remember(elapsedSeconds) {
        val mins = elapsedSeconds / 60
        val secs = elapsedSeconds % 60
        String.format("%02d:%02d", mins, secs)
    }

    // End SOS via PIN Resolution
    fun verifyPinAndEnd() {
        if (pinValue.length != 6) {
            pinError = "PIN must be 6 digits."
            return
        }

        isVerifyingPin = true
        pinError = null

        scope.launch {
            try {
                val activeId = tracking.sync.getActiveIncidentId(uid)
                if (activeId != incidentId) {
                    pinError = "Unable to confirm this incident."
                    return@launch
                }
                val result = BackendClient.api.resolveIncident(
                    id = activeId,
                    request = IncidentResolutionRequestDto(pin = pinValue, reason = "USER_RESOLVED"),
                    expected = ExpectedAccount(uid)
                )
                if (tracking.sync.getActiveIncidentId(uid) != incidentId || result.incidentId != activeId || result.status != "CLOSED" || result.resolutionStatus != "CONFIRMED") {
                    pinError = "Closure has not been confirmed."
                    return@launch
                }
                tracking.sync.clearActiveIncident(uid)
                showPinDialog = false
                Toast.makeText(context, "Incident safely resolved.", Toast.LENGTH_SHORT).show()
                onEndSos()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Shared mapping keeps closure, enrollment and lockout messages consistent with screen 13.
                pinError = pinFailure(e)
            } finally {
                isVerifyingPin = false
            }
        }
    }

    EmergencyBoard(if (p.dark) R.drawable.maitri_dark_08 else R.drawable.maitri_light_08,
        "SOS is active. Circle notified.") {
        val location = snapshot?.lastLocation
        BoardLabel(24, 98, 330, if (location != null && updated) "Location last recorded ${location.recordedAt}" else "Awaiting current location", p.secondary)
        if (location != null && updated) {
            BoardLabel(52, 215, 260, "Last reported location", p.primary, 14)
            BoardLabel(52, 239, 260, "${location.latitude}, ${location.longitude}", p.secondary)
        }
        BoardAction(282, 56, 84, 48, "End SOS") { showPinDialog = true; pinValue = ""; pinError = null }
        BoardLabel(24, 405, 342, snapshot?.let { "${it.respondersSummary.acceptedCount} responders accepted" } ?: "Checking responders…", p.primary, 16)
        Column(Modifier.offset(40.dp, 412.dp).size(310.dp, 80.dp).verticalScroll(rememberScrollState())) {
            snapshot?.activeResponders.orEmpty().forEach { responder ->
                Text("${responder.responderId} · ${responder.assistanceStatus.replace('_', ' ')}",
                    color = p.primary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
            }
        }
        Row(Modifier.offset(30.dp, 549.dp).width(330.dp).horizontalScroll(rememberScrollState())) {
            val statuses = delivery
            if (statuses.isNullOrEmpty()) Text(if (statuses == null) "Delivery unconfirmed" else "No notifications recorded", color = p.secondary)
            else statuses.forEach { Text("${it.name} · ${com.example.surakshanet.network.contactDeliveryLabel(it.status)}", Modifier.width(166.dp), color = p.primary, fontSize = 12.sp) }
        }
        BoardAction(24, 638, 165, 64, "Voice recorder") { openCapture(android.provider.MediaStore.Audio.Media.RECORD_SOUND_ACTION) }
        BoardAction(201, 638, 165, 64, "Open camera") { openCapture(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) }
        BoardLabel(66, 719, 280, if (updated) "Updates connected · $timerString" else "Awaiting server update · $timerString", p.secondary)
    }

    // Safety PIN Resolution Modal
    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { if (!isVerifyingPin) showPinDialog = false },
            title = {
                Text(
                    text = "Enter Safety PIN to End SOS",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = p.primary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Your Safety PIN is required to confirm you are safe and de-escalate emergency responders.",
                        fontSize = 12.sp,
                        color = p.secondary,
                        lineHeight = 16.sp
                    )

                    if (pinError != null) {
                        Text(
                            text = pinError!!,
                            fontSize = 11.sp,
                            color = p.accent,
                            fontWeight = FontWeight.Medium
                        )
                        if (pinError == MISSING_PRIMARY_PIN) {
                            TextButton(onClick = { showPinDialog = false; onSetPin() }) {
                                Text(settingsResources().getString(com.example.surakshanet.R.string.pin_set_from_sos), color = p.accent)
                            }
                        }
                    }

                    OutlinedTextField(
                        value = pinValue,
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pinValue = it },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        label = { Text("6-digit PIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { verifyPinAndEnd() },
                    enabled = !isVerifyingPin && pinValue.length == 6,
                    colors = ButtonDefaults.buttonColors(containerColor = p.accent)
                ) {
                    if (isVerifyingPin) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Confirm End SOS", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showPinDialog = false },
                    enabled = !isVerifyingPin
                ) { Text("Cancel", color = p.secondary) }
            },
            containerColor = p.surface,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

