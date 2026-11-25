package `in`.maitri.app

import `in`.maitri.app.notification.FullScreenReminder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullScreenReminderTest {
    @Test fun onlyAvailableVolunteersOnAndroid14WithThePermissionOffOncePerVersion() {
        assertTrue(FullScreenReminder.needed(sdk = 34, allowed = false, available = true, shownFor = 902, version = 903))
        assertFalse("shown for this version", FullScreenReminder.needed(34, false, true, 903, 903))
        assertFalse("permission allowed", FullScreenReminder.needed(34, true, true, 0, 903))
        assertFalse("not an available volunteer", FullScreenReminder.needed(34, false, false, 0, 903))
        assertFalse("before Android 14 the permission is always granted", FullScreenReminder.needed(33, false, true, 0, 903))
    }
}
