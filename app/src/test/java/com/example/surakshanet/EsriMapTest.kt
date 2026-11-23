package com.example.surakshanet

import com.example.surakshanet.ui.EsriWorldStreetMap
import com.example.surakshanet.ui.straightLineLabel
import com.example.surakshanet.ui.straightLineMeters
import org.junit.Assert.*
import org.junit.Test

/** Screens 07/08 use Esri World Street Map tiles; distances are straight-line only (no routing service). */
class EsriMapTest {
    @Test fun tileUrlsUseEsriRowColumnOrder() {
        assertEquals("https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/16/28520/46412",
            EsriWorldStreetMap.tileUrl(zoom = 16, x = 46412, y = 28520, apiKey = ""))
    }

    @Test fun anApiKeySwitchesToTheKeyedArcGisEndpoint() {
        val url = EsriWorldStreetMap.tileUrl(10, 1, 2, "AAPK-test.key_1")
        assertTrue(url.startsWith("https://ibasemaps-api.arcgis.com/arcgis/rest/services/World_Street_Map/MapServer/tile/10/2/1"))
        assertTrue(url.endsWith("?token=AAPK-test.key_1"))
    }

    @Test fun attributionMatchesTheServiceCopyrightText() {
        assertTrue(EsriWorldStreetMap.SOURCES.startsWith("Sources: Esri, HERE, Garmin"))
        assertTrue(EsriWorldStreetMap.SOURCES.contains("OpenStreetMap contributors"))
    }

    @Test fun distanceIsLabelledStraightLine() {
        // India Gate to Connaught Place, New Delhi: about 2.4 km as the crow flies.
        val m = straightLineMeters(28.6129, 77.2295, 28.6315, 77.2167)
        assertTrue(m in 2300.0..2500.0)
        assertEquals("≈ 2.4 km straight-line", straightLineLabel(m))
        assertEquals("≈ 650 m straight-line", straightLineLabel(648.0))
        assertEquals(0.0, straightLineMeters(28.6, 77.2, 28.6, 77.2), 0.001)
    }
}
