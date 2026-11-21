package com.example.surakshanet.notification

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
import com.example.surakshanet.MainActivity


enum class ChannelStatus {
    AVAILABLE,
    BLOCKED,
    MISSING,
    NOTIFICATIONS_DISABLED
}

object NotificationHelper {
    const val CHANNEL_ID_SOS = "urgent_sos_v1"
    const val CHANNEL_NAME_SOS = "Urgent SOS Alerts"
    const val CHANNEL_DESC_SOS = "Incoming emergency SOS invitations and practice responder alerts"

    const val EXTRA_INVITATION_ID = "com.example.surakshanet.extra.INVITATION_ID"
    const val EXTRA_INCIDENT_ID = "com.example.surakshanet.extra.INCIDENT_ID"
    const val EXTRA_EVENT_TYPE = "com.example.surakshanet.extra.EVENT_TYPE"
    const val ACTION_VIEW_INVITATION = "com.example.surakshanet.action.VIEW_INVITATION"

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
            val existing = manager.getNotificationChannel(CHANNEL_ID_SOS)
            if (existing != null) return

            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID_SOS,
                CHANNEL_NAME_SOS,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESC_SOS
                enableVibration(true)
                vibrationPattern = EMERGENCY_VIBRATION_PATTERN
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
            val channel = manager.getNotificationChannel(CHANNEL_ID_SOS) ?: return ChannelStatus.MISSING
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
                putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID_SOS)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } else {
            openNotificationSettings(context)
        }
    }

    fun showSosNotification(
        context: Context,
        invitationId: String,
        incidentId: String,
        title: String,
        body: String,
        eventType: String
    ): Boolean {
        createEmergencyNotificationChannel(context)

        if (!hasNotificationPermission(context) || !areNotificationsEnabled(context)) {
            return false
        }

        if (!claimAlert(context, invitationId, System.currentTimeMillis())) return false

        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_VIEW_INVITATION
            putExtra(EXTRA_INVITATION_ID, invitationId)
            putExtra(EXTRA_INCIDENT_ID, incidentId)
            putExtra(EXTRA_EVENT_TYPE, eventType)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            invitationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SOS)
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
            .setVibrate(EMERGENCY_VIBRATION_PATTERN)
            .setSound(soundUri)
            .build()

        val notificationId = 1000 + (invitationId.hashCode() and 0x7FFF)
        NotificationManagerCompat.from(context).notify(notificationId, notification)
        return true
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
}
