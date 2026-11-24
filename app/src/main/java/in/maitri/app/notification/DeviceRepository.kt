package `in`.maitri.app.notification

import `in`.maitri.app.data.isMaitriVerified
import android.content.Context
import java.util.UUID
import java.util.concurrent.TimeUnit
import `in`.maitri.app.BuildConfig
import `in`.maitri.app.network.BackendClient
import `in`.maitri.app.network.DeviceRegistrationDto
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class DeviceRepository private constructor(private val context: Context) {

    private val _tokenState = MutableStateFlow<FcmTokenState>(FcmTokenState.Initializing)
    val tokenState: StateFlow<FcmTokenState> = _tokenState.asStateFlow()

    fun getOrCreateDeviceId(): String = synchronized(this) {
        val prefs = context.getSharedPreferences("device_installation", Context.MODE_PRIVATE)
        prefs.getString("id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("id", it).apply()
        }
    }

    suspend fun refreshAndRegister(explicitUser: String? = null, force: Boolean = false): FcmTokenState = withContext(Dispatchers.IO) {
        // 1. Verify FirebaseApp initialization
        val firebaseApp = runCatching { FirebaseApp.getInstance() }.getOrNull()

        if (firebaseApp == null) {
            val state = FcmTokenState.ConfigurationError(
                "The default Firebase app could not initialize from this build’s configuration."
            )
            _tokenState.value = state
            return@withContext state
        }

        // 2. Verify Google Play Services availability
        val gms = GoogleApiAvailability.getInstance()
        val gmsResult = gms.isGooglePlayServicesAvailable(context)
        if (gmsResult != ConnectionResult.SUCCESS) {
            val errorString = gms.getErrorString(gmsResult)
            val state = FcmTokenState.PlayServicesUnavailable(
                gmsResult,
                "Google Play services unavailable: $errorString (code $gmsResult)"
            )
            _tokenState.value = state
            return@withContext state
        }

        // 3. Connect to Firebase and fetch registration token asynchronously
        _tokenState.value = FcmTokenState.ConnectingToFirebase
        val token = try {
            val task = FirebaseMessaging.getInstance().token
            Tasks.await(task, 15, TimeUnit.SECONDS)
        } catch (e: Exception) {
            val isNetwork = e is java.io.IOException
            val state = if (isNetwork) {
                FcmTokenState.NetworkError("Network error while connecting to Firebase: ${e.localizedMessage ?: "timeout"}")
            } else {
                FcmTokenState.RegistrationFailed("FCM token retrieval failed: ${e.localizedMessage ?: e.javaClass.simpleName}")
            }
            _tokenState.value = state
            return@withContext state
        }

        if (token.isNullOrBlank()) {
            val state = FcmTokenState.RegistrationFailed("Firebase returned an empty registration token.")
            _tokenState.value = state
            return@withContext state
        }

        // Token acquired
        SosFirebaseMessagingService.saveLocalFcmToken(context, token)
        _tokenState.value = FcmTokenState.TokenReady(token)

        registerToken(token, explicitUser)
    }

    suspend fun handleNewToken(token: String) = withContext(Dispatchers.IO) {
        SosFirebaseMessagingService.saveLocalFcmToken(context, token)
        _tokenState.value = FcmTokenState.TokenReady(token)
        registerToken(token, null)
        Unit
    }

    private suspend fun registerToken(token: String, explicitUser: String?): FcmTokenState {
        // 4. Authenticated backend registration
        val authUser = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
        val effectiveUid = authUser?.uid

        if (authUser == null || !authUser.isMaitriVerified() || effectiveUid.isNullOrBlank() ||
            (explicitUser != null && explicitUser != effectiveUid)) {
            val state = FcmTokenState.AuthenticationRequired(token)
            _tokenState.value = state
            return state
        }

        if (BuildConfig.API_BASE_URL.isBlank()) {
            val state = FcmTokenState.ConfigurationError("Backend API URL (maitriDevApiUrl) is not configured.")
            _tokenState.value = state
            return state
        }

        _tokenState.value = FcmTokenState.RegisteringWithBackend(token)
        try {
            val deviceId = getOrCreateDeviceId()
            val dto = DeviceRegistrationDto(
                fcmToken = token,
                platform = "ANDROID",
                deviceIdentifier = deviceId
            )
            val response = BackendClient.api.registerDevice(dto, `in`.maitri.app.network.ExpectedAccount(effectiveUid))
            check(response.status == "REGISTERED") { "Backend did not confirm device registration." }
            val registeredState = FcmTokenState.Registered(token)
            _tokenState.value = registeredState
            return registeredState
        } catch (e: Exception) {
            val isNetwork = e is java.io.IOException
            val state = if (isNetwork) {
                FcmTokenState.NetworkError("Backend registration unreachable: ${e.localizedMessage ?: "Connection error"}")
            } else {
                FcmTokenState.RegistrationFailed("Backend registration rejected: ${e.localizedMessage ?: e.javaClass.simpleName}")
            }
            _tokenState.value = state
            return state
        }
    }

    suspend fun registerCurrentToken(): Boolean = withContext(Dispatchers.IO) {
        val result = refreshAndRegister()
        result is FcmTokenState.Registered
    }

    suspend fun unregisterDevice(): Boolean = withContext(Dispatchers.IO) {
        if (BuildConfig.API_BASE_URL.isBlank()) return@withContext false
        val token = SosFirebaseMessagingService.getLocalFcmToken(context) ?: return@withContext false
        return@withContext try {
            val res = BackendClient.api.unregisterDevice(token)
            if (res.isSuccessful) {
                _tokenState.value = FcmTokenState.TokenReady(token)
            }
            res.isSuccessful
        } catch (_: Exception) {
            false
        }
    }

    suspend fun sendTestEmergencyAlert(): Result<Int> = withContext(Dispatchers.IO) {
        if (BuildConfig.API_BASE_URL.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Backend API URL not configured"))
        }
        val current = _tokenState.value
        if (current !is FcmTokenState.Registered) {
            return@withContext Result.failure(
                IllegalStateException("Device is not registered with backend (Status: ${current.displayStatus}). Please retry registration first.")
            )
        }
        return@withContext try {
            val resp = BackendClient.api.sendTestAlert(getOrCreateDeviceId())
            check(resp.status == "FCM_ACCEPTED" && resp.deviceCount == 1) { "Firebase acceptance for this phone was not confirmed." }
            Result.success(resp.deviceCount)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getDiagnosticSummary(): Map<String, String> {
        val hasFirebase = runCatching { FirebaseApp.getInstance() }.isSuccess
        val gmsCode = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        val token = SosFirebaseMessagingService.getLocalFcmToken(context)
        val deviceId = getOrCreateDeviceId()

        return mapOf(
            "Firebase Initialized" to if (hasFirebase) "YES (App configured)" else "NO (default Firebase app unavailable)",
            "Google Play Services" to if (gmsCode == ConnectionResult.SUCCESS) "Available (Code 0)" else "Unavailable (Code $gmsCode)",
            "Lifecycle State" to _tokenState.value.displayStatus,
            "State Class" to _tokenState.value.javaClass.simpleName,
            "FCM Token" to if (!token.isNullOrBlank()) "${token.take(12)}... (len ${token.length})" else "None",
            "Device Identifier" to "${deviceId.take(8)}...",
            "Backend Base URL" to BuildConfig.API_BASE_URL.ifBlank { "Not configured" }
        )
    }

    companion object {
        @Volatile
        private var instance: DeviceRepository? = null

        fun get(context: Context): DeviceRepository =
            instance ?: synchronized(this) {
                instance ?: DeviceRepository(context.applicationContext).also { instance = it }
            }
    }
}
