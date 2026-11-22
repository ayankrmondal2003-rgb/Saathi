package com.example.surakshanet.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.surakshanet.network.BackendClient
import com.example.surakshanet.network.DecisionDto
import com.example.surakshanet.network.ExpectedAccount
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * Screen 06 — Incoming SOS Request Dialog.
 * Strict SVG fidelity matching design-reference/crops/light-06.svg and dark-06.svg.
 */
@Composable
fun MaitriIncoming(
    invitationId: String?,
    approxLocality: String = "Kolkata, Sector V",
    approxDistance: String = "Approx. 450 m away",
    estimatedEta: String = "Estimated arrival ~ 3-4 mins",
    onAccepted: (incidentId: String) -> Unit,
    onDeclined: () -> Unit
) {
    val p = maitriPalette()
    val scope = rememberCoroutineScope()
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun submitDecision(decision: String) {
        if (isSubmitting) return
        if (invitationId.isNullOrBlank()) {
            errorMessage = "Invalid invitation ID."
            return
        }

        val currentUser = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
        if (currentUser == null) {
            errorMessage = "Sign in required to respond to SOS requests."
            return
        }

        isSubmitting = true
        errorMessage = null

        scope.launch {
            try {
                val account = ExpectedAccount(currentUser.uid)
                val response = BackendClient.api.respond(invitationId, DecisionDto(decision), account)
                if (response.status == "EXPIRED") {
                    errorMessage = "This emergency invitation has expired."
                    return@launch
                }

                if (decision == "ACCEPTED") {
                    // Fetch incident ID from invitations inbox
                    val inbox = runCatching { BackendClient.api.invitations(account) }.getOrNull()
                    val match = inbox?.find { it.id == invitationId }
                    val incidentId = match?.incidentId ?: invitationId
                    onAccepted(incidentId)
                } else {
                    onDeclined()
                }
            } catch (e: HttpException) {
                errorMessage = when (e.code()) {
                    403 -> "Responder location must be fresh and within active recruitment area."
                    404 -> "Emergency incident no longer available."
                    409 -> "Invitation already answered."
                    422 -> "Invalid invitation parameters."
                    else -> "Unable to respond (HTTP ${e.code()})."
                }
            } catch (e: Exception) {
                errorMessage = "Network error: ${e.localizedMessage ?: "Unable to contact server"}."
            } finally {
                isSubmitting = false
            }
        }
    }

    MaitriIncomingContent(
        p = p,
        approxLocality = approxLocality,
        approxDistance = approxDistance,
        estimatedEta = estimatedEta,
        isSubmitting = isSubmitting,
        errorMessage = errorMessage,
        onAccept = { submitDecision("ACCEPTED") },
        onDecline = { submitDecision("DECLINED") }
    )
}

@Composable
internal fun MaitriIncomingContent(
    p: MaitriPalette,
    approxLocality: String,
    approxDistance: String,
    estimatedEta: String,
    isSubmitting: Boolean,
    errorMessage: String?,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val isDark = p.dark

    // Scrim overlay covering the full 390x844 viewport
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center
    ) {
        // Modal Card strictly matching 341x395dp, rx=27.5dp in light-06.svg / dark-06.svg
        Box(
            modifier = Modifier
                .width(342.dp)
                .wrapContentHeight()
                .clip(RoundedCornerShape(28.dp))
                .background(p.surface)
                .border(1.dp, p.divider, RoundedCornerShape(28.dp))
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Top Icon Circle (44x44dp, rx=22dp)
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(p.card),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Emergency,
                        contentDescription = "Emergency Alert",
                        tint = p.accent,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Heading: "Nearby SOS Request"
                Text(
                    text = "Nearby SOS Request",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = p.primary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Subtitle: "Someone needs help nearby"
                Text(
                    text = "Someone needs urgent help nearby.",
                    fontSize = 12.sp,
                    color = p.secondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Locality / Distance Box (310x98dp, rx=18dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(p.background)
                        .border(1.dp, p.divider.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                        .padding(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.LocationOn,
                                contentDescription = null,
                                tint = p.accent,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = approxLocality,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = p.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "•  $approxDistance",
                                fontSize = 11.sp,
                                color = p.secondary
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Schedule,
                                contentDescription = null,
                                tint = p.secondary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = estimatedEta,
                                fontSize = 11.sp,
                                color = p.secondary
                            )
                        }

                        Text(
                            text = "Precise victim coordinates will be disclosed only after acceptance.",
                            fontSize = 10.sp,
                            color = p.secondary.copy(alpha = 0.85f),
                            lineHeight = 14.sp
                        )
                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = errorMessage,
                        fontSize = 11.sp,
                        color = p.accent,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action Buttons Row (each 148x48dp, rx=24dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Accept Action Button (Left)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(if (isSubmitting) p.accent.copy(alpha = 0.6f) else p.accent)
                            .clickable(enabled = !isSubmitting, onClick = onAccept),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = "Accept",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isDark) Color(0xFF40303A) else Color.White
                            )
                        }
                    }

                    // Decline Action Button (Right)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(p.card)
                            .clickable(enabled = !isSubmitting, onClick = onDecline),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Decline",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = p.accent
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Previews for Screen 06 (Light & Dark)
// ---------------------------------------------------------------------------

@Preview(name = "Screen 06 — Incoming SOS Light", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun MaitriIncomingLightPreview() {
    MaitriIncomingContent(
        p = MaitriPalette(dark = false),
        approxLocality = "Kolkata, Sector V",
        approxDistance = "Approx. 450 m away",
        estimatedEta = "Estimated arrival ~ 3-4 mins",
        isSubmitting = false,
        errorMessage = null,
        onAccept = {},
        onDecline = {}
    )
}

@Preview(name = "Screen 06 — Incoming SOS Dark", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun MaitriIncomingDarkPreview() {
    MaitriIncomingContent(
        p = MaitriPalette(dark = true),
        approxLocality = "Kolkata, Sector V",
        approxDistance = "Approx. 450 m away",
        estimatedEta = "Estimated arrival ~ 3-4 mins",
        isSubmitting = false,
        errorMessage = null,
        onAccept = {},
        onDecline = {}
    )
}
