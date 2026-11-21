package com.example.surakshanet.network

import com.google.gson.JsonParser
import retrofit2.HttpException
import java.io.IOException

internal const val MISSING_PRIMARY_PIN = "Safety PIN not configured. Set or recover your PIN to end this SOS."

internal fun pinFailure(error: Exception): String {
    if (error is IOException) return "Connection interrupted. Closure is unconfirmed. Reconnect and retry."
    if (error !is HttpException) return "The PIN request could not be confirmed. Please retry."
    val detail = runCatching {
        JsonParser.parseString(error.response()?.errorBody()?.string()).asJsonObject
            .getAsJsonPrimitive("detail").asString
    }.getOrNull().orEmpty()
    return when (error.code()) {
        400 -> when (detail) {
            MISSING_PRIMARY_PIN, "Safety PIN not enrolled. Configure your Safety PIN in settings first." -> MISSING_PRIMARY_PIN
            "New PIN and confirmation PIN do not match.", "PIN and confirmation PIN do not match." -> detail
            else -> "The server could not accept the PIN request. Check the entries and retry."
        }
        401 -> "Your session expired. Sign in again."
        403 -> when {
            detail == "Only the incident owner can resolve the incident." -> detail
            detail.matches(Regex("""Incorrect Safety PIN\. \d+ attempt\(s\) remaining\.""")) -> detail
            detail == "Sign in again to recover your Safety PIN" -> "Verify your account password again to recover your PIN."
            else -> "This account does not have permission for this action."
        }
        404 -> "This incident is unavailable for this account."
        409 -> if (detail == "PIN already enrolled. Use PIN change or recovery.") detail
               else "The incident state changed. Reopen the PIN dialog and retry."
        422 -> when (detail) {
            "Safety PIN must consist of exactly 6 digits.",
            "PIN cannot consist of a single repeated digit (e.g., 111111).",
            "PIN cannot be an obvious sequential sequence (e.g., 123456).",
            "PIN cannot be an obvious reverse sequence (e.g., 654321).",
            "Primary PIN and Duress PIN must be distinct." -> detail
            else -> "Invalid PIN request. Check that your PIN has six digits."
        }
        429 -> if (detail.matches(Regex("""Safety PIN verification is rate-limited\. Retry in \d+ seconds\."""))) detail
               else "Too many attempts. Wait for the security cooldown before trying again."
        else -> "PIN service unavailable (HTTP ${error.code()}). Retry when connected."
    }
}

internal suspend fun resolveWithPin(id: String, submit: suspend () -> IncidentResolutionResponseDto): IncidentResolutionResponseDto {
    val result = submit()
    check(result.incidentId == id && (
        (result.status == "CLOSED" && result.resolutionStatus == "CONFIRMED") ||
        result.resolutionStatus == "DURESS_SIMULATION"
    ))
    return result
}

internal enum class PrimaryPinState(val label: String) {
    UNKNOWN("PIN status unknown"), NOT_CONFIGURED("PIN not set"), CONFIGURED("PIN set"),
    LOCKED("PIN locked"), RECOVERY_REQUIRED("PIN recovery required")
}

internal fun primaryPinState(status: SafetyPinStatusDto?, recovering: Boolean = false): PrimaryPinState = when {
    status == null -> PrimaryPinState.UNKNOWN
    !status.hasPrimaryPin -> PrimaryPinState.NOT_CONFIGURED
    recovering -> PrimaryPinState.RECOVERY_REQUIRED
    status.isLocked -> PrimaryPinState.LOCKED
    else -> PrimaryPinState.CONFIGURED
}
