plugins {
    alias(libs.plugins.player.feature)
}

android {
    namespace = "app.player.feature.playback"
}

dependencies {
    implementation(project(":core:settings-api"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}
