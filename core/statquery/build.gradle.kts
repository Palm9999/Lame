plugins {
    alias(libs.plugins.gridiron.jvm.library)
}

dependencies {
    api(projects.core.model)
    testImplementation(libs.sqlite.jdbc)
}
