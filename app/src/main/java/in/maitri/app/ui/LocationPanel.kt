package `in`.maitri.app.ui

import android.Manifest
import android.content.*
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.maitri.app.location.*
import `in`.maitri.app.ui.theme.*
import java.text.DateFormat
import java.util.Date

private tailrec fun Context.activity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

fun startDemoTracking(context: Context, uid: String) {
    val activity = context.activity() ?: return
    val repo = LocalTrackingRepository.get(context)
    val session = repo.session.value
    if (session.owner != uid || session.id.isEmpty() || session.running ||
        session.status == TrackingState.STARTING) return
    if (!repo.sync.canStart(uid, session.id)) {
        // A session already shared with the server must be re-confirmed first (e.g. after the app was stopped
        // mid-SOS): run the sync, and the caller's watchdog starts tracking once it's confirmed.
        android.util.Log.i("MaitriSosSync", "Tracking waits for the server to confirm this SOS")
        repo.sync.wake(uid)
        return
    }
    val next = when {
        !context.locationAllowed() -> TrackingState.PERMISSION_REQUIRED
        !context.locationEnabled() -> TrackingState.LOCATION_UNAVAILABLE
        !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) -> return
        else -> TrackingState.STARTING
    }
    repo.change(session.id) { it.copy(status = next) }
    if (next != TrackingState.STARTING) return
    try {
        ContextCompat.startForegroundService(context, Intent(context, SosLocationService::class.java)
            .putExtra("session", session.id))
    } catch (e: RuntimeException) {
        repo.change(session.id) { it.copy(status = if (e is SecurityException) TrackingState.PERMISSION_REQUIRED else TrackingState.ERROR) }
    }
}

