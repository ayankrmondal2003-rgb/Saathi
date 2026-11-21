package com.example.surakshanet.location

import kotlinx.coroutines.flow.StateFlow

object TrackingConfig {
    const val INTERVAL_MS = 5000L
    const val MIN_INTERVAL_MS = 2000L
    const val DISTANCE_METRES = 10f
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
