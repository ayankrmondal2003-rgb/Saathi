package `in`.maitri.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import `in`.maitri.app.data.*
import `in`.maitri.app.ui.theme.MaitriTheme
import `in`.maitri.app.ui.theme.maitriColors

@Preview(name = "Compact phone · Light", widthDp = 320, heightDp = 640, showSystemUi = true, showBackground = true)
@Preview(name = "Large phone · Dark", widthDp = 432, heightDp = 915, showSystemUi = true, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
fun PreviewHomeScreen() {
    MaitriTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            Home(
                state = DemoState(
                    user = UserProfile(
                        uid = "uid-123",
                        name = "Priya Sharma",
                        email = "priya@example.com",
                        phone = "+91 98765 00000",
                        createdAt = System.currentTimeMillis(),
                        responder = Responder(enabled = true, available = true)
                    ),
                    contacts = listOf(
                        EmergencyContact("c1", "Aarav Sharma", "+91 98765 43210").copy(isPrimary = true, label = "Spouse"),
                        EmergencyContact("c2", "Neha Gupta", "+91 98123 45678").copy(isPrimary = false, label = "Friend")
                    ),
                    responder = Responder(enabled = true, available = true)
                ),
                start = {},
                contacts = {}
            )
            MaitriFloatingNavBar(
                tabs = `in`.maitri.app.navigation.tabs,
                currentRoute = "Home",
                onTabSelected = {},
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp, start = 20.dp, end = 20.dp)
                    .widthIn(max = 420.dp)
            )
        }
    }
}

@Preview(name = "Active Incident - Dark Mode", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "Active Incident - Light Mode", showBackground = true)
@Composable
fun PreviewActiveIncident() {
    MaitriTheme {
        ActiveIncident(
            incident = SosIncident(
                status = IncidentStatus.ACTIVE,
                started = System.currentTimeMillis() - 145000L,
                notified = 4,
                accepted = 1
            ),
            uid = "uid-123",
            end = {}
        )
    }
}

@Preview(name = "SOS Button - Dark Mode", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "SOS Button - Light Mode")
@Composable
fun PreviewSosButton() {
    MaitriTheme {
        Surface {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                EmergencySosButton(onTrigger = {}, onQuickTap = {})
            }
        }
    }
}

@Preview(name = "Safety PIN Pad", showBackground = true)
@Composable
fun PreviewPinPad() {
    MaitriTheme {
        Surface {
            MaitriPinPad(
                expectedPin = "1234",
                onPinSuccess = {},
                onKeepActive = {}
            )
        }
    }
}

@Preview(name = "Radar Map Container", showBackground = true)
@Composable
fun PreviewMapContainer() {
    MaitriTheme {
        Surface(modifier = Modifier.padding(16.dp)) {
            MaitriMapContainer(
                incidentActive = true,
                responderCount = 1
            )
        }
    }
}

@Preview(name = "Liquid Glass System - Dark Mode", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "Liquid Glass System - Light Mode", showBackground = true)
@Composable
fun PreviewLiquidGlassSystem() {
    MaitriTheme {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                GlassCard {
                    Text(
                        text = "Liquid Glass Card",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Translucent frosted depth with physical specular rim refraction.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassStatusChip(text = "PROTECTED", color = MaterialTheme.maitriColors.success)
                        GlassStatusChip(text = "LIVE SOS", color = MaterialTheme.maitriColors.emergency, pulsing = true)
                    }
                }

                GlassSettingsTile(
                    title = "Liquid Glass Settings",
                    subtitle = "Tactile refraction and hardware fallback",
                    icon = Icons.Rounded.Shield,
                    trailing = {
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null
                        )
                    }
                )

                GlassBottomNavigation(
                    tabs = listOf(
                        NavTab("Home", "SOS", Icons.Rounded.Shield),
                        NavTab("Responder", "Responder", Icons.Rounded.Radar),
                        NavTab("Contacts", "Contacts", Icons.Rounded.People),
                        NavTab("Profile", "Profile", Icons.Rounded.Person)
                    ),
                    currentRoute = "Home",
                    onTabSelected = {}
                )
            }
        }
    }
}
