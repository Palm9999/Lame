import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String) = findLibrary(alias).get()

internal object Sdk {
    // Compose 1.12 libraries require compiling against API 37.
    const val COMPILE = 37
    const val TARGET = 36

    /** The S24 Ultra shipped on Android 14 (API 34); nothing older needs supporting. */
    const val MIN = 34
}

/** JVM 17 bytecode everywhere, so every module is consumable from Android. */
internal fun Project.configureKotlin() {
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            allWarningsAsErrors.set(true)
        }
    }
}

/**
 * Hands every test task the real ETL-built database named by GRIDIRON_STATS_DB,
 * for tests that run against it (they skip without it). A relative path
 * resolves from the repository root, since tests run with the module as their
 * working directory. The file is a declared input, so rebuilding the database
 * re-runs the tests rather than reporting them up to date.
 */
internal fun Project.configureTests() {
    val statsDb = providers.environmentVariable("GRIDIRON_STATS_DB").orNull?.let { rootDir.resolve(it) }
    tasks.withType<Test>().configureEach {
        inputs.property("statsDbPath", statsDb?.path ?: "")
        if (statsDb != null) {
            environment("GRIDIRON_STATS_DB", statsDb.path)
            if (statsDb.isFile) inputs.file(statsDb).withPropertyName("statsDb")
        }
        testLogging {
            events("failed", "skipped")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}
