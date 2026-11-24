package `in`.maitri.app.notification

import `in`.maitri.app.tr

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
            is Initializing -> tr("Initializing...")
            is ConnectingToFirebase -> tr("Connecting to Firebase...")
            is TokenReady -> tr("Device token ready")
            is RegisteringWithBackend -> tr("Registering this device...")
            is Registered -> tr("Ready to receive test invitations")
            is Retrying -> tr("Retrying ({0})...", attempt)
            is ConfigurationError -> tr("Check Firebase setup")
            is PlayServicesUnavailable -> tr("Play Services unavailable")
            is NetworkError -> tr("Network unavailable")
            is AuthenticationRequired -> tr("Sign in required")
            is RegistrationFailed -> tr("Registration failed — Retry")
        }

    val badgeText: String
        get() = when (this) {
            is Registered -> "ACTIVE"
            is TokenReady -> "READY"
            is RegisteringWithBackend -> "REGISTERING"
            is ConnectingToFirebase -> "CONNECTING"
            is ConfigurationError -> tr("CONFIG REQUIRED")
            is PlayServicesUnavailable -> "UNAVAILABLE"
            is NetworkError -> "OFFLINE"
            is AuthenticationRequired -> tr("SIGN IN")
            is RegistrationFailed -> "FAILED"
            is Retrying -> "RETRYING"
            is Initializing -> "INIT"
        }

    val isVerifiedActive: Boolean
        get() = this is Registered
}
