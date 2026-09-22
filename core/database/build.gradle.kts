plugins {
    alias(libs.plugins.gridiron.jvm.library)
}

// Pure JVM on purpose. It depends on androidx.sqlite's multiplatform driver,
// which resolves to the Android build inside the app and to the desktop JVM
// build here, so the exact driver code the phone runs is tested on the JVM
// against the real database.
dependencies {
    api(projects.core.statquery)
    api(libs.androidx.sqlite)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(projects.core.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}
