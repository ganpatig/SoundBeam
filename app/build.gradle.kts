plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.soundbeam"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.soundbeam"
        minSdk = 29 // AudioPlaybackCapture requires Android 10
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
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
}
