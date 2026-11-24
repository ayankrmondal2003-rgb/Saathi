package `in`.maitri.app.network

import org.junit.Assert.*
import org.junit.Test

/** "Connecting to Maitri…" while the free server wakes up (cold start up to a minute). */
class ConnectionStateTest {
    @Test fun fastCallsNeverShowConnecting() {
        val state = ConnectionState()
        state.finished(wasSlow = false, reachedServer = true)
        assertFalse(state.connecting.value)
    }

    @Test fun aCallPastThreeSecondsShowsConnectingUntilTheServerAnswers() {
        val state = ConnectionState()
        state.becameSlow()
        assertTrue(state.connecting.value)
        state.finished(wasSlow = true, reachedServer = true)
        assertFalse(state.connecting.value)
    }

    @Test fun aSlowCallThatFailsKeepsConnectingWhileTheSosRetries() {
        val state = ConnectionState()
        state.becameSlow()
        state.finished(wasSlow = true, reachedServer = false) // timed out while Render was booting
        assertTrue(state.connecting.value)
        state.finished(wasSlow = false, reachedServer = false) // a quick failure doesn't clear it either
        assertTrue(state.connecting.value)
        state.finished(wasSlow = false, reachedServer = true) // the retry got through
        assertFalse(state.connecting.value)
    }

    @Test fun overlappingCallsClearOnlyWhenNoneIsStillSlow() {
        val state = ConnectionState()
        state.becameSlow()
        state.becameSlow()
        state.finished(wasSlow = true, reachedServer = true)
        assertTrue(state.connecting.value)
        state.finished(wasSlow = true, reachedServer = true)
        assertFalse(state.connecting.value)
    }

    @Test fun gatewayErrorsWhileBootingAreNotAnAnswer() {
        assertFalse(ConnectionState.reachedServer(null))
        listOf(502, 503, 504).forEach { assertFalse(ConnectionState.reachedServer(it)) }
        listOf(200, 204, 401, 404, 409, 422, 500).forEach { assertTrue(ConnectionState.reachedServer(it)) }
    }
}
