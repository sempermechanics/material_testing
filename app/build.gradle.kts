plugins {
    alias(libs.plugins.android.application)
    kotlin("plugin.serialization") version "1.9.22"
}

// 🚀 PURE KOTLIN BYPASS: Reads the file without needing 'java.util'
val localPropertiesFile = rootProject.file("local.properties")
val supabaseUrl = if (localPropertiesFile.exists()) {
    localPropertiesFile.readLines().find { it.startsWith("SUPABASE_URL=") }?.substringAfter("=")?.trim() ?: ""
} else ""
val supabaseAnonKey = if (localPropertiesFile.exists()) {
    localPropertiesFile.readLines().find { it.startsWith("SUPABASE_ANON_KEY=") }?.substringAfter("=")?.trim() ?: ""
} else ""
// Google OAuth *Web* client ID (from Google Cloud console). Used by the
// Credential Manager one-tap flow to obtain a Google ID token that Supabase
// verifies. See docs/GOOGLE_SSO_SETUP.md. Empty = SSO button shows a setup hint.
val googleWebClientId = if (localPropertiesFile.exists()) {
    localPropertiesFile.readLines().find { it.startsWith("GOOGLE_WEB_CLIENT_ID=") }?.substringAfter("=")?.trim() ?: ""
} else ""

android {
    namespace = "com.rafad.indicvisiondic"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rafad.indicvisiondic"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // 🚀 SECURE INJECTION: Uses our pure Kotlin variables
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")

        // 🚀 ARCHITECTURE-ADAPTIVE: No hardcoded abiFilters here.
        // The native C++/OpenCV code is compiled for every supported ABI
        // (arm64-v8a, armeabi-v7a, x86, x86_64) so the app runs natively on
        // any physical device AND any emulator without changing the build.
        // At install time Android extracts only the matching architecture's
        // .so, so each device transparently uses its own native code.
        //
        // CI override: -PabiFilters=x86_64 (comma-separated) restricts the
        // native build so e.g. the emulator smoke job doesn't compile
        // OpenCV four times. Never set for release builds.
        (project.findProperty("abiFilters") as String?)
            ?.takeIf { it.isNotBlank() }
            ?.let { filters -> ndk { abiFilters.addAll(filters.split(",")) } }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true // 🚀 THE SHREDDER IS NOW ON
            isShrinkResources = true // 🚀 Destroys unused files
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // 🚀 ADAPTIVE DELIVERY: Build one optimized APK per architecture so each
    // device gets only the native code it can actually run (smaller, faster),
    // plus a universal APK that runs anywhere. `installDebug` / Android Studio
    // automatically install the APK matching the connected device's ABI.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    // 🚀 NEW: Ensures C++ debug symbols are physically stripped from the final APK
    packaging {
        jniLibs {
            keepDebugSymbols.clear()
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity)
    // AndroidX transitions power the shared-element / expand-collapse motion in ui/Motion.kt
    implementation("androidx.transition:transition:1.5.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation("androidx.activity:activity-ktx:1.8.2")

    // 🚀 Google SSO via Credential Manager (native one-tap) + Supabase ID token
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    implementation(platform("io.github.jan-tennert.supabase:bom:3.0.3"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:storage-kt")
    implementation("io.ktor:ktor-client-okhttp:3.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}