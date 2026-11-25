import java.util.Properties
import java.net.URI
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import java.awt.RenderingHints
import java.awt.image.BufferedImage

plugins {
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("com.google.firebase.appdistribution")
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("androidx.baselineprofile")
}


/** local.properties is never committed; it holds machine paths, backend URLs, keys and signing passwords. */
fun localProperties(): Properties {
    val local = Properties()
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { local.load(it) }
    return local
}

/** Gradle property first, then local.properties; [legacy] keeps pre-rename SurakshaNet keys working. */
fun localSetting(name: String, legacy: String? = null): String {
    val local = localProperties()
    return providers.gradleProperty(name).orNull ?: local.getProperty(name)
        ?: legacy?.let { providers.gradleProperty(it).orNull ?: local.getProperty(it) } ?: ""
}

fun backendUrl(property: String, legacy: String): String {
    val url = localSetting(property, legacy)
    if (url.isNotEmpty()) {
        val uri = runCatching { URI(url) }.getOrNull()
        require(uri != null && uri.scheme == "https" && uri.host != null &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "$property must be an HTTPS URL without credentials, query, or fragment"
        }
    }
    val baseUrl = if (url.isEmpty()) "" else url.trimEnd('/') + "/"
    return "\"" + baseUrl + "\""
}

val crashTest = (project.findProperty("maitriCrashTest") as String?) == "true"
// Only a build with the test crash button gets the refusal; a normal build has no extra task action, because a
// doFirst that reads a build-script value can't be stored by the configuration cache and broke every upload.
if (crashTest) tasks.matching { it.name.startsWith("appDistributionUpload") }.configureEach {
    doFirst { throw GradleException("This build has the test crash button (-PmaitriCrashTest=true); don't send it to testers.") }
}

android {
    namespace = "in.maitri.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "in.maitri.app"
        minSdk = 26
        targetSdk = 37
        // versionCode = major*10000 + minor*100 + patch. A later beta of the same version must bump patch
        // (0.9.1-beta.2 -> 901), because Android rejects an update with an equal or lower versionCode.
        val (major, minor, patch) = listOf(0, 9, 4)
        versionCode = major * 10000 + minor * 100 + patch
        versionName = "$major.$minor.$patch-beta.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Upload key for Play App Signing. Passwords come only from local.properties (or -P), never from the repo.
    val keystorePassword = localSetting("maitriKeystorePassword")
    val keyPassword = localSetting("maitriKeyPassword")
    val keystoreFile = file(localSetting("maitriKeystorePath").ifBlank {
        System.getProperty("user.home") + "/keys/maitri-upload.jks" })
    val canSignRelease = keystorePassword.isNotEmpty() && keyPassword.isNotEmpty() && keystoreFile.isFile
    signingConfigs {
        if (canSignRelease) create("upload") {
            storeFile = keystoreFile
            storePassword = keystorePassword
            keyAlias = localSetting("maitriKeyAlias").ifBlank { "maitri" }
            this.keyPassword = keyPassword
        }
    }

    lint {
        // androidx.navigation's WrongNavigateRouteDetector crashes while analysing SafetyApp.kt (a bug in that lint
        // check). It validates type-safe routes; Maitri uses plain string routes, so nothing is lost.
        disable += "WrongNavigateRouteType"
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", backendUrl("maitriDevApiUrl", "surakshaDevApiUrl"))
            buildConfigField("boolean", "CRASH_TEST", "false")
            // Opt-in (-PsignDebugWithUpload=true): sign debug and androidTest APKs with the upload key, so device
            // tests can install over a phone's release build without uninstalling it (and signing the user out).
            if (canSignRelease && (project.findProperty("signDebugWithUpload") as String?) == "true")
                signingConfig = signingConfigs.getByName("upload")
            // Debug builds never report crashes or upload mapping files.
            configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> { mappingFileUploadEnabled = false }
        }
        release {
            buildConfigField("String", "API_BASE_URL", backendUrl("maitriProdApiUrl", "surakshaProdApiUrl"))
            // Opt-in (-PmaitriCrashTest=true): adds "Send test crash report" to Beta info, to check Crashlytics on a
            // developer phone. Never in a tester build: the App Distribution upload refuses such a build (below).
            buildConfigField("boolean", "CRASH_TEST", crashTest.toString())
            if (canSignRelease) signingConfig = signingConfigs.getByName("upload")
            // Firebase App Distribution (free): ./gradlew assembleRelease appDistributionUploadRelease
            // sends this build to the "friends" tester group. Credentials and testers stay in local.properties.
            configure<com.google.firebase.appdistribution.gradle.AppDistributionExtension> {
                artifactType = "APK"
                groups = localSetting("maitriTesterGroups").ifBlank { "friends" }
                releaseNotesFile = rootProject.file("release-notes.txt").path
                localSetting("maitriAppDistributionCredentials").takeIf { it.isNotBlank() }?.let {
                    serviceCredentialsFile = it
                }
            }
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
            // Keep rules for reflection inside the optimized packages (Room/WorkManager, startup); see the file.
            proguardFiles("proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // MapLibre + OpenFreeMap tiles: no API key, free for commercial use.
    // OpenGL ES renderer: the default artifact renders with Vulkan and crashes ("No Vulkan compatible GPU found")
    // on phones without Vulkan, which includes the Android 8 emulator and many budget phones.
    implementation("org.maplibre.gl:android-sdk-opengl:13.7.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    // Installs the shipped Baseline Profile (src/release/generated/baselineProfiles) on devices without Play.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    baselineProfile(project(":baselineprofile"))
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-auth")
    // Google Sign-In on screen 03 through Credential Manager.
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("com.google.firebase:firebase-messaging")
    // Crash reports for release builds (free on the Spark plan); see MaitriApp for what is and isn't sent.
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Decode small density-specific images in launcher and Compose instead of the 1536 px master.
abstract class GenerateBrandResources : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val master: RegularFileProperty

    @get:OutputDirectory
    abstract val images: DirectoryProperty

    @TaskAction
    fun render() {
        val source = ImageIO.read(master.get().asFile)
        for ((density, pixels) in mapOf("mdpi" to 108, "hdpi" to 162, "xhdpi" to 216, "xxhdpi" to 324, "xxxhdpi" to 432)) {
            val scaled = BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_ARGB)
            val graphics = scaled.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                graphics.drawImage(source, 0, 0, pixels, pixels, null)
            } finally { graphics.dispose() }
            val dir = images.get().dir("drawable-$density").asFile
            dir.mkdirs()
            ImageIO.write(scaled, "png", dir.resolve("brand_mark.png"))
        }
    }
}

androidComponents.onVariants { variant ->
    val generator = tasks.register<GenerateBrandResources>("generate${variant.name.replaceFirstChar { it.uppercase() }}BrandResources") {
        master.set(layout.projectDirectory.file("src/main/res/drawable-nodpi/maitri_brand_master.png"))
    }
    variant.sources.res?.addGeneratedSourceDirectory(generator, GenerateBrandResources::images)
}

// Import once. Normal builds use the approved artwork from the resource tree.
tasks.register("importMaitriLogo") {
    val archive = providers.gradleProperty("maitriLogoArchive")
    val destination = layout.projectDirectory.file("src/main/res/drawable-nodpi/maitri_brand_master.png")
    doLast {
        val output = destination.asFile
        require(!output.exists()) { "Official artwork already imported" }
        ZipFile(archive.get()).use { zip ->
            val entry = zip.entries().asSequence().single { it.name.endsWith("@3x.png") }
            val image = zip.getInputStream(entry).use { ImageIO.read(it) }
            require(image.width == 1536 && image.height == 1536)
            output.parentFile.mkdirs()
            zip.getInputStream(entry).use { input -> output.outputStream().use { input.copyTo(it) } }
        }
    }
}

// Baseline Profile: generated on a real phone (BaselineProfileGenerator) and kept in src/release/generated, so release
// builds ship it. Generation runs only on request. The variants it builds (nonMinifiedRelease, benchmarkRelease) keep
// the release package and are signed with the upload key, so they install over the phone's app (no sign-out) and are
// never uploaded to App Distribution.
baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
    dexLayoutOptimization = true
}
androidComponents {
    finalizeDsl { android ->
        android.buildTypes.matching { it.name == "nonMinifiedRelease" || it.name == "benchmarkRelease" }.configureEach {
            android.signingConfigs.findByName("upload")?.let { signingConfig = it }
        }
    }
}
