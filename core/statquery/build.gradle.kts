plugins {
    alias(libs.plugins.gridiron.jvm.library)
}

dependencies {
    api(projects.core.model)
    testImplementation(libs.sqlite.jdbc)
}

tasks.test {
    // Contract tests against a real ETL-built database run when this points at
    // one; otherwise they are skipped. A relative path resolves from the
    // repository root (tests run with the module as working directory). The
    // database is declared as an input so rebuilding it re-runs the tests
    // instead of reporting them up to date.
    val statsDb = providers.environmentVariable("GRIDIRON_STATS_DB").orNull?.let { rootDir.resolve(it) }
    inputs.property("statsDbPath", statsDb?.path ?: "")
    if (statsDb != null) {
        environment("GRIDIRON_STATS_DB", statsDb.path)
        if (statsDb.isFile) inputs.file(statsDb).withPropertyName("statsDb")
    }
}
