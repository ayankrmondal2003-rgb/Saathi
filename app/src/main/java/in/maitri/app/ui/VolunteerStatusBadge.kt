package `in`.maitri.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.maitri.app.tr
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the server's volunteer status means on screen. */
internal enum class VolunteerState { APPROVED, PENDING, REJECTED, NOT_APPLIED }

internal fun volunteerState(status: String?): VolunteerState? = when (status) {
    null -> null
    "APPROVED" -> VolunteerState.APPROVED
    "SUBMITTED", "UNDER_REVIEW" -> VolunteerState.PENDING
    "REJECTED", "REQUIRES_CORRECTION" -> VolunteerState.REJECTED
    else -> VolunteerState.NOT_APPLIED  // NOT_STARTED, DRAFT
}

/** "25 Nov 2026" in the phone's locale, from the server's ISO timestamp; null if missing or unreadable. */
internal fun approvalDate(iso: String?): String? = iso?.let {
    runCatching {
        OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }.getOrNull()
}

/** A filled circle with a check, used for the badge and next to the name on the Profile card. */
@Composable
internal fun VerifiedCheck(p: MaitriPalette, size: Dp = 22.dp) {
    // White on the light theme's green (5.4:1); on dark mode's light green a dark check keeps 3:1+ (11.7:1).
    Box(Modifier.size(size).background(p.verified, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Check, tr("Verified volunteer"), Modifier.size(size * 0.68f),
            tint = if (p.dark) p.background else Color.White)
    }
}

/**
 * Top of the Responder tab: Verified volunteer (with the approval date), Application under review, Application not
 * approved (reviewer's note + Reapply), or the Volunteer application button when the person hasn't applied.
 */
@Composable
internal fun VolunteerStatusBadge(p: MaitriPalette, status: String?, reviewedAt: String?, reviewerNote: String?,
                                  onApply: () -> Unit, modifier: Modifier = Modifier) {
    when (volunteerState(status)) {
        null -> Unit
        VolunteerState.APPROVED -> BadgeCard(p, modifier, { VerifiedCheck(p, 36.dp) }, tr("Verified volunteer"), p.verified,
            approvalDate(reviewedAt)?.let { tr("Approved {0}", it) } ?: tr("Approved"))
        VolunteerState.PENDING -> BadgeCard(p, modifier, { StatusCircle(p.pending, Icons.Rounded.Schedule, p) },
            tr("Application under review"), p.pending, tr("We'll let you know when a reviewer has checked it."))
        VolunteerState.REJECTED -> BadgeCard(p, modifier, { StatusCircle(p.secondary, Icons.Rounded.Close, p) },
            tr("Application not approved"), p.primary,
            reviewerNote?.takeIf { it.isNotBlank() }?.let { tr("Reviewer's note: {0}", it) }) {
            OutlinedButton(onClick = onApply, border = BorderStroke(1.dp, p.accent)) { Text(tr("Reapply"), color = p.accent) }
        }
        VolunteerState.NOT_APPLIED -> BadgeCard(p, modifier, { StatusCircle(p.accent, Icons.Rounded.PersonAdd, p) },
            tr("Become a verified volunteer"), p.primary,
            tr("Send your documents so Maitri can ask you to help people nearby.")) {
            OutlinedButton(onClick = onApply, border = BorderStroke(1.dp, p.accent)) { Text(tr("Volunteer application"), color = p.accent) }
        }
    }
}

@Composable
private fun StatusCircle(color: Color, icon: ImageVector, p: MaitriPalette) {
    Box(Modifier.size(36.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(24.dp), tint = if (p.dark) p.background else Color.White)
    }
}

@Composable
private fun BadgeCard(p: MaitriPalette, modifier: Modifier, icon: @Composable () -> Unit, title: String, titleColor: Color,
                      detail: String?, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().background(p.card, RoundedCornerShape(20.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, Modifier.semantics { heading() }, color = titleColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (detail != null) Text(detail, color = p.secondary, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        if (action != null) { Spacer(Modifier.height(10.dp)); action() }
    }
}
