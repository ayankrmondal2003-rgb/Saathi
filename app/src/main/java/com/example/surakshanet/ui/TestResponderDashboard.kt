package com.example.surakshanet.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.surakshanet.R
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.surakshanet.location.*
import com.example.surakshanet.network.*
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

    LaunchedEffect(uid, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    if (profile == null) profile = BackendClient.api.profile(account)
                    inbox = BackendClient.api.invitations(account).filter { it.canOpenIncoming() || it.status == "ACCEPTED" }
                    inboxLoaded = true
                    applicationStatus = BackendClient.api.volunteerStatus(account).status
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    preferenceMessage = "Could not refresh responder information"
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
                                if (!hasResponse) locationStatusMessage = "GPS signal acquired; waiting for first sync…"
                            }
                            TrackingState.STARTING -> {
                                locationStatusMessage = "Acquiring GPS fix..."
                            }
                            TrackingState.LOCATION_UNAVAILABLE -> {
                                if (!context.locationEnabled()) {
                                    locationStatusMessage = "Location unavailable (disabled in system settings)"
                                } else {
                                    locationStatusMessage = "Location unavailable (searching for GPS...)"
                                }
                            }
                            TrackingState.PERMISSION_REQUIRED -> {
                                locationStatusMessage = "Location permission required for responder mode"
                            }
                            TrackingState.ERROR -> {
                                locationStatusMessage = "Location provider error"
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
                    locationStatusMessage = "Waiting for better GPS accuracy (${fix.accuracyMeters.toInt()}m > 100m)"
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
                    locationStatusMessage = "Responder location updated"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HttpException) {
                    when (e.code()) {
                        409 -> {
                            // Server already has this timestamp or newer.
                            // Stale update: Recoverable conflict! Update lastSubmittedTimestamp so we do not retry this fix,
                            // and keep listening for newer GPS fixes without disabling tracking or marking permanently unavailable.
                            if (!staleResponderFix(e.code(), e.response()?.headers()?.get("X-Error-Code"))) {
                                locationStatusMessage = "Location conflict. Waiting for another fix."
                                return@collect
                            }
                            hasResponse = true
                            lastSubmittedTimestamp = maxOf(lastSubmittedTimestamp, fix.timestampMillis)
                            locationStatusMessage = "Older GPS fix skipped. Waiting for a newer fix."
                        }
                        401 -> {
                            locationStatusMessage = "Session expired. Sign in again."
                        }
                        403 -> {
                            locationStatusMessage = "Responder consent and availability required (HTTP 403)"
                        }
                        422 -> {
                            locationStatusMessage = "Location rejected: check device clock and GPS accuracy (HTTP 422)"
                        }
                        else -> {
                            locationStatusMessage = "Location sync failed (HTTP ${e.code()})"
                        }
                    }
                } catch (e: IOException) {
                    locationStatusMessage = "Network interrupted; will retry on next GPS fix"
                } catch (e: Exception) {
                    locationStatusMessage = "Location sync error: ${e.localizedMessage ?: "Unknown error"}"
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
                preferenceMessage = "Preferences saved"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                preferenceMessage = "Could not save preferences"
            } finally {
                busy = false
            }
        }
    }

    val palette = maitriPalette()
    Column(Modifier.fillMaxSize().background(palette.background).statusBarsPadding()
        .verticalScroll(rememberScrollState()).padding(bottom = LocalFloatingNavBarPadding.current)) {
        Image(painterResource(if (palette.dark) R.drawable.maitri_dark_responder else R.drawable.maitri_light_responder),
            "Nearby support. Responder. Illustrative map.", Modifier.fillMaxWidth().aspectRatio(390f / 466f))
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
            .background(palette.surface, RoundedCornerShape(22.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (applicationStatus in setOf("NOT_STARTED", "DRAFT", "REJECTED")) {
            OutlinedButton(onClick = onApply) { Text("Volunteer application") }
        }
        Text("Community responder")
        Switch(checked = profile?.optedIn == true, enabled = profile != null && !busy,
            onCheckedChange = { update(it, false) })
        Text("Share my location while this dashboard is open")
        Switch(checked = profile?.available == true, enabled = profile?.optedIn == true && !busy,
            onCheckedChange = { update(true, it) })
        if (profile?.available == true && !permitted) {
            Button(onClick = { permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Allow location") }
        }
        if (preferenceMessage.isNotEmpty()) Text(preferenceMessage)
        locationStatusMessage?.let { Text(it) }
        Text("Invitations")
        if (inbox.isEmpty()) Text(if (inboxLoaded) "No current invitations" else "Checking invitations…")
        for (invitation in inbox) {
            if (invitation.status == "ACCEPTED") {
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val snapshot = BackendClient.api.snapshot(invitation.incidentId, account)
                            if (snapshot.authorizesJourney(invitation.incidentId)) onResumeJourney(invitation.incidentId)
                            else preferenceMessage = "Responder access has ended."
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { preferenceMessage = "Could not confirm responder access." }
                        finally { busy = false }
                    }
                }) { Text("Resume responder journey") }
            } else OutlinedButton(onClick = { openInvitation(invitation.id) }) { Text("Open invitation") }
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
            "Nearby support. Responder. Illustrative map.", Modifier.fillMaxWidth().aspectRatio(390f / 466f))
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
            .background(palette.surface, RoundedCornerShape(22.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Responder mode unavailable", color = palette.primary)
            Text("Responder availability and invitations need a signed-in account connected to the Maitri server. " +
                "Nothing is shared and no requests are received on this screen.", color = palette.secondary)
        }
    }
}
