pluginManagement {
    includeBuild("build-logic")
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

rootProject.name = "player"

include(":app")
include(":baselineprofile")
include(":bench")
include(":core:engine")
include(":core:data")
include(":core:designsystem")
include(":core:player-api")
include(":core:settings-api")
include(":feature:player")
include(":feature:library")
include(":feature:subtitles")
include(":feature:audio")
