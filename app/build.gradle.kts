plugins {
    alias(libs.plugins.android.application)
    kotlin("plugin.serialization") version "1.9.22"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

// 🚀 PURE KOTLIN BYPASS: Reads the file without needing 'java.util'
val localPropertiesFile = rootProject.file("local.properties")
// Base URL of the inDIC GCP backend (Cloud Run). Empty = cloud sync disabled;
// the app still runs fully offline. e.g. https://indic-api-xxxx.a.run.app
val indicApiBaseUrl =
    if (localPropertiesFile.exists()) {
        localPropertiesFile
            .readLines()
            .find { it.startsWith("INDIC_API_BASE_URL=") }
            ?.substringAfter("=")
            ?.trim() ?: ""
    } else {
        ""
    }
// Google OAuth *Web* client ID (from Google Cloud console). Used by the
// Credential Manager one-tap flow to obtain a Google ID token that the backend
// verifies. See docs/GOOGLE_SSO_SETUP.md. Empty = SSO button shows a setup hint.
val googleWebClientId =
    if (localPropertiesFile.exists()) {
        localPropertiesFile
            .readLines()
            .find { it.startsWith("GOOGLE_WEB_CLIENT_ID=") }
            ?.substringAfter("=")
            ?.trim() ?: ""
    } else {
        ""
    }

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
        buildConfigField("String", "INDIC_API_BASE_URL", "\"$indicApiBaseUrl\"")
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
                "proguard-rules.pro",
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

    // Android Lint gate: existing findings frozen in lint-baseline.xml;
    // only new issues fail `./gradlew :app:lintDebug` (CI).
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
        // Layouts are fully extracted to strings.xml — keep it that way.
        error += "HardcodedText"
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

    // 🚀 Google SSO via Credential Manager (native one-tap) → Google ID token
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // inDIC GCP backend client: OkHttp + kotlinx.serialization
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.jakewharton.timber:timber:5.0.1")
}

// Static analysis gate: `./gradlew :app:detekt` (CI). Existing findings are
// frozen in detekt-baseline.xml — only NEW issues fail the build. Regenerate
// deliberately with `./gradlew :app:detektBaseline`.
detekt {
    buildUponDefaultConfig = true
    baseline = file("detekt-baseline.xml")
}
