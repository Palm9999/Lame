import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.findByType
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/** Compose for an Android application or library module. Apply after either. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        extensions.findByType<ApplicationExtension>()?.buildFeatures?.compose = true
        extensions.findByType<LibraryExtension>()?.buildFeatures?.compose = true

        extensions.configure<ComposeCompilerGradlePluginExtension> {
            // Types from the pure-Kotlin modules aren't compiled by the Compose
            // compiler, so it would treat them as unstable and recompose every
            // table row on every frame. They are immutable; say so.
            stabilityConfigurationFiles.add(
                rootProject.layout.projectDirectory.file("compose_stability.conf"),
            )
        }

        dependencies {
            val bom = platform(libs.lib("androidx-compose-bom"))
            add("implementation", bom)
            add("testImplementation", bom)
            add("implementation", libs.lib("androidx-compose-ui"))
            add("implementation", libs.lib("androidx-compose-foundation"))
            add("implementation", libs.lib("androidx-compose-material3"))
            add("implementation", libs.lib("androidx-compose-ui-tooling-preview"))
            add("debugImplementation", libs.lib("androidx-compose-ui-tooling"))
        }
    }
}
