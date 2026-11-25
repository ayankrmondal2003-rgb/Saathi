package `in`.maitri.app.navigation

import `in`.maitri.app.tr
import `in`.maitri.app.trKey

import kotlinx.coroutines.flow.first
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import `in`.maitri.app.data.*
import `in`.maitri.app.state.*
import `in`.maitri.app.ui.*
import `in`.maitri.app.ui.theme.maitriColors
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import `in`.maitri.app.network.*
import `in`.maitri.app.location.LocalTrackingRepository
import `in`.maitri.app.location.preciseLocationAllowed
import androidx.activity.compose.rememberLauncherForActivityResult
import kotlinx.coroutines.launch

internal val tabs = listOf(
    NavTab("Home", trKey("Home"), Icons.Rounded.Home),
    NavTab("Contacts", trKey("Circle"), Icons.Rounded.People),
    NavTab("Responder", trKey("Responder"), Icons.Rounded.Shield),
    NavTab("Profile", trKey("Profile"), Icons.Rounded.Person)
)

/** Set by MainActivity when a circle notification without a link is tapped; Home's graph opens the inbox. */
object PendingNavigation {
    val openInbox = androidx.compose.runtime.mutableStateOf(false)
}

@Composable
fun SafetyApp(
    vm: SafetyViewModel = viewModel(),
    pendingInvitationId: String? = null,
    onInvitationHandled: () -> Unit = {}
) {
    val colors = MaterialTheme.maitriColors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(colors.cardBackgroundSecondary, MaterialTheme.colorScheme.background)
                )
            )
    ) {
        SafetyNavigation(vm, pendingInvitationId, onInvitationHandled)
    }
}

@Composable
private fun SafetyNavigation(vm: SafetyViewModel, pendingInvitationId: String?, onInvitationHandled: () -> Unit) {
    val context = LocalContext.current
    val app = remember {
        runCatching { FirebaseApp.getInstance() }.getOrNull()
    }
    val isDemoMode = app == null
    val auth: AuthViewModel = viewModel(factory = viewModelFactory {
        initializer {
            val repository: AuthRepository = if (app != null) {
                FirebaseAuthRepository(FirebaseAuth.getInstance(app))
            } else {
                LocalAuthRepository.get(context)
            }
            AuthViewModel(repository)
        }
    })
    val account by auth.state.collectAsStateWithLifecycle()
    val identity = account.identity?.takeIf { it.verified }
    LaunchedEffect(identity, account.ready) { if (account.ready) vm.bind(identity) }
    LaunchedEffect(identity?.uid) {
        if (identity != null) {
            `in`.maitri.app.notification.DeviceRepository.get(context).registerCurrentToken()
        }
    }
    // Every cold start shows "Getting things ready" while Preloader works in parallel: at least 700 ms (no flicker),
    // at most 2.5 s, then the app continues even if the server is still waking. Opening the app from an SOS-request
    // notification skips it and goes straight to the request.
    var startupDone by rememberSaveable { mutableStateOf(`in`.maitri.app.startup.StartupGate.done || pendingInvitationId != null) }
    if (!startupDone) {
        LaunchedEffect(Unit) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            kotlinx.coroutines.withTimeoutOrNull(2_500) {
                androidx.compose.runtime.snapshotFlow { account.gate != Gate.LOADING }.first { ready: Boolean -> ready }
                `in`.maitri.app.startup.Preloader.essentialsReady.first { ready: Boolean -> ready }
            }
            val left = 700 - (android.os.SystemClock.elapsedRealtime() - t0)
            if (left > 0) kotlinx.coroutines.delay(left)
            if (vm.state.value.incident?.status == IncidentStatus.ACTIVE) `in`.maitri.app.startup.Preloader.requestOpenActiveSos()
            `in`.maitri.app.startup.StartupGate.done = true
            startupDone = true
        }
        `in`.maitri.app.ui.MaitriStartupScreen()
        return
    }
    if (account.gate == Gate.LOADING) {
        Splash()
        return
    }
    key(account.gate, account.identity?.uid) {
        when (account.gate) {
            Gate.VERIFY -> Box(Modifier.fillMaxSize()) { Verification(account, auth) }
            Gate.SIGNED_OUT -> SignedOut(account, auth, isDemoMode)
            Gate.HOME -> {
                if (isDemoMode || identity == null || `in`.maitri.app.BuildConfig.API_BASE_URL.isBlank()) {
                    Page(tr("Account profile")) {
                        Text(tr("Account synchronization is unavailable."))
                        TextButton(onClick = auth::logout) { Text(tr("Sign out")) }
                    }
                } else {
                    // Home (and the SOS button) never waits for the server: a free-tier cold start can take a minute.
                    // The account syncs in the background; only a refusal is shown, as a banner.
                    var attempt by remember { mutableIntStateOf(0) }
                    var failure by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(attempt) {
                        failure = null
                        var wait = 5_000L
                        while (true) {
                            try {
                                `in`.maitri.app.network.BackendClient.api.profile(
                                    `in`.maitri.app.network.ExpectedAccount(identity.uid))
                                break
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e
                            } catch (e: retrofit2.HttpException) {
                                if (e.code() in 500..599) { kotlinx.coroutines.delay(wait); wait = (wait * 2).coerceAtMost(60_000L); continue }
                                failure = accountSyncMessage(e.code())
                                break
                            } catch (e: java.io.IOException) {
                                // Offline or the server is still waking up; "Connecting to Maitri…" covers it.
                                kotlinx.coroutines.delay(wait); wait = (wait * 2).coerceAtMost(60_000L)
                            }
                        }
                    }
                    val state by vm.state.collectAsStateWithLifecycle()
                    val connecting by `in`.maitri.app.network.BackendLatency.connecting.collectAsStateWithLifecycle()
                    Box(Modifier.fillMaxSize()) {
                        if (state.user?.uid == identity.uid) {
                            MainGraph(
                                state = state,
                                vm = vm,
                                logout = auth::logout,
                                isDemoMode = isDemoMode,
                                pendingInvitationId = pendingInvitationId,
                                onInvitationHandled = onInvitationHandled
                            )
                        } else {
                            // Loading the account (a cold server can take a minute): the loader with the existing message,
                            // and Call 112 always reachable underneath, never behind the loader.
                            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                MaitriLoader(message = if (connecting) tr("Connecting to Maitri…") else null)
                                Spacer(Modifier.height(32.dp))
                                `in`.maitri.app.ui.Call112Button()
                            }
                        }
                        AccountBanner(failure ?: if (connecting && state.user?.uid == identity.uid) tr("Connecting to Maitri…") else null,
                            retry = if (failure != null) ({ attempt++ }) else null, signOut = if (failure != null) auth::logout else null,
                            modifier = Modifier.align(Alignment.TopCenter))
                    }
                }
            }
            Gate.LOADING -> Unit
        }
    }
}

@Composable
private fun SignedOut(state: AuthState, auth: AuthViewModel, isDemoMode: Boolean) {
    val prefs = LocalContext.current.getSharedPreferences("onboarding", 0)
    val first = remember { !prefs.getBoolean("seen", false) }
    val nav = rememberNavController()
    Box(Modifier.fillMaxSize()) {
        NavHost(nav, if (first) "Intro" else "Login", Modifier.fillMaxSize()) {
            composable("Intro") {
                MaitriWelcome {
                    prefs.edit().putBoolean("seen", true).apply()
                    nav.navigate("Login") { launchSingleTop = true }
                }
            }
            composable("Login") {
                LaunchedEffect(Unit) { auth.clearMessage() }
                MaitriLogin(state, auth, isDemoMode)
            }
        }
    }
}

@Composable
internal fun MainGraph(
    state: DemoState,
    vm: SafetyViewModel,
    logout: () -> Unit,
    isDemoMode: Boolean,
    pendingInvitationId: String? = null,
    onInvitationHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    val uid = requireNotNull(state.user).uid
    val sync = remember(context) { LocalTrackingRepository.get(context).sync }
    var authorizedJourney by remember(uid) { mutableStateOf<String?>(null) }
    var confirmedActive by remember(uid) { mutableStateOf<String?>(null) }
    // "End SOS" on Home opens screen 08 with the Safety PIN dialog already showing.
    var endRequested by remember(uid) { mutableStateOf(false) }
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val colors = MaterialTheme.maitriColors
    val tabRoutes = remember { tabs.map { it.route }.toSet() }
    val currentSelectedRoute = route ?: "Home"

    fun tab(target: String) {
        if (target == currentSelectedRoute) return
        nav.navigate(target) {
            popUpTo(nav.graph.findStartDestination().id)
            launchSingleTop = true
        }
    }

    val inboxRequested by PendingNavigation.openInbox
    LaunchedEffect(inboxRequested) {
        if (inboxRequested) {
            PendingNavigation.openInbox.value = false
            nav.navigate("Inbox") { launchSingleTop = true }
        }
    }
    LaunchedEffect(pendingInvitationId) {
        if (!pendingInvitationId.isNullOrBlank()) {
            tab("Responder")
            nav.navigate("Incoming?invitationId=" + android.net.Uri.encode(pendingInvitationId)) {
                launchSingleTop = true
            }
            onInvitationHandled()
        }
    }

    val dockRoute = when {
        route == "Active" -> "Home"
        route?.startsWith("ResponderJourney?") == true -> "Responder"
        route?.startsWith("Incoming?") == true -> "Responder"
        else -> route
    }
    val isTabScreen = dockRoute in tabRoutes && state.user != null
    val navBarBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val density = LocalDensity.current
    var dockHeight by remember { mutableStateOf(92.dp) }
    // The dock's measured height already includes the system navigation bar it sits over.
    val floatingNavBottomPadding = if (isTabScreen) dockHeight + 24.dp else navBarBottomInset + 24.dp

    CompositionLocalProvider(LocalFloatingNavBarPadding provides floatingNavBottomPadding) {
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = nav,
                startDestination = "Home",
                modifier = Modifier.fillMaxSize(),
                // Shared motion: tabs crossfade (200 ms); other screens slide + fade (250 ms, emphasized).
                enterTransition = `in`.maitri.app.ui.MaitriMotion.enter,
                exitTransition = `in`.maitri.app.ui.MaitriMotion.exit,
                popEnterTransition = `in`.maitri.app.ui.MaitriMotion.popEnter,
                popExitTransition = `in`.maitri.app.ui.MaitriMotion.popExit
            ) {
                composable("Home") {
                    var requested by rememberSaveable { mutableStateOf(false) }
                    val syncState by sync.status.collectAsStateWithLifecycle()
                    val id = sync.getActiveIncidentId(uid)
                    LaunchedEffect(requested, id, syncState, state.incident?.status) {
                        if (requested && id != null && state.incident?.status == IncidentStatus.ACTIVE) {
                            try {
                                val snapshot = BackendClient.api.snapshot(id, ExpectedAccount(uid))
                                if (snapshot.incidentId == id && snapshot.status == "ACTIVE" && sync.getActiveIncidentId(uid) == id) {
                                    confirmedActive = id
                                    nav.navigate("Active") { launchSingleTop = true }
                                } else android.widget.Toast.makeText(context, tr("Incident is no longer active"), android.widget.Toast.LENGTH_SHORT).show()
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { android.widget.Toast.makeText(context, tr("Could not confirm the incident"), android.widget.Toast.LENGTH_SHORT).show() }
                            requested = false
                        }
                    }
                    // The SOS never waits for location permission: it starts at once, and tracking begins when granted.
                    val sosLocationPermission = rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
                        startDemoTracking(context, uid)
                    }
                    val onStartIncident: (Boolean) -> Unit = { share ->
                        sync.dismissProblem(uid)
                        if (state.incident?.status != IncidentStatus.ACTIVE) {
                            vm.start(true)
                            startDemoTracking(context, uid)
                        }
                        if (!context.preciseLocationAllowed()) sosLocationPermission.launch(arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                        val session = LocalTrackingRepository.get(context).session.value
                        if (sync.isShared(uid, session.id)) requested = true
                    }
                    val ownStatus = syncState.uid == uid
                    val problem = if (ownStatus) syncState.problem else null
                    // A session the sync layer ended can no longer be confirmed; stop waiting for it.
                    LaunchedEffect(problem) { if (problem == SyncProblem.ENDED) { requested = false; endRequested = false } }
                    // Watchdog: while an SOS waits for its server incident, keep the sync and tracking going. Covers a
                    // session restored after the app was stopped mid-SOS (update, reboot, battery), where nothing else
                    // would restart them, and a server that was asleep.
                    var stillTrying by remember { mutableStateOf(false) }
                    LaunchedEffect(requested, id, uid) {
                        stillTrying = false
                        var waited = 0
                        while (requested && id == null) {
                            sync.wake(uid)
                            startDemoTracking(context, uid)
                            kotlinx.coroutines.delay(5000)
                            waited += 5
                            if (waited >= 20) stillTrying = true
                        }
                    }
                    val connecting by BackendLatency.connecting.collectAsStateWithLifecycle()
                    val sessionMessage = when {
                        problem == SyncProblem.ENDED -> syncState.message
                        connecting && (requested || state.incident?.status == IncidentStatus.ACTIVE) ->
                            tr("Connecting to Maitri… Your SOS keeps retrying in the background.")
                        problem != null -> syncState.message
                        requested && id == null && stillTrying -> tr("Still trying to reach Maitri… If you need help now, call 112.")
                        requested && id == null -> tr("Connecting your SOS to Maitri…")
                        else -> null
                    }
                    val onContactsClick = remember { { tab("Contacts") } }
                    val active = state.incident?.status == IncidentStatus.ACTIVE

                    // An open SOS found at start-up (server or this phone) goes straight to screen 08, once.
                    LaunchedEffect(state.incident?.status) {
                        if (state.incident?.status == IncidentStatus.ACTIVE && `in`.maitri.app.startup.Preloader.consumeOpenActiveSos())
                            onStartIncident(false)
                    }
                    LaunchedEffect(Unit) { `in`.maitri.app.startup.StartupGate.reportFullyDrawn(context as? android.app.Activity) }
                    // Unread inbox count for the bell: cached value at once, refreshed while Home is visible.
                    var unread by remember { mutableIntStateOf(`in`.maitri.app.startup.StartupCache.get<Int>(uid, `in`.maitri.app.startup.StartupCache.UNREAD) ?: 0) }
                    val homeLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
                    LaunchedEffect(uid) {
                        homeLifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                            while (true) {
                                try {
                                    unread = `in`.maitri.app.network.BackendClient.circle.inbox(ExpectedAccount(uid)).unreadCount
                                    `in`.maitri.app.startup.StartupCache.put(uid, `in`.maitri.app.startup.StartupCache.UNREAD, unread)
                                }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
                                kotlinx.coroutines.delay(60_000)
                            }
                        }
                    }
                    MaitriHome(
                        state = state,
                        start = onStartIncident,
                        contacts = onContactsClick,
                        onInbox = { nav.navigate("Inbox") { launchSingleTop = true } },
                        unread = unread,
                        session = {
                            SosSessionPanel(maitriPalette(), active, sessionMessage, problem,
                                onResume = { onStartIncident(false) },
                                onEnd = {
                                    if (sync.getActiveIncidentId(uid) != null) {
                                        endRequested = true
                                        onStartIncident(false)
                                    } else {
                                        // Never confirmed by the server: nothing to resolve with the PIN.
                                        sync.clearActiveIncident(uid)
                                        vm.end()
                                        requested = false
                                    }
                                },
                                onRetry = { sync.retryNow(uid) },
                                onDismiss = { sync.dismissProblem(uid) })
                        }
                    )
                }
                composable(
                    route = "Active",
                    enterTransition = {
                        slideInVertically(
                            initialOffsetY = { it / 4 },
                            animationSpec = tween(200, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(200))
                    },
                    exitTransition = {
                        slideOutVertically(
                            targetOffsetY = { it / 4 },
                            animationSpec = tween(160, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(160))
                    }
                ) {
                    val incident = state.incident
                    if (incident?.status == IncidentStatus.ACTIVE && confirmedActive != null && confirmedActive == sync.getActiveIncidentId(uid)) {
                        LaunchedEffect(Unit) { endRequested = false }
                        MaitriActiveSos(
                            incident = incident,
                            requestEnd = endRequested,
                            incidentId = requireNotNull(confirmedActive),
                            uid = state.user?.uid.orEmpty(),
                            onAccessLost = { nav.popBackStack() },
                            onSetPin = { nav.navigate("SafetyPin") { launchSingleTop = true } },
                            onEndSos = {
                                vm.end()
                                nav.popBackStack()
                            }
                        )
                    } else {
                        LaunchedEffect(Unit) { tab("Home") }
                    }
                }
                composable("Responder") {
                    if (!isDemoMode && `in`.maitri.app.BuildConfig.API_BASE_URL.isNotBlank()) {
                        TestResponderDashboard(uid, onApply = { nav.navigate("VolunteerVerification") }, onResumeJourney = { id ->
                            authorizedJourney = id
                            nav.navigate("ResponderJourney?incidentId=" + android.net.Uri.encode(id))
                        }) { invitation ->
                            nav.navigate("Incoming?invitationId=" + android.net.Uri.encode(invitation))
                        }
                    } else ResponderUnavailable()
                }
                composable(
                    route = "Incoming?invitationId={invitationId}",
                    arguments = listOf(androidx.navigation.navArgument("invitationId") {
                        type = androidx.navigation.NavType.StringType
                        nullable = true
                        defaultValue = null
                    })
                ) { backStackEntry ->
                    val invitationId = backStackEntry.arguments?.getString("invitationId")
                    var match by remember(invitationId) { mutableStateOf<InvitationDto?>(null) }
                    LaunchedEffect(invitationId) {
                        try {
                            match = BackendClient.api.invitations(ExpectedAccount(uid)).firstOrNull {
                                it.id == invitationId && it.canOpenIncoming()
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { /* Return to the inbox for retry. */ }
                        if (match == null) {
                            android.widget.Toast.makeText(context, tr("Invitation unavailable"), android.widget.Toast.LENGTH_SHORT).show()
                            nav.popBackStack()
                        }
                    }
                    val invitation = match
                    if (invitation == null) Box(Modifier.fillMaxSize().background(maitriPalette().background), contentAlignment = Alignment.Center) {
                        MaitriLoader()
                    } else MaitriIncoming(
                        invitation = invitation,
                        uid = uid,
                        onAccepted = { incId ->
                            authorizedJourney = incId
                            nav.navigate("ResponderJourney?incidentId=" + android.net.Uri.encode(incId)) {
                                popUpTo("Incoming?invitationId={invitationId}") { inclusive = true }
                            }
                        },
                        onDeclined = { nav.popBackStack() }
                    )
                }
                composable(
                    route = "ResponderJourney?incidentId={incidentId}",
                    arguments = listOf(androidx.navigation.navArgument("incidentId") {
                        type = androidx.navigation.NavType.StringType
                        nullable = false
                    })
                ) { backStackEntry ->
                    val incId = backStackEntry.arguments?.getString("incidentId").orEmpty()
                    if (incId != authorizedJourney) {
                        LaunchedEffect(Unit) { nav.popBackStack() }
                    } else MaitriResponderJourney(
                        incidentId = incId,
                        uid = uid,
                        onIncidentClosed = { authorizedJourney = null; nav.popBackStack() }
                    )
                }
                composable("Contacts") {
                    MaitriCircle(contacts = state.contacts, vm = vm, message = state.contactsMessage)
                }
                composable("VolunteerVerification") {
                    MaitriVolunteer(
                        onSkip = { nav.popBackStack() },
                        onSubmitSuccess = { nav.popBackStack() }
                    )
                }
                composable("Profile") {
                    MaitriSettings(
                        user = state.user,
                        onNavigate = { target -> nav.navigate(target) },
                        onLogout = {
                            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
                            scope.launch {
                                `in`.maitri.app.notification.DeviceRepository.get(context).unregisterDevice()
                            }
                            if (state.incident?.status == IncidentStatus.ACTIVE) {
                                android.widget.Toast.makeText(context, tr("Signing out stops local sharing. Your server incident remains open until closure is confirmed."), android.widget.Toast.LENGTH_LONG).show()
                            }
                            logout()
                        }
                    )
                }
                composable("BetaInfo") { MaitriBetaInfo(onBack = { nav.popBackStack() }) }
                composable("PrivacyData") {
                    MaitriPrivacyData(onBack = { nav.popBackStack() }, onDeleted = {
                        android.widget.Toast.makeText(context, tr("Your Maitri account was deleted."), android.widget.Toast.LENGTH_LONG).show()
                        logout()
                    })
                }
                composable("EditProfile") {
                    MaitriEditProfile(
                        user = state.user,
                        onBack = { nav.popBackStack() },
                        onProfileUpdated = { updated -> vm.updateUserProfile(updated) }
                    )
                }
                composable("Permissions") {
                    MaitriPermissions(
                        onBack = { nav.popBackStack() }
                    )
                }
                composable("TestVibration") {
                    MaitriVibration(
                        onBack = { nav.popBackStack() }
                    )
                }
                composable("SafetyPin") {
                    MaitriPin(
                        uid = state.user?.uid,
                        onBack = { nav.popBackStack() }
                    )
                }
                composable("Inbox") {
                    MaitriInbox(uid, onBack = { nav.popBackStack() }, onInvitation = { id ->
                        nav.navigate("Incoming?invitationId=" + android.net.Uri.encode(id)) { launchSingleTop = true }
                    })
                }
                composable("Language") {
                    MaitriLanguage(
                        onBack = { nav.popBackStack() }
                    )
                }
            }

            // During an SOS, 112 is one tap away on every screen, whatever the server is doing.
            if (state.incident?.status == IncidentStatus.ACTIVE) {
                Call112Button(Modifier.align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = floatingNavBottomPadding))
            }

            // SVG navigation: Home, Circle, Responder, Profile.
            AnimatedVisibility(
                visible = isTabScreen,
                enter = fadeIn(animationSpec = tween(220)) + slideInVertically(initialOffsetY = { it }, animationSpec = tween(260)),
                exit = fadeOut(animationSpec = tween(180)) + slideOutVertically(targetOffsetY = { it }, animationSpec = tween(200)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                MaitriBottomBar(
                    currentRoute = dockRoute,
                    onTabSelected = { target -> tab(target) },
                    modifier = Modifier
                        .onSizeChanged { dockHeight = with(density) { it.height.toDp() } }
                )
            }
        }
    }
}

/** Why the server refused this account, in words (never an HTTP code). */
internal fun accountSyncMessage(code: Int): String = when (code) {
    401 -> tr("Your sign-in has expired. Sign out and sign in again.")
    403 -> tr("This account can't use Maitri yet. Check that your email is verified, then retry.")
    else -> tr("Maitri couldn't load your account. Retry in a moment.")
}

@Composable
private fun AccountBanner(text: String?, retry: (() -> Unit)?, signOut: (() -> Unit)?, modifier: Modifier = Modifier) {
    if (text == null) return
    val p = `in`.maitri.app.ui.maitriPalette()
    Row(modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp)
        .background(p.surface, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
        .padding(horizontal = 14.dp, vertical = 8.dp)
        .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f, fill = false), color = p.accent, fontSize = 12.sp)
        retry?.let { TextButton(onClick = it) { Text(tr("Retry"), color = p.accent) } }
        signOut?.let { TextButton(onClick = it) { Text(tr("Sign out"), color = p.secondary) } }
    }
}
