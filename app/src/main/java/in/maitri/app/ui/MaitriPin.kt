package `in`.maitri.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.maitri.app.R
import `in`.maitri.app.R.string as S
import `in`.maitri.app.network.*
import com.google.firebase.FirebaseException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import retrofit2.HttpException

internal enum class PinMode { LOADING, LOAD_FAILED, SETUP, CHANGE_CURRENT, CHANGE_NEW, RECOVER }

/** Initial mode from the server's enrollment state. */
internal fun pinModeFor(status: SafetyPinStatusDto): PinMode =
    if (status.hasPrimaryPin) PinMode.CHANGE_CURRENT else PinMode.SETUP

/** Hint resource for the two-row entry modes, or null when the entries can be submitted. */
internal fun pinEntryHint(first: String, second: String): Int? = when {
    first.length == 6 && pinWeakness(first) == PinWeakness.REPEATED -> S.pin_weak_repeated
    first.length == 6 && pinWeakness(first) in setOf(PinWeakness.SEQUENTIAL, PinWeakness.REVERSE) -> S.pin_weak_sequence
    first.length < 6 || second.length < 6 -> S.pin_hint_setup
    first != second -> S.pin_mismatch
    else -> null
}

private const val RECENT_SIGN_IN_MS = 4 * 60 * 1000L

/** Screen 13: enrollment, change and password-verified recovery of the primary Safety PIN. */
@Composable
fun MaitriPin(uid: String?, onBack: () -> Unit, modifier: Modifier = Modifier, onDone: () -> Unit = onBack) {
    val p = maitriPalette()
    val strings = settingsResources()
    val scope = rememberCoroutineScope()
    val preview = LocalInspectionMode.current
    val context = androidx.compose.ui.platform.LocalContext.current

    // PIN values use plain remember so they are never written to the saved-instance bundle.
    var mode by remember { mutableStateOf(if (preview) PinMode.SETUP else PinMode.LOADING) }
    var status by remember { mutableStateOf<SafetyPinStatusDto?>(null) }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var current by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var askPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var load by remember { mutableIntStateOf(0) }
    val firstFocus = remember { FocusRequester() }
    val secondFocus = remember { FocusRequester() }

    fun reset(next: PinMode) { first = ""; second = ""; mode = next; show = false }

    LaunchedEffect(uid, load) {
        if (preview) return@LaunchedEffect
        if (uid.isNullOrBlank()) { error = strings.getString(S.edit_session); mode = PinMode.LOAD_FAILED; return@LaunchedEffect }
        val cached = `in`.maitri.app.startup.StartupCache.get<SafetyPinStatusDto>(uid, `in`.maitri.app.startup.StartupCache.PIN)
        if (cached != null) { status = cached; reset(pinModeFor(cached)) } else mode = PinMode.LOADING
        try {
            val loaded = BackendClient.api.safetyPinStatus(ExpectedAccount(uid))
            `in`.maitri.app.startup.StartupCache.put(uid, `in`.maitri.app.startup.StartupCache.PIN, loaded)
            status = loaded
            error = null
            // Don't wipe digits already typed when the fresh answer agrees with the cached one.
            if (cached == null || pinModeFor(cached) != pinModeFor(loaded)) reset(pinModeFor(loaded))
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            error = null
            mode = PinMode.LOAD_FAILED
        }
    }

    LaunchedEffect(mode) {
        if (mode in setOf(PinMode.SETUP, PinMode.CHANGE_CURRENT, PinMode.CHANGE_NEW, PinMode.RECOVER))
            runCatching { firstFocus.requestFocus() }
    }

    suspend fun recover(account: String) {
        check(BackendClient.api.recoverSafetyPin(SafetyPinRecoveryDto(first, second), ExpectedAccount(account)).status == "RECOVERED")
    }

    fun finish(message: Int) {
        first = ""; second = ""; current = ""
        android.widget.Toast.makeText(context, strings.getString(message),
            android.widget.Toast.LENGTH_SHORT).show()
        onDone()
    }

    fun submit() {
        val account = uid ?: return
        if (mode == PinMode.LOAD_FAILED) { load++; return }
        if (mode == PinMode.CHANGE_CURRENT) {
            current = first; error = null; reset(PinMode.CHANGE_NEW); return
        }
        busy = true; error = null
        scope.launch {
            try {
                val expected = ExpectedAccount(account)
                when (mode) {
                    PinMode.SETUP -> {
                        check(BackendClient.api.setupSafetyPin(SafetyPinSetupDto(first, second), expected).status == "CONFIGURED")
                        finish(S.pin_saved)
                    }
                    PinMode.CHANGE_NEW -> {
                        check(BackendClient.api.changeSafetyPin(SafetyPinChangeDto(current, first, second), expected).status == "UPDATED")
                        finish(S.pin_changed)
                    }
                    PinMode.RECOVER -> {
                        val user = FirebaseAuth.getInstance().currentUser
                        val signedIn = user?.metadata?.lastSignInTimestamp ?: 0L
                        if (user?.uid == account && System.currentTimeMillis() - signedIn in 0..RECENT_SIGN_IN_MS) {
                            recover(account); finish(S.pin_recovered)
                        } else askPassword = true
                    }
                    else -> Unit
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val message = pinFailure(e)
                error = message
                when {
                    e is HttpException && e.code() == 409 -> { load++ }
                    e is HttpException && e.code() == 403 && mode == PinMode.CHANGE_NEW && message.startsWith("Current PIN") -> {
                        current = ""; reset(PinMode.CHANGE_CURRENT)
                    }
                    e is HttpException && e.code() == 403 && mode == PinMode.RECOVER -> askPassword = true
                }
            } finally { busy = false }
        }
    }

    fun verifyPassword() {
        val account = uid ?: return
        busy = true; passwordError = null
        scope.launch {
            try {
                val user = FirebaseAuth.getInstance().currentUser
                val email = user?.email
                if (user == null || user.uid != account || email.isNullOrBlank()) {
                    passwordError = strings.getString(S.pin_no_password); return@launch
                }
                user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()
                password = ""
                user.getIdToken(true).await()
                askPassword = false
                recover(account)
                finish(S.pin_recovered)
            } catch (e: CancellationException) { throw e } catch (e: FirebaseException) {
                passwordError = strings.getString(S.pin_password_failed)
            } catch (e: Exception) {
                askPassword = false
                error = pinFailure(e)
            } finally { busy = false }
        }
    }

    val twoRows = mode in setOf(PinMode.SETUP, PinMode.CHANGE_NEW, PinMode.RECOVER)
    val (banner, bannerSub) = when (mode) {
        PinMode.CHANGE_CURRENT, PinMode.CHANGE_NEW -> S.pin_banner_change to S.pin_banner_change_sub
        PinMode.RECOVER -> S.pin_banner_recover to S.pin_banner_recover_sub
        else -> S.pin_banner_setup to S.pin_banner_setup_sub
    }
    val firstLabel = when (mode) {
        PinMode.CHANGE_CURRENT -> S.pin_current
        PinMode.CHANGE_NEW, PinMode.RECOVER -> S.pin_new
        else -> S.pin_enter
    }
    val hint = when (mode) {
        PinMode.LOADING -> S.pin_loading
        PinMode.LOAD_FAILED -> S.pin_load_failed
        PinMode.CHANGE_CURRENT -> if (status?.isLocked == true) S.pin_locked else S.pin_hint_current
        else -> pinEntryHint(first, second) ?: S.pin_ready
    }
    val canSubmit = !busy && when (mode) {
        PinMode.LOADING -> false
        PinMode.LOAD_FAILED -> uid != null
        PinMode.CHANGE_CURRENT -> first.length == 6
        else -> pinEntryHint(first, second) == null
    }
    val action = when (mode) {
        PinMode.LOAD_FAILED -> S.pin_retry
        PinMode.CHANGE_CURRENT -> S.pin_continue
        PinMode.CHANGE_NEW -> S.pin_change
        PinMode.RECOVER -> S.pin_reset
        else -> S.pin_save
    }

    SettingsBoard(if (p.dark) R.drawable.maitri_dark_13 else R.drawable.maitri_light_13, 788, modifier) {
        SettingsHeader(strings.getString(S.pin_title), strings.getString(S.settings_profile), strings.getString(S.settings_back),
            onBack = { if (mode == PinMode.CHANGE_NEW || mode == PinMode.RECOVER) { error = null; load++ } else onBack() })
        SettingsText(strings.getString(banner), 86, 166, 270, 28, 20, p.primary, true)
        SettingsText(strings.getString(bannerSub), 40, 207, 300, 36, 12, p.secondary, line = 17)

        val entryEnabled = !busy && mode !in setOf(PinMode.LOADING, PinMode.LOAD_FAILED)
        SettingsText(strings.getString(firstLabel), 24, 291, 300, 22, 12, p.primary)
        PinBoxes(first, { first = it; error = null; if (it.length == 6 && twoRows) runCatching { secondFocus.requestFocus() } },
            320, strings.getString(firstLabel), show, entryEnabled, "pin_first", firstFocus,
            if (twoRows) ImeAction.Next else ImeAction.Done) { if (canSubmit && !twoRows) submit() }
        if (twoRows) {
            SettingsText(strings.getString(S.pin_confirm), 24, 399, 300, 22, 12, p.primary)
            PinBoxes(second, { second = it; error = null }, 428, strings.getString(S.pin_confirm), show, entryEnabled,
                "pin_second", secondFocus, ImeAction.Done) { if (canSubmit) submit() }
        } else if (mode == PinMode.CHANGE_CURRENT) {
            SettingsText(strings.getString(S.pin_forgot), 24, 396, 200, 30, 13, p.accent, bold = true,
                action = { error = null; current = ""; reset(PinMode.RECOVER) })
        }

        // Show PIN checkbox geometry: 15 x 15 at (24.5, 498.5), radius 3.5.
        Box(settingsSlot(16, 490, 132, 32).testTag("pin_show")
            .semantics { stateDescription = if (show) "on" else "off" }
            .clickable(role = Role.Checkbox, enabled = entryEnabled) { show = !show }) {
            Box(Modifier.offset(8.5.dp, 8.5.dp).size(15.dp)
                .background(if (show) p.accent else Color.Transparent, RoundedCornerShape(3.5.dp))
                .border(1.dp, if (show) p.accent else p.secondary, RoundedCornerShape(3.5.dp)),
                contentAlignment = Alignment.Center) {
                if (show) Icon(Icons.Rounded.Check, null, Modifier.size(12.dp), tint = if (p.dark) p.background else Color.White)
            }
            Text(strings.getString(S.pin_show), Modifier.offset(34.dp, 6.dp), color = p.secondary, fontSize = 12.sp)
        }

        SettingsText(strings.getString(S.pin_tip), 74, 557, 280, 24, 14, p.primary, true)
        SettingsText(strings.getString(S.pin_tip_body), 40, 593, 316, 34, 11, p.secondary, line = 15)
        SettingsText(error ?: strings.getString(hint), 28, 698, 334, 36, 11, if (error != null) p.accent else p.secondary,
            center = true, line = 14)

        // Save button geometry: 342 x 52 at (24, 744), radius 26. Disabled state matches the crop.
        Box(settingsSlot(24, 744, 342, 52).testTag("pin_submit")
            .background(if (canSubmit) p.accent else p.card, RoundedCornerShape(26.dp))
            .clickable(enabled = canSubmit, role = Role.Button, onClick = ::submit), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp).semantics { contentDescription = strings.getString(S.pin_saving) }, color = if (p.dark) p.background else Color.White, strokeWidth = 2.dp)
            else Text(strings.getString(action), color = if (!canSubmit) p.secondary else if (p.dark) p.background else Color.White,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    if (askPassword) {
        AlertDialog(modifier = Modifier.dialogEnter(), onDismissRequest = { if (!busy) { askPassword = false; password = "" } },
            title = { Text(strings.getString(S.pin_password_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(strings.getString(S.pin_password_body))
                    OutlinedTextField(password, { password = it; passwordError = null }, Modifier.fillMaxWidth().testTag("pin_password"),
                        label = { Text(strings.getString(S.pin_password)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (password.isNotEmpty() && !busy) verifyPassword() }))
                    passwordError?.let { Text(it, color = p.accent, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = ::verifyPassword, enabled = password.isNotEmpty() && !busy) { Text(strings.getString(S.pin_verify)) }
            },
            dismissButton = {
                TextButton(onClick = { askPassword = false; password = "" }, enabled = !busy) { Text(strings.getString(S.pin_cancel)) }
            })
    }
}

/** Six native 46 x 52 boxes at x = 24 + 59.2 n, matching the crop; one hidden numeric field drives them. */
@Composable
private fun PinBoxes(value: String, onValue: (String) -> Unit, y: Int, label: String, show: Boolean, enabled: Boolean,
                     tag: String, focus: FocusRequester, ime: ImeAction, onIme: () -> Unit) {
    val p = maitriPalette()
    var focused by remember { mutableStateOf(false) }
    BasicTextField(value, { next -> onValue(next.filter { it in '0'..'9' }.take(6)) },
        settingsSlot(24, y, 342, 52).testTag(tag).focusRequester(focus).onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = label },
        enabled = enabled, singleLine = true,
        textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ime),
        keyboardActions = KeyboardActions(onAny = { onIme() }),
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        cursorBrush = SolidColor(Color.Transparent),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(1.dp).alpha(0f)) { inner() }
                repeat(6) { index ->
                    val active = focused && index == value.length.coerceAtMost(5)
                    val box = Modifier.offset((index * 59.2f).dp, 0.dp).size(46.dp, 52.dp)
                        .background(p.surface, RoundedCornerShape(14.dp))
                    Box(if (active) box.border(1.5.dp, p.accent, RoundedCornerShape(14.dp)) else box,
                        contentAlignment = Alignment.Center) {
                        val digit = value.getOrNull(index)
                        if (digit == null) Box(Modifier.size(20.dp, 1.8.dp).background(p.secondary))
                        else Text(if (show) digit.toString() else "•", color = p.primary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        })
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun MaitriPinLightPreview() {
    `in`.maitri.app.ui.theme.MaitriTheme { MaitriPin(uid = null, onBack = {}) }
}

@Preview(widthDp = 390, heightDp = 844, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MaitriPinDarkPreview() {
    `in`.maitri.app.ui.theme.MaitriTheme { MaitriPin(uid = null, onBack = {}) }
}
