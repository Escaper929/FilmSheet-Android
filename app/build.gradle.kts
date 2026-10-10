plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.filmsheet.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.filmsheet.android"
        // 24 = Android 7.0. 与 iOS 端 iOS 17.0 的理由相同：PhotoPicker、
        // BiometricPrompt 之类的新 API 都不是必需，用低一点换取覆盖面。
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // EXIF 方向校正。相机 JPEG 靠它转正 —— inSampleSize 不管这件事，
    // 少了它竖拍照片会躺着进拼版。
    implementation(libs.androidx.exifinterface)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}