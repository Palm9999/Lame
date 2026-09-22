plugins {
    // Puts the Kotlin Gradle plugin on the build classpath for the convention
    // plugins in build-logic, which compile against it compileOnly.
    alias(libs.plugins.kotlin.jvm) apply false
}
