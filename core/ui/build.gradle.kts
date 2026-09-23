plugins {
    alias(libs.plugins.gridiron.android.library)
    alias(libs.plugins.gridiron.android.compose)
}

android {
    namespace = "dev.gridiron.core.ui"
}

dependencies {
    api(projects.core.data)
    api(projects.core.designsystem)
}
