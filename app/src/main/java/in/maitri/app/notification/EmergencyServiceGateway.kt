package `in`.maitri.app.notification

/**
 * Result returned when attempting to communicate with emergency service dispatch.
 */
data class EmergencyDispatchResult(
    val success: Boolean,
    val referenceId: String?,
    val message: String
)

/**
 * Result returned by a test-only SMS gateway provider.
 */
data class FakeSmsResult(
    val recipientPhone: String,
    val delivered: Boolean,
    val providerReference: String,
    val isSimulated: Boolean,
    val timestamp: Long
)

/**
 * Disabled-by-default gateway interface for future integration with official
 * Emergency Response Support System (ERSS-112) or public safety answering points (PSAP).
 *
 * SAFETY GUARDRAIL:
 * Automated or unattended direct dispatch is strictly disabled in test, demo, and development builds.
 * All emergency calls must be confirmed and placed by the user via the native Android 112 dialer.
 */
interface EmergencyServiceGateway {
    fun isConfigured(): Boolean = false

    suspend fun requestEmergencyDispatch(
        incidentId: String,
        victimName: String,
        latitude: Double,
        longitude: Double,
        message: String
    ): EmergencyDispatchResult
}

/**
 * Safe, default gateway implementation enforcing safety boundaries.
 */
class DisabledEmergencyServiceGateway : EmergencyServiceGateway {
    override fun isConfigured(): Boolean = false

    override suspend fun requestEmergencyDispatch(
        incidentId: String,
        victimName: String,
        latitude: Double,
        longitude: Double,
        message: String
    ): EmergencyDispatchResult {
        return EmergencyDispatchResult(
            success = false,
            referenceId = null,
            message = "ERSS-112 automated direct dispatch is DISABLED by default in test/demo builds. " +
                    "Use the native Call 112 dialer button to reach emergency services directly."
        )
    }
}

/**
 * Fake/test-only SMS provider interface for automated testing and demonstration.
 * Prevents unintended carrier SMS charges, rate-limit bans, or unsolicited messaging.
 */
interface FakeSmsProvider {
    suspend fun sendSms(
        recipientPhone: String,
        message: String,
        isSimulated: Boolean = true
    ): FakeSmsResult
}

/**
 * In-memory test SMS provider recording simulated dispatches.
 */
class MockFakeSmsProvider : FakeSmsProvider {
    private val sentLog = mutableListOf<FakeSmsResult>()

    override suspend fun sendSms(
        recipientPhone: String,
        message: String,
        isSimulated: Boolean
    ): FakeSmsResult {
        val result = FakeSmsResult(
            recipientPhone = recipientPhone,
            delivered = true,
            providerReference = "SIM-SMS-" + System.currentTimeMillis(),
            isSimulated = isSimulated,
            timestamp = System.currentTimeMillis()
        )
        sentLog.add(result)
        return result
    }

    fun getSentLog(): List<FakeSmsResult> = sentLog.toList()
    fun clear() = sentLog.clear()
}
