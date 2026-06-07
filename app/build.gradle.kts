plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.ptithcm.apptranslate"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.ptithcm.apptranslate"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    
    // Thư viện Google ML Kit OCR (Giữ nguyên vì group này đúng)
    implementation(libs.play.services.mlkit.text.recognition)

    // OCR tiếng Trung (để đọc được chữ Trung trong ảnh màn hình)
    implementation(libs.mlkit.text.recognition.chinese)

    // Thư viện Google ML Kit Dịch và Nhận diện ngôn ngữ (Đã sửa alias)
    implementation(libs.mlkit.language.id)
    implementation(libs.mlkit.translate)

    // Navigation Component
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.fragment)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}