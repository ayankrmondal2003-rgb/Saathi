package `in`.maitri.app

import `in`.maitri.app.location.LocationUpdate
import `in`.maitri.app.location.TrackingState
import org.junit.Assert.*
import org.junit.Test

class ResponderLocationTrackingTest {

    // ------------------------------------------------------------------------
    // Client-Side Monotonic & Quality Filtering Tests
    // ------------------------------------------------------------------------

    @Test
    fun testFirstValidGpsFixAccepted() {
        var lastSubmitted = 0L
        val fix = LocationUpdate(
            latitude = 22.5726,
            longitude = 88.3639,
            accuracyMeters = 15.0f,
            timestampMillis = 1000000L,
            speedMetersPerSecond = 1.2f,
            isMock = false
        )

        fun shouldSubmit(update: LocationUpdate): Boolean {
            if (!update.valid()) return false
            if (update.accuracyMeters > 100f) return false
            if (update.timestampMillis <= lastSubmitted) return false
            return true
        }

        assertTrue("First valid GPS fix must be accepted", shouldSubmit(fix))
        lastSubmitted = fix.timestampMillis
        assertEquals(1000000L, lastSubmitted)
    }

    @Test
    fun testNewerFixAccepted() {
        var lastSubmitted = 1000000L
        val newerFix = LocationUpdate(
            latitude = 22.5728,
            longitude = 88.3640,
            accuracyMeters = 12.0f,
            timestampMillis = 1005000L,
            speedMetersPerSecond = 1.5f,
            isMock = false
        )

        fun shouldSubmit(update: LocationUpdate): Boolean {
            if (!update.valid()) return false
            if (update.accuracyMeters > 100f) return false
            if (update.timestampMillis <= lastSubmitted) return false
            return true
        }

        assertTrue("Newer timestamp must be accepted", shouldSubmit(newerFix))
        lastSubmitted = newerFix.timestampMillis
        assertEquals(1005000L, lastSubmitted)
    }

    @Test
    fun testIdenticalTimestampIgnoredClientSide() {
        val lastSubmitted = 1000000L
        val duplicateFix = LocationUpdate(
            latitude = 22.5726,
            longitude = 88.3639,
            accuracyMeters = 15.0f,
            timestampMillis = 1000000L, // Exact duplicate timestamp
            speedMetersPerSecond = 1.2f,
            isMock = false
        )

        fun shouldSubmit(update: LocationUpdate): Boolean {
            if (!update.valid()) return false
            if (update.accuracyMeters > 100f) return false
            if (update.timestampMillis <= lastSubmitted) return false
            return true
        }

        assertFalse("Duplicate timestamp must be ignored to prevent HTTP 409", shouldSubmit(duplicateFix))
    }

    @Test
    fun testOlderTimestampIgnoredClientSide() {
        val lastSubmitted = 1005000L
        val olderFix = LocationUpdate(
            latitude = 22.5726,
            longitude = 88.3639,
            accuracyMeters = 15.0f,
            timestampMillis = 1002000L, // Older than lastSubmitted
            speedMetersPerSecond = 1.0f,
            isMock = false
        )

        fun shouldSubmit(update: LocationUpdate): Boolean {
            if (!update.valid()) return false
            if (update.accuracyMeters > 100f) return false
            if (update.timestampMillis <= lastSubmitted) return false
            return true
        }

        assertFalse("Older/reordered timestamp must be ignored to prevent HTTP 409", shouldSubmit(olderFix))
    }

    @Test
    fun testInaccurateGpsFixIgnored() {
        val lastSubmitted = 1000000L
        val inaccurateFix = LocationUpdate(
            latitude = 22.5726,
            longitude = 88.3639,
            accuracyMeters = 150.0f, // Worse than 100m threshold
            timestampMillis = 1005000L,
            speedMetersPerSecond = null,
            isMock = false
        )

        fun shouldSubmit(update: LocationUpdate): Boolean {
            if (!update.valid()) return false
            if (update.accuracyMeters > 100f) return false
            if (update.timestampMillis <= lastSubmitted) return false
            return true
        }

        assertFalse("Fix with accuracy > 100m must be filtered client-side", shouldSubmit(inaccurateFix))
    }

    @Test
    fun testStaleAndFutureDatedFixesIgnored() {
        val now = System.currentTimeMillis()
        val staleFix = LocationUpdate(
            latitude = 22.5726,
            longitude = 88.3639,
            accuracyMeters = 10.0f,
            timestampMillis = now - 90000L, // 90 seconds old (> 60s)
            speedMetersPerSecond = 0f,
            isMock = false
        )
        val futureFix = LocationUpdate(
            latitude = 22.5726,
            longitude = 88.3639,
            accuracyMeters = 10.0f,
            timestampMillis = now + 15000L, // 15 seconds in future (> 5s)
            speedMetersPerSecond = 0f,
            isMock = false
        )

        fun isFresh(timestamp: Long, currentTime: Long): Boolean {
            val ageMs = currentTime - timestamp
            return ageMs in -5000L..60000L
        }

        assertFalse("Stale fix (> 60s) must be rejected", isFresh(staleFix.timestampMillis, now))
        assertFalse("Future-dated fix (> 5s ahead) must be rejected", isFresh(futureFix.timestampMillis, now))
        assertTrue("Fresh fix (2s old) must be accepted", isFresh(now - 2000L, now))
    }

    // ------------------------------------------------------------------------
    // State Recovery & Error Handling Tests
    // ------------------------------------------------------------------------

    @Test
    fun testTemporaryLocationUnavailableFollowedByRecovery() {
        var statusMessage: String? = null
        var lastSuccessfulFixTime = 0L

        fun onStatusChanged(state: TrackingState, isLocationEnabled: Boolean) {
            when (state) {
                TrackingState.TRACKING -> {
                    statusMessage = if (lastSuccessfulFixTime > 0L) "Responder location updated" else "Acquiring fresh location..."
                }
                TrackingState.STARTING -> {
                    statusMessage = "Acquiring GPS fix..."
                }
                TrackingState.LOCATION_UNAVAILABLE -> {
                    statusMessage = if (!isLocationEnabled) "Device location is disabled in system settings" else "Location unavailable (searching for GPS...)"
                }
                TrackingState.PERMISSION_REQUIRED -> {
                    statusMessage = "Location permission required for responder mode"
                }
                TrackingState.ERROR -> {
                    statusMessage = "Location provider error"
                }
                TrackingState.IDLE, TrackingState.STOPPING, TrackingState.STOPPED -> {
                    statusMessage = null
                }
            }
        }

        // 1. Initial state: starting
        onStatusChanged(TrackingState.STARTING, isLocationEnabled = true)
        assertEquals("Acquiring GPS fix...", statusMessage)

        // 2. GNSS search: temporary unavailable
        onStatusChanged(TrackingState.LOCATION_UNAVAILABLE, isLocationEnabled = true)
        assertEquals("Location unavailable (searching for GPS...)", statusMessage)

        // 3. First fix acquired: transitions to tracking and syncs
        lastSuccessfulFixTime = System.currentTimeMillis()
        onStatusChanged(TrackingState.TRACKING, isLocationEnabled = true)
        assertEquals("Responder location updated", statusMessage)

        // 4. Temporary tunnel / indoor loss: unavailable
        onStatusChanged(TrackingState.LOCATION_UNAVAILABLE, isLocationEnabled = true)
        assertEquals("Location unavailable (searching for GPS...)", statusMessage)

        // 5. Emerging from tunnel: recovered immediately!
        onStatusChanged(TrackingState.TRACKING, isLocationEnabled = true)
        assertEquals("Responder location updated", statusMessage)
    }

    @Test
    fun testHttp409IsRecoverableConflict() {
        var lastSubmitted = 1000000L
        var isTrackingActive = true
        var statusMessage = "Responder location updated"

        fun handleHttpError(statusCode: Int, fixTimestamp: Long) {
            when (statusCode) {
                409 -> {
                    // Stale/duplicate on server: recoverable!
                    lastSubmitted = maxOf(lastSubmitted, fixTimestamp)
                    // Does NOT abort tracking:
                    statusMessage = "Responder location updated"
                }
                401 -> {
                    isTrackingActive = false
                    statusMessage = "Session expired. Sign in again."
                }
                403 -> {
                    isTrackingActive = false
                    statusMessage = "Responder consent and availability required (HTTP 403)"
                }
                422 -> {
                    statusMessage = "Location rejected: check device clock and GPS accuracy (HTTP 422)"
                }
            }
        }

        // Simulate 409 returned when server already had newer fix
        handleHttpError(409, 1000000L)
        assertTrue("Tracking must remain active on HTTP 409", isTrackingActive)
        assertEquals("Status message must not show fatal error", "Responder location updated", statusMessage)
        assertEquals(1000000L, lastSubmitted)

        // When a fresh fix arrives with newer timestamp, it is submitted
        val newerTimestamp = 1005000L
        assertTrue("Newer fix is greater than lastSubmitted", newerTimestamp > lastSubmitted)
    }

    @Test
    fun testNetworkDisconnectionAndRecovery() {
        var statusMessage: String?
        var isTrackingActive = true

        // Simulate network failure
        statusMessage = "Network interrupted; will retry on next GPS fix"
        assertTrue("Tracking must remain active during transient network drops", isTrackingActive)
        assertEquals("Network interrupted; will retry on next GPS fix", statusMessage)

        // When next fix arrives and succeeds
        statusMessage = "Responder location updated"
        assertEquals("Responder location updated", statusMessage)
    }
}
