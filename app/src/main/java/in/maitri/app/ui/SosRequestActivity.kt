package `in`.maitri.app.ui

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import `in`.maitri.app.R
import `in`.maitri.app.notification.SosRequestAlerts
import `in`.maitri.app.tr
import `in`.maitri.app.ui.theme.MaitriTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen nearby SOS request, shown over the lock screen and turning the screen on: large Accept / Decline, the
 * distance band, a countdown and Silence. Accept asks to unlock first (requestDismissKeyguard), because the person's
 * live location is shown only after accepting. Volume keys silence the alert without declining.
 */
class SosRequestActivity : ComponentActivity() {
    private var invitation by mutableStateOf<String?>(null)
    private var closedReason by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        invitation = intent.getStringExtra(SosRequestAlerts.EXTRA_INVITATION)
        lifecycleScope.launch {
            SosRequestAlerts.updates.collect { event ->
                if (event.invitation != invitation) return@collect
                when (event) {
                    is SosRequestAlerts.Event.Answered -> finish()
                    is SosRequestAlerts.Event.Closed -> { closedReason = event.reason; delay(5_000); finish() }
                    is SosRequestAlerts.Event.Silenced -> Unit
                }
            }
        }
        setContent { MaitriTheme { Screen() } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(SosRequestAlerts.EXTRA_INVITATION)?.let { invitation = it; closedReason = null }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            invitation?.let { SosRequestAlerts.silence(this, it) }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun accept() {
        val id = invitation ?: return
        val open = { startActivity(SosRequestAlerts.viewIntent(this, id, accept = true)); finish() }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true && Build.VERSION.SDK_INT >= 26) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { open() }
            })
        } else open()
    }

    @Composable
    private fun Screen() {
        val p = maitriPalette()
        val id = invitation
        val request = remember(id) { id?.let { SosRequestAlerts.get(this, it) } }
        var secondsLeft by remember(request) { mutableLongStateOf(0L) }
        LaunchedEffect(request) {
            while (request != null) {
                secondsLeft = ((request.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                if (secondsLeft == 0L) { SosRequestAlerts.remove(this@SosRequestActivity, request.invitation); finish(); break }
                delay(1000)
            }
        }
        if (request == null && closedReason == null) { LaunchedEffect(Unit) { finish() }; return }
        Column(Modifier.fillMaxSize().background(p.background).systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(150.dp).background(p.card, CircleShape), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.maitri_logo), null, Modifier.size(84.dp))
            }
            Spacer(Modifier.height(28.dp))
            val closed = closedReason
            if (closed != null) {
                Text(if (closed == "FILLED") tr("Help is already on the way") else tr("SOS ended"), color = p.primary,
                    fontSize = 26.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(tr("You don't need to respond."), color = p.secondary, fontSize = 15.sp)
                Spacer(Modifier.weight(1.4f))
                return@Column
            }
            Text(request!!.title, color = p.primary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(request.body, color = p.secondary, fontSize = 16.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text(tr("Answer within {0}", "%d:%02d".format(secondsLeft / 60, secondsLeft % 60)), color = p.accent,
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Button(onClick = ::accept, modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent)) {
                Text(tr("Accept"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { SosRequestAlerts.decline(this@SosRequestActivity, request.invitation); finish() },
                modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(32.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, p.accent)) {
                Text(tr("Decline"), color = p.accent, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { SosRequestAlerts.silence(this@SosRequestActivity, request.invitation) }) {
                Text(tr("Silence"), color = p.secondary, fontSize = 15.sp)
            }
            Text(tr("Accept only if you can help safely. Declining is always fine."), color = p.secondary, fontSize = 12.sp,
                textAlign = TextAlign.Center)
        }
    }
}
