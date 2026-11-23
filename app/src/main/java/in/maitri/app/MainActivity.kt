package `in`.maitri.app

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
    }
}
