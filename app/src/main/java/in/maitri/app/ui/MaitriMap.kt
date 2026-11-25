package `in`.maitri.app.ui

import `in`.maitri.app.tr

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import `in`.maitri.app.R
import `in`.maitri.app.location.TrackingConfig
import `in`.maitri.app.location.zoomForAccuracy
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.*

/** OpenFreeMap: free, no key, commercial use allowed. Data © OpenStreetMap contributors. */
internal object OpenFreeMap {
    const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
    /** Bundled; generated from liberty by tools/make_dark_map_style.py. Same tiles and attribution. */
    const val DARK_STYLE_ASSET = "map/maitri-dark.json"
    const val ATTRIBUTION = "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors"
    val LINKS = listOf(
        tr("OpenFreeMap") to "https://openfreemap.org",
        tr("OpenMapTiles") to "https://www.openmaptiles.org/",
        tr("OpenStreetMap contributors") to "https://www.openstreetmap.org/copyright")
}

/** A point drawn on the map. [kind] picks the marker artwork. */
internal data class MapMarker(val latitude: Double, val longitude: Double, val kind: MarkerKind)

internal enum class MarkerKind(val image: String) { PERSON_IN_NEED("maitri-person"), YOU("maitri-you"), RESPONDER("maitri-responder") }

/** Great-circle distance in metres. Shown only as an approximate, straight-line value (no routing service). */
internal fun straightLineMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(bLat - aLat)
    val dLon = Math.toRadians(bLon - aLon)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(min(1.0, sqrt(h)))
}

/**
 * Walking time for a straight-line distance: streets are rarely straight (×1.3) and people walk about 80 m a
 * minute (4.8 km/h). Always labelled "approx." because no route is computed.
 */
internal fun walkingMinutes(straightMeters: Double): Int = max(1, ceil(straightMeters * 1.3 / 80.0).toInt())

internal fun distanceLabel(meters: Double): String =
    if (meters < 1000) "${max(10, (meters / 10).roundToInt() * 10)} m" else "${"%.1f".format(meters / 1000)} km"

/** e.g. "≈ 650 m away · approx. 11 min walk". */
internal fun approxDistanceAndWalk(meters: Double): String =
    tr("≈ {0} away · approx. {1} min walk", distanceLabel(meters), walkingMinutes(meters))

/** Accuracy circle as a 64-point polygon (lon/lat ring, closed). */
internal fun circleRing(latitude: Double, longitude: Double, radiusMeters: Double, steps: Int = 64): List<Pair<Double, Double>> {
    val dLat = radiusMeters / 111_320.0
    val dLon = radiusMeters / (111_320.0 * cos(Math.toRadians(latitude)).coerceAtLeast(0.01))
    return (0..steps).map { i ->
        val a = 2 * PI * (i % steps) / steps
        (longitude + dLon * cos(a)) to (latitude + dLat * sin(a))
    }
}

/**
 * Live map (MapLibre + OpenFreeMap). Draws only positions it was given; with none it shows a regional view and
 * [emptyLabel], and never invents a position. [accuracyMeters] belongs to the first PERSON_IN_NEED or YOU marker.
 */
/**
 * MapLibre needs OpenGL ES 3.0 and crashes natively (uncatchable) on GPUs that only have 2.0: older and budget
 * "Android Go" phones, and the Android 8 emulator. Those get a card with the coordinates and Open in Maps instead.
 */
internal fun liveMapSupported(context: Context): Boolean {
    val info = (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.deviceConfigurationInfo
    return (info?.reqGlEsVersion ?: 0) >= 0x30000
}

@Composable
internal fun MaitriMap(markers: List<MapMarker>, emptyLabel: String, modifier: Modifier = Modifier,
                       accuracyMeters: Double? = null, rangeMeters: Double? = null, alwaysShowAccuracy: Boolean = false) {
    val context = LocalContext.current
    val live = remember(context) { liveMapSupported(context) }
    if (live) MaitriLiveMap(markers, emptyLabel, modifier, accuracyMeters, rangeMeters, alwaysShowAccuracy)
    else MaitriMapFallback(markers, emptyLabel, modifier, accuracyMeters)
}

/** No map on this phone: where the person is, in words and numbers, and a hand-off to the phone's maps app. */
@Composable
private fun MaitriMapFallback(markers: List<MapMarker>, emptyLabel: String, modifier: Modifier, accuracyMeters: Double?) {
    val p = maitriPalette()
    val context = LocalContext.current
    val target = markers.firstOrNull { it.kind == MarkerKind.PERSON_IN_NEED } ?: markers.firstOrNull()
    Box(modifier.clip(RoundedCornerShape(24.dp)).background(p.card).padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (target == null) Text(emptyLabel, color = p.primary, fontSize = 13.sp)
            else {
                Text("%.5f, %.5f".format(java.util.Locale.ROOT, target.latitude, target.longitude), color = p.primary,
                    fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                accuracyMeters?.let { Text(tr("Accurate to about {0} m", it.toInt()), color = p.secondary, fontSize = 12.sp) }
                TextButton(onClick = {
                    val uri = Uri.parse("geo:${target.latitude},${target.longitude}?q=${target.latitude},${target.longitude}")
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                }) { Text(tr("Open in Maps"), color = p.accent, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) }
            }
            Text(tr("The live map isn't available on this phone."), color = p.secondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MaitriLiveMap(markers: List<MapMarker>, emptyLabel: String, modifier: Modifier = Modifier,
                          accuracyMeters: Double? = null, rangeMeters: Double? = null, alwaysShowAccuracy: Boolean = false) {
    val p = maitriPalette()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var showCredits by remember { mutableStateOf(false) }
    val holder = remember(context) {
        MapLibre.getInstance(context)
        MapHolder(MapView(context).apply { onCreate(null) })
    }
    DisposableEffect(lifecycle, holder) {
        val view = holder.view
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStop()
            view.onDestroy()
        }
    }
    val data = MapData(markers, accuracyMeters, p.dark, rangeMeters, alwaysShowAccuracy)
    Box(modifier.clip(RoundedCornerShape(24.dp)).background(p.card)) {
        AndroidView({ holder.view }, Modifier.fillMaxSize()) { holder.show(data) }
        if (markers.isEmpty()) Text(emptyLabel, Modifier.align(Alignment.Center)
            .background(p.surface.copy(alpha = 0.92f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
            color = p.primary, fontSize = 12.sp)
        Text(OpenFreeMap.ATTRIBUTION,
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background((if (p.dark) Color.Black else Color.White).copy(alpha = 0.72f))
                .clickable(onClickLabel = tr("Map data credits")) { showCredits = true }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            color = if (p.dark) Color(0xFFE8D9E1) else Color(0xFF40303A), fontSize = 8.sp, lineHeight = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (showCredits) AlertDialog(modifier = Modifier.dialogEnter(), onDismissRequest = { showCredits = false }, title = { Text(tr("Map data")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr("Map tiles from OpenFreeMap, built with OpenMapTiles. Map data © OpenStreetMap contributors."))
                OpenFreeMap.LINKS.forEach { (name, url) ->
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    }) { Text(name) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showCredits = false }) { Text(tr("Close")) } })
}

/** [rangeMeters]: a faint circle around the first marker (a volunteer's request area), kept in view. */
private data class MapData(val markers: List<MapMarker>, val accuracyMeters: Double?, val dark: Boolean,
                           val rangeMeters: Double? = null, val alwaysShowAccuracy: Boolean = false)

/** Owns the MapView and redraws when the style is ready or the data changes. */
private class MapHolder(val view: MapView) {
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var styleDark: Boolean? = null
    private var pending: MapData? = null
    private var framing: Any? = null

    init {
        view.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                isLogoEnabled = false
                isAttributionEnabled = false  // shown as text over the map instead, always visible
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isCompassEnabled = false
            }
            pending?.let { show(it) }
        }
    }

    fun show(data: MapData) {
        pending = data
        val m = map ?: return
        if (styleDark != data.dark) {
            styleDark = data.dark
            style = null
            val builder = if (data.dark) Style.Builder().fromJson(
                view.context.assets.open(OpenFreeMap.DARK_STYLE_ASSET).bufferedReader().use { it.readText() })
                else Style.Builder().fromUri(OpenFreeMap.LIGHT_STYLE)
            m.setStyle(builder) { loaded -> addArtwork(loaded); style = loaded; pending?.let { draw(m, loaded, it) } }
            return
        }
        style?.takeIf { it.isFullyLoaded }?.let { draw(m, it, data) }
    }

    private fun addArtwork(style: Style) {
        val ctx = view.context
        fun image(name: String, res: Int) = ContextCompat.getDrawable(ctx, res)?.toBitmap()?.let { style.addImage(name, it) }
        val dark = styleDark == true
        image(MarkerKind.PERSON_IN_NEED.image, if (dark) R.drawable.maitri_map_pin_dark else R.drawable.maitri_map_pin_light)
        image(MarkerKind.YOU.image, R.drawable.maitri_map_you)
        image(MarkerKind.RESPONDER.image, R.drawable.maitri_map_responder)
        style.addSource(GeoJsonSource(RANGE))
        style.addLayer(FillLayer(RANGE, RANGE).withProperties(
            PropertyFactory.fillColor(if (dark) "#FF9CBE" else "#B82055"), PropertyFactory.fillOpacity(0.06f)))
        style.addLayer(LineLayer(RANGE + "-edge", RANGE).withProperties(
            PropertyFactory.lineColor(if (dark) "#FF9CBE" else "#B82055"), PropertyFactory.lineOpacity(0.45f),
            PropertyFactory.lineWidth(1.5f), PropertyFactory.lineDasharray(arrayOf(2f, 2f))))
        style.addSource(GeoJsonSource(ACCURACY))
        style.addLayer(FillLayer(ACCURACY, ACCURACY).withProperties(
            PropertyFactory.fillColor("#B82055"), PropertyFactory.fillOpacity(0.18f)))
        style.addLayer(LineLayer(ACCURACY + "-edge", ACCURACY).withProperties(
            PropertyFactory.lineColor("#B82055"), PropertyFactory.lineOpacity(0.65f), PropertyFactory.lineWidth(1.5f)))
        style.addSource(GeoJsonSource(MARKERS))
        style.addLayer(SymbolLayer(MARKERS, MARKERS).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
    }

    private fun draw(m: MapLibreMap, style: Style, data: MapData) {
        style.getSourceAs<GeoJsonSource>(MARKERS)?.setGeoJson(FeatureCollection.fromFeatures(data.markers.map {
            Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)).apply { addStringProperty("icon", it.kind.image) }
        }))
        val anchor = data.markers.firstOrNull { it.kind != MarkerKind.RESPONDER }
        val ring = anchor?.takeIf { (data.accuracyMeters ?: 0.0) > (if (data.alwaysShowAccuracy) 0.0 else TrackingConfig.PRECISE_METRES.toDouble()) }
            ?.let { circleRing(it.latitude, it.longitude, data.accuracyMeters!!) }
        val range = anchor?.let { a -> data.rangeMeters?.let { circleRing(a.latitude, a.longitude, it) } }
        style.getSourceAs<GeoJsonSource>(RANGE)?.setGeoJson(FeatureCollection.fromFeatures(
            listOfNotNull(range?.let { r -> Feature.fromGeometry(Polygon.fromLngLats(listOf(r.map { Point.fromLngLat(it.first, it.second) }))) })))
        style.getSourceAs<GeoJsonSource>(ACCURACY)?.setGeoJson(FeatureCollection.fromFeatures(
            listOfNotNull(ring?.let { r -> Feature.fromGeometry(Polygon.fromLngLats(listOf(r.map { Point.fromLngLat(it.first, it.second) }))) })))
        frame(m, data)
    }

    /** Moves the camera only when what must be visible changes, so a user's pan isn't undone on every update. */
    private fun frame(m: MapLibreMap, data: MapData) {
        val points = data.markers.map { LatLng(it.latitude, it.longitude) }
        val range = data.rangeMeters
        val key: Any = when {
            points.isEmpty() -> "region"
            points.size == 1 && range != null -> "range" to (points[0].latitude * 1e3).roundToInt() to (points[0].longitude * 1e3).roundToInt()
            points.size == 1 -> points[0] to data.accuracyMeters?.let { zoomForAccuracy(it) }
            else -> points.toSet()
        }
        if (key == framing) return
        framing = key
        when {
            points.isEmpty() -> m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(22.5, 79.0), 3.5))
            // The whole request area in view; re-framed only after moving about 100 m, so panning isn't undone.
            points.size == 1 && range != null -> view.post {
                runCatching {
                    val ring = circleRing(points[0].latitude, points[0].longitude, range)
                    val pad = (24 * view.resources.displayMetrics.density).toInt()
                    m.moveCamera(CameraUpdateFactory.newLatLngBounds(
                        LatLngBounds.Builder().includes(ring.map { LatLng(it.second, it.first) }).build(), pad))
                }
            }
            points.size == 1 -> m.moveCamera(CameraUpdateFactory.newLatLngZoom(points[0], zoomForAccuracy(data.accuracyMeters)))
            else -> view.post {
                runCatching {
                    // 56 dp around the markers so neither sits on the edge or under the attribution line.
                    val pad = (56 * view.resources.displayMetrics.density).toInt()
                    m.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), pad))
                    // People a few metres apart would zoom to the maximum, where there's nothing to see: keep streets in view.
                    if (m.cameraPosition.zoom > 16.5) m.moveCamera(CameraUpdateFactory.zoomTo(16.5))
                }
            }
        }
    }

    companion object {
        const val MARKERS = "maitri-markers"
        const val ACCURACY = "maitri-accuracy"
        const val RANGE = "maitri-range"
    }
}
