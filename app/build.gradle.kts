plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jiyue.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jiyue.reader"
        minSdk = 26
        targetSdk = 35
        versionCode = 24
        versionName = "2.0.4"
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
    implementation("androidx.core:core-ktx:1.15.0")
}
