plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "top.xixiclaire.screentime"
    compileSdk = 34

    defaultConfig {
        applicationId = "top.xixiclaire.screentime"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "2.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Use the debug signing config so unsigned-but-installable APK works in CI without secrets
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
