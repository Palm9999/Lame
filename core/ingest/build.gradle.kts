plugins {
    alias(libs.plugins.gridiron.jvm.library)
}

// Pure JVM, like :core:database: the phone runs this exact code to build
// stats.db from nflverse, and CI runs it on the JVM against the Python ETL.
dependencies {
    implementation(projects.core.statquery)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
