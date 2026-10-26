plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.spk.windsordash"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spk.windsordash"
        minSdk = 26
        targetSdk = 35
        versionCode = 24
        versionName = "0.9.11"
    }

    signingConfigs {
        // Fixed personal key committed with the project, so every CI build installs as an update.
        create("personal") {
            storeFile = file("windsordash.keystore")
            storePassword = "windsordash"
            keyAlias = "windsordash"
            keyPassword = "windsordash"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // Android Auto (Car App Library)
    implementation("androidx.car.app:app:1.4.0")
}
