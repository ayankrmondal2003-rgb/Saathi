package `in`.maitri.app.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.maitri.app.R
import `in`.maitri.app.location.IncidentTrackingRepository
import `in`.maitri.app.location.IncidentTrackingState
import `in`.maitri.app.network.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.CancellationException

@Composable
fun MaitriResponderJourney(incidentId: String, uid: String, onIncidentClosed: () -> Unit) {
    val p = maitriPalette()
    val context = LocalContext.current
    val account = remember(uid) { ExpectedAccount(uid) }
    val repo = remember(incidentId, uid) { IncidentTrackingRepository(incidentId) }
    val live by repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var authorized by remember { mutableStateOf<IncidentSnapshotDto?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var chooseStatus by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(repo, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                while (true) {
                    try {
                        val snapshot = BackendClient.api.snapshot(incidentId, account)
                        if (!snapshot.authorizesJourney(incidentId)) {
                            onIncidentClosed()
                            return@repeatOnLifecycle
                        }
                        authorized = snapshot
                        repo.startTracking()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        authorized = null
                        if (e is retrofit2.HttpException && e.code() in listOf(401, 403, 404, 410)) {
                            onIncidentClosed()
                            return@repeatOnLifecycle
                        }
                    }
                    delay(5000)
                }
            } finally {
                authorized = null
                repo.stopTracking()
            }
        }
    }
    if (live.status in setOf("CLOSED", "REVOKED")) {
        LaunchedEffect(Unit) { onIncidentClosed() }
        return
    }
    val confirmed = authorized
    if (confirmed == null) {
        Page("Responder") { Text("Checking incident access…") }
        return
    }
    val fix = listOfNotNull(confirmed.lastLocation, live.latestLocation).maxByOrNull {
        runCatching { java.time.Instant.parse(it.recordedAt).toEpochMilli() }.getOrDefault(0L)
    }
    // The responder's own recent fix: the "You" marker and an approximate straight-line distance and walk.
    var own by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    LaunchedEffect(Unit) { while (true) {
        val permitted = androidx.core.content.ContextCompat.checkSelfPermission(context,
            android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        own = if (!permitted) null else try {
            @Suppress("MissingPermission")
            val last: android.location.Location? = com.google.android.gms.location.LocationServices
                .getFusedLocationProviderClient(context).lastLocation.await()
            last?.takeIf { System.currentTimeMillis() - it.time < 5 * 60_000 }?.let { it.latitude to it.longitude }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        kotlinx.coroutines.delay(15_000)
    } }
    val distance = if (fix != null && own != null)
        straightLineMeters(own!!.first, own!!.second, fix.latitude, fix.longitude) else null
    fun dial() {
        try { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))) }
        catch (_: android.content.ActivityNotFoundException) { message = "Dialer unavailable" }
    }
    fun requestHelp() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val queued = BackendClient.api.requestHelp(incidentId, account).queued
                message = if (queued == 0) "No new eligible responders nearby." else "Invitations queued: $queued"
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Additional invitations could not be confirmed." }
            finally { busy = false }
        }
    }
    fun openMaps() {
        fix?.let {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${it.latitude},${it.longitude}?q=${it.latitude},${it.longitude}"))) }
            catch (_: android.content.ActivityNotFoundException) { message = "Maps unavailable" }
        }
    }
    MaitriResponderJourneyBoard(p, confirmed.requesterMembership?.assistanceStatus, fix?.let { it.latitude to it.longitude },
        busy, onStatus = { chooseStatus = true }, onMaps = ::openMaps, onHelp = ::requestHelp, onDial = ::dial,
        distanceMeters = distance, self = own)
    if (chooseStatus) AlertDialog(onDismissRequest = { chooseStatus = false }, title = { Text("Responder status") },
        text = { Column {
            for (status in listOf("ON_THE_WAY", "HELPING", "UNABLE_TO_HELP", "LEFT_INCIDENT")) {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            if (repo.updateAssistanceStatus(status)) {
                                chooseStatus = false
                                if (status == "LEFT_INCIDENT") onIncidentClosed()
                            } else message = "Status change could not be confirmed."
                        } finally { busy = false }
                    }
                }) { Text(status.replace('_', ' ')) }
            }
        } }, confirmButton = {})
    message?.let { AlertDialog(onDismissRequest = { message = null }, text = { Text(it) },
        confirmButton = { TextButton(onClick = { message = null }) { Text("Close") } }) }
}

/** Screen 07 board. Stateless: shows only server-confirmed status and the last reported coordinates, if any. */
@Composable
internal fun MaitriResponderJourneyBoard(p: MaitriPalette, assistanceStatus: String?, coordinates: Pair<Double, Double>?,
                                         busy: Boolean, onStatus: () -> Unit, onMaps: () -> Unit,
                                         onHelp: () -> Unit, onDial: () -> Unit, distanceMeters: Double? = null,
                                         self: Pair<Double, Double>? = null) {
    EmergencyBoard(if (p.dark) R.drawable.maitri_dark_07 else R.drawable.maitri_light_07,
        "On your way. Keep both of you safe. Request more responders before you approach.") {
        BoardLabel(84, 59, 270, "RESPONDER · " + (assistanceStatus ?: "AUTHORIZED").replace('_', ' '), p.accent, 11)
        BoardAction(24, 51, 342, 65, "Update responder status", !busy, onStatus)
        // Live map over the crop's map area (342 x 320 at 24,140): the person in need and, when known, you.
        MaitriMap(listOfNotNull(coordinates?.let { MapMarker(it.first, it.second, MarkerKind.PERSON_IN_NEED) },
            self?.let { MapMarker(it.first, it.second, MarkerKind.YOU) }),
            "Awaiting location", Modifier.offset(24.dp, (140 - 28).dp).size(342.dp, 320.dp))
        BoardLabel(40, 489, 308, "Person in need", p.primary, 16)
        BoardLabel(40, 515, 308, coordinates?.let { "${it.first}, ${it.second}" } ?: "Awaiting location", p.secondary)
        BoardLabel(40, 546, 306, when {
            coordinates == null -> "Distance and ETA unavailable"
            distanceMeters != null -> approxDistanceAndWalk(distanceMeters) + " · Open in Maps"
            else -> "Open last reported location in Maps"
        }, p.accent)
        BoardAction(24, 476, 342, 104, "Open last reported location in Maps", coordinates != null, onMaps)
        BoardAction(24, 654, 226, 48, "Bring in help", !busy, onHelp)
        BoardAction(262, 654, 104, 48, "Call emergency services", onClick = onDial)
        BoardAction(24, 704, 220, 44, "Contact emergency services", onClick = onDial)
    }
}
