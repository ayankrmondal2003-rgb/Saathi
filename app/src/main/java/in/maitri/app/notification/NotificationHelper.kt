package `in`.maitri.app.notification

import `in`.maitri.app.tr

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import `in`.maitri.app.MainActivity


enum class ChannelStatus {
    AVAILABLE,
    BLOCKED,
    MISSING,
    NOTIFICATIONS_DISABLED
}

object NotificationHelper {
    /** The pre-P5 urgent channel; deleted once a pattern channel exists. */
    const val CHANNEL_ID_SOS = "urgent_sos_v1"
    /**
     * Android fixes a channel's vibration when it's created, so each pattern from screen 11 has its own urgent
     * channel; switching patterns switches channels.
     */
    fun sosChannelId(context: Context): String = "urgent_sos_v2_" + AlertVibration.current(context).name.lowercase()
    const val CHANNEL_NAME_SOS = "Urgent SOS Alerts"
    const val CHANNEL_DESC_SOS = "Incoming emergency SOS invitations and practice responder alerts"

    const val EXTRA_INVITATION_ID = "in.maitri.app.extra.INVITATION_ID"
    const val EXTRA_INCIDENT_ID = "in.maitri.app.extra.INCIDENT_ID"
    const val EXTRA_EVENT_TYPE = "in.maitri.app.extra.EVENT_TYPE"
    const val ACTION_VIEW_INVITATION = "in.maitri.app.action.VIEW_INVITATION"
    const val EXTRA_INBOX_ID = "in.maitri.app.extra.INBOX_ID"
    const val EXTRA_URL = "in.maitri.app.extra.URL"
    const val EVENT_INBOX = "INBOX"
    const val CHANNEL_ID_CIRCLE = "circle_updates_v1"
    /** Inbox pushes that need attention now use the urgent channel; the rest are ordinary updates. */
    val URGENT_INBOX_KINDS = setOf("CIRCLE_SOS", "CHECKIN_MISSED")
    val INBOX_KINDS = setOf("CIRCLE_SOS", "CIRCLE_SOS_ENDED", "CHECKIN_REMINDER", "CHECKIN_MISSED",
        "TRIP_STARTED", "CHECKIN_STARTED", "CIRCLE_REQUEST", "CIRCLE_LINKED", "VOLUNTEER_DECISION")

    // Target ~5.2 seconds: 6 pulses of 700ms with 200ms pauses between them
    val EMERGENCY_VIBRATION_PATTERN = longArrayOf(
        0,
        700, 200,
        700, 200,
        700, 200,
        700, 200,
        700, 200,
        700
    )

    @Synchronized
    internal fun claimAlert(context: Context, invitationId: String, now: Long): Boolean {
        val store = context.getSharedPreferences("seen_sos_invitations", Context.MODE_PRIVATE)
        val cutoff = now - 3600000L
        if (store.getLong(invitationId, 0) > cutoff) return false
        val entries = store.all.entries.sortedByDescending { it.value as? Long ?: 0L }
        val editor = store.edit()
        entries.forEachIndexed { index, entry ->
            if (index >= 255 || (entry.value as? Long ?: 0L) <= cutoff) editor.remove(entry.key)
        }
        return editor.putLong(invitationId, now).commit()
    }

    fun createEmergencyNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val id = sosChannelId(context)
            // One urgent channel at a time: the legacy one and other patterns' channels go.
            (listOf(CHANNEL_ID_SOS) + `in`.maitri.app.ui.VibrationPattern.entries.map { "urgent_sos_v2_" + it.name.lowercase() })
                .filter { it != id }.forEach { manager.deleteNotificationChannel(it) }
            val existing = manager.getNotificationChannel(id)
            if (existing != null) return

            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build()

            val channel = NotificationChannel(
                id,
                CHANNEL_NAME_SOS,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESC_SOS
                enableVibration(true)
                vibrationPattern = AlertVibration.pattern(context)
                setSound(soundUri, audioAttributes)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun areNotificationsEnabled(context: Context): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun getChannelStatus(context: Context): ChannelStatus {
        if (!areNotificationsEnabled(context)) {
            return ChannelStatus.NOTIFICATIONS_DISABLED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return ChannelStatus.MISSING
            val channel = manager.getNotificationChannel(sosChannelId(context)) ?: return ChannelStatus.MISSING
            if (channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return ChannelStatus.BLOCKED
            }
        }
        return ChannelStatus.AVAILABLE
    }

    fun openNotificationSettings(context: Context) {
        val intent = Intent().apply {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                else -> {
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = android.net.Uri.fromParts("package", context.packageName, null)
                }
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openChannelSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, sosChannelId(context))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } else {
            openNotificationSettings(context)
        }
    }

    /** Circle alerts, check-ins and circle requests (server inbox items). Tapping opens the item in the app. */
    /** Tag shared by every notification about one SOS (circle alert and volunteer request), so they clear together. */
    internal fun incidentTag(incidentId: String?) = incidentId?.takeIf { it.isNotBlank() }?.let { "sos:$it" }

    /** The SOS ended: remove its circle alert and volunteer request from the shade. */
    fun clearIncident(context: Context, incidentId: String?) {
        val tag = incidentTag(incidentId) ?: return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.activeNotifications.filter { it.tag == tag }.forEach { manager.cancel(it.tag, it.id) } }
    }

    fun showInboxNotification(context: Context, inboxId: String, kind: String, title: String, body: String, url: String?,
                              incidentId: String? = null): Boolean {
        val urgent = kind in URGENT_INBOX_KINDS
        createEmergencyNotificationChannel(context)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(
                android.app.NotificationChannel(CHANNEL_ID_CIRCLE, tr("Circle updates"), android.app.NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = tr("Trips, check-ins and circle requests from people you know") })
        }
        if (!hasNotificationPermission(context) || !areNotificationsEnabled(context)) return false
        if (!claimAlert(context, "inbox:$inboxId", System.currentTimeMillis())) return false
        val intent = Intent(context, MainActivity::class.java).apply {
            data = android.net.Uri.Builder().scheme("saathi").authority("notification").appendPath(EVENT_INBOX).appendPath(inboxId).build()
            putExtra(EXTRA_EVENT_TYPE, EVENT_INBOX)
            putExtra(EXTRA_INBOX_ID, inboxId)
            if (!url.isNullOrBlank()) putExtra(EXTRA_URL, url)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(context, ("inbox:$inboxId").hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, if (urgent) sosChannelId(context) else CHANNEL_ID_CIRCLE)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (urgent) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_SOCIAL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pending).setAutoCancel(true)
        if (urgent) builder.setVibrate(AlertVibration.pattern(context))
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        return try {
            // Only the live alert carries the SOS tag; "ended" must survive the clean-up it triggers.
            val tag = if (kind == "CIRCLE_SOS") incidentTag(incidentId) else null
            NotificationManagerCompat.from(context).notify(tag, 3000 + (inboxId.hashCode() and 0x7FFF), builder.build())
            true
        } catch (_: SecurityException) { false }
    }

    fun showSosNotification(
        context: Context,
        alertId: String,
        incidentId: String?,
        title: String,
        body: String,
        eventType: String,
        timeoutMillis: Long? = null
    ): Boolean {
        createEmergencyNotificationChannel(context)

        if (!hasNotificationPermission(context) || !areNotificationsEnabled(context)) {
            return false
        }

        if (!claimAlert(context, alertId, System.currentTimeMillis())) return false

        val intent = Intent(context, MainActivity::class.java).apply {
            data = android.net.Uri.Builder().scheme("saathi").authority("notification").appendPath(eventType).appendPath(alertId).build()
            if (eventType == "RESPONDER_INVITATION_CREATED") {
                action = ACTION_VIEW_INVITATION
                putExtra(EXTRA_INVITATION_ID, alertId)
                putExtra(EXTRA_INCIDENT_ID, incidentId)
            }
            putExtra(EXTRA_EVENT_TYPE, eventType)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            alertId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val builder = NotificationCompat.Builder(context, sosChannelId(context))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setOngoing(false)
            .setVibrate(AlertVibration.pattern(context))
            .setSound(soundUri)
        // A nearby SOS request opens full screen where Android allows it (Android 14+ asks the user to allow
        // "full-screen notifications"); otherwise it is a heads-up notification with sound and vibration.
        if (eventType == "RESPONDER_INVITATION_CREATED" && canUseFullScreen(context)) builder.setFullScreenIntent(pendingIntent, true)
        // A request can't be answered after it expires, so it leaves the shade by itself then.
        timeoutMillis?.takeIf { it > 0 }?.let { builder.setTimeoutAfter(it) }
        val notification = builder.build()

        val notificationId = 1000 + (alertId.hashCode() and 0x7FFF)
        // Re-checked here (not only in hasNotificationPermission) so lint can see it; revocation can race this call.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        return try {
            NotificationManagerCompat.from(context).notify(incidentTag(incidentId), notificationId, notification)
            true
        } catch (_: SecurityException) { false }
    }

    fun triggerTestVibration(context: Context): Boolean {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return false

            if (!vibrator.hasVibrator()) return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(EMERGENCY_VIBRATION_PATTERN, -1)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(EMERGENCY_VIBRATION_PATTERN, -1)
            }
            return true
        } catch (_: Exception) {
            return false
        }
    }

    /** Android 14+ settings page where the user allows Maitri's full-screen notifications. */
    fun openFullScreenSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= 34)
            Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                android.net.Uri.fromParts("package", context.packageName, null))
        else Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    fun canUseFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || (context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true)
}

/** The vibration chosen on screen 11, used for real SOS alerts as well as the test. */
object AlertVibration {
    private const val PREFS = "alert_prefs"
    private const val KEY = "vibration_pattern"

    fun current(context: Context): `in`.maitri.app.ui.VibrationPattern =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.let { runCatching { `in`.maitri.app.ui.VibrationPattern.valueOf(it) }.getOrNull() }
            ?: `in`.maitri.app.ui.VibrationPattern.STEADY

    fun save(context: Context, pattern: `in`.maitri.app.ui.VibrationPattern) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, pattern.name).apply()
        NotificationHelper.createEmergencyNotificationChannel(context)
    }

    /** The chosen pattern repeated to about 5 seconds, so an alert is hard to miss whichever pattern it is. */
    fun pattern(context: Context): LongArray = repeatTo(current(context).timings, 5_000)

    internal fun repeatTo(timings: LongArray, totalMs: Long): LongArray {
        val body = timings.drop(1)  // first entry is the initial delay
        val out = mutableListOf(0L)
        while (out.sum() < totalMs && body.isNotEmpty()) {
            if (out.size > 1) out += 250L
            out += body
        }
        return out.toLongArray()
    }
}
