plugins {
    alias(libs.plugins.gridiron.android.library)
    alias(libs.plugins.gridiron.android.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "dev.gridiron.feature.projections"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.projections)
    implementation(projects.core.data)
    implementation(projects.core.designsystem)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(projects.core.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
