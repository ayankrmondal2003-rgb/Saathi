package `in`.maitri.app.ui

import `in`.maitri.app.tr

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import `in`.maitri.app.R
import `in`.maitri.app.R.string as S
import `in`.maitri.app.data.UserProfile
import `in`.maitri.app.ui.theme.LocalThemeController
import `in`.maitri.app.ui.theme.ThemeMode

@Composable
fun MaitriSettings(user: UserProfile?, onNavigate: (String) -> Unit, onLogout: () -> Unit,
                   modifier: Modifier = Modifier) {
    val p = maitriPalette()
    val context = LocalContext.current
    val strings = settingsResources()
    val theme = LocalThemeController.current
    val themeNames = mapOf(ThemeMode.SYSTEM to S.settings_system, ThemeMode.LIGHT to S.settings_light, ThemeMode.DARK to S.settings_dark)
    var dialog by remember { mutableStateOf<Int?>(null) }
    var verified by remember(user?.uid) { mutableStateOf(false) }
    LaunchedEffect(user?.uid) {
        val uid = user?.uid?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        verified = runCatching {
            `in`.maitri.app.network.BackendClient.api.volunteerStatus(`in`.maitri.app.network.ExpectedAccount(uid)).status == "APPROVED"
        }.getOrDefault(false)
    }
    val language = java.util.Locale.forLanguageTag(settingsLanguageCode())
    fun appSettings() = context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)))

    SettingsBoard(if (p.dark) R.drawable.maitri_dark_09 else R.drawable.maitri_light_09, 729, modifier,
        gapAt = KEEP_VIBRATING_Y, gapHeight = KEEP_VIBRATING_HEIGHT,
        footer = {
            TextButton(onClick = onLogout, modifier = Modifier.padding(horizontal = 24.dp)) {
                Text(strings.getString(S.settings_logout), color = p.accent)
            }
            Spacer(Modifier.height(LocalFloatingNavBarPadding.current))
        }) {
        SettingsText(strings.getString(S.settings_eyebrow), 84, 58, 282, 22, 10, p.accent, true)
        SettingsText(strings.getString(S.settings_profile), 84, 80, 284, 36, 25, p.primary, true)
        Row(settingsSlot(40, 156, 222, 28), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.getString(S.settings_your_profile), color = p.primary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (verified) { Spacer(Modifier.width(6.dp)); VerifiedCheck(p, 18.dp) }
        }
        SettingsText(strings.getString(S.settings_details), 40, 186, 222, 23, 11, p.secondary)
        SettingsText(strings.getString(S.settings_edit), 299, 166, 50, 38, 12, p.accent, true, true) { onNavigate("EditProfile") }
        BoardAction(24, 144, 275, 82, strings.getString(S.settings_your_profile)) { onNavigate("EditProfile") }
        SettingsText(strings.getString(S.settings_safety), 24, 249, 342, 28, 18, p.primary, true)
        SettingsText(strings.getString(S.settings_comfort), 24, 478 + KEEP_VIBRATING_HEIGHT, 342, 28, 18, p.primary, true)
        ProfileRow(285, 58, S.settings_permissions, S.settings_permissions_sub) { onNavigate("Permissions") }
        ProfileRow(343, 58, S.settings_vibrations, S.settings_vibrations_sub, strings.getString(S.settings_test)) { onNavigate("TestVibration") }
        KeepVibratingRow(p)
        ProfileRow(401 + KEEP_VIBRATING_HEIGHT, 58, S.settings_pins, S.settings_pins_sub) { onNavigate("SafetyPin") }
        ProfileRow(510 + KEEP_VIBRATING_HEIGHT, 52, S.settings_language, S.settings_language_sub, language.getDisplayLanguage(language).replaceFirstChar { it.titlecase(language) }) { onNavigate("Language") }
        ProfileRow(562 + KEEP_VIBRATING_HEIGHT, 52, S.settings_themes, S.settings_themes_sub, strings.getString(themeNames.getValue(theme.themeMode))) { dialog = S.settings_themes }
        ProfileRow(614 + KEEP_VIBRATING_HEIGHT, 52, S.settings_notifications, S.settings_notifications_sub) {
            try {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            } catch (_: android.content.ActivityNotFoundException) { appSettings() }
        }
        ProfileRow(666 + KEEP_VIBRATING_HEIGHT, 52, S.settings_privacy, S.settings_privacy_sub) { onNavigate("PrivacyData") }
        SettingsText(strings.getString(S.settings_help), 74, 721 + KEEP_VIBRATING_HEIGHT, 290, 36, 13, p.secondary, action = { dialog = S.settings_help })
        BoardAction(24, 721 + KEEP_VIBRATING_HEIGHT, 50, 36, strings.getString(S.settings_help)) { dialog = S.settings_help }
    }

    if (dialog == S.settings_themes) ThemeDialog(themeNames.mapValues { strings.getString(it.value) }) { dialog = null }
    else dialog?.let { id ->
        AlertDialog(modifier = Modifier.dialogEnter(), onDismissRequest = { dialog = null }, containerColor = p.surface,
            titleContentColor = p.primary, textContentColor = p.secondary,
            title = { Text(strings.getString(id)) },
            text = { Text(strings.getString(if (id == S.settings_help) S.settings_help_body else S.settings_privacy_body)) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    if (id == S.settings_privacy) appSettings()
                }) { Text(strings.getString(if (id == S.settings_privacy) S.settings_manage else S.settings_close), color = p.accent) }
            }, dismissButton = if (id == S.settings_help) ({
                Row {
                    TextButton(onClick = { dialog = null; onNavigate("BetaInfo") }) { Text(tr("Beta info"), color = p.accent) }
                    TextButton(onClick = {
                        dialog = null
                        runCatching { context.startActivity(supportEmailIntent("Maitri help")) }
                    }) { Text(tr("Email support"), color = p.accent) }
                }
            }) else null)
    }
}

/** Profile -> Themes: System, Light, Dark as radio rows. A choice applies at once and is saved; Done closes. */
@Composable
internal fun ThemeDialog(names: Map<ThemeMode, String>, onDone: () -> Unit) {
    val theme = LocalThemeController.current
    val descriptions = mapOf(ThemeMode.SYSTEM to tr("Follow your phone's setting"),
        ThemeMode.LIGHT to tr("Always light (Blush)"), ThemeMode.DARK to tr("Always dark"))
    MaitriChoiceDialog(title = settingsResources().getString(S.settings_themes), confirmLabel = tr("Done"),
        onConfirm = onDone, onDismiss = onDone) {
        names.forEach { (mode, name) ->
            MaitriRadioRow(selected = theme.themeMode == mode, label = name, description = descriptions.getValue(mode)) {
                theme.setMode(mode)
            }
        }
    }
}

internal const val KEEP_VIBRATING_Y = 401
internal const val KEEP_VIBRATING_HEIGHT = 72

/**
 * "Keep vibrating for SOS requests" (default on): a native row in the "Safety, your way" card, styled like the
 * artwork's rows. The link under it opens the SOS requests channel, where the user may let it through Do Not Disturb.
 */
@Composable
private fun KeepVibratingRow(p: MaitriPalette) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(`in`.maitri.app.notification.SosRequestAlerts.keepVibrating(context)) }
    Box(settingsSlot(24, KEEP_VIBRATING_Y, 342, KEEP_VIBRATING_HEIGHT).background(p.surface)) {
        Box(Modifier.padding(start = 56.dp, end = 16.dp).fillMaxWidth().height(1.dp).background(p.divider))
        Box(Modifier.padding(start = 16.dp, top = 14.dp).size(32.dp).background(p.background, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Vibration, null, Modifier.size(18.dp), tint = p.accent)
        }
        Row(Modifier.fillMaxSize().toggleable(value = on, role = Role.Switch, onValueChange = {
                on = it; `in`.maitri.app.notification.SosRequestAlerts.setKeepVibrating(context, it) })
            .padding(start = 56.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                FitText(tr("Keep vibrating for SOS requests"), Modifier, p.primary, 14f, 16f, FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                FitText(tr("Vibrates until you accept or decline"), Modifier, p.secondary, 10f, 12f)
                Text(tr("Ring during Do Not Disturb"), color = p.accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 2.dp).clickable(role = Role.Button) {
                        `in`.maitri.app.notification.SosRequestAlerts.openChannelSettings(context) })
            }
            Switch(checked = on, onCheckedChange = null, modifier = Modifier.scale(0.8f))
        }
    }
}

@Composable
private fun ProfileRow(y: Int, height: Int, title: Int, detail: Int, value: String? = null, onClick: () -> Unit) {
    val strings = settingsResources()
    val p = maitriPalette()
    Box(settingsSlot(24, y, 342, height).clickable(role = Role.Button, onClick = onClick)) {
        Column(Modifier.align(Alignment.CenterStart).padding(start = 56.dp, end = if (value == null) 26.dp else 83.dp)) {
            FitText(strings.getString(title), Modifier, p.primary, 14f, 16f, FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            FitText(strings.getString(detail), Modifier, p.secondary, 10f, 12f)
        }
        if (value != null) Text(value, Modifier.align(Alignment.CenterEnd).padding(end = if (title == S.settings_vibrations) 24.dp else 36.dp),
            color = if (title == S.settings_vibrations) p.accent else p.secondary, fontSize = 11.sp)
    }
}
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun MaitriSettingsLightPreview() {
    `in`.maitri.app.ui.theme.MaitriTheme {
        MaitriSettings(
            user = null,
            onNavigate = {},
            onLogout = {}
        )
    }
}

@Preview(widthDp = 390, heightDp = 844, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MaitriSettingsDarkPreview() {
    `in`.maitri.app.ui.theme.MaitriTheme {
        MaitriSettings(
            user = null,
            onNavigate = {},
            onLogout = {}
        )
    }
}
