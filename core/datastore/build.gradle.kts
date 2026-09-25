plugins {
    alias(libs.plugins.gridiron.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// Pure JVM: DataStore's core is multiplatform, so the store is tested on the
// JVM with the same code the phone runs.
dependencies {
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.datastore.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
}
