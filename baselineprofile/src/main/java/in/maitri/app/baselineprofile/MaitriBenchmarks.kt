package `in`.maitri.app.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startup and frame timing on the phone. Results (timeToInitialDisplayMs, timeToFullDisplayMs, frameDurationCpuMs
 * P50/P90/P95/P99, frameOverrunMs) print in the instrumentation output.
 *
 * CompilationMode.Ignore() only: None() and Partial() reinstall the app on non-rooted Android 13+ phones and wipe its
 * data (the account is signed out). Switch the compiled state beforehand instead, which keeps the data:
 *   no profile:            adb shell cmd package compile -f -m verify in.maitri.app
 *   with Baseline Profile: adb shell cmd package compile -f -m speed-profile in.maitri.app
 * Cold starts stop the app between iterations: run only on the victim test phone, never on a responder phone.
 */
@OptIn(androidx.benchmark.macro.ExperimentalMacrobenchmarkApi::class)
@RunWith(AndroidJUnit4::class)
class MaitriBenchmarks {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun startup() = rule.measureRepeated(PACKAGE, listOf(StartupTimingMetric()), CompilationMode.Ignore(),
        StartupMode.COLD, iterations = 10, setupBlock = { pressHome() }) {
        startActivityAndWait()
        waitForHome()
    }

    @Test fun tabs() = rule.measureRepeated(PACKAGE, listOf(FrameTimingMetric()), CompilationMode.Ignore(),
        StartupMode.WARM, iterations = 5, setupBlock = { pressHome(); startActivityAndWait(); waitForHome() }) {
        switchTabsAndScroll()
        partialSosHold()
    }
}
