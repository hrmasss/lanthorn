// Owned by brand: theme, tokens and shared components. Engine only scaffolds it.
plugins {
    alias(libs.plugins.player.android.library)
    alias(libs.plugins.player.android.compose)
}

android {
    namespace = "app.player.core.designsystem"
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
