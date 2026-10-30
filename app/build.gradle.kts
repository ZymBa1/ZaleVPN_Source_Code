plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zale.vpn"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zale.vpn"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    implementation("com.google.zxing:core:3.5.3")
}
