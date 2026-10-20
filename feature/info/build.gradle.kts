// Info panel (US-37). Owned by engine.
plugins {
    alias(libs.plugins.player.feature)
}

android {
    namespace = "app.player.feature.info"
}

dependencies {
    implementation(project(":core:settings-api"))
}

dependencies {
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
