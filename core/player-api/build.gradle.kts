// Extension points the player screen hosts. Features implement them; app wires them in.
plugins {
    alias(libs.plugins.player.android.library)
    alias(libs.plugins.player.android.compose)
}

android {
    namespace = "app.player.core.playerapi"
}

dependencies {
    api(project(":core:engine"))
}
