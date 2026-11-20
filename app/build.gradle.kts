import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 鍵はリポジトリの外に置き、場所だけを local.properties で受け取る。
// debug も同じ鍵で署名するのは、実機で debug → release に替えたときに
// 署名の不一致でアンインストールが要り、フォルダとウィジェットの割り当てが消えるのを避けるため
val localProps =
    Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
val keystorePath: String? = localProps.getProperty("koma.keystore")
val keystorePassword: String? =
    localProps.getProperty("koma.keystorePasswordFile")?.let { file(it).readText().trim() }

android {
    namespace = "dev.furunov.komalauncher"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.furunov.komalauncher"
        // 実機は Android 15 以降だが、確認に使う Waydroid が Android 13（API 33）なので 33 にする
        minSdk = 33
        targetSdk = 36
        versionCode = 5
        versionName = "0.5.0"
    }

    signingConfigs {
        if (keystorePath != null && keystorePassword != null) {
            create("koma") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = "koma"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        val koma = signingConfigs.findByName("koma")
        debug {
            if (koma != null) signingConfig = koma
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (koma != null) signingConfig = koma
        }
    }

    buildFeatures { compose = true }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:platform"))
    implementation(project(":feature:home"))
    implementation(project(":feature:drawer"))
    implementation(project(":feature:widgets"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:editor"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
}
