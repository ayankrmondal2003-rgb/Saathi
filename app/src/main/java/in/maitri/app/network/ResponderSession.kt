package `in`.maitri.app.network

import `in`.maitri.app.tr

import com.google.gson.annotations.SerializedName

data class ContactDeliveryDto(@SerializedName("display_name") val name: String, @SerializedName("delivery_status") val status: String)
data class RecruitmentDto(@SerializedName("queued") val queued: Int)

internal fun IncidentSnapshotDto.authorizesJourney(id: String) = incidentId == id && status == "ACTIVE" &&
    requesterMembership?.role == "RESPONDER" && requesterMembership.status == "AUTHORIZED"

internal fun staleResponderFix(code: Int, reason: String?) =
    code == 409 && reason == "STALE_OR_DUPLICATE_LOCATION"

/** Plain wording for screen 08's circle row. Never upgrades a status: only FCM_ACCEPTED reads as sent. */
internal fun contactDeliveryLabel(status: String): String = when (status) {
    "NO_SMS_PROVIDER" -> tr("Not sent · SMS not connected")
    "NOT_SENT" -> tr("Not sent")
    "NOT_REQUESTED" -> tr("Alerts off")
    "FCM_ACCEPTED" -> tr("Sent to app")
    "PENDING", "QUEUED", "SENDING", "RETRY_SCHEDULED" -> tr("Sending…")
    "FAILED", "PERMANENT_FAILURE" -> tr("Not delivered")
    "CANCELLED" -> tr("Cancelled")
    else -> tr("Delivery unconfirmed")
}
