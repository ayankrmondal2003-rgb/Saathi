package `in`.maitri.app.startup

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private val Context.startupStore by preferencesDataStore("startup_cache")

/**
 * Last known server data for the signed-in account (profile, volunteer status, unread count, Safety PIN status,
 * circle links and memberships), kept on disk so screens show it instantly and offline, then refresh in the
 * background (stale-while-revalidate). Keys are per account; signing out clears it. Not included in cloud backup.
 */
object StartupCache {
    const val PROFILE = "profile"
    const val VOLUNTEER = "volunteer"
    const val UNREAD = "unread"
    const val PIN = "pin"
    const val LINKS = "links"
    const val MEMBERSHIPS = "memberships"

    @PublishedApi internal val gson = Gson()
    private val memory = ConcurrentHashMap<String, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var app: Context? = null

    /** Reads the disk copy into memory; called first thing at start-up (a few KB). */
    suspend fun load(context: Context) {
        app = context.applicationContext
        runCatching {
            context.applicationContext.startupStore.data.first().asMap().forEach { (k, v) -> (v as? String)?.let { memory[k.name] = it } }
        }
    }

    fun json(uid: String, key: String): String? = memory["$uid/$key"]

    inline fun <reified T> get(uid: String?, key: String): T? {
        val raw = uid?.let { json(it, key) } ?: return null
        return runCatching { gson.fromJson<T>(raw, object : TypeToken<T>() {}.type) }.getOrNull()
    }

    fun put(uid: String?, key: String, value: Any?) {
        if (uid.isNullOrBlank() || value == null) return
        val k = "$uid/$key"
        val raw = gson.toJson(value)
        if (memory[k] == raw) return
        memory[k] = raw
        val context = app ?: return
        scope.launch { runCatching { context.startupStore.edit { it[stringPreferencesKey(k)] = raw } } }
    }

    /** Signing out (or another account signing in) removes everything cached. */
    fun clear(context: Context) {
        memory.clear()
        scope.launch { runCatching { context.applicationContext.startupStore.edit { it.clear() } } }
    }
}
