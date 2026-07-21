plugins {
    alias(libs.plugins.android.application)
    kotlin("plugin.serialization") version "1.9.22"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("com.google.gms.google-services")
}

// Read local.properties directly rather than via java.util.Properties, so the
// build script stays pure Kotlin.
val localPropertiesFile = rootProject.file("local.properties")
// Base URL of the inDIC GCP backend (Cloud Run). Empty = cloud sync disabled;
// the app still runs fully offline. e.g. https://indic-api-xxxx.a.run.app
// The Google client ID is NOT read here: Firebase Auth supplies it via the
// google-services plugin as the default_web_client_id resource.
// See docs/backend/AUTH_SETUP.md.
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

android {
    namespace = "com.rafad.indicvisiondic"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rafad.indicvisiondic"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "INDIC_API_BASE_URL", "\"$indicApiBaseUrl\"")

        // Ship arm64-v8a only. Every Android phone from ~2019 onward is 64-bit
        // ARM, so a 2022+ target needs nothing else; armeabi-v7a (32-bit) and
        // x86/x86_64 (emulators only) would just triple the OpenCV build and
        // bloat the APK with native code no real device runs. "Test what you
        // ship": CI builds exactly this ABI.
        //
        // Local override: -PabiFilters=x86_64 builds for an emulator instead
        // (comma-separated for several). Defaults to arm64-v8a when unset.
        val requestedAbis =
            (project.findProperty("abiFilters") as String?)
                ?.takeIf { it.isNotBlank() }
                ?.split(",")
                ?: listOf("arm64-v8a")
        ndk { abiFilters.addAll(requestedAbis) }

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

    // No ABI splits: the app targets a single ABI (arm64-v8a, see abiFilters
    // above), so each build produces one APK (e.g. app-debug.apk) — there is
    // nothing to split per architecture.

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

    testOptions {
        unitTests {
            // Robolectric (UploadResumableTest) needs the real resource table.
            isIncludeAndroidResources = true
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
    implementation(platform("com.google.firebase:firebase-bom:34.16.0"))
    implementation("com.google.firebase:firebase-analytics")
    // Firebase Authentication (email/password, email-link, Google) — the identity layer.
    implementation("com.google.firebase:firebase-auth")
    // Await() on Firebase Task<T> from coroutines.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    // AndroidX transitions power the shared-element / expand-collapse motion in ui/Motion.kt
    implementation("androidx.transition:transition:1.5.1")
    // Pull-to-refresh on the Home list (re-checks cloud backup state on demand)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    testImplementation(libs.junit)
    // JVM tests for the network layer: MockWebServer fakes the backend/Drive,
    // Robolectric supplies a real Context + org.json without a device.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
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
