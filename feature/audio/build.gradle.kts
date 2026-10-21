plugins {
    alias(libs.plugins.player.feature)
}

android {
    namespace = "app.player.feature.audio"
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
    androidTestImplementation(libs.androidx.uiautomator)
}
