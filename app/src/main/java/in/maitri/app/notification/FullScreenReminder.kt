package `in`.maitri.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.auth.FirebaseAuth
import `in`.maitri.app.BuildConfig
import `in`.maitri.app.R
import `in`.maitri.app.network.ResponderLocation
import `in`.maitri.app.tr

/**
 * Android 14+ can switch "full-screen notifications" off when Maitri is updated, and then a nearby SOS request can't
 * wake a volunteer's locked screen. After each update (and at app start) an available volunteer without the permission
 * gets one notification per app version; tapping it opens the setting. It clears itself once the permission is back.
 */
object FullScreenReminder {
    private const val PREFS = "fullscreen_reminder"
    private const val KEY = "shown_for_version"
    private const val CHANNEL = "maitri_setup_v1"
    private const val ID = 4100

    internal fun needed(sdk: Int, allowed: Boolean, available: Boolean, shownFor: Int, version: Int) =
        sdk >= 34 && !allowed && available && shownFor != version

    fun check(context: Context) {
        val app = context.applicationContext
        if (NotificationHelper.canUseFullScreen(app)) { clear(app); return }
        val uid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull() ?: return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!needed(Build.VERSION.SDK_INT, false, ResponderLocation.isAvailable(app, uid), prefs.getInt(KEY, 0),
                BuildConfig.VERSION_CODE)) return
        if (post(app)) prefs.edit().putInt(KEY, BuildConfig.VERSION_CODE).apply()
    }

    fun clear(context: Context) = NotificationManagerCompat.from(context).cancel(ID)

    private fun post(context: Context): Boolean {
        if (!NotificationHelper.hasNotificationPermission(context) || !NotificationHelper.areNotificationsEnabled(context)) return false
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, tr("Setup reminders"), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = tr("Settings Maitri needs so SOS alerts reach you on time") })
        val settings = Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            android.net.Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(context, ID, settings, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = tr("Maitri was updated and this setting was turned off. Without it, a nearby SOS request can't wake your screen. Tap to allow.")
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.maitri_launcher_monochrome).setColor(0xFFB82055.toInt())
            .setContentTitle(tr("Allow SOS requests on your lock screen"))
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        return try { NotificationManagerCompat.from(context).notify(ID, n); true } catch (_: SecurityException) { false }
    }
}

/** Runs after every Maitri update, without the app being opened. */
class AppUpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) FullScreenReminder.check(context)
    }
}
