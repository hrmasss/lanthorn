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
        // libmpv AAR built by our fork's Actions, attached to its GitHub releases. Pinned by tag in
        // the version catalog and by sha256 in gradle/verification-metadata.xml.
        ivy {
            name = "libmpv-releases"
            url = uri("https://github.com/hrmasss/libmpv-android/releases/download/")
            patternLayout { artifact("[revision]/[module]-[revision].[ext]") }
            metadataSources { artifact() }
            content { includeModule("hrmasss", "libmpv") }
        }
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
include(":feature:info")
include(":feature:library")
include(":feature:subtitles")
include(":feature:audio")
