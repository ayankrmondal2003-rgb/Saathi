package `in`.maitri.app.network

import `in`.maitri.app.tr

import android.content.Intent
import android.net.Uri
import android.os.Build
import com.google.gson.annotations.SerializedName
import retrofit2.http.*

/** P3: inbox, circle invites and the SOS alert to the circle (push to app users, SMS prepared by the owner). */
data class InboxItemDto(val id: String, val kind: String, val title: String, val body: String,
    val data: Map<String, String> = emptyMap(), @SerializedName("created_at") val createdAt: String, val read: Boolean)
data class InboxDto(val items: List<InboxItemDto>, @SerializedName("unread_count") val unreadCount: Int)

data class CircleInviteRequest(@SerializedName("contact_id") val contactId: String)
data class CircleInviteDto(val code: String, @SerializedName("expires_at") val expiresAt: String)
data class AcceptInviteRequest(val code: String)
data class AcceptInviteDto(val status: String, @SerializedName("owner_name") val ownerName: String?)
data class CircleRequestDto(val id: String, @SerializedName("owner_name") val ownerName: String)
/** A circle this account is in. [addedBack]: LINKED (each other's circles), REQUESTED (waiting for them), or null. */
data class MembershipDto(@SerializedName("link_id") val linkId: String, @SerializedName("owner_name") val ownerName: String,
    @SerializedName("since") val since: String? = null, @SerializedName("added_back") val addedBack: String? = null)
data class AddBackRequest(@SerializedName("phone_number") val phoneNumber: String? = null)
data class AddBackDto(val status: String, @SerializedName("contact_id") val contactId: String? = null)

data class SmsContactDto(@SerializedName("contact_id") val contactId: String,
    @SerializedName("display_name") val name: String, @SerializedName("phone_number") val phone: String)
data class CircleAlertDto(@SerializedName("live_link") val liveLink: String?,
    @SerializedName("sms_contacts") val smsContacts: List<SmsContactDto> = emptyList())
data class SmsPreparedRequest(@SerializedName("contact_ids") val contactIds: List<String>)

interface CircleApi {
    @GET("api/inbox") suspend fun inbox(@Tag expected: ExpectedAccount): InboxDto
    @POST("api/inbox/{id}/read") suspend fun read(@Path("id") id: String, @Tag expected: ExpectedAccount): retrofit2.Response<Unit>
    @POST("api/inbox/read-all") suspend fun readAll(@Tag expected: ExpectedAccount): retrofit2.Response<Unit>
    @POST("api/inbox/{id}/delivered") suspend fun delivered(@Path("id") id: String, @Tag expected: ExpectedAccount): retrofit2.Response<Unit>
    @POST("api/circle/invites") suspend fun invite(@Body body: CircleInviteRequest, @Tag expected: ExpectedAccount): CircleInviteDto
    @POST("api/circle/invites/accept") suspend fun accept(@Body body: AcceptInviteRequest, @Tag expected: ExpectedAccount): AcceptInviteDto
    @GET("api/circle/requests") suspend fun requests(@Tag expected: ExpectedAccount): List<CircleRequestDto>
    @POST("api/circle/requests/{id}/{decision}") suspend fun answer(@Path("id") id: String, @Path("decision") decision: String,
        @Tag expected: ExpectedAccount): StatusDto
    @GET("api/circle/memberships") suspend fun memberships(@Tag expected: ExpectedAccount): List<MembershipDto>
    @POST("api/circle/memberships/{id}/add-back") suspend fun addBack(@Path("id") linkId: String, @Body body: AddBackRequest,
        @Tag expected: ExpectedAccount): AddBackDto
    @POST("api/incidents/{id}/trusted-contacts/alert") suspend fun alert(@Path("id") id: String, @Tag expected: ExpectedAccount): CircleAlertDto
    @POST("api/incidents/{id}/trusted-contacts/sms-prepared") suspend fun smsPrepared(@Path("id") id: String,
        @Body body: SmsPreparedRequest, @Tag expected: ExpectedAccount): retrofit2.Response<Unit>
}

/** Per-contact status on screen 08. Never says "sent" for an SMS: Maitri can't see the messaging app. */
fun circleStatusLabel(status: String): String = when (status) {
    "OPENED" -> tr("Opened")
    "DELIVERED" -> tr("Push delivered")
    "PUSH_SENT" -> tr("Push sent")
    "SENDING" -> tr("Sending…")
    "SMS_PREPARED" -> tr("SMS prepared")
    "NOT_REQUESTED" -> tr("Alerts off")
    else -> tr("Not reached")
}

/** True for statuses where the contact has (probably) been reached, for the tick icon. */
fun circleStatusReached(status: String): Boolean = status in setOf("OPENED", "DELIVERED", "PUSH_SENT")

/** The SMS the owner's phone prepares for contacts without the app. The owner taps Send. */
fun sosSmsText(liveLink: String?): String =
    if (liveLink.isNullOrBlank()) tr("I need help. I started an SOS in Maitri. If you can't reach me, call 112.")
    else tr("I need help. My live location: {0} — if you can't reach me, call 112.", liveLink)

/**
 * Opens the default SMS app with every number and the text filled in. Samsung Messages separates recipients
 * with ',' and most others with ';'.
 */
fun smsComposerIntent(numbers: List<String>, text: String, manufacturer: String = Build.MANUFACTURER): Intent {
    val separator = if (manufacturer.equals("samsung", ignoreCase = true)) "," else ";"
    return Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + numbers.joinToString(separator) { Uri.encode(it) }))
        .putExtra("sms_body", text)
}

/** WhatsApp (or any app) through the share sheet; the owner picks the chat. */
fun shareTextIntent(text: String): Intent = Intent.createChooser(
    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Send your SOS with")

/** A pairing code invitation the owner can send any way they like. */
fun inviteText(code: String) =
    tr("I've added you to my Maitri circle so my SOS alerts reach you. Install Maitri, then open Circle → Enter a code and type {0} (valid 24 hours).", code)
