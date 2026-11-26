package `in`.maitri.app.ui

import `in`.maitri.app.tr

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.*
import `in`.maitri.app.R
import kotlin.math.*

/** A point drawn on the map. [kind] picks the marker. */
internal data class MapMarker(val latitude: Double, val longitude: Double, val kind: MarkerKind)

/** Pink pin: the person in danger. Teal dot: a responder. Blue dot (with its accuracy circle): you. */
internal enum class MarkerKind { PERSON_IN_NEED, YOU, RESPONDER }

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
 * minute (4.8 km/h). Always labelled "approx." because no route is computed (no Routes/Directions API).
 */
internal fun walkingMinutes(straightMeters: Double): Int = max(1, ceil(straightMeters * 1.3 / 80.0).toInt())

internal fun distanceLabel(meters: Double): String =
    if (meters < 1000) "${max(10, (meters / 10).roundToInt() * 10)} m" else "${"%.1f".format(meters / 1000)} km"

/** e.g. "≈ 650 m away · approx. 11 min walk". */
internal fun approxDistanceAndWalk(meters: Double): String =
    tr("≈ {0} away · approx. {1} min walk", distanceLabel(meters), walkingMinutes(meters))

/** A circle as a ring of (longitude, latitude) points, closed. Used to fit the camera around a circle. */
internal fun circleRing(latitude: Double, longitude: Double, radiusMeters: Double, steps: Int = 64): List<Pair<Double, Double>> {
    val dLat = radiusMeters / 111_320.0
    val dLon = radiusMeters / (111_320.0 * cos(Math.toRadians(latitude)).coerceAtLeast(0.01))
    return (0..steps).map { i ->
        val a = 2 * PI * (i % steps) / steps
        (longitude + dLon * cos(a)) to (latitude + dLat * sin(a))
    }
}

/** The outer edge of a distance band ("Under 500 m" → 500 m): screen 06's approximate area, never a position. */
internal fun bandRadiusMeters(band: String?): Double? = when (band) {
    "Under 500 m" -> 500.0
    "500 m – 1 km" -> 1000.0
    "1–2 km" -> 2000.0
    "2–5 km" -> 5000.0
    else -> null
}

/** The navigation hand-off for "Open in Google Maps": walking directions in the Google Maps app, then any maps app. */
internal fun navigationUris(latitude: Double, longitude: Double): List<String> {
    val at = "%.6f,%.6f".format(java.util.Locale.ROOT, latitude, longitude)
    return listOf("google.navigation:q=$at&mode=w", "geo:$at?q=$at")
}

/**
 * "Open in Google Maps": walking navigation in the Google Maps app (google.navigation:…&mode=w), or any maps app
 * through a geo: link when Google Maps isn't installed. Only an intent: no Maps Platform API is called.
 */
internal fun openNavigation(context: Context, latitude: Double, longitude: Double): Boolean {
    val uris = navigationUris(latitude, longitude)
    val intents = listOf(Intent(Intent.ACTION_VIEW, Uri.parse(uris[0])).setPackage("com.google.android.apps.maps"),
        Intent(Intent.ACTION_VIEW, Uri.parse(uris[1])))
    for (intent in intents) {
        try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true }
        catch (_: android.content.ActivityNotFoundException) { }
    }
    return false
}

/**
 * The Maps SDK can draw here: a key was built in (local.properties maitriMapsApiKey) and Google Play services is
 * present. Otherwise every map is a card with "Open in Google Maps" instead.
 */
internal fun googleMapsReady(context: Context): Boolean {
    val key = runCatching {
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData?.getString("com.google.android.geo.API_KEY")
    }.getOrNull()
    if (key.isNullOrBlank() || key.startsWith("\${")) return false
    return GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
}

/** What one map shows. Circles: [accuracyMeters] around "you" (or the person), [rangeMeters] / [areaMeters] around "you". */
internal data class MapContent(
    val markers: List<MapMarker>,
    val accuracyMeters: Double? = null,
    val rangeMeters: Double? = null,
    val alwaysShowAccuracy: Boolean = false,
    /** Screen 06: "somewhere within this distance of you". No pin for the person: their position isn't known yet. */
    val areaMeters: Double? = null,
) {
    /** The marker the circles belong to: you, else the person in need. */
    val anchor: MapMarker? get() = markers.firstOrNull { it.kind == MarkerKind.YOU } ?: markers.firstOrNull { it.kind != MarkerKind.RESPONDER }

    /** Everything the camera keeps in view: the markers, and the full circles. */
    fun bounds(): LatLngBounds? {
        if (markers.isEmpty()) return null
        val b = LatLngBounds.builder()
        markers.forEach { b.include(LatLng(it.latitude, it.longitude)) }
        val a = anchor
        if (a != null) listOfNotNull(rangeMeters, areaMeters).maxOrNull()?.let { r ->
            for (point in circleRing(a.latitude, a.longitude, r, 16)) b.include(LatLng(point.second, point.first))
        }
        return b.build()
    }
}

/** The live map inside MaitriMapCard: Google Maps when available, otherwise the "Open in Google Maps" card. */
@Composable
internal fun MaitriMap(content: MapContent, emptyLabel: String, modifier: Modifier = Modifier, interactive: Boolean = true,
                       expandable: Boolean = true) {
    val context = LocalContext.current
    val ready = remember(context) { googleMapsReady(context) }
    if (!ready) { MaitriMapFallback(content.markers, emptyLabel, modifier, content.accuracyMeters); return }
    var expanded by rememberSaveable { mutableStateOf(false) }
    MaitriGoogleMap(content, emptyLabel, modifier, interactive, onExpand = if (expandable) ({ expanded = true }) else null)
    if (expanded) Dialog(onDismissRequest = { expanded = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val p = maitriPalette()
        Box(Modifier.fillMaxSize().background(p.background)) {
            // Above the phone's navigation buttons: the Google logo (map content padding) and + / −.
            val bars = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            MaitriGoogleMap(content, emptyLabel, Modifier.fillMaxSize(), interactive = true, onExpand = null,
                controlsPadding = PaddingValues(bottom = bars + 24.dp, end = 12.dp), mapPadding = PaddingValues(bottom = bars))
            IconButton(onClick = { expanded = false },
                Modifier.statusBarsPadding().padding(16.dp).size(48.dp).shadow(4.dp, CircleShape).background(p.surface, CircleShape)) {
                Icon(Icons.Rounded.Close, tr("Close full-screen map"), tint = p.primary)
            }
        }
    }
}

/** No map on this phone (no key built in, or no Google Play services): where, in words, and a hand-off to Maps. */
@Composable
private fun MaitriMapFallback(markers: List<MapMarker>, emptyLabel: String, modifier: Modifier, accuracyMeters: Double?) {
    val p = maitriPalette()
    val context = LocalContext.current
    val target = markers.firstOrNull { it.kind == MarkerKind.PERSON_IN_NEED } ?: markers.firstOrNull()
    Box(modifier.background(p.card).padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (target == null) Text(emptyLabel.ifBlank { tr("Map unavailable") }, color = p.primary, fontSize = 13.sp)
            else {
                accuracyMeters?.let { Text(tr("Accurate to about {0} m", it.toInt()), color = p.secondary, fontSize = 12.sp) }
                TextButton(onClick = { openNavigation(context, target.latitude, target.longitude) }) {
                    Text(tr("Open in Google Maps"), color = p.accent, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(tr("The live map isn't available on this phone."), color = p.secondary, fontSize = 11.sp)
        }
    }
}

/** Appended to a bundled style (before its closing bracket) for small maps that show only a circle. */
private const val NO_LABELS = """, {"elementType": "labels", "stylers": [{"visibility": "off"}]}]"""

private val YOU_BLUE = Color(0xFF1A73E8)
private val RESPONDER_TEAL = Color(0xFF0E9F8E)

@Composable
private fun MaitriGoogleMap(content: MapContent, emptyLabel: String, modifier: Modifier, interactive: Boolean,
                            onExpand: (() -> Unit)?, controlsPadding: PaddingValues = PaddingValues(8.dp),
                            mapPadding: PaddingValues = PaddingValues(0.dp)) {
    val p = maitriPalette()
    val context = LocalContext.current
    val density = LocalDensity.current
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(22.5, 79.0), 3.5f) }
    var loaded by remember { mutableStateOf(false) }
    // Follow the markers until the user moves the map; "Re-centre" turns it back on.
    var follow by rememberSaveable { mutableStateOf(true) }
    val style = remember(p.dark, interactive) {
        runCatching {
            val json = context.resources.openRawResource(if (p.dark) R.raw.maitri_map_dark else R.raw.maitri_map_light)
                .bufferedReader().use { it.readText() }.trim()
            // A small, non-interactive map (screen 06) shows only its circle: street and place names would swamp it.
            MapStyleOptions(if (interactive) json else json.removeSuffix("]").trimEnd() + NO_LABELS)
        }.getOrNull()
    }
    LaunchedEffect(camera.isMoving) {
        if (camera.isMoving && camera.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE) follow = false
    }
    val bounds = content.bounds()
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // 48 dp of room around the markers, less on small maps (a padding bigger than the map makes the fit fail).
    val pad = minOf(with(density) { 48.dp.roundToPx() }, minOf(size.width, size.height) / 6)
    // Auto-fit: everything relevant in view, with room around it; never closer than street level.
    LaunchedEffect(loaded, follow, bounds, size) {
        if (!loaded || !follow || bounds == null || size == androidx.compose.ui.unit.IntSize.Zero) return@LaunchedEffect
        val single = content.markers.size == 1 && content.rangeMeters == null && content.areaMeters == null
        runCatching {
            if (single) camera.animate(CameraUpdateFactory.newLatLngZoom(bounds.center, 16f), 600)
            else {
                camera.animate(CameraUpdateFactory.newLatLngBounds(bounds, pad), 600)
                if (camera.position.zoom > 17f) camera.animate(CameraUpdateFactory.zoomTo(17f), 300)
            }
        }
    }
    Box(modifier.onSizeChanged { size = it }) {
        GoogleMap(
            modifier = Modifier.matchParentSize(),
            contentPadding = mapPadding,
            cameraPositionState = camera,
            properties = MapProperties(mapStyleOptions = style, isBuildingEnabled = false, minZoomPreference = 3f, maxZoomPreference = 20f),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = false, mapToolbarEnabled = false,
                compassEnabled = interactive, rotationGesturesEnabled = false, tiltGesturesEnabled = interactive,
                scrollGesturesEnabled = interactive, zoomGesturesEnabled = interactive, indoorLevelPickerEnabled = false,
                scrollGesturesEnabledDuringRotateOrZoom = interactive),
            onMapLoaded = { loaded = true },
            onMapClick = { onExpand?.invoke() },
        ) {
            val anchor = content.anchor
            val icons = rememberMarkerIcons(p.dark)
            // Circles first, markers on top.
            if (anchor != null) {
                content.areaMeters?.let { r ->
                    Circle(LatLng(anchor.latitude, anchor.longitude), radius = r, fillColor = p.accent.copy(alpha = 0.16f),
                        strokeColor = p.accent, strokeWidth = with(density) { 2.5.dp.toPx() },
                        strokePattern = listOf(Dash(24f), Gap(14f)))
                }
                content.rangeMeters?.let { r ->
                    Circle(LatLng(anchor.latitude, anchor.longitude), radius = r, fillColor = p.accent.copy(alpha = 0.05f),
                        strokeColor = p.accent.copy(alpha = 0.45f), strokeWidth = with(density) { 1.5.dp.toPx() },
                        strokePattern = listOf(Dash(20f), Gap(14f)))
                }
                val accuracy = content.accuracyMeters?.takeIf { it > 0 && (content.alwaysShowAccuracy || it > 25) }
                if (accuracy != null) {
                    val color = if (anchor.kind == MarkerKind.YOU) YOU_BLUE else p.accent
                    Circle(LatLng(anchor.latitude, anchor.longitude), radius = accuracy, fillColor = color.copy(alpha = 0.14f),
                        strokeColor = color.copy(alpha = 0.5f), strokeWidth = with(density) { 1.dp.toPx() })
                }
            }
            content.markers.forEachIndexed { index, marker ->
                key(marker.kind, index) { MovingMarker(marker, icons, title = markerTitle(marker.kind)) }
            }
        }
        if (content.markers.isEmpty() && emptyLabel.isNotBlank()) Text(emptyLabel, Modifier.align(Alignment.Center)
            .background(p.surface.copy(alpha = 0.92f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
            color = p.primary, fontSize = 12.sp)
        if (interactive && content.markers.isNotEmpty()) Column(Modifier.align(Alignment.BottomEnd).padding(controlsPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
            if (!follow) MapButton(p, Icons.Rounded.MyLocation, tr("Re-centre the map")) { follow = true }
            Column(Modifier.shadow(3.dp, RoundedCornerShape(14.dp)).background(p.surface, RoundedCornerShape(14.dp))) {
                IconButton(onClick = { follow = false; runCatching { camera.move(CameraUpdateFactory.zoomIn()) } }, Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.Add, tr("Zoom in"), tint = p.primary)
                }
                IconButton(onClick = { follow = false; runCatching { camera.move(CameraUpdateFactory.zoomOut()) } }, Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.Remove, tr("Zoom out"), tint = p.primary)
                }
            }
        }
    }
}

@Composable
private fun MapButton(p: MaitriPalette, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, Modifier.size(40.dp).shadow(3.dp, CircleShape).background(p.surface, CircleShape)) {
        Icon(icon, label, tint = p.accent)
    }
}

private fun markerTitle(kind: MarkerKind) = when (kind) {
    MarkerKind.PERSON_IN_NEED -> tr("Person in need")
    MarkerKind.YOU -> tr("You")
    MarkerKind.RESPONDER -> tr("Responder")
}

/** A marker that glides to each new position instead of jumping (about 0.8 s). */
@Composable
private fun MovingMarker(marker: MapMarker, icons: Map<MarkerKind, BitmapDescriptor>, title: String) {
    val target = LatLng(marker.latitude, marker.longitude)
    val state = remember { MarkerState(target) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target) {
        val from = state.position
        if (from == target) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(800)) {
            state.position = LatLng(from.latitude + (target.latitude - from.latitude) * value,
                from.longitude + (target.longitude - from.longitude) * value)
        }
    }
    Marker(state = state, icon = icons[marker.kind], title = title,
        anchor = if (marker.kind == MarkerKind.PERSON_IN_NEED) androidx.compose.ui.geometry.Offset(0.5f, 1f)
            else androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
        zIndex = when (marker.kind) { MarkerKind.PERSON_IN_NEED -> 3f; MarkerKind.RESPONDER -> 2f; MarkerKind.YOU -> 1f })
}

/** Maitri's markers: the pink pin artwork, and drawn dots for you (blue) and responders (teal). */
@Composable
private fun rememberMarkerIcons(dark: Boolean): Map<MarkerKind, BitmapDescriptor> {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    return remember(dark, density) {
        val pin = ContextCompat.getDrawable(context, if (dark) R.drawable.maitri_map_pin_dark else R.drawable.maitri_map_pin_light)
            ?.toBitmap()?.let { BitmapDescriptorFactory.fromBitmap(it) } ?: BitmapDescriptorFactory.defaultMarker(340f)
        mapOf(MarkerKind.PERSON_IN_NEED to pin,
            MarkerKind.YOU to BitmapDescriptorFactory.fromBitmap(dot(YOU_BLUE, 18f * density, 3f * density)),
            MarkerKind.RESPONDER to BitmapDescriptorFactory.fromBitmap(dot(RESPONDER_TEAL, 16f * density, 3f * density)))
    }
}

/** A filled circle with a white ring and a soft shadow, [size] px across. */
private fun dot(color: Color, size: Float, ring: Float): Bitmap {
    val edge = (size + ring * 2).roundToInt() + 4
    val bitmap = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val c = edge / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = 0x33000000
    canvas.drawCircle(c, c + 1f, size / 2 + ring + 1f, paint)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(c, c, size / 2 + ring, paint)
    paint.color = color.toArgb()
    canvas.drawCircle(c, c, size / 2, paint)
    return bitmap
}
