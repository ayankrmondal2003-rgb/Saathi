package com.example.surakshanet.navigation

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
import com.example.surakshanet.data.*
import com.example.surakshanet.state.*
import com.example.surakshanet.ui.*
import com.example.surakshanet.ui.theme.surakshaColors
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
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
    val colors = MaterialTheme.surakshaColors
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
            com.example.surakshanet.notification.DeviceRepository.get(context).registerCurrentToken()
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
                var profileReady = true
                if (!isDemoMode && identity != null && com.example.surakshanet.BuildConfig.API_BASE_URL.isNotBlank()) {
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
                            com.example.surakshanet.network.BackendClient.api.profile(
                                com.example.surakshanet.network.ExpectedAccount(identity.uid)
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
                        profileReady = false
                    }
                }
                val state by vm.state.collectAsStateWithLifecycle()
                if (profileReady) {
                    if (state.user?.uid == identity?.uid && identity != null) {
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
                    nav.navigate("Login") { popUpTo("Intro") { inclusive = true } }
                }
            }
            composable("Login") {
                LaunchedEffect(Unit) { auth.clearMessage() }
                MaitriLogin(state, auth, isDemoMode)
            }
            listOf("Register", "Reset").forEach { route ->
                composable(route) {
                    LaunchedEffect(Unit) { auth.clearMessage() }
                    AccountForm(route, auth, state, isDemoMode) { target ->
                        if (target == "Login") nav.popBackStack() else nav.navigate(target)
                    }
                }
            }
        }
    }
}

@Composable
private fun MainGraph(
    state: DemoState,
    vm: SafetyViewModel,
    logout: () -> Unit,
    isDemoMode: Boolean,
    pendingInvitationId: String? = null,
    onInvitationHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    val debug = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val colors = MaterialTheme.surakshaColors
    val tabRoutes = remember { tabs.map { it.route }.toSet() }
    val currentSelectedRoute = route ?: "Home"

    fun tab(target: String) {
        if (target == currentSelectedRoute) return
        nav.navigate(target) {
            popUpTo(nav.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(pendingInvitationId) {
        if (!pendingInvitationId.isNullOrBlank()) {
            nav.navigate("Incoming?invitationId=$pendingInvitationId") {
                launchSingleTop = true
            }
            onInvitationHandled()
        }
    }

    val isTabScreen = route in tabRoutes && state.user != null
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
                    val onStartIncident = remember(state.incident?.status, state.user?.uid) {
                        { share: Boolean ->
                            val fresh = state.incident?.status != IncidentStatus.ACTIVE
                            vm.start(share)
                            if (fresh) state.user?.let { startDemoTracking(context, it.uid) }
                            nav.navigate("Active") { launchSingleTop = true }
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
                    if (incident?.status == IncidentStatus.ACTIVE) {
                        MaitriActiveSos(
                            incident = incident,
                            uid = state.user?.uid.orEmpty(),
                            contacts = state.contacts,
                            alertStatuses = state.contactAlertStatuses,
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
                    if (!isDemoMode && com.example.surakshanet.BuildConfig.API_BASE_URL.isNotBlank()) {
                        TestResponderDashboard(requireNotNull(state.user).uid) { invitation ->
                            nav.navigate("Incoming?invitationId=" + android.net.Uri.encode(invitation))
                        }
                    } else {
                        val onIncoming = remember { { nav.navigate("Incoming") } }
                        ResponderDashboard(state.responder, vm::enable, vm::available, onIncoming)
                    }
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
                    MaitriIncoming(
                        invitationId = invitationId,
                        onAccepted = { incId ->
                            nav.navigate("ResponderJourney?incidentId=" + android.net.Uri.encode(incId)) {
                                popUpTo("Incoming?invitationId={invitationId}") { inclusive = true }
                            }
                        },
                        onDeclined = { nav.popBackStack() }
                    )
                }
                composable("Incoming") {
                    MaitriIncoming(
                        invitationId = null,
                        onAccepted = { incId ->
                            nav.navigate("ResponderJourney?incidentId=" + android.net.Uri.encode(incId)) {
                                popUpTo("Incoming") { inclusive = true }
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
                    MaitriResponderJourney(
                        incidentId = incId,
                        onBack = { nav.popBackStack() },
                        onIncidentClosed = { tab("Home") }
                    )
                }
                composable("Contacts") {
                    MaitriCircle(contacts = state.contacts, vm = vm)
                }
                composable("VolunteerVerification") {
                    MaitriVolunteer(
                        onSkip = { nav.popBackStack() },
                        onSubmitSuccess = { nav.popBackStack() }
                    )
                }
                if (debug) {
                    composable("Diagnostics") {
                        Page("GPS Diagnostics") {
                            LocationPanel(state.user?.uid.orEmpty(), true)
                        }
                    }
                }
                composable("Profile") {
                    MaitriSettings(
                        user = state.user,
                        onNavigate = { target -> nav.navigate(target) },
                        onLogout = {
                            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
                            scope.launch {
                                com.example.surakshanet.notification.DeviceRepository.get(context).unregisterDevice()
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
                    currentRoute = route,
                    onTabSelected = { target -> tab(target) },
                    modifier = Modifier
                        .onSizeChanged { dockHeight = with(density) { it.height.toDp() } }
                )
            }
        }
    }
}
