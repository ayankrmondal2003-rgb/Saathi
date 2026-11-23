package `in`.maitri.app

import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.platform.app.InstrumentationRegistry
import `in`.maitri.app.location.LocationUpdate
import `in`.maitri.app.network.IncidentFixQueue
import `in`.maitri.app.notification.NotificationHelper
import java.util.UUID
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import `in`.maitri.app.notification.SosFirebaseMessagingService
import org.junit.Assert.*
import org.junit.Test

class PhaseNineRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun encryptedQueueSurvivesReopenAndIsolatesAccounts() {
        val name = "test-fix-" + UUID.randomUUID() + ".db"
        val now = System.currentTimeMillis()
        fun fix(t: Long) = LocationUpdate(0.01, 0.02, 4f, t, null, true)
        try {
            IncidentFixQueue(context, name).use { queue ->
                for (i in 0L until 55) queue.offer("fixture-a", "incident", fix(now - i), now)
                queue.offer("fixture-a", "incident", fix(now), now)
                queue.offer("fixture-a", "incident", fix(now - 130000), now)
                assertEquals(48, queue.pending("fixture-a", "incident", now).size)
                queue.readableDatabase.rawQuery("SELECT payload FROM fixes ORDER BY stamp DESC LIMIT 1", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(12 + 21 + 16, it.getBlob(0).size)
                }
            }
            IncidentFixQueue(context, name).use { queue ->
                assertTrue(queue.pending("fixture-b", "incident", now).isEmpty())
                assertTrue(queue.pending("fixture-a", "other", now).isEmpty())
                val fixes = queue.pending("fixture-a", "incident", now)
                assertEquals(fix(now), fixes.last())
                assertEquals(fixes.sortedBy { it.timestampMillis }, fixes)
                queue.acknowledge("fixture-a", "incident", now)
                assertEquals(47, queue.pending("fixture-a", "incident", now).size)
                assertTrue(queue.pending("fixture-a", "incident", now + 121000).isEmpty())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun notificationReceiptTimestampUpdatesWhileObserved() = runBlocking {
        val original = SosFirebaseMessagingService.getLastAlertTimestamp(context)
        val next = original + 1
        try {
            val values = withTimeout(5000) {
                SosFirebaseMessagingService.lastAlertTimestamps(context).onEach {
                    if (it == original) SosFirebaseMessagingService.saveLastAlertTimestamp(context, next)
                }.take(2).toList()
            }
            assertEquals(listOf(original, next), values)
        } finally {
            SosFirebaseMessagingService.saveLastAlertTimestamp(context, original)
        }
    }

    @Test fun duplicateInvitationDoesNotAlertAgain() {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        assertTrue(NotificationHelper.claimAlert(context, id, now))
        assertFalse(NotificationHelper.claimAlert(context, id, now + 60000))
    }

    @Test fun installedLauncherUsesOfficialAdaptiveResource() {
        assertEquals(R.mipmap.maitri_launcher, context.applicationInfo.icon)
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue(icon is AdaptiveIconDrawable)
    }
}
