package `in`.maitri.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import `in`.maitri.app.R
import `in`.maitri.app.location.locationAllowed
import `in`.maitri.app.location.locationEnabled
import `in`.maitri.app.tr
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/** The request area the server uses for volunteers (Settings.radius_meters, 1000 m). */
internal const val RESPONDER_RANGE_METERS = 1000.0

/** Header of the Responder tab, in the same style as the Circle and Profile headers. */
@Composable
internal fun ResponderHeader(p: MaitriPalette) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.maitri_logo), null, Modifier.size(38.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(tr("NEARBY SUPPORT"), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp, color = p.accent)
            Text(tr("Responder"), fontSize = 25.sp, fontWeight = FontWeight.SemiBold, color = p.primary)
        }
    }
}

/** The phone's own position, for display only: balanced power, only while this screen is visible. Never uploaded. */
internal data class OwnFix(val latitude: Double, val longitude: Double, val accuracyMeters: Double)

@SuppressLint("MissingPermission") // Only called with location permission granted (checked by the caller).
@Composable
private fun liveOwnLocation(enabled: Boolean): OwnFix? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var fix by remember { mutableStateOf<OwnFix?>(null) }
    LaunchedEffect(enabled, lifecycle) {
        if (!enabled) return@LaunchedEffect
        // Updates stop when the screen leaves composition (this effect is cancelled) or the app goes to the background.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val client = LocationServices.getFusedLocationProviderClient(context)
            runCatching { client.lastLocation.addOnSuccessListener { l -> if (l != null && fix == null) fix = OwnFix(l.latitude, l.longitude, l.accuracy.toDouble()) } }
            callbackFlow {
                val callback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        result.lastLocation?.let { trySend(OwnFix(it.latitude, it.longitude, it.accuracy.toDouble())) }
                    }
                }
                val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L)
                    .setMinUpdateIntervalMillis(5_000L).build()
                runCatching { client.requestLocationUpdates(request, callback, Looper.getMainLooper()) }
                awaitClose { client.removeLocationUpdates(callback) }
            }.conflate().collect { fix = it }
        }
    }
    return fix
}

/**
 * Live map of where the responder is: a "You" marker with its accuracy circle and, while Available, a faint 1 km circle
 * (the area requests come from). Same card size and position as the illustrative map it replaces.
 */
@Composable
internal fun ResponderMapCard(p: MaitriPalette, available: Boolean, requestPermission: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var allowed by remember { mutableStateOf(context.locationAllowed()) }
    var enabled by remember { mutableStateOf(context.locationEnabled()) }
    // Re-checked when the user comes back from the permission dialog or location settings.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            allowed = context.locationAllowed()
            enabled = context.locationEnabled()
        }
    }
    val fix = liveOwnLocation(allowed && enabled)
    val shape = RoundedCornerShape(24.dp)
    val cardModifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(300.dp)
    when {
        !allowed -> MapPrompt(p, cardModifier, tr("Allow location to see where you are"), tr("Allow location"), requestPermission)
        !enabled -> MapPrompt(p, cardModifier, tr("Turn on location to see where you are"), tr("Turn on location")) {
            runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        }
        fix == null -> Box(cardModifier.clip(shape).background(p.card), contentAlignment = Alignment.Center) {
            MaitriLoader(message = tr("Finding where you are…"), logoSize = 56.dp)
        }
        else -> MaitriMap(listOf(MapMarker(fix.latitude, fix.longitude, MarkerKind.YOU)), tr("Your location"), cardModifier,
            accuracyMeters = fix.accuracyMeters, rangeMeters = if (available) RESPONDER_RANGE_METERS else null,
            alwaysShowAccuracy = true)
    }
}

@Composable
private fun MapPrompt(p: MaitriPalette, modifier: Modifier, message: String, action: String, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(p.card).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painterResource(R.drawable.maitri_logo), null, Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(message, color = p.primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent)) {
            Text(action, fontWeight = FontWeight.SemiBold)
        }
    }
}
