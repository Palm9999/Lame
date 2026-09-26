package dev.gridiron.core.data.live

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Bump when the layout below changes; an older file is then deleted and refetched. */
private const val LIVE_VERSION = 1L

private val LIVE_SCHEMA = listOf(
    "CREATE TABLE IF NOT EXISTS live_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID",
    """CREATE TABLE IF NOT EXISTS news_item (
        id TEXT PRIMARY KEY, published INTEGER NOT NULL, headline TEXT NOT NULL,
        description TEXT, url TEXT NOT NULL) WITHOUT ROWID""",
    """CREATE TABLE IF NOT EXISTS news_player (
        news_id TEXT NOT NULL, espn_id TEXT NOT NULL, name TEXT NOT NULL, player_id TEXT,
        PRIMARY KEY (news_id, espn_id)) WITHOUT ROWID""",
    "CREATE INDEX IF NOT EXISTS idx_news_player_player ON news_player (player_id)",
    """CREATE TABLE IF NOT EXISTS injury_status (
        espn_id TEXT PRIMARY KEY, player_id TEXT, name TEXT NOT NULL, team TEXT, position TEXT,
        status TEXT NOT NULL, abbr TEXT NOT NULL, short_comment TEXT, long_comment TEXT,
        updated_at INTEGER) WITHOUT ROWID""",
    "CREATE INDEX IF NOT EXISTS idx_injury_status_player ON injury_status (player_id)",
    """CREATE TABLE IF NOT EXISTS injury_note (
        espn_id TEXT NOT NULL, noted_at INTEGER NOT NULL, player_id TEXT, status TEXT NOT NULL,
        comment TEXT NOT NULL, PRIMARY KEY (espn_id, noted_at)) WITHOUT ROWID""",
    "CREATE INDEX IF NOT EXISTS idx_injury_note_player ON injury_note (player_id)",
)

/**
 * `live.db`: ESPN news and injuries, updated in place on every live refresh.
 *
 * Unlike `stats.db` it is written on the phone, and it is disposable: a file
 * that can't be read, or has another layout version, is deleted and started
 * fresh, because the next fetch refills it in seconds. One connection, used
 * from one thread, opened on first use.
 */
public class LiveDb(
    private val file: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : AutoCloseable {
    private var connection: SQLiteConnection? = null

    internal suspend fun <T> read(block: (SQLiteConnection) -> T): T =
        withContext(dispatcher) { block(connection()) }

    /** Runs [block] in one transaction; a throw rolls all of it back. */
    internal suspend fun write(block: (SQLiteConnection) -> Unit): Unit =
        withContext(dispatcher) {
            val c = connection()
            c.execSQL("BEGIN IMMEDIATE")
            try {
                block(c)
                c.execSQL("COMMIT")
            } catch (t: Throwable) {
                c.execSQL("ROLLBACK")
                throw t
            }
        }

    override fun close() {
        connection?.close()
        connection = null
    }

    private fun connection(): SQLiteConnection = connection ?: openOrRecreate().also { connection = it }

    private fun openOrRecreate(): SQLiteConnection {
        file.parentFile?.mkdirs()
        return try {
            open()
        } catch (_: RuntimeException) {
            // Corrupt, or another layout: live data is refetched in seconds, so start over.
            for (suffix in listOf("", "-journal", "-wal", "-shm")) File(file.path + suffix).delete()
            open()
        }
    }

    /** Opens [file] and brings its schema up; throws (a RuntimeException, on every platform) if it isn't a usable live.db. */
    private fun open(): SQLiteConnection {
        val c = BundledSQLiteDriver().open(file.path)
        try {
            val version = c.prepare("PRAGMA user_version").use { it.step(); it.getLong(0) }
            check(version == 0L || version == LIVE_VERSION) { "live.db layout $version" }
            LIVE_SCHEMA.forEach { c.execSQL(it) }
            c.execSQL("PRAGMA user_version = $LIVE_VERSION")
            return c
        } catch (e: RuntimeException) {
            c.close()
            throw e
        }
    }
}
