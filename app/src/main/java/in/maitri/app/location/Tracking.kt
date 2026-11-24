package `in`.maitri.app.location

import kotlinx.coroutines.flow.StateFlow

object TrackingConfig {
    const val INTERVAL_MS = 5000L
    const val MIN_INTERVAL_MS = 2000L
    const val DISTANCE_METRES = 10f
    /** Matches the server's max_accuracy_meters: responder fixes and geofence matching need this precision. */
    const val PRECISE_METRES = 100f
    /** Matches the server's max_sos_accuracy_meters: an SOS keeps any fix up to this, e.g. "Approximate" (1-3 km). */
    const val MAX_SOS_ACCURACY_METRES = 5000f
    /** Matches the server's max_last_known_age_seconds: the first SOS fix may be a last-known fix this old. */
    const val LAST_KNOWN_MAX_AGE_MS = 600_000L
}

/** Which fixes a [LocationSource] delivers. */
enum class FixMode {
    /** Victim SOS: never drop a fix for accuracy alone, keep updating while stationary, start from the last-known fix. */
    SOS,
    /** Responder position: only precise fixes, as the server and geofence require. */
    PRECISE
}

/** Pure delivery rules, shared by the fused source and unit tests. */
object FixPolicy {
    /** A fix without an accuracy value is reported as the worst accuracy an SOS still accepts. */
    fun accuracyOf(hasAccuracy: Boolean, accuracy: Float): Float =
        if (hasAccuracy && accuracy.isFinite() && accuracy >= 0f) accuracy else TrackingConfig.MAX_SOS_ACCURACY_METRES

    fun shouldDeliver(mode: FixMode, timeMillis: Long, accuracyMeters: Float, lastDelivered: Long, now: Long): Boolean {
        if (timeMillis <= lastDelivered) return false // monotonic: duplicate, cached or out-of-order
        return when (mode) {
            FixMode.SOS -> timeMillis >= now - TrackingConfig.LAST_KNOWN_MAX_AGE_MS
            FixMode.PRECISE -> accuracyMeters <= TrackingConfig.PRECISE_METRES
        }
    }

    /** True when the fix is a last-known fix rather than a current one (shown as "Last known · time"). */
    fun isLastKnown(timeMillis: Long, now: Long): Boolean = now - timeMillis > 120_000L
}

enum class TrackingState { IDLE, STARTING, TRACKING, LOCATION_UNAVAILABLE, PERMISSION_REQUIRED, STOPPING, STOPPED, ERROR }

data class LocationUpdate(val latitude: Double, val longitude: Double, val accuracyMeters: Float,
                          val timestampMillis: Long, val speedMetersPerSecond: Float?, val isMock: Boolean) {
    fun valid() = latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        accuracyMeters.isFinite() && accuracyMeters >= 0 && timestampMillis > 0 &&
        (speedMetersPerSecond == null || speedMetersPerSecond.isFinite() && speedMetersPerSecond >= 0)
}

data class TrackingSession(val owner: String = "", val id: String = "", val startedAt: Long = 0,
                           val status: TrackingState = TrackingState.IDLE, val running: Boolean = false,
                           val latest: LocationUpdate? = null, val count: Int = 0) {
    fun accept(fix: LocationUpdate): TrackingSession {
        if (!fix.valid() || fix.timestampMillis <= (latest?.timestampMillis ?: 0)) return this
        return copy(status = TrackingState.TRACKING, latest = fix, count = count + 1)
    }
}

interface LocationRepository { val session: StateFlow<TrackingSession> }

interface LocationSource {
    fun subscribe(fix: (LocationUpdate) -> Unit, status: (TrackingState) -> Unit)
    fun cancel()
}

// Owned by one Service instance. All events arrive on the main thread.
class LocationSubscription(private val source: LocationSource, private val record: (LocationUpdate) -> Unit,
                           private val status: (TrackingState) -> Unit) {
    private var active = false
    fun start(permitted: Boolean) {
        if (active) return
        if (!permitted) { status(TrackingState.PERMISSION_REQUIRED); return }
        active = true
        status(TrackingState.STARTING)
        source.subscribe({ if (active) record(it) }, { if (active) status(it) })
    }
    fun stop() {
        if (active) {
            active = false
            status(TrackingState.STOPPING)
            source.cancel()
        }
        status(TrackingState.STOPPED)
    }
}
