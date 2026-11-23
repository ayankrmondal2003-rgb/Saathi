package `in`.maitri.app.data

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

class LocalAuthRepository private constructor(private val prefs: SharedPreferences) : AuthRepository {
    private val _sessions = MutableStateFlow<Identity?>(restoreSession())
    override val sessions: Flow<Identity?> = _sessions.asStateFlow()
    override val current: Identity? get() = _sessions.value

    init {
        seedDemoUser()
    }

    private fun restoreSession(): Identity? = runCatching {
        val jsonStr = prefs.getString(KEY_CURRENT_USER, null) ?: return null
        val obj = JSONObject(jsonStr)
        Identity(
            uid = obj.getString("uid"),
            name = obj.getString("name"),
            email = obj.getString("email"),
            verified = obj.optBoolean("verified", true),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            phone = obj.optString("phone").takeIf { it.isNotEmpty() }
        )
    }.getOrNull()

    private fun seedDemoUser() {
        val users = loadUsers()
        var migrated = false
        for (key in users.keys()) {
            val account = users.getJSONObject(key)
            if (account.has("password")) {
                storePassword(account, account.getString("password"))
                account.remove("password")
                migrated = true
            }
        }
        if (migrated) prefs.edit().putString(KEY_USERS, users.toString()).apply()
        if (!users.has(DEMO_EMAIL)) {
            val demoObj = JSONObject().apply {
                put("uid", DEMO_UID)
                put("name", "Demo User")
                put("email", DEMO_EMAIL)
                storePassword(this, DEMO_PASSWORD)
                put("verified", true)
                put("createdAt", System.currentTimeMillis())
            }
            users.put(DEMO_EMAIL, demoObj)
            prefs.edit().putString(KEY_USERS, users.toString()).apply()
        }
    }

    private fun derivePassword(password: String, salt: ByteArray): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, 210000, 256)
        return try {
            javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun storePassword(account: JSONObject, password: String) {
        val salt = ByteArray(16).also(java.security.SecureRandom()::nextBytes)
        val encoder = java.util.Base64.getEncoder()
        account.put("salt", encoder.encodeToString(salt))
        account.put("passwordHash", encoder.encodeToString(derivePassword(password, salt)))
    }

    private fun loadUsers(): JSONObject = runCatching {
        val str = prefs.getString(KEY_USERS, null) ?: return JSONObject()
        JSONObject(str)
    }.getOrDefault(JSONObject())

    override suspend fun login(email: String, password: String) {
        val users = loadUsers()
        val key = email.lowercase().trim()
        if (!users.has(key)) {
            throw FirebaseAuthException("ERROR_USER_NOT_FOUND", "Check your email and password.")
        }
        val userObj = users.getJSONObject(key)
        val salt = java.util.Base64.getDecoder().decode(userObj.getString("salt"))
        val expected = java.util.Base64.getDecoder().decode(userObj.getString("passwordHash"))
        if (!java.security.MessageDigest.isEqual(expected, derivePassword(password, salt))) {
            throw FirebaseAuthException("ERROR_WRONG_PASSWORD", "Check your email and password.")
        }
        val identity = Identity(
            uid = userObj.getString("uid"),
            name = userObj.getString("name"),
            email = userObj.getString("email"),
            verified = userObj.optBoolean("verified", false),
            createdAt = userObj.optLong("createdAt", System.currentTimeMillis()),
            phone = userObj.optString("phone").takeIf { it.isNotEmpty() }
        )
        saveCurrentSession(identity)
    }

    override suspend fun register(name: String, email: String, password: String) {
        val users = loadUsers()
        val key = email.lowercase().trim()
        if (users.has(key)) {
            throw FirebaseAuthException("ERROR_EMAIL_ALREADY_IN_USE", "Unable to create this account. Try signing in or resetting your password.")
        }
        val uid = UUID.randomUUID().toString()
        val identity = Identity(
            uid = uid,
            name = name.trim(),
            email = email.trim(),
            verified = false,
            createdAt = System.currentTimeMillis()
        )
        val userObj = JSONObject().apply {
            put("uid", uid)
            put("name", identity.name)
            put("email", identity.email)
            storePassword(this, password)
            put("verified", false)
            put("createdAt", identity.createdAt)
        }
        users.put(key, userObj)
        prefs.edit().putString(KEY_USERS, users.toString()).apply()
        saveCurrentSession(identity)
    }

    override suspend fun sendVerification() {
        // Verification email simulated for local demo mode
    }

    override suspend fun refresh() {
        val active = current ?: return
        val users = loadUsers()
        val key = active.email.lowercase().trim()
        if (users.has(key)) {
            val userObj = users.getJSONObject(key)
            userObj.put("verified", true)
            prefs.edit().putString(KEY_USERS, users.toString()).apply()
            val verifiedIdentity = active.copy(verified = true)
            saveCurrentSession(verifiedIdentity)
        }
    }

    override suspend fun reset(email: String) {
        // Password reset simulated for local demo mode
    }

    override fun logout() {
        prefs.edit().remove(KEY_CURRENT_USER).apply()
        _sessions.value = null
    }

    private fun saveCurrentSession(identity: Identity) {
        val obj = JSONObject().apply {
            put("uid", identity.uid)
            put("name", identity.name)
            put("email", identity.email)
            put("verified", identity.verified)
            put("createdAt", identity.createdAt ?: System.currentTimeMillis())
            identity.phone?.let { put("phone", it) }
        }
        prefs.edit().putString(KEY_CURRENT_USER, obj.toString()).apply()
        _sessions.value = identity
    }

    companion object {
        const val DEMO_EMAIL = "demo@maitri.invalid"
        const val DEMO_PASSWORD = "password123"
        const val DEMO_UID = "demo-local-user-uuid"

        private const val PREFS_NAME = "local_auth_repo"
        private const val KEY_CURRENT_USER = "current_session_user"
        private const val KEY_USERS = "registered_local_users"

        @Volatile
        private var instance: LocalAuthRepository? = null

        fun get(context: Context): LocalAuthRepository =
            instance ?: synchronized(this) {
                instance ?: LocalAuthRepository(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}
