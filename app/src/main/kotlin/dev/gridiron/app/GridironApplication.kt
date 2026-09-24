package dev.gridiron.app

import android.app.Application
import dev.gridiron.core.data.AccuracyRepository
import dev.gridiron.core.data.CompareRepository
import dev.gridiron.core.data.CompareTrayRepository
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.core.database.DeferredQueryExecutor
import dev.gridiron.core.database.SqliteQueryExecutor
import dev.gridiron.core.datastore.UserPrefsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * The app's object graph, by hand. Four repositories over one database
 * connection and one preferences file still don't justify Hilt.
 */
class GridironApplication : Application() {
    // Outlives every screen; the preferences file is written on it.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val executor by lazy {
        DeferredQueryExecutor { SqliteQueryExecutor.openReadOnly(StatsDbInstaller(this).install().path) }
    }
    private val prefs by lazy { UserPrefsStore.create(File(filesDir, "user_prefs.json"), appScope) }

    val deps: Deps by lazy {
        Deps(
            StatsRepository(executor),
            CompareRepository(executor),
            ScoringRepository(prefs),
            CompareTrayRepository(prefs),
            ProjectionsRepository(executor),
            AccuracyRepository(executor),
        )
    }
}
