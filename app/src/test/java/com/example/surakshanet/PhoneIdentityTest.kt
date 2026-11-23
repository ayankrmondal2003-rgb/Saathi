package com.example.surakshanet

import com.example.surakshanet.data.accountVerified
import org.junit.Assert.*
import org.junit.Test

/** Phone-OTP accounts pass the same verification gate as verified-email accounts (server: core.identity). */
class PhoneIdentityTest {
    @Test fun phoneOrVerifiedEmailUnlocksTheApp() {
        assertTrue(accountVerified(emailVerified = true, phone = null))
        assertTrue(accountVerified(emailVerified = false, phone = "+919876500001"))
        assertFalse(accountVerified(emailVerified = false, phone = null))
        assertFalse(accountVerified(emailVerified = false, phone = "  "))
    }
}
