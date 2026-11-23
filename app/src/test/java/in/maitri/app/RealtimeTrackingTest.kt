package `in`.maitri.app

import `in`.maitri.app.location.IncidentTrackingRepository
import `in`.maitri.app.network.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeTrackingTest {
    @Test
    fun exhaustedReconnectsPauseUntilExplicitRetry() = runTest {
        var attempts = 0
        val client = IncidentWebSocketClient(
            incidentId = "fixture",
            okHttpClient = okhttp3.OkHttpClient(),
            ticketProvider = { attempts++; throw java.io.IOException() },
            scope = TestScope(StandardTestDispatcher(testScheduler))
        )
        client.connect()
        testScheduler.advanceUntilIdle()
        assertEquals(6, attempts)
        assertEquals(WsConnectionState.FAILED, client.state.value)
        client.connect()
        testScheduler.advanceUntilIdle()
        assertEquals(12, attempts)
        assertEquals(WsConnectionState.FAILED, client.state.value)
        client.disconnect()
    }

    @Test
    fun assistanceStatusRequiresMatchingServerResponse() = runTest {
        var response = "ACCEPTED"
        var failure = false
        val api = java.lang.reflect.Proxy.newProxyInstance(
            BackendApi::class.java.classLoader, arrayOf(BackendApi::class.java)
        ) { _, method, _ ->
            check(method.name == "updateResponderStatus")
            if (failure) throw java.io.IOException("private detail")
            StatusDto(response)
        } as BackendApi
        val repo = IncidentTrackingRepository("fixture", apiProvider = { api },
            scope = TestScope(StandardTestDispatcher(testScheduler)))
        assertFalse(repo.updateAssistanceStatus("ON_THE_WAY"))
        assertNull(repo.state.value.myAssistanceStatus)
        response = "ON_THE_WAY"
        assertTrue(repo.updateAssistanceStatus("ON_THE_WAY"))
        assertEquals("ON_THE_WAY", repo.state.value.myAssistanceStatus)
        failure = true
        assertFalse(repo.updateAssistanceStatus("HELPING"))
        assertEquals("ON_THE_WAY", repo.state.value.myAssistanceStatus)
        assertFalse(repo.state.value.errorMessage.orEmpty().contains("private detail"))
    }

    @Test
    fun parsesFastApiLocationEnvelope() {
        val message = """{"protocol_version":"1.0","event_type":"LOCATION_UPDATED","incident_id":"fixture","sequence":7,"timestamp":"now","payload":{"latitude":1.25,"longitude":2.5,"accuracy_meters":3,"recorded_at":"now","received_at":"now","is_mock":true}}"""
        val update = RealtimeIncidentEvent.parse(message) as RealtimeIncidentEvent.LocationUpdatedEvent
        assertEquals(7L, update.sequence)
        assertEquals(2.5, update.location.longitude, 0.0)
    }

    @Test
    fun parseIncidentSnapshotEvent() {
        val json = """
        {
            "event": "INCIDENT_SNAPSHOT",
            "version": 1,
            "incident_id": "inc-test-123",
            "timestamp": "2026-11-20T12:00:00Z",
            "snapshot": {
                "incident_id": "inc-test-123",
                "status": "ACTIVE",
                "is_test": true,
                "created_at": "2026-11-20T11:59:00Z",
                "last_location": {
                    "latitude": 28.6139,
                    "longitude": 77.2090,
                    "accuracy_meters": 5.5,
                    "recorded_at": "2026-11-20T11:59:30Z",
                    "received_at": "2026-11-20T11:59:31Z",
                    "is_mock": true,
                    "is_stale": false
                },
                "responders_summary": {
                    "total_invited": 4,
                    "accepted_count": 2,
                    "active_helping_count": 1
                },
                "active_responders": [
                    {
                        "responder_id": "resp-abcd1234",
                        "assistance_status": "HELPING",
                        "updated_at": "2026-11-20T12:00:00Z"
                    }
                ],
                "requester_membership": {
                    "role": "RESPONDER",
                    "status": "ACCEPTED",
                    "assistance_status": "HELPING"
                }
            }
        }
        """.trimIndent()

        val event = RealtimeIncidentEvent.parse(json)
        assertTrue(event is RealtimeIncidentEvent.IncidentSnapshotEvent)
        val snapshotEvent = event as RealtimeIncidentEvent.IncidentSnapshotEvent
        assertEquals("inc-test-123", snapshotEvent.incidentId)
        assertEquals(28.6139, snapshotEvent.snapshot.lastLocation!!.latitude, 0.0001)
        assertEquals(2, snapshotEvent.snapshot.respondersSummary.acceptedCount)
        assertEquals(1, snapshotEvent.snapshot.respondersSummary.activeHelpingCount)
        assertEquals(1, snapshotEvent.snapshot.activeResponders.size)
        assertEquals("HELPING", snapshotEvent.snapshot.activeResponders[0].assistanceStatus)
    }

    @Test
    fun parseLocationUpdatedEvent() {
        val json = """
        {
            "event": "LOCATION_UPDATED",
            "version": 1,
            "incident_id": "inc-test-123",
            "timestamp": "2026-11-20T12:01:00Z",
            "sequence": 42,
            "location": {
                "latitude": 28.6145,
                "longitude": 77.2095,
                "accuracy_meters": 4.2,
                "recorded_at": "2026-11-20T12:00:59Z",
                "received_at": "2026-11-20T12:01:00Z",
                "is_mock": false,
                "is_stale": false
            }
        }
        """.trimIndent()

        val event = RealtimeIncidentEvent.parse(json)
        assertTrue(event is RealtimeIncidentEvent.LocationUpdatedEvent)
        val locEvent = event as RealtimeIncidentEvent.LocationUpdatedEvent
        assertEquals(42L, locEvent.sequence)
        assertEquals(28.6145, locEvent.location.latitude, 0.0001)
        assertEquals(4.2, locEvent.location.accuracyMeters, 0.01)
        assertFalse(locEvent.location.mock)
    }

    @Test
    fun parseResponderStatusAndRevocationEvents() {
        val statusJson = """
        {
            "event": "RESPONDER_STATUS_CHANGED",
            "version": 1,
            "incident_id": "inc-test-123",
            "timestamp": "2026-11-20T12:02:00Z",
            "responder_id": "resp-1234",
            "assistance_status": "ON_THE_WAY",
            "responders_summary": {
                "total_invited": 3,
                "accepted_count": 2,
                "active_helping_count": 1
            }
        }
        """.trimIndent()

        val statusEvent = RealtimeIncidentEvent.parse(statusJson)
        assertTrue(statusEvent is RealtimeIncidentEvent.ResponderStatusChangedEvent)
        assertEquals("ON_THE_WAY", (statusEvent as RealtimeIncidentEvent.ResponderStatusChangedEvent).assistanceStatus)

        val revokedJson = """
        {
            "event": "ACCESS_REVOKED",
            "version": 1,
            "incident_id": "inc-test-123",
            "timestamp": "2026-11-20T12:03:00Z",
            "reason": "Responder account suspended by administrator"
        }
        """.trimIndent()

        val revokedEvent = RealtimeIncidentEvent.parse(revokedJson)
        assertTrue(revokedEvent is RealtimeIncidentEvent.AccessRevokedEvent)
        assertEquals("Responder account suspended by administrator", (revokedEvent as RealtimeIncidentEvent.AccessRevokedEvent).reason)

        val closedJson = """
        {
            "event": "INCIDENT_CLOSED",
            "version": 1,
            "incident_id": "inc-test-123",
            "timestamp": "2026-11-20T12:04:00Z",
            "reason": "Resolved by victim"
        }
        """.trimIndent()

        val closedEvent = RealtimeIncidentEvent.parse(closedJson)
        assertTrue(closedEvent is RealtimeIncidentEvent.IncidentClosedEvent)
        assertEquals("Resolved by victim", (closedEvent as RealtimeIncidentEvent.IncidentClosedEvent).reason)
    }

    @Test
    fun repositoryPurgesCoordinatesOnRevocation() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val repo = IncidentTrackingRepository(
            incidentId = "inc-test-456",
            scope = testScope
        )

        // Seed with a location event
        val locEvent = RealtimeIncidentEvent.LocationUpdatedEvent(
            version = 1,
            incidentId = "inc-test-456",
            timestamp = "2026-11-20T12:00:00Z",
            sequence = 1L,
            location = IncidentLocationDetailDto(28.6139, 77.2090, 5.0, "2026-11-20T12:00:00Z", "2026-11-20T12:00:01Z", true)
        )

        // Simulate receiving location
        val handleEventMethod = IncidentTrackingRepository::class.java.getDeclaredMethod("handleEvent", RealtimeIncidentEvent::class.java).apply {
            isAccessible = true
        }
        handleEventMethod.invoke(repo, locEvent)

        assertEquals(28.6139, repo.state.value.latestLocation!!.latitude, 0.0001)
        assertEquals("ACTIVE", repo.state.value.status)

        // Now simulate ACCESS_REVOKED
        val revokeEvent = RealtimeIncidentEvent.AccessRevokedEvent(
            version = 1,
            incidentId = "inc-test-456",
            timestamp = "2026-11-20T12:05:00Z",
            reason = "Account suspended"
        )
        handleEventMethod.invoke(repo, revokeEvent)

        // Strict security & privacy invariant: coordinates MUST be purged
        assertNull("Victim coordinates must be purged upon revocation", repo.state.value.latestLocation)
        assertEquals("REVOKED", repo.state.value.status)
        assertEquals("Account suspended", repo.state.value.revocationReason)
        assertEquals(WsConnectionState.ACCESS_REVOKED, repo.state.value.connectionState)
    }

    @Test
    fun repositoryEnforcesMonotonicSequenceUpdates() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val repo = IncidentTrackingRepository(
            incidentId = "inc-test-789",
            scope = testScope
        )

        val handleEventMethod = IncidentTrackingRepository::class.java.getDeclaredMethod("handleEvent", RealtimeIncidentEvent::class.java).apply {
            isAccessible = true
        }

        // Sequence 10
        handleEventMethod.invoke(repo, RealtimeIncidentEvent.LocationUpdatedEvent(
            version = 1, incidentId = "inc-test-789", timestamp = "2026-11-20T12:00:00Z", sequence = 10L,
            location = IncidentLocationDetailDto(28.1, 77.1, 5.0, "2026-11-20T12:00:00Z", "2026-11-20T12:00:01Z", false)
        ))
        assertEquals(28.1, repo.state.value.latestLocation!!.latitude, 0.0001)

        // Sequence 5 (older, out-of-order) -> should be discarded
        handleEventMethod.invoke(repo, RealtimeIncidentEvent.LocationUpdatedEvent(
            version = 1, incidentId = "inc-test-789", timestamp = "2026-11-20T11:59:00Z", sequence = 5L,
            location = IncidentLocationDetailDto(28.9, 77.9, 5.0, "2026-11-20T11:59:00Z", "2026-11-20T11:59:01Z", false)
        ))
        assertEquals("Out-of-order older sequence must be discarded", 28.1, repo.state.value.latestLocation!!.latitude, 0.0001)

        // Sequence 11 (newer) -> should be accepted
        handleEventMethod.invoke(repo, RealtimeIncidentEvent.LocationUpdatedEvent(
            version = 1, incidentId = "inc-test-789", timestamp = "2026-11-20T12:01:00Z", sequence = 11L,
            location = IncidentLocationDetailDto(28.2, 77.2, 5.0, "2026-11-20T12:01:00Z", "2026-11-20T12:01:01Z", false)
        ))
        assertEquals(28.2, repo.state.value.latestLocation!!.latitude, 0.0001)
    }
}
