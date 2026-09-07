plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.luming.tray"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.luming.tray"
        minSdk = 26
        targetSdk = 35
        versionCode = 25
        versionName = "0.17.1"
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
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}