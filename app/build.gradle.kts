plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.productwatcher.app"
    compileSdk = 36
    defaultConfig { applicationId = "com.productwatcher.app"; minSdk = 26; targetSdk = 35; versionCode = 2; versionName = "0.2.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.github.capullo-tech:lib-tdlib-android:main-SNAPSHOT")
}
