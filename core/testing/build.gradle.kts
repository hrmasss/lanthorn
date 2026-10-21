// Test support shared by every module's unit and on-device tests (P1.35): the @Ears tag that
// links a test to the EARS criteria it proves, and evidenceShot() for its screenshots. The
// convention plugins add it to testImplementation and androidTestImplementation everywhere.
plugins {
    alias(libs.plugins.player.android.library)
}

android {
    namespace = "app.player.core.testing"
}

dependencies {
    implementation(libs.junit)
    implementation(libs.androidx.test.runner)
}
