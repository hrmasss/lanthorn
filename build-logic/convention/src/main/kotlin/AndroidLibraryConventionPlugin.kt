import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            configureAndroid(this)
            defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            defaultConfig.consumerProguardFiles("consumer-rules.pro")
        }
        dependencies {
            add("testImplementation", catalog.findLibrary("junit").get())
            // @Ears and evidenceShot() for every module's tests (P1.35).
            if (path != ":core:testing") {
                add("testImplementation", project(":core:testing"))
                add("androidTestImplementation", project(":core:testing"))
            }
        }
        // Instrumented-test APKs only run on the x86_64 emulator. Without this each one carries
        // libmpv for four ABIs (about 140 MB) and the CI artifact grows past 400 MB.
        extensions.configure<LibraryAndroidComponentsExtension> {
            onVariants { variant ->
                variant.deviceTests.values.forEach { test ->
                    test.packaging.jniLibs.excludes.addAll("lib/arm64-v8a/**", "lib/armeabi-v7a/**", "lib/x86/**")
                }
            }
        }
    }
}
