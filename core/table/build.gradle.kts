plugins {
    alias(libs.plugins.gridiron.android.library)
    alias(libs.plugins.gridiron.android.compose)
}

android {
    namespace = "dev.gridiron.core.table"
}

dependencies {
    api(libs.kotlinx.collections.immutable)
}
