import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/** Android library modules. Unit tests run on the JVM under Robolectric (JUnit 4). */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            compileSdk = Sdk.COMPILE
            defaultConfig { minSdk = Sdk.MIN }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            testOptions { unitTests.isIncludeAndroidResources = true }
        }
        configureKotlin()
        configureTests()
        dependencies { add("testImplementation", libs.lib("junit4")) }
        // Robolectric's Android 16 image reaches into JDK internals that Java 17+
        // only exposes when asked.
        tasks.withType<Test>().configureEach {
            jvmArgs(
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
            )
        }
    }
}
