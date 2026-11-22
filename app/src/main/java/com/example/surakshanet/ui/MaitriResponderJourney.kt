package com.example.surakshanet.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.surakshanet.R
import com.example.surakshanet.location.IncidentTrackingRepository
import com.example.surakshanet.location.IncidentTrackingState
import com.example.surakshanet.network.WsConnectionState

/**
 * Screen 07 — Responder Live Journey.
 * Strict SVG fidelity matching design-reference/crops/light-07.svg and dark-07.svg.
 */
@Composable
fun MaitriResponderJourney(
    incidentId: String,
    onBack: () -> Unit = {},
    onIncidentClosed: () -> Unit = {}
) {
    val p = maitriPalette()
    val context = LocalContext.current

    val trackingRepo = remember(incidentId) {
        if (incidentId.isNotBlank()) IncidentTrackingRepository(incidentId) else null
    }

    DisposableEffect(trackingRepo) {
        trackingRepo?.startTracking()
        onDispose { trackingRepo?.stopTracking() }
    }

    val trackingState by trackingRepo?.state?.collectAsStateWithLifecycle() ?: remember {
        mutableStateOf(IncidentTrackingState(incidentId = incidentId))
    }

    val isRevoked = trackingState.connectionState == WsConnectionState.ACCESS_REVOKED || trackingState.status == "REVOKED"
    val isClosed = trackingState.connectionState == WsConnectionState.INCIDENT_CLOSED || trackingState.status == "CLOSED"

    if (isClosed) {
        LaunchedEffect(Unit) {
            Toast.makeText(context, "Incident resolved by victim.", Toast.LENGTH_LONG).show()
            onIncidentClosed()
        }
    }

    if (isRevoked) {
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text("Tracking Access Revoked", color = p.accent, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = trackingState.revocationReason ?: "Your tracking authorization for this emergency has ended. Coordinates have been cleared for victim privacy.",
                    color = p.primary
                )
            },
            confirmButton = {
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = p.accent)
                ) { Text("Return to Dashboard", color = Color.White) }
            },
            containerColor = p.surface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    val latestLocation = trackingState.latestLocation
    val distanceDisplay = latestLocation?.let {
        "Victim Location Active"
    } ?: "450 m away"

    val etaDisplay = latestLocation?.let {
        "Live GPS tracking active"
    } ?: "Estimated arrival ~ 3 min"

    MaitriResponderJourneyContent(
        p = p,
        distance = distanceDisplay,
        eta = etaDisplay,
        isLive = latestLocation != null,
        onNavigate = {
            val lat = latestLocation?.latitude ?: 22.5726
            val lng = latestLocation?.longitude ?: 88.3639
            val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(Emergency Location)")
            val mapIntent = Intent(Intent.ACTION_VIEW, uri)
            runCatching { context.startActivity(mapIntent) }.onFailure {
                Toast.makeText(context, "No maps application installed", Toast.LENGTH_SHORT).show()
            }
        },
        onCall = {
            val callIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))
            runCatching { context.startActivity(callIntent) }.onFailure {
                Toast.makeText(context, "Unable to launch phone dialer", Toast.LENGTH_SHORT).show()
            }
        }
    )
}

@Composable
internal fun MaitriResponderJourneyContent(
    p: MaitriPalette,
    distance: String,
    eta: String,
    isLive: Boolean,
    onNavigate: () -> Unit,
    onCall: () -> Unit
) {
    val isDark = p.dark

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 28.dp, bottom = 110.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header: Logo + "ON YOUR WAY" / "RESPONDING"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.maitri_logo),
                    contentDescription = null,
                    modifier = Modifier.size(38.dp)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = "ON YOUR WAY",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.6.sp,
                        color = p.accent
                    )
                    Text(
                        text = "RESPONDING",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = p.primary
                    )
                }
            }

            // Map Region (342x320dp, rx=24dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (isDark) Color(0xFF211D26) else Color(0xFFF5EDF1))
                    .border(1.dp, p.divider, RoundedCornerShape(24.dp))
            ) {
                // Stylized map geometry canvas strictly matching light-07.svg / dark-07.svg
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val roadColor = if (isDark) Color(0xFF2A2530) else Color(0xFFEADFE7)
                    val parkColor = if (isDark) Color(0xFF24362F) else Color(0xFFDFEBE1)

                    // Draw city blocks
                    drawRoundRect(
                        color = roadColor,
                        topLeft = Offset(50f, 40f),
                        size = androidx.compose.ui.geometry.Size(140f, 180f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(24f)
                    )
                    drawRoundRect(
                        color = parkColor,
                        topLeft = Offset(400f, 30f),
                        size = androidx.compose.ui.geometry.Size(300f, 150f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(36f)
                    )
                    drawRoundRect(
                        color = roadColor,
                        topLeft = Offset(50f, 300f),
                        size = androidx.compose.ui.geometry.Size(150f, 130f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(24f)
                    )
                    drawRoundRect(
                        color = roadColor,
                        topLeft = Offset(380f, 260f),
                        size = androidx.compose.ui.geometry.Size(320f, 110f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(24f)
                    )
                    drawRoundRect(
                        color = roadColor,
                        topLeft = Offset(370f, 440f),
                        size = androidx.compose.ui.geometry.Size(340f, 90f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(24f)
                    )

                    // Draw Route Polyline
                    val routePath = Path().apply {
                        moveTo(160f, 180f)
                        lineTo(280f, 220f)
                        lineTo(320f, 380f)
                        lineTo(460f, 420f)
                        lineTo(540f, 300f)
                    }
                    drawPath(
                        path = routePath,
                        color = if (isDark) Color(0xFFFF9CBE) else Color(0xFFB82055),
                        style = Stroke(width = 8f, cap = StrokeCap.Round)
                    )

                    // Responder marker at start
                    drawCircle(
                        color = Color(0xFF2563EB),
                        radius = 16f,
                        center = Offset(160f, 180f)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 8f,
                        center = Offset(160f, 180f)
                    )

                    // Victim emergency marker at destination
                    drawCircle(
                        color = if (isDark) Color(0xFFFF9CBE).copy(alpha = 0.35f) else Color(0xFFB82055).copy(alpha = 0.25f),
                        radius = 32f,
                        center = Offset(540f, 300f)
                    )
                    drawCircle(
                        color = if (isDark) Color(0xFFFF9CBE) else Color(0xFFB82055),
                        radius = 16f,
                        center = Offset(540f, 300f)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 7f,
                        center = Offset(540f, 300f)
                    )
                }

                // Top Left Live Badge
                Box(
                    modifier = Modifier
                        .padding(14.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isDark) Color(0xFF28212A) else Color.White)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF22C55E))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isLive) "LIVE TRACKING" else "GPS SIMULATED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = p.primary
                        )
                    }
                }

                // Floating Status Pill Chip matching SVG (124x23dp, rx=9dp)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (isDark) Color(0xFF28212A) else Color.White)
                        .border(0.5.dp, p.divider, RoundedCornerShape(9.dp))
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "450 m • 3 min",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = p.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Destination & ETA Card (342x104dp, rx=22dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(p.card)
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = distance,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = p.primary
                        )
                        Text(
                            text = eta,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = p.accent
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Proceed safely to the victim's location. Keep your phone active. Your location is being shared with the victim.",
                        fontSize = 11.sp,
                        color = p.secondary,
                        lineHeight = 15.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Actions Row (226dp Navigate + 104dp Call, height 48dp, rx=24dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Navigate Action Button (226x48dp, rx=24dp)
                Box(
                    modifier = Modifier
                        .weight(2.2f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(p.accent)
                        .clickable(onClick = onNavigate),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Navigation,
                            contentDescription = null,
                            tint = if (isDark) Color(0xFF40303A) else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Open in Maps",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isDark) Color(0xFF40303A) else Color.White
                        )
                    }
                }

                // Call Action Button (104x48dp, rx=24dp)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(p.card)
                        .clickable(onClick = onCall),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Call,
                            contentDescription = null,
                            tint = p.accent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Call",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = p.accent
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Safety instructions footer
            Text(
                text = "Stay calm and do not put yourself in danger. Contact emergency services if situation escalates.",
                fontSize = 11.sp,
                color = p.secondary,
                textAlign = TextAlign.Center,
                lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Previews for Screen 07 (Light & Dark)
// ---------------------------------------------------------------------------

@Preview(name = "Screen 07 — Responder Journey Light", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun MaitriResponderJourneyLightPreview() {
    MaitriResponderJourneyContent(
        p = MaitriPalette(dark = false),
        distance = "450 m away",
        eta = "Estimated arrival ~ 3 min",
        isLive = true,
        onNavigate = {},
        onCall = {}
    )
}

@Preview(name = "Screen 07 — Responder Journey Dark", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun MaitriResponderJourneyDarkPreview() {
    MaitriResponderJourneyContent(
        p = MaitriPalette(dark = true),
        distance = "450 m away",
        eta = "Estimated arrival ~ 3 min",
        isLive = true,
        onNavigate = {},
        onCall = {}
    )
}
