package `in`.maitri.app.network

import `in`.maitri.app.BuildConfig
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
                      @SerializedName("responder_opt_in") val optedIn: Boolean, @SerializedName("responder_available") val available: Boolean,
                      @SerializedName("phone_number") val phoneNumber: String? = null, @SerializedName("city") val city: String? = null,
                      @SerializedName("share_phone_in_sos") val sharePhoneInSos: Boolean = false)
/** Screen 14 partial update. Responder flags are never sent, so editing details cannot change availability. */
data class ProfileDetailsRequest(@SerializedName("display_name") val name: String,
                                 @SerializedName("phone_number") val phoneNumber: String,
                                 @SerializedName("city") val city: String,
                                 @SerializedName("share_phone_in_sos") val sharePhoneInSos: Boolean? = null)
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
    @SerializedName("expires_at") val expiresAt: String, @SerializedName("status") val status: String,
    @SerializedName("distance_band") val distanceBand: String? = null,
    @SerializedName("is_test") val isTest: Boolean = false)
internal fun InvitationDto.canOpenIncoming(now: java.time.Instant = java.time.Instant.now()): Boolean =
    id.isNotBlank() && incidentId.isNotBlank() && status == "PENDING" &&
        runCatching { java.time.Instant.parse(expiresAt).isAfter(now) }.getOrDefault(false)

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
    @SerializedName("updated_at") val updatedAt: String?,
    @SerializedName("display_name") val displayName: String? = null,
    @SerializedName("distance_meters") val distanceMeters: Double? = null,
    @SerializedName("latitude") val latitude: Double? = null,
    @SerializedName("longitude") val longitude: Double? = null
)

data class IncidentSnapshotDto(
    @SerializedName("incident_id") val incidentId: String,
    @SerializedName("status") val status: String,
    @SerializedName("is_test") val test: Boolean,
    @SerializedName(value = "started_at", alternate = ["created_at"]) val createdAt: String,
    @SerializedName(value = "latest_location", alternate = ["last_location"]) val lastLocation: IncidentLocationDetailDto?,
    @SerializedName(value = "responder_summary", alternate = ["responders_summary"]) val respondersSummary: ResponderSummaryDto,
    @SerializedName("active_responders") val activeResponders: List<ActiveResponderPublicDto> = emptyList(),
    @SerializedName("requester_membership") val requesterMembership: RequesterMembershipDto?,
    @SerializedName("victim_phone") val victimPhone: String? = null,
    @SerializedName("medical_notes") val medicalNotes: String? = null
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

data class VolunteerStatusDto(
    @SerializedName("status") val status: String,
    @SerializedName("intent") val intent: String,
    @SerializedName("has_govt_id") val hasGovtId: Boolean,
    @SerializedName("has_address_proof") val hasAddressProof: Boolean,
    @SerializedName("has_police_cert") val hasPoliceCert: Boolean,
    @SerializedName("has_photo") val hasPhoto: Boolean,
    @SerializedName("has_declaration") val hasDeclaration: Boolean,
    @SerializedName("consent_declared") val consentDeclared: Boolean,
    @SerializedName("secure_storage_ready") val secureStorageReady: Boolean,
    @SerializedName("submitted_at") val submittedAt: String? = null,
    @SerializedName("reviewer_notes") val reviewerNotes: String? = null,
    @SerializedName("reviewed_at") val reviewedAt: String? = null,
    @SerializedName("documents") val documents: List<VolunteerDocumentDto>? = null
)

data class VolunteerIntentRequestDto(
    @SerializedName("intent") val intent: String
)

data class VolunteerConsentRequestDto(
    @SerializedName("consent_declared") val consentDeclared: Boolean
)

data class VolunteerDocUploadDto(
    @SerializedName("doc_type") val docType: String,
    @SerializedName("file_name") val fileName: String,
    @SerializedName("file_size_bytes") val fileSizeBytes: Long,
    @SerializedName("mime_type") val mimeType: String
)

interface BackendApi {
    @GET("api/trusted-contacts") suspend fun circleContacts(@Tag expected: ExpectedAccount): List<CircleContactDto>
    @GET("api/circle-sharing/links") suspend fun circleLinks(@Tag expected: ExpectedAccount): List<CircleLinkDto>
    @POST("api/circle-sharing/pairing-code") suspend fun circlePairingCode(@Body body: PairingRequestDto, @Tag expected: ExpectedAccount): PairingCodeDto
    @POST("api/circle-sharing/links") suspend fun bindCircle(@Body body: BindCircleDto, @Tag expected: ExpectedAccount): StatusDto
    @DELETE("api/circle-sharing/links/{id}") suspend fun revokeCircle(@Path("id") id: String, @Tag expected: ExpectedAccount): StatusDto
    @GET("api/circle-sharing/requests/{key}") suspend fun circleRequest(@Path("key") key: String, @Tag expected: ExpectedAccount): CircleSessionDto
    @GET("api/circle-sharing/sessions") suspend fun circleSessions(@Tag expected: ExpectedAccount): List<CircleSessionDto>
    @POST("api/circle-sharing/sessions") suspend fun createCircleSession(@Header("Idempotency-Key") key: String, @Body body: CircleShareRequest, @Tag expected: ExpectedAccount): CircleSessionDto
    @GET("api/circle-sharing/sessions/{id}") suspend fun circleSession(@Path("id") id: String, @Tag expected: ExpectedAccount): CircleSessionDto
    @PUT("api/circle-sharing/sessions/{id}/location") suspend fun circleFix(@Path("id") id: String, @Body body: FixDto, @Tag expected: ExpectedAccount): StatusDto
    @POST("api/circle-sharing/sessions/{id}/stop") suspend fun stopCircleSession(@Path("id") id: String, @Tag expected: ExpectedAccount): StatusDto
    @POST("api/circle-sharing/sessions/{id}/opened") suspend fun openCircleSession(@Path("id") id: String, @Tag expected: ExpectedAccount): StatusDto

    @PUT("api/responders/location") suspend fun submitResponderFix(@Body fix: FixDto, @Tag expected: ExpectedAccount)
    @POST("api/incidents/{id}/location") suspend fun submitIncidentFix(@Path("id") id: String, @Body fix: FixDto, @Tag expected: ExpectedAccount)
    @GET("api/incidents/{id}/location") suspend fun incidentLocation(@Path("id") id: String, @Tag expected: ExpectedAccount? = null): IncidentLocationDto
    @GET("api/responders/invitations") suspend fun invitations(@Tag expected: ExpectedAccount? = null): List<InvitationDto>
    @POST("api/responders/invitations/{id}/response") suspend fun respond(@Path("id") id: String, @Body decision: DecisionDto, @Tag account: ExpectedAccount? = null): StatusDto

    @GET("health") suspend fun health(): HealthDto
    @POST("api/incidents/{id}/report") suspend fun reportIncident(@Path("id") id: String, @Body body: `in`.maitri.app.ui.ReportDto, @Tag expected: ExpectedAccount): retrofit2.Response<Unit>
    @POST("api/incidents/{id}/request-help") suspend fun requestHelp(@Path("id") id: String, @Tag expected: ExpectedAccount): RecruitmentDto
    /** Records circle alerts for the owner's confirmed incident. Idempotent server-side (one outbox row per contact). */
    @POST("api/incidents/{id}/trusted-contacts/alert") suspend fun alertCircle(@Path("id") id: String, @Tag expected: ExpectedAccount): com.google.gson.JsonObject
    @GET("api/incidents/{id}/trusted-contacts") suspend fun contactDelivery(@Path("id") id: String, @Tag expected: ExpectedAccount): List<ContactDeliveryDto>
    @GET("api/users/me") suspend fun profile(@Tag expected: ExpectedAccount? = null): ProfileDto
    @PUT("api/users/me") suspend fun profile(@Body request: ProfileRequest, @Tag expected: ExpectedAccount): ProfileDto
    @PATCH("api/users/me") suspend fun profileDetails(@Body request: ProfileDetailsRequest, @Tag expected: ExpectedAccount): ProfileDto
    @GET("api/incidents/active") suspend fun activeIncident(@Tag expected: ExpectedAccount): retrofit2.Response<ActiveIncidentDto>
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

    @GET("api/volunteer/status") suspend fun volunteerStatus(@Tag expected: ExpectedAccount? = null): VolunteerStatusDto
    @POST("api/volunteer/intent") suspend fun setVolunteerIntent(@Body body: VolunteerIntentRequestDto, @Tag expected: ExpectedAccount): StatusDto
    @POST("api/volunteer/consent") suspend fun setVolunteerConsent(@Body body: VolunteerConsentRequestDto, @Tag expected: ExpectedAccount): StatusDto
    @POST("api/volunteer/upload-document") suspend fun uploadVolunteerDocument(@Body body: VolunteerDocUploadDto, @Tag expected: ExpectedAccount): StatusDto
    @POST("api/volunteer/submit") suspend fun submitVolunteerApplication(@Tag expected: ExpectedAccount): StatusDto
}

object BackendClient {
    val rawOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    val api: BackendApi by lazy { retrofit.create(BackendApi::class.java) }
    val contacts: TrustedContactApi by lazy { retrofit.create(TrustedContactApi::class.java) }
    val circle: CircleApi by lazy { retrofit.create(CircleApi::class.java) }
    val files: FilesApi by lazy { retrofit.create(FilesApi::class.java) }
    val privacy: PrivacyApi by lazy { retrofit.create(PrivacyApi::class.java) }

    private val retrofit: Retrofit by lazy {
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
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // Before the token interceptor: a cold Render start (up to a minute) shows "Connecting to Maitri…".
            .addInterceptor(BackendLatency.interceptor)
            .addInterceptor(bearer)
            .build()
        Retrofit.Builder().baseUrl(base).client(client).addConverterFactory(GsonConverterFactory.create()).build()
    }
}
