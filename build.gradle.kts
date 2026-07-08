// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    id("com.diffplug.spotless") version "8.0.0"
}

// Formatting gate: `./gradlew spotlessCheck` (CI) / `./gradlew spotlessApply` (fix).
// Kotlin only — the C++ engine under app/src/main/cpp is deliberately excluded.
spotless {
    kotlin {
        target("app/src/**/*.kt")
        targetExclude("**/build/**")
        ktlint("1.5.0").editorConfigOverride(
            mapOf(
                // Keep the gate about consistency, not churn: these rules would
                // force large mechanical rewrites with no readability payoff.
                "max_line_length" to "off",
                "ktlint_standard_no-wildcard-imports" to "disabled",
                "ktlint_standard_filename" to "disabled",
                "ktlint_standard_property-naming" to "disabled",
                "ktlint_standard_function-naming" to "disabled",
            ),
        )
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts")
        ktlint("1.5.0")
    }
}
