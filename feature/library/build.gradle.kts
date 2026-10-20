plugins {
    alias(libs.plugins.player.feature)
}

android {
    namespace = "app.player.feature.library"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    testImplementation(libs.kotlinx.coroutines.test)
}
