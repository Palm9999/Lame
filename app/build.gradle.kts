plugins {
    alias(libs.plugins.gridiron.android.application)
    alias(libs.plugins.gridiron.android.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.gridiron.app"
    defaultConfig {
        applicationId = "dev.gridiron.app"
        versionCode = 4
        versionName = "0.4.0"
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
    implementation(projects.feature.projections)
    implementation(projects.core.data)
    implementation(projects.core.projections)
    implementation(projects.core.datastore)
    implementation(projects.core.database)
    implementation(projects.core.ingest)
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
