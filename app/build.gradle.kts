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
        // Release code with the bench mode added, debug-signed so any phone can install it.
        // Debuggable builds run slower, so perf numbers come from this one (perf/README.md).
        create("bench") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            applicationIdSuffix = ".bench"
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

    // US-68.1: declare the app's languages so the system per-app language page lists them.
    // Generated from the values-* folders; the default locale is in res/resources.properties.
    androidResources {
        generateLocaleConfig = true
    }
}

dependencies {
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.uiautomator)
    implementation(project(":core:designsystem"))
    implementation(project(":core:engine"))
    implementation(project(":core:data"))
    implementation(project(":feature:player"))
    implementation(project(":feature:info"))
    implementation(project(":feature:subtitles"))
    implementation(project(":feature:audio"))
    // After feature:player: its aliases target PlayerActivity, which must come first in the merged manifest.
    implementation(project(":feature:playback"))
    implementation(project(":core:player-api"))
    implementation(project(":core:settings-api"))
    implementation(project(":feature:library"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    // Bench mode ships in debug and bench builds, never in release.
    debugImplementation(project(":bench"))
    "benchImplementation"(project(":bench"))
    testImplementation(libs.junit)
}
