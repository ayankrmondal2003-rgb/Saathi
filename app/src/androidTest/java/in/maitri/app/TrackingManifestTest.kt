package `in`.maitri.app

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import `in`.maitri.app.location.SosLocationService
import org.junit.Assert.*
import org.junit.Test

class TrackingManifestTest {
    /** The SOS service is foreground-location only; background location exists solely for opted-in volunteers. */
    @Test fun installedServiceIsPrivateAndUsesForegroundLocationOnly() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val service = app.packageManager.getServiceInfo(ComponentName(app, SosLocationService::class.java), 0)
        assertFalse(service.exported)
        if (Build.VERSION.SDK_INT >= 29) {
            assertTrue(service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0)
        }
        val permissions = app.packageManager.getPackageInfo(app.packageName,
            PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertTrue(permissions.containsAll(listOf(Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)))
        assertTrue(Manifest.permission.ACCESS_BACKGROUND_LOCATION in permissions)
    }
}
