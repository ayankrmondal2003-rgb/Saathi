package `in`.maitri.app.ui

import `in`.maitri.app.tr

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import `in`.maitri.app.R
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import `in`.maitri.app.location.*
import `in`.maitri.app.network.*
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import retrofit2.HttpException

@Composable
fun TestResponderDashboard(uid: String, onApply: () -> Unit, onResumeJourney: (String) -> Unit, openInvitation: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val account = remember(uid) { ExpectedAccount(uid) }
    var profile by remember(uid) { mutableStateOf<ProfileDto?>(null) }
    var inbox by remember(uid) { mutableStateOf(emptyList<InvitationDto>()) }
    var preferenceMessage by remember { mutableStateOf("") }
    var locationStatusMessage by remember { mutableStateOf<String?>(null) }
    var inboxLoaded by remember(uid) { mutableStateOf(false) }
    var applicationStatus by remember(uid) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var permitted by remember { mutableStateOf(context.locationAllowed()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = context.locationAllowed()
    }
    var backgroundAllowed by remember { mutableStateOf(`in`.maitri.app.network.ResponderLocation.backgroundAllowed(context)) }
    var explainBackground by remember { mutableStateOf(false) }
    val backgroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        backgroundAllowed = `in`.maitri.app.network.ResponderLocation.backgroundAllowed(context)
        `in`.maitri.app.network.ResponderLocation.schedule(context, uid)
    }
    // Coming back from the system settings page also updates the state.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            backgroundAllowed = `in`.maitri.app.network.ResponderLocation.backgroundAllowed(context)
        }
    }
    if (explainBackground) androidx.compose.material3.AlertDialog(onDismissRequest = { explainBackground = false },
        title = { Text(tr("Get requests when Maitri is closed?")) },
        text = { Text(tr("While you're Available, Maitri will share your location with the server about every 15 minutes, even when the app is closed, so it can ask you to help someone nearby. ") +
            tr("Nobody sees your location unless you accept a request. On the next screen, choose \"Allow all the time\". Turn Available off at any time to stop.")) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = {
            explainBackground = false
            if (android.os.Build.VERSION.SDK_INT >= 29) backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }) { Text(tr("Continue")) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { explainBackground = false }) { Text(tr("Not now")) } })

    LaunchedEffect(uid, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    if (profile == null) profile = BackendClient.api.profile(account)
                    profile?.let { `in`.maitri.app.network.ResponderLocation.setAvailable(context, uid, it.optedIn && it.available) }
                    inbox = BackendClient.api.invitations(account).filter { it.canOpenIncoming() || it.status == "ACCEPTED" }
                    inboxLoaded = true
                    applicationStatus = BackendClient.api.volunteerStatus(account).status
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    preferenceMessage = tr("Could not refresh responder information")
                }
                delay(10000)
            }
        }
    }

    LaunchedEffect(uid, permitted, profile?.available, lifecycle) {
        if (!permitted || profile?.available != true) {
            locationStatusMessage = null
            return@LaunchedEffect
        }
        var lastSubmittedTimestamp = 0L
        var isSubmitting = false
        var hasResponse = false

        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            callbackFlow<LocationUpdate> {
                val source = FusedLocationSource(context)
                source.subscribe(
                    fix = { update -> trySend(update) },
                    status = { state ->
                        when (state) {
                            TrackingState.TRACKING -> {
                                if (!hasResponse) locationStatusMessage = tr("GPS signal acquired; waiting for first sync…")
                            }
                            TrackingState.STARTING -> {
                                locationStatusMessage = tr("Acquiring GPS fix...")
                            }
                            TrackingState.LOCATION_UNAVAILABLE -> {
                                if (!context.locationEnabled()) {
                                    locationStatusMessage = tr("Location unavailable (disabled in system settings)")
                                } else {
                                    locationStatusMessage = tr("Location unavailable (searching for GPS...)")
                                }
                            }
                            TrackingState.PERMISSION_REQUIRED -> {
                                locationStatusMessage = tr("Location permission required for responder mode")
                            }
                            TrackingState.ERROR -> {
                                locationStatusMessage = tr("Location provider error")
                            }
                            TrackingState.IDLE, TrackingState.STOPPING, TrackingState.STOPPED -> {
                                locationStatusMessage = null
                            }
                        }
                    }
                )
                awaitClose { source.cancel() }
            }.conflate().collect { fix ->
                // Client-side validation: valid coordinates and positive timestamp
                if (!fix.valid()) return@collect

                // Accuracy filter: ignore fixes worse than 100 meters
                if (fix.accuracyMeters > 100f) {
                    locationStatusMessage = tr("Waiting for better GPS accuracy ({0}m > 100m)", fix.accuracyMeters.toInt())
                    return@collect
                }

                // Age filter: ignore stale (> 60s) or future-dated (< -5s) fixes
                val ageMs = System.currentTimeMillis() - fix.timestampMillis
                if (ageMs > 60000L || ageMs < -5000L) {
                    return@collect
                }

                // Monotonic filter: ignore duplicate or out-of-order timestamps
                if (fix.timestampMillis <= lastSubmittedTimestamp) {
                    return@collect
                }

                // Concurrency control: avoid sending overlapping requests
                if (isSubmitting) return@collect
                isSubmitting = true

                try {
                    BackendClient.api.submitResponderFix(
                        FixDto(
                            latitude = fix.latitude,
                            longitude = fix.longitude,
                            recordedAt = Instant.ofEpochMilli(fix.timestampMillis).toString(),
                            mock = fix.isMock,
                            accuracyMeters = fix.accuracyMeters.toDouble()
                        ),
                        account
                    )
                    lastSubmittedTimestamp = fix.timestampMillis
                    hasResponse = true
                    locationStatusMessage = tr("Responder location updated")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HttpException) {
                    when (e.code()) {
                        409 -> {
                            // Server already has this timestamp or newer.
                            // Stale update: Recoverable conflict! Update lastSubmittedTimestamp so we do not retry this fix,
                            // and keep listening for newer GPS fixes without disabling tracking or marking permanently unavailable.
                            if (!staleResponderFix(e.code(), e.response()?.headers()?.get("X-Error-Code"))) {
                                locationStatusMessage = tr("Location conflict. Waiting for another fix.")
                                return@collect
                            }
                            hasResponse = true
                            lastSubmittedTimestamp = maxOf(lastSubmittedTimestamp, fix.timestampMillis)
                            locationStatusMessage = tr("Older GPS fix skipped. Waiting for a newer fix.")
                        }
                        401 -> {
                            locationStatusMessage = tr("Session expired. Sign in again.")
                        }
                        403 -> {
                            locationStatusMessage = tr("Responder consent and availability required (HTTP 403)")
                        }
                        422 -> {
                            locationStatusMessage = tr("Location rejected: check device clock and GPS accuracy (HTTP 422)")
                        }
                        else -> {
                            locationStatusMessage = tr("Location sync failed (HTTP {0})", e.code())
                        }
                    }
                } catch (e: IOException) {
                    locationStatusMessage = tr("Network interrupted; will retry on next GPS fix")
                } catch (e: Exception) {
                    locationStatusMessage = tr("Location sync error: {0}", e.localizedMessage ?: tr("Unknown error"))
                } finally {
                    isSubmitting = false
                }
            }
        }
    }

    fun update(optIn: Boolean, available: Boolean) {
        val current = profile ?: return
        busy = true
        scope.launch {
            try {
                profile = BackendClient.api.profile(ProfileRequest(current.name, optIn, available), account)
                profile?.let { `in`.maitri.app.network.ResponderLocation.setAvailable(context, uid, it.optedIn && it.available) }
                preferenceMessage = tr("Preferences saved")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                preferenceMessage = tr("Could not save preferences")
            } finally {
                busy = false
            }
        }
    }

    val palette = maitriPalette()
    Column(Modifier.fillMaxSize().background(palette.background).statusBarsPadding()
        .verticalScroll(rememberScrollState()).padding(bottom = LocalFloatingNavBarPadding.current)) {
        Image(painterResource(if (palette.dark) R.drawable.maitri_dark_responder else R.drawable.maitri_light_responder),
            tr("Nearby support. Responder. Illustrative map."), Modifier.fillMaxWidth().aspectRatio(390f / 466f))
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
            .background(palette.surface, RoundedCornerShape(22.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (applicationStatus in setOf("NOT_STARTED", "DRAFT", "REJECTED")) {
            OutlinedButton(onClick = onApply) { Text(tr("Volunteer application")) }
        }
        Text(tr("Community responder"))
        Switch(checked = profile?.optedIn == true, enabled = profile != null && !busy,
            onCheckedChange = { update(it, false) })
        Text(tr("Available for nearby requests"))
        Switch(checked = profile?.available == true, enabled = profile?.optedIn == true && !busy,
            onCheckedChange = { update(true, it) })
        if (profile?.available == true && !permitted) {
            Button(onClick = { permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text(tr("Allow location")) }
        }
        // Reachable while Maitri is closed: only with the volunteer's explicit "Allow all the time".
        if (profile?.available == true && permitted && !backgroundAllowed) {
            Text(tr("You'll only get requests while Maitri is open."), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Text(tr("To get SOS requests when Maitri is closed, allow location all the time."))
            OutlinedButton(onClick = { explainBackground = true }) { Text(tr("Get requests when Maitri is closed")) }
        } else if (profile?.available == true && backgroundAllowed) {
            Text(tr("You'll get nearby SOS requests even when Maitri is closed. Turn Available off to stop sharing your location."))
        }
        if (preferenceMessage.isNotEmpty()) Text(preferenceMessage)
        locationStatusMessage?.let { Text(it) }
        Text(tr("Invitations"))
        if (inbox.isEmpty()) Text(if (inboxLoaded) tr("No current invitations") else tr("Checking invitations…"))
        for (invitation in inbox) {
            if (invitation.status == "ACCEPTED") {
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val snapshot = BackendClient.api.snapshot(invitation.incidentId, account)
                            if (snapshot.authorizesJourney(invitation.incidentId)) onResumeJourney(invitation.incidentId)
                            else preferenceMessage = tr("Responder access has ended.")
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { preferenceMessage = tr("Could not confirm responder access.") }
                        finally { busy = false }
                    }
                }) { Text(tr("Resume responder journey")) }
            } else OutlinedButton(onClick = { openInvitation(invitation.id) }) { Text(tr("Open invitation")) }
        }
        }
    }
}

/** Responder tab without a server-backed session: same artwork, no invented invitations or availability. */
@Composable
fun ResponderUnavailable() {
    val palette = maitriPalette()
    Column(Modifier.fillMaxSize().background(palette.background).statusBarsPadding()
        .verticalScroll(rememberScrollState()).padding(bottom = LocalFloatingNavBarPadding.current)) {
        Image(painterResource(if (palette.dark) R.drawable.maitri_dark_responder else R.drawable.maitri_light_responder),
            tr("Nearby support. Responder. Illustrative map."), Modifier.fillMaxWidth().aspectRatio(390f / 466f))
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
            .background(palette.surface, RoundedCornerShape(22.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Responder mode unavailable"), color = palette.primary)
            Text(tr("Responder availability and invitations need a signed-in account connected to the Maitri server. ") +
                tr("Nothing is shared and no requests are received on this screen."), color = palette.secondary)
        }
    }
}
