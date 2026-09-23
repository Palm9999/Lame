package dev.gridiron.app

import android.app.Application
import dev.gridiron.core.data.CompareTrayRepository
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
 * The app's object graph, by hand. One screen doesn't justify Hilt yet; it
 * comes in once there are several features to wire.
 */
class GridironApplication : Application() {
    val repository: StatsRepository by lazy {
        StatsRepository(
            DeferredQueryExecutor {
                SqliteQueryExecutor.openReadOnly(StatsDbInstaller(this).install().path)
            },
        )
    }

    // Outlives every screen; the preferences file is written on it.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val prefs by lazy { UserPrefsStore.create(File(filesDir, "user_prefs.json"), appScope) }

    val scoring: ScoringRepository by lazy { ScoringRepository(prefs) }
    val tray: CompareTrayRepository by lazy { CompareTrayRepository(prefs) }
}
