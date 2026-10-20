plugins {
    alias(libs.plugins.player.android.library)
}

android {
    namespace = "app.player.core.engine"
}

dependencies {
    // libmpv + FFmpeg + libass, built in hrmasss/libmpv-android's Actions. Provenance, licence and
    // the filter list: docs/decisions/2026-10-20-libmpv-source.md (workspace).
    implementation(libs.libmpv) { artifact { type = "aar" } }
    implementation(libs.kotlinx.coroutines.android)
    api(libs.androidx.datastore.preferences) // DecoderSettings.snapshot/restore expose Preferences
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
