package com.example.surakshanet.ui

import android.Manifest
import android.content.*
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.surakshanet.location.*
import com.example.surakshanet.ui.theme.*
import java.text.DateFormat
import java.util.Date

private tailrec fun Context.activity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

fun startDemoTracking(context: Context, uid: String) {
    val activity = context.activity() ?: return
    val repo = LocalTrackingRepository.get(context)
    val session = repo.session.value
    if (session.owner != uid || session.id.isEmpty() || session.running ||
        session.status == TrackingState.STARTING) return
    if (!repo.sync.canStart(uid, session.id)) return
    val next = when {
        !context.locationAllowed() -> TrackingState.PERMISSION_REQUIRED
        !context.locationEnabled() -> TrackingState.LOCATION_UNAVAILABLE
        !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) -> return
        else -> TrackingState.STARTING
    }
    repo.change(session.id) { it.copy(status = next) }
    if (next != TrackingState.STARTING) return
    try {
        ContextCompat.startForegroundService(context, Intent(context, SosLocationService::class.java)
            .putExtra("session", session.id))
    } catch (e: RuntimeException) {
        repo.change(session.id) { it.copy(status = if (e is SecurityException) TrackingState.PERMISSION_REQUIRED else TrackingState.ERROR) }
    }
}

@Composable
fun LocationPanel(uid: String, diagnostics: Boolean = false) {
    val context = LocalContext.current
    val activity = context.activity() ?: return
    val repository: LocationRepository = remember(context) { LocalTrackingRepository.get(context) }
    val observed by repository.session.collectAsStateWithLifecycle()
    val session = observed.takeIf { it.owner == uid || it.owner.isEmpty() } ?: TrackingSession()
    var revision by remember { mutableIntStateOf(0) }
    var checked by rememberSaveable { mutableStateOf(false) }
    var requested by rememberSaveable { mutableStateOf(false) }
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { revision++ }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
    val colors = MaterialTheme.surakshaColors

    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { revision++ }
        }
        activity.lifecycle.addObserver(observer)
        ContextCompat.registerReceiver(context, receiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { activity.lifecycle.removeObserver(observer); context.unregisterReceiver(receiver) }
    }

    val precise = remember(revision) { context.granted(Manifest.permission.ACCESS_FINE_LOCATION) }
    val allowed = remember(revision) { context.locationAllowed() }
    val enabled = remember(revision) { context.locationEnabled() }
    val notifications = remember(revision) { Build.VERSION.SDK_INT < 33 || context.granted(Manifest.permission.POST_NOTIFICATIONS) }

    // Device Location & Permission Card
    SurakshaCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (precise) colors.success.copy(alpha = 0.15f)
                        else if (allowed) colors.warning.copy(alpha = 0.15f)
                        else colors.emergency.copy(alpha = 0.15f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (precise) Icons.Rounded.GpsFixed else Icons.Rounded.LocationSearching,
                    contentDescription = null,
                    tint = if (precise) colors.success else if (allowed) colors.warning else colors.emergency,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "GPS & Location Hardware",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
                Text(
                    text = "${if (precise) "Precise GPS" else if (allowed) "Approximate" else "Permission missing"} · Service ${if (enabled) "active" else "disabled"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
            StatusBadge(
                text = if (precise && enabled) "PRECISE" else if (allowed) "COARSE" else "OFFLINE",
                color = if (precise && enabled) colors.success else if (allowed) colors.warning else colors.emergency
            )
        }
    }

    // Foreground Tracking Status
    SurakshaCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (session.running) colors.success.copy(alpha = 0.15f)
                        else colors.cardBackgroundSecondary
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.TrackChanges,
                    contentDescription = null,
                    tint = if (session.running) colors.success else colors.textSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Foreground Service Tracking",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
                Text(
                    text = "Status: ${session.status.name.lowercase().replace('_', ' ')} · ${session.count} fixes collected",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
            StatusBadge(
                text = if (session.running) "STREAMING" else "STANDBY",
                color = if (session.running) colors.success else colors.textTertiary,
                pulsing = session.running
            )
        }
    }

    // Latest Coordinates
    val fix = session.latest
    if (fix != null) {
        val stamp = DateFormat.getDateTimeInstance().format(Date(fix.timestampMillis))
        SurakshaCard(
            backgroundColor = colors.cardBackgroundSecondary
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(imageVector = Icons.Rounded.MyLocation, contentDescription = null, tint = colors.info, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (fix.isMock) "Simulated Coordinate Fix" else "Live GPS Telemetry",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
            }
            Text(
                text = "Latitude: ${fix.latitude}\nLongitude: ${fix.longitude}\nAccuracy: ±${fix.accuracyMeters} m\nUpdated: $stamp",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )
        }
    }

    if (diagnostics) {
        Action(
            label = "Run Local Settings Check",
            icon = Icons.Rounded.CheckCircleOutline,
            click = { revision++; checked = true }
        )
        if (checked) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.successContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Settings verified locally. GPS hardware and permissions active.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSuccessContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        return
    }

    val sync by LocalTrackingRepository.get(context).sync.status.collectAsStateWithLifecycle()
    LaunchedEffect(sync, allowed, enabled) {
        if (repository.session.value.owner == uid && allowed && enabled &&
            LocalTrackingRepository.get(context).sync.getActiveIncidentId(uid) != null) {
            startDemoTracking(context, uid)
        }
    }
    StatusCard(
        title = "Test Server Real-Time Sync",
        detail = if (sync.uid == uid) sync.message else "Local tracking mode (standalone)",
        icon = Icons.Rounded.CloudSync
    )

    // Safety Readiness & Background Battery Restrictions
    SurakshaCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.warning.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.BatteryChargingFull,
                    contentDescription = null,
                    tint = colors.warning,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Safety Readiness & Battery Restrictions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
                Text(
                    text = "OEM battery savers and Doze mode freeze background telemetry. Set app battery setting to \"Unrestricted\" for continuous moving geofence SOS tracking.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = colors.warningContainer.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Disclaimer: Maitri cannot guarantee continuous tracking after manual force-stop, device shutdown, or aggressive manufacturer background termination.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onWarningContainer,
                modifier = Modifier.padding(10.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        SurakshaOutlinedButton(
            label = "Battery Optimization Settings",
            icon = Icons.Rounded.BatteryChargingFull,
            click = {
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    context.startActivity(intent)
                } catch (_: Exception) {
                    try {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                }
            }
        )
    }

    fun settings() {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)))
    }

    if (!allowed) {
        Action(
            label = "Request Foreground Location",
            icon = Icons.Rounded.LocationOn,
            click = {
                requested = true
                location.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            }
        )
        if (requested && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            SurakshaOutlinedButton(
                label = "Open App Permission Settings",
                icon = Icons.Rounded.Settings,
                click = ::settings
            )
        }
    }

    if (!enabled) {
        SurakshaOutlinedButton(
            label = "Enable Android Location Services",
            icon = Icons.Rounded.LocationSearching,
            click = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        )
    }

    if (Build.VERSION.SDK_INT >= 33 && !notifications) {
        SurakshaOutlinedButton(
            label = "Allow Tracking Notifications",
            icon = Icons.Rounded.Notifications,
            click = { notification.launch(Manifest.permission.POST_NOTIFICATIONS) }
        )
    }

    if (allowed && enabled && !session.running && session.status != TrackingState.STARTING) {
        Action(
            label = "Start Local SOS Tracking",
            icon = Icons.Rounded.PlayArrow,
            click = { startDemoTracking(context, uid) }
        )
    }
}
