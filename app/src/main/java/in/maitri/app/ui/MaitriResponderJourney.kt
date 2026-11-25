package `in`.maitri.app.ui

import `in`.maitri.app.tr

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.selection.selectableGroup
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
        Page(tr("Responder")) { Text(tr("Checking incident access…")) }
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
    fun dial(number: String = "112") {
        try { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))) }
        catch (_: android.content.ActivityNotFoundException) { message = tr("Dialer unavailable") }
    }
    // Only when the person in need agreed to share their number with responders (screen 14).
    val victimPhone = confirmed.victimPhone?.takeIf { it.isNotBlank() }
    var reporting by remember { mutableStateOf(false) }
    fun requestHelp() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val queued = BackendClient.api.requestHelp(incidentId, account).queued
                message = if (queued == 0) tr("No new eligible responders nearby.") else tr("Invitations queued: {0}", queued)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = tr("Additional invitations could not be confirmed.") }
            finally { busy = false }
        }
    }
    fun openMaps() {
        fix?.let {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${it.latitude},${it.longitude}?q=${it.latitude},${it.longitude}"))) }
            catch (_: android.content.ActivityNotFoundException) { message = tr("Maps unavailable") }
        }
    }
    MaitriResponderJourneyBoard(p, confirmed.requesterMembership?.assistanceStatus, fix?.let { it.latitude to it.longitude },
        busy, onStatus = { chooseStatus = true }, onMaps = ::openMaps, onHelp = ::requestHelp, onDial = { dial() },
        distanceMeters = distance, self = own, onCallPerson = victimPhone?.let { number -> { dial(number) } },
        onMedicalNotes = confirmed.medicalNotes?.takeIf { it.isNotBlank() }?.let { notes -> { message = tr("Medical notes\n\n{0}", notes) } })
    if (chooseStatus) MaitriChoiceDialog(title = tr("Responder status"), confirmLabel = tr("Done"),
        onConfirm = { chooseStatus = false }, onDismiss = { chooseStatus = false },
        footer = {
            TextButton(enabled = !busy, onClick = { chooseStatus = false; reporting = true }) {
                Text(tr("Report a false alarm or a problem"), color = p.accent)
            }
        }) {
            val current = confirmed.requesterMembership?.assistanceStatus
            for (status in listOf("ON_THE_WAY", "HELPING", "UNABLE_TO_HELP", "LEFT_INCIDENT")) {
                MaitriRadioRow(selected = current == status, enabled = !busy,
                    label = responderStatusLabel(status).let { if (status == "LEFT_INCIDENT") tr("Leave this SOS") else it }) {
                    busy = true
                    scope.launch {
                        try {
                            if (repo.updateAssistanceStatus(status)) {
                                chooseStatus = false
                                if (status == "LEFT_INCIDENT") onIncidentClosed()
                            } else message = tr("Status change could not be confirmed.")
                        } finally { busy = false }
                    }
                }
            }
        }
    if (reporting) {
        var reason by remember { mutableStateOf("FALSE_ALARM") }
        var note by remember { mutableStateOf("") }
        AlertDialog(modifier = Modifier.dialogEnter(), onDismissRequest = { reporting = false }, containerColor = p.surface,
            titleContentColor = p.primary, textContentColor = p.secondary, title = { Text(tr("Report this SOS")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Maitri reviews reports. The person in need isn't told."))
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("FALSE_ALARM" to tr("False alarm"), "UNSAFE" to tr("I felt unsafe"), "OTHER" to tr("Something else")).forEach { (value, label) ->
                        MaitriRadioRow(selected = reason == value, label = label) { reason = value }
                    }
                }
                OutlinedTextField(note, { note = it.take(500) }, label = { Text(tr("What happened (optional)")) })
            } },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        BackendClient.api.reportIncident(incidentId, ReportDto(reason, note), account)
                        reporting = false
                        message = tr("Thanks. Your report was sent.")
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { message = tr("The report couldn't be sent. Try again.") }
                    finally { busy = false }
                }
            }) { Text(tr("Send report"), color = p.accent, fontWeight = FontWeight.SemiBold) } },
            dismissButton = { TextButton(onClick = { reporting = false }) { Text(tr("Cancel"), color = p.accent) } })
    }
    message?.let { AlertDialog(modifier = Modifier.dialogEnter(), onDismissRequest = { message = null }, containerColor = p.surface,
        textContentColor = p.primary, text = { Text(it) },
        confirmButton = { TextButton(onClick = { message = null }) { Text(tr("Close"), color = p.accent) } }) }
}

/** Screen 07 board. Stateless: shows only server-confirmed status and the last reported coordinates, if any. */
@Composable
internal fun MaitriResponderJourneyBoard(p: MaitriPalette, assistanceStatus: String?, coordinates: Pair<Double, Double>?,
                                         busy: Boolean, onStatus: () -> Unit, onMaps: () -> Unit,
                                         onHelp: () -> Unit, onDial: () -> Unit, distanceMeters: Double? = null,
                                         self: Pair<Double, Double>? = null, onCallPerson: (() -> Unit)? = null,
                                         onMedicalNotes: (() -> Unit)? = null) {
    EmergencyBoard(if (p.dark) R.drawable.maitri_dark_07 else R.drawable.maitri_light_07,
        tr("On your way. Keep both of you safe. Request more responders before you approach.")) {
        BoardLabel(84, 59, 270, tr("RESPONDER · ") + (assistanceStatus?.let { responderStatusLabel(it) } ?: tr("Accepted")).uppercase(), p.accent, 11)
        ArtText(tr("On your way"), 84f, 107.7f, 280f, 26f, p.primary, FontWeight.SemiBold)
        ArtText(tr("Keep both of you safe"), 24f, 612.9f, 340f, 16f, p.primary, FontWeight.SemiBold)
        ArtText(tr("Request more responders before you approach."), 24f, 636.9f, 342f, 11f, p.secondary)
        ArtText(tr("Bring in help"), 24f, 682.9f, 226f, 14f, if (p.dark) p.background else Color.White, FontWeight.SemiBold,
            center = true)
        ArtText(tr("Contact emergency services"), 24f, 727.7f, 300f, 11f, p.accent, FontWeight.Medium)
        BoardAction(24, 51, 342, 65, tr("Update responder status"), !busy, onStatus)
        // Live map over the crop's map area (342 x 320 at 24,140): the person in need and, when known, you.
        MaitriMap(listOfNotNull(coordinates?.let { MapMarker(it.first, it.second, MarkerKind.PERSON_IN_NEED) },
            self?.let { MapMarker(it.first, it.second, MarkerKind.YOU) }),
            tr("Awaiting location"), Modifier.offset(24.dp, (140 - 28).dp).size(342.dp, 320.dp))
        // With medical notes the title stops short of the link, so neither runs into the other at large text sizes.
        BoardLabel(40, 489, if (onMedicalNotes != null) 190 else 308, tr("Person in need"), p.primary, 16, height = 22, maxLines = 1)
        // Shared by the person in need for this SOS only; gone when it ends.
        if (onMedicalNotes != null) {
            BoardLabel(236, 491, 120, tr("Medical notes ›"), p.accent, 12, height = 20, maxLines = 1)
            BoardAction(228, 480, 130, 32, tr("Read their medical notes"), onClick = onMedicalNotes)
        }
        BoardLabel(40, 515, 308, coordinates?.let { "${it.first}, ${it.second}" } ?: tr("Awaiting location"), p.secondary)
        // Two lines at most, kept inside the card (its bottom edge is 34 units below).
        BoardLabel(40, 546, 306, height = 32, label = when {
            coordinates == null -> tr("Distance and ETA unavailable")
            distanceMeters != null -> approxDistanceAndWalk(distanceMeters) + tr(" · Open in Maps")
            else -> tr("Open last reported location in Maps")
        }, color = p.accent)
        BoardAction(24, 476, 342, 104, tr("Open last reported location in Maps"), coordinates != null, onMaps)
        BoardAction(24, 654, 226, 48, tr("Bring in help"), !busy, onHelp)
        // The artwork's "Call" pill calls the person in need, only with their consent; otherwise it's covered.
        if (onCallPerson != null) {
            ArtText(tr("Call"), 309.8f, 683.2f, 54f, 14f, p.accent, FontWeight.SemiBold)
            BoardAction(262, 654, 104, 48, tr("Call the person in need"), onClick = onCallPerson)
        } else Box(Modifier.offset(258.dp, (650 - 28).dp).size(112.dp, 56.dp).background(p.background))
        BoardAction(24, 704, 220, 44, tr("Contact emergency services"), onClick = onDial)
    }
}

data class ReportDto(@com.google.gson.annotations.SerializedName("reason") val reason: String,
                     @com.google.gson.annotations.SerializedName("note") val note: String)
