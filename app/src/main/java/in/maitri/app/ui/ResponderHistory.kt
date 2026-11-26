package `in`.maitri.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import `in`.maitri.app.network.*
import `in`.maitri.app.startup.StartupCache
import `in`.maitri.app.tr
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------- Texts ----------

private fun zone(): ZoneId = ZoneId.systemDefault()

/** "Today, 5:12 pm", "Yesterday, 9:40 am", "24 Nov, 9:40 am" (and the year when it isn't this year). */
internal fun requestWhen(iso: String?, now: Instant = Instant.now(), zone: ZoneId = zone()): String {
    val at = instantOf(iso) ?: return ""
    val local = at.atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    val time = local.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())).lowercase(Locale.getDefault())
    return when (local.toLocalDate()) {
        today -> tr("Today, {0}", time)
        today.minusDays(1) -> tr("Yesterday, {0}", time)
        else -> local.format(DateTimeFormatter.ofPattern(if (local.year == today.year) "d MMM" else "d MMM yyyy",
            Locale.getDefault())) + ", " + time
    }
}

/** The time only, for "Ended 5:20 pm". */
internal fun clockTime(iso: String?, zone: ZoneId = zone()): String = instantOf(iso)?.atZone(zone)
    ?.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))?.lowercase(Locale.getDefault()).orEmpty()

internal fun directionName(code: String?): String? = when (code) {
    "N" -> tr("north"); "NE" -> tr("north-east"); "E" -> tr("east"); "SE" -> tr("south-east")
    "S" -> tr("south"); "SW" -> tr("south-west"); "W" -> tr("west"); "NW" -> tr("north-west")
    else -> null
}

/** "~450 m north-east of you" (accepted) or "Under 500 m away" (the band shown before answering). Never a place. */
internal fun areaLabel(item: ResponderHistoryItemDto): String {
    val area = item.area
    if (area != null && area.distanceMeters < 50) return tr("Under 50 m from you")
    if (area != null) {
        val distance = if (area.distanceMeters < 1000) "${area.distanceMeters} m"
            else "%.1f km".format(Locale.ROOT, area.distanceMeters / 1000.0)
        return directionName(area.direction)?.let { tr("~{0} {1} of you", distance, it) } ?: tr("~{0} from you", distance)
    }
    return item.distanceBand?.let { tr("{0} away", tr(it)) } ?: tr("Nearby")
}

/** "8 min", "1 h 5 min", "under 1 min". */
internal fun minutesLabel(seconds: Long): String {
    val minutes = seconds / 60
    return when {
        minutes < 1 -> tr("under 1 min")
        minutes < 60 -> tr("{0} min", minutes)
        else -> tr("{0} h {1} min", minutes / 60, minutes % 60)
    }
}

/** The card's third line: what you did, and for how long. */
internal fun responseLine(item: ResponderHistoryItemDto, now: Instant = Instant.now()): String {
    val seconds = item.durationSeconds?.let { stored ->
        // While the SOS is active the server's number is from when the page loaded: count on from acceptance.
        if (item.status() == RequestStatus.ACTIVE) instantOf(item.acceptedAt)?.let { Duration.between(it, now).seconds } ?: stored
        else stored
    }
    val time = seconds?.let { " · " + minutesLabel(it.coerceAtLeast(0)) }.orEmpty()
    return when (item.status()) {
        RequestStatus.NEW -> tr("Waiting for your answer")
        RequestStatus.ACTIVE, RequestStatus.RESOLVED, RequestStatus.ENDED -> tr("Accepted") + time
        RequestStatus.LEFT -> tr("Accepted, then left") + time
        RequestStatus.CANT_HELP -> tr("Accepted, then couldn't help") + time
        RequestStatus.DECLINED -> tr("You declined")
        RequestStatus.MISSED -> tr("Not answered in time")
    }
}

internal fun timelineLabel(type: String): String = when (type) {
    "INVITED" -> tr("Request received")
    "ACCEPTED" -> tr("You accepted")
    "DECLINED" -> tr("You declined")
    "MISSED" -> tr("Request closed before you answered")
    "ON_THE_WAY" -> tr("On the way")
    "HELPING" -> tr("Helping")
    "CANT_HELP" -> tr("Can't help")
    "LEFT" -> tr("You left")
    "RESOLVED" -> tr("Incident resolved · person marked safe")
    "ENDED" -> tr("SOS ended")
    else -> type
}

// ---------- Status pill ----------

@Composable
internal fun RequestStatusPill(p: MaitriPalette, item: ResponderHistoryItemDto, now: Instant = Instant.now()) {
    val green = p.verified
    val status = item.status()
    val (fill, ink) = when (status) {
        RequestStatus.ACTIVE, RequestStatus.NEW -> p.card to p.accent
        RequestStatus.RESOLVED -> green.copy(alpha = if (p.dark) 0.18f else 0.12f) to green
        // Neutral greys, not the palette's blush: finished states shouldn't look like the pink "Active".
        RequestStatus.DECLINED, RequestStatus.MISSED -> (if (p.dark) Color(0x14FFFFFF) else Color(0x0D000000)) to p.secondary
        else -> (if (p.dark) Color(0x24FFFFFF) else Color(0x17000000)) to p.secondary
    }
    val text = when (status) {
        RequestStatus.ACTIVE -> {
            val since = instantOf(item.acceptedAt) ?: instantOf(item.invitedAt)
            tr("Active") + (since?.let { " · " + minutesLabel(Duration.between(it, now).seconds.coerceAtLeast(0)) }.orEmpty())
        }
        RequestStatus.NEW -> tr("New request")
        RequestStatus.RESOLVED -> tr("Incident resolved · person marked safe")
        RequestStatus.ENDED -> tr("SOS ended")
        RequestStatus.LEFT -> tr("You left")
        RequestStatus.CANT_HELP -> tr("Couldn't help")
        RequestStatus.DECLINED -> tr("Declined")
        RequestStatus.MISSED -> tr("Missed")
    }
    val short = if (status == RequestStatus.RESOLVED) tr("Resolved · marked safe") else null
    val hasIcon = status in setOf(RequestStatus.ACTIVE, RequestStatus.NEW, RequestStatus.RESOLVED)
    val style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp)
    val measurer = rememberTextMeasurer()
    // One line, never two: the shorter wording if the full one doesn't fit, then a slightly smaller font, then "…".
    BoxWithConstraints {
        val room = constraints.maxWidth - with(LocalDensity.current) { (20 + if (hasIcon) 18 else 0).dp.roundToPx() }
        val label = if (short != null && measurer.measure(text, style, maxLines = 1).size.width > room) short else text
        Row(Modifier.background(fill, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            when (status) {
                RequestStatus.ACTIVE, RequestStatus.NEW -> PulseDot(ink)
                RequestStatus.RESOLVED -> Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = ink)
                else -> Unit
            }
            if (hasIcon) Spacer(Modifier.width(6.dp))
            Text(label, color = ink, style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 11.sp, stepSize = 0.25.sp))
        }
    }
}

/** A live dot: grows and fades out once a second (still with reduced motion on). */
@Composable
private fun PulseDot(color: Color) {
    val still = reduceMotion()
    val transition = rememberInfiniteTransition(label = "pulse")
    val wave by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearOutSlowInEasing)), label = "wave")
    Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
        if (!still) Box(Modifier.size(12.dp).scale(0.5f + wave).alpha(1f - wave).background(color.copy(alpha = 0.45f), CircleShape))
        Box(Modifier.size(7.dp).background(color, CircleShape))
    }
}

// ---------- Incident card ----------

/** One SOS request, in the Profile & settings card style. Shared by Recent requests and Responder history. */
@Composable
internal fun IncidentCard(p: MaitriPalette, item: ResponderHistoryItemDto, now: Instant, onClick: () -> Unit,
                          modifier: Modifier = Modifier) {
    val status = item.status()
    val hint = when (status) {
        RequestStatus.ACTIVE -> tr("Resume")
        RequestStatus.NEW -> tr("Open request")
        else -> tr("View summary")
    }
    // Press feedback only while the finger is down (scale); no ripple or focus highlight that can stay behind.
    Column(modifier.fillMaxWidth().pressScale().background(p.surface, RoundedCornerShape(20.dp))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
            role = Role.Button, onClickLabel = hint, onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Stacked, not side by side, so nothing gets squeezed at large text sizes.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("SOS request"), color = p.primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (item.isTest) {
                Spacer(Modifier.width(8.dp))
                Text(tr("Practice"), Modifier.background(p.subtle, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    color = p.secondary, fontSize = 10.sp, maxLines = 1)
            }
        }
        Text(requestWhen(item.invitedAt, now), color = p.secondary, fontSize = 12.sp)
        Text(areaLabel(item), color = p.secondary, fontSize = 12.sp)
        Text(responseLine(item, now), color = p.primary, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        RequestStatusPill(p, item, now)
        val ended = item.closedAt?.takeIf { status == RequestStatus.RESOLVED || status == RequestStatus.ENDED }
        val open = status == RequestStatus.ACTIVE || status == RequestStatus.NEW
        if (ended != null || open) Row(verticalAlignment = Alignment.CenterVertically) {
            if (ended != null) Text(tr("Ended {0}", clockTime(ended)), Modifier.weight(1f), color = p.secondary, fontSize = 11.sp)
            else Spacer(Modifier.weight(1f))
            if (open) Text(hint + " ›", color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A clock that ticks every 30 s while shown, so "Active · 12 min" counts on. */
@Composable
internal fun rememberTicker(periodMillis: Long = 30_000): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(periodMillis) { while (true) { delay(periodMillis); now = Instant.now() } }
    return now
}

// ---------- Responder tab: Recent requests ----------

/**
 * Responder tab "Recent requests": a section title, then one IncidentCard per request on the page background (the
 * same cards, width and spacing as the History screen), then "View all history (N) ›".
 */
@Composable
internal fun RecentRequestsSection(p: MaitriPalette, items: List<ResponderHistoryItemDto>?, total: Int,
                                   onOpen: (ResponderHistoryItemDto) -> Unit, onViewAll: () -> Unit,
                                   now: Instant = rememberTicker()) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(CARD_GAP)) {
        Text(tr("Recent requests"), Modifier.padding(top = 8.dp).semantics { heading() }, color = p.primary,
            fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        val shown = items?.let { recentRequests(it, now) }
        when {
            shown == null -> MaitriLoader(Modifier.fillMaxWidth().padding(vertical = 8.dp), logoSize = 40.dp)
            shown.isEmpty() -> Text(tr("No recent requests"), Modifier.fillMaxWidth()
                .background(p.surface, RoundedCornerShape(20.dp)).padding(16.dp), color = p.secondary, fontSize = 13.sp)
            else -> shown.forEach { item -> key(item.invitationId) { IncidentCard(p, item, now, { onOpen(item) }) } }
        }
        if (total > 0 || !shown.isNullOrEmpty()) Text(tr("View all history ({0}) ›", total),
            Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onViewAll).padding(vertical = 6.dp),
            color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The gap between incident cards, on the Responder tab and the History screen alike. */
internal val CARD_GAP = 10.dp

// ---------- Summary of a finished request ----------

@Composable
internal fun IncidentSummaryDialog(p: MaitriPalette, item: ResponderHistoryItemDto, onDismiss: () -> Unit) {
    AlertDialog(modifier = Modifier.dialogEnter(), onDismissRequest = onDismiss, containerColor = p.surface,
        titleContentColor = p.primary, textContentColor = p.secondary,
        title = { Column {
            Text(tr("SOS request"))
            Text(requestWhen(item.invitedAt), color = p.secondary, fontSize = 13.sp)
        } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RequestStatusPill(p, item)
                Text(areaLabel(item), color = p.primary, fontSize = 13.sp)
                Text(responseLine(item), color = p.primary, fontSize = 13.sp)
                Text(if (item.responders == 1) tr("1 responder took part") else tr("{0} responders took part", item.responders),
                    color = p.primary, fontSize = 13.sp)
                HorizontalDivider(color = p.divider)
                Text(tr("Timeline"), Modifier.semantics { heading() }, color = p.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                item.timeline.forEachIndexed { index, entry ->
                    val gap = item.timeline.getOrNull(index - 1)?.let { previous ->
                        val a = instantOf(previous.at); val b = instantOf(entry.at)
                        if (a != null && b != null) Duration.between(a, b).seconds.takeIf { it >= 60 } else null
                    }
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 5.dp).size(8.dp).background(
                            if (entry.type == "RESOLVED") p.verified else p.accent.copy(alpha = 0.6f), CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(timelineLabel(entry.type), color = p.primary, fontSize = 13.sp)
                            Text(clockTime(entry.at) + (gap?.let { " · " + tr("{0} later", minutesLabel(it)) }.orEmpty()),
                                color = p.secondary, fontSize = 11.sp)
                        }
                    }
                }
                Text(tr("No map or exact location is kept after an SOS ends."), color = p.secondary, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close"), color = p.accent) } })
}

// ---------- Opening a card ----------

/**
 * What tapping a card does: an active SOS you accepted resumes screen 07 (after the server confirms you still have
 * access), a new request opens screen 06, anything else shows the read-only summary.
 */
@Composable
internal fun rememberRequestOpener(uid: String, onResumeJourney: (String) -> Unit, onOpenInvitation: (String) -> Unit,
                                   onMessage: (String) -> Unit): Pair<(ResponderHistoryItemDto) -> Unit, ResponderHistoryItemDto?> {
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<ResponderHistoryItemDto?>(null) }
    var busy by remember { mutableStateOf(false) }
    val open: (ResponderHistoryItemDto) -> Unit = { item ->
        when (item.status()) {
            RequestStatus.NEW -> onOpenInvitation(item.invitationId)
            RequestStatus.ACTIVE -> if (!busy) {
                busy = true
                scope.launch {
                    try {
                        val snapshot = BackendClient.api.snapshot(item.incidentId, ExpectedAccount(uid))
                        if (snapshot.authorizesJourney(item.incidentId)) onResumeJourney(item.incidentId)
                        else onMessage(tr("Responder access has ended."))
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { onMessage(tr("Could not confirm responder access.")) }
                    finally { busy = false }
                }
            }
            else -> summary = item
        }
    }
    summary?.let { IncidentSummaryDialog(maitriPalette(), it) { summary = null } }
    return open to summary
}

/** Keeps a list's open cards in step with "SOS ended" events, without waiting for a refresh. */
@Composable
internal fun FollowClosures(onClosed: (ResponderHistoryEvents.Closed) -> Unit) {
    LaunchedEffect(Unit) { ResponderHistoryEvents.closed.collect { onClosed(it) } }
}

internal fun List<ResponderHistoryItemDto>.closing(event: ResponderHistoryEvents.Closed) =
    map { if (it.incidentId == event.incidentId) it.closedNow(event.resolved) else it }

// ---------- Responder history screen ----------

internal enum class HistoryFilter(val key: String) { ALL("all"), ACCEPTED("accepted"), RESOLVED("resolved"), DECLINED_MISSED("declined_missed") }

private fun HistoryFilter.label() = when (this) {
    HistoryFilter.ALL -> tr("All")
    HistoryFilter.ACCEPTED -> tr("Accepted")
    HistoryFilter.RESOLVED -> tr("Resolved")
    HistoryFilter.DECLINED_MISSED -> tr("Declined & missed")
}

private fun HistoryFilter.empty(days: Int) = when (this) {
    HistoryFilter.ALL -> tr("No SOS requests yet. Requests you receive show up here for {0} days.", days)
    HistoryFilter.ACCEPTED -> tr("You haven't accepted a request in the last {0} days.", days)
    HistoryFilter.RESOLVED -> tr("No resolved incidents in the last {0} days.", days)
    HistoryFilter.DECLINED_MISSED -> tr("Nothing declined or missed in the last {0} days.", days)
}

/** "Today", "Yesterday", "This week", then the month ("November 2026"). */
internal fun historyGroup(iso: String, now: Instant = Instant.now(), zone: ZoneId = zone()): String {
    val day = instantOf(iso)?.atZone(zone)?.toLocalDate() ?: return tr("Earlier")
    val today: LocalDate = now.atZone(zone).toLocalDate()
    return when {
        day == today -> tr("Today")
        day == today.minusDays(1) -> tr("Yesterday")
        day.isAfter(today.minusDays(7)) -> tr("This week")
        else -> day.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
    }
}

/** Everything loaded so far for one filter, cached on the phone so the screen works offline. */
private data class HistoryState(val items: List<ResponderHistoryItemDto> = emptyList(), val cursor: String? = null,
                                val loaded: Boolean = false, val summary: ResponderHistorySummaryDto? = null,
                                val days: Int = 30)

private const val CACHED_ITEMS = 100

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaitriResponderHistory(uid: String, onBack: () -> Unit, onResumeJourney: (String) -> Unit,
                           onOpenInvitation: (String) -> Unit) {
    val p = maitriPalette()
    val account = remember(uid) { ExpectedAccount(uid) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    val cacheKey = "history_" + filter.key
    var state by remember(filter) {
        mutableStateOf(StartupCache.get<ResponderHistoryPageDto>(uid, cacheKey)?.let {
            HistoryState(ResponderHistoryEvents.patch(it.items), it.nextCursor, false, it.summary, it.days)
        } ?: HistoryState())
    }
    var loadingMore by remember(filter) { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun firstPage() {
        try {
            val page = BackendClient.api.responderHistory(limit = 20, filter = filter.key, expected = account)
            state = HistoryState(ResponderHistoryEvents.patch(page.items), page.nextCursor, true, page.summary, page.days)
            StartupCache.put(uid, cacheKey, page)
            error = null
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            error = if (state.items.isEmpty()) tr("Couldn't load your history. Check your connection.")
                else tr("Showing saved history. Pull down to try again.")
            state = state.copy(loaded = true)
        }
    }
    fun more() {
        val cursor = state.cursor ?: return
        if (loadingMore) return
        loadingMore = true
        scope.launch {
            try {
                val page = BackendClient.api.responderHistory(cursor = cursor, limit = 20, filter = filter.key, expected = account)
                state = state.copy(items = (state.items + ResponderHistoryEvents.patch(page.items)).distinctBy { it.invitationId },
                    cursor = page.nextCursor, summary = page.summary)
                StartupCache.put(uid, cacheKey, ResponderHistoryPageDto(state.items.take(CACHED_ITEMS),
                    state.cursor.takeIf { state.items.size <= CACHED_ITEMS }, page.summary, page.days))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = tr("Couldn't load more. Check your connection.") }
            finally { loadingMore = false }
        }
    }
    LaunchedEffect(filter, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { firstPage(); delay(30_000) } }
    }
    FollowClosures { event -> state = state.copy(items = state.items.closing(event)) }
    val (open, _) = rememberRequestOpener(uid, onResumeJourney, onOpenInvitation) { message = it }
    val now = rememberTicker()
    val list = rememberLazyListState()
    // Infinite scroll: the next 20 when the last few cards come into view.
    val nearEnd by remember { derivedStateOf {
        val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        last >= list.layoutInfo.totalItemsCount - 3
    } }
    LaunchedEffect(nearEnd, state.cursor) { if (nearEnd && state.cursor != null) more() }

    ResponderHistoryBody(p, state.summary, filter, { filter = it }, error, refreshing,
        { scope.launch { refreshing = true; try { firstPage() } finally { refreshing = false } } },
        state.loaded, state.items, state.days, state.cursor != null, list, now, open, onBack)
    message?.let { AlertDialog(modifier = Modifier.dialogEnter(), onDismissRequest = { message = null }, containerColor = p.surface,
        textContentColor = p.primary, text = { Text(it) },
        confirmButton = { TextButton(onClick = { message = null }) { Text(tr("Close"), color = p.accent) } }) }
}

/** The History screen's layout, without loading: what the screenshot tests draw with fixed data. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ResponderHistoryBody(p: MaitriPalette, summary: ResponderHistorySummaryDto?, filter: HistoryFilter,
                                  onFilter: (HistoryFilter) -> Unit, error: String?, refreshing: Boolean, onRefresh: () -> Unit,
                                  loaded: Boolean, items: List<ResponderHistoryItemDto>, days: Int, hasMore: Boolean,
                                  list: androidx.compose.foundation.lazy.LazyListState, now: Instant,
                                  onOpen: (ResponderHistoryItemDto) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(p.background).statusBarsPadding()) {
        Spacer(Modifier.height(20.dp))
        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp).background(p.surface, CircleShape)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back"), tint = p.primary)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("NEARBY SUPPORT"), color = p.accent, fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.SemiBold)
                Text(tr("Responder history"), Modifier.semantics { heading() }, color = p.primary, fontSize = 25.sp,
                    fontWeight = FontWeight.SemiBold)
            }
        }
        summary?.let { s ->
            Text(summaryLine(s), Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp),
                color = p.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HistoryFilter.entries.forEach { option ->
                FilterChip(selected = filter == option, onClick = { onFilter(option) }, label = { Text(option.label()) },
                    colors = FilterChipDefaults.filterChipColors(containerColor = p.surface, labelColor = p.primary,
                        selectedContainerColor = p.card, selectedLabelColor = p.accent,
                        selectedLeadingIconColor = p.accent),
                    border = FilterChipDefaults.filterChipBorder(enabled = true, selected = filter == option,
                        borderColor = p.divider, selectedBorderColor = p.accent))
            }
        }
        error?.let { Text(it, Modifier.padding(horizontal = 24.dp, vertical = 4.dp), color = p.accent, fontSize = 12.sp) }
        PullToRefreshBox(isRefreshing = refreshing, modifier = Modifier.fillMaxSize(),
            onRefresh = onRefresh) {
            when {
                !loaded && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MaitriLoader(message = tr("Loading your history…"), logoSize = 56.dp)
                }
                items.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(filter.empty(days), Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
                            color = p.secondary, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), state = list,
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp + LocalFloatingNavBarPadding.current),
                    verticalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                    val groups = items.groupBy { historyGroup(it.invitedAt, now) }
                    groups.forEach { (group, entries) ->
                        item(key = "h-$group") {
                            Text(group, Modifier.padding(top = 8.dp).semantics { heading() }, color = p.secondary,
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
                        }
                        items(entries, key = { it.invitationId }) { item -> IncidentCard(p, item, now, { onOpen(item) }) }
                    }
                    if (hasMore) item(key = "more") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            MaitriLoader(logoSize = 36.dp, delayMillis = 0)
                        }
                    }
                }
            }
        }
    }
}

/** "You've helped 4 times · Resolved 3", plus " · + 2 practice" for practice SOSes (never counted as help). */
internal fun summaryLine(s: ResponderHistorySummaryDto): String =
    tr("You've helped {0} times · Resolved {1}", s.helped, s.resolved) +
        // Non-breaking spaces: "+ 3 practice" never splits across lines.
        if (s.practice > 0) " ·\u00A0" + tr("+ {0} practice", s.practice).replace(' ', '\u00A0') else ""

/** The Profile & settings row's description, and the screen's content description. */
internal fun responderHistoryRowDescription(): String = tr("Requests you received and how they ended")
