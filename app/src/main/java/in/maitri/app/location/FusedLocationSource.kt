package `in`.maitri.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.provider.Settings
import androidx.core.location.LocationCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource

fun Context.granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
fun Context.locationAllowed() = granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION)
fun Context.preciseLocationAllowed() = granted(Manifest.permission.ACCESS_FINE_LOCATION)
fun Context.locationEnabled() = LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java))
fun Context.airplaneModeOn() = Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0

/**
 * Fused location for one subscriber. In [FixMode.SOS] the first available fix is delivered immediately
 * (last known, then a high-accuracy current fix, then a balanced one), and every newer fix follows, so the
 * position keeps improving. Accuracy is always reported; nothing is dropped for being imprecise.
 */
class FusedLocationSource(private val context: Context, private val mode: FixMode = FixMode.PRECISE) : LocationSource {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private var callback: LocationCallback? = null
    private val cancellation = CancellationTokenSource()
    private var lastDeliveredTimestamp: Long = 0L

    @SuppressLint("MissingPermission")
    override fun subscribe(fix: (LocationUpdate) -> Unit, status: (TrackingState) -> Unit) {
        if (!context.locationAllowed()) {
            status(TrackingState.PERMISSION_REQUIRED)
            return
        }
        if (!context.locationEnabled()) {
            status(TrackingState.LOCATION_UNAVAILABLE)
            return
        }

        status(TrackingState.STARTING)
        var received = false

        fun deliver(location: Location?) {
            if (location == null) return
            val accuracy = FixPolicy.accuracyOf(location.hasAccuracy(), location.accuracy)
            if (!FixPolicy.shouldDeliver(mode, location.time, accuracy, lastDeliveredTimestamp, System.currentTimeMillis())) return
            lastDeliveredTimestamp = location.time
            received = true
            status(TrackingState.TRACKING)
            fix(
                LocationUpdate(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = accuracy,
                    timestampMillis = location.time,
                    speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() },
                    isMock = LocationCompat.isMock(location)
                )
            )
        }

        fun failed(e: Exception) = status(
            if (e is SecurityException || !context.locationAllowed())
                TrackingState.PERMISSION_REQUIRED
            else
                TrackingState.ERROR
        )

        fun noFixYet() {
            if (!received) status(if (context.locationEnabled()) TrackingState.STARTING else TrackingState.LOCATION_UNAVAILABLE)
        }

        val listener = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    deliver(loc)
                }
            }

            override fun onLocationAvailability(value: LocationAvailability) {
                if (value.isLocationAvailable) {
                    status(TrackingState.TRACKING)
                } else if (!received || mode == FixMode.PRECISE) {
                    // During an SOS a fix already shown stays valid; only report "unavailable" before the first one.
                    status(TrackingState.LOCATION_UNAVAILABLE)
                }
            }
        }
        callback = listener

        // An SOS keeps refreshing even when the phone is not moving, so the server's fix never goes stale.
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, TrackingConfig.INTERVAL_MS)
            .setMinUpdateIntervalMillis(TrackingConfig.MIN_INTERVAL_MS)
            .setMinUpdateDistanceMeters(if (mode == FixMode.SOS) 0f else TrackingConfig.DISTANCE_METRES)
            .setMaxUpdateAgeMillis(0)
            .build()

        fun current(priority: Int, then: (() -> Unit)?) {
            val current = CurrentLocationRequest.Builder()
                .setPriority(priority)
                .setMaxUpdateAgeMillis(60000)
                .build()
            client.getCurrentLocation(current, cancellation.token).addOnSuccessListener { location ->
                if (location != null) deliver(location) else if (then != null) then() else noFixYet()
            }.addOnFailureListener { if (then != null) then() else noFixYet() }
        }

        try {
            client.requestLocationUpdates(request, listener, Looper.getMainLooper()).addOnFailureListener(::failed)
            if (mode == FixMode.SOS) {
                // 1. Whatever the phone already knows (up to 10 min old), so the map has a position at once.
                client.lastLocation.addOnSuccessListener(::deliver)
                // 2. A current high-accuracy fix, 3. falling back to wifi/cell when GPS has nothing (indoors).
                current(Priority.PRIORITY_HIGH_ACCURACY) { current(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null) }
            } else {
                current(Priority.PRIORITY_HIGH_ACCURACY, null)
            }
        } catch (e: RuntimeException) {
            failed(e)
        }
    }

    override fun cancel() {
        cancellation.cancel()
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
        lastDeliveredTimestamp = 0L
    }
}
