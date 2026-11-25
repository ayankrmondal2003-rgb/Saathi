package `in`.maitri.app.ui

import `in`.maitri.app.tr

import androidx.compose.foundation.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import `in`.maitri.app.R
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import `in`.maitri.app.location.*
import `in`.maitri.app.network.*
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import retrofit2.HttpException

@Composable
fun TestResponderDashboard(uid: String, onApply: () -> Unit, onResumeJourney: (String) -> Unit, openInvitation: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val account = remember(uid) { ExpectedAccount(uid) }
    var profile by remember(uid) { mutableStateOf(`in`.maitri.app.startup.StartupCache.get<ProfileDto>(uid, `in`.maitri.app.startup.StartupCache.PROFILE)) }
    var inbox by remember(uid) { mutableStateOf(emptyList<InvitationDto>()) }
    var preferenceMessage by remember { mutableStateOf("") }
    var locationStatusMessage by remember { mutableStateOf<String?>(null) }
    var inboxLoaded by remember(uid) { mutableStateOf(false) }
    var volunteer by remember(uid) { mutableStateOf(`in`.maitri.app.startup.StartupCache.get<VolunteerStatusDto>(uid, `in`.maitri.app.startup.StartupCache.VOLUNTEER)) }
    var refreshing by remember { mutableStateOf(false) }
    var profileFresh by remember(uid) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var permitted by remember { mutableStateOf(context.locationAllowed()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = context.locationAllowed()
    }
    var backgroundAllowed by remember { mutableStateOf(`in`.maitri.app.network.ResponderLocation.backgroundAllowed(context)) }
    var fullScreenAllowed by remember { mutableStateOf(`in`.maitri.app.notification.NotificationHelper.canUseFullScreen(context)) }
    var explainBackground by remember { mutableStateOf(false) }
    val backgroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        backgroundAllowed = `in`.maitri.app.network.ResponderLocation.backgroundAllowed(context)
        `in`.maitri.app.network.ResponderLocation.schedule(context, uid)
    }
    // Coming back from the system settings page also updates the state.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            backgroundAllowed = `in`.maitri.app.network.ResponderLocation.backgroundAllowed(context)
            fullScreenAllowed = `in`.maitri.app.notification.NotificationHelper.canUseFullScreen(context)
        }
    }
    val palette = maitriPalette()
    if (explainBackground) androidx.compose.material3.AlertDialog(onDismissRequest = { explainBackground = false },
        containerColor = palette.surface, titleContentColor = palette.primary, textContentColor = palette.secondary,
        title = { Text(tr("Get requests when Maitri is closed?")) },
        text = { Text(tr("While you're Available, Maitri will share your location with the server about every 15 minutes, even when the app is closed, so it can ask you to help someone nearby. ") +
            tr("Nobody sees your location unless you accept a request. On the next screen, choose \"Allow all the time\". Turn Available off at any time to stop.")) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = {
            explainBackground = false
            if (android.os.Build.VERSION.SDK_INT >= 29) backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }) { Text(tr("Continue"), color = palette.accent, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { explainBackground = false }) { Text(tr("Not now"), color = palette.accent) } })

    // Profile, invitations and volunteer status. Runs when the tab opens, every 10 s while it's visible, and on
    // pull-to-refresh (which also re-reads the profile).
    suspend fun load(fullProfile: Boolean) {
        try {
            // The cached profile shows at once; the first load of this visit always refreshes it.
            if (profile == null || fullProfile || !profileFresh) {
                profile = BackendClient.api.profile(account)
                profileFresh = true
                `in`.maitri.app.startup.StartupCache.put(uid, `in`.maitri.app.startup.StartupCache.PROFILE, profile)
            }
            profile?.let { `in`.maitri.app.network.ResponderLocation.setAvailable(context, uid, it.optedIn && it.available) }
            inbox = BackendClient.api.invitations(account).filter { it.canOpenIncoming() || it.status == "ACCEPTED" }
            inboxLoaded = true
            volunteer = BackendClient.api.volunteerStatus(account)
            `in`.maitri.app.startup.StartupCache.put(uid, `in`.maitri.app.startup.StartupCache.VOLUNTEER, volunteer)
            if (preferenceMessage == tr("Could not refresh responder information")) preferenceMessage = ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            preferenceMessage = tr("Could not refresh responder information")
        }
    }
    LaunchedEffect(uid, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                load(fullProfile = false)
                delay(10000)
            }
        }
    }

    LaunchedEffect(uid, permitted, profile?.available, lifecycle) {
        if (!permitted || profile?.available != true) {
            locationStatusMessage = null
            return@LaunchedEffect
        }
        var lastSubmittedTimestamp = 0L
        var isSubmitting = false
        var hasResponse = false

        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            callbackFlow<LocationUpdate> {
                val source = FusedLocationSource(context)
                source.subscribe(
                    fix = { update -> trySend(update) },
                    status = { state ->
                        when (state) {
                            TrackingState.TRACKING -> {
                                if (!hasResponse) locationStatusMessage = tr("GPS signal acquired; waiting for first sync…")
                            }
                            TrackingState.STARTING -> {
                                locationStatusMessage = tr("Acquiring GPS fix...")
                            }
                            TrackingState.LOCATION_UNAVAILABLE -> {
                                if (!context.locationEnabled()) {
                                    locationStatusMessage = tr("Location unavailable (disabled in system settings)")
                                } else {
                                    locationStatusMessage = tr("Location unavailable (searching for GPS...)")
                                }
                            }
                            TrackingState.PERMISSION_REQUIRED -> {
                                locationStatusMessage = tr("Location permission required for responder mode")
                            }
                            TrackingState.ERROR -> {
                                locationStatusMessage = tr("Location provider error")
                            }
                            TrackingState.IDLE, TrackingState.STOPPING, TrackingState.STOPPED -> {
                                locationStatusMessage = null
                            }
                        }
                    }
                )
                awaitClose { source.cancel() }
            }.conflate().collect { fix ->
                // Client-side validation: valid coordinates and positive timestamp
                if (!fix.valid()) return@collect

                // Accuracy filter: ignore fixes worse than 100 meters
                if (fix.accuracyMeters > 100f) {
                    locationStatusMessage = tr("Waiting for better GPS accuracy ({0}m > 100m)", fix.accuracyMeters.toInt())
                    return@collect
                }

                // Age filter: ignore stale (> 60s) or future-dated (< -5s) fixes
                val ageMs = System.currentTimeMillis() - fix.timestampMillis
                if (ageMs > 60000L || ageMs < -5000L) {
                    return@collect
                }

                // Monotonic filter: ignore duplicate or out-of-order timestamps
                if (fix.timestampMillis <= lastSubmittedTimestamp) {
                    return@collect
                }

                // Concurrency control: avoid sending overlapping requests
                if (isSubmitting) return@collect
                isSubmitting = true

                try {
                    BackendClient.api.submitResponderFix(
                        FixDto(
                            latitude = fix.latitude,
                            longitude = fix.longitude,
                            recordedAt = Instant.ofEpochMilli(fix.timestampMillis).toString(),
                            mock = fix.isMock,
                            accuracyMeters = fix.accuracyMeters.toDouble()
                        ),
                        account
                    )
                    lastSubmittedTimestamp = fix.timestampMillis
                    hasResponse = true
                    locationStatusMessage = tr("Responder location updated")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HttpException) {
                    when (e.code()) {
                        409 -> {
                            // Server already has this timestamp or newer.
                            // Stale update: Recoverable conflict! Update lastSubmittedTimestamp so we do not retry this fix,
                            // and keep listening for newer GPS fixes without disabling tracking or marking permanently unavailable.
                            if (!staleResponderFix(e.code(), e.response()?.headers()?.get("X-Error-Code"))) {
                                locationStatusMessage = tr("Location conflict. Waiting for another fix.")
                                return@collect
                            }
                            hasResponse = true
                            lastSubmittedTimestamp = maxOf(lastSubmittedTimestamp, fix.timestampMillis)
                            locationStatusMessage = tr("Older GPS fix skipped. Waiting for a newer fix.")
                        }
                        401 -> {
                            locationStatusMessage = tr("Session expired. Sign in again.")
                        }
                        403 -> {
                            locationStatusMessage = tr("Responder consent and availability required (HTTP 403)")
                        }
                        422 -> {
                            locationStatusMessage = tr("Location rejected: check device clock and GPS accuracy (HTTP 422)")
                        }
                        else -> {
                            locationStatusMessage = tr("Location sync failed (HTTP {0})", e.code())
                        }
                    }
                } catch (e: IOException) {
                    locationStatusMessage = tr("Network interrupted; will retry on next GPS fix")
                } catch (e: Exception) {
                    locationStatusMessage = tr("Location sync error: {0}", e.localizedMessage ?: tr("Unknown error"))
                } finally {
                    isSubmitting = false
                }
            }
        }
    }

    fun update(optIn: Boolean, available: Boolean) {
        val current = profile ?: return
        busy = true
        scope.launch {
            try {
                profile = BackendClient.api.profile(ProfileRequest(current.name, optIn, available), account)
                `in`.maitri.app.startup.StartupCache.put(uid, `in`.maitri.app.startup.StartupCache.PROFILE, profile)
                profile?.let { `in`.maitri.app.network.ResponderLocation.setAvailable(context, uid, it.optedIn && it.available) }
                preferenceMessage = tr("Preferences saved")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                preferenceMessage = tr("Could not save preferences")
            } finally {
                busy = false
            }
        }
    }

    // "Request alerts" checks, re-read when the user returns from a settings page.
    var notificationsOn by remember { mutableStateOf(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var batteryOk by remember { mutableStateOf(batteryUnrestricted(context)) }
    var channelOk by remember { mutableStateOf(`in`.maitri.app.notification.SosRequestAlerts.channelOk(context)) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsOn = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            notificationsOn = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
            batteryOk = batteryUnrestricted(context)
            channelOk = `in`.maitri.app.notification.SosRequestAlerts.channelOk(context)
        }
    }
    val bold = androidx.compose.ui.text.font.FontWeight.SemiBold
    val available = profile?.available == true

    PullToRefreshBox(isRefreshing = refreshing, modifier = Modifier.fillMaxSize().background(palette.background),
        onRefresh = { scope.launch { refreshing = true; try { load(fullProfile = true) } finally { refreshing = false } } }) {
    Column(Modifier.fillMaxSize().statusBarsPadding()
        .verticalScroll(rememberScrollState()).padding(bottom = LocalFloatingNavBarPadding.current),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ResponderHeader(palette)
        // Volunteer status card (Verified / Under review / Not approved / Apply).
        VolunteerStatusBadge(palette, volunteer?.status, volunteer?.reviewedAt, volunteer?.reviewerNotes, onApply,
            Modifier.padding(horizontal = 24.dp))
        // Live map card.
        ResponderMapCard(palette, available = available, requestPermission = {
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        })
        if (profile == null && preferenceMessage.isEmpty()) {
            ResponderCard(palette) {
                MaitriLoader(Modifier.fillMaxWidth().padding(vertical = 16.dp), message = tr("Checking your responder settings…"),
                    logoSize = 48.dp)
            }
        } else {
            // "Community responder" card.
            ResponderCard(palette) {
                ToggleRow(palette, tr("Community responder"), tr("Let Maitri ask you to help people near you."),
                    profile?.optedIn == true, profile != null && !busy) {
                    // Opting in on Android 13+: notifications must be allowed, or requests can't reach this phone.
                    if (it && android.os.Build.VERSION.SDK_INT >= 33 && !notificationsOn)
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    update(it, false)
                }
            }
            // "Available for nearby requests" card.
            ResponderCard(palette) {
                ToggleRow(palette, tr("Available for nearby requests"), tr("Get SOS requests from within 1 km of you."),
                    available, profile?.optedIn == true && !busy) { update(true, it) }
                if (available && !permitted) {
                    Button(onClick = { permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION)) },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.onAccent)) { Text(tr("Allow location")) }
                }
                if (preferenceMessage.isNotEmpty()) Text(preferenceMessage, color = palette.secondary, fontSize = 12.sp)
                locationStatusMessage?.let { Text(it, color = palette.secondary, fontSize = 12.sp) }
            }
            // "Request alerts" card: can a request reach this phone right now?
            if (profile?.optedIn == true) RequestAlertsCard(palette, listOf(
                AlertCheck(tr("Notifications"), notificationsOn,
                    if (notificationsOn) tr("Maitri can alert you.") else tr("Maitri can't alert you about requests."), tr("Fix")) {
                    if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                            Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else openAppNotificationSettings(context)
                },
                AlertCheck(tr("SOS request alerts"), channelOk,
                    if (channelOk) tr("Requests pop up over other apps with sound.")
                    else tr("The SOS requests notifications are silenced or minimised."), tr("Fix")) {
                    `in`.maitri.app.notification.SosRequestAlerts.openChannelSettings(context)
                },
                AlertCheck(tr("Lock-screen alert"), fullScreenAllowed,
                    if (fullScreenAllowed) tr("Requests can wake your screen.")
                    else tr("Requests wait in the notification shade while the screen is off."), tr("Fix")) {
                    `in`.maitri.app.notification.NotificationHelper.openFullScreenSettings(context)
                },
                AlertCheck(tr("Battery"), batteryOk,
                    if (batteryOk) tr("Maitri isn't held back by battery saving.")
                    else tr("Battery saving can delay requests. Set Maitri's battery use to Unrestricted."), tr("Fix")) {
                    openAppDetails(context)
                },
                AlertCheck(tr("Requests when Maitri is closed"), backgroundAllowed,
                    if (backgroundAllowed) tr("You get requests even when Maitri is closed.")
                    else tr("Allow location all the time to get requests when Maitri is closed."), tr("Fix")) {
                    explainBackground = true
                },
            )) { PhoneTips(palette) }
            // Recent requests, if any.
            if (inbox.isNotEmpty()) ResponderCard(palette, title = tr("Recent requests")) {
                for (invitation in inbox) {
                    if (invitation.status == "ACCEPTED") {
                        OutlinedButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    val snapshot = BackendClient.api.snapshot(invitation.incidentId, account)
                                    if (snapshot.authorizesJourney(invitation.incidentId)) onResumeJourney(invitation.incidentId)
                                    else preferenceMessage = tr("Responder access has ended.")
                                } catch (e: CancellationException) { throw e }
                                catch (_: Exception) { preferenceMessage = tr("Could not confirm responder access.") }
                                finally { busy = false }
                            }
                        }, border = BorderStroke(1.dp, palette.accent)) { Text(tr("Resume responder journey"), color = palette.accent) }
                    } else OutlinedButton(onClick = { openInvitation(invitation.id) }, border = BorderStroke(1.dp, palette.accent)) {
                        Text(tr("Open invitation"), color = palette.accent)
                    }
                }
            }
            // "How responding works" info card.
            HowRespondingWorksCard(palette)
        }
        Spacer(Modifier.height(4.dp))
    }
    }
}

/** Battery optimisation off for Maitri (Samsung "Unrestricted", Xiaomi "No restrictions"). */
internal fun batteryUnrestricted(context: android.content.Context): Boolean =
    (context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager)
        .isIgnoringBatteryOptimizations(context.packageName)

internal fun openAppNotificationSettings(context: android.content.Context) {
    runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }.onFailure { openAppDetails(context) }
}

/** App info: Battery (Unrestricted) lives here on Samsung, Xiaomi, Oppo/OnePlus and Vivo. */
internal fun openAppDetails(context: android.content.Context) {
    runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.fromParts("package", context.packageName, null))) }
}

/** Responder tab without a server-backed session: the same header and map, no invented invitations or availability. */
@Composable
fun ResponderUnavailable() {
    val palette = maitriPalette()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    Column(Modifier.fillMaxSize().background(palette.background).statusBarsPadding()
        .verticalScroll(rememberScrollState()).padding(bottom = LocalFloatingNavBarPadding.current)) {
        ResponderHeader(palette)
        ResponderMapCard(palette, available = false, requestPermission = {
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        })
        Spacer(Modifier.height(12.dp))
        ResponderCard(palette) {
            Text(tr("Responder mode unavailable"), color = palette.primary, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Text(tr("Responder availability and invitations need a signed-in account connected to the Maitri server. ") +
                tr("Nothing is shared and no requests are received on this screen."), color = palette.secondary)
        }
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, p: MaitriPalette, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(value = checked, enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = if (enabled) p.primary else p.secondary, fontSize = 15.sp)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
