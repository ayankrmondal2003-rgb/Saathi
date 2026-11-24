package `in`.maitri.app.data

import android.content.SharedPreferences

interface UserProfileRepository {
    fun load(identity: Identity): UserProfile
    fun savePreferences(uid: String, responder: Responder)
}

class LocalUserProfileRepository(private val prefs: SharedPreferences) : UserProfileRepository {
    override fun load(identity: Identity): UserProfile {
        require(identity.verified)
        val uid = identity.uid
        val enabled = prefs.getBoolean("$uid.optIn", false)
        return UserProfile(uid, identity.name, identity.email, identity.phone, identity.createdAt,
            Responder(enabled, enabled && prefs.getBoolean("$uid.available", false)))
    }
    override fun savePreferences(uid: String, responder: Responder) {
        prefs.edit().putBoolean("$uid.optIn", responder.enabled)
            .putBoolean("$uid.available", responder.available).apply()
    }
}
