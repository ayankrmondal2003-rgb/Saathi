package `in`.maitri.app.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.random.Random

enum class WsConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ACCESS_REVOKED,
    INCIDENT_CLOSED,
    FAILED
}

sealed class RealtimeIncidentEvent {
    data class IncidentSnapshotEvent(
        val version: Int,
        val incidentId: String,
        val timestamp: String,
        val snapshot: IncidentSnapshotDto
    ) : RealtimeIncidentEvent()

    data class LocationUpdatedEvent(
        val version: Int,
        val incidentId: String,
        val timestamp: String,
        val sequence: Long,
        val location: IncidentLocationDetailDto
    ) : RealtimeIncidentEvent()

    data class ResponderJoinedEvent(
        val version: Int,
        val incidentId: String,
        val timestamp: String,
        val responderId: String,
        val assistanceStatus: String,
        val respondersSummary: ResponderSummaryDto?
    ) : RealtimeIncidentEvent()

    data class ResponderLeftEvent(
        val version: Int,
        val incidentId: String,
        val timestamp: String,
        val responderId: String,
        val respondersSummary: ResponderSummaryDto?
    ) : RealtimeIncidentEvent()

    data class ResponderStatusChangedEvent(
        val version: Int,
        val incidentId: String,
        val timestamp: String,
        val responderId: String,
        val assistanceStatus: String,
        val respondersSummary: ResponderSummaryDto?
    ) : RealtimeIncidentEvent()

    data class IncidentStatusChangedEvent(
        val version: Int,
        val incidentId: String,
        val timestamp: String,
        val status: String
    ) : RealtimeIncidentEvent()

    data class AccessRevokedEvent(
        val version: Int,
        val incidentId: String,
        val timestamp: String,
        val reason: String
    ) : RealtimeIncidentEvent()

    data class IncidentClosedEvent(
        val version: Int,
        val incidentId: String,
        val timestamp: String,
        val reason: String
    ) : RealtimeIncidentEvent()

    data class LocationStaleEvent(
        val version: Int,
        val incidentId: String,
        val timestamp: String
    ) : RealtimeIncidentEvent()

    data class ErrorEvent(
        val version: Int,
        val code: String,
        val message: String
    ) : RealtimeIncidentEvent()

    data class UnknownEvent(
        val eventType: String,
        val rawJson: String
    ) : RealtimeIncidentEvent()

    companion object {
        private val gson = Gson()

        fun parse(text: String): RealtimeIncidentEvent? {
            return runCatching {
                val envelope = JsonParser.parseString(text).asJsonObject
                val payload = envelope.getAsJsonObject("payload")
                val obj = payload ?: envelope
                val eventType = (envelope.get("event_type") ?: envelope.get("event"))?.asString ?: return null
                val version = envelope.get("version")?.asInt ?: 1
                val incidentId = envelope.get("incident_id")?.asString ?: ""
                val timestamp = envelope.get("timestamp")?.asString ?: ""

                when (eventType) {
                    "INCIDENT_SNAPSHOT" -> {
                        val snapshotObj = payload ?: obj.getAsJsonObject("snapshot")
                        val snapshot = gson.fromJson(snapshotObj, IncidentSnapshotDto::class.java)
                        IncidentSnapshotEvent(version, incidentId, timestamp, snapshot)
                    }
                    "LOCATION_UPDATED" -> {
                        val sequence = envelope.get("sequence")?.asLong ?: 0L
                        val locObj = payload ?: obj.getAsJsonObject("location")
                        val loc = gson.fromJson(locObj, IncidentLocationDetailDto::class.java)
                        LocationUpdatedEvent(version, incidentId, timestamp, sequence, loc)
                    }
                    "RESPONDER_JOINED" -> {
                        val responderId = obj.get("responder_id")?.asString ?: ""
                        val assistanceStatus = (obj.get("assistance_status") ?: obj.get("status"))?.asString ?: "ACCEPTED"
                        val summaryObj = obj.getAsJsonObject("responders_summary")
                        val summary = gson.fromJson(summaryObj, ResponderSummaryDto::class.java)
                        ResponderJoinedEvent(version, incidentId, timestamp, responderId, assistanceStatus, summary)
                    }
                    "RESPONDER_LEFT" -> {
                        val responderId = obj.get("responder_id")?.asString ?: ""
                        val summaryObj = obj.getAsJsonObject("responders_summary")
                        val summary = gson.fromJson(summaryObj, ResponderSummaryDto::class.java)
                        ResponderLeftEvent(version, incidentId, timestamp, responderId, summary)
                    }
                    "RESPONDER_STATUS_CHANGED" -> {
                        val responderId = obj.get("responder_id")?.asString ?: ""
                        val assistanceStatus = (obj.get("assistance_status") ?: obj.get("status"))?.asString ?: ""
                        val summaryObj = obj.getAsJsonObject("responders_summary")
                        val summary = gson.fromJson(summaryObj, ResponderSummaryDto::class.java)
                        ResponderStatusChangedEvent(version, incidentId, timestamp, responderId, assistanceStatus, summary)
                    }
                    "INCIDENT_STATUS_CHANGED" -> {
                        val status = obj.get("status")?.asString ?: ""
                        IncidentStatusChangedEvent(version, incidentId, timestamp, status)
                    }
                    "ACCESS_REVOKED" -> {
                        val reason = obj.get("reason")?.asString ?: "Access revoked"
                        AccessRevokedEvent(version, incidentId, timestamp, reason)
                    }
                    "INCIDENT_CLOSED" -> {
                        val reason = obj.get("reason")?.asString ?: "Incident closed"
                        IncidentClosedEvent(version, incidentId, timestamp, reason)
                    }
                    "LOCATION_STALE" -> {
                        LocationStaleEvent(version, incidentId, timestamp)
                    }
                    "ERROR" -> {
                        val code = obj.get("code")?.asString ?: "UNKNOWN"
                        val msg = obj.get("message")?.asString ?: "Unknown error"
                        ErrorEvent(version, code, msg)
                    }
                    else -> UnknownEvent(eventType, text)
                }
            }.getOrNull()
        }
    }
}

class IncidentWebSocketClient(
    val incidentId: String,
    private val okHttpClient: OkHttpClient = BackendClient.rawOkHttpClient,
    private val ticketProvider: suspend (incidentId: String) -> WsTicketDto = { id -> BackendClient.api.wsTicket(id) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val _state = MutableStateFlow(WsConnectionState.DISCONNECTED)
    val state: StateFlow<WsConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RealtimeIncidentEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RealtimeIncidentEvent> = _events.asSharedFlow()

    private var activeWebSocket: WebSocket? = null
    private var connectionJob: Job? = null
    private val isClosedByUser = AtomicBoolean(false)

    fun connect() {
        if (_state.value == WsConnectionState.CONNECTED || _state.value == WsConnectionState.CONNECTING) return
        isClosedByUser.set(false)
        connectionJob?.cancel()
        connectionJob = scope.launch { runConnectionLoop() }
    }

    fun disconnect() {
        isClosedByUser.set(true)
        connectionJob?.cancel()
        connectionJob = null
        activeWebSocket?.close(1000, "Client disconnected")
        activeWebSocket = null
        _state.value = WsConnectionState.DISCONNECTED
    }

    private suspend fun runConnectionLoop() {
        var backoffMs = 1000L
        val maxBackoffMs = 30000L

        var failures = 0
        while (scope.isActive && !isClosedByUser.get()) {
            if (failures++ >= 6) {
                _state.value = WsConnectionState.FAILED
                break
            }
            val currentState = _state.value
            if (currentState == WsConnectionState.ACCESS_REVOKED || currentState == WsConnectionState.INCIDENT_CLOSED) {
                break
            }

            _state.value = if (currentState == WsConnectionState.CONNECTED) WsConnectionState.RECONNECTING else WsConnectionState.CONNECTING

            var ticket: WsTicketDto? = null
            try {
                ticket = ticketProvider(incidentId)
            } catch (e: Exception) {
                if (isClosedByUser.get()) break
                if (e is retrofit2.HttpException && e.code() in listOf(401, 403, 404, 409)) {
                    _state.value = if (e.code() == 409) WsConnectionState.INCIDENT_CLOSED else WsConnectionState.ACCESS_REVOKED
                    break
                }
                _state.value = WsConnectionState.RECONNECTING
                delay(backoffMs)
                backoffMs = min(maxBackoffMs, (backoffMs * 2) + Random.nextLong(200, 500))
                continue
            }

            val fullUrl = `in`.maitri.app.BuildConfig.API_BASE_URL.toHttpUrl()
                .newBuilder().addPathSegments("api/ws/incidents").addPathSegment(incidentId)
                .build()
            val request = Request.Builder().url(fullUrl).header("Authorization", "Ticket ${ticket.ticket}")
                .build()

            val socketCompleted = CompletableDeferred<Unit>()

            val listener = object : WebSocketListener() {
                private var lastSequence = -1L
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    activeWebSocket = webSocket
                    backoffMs = 1000L
                    _state.value = WsConnectionState.CONNECTED
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (isClosedByUser.get() || activeWebSocket !== webSocket) return
                    val envelope = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
                    if (envelope.get("incident_id")?.asString != incidentId) return
                    val event = RealtimeIncidentEvent.parse(text) ?: return
                    val sequence = envelope.get("sequence")?.asLong ?: return
                    if (event is RealtimeIncidentEvent.IncidentSnapshotEvent) failures = 0
                    val terminal = event is RealtimeIncidentEvent.AccessRevokedEvent || event is RealtimeIncidentEvent.IncidentClosedEvent
                    if (!terminal && sequence <= lastSequence) return
                    lastSequence = sequence
                    // OkHttp serializes listener calls on its reader thread. Preserve their order.
                    runBlocking {
                        _events.emit(event)
                        when (event) {
                            is RealtimeIncidentEvent.AccessRevokedEvent -> {
                                _state.value = WsConnectionState.ACCESS_REVOKED
                                webSocket.close(4003, "Access revoked")
                            }
                            is RealtimeIncidentEvent.IncidentClosedEvent -> {
                                _state.value = WsConnectionState.INCIDENT_CLOSED
                                webSocket.close(4004, "Incident closed")
                            }
                            else -> {}
                        }
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    when (code) {
                        4003 -> _state.value = WsConnectionState.ACCESS_REVOKED
                        4004 -> _state.value = WsConnectionState.INCIDENT_CLOSED
                    }
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    activeWebSocket = null
                    when (code) {
                        4003 -> _state.value = WsConnectionState.ACCESS_REVOKED
                        4004 -> _state.value = WsConnectionState.INCIDENT_CLOSED
                        else -> {
                            if (!isClosedByUser.get() && _state.value != WsConnectionState.ACCESS_REVOKED && _state.value != WsConnectionState.INCIDENT_CLOSED) {
                                _state.value = WsConnectionState.RECONNECTING
                            }
                        }
                    }
                    socketCompleted.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    activeWebSocket = null
                    if (!isClosedByUser.get() && _state.value != WsConnectionState.ACCESS_REVOKED && _state.value != WsConnectionState.INCIDENT_CLOSED) {
                        _state.value = WsConnectionState.RECONNECTING
                    }
                    socketCompleted.complete(Unit)
                }
            }

            val socket = okHttpClient.newWebSocket(request, listener)
            activeWebSocket = socket

            // Wait until this socket session terminates
            socketCompleted.await()

            if (isClosedByUser.get() || _state.value == WsConnectionState.ACCESS_REVOKED || _state.value == WsConnectionState.INCIDENT_CLOSED) {
                break
            }

            // Exponential backoff before reconnect attempt
            delay(backoffMs)
            backoffMs = min(maxBackoffMs, (backoffMs * 2) + Random.nextLong(200, 500))
        }

        if (isClosedByUser.get()) {
            _state.value = WsConnectionState.DISCONNECTED
        }
    }
}
