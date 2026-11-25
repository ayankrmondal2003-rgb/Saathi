package `in`.maitri.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Decline, Silence and the 2-minute cap from the notification, without opening the app. */
class SosRequestActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val invitation = intent.getStringExtra(SosRequestAlerts.EXTRA_INVITATION) ?: return
        when (intent.action) {
            // Kept alive until the server has the answer (a receiver's process can otherwise end at once).
            SosRequestAlerts.ACTION_DECLINE -> { val pending = goAsync(); SosRequestAlerts.decline(context, invitation) { pending.finish() } }
            SosRequestAlerts.ACTION_SILENCE -> SosRequestAlerts.silence(context, invitation)
            SosRequestAlerts.ACTION_CAP -> SosRequestAlerts.capReached(context, invitation)
        }
    }
}
