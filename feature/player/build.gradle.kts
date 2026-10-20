// The pattern every feature module copies: namespace, the feature plugin, nothing else by default.
plugins {
    alias(libs.plugins.player.feature)
}

android {
    namespace = "app.player.feature.player"
}
