package dev.gridiron.core.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.gridiron.core.statquery.SqlQuery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class ReopenableQueryExecutorTest {
    @TempDir
    lateinit var dir: Path

    private val read = SqlQuery("SELECT v FROM t", emptyList())

    private fun database(name: String, value: String): Path {
        val path = dir.resolve(name)
        BundledSQLiteDriver().open(path.toString()).use { c ->
            c.execSQL("CREATE TABLE t (v TEXT)")
            c.execSQL("INSERT INTO t VALUES ('$value')")
        }
        return path
    }

    @Test
    fun `opens once, on first use`() = runTest {
        val path = database("stats.db", "a")
        var opens = 0
        val executor = ReopenableQueryExecutor {
            opens++
            SqliteQueryExecutor.openReadOnly(path.toString())
        }
        assertEquals(0, opens)
        repeat(3) { executor.query(read) { it.text(0) } }
        assertEquals(1, opens)
    }

    @Test
    fun `after a swap the next query reads the file moved into place`() = runTest {
        val live = database("stats.db", "old")
        val next = database("stats.db.new", "new")
        var opens = 0
        val executor = ReopenableQueryExecutor {
            opens++
            SqliteQueryExecutor.openReadOnly(live.toString())
        }
        assertEquals(listOf("old"), executor.query(read) { it.text(0) })

        executor.swap { Files.move(next, live, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }

        assertEquals(1L, executor.version.value)
        assertEquals(listOf("new"), executor.query(read) { it.text(0) })
        assertEquals(2, opens)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a swap waits for the query in flight, then closes`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val slow = object : QueryExecutor, AutoCloseable {
            override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> {
                started.complete(Unit)
                release.await()
                events += "query finished"
                return emptyList()
            }

            override fun close() {
                events += "closed"
            }
        }
        val executor = ReopenableQueryExecutor { slow }
        val query = launch { executor.query(read) { } }
        started.await()
        val swap = launch { executor.swap { events += "replaced" } }
        runCurrent()
        assertEquals(emptyList<String>(), events)

        release.complete(Unit)
        query.join()
        swap.join()

        assertEquals(listOf("query finished", "closed", "replaced"), events)
    }

    @Test
    fun `a failed swap keeps the version and the next query reopens the old file`() = runTest {
        val path = database("stats.db", "old")
        var opens = 0
        val executor = ReopenableQueryExecutor {
            opens++
            SqliteQueryExecutor.openReadOnly(path.toString())
        }
        executor.query(read) { it.text(0) }

        val failure = runCatching { executor.swap { error("No space left on device") } }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("No space left"))
        assertEquals(0L, executor.version.value)
        assertEquals(listOf("old"), executor.query(read) { it.text(0) })
        assertEquals(2, opens)
    }
}
