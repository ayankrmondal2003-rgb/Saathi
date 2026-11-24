package `in`.maitri.app

import `in`.maitri.app.ui.OpenFreeMap
import `in`.maitri.app.ui.approxDistanceAndWalk
import `in`.maitri.app.ui.circleRing
import `in`.maitri.app.ui.straightLineMeters
import `in`.maitri.app.ui.walkingMinutes
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Screens 07/08: MapLibre + OpenFreeMap. Distance is straight-line and the walk is an "approx." estimate. */
class MaitriMapTest {
    @Test fun lightStyleIsOpenFreeMapLibertyAndAttributionNamesOsm() {
        assertEquals("https://tiles.openfreemap.org/styles/liberty", OpenFreeMap.LIGHT_STYLE)
        assertTrue(OpenFreeMap.ATTRIBUTION.contains("OpenStreetMap contributors"))
        assertTrue(OpenFreeMap.ATTRIBUTION.contains("OpenFreeMap"))
    }

    @Test fun bundledDarkStyleUsesOpenFreeMapSourcesOnly() {
        val style = File("src/main/assets/" + OpenFreeMap.DARK_STYLE_ASSET).readText()
        assertTrue(style.contains("https://tiles.openfreemap.org/planet"))
        assertFalse("no Esri or keyed endpoints", Regex("arcgis|esri|api_key|token=", RegexOption.IGNORE_CASE).containsMatchIn(style))
    }

    @Test fun distanceAndWalkAreApproximate() {
        // India Gate to Connaught Place, New Delhi: about 2.4 km as the crow flies.
        val m = straightLineMeters(28.6129, 77.2295, 28.6315, 77.2167)
        assertTrue(m in 2300.0..2500.0)
        assertEquals("≈ 650 m away · approx. 11 min walk", approxDistanceAndWalk(648.0))
        assertEquals("≈ 2.4 km away · approx. ${walkingMinutes(m)} min walk", approxDistanceAndWalk(m))
        assertEquals(1, walkingMinutes(0.0))
        assertEquals(0.0, straightLineMeters(28.6, 77.2, 28.6, 77.2), 0.001)
    }

    @Test fun accuracyRingIsClosedAndHasTheRightRadius() {
        val ring = circleRing(28.6, 77.2, 2000.0)
        assertEquals(ring.first(), ring.last())
        ring.forEach { (lon, lat) -> assertEquals(2000.0, straightLineMeters(28.6, 77.2, lat, lon), 25.0) }
    }
}
