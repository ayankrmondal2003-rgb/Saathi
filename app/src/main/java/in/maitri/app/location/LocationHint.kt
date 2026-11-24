package `in`.maitri.app.location

import `in`.maitri.app.tr

import java.util.Locale
import kotlin.math.roundToInt

/** Why screen 08 has no good position, in the order the user should fix it. */
enum class LocationHint {
    NONE,
    /** First seconds of an SOS: no fix yet, nothing wrong. */
    SEARCHING,
    PERMISSION_MISSING,
    LOCATION_OFF,
    /** GPS may still work, but nothing can be sent until the phone is online. */
    AIRPLANE_MODE,
    /** "Approximate" only: positions are 1-3 km wide. */
    PRECISE_NOT_ALLOWED,
    WEAK_SIGNAL
}

/** How long to show "Finding your location…" before calling it a weak signal. */
const val SEARCH_GRACE_MS = 15_000L

fun locationHint(airplaneMode: Boolean, locationOn: Boolean, allowed: Boolean, precise: Boolean,
                 accuracyMeters: Float?, serverConfirmed: Boolean, waitedMs: Long, online: Boolean = true): LocationHint = when {
    !allowed -> LocationHint.PERMISSION_MISSING
    !locationOn -> LocationHint.LOCATION_OFF
    // Airplane mode only matters when it actually cut the connection (Wi-Fi can stay on in airplane mode).
    airplaneMode && !online && !serverConfirmed -> LocationHint.AIRPLANE_MODE
    // Always said while precise is off: an older precise fix on screen doesn't mean new ones can be precise.
    !precise -> LocationHint.PRECISE_NOT_ALLOWED
    accuracyMeters == null -> if (waitedMs < SEARCH_GRACE_MS) LocationHint.SEARCHING else LocationHint.WEAK_SIGNAL
    accuracyMeters > TrackingConfig.PRECISE_METRES -> LocationHint.WEAK_SIGNAL
    else -> LocationHint.NONE
}

fun locationHintText(hint: LocationHint): String? = when (hint) {
    LocationHint.NONE -> null
    LocationHint.SEARCHING -> tr("Finding your location…")
    LocationHint.PERMISSION_MISSING -> tr("Location not allowed")
    LocationHint.LOCATION_OFF -> tr("Location is off")
    LocationHint.AIRPLANE_MODE -> tr("Airplane mode is on")
    LocationHint.PRECISE_NOT_ALLOWED -> tr("Precise location not allowed")
    LocationHint.WEAK_SIGNAL -> tr("Weak GPS signal — move near a window")
}

/** "±12 m" when precise; "Approx. ±650 m" / "Approx. ±1.2 km" when not. */
fun accuracyLabel(meters: Double): String = when {
    meters <= TrackingConfig.PRECISE_METRES -> "±${meters.roundToInt()} m"
    meters < 1000 -> tr("Approx. ±{0} m", (meters / 10).roundToInt() * 10)
    else -> String.format(Locale.US, "Approx. ±%.1f km", meters / 1000)
}

/** Map zoom that keeps the accuracy circle on screen: street level when precise, wider as accuracy drops. */
fun zoomForAccuracy(meters: Double?): Double {
    if (meters == null || meters <= TrackingConfig.PRECISE_METRES) return 16.0
    val zoom = 16.0 - kotlin.math.log2(meters / TrackingConfig.PRECISE_METRES) - 0.5
    return zoom.coerceIn(10.0, 16.0)
}

/** The position screen 08 draws, and whether the server has confirmed it. */
data class ShownFix(val latitude: Double, val longitude: Double, val accuracyMeters: Double,
                    val timeMillis: Long, val shared: Boolean)

/**
 * Fixes arrive every few seconds and the server is polled every few seconds, so the server's copy always trails
 * the phone a little. Within this window the location counts as shared.
 */
const val SHARED_LAG_MS = 30_000L

/**
 * What screen 08 draws: always the newest position (usually the phone's own), labelled "Location shared" when
 * the server holds a fix from the last [SHARED_LAG_MS] of it, otherwise "Sending…".
 */
fun shownLocation(serverLat: Double?, serverLon: Double?, serverAccuracy: Double?, serverTimeMillis: Long?,
                  local: LocationUpdate?): ShownFix? {
    val server = if (serverLat != null && serverLon != null && serverTimeMillis != null)
        ShownFix(serverLat, serverLon, serverAccuracy ?: TrackingConfig.MAX_SOS_ACCURACY_METRES.toDouble(), serverTimeMillis, true)
    else null
    val phone = local?.let { ShownFix(it.latitude, it.longitude, it.accuracyMeters.toDouble(), it.timestampMillis, false) }
    return when {
        server == null -> phone
        phone == null || server.timeMillis >= phone.timeMillis -> server
        else -> phone.copy(shared = server.timeMillis >= phone.timeMillis - SHARED_LAG_MS)
    }
}
