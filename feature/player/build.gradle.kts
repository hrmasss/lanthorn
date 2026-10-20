// The pattern every feature module copies: namespace, the feature plugin, its own extras.
plugins {
    alias(libs.plugins.player.feature)
}

android {
    namespace = "app.player.feature.player"
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Compose UI tests run on the JVM under Robolectric, so the CI command covers them.
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    // Emulator tests on a real engine.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
