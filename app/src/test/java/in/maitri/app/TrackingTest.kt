package `in`.maitri.app

import `in`.maitri.app.location.*
import org.junit.Assert.*
import org.junit.Test

class TrackingTest {
    private val sample = LocationUpdate(22.0, 88.0, 9f, 1000L, null, true)
    private val source = FakeSource()
    private var session = TrackingSession()
    private val events = mutableListOf<TrackingState>()
    private val tracker = LocationSubscription(source, { session = session.accept(it) }, {
        events += it
        session = session.copy(status = it)
    })

    @Test fun validatesProviderValues() {
        assertTrue(sample.valid())
        listOf(sample.copy(latitude = Double.NaN), sample.copy(longitude = -181.0),
            sample.copy(timestampMillis = 0), sample.copy(accuracyMeters = -1f),
            sample.copy(speedMetersPerSecond = Float.POSITIVE_INFINITY)).forEach {
            assertEquals(session, session.accept(it))
        }
    }

    @Test fun deniedPermissionNeverSubscribes() {
        tracker.start(false)
        assertEquals(TrackingState.PERMISSION_REQUIRED, session.status)
        assertEquals(0, source.starts)
    }

    @Test fun startAndStopAreIdempotentAndLateFixesAreIgnored() {
        tracker.start(true)
        tracker.start(true)
        assertEquals(1, source.starts)
        assertEquals(TrackingState.STARTING, session.status)
        source.fix(sample)
        assertEquals(TrackingState.TRACKING, session.status)
        tracker.stop()
        tracker.stop()
        source.fix(sample.copy(timestampMillis = 2000))
        assertEquals(1, source.stops)
        assertTrue(TrackingState.STOPPING in events)
        assertEquals(TrackingState.STOPPED, session.status)
        assertEquals(sample, session.latest)
    }

    @Test fun unavailablePreservesTimestampUntilANewerFixArrives() {
        tracker.start(true)
        source.status(TrackingState.LOCATION_UNAVAILABLE)
        assertNull(session.latest)
        source.fix(sample)
        source.status(TrackingState.LOCATION_UNAVAILABLE)
        source.fix(sample)
        source.fix(sample.copy(timestampMillis = 900))
        assertEquals(TrackingState.LOCATION_UNAVAILABLE, session.status)
        assertEquals(1000L, session.latest!!.timestampMillis)
        assertEquals(1, session.count)
        source.fix(sample.copy(timestampMillis = 1500))
        assertEquals(TrackingState.TRACKING, session.status)
        assertEquals(2, session.count)
        assertTrue(session.latest!!.isMock)
    }

    private class FakeSource : LocationSource {
        var starts = 0
        var stops = 0
        lateinit var fix: (LocationUpdate) -> Unit
        lateinit var status: (TrackingState) -> Unit
        override fun subscribe(fix: (LocationUpdate) -> Unit, status: (TrackingState) -> Unit) {
            starts++
            this.fix = fix
            this.status = status
        }
        override fun cancel() { stops++ }
    }
}
