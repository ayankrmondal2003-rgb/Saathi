package com.example.surakshanet.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.surakshanet.BuildConfig
import com.example.surakshanet.R
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import kotlin.math.*

/** Esri World Street Map raster tiles (Web Mercator, 256 px). */
internal object EsriWorldStreetMap {
    private const val PUBLIC = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/"
    /** Keyed ArcGIS Location Platform endpoint, required by Esri's terms for production/commercial apps. */
    private const val KEYED = "https://ibasemaps-api.arcgis.com/arcgis/rest/services/World_Street_Map/MapServer/tile/"

    /** From the service's copyrightText (MapServer?f=json). Esri requires it, plus "Powered by Esri", to be shown. */
    const val SOURCES = "Sources: Esri, HERE, Garmin, USGS, Intermap, INCREMENT P, NRCan, Esri Japan, METI, " +
        "Esri China (Hong Kong), Esri Korea, Esri (Thailand), NGCC, (c) OpenStreetMap contributors, and the GIS User Community"

    /** Esri orders tile paths z/y/x (osmdroid's default is z/x/y). */
    fun tileUrl(zoom: Int, x: Int, y: Int, apiKey: String): String =
        if (apiKey.isBlank()) "$PUBLIC$zoom/$y/$x" else "$KEYED$zoom/$y/$x?token=$apiKey"
}

private class EsriTileSource(private val apiKey: String) : OnlineTileSourceBase(
    "EsriWorldStreetMap", 0, 19, 256, "", arrayOf(""), "Powered by Esri") {
    override fun getTileURLString(index: Long): String = EsriWorldStreetMap.tileUrl(
        MapTileIndex.getZoom(index), MapTileIndex.getX(index), MapTileIndex.getY(index), apiKey)
}

/** Great-circle distance in metres; used only for an explicitly labelled straight-line distance. */
internal fun straightLineMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(bLat - aLat)
    val dLon = Math.toRadians(bLon - aLon)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(min(1.0, sqrt(h)))
}

internal fun straightLineLabel(meters: Double): String =
    if (meters < 1000) "≈ ${(meters / 10).roundToInt() * 10} m straight-line"
    else "≈ ${"%.1f".format(meters / 1000)} km straight-line"

/**
 * Live map pinned at a server-reported point. With no point it shows a regional view and says so;
 * it never invents a position.
 */
@Composable
internal fun EsriMap(point: Pair<Double, Double>?, pinLabel: String, emptyLabel: String, modifier: Modifier = Modifier) {
    val p = maitriPalette()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var showSources by remember { mutableStateOf(false) }
    val map = remember(context) {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        MapView(context).apply {
            setTileSource(EsriTileSource(BuildConfig.ESRI_API_KEY))
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            isTilesScaledToDpi = true
        }
    }
    DisposableEffect(lifecycle, map) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); map.onDetach() }
    }
    Box(modifier.clip(RoundedCornerShape(24.dp))) {
        AndroidView({ map }, Modifier.fillMaxSize()) { view ->
            // World Street Map has no dark variant: dim the tiles in dark theme instead of swapping basemaps.
            view.overlayManager.tilesOverlay.setColorFilter(if (p.dark) ColorMatrixColorFilter(ColorMatrix().apply {
                setScale(0.62f, 0.58f, 0.62f, 1f)
            }) else null)
            view.overlays.removeAll { it is Marker }
            if (point != null) {
                val at = GeoPoint(point.first, point.second)
                view.overlays.add(Marker(view).apply {
                    position = at
                    title = pinLabel
                    // Exact "Person in need" marker geometry from the 07 crop, centred on the point.
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = ContextCompat.getDrawable(context, if (p.dark) R.drawable.maitri_map_pin_dark else R.drawable.maitri_map_pin_light)
                })
                if (view.tag != at) { view.controller.setZoom(16.0); view.controller.setCenter(at); view.tag = at }
            } else if (view.tag != "region") {
                view.controller.setZoom(4.5); view.controller.setCenter(GeoPoint(22.5, 79.0)); view.tag = "region"
            }
            view.invalidate()
        }
        if (point == null) Text(emptyLabel, Modifier.align(Alignment.Center)
            .background(p.surface.copy(alpha = 0.92f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
            color = p.primary, fontSize = 12.sp)
        Text("Powered by Esri · " + EsriWorldStreetMap.SOURCES,
            Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.White.copy(alpha = 0.78f))
                .clickable { showSources = true }.padding(horizontal = 8.dp, vertical = 2.dp),
            color = Color(0xFF40303A), fontSize = 8.sp, lineHeight = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (showSources) AlertDialog(onDismissRequest = { showSources = false }, title = { Text("Map data") },
        text = { Text("Powered by Esri\n\n" + EsriWorldStreetMap.SOURCES) },
        confirmButton = { TextButton(onClick = { showSources = false }) { Text("Close") } })
}
