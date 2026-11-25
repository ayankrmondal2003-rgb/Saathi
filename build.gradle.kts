// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.google.gms.google-services") version "4.6.0" apply false
    id("com.google.firebase.crashlytics") version "3.0.8" apply false
    id("com.google.firebase.appdistribution") version "5.3.1" apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    id("com.android.test") version libs.versions.agp.get() apply false
    id("androidx.baselineprofile") version "1.5.0" apply false
}