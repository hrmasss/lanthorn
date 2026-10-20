import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Apply after an Android application or library plugin. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        extensions.getByType(CommonExtension::class.java).buildFeatures.compose = true
        dependencies {
            val bom = catalog.findLibrary("compose-bom").get()
            add("implementation", platform(bom))
            add("implementation", catalog.findLibrary("compose-ui").get())
            add("implementation", catalog.findLibrary("compose-ui-tooling-preview").get())
            add("implementation", catalog.findLibrary("compose-material3").get())
            add("debugImplementation", catalog.findLibrary("compose-ui-tooling").get())
        }
    }
}
