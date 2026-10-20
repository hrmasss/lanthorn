// Extension points the settings screen hosts. Feature owners implement them; app wires them in.
plugins {
    alias(libs.plugins.player.android.library)
    alias(libs.plugins.player.android.compose)
}

android {
    namespace = "app.player.core.settingsapi"
}

dependencies {
    api(libs.androidx.datastore.preferences)
    testImplementation(libs.json)
}
