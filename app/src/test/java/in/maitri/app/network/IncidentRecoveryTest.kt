package `in`.maitri.app.network

import org.junit.Assert.*
import org.junit.Test

/** How the app recovers its SOS session when the server already has an incident (the "409" bug). */
class IncidentRecoveryTest {
    @Test fun structuredConflictIsParsed() {
        val body = """{"detail":{"code":"INCIDENT_ALREADY_OPEN","message":"An incident is already open","incident_id":"abc"}}"""
        assertEquals(IncidentConflict(INCIDENT_ALREADY_OPEN, "abc"), parseIncidentConflict(body))
        assertEquals(IncidentConflict(IDEMPOTENCY_MISMATCH, null),
            parseIncidentConflict("""{"detail":{"code":"IDEMPOTENCY_MISMATCH","message":"x"}}"""))
    }

    @Test fun olderServersAndJunkBodiesAreTreatedAsUnstructured() {
        assertNull(parseIncidentConflict("""{"detail":"An incident is already open"}"""))
        assertNull(parseIncidentConflict("<html>Bad gateway</html>"))
        assertNull(parseIncidentConflict(""))
        assertNull(parseIncidentConflict(null))
    }

    @Test fun conflictsAndTransientErrorsNeverStopTheSos() {
        listOf(408, 409, 429, 500, 502, 503).forEach { assertFalse("$it must not end the SOS", isFatalSyncCode(it)) }
        listOf(400, 401, 403, 404, 410, 422).forEach { assertTrue("$it ends the session", isFatalSyncCode(it)) }
    }

    @Test fun phoneThatLostItsSessionAdoptsTheOpenServerIncident() {
        // Reinstall, cleared data, or sign-out: nothing linked locally, server still ACTIVE.
        assertEquals(ReconcileAction.ADOPT, decideReconcile(null, "server-1", "ACTIVE"))
        // Linked to an older incident, but the server's open one is different.
        assertEquals(ReconcileAction.ADOPT, decideReconcile("old", "server-1", "ACTIVE"))
        assertEquals(ReconcileAction.KEEP, decideReconcile("server-1", "server-1", "ACTIVE"))
    }

    @Test fun localSessionTheServerEndedIsCleared() {
        assertEquals(ReconcileAction.CLEAR_LOCAL, decideReconcile("server-1", null, null))
    }

    @Test fun unconfirmedOrUnadoptableSessionsAreLeftToTheSyncWorker() {
        // A fresh SOS whose create hasn't reached the server must not be cleared.
        assertEquals(ReconcileAction.KEEP, decideReconcile(null, null, null))
        // Duress or a pending resolution is never adopted or cleared from here.
        assertEquals(ReconcileAction.KEEP, decideReconcile(null, "server-1", "ESCALATION_REQUIRED"))
        assertEquals(ReconcileAction.KEEP, decideReconcile("server-1", "server-1", "RESOLUTION_REQUESTED"))
    }

    @Test fun serverStartTimeKeepsTheElapsedTimerRight() {
        val now = 1_800_000_000_000L
        // The server sends an explicit offset; ten minutes ago in IST is the same instant.
        val tenMinutesAgo = java.time.Instant.ofEpochMilli(now - 600_000).atOffset(java.time.ZoneOffset.ofHoursMinutes(5, 30))
        assertEquals(now - 600_000, serverTimeMillis(tenMinutesAgo.toString(), now))
        assertEquals(now, serverTimeMillis("not a date", now))
        assertEquals(now, serverTimeMillis(null, now))
        // A server clock ahead of the phone never produces a negative elapsed time.
        assertEquals(now, serverTimeMillis(java.time.Instant.ofEpochMilli(now + 60_000).toString(), now))
    }

    @Test fun usersNeverSeeHttpCodes() {
        listOf(400, 401, 403, 404, 410, 422).forEach { code ->
            val text = fatalSyncMessage(code)
            // HTTP error codes are 4xx/5xx; "112" (the emergency number) must still appear.
            assertFalse(text, Regex("\\b[45]\\d\\d\\b").containsMatchIn(text))
            assertTrue(text, "112" in text)
        }
    }

    @Test fun rateLimitedSosEndsTheSessionWithA112Message() {
        val body = """{"detail":{"code":"SOS_RATE_LIMITED","message":"At most 3 SOS alerts per hour."}}"""
        val code = parseIncidentConflict(body)?.code
        assertEquals(SOS_RATE_LIMITED, code)
        assertTrue(isFatalSync(429, code))
        assertFalse("A plain 429 is still retried", isFatalSync(429, null))
        val text = fatalSyncMessage(429, code)
        assertTrue(text, "112" in text)
        assertFalse(text, Regex("\\b[45]\\d\\d\\b").containsMatchIn(text))
    }

    @Test fun realIncidentsDisabledIsRecognised() {
        assertEquals(REAL_INCIDENTS_DISABLED,
            parseIncidentConflict("""{"detail":{"code":"REAL_INCIDENTS_DISABLED","message":"x"}}""")?.code)
    }
}
