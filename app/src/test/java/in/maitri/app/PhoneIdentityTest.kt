package `in`.maitri.app

import `in`.maitri.app.data.accountVerified
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Phone-OTP accounts pass the same verification gate as verified-email accounts (server: core.identity). */
class PhoneIdentityTest {
    @Test fun phoneOrVerifiedEmailUnlocksTheApp() {
        assertTrue(accountVerified(emailVerified = true, phone = null))
        assertTrue(accountVerified(emailVerified = false, phone = "+919876500001"))
        assertFalse(accountVerified(emailVerified = false, phone = null))
        assertFalse(accountVerified(emailVerified = false, phone = "  "))
    }

    /** SOS sync, location, FCM registration and invitations must all use the shared rule, not email alone. */
    @Test fun sosPathsDoNotRequireAnEmail() {
        val offenders = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
            .filter { f -> f.readLines().any { "isEmailVerified" in it && "fun FirebaseUser.isMaitriVerified" !in it } }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
