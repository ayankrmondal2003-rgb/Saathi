package `in`.maitri.app.state

import `in`.maitri.app.tr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import `in`.maitri.app.data.*
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class Gate { LOADING, SIGNED_OUT, VERIFY, HOME }
data class AuthState(val ready: Boolean = false, val identity: Identity? = null, val busy: Boolean = false,
                     val message: String? = null, val cooldown: Int = 0) {
    val gate get() = when {
        !ready -> Gate.LOADING
        identity == null -> Gate.SIGNED_OUT
        !identity.verified -> Gate.VERIFY
        else -> Gate.HOME
    }
}

object AuthForm {
    fun email(value: String) = value.trim().matches(Regex("[^@\\s]+@[^@\\s.]+(?:\\.[^@\\s.]+)+"))
    fun validate(name: String?, email: String, password: String, confirm: String): String? = when {
        name != null && name.isBlank() -> tr("Enter your full name.")
        !email(email) -> tr("Enter a valid email address.")
        password.isBlank() -> tr("Enter your password.")
        name != null && password.length < 6 -> tr("Use at least six characters.")
        name != null && password != confirm -> tr("The passwords must match.")
        else -> null
    }
}

class AuthViewModel(private val repo: AuthRepository) : ViewModel() {
    private val mutable = MutableStateFlow(AuthState())
    val state = mutable.asStateFlow()
    private var cooldownJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            repo.sessions.collect { user ->
                if (user?.uid != mutable.value.identity?.uid) {
                    cooldownJob?.cancel()
                    mutable.update { it.copy(identity = user, ready = true, cooldown = 0, message = null) }
                } else mutable.update { it.copy(identity = user, ready = true) }
            }
        }
    }

    fun clearMessage() { mutable.update { it.copy(message = null) } }
    private fun message(text: String) { mutable.update { it.copy(message = text) } }

    private fun perform(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: GoogleSignInCancelled) { /* The user closed the account sheet. */ }
            catch (e: Exception) {
                message(when (e) {
                    is GoogleSignInUnavailable -> e.userMessage
                    is FirebaseNetworkException -> tr("Connection failed. Check your network and retry.")
                    is FirebaseTooManyRequestsException -> tr("Too many requests. Please try again later.")
                    is FirebaseAuthException -> when (e.errorCode) {
                        "ERROR_WEAK_PASSWORD" -> tr("Choose a stronger password that meets the account policy.")
                        "ERROR_EMAIL_ALREADY_IN_USE" -> tr("Unable to create this account. Try signing in or resetting your password.")
                        "ERROR_USER_DISABLED" -> tr("This account has been disabled.")
                        "ERROR_OPERATION_NOT_ALLOWED" -> tr("Email and password sign-in is unavailable.")
                        "ERROR_INVALID_CREDENTIAL", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND" -> tr("Check your email and password.")
                        else -> tr("The account request failed. Please try again.")
                    }
                    else -> tr("Unable to complete the request. Please try again.")
                })
            } finally { mutable.update { it.copy(identity = repo.current, busy = false) } }
        }
    }

    fun submit(name: String?, email: String, password: String, confirm: String = "") {
        AuthForm.validate(name, email, password, confirm)?.let { message(it); return }
        perform {
            if (name == null) repo.login(email.trim(), password)
            else {
                repo.register(name.trim(), email.trim(), password)
                verificationEmail()
            }
        }
    }
    private suspend fun verificationEmail() {
        repo.sendVerification()
        mutable.update { it.copy(identity = repo.current, cooldown = 60, message = tr("Verification email sent. Check your inbox and spam folder.")) }
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            repeat(60) { delay(1000); mutable.update { it.copy(cooldown = (it.cooldown - 1).coerceAtLeast(0)) } }
        }
    }
    /** [idToken] shows Google's account picker; Firebase then signs in (or creates) the matching account. */
    fun signInWithGoogle(idToken: suspend () -> String) = perform {
        val token = idToken()
        try { repo.signInWithGoogle(token) }
        catch (e: FirebaseAuthException) {
            if (e.errorCode == "ERROR_OPERATION_NOT_ALLOWED")
                throw GoogleSignInUnavailable(tr("Google sign-in is not enabled for Maitri yet. Use phone or email sign-in."), e)
            throw e
        }
    }
    fun resend() { if (mutable.value.cooldown == 0) perform { verificationEmail() } }
    fun refresh() = perform {
        repo.refresh()
        if (repo.current?.verified == false) message(tr("Still awaiting verification. Open the link in your email first."))
    }
    fun reset(email: String) {
        if (!AuthForm.email(email)) { message(tr("Enter a valid email address.")); return }
        perform { repo.reset(email.trim()); message(tr("If an account is eligible, a reset link will arrive by email.")) }
    }
    fun logout() {
        if (mutable.value.busy) return
        repo.logout()
        cooldownJob?.cancel()
        mutable.value = AuthState(ready = true)
    }

    private var phoneVerificationId: String? = null

    fun sendPhoneOtp(activity: android.app.Activity?, phone: String, onPromptCode: () -> Unit) {
        val clean = phone.trim()
        if (clean.length < 10) {
            message(tr("Enter a valid phone number with country code."))
            return
        }
        if (activity == null) {
            message(tr("Unable to start phone verification: Activity context required."))
            return
        }
        mutable.update { it.copy(busy = true, message = null) }
        try {
            repo.verifyPhone(
                activity = activity,
                phone = clean,
                onCodeSent = { id ->
                    phoneVerificationId = id
                    mutable.update { it.copy(busy = false, message = tr("Code sent. Enter the 6-digit code.")) }
                    onPromptCode()
                },
                onCompleted = {
                    mutable.update { it.copy(busy = false, identity = repo.current, message = null) }
                },
                onFailed = { e ->
                    val userMsg = when {
                        e is FirebaseAuthException && e.errorCode == "ERROR_OPERATION_NOT_ALLOWED" ->
                            tr("Phone authentication is not enabled in Firebase Console. Please use email sign-in.")
                        e is FirebaseTooManyRequestsException ->
                            tr("SMS quota exceeded. Please try again later or use email sign-in.")
                        e.message?.contains("SHA-256", ignoreCase = true) == true || e.message?.contains("app verification", ignoreCase = true) == true ->
                            tr("Firebase Phone Auth requires app SHA-256 and Play Integrity configured in Firebase Console.")
                        else -> e.localizedMessage ?: tr("Phone verification failed. Use email sign-in.")
                    }
                    mutable.update { it.copy(busy = false, message = userMsg) }
                }
            )
        } catch (e: Exception) {
            mutable.update { it.copy(busy = false, message = e.localizedMessage ?: tr("Phone authentication is not available.")) }
        }
    }

    fun submitPhoneOtp(code: String) {
        val id = phoneVerificationId
        if (id.isNullOrBlank()) {
            message(tr("No active phone verification. Please request a new code."))
            return
        }
        if (code.trim().length < 6) {
            message(tr("Enter the full 6-digit verification code."))
            return
        }
        perform {
            repo.signInWithPhoneCode(id, code.trim())
            phoneVerificationId = null
        }
    }
}
