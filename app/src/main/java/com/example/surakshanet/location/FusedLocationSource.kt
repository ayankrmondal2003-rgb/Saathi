package com.example.surakshanet.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.location.LocationCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource

fun Context.granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
fun Context.locationAllowed() = granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION)
fun Context.locationEnabled() = LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java))

class FusedLocationSource(private val context: Context) : LocationSource {
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
            if (location == null || !location.hasAccuracy()) return
            // Ignore low accuracy fixes (> 100 meters)
            if (location.accuracy > 100f) return
            // Monotonic filter: ignore duplicate, cached or out-of-order timestamps
            if (location.time <= lastDeliveredTimestamp) return
            lastDeliveredTimestamp = location.time
            received = true
            status(TrackingState.TRACKING)
            fix(
                LocationUpdate(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracy,
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

        val listener = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    deliver(loc)
                }
            }

            override fun onLocationAvailability(value: LocationAvailability) {
                if (value.isLocationAvailable) {
                    status(TrackingState.TRACKING)
                } else {
                    status(TrackingState.LOCATION_UNAVAILABLE)
                }
            }
        }
        callback = listener

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, TrackingConfig.INTERVAL_MS)
            .setMinUpdateIntervalMillis(TrackingConfig.MIN_INTERVAL_MS)
            .setMinUpdateDistanceMeters(TrackingConfig.DISTANCE_METRES)
            .setMaxUpdateAgeMillis(0)
            .build()

        try {
            client.requestLocationUpdates(request, listener, Looper.getMainLooper()).addOnFailureListener(::failed)
            val current = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateAgeMillis(60000)
                .build()
            client.getCurrentLocation(current, cancellation.token).addOnSuccessListener { location ->
                if (location != null) {
                    deliver(location)
                } else if (!received) {
                    if (!context.locationEnabled()) {
                        status(TrackingState.LOCATION_UNAVAILABLE)
                    } else {
                        status(TrackingState.STARTING)
                    }
                }
            }.addOnFailureListener {
                if (!received && !context.locationEnabled()) {
                    status(TrackingState.LOCATION_UNAVAILABLE)
                }
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
