package com.example.surakshanet.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.surakshanet.data.*
import com.example.surakshanet.state.AuthState
import com.example.surakshanet.state.AuthViewModel
import com.example.surakshanet.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun Splash() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        BrandLogo(Modifier.size(96.dp))
    }
}

@Composable
fun Onboarding(done: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val colors = MaterialTheme.surakshaColors

    val titles = listOf(
        "Emergency SOS Assistance",
        "Nearby Community Responders",
        "Encrypted Dynamic Location"
    )
    val details = listOf(
        "Activate high-priority rescue alerts by holding the SOS button for 2 seconds. Trusted contacts and nearby guardians are notified immediately.",
        "A decentralized 1 km alert perimeter connects you with verified community members ready to support you when seconds count.",
        "Turn-by-turn encrypted coordinates stream securely in the background as you move, ensuring responders arrive at your exact position."
    )
    val icons = listOf<androidx.compose.ui.graphics.vector.ImageVector>(
        Icons.Rounded.Emergency,
        Icons.Rounded.Radar,
        Icons.Rounded.LocationSearching
    )

    Page(
        title = "Welcome to SurakshaNet",
        subtitle = "Step ${step + 1} of 3"
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            BrandLogo(Modifier.size(56.dp))
        }

        // Step Indicator Dots
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 0 until 3) {
                val active = i == step
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .height(8.dp)
                        .width(if (active) 28.dp else 8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (active) colors.emergency else colors.cardBorder)
                )
            }
        }

        // Onboarding Feature Card
        SurakshaCard(
            modifier = Modifier.padding(vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(colors.emergency.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icons[step],
                        contentDescription = null,
                        tint = colors.emergency,
                        modifier = Modifier.size(42.dp)
                    )
                }

                Text(
                    text = titles[step],
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = details[step],
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }

        // Buttons
        Action(
            label = if (step == 2) "Get Started" else "Continue",
            icon = if (step == 2) Icons.Rounded.Check else Icons.Rounded.ArrowForward,
            click = {
                if (step == 2) done() else step++
            }
        )

        if (step > 0) {
            SurakshaOutlinedButton(
                label = "Previous",
                icon = Icons.Rounded.ArrowBack,
                click = { step-- }
            )
        }
    }
}

@Composable
fun AccountForm(
    page: String,
    vm: AuthViewModel,
    state: AuthState,
    isDemoMode: Boolean = false,
    navigate: (String) -> Unit
) {
    val register = page == "Register"
    val reset = page == "Reset"
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val colors = MaterialTheme.surakshaColors

    Page(
        title = when {
            register -> "Create Account"
            reset -> "Reset Password"
            else -> "Welcome Back"
        },
        subtitle = "SurakshaNet Secure Access"
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            BrandLogo(Modifier.size(72.dp))
        }

        if (isDemoMode) {
            SurakshaCard(
                backgroundColor = colors.infoContainer,
                borderColor = colors.info.copy(alpha = 0.4f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Rounded.Speed, contentDescription = null, tint = colors.info, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Demo Mode Active",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.onInfoContainer
                        )
                        Text(
                            text = "Local demo authentication enabled. Use quick sign-in below to enter without registration.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onInfoContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        SurakshaCard {
            if (register) {
                Field(
                    label = "Full Name",
                    value = name,
                    change = { name = it },
                    icon = Icons.Rounded.Person
                )
            }

            Field(
                label = "Email Address",
                value = email,
                change = { email = it },
                type = KeyboardType.Email,
                icon = Icons.Rounded.Email
            )

            if (!reset) {
                Field(
                    label = "Password",
                    value = password,
                    change = { password = it },
                    type = KeyboardType.Password,
                    icon = Icons.Rounded.Lock
                )
            }

            if (register) {
                Field(
                    label = "Confirm Password",
                    value = confirm,
                    change = { confirm = it },
                    type = KeyboardType.Password,
                    icon = Icons.Rounded.LockReset
                )
            }
        }

        Feedback(state)

        if (!state.busy) {
            Action(
                label = when {
                    register -> "Create Account"
                    reset -> "Send Reset Link"
                    else -> "Sign In"
                },
                icon = when {
                    register -> Icons.Rounded.PersonAdd
                    reset -> Icons.Rounded.MailOutline
                    else -> Icons.Rounded.Login
                },
                click = {
                    if (reset) vm.reset(email)
                    else vm.submit(if (register) name else null, email, password, confirm)
                }
            )

            if (page == "Login") {
                if (isDemoMode) {
                    SurakshaOutlinedButton(
                        label = "⚡ Quick Demo Sign-In",
                        icon = Icons.Rounded.Bolt,
                        click = {
                            vm.submit(null, LocalAuthRepository.DEMO_EMAIL, LocalAuthRepository.DEMO_PASSWORD)
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { navigate("Reset") }) {
                        Text("Forgot password?", color = colors.textSecondary)
                    }
                    TextButton(onClick = { navigate("Register") }) {
                        Text("Create account", fontWeight = FontWeight.Bold, color = colors.info)
                    }
                }
            } else {
                TextButton(
                    onClick = { navigate("Login") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Return to Sign In", color = colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun Feedback(state: AuthState) {
    val colors = MaterialTheme.surakshaColors
    if (state.busy) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = colors.emergency
        )
    }
    state.message?.let { msg ->
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = colors.emergencyContainer,
            border = BorderStroke(1.dp, colors.emergency.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = colors.emergency,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
fun Verification(state: AuthState, vm: AuthViewModel) {
    val colors = MaterialTheme.surakshaColors

    Page(
        title = "Verify Email",
        subtitle = "Account Security"
    ) {
        SurakshaCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(colors.info.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MarkEmailRead,
                        contentDescription = null,
                        tint = colors.info,
                        modifier = Modifier.size(34.dp)
                    )
                }

                Text(
                    text = "Verification Email Sent",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )

                Text(
                    text = "A secure verification link has been sent to ${state.identity?.email}. Click the link in your email and tap Refresh below.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = colors.textSecondary
                )
            }
        }

        Feedback(state)

        if (!state.busy) {
            Action(
                label = "Refresh Status",
                icon = Icons.Rounded.Refresh,
                click = vm::refresh
            )

            SurakshaOutlinedButton(
                label = if (state.cooldown == 0) "Resend Verification Email" else "Resend in ${state.cooldown}s",
                icon = Icons.Rounded.Send,
                enabled = state.cooldown == 0,
                click = vm::resend
            )

            TextButton(
                onClick = vm::logout,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sign Out", color = colors.emergency)
            }
        }
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    change: (String) -> Unit,
    type: KeyboardType = KeyboardType.Text,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val isPassword = type == KeyboardType.Password

    OutlinedTextField(
        value = value,
        onValueChange = change,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        leadingIcon = if (icon != null) {
            { Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.surakshaColors.textSecondary) }
        } else null,
        trailingIcon = if (isPassword) {
            {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        tint = MaterialTheme.surakshaColors.textSecondary
                    )
                }
            }
        } else null,
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = type),
        visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None
    )
}
