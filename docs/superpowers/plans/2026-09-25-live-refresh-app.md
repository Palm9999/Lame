# Live Refresh App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The app builds its own `stats.db` on the phone with Plan 1's `IngestPipeline`, pulls ESPN injuries and news into a separate `live.db`, and shows them on new News, Player and Injury report screens, with no database shipped in the APK or published by the repo.

**Architecture:**
- A `ReopenableQueryExecutor` in `:core:database` closes and reopens the stats connection around a file swap and bumps a version flow. The Grid and Compare view models reload on that flow instead of the app restarting.
- A `RefreshCoordinator` in `:app` runs the pipeline into `stats.db.new` on an application-scope coroutine, swaps it into place, then refreshes live data. It publishes progress text as a `StateFlow`.
- A new `dev.gridiron.core.data.live` package holds the ESPN parser, a small HTTP client, the writable `live.db` store and `LiveRepository`. ESPN athlete ids map to app player ids through Plan 1's `player_xref` table.
- A seasons checklist lives in `UserPrefs` (`:core:datastore`) behind a `SettingsRepository`.

**Tech Stack:** Kotlin 2.4, Jetpack Compose (BOM 2026.09.00), Navigation 3, androidx.sqlite 2.7.1 bundled driver, kotlinx-coroutines 1.11, kotlinx-serialization-json 1.11 (`JsonElement` API only), JUnit Jupiter 6 (JVM modules), JUnit 4 + Robolectric 4.17 (Android modules).

**Spec:** `docs/superpowers/specs/2026-09-25-live-data-refresh-design.md`

**Depends on Plan 1** (`docs/superpowers/plans/2026-09-25-kotlin-ingest.md`) being fully executed. This plan uses these exact names from it:
- `dev.gridiron.core.ingest`: `IngestPipeline(fetcher, workDir, playersFile, now)` with `suspend fun build(seasons, previous, out, onProgress): IngestReport`; `IngestProgress` (`Checking(season: Int?)`, `Downloading(season: Int?, what: String, bytes: Long, total: Long)`, `Crunching(season: Int)`, `Validating`); `IngestReport(built, reused, skipped: Map<Int, String>, warnings, facts)`; `ValidationException(problems: List<String>)`; `HttpFetcher()`; `currentSeason(today: LocalDate = LocalDate.now()): Int`.
- `dev.gridiron.core.ingest.csv.MissingColumnsException(source, missing)`, whose message starts `nflverse changed column(s)`.
- The `player_xref (espn_id TEXT PRIMARY KEY, player_id TEXT NOT NULL, full_name TEXT NOT NULL, position TEXT, team TEXT)` table in every Kotlin-built `stats.db`.
- Plan 1 Task 6's app wiring: `Deps.benchmark`, the "Time a stats build" menu item, and `GridironApplication.benchmark()`. This plan removes them.

## Global Constraints

**Modules and tests**
- JVM modules (`:core:database`, `:core:datastore`, `:core:data`, `:core:ingest`) use the `gridiron.jvm.library` convention: **explicit API mode** (every declaration needs a visibility modifier; mark module-private things `internal`), `allWarningsAsErrors = true`, and JUnit Jupiter tests (`org.junit.jupiter.api.*`).
- Android modules (`:app`, `:feature:*`) test with JUnit 4 (`org.junit.Test`) and Robolectric `@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")`.
- **Robolectric can't load the bundled SQLite driver** (the app's classpath carries its Android `.so`, not the desktop build). Android-module tests never open `LiveDb` or `SqliteQueryExecutor`. They use `JdbcQueryExecutor` over `GRIDIRON_STATS_DB`, fakes, or stateless screens fed plain data.
- Never use `java.net.URL(String)`. It is deprecated, which is a warning, which is an error here. Use `URI(...).toURL()`.

**Files on the phone** (all under `Context.noBackupFilesDir`):
- `stats.db`: the stats database the app reads. Existing installs keep theirs until the first refresh.
- `stats.db.new`: the build in progress. It never survives a failed build.
- `stats.db.built-here`: an empty marker file, written after the first successful on-phone build.
- `players.csv.gz`: Plan 1's kept player list.
- `ingest-work/`: Plan 1's work directory.
- `live.db`: ESPN news and injuries.

**ESPN endpoints** (exact, no key):
- `https://site.api.espn.com/apis/site/v2/sports/football/nfl/news?limit=50`
- `https://site.api.espn.com/apis/site/v2/sports/football/nfl/injuries`

**Live data rules**
- Rows older than **30 days** are pruned.
- The News screen, Player page and live Injury report auto-fetch when live data is more than **15 minutes** old.
- An injury badge shows ESPN's `type.abbreviation` (Q, D, O, IR, …) for any status other than Active (`A`).

**Seasons**
- The choices run from **2012** through `currentSeason()`.
- The default is `currentSeason()` plus the two seasons before it.
- At least one season must stay selected.

**Copy**
- Progress: `Checking for new stats…`, `Checking 2025…`, `Downloading 2026 play-by-play 12/19 MB`, `Crunching 2026…`, `Checking the new stats…`, `Fetching injuries and news…`.
- Every stats failure message ends with `Your current stats are kept.` when a database already exists.

**Nothing is read from this repository's releases.** After this plan, no code, workflow or doc refers to the `data` release.

## Review Focus

1. **A refresh that fails at any point** leaves the current `stats.db` in place and readable, with no `stats.db.new` behind. Failure points include no network mid-download, a failed validation check, a full disk, and a failed file move. Test: Task 7 (build failure) and Task 1 (failed swap).
2. **A Grid or Compare query running at the moment of the swap** finishes on the old file. The next query reads the new file, and nothing hits a closed connection. Test: Task 1.
3. **Unchecking the only checked season** is refused with a message, so a build never runs with zero seasons. Test: Task 3 (repository) and Task 8 (screen).
4. **ESPN down, or answering in a new shape,** leaves the last injuries and news on screen with their "as of" time, and never fails a stats refresh. Test: Task 6 and Task 7.
5. **ESPN timestamps without seconds** (`2026-09-25T21:57Z`, the form of every injury `date`) parse. `Instant.parse` rejects them, which would drop all 800 entries. Test: Task 4.

Also pinned by tests:
- News fetched before the first stats load links to players once `player_xref` exists (Task 6).
- A corrupt `live.db` is recreated (Task 5).
- A selection that included the current season keeps following it into the next season (Task 3).

## File Map

| File | Responsibility | Task |
|---|---|---|
| `core/database/.../ReopenableQueryExecutor.kt` | Lazy open, close/reopen around a swap, `version` flow | 1 |
| `core/data/.../StatsRepository.kt` | Carries `dataVersion` to the view models | 2 |
| `feature/players/.../GridViewModel.kt` | Reloads the catalog and page on each data version; injury badges | 2, 10 |
| `feature/compare/.../CompareViewModel.kt` | Reloads on each data version | 2 |
| `core/datastore/.../UserPrefs.kt`, `UserPrefsJson.kt` | Stores the seasons choice | 3 |
| `core/data/.../SettingsRepository.kt` | Resolves and edits the seasons choice | 3 |
| `core/data/.../live/Espn.kt` | ESPN JSON → articles and injuries | 4 |
| `core/data/.../live/HttpGet.kt` | GET a URL as text (gzip-aware) | 4 |
| `core/data/.../live/LiveDb.kt` | The writable `live.db` connection, schema, transactions | 5 |
| `core/data/.../live/LiveStore.kt` | The SQL that writes and reads `live.db` | 5 |
| `core/data/.../PlayerDirectory.kt` | ESPN id → player id, and player headers incl. `player_xref` | 6 |
| `core/data/.../live/LiveRepository.kt` | Fetch, link, store, prune; reads for screens | 6 |
| `app/.../RefreshCoordinator.kt` | Build → swap → live, with progress state | 7 |
| `app/.../RefreshText.kt` | Progress, summary and failure wording | 7 |
| `app/.../GridironApplication.kt`, `GridironNavHost.kt`, `NavKeys.kt` | Wiring, Load stats gate, legacy prompt, refresh bar, menu | 8, 9, 10 |
| `app/.../LoadStatsScreen.kt`, `SettingsScreen.kt` | First-run screen; seasons checklist | 8 |
| `app/.../LiveFormat.kt` | Time formatting; injury report grouping | 9 |
| `app/.../NewsScreen.kt`, `PlayerScreen.kt`, `TeamScreens.kt` | News, Player page, live Injury report | 9 |
| `feature/players/.../GridScreen.kt` | Injury badge next to the name | 10 |
| `core/ingest/.../Seasons.kt` | `currentSeason()` (moved from `Benchmark.kt`) | 11 |
| `.github/workflows/*`, `CLAUDE.md`, `README.md` | Removals and docs | 11 |

---

### Task 1: A stats executor that reopens after a swap

`DeferredQueryExecutor` opens once and never lets go of the file. The phone now replaces `stats.db` while the app runs, so the executor must close the old connection, let the caller move the new file into place, and reopen on the next query. One mutex covers every query and the swap. A swap therefore waits for the query in flight, and no query ever sees a closed connection (Review Focus 2). Queries were already serial (one connection on a single-thread dispatcher), so the lock costs nothing.

**Files:**
- Create: `core/database/src/main/kotlin/dev/gridiron/core/database/ReopenableQueryExecutor.kt`
- Delete: `core/database/src/main/kotlin/dev/gridiron/core/database/DeferredQueryExecutor.kt`
- Test: `core/database/src/test/kotlin/dev/gridiron/core/database/ReopenableQueryExecutorTest.kt`
- Modify: `core/database/src/test/kotlin/dev/gridiron/core/database/SqliteQueryExecutorTest.kt` (remove the deferred-executor test)
- Modify: `app/src/main/kotlin/dev/gridiron/app/GridironApplication.kt`

**Interfaces:**
- Consumes: `QueryExecutor`, `ResultRow`, `SqliteQueryExecutor` (existing).
- Produces (package `dev.gridiron.core.database`):
  - `public class ReopenableQueryExecutor(open: suspend () -> QueryExecutor) : QueryExecutor`
  - `public val version: StateFlow<Long>`, which starts at 0 and gains 1 per successful swap
  - `public suspend fun swap(replace: suspend () -> Unit)`

- [ ] **Step 1: Write the failing tests**

```kotlin
// core/database/src/test/kotlin/dev/gridiron/core/database/ReopenableQueryExecutorTest.kt
package dev.gridiron.core.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.gridiron.core.statquery.SqlQuery
import kotlinx.coroutines.CompletableDeferred
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:database:test --tests "*ReopenableQueryExecutorTest*"`
Expected: FAIL to compile: `Unresolved reference 'ReopenableQueryExecutor'`.

- [ ] **Step 3: Implement the executor and delete the old one**

```kotlin
// core/database/src/main/kotlin/dev/gridiron/core/database/ReopenableQueryExecutor.kt
package dev.gridiron.core.database

import dev.gridiron.core.statquery.SqlQuery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The stats database's executor. It opens on first use, and it can be closed
 * and reopened around a file swap: the phone builds a new `stats.db` beside
 * the live one and moves it into place while the app runs.
 *
 * One lock covers every query and the swap, so a swap waits for the query in
 * flight and no query ever meets a closed connection. Queries were already
 * serial (one connection, one thread), so the lock costs nothing.
 */
public class ReopenableQueryExecutor(private val open: suspend () -> QueryExecutor) : QueryExecutor {
    private val mutex = Mutex()
    private var delegate: QueryExecutor? = null
    private val _version = MutableStateFlow(0L)

    /** Bumped after every successful [swap]; screens reload when it changes. */
    public val version: StateFlow<Long> = _version.asStateFlow()

    override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> =
        mutex.withLock {
            val db = delegate ?: open().also { delegate = it }
            db.query(query, map)
        }

    /**
     * Closes the open database, runs [replace] (which moves the new file into
     * place) and bumps [version]. The next query opens whatever file is then in
     * place. If [replace] throws, [version] stays put and the next query
     * reopens the old file.
     */
    public suspend fun swap(replace: suspend () -> Unit) {
        mutex.withLock {
            (delegate as? AutoCloseable)?.close()
            delegate = null
            replace()
            _version.value += 1
        }
    }
}
```

Delete `core/database/src/main/kotlin/dev/gridiron/core/database/DeferredQueryExecutor.kt`.

In `core/database/src/test/kotlin/dev/gridiron/core/database/SqliteQueryExecutorTest.kt`, delete the whole test function `` `the deferred executor opens once, on first use` `` (the `@Test` annotation and its body). The new test file covers it.

- [ ] **Step 4: Point the app at the new executor**

In `app/src/main/kotlin/dev/gridiron/app/GridironApplication.kt`:
- Replace the import `dev.gridiron.core.database.DeferredQueryExecutor` with `dev.gridiron.core.database.ReopenableQueryExecutor`.
- Replace `DeferredQueryExecutor {` with `ReopenableQueryExecutor {`. The lambda body stays the same until Task 8.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:database:test :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL, and all four `ReopenableQueryExecutorTest` tests pass.

- [ ] **Step 6: Commit**

```bash
git add core/database app/src/main/kotlin/dev/gridiron/app/GridironApplication.kt
git commit -m "database: an executor that closes and reopens around a file swap"
```

---

### Task 2: Screens reload on a new data version

Today a refresh restarts the whole app so every screen reopens on the new file. Instead, `StatsRepository` now carries a `dataVersion` flow, and the Grid and Compare view models reload their catalog and re-run their queries on each value.

**Grid**
- The user's request (season, weeks, pack, sort, filters) carries over.
- If the season still exists, it is kept. Default weeks follow the new data: "Wk 1–3" becomes "Wk 1–4" after week 4 lands. A custom week range is kept as is.
- If the season was dropped in Settings, the Grid moves to the latest season.

**Compare** restarts its pipeline, because the tray and profile live in preferences and survive.

**Files:**
- Modify: `core/data/src/main/kotlin/dev/gridiron/core/data/StatsRepository.kt`
- Modify: `feature/players/src/main/kotlin/dev/gridiron/feature/players/GridViewModel.kt`
- Modify: `feature/compare/src/main/kotlin/dev/gridiron/feature/compare/CompareViewModel.kt`
- Test: `feature/players/src/test/kotlin/dev/gridiron/feature/players/GridViewModelTest.kt`
- Test: `feature/compare/src/test/kotlin/dev/gridiron/feature/compare/CompareViewModelTest.kt`

**Interfaces:**
- Consumes: nothing new. The app passes Task 1's `ReopenableQueryExecutor.version` in Task 8.
- Produces:
  - `StatsRepository(executor: QueryExecutor, locale: Locale = Locale.getDefault(), dataVersion: Flow<Long> = flowOf(0L))`, with `public val dataVersion: Flow<Long>`
  - `GridViewModel.rebase(r: GridRequest, catalog: Catalog): GridRequest` (companion, `internal`)

- [ ] **Step 1: Write the failing tests**

Append to `GridViewModelTest` (inside the class). Add these imports: `dev.gridiron.core.data.Catalog` (already there), `dev.gridiron.core.data.GridRequest`, `dev.gridiron.core.data.SeasonInfo`, `dev.gridiron.core.statquery.CatalogQueries`, `kotlinx.collections.immutable.persistentListOf`, `kotlinx.collections.immutable.persistentMapOf` and `kotlinx.coroutines.flow.MutableStateFlow`.

```kotlin
    private val catalogQueries = setOf(CatalogQueries.seasons, CatalogQueries.metrics, CatalogQueries.teams)

    @Test
    fun `a new data version reloads the catalog and re-runs the page, keeping the user's choices`() = runTest(dispatcher) {
        val version = MutableStateFlow(0L)
        val log = mutableListOf<SqlQuery>()
        repo = StatsRepository(trackingExecutor { log += it }, Locale.US, dataVersion = version)
        val vm = viewModel()
        ready(vm)
        vm.onEvent(GridEvent.PackSelected(StatPack.RUSHING))
        val chosen = ready(vm).request
        val before = log.size

        version.value = 1
        val after = ready(vm)

        val since = log.drop(before)
        assertEquals(1, since.count { it == CatalogQueries.seasons })
        assertTrue("expected the page to re-run", since.any { it !in catalogQueries })
        assertEquals(chosen, after.request)
    }

    @Test
    fun `rebase keeps the season and lets default weeks follow new data`() {
        val old = SeasonInfo(2026, 2)
        val catalog = Catalog(persistentListOf(SeasonInfo(2025, 22), SeasonInfo(2026, 3)), persistentMapOf())
        val r = GridRequest(old, old.defaultWeeks, StatPack.OPPORTUNITY)

        assertEquals(WeekRange(1, 3), GridViewModel.rebase(r, catalog).weeks)
        val custom = r.copy(weeks = WeekRange(2, 2))
        assertEquals(WeekRange(2, 2), GridViewModel.rebase(custom, catalog).weeks)
        assertEquals(SeasonInfo(2026, 3), GridViewModel.rebase(custom, catalog).season)
    }

    @Test
    fun `rebase moves to the latest season when the old one was dropped`() {
        val gone = SeasonInfo(2023, 22)
        val catalog = Catalog(persistentListOf(SeasonInfo(2025, 22), SeasonInfo(2026, 3)), persistentMapOf())
        val rebased = GridViewModel.rebase(GridRequest(gone, gone.defaultWeeks, StatPack.OPPORTUNITY), catalog)

        assertEquals(SeasonInfo(2026, 3), rebased.season)
        assertEquals(WeekRange(1, 3), rebased.weeks)
    }
```

Append to `CompareViewModelTest` (inside the class). Add these imports: `dev.gridiron.core.database.QueryExecutor`, `dev.gridiron.core.database.ResultRow`, `dev.gridiron.core.statquery.CatalogQueries`, `dev.gridiron.core.statquery.SqlQuery`, `kotlinx.coroutines.flow.MutableStateFlow` and `org.junit.Assert.assertTrue`.

```kotlin
    @Test
    fun `a new data version reloads the catalog and keeps the page`() = runTest(dispatcher) {
        val slots = twoReceivers()
        val version = MutableStateFlow(0L)
        var catalogs = 0
        val counting = object : QueryExecutor {
            override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> {
                if (query == CatalogQueries.seasons) catalogs++
                return executor.query(query, map)
            }
        }
        stats = StatsRepository(counting, Locale.US, dataVersion = version)
        val vm = viewModel(slots)
        advanceUntilIdle()
        assertTrue(vm.state.value is CompareUiState.Ready)

        version.value = 1
        advanceUntilIdle()

        assertEquals(2, catalogs)
        assertTrue(vm.state.value is CompareUiState.Ready)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :feature:players:testDebugUnitTest --tests "*GridViewModelTest*" :feature:compare:testDebugUnitTest --tests "*CompareViewModelTest*"`
Expected: FAIL to compile: `No parameter with name 'dataVersion'` and `Unresolved reference 'rebase'`.

- [ ] **Step 3: Add `dataVersion` to `StatsRepository`**

In `core/data/src/main/kotlin/dev/gridiron/core/data/StatsRepository.kt`, add the imports `kotlinx.coroutines.flow.Flow` and `kotlinx.coroutines.flow.flowOf`. Then replace the constructor:

```kotlin
public class StatsRepository(
    private val executor: QueryExecutor,
    locale: Locale = Locale.getDefault(),
    /** Emits whenever the database behind [executor] is replaced; screens reload on each value. */
    public val dataVersion: Flow<Long> = flowOf(0L),
) {
```

- [ ] **Step 4: Reload the Grid on each version**

In `feature/players/src/main/kotlin/dev/gridiron/feature/players/GridViewModel.kt`:

1. Add these imports: `kotlinx.coroutines.flow.collectLatest` and `kotlinx.coroutines.flow.filterIsInstance`.

2. Tag each loaded catalog with its version, so a reload of identical data still re-runs the page. Replace:

```kotlin
        data class Loaded(val catalog: Catalog) : CatalogLoad
```

with:

```kotlin
        data class Loaded(val catalog: Catalog, val version: Long) : CatalogLoad
```

3. In `init`, replace the first `viewModelScope.launch { ... }` block (the one that calls `repository.catalog()`) with:

```kotlin
        viewModelScope.launch {
            repository.dataVersion.collectLatest { version ->
                val c = try {
                    repository.catalog()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    catalogLoad.value = CatalogLoad.Failed("Couldn't open the stats database: ${e.message}")
                    return@collectLatest
                }
                catalogLoad.value = CatalogLoad.Loaded(c, version)
                val current = request.value
                request.value = if (current != null) {
                    rebase(current, c)
                } else {
                    GridRequest(c.latest, c.latest.defaultWeeks, StatPack.OPPORTUNITY, scoring = scoring.active.first())
                }
            }
        }
```

4. Replace the second `viewModelScope.launch { ... }` block (the one with `request.filterNotNull().debounce(debounceMillis)`) with:

```kotlin
        viewModelScope.launch {
            // Pairs each request with the catalog it runs against, so a new
            // data version re-runs the page even when the request is unchanged.
            combine(request.filterNotNull(), catalogLoad.filterIsInstance<CatalogLoad.Loaded>(), ::Pair)
                .debounce(debounceMillis)
                .mapLatest { (r, load) ->
                    try {
                        lastPage.value = repository.grid(r, load.catalog)
                        pageError.value = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        pageError.value = r to (e.message ?: e::class.simpleName.orEmpty())
                    }
                }
                .collect()
        }
```

5. In the `companion object`, add after `reduce`:

```kotlin
        /**
         * Carries [r] over to a reloaded [catalog]. The same season is kept if
         * it still exists, and default weeks follow its new last week. If the
         * season is gone (deselected in Settings), the latest season is used.
         */
        internal fun rebase(r: GridRequest, catalog: Catalog): GridRequest {
            val season = catalog.seasons.firstOrNull { it.season == r.season.season }
                ?: return r.copy(season = catalog.latest, weeks = catalog.latest.defaultWeeks)
            val weeks = if (r.weeks == r.season.defaultWeeks) season.defaultWeeks else r.weeks
            return r.copy(season = season, weeks = weeks)
        }
```

- [ ] **Step 5: Reload Compare on each version**

In `feature/compare/src/main/kotlin/dev/gridiron/feature/compare/CompareViewModel.kt`, add the import `kotlinx.coroutines.flow.collectLatest`. In `init`, replace the second `viewModelScope.launch { ... }` block (the one that starts `val catalog = try { stats.catalog()`) with:

```kotlin
        viewModelScope.launch {
            // Each data version restarts the whole pipeline on the new catalog;
            // the tray and profile live in preferences, so they carry over.
            stats.dataVersion.collectLatest {
                val catalog = try {
                    stats.catalog()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    catalogLoad = CatalogLoad.Failed("Couldn't open the stats database: ${e.message}")
                    recompute()
                    return@collectLatest
                }
                catalogLoad = CatalogLoad.Loaded(catalog)
                recompute()

                combine(tray.slots, scoring.active, perGame, ::Triple)
                    .mapLatest { (slots, profile, pg) ->
                        if (slots.size < 2) {
                            needsPlayers = true
                            page = null
                            firstLoadError = null
                            recompute()
                            return@mapLatest
                        }
                        needsPlayers = false
                        computing = true
                        recompute()
                        try {
                            page = compare.compare(CompareRequest(slots, profile, pg), catalog)
                            firstLoadError = null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val msg = e.message ?: e::class.simpleName.orEmpty()
                            if (page == null) firstLoadError = msg else message = "Couldn't refresh: $msg"
                        } finally {
                            computing = false
                            recompute()
                        }
                    }
                    .collect()
            }
        }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test :feature:players:testDebugUnitTest :feature:compare:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. The existing Grid and Compare tests still pass, because the default `flowOf(0L)` emits once, exactly like the old single load.

- [ ] **Step 7: Commit**

```bash
git add core/data/src/main/kotlin/dev/gridiron/core/data/StatsRepository.kt feature/players feature/compare
git commit -m "grid, compare: reload on a new data version instead of an app restart"
```

---

### Task 3: A seasons choice in preferences

The user picks which seasons the phone builds. The choice is stored as the list of seasons plus the season that was current when it was saved (`chosenIn`), for these reasons:
- A user who never opens Settings has no stored choice. The default (the current season and the two before it) rolls forward on its own each September.
- A stored choice that included the then-current season keeps following the current season. Someone who picked 2024–2026 in 2026 gets 2027 added when it starts.
- A stored choice that didn't include the current season (only past seasons) is kept exactly.

Unchecking the only checked season is refused (Review Focus 3).

**Files:**
- Modify: `core/datastore/src/main/kotlin/dev/gridiron/core/datastore/UserPrefs.kt`
- Modify: `core/datastore/src/main/kotlin/dev/gridiron/core/datastore/UserPrefsJson.kt`
- Test: `core/datastore/src/test/kotlin/dev/gridiron/core/datastore/UserPrefsStoreTest.kt`
- Create: `core/data/src/main/kotlin/dev/gridiron/core/data/SettingsRepository.kt`
- Test: `core/data/src/test/kotlin/dev/gridiron/core/data/SettingsRepositoryTest.kt`

**Interfaces:**
- Consumes: `PrefsSource`, `UserPrefs` (existing); `FakePrefsSource` (tests).
- Produces:
  - `public data class SeasonChoice(val seasons: List<Int>, val chosenIn: Int)` in `dev.gridiron.core.datastore`
  - `UserPrefs.seasons: SeasonChoice? = null`, a new last constructor parameter
  - `public class SettingsRepository(prefs: PrefsSource, currentSeason: () -> Int)` in `dev.gridiron.core.data`, with:
    - `public val choices: List<Int>`: `FIRST_SEASON..currentSeason()`
    - `public val seasons: Flow<List<Int>>`: resolved, sorted, never empty
    - `public suspend fun setSelected(season: Int, selected: Boolean): Boolean`: false (and no change) when it would leave none selected
    - `companion`: `FIRST_SEASON = 2012`, `defaults(current: Int): List<Int>`

- [ ] **Step 1: Write the failing tests**

Append to `UserPrefsStoreTest` (inside the class):

```kotlin
    @Test
    fun `the seasons choice survives a reopen, and an older file has none`() {
        assertEquals(null, withStore { it.prefs.first() }.seasons)
        withStore { store -> store.update { it.copy(seasons = SeasonChoice(listOf(2026, 2024), 2026)) } }
        assertEquals(SeasonChoice(listOf(2024, 2026), 2026), withStore { it.prefs.first() }.seasons)
    }
```

```kotlin
// core/data/src/test/kotlin/dev/gridiron/core/data/SettingsRepositoryTest.kt
package dev.gridiron.core.data

import dev.gridiron.core.datastore.SeasonChoice
import dev.gridiron.core.datastore.UserPrefs
import dev.gridiron.core.testing.FakePrefsSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsRepositoryTest {
    private var current = 2026

    private fun repo(choice: SeasonChoice? = null): Pair<SettingsRepository, FakePrefsSource> {
        val prefs = FakePrefsSource(UserPrefs.DEFAULT.copy(seasons = choice))
        return SettingsRepository(prefs) { current } to prefs
    }

    @Test
    fun `the default is the current season and the two before it, and rolls forward`() = runTest {
        val (settings, _) = repo()
        assertEquals(listOf(2024, 2025, 2026), settings.seasons.first())
        current = 2027
        assertEquals(listOf(2025, 2026, 2027), settings.seasons.first())
    }

    @Test
    fun `choices run from 2012 through the current season`() {
        val (settings, _) = repo()
        assertEquals(2012, settings.choices.first())
        assertEquals(2026, settings.choices.last())
        assertEquals(15, settings.choices.size)
    }

    @Test
    fun `checking and unchecking saves the choice`() = runTest {
        val (settings, prefs) = repo()
        assertTrue(settings.setSelected(2012, true))
        assertTrue(settings.setSelected(2025, false))
        assertEquals(listOf(2012, 2024, 2026), settings.seasons.first())
        assertEquals(SeasonChoice(listOf(2012, 2024, 2026), 2026), prefs.current.seasons)
    }

    @Test
    fun `the last season can't be unchecked`() = runTest {
        val (settings, prefs) = repo(SeasonChoice(listOf(2026), 2026))
        assertFalse(settings.setSelected(2026, false))
        assertEquals(listOf(2026), settings.seasons.first())
        assertEquals(SeasonChoice(listOf(2026), 2026), prefs.current.seasons)
    }

    @Test
    fun `a choice that included the current season follows it into the next`() = runTest {
        val (settings, _) = repo(SeasonChoice(listOf(2024, 2026), 2026))
        current = 2028
        assertEquals(listOf(2024, 2026, 2027, 2028), settings.seasons.first())
    }

    @Test
    fun `a choice of past seasons only stays exactly as chosen`() = runTest {
        val (settings, _) = repo(SeasonChoice(listOf(2015, 2016), 2026))
        current = 2027
        assertEquals(listOf(2015, 2016), settings.seasons.first())
    }

    @Test
    fun `seasons outside the range are dropped, and an empty result falls back to the default`() = runTest {
        val (settings, _) = repo(SeasonChoice(listOf(2030), 2030))
        assertEquals(listOf(2024, 2025, 2026), settings.seasons.first())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:datastore:test :core:data:test --tests "*SettingsRepositoryTest*"`
Expected: FAIL to compile: `Unresolved reference 'SeasonChoice'`.

- [ ] **Step 3: Store the choice**

In `core/datastore/src/main/kotlin/dev/gridiron/core/datastore/UserPrefs.kt`:

1. Add this parameter to `UserPrefs`, after `resetNotice`, and add a `@property` line to the class KDoc: ` * @property seasons The seasons to build; null means the default (the current season and the two before it).`

```kotlin
    val seasons: SeasonChoice? = null,
```

2. Add at the end of the file:

```kotlin
/**
 * The seasons the phone builds, as the user left them.
 *
 * @property chosenIn The season that was current when this was saved. A choice
 *   that included it keeps following the current season as new ones start.
 */
public data class SeasonChoice(val seasons: List<Int>, val chosenIn: Int)
```

In `core/datastore/src/main/kotlin/dev/gridiron/core/datastore/UserPrefsJson.kt`:

1. Add two fields to `UserPrefsDto`, after `resetNotice`:

```kotlin
    val seasons: List<Int>? = null,
    val seasonsChosenIn: Int? = null,
```

2. In `UserPrefsDto.toDomain()`, replace the `return UserPrefs(...)` line with:

```kotlin
    val choice = seasons?.let { s -> seasonsChosenIn?.let { SeasonChoice(s.distinct().sorted(), it) } }
    return UserPrefs(profiles, activeProfileId ?: UserPrefs.DEFAULT.activeProfileId, tray, resetNotice, choice)
```

3. In `UserPrefs.toDto()`, add after `resetNotice = resetNotice,`:

```kotlin
    seasons = seasons?.seasons,
    seasonsChosenIn = seasons?.chosenIn,
```

- [ ] **Step 4: Write the repository**

```kotlin
// core/data/src/main/kotlin/dev/gridiron/core/data/SettingsRepository.kt
package dev.gridiron.core.data

import dev.gridiron.core.datastore.PrefsSource
import dev.gridiron.core.datastore.SeasonChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Which seasons the phone builds. Changes take effect on the next refresh: an
 * added season is fetched and crunched, and a removed season's rows are left
 * out of the new database.
 */
public class SettingsRepository(
    private val prefs: PrefsSource,
    private val currentSeason: () -> Int,
) {
    /** Every season the user can pick, oldest first. */
    public val choices: List<Int> get() = (FIRST_SEASON..currentSeason()).toList()

    /** The seasons to build, sorted and never empty. */
    public val seasons: Flow<List<Int>> =
        prefs.prefs.map { resolve(it.seasons, currentSeason()) }.distinctUntilChanged()

    /** Checks or unchecks [season]. Returns false, changing nothing, if that would leave no season checked. */
    public suspend fun setSelected(season: Int, selected: Boolean): Boolean {
        val current = currentSeason()
        require(season in FIRST_SEASON..current) { "season $season outside $FIRST_SEASON..$current" }
        var changed = false
        prefs.update { p ->
            val now = resolve(p.seasons, current)
            val next = if (selected) (now + season).distinct().sorted() else now - season
            if (next.isEmpty()) {
                p
            } else {
                changed = true
                p.copy(seasons = SeasonChoice(next, current))
            }
        }
        return changed
    }

    public companion object {
        /** nflverse's play-by-play goes back further, but ffopportunity's expected points start here. */
        public const val FIRST_SEASON: Int = 2012

        public fun defaults(current: Int): List<Int> = listOf(current - 2, current - 1, current)

        internal fun resolve(choice: SeasonChoice?, current: Int): List<Int> {
            val picked = when {
                choice == null -> defaults(current)
                choice.chosenIn in choice.seasons -> choice.seasons + ((choice.chosenIn + 1)..current)
                else -> choice.seasons
            }
            return picked.filter { it in FIRST_SEASON..current }.distinct().sorted().ifEmpty { defaults(current) }
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:datastore:test :core:data:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add core/datastore core/data/src/main/kotlin/dev/gridiron/core/data/SettingsRepository.kt core/data/src/test/kotlin/dev/gridiron/core/data/SettingsRepositoryTest.kt
git commit -m "settings: a seasons choice that follows the current season"
```

---

### Task 4: Reading ESPN's news and injuries

This task parses the two ESPN responses into plain Kotlin values. It uses kotlinx-serialization's `JsonElement` tree, not generated serializers, so a field ESPN adds or drops never breaks parsing.

**What happens to bad input**
- A single article or injury missing something essential is skipped. For an article that's an id, headline, time or link; for an injury it's an athlete id, name or status.
- A response that isn't the expected shape at all throws `LiveFormatException`. The repository then keeps the previous data.

**Where the values come from**
- Injury `date`s look like `2026-09-25T21:57Z`, with no seconds. `Instant.parse` rejects that form, so times go through `OffsetDateTime.parse` (Review Focus 5).
- The ESPN athlete id of an injury comes from `athlete.links[].href` (`…/id/4698113/max-melton`).
- The badge letter comes from `type.abbreviation`.

The test fixtures are real responses recorded on 2026-09-25, cut down to a few entries. Images, logos and extra links were removed; every other field is as ESPN sent it.

**Files:**
- Modify: `core/data/build.gradle.kts`
- Create: `core/data/src/main/kotlin/dev/gridiron/core/data/live/Espn.kt`
- Create: `core/data/src/main/kotlin/dev/gridiron/core/data/live/HttpGet.kt`
- Create: `core/data/src/test/resources/espn/news.json`
- Create: `core/data/src/test/resources/espn/injuries.json`
- Test: `core/data/src/test/kotlin/dev/gridiron/core/data/live/EspnTest.kt`
- Test: `core/data/src/test/kotlin/dev/gridiron/core/data/live/UrlConnectionHttpGetTest.kt`

**Interfaces:**
- Produces (package `dev.gridiron.core.data.live`):
  - `internal data class NewsArticle(id: String, published: Instant, headline: String, description: String?, url: String, athletes: List<TaggedAthlete>)`
  - `internal data class TaggedAthlete(espnId: String, name: String)`
  - `internal data class EspnInjury(espnId: String, name: String, team: String?, position: String?, status: String, abbr: String, shortComment: String?, longComment: String?, date: Instant?)`
  - `public class LiveFormatException(message: String) : Exception`
  - `internal object EspnParser { const val NEWS_URL; const val INJURIES_URL; fun news(text: String): List<NewsArticle>; fun injuries(text: String): List<EspnInjury> }`
  - `internal fun parseEspnTime(text: String): Instant?`
  - `public fun interface HttpGet { public suspend fun get(url: String): String }`
  - `public class UrlConnectionHttpGet(connectTimeoutMs: Int = 15_000, readTimeoutMs: Int = 30_000) : HttpGet`. It throws `IOException` on a non-200 status or a network failure.

- [ ] **Step 1: Add the JSON dependency**

In `core/data/build.gradle.kts`, add to `dependencies` after `api(libs.kotlinx.collections.immutable)`:

```kotlin
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.sqlite.bundled)
```

The bundled SQLite driver is for Task 5's `live.db`. Adding it here saves a second edit.

- [ ] **Step 2: Add the recorded responses**

`core/data/src/test/resources/espn/news.json`:

```json
{
  "header": "NFL News",
  "articles": [
    {
      "id": 50029598,
      "type": "HeadlineNews",
      "headline": "Eagles' Saquon Barkley to be 'fearless' vs. Bears despite stinger",
      "description": "Eagles running back Saquon Barkley plans to play \"fearless\" Monday night against the Chicago Bears as he eyes a return from a stinger injury.",
      "published": "2026-09-25T23:01:03Z",
      "links": { "web": { "href": "https://www.espn.com/nfl/story/_/id/50029598/eagles-saquon-barkley-fearless-vs-bears-stinger" } },
      "categories": [
        { "id": 1407, "type": "team", "description": "Philadelphia Eagles", "teamId": 21 },
        { "id": 184478, "type": "athlete", "description": "Saquon Barkley", "athleteId": 3929630 }
      ]
    },
    {
      "id": 50029379,
      "type": "HeadlineNews",
      "headline": "QB Sam Darnold back Sunday, but Seahawks down two safeties",
      "description": "Seahawks quarterback Sam Darnold will start Sunday, but the defending Super Bowl champions will be without both starting safeties as Julian Love and Ty Okada have been ruled out.",
      "published": "2026-09-25T22:25:25Z",
      "links": { "web": { "href": "https://www.espn.com/nfl/story/_/id/50029379/qb-sam-darnold-back-sunday-seahawks-two-safeties" } },
      "categories": [
        { "id": 1693, "type": "team", "description": "Seattle Seahawks", "teamId": 26 },
        { "id": 184497, "type": "athlete", "description": "Sam Darnold", "athleteId": 3912547 },
        { "id": 380576, "type": "athlete", "description": "Julian Love", "athleteId": 4046675 },
        { "id": 205575, "type": "athlete", "description": "Ty Okada", "athleteId": 4247808 }
      ]
    },
    {
      "id": 50028554,
      "type": "HeadlineNews",
      "headline": "NFL, NFLPA continue to monitor field conditions in Rio",
      "description": "Field experts for the NFL and the NFL Players Association have been in Brazil for days to monitor the playing surface at Maracanã Stadium, NFLPA executive director JC Tretter said in a lengthy statement on Friday.",
      "published": "2026-09-25T18:53:45Z",
      "links": { "web": { "href": "https://www.espn.com/nfl/story/_/id/50028554/nfl-nflpa-continue-monitor-field-conditions-rio" } },
      "categories": [
        { "id": 2830, "type": "team", "description": "Baltimore Ravens", "teamId": 33 },
        { "id": 1298, "type": "team", "description": "Dallas Cowboys", "teamId": 6 }
      ]
    }
  ]
}
```

`core/data/src/test/resources/espn/injuries.json`:

```json
{
  "timestamp": "2026-09-25T23:54:57Z",
  "status": "success",
  "season": { "year": 2026, "type": 2, "name": "Regular Season", "displayName": "2026" },
  "injuries": [
    {
      "id": "22",
      "displayName": "Arizona Cardinals",
      "injuries": [
        {
          "id": "-2015438",
          "longComment": "",
          "shortComment": "",
          "status": "Active",
          "date": "2026-09-25T21:57Z",
          "athlete": {
            "displayName": "Hjalte Froholdt",
            "links": [ { "language": "en-US", "rel": ["playercard", "desktop", "athlete"], "href": "https://www.espn.com/nfl/player/_/id/3886633/hjalte-froholdt", "text": "Player Card" } ],
            "position": { "abbreviation": "C" },
            "team": { "abbreviation": "ARI" }
          },
          "type": { "id": "0", "name": "INJURY_STATUS_ACTIVE", "description": "active", "abbreviation": "A" }
        },
        {
          "id": "639083",
          "longComment": "It's a step in the right direction for Melton, who missed Wednesday's practice due to a toe injury he sustained during the Cardinals' Week 2 loss to the Seahawks. The Cardinals will announce injury designations following Friday's practice, at which point Melton's status for Sunday's game against the 49ers will become clearer.",
          "shortComment": "Melton (toe) was a limited participant in Thursday's practice.",
          "status": "Questionable",
          "date": "2026-09-25T03:06Z",
          "athlete": {
            "displayName": "Max Melton",
            "links": [ { "language": "en-US", "rel": ["playercard", "desktop", "athlete"], "href": "https://www.espn.com/nfl/player/_/id/4698113/max-melton", "text": "Player Card" } ],
            "position": { "abbreviation": "CB" },
            "team": { "abbreviation": "ARI" }
          },
          "type": { "id": "2", "name": "INJURY_STATUS_QUESTIONABLE", "description": "questionable", "abbreviation": "Q" }
        },
        {
          "id": "639082",
          "longComment": "Taylor-Demerson missed Wednesday's practice due to a back injury he likely picked up in the Cardinals' Week 2 loss to the Seahawks. His ability to return to practice Thursday is a positive sign in his injury progression, and Friday's injury report will provide more clarity on Taylor-Demerson's status heading into Sunday's game against the 49ers.",
          "shortComment": "Taylor-Demerson (back) was a limited participant in Thursday's practice.",
          "status": "Out",
          "date": "2026-09-25T03:04Z",
          "athlete": {
            "displayName": "Dadrion Taylor-Demerson",
            "links": [ { "language": "en-US", "rel": ["playercard", "desktop", "athlete"], "href": "https://www.espn.com/nfl/player/_/id/4428633/dadrion-taylor-demerson", "text": "Player Card" } ],
            "position": { "abbreviation": "S" },
            "team": { "abbreviation": "ARI" }
          },
          "type": { "id": "4", "name": "INJURY_STATUS_OUT", "description": "out", "abbreviation": "O" }
        }
      ]
    },
    {
      "id": "1",
      "displayName": "Atlanta Falcons",
      "injuries": [
        {
          "id": "639030",
          "longComment": "Strand saw some action in relief of Cooper Rush in the 34-3 loss to Carolina in Week 2. However, he didn't play well, throwing for 59 yards and one interception off 8-for-15 passing, adding two carries for 16 yards. With Michael Penix (knee) and Tua Tagovailoa (oblique) back in the mix, and Rush serving as the emergency signal-caller, Strand's services won't be needed in Week 3.",
          "shortComment": "Strand (coach's decision) is inactive for Thursday's game against the Packers.",
          "status": "Out",
          "date": "2026-09-24T23:18Z",
          "athlete": {
            "displayName": "Jack Strand",
            "links": [ { "language": "en-US", "rel": ["playercard", "desktop", "athlete"], "href": "https://www.espn.com/nfl/player/_/id/5344782/jack-strand", "text": "Player Card" } ],
            "position": { "abbreviation": "QB" },
            "team": { "abbreviation": "ATL" }
          },
          "type": { "id": "4", "name": "INJURY_STATUS_OUT", "description": "out", "abbreviation": "O" }
        },
        {
          "id": "639024",
          "longComment": "Rush started for Atlanta in the team's first two games of the 2026 campaign. However, the signal-caller struggled overall, throwing for 229 yards, one touchdown and four interceptions while completing just 56.4 percent of his attempts. With Michael Penix (knee) and Tua Tagovailoa (oblique) back in the equation, Rush will be inactive against the Packers, though he will be available in an emergency situation.",
          "shortComment": "Rush (coach's decision) is inactive for Thursday's game versus Green Bay but will serve as the emergency quarterback.",
          "status": "Out",
          "date": "2026-09-24T23:01Z",
          "athlete": {
            "displayName": "Cooper Rush",
            "links": [ { "language": "en-US", "rel": ["playercard", "desktop", "athlete"], "href": "https://www.espn.com/nfl/player/_/id/2972515/cooper-rush", "text": "Player Card" } ],
            "position": { "abbreviation": "QB" },
            "team": { "abbreviation": "ATL" }
          },
          "type": { "id": "4", "name": "INJURY_STATUS_OUT", "description": "out", "abbreviation": "O" }
        }
      ]
    }
  ]
}
```

- [ ] **Step 3: Write the failing tests**

```kotlin
// core/data/src/test/kotlin/dev/gridiron/core/data/live/EspnTest.kt
package dev.gridiron.core.data.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class EspnTest {
    private fun recorded(name: String): String = checkNotNull(javaClass.getResource("/espn/$name")) { name }.readText()

    @Test
    fun `news articles keep their tagged athletes, and untagged articles still appear`() {
        val news = EspnParser.news(recorded("news.json"))

        assertEquals(listOf("50029598", "50029379", "50028554"), news.map { it.id })
        val barkley = news[0]
        assertEquals("Eagles' Saquon Barkley to be 'fearless' vs. Bears despite stinger", barkley.headline)
        assertEquals(Instant.parse("2026-09-25T23:01:03Z"), barkley.published)
        assertEquals("https://www.espn.com/nfl/story/_/id/50029598/eagles-saquon-barkley-fearless-vs-bears-stinger", barkley.url)
        assertEquals(listOf(TaggedAthlete("3929630", "Saquon Barkley")), barkley.athletes)
        assertEquals(listOf("3912547", "4046675", "4247808"), news[1].athletes.map { it.espnId })
        assertEquals(emptyList<TaggedAthlete>(), news[2].athletes)
    }

    @Test
    fun `injuries carry the athlete id from the player link and times without seconds`() {
        val injuries = EspnParser.injuries(recorded("injuries.json"))

        assertEquals(5, injuries.size)
        val melton = injuries.single { it.name == "Max Melton" }
        assertEquals(
            EspnInjury(
                espnId = "4698113",
                name = "Max Melton",
                team = "ARI",
                position = "CB",
                status = "Questionable",
                abbr = "Q",
                shortComment = "Melton (toe) was a limited participant in Thursday's practice.",
                longComment = melton.longComment,
                date = Instant.parse("2026-09-25T03:06:00Z"),
            ),
            melton,
        )
        val active = injuries.single { it.name == "Hjalte Froholdt" }
        assertEquals("A", active.abbr)
        assertNull(active.shortComment)
        assertEquals(listOf("A", "Q", "O", "O", "O"), injuries.map { it.abbr })
    }

    @Test
    fun `an article without a link or an injury without an athlete id is skipped`() {
        val news = EspnParser.news(
            """{"articles": [
                 {"id": 1, "headline": "No link", "published": "2026-09-25T10:00:00Z"},
                 {"id": 2, "headline": "Fine", "published": "2026-09-25T10:00:00Z", "links": {"web": {"href": "https://x/2"}}}
               ]}""",
        )
        assertEquals(listOf("2"), news.map { it.id })

        val injuries = EspnParser.injuries(
            """{"injuries": [{"injuries": [
                 {"status": "Out", "athlete": {"displayName": "No Id", "links": []}},
                 {"status": "Out", "athlete": {"displayName": "Has Id", "links": [{"href": "https://www.espn.com/nfl/player/_/id/77/has-id"}]}}
               ]}]}""",
        )
        assertEquals(listOf("77"), injuries.map { it.espnId })
        assertEquals("O", injuries.single().abbr)
    }

    @Test
    fun `a response in another shape is a format error, not an empty list`() {
        assertThrows<LiveFormatException> { EspnParser.news("<html>maintenance</html>") }
        assertThrows<LiveFormatException> { EspnParser.news("""{"articles": 3}""") }
        assertThrows<LiveFormatException> { EspnParser.injuries("""{"teams": []}""") }
    }

    @Test
    fun `times parse with and without seconds`() {
        assertEquals(Instant.parse("2026-09-25T21:57:00Z"), parseEspnTime("2026-09-25T21:57Z"))
        assertEquals(Instant.parse("2026-09-25T23:01:03Z"), parseEspnTime("2026-09-25T23:01:03Z"))
        assertNull(parseEspnTime("yesterday"))
    }
}
```

```kotlin
// core/data/src/test/kotlin/dev/gridiron/core/data/live/UrlConnectionHttpGetTest.kt
package dev.gridiron.core.data.live

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream

class UrlConnectionHttpGetTest {
    private lateinit var server: HttpServer

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun respond(path: String, status: Int, body: ByteArray, gzip: Boolean = false) {
        server.createContext(path) { ex ->
            if (gzip) ex.responseHeaders.add("Content-Encoding", "gzip")
            ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
    }

    @Test
    fun `reads a plain body`() = runTest {
        respond("/plain", 200, """{"ok":true}""".toByteArray())
        assertEquals("""{"ok":true}""", UrlConnectionHttpGet().get(url("/plain")))
    }

    @Test
    fun `reads a gzipped body`() = runTest {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write("""{"zipped":true}""".toByteArray()) }
        respond("/gz", 200, bytes.toByteArray(), gzip = true)
        assertEquals("""{"zipped":true}""", UrlConnectionHttpGet().get(url("/gz")))
    }

    @Test
    fun `an error status is an IOException naming the code`() = runTest {
        respond("/down", 503, ByteArray(0))
        val e = runCatching { UrlConnectionHttpGet().get(url("/down")) }.exceptionOrNull()
        assertTrue(e is IOException && e.message.orEmpty().contains("503"), "got $e")
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./gradlew :core:data:test --tests "*EspnTest*" --tests "*UrlConnectionHttpGetTest*"`
Expected: FAIL to compile: `Unresolved reference 'EspnParser'`.

- [ ] **Step 5: Implement the parser and the client**

```kotlin
// core/data/src/main/kotlin/dev/gridiron/core/data/live/Espn.kt
package dev.gridiron.core.data.live

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

internal data class NewsArticle(
    val id: String,
    val published: Instant,
    val headline: String,
    val description: String?,
    val url: String,
    val athletes: List<TaggedAthlete>,
)

internal data class TaggedAthlete(val espnId: String, val name: String)

internal data class EspnInjury(
    val espnId: String,
    val name: String,
    val team: String?,
    val position: String?,
    val status: String,
    /** ESPN's one- or two-letter code: A, Q, D, O, IR, … */
    val abbr: String,
    val shortComment: String?,
    val longComment: String?,
    val date: Instant?,
)

/** ESPN answered, but not in the shape this app reads. */
public class LiveFormatException(message: String) : Exception(message)

/**
 * ESPN's public (unofficial, keyless) NFL feeds. Parsing walks the JSON tree
 * rather than binding classes, so fields ESPN adds or drops never break it.
 * Entries missing what the app needs are skipped; a response that isn't the
 * expected shape at all is a [LiveFormatException].
 */
internal object EspnParser {
    const val NEWS_URL: String = "https://site.api.espn.com/apis/site/v2/sports/football/nfl/news?limit=50"
    const val INJURIES_URL: String = "https://site.api.espn.com/apis/site/v2/sports/football/nfl/injuries"

    private val ATHLETE_ID = Regex("/id/(\\d+)(/|$)")

    private val STATUS_ABBR = mapOf(
        "Active" to "A", "Questionable" to "Q", "Doubtful" to "D", "Out" to "O", "Injured Reserve" to "IR",
    )

    fun news(text: String): List<NewsArticle> {
        val articles = root(text, "news")["articles"] as? JsonArray
            ?: throw LiveFormatException("ESPN changed its news format (no articles list)")
        return articles.mapNotNull { (it as? JsonObject)?.let(::article) }
    }

    fun injuries(text: String): List<EspnInjury> {
        val teams = root(text, "injuries")["injuries"] as? JsonArray
            ?: throw LiveFormatException("ESPN changed its injuries format (no injuries list)")
        return teams
            .flatMap { team -> (team as? JsonObject)?.array("injuries").orEmpty() }
            .mapNotNull { (it as? JsonObject)?.let(::injury) }
            .distinctBy { it.espnId }
    }

    private fun article(a: JsonObject): NewsArticle? {
        val id = a.string("id") ?: return null
        val headline = a.string("headline") ?: return null
        val published = a.string("published")?.let(::parseEspnTime) ?: return null
        val url = a.obj("links")?.obj("web")?.string("href") ?: return null
        val athletes = a.array("categories").orEmpty().mapNotNull { c ->
            val category = c as? JsonObject ?: return@mapNotNull null
            if (category.string("type") != "athlete") return@mapNotNull null
            val espnId = category.string("athleteId") ?: return@mapNotNull null
            TaggedAthlete(espnId, category.string("description") ?: "")
        }.distinctBy { it.espnId }
        return NewsArticle(id, published, headline, a.string("description"), url, athletes)
    }

    private fun injury(e: JsonObject): EspnInjury? {
        val athlete = e.obj("athlete") ?: return null
        val espnId = athlete.array("links").orEmpty().firstNotNullOfOrNull { link ->
            (link as? JsonObject)?.string("href")?.let { ATHLETE_ID.find(it)?.groupValues?.get(1) }
        } ?: return null
        val name = athlete.string("displayName") ?: return null
        val status = e.string("status") ?: return null
        return EspnInjury(
            espnId = espnId,
            name = name,
            team = athlete.obj("team")?.string("abbreviation"),
            position = athlete.obj("position")?.string("abbreviation"),
            status = status,
            abbr = e.obj("type")?.string("abbreviation") ?: STATUS_ABBR[status] ?: status,
            shortComment = e.string("shortComment"),
            longComment = e.string("longComment"),
            date = e.string("date")?.let(::parseEspnTime),
        )
    }

    private fun root(text: String, what: String): JsonObject =
        try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: throw LiveFormatException("ESPN sent something that isn't $what JSON")
}

/**
 * ESPN writes times both with seconds (`2026-09-25T23:01:03Z`, news) and
 * without (`2026-09-25T21:57Z`, injuries). `Instant.parse` rejects the second
 * form; `OffsetDateTime.parse` reads both.
 */
internal fun parseEspnTime(text: String): Instant? =
    try {
        OffsetDateTime.parse(text).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }

/** A non-blank string (numbers as their text), or null. */
private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
```

```kotlin
// core/data/src/main/kotlin/dev/gridiron/core/data/live/HttpGet.kt
package dev.gridiron.core.data.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.GZIPInputStream

/** Fetches a URL's body as text; tests substitute canned responses. */
public fun interface HttpGet {
    public suspend fun get(url: String): String
}

/**
 * [HttpGet] over the JDK's connection. Asks for gzip (ESPN's injuries feed is
 * about 350 KB compressed, several MB plain) and throws [IOException] on any
 * status but 200.
 */
public class UrlConnectionHttpGet(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpGet {
    override suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Accept-Encoding", "gzip")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code from ${connection.url.host}")
            val body = connection.inputStream
            val input = if ("gzip".equals(connection.contentEncoding, ignoreCase = true)) GZIPInputStream(body) else body
            input.use { it.readBytes().decodeToString() }
        } finally {
            connection.disconnect()
        }
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :core:data:test --tests "*EspnTest*" --tests "*UrlConnectionHttpGetTest*"`
Expected: PASS, 8 tests.

- [ ] **Step 7: Commit**

```bash
git add core/data/build.gradle.kts core/data/src/main/kotlin/dev/gridiron/core/data/live core/data/src/test/resources/espn core/data/src/test/kotlin/dev/gridiron/core/data/live
git commit -m "live: parse ESPN news and injuries from recorded responses"
```

---

### Task 5: The `live.db` store

`live.db` holds ESPN news and injuries. Unlike `stats.db`, it is written in place on the phone, and it is disposable. A file that can't be read, or that has another layout version, is deleted and started fresh; the next fetch refills it in seconds.

**Tables** (the spec's four, plus a small key/value table):
- `news_item` and `news_player`. Every row keeps the ESPN athlete id and name, and the app `player_id` is nullable. So unmatched athletes still show in the feed, and a later refresh can link them (Task 6).
- `injury_status`: the current snapshot, replaced on every fetch. It keeps name, team and position, so the Injury report can show players the app has no stats for.
- `injury_note`: appended when a player's short comment differs from their latest note. It outlives the snapshot, so a player's history survives them leaving the report.
- `live_meta`: when each feed last arrived.
- Rows older than 30 days are pruned.

**Files:**
- Create: `core/data/src/main/kotlin/dev/gridiron/core/data/live/LiveDb.kt`
- Create: `core/data/src/main/kotlin/dev/gridiron/core/data/live/LiveStore.kt`
- Test: `core/data/src/test/kotlin/dev/gridiron/core/data/live/LiveStoreTest.kt`

**Interfaces:**
- Consumes: `NewsArticle`, `TaggedAthlete`, `EspnInjury` (Task 4); `androidx.sqlite.driver.bundled` (added to `:core:data` in Task 4).
- Produces (package `dev.gridiron.core.data.live`):
  - `public class LiveDb(file: File, dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)) : AutoCloseable`. It opens lazily on first use. `internal suspend fun <T> read(block: (SQLiteConnection) -> T): T` and `internal suspend fun write(block: (SQLiteConnection) -> Unit)` run one transaction and roll back on a throw.
  - Public models:
    - `NewsPlayer(name: String, playerId: String?)`
    - `NewsItem(id, published: Instant, headline, description: String?, url, players: List<NewsPlayer>)`
    - `LiveStatus(status, abbr, shortComment: String?, longComment: String?, updatedAt: Instant?)`
    - `InjuryNote(notedAt: Instant, status: String, comment: String)`
    - `LiveInjury(espnId, playerId: String?, name, team: String?, position: String?, status, abbr, shortComment: String?, updatedAt: Instant?)`
  - `internal` extensions on `SQLiteConnection`:
    - Writes: `saveNews(articles, playerIds: Map<String, String>)`, `saveInjuries(injuries, playerIds, now: Instant)`, `relink(playerIds)`, `prune(now)` and `setMeta(key, value)`.
    - Reads: `unlinkedEspnIds(): Set<String>`, `meta(key): String?`, `news(playerId: String?): List<NewsItem>`, `status(playerId): LiveStatus?`, `notes(playerId): List<InjuryNote>`, `badges(): Map<String, String>` and `injuries(): List<LiveInjury>`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// core/data/src/test/kotlin/dev/gridiron/core/data/live/LiveStoreTest.kt
package dev.gridiron.core.data.live

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant

class LiveStoreTest {
    @TempDir
    lateinit var dir: File

    private lateinit var db: LiveDb

    @BeforeEach
    fun open() {
        db = LiveDb(File(dir, "live.db"))
    }

    @AfterEach
    fun close() {
        db.close()
    }

    private val t0 = Instant.parse("2026-09-25T12:00:00Z")
    private val statusNames = mapOf("A" to "Active", "Q" to "Questionable", "D" to "Doubtful", "O" to "Out", "IR" to "Injured Reserve")

    private fun article(id: String, at: Instant, vararg athletes: Pair<String, String>) =
        NewsArticle(id, at, "Headline $id", null, "https://x/$id", athletes.map { TaggedAthlete(it.first, it.second) })

    private fun injury(espnId: String, abbr: String, comment: String?, date: Instant? = t0, team: String = "KC", name: String = "Player $espnId") =
        EspnInjury(espnId, name, team, "WR", statusNames.getValue(abbr), abbr, comment, null, date)

    @Test
    fun `news comes back newest first with linked and unlinked players`() = runTest {
        db.write {
            it.saveNews(listOf(article("1", t0, "10" to "Linked Guy", "11" to "Unknown Guy"), article("2", t0.plusSeconds(60))), mapOf("10" to "P10"))
        }
        val news = db.read { it.news(null) }

        assertEquals(listOf("2", "1"), news.map { it.id })
        assertEquals(listOf(NewsPlayer("Linked Guy", "P10"), NewsPlayer("Unknown Guy", null)), news[1].players)
        assertEquals(listOf("1"), db.read { it.news("P10") }.map { it.id })
    }

    @Test
    fun `saving an article again replaces its tags instead of duplicating them`() = runTest {
        db.write { it.saveNews(listOf(article("1", t0, "10" to "A")), emptyMap()) }
        db.write { it.saveNews(listOf(article("1", t0, "10" to "A", "12" to "B")), emptyMap()) }
        assertEquals(listOf("A", "B"), db.read { it.news(null) }.single().players.map { it.name })
    }

    @Test
    fun `the status snapshot is replaced and notes are appended only when the comment changes`() = runTest {
        val ids = mapOf("10" to "P10", "11" to "P11")
        db.write { it.saveInjuries(listOf(injury("10", "Q", "Limited Wednesday"), injury("11", "O", "Out for the week")), ids, t0) }
        db.write { it.saveInjuries(listOf(injury("10", "Q", "Limited Wednesday", date = t0.plusSeconds(3600))), ids, t0.plusSeconds(3600)) }
        db.write { it.saveInjuries(listOf(injury("10", "D", "Did not practice Thursday", date = t0.plusSeconds(7200))), ids, t0.plusSeconds(7200)) }

        assertEquals("D", db.read { it.status("P10") }?.abbr)
        assertNull(db.read { it.status("P11") })
        assertEquals(listOf("Did not practice Thursday", "Limited Wednesday"), db.read { it.notes("P10") }.map { it.comment })
        // A player's history outlives their place on the report.
        assertEquals(listOf("Out for the week"), db.read { it.notes("P11") }.map { it.comment })
    }

    @Test
    fun `badges skip active players and players with no app id`() = runTest {
        db.write {
            it.saveInjuries(listOf(injury("10", "Q", null), injury("11", "A", null), injury("12", "IR", null)), mapOf("10" to "P10", "11" to "P11"), t0)
        }
        assertEquals(mapOf("P10" to "Q"), db.read { it.badges() })
    }

    @Test
    fun `the injury list leaves out active players and orders by team, then severity, then name`() = runTest {
        db.write {
            it.saveInjuries(
                listOf(
                    injury("1", "Q", null, team = "KC", name = "Zed"),
                    injury("2", "O", null, team = "KC", name = "Amy"),
                    injury("3", "A", null, team = "KC"),
                    injury("4", "D", null, team = "BUF", name = "Bo"),
                ),
                emptyMap(),
                t0,
            )
        }
        assertEquals(listOf("Bo", "Amy", "Zed"), db.read { it.injuries() }.map { it.name })
    }

    @Test
    fun `rows older than thirty days are pruned`() = runTest {
        val old = t0.minus(Duration.ofDays(31))
        db.write {
            it.saveNews(listOf(article("old", old, "10" to "A"), article("new", t0)), emptyMap())
            it.saveInjuries(listOf(injury("10", "Q", "old note", date = old)), mapOf("10" to "P10"), t0)
            it.saveInjuries(listOf(injury("10", "Q", "new note", date = t0)), mapOf("10" to "P10"), t0)
            it.prune(t0)
        }
        assertEquals(listOf("new"), db.read { it.news(null) }.map { it.id })
        assertEquals(listOf("new note"), db.read { it.notes("P10") }.map { it.comment })
        // The pruned article's tag went with it.
        assertEquals(emptySet<String>(), db.read { it.unlinkedEspnIds() })
    }

    @Test
    fun `relinking fills in player ids that weren't known before`() = runTest {
        db.write {
            it.saveNews(listOf(article("1", t0, "10" to "A")), emptyMap())
            it.saveInjuries(listOf(injury("10", "Q", "note")), emptyMap(), t0)
        }
        assertEquals(setOf("10"), db.read { it.unlinkedEspnIds() })

        db.write { it.relink(mapOf("10" to "P10")) }

        assertEquals(emptySet<String>(), db.read { it.unlinkedEspnIds() })
        assertEquals(listOf("1"), db.read { it.news("P10") }.map { it.id })
        assertEquals(listOf("note"), db.read { it.notes("P10") }.map { it.comment })
        assertEquals("Q", db.read { it.status("P10") }?.abbr)
    }

    @Test
    fun `meta values round-trip`() = runTest {
        assertNull(db.read { it.meta("news_fetched_at") })
        db.write { it.setMeta("news_fetched_at", "123") }
        db.write { it.setMeta("news_fetched_at", "456") }
        assertEquals("456", db.read { it.meta("news_fetched_at") })
    }

    @Test
    fun `a failed write rolls back`() = runTest {
        val e = runCatching {
            db.write {
                it.saveNews(listOf(article("1", t0)), emptyMap())
                error("boom")
            }
        }.exceptionOrNull()
        assertEquals("boom", e?.message)
        assertEquals(0, db.read { it.news(null) }.size)
    }

    @Test
    fun `a corrupt file is replaced with an empty database`() = runTest {
        db.close()
        val file = File(dir, "live.db")
        file.writeText("this is not a database")
        db = LiveDb(file)

        assertEquals(emptyList<NewsItem>(), db.read { it.news(null) })
        db.write { it.saveNews(listOf(article("1", t0)), emptyMap()) }
        assertEquals(1, db.read { it.news(null) }.size)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:data:test --tests "*LiveStoreTest*"`
Expected: FAIL to compile: `Unresolved reference 'LiveDb'`.

- [ ] **Step 3: Write the connection and schema**

```kotlin
// core/data/src/main/kotlin/dev/gridiron/core/data/live/LiveDb.kt
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
```

- [ ] **Step 4: Write the store's SQL**

```kotlin
// core/data/src/main/kotlin/dev/gridiron/core/data/live/LiveStore.kt
package dev.gridiron.core.data.live

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import java.time.Duration
import java.time.Instant

public data class NewsPlayer(val name: String, val playerId: String?)

public data class NewsItem(
    val id: String,
    val published: Instant,
    val headline: String,
    val description: String?,
    val url: String,
    val players: List<NewsPlayer>,
)

public data class LiveStatus(
    val status: String,
    val abbr: String,
    val shortComment: String?,
    val longComment: String?,
    val updatedAt: Instant?,
)

public data class InjuryNote(val notedAt: Instant, val status: String, val comment: String)

public data class LiveInjury(
    val espnId: String,
    val playerId: String?,
    val name: String,
    val team: String?,
    val position: String?,
    val status: String,
    val abbr: String,
    val shortComment: String?,
    val updatedAt: Instant?,
)

private val RETENTION: Duration = Duration.ofDays(30)
private const val NEWS_LIMIT = 100L

private fun SQLiteStatement.bindTextOrNull(index: Int, value: String?) {
    if (value == null) bindNull(index) else bindText(index, value)
}

private fun SQLiteStatement.textOrNull(index: Int): String? = if (isNull(index)) null else getText(index)

private fun SQLiteStatement.instantOrNull(index: Int): Instant? = if (isNull(index)) null else Instant.ofEpochMilli(getLong(index))

/** Upserts [articles] and replaces their player tags. */
internal fun SQLiteConnection.saveNews(articles: List<NewsArticle>, playerIds: Map<String, String>) {
    prepare(
        """INSERT INTO news_item (id, published, headline, description, url) VALUES (?, ?, ?, ?, ?)
           ON CONFLICT (id) DO UPDATE SET published = excluded.published, headline = excluded.headline,
               description = excluded.description, url = excluded.url""",
    ).use { st ->
        for (a in articles) {
            st.reset()
            st.clearBindings()
            st.bindText(1, a.id)
            st.bindLong(2, a.published.toEpochMilli())
            st.bindText(3, a.headline)
            st.bindTextOrNull(4, a.description)
            st.bindText(5, a.url)
            st.step()
        }
    }
    prepare("DELETE FROM news_player WHERE news_id = ?").use { st ->
        for (a in articles) {
            st.reset()
            st.bindText(1, a.id)
            st.step()
        }
    }
    prepare("INSERT OR IGNORE INTO news_player (news_id, espn_id, name, player_id) VALUES (?, ?, ?, ?)").use { st ->
        for (a in articles) {
            for (p in a.athletes) {
                st.reset()
                st.clearBindings()
                st.bindText(1, a.id)
                st.bindText(2, p.espnId)
                st.bindText(3, p.name)
                st.bindTextOrNull(4, playerIds[p.espnId])
                st.step()
            }
        }
    }
}

/**
 * Replaces the status snapshot with [injuries], first appending a note for
 * each player whose short comment differs from their latest note. A note is
 * dated by ESPN's own time for it, or [now] if ESPN gave none.
 */
internal fun SQLiteConnection.saveInjuries(injuries: List<EspnInjury>, playerIds: Map<String, String>, now: Instant) {
    prepare("SELECT comment FROM injury_note WHERE espn_id = ? ORDER BY noted_at DESC LIMIT 1").use { latest ->
        prepare("INSERT OR IGNORE INTO injury_note (espn_id, noted_at, player_id, status, comment) VALUES (?, ?, ?, ?, ?)").use { add ->
            for (i in injuries) {
                val comment = i.shortComment ?: continue
                latest.reset()
                latest.bindText(1, i.espnId)
                val previous = if (latest.step()) latest.getText(0) else null
                if (previous == comment) continue
                add.reset()
                add.clearBindings()
                add.bindText(1, i.espnId)
                add.bindLong(2, (i.date ?: now).toEpochMilli())
                add.bindTextOrNull(3, playerIds[i.espnId])
                add.bindText(4, i.status)
                add.bindText(5, comment)
                add.step()
            }
        }
    }
    execSQL("DELETE FROM injury_status")
    prepare(
        """INSERT OR IGNORE INTO injury_status
           (espn_id, player_id, name, team, position, status, abbr, short_comment, long_comment, updated_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
    ).use { st ->
        for (i in injuries) {
            st.reset()
            st.clearBindings()
            st.bindText(1, i.espnId)
            st.bindTextOrNull(2, playerIds[i.espnId])
            st.bindText(3, i.name)
            st.bindTextOrNull(4, i.team)
            st.bindTextOrNull(5, i.position)
            st.bindText(6, i.status)
            st.bindText(7, i.abbr)
            st.bindTextOrNull(8, i.shortComment)
            st.bindTextOrNull(9, i.longComment)
            if (i.date == null) st.bindNull(10) else st.bindLong(10, i.date.toEpochMilli())
            st.step()
        }
    }
}

private val LINKED_TABLES = listOf("news_player", "injury_status", "injury_note")

/** ESPN ids stored without an app player id, to look up again. */
internal fun SQLiteConnection.unlinkedEspnIds(): Set<String> =
    prepare(LINKED_TABLES.joinToString(" UNION ") { "SELECT espn_id FROM $it WHERE player_id IS NULL" }).use { st ->
        buildSet { while (st.step()) add(st.getText(0)) }
    }

/** Fills in player ids that are now known, for rows stored before they were. */
internal fun SQLiteConnection.relink(playerIds: Map<String, String>) {
    for (table in LINKED_TABLES) {
        prepare("UPDATE $table SET player_id = ? WHERE espn_id = ? AND player_id IS NULL").use { st ->
            for ((espnId, playerId) in playerIds) {
                st.reset()
                st.bindText(1, playerId)
                st.bindText(2, espnId)
                st.step()
            }
        }
    }
}

/** Drops news and notes older than thirty days before [now]. */
internal fun SQLiteConnection.prune(now: Instant) {
    val cutoff = now.minus(RETENTION).toEpochMilli()
    for (sql in listOf(
        "DELETE FROM news_player WHERE news_id IN (SELECT id FROM news_item WHERE published < ?)",
        "DELETE FROM news_item WHERE published < ?",
        "DELETE FROM injury_note WHERE noted_at < ?",
    )) {
        prepare(sql).use {
            it.bindLong(1, cutoff)
            it.step()
        }
    }
}

internal fun SQLiteConnection.setMeta(key: String, value: String) {
    prepare("INSERT OR REPLACE INTO live_meta (key, value) VALUES (?, ?)").use {
        it.bindText(1, key)
        it.bindText(2, value)
        it.step()
    }
}

internal fun SQLiteConnection.meta(key: String): String? =
    prepare("SELECT value FROM live_meta WHERE key = ?").use {
        it.bindText(1, key)
        if (it.step()) it.getText(0) else null
    }

/** The newest articles, or only those tagged with [playerId]; each with its tagged players. */
internal fun SQLiteConnection.news(playerId: String?): List<NewsItem> {
    val sql = if (playerId == null) {
        "SELECT id, published, headline, description, url FROM news_item ORDER BY published DESC, id LIMIT ?"
    } else {
        """SELECT n.id, n.published, n.headline, n.description, n.url
           FROM news_item n JOIN news_player p ON p.news_id = n.id
           WHERE p.player_id = ? ORDER BY n.published DESC, n.id LIMIT ?"""
    }
    val items = prepare(sql).use { st ->
        if (playerId == null) {
            st.bindLong(1, NEWS_LIMIT)
        } else {
            st.bindText(1, playerId)
            st.bindLong(2, NEWS_LIMIT)
        }
        buildList {
            while (st.step()) {
                add(NewsItem(st.getText(0), Instant.ofEpochMilli(st.getLong(1)), st.getText(2), st.textOrNull(3), st.getText(4), emptyList()))
            }
        }
    }
    if (items.isEmpty()) return items
    // At most a few thousand tags in thirty days: one read, grouped here.
    val tags = prepare("SELECT news_id, name, player_id FROM news_player ORDER BY news_id, name").use { st ->
        buildList { while (st.step()) add(st.getText(0) to NewsPlayer(st.getText(1), st.textOrNull(2))) }
    }.groupBy({ it.first }, { it.second })
    return items.map { it.copy(players = tags[it.id].orEmpty()) }
}

internal fun SQLiteConnection.status(playerId: String): LiveStatus? =
    prepare(
        """SELECT status, abbr, short_comment, long_comment, updated_at FROM injury_status
           WHERE player_id = ? ORDER BY updated_at DESC LIMIT 1""",
    ).use { st ->
        st.bindText(1, playerId)
        if (st.step()) LiveStatus(st.getText(0), st.getText(1), st.textOrNull(2), st.textOrNull(3), st.instantOrNull(4)) else null
    }

internal fun SQLiteConnection.notes(playerId: String): List<InjuryNote> =
    prepare("SELECT noted_at, status, comment FROM injury_note WHERE player_id = ? ORDER BY noted_at DESC").use { st ->
        st.bindText(1, playerId)
        buildList { while (st.step()) add(InjuryNote(Instant.ofEpochMilli(st.getLong(0)), st.getText(1), st.getText(2))) }
    }

/** The Grid's badge letters by player id: every linked player whose status isn't Active. */
internal fun SQLiteConnection.badges(): Map<String, String> =
    prepare("SELECT player_id, abbr FROM injury_status WHERE player_id IS NOT NULL AND abbr <> 'A'").use { st ->
        buildMap { while (st.step()) put(st.getText(0), st.getText(1)) }
    }

/** Everyone on the report but Active players, by team, most serious first. */
internal fun SQLiteConnection.injuries(): List<LiveInjury> =
    prepare(
        """SELECT espn_id, player_id, name, team, position, status, abbr, short_comment, updated_at
           FROM injury_status WHERE abbr <> 'A'
           ORDER BY team, CASE abbr WHEN 'O' THEN 0 WHEN 'IR' THEN 1 WHEN 'D' THEN 2 WHEN 'Q' THEN 3 ELSE 4 END, name""",
    ).use { st ->
        buildList {
            while (st.step()) {
                add(
                    LiveInjury(
                        espnId = st.getText(0),
                        playerId = st.textOrNull(1),
                        name = st.getText(2),
                        team = st.textOrNull(3),
                        position = st.textOrNull(4),
                        status = st.getText(5),
                        abbr = st.getText(6),
                        shortComment = st.textOrNull(7),
                        updatedAt = st.instantOrNull(8),
                    ),
                )
            }
        }
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:data:test --tests "*LiveStoreTest*"`
Expected: PASS, 10 tests.

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/dev/gridiron/core/data/live/LiveDb.kt core/data/src/main/kotlin/dev/gridiron/core/data/live/LiveStore.kt core/data/src/test/kotlin/dev/gridiron/core/data/live/LiveStoreTest.kt
git commit -m "live: a disposable live.db for news, injury status and notes"
```

---

### Task 6: `LiveRepository`: fetch, link, keep, prune

This task ties Tasks 4 and 5 together. It adds a `PlayerDirectory` that looks up app player ids and names in `stats.db`.

**A refresh**
1. Fetch both ESPN feeds. Each succeeds or fails on its own.
2. Look up app player ids for three sets of ESPN ids: every athlete in the new news, everyone on the new injury list, and every ESPN id still stored without a player id.
3. Write it all in one transaction, then prune.

Two consequences:
- If the injuries feed fails, the old injuries stay and the news still updates, and the other way round. Each feed's "fetched at" time moves only when it arrives (Review Focus 4).
- News and injuries fetched before any `stats.db` existed get linked on the first refresh after it does.

**Where names and ids come from**
- The lookup reads Plan 1's `player_xref`. A database without it (none yet, or an older Python-built one still on the phone) gives no matches instead of an error.
- `PlayerDirectory.header` finds a player in `player`, which only has players with stats, and falls back to `player_xref`. That fallback covers everyone nflverse lists, so a news chip for a rookie with no snaps still opens a page with their name.

**Files:**
- Create: `core/data/src/main/kotlin/dev/gridiron/core/data/PlayerDirectory.kt`
- Test: `core/data/src/test/kotlin/dev/gridiron/core/data/PlayerDirectoryTest.kt`
- Create: `core/data/src/main/kotlin/dev/gridiron/core/data/live/LiveRepository.kt`
- Test: `core/data/src/test/kotlin/dev/gridiron/core/data/live/LiveRepositoryTest.kt`

**Interfaces:**
- Consumes: `EspnParser`, `HttpGet`, `LiveFormatException` (Task 4); `LiveDb` and the `LiveStore` extensions and models (Task 5); `QueryExecutor`, `playerHeaders`, `PlayerHeader` (existing); `JdbcQueryExecutor` (tests).
- Produces:
  - `public class PlayerDirectory(executor: QueryExecutor)` in `dev.gridiron.core.data`, with `suspend fun playerIds(espnIds: Collection<String>): Map<String, String>` and `suspend fun header(playerId: String): PlayerHeader?`
  - In `dev.gridiron.core.data.live`: `public data class LiveResult(newsError: String?, injuriesError: String?)`, with `ok: Boolean` and `message: String`.
  - `public class LiveRepository(db: LiveDb, http: HttpGet, players: PlayerDirectory, clock: () -> Instant = Instant::now)`, with:
    - `changes: StateFlow<Long>`, bumped after every write
    - `badges: Flow<Map<String, String>>`
    - `suspend fun refresh(): LiveResult`
    - `suspend fun refreshIfStale(maxAge: Duration = STALE_AFTER): LiveResult?`, which returns null when nothing was fetched
    - `suspend fun fetchedAt(): Instant?`
    - Reads: `suspend fun news(): List<NewsItem>`, `playerNews(playerId)`, `status(playerId): LiveStatus?`, `notes(playerId): List<InjuryNote>` and `injuries(): List<LiveInjury>`
    - `companion val STALE_AFTER: Duration = 15 minutes`

- [ ] **Step 1: Write the failing tests**

```kotlin
// core/data/src/test/kotlin/dev/gridiron/core/data/PlayerDirectoryTest.kt
package dev.gridiron.core.data

import dev.gridiron.core.testing.JdbcQueryExecutor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager

class PlayerDirectoryTest {
    @TempDir
    lateinit var dir: File

    private fun fixture(withXref: Boolean): JdbcQueryExecutor {
        val file = File(dir, "stats-$withXref.db")
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE player (player_id TEXT, full_name TEXT, search_name TEXT, position TEXT, team TEXT)")
                st.executeUpdate("INSERT INTO player VALUES ('P1', 'Stat Guy', 'stat guy', 'WR', 'KC')")
                if (withXref) {
                    st.executeUpdate("CREATE TABLE player_xref (espn_id TEXT PRIMARY KEY, player_id TEXT NOT NULL, full_name TEXT NOT NULL, position TEXT, team TEXT)")
                    st.executeUpdate("INSERT INTO player_xref VALUES ('101', 'P1', 'Stat Guy', 'WR', 'KC'), ('102', 'P2', 'Rookie Noshow', 'TE', 'BUF')")
                }
            }
        }
        return JdbcQueryExecutor(file.path)
    }

    @Test
    fun `maps espn ids through player_xref`() = runTest {
        val players = PlayerDirectory(fixture(withXref = true))
        assertEquals(mapOf("101" to "P1", "102" to "P2"), players.playerIds(listOf("101", "102", "999")))
        assertEquals(emptyMap<String, String>(), players.playerIds(emptyList()))
    }

    @Test
    fun `a database without player_xref matches nothing instead of failing`() = runTest {
        assertEquals(emptyMap<String, String>(), PlayerDirectory(fixture(withXref = false)).playerIds(listOf("101")))
    }

    @Test
    fun `headers come from player, then from player_xref for players with no stats`() = runTest {
        val players = PlayerDirectory(fixture(withXref = true))
        assertEquals(PlayerHeader("P1", "Stat Guy", "WR", "KC"), players.header("P1"))
        assertEquals(PlayerHeader("P2", "Rookie Noshow", "TE", "BUF"), players.header("P2"))
        assertNull(players.header("P404"))
    }
}
```

```kotlin
// core/data/src/test/kotlin/dev/gridiron/core/data/live/LiveRepositoryTest.kt
package dev.gridiron.core.data.live

import dev.gridiron.core.data.PlayerDirectory
import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.ResultRow
import dev.gridiron.core.statquery.SqlQuery
import dev.gridiron.core.testing.JdbcQueryExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant

class LiveRepositoryTest {
    @TempDir
    lateinit var dir: File

    private var now = Instant.parse("2026-09-26T00:00:00Z")
    private val responses = mutableMapOf<String, () -> String>()
    private var calls = 0
    private val http = HttpGet { url ->
        calls++
        responses[url]?.invoke() ?: throw IOException("offline")
    }

    /** Swappable, like the app's reopenable executor: starts with no stats database at all. */
    private var stats: QueryExecutor = object : QueryExecutor {
        override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> = error("No stats yet")
    }
    private val players = PlayerDirectory(
        object : QueryExecutor {
            override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> = stats.query(query, map)
        },
    )
    private val db by lazy { LiveDb(File(dir, "live.db")) }
    private val live by lazy { LiveRepository(db, http, players) { now } }

    @AfterEach
    fun close() {
        db.close()
    }

    private fun recorded(name: String): String = checkNotNull(javaClass.getResource("/espn/$name")) { name }.readText()

    private fun serveRecorded() {
        responses[EspnParser.NEWS_URL] = { recorded("news.json") }
        responses[EspnParser.INJURIES_URL] = { recorded("injuries.json") }
    }

    /** Barkley (news) and Melton (injuries, Q) have app ids; nobody else does. */
    private fun statsWithXref(): QueryExecutor {
        val file = File(dir, "stats.db")
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE player_xref (espn_id TEXT PRIMARY KEY, player_id TEXT NOT NULL, full_name TEXT NOT NULL, position TEXT, team TEXT)")
                st.executeUpdate("INSERT INTO player_xref VALUES ('3929630', '00-0034844', 'Saquon Barkley', 'RB', 'PHI'), ('4698113', '00-0039900', 'Max Melton', 'CB', 'ARI')")
            }
        }
        return JdbcQueryExecutor(file.path)
    }

    @Test
    fun `refresh stores both feeds and links players through player_xref`() = runTest {
        stats = statsWithXref()
        serveRecorded()

        val result = live.refresh()

        assertTrue(result.ok)
        val news = live.news()
        assertEquals(3, news.size)
        assertEquals(listOf(NewsPlayer("Saquon Barkley", "00-0034844")), news[0].players)
        assertEquals(listOf(null, null, null), news[1].players.map { it.playerId })
        assertEquals(mapOf("00-0039900" to "Q"), live.badges.first())
        assertEquals("Melton (toe) was a limited participant in Thursday's practice.", live.status("00-0039900")?.shortComment)
        assertEquals(4, live.injuries().size)
        assertEquals(now, live.fetchedAt())
    }

    @Test
    fun `with ESPN down the last data and its time are kept`() = runTest {
        serveRecorded()
        live.refresh()
        val fetched = now
        responses.clear()
        now = now.plus(Duration.ofHours(1))

        val result = live.refresh()

        assertEquals("couldn't reach ESPN", result.newsError)
        assertEquals("couldn't reach ESPN", result.injuriesError)
        assertEquals(3, live.news().size)
        assertEquals(4, live.injuries().size)
        assertEquals(fetched, live.fetchedAt())
    }

    @Test
    fun `a changed news format fails news only, and injuries still update`() = runTest {
        responses[EspnParser.NEWS_URL] = { """{"headlines": []}""" }
        responses[EspnParser.INJURIES_URL] = { recorded("injuries.json") }

        val result = live.refresh()

        assertTrue(result.newsError.orEmpty().contains("ESPN changed its news format"))
        assertNull(result.injuriesError)
        assertEquals(4, live.injuries().size)
        assertNull(live.fetchedAt(), "news has never arrived, so there is no as-of time for both feeds")
    }

    @Test
    fun `refreshIfStale waits fifteen minutes between fetches`() = runTest {
        serveRecorded()
        assertNotNull(live.refreshIfStale())
        assertEquals(2, calls)

        now = now.plus(Duration.ofMinutes(10))
        assertNull(live.refreshIfStale())
        assertEquals(2, calls)

        now = now.plus(Duration.ofMinutes(6))
        assertNotNull(live.refreshIfStale())
        assertEquals(4, calls)
    }

    @Test
    fun `news fetched before any stats database gets linked once player_xref exists`() = runTest {
        serveRecorded()
        live.refresh()
        assertEquals(emptyList<NewsItem>(), live.playerNews("00-0034844"))

        stats = statsWithXref()
        responses[EspnParser.NEWS_URL] = { """{"articles": []}""" }
        live.refresh()

        assertEquals(listOf("50029598"), live.playerNews("00-0034844").map { it.id })
    }

    @Test
    fun `articles older than thirty days are dropped even while ESPN still lists them`() = runTest {
        serveRecorded()
        now = Instant.parse("2026-10-27T00:00:00Z")

        live.refresh()

        assertEquals(emptyList<NewsItem>(), live.news())
    }

    @Test
    fun `every write bumps changes`() = runTest {
        serveRecorded()
        val before = live.changes.value
        live.refresh()
        assertEquals(before + 1, live.changes.value)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:data:test --tests "*PlayerDirectoryTest*" --tests "*LiveRepositoryTest*"`
Expected: FAIL to compile: `Unresolved reference 'PlayerDirectory'`.

- [ ] **Step 3: Write `PlayerDirectory`**

```kotlin
// core/data/src/main/kotlin/dev/gridiron/core/data/PlayerDirectory.kt
package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.textOrNull
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.SqlQuery
import kotlinx.coroutines.CancellationException

/**
 * Players by id across both lists `stats.db` carries: `player` (players with
 * stats) and `player_xref` (everyone nflverse lists with an ESPN id).
 *
 * Every lookup is best effort. With no stats database yet, or an older one
 * without `player_xref`, it finds nothing rather than failing: live news and
 * injuries still show, just unlinked.
 */
public class PlayerDirectory(private val executor: QueryExecutor) {

    /** App player ids for [espnIds], keyed by ESPN id; ids with no match are left out. */
    public suspend fun playerIds(espnIds: Collection<String>): Map<String, String> {
        val ids = espnIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        return bestEffort(emptyMap()) {
            ids.chunked(CHUNK).flatMap { chunk ->
                executor.query(
                    SqlQuery(
                        "SELECT espn_id, player_id FROM player_xref WHERE espn_id IN (${chunk.joinToString(", ") { "?" }})",
                        chunk.map { Bind.Text(it) },
                    ),
                ) { it.text(0) to it.text(1) }
            }.toMap()
        }
    }

    /** Name, position and team for [playerId]: from `player` if they have stats, else from `player_xref`. */
    public suspend fun header(playerId: String): PlayerHeader? =
        bestEffort(null) { executor.playerHeaders(listOf(playerId))[playerId] }
            ?: bestEffort(null) {
                executor.query(
                    SqlQuery(
                        "SELECT player_id, full_name, position, team FROM player_xref WHERE player_id = ? LIMIT 1",
                        listOf(Bind.Text(playerId)),
                    ),
                ) { PlayerHeader(it.text(0), it.text(1), it.textOrNull(2), it.textOrNull(3)) }.firstOrNull()
            }

    private suspend fun <T> bestEffort(fallback: T, block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fallback
        }

    private companion object {
        /** Under SQLite's bind-variable limit with room to spare. */
        const val CHUNK = 500
    }
}
```

- [ ] **Step 4: Write `LiveRepository`**

```kotlin
// core/data/src/main/kotlin/dev/gridiron/core/data/live/LiveRepository.kt
package dev.gridiron.core.data.live

import dev.gridiron.core.data.PlayerDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Duration
import java.time.Instant

/** How a live refresh went, feed by feed; null means that feed updated. */
public data class LiveResult(val newsError: String?, val injuriesError: String?) {
    public val ok: Boolean get() = newsError == null && injuriesError == null

    /** One line for a toast. */
    public val message: String
        get() = when {
            ok -> "Injuries and news updated."
            newsError != null && injuriesError != null -> "Couldn't update injuries or news: $injuriesError."
            injuriesError != null -> "News updated; injuries didn't: $injuriesError."
            else -> "Injuries updated; news didn't: $newsError."
        }
}

/**
 * ESPN injuries and news, kept in [db] and linked to app players through
 * [players]. Each feed updates on its own: if one fails, the other still
 * lands, and the failed one keeps its last data and "fetched at" time.
 */
public class LiveRepository(
    private val db: LiveDb,
    private val http: HttpGet,
    private val players: PlayerDirectory,
    private val clock: () -> Instant = Instant::now,
) {
    private val fetching = Mutex()
    private val _changes = MutableStateFlow(0L)

    /** Bumped after every write; screens reload when it changes. */
    public val changes: StateFlow<Long> = _changes.asStateFlow()

    /** The Grid's injury badge (Q, D, O, IR, …) by player id. Empty if live.db can't be read. */
    public val badges: Flow<Map<String, String>> = _changes.map {
        try {
            db.read { c -> c.badges() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Fetches both feeds now. */
    public suspend fun refresh(): LiveResult = fetching.withLock { fetchAll() }

    /** Fetches both feeds unless both arrived within [maxAge]; null when nothing was fetched. */
    public suspend fun refreshIfStale(maxAge: Duration = STALE_AFTER): LiveResult? = fetching.withLock {
        val asOf = fetchedAt()
        if (asOf != null && Duration.between(asOf, clock()) < maxAge) null else fetchAll()
    }

    /** When both feeds had last arrived: the older of the two times, or null if either never has. */
    public suspend fun fetchedAt(): Instant? = db.read { c ->
        val times = listOf(NEWS_AT, INJURIES_AT).map { c.meta(it)?.toLongOrNull() }
        if (times.any { it == null }) null else Instant.ofEpochMilli(times.filterNotNull().min())
    }

    public suspend fun news(): List<NewsItem> = db.read { it.news(null) }

    public suspend fun playerNews(playerId: String): List<NewsItem> = db.read { it.news(playerId) }

    public suspend fun status(playerId: String): LiveStatus? = db.read { it.status(playerId) }

    public suspend fun notes(playerId: String): List<InjuryNote> = db.read { it.notes(playerId) }

    public suspend fun injuries(): List<LiveInjury> = db.read { it.injuries() }

    private suspend fun fetchAll(): LiveResult {
        val news = fetch(EspnParser.NEWS_URL, EspnParser::news)
        val injuries = fetch(EspnParser.INJURIES_URL, EspnParser::injuries)
        val now = clock()
        val articles = news.getOrNull()
        val report = injuries.getOrNull()
        val wanted = db.read { it.unlinkedEspnIds() } +
            articles.orEmpty().flatMap { a -> a.athletes.map { it.espnId } } +
            report.orEmpty().map { it.espnId }
        val ids = players.playerIds(wanted)
        db.write { c ->
            if (articles != null) {
                c.saveNews(articles, ids)
                c.setMeta(NEWS_AT, now.toEpochMilli().toString())
            }
            if (report != null) {
                c.saveInjuries(report, ids, now)
                c.setMeta(INJURIES_AT, now.toEpochMilli().toString())
            }
            c.relink(ids)
            c.prune(now)
        }
        _changes.value += 1
        return LiveResult(news.exceptionOrNull()?.let(::describe), injuries.exceptionOrNull()?.let(::describe))
    }

    private suspend fun <T> fetch(url: String, parse: (String) -> T): Result<T> =
        try {
            Result.success(parse(http.get(url)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private fun describe(e: Throwable): String = when (e) {
        is LiveFormatException -> e.message ?: "ESPN changed its format"
        is IOException -> "couldn't reach ESPN"
        else -> e.message ?: e::class.simpleName.orEmpty()
    }

    public companion object {
        /** Screens that show live data refetch it when it is older than this. */
        public val STALE_AFTER: Duration = Duration.ofMinutes(15)

        private const val NEWS_AT = "news_fetched_at"
        private const val INJURIES_AT = "injuries_fetched_at"
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:data:test`
Expected: BUILD SUCCESSFUL, including the 3 `PlayerDirectoryTest` and 7 `LiveRepositoryTest` tests.

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/dev/gridiron/core/data/PlayerDirectory.kt core/data/src/test/kotlin/dev/gridiron/core/data/PlayerDirectoryTest.kt core/data/src/main/kotlin/dev/gridiron/core/data/live/LiveRepository.kt core/data/src/test/kotlin/dev/gridiron/core/data/live/LiveRepositoryTest.kt
git commit -m "live: fetch ESPN feeds independently, link players, prune at thirty days"
```

---

### Task 7: `RefreshCoordinator`: build, swap, then live data

This replaces `StatsDbInstaller`'s download-and-restart. It runs on an application-scope coroutine, so it keeps going when the user leaves the screen.

**One refresh**
1. Run Plan 1's pipeline into `stats.db.new`, passing the current `stats.db` as `previous` so unchanged seasons are copied instead of rebuilt.
2. Swap it into place through `ReopenableQueryExecutor.swap`: an atomic rename, then a version bump.
3. Write the `stats.db.built-here` marker.
4. Refresh live data, whether or not the stats build succeeded (Review Focus 4).

**Failure** deletes `stats.db.new` and leaves `stats.db` exactly as it was (Review Focus 1). The message says why:
- the failed validation check;
- the upstream column that changed;
- no connection;
- a full disk.

**Legacy data:** a `stats.db` without the marker came from an older app version (the APK or the repo's `data` release). The screens use that flag to prompt a refresh.

**Files:**
- Create: `app/src/main/kotlin/dev/gridiron/app/RefreshText.kt`
- Create: `app/src/main/kotlin/dev/gridiron/app/RefreshCoordinator.kt`
- Test: `app/src/test/kotlin/dev/gridiron/app/RefreshTextTest.kt`
- Test: `app/src/test/kotlin/dev/gridiron/app/RefreshCoordinatorTest.kt`

**Interfaces:**
- Consumes: `ReopenableQueryExecutor` (Task 1); `LiveResult` (Task 6); `IngestProgress`, `IngestReport`, `ValidationException` and `MissingColumnsException` (Plan 1).
- Produces (package `dev.gridiron.app`):
  - `sealed interface RefreshState { data object Idle; data class Running(text: String); data class Finished(message: String, ok: Boolean) }`
  - `interface Refresher`, with:
    - `state: StateFlow<RefreshState>`
    - `hasStats: StateFlow<Boolean>`
    - `legacyData: StateFlow<Boolean>`
    - `fun refresh(): Boolean`, false when one is already running
    - `fun acknowledge()`
  - `fun interface StatsBuilder { suspend fun build(seasons: List<Int>, previous: File?, out: File, onProgress: (IngestProgress) -> Unit): IngestReport }`
  - `class RefreshCoordinator(dir: File, executor: ReopenableQueryExecutor, stats: StatsBuilder, seasons: suspend () -> List<Int>, scope: CoroutineScope, live: (suspend () -> LiveResult)? = null, millis: () -> Long = …) : Refresher`, with `companion const val DB_NAME = "stats.db"`
  - `internal` wording functions: `progressText(IngestProgress): String`, `describeFailure(e: Throwable, kept: Boolean): String`, `summary(IngestReport, elapsedMs: Long): String` and `formatDuration(ms: Long): String`

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/kotlin/dev/gridiron/app/RefreshTextTest.kt
package dev.gridiron.app

import dev.gridiron.core.ingest.IngestProgress
import dev.gridiron.core.ingest.IngestReport
import dev.gridiron.core.ingest.ValidationException
import dev.gridiron.core.ingest.csv.MissingColumnsException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class RefreshTextTest {
    @Test
    fun progressLines() {
        assertEquals("Checking for new stats…", progressText(IngestProgress.Checking(null)))
        assertEquals("Checking 2025…", progressText(IngestProgress.Checking(2025)))
        assertEquals(
            "Downloading 2026 play-by-play 12/19 MB",
            progressText(IngestProgress.Downloading(2026, "play-by-play", 12_400_000, 19_000_000)),
        )
        assertEquals(
            "Downloading player list 1.3/2.5 MB",
            progressText(IngestProgress.Downloading(null, "player list", 1_300_000, 2_500_000)),
        )
        assertEquals(
            "Downloading 2026 snap counts 0.4 MB",
            progressText(IngestProgress.Downloading(2026, "snap counts", 400_000, -1)),
        )
        assertEquals("Crunching 2026…", progressText(IngestProgress.Crunching(2026)))
        assertEquals("Checking the new stats…", progressText(IngestProgress.Validating))
    }

    @Test
    fun failureMessagesNameTheCause() {
        assertEquals(
            "The new stats failed a check (range: target_share 1.4). Your current stats are kept.",
            describeFailure(ValidationException(listOf("range: target_share 1.4")), kept = true),
        )
        assertEquals(
            "nflverse changed column(s) epa in play_by_play_2026.csv.gz. Your current stats are kept.",
            describeFailure(MissingColumnsException("play_by_play_2026.csv.gz", listOf("epa")), kept = true),
        )
        assertEquals(
            "No connection. Your current stats are kept.",
            describeFailure(IOException("fetch failed", UnknownHostException("github.com")), kept = true),
        )
        assertEquals(
            "Not enough free storage to build stats. Your current stats are kept.",
            describeFailure(IOException("write failed: ENOSPC (No space left on device)"), kept = true),
        )
        assertEquals(
            "Refresh failed: HTTP 502 from github.com. Your current stats are kept.",
            describeFailure(IOException("HTTP 502 from github.com"), kept = true),
        )
        assertEquals("No connection.", describeFailure(UnknownHostException("github.com"), kept = false))
    }

    @Test
    fun summariesListTheSeasonsAndWhatWasSkipped() {
        val report = IngestReport(listOf(2026), listOf(2024, 2025), mapOf(2027 to "play-by-play isn't published yet"), emptyList(), 1L)
        assertEquals(
            "Stats updated for 2024, 2025, 2026 in 1 min 5 s. 2027 skipped: play-by-play isn't published yet.",
            summary(report, 65_000),
        )
        assertEquals("42 s", formatDuration(41_600))
        assertEquals("0 s", formatDuration(0))
    }
}
```

```kotlin
// app/src/test/kotlin/dev/gridiron/app/RefreshCoordinatorTest.kt
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*RefreshTextTest*" --tests "*RefreshCoordinatorTest*"`
Expected: FAIL to compile: `Unresolved reference 'progressText'` and `Unresolved reference 'RefreshCoordinator'`.

- [ ] **Step 3: Write the wording**

```kotlin
// app/src/main/kotlin/dev/gridiron/app/RefreshText.kt
package dev.gridiron.app

import dev.gridiron.core.ingest.IngestProgress
import dev.gridiron.core.ingest.IngestReport
import dev.gridiron.core.ingest.ValidationException
import dev.gridiron.core.ingest.csv.MissingColumnsException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

/** The one line a running refresh shows, e.g. "Downloading 2026 play-by-play 12/19 MB". */
internal fun progressText(p: IngestProgress): String = when (p) {
    is IngestProgress.Checking -> if (p.season == null) "Checking for new stats…" else "Checking ${p.season}…"
    is IngestProgress.Downloading -> buildString {
        append("Downloading ")
        p.season?.let { append(it).append(' ') }
        append(p.what).append(' ')
        // Whole megabytes for big files; one decimal for small ones, so they don't read "0/0".
        val whole = maxOf(p.bytes, p.total) >= 10_000_000
        append(megabytes(p.bytes, whole))
        if (p.total > 0) append('/').append(megabytes(p.total, whole))
        append(" MB")
    }
    is IngestProgress.Crunching -> "Crunching ${p.season}…"
    IngestProgress.Validating -> "Checking the new stats…"
}

private fun megabytes(bytes: Long, whole: Boolean): String =
    if (whole) (bytes / 1_000_000).toString() else String.format(Locale.US, "%.1f", bytes / 1e6)

/** Why a stats build failed, in words; [kept] adds that the old stats stay. */
internal fun describeFailure(e: Throwable, kept: Boolean): String {
    val what = when {
        e is ValidationException -> "The new stats failed a check (${e.problems.first()})."
        e is MissingColumnsException -> "${e.message}."
        e.causes().any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException || it is SocketTimeoutException } ->
            "No connection."
        e.causes().any { t -> t.message.orEmpty().let { "No space left" in it || "ENOSPC" in it || "disk is full" in it } } ->
            "Not enough free storage to build stats."
        else -> "Refresh failed: ${e.message ?: e::class.simpleName}."
    }
    return if (kept) "$what Your current stats are kept." else what
}

private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

/** "Stats updated for 2024, 2025, 2026 in 1 min 5 s." plus any skipped seasons. */
internal fun summary(report: IngestReport, elapsedMs: Long): String = buildString {
    append("Stats updated for ").append((report.built + report.reused).sorted().joinToString(", "))
    append(" in ").append(formatDuration(elapsedMs)).append('.')
    for ((season, why) in report.skipped.toSortedMap()) append(' ').append(season).append(" skipped: ").append(why).append('.')
}

internal fun formatDuration(ms: Long): String {
    val s = (ms + 500) / 1000
    return if (s < 60) "$s s" else "${s / 60} min ${s % 60} s"
}
```

- [ ] **Step 4: Write the coordinator**

```kotlin
// app/src/main/kotlin/dev/gridiron/app/RefreshCoordinator.kt
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
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*RefreshTextTest*" --tests "*RefreshCoordinatorTest*"`
Expected: PASS, 12 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/gridiron/app/RefreshText.kt app/src/main/kotlin/dev/gridiron/app/RefreshCoordinator.kt app/src/test/kotlin/dev/gridiron/app/RefreshTextTest.kt app/src/test/kotlin/dev/gridiron/app/RefreshCoordinatorTest.kt
git commit -m "app: build stats on the phone, swap them in, then refresh live data"
```

---

### Task 8: Wire the app: Load stats, the refresh bar, Settings, and no bundled database

This task connects Tasks 1–7 and removes the old delivery path.

**Wiring**
- `GridironApplication` builds the reopenable executor over `noBackupFilesDir/stats.db`, the `SettingsRepository`, `LiveRepository` and `RefreshCoordinator`.
- `StatsRepository` gets the executor's `version` as its `dataVersion`.

**What the user sees** (`GridironNavHost`):
- **No `stats.db` yet** (fresh install): a Load stats screen with the progress line, a Retry after a failure, and a way into the seasons checklist.
- **An existing install's database without the built-here marker:** kept and shown, with a one-time prompt per launch to refresh.
- **While a refresh runs:** a progress bar under the current screen.
- **When it finishes:** a toast with the result. No restart.

**Menu:** "Settings" is added. "Projection accuracy" and "Time a stats build" are removed.

**Removed:** `StatsDbInstaller` (the asset copy and the repo download) and the APK's `BundleStatsDb` task. Building the APK no longer needs `GRIDIRON_STATS_DB`; tests still do.

**Files:**
- Modify: `app/src/main/kotlin/dev/gridiron/app/GridironApplication.kt` (rewrite)
- Modify: `app/src/main/kotlin/dev/gridiron/app/GridironNavHost.kt` (rewrite)
- Modify: `app/src/main/kotlin/dev/gridiron/app/NavKeys.kt`
- Create: `app/src/main/kotlin/dev/gridiron/app/LoadStatsScreen.kt`
- Create: `app/src/main/kotlin/dev/gridiron/app/SettingsScreen.kt`
- Delete: `app/src/main/kotlin/dev/gridiron/app/StatsDbInstaller.kt`
- Modify: `app/build.gradle.kts`
- Test: `app/src/test/kotlin/dev/gridiron/app/FakeRefresher.kt`
- Test: `app/src/test/kotlin/dev/gridiron/app/LoadStatsScreenTest.kt`
- Test: `app/src/test/kotlin/dev/gridiron/app/SettingsScreenTest.kt`
- Test: `app/src/test/kotlin/dev/gridiron/app/NavigationTest.kt`

**Interfaces:**
- Consumes:
  - `ReopenableQueryExecutor` (Task 1)
  - `StatsRepository(…, dataVersion)` (Task 2)
  - `SettingsRepository`, `SeasonChoice` (Task 3)
  - `UrlConnectionHttpGet` (Task 4)
  - `LiveDb` (Task 5)
  - `PlayerDirectory`, `LiveRepository` (Task 6)
  - `RefreshCoordinator`, `Refresher`, `RefreshState`, `StatsBuilder` (Task 7)
  - `IngestPipeline`, `HttpFetcher` and `currentSeason` (Plan 1)
- Produces:
  - `Deps`: the old `refresh` and `benchmark` fields are removed. It adds `players: PlayerDirectory? = null`, `live: LiveRepository? = null`, `settings: SettingsRepository? = null` and `refresher: Refresher? = null`. All default to null, so tests that build `Deps` by name still compile.
  - `@Serializable data object SettingsKey : NavKey`
  - `@Composable fun LoadStatsScreen(state: RefreshState, onLoad: () -> Unit, onSettings: (() -> Unit)?)`
  - `@Composable fun SettingsScreen(settings: SettingsRepository, onBack: () -> Unit)`
  - Test helper `FakeRefresher(hasStats: Boolean = true, legacy: Boolean = false)`, with `MutableStateFlow` `state`, `hasStats` and `legacyData`, plus a `refreshes` count.

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/kotlin/dev/gridiron/app/FakeRefresher.kt
package dev.gridiron.app

import kotlinx.coroutines.flow.MutableStateFlow

/** A [Refresher] the test drives by hand. */
class FakeRefresher(hasStats: Boolean = true, legacy: Boolean = false) : Refresher {
    override val state = MutableStateFlow<RefreshState>(RefreshState.Idle)
    override val hasStats = MutableStateFlow(hasStats)
    override val legacyData = MutableStateFlow(legacy)
    var refreshes = 0

    override fun refresh(): Boolean {
        refreshes++
        return true
    }

    override fun acknowledge() {
        if (state.value is RefreshState.Finished) state.value = RefreshState.Idle
    }
}
```

```kotlin
// app/src/test/kotlin/dev/gridiron/app/LoadStatsScreenTest.kt
package dev.gridiron.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.gridiron.core.designsystem.GridironTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class LoadStatsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun idleOffersToLoadAndToChooseSeasons() {
        var loads = 0
        var settings = 0
        compose.setContent { GridironTheme { LoadStatsScreen(RefreshState.Idle, onLoad = { loads++ }, onSettings = { settings++ }) } }

        compose.onNodeWithText("Load stats").performClick()
        compose.onNodeWithText("Choose seasons").performClick()

        assertEquals(1, loads)
        assertEquals(1, settings)
    }

    @Test
    fun runningShowsProgressInsteadOfTheButton() {
        compose.setContent {
            GridironTheme { LoadStatsScreen(RefreshState.Running("Downloading 2026 play-by-play 12/19 MB"), onLoad = {}, onSettings = {}) }
        }
        compose.onNodeWithText("Downloading 2026 play-by-play 12/19 MB").assertExists()
        compose.onNodeWithText("Load stats").assertDoesNotExist()
    }

    @Test
    fun aFailureSaysWhyAndOffersRetry() {
        var loads = 0
        compose.setContent {
            GridironTheme { LoadStatsScreen(RefreshState.Finished("No connection.", ok = false), onLoad = { loads++ }, onSettings = null) }
        }
        compose.onNodeWithText("No connection.").assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, loads)
    }
}
```

```kotlin
// app/src/test/kotlin/dev/gridiron/app/SettingsScreenTest.kt
package dev.gridiron.app

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.gridiron.core.data.SettingsRepository
import dev.gridiron.core.datastore.SeasonChoice
import dev.gridiron.core.datastore.UserPrefs
import dev.gridiron.core.designsystem.GridironTheme
import dev.gridiron.core.testing.FakePrefsSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(prefs: FakePrefsSource) {
        compose.setContent { GridironTheme { SettingsScreen(SettingsRepository(prefs) { 2026 }, onBack = {}) } }
    }

    @Test
    fun theDefaultSeasonsAreChecked() {
        show(FakePrefsSource())
        compose.onNodeWithTag("season:2026").assertIsOn()
        compose.onNodeWithTag("season:2024").assertIsOn()
        compose.onNodeWithTag("season:2023").assertIsOff()
    }

    @Test
    fun checkingASeasonSavesIt() {
        val prefs = FakePrefsSource()
        show(prefs)

        compose.onNodeWithTag("season:2023").performClick()
        compose.waitForIdle()

        assertEquals(SeasonChoice(listOf(2023, 2024, 2025, 2026), 2026), prefs.current.seasons)
        compose.onNodeWithTag("season:2023").assertIsOn()
    }

    @Test
    fun theLastSeasonCantBeUnchecked() {
        val prefs = FakePrefsSource(UserPrefs.DEFAULT.copy(seasons = SeasonChoice(listOf(2026), 2026)))
        show(prefs)

        compose.onNodeWithTag("season:2026").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("season:2026").assertIsOn()
        assertEquals("Keep at least one season", ShadowToast.getTextOfLatestToast())
    }
}
```

Append to `NavigationTest` (inside the class). Add these imports: `androidx.compose.ui.test.onNodeWithTag` (already there), `org.junit.Assert.assertEquals`.

```kotlin
    @Test
    fun aFreshInstallShowsLoadStatsUntilTheFirstBuildLands() {
        val refresher = FakeRefresher(hasStats = false)
        compose.setContent { GridironTheme { GridironNavHost(deps.copy(refresher = refresher)) } }
        settle()

        compose.onNodeWithText("Load stats").performClick()
        assertEquals(1, refresher.refreshes)

        refresher.hasStats.value = true
        settle()
        compose.onNodeWithTag("grid").assertExists()
    }

    @Test
    fun statsFromAnOlderAppPromptARefresh() {
        val refresher = FakeRefresher(hasStats = true, legacy = true)
        compose.setContent { GridironTheme { GridironNavHost(deps.copy(refresher = refresher)) } }
        settle()

        compose.onNodeWithText("Refresh now").performClick()
        settle()

        assertEquals(1, refresher.refreshes)
        compose.onNodeWithText("Refresh now").assertDoesNotExist()
    }

    @Test
    fun aRunningRefreshShowsItsProgressUnderTheGrid() {
        val refresher = FakeRefresher()
        refresher.state.value = RefreshState.Running("Crunching 2026…")
        compose.setContent { GridironTheme { GridironNavHost(deps.copy(refresher = refresher)) } }
        settle()

        compose.onNodeWithText("Crunching 2026…").assertExists()
        compose.onNodeWithTag("grid").assertExists()
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :app:testDebugUnitTest`
Expected: FAIL to compile: `Unresolved reference 'LoadStatsScreen'`, `'SettingsScreen'` and `No parameter with name 'refresher'`.

- [ ] **Step 3: Add the Settings key**

In `app/src/main/kotlin/dev/gridiron/app/NavKeys.kt`, add after `DefenseKey`:

```kotlin
@Serializable data object SettingsKey : NavKey
```

- [ ] **Step 4: Write the Load stats and Settings screens**

```kotlin
// app/src/main/kotlin/dev/gridiron/app/LoadStatsScreen.kt
package dev.gridiron.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** A fresh install: nothing to show until the phone has built its first stats. */
@Composable
fun LoadStatsScreen(state: RefreshState, onLoad: () -> Unit, onSettings: (() -> Unit)?) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Gridiron", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "Stats are built on this phone from nflverse's public data. The first load downloads " +
                    "about 25 MB per season and can take a few minutes. It keeps going if you leave the app.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            when (state) {
                is RefreshState.Running -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(state.text, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
                is RefreshState.Finished -> {
                    if (!state.ok) {
                        Text(state.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                    }
                    Button(onClick = onLoad) { Text(if (state.ok) "Load stats" else "Retry") }
                }
                RefreshState.Idle -> Button(onClick = onLoad) { Text("Load stats") }
            }
            if (onSettings != null && state !is RefreshState.Running) {
                TextButton(onClick = onSettings) { Text("Choose seasons") }
            }
        }
    }
}
```

```kotlin
// app/src/main/kotlin/dev/gridiron/app/SettingsScreen.kt
package dev.gridiron.app

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.SettingsRepository
import kotlinx.coroutines.launch

/** The seasons the phone builds, newest first. Changes apply on the next refresh. */
@Composable
fun SettingsScreen(settings: SettingsRepository, onBack: () -> Unit) {
    val selected by settings.seasons.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(
                "Seasons",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Stats are built for the checked seasons on the next refresh. Each season is about a 25 MB download.",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val chosen = selected ?: return@Column
            LazyColumn {
                items(settings.choices.asReversed()) { season ->
                    val checked = season in chosen
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = checked, role = Role.Checkbox) { now ->
                                scope.launch {
                                    if (!settings.setSelected(season, now)) {
                                        Toast.makeText(context, "Keep at least one season", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag("season:$season"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(season.toString(), Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 5: Rewrite the application object**

Replace `app/src/main/kotlin/dev/gridiron/app/GridironApplication.kt` with:

```kotlin
package dev.gridiron.app

import android.app.Application
import dev.gridiron.core.data.AccuracyRepository
import dev.gridiron.core.data.CompareRepository
import dev.gridiron.core.data.CompareTrayRepository
import dev.gridiron.core.data.PlayerDirectory
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.SettingsRepository
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.core.data.TeamsRepository
import dev.gridiron.core.data.live.LiveDb
import dev.gridiron.core.data.live.LiveRepository
import dev.gridiron.core.data.live.UrlConnectionHttpGet
import dev.gridiron.core.database.ReopenableQueryExecutor
import dev.gridiron.core.database.SqliteQueryExecutor
import dev.gridiron.core.datastore.UserPrefsStore
import dev.gridiron.core.ingest.HttpFetcher
import dev.gridiron.core.ingest.IngestPipeline
import dev.gridiron.core.ingest.currentSeason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * The app's object graph, by hand. Stats are built on the phone into
 * `noBackupFilesDir/stats.db` (an existing install's database stays until the
 * first build replaces it); ESPN's injuries and news live beside it in
 * `live.db`.
 */
class GridironApplication : Application() {
    // Outlives every screen: preferences are written on it and refreshes run on it.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val statsFile by lazy { File(noBackupFilesDir, RefreshCoordinator.DB_NAME) }

    private val executor by lazy {
        ReopenableQueryExecutor {
            check(statsFile.isFile) { "No stats yet: load them first" }
            SqliteQueryExecutor.openReadOnly(statsFile.path)
        }
    }
    private val prefs by lazy { UserPrefsStore.create(File(filesDir, "user_prefs.json"), appScope) }
    private val settings by lazy { SettingsRepository(prefs) { currentSeason() } }
    private val players by lazy { PlayerDirectory(executor) }
    private val live by lazy { LiveRepository(LiveDb(File(noBackupFilesDir, "live.db")), UrlConnectionHttpGet(), players) }

    private val refresher by lazy {
        RefreshCoordinator(
            dir = noBackupFilesDir,
            executor = executor,
            stats = { seasons, previous, out, onProgress ->
                IngestPipeline(HttpFetcher(), File(noBackupFilesDir, "ingest-work"), File(noBackupFilesDir, "players.csv.gz"))
                    .build(seasons, previous, out, onProgress)
            },
            seasons = { settings.seasons.first() },
            scope = appScope,
            live = { live.refresh() },
        )
    }

    val deps: Deps by lazy {
        Deps(
            StatsRepository(executor, dataVersion = executor.version),
            CompareRepository(executor),
            ScoringRepository(prefs),
            CompareTrayRepository(prefs),
            ProjectionsRepository(executor),
            AccuracyRepository(executor),
            TeamsRepository(executor),
            players = players,
            live = live,
            settings = settings,
            refresher = refresher,
        )
    }
}
```

- [ ] **Step 6: Rewrite the nav host**

Replace `app/src/main/kotlin/dev/gridiron/app/GridironNavHost.kt` with:

```kotlin
package dev.gridiron.app

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.gridiron.core.data.AccuracyRepository
import dev.gridiron.core.data.CompareRepository
import dev.gridiron.core.data.CompareTrayRepository
import dev.gridiron.core.data.PlayerDirectory
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.SettingsRepository
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.core.data.TeamsRepository
import dev.gridiron.core.data.live.LiveRepository
import dev.gridiron.feature.compare.CompareRoute
import dev.gridiron.feature.players.GridRoute
import dev.gridiron.feature.projections.AccuracyRoute
import dev.gridiron.feature.projections.ProjectionsRoute
import dev.gridiron.feature.scoring.ScoringEditRoute
import dev.gridiron.feature.scoring.ScoringListRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the screens need, built by [GridironApplication] or by a test. */
data class Deps(
    val stats: StatsRepository,
    val compare: CompareRepository,
    val scoring: ScoringRepository,
    val tray: CompareTrayRepository,
    val projections: ProjectionsRepository,
    val accuracy: AccuracyRepository,
    val teams: TeamsRepository,
    /** Player names by id, including players with no stats; null where a test doesn't need them. */
    val players: PlayerDirectory? = null,
    /** ESPN injuries and news. Null in tests: its SQLite driver can't load under Robolectric. */
    val live: LiveRepository? = null,
    val settings: SettingsRepository? = null,
    /** Builds stats on the phone. Null in tests, which read a prebuilt database. */
    val refresher: Refresher? = null,
)

/**
 * Pushes [key] unless it is already on top, so a double tap (Compare, Edit
 * profiles, a profile row) opens one screen, not two stacked copies that Back
 * would then have to peel off one by one.
 */
internal fun <T> MutableList<T>.push(key: T) {
    if (lastOrNull() != key) add(key)
}

// Stand-ins when there is no refresher (tests): stats exist, nothing is running.
private val STATS_PRESENT: StateFlow<Boolean> = MutableStateFlow(true)
private val NOT_LEGACY: StateFlow<Boolean> = MutableStateFlow(false)
private val IDLE: StateFlow<RefreshState> = MutableStateFlow(RefreshState.Idle)

@Composable
fun GridironNavHost(deps: Deps) {
    val hasStats by (deps.refresher?.hasStats ?: STATS_PRESENT).collectAsState()
    val refreshState by (deps.refresher?.state ?: IDLE).collectAsState()
    if (hasStats) StatsApp(deps, refreshState) else FirstLoad(deps, refreshState)
}

/** A fresh install: the Load stats screen, and the seasons checklist behind it. */
@Composable
private fun FirstLoad(deps: Deps, state: RefreshState) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    val settings = deps.settings
    if (settingsOpen && settings != null) {
        SettingsScreen(settings, onBack = { settingsOpen = false })
    } else {
        LoadStatsScreen(
            state,
            onLoad = { deps.refresher?.refresh() },
            onSettings = if (settings != null) ({ settingsOpen = true }) else null,
        )
    }
}

@Composable
private fun StatsApp(deps: Deps, refreshState: RefreshState) {
    val refresher = deps.refresher
    val context = LocalContext.current
    // Each finished refresh is announced once, then cleared.
    LaunchedEffect(refreshState) {
        val finished = refreshState as? RefreshState.Finished ?: return@LaunchedEffect
        Toast.makeText(context, finished.message, Toast.LENGTH_LONG).show()
        refresher?.acknowledge()
    }
    val refresh: () -> Unit = {
        if (refresher?.refresh() == false) Toast.makeText(context, "A refresh is already running", Toast.LENGTH_SHORT).show()
    }
    LegacyPrompt(refresher, refreshState, refresh)

    val backStack = rememberNavBackStack(GridKey)
    val back: () -> Unit = { backStack.removeLastOrNull() }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            NavDisplay(
                backStack = backStack,
                onBack = back,
                // Each destination gets its own saved state and ViewModelStore, so
                // leaving Compare clears its view model and returning rebuilds it.
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                entryProvider = entryProvider {
                    entry<GridKey> {
                        GridRoute(
                            deps.stats, deps.scoring, deps.tray,
                            onCompare = { backStack.push(CompareKey) },
                            onEditProfiles = { backStack.push(ScoringListKey) },
                            onPlayer = { id, season, week -> backStack.push(ProjectionsKey(id, season, week)) },
                            menu = buildList<Pair<String, (Int) -> Unit>> {
                                add("Injury report" to { s: Int -> backStack.push(InjuriesKey(s)) })
                                add("Team defense" to { s: Int -> backStack.push(DefenseKey(s)) })
                                if (deps.settings != null) add("Settings" to { _: Int -> backStack.push(SettingsKey) })
                                if (refresher != null) add("Refresh stats" to { _: Int -> refresh() })
                            },
                        )
                    }
                    entry<CompareKey> {
                        CompareRoute(deps.stats, deps.compare, deps.scoring, deps.tray, onBack = back, onEditProfiles = { backStack.push(ScoringListKey) })
                    }
                    entry<ScoringListKey> {
                        ScoringListRoute(deps.scoring, onEdit = { backStack.push(ScoringEditKey(it)) }, onBack = back)
                    }
                    entry<ScoringEditKey> { key -> ScoringEditRoute(key.profileId, deps.scoring, onDone = back) }
                    // Unreachable until the on-device projections follow-up: no menu item or row tap leads here.
                    entry<ProjectionsKey> { key -> ProjectionsRoute(key.playerId, key.season, key.week, deps.projections, onBack = back) }
                    entry<AccuracyKey> { key -> AccuracyRoute(key.season, deps.accuracy, onBack = back) }
                    entry<InjuriesKey> { key -> InjuriesScreen(key.season, deps.teams, onBack = back) }
                    entry<DefenseKey> { key -> DefenseScreen(key.season, deps.teams, onBack = back) }
                    entry<SettingsKey> { deps.settings?.let { SettingsScreen(it, onBack = back) } }
                },
            )
        }
        (refreshState as? RefreshState.Running)?.let { RefreshBar(it.text) }
    }
}

/** Stats that came with an older app version: offer, once per launch, to build fresh ones here. */
@Composable
private fun LegacyPrompt(refresher: Refresher?, state: RefreshState, refresh: () -> Unit) {
    val legacy by (refresher?.legacyData ?: NOT_LEGACY).collectAsState()
    var dismissed by rememberSaveable { mutableStateOf(false) }
    if (!legacy || dismissed || state is RefreshState.Running) return
    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text("Stats now build on your phone") },
        text = {
            Text(
                "These stats came with an older version of the app. Refresh to build them from nflverse " +
                    "on this phone, about a minute per season. The current stats stay until then.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                dismissed = true
                refresh()
            }) { Text("Refresh now") }
        },
        dismissButton = { TextButton(onClick = { dismissed = true }) { Text("Later") } },
    )
}

/** A running refresh's progress, under whatever screen is open. */
@Composable
private fun RefreshBar(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(text, style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}
```

- [ ] **Step 7: Delete the installer and the APK bundling**

Delete `app/src/main/kotlin/dev/gridiron/app/StatsDbInstaller.kt`.

In `app/build.gradle.kts`, delete everything after the closing brace of the `dependencies { ... }` block. That is:
- the `/** Bundles the ETL-built stats database ... */` KDoc and the `abstract class BundleStatsDb` class;
- `val statsDbPath`;
- `val bundleStatsDb`;
- the `androidComponents { ... }` block.

Also bump the app's version, since this changes how data arrives. In the same file, set `versionCode = 4` and `versionName = "0.4.0"`.

- [ ] **Step 8: Run the tests and build the APK without a database**

Run:

```bash
GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :app:testDebugUnitTest
env -u GRIDIRON_STATS_DB ./gradlew :app:assembleRelease
unzip -l app/build/outputs/apk/release/app-release.apk | grep -c 'assets/stats.db' || true
```

Expected:
- All app tests pass, the new ones included.
- The APK builds with no database present.
- The `grep -c` prints `0`.

- [ ] **Step 9: Commit**

```bash
git add app
git commit -m "app: build stats on the phone; Load stats, refresh bar, seasons setting; no bundled database"
```

---

### Task 9: News, the Player page, and the live Injury report

This task adds three screens, each split into a stateless screen and a small route that loads from the repositories. The split exists because Robolectric can't open `live.db`, so tests drive the screens with plain data.

**News** (☰ → News)
- ESPN headlines, newest first. Tapping one opens the article in the browser.
- A chip for each tagged player the app knows opens the Player page. Unmatched names aren't shown, but their articles are.

**Player page** (a Grid row tap, a news chip, or an injury line)
- Name, position and team, the current ESPN status and comments, the injury-note timeline, and tagged news.
- It replaces the projection screen as the Grid row tap. The projection and accuracy entries stay registered but become unreachable until the projections follow-up.

**Injury report** (☰ → Injury report)
- For the current season: ESPN's live list grouped by team. Each player shows their latest official nflverse practice participation when there is one ("Practice: Limited · Wk 3").
- For a past season: the official list as today.

All three auto-fetch live data older than 15 minutes, and show an "as of" line that says when the data is older because ESPN couldn't be reached.

**Files:**
- Modify: `core/data/src/main/kotlin/dev/gridiron/core/data/TeamsRepository.kt` (`InjuryRow.playerId`)
- Test: `core/data/src/test/kotlin/dev/gridiron/core/data/TeamsRepositoryTest.kt`
- Create: `app/src/main/kotlin/dev/gridiron/app/LiveFormat.kt`
- Test: `app/src/test/kotlin/dev/gridiron/app/LiveFormatTest.kt`
- Create: `app/src/main/kotlin/dev/gridiron/app/NewsScreen.kt`
- Create: `app/src/main/kotlin/dev/gridiron/app/PlayerScreen.kt`
- Modify: `app/src/main/kotlin/dev/gridiron/app/TeamScreens.kt`
- Modify: `app/src/main/kotlin/dev/gridiron/app/NavKeys.kt`
- Modify: `app/src/main/kotlin/dev/gridiron/app/GridironNavHost.kt`
- Test: `app/src/test/kotlin/dev/gridiron/app/LiveScreensTest.kt`
- Test: `app/src/test/kotlin/dev/gridiron/app/NavigationTest.kt`

**Interfaces:**
- Consumes: `LiveRepository`, `NewsItem`, `NewsPlayer`, `LiveStatus`, `InjuryNote`, `LiveInjury` and `PlayerDirectory` (Tasks 5–6); `Deps` and the nav host (Task 8); `currentSeason` (Plan 1).
- Produces:
  - `InjuryRow(playerId: String, name, team, position, week, status, injury, practice)`. `playerId` is a new first field.
  - `@Serializable data object NewsKey`, `@Serializable data class PlayerKey(val playerId: String)`
  - `fun formatWhen(instant: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String`, e.g. "Sep 25, 7:01 PM"
  - `data class InjuryLine(injury: LiveInjury, practice: String?)`, `data class InjuryGroup(team: String, lines: List<InjuryLine>)` and `fun injuryReport(live: List<LiveInjury>, official: List<InjuryRow>): List<InjuryGroup>`
  - Screens:
    - `NewsScreen(items: List<NewsItem>?, asOf: Instant?, error: String?, onBack, onOpen: (String) -> Unit, onPlayer: (String) -> Unit)`
    - `data class PlayerPage(header: PlayerHeader?, status: LiveStatus?, notes: List<InjuryNote>, news: List<NewsItem>, asOf: Instant?)`
    - `PlayerScreen(playerId: String, page: PlayerPage?, liveAvailable: Boolean, onBack, onOpen: (String) -> Unit)`
    - `LiveInjuriesScreen(groups: List<InjuryGroup>?, asOf: Instant?, error: String?, onBack, onPlayer: (String) -> Unit)`
  - Routes: `NewsRoute(live, onBack, onPlayer)`, `PlayerRoute(playerId, players: PlayerDirectory?, live: LiveRepository?, onBack)` and `InjuriesRoute(season, currentSeason, teams, live: LiveRepository?, onBack, onPlayer)`
  - Test tag `playerName` on the Player page title.

- [ ] **Step 1: Write the failing tests**

In `core/data/src/test/kotlin/dev/gridiron/core/data/TeamsRepositoryTest.kt`, in `` `injuries keep each player's latest report` ``, add after `assertEquals("Out", rows.single().status)`:

```kotlin
        assertEquals("p1", rows.single().playerId)
```

```kotlin
// app/src/test/kotlin/dev/gridiron/app/LiveFormatTest.kt
package dev.gridiron.app

import dev.gridiron.core.data.InjuryRow
import dev.gridiron.core.data.live.LiveInjury
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class LiveFormatTest {
    @Test
    fun timesReadInThePhonesZone() {
        assertEquals("Sep 25, 7:01 PM", formatWhen(Instant.parse("2026-09-25T23:01:03Z"), ZoneId.of("America/New_York"), Locale.US))
    }

    @Test
    fun theReportGroupsByTeamAndAddsTheLatestOfficialPractice() {
        fun live(name: String, team: String?, playerId: String?) =
            LiveInjury("e-$name", playerId, name, team, "WR", "Questionable", "Q", null, null)

        val groups = injuryReport(
            listOf(live("Amy", "KC", "P1"), live("Bo", "BUF", "P2"), live("Cy", null, null), live("Di", "KC", "P3")),
            listOf(
                InjuryRow("P1", "Amy", "KC", "WR", 2, "Questionable", "Ankle", "Limited"),
                InjuryRow("P1", "Amy", "KC", "WR", 3, "Questionable", "Ankle", "Full"),
                InjuryRow("P3", "Di", "KC", "WR", 3, "Out", "Knee", null),
            ),
        )

        assertEquals(listOf("BUF", "KC", "—"), groups.map { it.team })
        assertEquals(listOf("Amy", "Di"), groups[1].lines.map { it.injury.name })
        assertEquals(listOf("Full · Wk 3", null), groups[1].lines.map { it.practice })
        assertEquals(listOf<String?>(null), groups[0].lines.map { it.practice })
    }
}
```

```kotlin
// app/src/test/kotlin/dev/gridiron/app/LiveScreensTest.kt
package dev.gridiron.app

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.gridiron.core.data.PlayerHeader
import dev.gridiron.core.data.live.InjuryNote
import dev.gridiron.core.data.live.LiveInjury
import dev.gridiron.core.data.live.LiveStatus
import dev.gridiron.core.data.live.NewsItem
import dev.gridiron.core.data.live.NewsPlayer
import dev.gridiron.core.designsystem.GridironTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/** The live screens fed plain data: Robolectric can't open live.db itself. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class LiveScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val t = Instant.parse("2026-09-25T23:01:03Z")
    private val barkley = NewsItem(
        "1", t, "Barkley to play", "He plans to play through a stinger.", "https://espn.example/1",
        listOf(NewsPlayer("Saquon Barkley", "P1"), NewsPlayer("Unknown Guy", null)),
    )

    @Test
    fun newsLinksOnlyKnownPlayersAndOpensArticles() {
        val opened = mutableListOf<String>()
        val players = mutableListOf<String>()
        compose.setContent {
            GridironTheme { NewsScreen(listOf(barkley), t, null, onBack = {}, onOpen = { opened += it }, onPlayer = { players += it }) }
        }

        compose.onNodeWithTag("chip:P1").performClick()
        compose.onNodeWithText("Barkley to play").performClick()

        assertEquals(listOf("P1"), players)
        assertEquals(listOf("https://espn.example/1"), opened)
        compose.onNodeWithText("Unknown Guy").assertDoesNotExist()
        compose.onNodeWithText("as of", substring = true).assertExists()
    }

    @Test
    fun newsSaysWhenItCouldNotUpdate() {
        compose.setContent { GridironTheme { NewsScreen(listOf(barkley), t, "couldn't reach ESPN", {}, {}, {}) } }
        compose.onNodeWithText("Not updated: couldn't reach ESPN", substring = true).assertExists()
        compose.onNodeWithText("Barkley to play").assertExists()
    }

    @Test
    fun thePlayerPageShowsStatusNotesAndNews() {
        val page = PlayerPage(
            header = PlayerHeader("P1", "Saquon Barkley", "RB", "PHI"),
            status = LiveStatus("Questionable", "Q", "Barkley (neck) is questionable.", null, t),
            notes = listOf(
                InjuryNote(t, "Questionable", "Barkley (neck) is questionable."),
                InjuryNote(t.minusSeconds(86_400), "Questionable", "Barkley (neck) was limited."),
            ),
            news = listOf(barkley),
            asOf = t,
        )
        compose.setContent { GridironTheme { PlayerScreen("P1", page, liveAvailable = true, onBack = {}, onOpen = {}) } }

        compose.onNodeWithTag("playerName").assertTextEquals("Saquon Barkley")
        compose.onNodeWithText("RB · PHI").assertExists()
        compose.onNodeWithText("Questionable").assertExists()
        compose.onNodeWithText("Barkley (neck) was limited.").assertExists()
        compose.onNodeWithText("Barkley to play").assertExists()
    }

    @Test
    fun aPlayerWithNothingLiveSaysSo() {
        compose.setContent {
            GridironTheme { PlayerScreen("P9", PlayerPage(null, null, emptyList(), emptyList(), null), liveAvailable = true, onBack = {}, onOpen = {}) }
        }
        compose.onNodeWithTag("playerName").assertTextEquals("P9")
        compose.onNodeWithText("No injury designation.").assertExists()
        compose.onNodeWithText("No recent news.").assertExists()
    }

    @Test
    fun theLiveInjuryReportGroupsByTeamWithPractice() {
        val line = InjuryLine(LiveInjury("e1", "P1", "Max Melton", "ARI", "CB", "Questionable", "Q", "Melton (toe) was limited.", t), "Limited · Wk 3")
        val players = mutableListOf<String>()
        compose.setContent {
            GridironTheme { LiveInjuriesScreen(listOf(InjuryGroup("ARI", listOf(line))), t, null, onBack = {}, onPlayer = { players += it }) }
        }

        compose.onNodeWithText("ARI").assertExists()
        compose.onNodeWithText("CB · Practice: Limited · Wk 3").assertExists()
        compose.onNodeWithText("Max Melton").performClick()

        assertEquals(listOf("P1"), players)
    }
}
```

In `NavigationTest`:
1. Add the import `dev.gridiron.core.data.PlayerDirectory` and `androidx.compose.ui.test.assertTextEquals`.
2. In `setUp`, add `players = PlayerDirectory(executor),` after `teams = TeamsRepository(executor),`.
3. Append inside the class:

```kotlin
    @Test
    fun tappingAGridRowOpensThePlayerPage() {
        val (first, _) = firstTwoPlayerNames()
        compose.setContent { GridironTheme { GridironNavHost(deps) } }
        settle()

        compose.onNodeWithContentDescription(first, substring = true).performClick()
        settle()

        compose.onNodeWithTag("playerName").assertTextEquals(first)
        compose.onNodeWithText("Live injuries and news aren't available.").assertExists()
    }

    @Test
    fun theMenuNoLongerOffersProjections() {
        compose.setContent { GridironTheme { GridironNavHost(deps) } }
        settle()

        compose.onNodeWithTag("menu").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Injury report").assertExists()
        compose.onNodeWithText("Projection accuracy").assertDoesNotExist()
        compose.onNodeWithText("Time a stats build").assertDoesNotExist()
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test --tests "*TeamsRepositoryTest*" :app:testDebugUnitTest`
Expected: FAIL to compile: `Unresolved reference 'playerId'` and `Unresolved reference 'formatWhen'`.

- [ ] **Step 3: Carry the player id on official injury rows**

In `core/data/src/main/kotlin/dev/gridiron/core/data/TeamsRepository.kt`:

1. Add `val playerId: String,` as the first field of `InjuryRow`.
2. In `injuries(season)`, change the select list to `SELECT i.player_id, i.name, i.team, i.position, i.week, i.status, i.injury, i.practice`.
3. Replace the row mapping with:

```kotlin
        InjuryRow(
            playerId = it.text(0),
            name = it.textOrNull(1) ?: "?",
            team = it.textOrNull(2) ?: "",
            position = it.textOrNull(3) ?: "",
            week = it.long(4).toInt(),
            status = it.textOrNull(5),
            injury = it.textOrNull(6),
            practice = it.textOrNull(7),
        )
```

- [ ] **Step 4: Write the formatting and grouping**

```kotlin
// app/src/main/kotlin/dev/gridiron/app/LiveFormat.kt
package dev.gridiron.app

import dev.gridiron.core.data.InjuryRow
import dev.gridiron.core.data.live.LiveInjury
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "Sep 25, 7:01 PM", in the phone's time zone. */
fun formatWhen(instant: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("MMM d, h:mm a", locale).withZone(zone).format(instant)

/** One player on the live report, with their latest official practice participation if nflverse has one. */
data class InjuryLine(val injury: LiveInjury, val practice: String?)

data class InjuryGroup(val team: String, val lines: List<InjuryLine>)

private const val NO_TEAM = "—"

/**
 * ESPN's live list by team (alphabetical, players without a team last). Each
 * player keeps ESPN's order within the team and gains their latest official
 * practice status from nflverse, e.g. "Limited · Wk 3".
 */
fun injuryReport(live: List<LiveInjury>, official: List<InjuryRow>): List<InjuryGroup> {
    val practice = official.filter { it.practice != null }.groupBy { it.playerId }.mapValues { (_, rows) -> rows.maxBy { it.week } }
    return live.groupBy { it.team ?: NO_TEAM }.toSortedMap().map { (team, rows) ->
        InjuryGroup(
            team,
            rows.map { i -> InjuryLine(i, i.playerId?.let(practice::get)?.let { "${it.practice} · Wk ${it.week}" }) },
        )
    }
}
```

- [ ] **Step 5: Write the News screen**

```kotlin
// app/src/main/kotlin/dev/gridiron/app/NewsScreen.kt
package dev.gridiron.app

import android.content.ActivityNotFoundException
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.live.LiveRepository
import dev.gridiron.core.data.live.NewsItem
import java.time.Instant

@Composable
fun NewsRoute(live: LiveRepository, onBack: () -> Unit, onPlayer: (String) -> Unit) {
    val version by live.changes.collectAsState()
    var items by remember { mutableStateOf<List<NewsItem>?>(null) }
    var asOf by remember { mutableStateOf<Instant?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { error = live.refreshIfStale()?.newsError }
    LaunchedEffect(version) {
        items = live.news()
        asOf = live.fetchedAt()
    }
    val uri = LocalUriHandler.current
    NewsScreen(items, asOf, error, onBack, onOpen = { uri.openSafely(it) }, onPlayer = onPlayer)
}

/** ESPN headlines, newest first. [error] is set when the last fetch failed and older news is showing. */
@Composable
fun NewsScreen(
    items: List<NewsItem>?,
    asOf: Instant?,
    error: String?,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onPlayer: (String) -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("News", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            LiveCaption(asOf, error)
            when {
                items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                items.isEmpty() -> Message(if (error != null) "Couldn't reach ESPN. Try again later." else "No news yet.")
                else -> LazyColumn {
                    items(items, key = { it.id }) { item ->
                        NewsRow(item, onOpen, onPlayer)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun NewsRow(item: NewsItem, onOpen: (String) -> Unit, onPlayer: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.fillMaxWidth().clickable { onOpen(item.url) }.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(item.headline, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            item.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(formatWhen(item.published), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Only players the app knows get a chip; the article shows either way.
        val linked = item.players.mapNotNull { p -> p.playerId?.let { id -> id to p.name } }
        if (linked.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                linked.forEach { (id, name) ->
                    AssistChip(onClick = { onPlayer(id) }, label = { Text(name) }, modifier = Modifier.testTag("chip:$id"))
                }
            }
        }
    }
}

/** "ESPN · as of Sep 25, 7:01 PM", or why the data is older than it should be. */
@Composable
internal fun LiveCaption(asOf: Instant?, error: String?) {
    val text = when {
        error != null && asOf != null -> "Not updated: $error. Showing data as of ${formatWhen(asOf)}."
        error != null -> "Not updated: $error."
        asOf != null -> "ESPN · as of ${formatWhen(asOf)}"
        else -> "ESPN"
    }
    Text(
        text,
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Opens [url] in the browser; with no browser installed, does nothing rather than crash. */
internal fun UriHandler.openSafely(url: String) {
    try {
        openUri(url)
    } catch (_: ActivityNotFoundException) {
        // Nothing can open it; the headline stays on screen.
    } catch (_: IllegalArgumentException) {
        // A malformed link from the feed.
    }
}
```

- [ ] **Step 6: Write the Player page**

```kotlin
// app/src/main/kotlin/dev/gridiron/app/PlayerScreen.kt
package dev.gridiron.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.PlayerDirectory
import dev.gridiron.core.data.PlayerHeader
import dev.gridiron.core.data.live.InjuryNote
import dev.gridiron.core.data.live.LiveRepository
import dev.gridiron.core.data.live.LiveStatus
import dev.gridiron.core.data.live.NewsItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/** Everything the Player page shows. The projections follow-up adds its card here. */
data class PlayerPage(
    val header: PlayerHeader?,
    val status: LiveStatus?,
    val notes: List<InjuryNote>,
    val news: List<NewsItem>,
    val asOf: Instant?,
)

private val NO_CHANGES: StateFlow<Long> = MutableStateFlow(0L)

@Composable
fun PlayerRoute(playerId: String, players: PlayerDirectory?, live: LiveRepository?, onBack: () -> Unit) {
    val version by (live?.changes ?: NO_CHANGES).collectAsState()
    var page by remember(playerId) { mutableStateOf<PlayerPage?>(null) }
    LaunchedEffect(Unit) { live?.refreshIfStale() }
    LaunchedEffect(playerId, version) {
        page = PlayerPage(
            header = players?.header(playerId),
            status = live?.status(playerId),
            notes = live?.notes(playerId).orEmpty(),
            news = live?.playerNews(playerId).orEmpty(),
            asOf = live?.fetchedAt(),
        )
    }
    val uri = LocalUriHandler.current
    PlayerScreen(playerId, page, liveAvailable = live != null, onBack = onBack, onOpen = { uri.openSafely(it) })
}

@Composable
fun PlayerScreen(playerId: String, page: PlayerPage?, liveAvailable: Boolean, onBack: () -> Unit, onOpen: (String) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
            }
            if (page == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            LazyColumn {
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            page.header?.name ?: playerId,
                            Modifier.testTag("playerName"),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        val detail = listOfNotNull(page.header?.position, page.header?.team)
                        if (detail.isNotEmpty()) {
                            Text(detail.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { SectionTitle("Status") }
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        val s = page.status
                        when {
                            !liveAvailable -> Text("Live injuries and news aren't available.", style = MaterialTheme.typography.bodySmall)
                            s == null -> Text("No injury designation.", style = MaterialTheme.typography.bodyMedium)
                            else -> {
                                Text(s.status, color = injuryColor(s.abbr), fontWeight = FontWeight.Bold)
                                s.shortComment?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                s.longComment?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                if (page.notes.isNotEmpty()) {
                    item { SectionTitle("Injury notes") }
                    items(page.notes) { note ->
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(
                                "${formatWhen(note.notedAt)} · ${note.status}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(note.comment, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (liveAvailable) {
                    item { SectionTitle("News") }
                    if (page.news.isEmpty()) {
                        item { Text("No recent news.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium) }
                    } else {
                        items(page.news, key = { it.id }) { n ->
                            Column(Modifier.fillMaxWidth().clickable { onOpen(n.url) }.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                Text(n.headline, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(formatWhen(n.published), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                page.asOf?.let { asOf ->
                    item {
                        Text(
                            "ESPN · as of ${formatWhen(asOf)}",
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )
}

/** Red for Out, IR and Doubtful; the accent for Questionable. */
@Composable
internal fun injuryColor(abbr: String): Color = when (abbr) {
    "O", "IR", "D" -> MaterialTheme.colorScheme.error
    "Q" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurface
}
```

- [ ] **Step 7: Switch the Injury report to ESPN's live list**

In `app/src/main/kotlin/dev/gridiron/app/TeamScreens.kt`:

1. Change `private fun Message(text: String)` to `internal fun Message(text: String)`, so the News screen can use it.
2. Add these imports:

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.collectAsState
import dev.gridiron.core.data.live.LiveRepository
import kotlinx.coroutines.CancellationException
import java.time.Instant
```

3. Add after `InjuriesScreen`:

```kotlin
/**
 * The current season's report is ESPN's live list with official practice
 * alongside; a past season (or no live data source, in tests) shows
 * nflverse's official list as before.
 */
@Composable
fun InjuriesRoute(
    season: Int,
    currentSeason: Int,
    teams: TeamsRepository,
    live: LiveRepository?,
    onBack: () -> Unit,
    onPlayer: (String) -> Unit,
) {
    if (live != null && season == currentSeason) {
        LiveInjuriesRoute(season, teams, live, onBack, onPlayer)
    } else {
        InjuriesScreen(season, teams, onBack)
    }
}

@Composable
private fun LiveInjuriesRoute(season: Int, teams: TeamsRepository, live: LiveRepository, onBack: () -> Unit, onPlayer: (String) -> Unit) {
    val version by live.changes.collectAsState()
    var groups by remember { mutableStateOf<List<InjuryGroup>?>(null) }
    var asOf by remember { mutableStateOf<Instant?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { error = live.refreshIfStale()?.injuriesError }
    LaunchedEffect(version) {
        // Official practice rows are a bonus: the live list shows without them.
        val official = try {
            teams.injuries(season)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        groups = injuryReport(live.injuries(), official)
        asOf = live.fetchedAt()
    }
    LiveInjuriesScreen(groups, asOf, error, onBack, onPlayer)
}

@Composable
fun LiveInjuriesScreen(
    groups: List<InjuryGroup>?,
    asOf: Instant?,
    error: String?,
    onBack: () -> Unit,
    onPlayer: (String) -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("Injury report", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            LiveCaption(asOf, error)
            when {
                groups == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                groups.isEmpty() -> Message(if (error != null) "Couldn't reach ESPN. Try again later." else "No injuries reported.")
                else -> LazyColumn {
                    for (group in groups) {
                        item(key = "team:${group.team}") {
                            Text(
                                group.team,
                                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        items(group.lines, key = { it.injury.espnId }) { line ->
                            InjuryLineRow(line, onPlayer)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InjuryLineRow(line: InjuryLine, onPlayer: (String) -> Unit) {
    val i = line.injury
    val id = i.playerId
    Column(
        Modifier.fillMaxWidth()
            .then(if (id != null) Modifier.clickable { onPlayer(id) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row {
            Text(i.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
            Text(i.status, color = injuryColor(i.abbr))
        }
        Text(
            listOfNotNull(i.position, line.practice?.let { "Practice: $it" }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        i.shortComment?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
```

- [ ] **Step 8: Add the keys and wire the screens**

In `app/src/main/kotlin/dev/gridiron/app/NavKeys.kt`, add:

```kotlin
@Serializable data object NewsKey : NavKey
@Serializable data class PlayerKey(val playerId: String) : NavKey
```

In `app/src/main/kotlin/dev/gridiron/app/GridironNavHost.kt`:

1. Add the import `dev.gridiron.core.ingest.currentSeason`.
2. In `entry<GridKey>`, replace the `onPlayer = ...` line with:

```kotlin
                            onPlayer = { id, _, _ -> backStack.push(PlayerKey(id)) },
```

3. Make "News" the first menu entry. Inside `buildList<Pair<String, (Int) -> Unit>> {`, before `add("Injury report" ...`, add:

```kotlin
                                if (deps.live != null) add("News" to { _: Int -> backStack.push(NewsKey) })
```

4. Replace `entry<InjuriesKey> { key -> InjuriesScreen(key.season, deps.teams, onBack = back) }` with:

```kotlin
                    entry<InjuriesKey> { key ->
                        InjuriesRoute(key.season, currentSeason(), deps.teams, deps.live, onBack = back, onPlayer = { backStack.push(PlayerKey(it)) })
                    }
                    entry<NewsKey> {
                        deps.live?.let { NewsRoute(it, onBack = back, onPlayer = { id -> backStack.push(PlayerKey(id)) }) }
                    }
                    entry<PlayerKey> { key -> PlayerRoute(key.playerId, deps.players, deps.live, onBack = back) }
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, including the 2 `LiveFormatTest`, 5 `LiveScreensTest` and 2 new `NavigationTest` tests.

- [ ] **Step 10: Commit**

```bash
git add core/data/src/main/kotlin/dev/gridiron/core/data/TeamsRepository.kt core/data/src/test/kotlin/dev/gridiron/core/data/TeamsRepositoryTest.kt app
git commit -m "app: News, Player page, and ESPN's live injury report; row tap opens the player"
```

---

### Task 10: Injury badges on the Grid

Each Grid row gets a small Q/D/O/IR badge (ESPN's letter) after the player's name when ESPN lists them with any status other than Active. The badges come from `LiveRepository.badges`, a flow that re-emits after every live write, so they appear as soon as a refresh or the News screen's auto-fetch lands.

Grid rows replace their semantics with one content description (`clearAndSetSemantics` in `:core:table`). So the badge is added to that description ("Saquon Barkley (injury status Q), …"): tests find it there, and so do screen readers.

**Files:**
- Modify: `feature/players/src/main/kotlin/dev/gridiron/feature/players/GridViewModel.kt`
- Modify: `feature/players/src/main/kotlin/dev/gridiron/feature/players/GridScreen.kt`
- Test: `feature/players/src/test/kotlin/dev/gridiron/feature/players/GridViewModelTest.kt`
- Test: `feature/players/src/test/kotlin/dev/gridiron/feature/players/GridScreenTest.kt`
- Modify: `app/src/main/kotlin/dev/gridiron/app/GridironNavHost.kt`

**Interfaces:**
- Consumes: `LiveRepository.badges: Flow<Map<String, String>>` (Task 6); `Deps.live` (Task 8).
- Produces:
  - `GridUiState.Ready.badges: ImmutableMap<String, String> = persistentMapOf()`
  - `GridViewModel(…, badges: Flow<Map<String, String>> = flowOf(emptyMap()))`, as a new last parameter
  - `GridViewModel.factory(repository, scoring, tray, badges = flowOf(emptyMap()))`
  - `GridRoute(…, badges: Flow<Map<String, String>> = flowOf(emptyMap()))`, as a new last parameter

- [ ] **Step 1: Write the failing tests**

Append to `GridViewModelTest` (inside the class). Add the import `kotlinx.coroutines.flow.MutableStateFlow` if Task 2 hasn't already.

```kotlin
    @Test
    fun `injury badges reach the state and follow live updates`() = runTest(dispatcher) {
        val badges = MutableStateFlow<Map<String, String>>(emptyMap())
        val vm = GridViewModel(repo, ScoringRepository(prefs), CompareTrayRepository(prefs), badges = badges)
        val first = ready(vm).page!!.rows.first().playerId

        badges.value = mapOf(first to "Q")
        assertEquals(mapOf(first to "Q"), ready(vm).badges)

        badges.value = emptyMap()
        assertEquals(emptyMap<String, String>(), ready(vm).badges)
    }
```

Append to `GridScreenTest` (inside the class). Add the import `kotlinx.collections.immutable.persistentMapOf`.

```kotlin
    @Test
    fun anInjuredPlayerShowsTheirBadge() {
        val season = catalog.season(2025)
        val state = ready(GridRequest(season, season.defaultWeeks, StatPack.OPPORTUNITY))
        val first = state.page!!.rows.first()
        show(state.copy(badges = persistentMapOf(first.playerId to "Q")))

        // Rows expose one merged description; the badge is part of it.
        compose.onNodeWithContentDescription("${first.name} (injury status Q)", substring = true).assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/11_injury_badge.png")
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :feature:players:testDebugUnitTest --tests "*GridViewModelTest*" --tests "*GridScreenTest*"`
Expected: FAIL to compile: `No parameter with name 'badges'`.

- [ ] **Step 3: Carry badges through the view model**

In `feature/players/src/main/kotlin/dev/gridiron/feature/players/GridViewModel.kt`:

1. Add the import `kotlinx.coroutines.flow.flowOf`.

2. In `GridUiState.Ready`, add a field after `draftCount`:

```kotlin
        /** ESPN injury letters (Q, D, O, IR, …) by player id; empty when there is no live data. */
        val badges: ImmutableMap<String, String> = persistentMapOf(),
```

3. Add a constructor parameter to `GridViewModel`, after `countDebounceMillis: Long = 250,`:

```kotlin
    /** Live injury letters by player id, from ESPN; re-emits after every live refresh. */
    badges: Flow<Map<String, String>> = flowOf(emptyMap()),
```

4. Extend `Lines` with the badges. Replace the `Lines` class with:

```kotlin
    /** The sparkline, draft-count and badge sources, combined once so each carries its own staleness tag. */
    private data class Lines(
        val sparklines: Pair<GridPage, Map<String, Sparkline>>?,
        val draft: List<Filter>?,
        val count: Pair<List<Filter>, DraftCount>?,
        val badges: Map<String, String>,
    )
```

5. In the `state` pipeline, replace `combine(sparklines, draft, draftCount, ::Lines)` with `combine(sparklines, draft, draftCount, badges, ::Lines)`.

6. In the same pipeline's `base.copy(...)`, add after `draftCount = draftCount,`:

```kotlin
                    badges = extras.lines.badges.toImmutableMap(),
```

7. Replace `factory` in the companion object with:

```kotlin
        fun factory(
            repository: StatsRepository,
            scoring: ScoringRepository,
            tray: CompareTrayRepository,
            badges: Flow<Map<String, String>> = flowOf(emptyMap()),
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { GridViewModel(repository, scoring, tray, badges = badges) }
            }
```

- [ ] **Step 4: Draw the badge**

In `feature/players/src/main/kotlin/dev/gridiron/feature/players/GridScreen.kt`:

1. Add the imports `kotlinx.coroutines.flow.Flow`, `kotlinx.coroutines.flow.flowOf` and `kotlinx.collections.immutable.ImmutableMap` (the last one is already imported).

2. Give `GridRoute` a last parameter and pass it to the factory:

```kotlin
    menu: List<Pair<String, (season: Int) -> Unit>> = emptyList(),
    badges: Flow<Map<String, String>> = flowOf(emptyMap()),
) {
    val vm: GridViewModel = viewModel(factory = GridViewModel.factory(repository, scoring, tray, badges))
```

3. In `GridContent`, pass the badges to `PlayerTable`. Replace `state.sparklines,` in the `PlayerTable(` call with:

```kotlin
                        state.sparklines,
                        state.badges,
```

4. In `PlayerTable`'s parameter list, add after `sparklines: ImmutableMap<String, SparklineData>,`:

```kotlin
    badges: ImmutableMap<String, String>,
```

5. In `frozenCell`, replace the player-name `Text(row.name, ...)` line with a row holding the name and the badge:

```kotlin
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            row.name,
                            Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        badges[row.playerId]?.let { InjuryBadge(it, Modifier.padding(start = 4.dp)) }
                    }
```

6. In `rowDescription`, replace `append(row.name).append(", ").append(row.detail).append(". ")` with:

```kotlin
                append(row.name)
                badges[row.playerId]?.let { append(" (injury status ").append(it).append(')') }
                append(", ").append(row.detail).append(". ")
```

7. Add at the end of the file:

```kotlin
/** ESPN's injury letter after a name: red for O, IR and D, the accent color for Q and anything else. */
@Composable
private fun InjuryBadge(abbr: String, modifier: Modifier = Modifier) {
    val color = when (abbr) {
        "O", "IR", "D" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }
    Text(abbr, modifier, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
}
```

- [ ] **Step 5: Feed the Grid from live data**

In `app/src/main/kotlin/dev/gridiron/app/GridironNavHost.kt`:
- Add the import `kotlinx.coroutines.flow.flowOf`.
- In `entry<GridKey>`'s `GridRoute(...)` call, add after the `menu = ...` argument:

```kotlin
                            badges = deps.live?.badges ?: flowOf(emptyMap()),
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :feature:players:testDebugUnitTest :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. `build/outputs/roborazzi/11_injury_badge.png` shows a small "Q" after the first player's name. Open it to check.

- [ ] **Step 7: Commit**

```bash
git add feature/players app/src/main/kotlin/dev/gridiron/app/GridironNavHost.kt
git commit -m "grid: ESPN injury badge next to injured players"
```

---

### Task 11: Remove the repo data path, and update the docs

This task finishes the removals the spec lists:
- the `etl.yml` workflow that published the `data` release;
- Plan 1's timing benchmark (only its menu item used it);
- every mention of the `data` release or a bundled database.

`currentSeason()` moves out of `Benchmark.kt` into its own file, keeping its package and name, so no import changes. CI keeps building a database for tests with the Kotlin CLI, and keeps the weekly run, which now exists to rerun the tests against fresh nflverse data.

**Files:**
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Seasons.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/SeasonsTest.kt`
- Delete: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Benchmark.kt`
- Delete: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/BenchmarkTest.kt`
- Delete: `.github/workflows/etl.yml`
- Modify: `.github/workflows/ci.yml`
- Modify: `CLAUDE.md`
- Modify: `README.md`

**Interfaces:**
- Produces: `public fun currentSeason(today: LocalDate = LocalDate.now()): Int`, unchanged, now in `Seasons.kt`.

- [ ] **Step 1: Move `currentSeason` and delete the benchmark**

```kotlin
// core/ingest/src/test/kotlin/dev/gridiron/core/ingest/SeasonsTest.kt
package dev.gridiron.core.ingest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

class SeasonsTest {
    @Test
    fun `a season is current from September`() {
        assertEquals(2026, currentSeason(LocalDate.of(2026, 9, 1)))
        assertEquals(2025, currentSeason(LocalDate.of(2026, 8, 31)))
        assertEquals(2025, currentSeason(LocalDate.of(2026, 1, 15)))
    }
}
```

Delete `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Benchmark.kt` and `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/BenchmarkTest.kt`. Keep `Fixtures.kt` and `FakeFetcher.kt`; other tests use them.

Run: `./gradlew :core:ingest:test --tests "*SeasonsTest*"`
Expected: FAIL to compile: `Unresolved reference 'currentSeason'`.

```kotlin
// core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Seasons.kt
package dev.gridiron.core.ingest

import java.time.LocalDate

/** The NFL season in progress on [today]: a season is current from September. */
public fun currentSeason(today: LocalDate = LocalDate.now()): Int =
    if (today.monthValue >= 9) today.year else today.year - 1
```

Run: `./gradlew :core:ingest:test :app:compileDebugKotlin && grep -rn 'benchmarkSeason\|BenchmarkResult' core app || echo "no references"`
Expected: BUILD SUCCESSFUL, then `no references`.

- [ ] **Step 2: Delete the data-release workflow and update CI**

Delete `.github/workflows/etl.yml`.

In `.github/workflows/ci.yml` (as Plan 1 Task 12 left it):

1. Replace the comment block at the top, from `# Builds the stats database with the on-device ingest code` down to `# The parity job proves the Kotlin ingest reproduces the Python ETL exactly.`, with:

```yaml
# Builds a stats database with the same Kotlin code the phone runs, runs every
# test against it, renders the Grid screenshots, and builds the installable app.
# The APK carries no data: the phone builds its own stats. On anything but a
# pull request it then publishes the APK to a fixed link:
#   https://github.com/Palm9999/Lame/releases/download/app/gridiron.apk
# The parity job proves the Kotlin ingest reproduces the Python ETL exactly.
```

2. Replace `    # Tuesdays 10:00 UTC, after Monday Night Football.` with:

```yaml
    # Tuesdays 10:00 UTC, after Monday Night Football: reruns every test against
    # the week's fresh nflverse data, so an upstream change shows up here first.
```

3. In the `Publish the app` step, replace these two lines:

```yaml
          SEASONS=$(python -c "import sqlite3; print(sqlite3.connect('etl/build/stats.db').execute(\"SELECT value FROM schema_meta WHERE key = 'seasons'\").fetchone()[0])")
          NOTES="Built $(date -u +%Y-%m-%d) from ${GITHUB_SHA::7}. Seasons: ${SEASONS}. Install gridiron.apk on your phone."
```

with:

```yaml
          NOTES="Built $(date -u +%Y-%m-%d) from ${GITHUB_SHA::7}. Install gridiron.apk on your phone, then tap ☰ → Refresh stats to build the latest stats on the phone."
```

- [ ] **Step 3: Update CLAUDE.md**

Make these replacements in `CLAUDE.md`. Each old line is quoted exactly.

1. Replace `The app ships as a prebuilt SQLite database within the APK, so it works offline.` with:

```markdown
The phone builds its own SQLite stats database from nflverse and ffopportunity when the user taps Refresh, and works offline between refreshes. Injuries and news come live from ESPN.
```

2. Replace `# Set the stats database path (required for all tests and builds)` with `# Set the stats database path (required for tests; the APK carries no data)`.

3. Replace the `:app` bullet (`- \`:app\` — App entry point; ships \`stats.db\` inside the APK and copies it on first launch`) with:

```markdown
- `:app` — App entry point. `RefreshCoordinator` builds `stats.db` on the phone with `:core:ingest` and swaps it in without a restart; News, Player page, live Injury report, Settings (seasons) and Load stats screens
```

4. Replace the `:core:database` bullet with:

```markdown
- `:core:database` — Read-only SQLite access via the bundled driver. `ReopenableQueryExecutor` closes and reopens the connection when a refresh swaps in a new `stats.db`, and bumps a version flow the Grid and Compare reload on
```

5. Replace the `:core:data` bullet with:

```markdown
- `:core:data` — Stat packs, qualifying bars, formatting, repositories; `SettingsRepository` (which seasons to build); `PlayerDirectory` (ESPN id → player via `player_xref`); and the `live` package: the ESPN news/injuries parser and client, the writable `live.db` store, and `LiveRepository`
```

6. Replace the whole `### Data Flow` section (its four numbered items) with:

```markdown
### Data Flow

1. **Refresh on the phone** — ☰ → Refresh stats (or Load stats on a fresh install) runs `:core:ingest`'s `IngestPipeline` for the seasons chosen in Settings. It downloads nflverse and ffopportunity files with conditional GETs, copies unchanged seasons from the current database, crunches the rest, validates, and writes `stats.db.new`
2. **Swap** — `RefreshCoordinator` renames `stats.db.new` over `stats.db` inside `ReopenableQueryExecutor.swap`; screens reload on the version bump. A failed build leaves `stats.db` untouched
3. **Live data** — the same refresh (and the News, Player and Injury report screens, when data is over 15 minutes old) fetches ESPN's news and injuries into `live.db`, linked to players through `player_xref`, pruned at 30 days
4. **Query Layer** — `:core:statquery` generates parameterized SQL for any stat grid query (columns, filters, week ranges, percentiles). Tests run it through the JDBC executor against a Kotlin-built database (`GRIDIRON_STATS_DB`); the phone runs it through the bundled SQLite driver
5. **Python ETL** (`etl/`) — kept only as CI's parity reference for the Kotlin port (and for the projections follow-up); nothing it builds reaches the app
```

7. Replace the `**Read-Only Database at Build Time**` paragraph with:

```markdown
**Stats Database Replaced Whole, Never Edited** — The app opens `stats.db` read-only; a refresh builds a complete new file beside it and swaps it in atomically. Live ESPN data lives in a separate `live.db` that is updated in place and can be deleted at any time. User state (presets, rosters) will live in `user.db` when implemented.
```

8. Replace `### Database Schema (Version 5)` with `### Database Schema (Version 6)`, and in its table add this row after the `player` row:

```markdown
| `player_xref` | ESPN athlete id → `player_id` for every player nflverse lists, stats or not; links ESPN news and injuries |
```

Then add after the table (before `**No year in table/column names**`):

```markdown
**`live.db`** (separate file, `PRAGMA user_version` 1): `news_item`, `news_player` (ESPN id, name, nullable `player_id`), `injury_status` (current snapshot), `injury_note` (appended when a comment changes), `live_meta` (fetch times). Rows older than 30 days are pruned; an unreadable file is recreated.
```

9. In **Known Gaps & Next Steps**, replace the `**Grid entry points**` bullet with:

```markdown
- **Grid entry points**: tapping a Grid row opens the Player page (ESPN status, injury notes, tagged news); the ☰ menu opens News, Injury report (ESPN's live list with nflverse practice for the current season; the official list for past seasons), Team defense, Settings and Refresh stats. `ProjectionsKey`/`AccuracyKey` stay registered but unreachable until on-device projections.
- **ESPN's endpoints are unofficial and keyless**; a shape change shows as "Not updated: ESPN changed its … format" with the last data kept. Parsing lives in `core/data/.../live/Espn.kt`, tested against recorded responses in `core/data/src/test/resources/espn/`.
- **Refresh runs in an application-scope coroutine, not WorkManager**: if Android kills the process mid-build, the old database stays and the next refresh starts over.
```

and replace the `**ETL projections stage currently fails**` bullet with:

```markdown
- **Projections are hidden** until the follow-up that computes them on the phone; the phone-built `stats.db` has no projection rows. (The Python ETL's projections stage also fails today: `unable to find column "regime_break"`.)
```

- [ ] **Step 4: Update README.md**

In `README.md`:

1. After the install instructions' numbered list (after the line starting `3. If Play Protect warns`), add:

```markdown

On first launch, tap **Load stats**. The phone downloads nflverse's public data and builds its own stats: about a minute per season. After that, **☰ → Refresh stats** brings in new games, injuries and news; **☰ → Settings** picks the seasons.
```

2. Replace the `| Data pipeline |` row with:

```markdown
| Data pipeline | Done, on the phone: [`core/ingest/`](core/ingest) is a Kotlin port of [`etl/`](etl/README.md), held to it by a CI parity gate. nflverse, plus ffopportunity for expected-points components → pre-indexed SQLite, validated on every build. |
```

and add this row at the end of the Status table:

```markdown
| **Live data** | **Done.** Stats build on the phone; ESPN injuries and news on a News screen, Player pages and Grid badges; seasons chosen in Settings. |
```

3. In the Modules table:
   - Replace the `:app` row's role with `Navigation 3 wiring; builds \`stats.db\` on the phone and swaps it in without a restart; News, Player, Injury report, Settings and Load stats screens`.
   - Replace the `:core:data` row's role with `Stat packs, qualifying bars, formatting, the Grid/Compare/scoring/tray/settings repositories, and ESPN live data (\`live.db\`)`.
   - Replace the `:core:database` row's role with `Read-only SQLite access via the bundled driver, reopened after each refresh`.
   - Add a row after `:core:database`:

```markdown
| `:core:ingest` | JVM | Builds `stats.db` from nflverse and ffopportunity, on the phone and in CI |
```

4. Replace the whole fenced block under `## Building` with:

````markdown
```bash
# A stats database for the tests, built by the same Kotlin code the phone runs
./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025 2026" -Pout=etl/build/stats.db

# Everything else (JDK 17+, Android SDK with platform 37)
GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew test   # all tests, against the real data
./gradlew :app:assembleRelease                        # the APK (it carries no data)
./gradlew :feature:players:recordRoborazziDebug       # screenshots of the Grid

# The Python ETL, now only the parity reference
cd etl && pip install -r requirements.txt && python -m pytest tests/ -q
```
````

- [ ] **Step 5: Check nothing still points at the old path**

Run:

```bash
grep -rn --exclude-dir=.git --exclude-dir=build --exclude-dir=docs --exclude-dir=.claude --exclude-dir=.gradle \
  -e 'download/data' -e 'data` release' -e 'StatsDbInstaller' -e 'BundleStatsDb' -e 'stats.db.gz' \
  -e 'Time a stats build' -e 'benchmarkSeason' -e 'DeferredQueryExecutor' . || echo "clean"
```

Expected: `clean`. Plans and specs under `docs/` keep their history and are excluded.

- [ ] **Step 6: Run everything**

Run:

```bash
./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025 2026" -Pout=etl/build/stats.db
GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew build
(cd etl && python -m pytest tests/ -q)
```

Expected:
- The database builds; a season nflverse hasn't published is skipped.
- `./gradlew build` is green: every unit, Robolectric and contract test, plus the release APK.
- The ETL tests pass.

- [ ] **Step 7: Commit**

```bash
git add -A core/ingest .github/workflows CLAUDE.md README.md
git commit -m "Remove the repo data release; stats come only from the phone's own build"
```

- [ ] **Step 8: Checkpoint on the phone (non-blocking; the controller asks the user)**

Once this reaches CI, the published APK is the first with no bundled data. Ask the user to install it over the current app and check:
1. The existing stats still show, with the "Stats now build on your phone" prompt.
2. **Refresh now** shows progress under the Grid, ends with a "Stats updated for …" toast, and the Grid reloads without a restart.
3. ☰ → News shows headlines, and a player chip opens a Player page.
4. Injured players carry a badge on the Grid.
5. ☰ → Settings lists 2012 onward.

Record the refresh time the toast reports. It is the spec's on-device timing, and the target is under a minute per season.
