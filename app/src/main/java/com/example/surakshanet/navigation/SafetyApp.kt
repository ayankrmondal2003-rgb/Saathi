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
    NavTab("Home", "SOS", Icons.Rounded.Shield),
    NavTab("Responder", "Responder", Icons.Rounded.Radar),
    NavTab("Contacts", "Contacts", Icons.Rounded.People),
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
                    LaunchedEffect(attempt) {
                        failure = null
                        try {
                            com.example.surakshanet.network.BackendClient.api.profile(
                                com.example.surakshanet.network.ExpectedAccount(identity.uid)
                            )
                            synced = true
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: retrofit2.HttpException) {
                            failure = when (e.code()) {
                                401 -> "Your session expired. Sign in again."
                                403 -> "This verified account needs access to the controlled test."
                                else -> "Profile sync failed (HTTP ${e.code()}). Retry when the server is ready."
                            }
                        } catch (e: java.io.IOException) {
                            failure = "Unable to reach the profile service. Check the private network and retry."
                        }
                    }
                    if (!synced) {
                        Page("Account profile") {
                            Text(failure ?: "Syncing your verified account…")
                            if (failure != null) {
                                Button(onClick = { attempt++ }) { Text("Retry profile sync") }
                                TextButton(onClick = auth::logout) { Text("Sign out") }
                            }
                        }
                        profileReady = false
                    }
                }
                val state by vm.state.collectAsStateWithLifecycle()
                if (profileReady && state.user?.uid == identity?.uid && identity != null) {
                    MainGraph(
                        state = state,
                        vm = vm,
                        logout = auth::logout,
                        isDemoMode = isDemoMode,
                        pendingInvitationId = pendingInvitationId,
                        onInvitationHandled = onInvitationHandled
                    )
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
                Onboarding {
                    prefs.edit().putBoolean("seen", true).apply()
                    nav.navigate("Login") { popUpTo("Intro") { inclusive = true } }
                }
            }
            listOf("Login", "Register", "Reset").forEach { route ->
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

                    Home(
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
                        ActiveIncident(
                            incident = incident,
                            uid = state.user?.uid.orEmpty(),
                            contacts = state.contacts,
                            alertStatuses = state.contactAlertStatuses
                        ) {
                            vm.end()
                            nav.popBackStack()
                        }
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
                    Incoming(invitationId = invitationId) {
                        vm.respond(it)
                        nav.popBackStack()
                    }
                }
                composable("Incoming") {
                    Incoming {
                        vm.respond(it)
                        nav.popBackStack()
                    }
                }
                composable("Contacts") {
                    Contacts(state.contacts, vm)
                }
                if (debug) {
                    composable("Diagnostics") {
                        Page("GPS Diagnostics") {
                            LocationPanel(state.user?.uid.orEmpty(), true)
                        }
                    }
                }
                composable("Profile") {
                    state.user?.let {
                        Profile(
                            user = it,
                            logout = {
                                val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
                                scope.launch {
                                    com.example.surakshanet.notification.DeviceRepository.get(context).unregisterDevice()
                                }
                                vm.end()
                                logout()
                            },
                            diagnostics = if (debug) ({ nav.navigate("Diagnostics") }) else null,
                            isDemoMode = isDemoMode
                        )
                    }
                }
            }

            // Genuinely Floating Liquid Glass Dock
            AnimatedVisibility(
                visible = isTabScreen,
                enter = fadeIn(animationSpec = tween(220)) + slideInVertically(initialOffsetY = { it }, animationSpec = tween(260)),
                exit = fadeOut(animationSpec = tween(180)) + slideOutVertically(targetOffsetY = { it }, animationSpec = tween(200)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
            ) {
                SurakshaFloatingNavBar(
                    tabs = tabs,
                    currentRoute = route,
                    onTabSelected = { target -> tab(target) },
                    modifier = Modifier
                        .onSizeChanged { dockHeight = with(density) { it.height.toDp() } }
                        .padding(horizontal = 20.dp)
                        .widthIn(max = 420.dp)
                )
            }
        }
    }
}
