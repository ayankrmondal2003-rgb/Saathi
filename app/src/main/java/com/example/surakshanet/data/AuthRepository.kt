package com.example.surakshanet.data

import kotlinx.coroutines.flow.Flow

data class Identity(val uid: String, val name: String, val email: String,
                    val verified: Boolean, val createdAt: Long?, val phone: String? = null)

interface AuthRepository {
    val sessions: Flow<Identity?>
    val current: Identity?
    suspend fun login(email: String, password: String)
    suspend fun register(name: String, email: String, password: String)
    suspend fun sendVerification()
    suspend fun refresh()
    suspend fun reset(email: String)
    fun logout()
}
