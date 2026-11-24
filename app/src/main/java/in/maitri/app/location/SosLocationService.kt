package `in`.maitri.app.location

import `in`.maitri.app.tr

import `in`.maitri.app.data.isMaitriVerified
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import `in`.maitri.app.MainActivity
import `in`.maitri.app.data.LocalAuthRepository
import com.google.firebase.auth.FirebaseAuth

class SosLocationService : Service() {
    private val repo by lazy { LocalTrackingRepository.get(this) }
    private var id = ""
    private var subscription: LocationSubscription? = null
    private var finalState = TrackingState.STOPPED
    private val auth by lazy { runCatching { FirebaseAuth.getInstance() }.getOrNull() }
    private val authListener = FirebaseAuth.AuthStateListener {
        if (id.isNotEmpty() && repo.session.value.id == id && !authorized()) {
            repo.close()
            stopSelf()
        }
    }
    private fun authorized(): Boolean {
        val user = auth?.currentUser
        if (auth != null) {
            return user != null && user.isMaitriVerified() && user.uid == repo.session.value.owner
        }
        val localUser = LocalAuthRepository.get(this).current
        return localUser != null && localUser.verified && localUser.uid == repo.session.value.owner
    }
    private val providers = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!locationAllowed()) status(TrackingState.PERMISSION_REQUIRED)
            else if (!locationEnabled()) status(TrackingState.LOCATION_UNAVAILABLE)
        }
    }
    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, providers, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        auth?.addAuthStateListener(authListener)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getStringExtra("session")
        val owner = repo.session.value.owner
        if (requested != null && !repo.sync.canStart(owner, requested)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (requested.isNullOrEmpty() || requested != repo.session.value.id || !authorized()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (id == requested && subscription != null) return START_NOT_STICKY
        subscription?.stop()
        id = requested
        finalState = TrackingState.STOPPED
        try {
            if (!locationAllowed()) {
                status(TrackingState.PERMISSION_REQUIRED)
                return START_NOT_STICKY
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("demo_tracking", tr("Demo location tracking"),
                NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, "demo_tracking")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(tr("Maitri — Location sharing active"))
                .setContentText(tr("Open Maitri to view sharing status or end it."))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(301, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(301, notification)
            repo.change(id) { it.copy(running = true) }
            if (repo.sync.isShared(owner, requested)) repo.sync.wake(owner)
            subscription = LocationSubscription(FusedLocationSource(this, FixMode.SOS), { fix ->
                if (authorized() && locationEnabled()) repo.change(requested) { it.accept(fix) }
            }, ::status).also { it.start(locationAllowed()) }
            if (!locationEnabled()) status(TrackingState.LOCATION_UNAVAILABLE)
        } catch (e: RuntimeException) {
            status(if (e is SecurityException) TrackingState.PERMISSION_REQUIRED else TrackingState.ERROR)
        }
        return START_NOT_STICKY
    }

    private fun status(state: TrackingState) {
        repo.change(id) { it.copy(status = state) }
        if (state == TrackingState.ERROR || state == TrackingState.PERMISSION_REQUIRED) {
            finalState = state
            stopSelf()
        }
    }
    override fun onDestroy() {
        auth?.removeAuthStateListener(authListener)
        unregisterReceiver(providers)
        subscription?.stop()
        repo.change(id) { it.copy(running = false, status = finalState) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
