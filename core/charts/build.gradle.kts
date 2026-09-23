plugins {
    alias(libs.plugins.gridiron.android.library)
    alias(libs.plugins.gridiron.android.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "dev.gridiron.core.charts"
}

// Canvas-drawn charts that take plain data. No charting library, and no
// dependency on the database or domain types.
dependencies {
    api(projects.core.designsystem)
    api(libs.kotlinx.collections.immutable)

    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
