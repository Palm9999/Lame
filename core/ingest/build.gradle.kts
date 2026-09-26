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

// ./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025" -Pout=etl/build/stats.db
tasks.register<JavaExec>("buildStatsDb") {
    group = "gridiron"
    description = "Builds stats.db from nflverse with the on-device ingest code."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.gridiron.core.ingest.cli.IngestCliKt")
    val seasons = providers.gradleProperty("seasons").orElse("").get().split(" ").filter { it.isNotBlank() }
    val out = rootDir.resolve(providers.gradleProperty("out").orElse("etl/build/stats.db").get())
    args = listOf("--seasons") + seasons + listOf("--out", out.path)
}
