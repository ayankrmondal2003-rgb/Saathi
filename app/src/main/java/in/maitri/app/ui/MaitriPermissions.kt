package `in`.maitri.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.NotificationsActive

import androidx.lifecycle.repeatOnLifecycle

import `in`.maitri.app.tr

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import `in`.maitri.app.R
import `in`.maitri.app.R.string as S

/** What Android will do for a row, derived only from live platform state. */
internal enum class AccessState(val label: Int) {
    ALLOWED(S.perm_allowed),
    APPROXIMATE(S.perm_approximate),
    /** Not granted, but Android will still show its runtime prompt. */
    ASK(S.perm_ask),
    /** Denied permanently or switched off in system settings; only Settings can change it. */
    BLOCKED(S.perm_not_allowed),
    /** Maitri does not declare this permission; the feature uses a system picker or another app. */
    NOT_NEEDED(S.perm_not_needed)
}

internal fun accessState(declared: Boolean, granted: Boolean, asked: Boolean, rationale: Boolean,
                         approximate: Boolean = false, systemEnabled: Boolean = true): AccessState = when {
    !declared -> AccessState.NOT_NEEDED
    granted && !systemEnabled -> AccessState.BLOCKED
    granted -> AccessState.ALLOWED
    approximate -> AccessState.APPROXIMATE
    !asked || rationale -> AccessState.ASK
    else -> AccessState.BLOCKED
}

internal enum class PermissionRow(val title: Int, val detail: Int, val permissions: List<String>, val info: Int? = null) {
    LOCATION(S.perm_location, S.perm_location_sub,
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
    NOTIFICATIONS(S.perm_notifications, S.perm_notifications_sub,
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()),
    CONTACTS(S.perm_contacts, S.perm_contacts_sub, listOf(Manifest.permission.READ_CONTACTS), S.perm_contacts_info),
    MICROPHONE(S.perm_mic, S.perm_mic_sub, listOf(Manifest.permission.RECORD_AUDIO), S.perm_mic_info),
    CAMERA(S.perm_camera, S.perm_camera_sub, listOf(Manifest.permission.CAMERA), S.perm_camera_info)
}

private const val PERMISSION_PREFS = "maitri_permission_prefs"

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

private fun Context.declared(permission: String): Boolean = runCatching {
    @Suppress("DEPRECATION")
    packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().contains(permission)
}.getOrDefault(false)

private fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

internal fun readAccess(context: Context, row: PermissionRow): AccessState {
    val prefs = context.getSharedPreferences(PERMISSION_PREFS, 0)
    val activity = context.activity()
    val primary = row.permissions.firstOrNull()
    val asked = row.permissions.any { prefs.getBoolean("asked.$it", false) }
    val rationale = activity != null && row.permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
    return when (row) {
        PermissionRow.LOCATION -> accessState(context.declared(Manifest.permission.ACCESS_FINE_LOCATION),
            context.granted(Manifest.permission.ACCESS_FINE_LOCATION), asked, rationale,
            approximate = context.granted(Manifest.permission.ACCESS_COARSE_LOCATION))
        PermissionRow.NOTIFICATIONS -> accessState(declared = primary == null || context.declared(primary),
            granted = primary == null || context.granted(primary), asked = asked, rationale = rationale,
            systemEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled())
        else -> accessState(context.declared(requireNotNull(primary)), context.granted(primary), asked, rationale)
    }
}

/** Screen 12, placed on the approved Light/Dark crop geometry. */
@Composable
fun MaitriPermissions(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val p = maitriPalette()
    val context = LocalContext.current
    val strings = settingsResources()
    val preview = LocalInspectionMode.current
    var refresh by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf<Int?>(null) }
    var battery by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val states = remember(refresh, preview) {
        PermissionRow.entries.associateWith { row ->
            if (preview) when (row) {
                PermissionRow.LOCATION, PermissionRow.NOTIFICATIONS -> AccessState.ALLOWED
                else -> AccessState.NOT_NEEDED
            } else readAccess(context, row)
        }
    }

    fun open(intent: Intent) = try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }
    fun appSettings() = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    fun onRow(row: PermissionRow, state: AccessState) {
        when {
            state == AccessState.NOT_NEEDED -> info = row.info
            state == AccessState.ASK || (state == AccessState.APPROXIMATE && readAccessCanPrompt(context, row)) -> {
                val prefs = context.getSharedPreferences(PERMISSION_PREFS, 0).edit()
                row.permissions.forEach { prefs.putBoolean("asked.$it", true) }
                prefs.apply()
                request.launch(row.permissions.toTypedArray())
            }
            row == PermissionRow.NOTIFICATIONS -> open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            else -> appSettings()
        }
    }

    // Android 14+: without "full-screen notifications", an SOS request can't show on the lock screen. A row between
    // Microphone and Camera (board y 572) appears only while it's off; it's re-checked when the user comes back.
    var fullScreenOff by remember { mutableStateOf(!preview && Build.VERSION.SDK_INT >= 34 &&
        !`in`.maitri.app.notification.NotificationHelper.canUseFullScreen(context)) }
    val permissionsLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(permissionsLifecycle) {
        permissionsLifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            fullScreenOff = !preview && Build.VERSION.SDK_INT >= 34 && !`in`.maitri.app.notification.NotificationHelper.canUseFullScreen(context)
        }
    }
    val gap = if (fullScreenOff) 80 else 0
    SettingsBoard(if (p.dark) R.drawable.maitri_dark_12 else R.drawable.maitri_light_12, 788, modifier,
        gapAt = if (fullScreenOff) 572 else null, gapHeight = gap) {
        SettingsHeader(strings.getString(S.settings_permissions), strings.getString(S.settings_profile),
            strings.getString(S.settings_back), onBack)
        SettingsText(strings.getString(S.perm_banner), 40, 156, 310, 30, 20, p.primary, true)
        SettingsText(strings.getString(S.perm_banner_sub), 40, 189, 208, 34, 11, p.secondary, line = 15)
        PermissionRow.entries.forEachIndexed { index, row ->
            val state = states.getValue(row)
            val top = 252 + index * 80 + if (index * 80 + 252 >= 572) gap else 0
            Box(settingsSlot(24, top, 342, 80).testTag("permission_${row.name.lowercase()}")
                .clickable(role = Role.Button, onClickLabel = strings.getString(row.title)) { onRow(row, state) }) {
                RowText(strings.getString(row.title), 11, 20, 14, p.primary, FontWeight.SemiBold)
                RowText(strings.getString(row.detail), 35, 18, 11, p.secondary, FontWeight.Normal)
                RowText(strings.getString(state.label), 57, 16, 10,
                    if (state == AccessState.ALLOWED || state == AccessState.APPROXIMATE) p.accent else p.secondary, FontWeight.SemiBold)
            }
        }
        if (fullScreenOff) Box(settingsSlot(24, 572, 342, 80).background(p.surface).testTag("permission_full_screen")
            .clickable(role = Role.Button) { `in`.maitri.app.notification.NotificationHelper.openFullScreenSettings(context) }) {
            Box(Modifier.padding(start = 16.dp, end = 16.dp).fillMaxWidth().height(1.dp).background(p.divider))
            Icon(androidx.compose.material.icons.Icons.Rounded.NotificationsActive, null,
                Modifier.offset(16.dp, 14.dp).size(20.dp), tint = p.accent)
            RowText(tr("Show SOS requests on the lock screen"), 11, 20, 14, p.primary, FontWeight.SemiBold)
            RowText(tr("So a nearby request can wake your screen"), 35, 18, 11, p.secondary, FontWeight.Normal)
            RowText(tr("Not allowed · Tap to allow"), 57, 16, 10, p.secondary, FontWeight.SemiBold)
        }
        // In the hint's place: alerts only arrive on time if the phone doesn't put Maitri to sleep.
        SettingsText(tr("Keep Maitri alerts on time ›"), 24, 682 + gap, 342, 22, 12, p.accent, bold = true, center = true,
            action = { battery = true })
        SettingsText(strings.getString(S.perm_open), 24, 724 + gap, 342, 52, 15,
            if (p.dark) p.background else Color.White, bold = true, center = true, action = ::appSettings)
    }

    if (battery) {
        val restricted = !preview && batteryRestricted(context)
        AlertDialog(modifier = Modifier.dialogEnter(), onDismissRequest = { battery = false },
            title = { Text(tr("Keep Maitri alerts on time")) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (restricted) tr("Battery optimisation is on for Maitri, so your phone may delay SOS requests and circle alerts.")
                    else tr("Battery optimisation is off for Maitri. Good."), fontWeight = FontWeight.SemiBold)
                Text(tr("On your {0} phone:", Build.MANUFACTURER.replaceFirstChar { it.uppercase() }))
                batteryTips(Build.MANUFACTURER).forEach { Text("• $it") }
                if (!preview && !`in`.maitri.app.notification.NotificationHelper.canUseFullScreen(context)) {
                    Text(tr("Full-screen alerts are off, so an SOS request can't wake your screen."), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { `in`.maitri.app.notification.NotificationHelper.openFullScreenSettings(context) }) {
                        Text(tr("Allow full-screen alerts"))
                    }
                }
                Text(strings.getString(S.perm_hint), color = p.secondary)
            } },
            confirmButton = { TextButton(onClick = {
                battery = false
                open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }) { Text(tr("Open battery settings")) } },
            dismissButton = { TextButton(onClick = { battery = false }) { Text(strings.getString(S.settings_close)) } })
    }
    info?.let { id ->
        AlertDialog(modifier = Modifier.dialogEnter(), onDismissRequest = { info = null }, text = { Text(strings.getString(id)) },
            confirmButton = { TextButton(onClick = { info = null }) { Text(strings.getString(S.settings_close)) } })
    }
}

private fun readAccessCanPrompt(context: Context, row: PermissionRow): Boolean {
    val activity = context.activity() ?: return false
    val asked = row.permissions.any { context.getSharedPreferences(PERMISSION_PREFS, 0).getBoolean("asked.$it", false) }
    return !asked || row.permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
}

@Composable
private fun RowText(text: String, y: Int, height: Int, size: Int, color: Color, weight: FontWeight) {
    Box(Modifier.offset(52.dp, y.dp).width(256.dp)) {
        FitText(text, Modifier, color, size.toFloat(), height.toFloat(), weight)
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun MaitriPermissionsLightPreview() {
    `in`.maitri.app.ui.theme.MaitriTheme { MaitriPermissions(onBack = {}) }
}

@Preview(widthDp = 390, heightDp = 844, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MaitriPermissionsDarkPreview() {
    `in`.maitri.app.ui.theme.MaitriTheme { MaitriPermissions(onBack = {}) }
}

internal fun batteryRestricted(context: Context): Boolean =
    context.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) != true

/** Where each phone maker hides "let this app run in the background". */
internal fun batteryTips(manufacturer: String): List<String> = when (manufacturer.lowercase()) {
    "samsung" -> listOf(tr("Settings › Apps › Maitri › Battery › Unrestricted."),
        tr("Settings › Battery › Background usage limits › Never sleeping apps › add Maitri."))
    "xiaomi", "redmi", "poco" -> listOf(tr("Settings › Apps › Manage apps › Maitri › Autostart: on."),
        tr("Same screen › Battery saver › No restrictions."))
    "oppo", "realme", "oneplus" -> listOf(tr("Settings › Battery › More settings (or App battery management) › Maitri › Allow background activity."),
        tr("Settings › Apps › Maitri › Auto launch: on."))
    "vivo", "iqoo" -> listOf(tr("Settings › Battery › Background power consumption management › Maitri › Allow."),
        tr("i Manager › App manager › Autostart › Maitri: on."))
    else -> listOf(tr("Settings › Apps › Maitri › Battery › Unrestricted (or \"Don't optimise\")."))
}
