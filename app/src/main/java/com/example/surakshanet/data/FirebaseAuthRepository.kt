package com.example.surakshanet.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class FirebaseAuthRepository(private val auth: FirebaseAuth) : AuthRepository {
    override val current get() = auth.currentUser?.let {
        Identity(it.uid, it.displayName.orEmpty(), it.email.orEmpty(), it.isEmailVerified,
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
        if (user.isEmailVerified) user.getIdToken(true).await()
    }
    override suspend fun reset(email: String) { auth.sendPasswordResetEmail(email).await() }
    override fun logout() = auth.signOut()
}
