plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.soundbeam.receiver"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.soundbeam.receiver"
        minSdk = 22 // Fire OS 5+ / Android TV 5.1+
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.1"
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
}

dependencies {
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
}
