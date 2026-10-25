plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.productwatcher.app"
    // TDLib's Android AAR is compiled against API 36. This does not opt the
    // installed app into Android 16 behavior; targetSdk intentionally stays 35.
    compileSdk = 36
    defaultConfig {
        applicationId = "com.productwatcher.app"
        minSdk = 26
        targetSdk = 35
        // Keep upgrades installable over the previously published versionCode 2
        // build so Android retains the existing Telegram session and app data.
        versionCode = 7
        versionName = "0.8.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}

dependencies {
    implementation("com.github.capullo-tech:lib-tdlib-android:11850efeb5791d4fd386c0eeec1ebce4c5080435")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
}
