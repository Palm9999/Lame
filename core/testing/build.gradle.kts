plugins {
    alias(libs.plugins.gridiron.jvm.library)
}

// Test fixtures shared across modules. Never a dependency of production code.
dependencies {
    api(projects.core.database)
    implementation(libs.sqlite.jdbc)
}
