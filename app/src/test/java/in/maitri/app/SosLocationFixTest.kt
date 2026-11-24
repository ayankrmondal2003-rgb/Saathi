package `in`.maitri.app

import `in`.maitri.app.location.*
import `in`.maitri.app.ui.locationLine
import org.junit.Assert.*
import org.junit.Test

/** Screen 08 "Awaiting current location" forever: SOS fixes are kept whatever their accuracy, and the reason is shown. */
class SosLocationFixTest {
    private val now = 1_800_000_000_000L

    @Test fun sosKeepsApproximateAndWeakFixes() {
        // "Approximate" permission (1-3 km) and indoor wifi fixes used to be dropped above 100 m.
        listOf(5f, 150f, 1_200f, 3_000f, 8_000f).forEach { accuracy ->
            assertTrue("$accuracy m", FixPolicy.shouldDeliver(FixMode.SOS, now, accuracy, lastDelivered = 0, now = now))
        }
    }

    @Test fun responderTrackingStillRequiresPrecision() {
        assertTrue(FixPolicy.shouldDeliver(FixMode.PRECISE, now, 100f, 0, now))
        assertFalse(FixPolicy.shouldDeliver(FixMode.PRECISE, now, 101f, 0, now))
    }

    @Test fun monotonicFilterIsKept() {
        assertFalse(FixPolicy.shouldDeliver(FixMode.SOS, now, 10f, lastDelivered = now, now = now))
        assertFalse(FixPolicy.shouldDeliver(FixMode.SOS, now - 1, 10f, lastDelivered = now, now = now))
        assertTrue(FixPolicy.shouldDeliver(FixMode.SOS, now + 1, 10f, lastDelivered = now, now = now + 1))
    }

    @Test fun lastKnownFixUpToTenMinutesStartsTheSos() {
        assertTrue(FixPolicy.shouldDeliver(FixMode.SOS, now - 9 * 60_000, 40f, 0, now))
        assertFalse(FixPolicy.shouldDeliver(FixMode.SOS, now - 11 * 60_000, 40f, 0, now))
        assertTrue(FixPolicy.isLastKnown(now - 5 * 60_000, now))
        assertFalse(FixPolicy.isLastKnown(now - 30_000, now))
    }

    @Test fun missingAccuracyIsReportedAsTheWorstAccepted() {
        assertEquals(TrackingConfig.MAX_SOS_ACCURACY_METRES, FixPolicy.accuracyOf(false, 0f))
        assertEquals(TrackingConfig.MAX_SOS_ACCURACY_METRES, FixPolicy.accuracyOf(true, Float.NaN))
        assertEquals(12f, FixPolicy.accuracyOf(true, 12f))
    }

    @Test fun reasonsComeInTheOrderTheUserCanFixThem() {
        fun hint(airplane: Boolean = false, on: Boolean = true, allowed: Boolean = true, precise: Boolean = true,
                 accuracy: Float? = null, confirmed: Boolean = false, waited: Long = 60_000) =
            locationHint(airplane, on, allowed, precise, accuracy, confirmed, waited)
        assertEquals(LocationHint.PERMISSION_MISSING, hint(allowed = false, on = false, airplane = true))
        assertEquals(LocationHint.LOCATION_OFF, hint(on = false, airplane = true))
        assertEquals(LocationHint.AIRPLANE_MODE, hint(airplane = true, accuracy = 8f))
        // Once the server has the fix, airplane mode is no longer the problem.
        assertEquals(LocationHint.NONE, hint(airplane = true, accuracy = 8f, confirmed = true))
        assertEquals(LocationHint.PRECISE_NOT_ALLOWED, hint(precise = false, accuracy = 1_500f))
        assertEquals(LocationHint.PRECISE_NOT_ALLOWED, hint(precise = false))
        assertEquals(LocationHint.SEARCHING, hint(waited = 5_000))
        assertEquals(LocationHint.WEAK_SIGNAL, hint(waited = 20_000))
        assertEquals(LocationHint.WEAK_SIGNAL, hint(accuracy = 600f))
        assertEquals(LocationHint.NONE, hint(accuracy = 15f))
    }

    @Test fun accuracyIsWrittenForPeople() {
        assertEquals("±12 m", accuracyLabel(12.4))
        assertEquals("Approx. ±650 m", accuracyLabel(648.0))
        assertEquals("Approx. ±1.2 km", accuracyLabel(1_234.0))
    }

    @Test fun mapZoomsOutForCoarseFixes() {
        assertEquals(16.0, zoomForAccuracy(null), 0.0)
        assertEquals(16.0, zoomForAccuracy(30.0), 0.0)
        assertTrue(zoomForAccuracy(1_500.0) < 13.0)
        assertEquals(10.0, zoomForAccuracy(50_000.0), 0.0)
    }

    @Test fun phoneFixShowsAtOnceThenServerConfirmsIt() {
        val local = LocationUpdate(28.61, 77.20, 1_500f, now, null, false)
        val sending = shownLocation(null, null, null, null, local)!!
        assertFalse(sending.shared)
        assertEquals(1_500.0, sending.accuracyMeters, 0.0)
        // Server holds the same fix (second precision): shared.
        assertTrue(shownLocation(28.61, 77.20, 1_500.0, now - 400, local)!!.shared)
        // Server still has an older fix: show the newer local one as sending.
        val newer = shownLocation(28.60, 77.19, 30.0, now - 60_000, local)!!
        assertFalse(newer.shared)
        assertEquals(28.61, newer.latitude, 0.0)
        assertNull(shownLocation(null, null, null, null, null))
    }

    @Test fun screen08LineNamesTheStateAndTheReason() {
        assertEquals("Finding your location…", locationLine(null, LocationHint.SEARCHING, now))
        assertEquals("Location is off", locationLine(null, LocationHint.LOCATION_OFF, now))
        val coarse = ShownFix(28.61, 77.20, 1_234.0, now, shared = false)
        assertEquals("Sending… · Approx. ±1.2 km\nPrecise location not allowed",
            locationLine(coarse, LocationHint.PRECISE_NOT_ALLOWED, now))
        val shared = ShownFix(28.61, 77.20, 9.0, now - 6 * 60_000, shared = true)
        assertTrue(locationLine(shared, LocationHint.NONE, now).startsWith("Location shared · ±9 m · Last known "))
    }
}
