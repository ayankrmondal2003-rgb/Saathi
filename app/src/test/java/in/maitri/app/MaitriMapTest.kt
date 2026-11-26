package `in`.maitri.app

import `in`.maitri.app.ui.MapContent
import `in`.maitri.app.ui.MapMarker
import `in`.maitri.app.ui.MarkerKind
import `in`.maitri.app.ui.approxDistanceAndWalk
import `in`.maitri.app.ui.bandRadiusMeters
import `in`.maitri.app.ui.circleRing
import `in`.maitri.app.ui.navigationUris
import `in`.maitri.app.ui.straightLineMeters
import `in`.maitri.app.ui.walkingMinutes
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Maps: Google Maps SDK for Android only (free mobile map loads). Bundled styles, no Map ID, no paid web services;
 * distance is straight-line and the walk an "approx." estimate; "Open in Google Maps" is only an intent.
 */
class MaitriMapTest {
    private val sources = File("src/main/java/in/maitri/app")

    @Test fun bundledStylesAreValidAndLocal() {
        for (name in listOf("maitri_map_light", "maitri_map_dark")) {
            val style = File("src/main/res/raw/$name.json").readText()
            assertTrue(name, style.trim().startsWith("[") && style.contains("\"stylers\""))
        }
    }

    @Test fun noBilledMapsServicesAndNoMapId() {
        val code = sources.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        // Map IDs (cloud styling) are billed as Dynamic Maps; these web services are billed per call.
        for (banned in listOf("mapId", "MapId", "maps.googleapis.com", "routes.googleapis.com", "places.googleapis.com",
                "com.google.android.libraries.places", "Geocoder(", "StreetView", "staticmap")) {
            assertFalse("$banned must not be used", code.contains(banned))
        }
        assertFalse("MapLibre is gone", code.contains("org.maplibre"))
        val gradle = File("build.gradle.kts").readText()
        assertFalse(gradle.contains("maplibre"))
        assertTrue(gradle.contains("com.google.maps.android:maps-compose"))
    }

    @Test fun apiKeyComesFromLocalPropertiesOnly() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:value=\"\${maitriMapsApiKey}\""))
        assertFalse("no key committed", Regex("AIza[0-9A-Za-z_-]{20,}").containsMatchIn(manifest + File("build.gradle.kts").readText()))
    }

    @Test fun openInGoogleMapsIsWalkingNavigationThenAnyMapsApp() {
        assertEquals(listOf("google.navigation:q=28.613900,77.209000&mode=w", "geo:28.613900,77.209000?q=28.613900,77.209000"),
            navigationUris(28.6139, 77.209))
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

    @Test fun circleRingIsClosedAndHasTheRightRadius() {
        val ring = circleRing(28.6, 77.2, 2000.0)
        assertEquals(ring.first(), ring.last())
        ring.forEach { (lon, lat) -> assertEquals(2000.0, straightLineMeters(28.6, 77.2, lat, lon), 25.0) }
    }

    @Test fun screen06ShowsOnlyAnAreaAroundYou() {
        assertEquals(500.0, bandRadiusMeters("Under 500 m"))
        assertEquals(1000.0, bandRadiusMeters("500 m – 1 km"))
        assertNull(bandRadiusMeters("Over 5 km"))
        assertNull(bandRadiusMeters(null))
        val you = MapMarker(28.6, 77.2, MarkerKind.YOU)
        val area = MapContent(listOf(you), areaMeters = 500.0)
        assertEquals(you, area.anchor)
        assertTrue("no pin for the person before accepting", area.markers.none { it.kind == MarkerKind.PERSON_IN_NEED })
    }

    @Test fun circlesBelongToYouThenThePerson() {
        val person = MapMarker(28.6, 77.2, MarkerKind.PERSON_IN_NEED)
        val you = MapMarker(28.61, 77.21, MarkerKind.YOU)
        val responder = MapMarker(28.62, 77.22, MarkerKind.RESPONDER)
        assertEquals(you, MapContent(listOf(person, you)).anchor)
        assertEquals(person, MapContent(listOf(responder, person)).anchor)
        assertNull(MapContent(emptyList()).anchor)
    }
}
