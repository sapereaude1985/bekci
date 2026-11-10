plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bekci.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bekci.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "2.4"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    val ksFile = rootProject.file("bekci.jks")
    val ksPass = System.getenv("KEYSTORE_PASSWORD")
    if (ksFile.exists() && !ksPass.isNullOrEmpty()) {
        signingConfigs {
            create("sabit") {
                storeFile = ksFile
                storePassword = ksPass
                keyAlias = "bekci"
                keyPassword = ksPass
            }
        }
        buildTypes {
            getByName("debug") { signingConfig = signingConfigs.getByName("sabit") }
            getByName("release") { signingConfig = signingConfigs.getByName("sabit") }
        }
    }
    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("io.getstream:stream-webrtc-android:1.1.3")
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
