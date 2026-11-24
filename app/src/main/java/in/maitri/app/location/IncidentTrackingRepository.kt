package `in`.maitri.app.location

import `in`.maitri.app.tr

import `in`.maitri.app.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class IncidentTrackingState(
    val incidentId: String = "",
    val status: String = "ACTIVE",
    val connectionState: WsConnectionState = WsConnectionState.DISCONNECTED,
    val latestLocation: IncidentLocationDetailDto? = null,
    val isStale: Boolean = false,
    val respondersSummary: ResponderSummaryDto = ResponderSummaryDto(0, 0, 0),
    val activeResponders: List<ActiveResponderPublicDto> = emptyList(),
    val myAssistanceStatus: String? = null,
    val revocationReason: String? = null,
    val lastLocationReceivedAtMillis: Long = 0L,
    val errorMessage: String? = null
)

class IncidentTrackingRepository(
    val incidentId: String,
    private val apiProvider: () -> BackendApi = { BackendClient.api },
    private val wsClient: IncidentWebSocketClient? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    private val api by lazy { apiProvider() }
    private val client by lazy { wsClient ?: IncidentWebSocketClient(incidentId) }

    private val _state = MutableStateFlow(IncidentTrackingState(incidentId = incidentId))
    val state: StateFlow<IncidentTrackingState> = _state.asStateFlow()

    private var eventJob: Job? = null
    private var stateJob: Job? = null
    private var staleCheckJob: Job? = null
    private var lastSequence: Long = -1L
    private var snapshotJob: Job? = null
    private var socketSnapshotReceived = false

    fun startTracking() {
        if (eventJob?.isActive == true) return

        // 1. Fetch initial snapshot
        socketSnapshotReceived = false
        snapshotJob = scope.launch {
            try {
                val snapshot = api.snapshot(incidentId)
                if (!socketSnapshotReceived && lastSequence < 0 && _state.value.status == "ACTIVE") applySnapshot(snapshot)
            } catch (e: Exception) {
                // Non-fatal, WS snapshot event will also arrive
                _state.update { it.copy(errorMessage = e.message) }
            }
        }

        // 2. Observe WS connection state
        stateJob = scope.launch {
            client.state.collect { wsState ->
                _state.update { current ->
                    current.copy(
                        connectionState = wsState,
                        status = when (wsState) {
                            WsConnectionState.ACCESS_REVOKED -> "REVOKED"
                            WsConnectionState.INCIDENT_CLOSED -> "CLOSED"
                            else -> current.status
                        },
                        // Strict security: Clear coordinates immediately if revoked
                        latestLocation = if (wsState == WsConnectionState.ACCESS_REVOKED || wsState == WsConnectionState.INCIDENT_CLOSED) null else current.latestLocation
                    )
                }
            }
        }

        // 3. Observe WS events
        eventJob = scope.launch {
            client.events.collect { event ->
                handleEvent(event)
            }
        }

        // 4. Stale location detection loop
        staleCheckJob = scope.launch {
            while (isActive) {
                delay(15000)
                val current = _state.value
                val now = System.currentTimeMillis()
                if (current.latestLocation != null && !current.isStale && current.lastLocationReceivedAtMillis > 0L) {
                    if (now - current.lastLocationReceivedAtMillis > 60000L) {
                        _state.update { it.copy(isStale = true) }
                    }
                }
            }
        }

        // 5. Connect WebSocket
        client.connect()
    }

    fun stopTracking() {
        snapshotJob?.cancel()
        client.disconnect()
        eventJob?.cancel()
        stateJob?.cancel()
        staleCheckJob?.cancel()
        eventJob = null
        stateJob = null
        staleCheckJob = null
    }

    fun retryConnection() {
        if (_state.value.status == "ACTIVE" && client.state.value == WsConnectionState.FAILED) {
            client.connect()
        }
    }

    suspend fun updateAssistanceStatus(newStatus: String): Boolean {
        return try {
            val response = api.updateResponderStatus(incidentId, ResponderStatusUpdateDto(newStatus))
            check(response.status == newStatus)
            _state.update { it.copy(myAssistanceStatus = response.status, errorMessage = null) }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            _state.update { it.copy(errorMessage = tr("Assistance status could not be confirmed. Check your connection and retry.")) }
            false
        }
    }

    private fun handleEvent(event: RealtimeIncidentEvent) {
        when (event) {
            is RealtimeIncidentEvent.IncidentSnapshotEvent -> {
                socketSnapshotReceived = true
                lastSequence = -1L
                if (_state.value.status == "ACTIVE") applySnapshot(event.snapshot)
            }
            is RealtimeIncidentEvent.LocationUpdatedEvent -> {
                if (event.sequence > lastSequence) {
                    lastSequence = event.sequence
                    val now = System.currentTimeMillis()
                    _state.update { current ->
                        if (current.status != "ACTIVE" || recordedMillis(event.location) <= current.lastLocationReceivedAtMillis) return@update current
                        current.copy(
                            latestLocation = event.location,
                            isStale = event.location.stale || now - recordedMillis(event.location) > 60000L,
                            lastLocationReceivedAtMillis = recordedMillis(event.location)
                        )
                    }
                }
            }
            is RealtimeIncidentEvent.ResponderJoinedEvent -> {
                _state.update { current ->
                    val updatedList = current.activeResponders.toMutableList()
                    val existingIndex = updatedList.indexOfFirst { it.responderId == event.responderId }
                    val item = ActiveResponderPublicDto(event.responderId, event.assistanceStatus, event.timestamp)
                    if (existingIndex >= 0) {
                        updatedList[existingIndex] = item
                    } else {
                        updatedList.add(item)
                    }
                    current.copy(
                        respondersSummary = event.respondersSummary ?: current.respondersSummary.copy(acceptedCount = updatedList.size),
                        activeResponders = updatedList
                    )
                }
            }
            is RealtimeIncidentEvent.ResponderLeftEvent -> {
                _state.update { current ->
                    val updatedList = current.activeResponders.filterNot { it.responderId == event.responderId }
                    current.copy(
                        respondersSummary = event.respondersSummary ?: current.respondersSummary.copy(acceptedCount = updatedList.size),
                        activeResponders = updatedList
                    )
                }
            }
            is RealtimeIncidentEvent.ResponderStatusChangedEvent -> {
                _state.update { current ->
                    val updatedList = current.activeResponders.toMutableList()
                    val existingIndex = updatedList.indexOfFirst { it.responderId == event.responderId }
                    val item = ActiveResponderPublicDto(event.responderId, event.assistanceStatus, event.timestamp)
                    if (existingIndex >= 0) {
                        updatedList[existingIndex] = item
                    } else {
                        updatedList.add(item)
                    }
                    current.copy(
                        respondersSummary = event.respondersSummary ?: current.respondersSummary.copy(acceptedCount = updatedList.size),
                        activeResponders = updatedList
                    )
                }
            }
            is RealtimeIncidentEvent.IncidentStatusChangedEvent -> {
                _state.update { it.copy(status = event.status) }
            }
            is RealtimeIncidentEvent.AccessRevokedEvent -> {
                // Strict privacy enforcement: Revocation immediately clears coordinates from client memory!
                _state.update { current ->
                    current.copy(
                        status = "REVOKED",
                        latestLocation = null,
                        revocationReason = event.reason,
                        connectionState = WsConnectionState.ACCESS_REVOKED
                    )
                }
            }
            is RealtimeIncidentEvent.IncidentClosedEvent -> {
                _state.update { current ->
                    current.copy(
                        status = "CLOSED",
                        latestLocation = null,
                        connectionState = WsConnectionState.INCIDENT_CLOSED
                    )
                }
            }
            is RealtimeIncidentEvent.LocationStaleEvent -> {
                _state.update { it.copy(isStale = true) }
            }
            is RealtimeIncidentEvent.ErrorEvent -> {
                _state.update { it.copy(errorMessage = event.message) }
            }
            is RealtimeIncidentEvent.UnknownEvent -> {}
        }
    }

    private fun recordedMillis(location: IncidentLocationDetailDto): Long =
        runCatching { java.time.Instant.parse(location.recordedAt).toEpochMilli() }.getOrDefault(0L)

    private fun applySnapshot(snapshot: IncidentSnapshotDto) {
        val now = System.currentTimeMillis()
        _state.update { current ->
            current.copy(
                status = snapshot.status,
                latestLocation = snapshot.lastLocation,
                isStale = snapshot.lastLocation?.let { loc ->
                    loc.stale || now - recordedMillis(loc) > 60000L
                } ?: true,
                respondersSummary = snapshot.respondersSummary,
                activeResponders = snapshot.activeResponders,
                myAssistanceStatus = snapshot.requesterMembership?.assistanceStatus ?: current.myAssistanceStatus,
                lastLocationReceivedAtMillis = snapshot.lastLocation?.let(::recordedMillis) ?: 0L
            )
        }
    }
}
