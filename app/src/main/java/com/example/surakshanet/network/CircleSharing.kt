package com.example.surakshanet.network

import com.google.gson.annotations.SerializedName
import retrofit2.HttpException
import java.io.IOException

data class CircleContactDto(val id: String, @SerializedName("display_name") val name: String)
data class CircleLinkDto(val id: String, @SerializedName("contact_id") val contactId: String,
    @SerializedName("display_name") val name: String, @SerializedName("is_owner") val owner: Boolean)
data class PairingRequestDto(@SerializedName("owner_email") val ownerEmail: String, val consent: Boolean = true)
data class PairingCodeDto(val code: String, @SerializedName("expires_at") val expiresAt: String)
data class BindCircleDto(@SerializedName("contact_id") val contactId: String, val code: String)
data class CircleShareRequest(val kind: String, @SerializedName("contact_ids") val contactIds: List<String>,
    val consent: Boolean, val location: FixDto? = null, @SerializedName("duration_minutes") val duration: Int = 30)
data class CircleReceiptDto(@SerializedName("contact_id") val contactId: String,
    @SerializedName("notification_status") val status: String, @SerializedName("opened_at") val openedAt: String?,
    val revoked: Boolean)
data class CircleSessionDto(val id: String, val kind: String, val status: String,
    @SerializedName("created_at") val createdAt: String, @SerializedName("expires_at") val expiresAt: String,
    @SerializedName("latest_fix") val fix: FixDto?, @SerializedName("fix_received_at") val syncedAt: String?,
    @SerializedName("is_owner") val owner: Boolean, val recipients: List<CircleReceiptDto>)

fun circleSharingError(error: Throwable): String = when (error) {
    is HttpException -> when (error.code()) {
        400, 422 -> "Check your selection, consent and location. The server rejected this request."
        401 -> "Sign in again to continue."
        403 -> "Sharing is not authorized. Check contact pairing and location-sharing permission."
        404 -> "Sharing is unavailable, expired or revoked. The test server may need the sharing update."
        409 -> "Sharing state changed. Refresh before retrying. No new success is confirmed."
        429 -> "Sharing limit reached. Try again later."
        else -> "Safety server unavailable. The result is unconfirmed; refresh before retrying."
    }
    is IOException -> "Connection interrupted. The result is unconfirmed; refresh before retrying."
    else -> "Unable to confirm sharing. Check your session and try again."
}

fun circleReceiptLabel(status: String): String = when (status) {
    "AVAILABLE_IN_APP" -> "Available in recipient’s app — not confirmed opened"
    "OPENED" -> "Opened in recipient’s app"
    else -> "Notification status unknown"
}
