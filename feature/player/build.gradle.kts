// The pattern every feature module copies: namespace, the feature plugin, its own extras.
plugins {
    alias(libs.plugins.player.feature)
}

android {
    namespace = "app.player.feature.player"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
}
