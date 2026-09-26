package dev.gridiron.app

import dev.gridiron.core.data.live.LiveResult
import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.ReopenableQueryExecutor
import dev.gridiron.core.database.ResultRow
import dev.gridiron.core.ingest.IngestProgress
import dev.gridiron.core.ingest.IngestReport
import dev.gridiron.core.ingest.ValidationException
import dev.gridiron.core.statquery.SqlQuery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.UnknownHostException

/** The coordinator with a fake build and a fake database connection: only files and flows are real. */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshCoordinatorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val db get() = File(tmp.root, "stats.db")
    private val next get() = File(tmp.root, "stats.db.new")
    private var closes = 0
    private val executor = ReopenableQueryExecutor {
        object : QueryExecutor, AutoCloseable {
            override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> = emptyList()

            override fun close() {
                closes++
            }
        }
    }
    private val report = IngestReport(listOf(2026), listOf(2025), emptyMap(), emptyList(), 10L)

    /** The `previous` file each build was given. */
    private val builds = mutableListOf<File?>()

    private fun TestScope.coordinator(
        live: (suspend () -> LiveResult)? = null,
        build: suspend (out: File, onProgress: (IngestProgress) -> Unit) -> IngestReport = { out, _ ->
            out.writeText("new")
            report
        },
    ) = RefreshCoordinator(
        dir = tmp.root,
        executor = executor,
        stats = StatsBuilder { _, previous, out, onProgress ->
            builds += previous
            build(out, onProgress)
        },
        seasons = { listOf(2025, 2026) },
        scope = this,
        live = live,
        millis = { 0L },
    )

    @Test
    fun aSuccessfulRefreshSwapsInTheNewDatabase() = runTest {
        db.writeText("old")
        val refresher = coordinator()
        executor.query(SqlQuery("SELECT 1", emptyList())) { }
        assertTrue(refresher.legacyData.value)

        assertTrue(refresher.refresh())
        advanceUntilIdle()

        assertEquals("new", db.readText())
        assertFalse(next.exists())
        assertEquals(listOf<File?>(db), builds)
        assertEquals(1L, executor.version.value)
        assertEquals(1, closes)
        assertTrue(refresher.hasStats.value)
        assertFalse(refresher.legacyData.value)
        assertEquals(RefreshState.Finished("Stats updated for 2025, 2026 in 0 s.", ok = true), refresher.state.value)
    }

    @Test
    fun aFailedBuildKeepsTheCurrentDatabase() = runTest {
        db.writeText("old")
        val refresher = coordinator(build = { out, _ ->
            out.writeText("half")
            throw ValidationException(listOf("range: target_share 1.4"))
        })

        refresher.refresh()
        advanceUntilIdle()

        assertEquals("old", db.readText())
        assertFalse(next.exists())
        assertEquals(0L, executor.version.value)
        assertEquals(
            RefreshState.Finished("The new stats failed a check (range: target_share 1.4). Your current stats are kept.", ok = false),
            refresher.state.value,
        )
    }

    @Test
    fun aFailedMoveLeavesNoNewFileBehind() = runTest {
        // A non-empty directory where stats.db should go: the rename must fail.
        db.mkdirs()
        File(db, "blocker").writeText("x")
        val refresher = coordinator()

        refresher.refresh()
        advanceUntilIdle()

        assertFalse(next.exists())
        assertEquals(0L, executor.version.value)
        assertFalse((refresher.state.value as RefreshState.Finished).ok)
    }

    @Test
    fun aSecondTapWhileRunningIsIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        val refresher = coordinator(build = { out, _ ->
            gate.await()
            out.writeText("new")
            report
        })

        assertTrue(refresher.refresh())
        runCurrent()
        assertFalse(refresher.refresh())
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, builds.size)
        assertTrue("a new refresh may start once the last one finished", refresher.refresh())
        advanceUntilIdle()
        assertEquals(2, builds.size)
    }

    @Test
    fun progressShowsWhatIsDownloading() = runTest {
        val gate = CompletableDeferred<Unit>()
        val refresher = coordinator(build = { out, onProgress ->
            onProgress(IngestProgress.Downloading(2026, "play-by-play", 12_400_000, 19_000_000))
            gate.await()
            out.writeText("new")
            report
        })

        refresher.refresh()
        runCurrent()
        assertEquals(RefreshState.Running("Downloading 2026 play-by-play 12/19 MB"), refresher.state.value)

        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun liveDataStillRefreshesWhenStatsFail() = runTest {
        db.writeText("old")
        var liveCalls = 0
        val refresher = coordinator(
            live = {
                liveCalls++
                LiveResult(null, null)
            },
            build = { _, _ -> throw UnknownHostException("github.com") },
        )

        refresher.refresh()
        advanceUntilIdle()

        assertEquals(1, liveCalls)
        assertEquals(
            RefreshState.Finished("No connection. Your current stats are kept. Injuries and news updated.", ok = false),
            refresher.state.value,
        )
    }

    @Test
    fun aFreshInstallBuildsWithNoPreviousDatabase() = runTest {
        val refresher = coordinator()
        assertFalse(refresher.hasStats.value)
        assertFalse(refresher.legacyData.value)

        refresher.refresh()
        advanceUntilIdle()

        assertEquals(listOf<File?>(null), builds)
        assertTrue(refresher.hasStats.value)
        assertTrue(File(tmp.root, "stats.db.built-here").exists())
    }

    @Test
    fun aFreshInstallFailureDoesNotClaimStatsWereKept() = runTest {
        val refresher = coordinator(build = { _, _ -> throw UnknownHostException("github.com") })

        refresher.refresh()
        advanceUntilIdle()

        assertEquals(RefreshState.Finished("No connection.", ok = false), refresher.state.value)
        assertFalse(refresher.hasStats.value)
    }

    @Test
    fun acknowledgeClearsAFinishedResult() = runTest {
        val refresher = coordinator()
        refresher.refresh()
        advanceUntilIdle()

        refresher.acknowledge()

        assertEquals(RefreshState.Idle, refresher.state.value)
    }
}
