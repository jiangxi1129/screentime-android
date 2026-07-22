plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ciVersionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()

android {
    namespace = "top.xixiclaire.screentime"
    compileSdk = 34

    defaultConfig {
        applicationId = "top.xixiclaire.screentime"
        minSdk = 26
        targetSdk = 34
        versionCode = ciVersionCode ?: 29
        versionName = "2.13"
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
