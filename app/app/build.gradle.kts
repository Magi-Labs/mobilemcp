plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "labs.magi.mobilemcp"
    compileSdk = 35

    defaultConfig {
        applicationId = "labs.magi.mobilemcp"
        minSdk = 30
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.0"
        // Prefilled hub URL for personal builds: ./gradlew -PdefaultHubUrl=wss://hub.example.com assembleRelease
        buildConfigField("String", "DEFAULT_HUB_URL", "\"${project.findProperty("defaultHubUrl") ?: ""}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideload-friendly: signed with the local debug key unless a release keystore is configured.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
