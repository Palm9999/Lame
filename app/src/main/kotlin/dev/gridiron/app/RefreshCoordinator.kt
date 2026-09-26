package dev.gridiron.app

import dev.gridiron.core.data.live.LiveResult
import dev.gridiron.core.database.ReopenableQueryExecutor
import dev.gridiron.core.ingest.IngestProgress
import dev.gridiron.core.ingest.IngestReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** What a refresh is doing: for the Load stats screen, the refresh bar and the result toast. */
sealed interface RefreshState {
    data object Idle : RefreshState

    data class Running(val text: String) : RefreshState

    /** [ok] is false when the stats build failed; live data may still have updated. */
    data class Finished(val message: String, val ok: Boolean) : RefreshState
}

/** The refresh as screens see it: [RefreshCoordinator] in the app, a fake in tests. */
interface Refresher {
    val state: StateFlow<RefreshState>

    /** Whether a stats database exists. False on a fresh install until the first build lands. */
    val hasStats: StateFlow<Boolean>

    /** Whether the stats came with an older app version rather than being built on this phone. */
    val legacyData: StateFlow<Boolean>

    /** Starts a refresh. False, doing nothing, if one is already running. */
    fun refresh(): Boolean

    /** Clears a [RefreshState.Finished] once its message has been shown. */
    fun acknowledge()
}

/** Plan 1's `IngestPipeline.build`, as a seam the tests can fake. */
fun interface StatsBuilder {
    suspend fun build(seasons: List<Int>, previous: File?, out: File, onProgress: (IngestProgress) -> Unit): IngestReport
}

/**
 * Builds stats on the phone and swaps them in without restarting the app,
 * then refreshes ESPN's injuries and news.
 *
 * Runs on [scope] (the application's), so it continues when the user leaves
 * the screen. If the process dies mid-build, `stats.db` is untouched: the new
 * database only ever exists as `stats.db.new` until one atomic rename.
 */
class RefreshCoordinator(
    dir: File,
    private val executor: ReopenableQueryExecutor,
    private val stats: StatsBuilder,
    private val seasons: suspend () -> List<Int>,
    private val scope: CoroutineScope,
    private val live: (suspend () -> LiveResult)? = null,
    private val millis: () -> Long = { System.nanoTime() / 1_000_000 },
) : Refresher {
    private val db = File(dir, DB_NAME)
    private val next = File(dir, "$DB_NAME.new")
    private val builtHere = File(dir, "$DB_NAME.built-here")

    private val _state = MutableStateFlow<RefreshState>(RefreshState.Idle)
    override val state: StateFlow<RefreshState> = _state.asStateFlow()

    private val _hasStats = MutableStateFlow(db.isFile)
    override val hasStats: StateFlow<Boolean> = _hasStats.asStateFlow()

    private val _legacy = MutableStateFlow(db.isFile && !builtHere.isFile)
    override val legacyData: StateFlow<Boolean> = _legacy.asStateFlow()

    private var job: Job? = null

    @Synchronized
    override fun refresh(): Boolean {
        if (job?.isActive == true) return false
        _state.value = RefreshState.Running(progressText(IngestProgress.Checking(null)))
        job = scope.launch { run() }
        return true
    }

    override fun acknowledge() {
        _state.update { if (it is RefreshState.Finished) RefreshState.Idle else it }
    }

    private suspend fun run() {
        val start = millis()
        var ok = true
        val statsLine = try {
            summary(buildAndSwap(), millis() - start)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ok = false
            describeFailure(e, kept = db.isFile)
        }
        val liveLine = live?.let { fetch ->
            _state.value = RefreshState.Running("Fetching injuries and news…")
            try {
                fetch().message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't update injuries or news: ${e.message}."
            }
        }
        _state.value = RefreshState.Finished(listOfNotNull(statsLine, liveLine).joinToString(" "), ok)
    }

    private suspend fun buildAndSwap(): IngestReport {
        val report = try {
            stats.build(seasons(), db.takeIf { it.isFile }, next) { _state.value = RefreshState.Running(progressText(it)) }
        } catch (e: Throwable) {
            next.delete()
            throw e
        }
        try {
            // The build writes without a journal or syncs: flush it to disk before it replaces
            // the only copy, so power loss after the rename can't leave a truncated stats.db.
            FileChannel.open(next.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
            executor.swap {
                Files.move(next.toPath(), db.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Throwable) {
            next.delete()
            throw e
        }
        builtHere.createNewFile()
        _hasStats.value = true
        _legacy.value = false
        return report
    }

    companion object {
        const val DB_NAME = "stats.db"
    }
}
