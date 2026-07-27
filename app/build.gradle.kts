plugins {
    alias(libs.plugins.android.application)
    kotlin("plugin.serialization") version "1.9.22"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("com.google.gms.google-services")
}

// Read local.properties directly rather than via java.util.Properties, so the
// build script stays pure Kotlin.
val localPropertiesFile = rootProject.file("local.properties")

/** Value of [key] in local.properties, or null when the file/key is absent. */
fun localProperty(key: String): String? =
    if (localPropertiesFile.exists()) {
        localPropertiesFile
            .readLines()
            .find { it.startsWith("$key=") }
            ?.substringAfter("=")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    } else {
        null
    }

// Debug-only convenience: on an emulator, skip sign-in and run the app as a
// local-only dev account, even when INDIC_API_BASE_URL is set. Cloud calls are
// switched off along with it (see DevAuth), so nothing hits the backend
// unauthenticated. Release builds never get this — the field is hardcoded false
// below. Put INDIC_DEV_AUTH_BYPASS=false in local.properties to exercise the
// real sign-in flow on an emulator.
val devAuthBypass = localProperty("INDIC_DEV_AUTH_BYPASS") != "false"
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
        // Overridden per build type below; the default keeps the flag defined
        // for any variant that doesn't set it (androidTest, lint models).
        buildConfigField("boolean", "DEV_AUTH_BYPASS", "false")

        // Ship arm64-v8a only. Every Android phone from ~2019 onward is 64-bit
        // ARM, so a 2022+ target needs nothing else; armeabi-v7a (32-bit) and
        // x86/x86_64 (emulators only) would just bloat the release APK with
        // native code no real device runs. "Test what you ship": CI builds
        // exactly this ABI, and so does the release build type.
        //
        // The debug build type adds x86_64 on top of this (see buildTypes) so
        // emulator runs work without anyone having to remember a flag.
        //
        // Local override: -PabiFilters=x86_64 pins the build to one ABI
        // (comma-separated for several), for a faster single-target build.
        val requestedAbis =
            (project.findProperty("abiFilters") as String?)
                ?.takeIf { it.isNotBlank() }
                ?.split(",")
                ?: listOf("arm64-v8a")
        ndk { abiFilters.addAll(requestedAbis) }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                // Vendored OpenCV defaults ENABLE_CCACHE to ON for Ninja builds,
                // and when it finds a ccache on PATH it installs it as a GLOBAL
                // RULE_LAUNCH_COMPILE — so it wraps our targets too, not just
                // OpenCV's. Upstream hardcodes IS_CCACHE_WORKS=1 (its own check
                // is commented out as non-functional), so a ccache that fails to
                // start takes down every translation unit with no compiler
                // diagnostic at all. Not a trade we want for a cache.
                arguments += "-DENABLE_CCACHE=OFF"

                // ...but an explicitly requested launcher is a different
                // matter: CI opts in with -PnativeCompilerLauncher=ccache,
                // where the cache is content-addressed and so survives the
                // fresh mtimes that actions/cache gives every restored object
                // file (which is what made the .cxx cache hit and still
                // recompile everything). Unset locally, so nothing changes for
                // developers unless they ask for it.
                val launcher =
                    (project.findProperty("nativeCompilerLauncher") as String?)
                        ?.takeIf { it.isNotBlank() }
                if (launcher != null) {
                    arguments += "-DCMAKE_C_COMPILER_LAUNCHER=$launcher"
                    arguments += "-DCMAKE_CXX_COMPILER_LAUNCHER=$launcher"
                }
            }
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "DEV_AUTH_BYPASS", "$devAuthBypass")

            // Emulators are x86_64. An arm64-only APK does install there and
            // runs under ARM translation (berberis), but libomp aborts inside
            // __kmp_parallel_initialize the moment the engine opens a parallel
            // region — SIGABRT with no usable diagnostic. Carrying x86_64 in
            // every debug APK means the emulator runs native code whatever
            // installs it, including Android Studio's Run button, which cannot
            // pass -PabiFilters. Costs one extra OpenCV compile (cached after
            // the first) and APK size that never reaches a user; opt out with
            // -PabiFilters=arm64-v8a.
            if (project.findProperty("abiFilters") == null) {
                ndk { abiFilters.add("x86_64") }
            }
        }
        release {
            // Never in a shipped build, whatever local.properties says.
            buildConfigField("boolean", "DEV_AUTH_BYPASS", "false")
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
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")

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

// Static analysis gate: `./gradlew :app:detekt` (CI). The codebase is kept
// clean of findings, so there is no baseline — any new issue fails the build.
detekt {
    buildUponDefaultConfig = true
}
