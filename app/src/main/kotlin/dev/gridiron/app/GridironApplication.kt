package dev.gridiron.app

import android.app.Application
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.core.database.DeferredQueryExecutor
import dev.gridiron.core.database.SqliteQueryExecutor

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
}
