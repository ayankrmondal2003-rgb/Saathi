package `in`.maitri.app.location

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

class LocalTrackingRepository private constructor(private val context: Context) : LocationRepository {
    private val prefs = context.getSharedPreferences("sos_location", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(restore())
    override val session = mutable.asStateFlow()
    val sync = `in`.maitri.app.network.TestIncidentSync(context) { session.value }
    private fun restore(): TrackingSession = runCatching {
        val row = JSONObject(prefs.getString("session", null) ?: return TrackingSession())
        // Remove location fields left in preferences by earlier builds on first restore.
        if (row.has("lat")) {
            listOf("lat", "lon", "accuracy", "time", "speed", "mock").forEach(row::remove)
            prefs.edit().putString("session", row.toString()).apply()
        }
        TrackingSession(row.getString("uid"), row.getString("id"), row.getLong("started"),
            TrackingState.STOPPED, count = row.getInt("count"))
    }.getOrDefault(TrackingSession())

    fun restoreConfirmed(uid: String) {
        if (session.value.id.isNotEmpty()) return
        val key = sync.confirmedKey(uid) ?: return
        persist(TrackingSession(uid, key, sync.startedAt(uid), TrackingState.STOPPED))
    }

    fun begin(uid: String, startedAt: Long = System.currentTimeMillis()) {
        if (session.value.owner == uid && session.value.id.isNotEmpty()) return
        persist(TrackingSession(uid, UUID.randomUUID().toString(), startedAt))
    }
    fun change(id: String, block: (TrackingSession) -> TrackingSession) {
        val old = session.value
        if (id.isNotEmpty() && old.id == id) {
            val next = block(old)
            if (old != next) {
                persist(next)
                val fix = next.latest
                if (fix != null && fix.timestampMillis != old.latest?.timestampMillis) sync.record(next.owner, id, fix)
            }
        }
    }
    fun close() {
        sync.discardLocations(session.value.owner)
        context.stopService(Intent(context, SosLocationService::class.java))
        prefs.edit().clear().apply()
        mutable.value = TrackingSession(status = TrackingState.STOPPED)
    }
    private fun persist(value: TrackingSession) {
        val row = JSONObject().put("uid", value.owner).put("id", value.id).put("started", value.startedAt)
            .put("status", value.status.name).put("count", value.count)

        prefs.edit().putString("session", row.toString()).apply()
        mutable.value = value
    }
    companion object {
        private var instance: LocalTrackingRepository? = null
        @Synchronized fun get(context: Context): LocalTrackingRepository =
            instance ?: LocalTrackingRepository(context.applicationContext).also { instance = it }
    }
}
