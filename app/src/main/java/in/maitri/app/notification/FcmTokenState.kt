package `in`.maitri.app.notification

/**
 * Strongly typed lifecycle states for Firebase Cloud Messaging (FCM) registration and backend sync.
 * Prevents ambiguous permanent "Pending initialization" status and distinguishes between
 * token acquisition, backend registration, configuration errors, and network issues.
 */
sealed class FcmTokenState {
    object Initializing : FcmTokenState()
    object ConnectingToFirebase : FcmTokenState()
    data class TokenReady(val token: String) : FcmTokenState()
    data class RegisteringWithBackend(val token: String) : FcmTokenState()
    data class Registered(val token: String, val registeredAt: Long = System.currentTimeMillis()) : FcmTokenState()
    data class Retrying(val attempt: Int, val reason: String) : FcmTokenState()
    data class ConfigurationError(val message: String) : FcmTokenState()
    data class PlayServicesUnavailable(val errorCode: Int, val message: String) : FcmTokenState()
    data class NetworkError(val message: String) : FcmTokenState()
    data class AuthenticationRequired(val token: String? = null) : FcmTokenState()
    data class RegistrationFailed(val reason: String, val canRetry: Boolean = true) : FcmTokenState()

    val displayStatus: String
        get() = when (this) {
            is Initializing -> "Initializing..."
            is ConnectingToFirebase -> "Connecting to Firebase..."
            is TokenReady -> "Device token ready"
            is RegisteringWithBackend -> "Registering this device..."
            is Registered -> "Ready to receive test invitations"
            is Retrying -> "Retrying ($attempt)..."
            is ConfigurationError -> "Check Firebase setup"
            is PlayServicesUnavailable -> "Play Services unavailable"
            is NetworkError -> "Network unavailable"
            is AuthenticationRequired -> "Sign in required"
            is RegistrationFailed -> "Registration failed — Retry"
        }

    val badgeText: String
        get() = when (this) {
            is Registered -> "ACTIVE"
            is TokenReady -> "READY"
            is RegisteringWithBackend -> "REGISTERING"
            is ConnectingToFirebase -> "CONNECTING"
            is ConfigurationError -> "CONFIG REQUIRED"
            is PlayServicesUnavailable -> "UNAVAILABLE"
            is NetworkError -> "OFFLINE"
            is AuthenticationRequired -> "SIGN IN"
            is RegistrationFailed -> "FAILED"
            is Retrying -> "RETRYING"
            is Initializing -> "INIT"
        }

    val isVerifiedActive: Boolean
        get() = this is Registered
}
