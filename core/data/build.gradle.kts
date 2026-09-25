plugins {
    alias(libs.plugins.gridiron.jvm.library)
}

dependencies {
    api(projects.core.database)
    api(projects.core.datastore)
    api(projects.core.projections)
    api(libs.kotlinx.collections.immutable)

    testImplementation(projects.core.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sqlite.jdbc)
}
