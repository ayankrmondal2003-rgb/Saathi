package com.example.surakshanet.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.surakshanet.data.*
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
        name != null && name.isBlank() -> "Enter your full name."
        !email(email) -> "Enter a valid email address."
        password.isBlank() -> "Enter your password."
        name != null && password.length < 6 -> "Use at least six characters."
        name != null && password != confirm -> "The passwords must match."
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
            catch (e: Exception) {
                message(when (e) {
                    is FirebaseNetworkException -> "Connection failed. Check your network and retry."
                    is FirebaseTooManyRequestsException -> "Too many requests. Please try again later."
                    is FirebaseAuthException -> when (e.errorCode) {
                        "ERROR_WEAK_PASSWORD" -> "Choose a stronger password that meets the account policy."
                        "ERROR_EMAIL_ALREADY_IN_USE" -> "Unable to create this account. Try signing in or resetting your password."
                        "ERROR_USER_DISABLED" -> "This account has been disabled."
                        "ERROR_OPERATION_NOT_ALLOWED" -> "Email and password sign-in is unavailable."
                        "ERROR_INVALID_CREDENTIAL", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND" -> "Check your email and password."
                        else -> "The account request failed. Please try again."
                    }
                    else -> "Unable to complete the request. Please try again."
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
        mutable.update { it.copy(identity = repo.current, cooldown = 60, message = "Verification email sent. Check your inbox and spam folder.") }
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            repeat(60) { delay(1000); mutable.update { it.copy(cooldown = (it.cooldown - 1).coerceAtLeast(0)) } }
        }
    }
    fun resend() { if (mutable.value.cooldown == 0) perform { verificationEmail() } }
    fun refresh() = perform {
        repo.refresh()
        if (repo.current?.verified == false) message("Still awaiting verification. Open the link in your email first.")
    }
    fun reset(email: String) {
        if (!AuthForm.email(email)) { message("Enter a valid email address."); return }
        perform { repo.reset(email.trim()); message("If an account is eligible, a reset link will arrive by email.") }
    }
    fun logout() {
        if (mutable.value.busy) return
        repo.logout()
        cooldownJob?.cancel()
        mutable.value = AuthState(ready = true)
    }
}
