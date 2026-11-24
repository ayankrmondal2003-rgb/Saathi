package `in`.maitri.app.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Keeps an available volunteer reachable. The server invites volunteers whose last reported location is under 30
 * minutes old, so the location is reported:
 *  - whenever Maitri comes to the foreground (any screen),
 *  - every ~15 minutes in the background, only if the volunteer allowed location "all the time",
 *  - right before accepting a request (accepting needs a fix under 2 minutes old).
 * Nothing is reported unless the volunteer is opted in and "Available" is on.
 */
object ResponderLocation {
    private const val PREFS = "responder_location"
    private const val PERIODIC = "responder-location-periodic"
    private const val NOW = "responder-location-now"

    fun foregroundAllowed(context: Context) = granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
        granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun backgroundAllowed(context: Context) = Build.VERSION.SDK_INT < 29 ||
        granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun isAvailable(context: Context, uid: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("$uid.available", false)

    /** Called whenever the server's availability for this account is known or changes. */
    fun setAvailable(context: Context, uid: String, available: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("$uid.available", available).apply()
        schedule(context, uid)
    }

    /** Periodic background reports only while available and allowed in the background; cancelled otherwise. */
    fun schedule(context: Context, uid: String) {
        val work = WorkManager.getInstance(context)
        if (isAvailable(context, uid) && foregroundAllowed(context) && backgroundAllowed(context)) {
            work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ResponderLocationWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build())
        } else work.cancelUniqueWork(PERIODIC)
    }

    /** Maitri came to the foreground: report once (in a worker, so it finishes even if the screen changes). */
    fun onForeground(context: Context) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (!isAvailable(context, uid) || !foregroundAllowed(context)) return
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ResponderLocationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build())
        schedule(context, uid)
    }

    /** One fresh fix, sent to the server. True when the server has a location under 2 minutes old for us. */
    @SuppressLint("MissingPermission")
    suspend fun reportNow(context: Context, uid: String): Boolean {
        if (!foregroundAllowed(context)) return false
        return try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val fix: Location = withTimeoutOrNull(15_000) {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await()
            } ?: return false
            if (!fix.hasAccuracy() || fix.accuracy > 100f) return false
            val mock = if (Build.VERSION.SDK_INT >= 31) fix.isMock else @Suppress("DEPRECATION") fix.isFromMockProvider
            BackendClient.api.submitResponderFix(FixDto(fix.latitude, fix.longitude,
                Instant.ofEpochMilli(minOf(fix.time, System.currentTimeMillis())).toString(), mock,
                fix.accuracy.toDouble()), ExpectedAccount(uid))
            true
        } catch (e: CancellationException) { throw e }
        catch (e: HttpException) { e.code() == 409 }   // 409: the server already has this fix or a newer one
        catch (_: Exception) { false }
    }
}

class ResponderLocationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return Result.success()
        if (!ResponderLocation.isAvailable(applicationContext, uid)) {
            ResponderLocation.schedule(applicationContext, uid)   // cancels the periodic report
            return Result.success()
        }
        ResponderLocation.reportNow(applicationContext, uid)
        return Result.success()
    }
}
