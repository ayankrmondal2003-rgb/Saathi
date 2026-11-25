package `in`.maitri.app.ui

import `in`.maitri.app.tr

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

    var smsAlert by remember(uid, incidentId) { mutableStateOf<`in`.maitri.app.network.CircleAlertDto?>(null) }
    var showSmsOffer by rememberSaveable(uid, incidentId) { mutableStateOf(false) }
    val smsPrompts = remember(context) { context.getSharedPreferences("sos_sms_prompts", android.content.Context.MODE_PRIVATE) }
    // The composer returning is all Maitri can know: recorded as "SMS prepared", never as sent.
    val smsComposer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val ids = smsAlert?.smsContacts.orEmpty().map { it.contactId }
        if (ids.isNotEmpty()) scope.launch {
            try { BackendClient.circle.smsPrepared(incidentId, `in`.maitri.app.network.SmsPreparedRequest(ids), ExpectedAccount(uid)) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        }
    }
    fun textCircle(whatsApp: Boolean) {
        val alert = smsAlert ?: return
        smsPrompts.edit().putBoolean("$uid:$incidentId", true).apply()
        val text = `in`.maitri.app.network.sosSmsText(alert.liveLink)
        try {
            if (whatsApp) context.startActivity(`in`.maitri.app.network.shareTextIntent(text))
            else smsComposer.launch(`in`.maitri.app.network.smsComposerIntent(alert.smsContacts.map { it.phone }, text))
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(context, tr("No messaging app found"), Toast.LENGTH_SHORT).show()
        }
    }
    var delivery by remember(uid, incident.started) { mutableStateOf<List<`in`.maitri.app.network.ContactDeliveryDto>?>(null) }
    var updated by remember { mutableStateOf(false) }
    // SOS voice notes and photos: captured here, uploaded in the background (see network/SosMedia.kt).
    val mediaPrefs = remember(context) { context.getSharedPreferences("sos_media", android.content.Context.MODE_PRIVATE) }
    var recorder by remember { mutableStateOf<android.media.MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<java.io.File?>(null) }
    var recordingStarted by remember { mutableLongStateOf(0L) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var askVoiceConsent by remember { mutableStateOf(false) }
    var photoFile by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingMedia by remember(incidentId) {
        androidx.work.WorkManager.getInstance(context).getWorkInfosByTagFlow("${`in`.maitri.app.network.SosMedia.TAG}:$incidentId")
    }.collectAsStateWithLifecycle(emptyList())
    val voiceStatus = mediaStatus(pendingMedia.filter { `in`.maitri.app.network.SosMedia.kindTag("SOS_VOICE") in it.tags }.map { it.state })
    val photoStatus = mediaStatus(pendingMedia.filter { `in`.maitri.app.network.SosMedia.kindTag("SOS_PHOTO") in it.tags }.map { it.state })
    fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        val file = recordingFile
        recordingFile = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        if (ok && file != null && file.length() > 0)
            `in`.maitri.app.network.SosMedia.enqueue(context, file, "SOS_VOICE", "audio/mp4", incidentId)
        else file?.delete()
    }
    fun startRecording() {
        val file = `in`.maitri.app.network.SosMedia.newFile(context, "voice", "m4a")
        try {
            val r = `in`.maitri.app.network.SosMedia.recorder(context, file)
            r.setOnInfoListener { _, what, _ ->
                if (what == android.media.MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopRecording()
            }
            r.start()
            recorder = r; recordingFile = file; recordingStarted = SystemClock.elapsedRealtime()
        } catch (_: Exception) {
            file.delete()
            Toast.makeText(context, tr("Couldn't start recording"), Toast.LENGTH_SHORT).show()
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else Toast.makeText(context, tr("Allow the microphone to record a voice note"), Toast.LENGTH_LONG).show()
    }
    fun voice() {
        when {
            recorder != null -> stopRecording()
            !mediaPrefs.getBoolean("voice_consent", false) -> askVoiceConsent = true
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED -> micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
            else -> startRecording()
        }
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = photoFile?.let { java.io.File(it) }
        photoFile = null
        if (saved && file != null && file.length() > 0)
            `in`.maitri.app.network.SosMedia.enqueue(context, file, "SOS_PHOTO", "image/jpeg", incidentId)
        else file?.delete()
    }
    fun camera() {
        val file = `in`.maitri.app.network.SosMedia.newFile(context, "photo", "jpg")
        photoFile = file.absolutePath
        try {
            takePicture.launch(androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file))
        } catch (_: android.content.ActivityNotFoundException) {
            photoFile = null; file.delete()
            Toast.makeText(context, tr("No camera app available"), Toast.LENGTH_SHORT).show()
        } catch (_: IllegalArgumentException) {
            // Never let a camera setup problem close the app in the middle of an SOS.
            photoFile = null; file.delete()
            Toast.makeText(context, tr("Couldn't open the camera"), Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(recorder) {
        while (recorder != null) { recordingSeconds = ((SystemClock.elapsedRealtime() - recordingStarted) / 1000).toInt(); delay(500) }
    }
    DisposableEffect(Unit) { onDispose { stopRecording() } }

    if (askVoiceConsent) androidx.compose.material3.AlertDialog(onDismissRequest = { askVoiceConsent = false },
        containerColor = p.surface, titleContentColor = p.primary, textContentColor = p.secondary,
        title = { Text(tr("Record a voice note?")) },
        text = { Text(tr("Up to 60 seconds, recorded only while you can see the red indicator. ") +
            tr("People in your circle who got this SOS can play it, along with any photos you take. Volunteer responders can't. It's deleted after 30 days.")) },
        confirmButton = { TextButton(onClick = {
            askVoiceConsent = false
            mediaPrefs.edit().putBoolean("voice_consent", true).apply()
            voice()
        }) { Text(tr("Record"), color = p.accent) } },
        dismissButton = { TextButton(onClick = { askVoiceConsent = false }) { Text(tr("Cancel"), color = p.secondary) } })
    if (showSmsOffer) smsAlert?.let { alert ->
        val names = alert.smsContacts.joinToString { it.name }
        androidx.compose.material3.AlertDialog(onDismissRequest = { showSmsOffer = false },
            containerColor = p.surface, titleContentColor = p.primary, textContentColor = p.secondary,
            title = { Text(tr("Text your circle")) },
            text = { Text((if (alert.smsContacts.size == 1) tr("{0} doesn't use Maitri. Open your messaging app with ", names) else tr("{0} don't use Maitri. Open your messaging app with ", names)) +
                tr("your live location filled in? You tap Send.")) },
            confirmButton = { TextButton(onClick = { showSmsOffer = false; textCircle(false) }) { Text(tr("Open messages"), color = p.accent) } },
            dismissButton = { Row {
                TextButton(onClick = { showSmsOffer = false; textCircle(true) }) { Text(tr("WhatsApp…"), color = p.accent) }
                TextButton(onClick = { showSmsOffer = false; smsPrompts.edit().putBoolean("$uid:$incidentId", true).apply() }) {
                    Text(tr("Not now"), color = p.secondary) }
            } })
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
            title = { Text(tr("Set your Safety PIN")) },
            text = { Text(tr("Your SOS is running. You'll need a 6-digit Safety PIN to end it, so set one now.")) },
            confirmButton = { TextButton(onClick = { pinPromptDismissed = true; onSetPin() }) { Text(tr("Set PIN"), color = p.accent) } },
            dismissButton = { TextButton(onClick = { pinPromptDismissed = true }) { Text(tr("Later"), color = p.accent) } })
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
                        val alert = BackendClient.circle.alert(id, ExpectedAccount(uid))
                        circleAlerted = true
                        smsAlert = alert
                        // Offer once per incident to text the contacts who don't use Maitri.
                        if (alert.smsContacts.isNotEmpty() && !smsPrompts.getBoolean("$uid:$id", false)) showSmsOffer = true
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
        LocationHint.PERMISSION_MISSING -> tr("Allow location") to { permissionLauncher.launch(locationPermissions) }
        LocationHint.PRECISE_NOT_ALLOWED -> tr("Allow precise location") to {
            if (shouldAskAgain(context)) permissionLauncher.launch(locationPermissions) else openAppSettings(context)
        }
        LocationHint.LOCATION_OFF -> tr("Turn on location") to { openSettings(context, android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS) }
        LocationHint.AIRPLANE_MODE -> tr("Open settings") to { openSettings(context, android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS) }
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
            pinError = tr("PIN must be 6 digits.")
            return
        }

        isVerifyingPin = true
        pinError = null

        scope.launch {
            try {
                val activeId = tracking.sync.getActiveIncidentId(uid)
                if (activeId != incidentId) {
                    pinError = tr("Unable to confirm this incident.")
                    return@launch
                }
                val result = BackendClient.api.resolveIncident(
                    id = activeId,
                    request = IncidentResolutionRequestDto(pin = pinValue, reason = "USER_RESOLVED"),
                    expected = ExpectedAccount(uid)
                )
                if (tracking.sync.getActiveIncidentId(uid) != incidentId || result.incidentId != activeId || result.status != "CLOSED" || result.resolutionStatus != "CONFIRMED") {
                    pinError = tr("Closure has not been confirmed.")
                    return@launch
                }
                tracking.sync.clearActiveIncident(uid)
                showPinDialog = false
                Toast.makeText(context, tr("Incident safely resolved."), Toast.LENGTH_SHORT).show()
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
        tr("SOS is active. Circle notified.")) {
        ArtText(tr("SOS is active"), 24f, 85.3f, 250f, 27f, p.primary, FontWeight.SemiBold)
        ArtText(tr("End SOS"), 282f, 83.1f, 84f, 11.5f, p.accent, FontWeight.SemiBold, center = true)
        ArtText(tr("Circle notified"), 24f, 557.2f, 300f, 17f, p.primary, FontWeight.SemiBold)
        val server = snapshot?.lastLocation?.takeIf { updated }
        val shown = shownLocation(server?.latitude, server?.longitude, server?.accuracyMeters,
            server?.recordedAt?.let { `in`.maitri.app.network.serverTimeMillis(it) }, localFix)
        val hint = locationHint(environment.airplane, environment.locationOn, environment.allowed, environment.precise,
            shown?.accuracyMeters?.toFloat(), shown?.shared == true, elapsedSeconds * 1000, environment.online)
        BoardLabel(24, 98, 330, locationLine(shown, hint),
            if (hint == LocationHint.NONE || hint == LocationHint.SEARCHING) p.secondary else p.accent)
        // Live map over the crop's map area (342 x 252 at 24,136): the phone's own fix at once, then the server's.
        MaitriMap(listOfNotNull(shown?.let { MapMarker(it.latitude, it.longitude, MarkerKind.PERSON_IN_NEED) }) +
            snapshot?.activeResponders.orEmpty().mapNotNull { r ->
                if (r.latitude != null && r.longitude != null) MapMarker(r.latitude, r.longitude, MarkerKind.RESPONDER) else null
            },
            locationHintText(hint) ?: tr("Finding your location…"), Modifier.offset(24.dp, (136 - 28).dp).size(342.dp, 252.dp),
            accuracyMeters = shown?.accuracyMeters)
        locationAction(hint)?.let { (label, act) ->
            Text(label, Modifier.offset(36.dp, (136 - 28 + 12).dp).clip(RoundedCornerShape(16.dp))
                .background(p.surface).clickable(onClickLabel = label) { act() }
                .padding(horizontal = 14.dp, vertical = 12.dp), color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        BoardAction(282, 56, 84, 48, tr("End SOS")) { showPinDialog = true; pinValue = ""; pinError = null }
        BoardLabel(24, 405, 342, snapshot?.let { s ->
            val n = s.activeResponders.size
            if (n == 0) tr("No responder yet") else if (n == 1) tr("1 responder accepted") else tr("{0} responders accepted", n)
        } ?: tr("Checking responders…"), p.primary, 16)
        Column(Modifier.offset(40.dp, 412.dp).size(310.dp, 80.dp).verticalScroll(rememberScrollState())) {
            val responders = snapshot?.activeResponders.orEmpty()
            if (snapshot != null && responders.isEmpty()) Text(tr("Nearby volunteers have been asked. Your circle has your live location."),
                color = p.secondary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
            responders.forEach { responder ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(responder.displayName ?: tr("Responder"), color = p.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(responderStatusLabel(responder.assistanceStatus) +
                            (responder.distanceMeters?.let { " · ≈ " + distanceLabel(it) + tr(" away") } ?: ""),
                            color = p.secondary, fontSize = 11.sp)
                    }
                    responder.distanceMeters?.let {
                        Text(tr("approx. {0} min", walkingMinutes(it)), color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Row(Modifier.offset(30.dp, 549.dp).width(330.dp).horizontalScroll(rememberScrollState())) {
            val statuses = delivery
            if (statuses.isNullOrEmpty()) Text(if (statuses == null) tr("Delivery unconfirmed") else tr("No notifications recorded"), color = p.secondary)
            else statuses.forEach { Text("${it.name} · ${`in`.maitri.app.network.circleStatusLabel(it.status)}", Modifier.width(166.dp),
                color = if (`in`.maitri.app.network.circleStatusReached(it.status)) p.primary else p.secondary, fontSize = 12.sp) }
            if (smsAlert?.smsContacts?.isNotEmpty() == true) Text(tr("Text circle"), Modifier.clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = tr("Text your circle")) { showSmsOffer = true }.padding(horizontal = 8.dp),
                color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        // Tile captions: "Tap to record" / "Tap to open", or what that tile's capture is doing right now.
        val voiceLine = if (recorder != null) tr("● Recording {0} · tap to stop", "%d:%02d".format(recordingSeconds / 60, recordingSeconds % 60))
            else voiceStatus
        ArtText(tr("Voice"), 72f, 663.1f, 112f, 14f, p.accent, FontWeight.SemiBold)
        ArtText(voiceLine ?: tr("Tap to record"), 72f, 683.9f, 112f, 10f, if (voiceLine != null) p.accent else p.secondary,
            if (voiceLine != null) FontWeight.SemiBold else FontWeight.Normal)
        ArtText(tr("Camera"), 249.5f, 663.1f, 112f, 14f, p.accent, FontWeight.SemiBold)
        ArtText(photoStatus ?: tr("Tap to open"), 249.5f, 683.9f, 112f, 10f, if (photoStatus != null) p.accent else p.secondary,
            if (photoStatus != null) FontWeight.SemiBold else FontWeight.Normal)
        BoardAction(24, 638, 165, 64, if (recorder != null) tr("Stop recording") else tr("Record a voice note")) { voice() }
        BoardAction(201, 638, 165, 64, tr("Take a photo")) { camera() }
        BoardLabel(66, 719, 280, when {
            connecting -> tr("Connecting to Maitri… · {0}", timerString)
            updated -> tr("Updates connected · {0}", timerString)
            else -> tr("Awaiting server update · {0}", timerString)
        }, if (connecting) p.accent else p.secondary)
    }

    // Safety PIN Resolution Modal
    if (showPinDialog) {
        AlertDialog(modifier = Modifier.dialogEnter(), 
            onDismissRequest = { if (!isVerifyingPin) showPinDialog = false },
            title = {
                Text(
                    text = tr("Enter Safety PIN to End SOS"),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = p.primary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = tr("Your Safety PIN is required to confirm you are safe and de-escalate emergency responders."),
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
                        label = { Text(tr("6-digit PIN")) },
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
                        CircularProgressIndicator(color = p.onAccent, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(tr("Confirm End SOS"), color = p.onAccent, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showPinDialog = false },
                    enabled = !isVerifyingPin
                ) { Text(tr("Cancel"), color = p.secondary) }
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
    if (shown == null) return reason ?: tr("Finding your location…")
    val parts = listOfNotNull(
        if (shown.shared) tr("Location shared") else tr("Sending…"),
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

/** "Uploading 1…", "2 saved", "1 not sent", or null when nothing of that kind was captured. Pure, for tests. */
internal fun mediaStatus(states: List<androidx.work.WorkInfo.State>): String? {
    val uploading = states.count { !it.isFinished }
    val saved = states.count { it == androidx.work.WorkInfo.State.SUCCEEDED }
    val failed = states.count { it == androidx.work.WorkInfo.State.FAILED || it == androidx.work.WorkInfo.State.CANCELLED }
    return when {
        uploading > 0 -> tr("Uploading {0}…", uploading)
        saved > 0 && failed == 0 -> tr("{0} saved", saved)
        failed > 0 -> tr("{0} not sent", failed)
        else -> null
    }
}

internal fun responderStatusLabel(status: String) = when (status) {
    "ACCEPTED" -> tr("Accepted")
    "ON_THE_WAY" -> tr("On the way")
    "HELPING" -> tr("With you")
    "UNABLE_TO_HELP" -> tr("Can't help")
    else -> status.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}
