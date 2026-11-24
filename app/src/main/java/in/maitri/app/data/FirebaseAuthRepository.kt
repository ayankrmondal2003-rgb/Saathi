package `in`.maitri.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** The server's account rule (verified email or OTP-confirmed phone) for a Firebase user; see [accountVerified]. */
fun FirebaseUser.isMaitriVerified(): Boolean = accountVerified(isEmailVerified, phoneNumber)

class FirebaseAuthRepository(private val auth: FirebaseAuth) : AuthRepository {
    override val current get() = auth.currentUser?.let {
        Identity(it.uid, it.displayName.orEmpty(), it.email.orEmpty(), it.isMaitriVerified(),
            it.metadata?.creationTimestamp, it.phoneNumber)
    }

    override val sessions = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(current) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    override suspend fun login(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password).await()
    }

    override suspend fun register(name: String, email: String, password: String) {
        val user = requireNotNull(auth.createUserWithEmailAndPassword(email, password).await().user)
        user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name).build()).await()
    }

    override suspend fun sendVerification() { requireNotNull(auth.currentUser).sendEmailVerification().await() }
    override suspend fun refresh() {
        val user = requireNotNull(auth.currentUser)
        user.reload().await()
        if (user.isMaitriVerified()) user.getIdToken(true).await()
    }
    override suspend fun reset(email: String) { auth.sendPasswordResetEmail(email).await() }
    override fun logout() = auth.signOut()

    override fun verifyPhone(
        activity: android.app.Activity,
        phone: String,
        onCodeSent: (verificationId: String) -> Unit,
        onCompleted: () -> Unit,
        onFailed: (Exception) -> Unit
    ) {
        val options = com.google.firebase.auth.PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phone)
            .setTimeout(60L, java.util.concurrent.TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(object : com.google.firebase.auth.PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: com.google.firebase.auth.PhoneAuthCredential) {
                    auth.signInWithCredential(credential).addOnCompleteListener { task ->
                        if (task.isSuccessful) onCompleted()
                        else onFailed(task.exception ?: Exception("Phone sign-in failed"))
                    }
                }
                override fun onVerificationFailed(e: com.google.firebase.FirebaseException) {
                    onFailed(e)
                }
                override fun onCodeSent(id: String, token: com.google.firebase.auth.PhoneAuthProvider.ForceResendingToken) {
                    onCodeSent(id)
                }
            })
            .build()
        com.google.firebase.auth.PhoneAuthProvider.verifyPhoneNumber(options)
    }

    override suspend fun signInWithGoogle(idToken: String) {
        auth.signInWithCredential(com.google.firebase.auth.GoogleAuthProvider.getCredential(idToken, null)).await()
    }

    override suspend fun signInWithPhoneCode(verificationId: String, code: String) {
        val credential = com.google.firebase.auth.PhoneAuthProvider.getCredential(verificationId, code)
        auth.signInWithCredential(credential).await()
    }
}
