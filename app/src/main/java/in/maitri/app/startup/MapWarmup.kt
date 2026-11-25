package `in`.maitri.app.startup

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.google.android.gms.location.LocationServices
import `in`.maitri.app.location.locationAllowed
import `in`.maitri.app.ui.OpenFreeMap
import `in`.maitri.app.ui.liveMapSupported
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.coroutines.resume

/**
 * Warms MapLibre's cache with the map style and the vector tiles around the last known location (about 3 km x 3 km,
 * zoom 12-15, roughly 40 tiles), at most once a day. The Responder and SOS maps then draw at once, also offline.
 * Uses only the location the phone already has; never asks for a new fix and never sends it anywhere.
 */
object MapWarmup {
    private const val TAG = "MaitriStartup"
    private const val PREFS = "map_warmup"
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private val NAME = "maitri-warmup".toByteArray()

    @SuppressLint("MissingPermission") // Checked with locationAllowed() first.
    suspend fun run(context: Context) {
        if (!liveMapSupported(context) || !context.locationAllowed()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("last", 0) < DAY_MS) return
        val location = withTimeoutOrNull(3_000) {
            runCatching { LocationServices.getFusedLocationProviderClient(context).lastLocation.await() }.getOrNull()
        } ?: return
        val d = 0.015  // about 1.6 km each way
        val bounds = LatLngBounds.from(location.latitude + d, location.longitude + d, location.latitude - d, location.longitude - d)
        val ok = withContext(Dispatchers.Main) {
            withTimeoutOrNull(90_000) { download(context, bounds) } ?: false
        }
        if (ok) prefs.edit().putLong("last", System.currentTimeMillis()).apply()
    }

    private suspend fun download(context: Context, bounds: LatLngBounds): Boolean {
        MapLibre.getInstance(context)
        val manager = OfflineManager.getInstance(context)
        deleteOld(manager)
        val definition = OfflineTilePyramidRegionDefinition(OpenFreeMap.LIGHT_STYLE, bounds, 12.0, 15.0,
            context.resources.displayMetrics.density)
        val region = suspendCancellableCoroutine<OfflineRegion?> { cont ->
            manager.createOfflineRegion(definition, NAME, object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) { cont.resume(offlineRegion) }
                override fun onError(error: String) { Log.w(TAG, "map warm-up: $error"); cont.resume(null) }
            })
        } ?: return false
        return suspendCancellableCoroutine { cont ->
            region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                override fun onStatusChanged(status: OfflineRegionStatus) {
                    if (status.isComplete && cont.isActive) {
                        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        cont.resume(true)
                    }
                }
                override fun onError(error: OfflineRegionError) { Log.w(TAG, "map warm-up: ${error.reason}") }
                override fun mapboxTileCountLimitExceeded(limit: Long) {
                    if (cont.isActive) { region.setDownloadState(OfflineRegion.STATE_INACTIVE); cont.resume(false) }
                }
            })
            region.setDownloadState(OfflineRegion.STATE_ACTIVE)
            cont.invokeOnCancellation { region.setDownloadState(OfflineRegion.STATE_INACTIVE) }
        }
    }

    /** Keeps one warm-up region: yesterday's area is removed before today's is fetched. */
    private suspend fun deleteOld(manager: OfflineManager) {
        val regions = suspendCancellableCoroutine<Array<OfflineRegion>> { cont ->
            manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) { cont.resume(offlineRegions ?: emptyArray()) }
                override fun onError(error: String) { cont.resume(emptyArray()) }
            })
        }
        regions.filter { it.metadata.contentEquals(NAME) }.forEach { old ->
            suspendCancellableCoroutine<Unit> { cont ->
                old.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() { cont.resume(Unit) }
                    override fun onError(error: String) { cont.resume(Unit) }
                })
            }
        }
    }
}
