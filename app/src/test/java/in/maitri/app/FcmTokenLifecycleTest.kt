package `in`.maitri.app

import `in`.maitri.app.notification.FcmTokenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmTokenLifecycleTest {

    @Test
    fun testFcmTokenStateDisplayStatusAndBadges() {
        val states = listOf(
            FcmTokenState.Initializing to Pair("Initializing...", "INIT"),
            FcmTokenState.ConnectingToFirebase to Pair("Connecting to Firebase...", "CONNECTING"),
            FcmTokenState.TokenReady("sample_token_abc") to Pair("Device token ready", "READY"),
            FcmTokenState.RegisteringWithBackend("sample_token_abc") to Pair("Registering this device...", "REGISTERING"),
            FcmTokenState.Registered("sample_token_abc") to Pair("Ready to receive test invitations", "ACTIVE"),
            FcmTokenState.Retrying(2, "Network reset") to Pair("Retrying (2)...", "RETRYING"),
            FcmTokenState.ConfigurationError("Missing google-services.json") to Pair("Check Firebase setup", "CONFIG REQUIRED"),
            FcmTokenState.PlayServicesUnavailable(2, "Service missing") to Pair("Play Services unavailable", "UNAVAILABLE"),
            FcmTokenState.NetworkError("Host unreachable") to Pair("Network unavailable", "OFFLINE"),
            FcmTokenState.AuthenticationRequired("sample_token_abc") to Pair("Sign in required", "SIGN IN"),
            FcmTokenState.RegistrationFailed("Invalid key", canRetry = true) to Pair("Registration failed — Retry", "FAILED")
        )

        for ((state, expected) in states) {
            assertEquals("Display status mismatch for ${state::class.simpleName}", expected.first, state.displayStatus)
            assertEquals("Badge text mismatch for ${state::class.simpleName}", expected.second, state.badgeText)
        }
    }

    @Test
    fun testIsVerifiedActiveOnlyForRegistered() {
        assertTrue(FcmTokenState.Registered("valid_token").isVerifiedActive)
        assertFalse(FcmTokenState.TokenReady("valid_token").isVerifiedActive)
        assertFalse(FcmTokenState.RegisteringWithBackend("valid_token").isVerifiedActive)
        assertFalse(FcmTokenState.ConnectingToFirebase.isVerifiedActive)
        assertFalse(FcmTokenState.ConfigurationError("No config").isVerifiedActive)
        assertFalse(FcmTokenState.PlayServicesUnavailable(1, "Missing").isVerifiedActive)
        assertFalse(FcmTokenState.NetworkError("Timeout").isVerifiedActive)
        assertFalse(FcmTokenState.AuthenticationRequired("valid_token").isVerifiedActive)
        assertFalse(FcmTokenState.RegistrationFailed("Rejected").isVerifiedActive)
    }

    @Test
    fun testConfigurationErrorDetailsPreserved() {
        val errorMsg = "Firebase initialization failure: google-services.json not found in app/ directory."
        val state = FcmTokenState.ConfigurationError(errorMsg)
        assertEquals(errorMsg, state.message)
        assertEquals("CONFIG REQUIRED", state.badgeText)
        assertFalse(state.isVerifiedActive)
    }

    @Test
    fun testPlayServicesUnavailablePreservesErrorCode() {
        val state = FcmTokenState.PlayServicesUnavailable(2, "Service update required")
        assertEquals(2, state.errorCode)
        assertEquals("Service update required", state.message)
        assertEquals("UNAVAILABLE", state.badgeText)
    }

    @Test
    fun testRegistrationFailedSupportsRetryFlag() {
        val retryable = FcmTokenState.RegistrationFailed("Rate limited", canRetry = true)
        assertTrue(retryable.canRetry)

        val nonRetryable = FcmTokenState.RegistrationFailed("Fatal mismatch", canRetry = false)
        assertFalse(nonRetryable.canRetry)
    }
}
