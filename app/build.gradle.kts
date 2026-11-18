plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dafu.estimator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dafu.estimator"
        // RapidOCR4j Android requires Android 10 / API 29 or newer.
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    implementation("androidx.webkit:webkit:1.11.0")
    // PP-OCRv4 Chinese mobile models + ONNX Runtime + OpenCV; bundled for offline inference.
    implementation("io.github.hzkitty:rapidocr4j-android:1.0.0")
}
