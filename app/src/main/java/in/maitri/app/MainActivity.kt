package `in`.maitri.app

import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch
import `in`.maitri.app.navigation.SafetyApp
import `in`.maitri.app.notification.NotificationHelper
import `in`.maitri.app.ui.theme.MaitriTheme

class MainActivity : ComponentActivity() {
    private val pendingInvitationId = mutableStateOf<String?>(null)

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(if (android.os.Build.VERSION.SDK_INT < 33)
            `in`.maitri.app.ui.MaitriLocale.localized(newBase) else newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system splash shows the Maitri logo until the first frame; a longer account check shows MaitriLoader.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        NotificationHelper.createEmergencyNotificationChannel(this)
        handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            MaitriTheme {
                val lightBars = MaterialTheme.colorScheme.background.luminance() > 0.5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
                SafetyApp(
                    pendingInvitationId = pendingInvitationId.value,
                    onInvitationHandled = { pendingInvitationId.value = null }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Available volunteers: the server learns where they are now, so they're reachable for the next ~30 minutes.
        `in`.maitri.app.network.ResponderLocation.onForeground(this)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        pendingInvitationId.value = `in`.maitri.app.network.invitationFromAlert(
            intent?.getStringExtra(NotificationHelper.EXTRA_EVENT_TYPE),
            intent?.getStringExtra(NotificationHelper.EXTRA_INVITATION_ID),
            intent?.getStringExtra(NotificationHelper.EXTRA_INCIDENT_ID)
        )
        intent?.removeExtra(NotificationHelper.EXTRA_INVITATION_ID)
        val inboxId = intent?.getStringExtra(NotificationHelper.EXTRA_INBOX_ID)
        if (intent?.getStringExtra(NotificationHelper.EXTRA_EVENT_TYPE) == NotificationHelper.EVENT_INBOX && inboxId != null) {
            val url = intent.getStringExtra(NotificationHelper.EXTRA_URL)?.takeIf { it.startsWith("https://") }
            intent.removeExtra(NotificationHelper.EXTRA_INBOX_ID)
            // Opening the notification counts as "Opened" for the person who sent the SOS.
            com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    runCatching { `in`.maitri.app.network.BackendClient.circle.read(inboxId, `in`.maitri.app.network.ExpectedAccount(uid)) }
                }
            }
            if (url != null) runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            else `in`.maitri.app.navigation.PendingNavigation.openInbox.value = true
        }
    }
}
