package com.example.surakshanet.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.surakshanet.location.*
import com.example.surakshanet.network.*
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

@Composable
fun TestResponderDashboard(uid: String, openInvitation: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val account = remember(uid) { ExpectedAccount(uid) }
    var profile by remember(uid) { mutableStateOf<ProfileDto?>(null) }
    var inbox by remember(uid) { mutableStateOf(emptyList<InvitationDto>()) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var permitted by remember { mutableStateOf(context.locationAllowed()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = context.locationAllowed()
    }

    LaunchedEffect(uid, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    if (profile == null) profile = BackendClient.api.profile()
                    inbox = BackendClient.api.invitations().filter { it.status == "PENDING" || it.status == "ACCEPTED" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = "Could not refresh responder information"
                }
                delay(10000)
            }
        }
    }
    LaunchedEffect(uid, permitted, profile?.available, lifecycle) {
        if (!permitted || profile?.available != true) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            callbackFlow<LocationUpdate> {
                val source = FusedLocationSource(context)
                source.subscribe({ trySend(it) }, { message = "Location unavailable" })
                awaitClose { source.cancel() }
            }.conflate().collect { fix ->
                try {
                    BackendClient.api.submitResponderFix(FixDto(fix.latitude, fix.longitude,
                        Instant.ofEpochMilli(fix.timestampMillis).toString(), fix.isMock, fix.accuracyMeters.toDouble()), account)
                    message = "Responder location updated"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = "Server did not accept this location"
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
                message = "Preferences saved"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Could not save preferences"
            } finally {
                busy = false
            }
        }
    }

    Page("Responder Mode") {
        Text("Community responder")
        Switch(checked = profile?.optedIn == true, enabled = profile != null && !busy,
            onCheckedChange = { update(it, false) })
        Text("Share my location while this dashboard is open")
        Switch(checked = profile?.available == true, enabled = profile?.optedIn == true && !busy,
            onCheckedChange = { update(true, it) })
        if (profile?.available == true && !permitted) {
            Button(onClick = { permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Allow location") }
        }
        if (message.isNotEmpty()) Text(message)
        for (invitation in inbox) {
            OutlinedButton(onClick = { openInvitation(invitation.id) }) { Text("Open invitation · " + invitation.status) }
        }
    }
}
