plugins {
    alias(libs.plugins.gridiron.jvm.library)
}

// Test fixtures shared across modules. Never a dependency of production code.
dependencies {
    api(projects.core.database)
    api(projects.core.datastore)
    implementation(libs.sqlite.jdbc)
    implementation(libs.kotlinx.coroutines.core)
}
