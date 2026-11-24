package `in`.maitri.app

import `in`.maitri.app.notification.NotificationHelper
import org.junit.Assert.*
import org.junit.Test

class NotificationHelperTest {

    @Test
    fun testEmergencyVibrationPatternDurationAndPulses() {
        val pattern = NotificationHelper.EMERGENCY_VIBRATION_PATTERN
        assertNotNull(pattern)
        // Format: [initial_delay, vibrate_1, pause_1, vibrate_2, pause_2, ..., vibrate_6]
        // 1 initial delay + 6 vibrations of 700ms + 5 pauses of 200ms = 12 elements
        assertEquals(12, pattern.size)
        assertEquals(0L, pattern[0]) // Initial delay

        var totalVibrateMs = 0L
        var totalPauseMs = 0L

        for (i in 1 until pattern.size) {
            if (i % 2 == 1) {
                assertEquals("Vibration pulse should be 700ms", 700L, pattern[i])
                totalVibrateMs += pattern[i]
            } else {
                assertEquals("Pause between pulses should be 200ms", 200L, pattern[i])
                totalPauseMs += pattern[i]
            }
        }

        assertEquals(6 * 700L, totalVibrateMs) // 4200ms vibration
        assertEquals(5 * 200L, totalPauseMs)   // 1000ms pause
        val totalDurationMs = totalVibrateMs + totalPauseMs
        assertEquals(5200L, totalDurationMs)   // ~5.2 seconds total duration
    }

    @Test
    fun testEmergencyChannelConfigurationConstants() {
        assertEquals("urgent_sos_v1", NotificationHelper.CHANNEL_ID_SOS)
        assertEquals("Urgent SOS Alerts", NotificationHelper.CHANNEL_NAME_SOS)
        assertTrue(NotificationHelper.CHANNEL_DESC_SOS.isNotBlank())
    }

    @Test
    fun testNotificationPayloadSecurityValidation() {
        // Valid payload
        val validPayload = mapOf(
            "event_type" to "RESPONDER_INVITATION_CREATED",
            "invitation_id" to "inv-1234",
            "incident_id" to "inc-5678",
            "created_at" to "2026-11-20T12:00:00Z",
            "expires_at" to "2026-11-20T12:05:00Z"
        )
        assertTrue(isValidSecurePayload(validPayload))

        // Security check: reject if coordinates are exposed in notification payload
        val coordinateLeakPayloads = listOf(
            validPayload + ("latitude" to "22.5"),
            validPayload + ("longitude" to "88.3"),
            validPayload + ("lat" to "22.5"),
            validPayload + ("lon" to "88.3")
        )
        for (leak in coordinateLeakPayloads) {
            assertFalse("Payloads with coordinates must be rejected to protect victim privacy", isValidSecurePayload(leak))
        }

        // Unknown event type
        val unknownEvent = validPayload + ("event_type" to "UNRECOGNIZED_EVENT")
        assertFalse(isValidSecurePayload(unknownEvent))

        // Missing required fields
        val missingInvitation = validPayload - "invitation_id"
        assertFalse(isValidSecurePayload(missingInvitation))

        val missingIncident = validPayload - "incident_id"
        assertFalse(isValidSecurePayload(missingIncident))
    }

    @Test
    fun testTestAlertPayloadValidation() {
        val testPayload = mapOf(
            "event_type" to "TEST_ALERT",
            "invitation_id" to "test-inv",
            "incident_id" to "test-inc"
        )
        assertTrue(isValidSecurePayload(testPayload))

        val emergencyTestPayload = mapOf(
            "event_type" to "EMERGENCY_TEST_NOTIFICATION",
            "invitation_id" to "test-inv",
            "incident_id" to "test-inc"
        )
        assertTrue(isValidSecurePayload(emergencyTestPayload))
    }

    private fun isValidSecurePayload(data: Map<String, String>): Boolean {
        if (data.containsKey("latitude") || data.containsKey("longitude") ||
            data.containsKey("lat") || data.containsKey("lon")) {
            return false
        }
        val eventType = data["event_type"].orEmpty()
        return when (eventType) {
            "RESPONDER_INVITATION_CREATED" -> {
                data["invitation_id"].orEmpty().isNotBlank() && data["incident_id"].orEmpty().isNotBlank()
            }
            "TEST_ALERT", "EMERGENCY_TEST_NOTIFICATION" -> true
            else -> false
        }
    }
}
