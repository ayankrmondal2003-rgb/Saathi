package `in`.maitri.app.ui

import `in`.maitri.app.tr

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.location.LocationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import `in`.maitri.app.BuildConfig
import `in`.maitri.app.location.locationAllowed
import `in`.maitri.app.network.*
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.time.Instant
import java.util.UUID

internal val TRIP_MINUTES = listOf(15, 30, 60, 120)
internal val CHECK_IN_MINUTES = listOf(15, 30, 60, 120, 240)

internal fun minutesLabel(minutes: Int) = if (minutes < 60) tr("{0} min", minutes) else "${minutes / 60} h"

/** The owner's own open session of this kind, if any (a check-in stays "open" after it's missed until checked in). */
internal fun ownOpenSession(sessions: List<CircleSessionDto>, kind: String) =
    sessions.firstOrNull { it.owner && it.kind == kind && it.status == "ACTIVE" }

/**
 * Screen 02 "Share my trip" and "Check in". Both go to circle members who use Maitri (linked on screen 05).
 * A trip shares this phone's location while Maitri is open; a check-in shares no location, and the server tells
 * the circle if the owner doesn't check in by the time they chose.
 */
@Composable
fun MaitriSharingDialog(kind: String, dismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val p = maitriPalette()
    val scope = rememberCoroutineScope()
    val uid = remember { FirebaseAuth.getInstance().currentUser?.uid }
    val expected = remember(uid) { uid?.let(::ExpectedAccount) }
    // Only a random request key is persisted, so an uncertain network retry can't create a second session.
    val requestStore = remember { context.getSharedPreferences("circle_request_ids", android.content.Context.MODE_PRIVATE) }
    val requestStoreKey = "$uid:$kind"
    val trip = kind == "TRIP"
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var links by remember { mutableStateOf<List<CircleLinkDto>>(emptyList()) }
    var sessions by remember { mutableStateOf<List<CircleSessionDto>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>?>(null) }
    var minutes by remember { mutableIntStateOf(if (trip) 30 else 60) }
    var consent by remember { mutableStateOf(false) }
    var sharingGps by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<String, CircleShareRequest>?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        error = if (context.locationAllowed()) null else tr("Allow location to share your trip.")
    }
    DisposableEffect(uid) {
        val auth = FirebaseAuth.getInstance()
        val listener = FirebaseAuth.AuthStateListener { if (it.currentUser?.uid != uid) dismiss() }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }

    fun account(): ExpectedAccount {
        if (expected == null || FirebaseAuth.getInstance().currentUser?.uid != uid) throw IOException("Session changed")
        if (BuildConfig.API_BASE_URL.isBlank()) throw IOException("Backend unavailable")
        return expected
    }
    suspend fun refresh() {
        val tag = account()
        requestStore.getString(requestStoreKey, null)?.let { key ->
            try {
                BackendClient.api.circleRequest(key, tag)
                requestStore.edit().remove(requestStoreKey).apply()
                pending = null
            } catch (e: retrofit2.HttpException) { if (e.code() != 404) throw e }
        }
        links = BackendClient.api.circleLinks(tag).filter { it.owner }
        sessions = BackendClient.api.circleSessions(tag)
        if (selected == null) selected = links.map { it.contactId }.toSet()
        loaded = true
    }
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = circleSharingError(e) }
            finally { busy = false }
        }
    }
    @android.annotation.SuppressLint("MissingPermission") // checked first; a revoked permission surfaces as an error
    suspend fun freshFix(): FixDto {
        if (!context.locationAllowed()) throw IOException("Location permission required")
        val cancellation = CancellationTokenSource()
        try {
            val location = withTimeoutOrNull(20000) {
                LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(
                    CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                        .setMaxUpdateAgeMillis(0).build(), cancellation.token).await()
            } ?: throw IOException("Fresh location unavailable")
            if (!location.hasAccuracy()) throw IOException("Location accuracy unavailable")
            return FixDto(location.latitude, location.longitude, Instant.ofEpochMilli(location.time).toString(),
                LocationCompat.isMock(location), location.accuracy.toDouble())
        } finally { cancellation.cancel() }
    }
    LaunchedEffect(uid) { run { refresh() } }
    val current = ownOpenSession(sessions, kind)
    // Trip updates while this panel is open and the app is visible.
    LaunchedEffect(sharingGps, current?.id) {
        val id = current?.id ?: return@LaunchedEffect
        if (!trip || !sharingGps) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                try {
                    BackendClient.api.circleFix(id, freshFix(), account())
                    sessions = sessions.map { if (it.id == id) BackendClient.api.circleSession(id, account()) else it }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = circleSharingError(e); sharingGps = false; break }
                delay(15000)
            }
        }
    }

    AlertDialog(modifier = Modifier.dialogEnter(), onDismissRequest = dismiss, containerColor = p.surface,
        titleContentColor = p.primary, textContentColor = p.primary,
        title = { Text(if (trip) tr("Share my trip") else tr("Check in")) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (trip) tr("People in your circle who use Maitri can follow your location until the trip ends.")
                    else tr("Pick when you'll check in. If you don't, Maitri tells your circle. No location is shared."),
                    color = p.secondary, fontSize = 13.sp)
                if (busy || !loaded) LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent)
                error?.let { Text(it, color = p.accent, fontSize = 13.sp) }
                when {
                    !loaded -> Unit
                    current != null && trip -> {
                        Text(tr("Sharing your trip until {0}", localTime(current.expiresAt)), fontWeight = FontWeight.SemiBold)
                        Text(current.syncedAt?.let { tr("Last update ") + relativeTime(it).lowercase() } ?: tr("No location sent yet"),
                            color = p.secondary, fontSize = 13.sp)
                        Text(tr("Updates are sent while Maitri is open."), color = p.secondary, fontSize = 12.sp)
                        OutlinedButton(enabled = !busy, onClick = {
                            if (!context.locationAllowed()) permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            else sharingGps = !sharingGps
                        }) { Text(if (sharingGps) tr("Pause updates") else tr("Send updates"), color = p.accent) }
                        Button(enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = p.accent), onClick = { run {
                            BackendClient.api.stopCircleSession(current.id, account())
                            sharingGps = false
                            refresh()
                        } }) { Text(tr("Stop sharing")) }
                    }
                    current != null -> {
                        Text(if (current.missed) tr("You missed this check-in. Your circle was told.")
                            else current.dueAt?.let { tr("Check in by {0}", localTime(it)) } ?: tr("Check in by your chosen time"),
                            fontWeight = FontWeight.SemiBold, color = if (current.missed) p.accent else p.primary)
                        Button(enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = p.accent), onClick = { run {
                            BackendClient.api.stopCircleSession(current.id, account())
                            refresh()
                        } }, modifier = Modifier.fillMaxWidth()) { Text(tr("I'm safe, check in now")) }
                    }
                    links.isEmpty() -> Text(tr("None of your circle uses Maitri yet. On the Circle screen, tap Invite next to ") +
                        tr("a contact and send them the code."), color = p.secondary, fontSize = 13.sp)
                    else -> {
                        Text(tr("Share with"), fontWeight = FontWeight.SemiBold)
                        links.forEach { link ->
                            val checked = link.contactId in selected.orEmpty()
                            Row(Modifier.fillMaxWidth().clickable(enabled = !busy && pending == null) {
                                selected = if (checked) selected.orEmpty() - link.contactId else selected.orEmpty() + link.contactId
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Text(link.name)
                            }
                        }
                        Text(if (trip) tr("For") else tr("Check in within"), fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (if (trip) TRIP_MINUTES else CHECK_IN_MINUTES).forEach { option ->
                                FilterChip(selected = minutes == option, onClick = { if (pending == null) minutes = option },
                                    label = { Text(minutesLabel(option), fontSize = 12.sp) })
                            }
                        }
                        Row(Modifier.clickable(enabled = pending == null) { consent = !consent }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = consent, onCheckedChange = null)
                            Text(if (trip) tr("Share my precise location with them for {0}.", minutesLabel(minutes))
                                else tr("Tell them if I haven't checked in within {0}.", minutesLabel(minutes)), fontSize = 13.sp)
                        }
                        Button(enabled = !busy && consent && selected.orEmpty().isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = p.accent), modifier = Modifier.fillMaxWidth(), onClick = {
                            if (trip && !context.locationAllowed()) {
                                permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            } else run {
                                val request = pending ?: ((requestStore.getString(requestStoreKey, null) ?: UUID.randomUUID().toString()) to
                                    CircleShareRequest(kind, selected.orEmpty().sorted(), true, if (trip) freshFix() else null,
                                        duration = if (trip) minutes else 30, dueMinutes = if (trip) 60 else minutes)).also { pending = it }
                                if (!requestStore.edit().putString(requestStoreKey, request.first).commit())
                                    throw IOException("Unable to preserve retry identity")
                                val result = BackendClient.api.createCircleSession(request.first, request.second, account())
                                pending = null
                                requestStore.edit().remove(requestStoreKey).apply()
                                consent = false
                                refresh()
                                if (result.kind == "TRIP") sharingGps = true
                            }
                        }) { Text(if (pending != null) tr("Try again") else if (trip) tr("Start sharing") else tr("Set check-in")) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(tr("Close"), color = p.accent) } })
}
