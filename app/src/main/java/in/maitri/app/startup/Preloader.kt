package `in`.maitri.app.startup

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import `in`.maitri.app.BuildConfig
import `in`.maitri.app.CrashReports
import `in`.maitri.app.network.BackendClient
import `in`.maitri.app.network.ExpectedAccount
import `in`.maitri.app.data.isMaitriVerified
import `in`.maitri.app.notification.DeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Work started on every cold start, all in parallel while "Getting things ready" shows:
 * a) Firebase auth restore + ID-token refresh, b) wake the server (/ready, so a sleeping Render instance starts now),
 * c) the account's open SOS, d) profile / volunteer / unread / PIN / circle into [StartupCache], e) FCM registration,
 * f) map style and tiles around the last location, g) crash-report settings off the main thread.
 * Nothing here blocks the app: the startup screen waits at most 2.5 s, and everything else keeps going behind it.
 */
object Preloader {
    private const val TAG = "MaitriStartup"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    private val essentials = MutableStateFlow(false)
    /** True once the startup screen has what it needs: no account, or the open-SOS check answered (or failed). */
    val essentialsReady = essentials.asStateFlow()

    private val openActive = AtomicBoolean(false)
    /** True once, when the server reported an open SOS at start-up: Home then goes straight to screen 08. */
    fun consumeOpenActiveSos(): Boolean = openActive.getAndSet(false)

    /** A session this phone already knew was open (restored locally) also goes straight to screen 08. */
    fun requestOpenActiveSos() = openActive.set(true)

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        val t0 = android.os.SystemClock.elapsedRealtime()
        fun done(what: String) = Log.i(TAG, "$what +${android.os.SystemClock.elapsedRealtime() - t0} ms")

        scope.launch { StartupCache.load(app); done("cache loaded") }                        // disk -> memory first
        scope.launch(Dispatchers.Default) { `in`.maitri.app.I18n.preload(); done("translations parsed") }
        scope.launch(Dispatchers.Default) { CrashReports.apply(app); done("crash reports") }  // g
        scope.launch { wakeServer(); done("server awake") }                                   // b
        scope.launch { MapWarmup.run(app); done("map warmed") }                               // f
        scope.launch {
            val user = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()?.takeIf { it.isMaitriVerified() }
            if (user == null || BuildConfig.API_BASE_URL.isBlank()) { essentials.value = true; done("no account"); return@launch }
            val uid = user.uid
            val account = ExpectedAccount(uid)
            coroutineScope {
                launch { withTimeoutOrNull(10_000) { runCatching { user.getIdToken(true).await() } }; done("token refreshed") } // a
                launch { checkActive(account); essentials.value = true; done("open SOS checked") }                              // c
                launch { warmCache(uid, account); done("cache refreshed") }                                                      // d
                launch { runCatching { DeviceRepository.get(app).registerCurrentToken() }; done("FCM registered") }               // e
            }
        }
    }

    private suspend fun wakeServer() = withContext(Dispatchers.IO) {
        val base = BuildConfig.API_BASE_URL.trimEnd('/')
        if (base.isBlank()) return@withContext
        runCatching { BackendClient.rawOkHttpClient.newCall(Request.Builder().url("$base/ready").build()).execute().close() }
    }

    private suspend fun checkActive(account: ExpectedAccount) {
        val open = runCatching { BackendClient.api.activeIncident(account).code() == 200 }.getOrNull()
        if (open == true) openActive.set(true)
    }

    /** Each read on its own, so one slow or failing endpoint doesn't hold the others. */
    private suspend fun warmCache(uid: String, account: ExpectedAccount) = coroutineScope {
        launch { runCatching { StartupCache.put(uid, StartupCache.PROFILE, BackendClient.api.profile(account)) } }
        launch { runCatching { StartupCache.put(uid, StartupCache.VOLUNTEER, BackendClient.api.volunteerStatus(account)) } }
        launch { runCatching { StartupCache.put(uid, StartupCache.UNREAD, BackendClient.circle.inbox(account).unreadCount) } }
        launch { runCatching { StartupCache.put(uid, StartupCache.PIN, BackendClient.api.safetyPinStatus(account)) } }
        launch { runCatching { StartupCache.put(uid, StartupCache.LINKS, BackendClient.api.circleLinks(account)) } }
        launch { runCatching { StartupCache.put(uid, StartupCache.MEMBERSHIPS, BackendClient.circle.memberships(account)) } }
    }
}

/** Once per process: the startup screen shows on every cold start, not when the activity is recreated. */
object StartupGate {
    @Volatile var done = false
    @Volatile private var reported = false

    /** Marks "fully drawn" for startup timing (Macrobenchmark / am start), the first time Home appears. */
    fun reportFullyDrawn(activity: android.app.Activity?) {
        if (reported || activity == null) return
        reported = true
        activity.reportFullyDrawn()
    }
}
