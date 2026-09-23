plugins {
    alias(libs.plugins.gridiron.android.application)
    alias(libs.plugins.gridiron.android.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.gridiron.app"
    defaultConfig {
        applicationId = "dev.gridiron.app"
        versionCode = 2
        versionName = "0.2.0"
    }

    // A personal, sideloaded app. Android only installs an update signed with
    // the same key as the installed app, so every build (local or CI) must use
    // one fixed key, and it lives in the repository. That is fine for an app
    // that is never published, and would not be for one that is.
    signingConfigs {
        create("personal") {
            storeFile = file("gridiron.keystore")
            storePassword = "gridiron"
            keyAlias = "gridiron"
            keyPassword = "gridiron"
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("personal")
        }
        // The build to install. Not debuggable, so Compose runs at full speed
        // (debug builds are markedly slower). Not minified: R8 problems only
        // appear on a device, and size doesn't matter for a sideloaded app.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
    }
}

dependencies {
    implementation(projects.feature.players)
    implementation(projects.feature.compare)
    implementation(projects.feature.scoring)
    implementation(projects.core.data)
    implementation(projects.core.datastore)
    implementation(projects.core.database)
    implementation(projects.core.designsystem)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.kotlinx.serialization.json)

    // The navigation test drives the real Grid, Compare and scoring screens
    // over the real database, like the feature modules' own screen tests.
    testImplementation(projects.core.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

/**
 * Bundles the ETL-built stats database into the APK as assets/stats.db.
 * Uses GRIDIRON_STATS_DB if set, else etl/build/stats.db.
 */
abstract class BundleStatsDb : DefaultTask() {
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val database: RegularFileProperty

    @get:Input
    abstract val expectedPath: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val source = database.orNull?.asFile
        if (source == null || !source.isFile) {
            throw GradleException(
                "No stats database at ${expectedPath.get()}. Build one first:\n" +
                    "  cd etl && python -m gridiron_etl.build --seasons 2024 2025 2026 --out build/stats.db",
            )
        }
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        source.copyTo(out.resolve("stats.db"))
    }
}

val statsDbPath: File = rootDir.resolve(providers.environmentVariable("GRIDIRON_STATS_DB").getOrElse("etl/build/stats.db"))
val bundleStatsDb = tasks.register<BundleStatsDb>("bundleStatsDb") {
    expectedPath.set(statsDbPath.path)
    if (statsDbPath.isFile) database.set(statsDbPath)
}
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleStatsDb, BundleStatsDb::outputDir)
    }
}
