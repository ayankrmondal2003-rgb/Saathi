package `in`.maitri.app.network

import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Instant

/**
 * A volunteer's own SOS requests (GET /api/responders/history, backend/app/responder_history.py). Never coordinates
 * and never the person's name or phone: where it was is only "about N m <direction> of you", and only after accepting.
 */
data class ResponderHistoryPageDto(
    @SerializedName("items") val items: List<ResponderHistoryItemDto> = emptyList(),
    @SerializedName("next_cursor") val nextCursor: String? = null,
    @SerializedName("summary") val summary: ResponderHistorySummaryDto = ResponderHistorySummaryDto(),
    @SerializedName("days") val days: Int = 30)

data class ResponderHistorySummaryDto(@SerializedName("total") val total: Int = 0,
                                      @SerializedName("helped") val helped: Int = 0,
                                      @SerializedName("resolved") val resolved: Int = 0,
                                      /** Practice SOSes accepted: shown apart, never counted as help given. */
                                      @SerializedName("practice") val practice: Int = 0)

data class ResponderHistoryItemDto(
    @SerializedName("invitation_id") val invitationId: String,
    @SerializedName("incident_id") val incidentId: String,
    @SerializedName("is_test") val isTest: Boolean = false,
    @SerializedName("invited_at") val invitedAt: String,
    @SerializedName("accepted_at") val acceptedAt: String? = null,
    @SerializedName("closed_at") val closedAt: String? = null,
    /** PENDING, ACCEPTED, DECLINED, EXPIRED, LEFT or CANT_HELP. */
    @SerializedName("my_response") val myResponse: String,
    /** ACTIVE, RESOLVED, CLOSED_ORPHANED or CLOSED_OTHER. */
    @SerializedName("incident_state") val incidentState: String,
    @SerializedName("distance_band") val distanceBand: String? = null,
    @SerializedName("area") val area: ResponderAreaDto? = null,
    @SerializedName("duration_seconds") val durationSeconds: Long? = null,
    @SerializedName("responders") val responders: Int = 0,
    @SerializedName("timeline") val timeline: List<ResponderTimelineDto> = emptyList())

data class ResponderAreaDto(@SerializedName("distance_m") val distanceMeters: Int,
                            @SerializedName("direction") val direction: String? = null)

data class ResponderTimelineDto(@SerializedName("type") val type: String, @SerializedName("at") val at: String)

/** How a card looks: one of the status pills. */
internal enum class RequestStatus { NEW, ACTIVE, RESOLVED, ENDED, LEFT, CANT_HELP, DECLINED, MISSED }

internal fun ResponderHistoryItemDto.status(): RequestStatus = when {
    myResponse == "PENDING" && incidentState == "ACTIVE" -> RequestStatus.NEW
    myResponse == "LEFT" -> RequestStatus.LEFT
    myResponse == "CANT_HELP" -> RequestStatus.CANT_HELP
    myResponse == "DECLINED" -> RequestStatus.DECLINED
    myResponse == "EXPIRED" || myResponse == "PENDING" -> RequestStatus.MISSED
    incidentState == "ACTIVE" -> RequestStatus.ACTIVE
    incidentState == "RESOLVED" -> RequestStatus.RESOLVED
    else -> RequestStatus.ENDED
}

/** Still needs this volunteer: an accepted SOS that hasn't ended, or a request waiting for an answer. */
internal fun ResponderHistoryItemDto.isOpen(): Boolean = status() == RequestStatus.ACTIVE || status() == RequestStatus.NEW

internal fun instantOf(iso: String?): Instant? = iso?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull() }

/**
 * Responder tab "Recent requests": every open request first (accepted SOSes still active, and requests waiting for an
 * answer), then the 3 most recent finished ones from the last 7 days.
 */
internal fun recentRequests(items: List<ResponderHistoryItemDto>, now: Instant = Instant.now()): List<ResponderHistoryItemDto> {
    val open = items.filter { it.isOpen() }.sortedByDescending { it.status() == RequestStatus.ACTIVE }
    val weekAgo = now.minusSeconds(7 * 24 * 3600L)
    val finished = items.filter { !it.isOpen() && (instantOf(it.invitedAt)?.isAfter(weekAgo) ?: false) }.take(3)
    return open + finished
}

/** The SOS ended while this volunteer had accepted it: the card changes without a refresh (Active → Resolved / Ended). */
internal fun ResponderHistoryItemDto.closedNow(resolved: Boolean, at: Instant = Instant.now()): ResponderHistoryItemDto =
    if (incidentState != "ACTIVE") this
    else copy(incidentState = if (resolved) "RESOLVED" else "CLOSED_OTHER", closedAt = at.toString(),
        timeline = timeline + ResponderTimelineDto(if (resolved) "RESOLVED" else "ENDED", at.toString()),
        myResponse = if (myResponse == "PENDING") "EXPIRED" else myResponse)

/**
 * "This SOS ended" for open cards, from the push the server sends accepted volunteers (SOS_REQUEST_CLOSED) and from
 * screen 07's live connection (INCIDENT_CLOSED). Both the Responder tab and the History screen listen.
 */
object ResponderHistoryEvents {
    data class Closed(val incidentId: String, val resolved: Boolean)

    private val events = MutableSharedFlow<Closed>(extraBufferCapacity = 8)
    val closed: SharedFlow<Closed> = events.asSharedFlow()
    /** Remembered for screens that open after the event (e.g. History opened right after the push). */
    private val known = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun emit(incidentId: String, resolved: Boolean) {
        known[incidentId] = resolved
        events.tryEmit(Closed(incidentId, resolved))
    }

    /** Applies every close this process has heard of, so a cached or in-flight page never shows a stale Active. */
    internal fun patch(items: List<ResponderHistoryItemDto>): List<ResponderHistoryItemDto> =
        items.map { item -> known[item.incidentId]?.let { item.closedNow(it) } ?: item }
}
