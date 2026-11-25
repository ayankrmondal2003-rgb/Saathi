package `in`.maitri.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.maitri.app.tr

/** A Responder-tab card in the Profile & settings (screen 09) style: palette surface, 20 dp corners, 16 dp padding. */
@Composable
internal fun ResponderCard(p: MaitriPalette, modifier: Modifier = Modifier, title: String? = null,
                           content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp).background(p.surface, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) Text(title, Modifier.semantics { heading() }, color = p.primary, fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold)
        content()
    }
}

/** A toggle row like screen 09's rows: title, one-line description, switch on the right; the whole row toggles. */
@Composable
internal fun ToggleRow(p: MaitriPalette, title: String, description: String, checked: Boolean, enabled: Boolean,
                       onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = if (enabled) p.primary else p.secondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = p.secondary, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** One check in the "Request alerts" card. [fix] is null when there is nothing to fix. */
internal data class AlertCheck(val title: String, val ok: Boolean, val detail: String, val fixLabel: String = "",
                               val fix: (() -> Unit)? = null)

/** Whether requests can reach this phone: each check is OK (green tick) or shows why it matters and a Fix button. */
@Composable
internal fun RequestAlertsCard(p: MaitriPalette, checks: List<AlertCheck>, footer: @Composable ColumnScope.() -> Unit = {}) {
    ResponderCard(p, title = tr("Request alerts")) {
        val problems = checks.count { !it.ok }
        Text(if (problems == 0) tr("Everything is set for SOS requests to reach you.")
            else tr("Fix these so SOS requests reach you quickly."), color = p.secondary, fontSize = 12.sp)
        checks.forEachIndexed { i, check ->
            if (i > 0) HorizontalDivider(color = p.divider, thickness = 1.dp)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(p, check.ok)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(check.title, color = p.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(check.detail, color = p.secondary, fontSize = 12.sp, lineHeight = 16.sp)
                }
                if (!check.ok && check.fix != null) OutlinedButton(onClick = check.fix, border = BorderStroke(1.dp, p.accent),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    Text(check.fixLabel.ifBlank { tr("Fix") }, color = p.accent, fontSize = 13.sp)
                }
            }
        }
        footer()
    }
}

@Composable
private fun StatusDot(p: MaitriPalette, ok: Boolean) {
    val color = if (ok) p.verified else p.pending
    Box(Modifier.size(24.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Icon(if (ok) Icons.Rounded.Check else Icons.Rounded.PriorityHigh, if (ok) tr("OK") else tr("Needs attention"),
            Modifier.size(16.dp), tint = if (p.dark) p.background else Color.White)
    }
}

/** A short explanation of what happens when a request arrives. */
@Composable
internal fun HowRespondingWorksCard(p: MaitriPalette) {
    ResponderCard(p, title = tr("How responding works")) {
        listOf(
            tr("When someone within 1 km sends an SOS, Maitri asks nearby available volunteers."),
            tr("You see only an approximate distance until you accept."),
            tr("Accept only if you can help safely. Declining is always fine."),
            tr("After you accept, you see their live location until the SOS ends."),
        ).forEach { line ->
            Row {
                Text("•", color = p.accent, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                Text(line, color = p.secondary, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

/**
 * Phone-maker settings that hold back alerts, under "Request alerts": a link to let requests ring during Do Not
 * Disturb, and tips for this phone's maker (pop-up style and battery on Samsung; autostart and battery saver on Xiaomi,
 * Oppo/OnePlus and Vivo), with deep links where Android has them.
 */
@Composable
internal fun PhoneTips(p: MaitriPalette) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val maker = android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    HorizontalDivider(color = p.divider, thickness = 1.dp)
    TextButton(onClick = { `in`.maitri.app.notification.SosRequestAlerts.openChannelSettings(context) },
        contentPadding = PaddingValues(0.dp)) {
        Text(tr("Ring during Do Not Disturb"), color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
    TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
        Text(if (open) tr("Hide tips for your {0} phone", maker) else tr("Tips for your {0} phone", maker),
            color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
    if (open) {
        val tips = buildList {
            if (android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true))
                add(tr("Settings › Notifications › Notification pop-up style: choose Brief or Detailed, and include Maitri."))
            addAll(batteryTips(android.os.Build.MANUFACTURER))
        }
        tips.forEach { tip ->
            Row {
                Text("•", color = p.accent, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                Text(tip, color = p.secondary, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openAppNotificationSettings(context) }, border = BorderStroke(1.dp, p.accent)) {
                Text(tr("Notifications"), color = p.accent, fontSize = 13.sp)
            }
            OutlinedButton(onClick = { openAppDetails(context) }, border = BorderStroke(1.dp, p.accent)) {
                Text(tr("Battery"), color = p.accent, fontSize = 13.sp)
            }
        }
    }
}
