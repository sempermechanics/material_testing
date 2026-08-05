import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    // Coverage measurement only (report-only, no gate). Generate with
    // `./gradlew :app:koverHtmlReport` → app/build/reports/kover/.
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
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
// Base URL of the Semper GCP backend (API Gateway or Cloud Run). Empty = cloud
// sync disabled for local/debug. Release builds that set -PrequireCloudApi=true
// (CI Release workflow) MUST inject INDIC_API_BASE_URL via env or
// local.properties — otherwise the build fails instead of silently shipping
// offline-only.
val indicApiBaseUrl =
    System.getenv("INDIC_API_BASE_URL")?.trim()?.takeIf { it.isNotEmpty() }
        ?: if (localPropertiesFile.exists()) {
            localPropertiesFile
                .readLines()
                .find { it.startsWith("INDIC_API_BASE_URL=") }
                ?.substringAfter("=")
                ?.trim() ?: ""
        } else {
            ""
        }

val requireCloudApi =
    (project.findProperty("requireCloudApi") as String?)?.equals("true", ignoreCase = true) == true
if (requireCloudApi && indicApiBaseUrl.isBlank()) {
    throw GradleException(
        "INDIC_API_BASE_URL is required for this release build " +
            "(-PrequireCloudApi=true). Set the env var or local.properties entry " +
            "to the API Gateway URL so cloud sync is not silently disabled.",
    )
}
if (requireCloudApi && !indicApiBaseUrl.startsWith("https://")) {
    throw GradleException("INDIC_API_BASE_URL must be https when requireCloudApi=true: $indicApiBaseUrl")
}

// Optional comma-separated CertificatePinner pins for the API host
// (e.g. sha256/AAAA...=). Empty = system trust store only.
val indicApiCertPins =
    if (localPropertiesFile.exists()) {
        localPropertiesFile
            .readLines()
            .find { it.startsWith("INDIC_API_CERT_PINS=") }
            ?.substringAfter("=")
            ?.trim() ?: ""
    } else {
        ""
    }

// Release signing. The keystore and passwords come from the environment
// (SIGNING_* — set by .github/workflows/release.yml and the tier-5 CI job),
// never from the repo. When the keystore is absent — every local build, and
// any CI run without the secrets — the release variant simply stays unsigned
// instead of failing, so `assembleRelease` still works for inspection.
val releaseKeystore =
    System
        .getenv("SIGNING_KEYSTORE")
        ?.takeIf { it.isNotBlank() }
        ?.let { rootProject.file(it) }
        ?.takeIf { it.exists() }

android {
    namespace = "com.indicvision.semper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.indicvision.semper"
        minSdk = 24
        targetSdk = 36
        // Overridable from the release workflow: -PversionCode / -PversionName.
        // Every build handed to anyone must bump versionCode (see docs/ops/RELEASING.md).
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?)?.takeIf { it.isNotBlank() } ?: "1.0"

        buildConfigField("String", "INDIC_API_BASE_URL", "\"$indicApiBaseUrl\"")
        buildConfigField("String", "INDIC_API_CERT_PINS", "\"$indicApiCertPins\"")
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
                // Portable engine package at repo-root native/; JNI adapter on.
                arguments += "-DSEMPER_ANDROID=ON"

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
        // findViewById is used throughout; generating unused binding classes only
        // slows compile and confuses contributors into thinking ViewBinding is adopted.
        viewBinding = false
    }

    signingConfigs {
        create("release") {
            releaseKeystore?.let { ks ->
                storeFile = ks
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "DEV_AUTH_BYPASS", "$devAuthBypass")
            // Enables JVM unit-test coverage collection for Kover/JaCoCo tooling.
            enableUnitTestCoverage = true

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
            // Sign with the release key when the keystore is present (CI with
            // secrets). Absent it, the variant stays unsigned rather than
            // silently debug-signed, so an unsigned APK never masquerades as a
            // release build.
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Keep the build offline/credential-free: the plugin still injects the
            // build-ID resource the SDK needs, it just skips uploading the R8
            // mapping to Firebase at build time.
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = false
            }
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
            path = file("../native/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    testOptions {
        unitTests {
            // Robolectric (UploadResumableTest) needs the real resource table.
            isIncludeAndroidResources = true
        }
    }

    // Android Lint gate: empty baseline; new issues fail CI.
    // Version-availability noise is silenced — bumps are deliberate catalog PRs.
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
        // Layouts are fully extracted to strings.xml — keep it that way.
        error += "HardcodedText"
        disable +=
            setOf(
                "GradleDependency",
                "NewerVersionAvailable",
                "AndroidGradlePluginVersion",
                // Photo Picker is the primary path; broad gallery access is unused.
                "SelectedPhotoAccess",
            )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    // Firebase Authentication (email/password, email-link, Google) — the identity layer.
    implementation(libs.firebase.auth)
    // Crash + non-fatal reporting (field visibility for release builds). The
    // Crashlytics Gradle plugin (applied above) injects the build-ID resource the
    // SDK requires at startup; mapping-file upload is disabled below so no build-time
    // network/credentials are needed. Non-fatals and breadcrumbs from
    // CrashReportingTree still flow.
    implementation(libs.firebase.crashlytics)
    // Await() on Firebase Task<T> from coroutines.
    implementation(libs.kotlinx.coroutines.play.services)
    // AndroidX transitions power the shared-element / expand-collapse motion in ui/Motion.kt
    implementation(libs.androidx.transition)
    // Pull-to-refresh on the Home list (re-checks cloud backup state on demand)
    implementation(libs.androidx.swiperefreshlayout)
    testImplementation(libs.junit)
    // JVM tests for the network layer: MockWebServer fakes the backend/Drive,
    // Robolectric supplies a real Context + org.json without a device.
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.mockwebserver.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.junit)
    // 3.6.1 crashes on API 37 (Espresso's InputManagerEventInjectionStrategy
    // calls the hidden InputManager.getInstance, removed in Android 17).
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.rules)

    // Google SSO via Credential Manager (native one-tap) → Google ID token
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    // Semper GCP backend client: OkHttp + kotlinx.serialization
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.timber)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.recyclerview)
}

// Static analysis gate: `./gradlew :app:detekt` (CI). New findings fail the
// build. A few large UI orchestration files use targeted @file:Suppress for
// inherent size/complexity; keep that list small and prefer extracts instead.
detekt {
    buildUponDefaultConfig = true
    // Empty baseline retained so `./gradlew :app:detektBaseline` can still
    // freeze accidental regressions deliberately if needed.
    baseline = file("detekt-baseline.xml")
}

// Coverage: exclude UI; verify a modest line floor on remaining first-party code.
kover {
    reports {
        filters {
            excludes {
                packages("com.indicvision.semper.ui")
            }
        }
        verify {
            // Modest floor after excluding UI; raise deliberately when measured higher.
            rule {
                minBound(15)
            }
        }
    }
}
