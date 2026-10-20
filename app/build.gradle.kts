import java.util.Properties

plugins {
    alias(libs.plugins.player.android.application)
    alias(libs.plugins.player.android.compose)
    alias(libs.plugins.baselineprofile)
}

// Release signing comes from env (CI) or keystore.properties (local, never committed).
// The key itself lives outside git: ~/re/player-keys/ on hapl-aux and a GitHub Actions secret.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = System.getenv(env) ?: keystoreProps.getProperty(key)

android {
    namespace = "app.player"

    defaultConfig {
        // Codename id. Changes when brand's name lands.
        applicationId = "app.player"
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        val storeFile = signingValue("storeFile", "PLAYER_KEYSTORE")
        if (storeFile != null) {
            create("release") {
                this.storeFile = file(storeFile)
                storePassword = signingValue("storePassword", "PLAYER_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "PLAYER_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "PLAYER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:engine"))
    implementation(project(":core:data"))
    implementation(project(":feature:player"))
    implementation(project(":feature:subtitles"))
    implementation(project(":feature:audio"))
    implementation(project(":core:player-api"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    testImplementation(libs.junit)
}
