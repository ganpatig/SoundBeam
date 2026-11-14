plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.soundbeam"
    compileSdk = 36

    defaultConfig {
        // -PplayAppId=... overrides the ID for a Play listing created with a different package name
        applicationId = (project.findProperty("playAppId") as String?) ?: "dev.soundbeam"
        minSdk = 29 // AudioPlaybackCapture requires Android 10
        targetSdk = 36
        versionCode = 6
        versionName = "0.4.1"
    }

    // Release signing: set SOUNDBEAM_KEYSTORE (path) and SOUNDBEAM_KEYSTORE_PASSWORD.
    val keystore = System.getenv("SOUNDBEAM_KEYSTORE")
    signingConfigs {
        create("release") {
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("SOUNDBEAM_KEYSTORE_PASSWORD")
                keyAlias = "soundbeam"
                keyPassword = System.getenv("SOUNDBEAM_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Render real screenshots (docs/screenshots) when run with -Pscreenshots
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                // -Pplay renders Google Play-sized (2:1) screenshots into docs/play/screenshots
                val play = project.findProperty("play") != null
                it.systemProperty(
                    "screenshots.dir",
                    rootProject.file(if (play) "docs/play/screenshots" else "docs/screenshots").absolutePath,
                )
                if (play) it.systemProperty("screenshots.qualifiers", "w400dp-h800dp-xxhdpi")
                it.maxHeapSize = "2g"
            }
        }
    }
    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Ed25519 / X25519 / ChaCha20-Poly1305 / HKDF for AirPlay 2 pairing
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // WebSocket client for AriaCast
    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.36.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.36.0")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
