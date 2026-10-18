plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.resonance.hires"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.resonance.hires"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-alpha"
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.5.1")
}
