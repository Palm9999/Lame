import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** Android-free Kotlin modules: :core:model, :core:statquery, :core:database, :core:data. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        extensions.configure<KotlinJvmProjectExtension> { explicitApi() }
        configureKotlin()
        configureTests()

        dependencies {
            add("testImplementation", platform(libs.lib("junit-bom")))
            add("testImplementation", libs.lib("junit-jupiter"))
            add("testRuntimeOnly", libs.lib("junit-platform-launcher"))
        }
        tasks.withType<Test>().configureEach { useJUnitPlatform() }
    }
}
