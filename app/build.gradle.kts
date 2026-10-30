plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.mrassaf.firewall"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mrassaf.firewall"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
