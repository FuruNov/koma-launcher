pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "koma-launcher"

include(
    ":app",
    ":core:model",
    ":core:ui",
    ":core:platform",
    ":feature:home",
    ":feature:drawer",
    ":feature:widgets",
    ":feature:settings",
    ":feature:editor",
    ":tools:vis-replay",
)
