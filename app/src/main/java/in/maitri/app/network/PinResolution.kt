package `in`.maitri.app.network

import `in`.maitri.app.tr

import com.google.gson.JsonParser
import retrofit2.HttpException
import java.io.IOException

internal const val MISSING_PRIMARY_PIN = "Safety PIN not configured. Set or recover your PIN to end this SOS."

internal fun pinFailure(error: Exception): String {
    if (error is IOException) return tr("Connection interrupted. Closure is unconfirmed. Reconnect and retry.")
    if (error !is HttpException) return tr("The PIN request could not be confirmed. Please retry.")
    val detail = runCatching {
        JsonParser.parseString(error.response()?.errorBody()?.string()).asJsonObject
            .getAsJsonPrimitive("detail").asString
    }.getOrNull().orEmpty()
    return when (error.code()) {
        400 -> when (detail) {
            MISSING_PRIMARY_PIN, "Safety PIN not enrolled. Configure your Safety PIN in settings first." -> MISSING_PRIMARY_PIN
            "New PIN and confirmation PIN do not match.", "PIN and confirmation PIN do not match." -> detail
            else -> tr("The server could not accept the PIN request. Check the entries and retry.")
        }
        401 -> tr("Your session expired. Sign in again.")
        403 -> when {
            detail == "Only the incident owner can resolve the incident." -> detail
            detail.matches(Regex("""Incorrect Safety PIN\. \d+ attempt\(s\) remaining\.""")) -> detail
            detail.matches(Regex("""Current PIN is incorrect\. \d+ attempt\(s\) remaining\.""")) -> detail
            detail == "Sign in again to recover your Safety PIN" -> tr("Verify your account password again to recover your PIN.")
            else -> tr("This account does not have permission for this action.")
        }
        404 -> if (detail == "No existing Safety PIN configured.") tr("No Safety PIN is set yet. Create one first.")
               else tr("This incident is unavailable for this account.")
        409 -> if (detail == "PIN already enrolled. Use PIN change or recovery.") detail
               else tr("The incident state changed. Reopen the PIN dialog and retry.")
        422 -> when (detail) {
            tr("Safety PIN must consist of exactly 6 digits."),
            tr("PIN cannot consist of a single repeated digit (e.g., 111111)."),
            tr("PIN cannot be an obvious sequential sequence (e.g., 123456)."),
            tr("PIN cannot be an obvious reverse sequence (e.g., 654321)."),
            tr("Primary PIN and Duress PIN must be distinct."),
            "New PIN must be different from current PIN." -> detail
            else -> tr("Invalid PIN request. Check that your PIN has six digits.")
        }
        429 -> if (detail.matches(Regex("""Safety PIN verification is rate-limited\. Retry in \d+ seconds\.""")) ||
                   detail.matches(Regex("""Rate limited\. Verification locked for \d+ seconds\.""")) ||
                   detail == "Wait one minute after a PIN change before recovering again." ||
                   detail == "Safety PIN recovery is temporarily locked.") detail
               else tr("Too many attempts. Wait for the security cooldown before trying again.")
        else -> tr("PIN service unavailable (HTTP {0}). Retry when connected.", error.code())
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
    UNKNOWN(tr("PIN status unknown")), NOT_CONFIGURED(tr("PIN not set")), CONFIGURED(tr("PIN set")),
    LOCKED(tr("PIN locked")), RECOVERY_REQUIRED(tr("PIN recovery required"))
}

internal fun primaryPinState(status: SafetyPinStatusDto?, recovering: Boolean = false): PrimaryPinState = when {
    status == null -> PrimaryPinState.UNKNOWN
    !status.hasPrimaryPin -> PrimaryPinState.NOT_CONFIGURED
    recovering -> PrimaryPinState.RECOVERY_REQUIRED
    status.isLocked -> PrimaryPinState.LOCKED
    else -> PrimaryPinState.CONFIGURED
}

/** Mirrors backend validate_pin_strength so weak PINs are rejected before a network round trip. */
internal enum class PinWeakness { LENGTH, REPEATED, SEQUENTIAL, REVERSE }

internal fun pinWeakness(pin: String): PinWeakness? = when {
    pin.length != 6 || !pin.all { it in '0'..'9' } -> PinWeakness.LENGTH
    pin.toSet().size == 1 -> PinWeakness.REPEATED
    "0123456789".contains(pin) -> PinWeakness.SEQUENTIAL
    "9876543210".contains(pin) -> PinWeakness.REVERSE
    else -> null
}
