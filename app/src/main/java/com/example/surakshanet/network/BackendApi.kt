package com.example.surakshanet.network

import com.example.surakshanet.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.android.gms.tasks.Tasks
import com.google.gson.annotations.SerializedName
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.IOException
import java.util.concurrent.TimeUnit

data class HealthDto(@SerializedName("status") val status: String, @SerializedName("service") val service: String)
data class ProfileDto(@SerializedName("display_name") val name: String, @SerializedName("email") val email: String,
                      @SerializedName("responder_opt_in") val optedIn: Boolean, @SerializedName("responder_available") val available: Boolean)
data class ProfileRequest(@SerializedName("display_name") val name: String, @SerializedName("responder_opt_in") val optedIn: Boolean,
                          @SerializedName("responder_available") val available: Boolean)
data class FixDto(@SerializedName("latitude") val latitude: Double, @SerializedName("longitude") val longitude: Double,
                  @SerializedName("recorded_at") val recordedAt: String, @SerializedName("is_mock") val mock: Boolean,
                  @SerializedName("accuracy_meters") val accuracyMeters: Double)
data class IncidentLocationDto(@SerializedName("latitude") val latitude: Double?, @SerializedName("longitude") val longitude: Double?,
    @SerializedName("status") val status: String, @SerializedName("accuracy_meters") val accuracyMeters: Double?,
    @SerializedName("recorded_at") val recordedAt: String?, @SerializedName("received_at") val receivedAt: String?,
    @SerializedName("is_mock") val mock: Boolean?, @SerializedName("is_test") val test: Boolean)
data class InvitationDto(@SerializedName("id") val id: String, @SerializedName("incident_id") val incidentId: String,
    @SerializedName("expires_at") val expiresAt: String, @SerializedName("status") val status: String)
data class DecisionDto(@SerializedName("decision") val decision: String)
data class StatusDto(@SerializedName("status") val status: String)
data class TestIncidentRequest(@SerializedName("is_test") val test: Boolean = true,
                               @SerializedName("location") val location: FixDto? = null)
data class IncidentDto(@SerializedName("id") val id: String, @SerializedName("status") val status: String)
data class DeviceRegistrationDto(@SerializedName("fcm_token") val fcmToken: String,
                                 @SerializedName("platform") val platform: String = "ANDROID",
                                 @SerializedName("device_identifier") val deviceIdentifier: String = "")
data class TestAlertResponseDto(@SerializedName("status") val status: String,
                                @SerializedName("device_count") val deviceCount: Int)

data class WsTicketDto(
    @SerializedName("ticket") val ticket: String,
    @SerializedName("expires_in") val expiresInSeconds: Int,
    @SerializedName("role") val role: String
)

data class ResponderStatusUpdateDto(
    @SerializedName("status") val status: String
)

data class IncidentLocationDetailDto(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("accuracy_meters") val accuracyMeters: Double,
    @SerializedName("recorded_at") val recordedAt: String,
    @SerializedName("received_at") val receivedAt: String,
    @SerializedName("is_mock") val mock: Boolean,
    @SerializedName("is_stale") val stale: Boolean = false
)

data class ResponderSummaryDto(
    @SerializedName(value = "invited_count", alternate = ["total_invited"]) val totalInvited: Int,
    @SerializedName("accepted_count") val acceptedCount: Int,
    @SerializedName(value = "active_responders_count", alternate = ["active_helping_count"]) val activeHelpingCount: Int
)

data class RequesterMembershipDto(
    @SerializedName("role") val role: String,
    @SerializedName("authorization_status") val status: String,
    @SerializedName("assistance_status") val assistanceStatus: String?
)

data class ActiveResponderPublicDto(
    @SerializedName("responder_id") val responderId: String,
    @SerializedName("assistance_status") val assistanceStatus: String,
    @SerializedName("updated_at") val updatedAt: String?
)

data class IncidentSnapshotDto(
    @SerializedName("incident_id") val incidentId: String,
    @SerializedName("status") val status: String,
    @SerializedName("is_test") val test: Boolean,
    @SerializedName(value = "started_at", alternate = ["created_at"]) val createdAt: String,
    @SerializedName(value = "latest_location", alternate = ["last_location"]) val lastLocation: IncidentLocationDetailDto?,
    @SerializedName(value = "responder_summary", alternate = ["responders_summary"]) val respondersSummary: ResponderSummaryDto,
    @SerializedName("active_responders") val activeResponders: List<ActiveResponderPublicDto> = emptyList(),
    @SerializedName("requester_membership") val requesterMembership: RequesterMembershipDto?
)

data class SafetyPinSetupDto(
    @SerializedName("pin") val pin: String,
    @SerializedName("confirm_pin") val confirmPin: String,
    @SerializedName("credential_type") val credentialType: String = "PRIMARY_PIN"
)

data class SafetyPinChangeDto(
    @SerializedName("current_pin") val currentPin: String,
    @SerializedName("new_pin") val newPin: String,
    @SerializedName("confirm_pin") val confirmPin: String,
    @SerializedName("credential_type") val credentialType: String = "PRIMARY_PIN"
)

data class SafetyPinRecoveryDto(
    @SerializedName("new_pin") val newPin: String,
    @SerializedName("confirm_pin") val confirmPin: String
)

data class SafetyPinStatusDto(
    @SerializedName("has_primary_pin") val hasPrimaryPin: Boolean,
    @SerializedName("has_duress_pin") val hasDuressPin: Boolean,
    @SerializedName("failed_attempt_count") val failedAttemptCount: Int,
    @SerializedName("is_locked") val isLocked: Boolean,
    @SerializedName("locked_until") val lockedUntil: String? = null,
    @SerializedName("retry_after_seconds") val retryAfterSeconds: Int = 0
)

data class IncidentResolutionRequestDto(
    @SerializedName("pin") val pin: String,
    @SerializedName("reason") val reason: String = "USER_RESOLVED"
)

data class IncidentResolutionResponseDto(
    @SerializedName("incident_id") val incidentId: String,
    @SerializedName("status") val status: String,
    @SerializedName("resolution_status") val resolutionStatus: String,
    @SerializedName("message") val message: String,
    @SerializedName("ended_at") val endedAt: String? = null
)

interface BackendApi {
    @PUT("api/responders/location") suspend fun submitResponderFix(@Body fix: FixDto, @Tag expected: ExpectedAccount)
    @POST("api/incidents/{id}/location") suspend fun submitIncidentFix(@Path("id") id: String, @Body fix: FixDto, @Tag expected: ExpectedAccount)
    @GET("api/incidents/{id}/location") suspend fun incidentLocation(@Path("id") id: String, @Tag expected: ExpectedAccount? = null): IncidentLocationDto
    @GET("api/responders/invitations") suspend fun invitations(@Tag expected: ExpectedAccount? = null): List<InvitationDto>
    @POST("api/responders/invitations/{id}/response") suspend fun respond(@Path("id") id: String, @Body decision: DecisionDto): StatusDto

    @GET("health") suspend fun health(): HealthDto
    @GET("api/users/me") suspend fun profile(@Tag expected: ExpectedAccount? = null): ProfileDto
    @PUT("api/users/me") suspend fun profile(@Body request: ProfileRequest, @Tag expected: ExpectedAccount): ProfileDto
    @POST("api/incidents") suspend fun createTest(@Header("Idempotency-Key") key: String, @Body request: TestIncidentRequest, @Tag expected: ExpectedAccount): IncidentDto
    @POST("api/devices/register") suspend fun registerDevice(@Body request: DeviceRegistrationDto, @Tag expected: ExpectedAccount): StatusDto
    @DELETE("api/devices/{token}") suspend fun unregisterDevice(@Path("token") token: String): retrofit2.Response<Unit>
    @POST("api/devices/{deviceId}/test-alert") suspend fun sendTestAlert(@Path("deviceId") deviceId: String): TestAlertResponseDto
    @POST("api/incidents/{id}/ws-ticket") suspend fun wsTicket(@Path("id") id: String): WsTicketDto
    @GET("api/incidents/{id}/snapshot") suspend fun snapshot(@Path("id") id: String, @Tag expected: ExpectedAccount? = null): IncidentSnapshotDto
    @PUT("api/incidents/{id}/responders/status") suspend fun updateResponderStatus(@Path("id") id: String, @Body status: ResponderStatusUpdateDto): StatusDto
    @POST("api/security/safety-pin/setup") suspend fun setupSafetyPin(@Body request: SafetyPinSetupDto, @Tag expected: ExpectedAccount? = null): StatusDto
    @POST("api/security/safety-pin/change") suspend fun changeSafetyPin(@Body request: SafetyPinChangeDto, @Tag expected: ExpectedAccount? = null): StatusDto
    @GET("api/security/safety-pin/status") suspend fun safetyPinStatus(@Tag expected: ExpectedAccount? = null): SafetyPinStatusDto
    @POST("api/security/safety-pin/recover") suspend fun recoverSafetyPin(@Body request: SafetyPinRecoveryDto, @Tag expected: ExpectedAccount? = null): StatusDto
    @POST("api/incidents/{id}/resolve") suspend fun resolveIncident(@Path("id") id: String, @Body request: IncidentResolutionRequestDto, @Tag expected: ExpectedAccount? = null): IncidentResolutionResponseDto
}

object BackendClient {
    val rawOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    val api: BackendApi by lazy {
        val base = BuildConfig.API_BASE_URL.toHttpUrl()
        require(base.isHttps && base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null)
        val bearer = Interceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath == base.encodedPath + "health") return@Interceptor chain.proceed(request)
            val user = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull() ?: throw IOException("Sign in required")
            val expected = request.tag(ExpectedAccount::class.java)
            if (expected != null && expected.uid != user.uid) throw IOException("Account changed")
            val token = try {
                Tasks.await(user.getIdToken(false), 15, TimeUnit.SECONDS).token
            } catch (error: Exception) {
                throw IOException("Unable to obtain account token", error)
            } ?: throw IOException("Account token unavailable")
            if (runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull() != user.uid) throw IOException("Session changed")
            chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
        }
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).addInterceptor(bearer).build()
        Retrofit.Builder().baseUrl(base).client(client).addConverterFactory(GsonConverterFactory.create()).build()
            .create(BackendApi::class.java)
    }
}
