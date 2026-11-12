import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.humansignal.research"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.humansignal.research"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-m1"
        // M4'te Ayarlar ekranından gelecek. Şimdilik gradle özelliği: -Phumansignal.apiUrl=… -Phumansignal.apiKey=…
        // Öncelik: -P özelliği → android/local.properties (git'e girmez; `npm run key:create -- --write-properties`) → dev varsayılanı
        val local = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        fun prop(k: String): String? = (project.findProperty(k) as String?) ?: local.getProperty(k)
        // Varsayılan: canlı API (Vercel). Yerel geliştirme: -Phumansignal.apiUrl=http://10.0.2.2:8787
        val apiUrl = prop("humansignal.apiUrl") ?: "https://humansignal-fawn.vercel.app"
        val apiKey = prop("humansignal.apiKey") ?: "dev-app-key"
        buildConfigField("String", "DEFAULT_API_URL", "\"$apiUrl\"")
        buildConfigField("String", "DEFAULT_API_KEY", "\"$apiKey\"")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":sdk"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
