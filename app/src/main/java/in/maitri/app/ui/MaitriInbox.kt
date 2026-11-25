package `in`.maitri.app.ui

import androidx.compose.foundation.lazy.itemsIndexed

import `in`.maitri.app.tr

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import `in`.maitri.app.R
import `in`.maitri.app.network.BackendClient
import `in`.maitri.app.network.ExpectedAccount
import `in`.maitri.app.network.InboxItemDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** What tapping an inbox item does. Pure, so it can be unit-tested. */
internal sealed interface InboxAction {
    data class OpenInvitation(val invitationId: String) : InboxAction
    data class OpenLink(val url: String) : InboxAction
    /** Someone in your circle sent an SOS: their live location (while active) and their voice notes and photos. */
    data class ViewCircleSos(val incidentId: String, val liveUrl: String?) : InboxAction
    data class AnswerCircleRequest(val requestId: String) : InboxAction
    data object OpenCheckIn : InboxAction
    data class ViewSession(val sessionId: String) : InboxAction
    data object None : InboxAction
}

internal fun inboxAction(item: InboxItemDto): InboxAction = when (item.kind) {
    "SOS_REQUEST" -> item.data["invitation_id"]?.let { InboxAction.OpenInvitation(it) } ?: InboxAction.None
    "CIRCLE_SOS", "CIRCLE_SOS_ENDED" -> {
        val url = item.data["url"]?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        val incident = item.data["incident_id"]?.takeIf { it.isNotBlank() }
        when {
            incident != null -> InboxAction.ViewCircleSos(incident, url.takeIf { item.kind == "CIRCLE_SOS" })
            url != null -> InboxAction.OpenLink(url)
            else -> InboxAction.None
        }
    }
    "CIRCLE_REQUEST" -> item.data["request_id"]?.let { InboxAction.AnswerCircleRequest(it) } ?: InboxAction.None
    // Your own reminder or missed check-in: open the check-in so you can say you're safe.
    "CHECKIN_REMINDER" -> InboxAction.OpenCheckIn
    "CHECKIN_MISSED" -> if (item.title.startsWith("You ")) InboxAction.OpenCheckIn
        else item.data["session_id"]?.let { InboxAction.ViewSession(it) } ?: InboxAction.None
    "TRIP_STARTED", "CHECKIN_STARTED" -> item.data["session_id"]?.let { InboxAction.ViewSession(it) } ?: InboxAction.None
    else -> InboxAction.None
}

internal fun relativeTime(iso: String, now: Instant = Instant.now()): String {
    val at = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull() ?: return ""
    val minutes = Duration.between(at, now).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 1 -> tr("Just now")
        minutes < 60 -> tr("{0} min ago", minutes)
        minutes < 24 * 60 -> tr("{0} h ago", minutes / 60)
        else -> tr("{0} d ago", minutes / (24 * 60))
    }
}

private fun iconFor(kind: String) = when (kind) {
    "SOS_REQUEST", "CIRCLE_SOS" -> R.drawable.maitri_shield
    "CHECKIN_REMINDER", "CHECKIN_MISSED", "CHECKIN_STARTED" -> R.drawable.maitri_clock
    "TRIP_STARTED" -> R.drawable.maitri_pin
    else -> R.drawable.maitri_people
}

/** Screen 02's bell: SOS requests, circle alerts and requests, check-in reminders and alert status. */
@Composable
fun MaitriInbox(uid: String, onBack: () -> Unit, onInvitation: (String) -> Unit) {
    val p = maitriPalette()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val account = remember(uid) { ExpectedAccount(uid) }
    var items by remember { mutableStateOf<List<InboxItemDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf<String?>(null) }
    var checkIn by remember { mutableStateOf(false) }
    var circleSos by remember { mutableStateOf<InboxAction.ViewCircleSos?>(null) }

    suspend fun load() {
        try {
            items = BackendClient.circle.inbox(account).items
            error = null
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = tr("Couldn't load your inbox. Check your connection.") }
    }
    LaunchedEffect(uid) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { while (true) { load(); delay(30_000) } }
    }
    fun open(item: InboxItemDto) {
        if (!item.read && item.kind != "SOS_REQUEST") scope.launch {
            runCatching { BackendClient.circle.read(item.id, account) }
            items = items?.map { if (it.id == item.id) it.copy(read = true) else it }
        }
        when (val action = inboxAction(item)) {
            is InboxAction.OpenInvitation -> onInvitation(action.invitationId)
            is InboxAction.OpenLink -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(action.url))) }
            is InboxAction.AnswerCircleRequest -> request = action.requestId
            InboxAction.OpenCheckIn -> checkIn = true
            is InboxAction.ViewSession -> session = action.sessionId
            is InboxAction.ViewCircleSos -> circleSos = action
            InboxAction.None -> Unit
        }
    }

    val busy = rememberDelayedBusy(items == null && error == null, delayMillis = 0)
    Box(Modifier.fillMaxSize().background(p.background)) {
    Column(Modifier.fillMaxSize().blurWhenBusy(busy).background(p.background).statusBarsPadding().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp).background(p.surface, CircleShape)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back"), tint = p.primary)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("A LITTLE PEACE OF MIND"), color = p.accent, fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.SemiBold)
                Text(tr("Notifications"), color = p.primary, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            }
            if (items.orEmpty().any { !it.read && it.kind != "SOS_REQUEST" }) TextButton(onClick = { scope.launch {
                runCatching { BackendClient.circle.readAll(account) }
                load()
            } }) { Text(tr("Mark all read"), color = p.accent, fontSize = 12.sp) }
        }
        Spacer(Modifier.height(16.dp))
        error?.let { Text(it, color = p.accent, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        val list = items
        when {
            list == null && error == null -> Unit  // MaitriBusyOverlay below
            list.isNullOrEmpty() -> Text(tr("Nothing here yet. SOS requests, circle alerts and check-in reminders show up here."),
                Modifier.fillMaxWidth().padding(top = 48.dp), color = p.secondary, fontSize = 13.sp, textAlign = TextAlign.Center)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = LocalFloatingNavBarPadding.current)) {
                itemsIndexed(list, key = { _, it -> it.kind + it.id }) { index, item ->
                    Box(Modifier.fadeInOnFirstLoad(index)) { InboxRow(item, p) { open(item) } }
                }
            }
        }
    }
    MaitriBusyOverlay(busy)
    }

    request?.let { id ->
        AlertDialog(modifier = Modifier.dialogEnter(), onDismissRequest = { request = null }, containerColor = p.surface,
            titleContentColor = p.primary, textContentColor = p.secondary,
            title = { Text(tr("Join their circle?")) },
            text = { Text(tr("You'll get their SOS alerts and their live location while an SOS is active. You can leave at any time.")) },
            confirmButton = { TextButton(onClick = { request = null; scope.launch {
                runCatching { BackendClient.circle.answer(id, "accept", account) }; load() } }) { Text(tr("Accept"), color = p.accent) } },
            dismissButton = { TextButton(onClick = { request = null; scope.launch {
                runCatching { BackendClient.circle.answer(id, "decline", account) }; load() } }) { Text(tr("Decline"), color = p.secondary) } })
    }
    session?.let { id -> CircleSessionViewer(uid, id) { session = null } }
    circleSos?.let { sos -> CircleSosViewer(uid, sos.incidentId, sos.liveUrl) { circleSos = null } }
    if (checkIn) MaitriSharingDialog("CHECK_IN") { checkIn = false }
}

@Composable
private fun InboxRow(item: InboxItemDto, p: MaitriPalette, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(if (item.read) p.surface else p.card, RoundedCornerShape(20.dp))
        .clickable(onClick = onClick).padding(14.dp)
        .semantics { if (!item.read) contentDescription = tr("Unread. {0}. {1}", item.title, item.body) },
        verticalAlignment = Alignment.Top) {
        Box(Modifier.size(40.dp).background(p.background, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(painterResource(iconFor(item.kind)), null, tint = p.accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = p.primary, fontSize = 14.sp, fontWeight = if (item.read) FontWeight.Normal else FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(item.body, color = p.secondary, fontSize = 12.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(4.dp))
            Text(relativeTime(item.createdAt), color = p.secondary, fontSize = 10.sp)
        }
        if (!item.read) Box(Modifier.padding(top = 6.dp).size(8.dp).background(p.accent, CircleShape))
    }
}

/** A trip or check-in someone shared with you: their last location (trips) or due time (check-ins). */
@Composable
internal fun CircleSessionViewer(uid: String, sessionId: String, dismiss: () -> Unit) {
    val p = maitriPalette()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var session by remember { mutableStateOf<`in`.maitri.app.network.CircleSessionDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sessionId) {
        runCatching { BackendClient.api.openCircleSession(sessionId, ExpectedAccount(uid)) }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                try { session = BackendClient.api.circleSession(sessionId, ExpectedAccount(uid)); error = null }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { session = null; error = `in`.maitri.app.network.circleSharingError(e) }
                delay(15_000)
            }
        }
    }
    AlertDialog(modifier = Modifier.dialogEnter(), onDismissRequest = dismiss, containerColor = p.surface, titleContentColor = p.primary, textContentColor = p.secondary,
        title = { Text(if (session?.kind == "CHECK_IN") tr("Check-in") else tr("Shared trip")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = p.accent) }
                val s = session
                when {
                    s == null && error == null -> MaitriLoader(Modifier.fillMaxWidth(), logoSize = 48.dp)
                    s == null -> Unit
                    s.kind == "CHECK_IN" -> Text(when {
                        s.status == "STOPPED" -> tr("They checked in. They're safe.")
                        s.missed -> tr("They missed this check-in. Try reaching them, and call 112 if you think they're in danger.")
                        else -> tr("Due ") + (s.dueAt?.let { localTime(it) } ?: "soon") + tr(". You'll be told if they don't check in.")
                    })
                    s.status != "ACTIVE" -> Text(tr("This trip has ended. Their location is no longer shared."))
                    s.fix == null -> Text(tr("Waiting for their first location…"))
                    else -> {
                        MaitriMap(listOf(MapMarker(s.fix.latitude, s.fix.longitude, MarkerKind.PERSON_IN_NEED)), "",
                            Modifier.fillMaxWidth().height(220.dp), accuracyMeters = s.fix.accuracyMeters)
                        Text(tr("Updated ") + relativeTime(s.fix.recordedAt))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(tr("Close"), color = p.accent) } })
}

internal fun localTime(iso: String): String = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalTime()
        .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
}.getOrDefault(iso)
