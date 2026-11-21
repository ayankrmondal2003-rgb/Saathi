import java.util.Properties
import java.net.URI
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import java.awt.RenderingHints
import java.awt.image.BufferedImage

plugins {
    id("com.google.gms.google-services")
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}


fun backendUrl(property: String): String {
    val local = Properties()
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { local.load(it) }
    val url = providers.gradleProperty(property).orNull ?: local.getProperty(property, "")
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

android {
    namespace = "com.example.surakshanet"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.surakshanet"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug { buildConfigField("String", "API_BASE_URL", backendUrl("surakshaDevApiUrl")) }
        release {
            buildConfigField("String", "API_BASE_URL", backendUrl("surakshaProdApiUrl"))
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
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
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-messaging")
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
        master.set(layout.projectDirectory.file("src/main/res/drawable-nodpi/suraksha_logo.png"))
    }
    variant.sources.res?.addGeneratedSourceDirectory(generator, GenerateBrandResources::images)
}

// Import once. Normal builds use the approved artwork from the resource tree.
tasks.register("importSurakshaLogo") {
    val archive = providers.gradleProperty("surakshaLogoArchive")
    val destination = layout.projectDirectory.file("src/main/res/drawable-nodpi/suraksha_logo.png")
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
