package `in`.maitri.app.location

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
                 accuracyMeters: Float?, serverConfirmed: Boolean, waitedMs: Long): LocationHint = when {
    !allowed -> LocationHint.PERMISSION_MISSING
    !locationOn -> LocationHint.LOCATION_OFF
    airplaneMode && !serverConfirmed -> LocationHint.AIRPLANE_MODE
    !precise && (accuracyMeters == null || accuracyMeters > TrackingConfig.PRECISE_METRES) -> LocationHint.PRECISE_NOT_ALLOWED
    accuracyMeters == null -> if (waitedMs < SEARCH_GRACE_MS) LocationHint.SEARCHING else LocationHint.WEAK_SIGNAL
    accuracyMeters > TrackingConfig.PRECISE_METRES -> LocationHint.WEAK_SIGNAL
    else -> LocationHint.NONE
}

fun locationHintText(hint: LocationHint): String? = when (hint) {
    LocationHint.NONE -> null
    LocationHint.SEARCHING -> "Finding your location…"
    LocationHint.PERMISSION_MISSING -> "Location not allowed"
    LocationHint.LOCATION_OFF -> "Location is off"
    LocationHint.AIRPLANE_MODE -> "Airplane mode is on"
    LocationHint.PRECISE_NOT_ALLOWED -> "Precise location not allowed"
    LocationHint.WEAK_SIGNAL -> "Weak GPS signal — move near a window"
}

/** "±12 m" when precise; "Approx. ±650 m" / "Approx. ±1.2 km" when not. */
fun accuracyLabel(meters: Double): String = when {
    meters <= TrackingConfig.PRECISE_METRES -> "±${meters.roundToInt()} m"
    meters < 1000 -> "Approx. ±${(meters / 10).roundToInt() * 10} m"
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
 * The newest of the server-confirmed fix and the phone's own fix. The local one is shown at once ("Sending…")
 * and is "Location shared" once the server holds a fix at least as new.
 */
fun shownLocation(serverLat: Double?, serverLon: Double?, serverAccuracy: Double?, serverTimeMillis: Long?,
                  local: LocationUpdate?): ShownFix? {
    val server = if (serverLat != null && serverLon != null && serverTimeMillis != null)
        ShownFix(serverLat, serverLon, serverAccuracy ?: TrackingConfig.MAX_SOS_ACCURACY_METRES.toDouble(), serverTimeMillis, true)
    else null
    val phone = local?.let { ShownFix(it.latitude, it.longitude, it.accuracyMeters.toDouble(), it.timestampMillis, false) }
    return when {
        server == null -> phone
        // Server timestamps have second precision at worst; treat the same fix as shared.
        phone == null || server.timeMillis >= phone.timeMillis - 1000 -> server
        else -> phone
    }
}
