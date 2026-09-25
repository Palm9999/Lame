plugins {
    // Put the plugins on the build classpath for the convention plugins in
    // build-logic, which compile against them compileOnly.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
