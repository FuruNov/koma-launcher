// 実機で記録したビジュアライザーの生の値を PC で再生し、棒の計算を設定ごとに比べる道具。アプリには入らない
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core:model"))
}

application {
    mainClass.set("dev.furunov.komalauncher.tools.VisReplayKt")
}

// 相対パスの引数を、gradlew を呼んだディレクトリから解決する
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
