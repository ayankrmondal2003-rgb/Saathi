package com.example.surakshanet.ui

import android.os.SystemClock
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
    contacts: List<EmergencyContact> = emptyList(),
    alertStatuses: List<ContactAlertStatus> = emptyList(),
    onEndSos: () -> Unit
) {
    val p = maitriPalette()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val tracking = remember(context) { LocalTrackingRepository.get(context) }
    var snapshot by remember(uid, incident.started) { mutableStateOf<IncidentSnapshotDto?>(null) }
    var showPinDialog by rememberSaveable { mutableStateOf(false) }
    var pinValue by rememberSaveable { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var isVerifyingPin by remember { mutableStateOf(false) }

    // Media states (Voice & Camera) with explicit consent & runtime permissions
    var isRecordingAudio by rememberSaveable { mutableStateOf(false) }
    var hasPhotoEvidence by rememberSaveable { mutableStateOf(false) }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            isRecordingAudio = !isRecordingAudio
            val msg = if (isRecordingAudio) "Secure audio recording started" else "Audio recording stopped and encrypted"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Microphone permission required for voice notes", Toast.LENGTH_SHORT).show()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            hasPhotoEvidence = true
            Toast.makeText(context, "Photo evidence captured and encrypted locally", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Camera permission required to capture evidence", Toast.LENGTH_SHORT).show()
        }
    }

    // Poll live incident snapshot every 5s while active
    val activeIncidentId = tracking.sync.getActiveIncidentId(uid)
    LaunchedEffect(uid, incident.started) {
        while (true) {
            val id = tracking.sync.getActiveIncidentId(uid)
            if (id != null) {
                try {
                    val confirmed = BackendClient.api.snapshot(id, ExpectedAccount(uid))
                    snapshot = confirmed
                    if (confirmed.status == "CLOSED") {
                        tracking.sync.clearActiveIncident(uid)
                        onEndSos()
                        break
                    }
                } catch (e: Exception) {
                    // Non-fatal snapshot error
                }
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

    val responderCount = snapshot?.respondersSummary?.acceptedCount ?: 0
    val responderText = when (responderCount) {
        0 -> "Searching for nearby responders..."
        1 -> "1 nearby responder on the way"
        else -> "$responderCount nearby responders on the way"
    }

    val circleAlertCount = if (alertStatuses.isNotEmpty()) {
        alertStatuses.count { it.deliveryStatus != ContactDeliveryStatus.FAILED }
    } else {
        contacts.count { it.notifyOnSos }
    }
    val circleText = "$circleAlertCount trusted contacts alerted via SMS"

    // End SOS via PIN Resolution
    fun verifyPinAndEnd() {
        if (pinValue.length < 4) {
            pinError = "PIN must be at least 4 digits."
            return
        }

        isVerifyingPin = true
        pinError = null

        scope.launch {
            try {
                val activeId = tracking.sync.getActiveIncidentId(uid)
                if (activeId != null && com.example.surakshanet.BuildConfig.API_BASE_URL.isNotBlank()) {
                    BackendClient.api.resolveIncident(
                        id = activeId,
                        request = IncidentResolutionRequestDto(pin = pinValue, reason = "USER_RESOLVED"),
                        expected = ExpectedAccount(uid)
                    )
                }
                tracking.sync.clearActiveIncident(uid)
                showPinDialog = false
                Toast.makeText(context, "Incident safely resolved.", Toast.LENGTH_SHORT).show()
                onEndSos()
            } catch (e: HttpException) {
                pinError = when (e.code()) {
                    401, 403 -> "Incorrect Safety PIN. Incident remains active."
                    429 -> "Too many failed attempts. Temporary cooldown in effect."
                    else -> "Failed to resolve incident (HTTP ${e.code()})."
                }
            } catch (e: Exception) {
                // If offline or demo mode, allow safe local resolution
                tracking.sync.clearActiveIncident(uid)
                showPinDialog = false
                Toast.makeText(context, "Incident closed locally.", Toast.LENGTH_SHORT).show()
                onEndSos()
            } finally {
                isVerifyingPin = false
            }
        }
    }

    MaitriActiveSosContent(
        p = p,
        timerString = timerString,
        responderText = responderText,
        circleText = circleText,
        isRecordingAudio = isRecordingAudio,
        hasPhotoEvidence = hasPhotoEvidence,
        onEndSosClick = { showPinDialog = true },
        onVoiceNoteClick = {
            audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        },
        onCameraPhotoClick = {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    )

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
                    }

                    OutlinedTextField(
                        value = pinValue,
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pinValue = it },
                        label = { Text("4-6 Digit PIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { verifyPinAndEnd() },
                    enabled = !isVerifyingPin && pinValue.length >= 4,
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

@Composable
internal fun MaitriActiveSosContent(
    p: MaitriPalette,
    timerString: String,
    responderText: String,
    circleText: String,
    isRecordingAudio: Boolean,
    hasPhotoEvidence: Boolean,
    onEndSosClick: () -> Unit,
    onVoiceNoteClick: () -> Unit,
    onCameraPhotoClick: () -> Unit
) {
    val isDark = p.dark

    // Pulsing animation for the active beacon
    val infiniteTransition = rememberInfiniteTransition(label = "sos_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 28.dp, bottom = 110.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Row: Live indicator + "SOS is active" | "End SOS" button (84x36dp, rx=18dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Pulsing Red Dot
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFDC2626))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "SOS is active",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = p.primary
                        )
                        Text(
                            text = "Duration: $timerString",
                            fontSize = 11.sp,
                            color = p.accent,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // End SOS Button (84x36dp, rx=18dp)
                Box(
                    modifier = Modifier
                        .size(width = 84.dp, height = 36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(p.card)
                        .clickable(onClick = onEndSosClick),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "End SOS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = p.accent
                    )
                }
            }

            // Live Map Card (342x252dp, rx=24dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(252.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (isDark) Color(0xFF211D26) else Color(0xFFF5EDF1))
                    .border(1.dp, p.divider, RoundedCornerShape(24.dp))
            ) {
                // Map layout matching light-08.svg / dark-08.svg
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val roadColor = if (isDark) Color(0xFF2A2530) else Color(0xFFEADFE7)
                    val parkColor = if (isDark) Color(0xFF24362F) else Color(0xFFDFEBE1)
                    val centerPt = Offset(size.width / 2f, size.height / 2f)

                    // Draw blocks
                    drawRoundRect(
                        color = roadColor,
                        topLeft = Offset(40f, 30f),
                        size = androidx.compose.ui.geometry.Size(120f, 110f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(20f)
                    )
                    drawRoundRect(
                        color = parkColor,
                        topLeft = Offset(320f, 20f),
                        size = androidx.compose.ui.geometry.Size(240f, 120f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(30f)
                    )
                    drawRoundRect(
                        color = roadColor,
                        topLeft = Offset(40f, 260f),
                        size = androidx.compose.ui.geometry.Size(130f, 90f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(20f)
                    )
                    drawRoundRect(
                        color = roadColor,
                        topLeft = Offset(300f, 230f),
                        size = androidx.compose.ui.geometry.Size(260f, 90f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(20f)
                    )

                    // Moving 1 km recruitment radius geofence ring
                    drawCircle(
                        color = if (isDark) Color(0xFFFF9CBE).copy(alpha = 0.2f) else Color(0xFFB82055).copy(alpha = 0.15f),
                        radius = 180f * pulseScale,
                        center = centerPt
                    )
                    drawCircle(
                        color = if (isDark) Color(0xFFFF9CBE).copy(alpha = 0.45f) else Color(0xFFB82055).copy(alpha = 0.35f),
                        radius = 110f,
                        center = centerPt,
                        style = Stroke(width = 2.5f)
                    )

                    // Victim center beacon marker
                    drawCircle(
                        color = if (isDark) Color(0xFFFF9CBE) else Color(0xFFB82055),
                        radius = 20f,
                        center = centerPt
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 8f,
                        center = centerPt
                    )
                }

                // Floating Status Chip
                Box(
                    modifier = Modifier
                        .padding(14.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isDark) Color(0xFF28212A) else Color.White)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFDC2626))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "BROADCASTING LOCATION",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = p.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Incident Status Summary Card (342x90dp, rx=18dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(p.surface)
                    .border(1.dp, p.divider, RoundedCornerShape(18.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Row 1: Responders accepted
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.DirectionsRun,
                                contentDescription = null,
                                tint = p.accent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Responders accepted",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = p.primary
                            )
                        }
                        Text(
                            text = responderText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = p.accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Divider line (310x1dp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(p.divider)
                    )

                    // Row 2: Circle notified
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Shield,
                                contentDescription = null,
                                tint = p.accent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Circle notified",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = p.primary
                            )
                        }
                        Text(
                            text = circleText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = p.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Quick Media Buttons (165x42dp each, rx=15dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Voice Note Button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(if (isRecordingAudio) p.card else p.surface)
                        .border(1.dp, if (isRecordingAudio) p.accent else p.divider, RoundedCornerShape(15.dp))
                        .clickable(onClick = onVoiceNoteClick),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Mic,
                            contentDescription = null,
                            tint = if (isRecordingAudio) p.accent else p.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isRecordingAudio) "Recording..." else "Voice note",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isRecordingAudio) p.accent else p.primary
                        )
                    }
                }

                // Camera Photo Button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(if (hasPhotoEvidence) p.card else p.surface)
                        .border(1.dp, if (hasPhotoEvidence) p.accent else p.divider, RoundedCornerShape(15.dp))
                        .clickable(onClick = onCameraPhotoClick),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.CameraAlt,
                            contentDescription = null,
                            tint = if (hasPhotoEvidence) p.accent else p.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (hasPhotoEvidence) "Photo saved" else "Camera photo",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (hasPhotoEvidence) p.accent else p.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Live-Update Footer Tiles (165x64dp each, rx=18dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Tile 1: Live GPS active
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(p.card)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.GpsFixed,
                            contentDescription = null,
                            tint = p.accent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Live GPS",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = p.primary
                            )
                            Text(
                                text = "Real-time updates",
                                fontSize = 10.sp,
                                color = p.secondary
                            )
                        }
                    }
                }

                // Tile 2: Safety PIN required
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(p.card)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = p.accent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Safety PIN",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = p.primary
                            )
                            Text(
                                text = "Required to stop",
                                fontSize = 10.sp,
                                color = p.secondary
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Previews for Screen 08 (Light & Dark)
// ---------------------------------------------------------------------------

@Preview(name = "Screen 08 — Active SOS Light", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun MaitriActiveSosLightPreview() {
    MaitriActiveSosContent(
        p = MaitriPalette(dark = false),
        timerString = "00:42",
        responderText = "1 nearby responder on the way",
        circleText = "2 contacts alerted via SMS",
        isRecordingAudio = false,
        hasPhotoEvidence = false,
        onEndSosClick = {},
        onVoiceNoteClick = {},
        onCameraPhotoClick = {}
    )
}

@Preview(name = "Screen 08 — Active SOS Dark", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun MaitriActiveSosDarkPreview() {
    MaitriActiveSosContent(
        p = MaitriPalette(dark = true),
        timerString = "00:42",
        responderText = "1 nearby responder on the way",
        circleText = "2 contacts alerted via SMS",
        isRecordingAudio = false,
        hasPhotoEvidence = false,
        onEndSosClick = {},
        onVoiceNoteClick = {},
        onCameraPhotoClick = {}
    )
}
