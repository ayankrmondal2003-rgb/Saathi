package `in`.maitri.app.ui

import androidx.compose.runtime.withFrameNanos

import `in`.maitri.app.tr

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import `in`.maitri.app.R
import `in`.maitri.app.data.DemoState
import `in`.maitri.app.data.IncidentStatus
import `in`.maitri.app.network.SyncProblem
import `in`.maitri.app.ui.theme.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class MaitriPalette(val dark: Boolean) {
    val background = Color(if (dark) 0xFF19151B else 0xFFFFF9FA)
    val primary = Color(if (dark) 0xFFFAEFF5 else 0xFF40303A)
    val secondary = Color(if (dark) 0xFFC3ADBA else 0xFF725C66)  // light = @color/maitri_secondary
    val accent = Color(if (dark) 0xFFFF9CBE else 0xFFB82055)
    val card = Color(if (dark) 0xFF36232F else 0xFFF9DCE6)  // light = @color/maitri_blush
    /** Soft fills (chips, subtle backgrounds); light = @color/maitri_blush_subtle. */
    val subtle = Color(if (dark) 0xFF241B25 else 0xFFFBE7EE)
    val surface = Color(if (dark) 0xFF28212A else 0xFFFFFFFF)
    val divider = Color(if (dark) 0xFF48323F else 0xFFF2DCE3)
    /** Text and icons on an accent fill: white on raspberry (6.2:1), dark on pink (9.2:1). White on pink is 1.9:1. */
    val onAccent = if (dark) background else Color.White
    /** Approved-volunteer green: 0F7A4D on white is 5.4:1; 6EE7A8 on the dark surface is 10.2:1. */
    val verified = Color(if (dark) 0xFF6EE7A8 else 0xFF0F7A4D)
    /** Under-review amber: B45309 on white is 5.0:1; FCD34D on the dark surface is 10.9:1. */
    val pending = Color(if (dark) 0xFFFCD34D else 0xFFB45309)
}
@Composable
internal fun maitriPalette(): MaitriPalette {
    val dark = when (LocalThemeController.current.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    return MaitriPalette(dark)
}

@Composable
fun MaitriHome(state: DemoState, start: (Boolean) -> Unit, contacts: () -> Unit,
               session: @Composable ColumnScope.() -> Unit = {}, onInbox: (() -> Unit)? = null, unread: Int = 0) {
    val p = maitriPalette()
    val context = LocalContext.current
    var confirm by rememberSaveable { mutableStateOf(false) }
    var sharingKind by rememberSaveable { mutableStateOf<String?>(null) }
    val active = state.incident?.status == IncidentStatus.ACTIVE
    MaitriHomeContent(p, state.contacts.size, active,
        onSos = { start(true) },
        onSosTap = { if (active) start(true) else confirm = true },
        contacts = contacts,
        notifications = onInbox ?: {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        },
        unread = unread,
        trip = { sharingKind = "TRIP" },
        checkIn = { sharingKind = "CHECK_IN" },
        session = session,
        beta = `in`.maitri.app.BuildConfig.VERSION_NAME.contains("beta"))
    if (confirm) {
        // Reached by a quick tap or a screen reader, where a 3-second hold isn't practical.
        AlertDialog(modifier = Modifier.dialogEnter(), onDismissRequest = { confirm = false },
            containerColor = p.surface, titleContentColor = p.primary, textContentColor = p.secondary,
            title = { Text(tr("Send SOS now?")) },
            text = { Text(tr("Your circle and approved volunteers nearby get your live location. Maitri doesn't call 112 for you.")) },
            confirmButton = { TextButton(onClick = { if (confirm) { confirm = false; start(true) } }) {
                Text(tr("Send SOS"), color = p.accent, fontWeight = FontWeight.SemiBold) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("Cancel"), color = p.accent) } })
    }
    sharingKind?.let { kind -> MaitriSharingDialog(kind) { sharingKind = null } }
}

@Composable
internal fun MaitriHomeContent(p: MaitriPalette, contactCount: Int, active: Boolean,
    onSos: () -> Unit, contacts: () -> Unit, notifications: () -> Unit,
    trip: () -> Unit, checkIn: () -> Unit, session: @Composable ColumnScope.() -> Unit = {},
    onSosTap: () -> Unit = onSos, beta: Boolean = false, unread: Int = 0) {
    Column(Modifier.fillMaxSize().background(p.background).statusBarsPadding()
        .verticalScroll(rememberScrollState())
        .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = LocalFloatingNavBarPadding.current)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.maitri_logo), "maitri", Modifier.size(46.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The tagline gives way first, so the Beta tag keeps its width with large text.
                    Text(tr("A LITTLE PEACE OF MIND"), Modifier.weight(1f, fill = false), color = p.accent, fontSize = 10.sp,
                        letterSpacing = 1.3.sp, fontWeight = FontWeight.SemiBold)
                    if (beta) {
                        Spacer(Modifier.width(8.dp))
                        Text("BETA", Modifier.wrapContentWidth(unbounded = true).background(p.card, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp)
                            .semantics { contentDescription = tr("Beta version") },
                            color = p.accent, fontSize = 9.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, softWrap = false)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(tr("Here for you."), color = p.primary, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            }
            Box {
                IconButton(onClick = notifications, modifier = Modifier.size(44.dp).background(p.surface, CircleShape)) {
                    Icon(painterResource(R.drawable.maitri_bell),
                        if (unread > 0) tr("Notifications, {0} unread", unread) else tr("Notifications"), tint = p.primary, modifier = Modifier.size(24.dp))
                }
                if (unread > 0) Text(if (unread > 9) "9+" else unread.toString(),
                    Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).background(p.accent, CircleShape)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    color = if (p.dark) p.background else Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(30.dp))
        Column(Modifier.fillMaxWidth().background(p.card, RoundedCornerShape(28.dp)).padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (active) tr("Your SOS session is open.") else tr("You’re not on your own."), color = p.primary, fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(if (active) tr("Open your session for its confirmed status.") else tr("Reach your circle when you need them."),
                color = p.secondary, fontSize = 11.5.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(13.dp))
            MaitriHoldButton(p, onSos, onSosTap)
            Spacer(Modifier.height(8.dp))
            Text(if (active) tr("Hold for 3 seconds to open your SOS") else tr("Hold for 3 seconds to alert your circle"),
                color = p.accent, fontSize = 11.sp, textAlign = TextAlign.Center)
            session()
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MaitriQuickAction(tr("Share my trip"), tr("Let someone follow along"), R.drawable.maitri_pin, p, Modifier.weight(1f).fillMaxHeight(), trip)
            MaitriQuickAction(tr("Check in"), tr("Set a time to check in"), R.drawable.maitri_clock, p, Modifier.weight(1f).fillMaxHeight(), checkIn)
        }
        Spacer(Modifier.height(27.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Your trusted circle"), Modifier.weight(1f), color = p.primary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            // A text link as in the design; the padding keeps a 48dp-wide touch target without a taller row.
            Text(tr("Manage"), Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = contacts)
                .padding(horizontal = 8.dp, vertical = 4.dp), color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().pressScale().background(p.card, RoundedCornerShape(20.dp))
            .clickable(onClick = contacts).padding(14.dp).heightIn(min = 57.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(Color(if(p.dark) 0xFF2D222B else 0xFFFFF5F8), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.maitri_people), null, tint = p.accent)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("Keep your people close"), color = p.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(when (contactCount) { 0 -> tr("Add your first trusted contact"); 1 -> tr("1 saved contact")
                    else -> tr("{0} saved contacts", contactCount) },
                    color = p.secondary, fontSize = 11.sp)
            }
            Icon(painterResource(R.drawable.maitri_plus), tr("Add trusted contact"), tint = p.accent,
                modifier = Modifier.size(32.dp).background(p.surface, CircleShape).padding(4.dp))
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun MaitriQuickAction(title: String, subtitle: String, icon: Int, p: MaitriPalette, modifier: Modifier, click: () -> Unit) {
    Column(modifier.pressScale().background(p.surface, RoundedCornerShape(20.dp)).clickable(onClick = click)
        .heightIn(min = 114.dp).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Icon(painterResource(icon), null, tint = p.accent, modifier = Modifier.size(23.dp))
            Icon(painterResource(R.drawable.maitri_arrow), null, tint = p.accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, color = p.primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Text(subtitle, color = p.secondary, fontSize = 10.sp)
    }
}

@Composable
internal fun MaitriHoldButton(p: MaitriPalette, onComplete: () -> Unit, onTap: () -> Unit = onComplete) {
    val callback by rememberUpdatedState(onComplete)
    val tapCallback by rememberUpdatedState(onTap)
    val haptic = LocalHapticFeedback.current
    var progress by remember { mutableFloatStateOf(0f) }
    var completed by remember { mutableStateOf(false) }
    Box(Modifier.size(172.dp).semantics {
        role = Role.Button
        contentDescription = tr("Hold SOS for three seconds")
        stateDescription = if(progress > 0) tr("Hold progress {0} percent", (progress * 100).toInt()) else tr("Ready")
        onClick(tr("Send SOS")) { tapCallback(); true }
    }.pointerInput(Unit) {
        detectTapGestures(onPress = {
            coroutineScope {
                completed = false
                val hold = launch {
                    var startNanos = -1L
                    var ticks = 0
                    while (true) {
                        // One update per displayed frame (60 or 120 Hz), instead of a fixed 16 ms timer.
                        val elapsed = withFrameNanos { now -> if (startNanos < 0) startNanos = now; (now - startNanos) / 1_000_000 }
                        progress = (elapsed / 3000f).coerceIn(0f, 1f)
                        if (elapsed >= (ticks + 1) * 1000L && ticks < 2) {
                            ticks++
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                        if (elapsed >= 3000L) {
                            completed = true
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            callback()
                            break
                        }
                    }
                }
                try { tryAwaitRelease() } finally { hold.cancel(); progress = 0f }
            }
        }, onTap = { if (!completed) tapCallback() })
    }, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().background(Color(if(p.dark) 0xFF4A273B else 0xFFFFDDE7), CircleShape))
        Box(Modifier.size(150.dp).background(Color(if(p.dark) 0xFF75314F else 0xFFFFB0C8), CircleShape))
        Column(Modifier.size(128.dp).background(Color(0xFFC91F59), CircleShape),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(painterResource(R.drawable.maitri_shield), null, tint = Color.White, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(8.dp))
            Text("SOS", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(tr("Get help"), color = Color.White, fontSize = 11.sp)
        }
        if (progress > 0f) CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(168.dp),
            color = p.accent, strokeWidth = 3.dp)
    }
}

@Composable
fun MaitriBottomBar(currentRoute: String?, onTabSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    val p = maitriPalette()
    val items = listOf(Triple("Home", tr("Home"), R.drawable.maitri_home),
        Triple("Contacts", tr("Circle"), R.drawable.maitri_circle),
        Triple("Responder", tr("Responder"), R.drawable.maitri_responder),
        Triple("Profile", tr("Profile"), R.drawable.maitri_profile))
    // The background runs down behind the system navigation buttons, which are transparent edge-to-edge
    // (3-button navigation on Xiaomi and others); otherwise content shows through under the dock.
    Column(modifier.fillMaxWidth().background(p.background).navigationBarsPadding()) {
        // One continuous 1 dp line, edge to edge, directly on top of the tabs (shared by every tab). The bar's opaque
        // background starts right under it, so scrolled content passes beneath with no gap or second line.
        HorizontalDivider(Modifier.fillMaxWidth(), color = p.divider, thickness = 1.dp)
        // At least the design's 62 dp, taller when large text needs it (the content's bottom padding follows).
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(IntrinsicSize.Min).heightIn(min = 62.dp)) {
            items.forEach { (route, label, icon) ->
                val selected = currentRoute == route
                Column(Modifier.weight(1f).fillMaxHeight().selectable(selected, onClick = { onTabSelected(route) })
                    .padding(top = 8.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(48.dp,29.dp).background(if(selected) p.card else Color.Transparent, RoundedCornerShape(14.5.dp)),
                        contentAlignment = Alignment.Center) {
                        Icon(painterResource(icon), null, tint = if(selected) p.accent else p.secondary, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.height(3.dp))
                    FitText(label, Modifier, if (selected) p.accent else p.secondary, 10f)
                }
            }
        }
    }
}
@Preview(widthDp = 390, heightDp = 766)
@Composable private fun MaitriHomeLightPreview() {
    CompositionLocalProvider(LocalFloatingNavBarPadding provides 80.dp) {
        MaitriHomeContent(MaitriPalette(false), 0, false, {}, {}, {}, {}, {})
    }
}
@Preview(widthDp = 390, heightDp = 766)
@Composable private fun MaitriHomeDarkPreview() {
    CompositionLocalProvider(LocalFloatingNavBarPadding provides 80.dp) {
        MaitriHomeContent(MaitriPalette(true), 0, false, {}, {}, {}, {}, {})
    }
}

/**
 * Under the SOS button: what the session is doing (never an HTTP code) and what the user can do about it.
 * Resume opens screen 08; End opens the Safety PIN on screen 08, or ends a session the server never confirmed.
 */
@Composable
internal fun SosSessionPanel(p: MaitriPalette, active: Boolean, message: String?, problem: SyncProblem?,
    onResume: () -> Unit, onEnd: () -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit) {
    if (!active && message.isNullOrBlank()) return
    Spacer(Modifier.height(12.dp))
    if (!message.isNullOrBlank()) {
        Text(message, Modifier.padding(horizontal = 20.dp).semantics { liveRegion = LiveRegionMode.Polite },
            color = if (problem != null) p.accent else p.secondary, fontSize = 12.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (active) {
            if (problem == SyncProblem.RETRYING) SessionAction(tr("Try again"), p, onRetry)
            SessionAction(tr("Resume SOS"), p, onResume)
            SessionAction(tr("End SOS"), p, onEnd)
        } else if (problem == SyncProblem.ENDED) {
            SessionAction("OK", p, onDismiss)
        }
    }
}

@Composable
private fun SessionAction(label: String, p: MaitriPalette, click: () -> Unit) {
    OutlinedButton(onClick = click, modifier = Modifier.heightIn(min = 48.dp),
        border = BorderStroke(1.dp, p.accent), colors = ButtonDefaults.outlinedButtonColors(contentColor = p.accent),
        contentPadding = PaddingValues(horizontal = 14.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
