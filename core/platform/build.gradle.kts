plugins {
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.furunov.komalauncher.core.platform"
    compileSdk = 37
    defaultConfig { minSdk = 33 }
    buildFeatures { compose = true }
}

kotlin { jvmToolchain(21) }

// Android の API を包むアダプタだけを置く。機能のモジュールはここに依存しない（:app が組み立てる）
dependencies {
    api(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.mlkit.subject.segmentation)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
}
