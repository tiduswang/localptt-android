plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.example.localptt"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.localptt"
        minSdk = 24          // Android 7.0：Camera2/MediaCodec/ WiFi P2P 均可用
        targetSdk = 34
        versionCode = 5
        versionName = "1.1.1"
        // 语音编解码走 MediaCodec AMR-NB（本机构未装 NDK，暂不编译 Opus 原生库）
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    val lifecycle = "2.7.0"
    val nav = "2.7.7"
    val coroutines = "1.8.0"

    implementation("androidx.core:core-ktx:1.13.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // 异步
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutines")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // 生命周期 / 导航
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycle")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycle")
    implementation("androidx.navigation:navigation-fragment-ktx:$nav")
    implementation("androidx.navigation:navigation-ui-ktx:$nav")

    // 可选：若采用 WebRTC 做音视频（更稳定、自带 Opus/NetEq/JitterBuffer）
    // implementation("org.webrtc:google-webrtc:1.0.32006")
}
