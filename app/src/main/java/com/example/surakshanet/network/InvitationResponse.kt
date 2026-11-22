package com.example.surakshanet.network

import com.google.gson.JsonParser
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID

internal fun canonicalId(value: String?): String? = value?.let {
    runCatching { UUID.fromString(it).toString() }.getOrNull()?.takeIf { id -> id.equals(it, ignoreCase = true) }
}

internal fun invitationFromAlert(type: String?, invitation: String?, incident: String?): String? {
    if (type != "RESPONDER_INVITATION_CREATED") return null
    val incidentId = canonicalId(incident) ?: return null
    return canonicalId(invitation)?.takeUnless { it == incidentId }
}

internal suspend fun confirmedInvitationResponse(
    decision: String,
    submit: suspend () -> StatusDto
): String {
    require(decision == "ACCEPTED" || decision == "DECLINED")
    val status = submit().status
    check(status == decision || status == "EXPIRED")
    return status
}

internal fun invitationFailure(error: Exception, debug: Boolean = false): String {
    if (error is IOException) return "Cannot reach the server. Reconnect and retry."
    if (error !is HttpException) return "The server response could not be confirmed. Refresh your invitations."
    val detail = runCatching {
        JsonParser.parseString(error.response()?.errorBody()?.string()).asJsonObject["detail"]
    }.getOrNull()
    val reason = detail?.takeIf { it.isJsonPrimitive }?.asString
    return when (error.code()) {
        400 -> "Invalid invitation request. Refresh your invitations."
        401 -> "Sign in again to respond."
        403 -> when (reason) {
            "Fresh matching responder location required" -> "Update your responder location and try again."
            "Fresh matching responder location within the active radius required" -> "Update your location. You must be inside the current alert radius to accept."
            "Responder unavailable" -> "Enable responder availability before accepting."
            else -> "Your account is ineligible for this invitation."
        }
        404 -> "Invitation unavailable for this account."
        409 -> when (reason) {
            "Incident is inactive" -> "This incident is no longer active."
            "Invitation already answered" -> "This invitation was already answered. Refresh your inbox."
            else -> "The invitation has changed. Refresh your inbox."
        }
        422 -> {
            val errors = detail?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
            val fields = errors.mapNotNull { item ->
                val location = item.takeIf { it.isJsonObject }?.asJsonObject?.get("loc")
                when (location?.toString()) {
                    "[\"path\",\"invitation_id\"]" -> "invitation identifier"
                    "[\"body\",\"decision\"]" -> "response decision"
                    "[\"body\"]" -> "request body"
                    else -> null
                }
            }.distinct()
            val message = if (fields.isEmpty()) "Invitation request validation failed. Refresh your inbox."
                else "Invalid ${fields.joinToString()}. Refresh your inbox."
            val diagnostics = if (debug) errors.mapNotNull { item ->
                val errorObject = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val field = when (errorObject["loc"]?.toString()) {
                    "[\"path\",\"invitation_id\"]" -> "path.invitation_id"
                    "[\"body\",\"decision\"]" -> "body.decision"
                    "[\"body\"]" -> "body"
                    else -> return@mapNotNull null
                }
                val type = errorObject["type"]?.takeIf { it.isJsonPrimitive }?.asString
                if (type in setOf("uuid_parsing", "missing", "literal_error", "json_invalid", "model_attributes_type"))
                    "$field ($type)" else "$field (validation error)"
            }.distinct().joinToString() else ""
            if (diagnostics.isBlank()) message else "$message\n$diagnostics"
        }
        else -> "Invitation service unavailable. Try again later."
    }
}
