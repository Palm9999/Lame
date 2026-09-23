import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        extensions.configure<ApplicationExtension> {
            compileSdk = Sdk.COMPILE
            defaultConfig {
                minSdk = Sdk.MIN
                targetSdk = Sdk.TARGET
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            testOptions { unitTests.isIncludeAndroidResources = true }
        }
        configureKotlin()
        configureTests()
        // Robolectric's Android 16 image reaches into JDK internals that Java 17+
        // only exposes when asked (same as AndroidLibraryConventionPlugin).
        tasks.withType<Test>().configureEach {
            jvmArgs(
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
            )
        }
    }
}
