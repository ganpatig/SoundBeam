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
        versionCode = 1
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
