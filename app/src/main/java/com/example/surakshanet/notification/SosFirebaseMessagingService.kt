package com.example.surakshanet.notification

import android.content.Context
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
                if (invitationId.isBlank() || incidentId.isBlank()) return
                val expiry = runCatching { java.time.Instant.parse(data["expires_at"]) }.getOrNull() ?: return
                if (!expiry.isAfter(java.time.Instant.now())) return
                val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
                InvitationNotificationWorker.enqueue(this, uid, invitationId, incidentId, expiry.toEpochMilli())
            }
            "TEST_ALERT", "EMERGENCY_TEST_NOTIFICATION" -> {
                val testId = invitationId.ifBlank { UUID.randomUUID().toString() }
                val title = remoteMessage.notification?.title ?: "SurakshaNet Test Alert"
                val body = remoteMessage.notification?.body ?: "This is an authorized test notification."
                NotificationHelper.showSosNotification(
                    context = applicationContext,
                    invitationId = testId,
                    incidentId = incidentId.ifBlank { testId },
                    title = title,
                    body = body,
                    eventType = eventType
                )
                saveLastAlertTimestamp(applicationContext, System.currentTimeMillis())
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

        fun getLastAlertTimestamp(context: Context): Long {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_ALERT_TS, 0L)
        }
    }
}
