// Bench mode for perf/bench.sh. Linked into debug and bench builds only, never release.
plugins {
    alias(libs.plugins.player.android.library)
    alias(libs.plugins.player.android.compose)
}

android {
    namespace = "app.player.bench"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:player-api"))
    implementation(project(":feature:player"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
}
