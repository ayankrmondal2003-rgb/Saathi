package `in`.maitri.app.network

import `in`.maitri.app.tr

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.*
import `in`.maitri.app.location.LocationUpdate
import `in`.maitri.app.location.LocalTrackingRepository
import `in`.maitri.app.location.TrackingSession
import com.google.firebase.auth.FirebaseAuth
import `in`.maitri.app.data.isMaitriVerified
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import retrofit2.HttpException
import java.time.Instant
import java.util.concurrent.TimeUnit

data class ExpectedAccount(val uid: String)
data class SyncStatus(val uid: String = "", val message: String = tr("Local demo"), val problem: SyncProblem? = null)

/** Result of checking the local SOS session against the server's open incident. */
sealed interface ReconcileOutcome {
    data object Unchanged : ReconcileOutcome
    data class Adopted(val startedAtMillis: Long) : ReconcileOutcome
    data object Cleared : ReconcileOutcome
}

private const val TAG = "MaitriSosSync"
internal enum class UploadResult { ACTIVE, RETRY, DONE }

internal enum class ReplayState { COMPLETE, RECHECK, SESSION_CHANGED }

internal suspend fun replayPendingFixes(
    fixes: List<LocationUpdate>,
    stillAuthorized: () -> Boolean,
    upload: suspend (LocationUpdate) -> Unit,
    acknowledge: suspend (Long) -> Unit
): ReplayState {
    for (fix in fixes) {
        if (!stillAuthorized()) return ReplayState.SESSION_CHANGED
        try {
            upload(fix)
        } catch (failure: HttpException) {
            if (failure.code() !in listOf(409, 422)) throw failure
            acknowledge(fix.timestampMillis)
            return ReplayState.RECHECK
        }
        acknowledge(fix.timestampMillis)
    }
    return ReplayState.COMPLETE
}

class TestIncidentSync(private val context: Context, private val session: () -> TrackingSession) {
    private val prefs = context.getSharedPreferences("test_incident_sync", Context.MODE_PRIVATE)
    private val queue = IncidentFixQueue(context)
    private val disk = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val serial = Mutex()
    private val mutable = MutableStateFlow(SyncStatus())
    val status = mutable.asStateFlow()
    private var worker: Job? = null
    private var owner: String? = null
    private var authorizedKey: String? = null
    private var lastCheck = 0L
    private var lastCleanupScheduled = -60000L

    init { scope.launch(disk) { queue.prune() } }

    fun getActiveIncidentId(uid: String): String? {
        val local = session()
        return if (local.owner == uid && local.id == prefs.getString("confirmed:$uid", null))
            prefs.getString("incident:$uid", null) else null
    }
    fun startedAt(uid: String): Long = prefs.getLong("started:$uid", System.currentTimeMillis())

    fun confirmedKey(uid: String): String? =
        prefs.getString("confirmed:$uid", null)?.takeIf { isShared(uid, it) }

    internal fun retriesExhausted(uid: String) {
        mutable.value = SyncStatus(uid, tr("Still can't reach Maitri. Tap Try again, or call 112 if you need help now."),
            SyncProblem.RETRYING)
    }

    /** "Try again" on Home: drop the backoff and sync this session now. */
    fun retryNow(uid: String) {
        worker?.cancel()
        worker = null
        lastCheck = 0
        wake(uid)
    }

    /** Hides an ENDED message once the user has seen it. */
    fun dismissProblem(uid: String) {
        if (mutable.value.uid == uid && mutable.value.problem == SyncProblem.ENDED) mutable.value = SyncStatus(uid, "")
    }

    /** Stops the session on this phone (service, queued fixes, local session) so Home never shows a dead SOS as open. */
    private fun endLocally(uid: String, message: String, problem: SyncProblem? = SyncProblem.ENDED) {
        clearActiveIncident(uid)
        LocalTrackingRepository.get(context).close()
        mutable.value = SyncStatus(uid, message, problem)
    }

    /**
     * Run at sign-in and app start. Adopts the server's open incident when this phone lost its link to it
     * (reinstall, cleared data, signed out and back in), and clears a local session the server has already ended.
     * Network failures change nothing.
     */
    suspend fun reconcile(uid: String): ReconcileOutcome {
        val outcome = withContext(Dispatchers.Main.immediate) {
            serial.withLock {
                user(uid) ?: return@withLock ReconcileOutcome.Unchanged
                val server = try {
                    val response = BackendClient.api.activeIncident(ExpectedAccount(uid))
                    when {
                        response.code() == 204 -> null
                        response.isSuccessful -> response.body() ?: return@withLock ReconcileOutcome.Unchanged
                        else -> {
                            Log.w(TAG, "Active incident lookup failed: HTTP ${response.code()}")
                            return@withLock ReconcileOutcome.Unchanged
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    Log.w(TAG, "Active incident lookup unavailable: ${failure.javaClass.simpleName}")
                    return@withLock ReconcileOutcome.Unchanged
                }
                when (decideReconcile(getActiveIncidentId(uid), server?.id, server?.status)) {
                    ReconcileAction.KEEP -> ReconcileOutcome.Unchanged
                    ReconcileAction.CLEAR_LOCAL -> {
                        Log.i(TAG, "Server has no open incident; clearing the local SOS session")
                        endLocally(uid, "", problem = null)
                        ReconcileOutcome.Cleared
                    }
                    ReconcileAction.ADOPT -> {
                        val open = requireNotNull(server)
                        Log.i(TAG, "Adopting open server incident ${open.id}")
                        ReconcileOutcome.Adopted(adopt(uid, open))
                    }
                }
            }
        }
        if (outcome is ReconcileOutcome.Adopted) wake(uid)
        return outcome
    }

    /** Links this phone's session (creating one if needed) to an open server incident. Caller holds [serial]. */
    private fun adopt(uid: String, open: ActiveIncidentDto): Long {
        val started = serverTimeMillis(open.startedAt)
        LocalTrackingRepository.get(context).begin(uid, started)
        val key = session().id
        prefs.edit().putStringSet(uid, keys(uid) + key).putLong("started:$uid", started)
            .putString("confirmed:$uid", key).putString("incident:$uid", open.id).apply()
        authorizedKey = key
        lastCheck = SystemClock.elapsedRealtime()
        mutable.value = SyncStatus(uid, tr("Reconnected to your open SOS."))
        return started
    }

    /**
     * Gets the phone's newest fix to a just-confirmed incident. A recent one goes through the offline queue; an
     * older last-known fix (allowed by the server as the first fix, up to 10 minutes) is sent once, best effort.
     */
    private suspend fun sendLatestKnown(uid: String, incident: String, account: ExpectedAccount) {
        val fix = session().latest ?: return
        if (System.currentTimeMillis() - fix.timestampMillis <= 120_000L) {
            withContext(disk) { queue.offer(uid, incident, fix) }
            return
        }
        try {
            logRejectedFix {
                BackendClient.api.submitIncidentFix(incident, FixDto(fix.latitude, fix.longitude,
                    Instant.ofEpochMilli(fix.timestampMillis).toString(), fix.isMock, fix.accuracyMeters.toDouble()), account)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Log.w(TAG, "Last-known fix not sent: ${failure.javaClass.simpleName}") }
    }

    /** POST /api/incidents; on 409 the server already has this user's incident, so it is looked up and adopted. */
    private suspend fun createOrAdopt(key: String, account: ExpectedAccount): IncidentDto? {
        try {
            return try {
                BackendClient.api.createTest(key, TestIncidentRequest(test = false), account)
            } catch (refused: HttpException) {
                // A server outside beta mode only takes test incidents; the SOS still goes out as one.
                val code = if (refused.code() == 403) parseIncidentConflict(
                    runCatching { refused.response()?.errorBody()?.string() }.getOrNull())?.code else null
                if (code != REAL_INCIDENTS_DISABLED) throw refused
                Log.i(TAG, "Server accepts test incidents only; sending this SOS as a test incident")
                BackendClient.api.createTest(key, TestIncidentRequest(test = true), account)
            }
        } catch (failure: HttpException) {
            if (failure.code() != 409) throw failure
            val conflict = parseIncidentConflict(runCatching { failure.response()?.errorBody()?.string() }.getOrNull())
            Log.i(TAG, "Incident create conflict ${conflict?.code ?: "unstructured"}; looking up the open incident")
        }
        val lookup = BackendClient.api.activeIncident(account)
        if (lookup.code() == 204) return null
        if (!lookup.isSuccessful) throw HttpException(lookup)
        return lookup.body()?.let { IncidentDto(it.id, it.status) }
    }

    fun canStart(uid: String, key: String) = !isShared(uid, key) ||
        (authorizedKey == key && getActiveIncidentId(uid) != null)
    fun isShared(uid: String, key: String) = key in keys(uid)
    private fun keys(uid: String) = prefs.getStringSet(uid, emptySet()).orEmpty().toSet()
    private fun user(uid: String) = runCatching { FirebaseAuth.getInstance().currentUser }
        .getOrNull()?.takeIf { it.uid == uid && it.isMaitriVerified() }
    private fun active(uid: String, key: String) = session().let { it.owner == uid && it.id == key } && isShared(uid, key)

    fun record(uid: String, key: String, fix: LocationUpdate) {
        val incident = getActiveIncidentId(uid) ?: return
        if (!active(uid, key) || user(uid) == null || authorizedKey != key) return
        scope.launch(disk) {
            if (active(uid, key)) {
                try {
                    queue.offer(uid, incident, fix)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastCleanupScheduled >= 60000L) {
                        val cleanup = OneTimeWorkRequestBuilder<PruneIncidentFixesWorker>()
                            .setInitialDelay(2, TimeUnit.MINUTES)
                            .setBackoffCriteria(BackoffPolicy.LINEAR, 2, TimeUnit.MINUTES).build()
                        WorkManager.getInstance(context).enqueueUniqueWork("prune-expired-incident-fixes", ExistingWorkPolicy.KEEP, cleanup)
                        lastCleanupScheduled = now
                    }
                }
                catch (_: Exception) { mutable.value = SyncStatus(uid, tr("Couldn't save your latest location on this phone.")) }
            }
        }
    }

    fun discardLocations(uid: String) {
        val incident = prefs.getString("incident:$uid", null) ?: return
        scope.launch(disk) { queue.clear(uid, incident) }
    }

    fun clearActiveIncident(uid: String? = null) {
        if (uid == null) return
        val incident = prefs.getString("incident:$uid", null)
        authorizedKey = null
        prefs.edit().remove(uid).remove("incident:$uid").remove("confirmed:$uid").remove("started:$uid").apply()
        if (incident != null) scope.launch(disk) { queue.clear(uid, incident) }
    }

    fun enable(uid: String, key: String) {
        prefs.edit().putStringSet(uid, keys(uid) + key).putLong("started:$uid", session().startedAt).apply()
        wake(uid)
    }

    fun wake(uid: String?) {
        if (uid != owner) {
            worker?.cancel()
            authorizedKey = null
            owner = uid
            mutable.value = SyncStatus()
        }
        if (uid == null || !active(uid, session().id) || worker?.isActive == true) return
        val key = session().id
        worker = scope.launch {
            var quickRetries = 0
            while (active(uid, key)) {
                when (recover(uid, key, force = false)) {
                    UploadResult.DONE -> return@launch
                    // Not yet on the server (e.g. it was asleep): try again in 5 s for the first two minutes,
                    // instead of WorkManager's doubling waits, so an SOS is never held back for minutes.
                    UploadResult.RETRY -> if (getActiveIncidentId(uid) == null && quickRetries < 24) {
                        quickRetries++
                        delay(5000)
                    } else {
                        val work = OneTimeWorkRequestBuilder<IncidentUploadWorker>()
                            .setInputData(workDataOf("uid" to uid, "session" to key))
                            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
                        WorkManager.getInstance(context).enqueueUniqueWork("incident-upload-$key", ExistingWorkPolicy.KEEP, work)
                        return@launch
                    }
                    UploadResult.ACTIVE -> delay(5000)
                }
            }
        }
    }

    internal suspend fun recover(uid: String, key: String, force: Boolean): UploadResult = withContext(Dispatchers.Main.immediate) {
        serial.withLock {
            val signedIn = user(uid) ?: return@withLock UploadResult.DONE
            if (!active(uid, key)) return@withLock UploadResult.DONE
            try {
                try { drain(uid, key, force) }
                catch (error: HttpException) {
                    if (error.code() != 401) throw error
                    signedIn.getIdToken(true).await()
                    drain(uid, key, true)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val code = (failure as? HttpException)?.code()
                val detail = errorDetail(failure)
                val errorCode = parseIncidentConflict(detail)?.code
                if (code != null && isFatalSync(code, errorCode)) {
                    Log.w(TAG, "SOS sync refused: HTTP $code $detail")
                    endLocally(uid, fatalSyncMessage(code, errorCode))
                    UploadResult.DONE
                } else {
                    Log.w(TAG, "SOS sync interrupted: ${code?.let { "HTTP $it" } ?: failure.javaClass.simpleName}")
                    lastCheck = 0
                    mutable.value = SyncStatus(uid, tr("Connecting to Maitri… Your SOS keeps retrying in the background."), SyncProblem.RETRYING)
                    UploadResult.RETRY
                }
            }
        }
    }

    private suspend fun drain(uid: String, key: String, force: Boolean): UploadResult {
        val account = ExpectedAccount(uid)
        var incident = getActiveIncidentId(uid)
        if (force || incident == null || authorizedKey != key || lastCheck == 0L || SystemClock.elapsedRealtime() - lastCheck > 30000L) {
            val response = if (incident == null) {
                createOrAdopt(key, account) ?: run {
                    if (active(uid, key)) endLocally(uid, tr("That SOS session has already ended. Hold SOS again to start a new one."))
                    return UploadResult.DONE
                }
            } else {
                val current = BackendClient.api.incidentLocation(incident, account)
                IncidentDto(incident, current.status)
            }
            if (!active(uid, key) || user(uid) == null) return UploadResult.DONE
            if (response.status != "ACTIVE") {
                Log.i(TAG, "Server incident is ${response.status}; ending the local session")
                endLocally(uid, if (response.status == "CLOSED") tr("Your SOS has ended.")
                    else tr("Your earlier SOS is still open and needs attention. Call 112 if you need help now."))
                return UploadResult.DONE
            }
            val newlyConfirmed = authorizedKey != key
            prefs.edit().putString("confirmed:$uid", key).putString("incident:$uid", response.id).apply()
            authorizedKey = key
            incident = response.id
            lastCheck = SystemClock.elapsedRealtime()
            // Fixes that arrived before the server confirmed the incident were not queued; send the newest one now.
            if (newlyConfirmed) sendLatestKnown(uid, response.id, account)
        }
        val id = requireNotNull(incident)
        val pending = withContext(disk) { queue.pending(uid, id) }
        val result = replayPendingFixes(pending,
            stillAuthorized = { active(uid, key) && user(uid) != null },
            upload = { fix ->
                logRejectedFix {
                    BackendClient.api.submitIncidentFix(id, FixDto(fix.latitude, fix.longitude,
                        Instant.ofEpochMilli(fix.timestampMillis).toString(), fix.isMock, fix.accuracyMeters.toDouble()), account)
                }
            },
            acknowledge = { timestamp -> withContext(disk) { queue.acknowledge(uid, id, timestamp) } }
        )
        if (result == ReplayState.SESSION_CHANGED) return UploadResult.DONE
        if (result == ReplayState.RECHECK) lastCheck = 0
        mutable.value = SyncStatus(uid, when {
            result == ReplayState.RECHECK -> tr("Checking your SOS session…")
            pending.isNotEmpty() -> tr("Connected · your location is shared")
            else -> tr("Connected · waiting for your location")
        })
        return UploadResult.ACTIVE
    }
}

/** Logs why the server refused a fix (its reason code) and rethrows, so replay still acknowledges and moves on. */
private suspend fun <T> logRejectedFix(block: suspend () -> T): T = try { block() } catch (failure: HttpException) {
    if (failure.code() == 409 || failure.code() == 422) {
        val code = parseIncidentConflict(runCatching { failure.response()?.errorBody()?.string() }.getOrNull())?.code
        Log.w(TAG, "Server refused fix: HTTP ${failure.code()} ${code ?: "unstructured"}")
    }
    throw failure
}

private fun errorDetail(failure: Exception): String =
    (failure as? HttpException)?.let { runCatching { it.response()?.errorBody()?.string() }.getOrNull() }?.take(300).orEmpty()

class IncidentUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val uid = inputData.getString("uid") ?: return Result.failure()
        val key = inputData.getString("session") ?: return Result.failure()
        val sync = LocalTrackingRepository.get(applicationContext).sync
        return when (sync.recover(uid, key, true)) {
            UploadResult.DONE -> Result.success()
            // Exponential from 10 s: ten attempts cover about 2.8 hours, far beyond a cold start.
            UploadResult.RETRY -> if (runAttemptCount < 10) Result.retry() else {
                sync.retriesExhausted(uid)
                Result.failure()
            }
            UploadResult.ACTIVE -> {
                withContext(Dispatchers.Main.immediate) {
                    if (LocalTrackingRepository.get(applicationContext).session.value.running) sync.wake(uid)
                }
                Result.success()
            }
        }
    }
}
