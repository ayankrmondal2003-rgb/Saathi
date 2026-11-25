// Baseline Profile generator and Macrobenchmarks for Maitri (startup, Home <-> tabs, SOS hold).
// Run on a real phone with am instrument (see BaselineProfileGenerator); never with connectedAndroidTest, which
// uninstalls the app afterwards.
plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}

android {
    namespace = "in.maitri.app.baselineprofile"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    targetProjectPath = ":app"
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.uiautomator:uiautomator:2.4.1")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
}
