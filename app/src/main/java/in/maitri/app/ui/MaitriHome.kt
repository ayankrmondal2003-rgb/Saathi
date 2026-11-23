package `in`.maitri.app.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import `in`.maitri.app.R
import `in`.maitri.app.data.DemoState
import `in`.maitri.app.data.IncidentStatus
import `in`.maitri.app.ui.theme.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class MaitriPalette(val dark: Boolean) {
    val background = Color(if (dark) 0xFF19151B else 0xFFFFF9FA)
    val primary = Color(if (dark) 0xFFFAEFF5 else 0xFF40303A)
    val secondary = Color(if (dark) 0xFFC3ADBA else 0xFF7D6771)
    val accent = Color(if (dark) 0xFFFF9CBE else 0xFFB82055)
    val card = Color(if (dark) 0xFF36232F else 0xFFFCE8ED)
    val surface = Color(if (dark) 0xFF28212A else 0xFFFFFFFF)
    val divider = Color(if (dark) 0xFF48323F else 0xFFF2DCE3)
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
fun MaitriHome(state: DemoState, start: (Boolean) -> Unit, contacts: () -> Unit) {
    val p = maitriPalette()
    val context = LocalContext.current
    var confirm by rememberSaveable { mutableStateOf(false) }
    var share by rememberSaveable { mutableStateOf(false) }
    var sharingKind by rememberSaveable { mutableStateOf<String?>(null) }
    val active = state.incident?.status == IncidentStatus.ACTIVE
    MaitriHomeContent(p, state.contacts.size, active,
        onSos = { if (active) start(false) else confirm = true },
        contacts = contacts,
        notifications = {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        },
        trip = { sharingKind = "TRIP" },
        checkIn = { sharingKind = "CHECK_IN" })
    if (confirm) {
        AlertDialog(onDismissRequest = { confirm = false; share = false },
            containerColor = p.surface, titleContentColor = p.primary, textContentColor = p.secondary,
            title = { Text("Activate test SOS?") },
            text = {
                Column {
                    Text("This is a controlled test, not emergency dispatch. Server activation is confirmed separately after connection.")
                    if (`in`.maitri.app.BuildConfig.API_BASE_URL.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = share, onCheckedChange = { share = it })
                            Text("Share GPS with the test server and accepted responders")
                        }
                    }
                    Text(if (share) "Sharing begins only for your authorized test session." else "Without sharing, this is a local-only practice session.")
                }
            },
            confirmButton = { TextButton(onClick = {
                if (confirm) { confirm = false; val consent = share; share = false; start(consent) }
            }) { Text("Start test SOS", color = p.accent) } },
            dismissButton = { TextButton(onClick = { confirm = false; share = false }) { Text("Cancel", color = p.accent) } })
    }
    sharingKind?.let { kind -> MaitriSharingDialog(kind) { sharingKind = null } }
}

@Composable
internal fun MaitriHomeContent(p: MaitriPalette, contactCount: Int, active: Boolean,
    onSos: () -> Unit, contacts: () -> Unit, notifications: () -> Unit,
    trip: () -> Unit, checkIn: () -> Unit) {
    Column(Modifier.fillMaxSize().background(p.background).statusBarsPadding()
        .verticalScroll(rememberScrollState())
        .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = LocalFloatingNavBarPadding.current)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.maitri_logo), "maitri", Modifier.size(46.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("A LITTLE PEACE OF MIND", color = p.accent, fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("Here for you.", color = p.primary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = notifications, modifier = Modifier.size(44.dp).background(p.surface, CircleShape)) {
                Icon(painterResource(R.drawable.maitri_bell), "Notifications", tint = p.primary, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(30.dp))
        Column(Modifier.fillMaxWidth().background(p.card, RoundedCornerShape(28.dp)).padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (active) "Your SOS session is open." else "You’re not on your own.", color = p.primary, fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(if (active) "Open your session for its confirmed status." else "Reach your circle when you need them.",
                color = p.secondary, fontSize = 12.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            MaitriHoldButton(p, onSos)
            Spacer(Modifier.height(14.dp))
            Text(if (active) "Hold for 3 seconds to open your SOS" else "Hold for 3 seconds to alert your circle",
                color = p.accent, fontSize = 11.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MaitriQuickAction("Share my trip", "Let someone follow along", R.drawable.maitri_pin, p, Modifier.weight(1f), trip)
            MaitriQuickAction("Check in", "Set a time to check in", R.drawable.maitri_clock, p, Modifier.weight(1f), checkIn)
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Your trusted circle", Modifier.weight(1f), color = p.primary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = contacts) { Text("Manage", color = p.accent, fontSize = 12.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().background(p.card, RoundedCornerShape(20.dp))
            .clickable(onClick = contacts).padding(14.dp).heightIn(min = 57.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(Color(if(p.dark) 0xFF2D222B else 0xFFFFF5F8), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.maitri_people), null, tint = p.accent)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Keep your people close", color = p.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(if(contactCount == 0) "Add your first trusted contact" else "$contactCount saved contacts",
                    color = p.secondary, fontSize = 11.sp)
            }
            Icon(painterResource(R.drawable.maitri_plus), "Add trusted contact", tint = p.accent,
                modifier = Modifier.size(32.dp).background(p.surface, CircleShape).padding(4.dp))
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun MaitriQuickAction(title: String, subtitle: String, icon: Int, p: MaitriPalette, modifier: Modifier, click: () -> Unit) {
    Column(modifier.background(p.surface, RoundedCornerShape(20.dp)).clickable(onClick = click)
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
internal fun MaitriHoldButton(p: MaitriPalette, onComplete: () -> Unit) {
    val callback by rememberUpdatedState(onComplete)
    val haptic = LocalHapticFeedback.current
    var progress by remember { mutableFloatStateOf(0f) }
    var completed by remember { mutableStateOf(false) }
    Box(Modifier.size(172.dp).semantics {
        role = Role.Button
        contentDescription = "Hold SOS for three seconds"
        stateDescription = if(progress > 0) "Hold progress ${(progress * 100).toInt()} percent" else "Ready"
        onClick("Open test SOS confirmation") { callback(); true }
    }.pointerInput(Unit) {
        detectTapGestures(onPress = {
            coroutineScope {
                completed = false
                val started = SystemClock.elapsedRealtime()
                val hold = launch {
                    while (true) {
                        val elapsed = SystemClock.elapsedRealtime() - started
                        progress = (elapsed / 3000f).coerceIn(0f, 1f)
                        if (elapsed >= 3000L) {
                            completed = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            callback()
                            break
                        }
                        delay(16)
                    }
                }
                try { tryAwaitRelease() } finally { hold.cancel(); progress = 0f }
            }
        }, onTap = { if (!completed) callback() })
    }, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().background(Color(if(p.dark) 0xFF4A273B else 0xFFFFDDE7), CircleShape))
        Box(Modifier.size(150.dp).background(Color(if(p.dark) 0xFF75314F else 0xFFFFB0C8), CircleShape))
        Column(Modifier.size(128.dp).background(Color(0xFFC91F59), CircleShape),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(painterResource(R.drawable.maitri_shield), null, tint = Color.White, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(8.dp))
            Text("SOS", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("Get help", color = Color.White, fontSize = 12.sp)
        }
        if (progress > 0f) CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(168.dp),
            color = p.accent, strokeWidth = 3.dp)
    }
}

@Composable
fun MaitriBottomBar(currentRoute: String?, onTabSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    val p = maitriPalette()
    val items = listOf(Triple("Home", "Home", R.drawable.maitri_home),
        Triple("Contacts", "Circle", R.drawable.maitri_circle),
        Triple("Responder", "Responder", R.drawable.maitri_responder),
        Triple("Profile", "Profile", R.drawable.maitri_profile))
    Column(modifier.fillMaxWidth().background(p.background).padding(horizontal = 24.dp)) {
        HorizontalDivider(color = p.divider, thickness = 1.dp)
        Row(Modifier.fillMaxWidth().height(62.dp)) {
            items.forEach { (route, label, icon) ->
                val selected = currentRoute == route
                Column(Modifier.weight(1f).fillMaxHeight().selectable(selected, onClick = { onTabSelected(route) })
                    .padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(48.dp,29.dp).background(if(selected) p.card else Color.Transparent, RoundedCornerShape(14.5.dp)),
                        contentAlignment = Alignment.Center) {
                        Icon(painterResource(icon), null, tint = if(selected) p.accent else p.secondary, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(label, color = if(selected) p.accent else p.secondary, fontSize = 10.sp)
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
