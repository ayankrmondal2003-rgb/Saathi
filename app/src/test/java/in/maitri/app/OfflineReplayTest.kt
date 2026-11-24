package `in`.maitri.app

import `in`.maitri.app.location.LocationUpdate
import `in`.maitri.app.network.ReplayState
import `in`.maitri.app.network.replayPendingFixes
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class OfflineReplayTest {
    private val fixes = (1L..3L).map { LocationUpdate(0.0, 0.0, 4f, it, null, true) }

    @Test fun disconnectedBatchResumesAtFirstUnacknowledgedFix() = runTest {
        val ack = mutableListOf<Long>()
        try {
            replayPendingFixes(fixes, { true }, {
                if (it.timestampMillis == 2L) throw IOException("Offline fixture")
            }, { ack.add(it) })
            fail("Expected interruption")
        } catch (_: IOException) { assertEquals(listOf(1L), ack) }
        val result = replayPendingFixes(fixes.filter { it.timestampMillis !in ack }, { true }, {}, { ack.add(it) })
        assertEquals(ReplayState.COMPLETE, result)
        assertEquals(listOf(1L, 2L, 3L), ack)
    }

    @Test fun permanentFixRejectionRequestsNewIncidentStatus() = runTest {
        for (code in listOf(409, 422)) {
            val ack = mutableListOf<Long>()
            val result = replayPendingFixes(fixes, { true }, {
                throw HttpException(Response.error<Unit>(code, "".toResponseBody()))
            }, { ack.add(it) })
            assertEquals(ReplayState.RECHECK, result)
            assertEquals(listOf(1L), ack)
        }
    }

    @Test fun accountChangeStopsMidBatch() = runTest {
        val ack = mutableListOf<Long>()
        val result = replayPendingFixes(fixes, { ack.isEmpty() }, {}, { ack.add(it) })
        assertEquals(ReplayState.SESSION_CHANGED, result)
        assertEquals(listOf(1L), ack)
    }
}
