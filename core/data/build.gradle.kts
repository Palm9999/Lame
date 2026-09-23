plugins {
    alias(libs.plugins.gridiron.jvm.library)
}

dependencies {
    api(projects.core.database)
    api(projects.core.datastore)
    api(libs.kotlinx.collections.immutable)

    testImplementation(projects.core.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}
