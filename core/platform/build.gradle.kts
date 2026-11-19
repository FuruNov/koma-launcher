plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.furunov.komalauncher.core.platform"
    compileSdk = 37
    defaultConfig { minSdk = 33 }
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core:model"))
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
}
