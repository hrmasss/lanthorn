plugins {
    alias(libs.plugins.player.android.library)
}

android {
    namespace = "app.player.core.engine"
}

dependencies {
    // Prebuilt libmpv + FFmpeg + libass. Provenance and licence: docs/decisions/2026-10-20-libmpv-source.md (workspace).
    implementation(libs.libmpv)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.kotlinx.coroutines.test)
}
