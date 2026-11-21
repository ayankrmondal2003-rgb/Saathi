package com.example.surakshanet.network

import android.content.Context
import android.os.SystemClock
import androidx.work.*
import com.example.surakshanet.location.LocationUpdate
import com.example.surakshanet.location.LocalTrackingRepository
import com.example.surakshanet.location.TrackingSession
import com.google.firebase.auth.FirebaseAuth
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
data class SyncStatus(val uid: String = "", val message: String = "Local demo")
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
        mutable.value = SyncStatus(uid, "Sync paused after repeated failures · reopen to retry")
    }

    fun canStart(uid: String, key: String) = !isShared(uid, key) ||
        (authorizedKey == key && getActiveIncidentId(uid) != null)
    fun isShared(uid: String, key: String) = key in keys(uid)
    private fun keys(uid: String) = prefs.getStringSet(uid, emptySet()).orEmpty().toSet()
    private fun user(uid: String) = runCatching { FirebaseAuth.getInstance().currentUser }
        .getOrNull()?.takeIf { it.uid == uid && it.isEmailVerified }
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
                catch (_: Exception) { mutable.value = SyncStatus(uid, "Could not save pending fix") }
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
            while (active(uid, key)) {
                when (recover(uid, key, force = false)) {
                    UploadResult.DONE -> return@launch
                    UploadResult.RETRY -> {
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
                if (code != null && code in 400..499 && code !in listOf(408, 429)) {
                    authorizedKey = null
                    context.stopService(android.content.Intent(context, com.example.surakshanet.location.SosLocationService::class.java))
                    mutable.value = SyncStatus(uid, "Server refused synchronization ($code). Reopen after checking access.")
                    UploadResult.DONE
                } else {
                    lastCheck = 0
                    mutable.value = SyncStatus(uid, "Sync interrupted · waiting for network retry")
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
                BackendClient.api.createTest(key, TestIncidentRequest(), account)
            } else {
                val current = BackendClient.api.incidentLocation(incident, account)
                IncidentDto(incident, current.status)
            }
            if (!active(uid, key) || user(uid) == null) return UploadResult.DONE
            if (response.status != "ACTIVE") {
                authorizedKey = null
                if (response.status == "CLOSED") {
                    clearActiveIncident(uid)
                    LocalTrackingRepository.get(context).close()
                }
                mutable.value = SyncStatus(uid, "Server incident: " + response.status)
                return UploadResult.DONE
            }
            prefs.edit().putString("confirmed:$uid", key).putString("incident:$uid", response.id).apply()
            authorizedKey = key
            incident = response.id
            lastCheck = SystemClock.elapsedRealtime()
        }
        val id = requireNotNull(incident)
        val pending = withContext(disk) { queue.pending(uid, id) }
        val result = replayPendingFixes(pending,
            stillAuthorized = { active(uid, key) && user(uid) != null },
            upload = { fix ->
                BackendClient.api.submitIncidentFix(id, FixDto(fix.latitude, fix.longitude,
                    Instant.ofEpochMilli(fix.timestampMillis).toString(), fix.isMock, fix.accuracyMeters.toDouble()), account)
            },
            acknowledge = { timestamp -> withContext(disk) { queue.acknowledge(uid, id, timestamp) } }
        )
        if (result == ReplayState.SESSION_CHANGED) return UploadResult.DONE
        if (result == ReplayState.RECHECK) lastCheck = 0
        mutable.value = SyncStatus(uid, when {
            result == ReplayState.RECHECK -> "Fix rejected · checking incident state"
            pending.isNotEmpty() -> "Server accepted fix at " + Instant.ofEpochMilli(pending.last().timestampMillis)
            else -> "Test incident connected · no pending fixes"
        })
        return UploadResult.ACTIVE
    }
}

class IncidentUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val uid = inputData.getString("uid") ?: return Result.failure()
        val key = inputData.getString("session") ?: return Result.failure()
        val sync = LocalTrackingRepository.get(applicationContext).sync
        return when (sync.recover(uid, key, true)) {
            UploadResult.DONE -> Result.success()
            UploadResult.RETRY -> if (runAttemptCount < 5) Result.retry() else {
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
