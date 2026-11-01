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
        versionCode = 12
        versionName = "1.2 Lolipop"

        // Все адреса, которые нельзя публиковать, лежат в папке SECRET/ (urls.properties).
        // В публичный репозиторий папка SECRET не попадает — её удаляют перед выкладкой.
        // Если папки нет, сборка проходит, но без адреса Worker'а.
        val secretFile = rootProject.file("SECRET/urls.properties")
        val secretProps = java.util.Properties().apply {
            if (secretFile.isFile) runCatching { secretFile.inputStream().use { load(it) } }
        }
        val workerUrl = (secretProps.getProperty("worker") ?: "").trim().trimEnd('/')
        buildConfigField(
            "String",
            "WORKER_URL",
            "\"${workerUrl.trim().replace("\\", "\\\\").replace("\"", "\\\"")}\""
        )
    }
    buildFeatures {
        buildConfig = true
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
