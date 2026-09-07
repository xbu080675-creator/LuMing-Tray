plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val stableStorePath = System.getenv("LUMING_KEYSTORE_PATH")
val stableStorePassword = System.getenv("LUMING_KEYSTORE_PASSWORD")
val stableKeyAlias = System.getenv("LUMING_KEY_ALIAS")
val stableKeyPassword = System.getenv("LUMING_KEY_PASSWORD")
val stableSigningReady = listOf(
    stableStorePath,
    stableStorePassword,
    stableKeyAlias,
    stableKeyPassword
).all { !it.isNullOrBlank() } && stableStorePath?.let { file(it).isFile } == true

android {
    namespace = "com.luming.tray"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.luming.tray"
        minSdk = 26
        targetSdk = 35
        versionCode = 33
        versionName = "0.17.6.3"
    }

    signingConfigs {
        if (stableSigningReady) {
            create("stable") {
                storeFile = file(stableStorePath!!)
                storePassword = stableStorePassword
                keyAlias = stableKeyAlias
                keyPassword = stableKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("stable")?.let { signingConfig = it }
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
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
