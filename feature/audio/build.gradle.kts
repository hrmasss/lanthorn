plugins {
    alias(libs.plugins.player.feature)
}

android {
    namespace = "app.player.feature.audio"
    // Each test in its own process: a test that hangs cannot leave a stuck engine to the next one.
    testOptions.execution = "ANDROIDX_TEST_ORCHESTRATOR"
}

dependencies {
    implementation(project(":core:settings-api"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestUtil(libs.androidx.test.orchestrator)
    androidTestUtil(libs.androidx.test.services)
    androidTestImplementation(libs.androidx.uiautomator)
}
