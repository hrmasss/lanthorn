plugins {
    `kotlin-dsl`
}

kotlin { jvmToolchain(21) }

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.compose.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "player.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "player.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "player.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("feature") {
            id = "player.feature"
            implementationClass = "FeatureConventionPlugin"
        }
    }
}
