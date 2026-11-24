package `in`.maitri.app.network

import `in`.maitri.app.tr

import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import java.time.OffsetDateTime

/** GET /api/incidents/active: the caller's own open incident (204 when there is none). */
data class ActiveIncidentDto(
    @SerializedName("id") val id: String,
    @SerializedName("status") val status: String,
    @SerializedName("started_at") val startedAt: String,
    @SerializedName("idempotency_key") val idempotencyKey: String
)

/** Structured 409 body from POST /api/incidents: {"detail": {"code", "message", "incident_id"}}. */
data class IncidentConflict(val code: String, val incidentId: String?)

const val INCIDENT_ALREADY_OPEN = "INCIDENT_ALREADY_OPEN"
const val IDEMPOTENCY_MISMATCH = "IDEMPOTENCY_MISMATCH"
const val LOCATION_CONFLICT = "LOCATION_CONFLICT"
/** 403 from a server not in beta mode: it only accepts test incidents, so the app falls back to one. */
const val REAL_INCIDENTS_DISABLED = "REAL_INCIDENTS_DISABLED"
/** 429 after the per-hour real SOS limit. Retrying within the hour can't succeed. */
const val SOS_RATE_LIMITED = "SOS_RATE_LIMITED"

/** Parses the structured conflict; null for older servers that sent a plain string, or for an unreadable body. */
fun parseIncidentConflict(body: String?): IncidentConflict? = runCatching {
    val detail = JsonParser.parseString(body ?: return null).asJsonObject.get("detail")
    if (detail == null || !detail.isJsonObject) return null
    val obj = detail.asJsonObject
    val code = obj.get("code")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
    IncidentConflict(code, obj.get("incident_id")?.takeIf { it.isJsonPrimitive }?.asString)
}.getOrNull()

/**
 * Codes after which syncing this session can never succeed, so it is ended locally. 409 is never fatal: it means
 * the server already has an incident, which the app looks up and adopts. 408 and 429 are retried.
 */
fun isFatalSyncCode(code: Int): Boolean = code in 400..499 && code !in setOf(408, 409, 429)

/** As [isFatalSyncCode], plus the SOS rate limit, which is a 429 that retrying won't clear. */
fun isFatalSync(code: Int, errorCode: String?): Boolean = errorCode == SOS_RATE_LIMITED || isFatalSyncCode(code)

enum class ReconcileAction { KEEP, ADOPT, CLEAR_LOCAL }

/**
 * What to do with the local SOS session once the server's open incident is known.
 * - The server has an ACTIVE incident this phone isn't linked to: adopt it.
 * - The phone was linked to a server incident that no longer exists: clear the local session.
 * - A local session that never got a server incident is left alone; the sync worker creates or adopts it.
 */
fun decideReconcile(localConfirmedIncidentId: String?, serverIncidentId: String?, serverStatus: String?): ReconcileAction =
    when {
        serverIncidentId != null && serverStatus == "ACTIVE" ->
            if (localConfirmedIncidentId == serverIncidentId) ReconcileAction.KEEP else ReconcileAction.ADOPT
        serverIncidentId == null && localConfirmedIncidentId != null -> ReconcileAction.CLEAR_LOCAL
        else -> ReconcileAction.KEEP
    }

/** Server timestamps carry an offset (e.g. "+00:00"); falls back to now if the value is unreadable. */
fun serverTimeMillis(value: String?, now: Long = System.currentTimeMillis()): Long =
    runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrDefault(now).coerceAtMost(now)

/** Text shown to the user when the server refuses this session. Never contains HTTP codes. */
fun fatalSyncMessage(code: Int, errorCode: String? = null): String = if (errorCode == SOS_RATE_LIMITED)
    tr("You've sent the most SOS alerts allowed in an hour, so Maitri can't send another yet. Call 112 now if you need help.")
else when (code) {
    401, 403 -> tr("Your account can't send SOS updates right now. Sign in again, or call 112 if you need help now.")
    404, 410 -> tr("This SOS session has ended. Hold SOS again to start a new one. Call 112 if you need help now.")
    else -> tr("Maitri couldn't start this SOS session. Hold SOS again to retry, or call 112 if you need help now.")
}

/** Why Home shows a message under the SOS button. */
enum class SyncProblem {
    /** The session is still open locally but the server can't be reached; the app keeps retrying. */
    RETRYING,
    /** The session was ended on this phone because the server can't continue it. */
    ENDED
}
