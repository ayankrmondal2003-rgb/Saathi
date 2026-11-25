package `in`.maitri.app.notification

import `in`.maitri.app.tr

import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

class SosFirebaseMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        saveLocalFcmToken(this, token)
        // If user is currently signed in, asynchronously sync token with backend
        scope.launch {
            DeviceRepository.get(applicationContext).handleNewToken(token)
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val data = remoteMessage.data
        if (data.isEmpty()) return

        val eventType = data["event_type"].orEmpty()
        val invitationId = data["invitation_id"].orEmpty()
        val incidentId = data["incident_id"].orEmpty()

        // Security check: verify no precise GPS coordinates exist in incoming notification payload
        if (data.containsKey("latitude") || data.containsKey("longitude") ||
            data.containsKey("lat") || data.containsKey("lon")) {
            // Drop message containing prohibited coordinate leaks
            return
        }

        // Validate event types
        when (eventType) {
            "RESPONDER_INVITATION_CREATED" -> {
                if (`in`.maitri.app.network.invitationFromAlert(eventType, invitationId, incidentId) == null) return
                val expiry = runCatching { java.time.Instant.parse(data["expires_at"]) }.getOrNull() ?: return
                if (!expiry.isAfter(java.time.Instant.now())) return
                val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
                if (!NotificationHelper.claimAlert(applicationContext, invitationId, System.currentTimeMillis())) return
                // The alert goes up now (a high-priority message gives a few seconds), with the distance band if the
                // server answers within 2.5 s; otherwise "Nearby", and the worker fills in the details.
                val details = kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(2_500) {
                        runCatching { `in`.maitri.app.network.BackendClient.api.invitations(`in`.maitri.app.network.ExpectedAccount(uid))
                            .firstOrNull { it.id == invitationId } }.getOrNull()
                    }
                }
                if (details != null && details.status != "PENDING") return
                val (title, body) = SosRequestAlerts.texts(details?.isTest, details?.distanceBand)
                if (SosRequestAlerts.show(applicationContext, SosRequestAlerts.Request(invitationId, incidentId, uid,
                        expiry.toEpochMilli(), title, body)))
                    saveLastAlertTimestamp(applicationContext, System.currentTimeMillis())
                if (details == null) InvitationNotificationWorker.enqueue(this, uid, invitationId, incidentId, expiry.toEpochMilli())
            }
            "SOS_REQUEST_CLOSED" -> {
                // The SOS ended or enough volunteers accepted: stop alerting and say so for a few seconds.
                val invitation = data["invitation_id"]?.takeIf { runCatching { UUID.fromString(it) }.isSuccess } ?: return
                SosRequestAlerts.closed(applicationContext, invitation, data["reason"].orEmpty())
            }
            "TEST_ALERT", "EMERGENCY_TEST_NOTIFICATION" -> {
                val testId = data["notification_id"] ?: remoteMessage.messageId ?: UUID.randomUUID().toString()
                val title = remoteMessage.notification?.title ?: tr("Maitri alert")
                val body = remoteMessage.notification?.body ?: tr("This is an authorized test notification.")
                NotificationHelper.showSosNotification(
                    context = applicationContext,
                    alertId = testId,
                    incidentId = null,
                    title = title,
                    body = body,
                    eventType = eventType
                )
                saveLastAlertTimestamp(applicationContext, System.currentTimeMillis())
            }
            in NotificationHelper.INBOX_KINDS -> {
                val inboxId = data["inbox_id"]?.takeIf { runCatching { UUID.fromString(it) }.isSuccess } ?: return
                val url = data["url"]?.takeIf { it.startsWith("https://") }
                // "SOS ended": the circle alert and any volunteer request for that SOS leave the shade first.
                if (eventType == "CIRCLE_SOS_ENDED") NotificationHelper.clearIncident(applicationContext,
                    data["incident_id"]?.takeIf { runCatching { UUID.fromString(it) }.isSuccess })
                val shown = NotificationHelper.showInboxNotification(applicationContext, inboxId, eventType,
                    data["title"].orEmpty().ifBlank { tr("Maitri") }.take(120), data["body"].orEmpty().take(500), url,
                    data["incident_id"]?.takeIf { runCatching { UUID.fromString(it) }.isSuccess })
                // Tell the server the push reached this phone: the only basis for "Push delivered" on the sender's screen.
                val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                if (shown && uid != null) scope.launch {
                    runCatching { `in`.maitri.app.network.BackendClient.circle.delivered(inboxId, `in`.maitri.app.network.ExpectedAccount(uid)) }
                }
            }
            else -> {
                // Ignore unrecognized or untrusted event types
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "fcm_prefs"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_LAST_ALERT_TS = "last_alert_timestamp"

        fun saveLocalFcmToken(context: Context, token: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_FCM_TOKEN, token)
                .apply()
        }

        fun getLocalFcmToken(context: Context): String? {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_FCM_TOKEN, null)
        }

        fun saveLastAlertTimestamp(context: Context, timestamp: Long) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_ALERT_TS, timestamp)
                .apply()
        }

        fun lastAlertTimestamps(context: Context): kotlinx.coroutines.flow.Flow<Long> =
            kotlinx.coroutines.flow.callbackFlow {
                val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == KEY_LAST_ALERT_TS) trySend(getLastAlertTimestamp(context))
                }
                preferences.registerOnSharedPreferenceChangeListener(listener)
                trySend(getLastAlertTimestamp(context))
                awaitClose {
                    preferences.unregisterOnSharedPreferenceChangeListener(listener)
                }
            }

        fun getLastAlertTimestamp(context: Context): Long {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_ALERT_TS, 0L)
        }
    }
}
