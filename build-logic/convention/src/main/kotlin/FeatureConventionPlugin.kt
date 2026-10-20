import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * A feature module: Compose UI that talks to the engine through its public API only.
 * Features may depend on core modules, never on another feature.
 */
class FeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("player.android.library")
        pluginManager.apply("player.android.compose")
        dependencies {
            add("implementation", project(":core:designsystem"))
            add("implementation", project(":core:engine"))
            add("implementation", project(":core:data"))
            add("implementation", catalog.findLibrary("androidx-lifecycle-runtime-compose").get())
            add("implementation", catalog.findLibrary("androidx-lifecycle-viewmodel-compose").get())
            add("implementation", catalog.findLibrary("kotlinx-coroutines-android").get())
        }
        // Guard the module boundary: a feature that depends on another feature fails the build.
        afterEvaluate {
            configurations.forEach { conf ->
                conf.dependencies.forEach { dep ->
                    val path = (dep as? org.gradle.api.artifacts.ProjectDependency)?.path
                    if (path != null && path.startsWith(":feature:") && path != this.path) {
                        throw org.gradle.api.GradleException("$this depends on $path. Features must not depend on features.")
                    }
                }
            }
        }
    }
}
