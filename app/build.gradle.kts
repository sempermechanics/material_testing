plugins {
    alias(libs.plugins.android.application)
    // If using build.gradle.kts
    kotlin("plugin.serialization") version "1.9.22" // Match this to your project's Kotlin version

}

android {
    namespace = "com.rafad.indicvisiondic"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.rafad.indicvisiondic"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters.add("arm64-v8a") // Forces compilation of the 64-bit NEON code
        }
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
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    val camerax_version = "1.3.0-alpha04" // Or latest stable
    implementation("androidx.camera:camera-core:${camerax_version}")
    implementation("androidx.camera:camera-camera2:${camerax_version}")
    implementation("androidx.camera:camera-lifecycle:${camerax_version}")
    implementation("androidx.camera:camera-view:${camerax_version}")
    implementation("androidx.camera:camera-extensions:${camerax_version}")
    implementation("androidx.activity:activity-ktx:1.8.2")
    // 1. The Supabase BOM (Bill of Materials) - Keeps all module versions perfectly matched
    implementation(platform("io.github.jan-tennert.supabase:bom:3.0.3"))

    // 2. The Core Modules we are using
    implementation("io.github.jan-tennert.supabase:auth-kt")      // For Login
    implementation("io.github.jan-tennert.supabase:postgrest-kt") // For Database
    implementation("io.github.jan-tennert.supabase:storage-kt")   // For File Uploads

    // 3. The Ktor Network Engine (Supabase relies on this to send the actual HTTP requests)
    implementation("io.ktor:ktor-client-okhttp:3.0.0")

    // 4. Kotlin Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}