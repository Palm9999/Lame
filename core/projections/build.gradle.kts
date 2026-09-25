plugins {
    alias(libs.plugins.gridiron.jvm.library)
}

dependencies {
    api(projects.core.model)
    api(projects.core.statquery)
}
