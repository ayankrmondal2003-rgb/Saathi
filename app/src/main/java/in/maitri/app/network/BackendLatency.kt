package `in`.maitri.app.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * "Connecting to Maitri…": the free Render instance sleeps after 15 idle minutes and takes up to a minute to
 * wake, and the database may be waking too. Any API call still running after [SLOW_MS] turns this on; it turns
 * off when a call gets a real answer from the server. A failure (timeout, no network, a proxy 502/503/504 while
 * the server boots) leaves it on, because the SOS sync keeps retrying in the background.
 */
class ConnectionState {
    private var slowInFlight = 0
    private var waitingForServer = false
    private val mutable = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = mutable.asStateFlow()

    @Synchronized fun becameSlow() {
        slowInFlight++
        publish()
    }

    /** [reachedServer]: the API itself answered (any status except a gateway error while it boots). */
    @Synchronized fun finished(wasSlow: Boolean, reachedServer: Boolean) {
        if (wasSlow) slowInFlight = (slowInFlight - 1).coerceAtLeast(0)
        waitingForServer = if (reachedServer) false else (waitingForServer || wasSlow)
        publish()
    }

    private fun publish() { mutable.value = slowInFlight > 0 || waitingForServer }

    companion object {
        const val SLOW_MS = 3_000L
        fun reachedServer(code: Int?) = code != null && code !in setOf(502, 503, 504)
    }
}

object BackendLatency {
    val state = ConnectionState()
    val connecting: StateFlow<Boolean> get() = state.connecting

    private val timer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "maitri-latency").apply { isDaemon = true }
    }

    /** Added first, so time spent getting the Firebase token and waking the server both count. */
    val interceptor = Interceptor { chain ->
        // PENDING -> SLOW (timer won) or PENDING -> DONE (call finished first); exactly one transition happens,
        // so a late timer can never count a finished call as slow.
        val phase = AtomicInteger(PENDING)
        val mark = timer.schedule({ if (phase.compareAndSet(PENDING, SLOW)) state.becameSlow() },
            ConnectionState.SLOW_MS, TimeUnit.MILLISECONDS)
        var code: Int? = null
        try {
            chain.proceed(chain.request()).also { code = it.code }
        } finally {
            mark.cancel(false)
            val wasSlow = !phase.compareAndSet(PENDING, DONE)
            state.finished(wasSlow, ConnectionState.reachedServer(code))
        }
    }

    private const val PENDING = 0
    private const val SLOW = 1
    private const val DONE = 2
}
