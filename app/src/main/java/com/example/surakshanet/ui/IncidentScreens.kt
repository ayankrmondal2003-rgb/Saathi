package com.example.surakshanet.ui

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.animation.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.surakshanet.data.*
import com.example.surakshanet.location.LocalTrackingRepository
import com.example.surakshanet.network.BackendClient
import com.example.surakshanet.network.IncidentResolutionRequestDto
import com.example.surakshanet.network.SafetyPinRecoveryDto
import com.example.surakshanet.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.example.surakshanet.network.ExpectedAccount
import com.example.surakshanet.network.SafetyPinSetupDto
import com.example.surakshanet.network.SafetyPinStatusDto
import com.example.surakshanet.network.MISSING_PRIMARY_PIN
import com.example.surakshanet.network.pinFailure
import com.example.surakshanet.network.resolveWithPin
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

@Composable
fun Home(state: DemoState, start: (Boolean) -> Unit, contacts: () -> Unit) {
    val active = state.incident?.status == IncidentStatus.ACTIVE
    var confirm by rememberSaveable { mutableStateOf(false) }
    var share by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.surakshaColors
    val userName = state.user?.name?.ifBlank { "User" } ?: "User"

    Page(
        title = "SurakshaNet",
        subtitle = if (active) "SOS in progress" else "Personal safety"
    ) {
        // Readiness Status Hero Banner
        SurakshaCard(
            backgroundColor = if (active) colors.emergencyContainer else colors.cardBackground,
            borderColor = if (active) colors.emergency.copy(alpha = 0.5f) else colors.cardBorder
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (active) colors.emergency.copy(alpha = 0.15f)
                            else colors.success.copy(alpha = 0.15f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (active) Icons.Rounded.Emergency else Icons.Rounded.VerifiedUser,
                        contentDescription = null,
                        tint = if (active) colors.emergency else colors.success,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (active) "EMERGENCY SOS RUNNING" else "Safety Shield Active",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                    Text(
                        text = if (active) "Open your incident for tracking updates" else "Welcome, $userName",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
                StatusBadge(
                    text = if (active) "ACTIVE" else "READY",
                    color = if (active) colors.emergency else colors.success,
                    pulsing = active
                )
            }


        }

        // GPS Share Consent (if backend is active)
        if (!active && com.example.surakshanet.BuildConfig.API_BASE_URL.isNotBlank()) {
            GlassSurface(
                shape = RoundedCornerShape(16.dp),
                backgroundColor = colors.glassSurfaceSubtle,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = share,
                        onCheckedChange = { share = it },
                        colors = CheckboxDefaults.colors(checkedColor = colors.success)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Share demo GPS with test server & accepted responders",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textPrimary
                    )
                }
            }
        }

        // Hero SOS Button Section with pulsating heartbeat rings
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            EmergencySosButton(
                modifier = Modifier.width((maxWidth * 0.62f).coerceIn(160.dp, 230.dp)),
                onTrigger = {
                    start(share)
                    share = false
                },
                onQuickTap = {
                    confirm = true
                }
            )
        }

        // If an incident is active, show prominent shortcut
        if (active) {
            Action(
                label = "Open Active Emergency Dashboard",
                click = { start(false) },
                icon = Icons.Rounded.Shield,
                isEmergency = true
            )
        }

        val primaryContact = state.contacts.find { it.isPrimary }
        // Trusted Contacts Card Preview
        SurakshaCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF6366F1).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.People,
                            contentDescription = null,
                            tint = Color(0xFF6366F1),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Trusted Contacts",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary
                        )
                        Text(
                            text = if (state.contacts.isEmpty()) {
                                "No emergency contacts added yet."
                            } else {
                                val countStr = "${state.contacts.size} contact(s) configured"
                                if (primaryContact != null) {
                                    "$countStr · Primary: ${primaryContact.displayName}"
                                } else {
                                    countStr
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    }
                }
                TextButton(onClick = contacts) {
                    Text("Manage", fontWeight = FontWeight.Bold, color = colors.info)
                }
            }

            if (state.contacts.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    state.contacts.take(3).forEach { contact ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = colors.cardBackgroundSecondary,
                            border = BorderStroke(1.dp, colors.cardBorder),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(colors.info.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = contact.name.take(1).uppercase(),
                                        fontWeight = FontWeight.Bold,
                                        color = colors.info
                                    )
                                }
                                Text(
                                    text = contact.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    color = colors.textPrimary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Community Responder Mode Status
        SurakshaCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.info.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Radar,
                        contentDescription = null,
                        tint = colors.info,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Community Responder Mode",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )
                    Text(
                        text = when {
                            state.responder.available -> "Opted in · Available for simulated requests"
                            state.responder.enabled -> "Opted in · Currently off duty"
                            else -> "Disabled · Toggle in Responder tab"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
                StatusBadge(
                    text = when {
                        state.responder.available -> "DUTY ON"
                        state.responder.enabled -> "STANDBY"
                        else -> "OFF"
                    },
                    color = when {
                        state.responder.available -> colors.success
                        state.responder.enabled -> colors.warning
                        else -> colors.textTertiary
                    }
                )
            }
        }

        // Safety Guidance Note
        StatusCard(
            title = "Emergency & Practice Guidance",
            detail = "Holding the SOS button starts local location tracking and alerts nearby community responders within 1 km. For real emergencies, always contact 112 directly.",
            icon = Icons.Rounded.Security
        )
    }

    // Confirmation dialog for quick tap
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Emergency,
                    contentDescription = null,
                    tint = colors.emergency,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Activate Emergency SOS?",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Text(
                    text = "A quick tap was detected. You can activate the SOS practice session now or hold the button for 2 seconds anytime.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirm = false
                        start(share)
                        share = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.emergency)
                ) {
                    Text("Activate SOS", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ActiveIncident(
    incident: SosIncident,
    uid: String,
    contacts: List<EmergencyContact> = emptyList(),
    alertStatuses: List<ContactAlertStatus> = emptyList(),
    end: () -> Unit
) {
    var showPinDialog by rememberSaveable { mutableStateOf(false) }
    var showRecoveryDialog by rememberSaveable { mutableStateOf(false) }
    var isVerifying by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var cooldownSeconds by remember { mutableIntStateOf(0) }
    var isDuressSimulation by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.surakshaColors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val tracking = remember(context) { LocalTrackingRepository.get(context) }
    val shared = tracking.sync.isShared(uid, tracking.session.value.id)
    var snapshot by remember(uid, incident.started) { mutableStateOf<com.example.surakshanet.network.IncidentSnapshotDto?>(null) }
    LaunchedEffect(shared, uid, incident.started) {
        while (shared) {
            val id = tracking.sync.getActiveIncidentId(uid)
            if (id != null) {
                try {
                    snapshot = BackendClient.api.snapshot(id)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    snapshot = null
                }
            }
            delay(5000)
        }
    }
    LaunchedEffect(cooldownSeconds) {
        if (cooldownSeconds > 0) {
            delay(1000)
            cooldownSeconds--
        }
    }

    val seconds by produceState(0L, incident.started) {
        while (true) {
            value = (SystemClock.elapsedRealtime() - incident.started) / 1000
            delay(500)
        }
    }

    Page(
        title = "Emergency Active",
        subtitle = "Incident #SOS-${incident.started.toString().takeLast(4)}"
    ) {
        if (isDuressSimulation) {
            DemoBanner(
                label = "EXPERIMENTAL DURESS SIMULATION",
                active = true
            )
        } else {
            DemoBanner(label = if (shared) "TEST SOS · SERVER SYNC" else "LOCAL PRACTICE SOS", active = true)
        }

        // Hero Digital Timer Card
        SurakshaCard(
            backgroundColor = colors.emergencyContainer,
            borderColor = colors.emergency.copy(alpha = 0.5f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusBadge(text = if (isDuressSimulation) "ESCALATION REQUIRED" else "INCIDENT RUNNING", color = colors.emergency, pulsing = true)
                Text(
                    text = "%02d:%02d".format(seconds / 60, seconds % 60),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 54.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp
                    ),
                    color = colors.emergency
                )
                Text(
                    text = "Duration since SOS trigger",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
        }

        // Radar Map Container with 1 km zone & simulated responder
        SurakshaMapContainer(
            title = "1.0 KM EMERGENCY RADIUS",
            incidentActive = true,
            responderCount = snapshot?.respondersSummary?.acceptedCount ?: 0
        )

        // 3 Key Metrics Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SurakshaMetricCard(
                value = "1.0 km",
                label = "Alert Zone",
                icon = Icons.Rounded.Radar,
                accentColor = colors.info,
                modifier = Modifier.weight(1f)
            )
            SurakshaMetricCard(
                value = snapshot?.respondersSummary?.totalInvited?.toString() ?: "—",
                label = "Invited",
                icon = Icons.Rounded.Notifications,
                accentColor = colors.warning,
                modifier = Modifier.weight(1f)
            )
            SurakshaMetricCard(
                value = snapshot?.respondersSummary?.acceptedCount?.toString() ?: "—",
                label = "Accepted",
                icon = Icons.Rounded.DirectionsRun,
                accentColor = colors.success,
                modifier = Modifier.weight(1f)
            )
        }

        // Telemetry & GPS Panel
        LocationPanel(uid)

        // Responders Status
        SurakshaCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Group,
                    contentDescription = null,
                    tint = colors.success,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Responders En Route",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
            }

            val responders = snapshot?.activeResponders.orEmpty()
            if (responders.isEmpty()) Text("No confirmed responder updates")
            for (responder in responders) {
                Text(responder.responderId + " · " + responder.assistanceStatus)
            }
        }

        // Trusted Emergency Contacts Alert Card
        SurakshaCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContactPhone,
                    contentDescription = null,
                    tint = colors.emergency,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Trusted Contact Alerts",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Real-time delivery & live tracking grants",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
                StatusBadge(
                    text = "${contacts.count { it.notifyOnSos }} NOTIFIED",
                    color = if (contacts.any { it.notifyOnSos }) colors.success else colors.textTertiary
                )
            }

            if (contacts.isEmpty()) {
                Text(
                    text = "No emergency contacts configured. Tap Contacts tab to add trusted recipients.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            } else {
                contacts.forEach { contact ->
                    val alertStatus = alertStatuses.find { it.contactId == contact.id }
                    val deliveryStatus = alertStatus?.deliveryStatus
                        ?: if (contact.notifyOnSos) ContactDeliveryStatus.PROVIDER_ACCEPTED else ContactDeliveryStatus.NOT_REQUESTED
                    val statusColor = when (deliveryStatus) {
                        ContactDeliveryStatus.DELIVERED_WHEN_CONFIRMED, ContactDeliveryStatus.PROVIDER_ACCEPTED -> colors.success
                        ContactDeliveryStatus.QUEUED, ContactDeliveryStatus.SENDING -> colors.warning
                        ContactDeliveryStatus.NOT_REQUESTED -> colors.textTertiary
                        ContactDeliveryStatus.FAILED -> colors.emergency
                        ContactDeliveryStatus.UNKNOWN -> colors.textSecondary
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colors.cardBackgroundSecondary,
                        border = BorderStroke(1.dp, colors.cardBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = contact.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.textPrimary
                                    )
                                    if (contact.isPrimary) {
                                        Spacer(Modifier.width(6.dp))
                                        StatusBadge(text = "PRIMARY", color = colors.emergency)
                                    }
                                }
                                StatusBadge(text = deliveryStatus.label, color = statusColor)
                            }

                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = EmergencyContact.maskPhone(contact.phoneNumber),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textSecondary
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (contact.includeLocationLink) {
                                        Text("• Location Link", style = MaterialTheme.typography.labelSmall, color = colors.info)
                                    }
                                    if (contact.allowLiveTracking) {
                                        Text("• Live Tracking", style = MaterialTheme.typography.labelSmall, color = colors.success)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Fallback Emergency SMS Button (Explicit Intent ACTION_SENDTO - No broad permission needed)
        val primaryContact = contacts.find { it.isPrimary } ?: contacts.firstOrNull()
        if (primaryContact != null) {
            val session = tracking.session.value
            val effectiveLat = session.latest?.latitude
            val effectiveLng = session.latest?.longitude
            val emergencyMessage = com.example.surakshanet.notification.SosContactNotifierHelper.formatEmergencyMessage(
                contactName = primaryContact.displayName,
                latitude = effectiveLat,
                longitude = effectiveLng,
                recordedAtMillis = session.startedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                includeLocation = primaryContact.includeLocationLink
            )

            SurakshaOutlinedButton(
                label = "Send Emergency SMS Fallback",
                icon = Icons.Rounded.Send,
                click = {
                    val smsIntent = com.example.surakshanet.notification.SosContactNotifierHelper.createSmsComposerIntent(
                        primaryContact.phoneNumber,
                        emergencyMessage
                    )
                    context.startActivity(smsIntent)
                }
            )
        }

        // Call Police / 112 Action Button (Explicit Intent ACTION_DIAL - No broad permission needed)
        SurakshaOutlinedButton(
            label = "Call 112 Emergency Services",
            icon = Icons.Rounded.Phone,
            click = {
                val dialIntent = com.example.surakshanet.notification.SosContactNotifierHelper.createEmergencyDialerIntent("112")
                context.startActivity(dialIntent)
            }
        )

        // End SOS Button
        Action(
            label = "End SOS with Safety PIN",
            icon = Icons.Rounded.Lock,
            isEmergency = true,
            click = {
                pinError = null
                showPinDialog = true
            }
        )
    }

    // Safety PIN Deactivation Dialog
    var pinStatus by remember(uid) { mutableStateOf<SafetyPinStatusDto?>(null) }
    var statusAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(showPinDialog, shared, uid, statusAttempt) {
        if (showPinDialog && shared) {
            pinStatus = null
            try {
                val status = BackendClient.api.safetyPinStatus(ExpectedAccount(uid))
                pinStatus = status
                cooldownSeconds = status.retryAfterSeconds
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pinError = pinFailure(e)
            }
        }
    }
    if (showPinDialog) {
        Dialog(
            onDismissRequest = {
                if (!isVerifying) {
                    showPinDialog = false
                    pinError = null
                }
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().imePadding().padding(16.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                if (shared && (pinStatus?.hasPrimaryPin != true || pinError == MISSING_PRIMARY_PIN)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("End SOS", style = MaterialTheme.typography.titleLarge)
                        if (pinStatus?.hasPrimaryPin == false || pinError == MISSING_PRIMARY_PIN) {
                            Text(MISSING_PRIMARY_PIN)
                            Button(onClick = { showPinDialog = false; showRecoveryDialog = true }) { Text("Set Safety PIN") }
                        } else {
                            Text(pinError ?: "Checking your Safety PIN…")
                            if (pinError != null) TextButton(onClick = { pinError = null; statusAttempt++ }) { Text("Retry") }
                        }
                        TextButton(onClick = { showPinDialog = false }) { Text("Keep SOS active") }
                    }
                } else SurakshaPinPad(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    pinLength = 6,
                    isVerifying = isVerifying,
                    errorMessage = pinError,
                    cooldownSeconds = cooldownSeconds,
                    onPinSubmit = { pin ->
                        isVerifying = true
                        pinError = null
                        scope.launch {
                            try {
                                if (shared) {
                                    val repo = LocalTrackingRepository.get(context)
                                    val targetId = repo.sync.getActiveIncidentId(uid)
                                    if (targetId.isNullOrBlank()) {
                                        pinError = "No active server incident found to resolve. SOS remains active."
                                        isVerifying = false
                                        return@launch
                                    }
                                    val response = resolveWithPin(targetId) {
                                        BackendClient.api.resolveIncident(targetId, IncidentResolutionRequestDto(pin), ExpectedAccount(uid))
                                    }
                                    when (response.resolutionStatus) {
                                        "CONFIRMED" -> {
                                            if (repo.sync.getActiveIncidentId(uid) != targetId) return@launch
                                            repo.sync.clearActiveIncident(uid)
                                            showPinDialog = false
                                            end()
                                        }
                                        "DURESS_SIMULATION" -> {
                                            isDuressSimulation = true
                                            showPinDialog = false
                                        }
                                        else -> {
                                            pinError = response.message
                                        }
                                    }
                                } else {
                                    if (pin == "123456" || pin == "1234") {
                                        showPinDialog = false
                                        end()
                                    } else {
                                        pinError = "Incorrect Demo PIN (Use 123456 in demo mode)"
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                pinError = pinFailure(e)
                                if (e is HttpException && e.code() == 429) {
                                    statusAttempt++
                                }
                            } finally {
                                isVerifying = false
                            }
                        }
                    },
                    onKeepActive = {
                        if (!isVerifying) showPinDialog = false
                        pinError = null
                    },
                    onForgotPin = if (shared) ({
                        if (!isVerifying) {
                            showPinDialog = false
                            showRecoveryDialog = true
                        }
                    }) else null
                )
            }
        }
    }

    // Safety PIN Recovery Dialog
    if (showRecoveryDialog) {
        val enroll = pinStatus?.hasPrimaryPin == false || pinError == MISSING_PRIMARY_PIN
        var isRecovering by remember { mutableStateOf(false) }
        Dialog(
            onDismissRequest = { if (!isRecovering) showRecoveryDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().imePadding().padding(16.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                var newPin by remember { mutableStateOf("") }
                var confirmPin by remember { mutableStateOf("") }
                var recoveryError by rememberSaveable { mutableStateOf<String?>(null) }
                var password by remember { mutableStateOf("") }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(colors.info.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Key,
                            contentDescription = null,
                            tint = colors.info,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Text(
                        text = if (enroll) "Set Safety PIN" else "Recover Safety PIN",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )

                    Text(
                        text = "Your SOS stays active. After saving, enter your new six-digit PIN to end it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center
                    )

                    if (!enroll) OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Account password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        value = newPin,
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) newPin = it },
                        label = { Text("New 6-Digit PIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        value = confirmPin,
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) confirmPin = it },
                        label = { Text("Confirm New PIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    if (!recoveryError.isNullOrBlank()) {
                        Text(
                            text = recoveryError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.emergency,
                            textAlign = TextAlign.Center
                        )
                    }

                    Action(
                        label = if (isRecovering) "Saving…" else if (enroll) "Set Safety PIN" else "Verify password and reset PIN",
                        enabled = !isRecovering && (enroll || password.isNotEmpty()) && newPin.length == 6 && confirmPin.length == 6,
                        click = {
                            if (newPin != confirmPin) {
                                recoveryError = "PIN confirmation does not match"
                                return@Action
                            }
                            isRecovering = true
                            recoveryError = null
                            scope.launch {
                                try {
                                    val expected = ExpectedAccount(uid)
                                    if (enroll) {
                                        check(BackendClient.api.setupSafetyPin(SafetyPinSetupDto(newPin, confirmPin), expected).status == "CONFIGURED")
                                    } else {
                                        val user = FirebaseAuth.getInstance().currentUser
                                        val email = user?.email
                                        if (user == null || user.uid != uid || email == null) {
                                            recoveryError = "Sign in with the incident owner's account."
                                            return@launch
                                        }
                                        user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()
                                        password = ""
                                        user.getIdToken(true).await()
                                        check(BackendClient.api.recoverSafetyPin(SafetyPinRecoveryDto(newPin, confirmPin), expected).status == "RECOVERED")
                                    }
                                    pinStatus = null
                                    showRecoveryDialog = false
                                    showPinDialog = true
                                    pinError = null
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: com.google.firebase.FirebaseException) {
                                    recoveryError = "Account verification failed. Check your password and connection, then retry."
                                } catch (e: Exception) {
                                    recoveryError = pinFailure(e)
                                } finally {
                                    isRecovering = false
                                }
                            }
                        }
                    )

                    SurakshaOutlinedButton(
                        label = "Cancel",
                        click = { if (!isRecovering) showRecoveryDialog = false }
                    )
                }
            }
        }
    }
}


