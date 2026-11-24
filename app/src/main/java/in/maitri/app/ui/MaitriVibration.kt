package `in`.maitri.app.ui

import `in`.maitri.app.tr
import `in`.maitri.app.trKey

import android.content.Context
import android.os.Build
import `in`.maitri.app.R
import `in`.maitri.app.R.string as S
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class VibrationPattern(
    val title: String,
    val subtitle: String,
    val timings: LongArray,
    val amplitudes: IntArray?
) {
    GENTLE(
        title = trKey("Gentle"),
        subtitle = trKey("Short, soft pulses"),
        timings = longArrayOf(0, 150, 150, 150, 150, 150),
        amplitudes = intArrayOf(0, 100, 0, 100, 0, 100)
    ),
    STEADY(
        title = trKey("Steady"),
        subtitle = trKey("Balanced, clear pulses"),
        timings = longArrayOf(0, 350, 200, 350, 200, 350),
        amplitudes = intArrayOf(0, 180, 0, 180, 0, 180)
    ),
    STRONG(
        title = trKey("Strong"),
        subtitle = trKey("Longer, stronger pulses"),
        timings = longArrayOf(0, 700, 200, 700, 200, 700, 200, 700),
        amplitudes = intArrayOf(0, 255, 0, 255, 0, 255, 0, 255)
    )
}

/**
 * Screen 11 — Test Vibration.
 * Strict SVG fidelity matching design-reference/crops/light-11.svg and dark-11.svg.
 */
@Composable
fun MaitriVibration(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val p = maitriPalette()
    val context = LocalContext.current
    val strings = settingsResources()
    val preview0 = androidx.compose.ui.platform.LocalInspectionMode.current
    var selectedPattern by rememberSaveable { mutableStateOf(
        if (preview0) VibrationPattern.STEADY else `in`.maitri.app.notification.AlertVibration.current(context)) }
    val preview = androidx.compose.ui.platform.LocalInspectionMode.current
    val vibrator = remember(context, preview) {
        if (preview) return@remember null
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
    }
    val available = preview || vibrator?.hasVibrator() == true
    var testing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(vibrator, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP && testing) {
                vibrator?.cancel()
                testing = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); if (testing) vibrator?.cancel() }
    }
    val titles = listOf(S.settings_gentle, S.settings_steady, S.settings_strong)
    val details = listOf(S.settings_gentle_sub, S.settings_steady_sub, S.settings_strong_sub)
    val stateText = when {
        !available || failed -> S.settings_unavailable
        testing -> S.settings_playing
        else -> S.settings_ready
    }
    SettingsBoard(if (p.dark) R.drawable.maitri_dark_11 else R.drawable.maitri_light_11, 788, modifier) {
        SettingsHeader(strings.getString(S.settings_vibration), strings.getString(S.settings_profile), strings.getString(S.settings_back), onBack)
        SettingsText(strings.getString(S.settings_signal), 40, 232, 310, 36, 20, p.primary, true, true)
        SettingsText(strings.getString(S.settings_signal_sub), 38, 267, 314, 26, 11, p.secondary, center = true)
        SettingsText(strings.getString(stateText), 140, 302, 110, 25, 10, p.accent, center = true)
        SettingsText(strings.getString(S.settings_choose), 24, 361, 342, 30, 18, p.primary, true)
        Column(settingsSlot(24, 398, 342, 162).clip(RoundedCornerShape(20.dp))) {
            VibrationPattern.entries.forEachIndexed { index, pattern ->
                val selected = selectedPattern == pattern
                Box(Modifier.fillMaxWidth().height(54.dp).background(if (selected) p.card else p.surface)
                    .selectable(selected, enabled = !testing, role = Role.RadioButton, onClick = {
                        selectedPattern = pattern
                        // Saved at once: real SOS alerts use the chosen pattern too.
                        if (!preview0) `in`.maitri.app.notification.AlertVibration.save(context, pattern)
                    })) {
                    Column(Modifier.align(Alignment.CenterStart).padding(start = 16.dp)) {
                        Text(strings.getString(titles[index]), fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, color = p.primary)
                        Text(strings.getString(details[index]), fontSize = 10.sp, lineHeight = 14.sp, color = p.secondary)
                    }
                    PatternRadio(selected, Modifier.align(Alignment.CenterEnd).padding(end = 18.dp), p)
                    if (index < 2) HorizontalDivider(Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp), color = p.divider)
                }
            }
        }
        SettingsText(strings.getString(S.settings_hold), 24, 601, 342, 37, 11, p.secondary, center = true)
        val start: (() -> Unit)? = if (preview || !available || testing) null else ({
            val pattern = selectedPattern
            val motor = requireNotNull(vibrator)
            val effect = if (motor.hasAmplitudeControl() && pattern.amplitudes != null)
                VibrationEffect.createWaveform(pattern.timings, pattern.amplitudes, -1)
            else VibrationEffect.createWaveform(pattern.timings, -1)
            try {
                motor.vibrate(effect)
                failed = false
                testing = true
            } catch (_: SecurityException) { failed = true }
        })
        LaunchedEffect(testing) { if (testing) { delay(selectedPattern.timings.sum()); testing = false } }
        SettingsText(strings.getString(if (testing) S.settings_playing else S.settings_vibration), 24, 652, 342, 52,
            15, if (p.dark) p.background else Color.White, true, true, action = start)
        SettingsText(strings.getString(S.settings_no_sos), 24, 715, 342, 32, 11, p.accent, center = true)
        SettingsText(strings.getString(S.settings_feedback), 24, 751, 342, 32, 10, p.secondary, center = true)
    }
}

@Composable
internal fun PatternRadio(selected: Boolean, modifier: Modifier, p: MaitriPalette) {
    Box(modifier.size(18.dp).border(1.5.dp, if (selected) p.accent else p.secondary, CircleShape),
        contentAlignment = Alignment.Center) {
        if (selected) Box(Modifier.size(8.dp).background(p.accent, CircleShape))
    }
}
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun MaitriVibrationLightPreview() {
    `in`.maitri.app.ui.theme.MaitriTheme {
        MaitriVibration(onBack = {})
    }
}

@Preview(widthDp = 390, heightDp = 844, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MaitriVibrationDarkPreview() {
    `in`.maitri.app.ui.theme.MaitriTheme {
        MaitriVibration(onBack = {})
    }
}
