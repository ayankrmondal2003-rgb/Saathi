package com.example.surakshanet.ui

import com.example.surakshanet.network.primaryPinState
import com.example.surakshanet.network.ExpectedAccount
import com.example.surakshanet.network.pinFailure
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.surakshanet.data.*
import com.example.surakshanet.location.*
import com.example.surakshanet.network.*
import com.example.surakshanet.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ResponderDashboard(
    responder: Responder,
    enable: (Boolean) -> Unit,
    available: (Boolean) -> Unit,
    incoming: () -> Unit
) {
    val colors = MaterialTheme.surakshaColors

    Page(
        title = "Responder Mode",
        subtitle = "Community Emergency Network"
    ) {
        // Status Hero Card
        SurakshaCard(
            backgroundColor = if (responder.available) colors.successContainer else colors.cardBackground,
            borderColor = if (responder.available) colors.success.copy(alpha = 0.4f) else colors.cardBorder
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (responder.available) colors.success.copy(alpha = 0.2f)
                            else colors.cardBackgroundSecondary
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Radar,
                        contentDescription = null,
                        tint = if (responder.available) colors.success else colors.textSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (responder.available) "Responder Duty Active" else "Responder Standby",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                    Text(
                        text = if (responder.available) "Monitoring requests within 1 km radius" else "Opt in to receive local emergency alerts",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
                StatusBadge(
                    text = when {
                        responder.available -> "AVAILABLE"
                        responder.enabled -> "OFF DUTY"
                        else -> "DISABLED"
                    },
                    color = when {
                        responder.available -> colors.success
                        responder.enabled -> colors.warning
                        else -> colors.textTertiary
                    },
                    pulsing = responder.available
                )
            }
        }

        // Mode Toggles
        SurakshaCard {
            Text(
                text = "Preferences & Availability",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Community Responder Mode",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Enables emergency push notifications when someone nearby calls for help.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = responder.enabled,
                    onCheckedChange = enable,
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.success)
                )
            }

            HorizontalDivider(color = colors.cardBorder.copy(alpha = 0.5f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Available for Practice Requests",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Receive practice alerts to familiarize yourself with the responder flow.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = responder.available,
                    onCheckedChange = available,
                    enabled = responder.enabled,
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.success)
                )
            }
        }

        // Radar Simulation Preview
        SurakshaMapContainer(
            height = 150.dp,
            title = "1 KM COVERAGE RADIUS",
            incidentActive = responder.available
        )

        // Nearby Requests Card
        SurakshaCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Active Nearby Requests",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
                if (responder.available && responder.response == Response.PENDING) {
                    StatusBadge(text = "1 PENDING", color = colors.warning, pulsing = true)
                }
            }

            Text(
                text = when {
                    !responder.enabled -> "Enable Community Responder Mode above to view available practice requests."
                    !responder.available -> "You are currently off duty. Turn on availability to view and test requests."
                    responder.response == Response.ACCEPTED -> "Practice request accepted · Live responder simulation active."
                    responder.response == Response.DECLINED -> "Practice request declined · Awaiting next simulated alert."
                    else -> "One simulated emergency request is within your 1,000 m radius."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )

            if (responder.available && responder.response == Response.PENDING) {
                Action(
                    label = "View Practice Request",
                    icon = Icons.Rounded.NotificationImportant,
                    click = incoming
                )
            }
        }

        // Safety Guidelines
        StatusCard(
            title = "Responder Code of Conduct",
            detail = "1. Assess your own safety first before responding.\n2. Respect the victim's privacy and dignity.\n3. Call 112 if physical danger, violence, or medical crisis is suspected.",
            icon = Icons.Rounded.VerifiedUser
        )
    }
}

@Composable
fun Incoming(invitationId: String? = null, reply: (Response) -> Unit) {
    val id = canonicalId(invitationId)
    var incidentId by remember(id) { mutableStateOf<String?>(null) }
    var statusMessage by remember(id) { mutableStateOf<String?>(null) }
    var isExpired by remember(id) { mutableStateOf(false) }
    var isBusy by remember(id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.surakshaColors
    val context = LocalContext.current

    fun submit(decision: String) {
        if (isBusy || id == null) return
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null || com.example.surakshanet.BuildConfig.API_BASE_URL.isBlank()) {
            statusMessage = if (uid == null) "Sign in to respond." else "Safety server is not configured."
            return
        }
        isBusy = true
        scope.launch {
            try {
                val account = ExpectedAccount(uid)
                val invitation = BackendClient.api.invitations(account).firstOrNull { canonicalId(it.id) == id }
                val incident = canonicalId(invitation?.incidentId)
                if (incident == null || incident == id) {
                    statusMessage = "Invitation unavailable. Open the responder inbox."
                    return@launch
                }
                val result = confirmedInvitationResponse(decision) {
                    BackendClient.api.respond(id, DecisionDto(decision), account)
                }
                if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid != uid) return@launch
                when {
                    result == "EXPIRED" -> {
                        isExpired = true
                        statusMessage = "This invitation has expired."
                    }
                    decision == "ACCEPTED" -> incidentId = incident
                    else -> reply(Response.DECLINED)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                statusMessage = invitationFailure(error, debug = com.example.surakshanet.BuildConfig.DEBUG)
            } finally {
                isBusy = false
            }
        }
    }

    Page(
        title = "Incoming SOS Alert",
        subtitle = "Emergency Assistance Request"
    ) {
        if (id == null) {
            Text("No valid responder invitation. Open the responder inbox to review pending requests.")
            return@Page
        }
        if (incidentId != null) {
            // Screen D: Responder Tracking Screen
            ResponderTrackingView(
                incidentId = incidentId,
                onComplete = { reply(Response.ACCEPTED) },
                onLeave = { reply(Response.DECLINED) }
            )
            return@Page
        }

        // Urgent Alert Hero Region
        SurakshaCard(
            backgroundColor = colors.emergencyContainer,
            borderColor = colors.emergency.copy(alpha = 0.5f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(colors.emergency.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Emergency,
                        contentDescription = null,
                        tint = colors.emergency,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "EMERGENCY ALERT NEARBY",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = colors.emergency
                    )
                    Text(
                        text = "Someone within 1 km needs immediate assistance",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
                StatusBadge(text = "URGENT", color = colors.emergency, pulsing = true)
            }
        }

        // Privacy & Protection Info
        SurakshaCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = colors.info,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Location Privacy Protection",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
            }
            Text(
                text = "Precise coordinates are protected on the server. Live turn-by-turn location is only released after explicit acceptance and backend authorization verification.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }

        // Responder Safety Advice
        SurakshaCard {
            Text(
                text = "Safe Responder Protocol",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary
            )
            Text(
                text = "• Assess your personal safety before proceeding\n• Stay on public and well-lit paths\n• Call 112 if suspect is aggressive or weapon is spotted",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }

        if (statusMessage != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.infoContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = statusMessage!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onInfoContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Action Buttons
        if (!isExpired) {
            Action(
                label = if (isBusy) "Submitting response…" else "Accept and Help",
                icon = Icons.Rounded.CheckCircle,
                enabled = !isBusy,
                click = { submit("ACCEPTED") }
            )
            SurakshaOutlinedButton(
                label = "Decline Request",
                icon = Icons.Rounded.Close,
                click = { submit("DECLINED") }
            )

            // Direct 112 Call
            TextButton(
                onClick = {
                    val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))
                    context.startActivity(dial)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(imageVector = Icons.Rounded.Phone, contentDescription = null, tint = colors.emergency, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Call 112 Police Directly", color = colors.emergency, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun ResponderTrackingView(
    incidentId: String?,
    onComplete: () -> Unit,
    onLeave: () -> Unit
) {
    val colors = MaterialTheme.surakshaColors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val repo = remember(incidentId) {
        if (!incidentId.isNullOrBlank()) IncidentTrackingRepository(incidentId) else null
    }

    DisposableEffect(repo) {
        repo?.startTracking()
        onDispose { repo?.stopTracking() }
    }

    val trackingState by repo?.state?.collectAsStateWithLifecycle() ?: remember {
        mutableStateOf(
            IncidentTrackingState(
                incidentId = incidentId ?: "",
                latestLocation = null
            )
        )
    }

    val isRevoked = trackingState.connectionState == WsConnectionState.ACCESS_REVOKED || trackingState.status == "REVOKED"
    val isClosed = trackingState.connectionState == WsConnectionState.INCIDENT_CLOSED || trackingState.status == "CLOSED"

    // Access Revoked Alert Dialog (Enforces complete de-authorization & privacy)
    if (isRevoked) {
        AlertDialog(
            onDismissRequest = onLeave,
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = colors.emergency,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Tracking Access Revoked",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.emergency
                )
            },
            text = {
                Text(
                    text = trackingState.revocationReason ?: "Your tracking authorization for this emergency has ended or was revoked. Precise coordinates have been cleared from this device for victim privacy.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary
                )
            },
            confirmButton = {
                Button(
                    onClick = onLeave,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.emergency)
                ) {
                    Text("Return to Dashboard", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Active Responding Banner
    SurakshaCard(
        backgroundColor = if (isClosed) colors.cardBackgroundSecondary else colors.successContainer,
        borderColor = if (isClosed) colors.cardBorder else colors.success.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (isClosed) colors.cardBackground
                        else colors.success.copy(alpha = 0.2f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isClosed) Icons.Rounded.CheckCircle else Icons.Rounded.DirectionsRun,
                    contentDescription = null,
                    tint = if (isClosed) colors.textSecondary else colors.success,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isClosed) "INCIDENT RESOLVED" else "YOU ARE RESPONDING",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isClosed) colors.textPrimary else colors.success
                )
                Text(
                    text = if (isClosed) "Incident closed · Tracking session finished"
                    else when (trackingState.connectionState) {
                        WsConnectionState.CONNECTED -> "Connected · check the latest fix timestamp"
                        WsConnectionState.FAILED -> "Connection paused after repeated failures"
                        else -> "Connecting to safety server"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
            StatusBadge(
                text = when {
                    isClosed -> "CLOSED"
                    isRevoked -> "REVOKED"
                    trackingState.connectionState == WsConnectionState.FAILED -> "OFFLINE"
                    trackingState.connectionState == WsConnectionState.CONNECTED -> "CONNECTED"
                    trackingState.connectionState == WsConnectionState.RECONNECTING -> "RECONNECTING"
                    else -> "CONNECTING"
                },
                color = when {
                    isClosed -> colors.info
                    isRevoked -> colors.emergency
                    trackingState.connectionState == WsConnectionState.CONNECTED -> colors.success
                    else -> colors.warning
                },
                pulsing = trackingState.connectionState == WsConnectionState.CONNECTED || trackingState.connectionState == WsConnectionState.RECONNECTING
            )
        }
    }

    if (!isClosed && !isRevoked && trackingState.connectionState == WsConnectionState.FAILED) {
        TextButton(onClick = { repo?.retryConnection() }) { Text("Retry tracking connection") }
    }
    trackingState.errorMessage?.let { Text(it, color = colors.emergency) }

    // Telemetry Stat Cards
    val loc = trackingState.latestLocation
    val accuracyStr = if (loc != null) "±${loc.accuracyMeters.toInt()} m" else "—"
    val stale = trackingState.isStale

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SurakshaMetricCard(
            value = if (loc != null) "Received" else "Awaiting",
            label = "Location",
            icon = Icons.Rounded.Navigation,
            accentColor = colors.info,
            modifier = Modifier.weight(1f)
        )
        SurakshaMetricCard(
            value = if (loc == null) "—" else if (stale) "Stale" else "Recent",
            label = "Fix age",
            icon = Icons.Rounded.Timer,
            accentColor = if (stale) colors.warning else colors.success,
            modifier = Modifier.weight(1f)
        )
        SurakshaMetricCard(
            value = accuracyStr,
            label = "Accuracy",
            icon = Icons.Rounded.GpsFixed,
            accentColor = if (stale) colors.warning else colors.warning,
            modifier = Modifier.weight(1f)
        )
    }

    // Assistance Status Coordination
    if (!isClosed && !isRevoked) {
        SurakshaCard {
            Text(
                text = "My Assistance Status",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary
            )
            val currentAssistance = trackingState.myAssistanceStatus ?: "ACCEPTED"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = currentAssistance == "ON_THE_WAY",
                    onClick = {
                        scope.launch { repo?.updateAssistanceStatus("ON_THE_WAY") }
                    },
                    label = { Text("En Route") },
                    leadingIcon = {
                        Icon(Icons.Rounded.DirectionsRun, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = currentAssistance == "HELPING",
                    onClick = {
                        scope.launch { repo?.updateAssistanceStatus("HELPING") }
                    },
                    label = { Text("Helping") },
                    leadingIcon = {
                        Icon(Icons.Rounded.ThumbUp, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = currentAssistance == "UNABLE_TO_HELP",
                    onClick = {
                        scope.launch { repo?.updateAssistanceStatus("UNABLE_TO_HELP") }
                    },
                    label = { Text("Can't Help") },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    // Connected Responders Summary Card
    if (trackingState.activeResponders.isNotEmpty()) {
        SurakshaCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Responders (${trackingState.respondersSummary.acceptedCount} Accepted)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
                StatusBadge(
                    text = "${trackingState.respondersSummary.activeHelpingCount} HELPING",
                    color = colors.success
                )
            }
            trackingState.activeResponders.forEach { resp ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(colors.info.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = resp.responderId.takeLast(3).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.info
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = resp.responderId,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textPrimary
                        )
                    }
                    StatusBadge(
                        text = resp.assistanceStatus,
                        color = when (resp.assistanceStatus) {
                            "HELPING" -> colors.success
                            "ON_THE_WAY" -> colors.info
                            "UNABLE_TO_HELP" -> colors.warning
                            else -> colors.textTertiary
                        }
                    )
                }
            }
        }
    }

    // Victim Coordinates Card
    val displayLoc = loc?.let { "Lat: ${it.latitude}, Lon: ${it.longitude}\nAccuracy: ±${it.accuracyMeters} m" }
    if (displayLoc != null && !isRevoked) {
        StatusCard(
            title = if (stale) "Victim Coordinates (Stale >60s)" else "Authorized Live Coordinates",
            detail = displayLoc,
            icon = Icons.Rounded.LocationOn
        )
    }

    // Primary Actions
    val lat = loc?.latitude
    val lon = loc?.longitude

    if (!isClosed && !isRevoked) {
        Action(
            label = "Open in Google Maps",
            enabled = lat != null && lon != null,
            icon = Icons.Rounded.Map,
            click = {
                val uri = Uri.parse("geo:0,0?q=$lat,$lon(Victim+Emergency+Location)")
                val mapIntent = Intent(Intent.ACTION_VIEW, uri)
                context.startActivity(mapIntent)
            }
        )

        SurakshaOutlinedButton(
            label = "Call 112 Emergency",
            icon = Icons.Rounded.Call,
            click = {
                val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))
                context.startActivity(dial)
            }
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            onClick = onComplete,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(if (isClosed) "Done" else "Return to Dashboard")
        }
        if (!isClosed) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        if (repo == null || repo.updateAssistanceStatus("LEFT_INCIDENT")) onLeave()
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.emergency)
            ) {
                Text("Leave Incident")
            }
        }
    }
}

@Composable
fun SafetyReadinessSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceRepo = remember(context) { com.example.surakshanet.notification.DeviceRepository.get(context) }
    val tokenState by deviceRepo.tokenState.collectAsStateWithLifecycle()
    var permissionStatus by remember { mutableStateOf(com.example.surakshanet.notification.NotificationHelper.hasNotificationPermission(context)) }
    var channelStatus by remember { mutableStateOf(com.example.surakshanet.notification.NotificationHelper.getChannelStatus(context)) }
    var lastAlertTs by remember { mutableStateOf(com.example.surakshanet.notification.SosFirebaseMessagingService.getLastAlertTimestamp(context)) }
    var testResult by rememberSaveable { mutableStateOf<String?>(null) }
    var isTesting by rememberSaveable { mutableStateOf(false) }
    var isRefreshingToken by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.surakshaColors

    LaunchedEffect(Unit) {
        permissionStatus = com.example.surakshanet.notification.NotificationHelper.hasNotificationPermission(context)
        channelStatus = com.example.surakshanet.notification.NotificationHelper.getChannelStatus(context)
        lastAlertTs = com.example.surakshanet.notification.SosFirebaseMessagingService.getLastAlertTimestamp(context)
        deviceRepo.refreshAndRegister()
    }

    val readinessScore = when {
        permissionStatus && channelStatus == com.example.surakshanet.notification.ChannelStatus.AVAILABLE && tokenState.isVerifiedActive -> 100
        permissionStatus && channelStatus == com.example.surakshanet.notification.ChannelStatus.AVAILABLE -> 85
        permissionStatus -> 70
        else -> 40
    }

    // Safety Readiness Score Card
    SurakshaCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(colors.success.copy(alpha = 0.15f))
                    .border(2.dp, colors.success, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$readinessScore%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = colors.success
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Safety Readiness Score",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
                Text(
                    text = if (readinessScore >= 85) "Device configured for reliable emergency alerts" else "Configuration required for background SOS notifications",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
        }
    }

    // Checklist Items
    SurakshaCard {
        Text(
            text = "Emergency Preparedness Checklist",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary
        )

        ReadinessRow(
            icon = Icons.Rounded.Notifications,
            title = "Notification Permission",
            subtitle = if (permissionStatus) "Granted (POST_NOTIFICATIONS active)" else "Not granted · Tap below to enable",
            isOk = permissionStatus
        )

        HorizontalDivider(color = colors.cardBorder.copy(alpha = 0.5f))

        ReadinessRow(
            icon = Icons.Rounded.VolumeUp,
            title = "Urgent SOS Channel",
            subtitle = when (channelStatus) {
                com.example.surakshanet.notification.ChannelStatus.AVAILABLE -> "Active (urgent_sos_v1 · High Importance)"
                com.example.surakshanet.notification.ChannelStatus.BLOCKED -> "Blocked by user in system settings"
                com.example.surakshanet.notification.ChannelStatus.NOTIFICATIONS_DISABLED -> "App notifications disabled"
                com.example.surakshanet.notification.ChannelStatus.MISSING -> "Channel not yet created"
            },
            isOk = channelStatus == com.example.surakshanet.notification.ChannelStatus.AVAILABLE
        )

        HorizontalDivider(color = colors.cardBorder.copy(alpha = 0.5f))

        ReadinessRow(
            icon = Icons.Rounded.Vibration,
            title = "Emergency Vibration Pattern",
            subtitle = "6 high-intensity pulses (~5.2s total)",
            isOk = true
        )

        HorizontalDivider(color = colors.cardBorder.copy(alpha = 0.5f))

        ReadinessRow(
            icon = Icons.Rounded.BatteryChargingFull,
            title = "Background Battery Restrictions",
            subtitle = "Set app battery optimization to Unrestricted",
            isOk = true
        )

        HorizontalDivider(color = colors.cardBorder.copy(alpha = 0.5f))

        val fcmSubtitle = when (val s = tokenState) {
            is com.example.surakshanet.notification.FcmTokenState.Registered ->
                "Ready to receive test invitations (${s.token.take(8)}...)"
            is com.example.surakshanet.notification.FcmTokenState.TokenReady ->
                "Device token ready (${s.token.take(8)}...) · Pending backend sync"
            is com.example.surakshanet.notification.FcmTokenState.RegisteringWithBackend ->
                "Registering this device with backend..."
            is com.example.surakshanet.notification.FcmTokenState.ConnectingToFirebase ->
                "Connecting to Firebase Cloud Messaging..."
            is com.example.surakshanet.notification.FcmTokenState.ConfigurationError ->
                "Configuration error: ${s.message}"
            is com.example.surakshanet.notification.FcmTokenState.PlayServicesUnavailable ->
                "Google Play services unavailable (${s.message})"
            is com.example.surakshanet.notification.FcmTokenState.NetworkError ->
                "Network unavailable · Check connection"
            is com.example.surakshanet.notification.FcmTokenState.AuthenticationRequired ->
                "Sign in required to register device token"
            is com.example.surakshanet.notification.FcmTokenState.RegistrationFailed ->
                "Registration failed: ${s.reason}"
            is com.example.surakshanet.notification.FcmTokenState.Retrying ->
                "Retrying (${s.attempt})... ${s.reason}"
            is com.example.surakshanet.notification.FcmTokenState.Initializing ->
                "Initializing..."
        }

        ReadinessRow(
            icon = if (tokenState.isVerifiedActive) Icons.Rounded.CloudDone else Icons.Rounded.CloudSync,
            title = "FCM Cloud Messaging Token",
            subtitle = fcmSubtitle,
            isOk = tokenState.isVerifiedActive,
            statusBadgeText = tokenState.badgeText
        )
    }

    if (!tokenState.isVerifiedActive) {
        SurakshaOutlinedButton(
            label = if (isRefreshingToken) "Connecting to FCM..." else "Retry Token Registration",
            icon = Icons.Rounded.Refresh,
            click = {
                if (isRefreshingToken) return@SurakshaOutlinedButton
                isRefreshingToken = true
                scope.launch {
                    deviceRepo.refreshAndRegister(force = true)
                    isRefreshingToken = false
                }
            }
        )
    }

    // FCM & System Developer Diagnostics
    SurakshaCard(
        backgroundColor = colors.cardBackgroundSecondary,
        onClick = { showDiagnostics = !showDiagnostics }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.BugReport,
                contentDescription = null,
                tint = colors.info,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "FCM & Push Diagnostics",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (showDiagnostics) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.textSecondary
            )
        }
        if (showDiagnostics) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = colors.cardBorder.copy(alpha = 0.5f))
            Spacer(Modifier.height(8.dp))
            val summary = remember(tokenState) { deviceRepo.getDiagnosticSummary() }
            summary.forEach { (key, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = key,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary
                    )
                }
            }
        }
    }

    if (lastAlertTs > 0) {
        StatusCard(
            title = "Last Received Emergency Alert",
            detail = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(lastAlertTs)),
            icon = Icons.Rounded.History
        )
    }

    if (testResult != null) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = colors.cardBackgroundSecondary,
            border = BorderStroke(1.dp, colors.info.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = testResult!!,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.info,
                modifier = Modifier.padding(14.dp)
            )
        }
    }

    // Diagnostic Testing CTAs
    Action(
        label = if (isTesting) "Sending Test Alert..." else "Send Test Alert to This Phone",
        icon = Icons.Rounded.NotificationAdd,
        enabled = !isTesting,
        click = {
            if (isTesting) return@Action
            isTesting = true
            testResult = null
            scope.launch {
                val res = deviceRepo.sendTestEmergencyAlert()
                testResult = if (res.isSuccess) {
                    "Firebase accepted the test alert for ${res.getOrNull()} registered device(s). Check notification tray."
                } else {
                    val err = res.exceptionOrNull()?.localizedMessage ?: "Unknown error"
                    "Firebase test alert unavailable: $err\nEnsure device is registered with backend. You can use the local notification test below to verify sound and vibration."
                }
                isTesting = false
            }
        }
    )

    SurakshaOutlinedButton(
        label = "Post Local Test Notification",
        icon = Icons.Rounded.Notifications,
        click = {
            val posted = com.example.surakshanet.notification.NotificationHelper.showSosNotification(
                context, java.util.UUID.randomUUID().toString(), "local-test",
                "SurakshaNet practice notification", "Check this phone’s sound and vibration.", "TEST_ALERT")
            testResult = if (posted) "Local notification posted." else "Check notification settings."
        }
    )
    SurakshaOutlinedButton(
        label = "Test Emergency Vibration Pattern",
        icon = Icons.Rounded.Vibration,
        click = {
            com.example.surakshanet.notification.NotificationHelper.triggerTestVibration(context)
        }
    )

    SurakshaOutlinedButton(
        label = "Open Android Notification Settings",
        icon = Icons.Rounded.Settings,
        click = {
            com.example.surakshanet.notification.NotificationHelper.openChannelSettings(context)
        }
    )
}

@Composable
private fun ReadinessRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    isOk: Boolean,
    statusBadgeText: String? = null
) {
    val colors = MaterialTheme.surakshaColors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (isOk) colors.success.copy(alpha = 0.15f)
                    else colors.warning.copy(alpha = 0.15f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isOk) colors.success else colors.warning,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
        Spacer(Modifier.width(8.dp))
        StatusBadge(
            text = statusBadgeText ?: if (isOk) "ACTIVE" else "CHECK",
            color = if (isOk) colors.success else colors.warning
        )
    }
}

@Composable
fun Profile(
    user: UserProfile,
    logout: () -> Unit,
    diagnostics: (() -> Unit)?,
    isDemoMode: Boolean = false
) {
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrDefault("1.0") }
    val themeController = LocalThemeController.current
    val colors = MaterialTheme.surakshaColors

    Page(
        title = "Profile & Settings",
        subtitle = "Account and Emergency Configuration"
    ) {
        // User Profile Header Card
        SurakshaCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(colors.emergency, Color(0xFF6366F1))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = user.name.take(1).ifBlank { "U" }.uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = user.name.ifBlank { "User Account" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Rounded.Verified,
                            contentDescription = "Verified",
                            tint = colors.info,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = user.email,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
                StatusBadge(
                    text = if (user.complete) "COMPLETE" else "ACTIVE",
                    color = colors.success
                )
            }
        }

        // Theme Selection Card
        SurakshaCard {
            Text(
                text = "Display Theme",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary
            )
            Text(
                text = "Choose your preferred appearance or follow system settings.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemeOptionButton(
                    label = "System",
                    icon = Icons.Rounded.BrightnessAuto,
                    selected = themeController.themeMode == ThemeMode.SYSTEM,
                    onClick = { themeController.setMode(ThemeMode.SYSTEM) },
                    modifier = Modifier.weight(1f)
                )
                ThemeOptionButton(
                    label = "Light",
                    icon = Icons.Rounded.LightMode,
                    selected = themeController.themeMode == ThemeMode.LIGHT,
                    onClick = { themeController.setMode(ThemeMode.LIGHT) },
                    modifier = Modifier.weight(1f)
                )
                ThemeOptionButton(
                    label = "Dark",
                    icon = Icons.Rounded.DarkMode,
                    selected = themeController.themeMode == ThemeMode.DARK,
                    onClick = { themeController.setMode(ThemeMode.DARK) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (isDemoMode) {
            StatusCard(
                title = "Authentication Provider",
                detail = "Local Demo Authentication · Place google-services.json in the app module to enable cloud Firebase Auth.",
                icon = Icons.Rounded.Code
            )
        }

        // Safety Readiness Dashboard
        SafetyReadinessSection()

        // Safety PIN Section
        SafetyPinSetup(user.uid)

        // GPS Diagnostics (Debug Mode)
        diagnostics?.let {
            SurakshaOutlinedButton(
                label = "Open GPS Diagnostics",
                icon = Icons.Rounded.BugReport,
                click = it
            )
        }

        // Backend Connection Health
        BackendConnection()

        // Privacy Policy Note
        StatusCard(
            title = "Data Privacy & Encryption",
            detail = "Profile preferences and trusted contacts remain securely on this device. Live coordinates are only transmitted during active emergency incidents.",
            icon = Icons.Rounded.PrivacyTip
        )

        // About SurakshaNet Official Brand Card
        SurakshaCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BrandLogo(Modifier.size(48.dp))
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SurakshaNet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Native Android · Version $version\nContinuously Moving 1 km SOS Network",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }
        }

        // Logout Button
        Action(
            label = "Logout",
            icon = Icons.Rounded.ExitToApp,
            click = logout
        )
    }
}

@Composable
private fun ThemeOptionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.surakshaColors
    GlassSurface(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        elevation = if (selected) 4.dp else 1.dp,
        backgroundColor = if (selected) colors.info.copy(alpha = 0.2f) else colors.glassSurfaceSubtle
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) colors.info else colors.textSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) colors.info else colors.textPrimary
            )
        }
    }
}

@Composable
private fun SafetyPinSetup(uid: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) } // 0: Primary PIN, 1: Duress PIN
    val colors = MaterialTheme.surakshaColors
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<SafetyPinStatusDto?>(null) }
    var isLoadingStatus by remember { mutableStateOf(false) }
    var actionMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var actionError by rememberSaveable { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    // Primary PIN fields
    var currentPin by remember { mutableStateOf("") }
    var primaryPin by remember { mutableStateOf("") }
    var primaryPinConfirm by remember { mutableStateOf("") }

    // Duress PIN fields
    var duressPin by remember { mutableStateOf("") }
    var duressPinConfirm by remember { mutableStateOf("") }

    val expected = remember(uid) { ExpectedAccount(uid) }
    suspend fun refreshStatus(): SafetyPinStatusDto {
        status = null
        isLoadingStatus = true
        try {
            return BackendClient.api.safetyPinStatus(expected).also { status = it }
        } finally {
            isLoadingStatus = false
        }
    }

    LaunchedEffect(uid, expanded) {
        if (com.example.surakshanet.BuildConfig.API_BASE_URL.isNotBlank()) {
            try {
                refreshStatus()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                actionError = pinFailure(e)
            }
        }
    }

    fun validatePin(pin: String): String? {
        if (pin.length != 6) return "PIN must be exactly 6 digits"
        if (!pin.all { it.isDigit() }) return "PIN must contain only numbers"
        if (pin.toSet().size == 1) return "PIN cannot use repeated digits (e.g. 111111)"
        if (pin in listOf("123456", "234567", "345678", "456789", "567890",
                          "654321", "765432", "876543", "987654", "098765")) {
            return "PIN cannot be a sequential sequence (e.g. 123456)"
        }
        return null
    }

    SurakshaCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.emergency.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = colors.emergency,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Safety Deactivation PIN",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
                Text(
                    text = "Protects incident closure so only you can cancel an active alert.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
        }

        // Status Chips Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val hasPrimary = status?.hasPrimaryPin == true
            val hasDuress = status?.hasDuressPin == true

            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                color = colors.cardBackgroundSecondary,
                border = BorderStroke(1.dp, colors.cardBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (hasPrimary) Icons.Rounded.CheckCircle else Icons.Rounded.Key,
                        contentDescription = null,
                        tint = if (hasPrimary) colors.success else colors.warning,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = primaryPinState(status).label,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                color = colors.cardBackgroundSecondary,
                border = BorderStroke(1.dp, colors.cardBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (hasDuress) Icons.Rounded.Security else Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = if (hasDuress) colors.info else colors.textTertiary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (status == null) "Duress unknown" else if (hasDuress) "Duress set" else "Duress optional",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        SurakshaOutlinedButton(
            label = if (expanded) "Hide PIN Configuration" else "Configure Safety PIN",
            icon = Icons.Rounded.Key,
            click = {
                expanded = !expanded
                actionMessage = null
                actionError = null
            }
        )

        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Tab Selection: Primary PIN vs Duress PIN
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeOptionButton(
                        label = "Primary PIN",
                        icon = Icons.Rounded.Lock,
                        selected = selectedTab == 0,
                        onClick = {
                            selectedTab = 0
                            actionMessage = null
                            actionError = null
                        },
                        modifier = Modifier.weight(1f)
                    )
                    ThemeOptionButton(
                        label = "Duress PIN",
                        icon = Icons.Rounded.Warning,
                        selected = selectedTab == 1,
                        onClick = {
                            selectedTab = 1
                            actionMessage = null
                            actionError = null
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (selectedTab == 0) {
                    // Primary PIN Configuration
                    val isChange = status?.hasPrimaryPin == true

                    Text(
                        text = if (isChange) "Change Your 6-Digit Safety PIN" else "Set Up Your 6-Digit Safety PIN",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )

                    Text(
                        text = "Safety PIN protects emergency deactivation with Argon2id cryptographic hashing. Do not share this with anyone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )

                    if (isChange) {
                        OutlinedTextField(
                            value = currentPin,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) currentPin = it },
                            label = { Text("Current 6-Digit PIN") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    OutlinedTextField(
                        value = primaryPin,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) primaryPin = it },
                        label = { Text("New 6-Digit PIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = primaryPinConfirm,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) primaryPinConfirm = it },
                        label = { Text("Confirm 6-Digit PIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Action(
                        label = if (isSubmitting) "Saving PIN..." else (if (isChange) "Update Safety PIN" else "Save Safety PIN"),
                        enabled = status != null && !isLoadingStatus && !isSubmitting && primaryPin.length == 6 && primaryPinConfirm.length == 6 && (!isChange || currentPin.length == 6),
                        click = {
                            val valErr = validatePin(primaryPin)
                            if (valErr != null) {
                                actionError = valErr
                                return@Action
                            }
                            if (primaryPin != primaryPinConfirm) {
                                actionError = "New PIN and confirmation do not match"
                                return@Action
                            }
                            isSubmitting = true
                            actionError = null
                            actionMessage = null
                            scope.launch {
                                try {
                                    if (com.example.surakshanet.BuildConfig.API_BASE_URL.isNotBlank()) {
                                        val result = if (isChange) {
                                            BackendClient.api.changeSafetyPin(
                                                SafetyPinChangeDto(
                                                    currentPin = currentPin,
                                                    newPin = primaryPin,
                                                    confirmPin = primaryPinConfirm,
                                                    credentialType = "PRIMARY_PIN"
                                                ), expected
                                            )
                                        } else {
                                            BackendClient.api.setupSafetyPin(
                                                SafetyPinSetupDto(
                                                    pin = primaryPin,
                                                    confirmPin = primaryPinConfirm,
                                                    credentialType = "PRIMARY_PIN"
                                                ), expected
                                            )
                                        }
                                        check(result.status == (if (isChange) "UPDATED" else "CONFIGURED"))
                                        check(refreshStatus().hasPrimaryPin)
                                        actionMessage = "Safety PIN confirmed by the server."
                                        currentPin = ""
                                        primaryPin = ""
                                        primaryPinConfirm = ""
                                    } else {
                                        actionError = "Connect to the safety server to configure a PIN."
                                    }
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    actionError = pinFailure(e)
                                } finally {
                                    isSubmitting = false
                                }
                            }
                        }
                    )
                } else {
                    // Duress PIN Configuration
                    Text(
                        text = "Duress Safeguard Simulation PIN",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colors.cardBackgroundSecondary,
                        border = BorderStroke(1.dp, colors.cardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Duress Safeguard Simulation Notice:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.warning
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "A Duress PIN is designed for coerced deactivation testing. When entered during an emergency, the app mimics incident cancellation in the UI while the backend marks the incident as ESCALATION_REQUIRED and keeps live GPS telemetry active in the background. No real 112 emergency calls are triggered.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary
                            )
                        }
                    }

                    OutlinedTextField(
                        value = duressPin,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) duressPin = it },
                        label = { Text("6-Digit Duress PIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = duressPinConfirm,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) duressPinConfirm = it },
                        label = { Text("Confirm Duress PIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Action(
                        label = if (isSubmitting) "Saving Duress PIN..." else "Save Duress PIN",
                        enabled = status != null && !isLoadingStatus && !isSubmitting && duressPin.length == 6 && duressPinConfirm.length == 6,
                        click = {
                            val valErr = validatePin(duressPin)
                            if (valErr != null) {
                                actionError = valErr
                                return@Action
                            }
                            if (duressPin != duressPinConfirm) {
                                actionError = "Duress PIN and confirmation do not match"
                                return@Action
                            }
                            isSubmitting = true
                            actionError = null
                            actionMessage = null
                            scope.launch {
                                try {
                                    if (com.example.surakshanet.BuildConfig.API_BASE_URL.isNotBlank()) {
                                        val result = BackendClient.api.setupSafetyPin(
                                            SafetyPinSetupDto(
                                                pin = duressPin,
                                                confirmPin = duressPinConfirm,
                                                credentialType = "DURESS_PIN"
                                            ), expected
                                        )
                                        check(result.status == "CONFIGURED" && refreshStatus().hasDuressPin)
                                        actionMessage = "Duress PIN configured successfully!"
                                        duressPin = ""
                                        duressPinConfirm = ""
                                    } else {
                                        actionError = "Connect to the safety server to configure a PIN."
                                    }
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    actionError = pinFailure(e)
                                } finally {
                                    isSubmitting = false
                                }
                            }
                        }
                    )
                }

                // Feedback messages
                if (!actionError.isNullOrBlank()) {
                    Text(
                        text = actionError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.emergency,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (!actionMessage.isNullOrBlank()) {
                    Text(
                        text = actionMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.success,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
