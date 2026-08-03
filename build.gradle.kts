// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    id("com.diffplug.spotless") version "8.0.0"
    id("com.google.gms.google-services") version "4.5.0" apply false
}

tasks.register("ciReleaseGate") {
    group = "verification"
    description = "Mirrors CI tiers 1 + 5 locally (app gates + release build). Native/backend run separately."
    dependsOn(
        "spotlessCheck",
        ":app:detekt",
        ":app:lintDebug",
        ":app:testDebugUnitTest",
        ":app:minifyReleaseWithR8",
        ":app:assembleRelease",
    )
}

// Formatting gate: `./gradlew spotlessCheck` (CI) / `./gradlew spotlessApply` (fix).
// Kotlin only — the C++ engine under native/ is deliberately excluded.
spotless {
    kotlin {
        target("app/src/**/*.kt")
        targetExclude("**/build/**", "app/src/main/cpp/**", "native/**")
        ktlint("1.5.0").editorConfigOverride(
            mapOf(
                // Keep the gate about consistency, not churn: these rules would
                // force large mechanical rewrites with no readability payoff.
                "max_line_length" to "off",
                "ktlint_standard_no-wildcard-imports" to "disabled",
                "ktlint_standard_filename" to "disabled",
                "ktlint_standard_property-naming" to "disabled",
                "ktlint_standard_function-naming" to "disabled",
                // These two JOIN wrapped declarations onto one line. With
                // max_line_length off they produce >120-char lines that the
                // detekt gate (MaxLineLength 120) then rejects — the two gates
                // must not fight over the same lines.
                "ktlint_standard_function-signature" to "disabled",
                "ktlint_standard_function-expression-body" to "disabled",
            ),
        )
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts")
        ktlint("1.5.0")
    }
}
