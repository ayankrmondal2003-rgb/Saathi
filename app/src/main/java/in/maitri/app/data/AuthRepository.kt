package `in`.maitri.app.data

import kotlinx.coroutines.flow.Flow

data class Identity(val uid: String, val name: String, val email: String,
                    val verified: Boolean, val createdAt: Long?, val phone: String? = null)

/**
 * Matches the server rule in backend/app/core.py: a verified email, or a phone number. Firebase only
 * attaches a phone number to an account after it has been confirmed by OTP.
 */
fun accountVerified(emailVerified: Boolean, phone: String?): Boolean = emailVerified || !phone.isNullOrBlank()

interface AuthRepository {
    val sessions: Flow<Identity?>
    val current: Identity?
    suspend fun login(email: String, password: String)
    suspend fun register(name: String, email: String, password: String)
    suspend fun sendVerification()
    suspend fun refresh()
    suspend fun reset(email: String)
    fun logout()
    fun verifyPhone(
        activity: android.app.Activity,
        phone: String,
        onCodeSent: (verificationId: String) -> Unit,
        onCompleted: () -> Unit,
        onFailed: (Exception) -> Unit
    ) {
        onFailed(UnsupportedOperationException("Phone authentication is not configured for this provider."))
    }
    suspend fun signInWithPhoneCode(verificationId: String, code: String) {
        throw UnsupportedOperationException("Phone authentication is not configured for this provider.")
    }
    suspend fun signInWithGoogle(idToken: String) {
        throw UnsupportedOperationException("Google sign-in is not configured for this provider.")
    }
}
