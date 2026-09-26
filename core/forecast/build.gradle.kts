plugins {
    alias(libs.plugins.gridiron.jvm.library)
}

// Pure JVM, like :core:ingest: the phone runs this exact code at the end of a
// refresh, and CI runs it on the JVM when it builds the test database.
dependencies {
    api(libs.androidx.sqlite)

    testImplementation(libs.androidx.sqlite.bundled)
}
