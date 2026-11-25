package `in`.maitri.app.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the Baseline Profile: cold start (startup screen, Home), every tab with scrolling, and a partial SOS hold.
 * WARNING: BaselineProfileRule reinstalls the app on non-rooted Android 13+ phones, which wipes its data (signed out),
 * so run it only on an emulator or a spare phone signed in to a test account, never on a tester's phone. Copy the
 * result into app/src/release/generated/baselineProfiles/baseline-prof.txt and keep the in/maitri/app wildcard rules.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true, maxIterations = 6) {
        pressHome()
        startActivityAndWait()
        waitForHome()
        partialSosHold()
        switchTabsAndScroll()
    }
}
