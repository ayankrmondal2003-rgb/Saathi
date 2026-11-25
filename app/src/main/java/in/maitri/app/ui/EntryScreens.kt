package `in`.maitri.app.ui

import `in`.maitri.app.tr

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
import `in`.maitri.app.data.*
import `in`.maitri.app.state.AuthState
import `in`.maitri.app.state.AuthViewModel
import `in`.maitri.app.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun Splash() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        MaitriLoader(logoSize = 96.dp)
    }
}

@Composable
private fun Feedback(state: AuthState) {
    val colors = MaterialTheme.maitriColors
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
    val colors = MaterialTheme.maitriColors

    Page(
        title = tr("Verify Email"),
        subtitle = tr("Account Security")
    ) {
        MaitriCard {
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
                    text = tr("Verification Email Sent"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )

                Text(
                    text = tr("A secure verification link has been sent to {0}. Click the link in your email and tap Refresh below.", state.identity?.email),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = colors.textSecondary
                )
            }
        }

        Feedback(state)

        if (!state.busy) {
            Action(
                label = tr("Refresh Status"),
                icon = Icons.Rounded.Refresh,
                click = vm::refresh
            )

            MaitriOutlinedButton(
                label = if (state.cooldown == 0) tr("Resend Verification Email") else tr("Resend in {0}s", state.cooldown),
                icon = Icons.Rounded.Send,
                enabled = state.cooldown == 0,
                click = vm::resend
            )

            TextButton(
                onClick = vm::logout,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(tr("Sign Out"), color = colors.emergency)
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
            { Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.maitriColors.textSecondary) }
        } else null,
        trailingIcon = if (isPassword) {
            {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (passwordVisible) tr("Hide password") else tr("Show password"),
                        tint = MaterialTheme.maitriColors.textSecondary
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
