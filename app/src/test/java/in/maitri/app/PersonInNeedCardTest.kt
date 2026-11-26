package `in`.maitri.app

import `in`.maitri.app.ui.approxDistanceAndWalk
import `in`.maitri.app.ui.lastUpdatedLabel
import `in`.maitri.app.ui.mapsLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant

/** Screen 07's "Person in need" card: no raw coordinates on screen, only an approximate distance and walk. */
class PersonInNeedCardTest {
    private val now = Instant.parse("2026-11-26T12:00:00Z")

    @Test fun distanceAndWalkAreApproximate() {
        assertEquals("≈ 10 m away · approx. 1 min walk", approxDistanceAndWalk(4.0))
        assertEquals("≈ 650 m away · approx. 11 min walk", approxDistanceAndWalk(648.0))
        assertFalse(approxDistanceAndWalk(648.0).contains("28."))
    }

    @Test fun lastUpdatedCountsUp() {
        assertEquals("Last updated 5 s ago", lastUpdatedLabel("2026-11-26T11:59:55Z", now))
        assertEquals("Last updated 3 min ago", lastUpdatedLabel("2026-11-26T11:57:00Z", now))
        assertEquals("Last updated 2 h ago", lastUpdatedLabel("2026-11-26T10:00:00+00:00", now))
        assertEquals("Last updated 0 s ago", lastUpdatedLabel("2026-11-26T12:00:03Z", now))  // phone clock a little behind
        assertEquals(null, lastUpdatedLabel("not a time", now))
    }

    @Test fun copyLocationIsAPlainMapsLink() {
        assertEquals("https://www.google.com/maps/search/?api=1&query=28.613900,77.209000", mapsLink(28.6139, 77.209))
    }
}
