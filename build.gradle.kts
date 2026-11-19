plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.spotless)
}

// 整形の規則は全モジュールで同じにしたいので、ルートから一括で掛ける
spotless {
    kotlin {
        target("**/src/**/*.kt")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(
            // Compose の関数は大文字で始めるのが慣習なので、関数名の規則を外す
            mapOf("ktlint_function_naming_ignore_when_annotated_with" to "Composable"),
        )
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}
