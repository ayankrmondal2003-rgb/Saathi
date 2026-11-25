package `in`.maitri.app.notification

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import `in`.maitri.app.MainActivity
import `in`.maitri.app.R
import `in`.maitri.app.network.BackendClient
import `in`.maitri.app.network.DecisionDto
import `in`.maitri.app.network.ExpectedAccount
import `in`.maitri.app.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Nearby SOS requests on a volunteer's phone: a heads-up notification on the dedicated "SOS requests" channel that
 * pops over any app (and a full-screen alert on the lock screen where Android allows it), with Accept / Decline /
 * Silence and a countdown to expiry. With "Keep vibrating for SOS requests" on, it repeats sound and vibration
 * (FLAG_INSISTENT) until answered, silenced, expired, closed by the server, or for at most 2 minutes, after which a
 * normal notification stays. Only a distance band is shown before accepting.
 */
object SosRequestAlerts {
    const val ACTION_ACCEPT = "in.maitri.app.action.SOS_REQUEST_ACCEPT"
    const val ACTION_DECLINE = "in.maitri.app.action.SOS_REQUEST_DECLINE"
    const val ACTION_SILENCE = "in.maitri.app.action.SOS_REQUEST_SILENCE"
    const val ACTION_CAP = "in.maitri.app.action.SOS_REQUEST_CAP"
    const val EXTRA_INVITATION = "in.maitri.app.extra.REQUEST_INVITATION"
    const val CAP_MS = 120_000L
    private const val CLOSED_SHOWN_MS = 5_000L
    private const val PREFS = "sos_requests"
    private const val KEY_KEEP_VIBRATING = "keep_vibrating"

    /** A request the phone is showing. [silenced]: the user or the 2-minute cap stopped the sound and vibration. */
    data class Request(val invitation: String, val incident: String, val uid: String, val expiresAt: Long,
                       val title: String, val body: String, val silenced: Boolean = false, val postedAt: Long = System.currentTimeMillis())

    /** What the full-screen alert and screen 06 react to. */
    sealed interface Event { val invitation: String
        data class Closed(override val invitation: String, val reason: String) : Event
        data class Answered(override val invitation: String) : Event
        data class Silenced(override val invitation: String) : Event
    }
    private val events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val updates = events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Set when the user tapped Accept outside the app; screen 06 accepts this invitation as soon as it opens. */
    @Volatile var autoAccept: String? = null
    fun consumeAutoAccept(invitation: String): Boolean = (autoAccept == invitation).also { if (it) autoAccept = null }
    private val main = Handler(Looper.getMainLooper())

    // --- Setting: Profile & settings -> Safety, your way -> "Keep vibrating for SOS requests" (default on). ---
    fun keepVibrating(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_KEEP_VIBRATING, true)
    fun setKeepVibrating(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_KEEP_VIBRATING, on).apply()

    // --- Channel ---
    /** One channel per vibration pattern (Android fixes a channel's vibration when it's created). */
    fun channelId(context: Context) = "sos_requests_v1_" + AlertVibration.current(context).name.lowercase()

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val id = channelId(context)
        `in`.maitri.app.ui.VibrationPattern.entries.map { "sos_requests_v1_" + it.name.lowercase() }
            .filter { it != id }.forEach { manager.deleteNotificationChannel(it) }
        if (manager.getNotificationChannel(id) != null) return
        manager.createNotificationChannel(NotificationChannel(id, tr("SOS requests"), NotificationManager.IMPORTANCE_HIGH).apply {
            description = tr("Someone nearby needs help. Alerts until you accept or decline.")
            enableVibration(true)
            vibrationPattern = AlertVibration.pattern(context)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
        })
    }

    /** False when the user silenced or minimised the channel (importance below high): requests wouldn't pop up. */
    fun channelOk(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return true
        createChannel(context)
        val channel = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(channelId(context)) ?: return false
        return channel.importance >= NotificationManager.IMPORTANCE_HIGH
    }

    /** Whether this channel may sound during Do Not Disturb (only the user can allow it, in the channel settings). */
    fun bypassesDnd(context: Context): Boolean = Build.VERSION.SDK_INT >= 26 &&
        context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(channelId(context))?.canBypassDnd() == true

    fun openChannelSettings(context: Context) {
        createChannel(context)
        val intent = if (Build.VERSION.SDK_INT >= 26) Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, channelId(context))
        else Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // --- State of the requests being shown (survives the process; small). ---
    private fun store(context: Context) = context.getSharedPreferences("$PREFS-active", Context.MODE_PRIVATE)
    fun get(context: Context, invitation: String): Request? = store(context).getString(invitation, null)?.let { raw ->
        runCatching { JSONObject(raw).let { j -> Request(invitation, j.getString("incident"), j.getString("uid"), j.getLong("expires"),
            j.getString("title"), j.getString("body"), j.optBoolean("silenced"), j.optLong("posted")) } }.getOrNull()
    }
    private fun save(context: Context, r: Request) = store(context).edit().putString(r.invitation, JSONObject()
        .put("incident", r.incident).put("uid", r.uid).put("expires", r.expiresAt).put("title", r.title).put("body", r.body)
        .put("silenced", r.silenced).put("posted", r.postedAt).toString()).apply()
    private fun forget(context: Context, invitation: String) = store(context).edit().remove(invitation).apply()
    fun active(context: Context): List<Request> = store(context).all.keys.mapNotNull { get(context, it) }
        .filter { it.expiresAt > System.currentTimeMillis() }.sortedByDescending { it.postedAt }
    fun anyAlerting(context: Context) = active(context).any { !it.silenced }

    private fun notificationId(invitation: String) = 1000 + (invitation.hashCode() and 0x7FFF)

    /** Shows (or re-shows) a request. [alert] false updates it without sound or vibration. */
    fun show(context: Context, request: Request, alert: Boolean = true): Boolean {
        createChannel(context)
        if (!NotificationHelper.hasNotificationPermission(context) || !NotificationHelper.areNotificationsEnabled(context)) return false
        val now = System.currentTimeMillis()
        if (request.expiresAt <= now) return false
        save(context, request)
        val insistent = alert && !request.silenced && keepVibrating(context)
        val n = build(context, request, alert && !request.silenced, insistent)
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        return try {
            NotificationManagerCompat.from(context).notify(NotificationHelper.incidentTag(request.incident), notificationId(request.invitation), n)
            if (insistent) scheduleCap(context, request.invitation)
            true
        } catch (_: SecurityException) { false }
    }

    private fun build(context: Context, r: Request, sound: Boolean, insistent: Boolean): Notification {
        val now = System.currentTimeMillis()
        val view = PendingIntent.getActivity(context, ("view:" + r.invitation).hashCode(), viewIntent(context, r.invitation, false),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Accept opens the app (Android asks to unlock first from the lock screen), sends a fresh location, then accepts.
        val accept = PendingIntent.getActivity(context, ("accept:" + r.invitation).hashCode(), viewIntent(context, r.invitation, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, channelId(context))
            .setSmallIcon(R.drawable.maitri_launcher_monochrome)
            .setColor(0xFFB82055.toInt())
            .setContentTitle(r.title)
            .setContentText(r.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(view)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(!sound)
            .setSilent(!sound)
            // Countdown to expiry in the header.
            .setWhen(r.expiresAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            .setTimeoutAfter((r.expiresAt - now).coerceAtLeast(1))
            .addAction(0, tr("Accept"), accept)
            .addAction(0, tr("Decline"), broadcast(context, ACTION_DECLINE, r.invitation))
        if (insistent) builder.addAction(0, tr("Silence"), broadcast(context, ACTION_SILENCE, r.invitation))
        if (sound) builder.setVibrate(AlertVibration.pattern(context))
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        // Locked or screen off: a full-screen alert (Android 14+ needs the user's permission; otherwise heads-up only).
        if (sound && NotificationHelper.canUseFullScreen(context)) {
            val full = Intent(context, `in`.maitri.app.ui.SosRequestActivity::class.java)
                .putExtra(EXTRA_INVITATION, r.invitation).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            builder.setFullScreenIntent(PendingIntent.getActivity(context, ("full:" + r.invitation).hashCode(), full,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE), true)
        }
        return builder.build().apply { if (insistent) flags = flags or Notification.FLAG_INSISTENT }
    }

    fun viewIntent(context: Context, invitation: String, accept: Boolean): Intent {
        val r = get(context, invitation)
        return Intent(context, MainActivity::class.java).apply {
            data = android.net.Uri.Builder().scheme("saathi").authority("notification")
                .appendPath(if (accept) "accept" else "RESPONDER_INVITATION_CREATED").appendPath(invitation).build()
            action = if (accept) ACTION_ACCEPT else NotificationHelper.ACTION_VIEW_INVITATION
            putExtra(NotificationHelper.EXTRA_INVITATION_ID, invitation)
            putExtra(NotificationHelper.EXTRA_INCIDENT_ID, r?.incident)
            putExtra(NotificationHelper.EXTRA_EVENT_TYPE, "RESPONDER_INVITATION_CREATED")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    private fun broadcast(context: Context, action: String, invitation: String): PendingIntent =
        PendingIntent.getBroadcast(context, (action + invitation).hashCode(),
            Intent(context, SosRequestActionReceiver::class.java).setAction(action).putExtra(EXTRA_INVITATION, invitation),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** The 2-minute safety cap: in this process now, and an alarm in case the process is gone by then. */
    private fun scheduleCap(context: Context, invitation: String) {
        val app = context.applicationContext
        androidx.core.os.HandlerCompat.postDelayed(main, { capReached(app, invitation) }, invitation, CAP_MS)
        runCatching {
            app.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + CAP_MS, broadcast(app, ACTION_CAP, invitation))
        }
    }
    private fun cancelCap(context: Context, invitation: String) {
        main.removeCallbacksAndMessages(invitation)
        runCatching { context.getSystemService(AlarmManager::class.java).cancel(broadcast(context, ACTION_CAP, invitation)) }
    }

    /** Stops the sound and vibration but keeps the request (Silence, volume key, the 2-minute cap, screen 06 opened). */
    fun silence(context: Context, invitation: String) {
        val r = get(context, invitation) ?: return
        cancelCap(context, invitation)
        if (r.silenced) return
        // Cancelling is what stops an insistent alert; it's re-posted at once, quietly.
        NotificationManagerCompat.from(context).cancel(NotificationHelper.incidentTag(r.incident), notificationId(invitation))
        show(context, r.copy(silenced = true), alert = false)
        events.tryEmit(Event.Silenced(invitation))
    }
    fun silenceAll(context: Context) = active(context).filter { !it.silenced }.forEach { silence(context, it.invitation) }
    fun capReached(context: Context, invitation: String) = silence(context, invitation)

    /** Accept or Decline from anywhere: the alert stops and the notification goes. */
    fun answered(context: Context, invitation: String, accepted: Boolean) {
        remove(context, invitation)
        events.tryEmit(Event.Answered(invitation))
        // Accepting one request answers the others: they're declined so nobody waits on this volunteer.
        if (accepted) active(context).forEach { other -> decline(context, other.invitation) }
    }

    /** Decline without opening the app. The alert stops at once; the server is told in the background. */
    fun decline(context: Context, invitation: String, done: () -> Unit = {}) {
        val r = get(context, invitation)
        answered(context, invitation, accepted = false)
        val uid = r?.uid ?: return done()
        scope.launch {
            try { runCatching { BackendClient.api.respond(invitation, DecisionDto("DECLINED"), ExpectedAccount(uid)) } }
            finally { done() }
        }
    }

    /** The server closed the request (SOS ended, or enough volunteers accepted): say so, stop alerting, go in 5 s. */
    fun closed(context: Context, invitation: String, reason: String) {
        val r = get(context, invitation) ?: return
        cancelCap(context, invitation)
        forget(context, invitation)
        val nm = NotificationManagerCompat.from(context)
        nm.cancel(NotificationHelper.incidentTag(r.incident), notificationId(invitation))
        events.tryEmit(Event.Closed(invitation, reason))
        val text = if (reason == "FILLED") tr("Help is already on the way") else tr("SOS ended")
        val n = NotificationCompat.Builder(context, channelId(context))
            .setSmallIcon(R.drawable.maitri_launcher_monochrome).setColor(0xFFB82055.toInt())
            .setContentTitle(text).setContentText(tr("You don't need to respond."))
            .setSilent(true).setOnlyAlertOnce(true).setAutoCancel(true).setTimeoutAfter(CLOSED_SHOWN_MS).build()
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        try { nm.notify(NotificationHelper.incidentTag(r.incident), notificationId(invitation), n) } catch (_: SecurityException) { }
    }

    /** Expired or answered elsewhere: remove quietly. */
    fun remove(context: Context, invitation: String) {
        val r = get(context, invitation)
        cancelCap(context, invitation)
        forget(context, invitation)
        NotificationManagerCompat.from(context).cancel(NotificationHelper.incidentTag(r?.incident), notificationId(invitation))
    }

    /** "Someone nearby needs help" / "Practice SOS nearby", with the distance band only (never a place). */
    fun texts(isTest: Boolean?, band: String?): Pair<String, String> {
        val title = if (isTest == true) tr("Practice SOS nearby") else tr("Someone nearby needs help")
        val where = band?.let { tr("{0} away", tr(it)) } ?: tr("Nearby")
        return title to tr("{0} · Accept only if you can help safely.", where)
    }
}
