package `in`.maitri.app.ui

import android.os.SystemClock
import `in`.maitri.app.R
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
import `in`.maitri.app.data.ContactAlertStatus
import `in`.maitri.app.data.ContactDeliveryStatus
import `in`.maitri.app.data.EmergencyContact
import `in`.maitri.app.data.SosIncident
import `in`.maitri.app.location.LocalTrackingRepository
import `in`.maitri.app.location.FixPolicy
import `in`.maitri.app.location.LocationHint
import `in`.maitri.app.location.ShownFix
import `in`.maitri.app.location.accuracyLabel
import `in`.maitri.app.location.airplaneModeOn
import `in`.maitri.app.location.locationAllowed
import `in`.maitri.app.location.locationEnabled
import `in`.maitri.app.location.locationHint
import `in`.maitri.app.location.locationHintText
import `in`.maitri.app.location.preciseLocationAllowed
import `in`.maitri.app.location.shownLocation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import `in`.maitri.app.network.BackendClient
import `in`.maitri.app.network.MISSING_PRIMARY_PIN
import `in`.maitri.app.network.pinFailure
import `in`.maitri.app.network.ExpectedAccount
import `in`.maitri.app.network.IncidentResolutionRequestDto
import `in`.maitri.app.network.IncidentSnapshotDto
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
    onSetPin: () -> Unit = {},
    /** Opened from Home's "End SOS": show the Safety PIN dialog straight away. */
    requestEnd: Boolean = false
) {
    val p = maitriPalette()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val tracking = remember(context) { LocalTrackingRepository.get(context) }
    var snapshot by remember(uid, incident.started) { mutableStateOf<IncidentSnapshotDto?>(null) }
    var circleAlerted by rememberSaveable(uid, incidentId) { mutableStateOf(false) }
    var showPinDialog by rememberSaveable { mutableStateOf(requestEnd) }
    var pinValue by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var isVerifyingPin by remember { mutableStateOf(false) }

    var delivery by remember(uid, incident.started) { mutableStateOf<List<`in`.maitri.app.network.ContactDeliveryDto>?>(null) }
    var updated by remember { mutableStateOf(false) }
    fun openCapture(action: String) {
        try { context.startActivity(android.content.Intent(action)) }
        catch (_: android.content.ActivityNotFoundException) { Toast.makeText(context, "No capture app available", Toast.LENGTH_SHORT).show() }
        catch (_: SecurityException) { Toast.makeText(context, "Capture permission unavailable", Toast.LENGTH_SHORT).show() }
    }

    // An SOS never waits for a PIN; without one, ask for it now, because ending the SOS needs it.
    var pinMissing by remember(uid) { mutableStateOf(false) }
    var pinPromptDismissed by rememberSaveable(uid, incidentId) { mutableStateOf(false) }
    LaunchedEffect(uid) {
        pinMissing = try { !BackendClient.api.safetyPinStatus(ExpectedAccount(uid)).hasPrimaryPin }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
    }
    if (pinMissing && !pinPromptDismissed && !showPinDialog) {
        androidx.compose.material3.AlertDialog(onDismissRequest = { pinPromptDismissed = true },
            containerColor = p.surface, titleContentColor = p.primary, textContentColor = p.secondary,
            title = { Text("Set your Safety PIN") },
            text = { Text("Your SOS is running. You'll need a 6-digit Safety PIN to end it, so set one now.") },
            confirmButton = { TextButton(onClick = { pinPromptDismissed = true; onSetPin() }) { Text("Set PIN", color = p.accent) } },
            dismissButton = { TextButton(onClick = { pinPromptDismissed = true }) { Text("Later", color = p.accent) } })
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

    // Phone-side location: the fix this phone has (shown before the server confirms it) and why it may be missing.
    val session by tracking.session.collectAsStateWithLifecycle()
    val localFix = session.latest?.takeIf { session.owner == uid }
    var environment by remember { mutableStateOf(LocationEnvironment.read(context)) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            // Back from Settings or a permission dialog: restart tracking if it can run now, then keep watching.
            startDemoTracking(context, uid)
            while (true) {
                environment = LocationEnvironment.read(context)
                delay(3000)
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        environment = LocationEnvironment.read(context)
        startDemoTracking(context, uid)
        // Precise location refused without a dialog (the choice is fixed, e.g. after "Keep approximate"): the only
        // way left is the app's settings page. Checked on FINE, since COARSE alone is still granted then.
        if (result[android.Manifest.permission.ACCESS_FINE_LOCATION] != true && !shouldAskAgain(context)) openAppSettings(context)
    }
    val locationPermissions = arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
    fun locationAction(hint: LocationHint): Pair<String, () -> Unit>? = when (hint) {
        LocationHint.PERMISSION_MISSING -> "Allow location" to { permissionLauncher.launch(locationPermissions) }
        LocationHint.PRECISE_NOT_ALLOWED -> "Allow precise location" to {
            if (shouldAskAgain(context)) permissionLauncher.launch(locationPermissions) else openAppSettings(context)
        }
        LocationHint.LOCATION_OFF -> "Turn on location" to { openSettings(context, android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS) }
        LocationHint.AIRPLANE_MODE -> "Open settings" to { openSettings(context, android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS) }
        else -> null
    }

    val connecting by `in`.maitri.app.network.BackendLatency.connecting.collectAsStateWithLifecycle()

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
        val server = snapshot?.lastLocation?.takeIf { updated }
        val shown = shownLocation(server?.latitude, server?.longitude, server?.accuracyMeters,
            server?.recordedAt?.let { `in`.maitri.app.network.serverTimeMillis(it) }, localFix)
        val hint = locationHint(environment.airplane, environment.locationOn, environment.allowed, environment.precise,
            shown?.accuracyMeters?.toFloat(), shown?.shared == true, elapsedSeconds * 1000, environment.online)
        BoardLabel(24, 98, 330, locationLine(shown, hint),
            if (hint == LocationHint.NONE || hint == LocationHint.SEARCHING) p.secondary else p.accent)
        // Live map over the crop's map area (342 x 252 at 24,136): the phone's own fix at once, then the server's.
        MaitriMap(listOfNotNull(shown?.let { MapMarker(it.latitude, it.longitude, MarkerKind.PERSON_IN_NEED) }),
            locationHintText(hint) ?: "Finding your location…", Modifier.offset(24.dp, (136 - 28).dp).size(342.dp, 252.dp),
            accuracyMeters = shown?.accuracyMeters)
        locationAction(hint)?.let { (label, act) ->
            Text(label, Modifier.offset(36.dp, (136 - 28 + 12).dp).clip(RoundedCornerShape(16.dp))
                .background(p.surface).clickable(onClickLabel = label) { act() }
                .padding(horizontal = 14.dp, vertical = 12.dp), color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
            else statuses.forEach { Text("${it.name} · ${`in`.maitri.app.network.contactDeliveryLabel(it.status)}", Modifier.width(166.dp), color = p.primary, fontSize = 12.sp) }
        }
        BoardAction(24, 638, 165, 64, "Voice recorder") { openCapture(android.provider.MediaStore.Audio.Media.RECORD_SOUND_ACTION) }
        BoardAction(201, 638, 165, 64, "Open camera") { openCapture(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) }
        BoardLabel(66, 719, 280, when {
            connecting -> "Connecting to Maitri… · $timerString"
            updated -> "Updates connected · $timerString"
            else -> "Awaiting server update · $timerString"
        }, if (connecting) p.accent else p.secondary)
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
                                Text(settingsResources().getString(`in`.maitri.app.R.string.pin_set_from_sos), color = p.accent)
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


/** Phone settings that decide whether a location can be found and sent. Cheap reads, polled while screen 08 is open. */
internal data class LocationEnvironment(val airplane: Boolean, val locationOn: Boolean, val allowed: Boolean,
                                         val precise: Boolean, val online: Boolean) {
    companion object {
        fun read(context: android.content.Context) = LocationEnvironment(context.airplaneModeOn(), context.locationEnabled(),
            context.locationAllowed(), context.preciseLocationAllowed(), online(context))

        private fun online(context: android.content.Context): Boolean {
            val manager = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
            val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
            return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}

/** "Sending… · Approx. ±1.2 km · Last known 10:42", plus the reason on a second line when something needs fixing. */
internal fun locationLine(shown: ShownFix?, hint: LocationHint, now: Long = System.currentTimeMillis()): String {
    val reason = locationHintText(hint)
    if (shown == null) return reason ?: "Finding your location…"
    val parts = listOfNotNull(
        if (shown.shared) "Location shared" else "Sending…",
        accuracyLabel(shown.accuracyMeters),
        if (FixPolicy.isLastKnown(shown.timeMillis, now))
            "Last known " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(shown.timeMillis))
        else null)
    val status = parts.joinToString(" · ")
    return if (reason == null || hint == LocationHint.SEARCHING) status else status + "\n" + reason
}

private fun openSettings(context: android.content.Context, action: String) {
    try { context.startActivity(android.content.Intent(action)) }
    catch (_: android.content.ActivityNotFoundException) { openAppSettings(context) }
}

internal fun openAppSettings(context: android.content.Context) {
    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.fromParts("package", context.packageName, null)))
}

/**
 * Whether the system will still show the location dialog. Android gives no direct answer: it is true before the
 * first request and while a rationale is due; after a permanent denial only the settings page can grant it.
 */
internal fun shouldAskAgain(context: android.content.Context): Boolean {
    var c = context
    while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
    val activity = c as? android.app.Activity ?: return false
    val prefs = context.getSharedPreferences("location_permission", 0)
    val asked = prefs.getBoolean("asked", false)
    prefs.edit().putBoolean("asked", true).apply()
    return !asked || androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity,
        android.Manifest.permission.ACCESS_FINE_LOCATION)
}
