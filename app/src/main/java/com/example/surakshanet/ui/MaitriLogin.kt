package com.example.surakshanet.ui

import android.app.Activity
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.surakshanet.R
import com.example.surakshanet.state.AuthState
import com.example.surakshanet.state.AuthViewModel

/**
 * Screen 03 — Login / Sign-up.
 * Strict SVG fidelity matching design-reference/crops/light-03.svg and dark-03.svg.
 */
@Composable
fun MaitriLogin(
    state: AuthState,
    auth: AuthViewModel,
    isDemoMode: Boolean = false
) {
    val p = maitriPalette()
    val context = LocalContext.current
    val activity = context as? Activity

    var selectedTab by rememberSaveable { mutableIntStateOf(0) } // 0: Log in, 1: Sign up
    var phone by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    var showForgotDialog by rememberSaveable { mutableStateOf(false) }
    var resetEmail by rememberSaveable { mutableStateOf("") }

    var showOtpDialog by rememberSaveable { mutableStateOf(false) }
    var otpCode by rememberSaveable { mutableStateOf("") }

    MaitriLoginContent(
        p = p,
        selectedTab = selectedTab,
        onTabSelected = {
            selectedTab = it
            auth.clearMessage()
        },
        phone = phone,
        onPhoneChange = { phone = it },
        email = email,
        onEmailChange = { email = it },
        password = password,
        onPasswordChange = { password = it },
        passwordVisible = passwordVisible,
        onPasswordVisibilityToggle = { passwordVisible = !passwordVisible },
        onContinueWithPhone = {
            val fullPhone = if (phone.startsWith("+")) phone else "+91$phone"
            auth.sendPhoneOtp(activity, fullPhone) {
                showOtpDialog = true
            }
        },
        onSubmitEmail = {
            if (isDemoMode && email.isBlank() && password.isBlank()) {
                auth.submit(null, "demo@suraksha.net", "demo1234")
            } else if (selectedTab == 0) {
                auth.submit(null, email, password)
            } else {
                val name = email.substringBefore("@").replace(".", " ").replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                auth.submit(name.ifBlank { "User" }, email, password, password)
            }
        },
        onForgotPassword = {
            resetEmail = email
            showForgotDialog = true
        },
        isBusy = state.busy,
        message = state.message,
        isDemoMode = isDemoMode
    )

    if (showForgotDialog) {
        AlertDialog(
            onDismissRequest = { showForgotDialog = false },
            containerColor = p.surface,
            titleContentColor = p.primary,
            textContentColor = p.secondary,
            title = { Text("Reset password", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter your email address to receive a secure password reset link.")
                    OutlinedTextField(
                        value = resetEmail,
                        onValueChange = { resetEmail = it },
                        label = { Text("Email address") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = p.accent,
                            unfocusedBorderColor = p.divider,
                            focusedTextColor = p.primary,
                            unfocusedTextColor = p.primary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showForgotDialog = false
                        auth.reset(resetEmail)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = Color.White)
                ) { Text("Send reset link") }
            },
            dismissButton = {
                TextButton(onClick = { showForgotDialog = false }) {
                    Text("Cancel", color = p.secondary)
                }
            }
        )
    }

    if (showOtpDialog) {
        AlertDialog(
            onDismissRequest = { showOtpDialog = false },
            containerColor = p.surface,
            titleContentColor = p.primary,
            textContentColor = p.secondary,
            title = { Text("Enter SMS code", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the 6-digit verification code sent to your phone.")
                    OutlinedTextField(
                        value = otpCode,
                        onValueChange = { if (it.length <= 6) otpCode = it },
                        label = { Text("6-digit code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = p.accent,
                            unfocusedBorderColor = p.divider,
                            focusedTextColor = p.primary,
                            unfocusedTextColor = p.primary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showOtpDialog = false
                        auth.submitPhoneOtp(otpCode)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = Color.White)
                ) { Text("Verify code") }
            },
            dismissButton = {
                TextButton(onClick = { showOtpDialog = false }) {
                    Text("Cancel", color = p.secondary)
                }
            }
        )
    }
}

@Composable
internal fun MaitriLoginContent(
    p: MaitriPalette,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    phone: String,
    onPhoneChange: (String) -> Unit,
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onPasswordVisibilityToggle: () -> Unit,
    onContinueWithPhone: () -> Unit,
    onSubmitEmail: () -> Unit,
    onForgotPassword: () -> Unit,
    isBusy: Boolean,
    message: String?,
    isDemoMode: Boolean = false
) {
    val focusManager = LocalFocusManager.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background)
            .safeDrawingPadding()
    ) {
        val contentHeight = maxOf(maxHeight, 766.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = contentHeight)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(14.dp))

            // Brand Header: Logo mark + "maitri" Wordmark
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.maitri_logo),
                    contentDescription = null,
                    modifier = Modifier.size(38.dp)
                )
                Spacer(Modifier.width(10.dp))
                Image(
                    painter = painterResource(if (p.dark) R.drawable.maitri_wordmark_dark else R.drawable.maitri_wordmark_light),
                    contentDescription = stringResource(R.string.maitri_brand),
                    modifier = Modifier.height(24.dp)
                )
            }

            Spacer(Modifier.height(28.dp))

            // Title & Subtitle
            Text(
                text = "Welcome to maitri",
                color = p.primary,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "A little peace of mind starts here.",
                color = p.secondary,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(22.dp))

            // Pill Tab Toggle: "Log in" vs "Sign up" (342 x 44, rx 22)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(p.surface)
                    .padding(4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val halfWidth = maxWidth / 2
                    val indicatorOffset by animateDpAsState(
                        targetValue = if (selectedTab == 0) 0.dp else halfWidth,
                        animationSpec = tween(durationMillis = 200),
                        label = "pillIndicator"
                    )

                    // Active background pill
                    Box(
                        modifier = Modifier
                            .offset(x = indicatorOffset)
                            .width(halfWidth)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(18.dp))
                            .background(p.card)
                    )

                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onTabSelected(0) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Log in",
                                color = if (selectedTab == 0) p.accent else p.secondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onTabSelected(1) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Sign up",
                                color = if (selectedTab == 1) p.accent else p.secondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Phone Number Input Section
            Text(
                text = "Phone number",
                color = p.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(p.surface)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "+91",
                    color = p.primary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = p.secondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(22.dp)
                        .background(p.divider)
                )
                Spacer(Modifier.width(12.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (phone.isEmpty()) {
                        Text(
                            text = "Enter phone number",
                            color = p.secondary,
                            fontSize = 14.sp
                        )
                    }
                    BasicTextField(
                        value = phone,
                        onValueChange = onPhoneChange,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        textStyle = TextStyle(color = p.primary, fontSize = 14.sp),
                        cursorBrush = SolidColor(p.accent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // "Continue with phone" Button (342 x 44, rx 22)
            Button(
                onClick = {
                    focusManager.clearFocus()
                    onContinueWithPhone()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = p.accent,
                    contentColor = if (p.dark) p.background else Color.White
                ),
                elevation = null
            ) {
                Text(
                    text = "Continue with phone",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(22.dp))

            // Divider: "or use email address"
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(p.divider)
                )
                Text(
                    text = "or use email & password",
                    color = p.secondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(p.divider)
                )
            }

            Spacer(Modifier.height(22.dp))

            // Email Address Field
            Text(
                text = "Email address",
                color = p.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(p.surface)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Email,
                    contentDescription = null,
                    tint = p.secondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (email.isEmpty()) {
                        Text(
                            text = "you@example.com",
                            color = p.secondary,
                            fontSize = 14.sp
                        )
                    }
                    BasicTextField(
                        value = email,
                        onValueChange = onEmailChange,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        textStyle = TextStyle(color = p.primary, fontSize = 14.sp),
                        cursorBrush = SolidColor(p.accent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Password Field
            Text(
                text = "Password",
                color = p.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(p.surface)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = p.secondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (password.isEmpty()) {
                        Text(
                            text = "Enter your password",
                            color = p.secondary,
                            fontSize = 14.sp
                        )
                    }
                    BasicTextField(
                        value = password,
                        onValueChange = onPasswordChange,
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        keyboardActions = KeyboardActions(onDone = {
                            focusManager.clearFocus()
                            onSubmitEmail()
                        }),
                        textStyle = TextStyle(color = p.primary, fontSize = 14.sp),
                        cursorBrush = SolidColor(p.accent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                IconButton(
                    onClick = onPasswordVisibilityToggle,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = "Toggle password visibility",
                        tint = p.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // Forgot Password Link
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = "Forgot password?",
                    color = p.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable(onClick = onForgotPassword)
                        .padding(vertical = 4.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            // Action Button: "Log in with email" / "Sign up with email" (342 x 40-44, rx 20-22)
            Button(
                onClick = {
                    focusManager.clearFocus()
                    onSubmitEmail()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = p.card,
                    contentColor = p.accent
                ),
                elevation = null
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = p.accent,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = if (selectedTab == 0) "Log in with email" else "Sign up with email",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (message != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = message,
                    color = p.accent,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                )
            }

            if (isDemoMode) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Demo Mode: Leave fields empty and tap above to enter with local demo account.",
                    color = p.secondary,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                )
            }

            Spacer(Modifier.height(18.dp))

            // Terms & Privacy notice
            Text(
                text = "By continuing, you agree to the Terms of Service\nand Privacy Policy.",
                color = p.secondary,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(24.dp))

            // The SVG frame's home indicator belongs to the device chrome, not the app.
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Preview(name = "Maitri 03 Light", widthDp = 390, heightDp = 844)
@Composable
private fun MaitriLoginLightPreview() {
    MaitriLoginContent(
        p = MaitriPalette(dark = false),
        selectedTab = 0,
        onTabSelected = {},
        phone = "",
        onPhoneChange = {},
        email = "",
        onEmailChange = {},
        password = "",
        onPasswordChange = {},
        passwordVisible = false,
        onPasswordVisibilityToggle = {},
        onContinueWithPhone = {},
        onSubmitEmail = {},
        onForgotPassword = {},
        isBusy = false,
        message = null
    )
}

@Preview(name = "Maitri 03 Dark", widthDp = 390, heightDp = 844)
@Composable
private fun MaitriLoginDarkPreview() {
    MaitriLoginContent(
        p = MaitriPalette(dark = true),
        selectedTab = 0,
        onTabSelected = {},
        phone = "",
        onPhoneChange = {},
        email = "",
        onEmailChange = {},
        password = "",
        onPasswordChange = {},
        passwordVisible = false,
        onPasswordVisibilityToggle = {},
        onContinueWithPhone = {},
        onSubmitEmail = {},
        onForgotPassword = {},
        isBusy = false,
        message = null
    )
}
