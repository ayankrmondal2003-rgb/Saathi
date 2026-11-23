package `in`.maitri.app.navigation

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
import kotlinx.coroutines.launch

internal val tabs = listOf(
    NavTab("Home", "Home", Icons.Rounded.Home),
    NavTab("Contacts", "Circle", Icons.Rounded.People),
    NavTab("Responder", "Responder", Icons.Rounded.Shield),
    NavTab("Profile", "Profile", Icons.Rounded.Person)
)

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
                    Page("Account profile") {
                        Text("Account synchronization is unavailable.")
                        TextButton(onClick = auth::logout) { Text("Sign out") }
                    }
                } else {
                    var synced by remember { mutableStateOf(false) }
                    var attempt by remember { mutableIntStateOf(0) }
                    var failure by remember { mutableStateOf<String?>(null) }
                    var isColdStart by remember { mutableStateOf(false) }
                    LaunchedEffect(attempt) {
                        failure = null
                        isColdStart = false
                        val timer = kotlinx.coroutines.CoroutineScope(coroutineContext).launch {
                            kotlinx.coroutines.delay(5000)
                            isColdStart = true
                        }
                        try {
                            `in`.maitri.app.network.BackendClient.api.profile(
                                `in`.maitri.app.network.ExpectedAccount(identity.uid)
                            )
                            timer.cancel()
                            synced = true
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            timer.cancel()
                            throw e
                        } catch (e: retrofit2.HttpException) {
                            timer.cancel()
                            failure = when (e.code()) {
                                401 -> "Firebase session expired (HTTP 401). Sign out and sign in again."
                                403 -> "Backend test allowlist denial (HTTP 403). Contact admin to add your UID (${identity.uid}) to TEST_ACCOUNT_UIDS."
                                else -> "Profile sync failed (HTTP ${e.code()}). Server returned an error."
                            }
                        } catch (e: java.io.IOException) {
                            timer.cancel()
                            failure = when {
                                e is java.net.SocketTimeoutException ->
                                    "Connection timed out (Render free tier may still be spinning up). Tap retry."
                                else ->
                                    "Unable to reach the backend service: ${e.localizedMessage ?: "Network error"}. Check internet connection and retry."
                            }
                        }
                    }
                    if (!synced) {
                        Page("Account profile") {
                            val p = maitriPalette()
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                if (failure == null) {
                                    CircularProgressIndicator(color = p.accent)
                                    Text(
                                        text = if (isColdStart)
                                            "Waking up Render backend service...\n(Free tier spin-up may take up to 60 seconds)"
                                        else
                                            "Syncing your verified account…",
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        color = p.primary
                                    )
                                } else {
                                    Text(
                                        text = failure!!,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        color = p.accent,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Button(
                                        onClick = { attempt++ },
                                        colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = androidx.compose.ui.graphics.Color.White)
                                    ) { Text("Retry profile sync") }
                                }
                                TextButton(onClick = auth::logout) {
                                    Text("Sign out", color = p.secondary)
                                }
                            }
                        }
                    } else {
                        val state by vm.state.collectAsStateWithLifecycle()
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
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }
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
    val floatingNavBottomPadding = navBarBottomInset + if (isTabScreen) dockHeight + 24.dp else 24.dp

    CompositionLocalProvider(LocalFloatingNavBarPadding provides floatingNavBottomPadding) {
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = nav,
                startDestination = "Home",
                modifier = Modifier.fillMaxSize(),
                enterTransition = {
                    fadeIn(animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing))
                },
                exitTransition = {
                    fadeOut(animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing))
                },
                popEnterTransition = {
                    fadeIn(animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing))
                },
                popExitTransition = {
                    fadeOut(animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing))
                }
            ) {
                composable("Home") {
                    var localPractice by rememberSaveable { mutableStateOf(false) }
                    if (localPractice) {
                        AlertDialog(onDismissRequest = { localPractice = false },
                            title = { Text("Local practice session") },
                            text = { Text("This practice session is on this device only.") },
                            confirmButton = { TextButton(onClick = { vm.end(); localPractice = false }) { Text("End practice") } })
                    }
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
                                } else android.widget.Toast.makeText(context, "Incident is no longer active", android.widget.Toast.LENGTH_SHORT).show()
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { android.widget.Toast.makeText(context, "Could not confirm the incident", android.widget.Toast.LENGTH_SHORT).show() }
                            requested = false
                        }
                    }
                    val onStartIncident: (Boolean) -> Unit = { share ->
                        if (state.incident?.status != IncidentStatus.ACTIVE) {
                            vm.start(share)
                            startDemoTracking(context, uid)
                        }
                        val session = LocalTrackingRepository.get(context).session.value
                        if (sync.isShared(uid, session.id)) requested = true else localPractice = true
                    }
                    if (requested && id == null) {
                        LaunchedEffect(syncState) {
                            android.widget.Toast.makeText(context, if (syncState.uid == uid) syncState.message else "Waiting for incident confirmation", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    val onContactsClick = remember { { tab("Contacts") } }

                    MaitriHome(
                        state = state,
                        start = onStartIncident,
                        contacts = onContactsClick
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
                        MaitriActiveSos(
                            incident = incident,
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
                            android.widget.Toast.makeText(context, "Invitation unavailable", android.widget.Toast.LENGTH_SHORT).show()
                            nav.popBackStack()
                        }
                    }
                    val invitation = match
                    if (invitation == null) CircularProgressIndicator() else MaitriIncoming(
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
                                android.widget.Toast.makeText(context, "Signing out stops local sharing. Your server incident remains open until closure is confirmed.", android.widget.Toast.LENGTH_LONG).show()
                            }
                            logout()
                        }
                    )
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
                composable("Language") {
                    MaitriLanguage(
                        onBack = { nav.popBackStack() }
                    )
                }
            }

            // SVG navigation: Home, Circle, Responder, Profile.
            AnimatedVisibility(
                visible = isTabScreen,
                enter = fadeIn(animationSpec = tween(220)) + slideInVertically(initialOffsetY = { it }, animationSpec = tween(260)),
                exit = fadeOut(animationSpec = tween(180)) + slideOutVertically(targetOffsetY = { it }, animationSpec = tween(200)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
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
