# Grid Finish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the Grid: add team and snap-share quick filters, an advanced filter sheet with a live match count, last-6-week sparklines, and CSV export.

**Architecture:**
- The query builder already supports team filters, filters on any `StatColumn` and a `count()` query, so `:core:statquery` is untouched except for one catalog query.
- `:core:data` gains the request fields, filter text, sparklines (the existing grid query run once per week) and a CSV writer.
- `:core:charts` gains a Canvas `Sparkline`.
- `:feature:players` gets the chips, two sheets and the export action.
- `:app` declares a FileProvider.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), kotlinx.coroutines, JUnit 5 (JVM modules), JUnit 4 + Robolectric + Roborazzi (Android modules), SQLite through the JDBC test fixture.

**Spec:** `docs/superpowers/specs/2026-09-23-grid-finish-design.md`

## Global Constraints

- No changes to `StatQueryBuilder`'s SQL generation. Sparklines reuse `StatQueryBuilder.grid` once per week.
- Filters apply to values as displayed (per game in per-game mode) and never change percentiles.
- Operators offered: `≥`, `≤`, `between` (`Condition.AtLeast`, `Condition.AtMost`, `Condition.Between`).
- At most 8 advanced filters (`GridRequest.MAX_FILTERS = 8`).
- Snap-share choices: any, 0.25, 0.50, 0.75.
- Percent columns are exactly `StatFormat`'s percent set (TARGET_SHARE, AIR_YARDS_SHARE, CARRY_SHARE, SNAP_SHARE, CATCH_RATE, RUSH_SUCCESS_RATE). They're typed as percentages and stored as fractions.
- Sparklines cover the last six played weeks in the range, `max(first, last − 5)..last` with `last = min(weeks.last, season.lastWeek)`, and are omitted when fewer than two weeks or fewer than two points exist.
- Sparkline speed: median under 300 ms locally and 1,200 ms when `CI` is set, for a full page.
- The count is debounced 250 ms. The Grid's existing request debounce (150 ms) is unchanged.
- The CSV follows RFC 4180 with CRLF line endings, has a `# ` view line first and `# Data: nflverse (CC BY 4.0)` last, and exports a missing value as an empty field.
- FileProvider authority is `${applicationId}.exports`, files go under `cacheDir/exports/`, and the file name is `gridiron-<season>-<pack>.csv`.
- Filters, teams and snap share survive pack and season changes but not app restarts.
- Every test that needs the database reads `GRIDIRON_STATS_DB`: JVM tests use `@EnabledIfEnvironmentVariable`, Android tests use `assumeTrue`.

## Review Focus

1. **A played week with zero of a sparsely stored stat** (a WR with 0 receiving yards that week) draws as 0, not a gap. Gaps are only for weeks not played. Pinned in Task 3.
2. **Percent filters typed as "65"** mean 65% (stored as 0.65) and read back as "65" with no float noise ("65.00000000000001"). Pinned in Task 2.
3. **`between` with min > max, a blank second value, "abc", "1e3" or "NaN"** marks the row incomplete, skips it on Apply and never throws. Pinned in Task 7.
4. **Name search plus filters:** while you type a name, team, snap and advanced filters still apply; only the sample qualifier is relaxed. Pinned in Task 2.
5. **A one-week range, or a range with nothing played yet,** runs no sparkline queries and shows none, without crashing. Pinned in Task 3.

## Working Environment (read before any task)

- The database: `etl/build/stats.db` already exists (schema 3, seasons 2024–2026). Export `GRIDIRON_STATS_DB=etl/build/stats.db` for every Gradle command. If it's missing, build it with `cd etl && python3 -m gridiron_etl.build --seasons 2024 2025 2026 --skip-missing --out build/stats.db`.
- Run a module's tests: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test`. Android modules use `:feature:players:testDebugUnitTest`.
- Record screenshots: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :feature:players:recordRoborazziDebug`. They're written to `feature/players/build/outputs/roborazzi/`.
- `.claude/worktrees/` holds stale agent checkouts. Ignore them; never edit files there.
- Tests in `:core:data`, `:core:statquery` and `:core:model` use JUnit 5 (`org.junit.jupiter.api`). Android modules use JUnit 4 (`org.junit`).

## File Map

| File | Change |
|---|---|
| `core/data/src/main/kotlin/dev/gridiron/core/data/DecimalInput.kt` | Moved from `:feature:scoring`, now public, with a `limit` parameter |
| `core/data/src/main/kotlin/dev/gridiron/core/data/FilterText.kt` | New: `FilterUnits`, `describeFilter`, `describeView`, `filterColumnOrder` |
| `core/data/src/main/kotlin/dev/gridiron/core/data/Sparkline.kt` | New: `Sparkline`, `sparklineWeeks` |
| `core/data/src/main/kotlin/dev/gridiron/core/data/CsvExport.kt` | New |
| `core/data/src/main/kotlin/dev/gridiron/core/data/GridModels.kt` | `Catalog.teams`; `GridRequest` teams/minSnapShare/filters; `GridRowUi` position/team/games |
| `core/data/src/main/kotlin/dev/gridiron/core/data/StatsRepository.kt` | Shared spec builder, `count`, `sparklines`, teams in catalog |
| `core/data/src/main/kotlin/dev/gridiron/core/data/StatFormat.kt` | Public `isPercent` |
| `core/statquery/src/main/kotlin/dev/gridiron/core/statquery/CatalogQueries.kt` | `teams` query |
| `core/charts/src/main/kotlin/dev/gridiron/core/charts/Sparkline.kt` | New composable |
| `feature/players/src/main/kotlin/dev/gridiron/feature/players/GridViewModel.kt` | Events, reduce, sparklines, draft count |
| `feature/players/src/main/kotlin/dev/gridiron/feature/players/FilterDraft.kt` | New: sheet editing model |
| `feature/players/src/main/kotlin/dev/gridiron/feature/players/FilterSheets.kt` | New: `TeamSheet`, `FilterSheet` |
| `feature/players/src/main/kotlin/dev/gridiron/feature/players/CsvShare.kt` | New: write and share |
| `feature/players/src/main/kotlin/dev/gridiron/feature/players/GridScreen.kt` | Chip row, summary, sparkline in rows, export |
| `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/export_paths.xml` | FileProvider |

---

### Task 1: Move `DecimalInput` to `:core:data`

**Files:**
- Create: `core/data/src/main/kotlin/dev/gridiron/core/data/DecimalInput.kt`
- Delete: `feature/scoring/src/main/kotlin/dev/gridiron/feature/scoring/DecimalInput.kt`
- Move: `feature/scoring/src/test/kotlin/dev/gridiron/feature/scoring/DecimalInputTest.kt` → `core/data/src/test/kotlin/dev/gridiron/core/data/DecimalInputTest.kt`
- Modify: `feature/scoring/src/main/kotlin/dev/gridiron/feature/scoring/ScoringEditViewModel.kt` (import only)

**Interfaces:**
- Produces: `public object DecimalInput { const val LIMIT = 1000.0; fun parse(text: String, limit: Double = LIMIT): Result; fun format(value: Double): String; sealed interface Result { Value(value: Double), Blank, Invalid } }` in package `dev.gridiron.core.data`.

- [ ] **Step 1: Move the test and port it to JUnit 5, adding a limit case**

```bash
git mv feature/scoring/src/test/kotlin/dev/gridiron/feature/scoring/DecimalInputTest.kt core/data/src/test/kotlin/dev/gridiron/core/data/DecimalInputTest.kt
```

Replace the file's contents with:

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.data.DecimalInput.Result.Blank
import dev.gridiron.core.data.DecimalInput.Result.Invalid
import dev.gridiron.core.data.DecimalInput.Result.Value
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DecimalInputTest {
    @Test
    fun `parses what people type`() {
        assertEquals(Value(6.0), DecimalInput.parse("6"))
        assertEquals(Value(0.04), DecimalInput.parse("0.04"))
        assertEquals(Value(0.04), DecimalInput.parse(".04"))
        assertEquals(Value(-2.0), DecimalInput.parse("-2"))
        assertEquals(Value(1.5), DecimalInput.parse("1,5")) // comma decimal, any locale
        assertEquals(Value(1.0), DecimalInput.parse("1."))
        assertEquals(Value(3.0), DecimalInput.parse(" 3 "))
    }

    @Test
    fun `rejects everything else without throwing`() {
        assertEquals(Blank, DecimalInput.parse(""))
        assertEquals(Blank, DecimalInput.parse("   "))
        for (bad in listOf("-", ".", ",", "1e3", "NaN", "Infinity", "1.2.3", "1,000.5", "abc", "99999", "--1")) {
            assertEquals(Invalid, DecimalInput.parse(bad), bad)
        }
    }

    @Test
    fun `a caller can raise the limit`() {
        assertEquals(Value(4500.0), DecimalInput.parse("4500", limit = 100_000.0))
        assertEquals(Invalid, DecimalInput.parse("4500"))
        assertEquals(Invalid, DecimalInput.parse("100001", limit = 100_000.0))
    }

    @Test
    fun `formats without noise`() {
        assertEquals("0.04", DecimalInput.format(0.04))
        assertEquals("6", DecimalInput.format(6.0))
        assertEquals("-0.5", DecimalInput.format(-0.5))
        assertEquals("0", DecimalInput.format(-0.0))
    }
}
```

- [ ] **Step 2: Run it and confirm it fails to compile**

Run: `./gradlew :core:data:test --tests '*DecimalInputTest*'`
Expected: compilation FAILS with `Unresolved reference: DecimalInput`.

- [ ] **Step 3: Move the implementation, make it public and add `limit`**

```bash
git rm feature/scoring/src/main/kotlin/dev/gridiron/feature/scoring/DecimalInput.kt
```

Create `core/data/src/main/kotlin/dev/gridiron/core/data/DecimalInput.kt`:

```kotlin
package dev.gridiron.core.data

import java.math.BigDecimal
import kotlin.math.abs

/**
 * Strict decimal parsing for number fields. A comma is a decimal point in
 * every locale ("1,5" is 1.5), and grouping separators, exponents, NaN and
 * infinities are rejected, so no field can ever save a non-finite number.
 */
public object DecimalInput {
    /** The scoring editor's bound: no single weight is ever near it. */
    public const val LIMIT: Double = 1000.0

    private val PATTERN = Regex("""-?(\d+([.,]\d*)?|[.,]\d+)""")

    public sealed interface Result {
        public data class Value(val value: Double) : Result
        public data object Blank : Result
        public data object Invalid : Result
    }

    public fun parse(text: String, limit: Double = LIMIT): Result {
        val t = text.trim()
        if (t.isEmpty()) return Result.Blank
        if (!PATTERN.matches(t)) return Result.Invalid
        val value = t.replace(',', '.').toDoubleOrNull() ?: return Result.Invalid
        return if (value.isFinite() && abs(value) <= limit) Result.Value(value) else Result.Invalid
    }

    public fun format(value: Double): String =
        if (value == 0.0) "0" else BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}
```

In `ScoringEditViewModel.kt`, add `import dev.gridiron.core.data.DecimalInput` next to the other `dev.gridiron.core.data` imports. `:feature:scoring` already sees `:core:data` through `:core:ui`'s `api` dependency. If it doesn't compile, add `implementation(projects.core.data)` to `feature/scoring/build.gradle.kts`.

- [ ] **Step 4: Run both modules' tests**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test --tests '*DecimalInputTest*' :feature:scoring:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A core/data feature/scoring
git commit -m "data: share DecimalInput from core:data, with a caller-set limit"
```

---

### Task 2: Request filters, teams, count and filter text (`:core:data`)

**Files:**
- Modify: `core/statquery/src/main/kotlin/dev/gridiron/core/statquery/CatalogQueries.kt`
- Modify: `core/data/src/main/kotlin/dev/gridiron/core/data/GridModels.kt`
- Modify: `core/data/src/main/kotlin/dev/gridiron/core/data/StatFormat.kt`
- Modify: `core/data/src/main/kotlin/dev/gridiron/core/data/StatsRepository.kt`
- Create: `core/data/src/main/kotlin/dev/gridiron/core/data/FilterText.kt`
- Test: `core/data/src/test/kotlin/dev/gridiron/core/data/FilterTextTest.kt` (new), `core/data/src/test/kotlin/dev/gridiron/core/data/StatsRepositoryTest.kt` (add tests)

**Interfaces:**
- Consumes: `DecimalInput` (Task 1).
- Produces:
  - `CatalogQueries.teams: SqlQuery` (one text column).
  - `Catalog(seasons, metrics, teams: ImmutableList<String> = persistentListOf())`.
  - `GridRequest` gains `teams: Set<String> = emptySet()`, `minSnapShare: Double? = null`, `filters: List<Filter> = emptyList()`, plus `companion const val MAX_FILTERS = 8` and `val SNAP_SHARE_CHOICES: List<Double> = listOf(0.25, 0.5, 0.75)`.
  - `StatsRepository.count(request: GridRequest): Int`.
  - `StatFormat.isPercent(column: StatColumn): Boolean` (companion).
  - `object FilterUnits { const val FILTER_LIMIT = 100_000.0; fun toInput(column, stored: Double): Double; fun toStored(column, input: Double): Double }`.
  - `fun describeFilter(filter: Filter, catalog: Catalog): String`.
  - `fun describeView(request: GridRequest, catalog: Catalog): String`.
  - `fun filterColumnOrder(pack: StatPack): List<StatColumn>`.

- [ ] **Step 1: Write the failing unit tests for filter text**

Create `core/data/src/test/kotlin/dev/gridiron/core/data/FilterTextTest.kt`:

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.statquery.Condition
import dev.gridiron.core.statquery.Filter
import dev.gridiron.core.statquery.StatColumn
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FilterTextTest {
    private fun info(id: String, abbr: String) = MetricInfo(id, abbr, abbr, "", null, null, null)
    private val season = SeasonInfo(2025, lastWeek = 22)
    private val catalog = Catalog(
        persistentListOf(season),
        persistentMapOf(
            "targets" to info("targets", "TGT"),
            "catch_rate" to info("catch_rate", "CTCH%"),
            "snap_share" to info("snap_share", "SNAP%"),
        ),
    )

    @Test
    fun `percent columns read back in percent without float noise`() {
        assertEquals(0.65, FilterUnits.toStored(StatColumn.CATCH_RATE, 65.0))
        assertEquals(65.0, FilterUnits.toInput(StatColumn.CATCH_RATE, 0.65))
        assertEquals(57.3, FilterUnits.toInput(StatColumn.SNAP_SHARE, 0.573))
        assertEquals(50.0, FilterUnits.toStored(StatColumn.TARGETS, 50.0))
        assertEquals("CTCH% ≥ 65", describeFilter(Filter(StatColumn.CATCH_RATE, Condition.AtLeast(0.65)), catalog))
    }

    @Test
    fun `each operator has a short form`() {
        assertEquals("TGT ≥ 50", describeFilter(Filter(StatColumn.TARGETS, Condition.AtLeast(50.0)), catalog))
        assertEquals("TGT ≤ 7.5", describeFilter(Filter(StatColumn.TARGETS, Condition.AtMost(7.5)), catalog))
        assertEquals("TGT 20–40", describeFilter(Filter(StatColumn.TARGETS, Condition.Between(20.0, 40.0)), catalog))
    }

    @Test
    fun `the view line names everything that shapes the table`() {
        val r = GridRequest(
            season, dev.gridiron.core.model.WeekRange(1, 8), StatPack.RECEIVING,
            positions = PositionFilter.WR, perGame = true, teams = setOf("KC", "BUF"), minSnapShare = 0.5,
            filters = listOf(Filter(StatColumn.TARGETS, Condition.AtLeast(5.0))),
        )
        assertEquals(
            "Gridiron · 2025 · Wk 1–8 · Receiving · WR · PPR · per game · BUF/KC · SNAP% ≥ 50 · TGT ≥ 5",
            describeView(r, catalog),
        )
    }

    @Test
    fun `filter picker lists the current pack first, then every other column once`() {
        val order = filterColumnOrder(StatPack.RUSHING)
        assertEquals(StatPack.RUSHING.columns, order.take(StatPack.RUSHING.columns.size))
        assertEquals(StatColumn.entries.toSet(), order.toSet())
        assertEquals(order.size, order.distinct().size)
    }

    @Test
    fun `request rejects too many filters and odd snap shares`() {
        val f = Filter(StatColumn.TARGETS, Condition.AtLeast(1.0))
        assertTrue(runCatching { GridRequest(season, season.defaultWeeks, StatPack.RECEIVING, filters = List(8) { f }) }.isSuccess)
        assertFalse(runCatching { GridRequest(season, season.defaultWeeks, StatPack.RECEIVING, filters = List(9) { f }) }.isSuccess)
        assertFalse(runCatching { GridRequest(season, season.defaultWeeks, StatPack.RECEIVING, minSnapShare = 1.5) }.isSuccess)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails to compile**

Run: `./gradlew :core:data:test --tests '*FilterTextTest*'`
Expected: FAILS with unresolved `FilterUnits`, `describeFilter`, `teams` and so on.

- [ ] **Step 3: Add the teams query, request fields and `isPercent`**

In `CatalogQueries.kt`, add inside the object:

```kotlin
    /** Every team a player is currently on, alphabetically: team. */
    public val teams: SqlQuery = SqlQuery(
        "SELECT DISTINCT team FROM player WHERE team IS NOT NULL ORDER BY team",
        emptyList(),
    )
```

In `GridModels.kt`, give `Catalog` a teams list with a default, so existing constructions keep compiling:

```kotlin
public data class Catalog(
    val seasons: ImmutableList<SeasonInfo>,
    val metrics: ImmutableMap<String, MetricInfo>,
    /** Current team abbreviations, alphabetical. */
    val teams: ImmutableList<String> = persistentListOf(),
) {
```

(add `import kotlinx.collections.immutable.persistentListOf` and `import dev.gridiron.core.statquery.Filter`).

Replace `GridRequest` with:

```kotlin
/**
 * What the Grid screen is asking for.
 *
 * @property teams Current-team abbreviations; empty means every team.
 * @property minSnapShare Snap-share floor as a fraction, one of [SNAP_SHARE_CHOICES]; null means any.
 * @property filters Advanced filters, ANDed, in display units already converted
 *   to stored units (see [FilterUnits]).
 */
public data class GridRequest(
    val season: SeasonInfo,
    val weeks: WeekRange,
    val pack: StatPack,
    val positions: PositionFilter = PositionFilter.ALL,
    val sort: StatColumn = pack.defaultSort,
    val direction: Direction = defaultDirection(sort),
    val perGame: Boolean = false,
    val name: String = "",
    val scoring: ScoringProfile = ScoringPresets.PPR,
    val teams: Set<String> = emptySet(),
    val minSnapShare: Double? = null,
    val filters: List<Filter> = emptyList(),
) {
    init {
        require(filters.size <= MAX_FILTERS) { "at most $MAX_FILTERS filters, got ${filters.size}" }
        require(minSnapShare == null || minSnapShare in SNAP_SHARE_CHOICES) { "snap share $minSnapShare not offered" }
    }

    /** Weeks in the range that have actually been played. */
    public val playedWeeks: Int
        get() = (minOf(weeks.last, season.lastWeek) - weeks.first + 1).coerceAtLeast(1)

    public companion object {
        public const val MAX_FILTERS: Int = 8
        public val SNAP_SHARE_CHOICES: List<Double> = listOf(0.25, 0.5, 0.75)

        /** Best first: most yards, fewest interceptions. */
        public fun defaultDirection(column: StatColumn): Direction =
            if (column.higherIsBetter) Direction.DESCENDING else Direction.ASCENDING
    }
}
```

In `StatFormat.kt`'s companion, add:

```kotlin
        /** Shares and rates shown as percentages, which filters take as percentages too. */
        public fun isPercent(column: StatColumn): Boolean = column in PERCENT
```

- [ ] **Step 4: Write `FilterText.kt`**

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.statquery.Condition
import dev.gridiron.core.statquery.Filter
import dev.gridiron.core.statquery.StatColumn
import java.math.BigDecimal

/**
 * Filters are typed as values are displayed: a percent column as a
 * percentage, everything else as is. BigDecimal keeps 0.65 ↔ 65 exact, so a
 * saved filter never reads back as "65.00000000000001".
 */
public object FilterUnits {
    /** Large enough for any season total (passing yards), small enough to reject junk. */
    public const val FILTER_LIMIT: Double = 100_000.0

    public fun toInput(column: StatColumn, stored: Double): Double =
        if (StatFormat.isPercent(column)) BigDecimal.valueOf(stored).movePointRight(2).toDouble() else stored

    public fun toStored(column: StatColumn, input: Double): Double =
        if (StatFormat.isPercent(column)) BigDecimal.valueOf(input).movePointLeft(2).toDouble() else input
}

/** "TGT ≥ 50", "CTCH% ≤ 60", "TGT 20–40". */
public fun describeFilter(filter: Filter, catalog: Catalog): String {
    val abbr = catalog.metrics[filter.column.metricId]?.abbr ?: filter.column.metricId
    fun v(x: Double) = DecimalInput.format(FilterUnits.toInput(filter.column, x))
    return when (val c = filter.condition) {
        is Condition.AtLeast -> "$abbr ≥ ${v(c.value)}"
        is Condition.AtMost -> "$abbr ≤ ${v(c.value)}"
        is Condition.Between -> "$abbr ${v(c.min)}–${v(c.max)}"
        is Condition.GreaterThan -> "$abbr > ${v(c.value)}"
        is Condition.LessThan -> "$abbr < ${v(c.value)}"
    }
}

/** One line naming everything that shapes the table, for the CSV header. */
public fun describeView(r: GridRequest, catalog: Catalog): String = buildList {
    add("Gridiron")
    add(r.season.season.toString())
    add(weeksLabel(r.season, r.weeks))
    add(r.pack.label)
    add(r.positions.label)
    add(r.scoring.name)
    if (r.perGame) add("per game")
    if (r.teams.isNotEmpty()) add(r.teams.sorted().joinToString("/"))
    r.minSnapShare?.let { add(describeFilter(Filter(StatColumn.SNAP_SHARE, Condition.AtLeast(it)), catalog)) }
    r.filters.forEach { add(describeFilter(it, catalog)) }
}.joinToString(" · ")

/** The filter sheet's stat picker: this pack's columns, then the other packs', then the rest. */
public fun filterColumnOrder(pack: StatPack): List<StatColumn> =
    (pack.columns + StatPack.entries.flatMap { it.columns } + StatColumn.entries).distinct()
```

- [ ] **Step 5: Run the unit tests**

Run: `./gradlew :core:data:test --tests '*FilterTextTest*'`
Expected: PASS. If `ScoringPresets.PPR.name` isn't "PPR", change the test's expected view line to use the actual preset name. Don't change the preset.

- [ ] **Step 6: Write the failing repository tests (real database)**

Add to `StatsRepositoryTest` (imports: `dev.gridiron.core.statquery.Condition`, `dev.gridiron.core.statquery.Filter`, `org.junit.jupiter.api.Assertions.assertFalse`):

```kotlin
    @Test
    fun `catalog lists every current team`() {
        assertEquals(32, catalog.teams.size)
        assertEquals(catalog.teams.sorted(), catalog.teams)
        assertTrue("KC" in catalog.teams)
    }

    @Test
    fun `a team filter keeps only that team's players`() = runTest {
        val season = catalog.season(2025)
        val page = repo.grid(GridRequest(season, season.defaultWeeks, StatPack.RECEIVING, teams = setOf("KC")), catalog)
        assertTrue(page.rows.isNotEmpty())
        assertTrue(page.rows.all { it.detail.contains(" · KC · ") }, page.rows.map { it.detail }.toString())
    }

    @Test
    fun `snap share and advanced filters narrow the page and the count agrees`() = runTest {
        val season = catalog.season(2025)
        val base = GridRequest(season, season.defaultWeeks, StatPack.RECEIVING, positions = PositionFilter.WR)
        val all = repo.grid(base, catalog).rows.size
        val snap = base.copy(minSnapShare = 0.75)
        val snapRows = repo.grid(snap, catalog).rows.size
        assertTrue(snapRows in 1 until all, "snap filter kept $snapRows of $all")
        assertEquals(snapRows, repo.count(snap))

        // A filter on a column the pack doesn't show.
        val carries = base.copy(filters = listOf(Filter(StatColumn.CARRIES, Condition.AtLeast(5.0))))
        val carryRows = repo.grid(carries, catalog).rows.size
        assertTrue(carryRows in 1 until all, "carries filter kept $carryRows of $all")
        assertEquals(carryRows, repo.count(carries))
    }

    @Test
    fun `filters still apply while searching by name`() = runTest {
        val season = catalog.season(2025)
        val r = GridRequest(season, season.defaultWeeks, StatPack.RECEIVING, name = "a", teams = setOf("KC"))
        val rows = repo.grid(r, catalog).rows
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.detail.contains(" · KC · ") })
    }
```

- [ ] **Step 7: Run and confirm they fail**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test --tests '*StatsRepositoryTest*'`
Expected: FAIL. `repo.count` doesn't exist yet and `catalog.teams` is empty.

- [ ] **Step 8: Share the spec builder, add `count` and load teams**

In `StatsRepository.kt`:
- Add imports `dev.gridiron.core.statquery.Condition`, `dev.gridiron.core.statquery.Filter`, `dev.gridiron.core.statquery.StatColumn`.
- Load teams in `catalog()`:

```kotlin
        val teams = executor.query(CatalogQueries.teams) { it.text(0) }
        return Catalog(seasons.toImmutableList(), metrics.associateBy { it.id }.toImmutableMap(), teams.toImmutableList())
```

Replace the spec construction inside `grid` with a call to this new private function:

```kotlin
    private fun spec(request: GridRequest, threshold: SampleThreshold?): StatQuerySpec {
        val searching = request.name.isNotBlank()
        val snap = request.minSnapShare?.let { Filter(StatColumn.SNAP_SHARE, Condition.AtLeast(it)) }
        return StatQuerySpec(
            season = request.season.season,
            weeks = request.weeks,
            columns = request.pack.columns,
            sort = listOf(Sort(request.sort, request.direction)),
            positions = request.positions.positions,
            teams = request.teams,
            // Unlike the sample qualifier, these are the user's own choices, so a search keeps them.
            filters = listOfNotNull(snap) + request.filters,
            qualifiers = listOfNotNull(threshold?.qualifier),
            // A search should find anyone; players below the bar come back unranked.
            includeUnqualified = searching,
            minGames = if (searching) 1 else threshold?.minGames ?: 1,
            mode = if (request.perGame) ValueMode.PER_GAME else ValueMode.TOTAL,
            percentiles = true,
            name = request.name.takeIf { searching },
            limit = StatQuerySpec.MAX_LIMIT,
            scoring = request.scoring,
        )
    }

    private fun threshold(request: GridRequest): SampleThreshold? =
        SampleThreshold.forRequest(request.sort, request.pack, request.playedWeeks, request.perGame)

    /** How many players [request] matches, ignoring the page limit. Backs the filter sheet's live count. */
    public suspend fun count(request: GridRequest): Int =
        executor.query(StatQueryBuilder.count(spec(request, threshold(request)))) { it.long(0).toInt() }.single()
```

In `grid`, use `val threshold = threshold(request)` and `val spec = spec(request, threshold)`. Remove the old local `searching` if it's now unused.

- [ ] **Step 9: Run the module's tests**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test :core:statquery:test`
Expected: PASS, including every existing test.

- [ ] **Step 10: Commit**

```bash
git add core/statquery core/data
git commit -m "data: team, snap-share and advanced filters on the Grid request; live count; filter text"
```

---

### Task 3: Sparkline data (`:core:data`)

**Files:**
- Create: `core/data/src/main/kotlin/dev/gridiron/core/data/Sparkline.kt`
- Modify: `core/data/src/main/kotlin/dev/gridiron/core/data/StatsRepository.kt`
- Test: `core/data/src/test/kotlin/dev/gridiron/core/data/SparklineTest.kt` (new, unit), `core/data/src/test/kotlin/dev/gridiron/core/data/StatsRepositoryTest.kt` (add)

**Interfaces:**
- Consumes: `GridPage`, `GridRequest` (Task 2).
- Produces:
  - `data class Sparkline(val weeks: IntRange, val values: List<Double?>, val labels: List<String>) { val drawable: Boolean }`.
  - `fun sparklineWeeks(season: SeasonInfo, weeks: WeekRange): IntRange?`.
  - `StatsRepository.sparklines(page: GridPage): Map<String, Sparkline>`.

- [ ] **Step 1: Write the failing unit test for the week window**

Create `core/data/src/test/kotlin/dev/gridiron/core/data/SparklineTest.kt`:

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.model.WeekRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SparklineTest {
    private val full = SeasonInfo(2025, lastWeek = 22)

    @Test
    fun `the window is the last six played weeks inside the range`() {
        assertEquals(13..18, sparklineWeeks(full, WeekRange(1, 18)))
        assertEquals(3..8, sparklineWeeks(full, WeekRange(1, 8)))
        assertEquals(5..7, sparklineWeeks(full, WeekRange(5, 7)))
        // In progress: week 2 is the last played.
        assertEquals(1..2, sparklineWeeks(SeasonInfo(2026, lastWeek = 2), WeekRange(1, 18)))
    }

    @Test
    fun `no window when there aren't two played weeks`() {
        assertNull(sparklineWeeks(full, WeekRange(7, 7)))
        assertNull(sparklineWeeks(SeasonInfo(2026, lastWeek = 2), WeekRange(5, 18)))
        assertNull(sparklineWeeks(SeasonInfo(2026, lastWeek = 1), WeekRange(1, 18)))
    }

    @Test
    fun `drawable needs two points`() {
        assertFalse(Sparkline(1..3, listOf(null, 4.0, null), listOf("–", "4", "–")).drawable)
        assertTrue(Sparkline(1..3, listOf(1.0, null, 4.0), listOf("1", "–", "4")).drawable)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :core:data:test --tests '*SparklineTest*'`
Expected: FAILS with unresolved `sparklineWeeks` and `Sparkline`.

- [ ] **Step 3: Write `Sparkline.kt`**

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.model.WeekRange

/**
 * One row's trend: the sorted stat for each week of [weeks], null where the
 * player didn't play. [labels] are the same values formatted for a screen reader.
 */
public data class Sparkline(val weeks: IntRange, val values: List<Double?>, val labels: List<String>) {
    public val drawable: Boolean get() = values.count { it != null } >= 2
}

/**
 * The last six played weeks inside [weeks], or null when fewer than two have
 * been played, since one point is not a trend.
 */
public fun sparklineWeeks(season: SeasonInfo, weeks: WeekRange): IntRange? {
    val last = minOf(weeks.last, season.lastWeek)
    val first = maxOf(weeks.first, last - 5)
    return if (last - first >= 1) first..last else null
}
```

- [ ] **Step 4: Run the unit test**

Run: `./gradlew :core:data:test --tests '*SparklineTest*'`
Expected: PASS.

- [ ] **Step 5: Write the failing repository tests, including speed**

Add to `StatsRepositoryTest` (imports: `dev.gridiron.core.model.WeekRange`, `dev.gridiron.core.statquery.StatQueryBuilder`, `dev.gridiron.core.statquery.StatQuerySpec`, `dev.gridiron.core.statquery.GridLayout`, `dev.gridiron.core.database.doubleOrNull`, `dev.gridiron.core.statquery.SqlQuery`, `dev.gridiron.core.statquery.Bind`, `org.junit.jupiter.api.Assertions.assertNull`):

```kotlin
    /** One raw fact, straight from the table, independent of the query builder. */
    private suspend fun fact(playerId: String, week: Int, metric: String): Double? =
        executor.query(
            SqlQuery(
                "SELECT value FROM player_week_stat WHERE player_id = ? AND season = 2025 AND week = ? AND metric_id = ?",
                listOf(Bind.Text(playerId), Bind.Integer(week.toLong()), Bind.Text(metric)),
            ),
        ) { it.double(0) }.singleOrNull()

    @Test
    fun `sparklines are each week's own value, zero when played and empty, a gap when not played`() = runTest {
        val season = catalog.season(2025)
        val page = repo.grid(
            GridRequest(season, season.defaultWeeks, StatPack.RECEIVING, positions = PositionFilter.WR, sort = StatColumn.RECEIVING_YARDS),
            catalog,
        )
        val lines = repo.sparklines(page)
        assertEquals(page.rows.map { it.playerId }.toSet(), lines.keys)
        var gaps = 0
        var zeros = 0
        for (row in page.rows.take(60)) {
            val line = lines.getValue(row.playerId)
            assertEquals(13..18, line.weeks)
            line.weeks.forEachIndexed { i, week ->
                val played = (fact(row.playerId, week, "g") ?: 0.0) > 0
                val yards = line.values[i]
                if (!played) {
                    assertNull(yards, "${row.name} wk $week: not played but $yards")
                    gaps++
                } else {
                    // Sparse storage: a played week with no receiving-yards fact is 0, not a gap.
                    assertEquals(fact(row.playerId, week, "receiving_yards") ?: 0.0, yards!!, 1e-9, "${row.name} wk $week")
                    if (yards == 0.0) zeros++
                }
            }
        }
        assertTrue(gaps > 0, "expected at least one bye among 60 WRs over six weeks")
        println("sparkline check: $gaps gaps, $zeros played-zero weeks")
    }

    @Test
    fun `sparklines for rate and fantasy sorts match that week's single-week value`() = runTest {
        val season = catalog.season(2025)
        for (sort in listOf(StatColumn.CATCH_RATE, StatColumn.FANTASY_POINTS)) {
            val pack = if (sort == StatColumn.FANTASY_POINTS) StatPack.FANTASY else StatPack.RECEIVING
            val page = repo.grid(GridRequest(season, season.defaultWeeks, pack, sort = sort), catalog)
            val lines = repo.sparklines(page)
            val row = page.rows.first()
            val line = lines.getValue(row.playerId)
            line.weeks.forEachIndexed { i, week ->
                val spec = StatQuerySpec(
                    season = 2025, weeks = WeekRange.single(week), columns = listOf(sort),
                    playerIds = setOf(row.playerId), includeUnqualified = true, scoring = page.request.scoring,
                )
                val q = StatQueryBuilder.grid(spec)
                val expected = executor.query(q.query) { it.doubleOrNull(q.layout.valueIndex(sort)) }.singleOrNull()
                assertEquals(expected, line.values[i], "$sort ${row.name} wk $week")
            }
        }
    }

    @Test
    fun `no sparklines for a single week or an unplayed range`() = runTest {
        var queries = 0
        val counting = object : dev.gridiron.core.database.QueryExecutor {
            override suspend fun <T> query(query: SqlQuery, map: (dev.gridiron.core.database.ResultRow) -> T): List<T> {
                queries++
                return executor.query(query, map)
            }
        }
        val r = StatsRepository(counting, Locale.US)
        val season = catalog.season(2025)
        val page = r.grid(GridRequest(season, WeekRange.single(7), StatPack.RECEIVING), catalog)
        val before = queries
        assertEquals(emptyMap<String, Sparkline>(), r.sparklines(page))
        assertEquals(before, queries)
    }

    @Test
    fun `sparklines for a full page are fast`() = runTest {
        val season = catalog.season(2025)
        // The Fantasy pack with no position filter: the most rows and the most expensive (scored) column.
        val page = repo.grid(GridRequest(season, season.defaultWeeks, StatPack.FANTASY), catalog)
        repeat(2) { repo.sparklines(page) }
        val times = (1..7).map {
            val t0 = System.nanoTime()
            repo.sparklines(page)
            (System.nanoTime() - t0) / 1e6
        }.sorted()
        val median = times[times.size / 2]
        val ci = System.getenv("CI") != null
        val budget = if (ci) 1_200 else 300
        println("sparklines, ${page.rows.size} rows x 6 weeks: median %.1f ms (budget %d ms%s)".format(median, budget, if (ci) ", CI" else ""))
        assertTrue(median < budget, "median $median ms (budget ${budget}ms)")
    }
```

- [ ] **Step 6: Run and confirm they fail**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test --tests '*StatsRepositoryTest*'`
Expected: FAIL with unresolved `sparklines`.

- [ ] **Step 7: Implement `sparklines`**

Add to `StatsRepository` (imports `dev.gridiron.core.model.WeekRange`, `dev.gridiron.core.statquery.Aggregate`):

```kotlin
    /**
     * The sorted column's last six played weeks for every row on [page]. Each
     * week reuses the Grid query itself, restricted to the page's players, so
     * a week's rate or fantasy points are exactly what the Grid shows for that
     * single week. The page already decided who is listed, so no filters apply.
     */
    public suspend fun sparklines(page: GridPage): Map<String, Sparkline> {
        val r = page.request
        val window = sparklineWeeks(r.season, r.weeks) ?: return emptyMap()
        if (page.rows.isEmpty()) return emptyMap()
        val ids = page.rows.mapTo(LinkedHashSet()) { it.playerId }
        val column = r.sort
        val byWeek = window.map { week ->
            val q = StatQueryBuilder.grid(
                StatQuerySpec(
                    season = r.season.season,
                    weeks = WeekRange.single(week),
                    columns = listOf(column),
                    playerIds = ids,
                    includeUnqualified = true,
                    minGames = 1,
                    limit = StatQuerySpec.MAX_LIMIT,
                    scoring = r.scoring,
                ),
            )
            executor.query(q.query) { row ->
                // Every returned row played that week. A total with no fact is a
                // zero the database stores sparsely, not a missing week.
                val v = row.doubleOrNull(q.layout.valueIndex(column))
                    ?: if (column.aggregate is Aggregate.Total) 0.0 else null
                row.text(GridLayout.PLAYER_ID) to v
            }.toMap()
        }
        return ids.associateWith { id ->
            val values = byWeek.map { it[id] }
            Sparkline(window, values, values.map { format.format(column, it, perGame = false) })
        }
    }
```

If `Aggregate.Total` isn't accessible under that name, check `core/statquery/src/main/kotlin/dev/gridiron/core/statquery/Aggregate.kt`. It's `public sealed interface Aggregate` with `public data class Total`.

- [ ] **Step 8: Run the module's tests**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test`
Expected: PASS. Put the printed sparkline speed median in the task report.

If the local median is over 300 ms: **stop and report**. Don't raise the budget. The spec's fallback (a new per-week builder query) needs sign-off.

- [ ] **Step 9: Commit**

```bash
git add core/data
git commit -m "data: last-six-week sparklines from the Grid query, one week at a time"
```

---

### Task 4: CSV export (`:core:data`)

**Files:**
- Modify: `core/data/src/main/kotlin/dev/gridiron/core/data/GridModels.kt` (`GridRowUi`)
- Modify: `core/data/src/main/kotlin/dev/gridiron/core/data/StatsRepository.kt` (fill the new fields)
- Create: `core/data/src/main/kotlin/dev/gridiron/core/data/CsvExport.kt`
- Test: `core/data/src/test/kotlin/dev/gridiron/core/data/CsvExportTest.kt`

**Interfaces:**
- Consumes: `describeView` (Task 2), `StatFormat.MISSING`.
- Produces:
  - `GridRowUi(playerId, name, detail, position: String?, team: String?, games: Int, cells)`.
  - `object CsvExport { fun build(page: GridPage, catalog: Catalog): String; fun fileName(request: GridRequest): String }`.

- [ ] **Step 1: Write the failing test**

Create `core/data/src/test/kotlin/dev/gridiron/core/data/CsvExportTest.kt`:

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.StatColumn
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CsvExportTest {
    private val season = SeasonInfo(2025, lastWeek = 22)
    private val catalog = Catalog(persistentListOf(season), persistentMapOf())
    private val request = GridRequest(season, WeekRange(1, 8), StatPack.RECEIVING)

    private fun row(name: String, pos: String?, team: String?, vararg cells: String) =
        GridRowUi("id-$name", name, "detail", pos, team, 8, cells.map { CellUi(it, null) }.let { persistentListOf(*it.toTypedArray()) })

    private val page = GridPage(
        request,
        persistentListOf(
            ColumnUi(StatColumn.TARGETS, "TGT", null),
            ColumnUi(StatColumn.CATCH_RATE, "CTCH%", null),
        ),
        persistentListOf(
            row("Amon-Ra St. Brown", "WR", "DET", "88", "71.6%"),
            row("Smith, Jr. \"Deuce\"", null, null, "12", StatFormat.MISSING),
        ),
        threshold = null,
    )

    @Test
    fun `writes a view line, a header, one row per player and the attribution, with CRLF`() {
        val lines = CsvExport.build(page, catalog).split("\r\n")
        assertEquals("# Gridiron · 2025 · Wk 1–8 · Receiving · All · PPR", lines[0])
        assertEquals("Rank,Player,Pos,Team,Games,TGT,CTCH%", lines[1])
        assertEquals("1,Amon-Ra St. Brown,WR,DET,8,88,71.6%", lines[2])
        assertEquals("2,\"Smith, Jr. \"\"Deuce\"\"\",,,8,12,", lines[3])
        assertEquals("# Data: nflverse (CC BY 4.0)", lines[4])
        assertEquals("", lines[5]) // trailing CRLF
        assertEquals(6, lines.size)
    }

    @Test
    fun `file name names the season and pack`() {
        assertEquals("gridiron-2025-receiving.csv", CsvExport.fileName(request))
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :core:data:test --tests '*CsvExportTest*'`
Expected: FAILS with unresolved `CsvExport` and a `GridRowUi` constructor mismatch.

- [ ] **Step 3: Add the structured row fields and fill them**

In `GridModels.kt`, replace `GridRowUi` with:

```kotlin
public data class GridRowUi(
    val playerId: String,
    val name: String,
    /** "WR · KC · 17 g" */
    val detail: String,
    val position: String?,
    val team: String?,
    val games: Int,
    val cells: ImmutableList<CellUi>,
)
```

In `StatsRepository.grid`, read the raw values and pass them through:

```kotlin
        val rows = executor.query(q.query) { r ->
            val games = r.long(GridLayout.GAMES).toInt()
            val position = r.textOrNull(GridLayout.POSITION)
            val team = r.textOrNull(GridLayout.TEAM)
            GridRowUi(
                playerId = r.text(GridLayout.PLAYER_ID),
                name = r.text(GridLayout.FULL_NAME),
                detail = "${position ?: "–"} · ${team ?: "FA"} · $games g",
                position = position,
                team = team,
                games = games,
                cells = spec.columns.map { column ->
                    val pct = r.doubleOrNull(layout.percentileIndex(column))
                    CellUi(
                        text = format.format(column, r.doubleOrNull(layout.valueIndex(column)), request.perGame),
                        heat = pct?.let { ((it - 0.5) * 2).toFloat() },
                    )
                }.toImmutableList(),
            )
        }
```

- [ ] **Step 4: Write `CsvExport.kt`**

```kotlin
package dev.gridiron.core.data

/**
 * The Grid as a CSV (RFC 4180, CRLF), values exactly as displayed. A first
 * `#` line describes the view, since a spreadsheet shows it as its first row,
 * and the last line carries the nflverse attribution its license asks for.
 */
public object CsvExport {
    public fun build(page: GridPage, catalog: Catalog): String {
        val out = StringBuilder()
        fun line(fields: List<String>) {
            fields.joinTo(out, ",") { field(it) }
            out.append("\r\n")
        }
        line(listOf("# " + describeView(page.request, catalog)))
        line(listOf("Rank", "Player", "Pos", "Team", "Games") + page.columns.map { it.header })
        page.rows.forEachIndexed { i, row ->
            line(
                listOf((i + 1).toString(), row.name, row.position.orEmpty(), row.team.orEmpty(), row.games.toString()) +
                    row.cells.map { if (it.text == StatFormat.MISSING) "" else it.text },
            )
        }
        line(listOf("# Data: nflverse (CC BY 4.0)"))
        return out.toString()
    }

    public fun fileName(request: GridRequest): String =
        "gridiron-${request.season.season}-${request.pack.name.lowercase()}.csv"

    private fun field(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
```

- [ ] **Step 5: Run the tests**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test`
Expected: PASS. If any other module constructs `GridRowUi` (`grep -rn "GridRowUi(" --include=*.kt core feature app`), fix it to pass `position`, `team` and `games`.

- [ ] **Step 6: Commit**

```bash
git add core/data
git commit -m "data: CSV export of the Grid as displayed"
```

---

### Task 5: `Sparkline` composable (`:core:charts`)

**Files:**
- Create: `core/charts/src/main/kotlin/dev/gridiron/core/charts/Sparkline.kt`
- Test: `core/charts/src/test/kotlin/dev/gridiron/core/charts/ChartsScreenshotTest.kt` (add a test)

**Interfaces:**
- Produces: `@Composable fun Sparkline(values: ImmutableList<Float?>, color: Color, modifier: Modifier = Modifier)`. It draws nothing when fewer than two values are non-null.

- [ ] **Step 1: Write the screenshot test**

Add to `ChartsScreenshotTest` (imports `androidx.compose.foundation.layout.padding`, `androidx.compose.foundation.layout.Row`, `androidx.compose.material3.MaterialTheme`):

```kotlin
    @Composable
    private fun SparklinesContent() {
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        Column(Modifier.padding(16.dp)) {
            listOf(
                listOf(5f, 7f, null, 9f, 4f, 8f),   // a bye in the middle
                listOf(0f, 0f, 0f, 0f, 0f, 0f),     // flat
                listOf(null, 3f, null, null, 6f, null), // isolated points
                listOf(12f, null, null, null, null, null), // one point: draws nothing
            ).forEach { values ->
                Sparkline(values.toImmutableList(), color, Modifier.padding(vertical = 6.dp).size(44.dp, 14.dp))
            }
        }
    }

    @Test
    fun sparklinesLightAndDark() {
        compose.setContent { GridironTheme(darkTheme = false) { SparklinesContent() } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/charts_7_sparklines.png")
    }

    @Test
    fun sparklinesDark() {
        compose.setContent { GridironTheme(darkTheme = true) { SparklinesContent() } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/charts_8_sparklines_dark.png")
    }
```

- [ ] **Step 2: Run and confirm it fails to compile**

Run: `./gradlew :core:charts:testDebugUnitTest --tests '*ChartsScreenshotTest*'`
Expected: FAILS with unresolved `Sparkline`.

- [ ] **Step 3: Implement**

```kotlin
package dev.gridiron.core.charts

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList

/**
 * A tiny trend line. Nulls are gaps: the line breaks rather than dropping to
 * zero, and a point with no neighbor still shows as a dot. The last point is
 * emphasized. Draws nothing for fewer than two points. Decorative: the
 * containing row describes the values for screen readers.
 */
@Composable
public fun Sparkline(values: ImmutableList<Float?>, color: Color, modifier: Modifier = Modifier) {
    val present = values.filterNotNull()
    if (present.size < 2) return
    Canvas(modifier) {
        val pad = 2.dp.toPx()
        val min = present.min()
        val span = (present.max() - min).takeIf { it > 0f }
        val stepX = (size.width - 2 * pad) / (values.size - 1).coerceAtLeast(1)
        val h = size.height - 2 * pad
        fun at(i: Int, v: Float) = Offset(
            pad + i * stepX,
            // A flat line sits in the middle rather than on the floor.
            pad + if (span == null) h / 2 else h - (v - min) / span * h,
        )

        val stroke = 1.5.dp.toPx()
        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val p = at(i, v)
            val next = values.getOrNull(i + 1)
            if (next != null) drawLine(color, p, at(i + 1, next), strokeWidth = stroke, cap = StrokeCap.Round)
            val isolated = values.getOrNull(i - 1) == null && next == null
            if (isolated) drawCircle(color, radius = stroke, center = p)
        }
        val last = values.indexOfLast { it != null }
        drawCircle(color, radius = 2.dp.toPx(), center = at(last, values[last]!!))
    }
}
```

- [ ] **Step 4: Run and record**

Run: `./gradlew :core:charts:testDebugUnitTest :core:charts:recordRoborazziDebug`
Expected: PASS. Open `core/charts/build/outputs/roborazzi/charts_7_sparklines.png` and check four rows: a line with a break, a flat middle line, two dots with the right one larger, and a blank row.

- [ ] **Step 5: Commit**

```bash
git add core/charts
git commit -m "charts: Sparkline, with gaps for missed weeks"
```

---

### Task 6: Grid ViewModel: filters, sparklines, draft count

**Files:**
- Modify: `feature/players/src/main/kotlin/dev/gridiron/feature/players/GridViewModel.kt`
- Test: `feature/players/src/test/kotlin/dev/gridiron/feature/players/GridViewModelTest.kt` (add to `GridViewModelTest` and `GridReduceTest`)

**Interfaces:**
- Consumes:
  - `StatsRepository.count(GridRequest): Int` and `StatsRepository.sparklines(GridPage): Map<String, Sparkline>` (Tasks 2 and 3).
  - `GridRequest.teams/minSnapShare/filters`.
- Produces:
  - New `GridEvent`s: `TeamsSelected(teams: Set<String>)`, `MinSnapShareSelected(share: Double?)`, `FiltersApplied(filters: List<Filter>)`, `FilterDraftChanged(filters: List<Filter>)`, `data object FilterSheetClosed`.
  - `sealed interface DraftCount { data object Counting; data class Matches(val count: Int); data object Unavailable }`.
  - `GridUiState.Ready` gains `sparklines: ImmutableMap<String, Sparkline> = persistentMapOf()` and `draftCount: DraftCount? = null`.
  - `GridViewModel(..., debounceMillis: Long = 150, countDebounceMillis: Long = 250)`.

- [ ] **Step 1: Write the failing reduce tests**

Add to `GridReduceTest` (imports `dev.gridiron.core.statquery.Condition`, `dev.gridiron.core.statquery.Filter`):

```kotlin
    @Test
    fun `team, snap and advanced filters survive pack and season changes`() {
        val f = Filter(StatColumn.TARGETS, Condition.AtLeast(50.0))
        val r = reduce(
            GridEvent.TeamsSelected(setOf("KC")),
            GridEvent.MinSnapShareSelected(0.5),
            GridEvent.FiltersApplied(listOf(f)),
            GridEvent.PackSelected(StatPack.RUSHING),
            GridEvent.SeasonSelected(2024),
        )
        assertEquals(setOf("KC"), r.teams)
        assertEquals(0.5, r.minSnapShare)
        assertEquals(listOf(f), r.filters)
    }

    @Test
    fun `draft edits and closing the sheet never touch the request`() {
        val f = Filter(StatColumn.TARGETS, Condition.AtLeast(50.0))
        assertEquals(start, reduce(GridEvent.FilterDraftChanged(listOf(f)), GridEvent.FilterSheetClosed))
    }
```

- [ ] **Step 2: Write the failing ViewModel tests**

Add to `GridViewModelTest` (imports `dev.gridiron.core.statquery.Condition`, `dev.gridiron.core.statquery.Filter`, `dev.gridiron.core.database.QueryExecutor`, `dev.gridiron.core.database.ResultRow`, `dev.gridiron.core.statquery.SqlQuery`, `kotlinx.coroutines.test.advanceTimeBy`, `kotlinx.coroutines.test.runCurrent`, `dev.gridiron.core.data.sparklineWeeks`):

```kotlin
    private fun executor(onQuery: (SqlQuery) -> Unit): QueryExecutor = object : QueryExecutor {
        override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> {
            onQuery(query)
            return executor.query(query, map)
        }
    }

    @Test
    fun `sparklines load for the current page`() = runTest(dispatcher) {
        val vm = viewModel()
        val s = ready(vm)
        val page = s.page!!
        assertEquals(page.rows.map { it.playerId }.toSet(), s.sparklines.keys)
        val window = sparklineWeeks(page.request.season, page.request.weeks)
        assertTrue(s.sparklines.values.all { it.weeks == window })
    }

    @Test
    fun `a sparkline failure leaves the Grid alone`() = runTest(dispatcher) {
        // Only the sparkline queries restrict to a player list.
        val failing = executor { if ("player_id IN (" in it.sql) error("boom") }
        val vm = GridViewModel(StatsRepository(failing, Locale.US), ScoringRepository(prefs), CompareTrayRepository(prefs))
        val s = ready(vm)
        assertTrue(s.page!!.rows.isNotEmpty())
        assertNull(s.error)
        assertTrue(s.sparklines.isEmpty())
    }

    @Test
    fun `the draft count is debounced, counts the draft and clears when the sheet closes`() = runTest(dispatcher) {
        var counts = 0
        val counting = executor { if (it.sql.contains("SELECT COUNT(*)")) counts++ }
        val vm = GridViewModel(StatsRepository(counting, Locale.US), ScoringRepository(prefs), CompareTrayRepository(prefs))
        ready(vm)
        val before = counts

        listOf(10.0, 30.0, 60.0).forEach {
            vm.onEvent(GridEvent.FilterDraftChanged(listOf(Filter(StatColumn.TARGETS, Condition.AtLeast(it)))))
        }
        runCurrent()
        assertEquals(DraftCount.Counting, (vm.state.value as GridUiState.Ready).draftCount)
        val s = ready(vm)
        assertEquals("one count for three quick edits", before + 1, counts)
        val applied = s.request.copy(filters = listOf(Filter(StatColumn.TARGETS, Condition.AtLeast(60.0))))
        assertEquals(DraftCount.Matches(repo.count(applied)), s.draftCount)
        assertTrue("the draft must not reach the Grid", s.request.filters.isEmpty())

        vm.onEvent(GridEvent.FilterSheetClosed)
        assertNull(ready(vm).draftCount)
    }

    @Test
    fun `applying filters narrows the page and clears the draft`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEvent(GridEvent.SeasonSelected(2025))
        val before = ready(vm).page!!.rows.size
        val f = Filter(StatColumn.TARGETS, Condition.AtLeast(100.0))
        vm.onEvent(GridEvent.FilterDraftChanged(listOf(f)))
        vm.onEvent(GridEvent.FiltersApplied(listOf(f)))
        val s = ready(vm)
        assertEquals(listOf(f), s.page!!.request.filters)
        assertTrue(s.page!!.rows.size in 1 until before)
        assertNull(s.draftCount)
    }

    @Test
    fun `a failed count says so without blocking`() = runTest(dispatcher) {
        val failing = executor { if (it.sql.contains("SELECT COUNT(*)")) error("boom") }
        val vm = GridViewModel(StatsRepository(failing, Locale.US), ScoringRepository(prefs), CompareTrayRepository(prefs))
        ready(vm)
        vm.onEvent(GridEvent.FilterDraftChanged(listOf(Filter(StatColumn.TARGETS, Condition.AtLeast(10.0)))))
        val s = ready(vm)
        assertEquals(DraftCount.Unavailable, s.draftCount)
        assertNull(s.error)
    }
```

- [ ] **Step 3: Run and confirm they fail**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :feature:players:testDebugUnitTest --tests '*GridViewModelTest*' --tests '*GridReduceTest*'`
Expected: FAILS to compile (unknown events, `DraftCount`, `sparklines`).

- [ ] **Step 4: Implement**

In `GridViewModel.kt`:

Add the events to `GridEvent`:

```kotlin
    data class TeamsSelected(val teams: Set<String>) : GridEvent
    data class MinSnapShareSelected(val share: Double?) : GridEvent
    /** Commits the filter sheet's complete rows. */
    data class FiltersApplied(val filters: List<Filter>) : GridEvent
    /** The sheet's complete rows changed; counts them without touching the Grid. */
    data class FilterDraftChanged(val filters: List<Filter>) : GridEvent
    data object FilterSheetClosed : GridEvent
```

Add below `GridUiState`:

```kotlin
/** The filter sheet's live "N players match". */
sealed interface DraftCount {
    data object Counting : DraftCount
    data class Matches(val count: Int) : DraftCount
    data object Unavailable : DraftCount
}
```

Add to `GridUiState.Ready`'s constructor, after `editingSlot`:

```kotlin
        /** Sparklines for [page]'s rows, by player id; empty until they load or if they fail. */
        val sparklines: ImmutableMap<String, Sparkline> = persistentMapOf(),
        /** The open filter sheet's match count; null when the sheet is closed. */
        val draftCount: DraftCount? = null,
```

Change the constructor signature to:

```kotlin
class GridViewModel(
    private val repository: StatsRepository,
    private val scoring: ScoringRepository,
    private val tray: CompareTrayRepository,
    /** Coalesces bursts (typing, dragging the week slider) into one query. */
    debounceMillis: Long = 150,
    /** Coalesces filter-sheet typing into one count. */
    countDebounceMillis: Long = 250,
) : ViewModel() {
```

Add the new state holders beside `editingSlot`:

```kotlin
    /** Sparklines tagged with the page they were computed for, so a stale set is never shown. */
    private val sparklines = MutableStateFlow<Pair<GridPage, Map<String, Sparkline>>?>(null)
    private val draft = MutableStateFlow<List<Filter>?>(null)
    private val draftCount = MutableStateFlow<DraftCount?>(null)
```

Replace the `state` definition's second `combine` and `Extras` with:

```kotlin
        }.combine(
            combine(scoring.profiles, trayUi, message, editingSlot, combine(sparklines, draftCount, ::Pair), ::Extras),
        ) { base, extras ->
            if (base is GridUiState.Ready) {
                val lines = extras.lines.first?.takeIf { (page, _) -> page == base.page }?.second.orEmpty()
                base.copy(
                    profiles = extras.profiles,
                    tray = extras.tray,
                    message = extras.message,
                    editingSlot = extras.editingSlot,
                    sparklines = lines.toImmutableMap(),
                    draftCount = extras.lines.second,
                )
            } else {
                base
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, GridUiState.Loading)

    private data class Extras(
        val profiles: ImmutableList<ScoringProfile>,
        val tray: ImmutableList<TraySlotUi>,
        val message: String?,
        val editingSlot: CompareSlot?,
        val lines: Pair<Pair<GridPage, Map<String, Sparkline>>?, DraftCount?>,
    )
```

In `init`, add two collectors after the page collector:

```kotlin
        viewModelScope.launch {
            // After each page, never before it: the table must not wait on its sparklines.
            lastPage.filterNotNull()
                .mapLatest { page ->
                    val lines = try {
                        repository.sparklines(page)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyMap()
                    }
                    sparklines.value = page to lines
                }
                .collect()
        }
        viewModelScope.launch {
            draft.debounce(countDebounceMillis)
                .mapLatest { filters ->
                    val r = request.value
                    if (filters == null || r == null) return@mapLatest
                    draftCount.value = try {
                        DraftCount.Matches(repository.count(r.copy(filters = filters)))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        DraftCount.Unavailable
                    }
                }
                .collect()
        }
```

In `onEvent`, add these cases to the first `when`. Draft events return early. `FiltersApplied` clears the draft and falls through to `reduce`.

```kotlin
            is GridEvent.FilterDraftChanged -> {
                draft.value = event.filters
                draftCount.value = DraftCount.Counting
                return
            }
            GridEvent.FilterSheetClosed -> {
                draft.value = null
                draftCount.value = null
                return
            }
            is GridEvent.FiltersApplied -> {
                draft.value = null
                draftCount.value = null
            }
```

In `reduce`, add:

```kotlin
            is GridEvent.TeamsSelected -> r.copy(teams = event.teams)
            is GridEvent.MinSnapShareSelected -> r.copy(minSnapShare = event.share)
            is GridEvent.FiltersApplied -> r.copy(filters = event.filters)
            is GridEvent.FilterDraftChanged -> r
            GridEvent.FilterSheetClosed -> r
```

New imports: `dev.gridiron.core.data.Sparkline`, `dev.gridiron.core.statquery.Filter`, `kotlinx.collections.immutable.ImmutableMap`, `kotlinx.collections.immutable.persistentMapOf`, `kotlinx.collections.immutable.toImmutableMap`.

Also: `debounce` on a `MutableStateFlow` whose value becomes `null` still emits `null`, which `mapLatest` ignores, and any count running at that moment is cancelled by the next emission. The `FilterSheetClosed` test covers this.

- [ ] **Step 5: Run the module's tests**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :feature:players:testDebugUnitTest`
Expected: PASS, including the existing `a burst of events runs one query for the final state`.

That test counts every query. Sparklines now add queries after each page, so a strict `before + 1` will fail. Keep its intent (one **grid** query per burst) by counting only grid queries: `if (!("player_id IN (" in query.sql)) queries++`. Put the edit in this task's commit.

- [ ] **Step 6: Commit**

```bash
git add feature/players
git commit -m "grid: team, snap and advanced filter events; sparklines after each page; debounced draft count"
```

---

### Task 7: Grid screen: chip row, team sheet, filter sheet, sparklines

**Files:**
- Create: `feature/players/src/main/kotlin/dev/gridiron/feature/players/FilterDraft.kt`
- Create: `feature/players/src/main/kotlin/dev/gridiron/feature/players/FilterSheets.kt`
- Modify: `feature/players/src/main/kotlin/dev/gridiron/feature/players/GridScreen.kt`
- Modify: `feature/players/build.gradle.kts` (add `implementation(projects.core.charts)`)
- Test: `feature/players/src/test/kotlin/dev/gridiron/feature/players/FilterDraftTest.kt` (new), `feature/players/src/test/kotlin/dev/gridiron/feature/players/GridScreenTest.kt` (add)

**Interfaces:**
- Consumes:
  - Task 6 events and `DraftCount`, and `GridUiState.Ready.sparklines`.
  - Task 2's `FilterUnits`, `describeFilter`, `filterColumnOrder`, `StatFormat.isPercent`, `GridRequest.SNAP_SHARE_CHOICES`, `GridRequest.MAX_FILTERS`.
  - Task 1's `DecimalInput`, Task 5's `Sparkline`.
- Produces:
  - `FilterOp`, `FilterRowDraft`, `FilterDraft` (internal).
  - `internal fun TeamSheet(...)` and `internal fun FilterSheet(...)` composables.
  - Test tags: `chip:teams`, `chip:snaps`, `chip:filters`, `filter:add`, `filter:apply`, `filter:value:<index>`, `filter:second:<index>`, `spark:<playerId>`.

- [ ] **Step 1: Write the failing draft-model tests**

Create `feature/players/src/test/kotlin/dev/gridiron/feature/players/FilterDraftTest.kt`:

```kotlin
package dev.gridiron.feature.players

import dev.gridiron.core.statquery.Condition
import dev.gridiron.core.statquery.Filter
import dev.gridiron.core.statquery.StatColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterDraftTest {
    private fun row(column: StatColumn, op: FilterOp, a: String, b: String = "") = FilterRowDraft(0, column, op, a, b)

    @Test
    fun `complete rows become filters in stored units`() {
        assertEquals(Filter(StatColumn.TARGETS, Condition.AtLeast(50.0)), row(StatColumn.TARGETS, FilterOp.AT_LEAST, "50").toFilter())
        assertEquals(Filter(StatColumn.CATCH_RATE, Condition.AtMost(0.6)), row(StatColumn.CATCH_RATE, FilterOp.AT_MOST, "60").toFilter())
        assertEquals(Filter(StatColumn.TARGETS, Condition.Between(20.0, 40.5)), row(StatColumn.TARGETS, FilterOp.BETWEEN, "20", "40,5").toFilter())
        assertEquals(Filter(StatColumn.PASSING_YARDS, Condition.AtLeast(4500.0)), row(StatColumn.PASSING_YARDS, FilterOp.AT_LEAST, "4500").toFilter())
    }

    @Test
    fun `bad or partial input is incomplete, never an exception`() {
        for (bad in listOf("", "-", ".", "abc", "1e3", "NaN", "Infinity", "100001")) {
            assertNull(bad, row(StatColumn.TARGETS, FilterOp.AT_LEAST, bad).toFilter())
        }
        assertNull(row(StatColumn.TARGETS, FilterOp.BETWEEN, "40", "20").toFilter()) // min > max is not swapped
        assertNull(row(StatColumn.TARGETS, FilterOp.BETWEEN, "20", "").toFilter())
        assertFalse(row(StatColumn.TARGETS, FilterOp.AT_LEAST, "").showsError)
        assertTrue(row(StatColumn.TARGETS, FilterOp.AT_LEAST, "abc").showsError)
        assertTrue(row(StatColumn.TARGETS, FilterOp.BETWEEN, "40", "20").showsError)
    }

    @Test
    fun `a draft round-trips applied filters and respects the limit`() {
        val applied = listOf(
            Filter(StatColumn.CATCH_RATE, Condition.AtLeast(0.65)),
            Filter(StatColumn.TARGETS, Condition.Between(20.0, 40.0)),
        )
        val draft = FilterDraft.of(applied)
        assertEquals("65", draft.rows[0].first)
        assertEquals(applied, draft.complete)

        var d = FilterDraft.of(emptyList())
        repeat(10) { d = d.add(StatColumn.TARGETS) }
        assertEquals(8, d.rows.size)
        assertFalse(d.canAdd)
        assertEquals(7, d.remove(d.rows.first().id).rows.size)
        assertTrue(d.clear().rows.isEmpty())
    }
}
```

- [ ] **Step 2: Run and confirm it fails**

Run: `./gradlew :feature:players:testDebugUnitTest --tests '*FilterDraftTest*'`
Expected: FAILS with unresolved `FilterRowDraft` and friends.

- [ ] **Step 3: Implement `FilterDraft.kt`**

```kotlin
package dev.gridiron.feature.players

import dev.gridiron.core.data.DecimalInput
import dev.gridiron.core.data.FilterUnits
import dev.gridiron.core.data.GridRequest
import dev.gridiron.core.statquery.Condition
import dev.gridiron.core.statquery.Filter
import dev.gridiron.core.statquery.StatColumn

internal enum class FilterOp(val symbol: String) { AT_LEAST("≥"), AT_MOST("≤"), BETWEEN("between") }

/** One row of the filter sheet as typed: values are text in display units. */
internal data class FilterRowDraft(
    val id: Int,
    val column: StatColumn,
    val op: FilterOp,
    val first: String = "",
    val second: String = "",
) {
    /** The filter this row means, or null while it's incomplete or invalid. */
    fun toFilter(): Filter? {
        val a = value(first) ?: return null
        return when (op) {
            FilterOp.AT_LEAST -> Filter(column, Condition.AtLeast(a))
            FilterOp.AT_MOST -> Filter(column, Condition.AtMost(a))
            FilterOp.BETWEEN -> {
                val b = value(second) ?: return null
                if (a > b) null else Filter(column, Condition.Between(a, b))
            }
        }
    }

    /** A fresh, empty row isn't an error yet; anything typed that doesn't parse is. */
    val showsError: Boolean
        get() = toFilter() == null && (first.isNotBlank() || second.isNotBlank())

    private fun value(text: String): Double? =
        (DecimalInput.parse(text, FilterUnits.FILTER_LIMIT) as? DecimalInput.Result.Value)
            ?.value?.let { FilterUnits.toStored(column, it) }

    companion object {
        fun from(id: Int, filter: Filter): FilterRowDraft {
            fun t(x: Double) = DecimalInput.format(FilterUnits.toInput(filter.column, x))
            return when (val c = filter.condition) {
                is Condition.AtLeast -> FilterRowDraft(id, filter.column, FilterOp.AT_LEAST, t(c.value))
                is Condition.AtMost -> FilterRowDraft(id, filter.column, FilterOp.AT_MOST, t(c.value))
                is Condition.Between -> FilterRowDraft(id, filter.column, FilterOp.BETWEEN, t(c.min), t(c.max))
                // The sheet never creates these; show them as their inclusive neighbors.
                is Condition.GreaterThan -> FilterRowDraft(id, filter.column, FilterOp.AT_LEAST, t(c.value))
                is Condition.LessThan -> FilterRowDraft(id, filter.column, FilterOp.AT_MOST, t(c.value))
            }
        }
    }
}

/** The filter sheet's working copy. Nothing reaches the Grid until Apply. */
internal data class FilterDraft(val rows: List<FilterRowDraft>, private val nextId: Int) {
    val complete: List<Filter> get() = rows.mapNotNull { it.toFilter() }
    val canAdd: Boolean get() = rows.size < GridRequest.MAX_FILTERS

    fun add(column: StatColumn): FilterDraft =
        if (!canAdd) this else FilterDraft(rows + FilterRowDraft(nextId, column, FilterOp.AT_LEAST), nextId + 1)

    fun update(row: FilterRowDraft): FilterDraft = copy(rows = rows.map { if (it.id == row.id) row else it })
    fun remove(id: Int): FilterDraft = copy(rows = rows.filterNot { it.id == id })
    fun clear(): FilterDraft = copy(rows = emptyList())

    companion object {
        fun of(filters: List<Filter>): FilterDraft =
            FilterDraft(filters.mapIndexed { i, f -> FilterRowDraft.from(i, f) }, filters.size)
    }
}
```

- [ ] **Step 4: Run the draft tests**

Run: `./gradlew :feature:players:testDebugUnitTest --tests '*FilterDraftTest*'`
Expected: PASS.

- [ ] **Step 5: Write the sheets**

Add `implementation(projects.core.charts)` to `feature/players/build.gradle.kts` dependencies.

Create `FilterSheets.kt`:

```kotlin
package dev.gridiron.feature.players

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.Catalog
import dev.gridiron.core.data.StatFormat
import dev.gridiron.core.data.StatPack
import dev.gridiron.core.data.filterColumnOrder
import dev.gridiron.core.statquery.Filter
import dev.gridiron.core.statquery.StatColumn

/** Team toggles that apply as you tap. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TeamSheet(teams: List<String>, selected: Set<String>, onChange: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Teams", style = MaterialTheme.typography.titleMedium)
            Text(
                "By current team: a traded player counts for his new team in every week.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = selected.isEmpty(), onClick = { onChange(emptySet()) }, label = { Text("All teams") })
                teams.forEach { team ->
                    FilterChip(
                        selected = team in selected,
                        onClick = { onChange(if (team in selected) selected - team else selected + team) },
                        label = { Text(team) },
                        modifier = Modifier.testTag("team:$team"),
                    )
                }
            }
        }
    }
}

/**
 * Edits a draft of the advanced filters. Every change to the draft's complete
 * rows is reported for the live count; nothing reaches the Grid until Apply.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilterSheet(
    catalog: Catalog,
    pack: StatPack,
    sort: StatColumn,
    perGame: Boolean,
    applied: List<Filter>,
    count: DraftCount?,
    onDraftChanged: (List<Filter>) -> Unit,
    onApply: (List<Filter>) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(FilterDraft.of(applied)) }
    LaunchedEffect(draft.complete) { onDraftChanged(draft.complete) }
    val columns = remember(pack) { filterColumnOrder(pack) }
    fun name(c: StatColumn) = catalog.metrics[c.metricId]?.name ?: c.metricId

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { draft = draft.clear() }, enabled = draft.rows.isNotEmpty()) { Text("Clear all") }
            }
            if (perGame) {
                Text("Values are per game.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            draft.rows.forEachIndexed { index, row ->
                FilterRow(index, row, columns, ::name, onChange = { draft = draft.update(it) }, onRemove = { draft = draft.remove(row.id) })
            }
            TextButton(
                onClick = { draft = draft.add(sort) },
                enabled = draft.canAdd,
                modifier = Modifier.testTag("filter:add"),
            ) { Text("+ Add filter") }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (count) {
                        null, DraftCount.Counting -> "Counting…"
                        is DraftCount.Matches -> if (count.count == 1) "1 player matches" else "${count.count} players match"
                        DraftCount.Unavailable -> "Count unavailable"
                    },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                )
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onApply(draft.complete) }, modifier = Modifier.testTag("filter:apply")) { Text("Apply") }
            }
        }
    }
}

@Composable
private fun FilterRow(
    index: Int,
    row: FilterRowDraft,
    columns: List<StatColumn>,
    name: (StatColumn) -> String,
    onChange: (FilterRowDraft) -> Unit,
    onRemove: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    val percent = StatFormat.isPercent(row.column)
    val error = row.showsError
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TextButton(onClick = { picking = true }) { Text(name(row.column) + " ▾", maxLines = 1) }
                DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                    columns.forEach { c ->
                        DropdownMenuItem(text = { Text(name(c)) }, onClick = { picking = false; onChange(row.copy(column = c)) })
                    }
                }
            }
            TextButton(
                onClick = onRemove,
                modifier = Modifier.size(48.dp).semantics { contentDescription = "Remove filter on ${name(row.column)}" },
            ) { Text("✕") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterOp.entries.forEach { op ->
                FilterChip(selected = row.op == op, onClick = { onChange(row.copy(op = op)) }, label = { Text(op.symbol) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val suffix: (@Composable () -> Unit)? = if (percent) ({ Text("%") }) else null
            OutlinedTextField(
                value = row.first,
                onValueChange = { onChange(row.copy(first = it)) },
                label = { Text(if (row.op == FilterOp.BETWEEN) "Min" else "Value") },
                suffix = suffix,
                isError = error,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f).testTag("filter:value:$index"),
            )
            if (row.op == FilterOp.BETWEEN) {
                OutlinedTextField(
                    value = row.second,
                    onValueChange = { onChange(row.copy(second = it)) },
                    label = { Text("Max") },
                    suffix = suffix,
                    isError = error,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f).testTag("filter:second:$index"),
                )
            }
        }
    }
}
```

- [ ] **Step 6: Wire the screen**

In `GridScreen.kt`:

1. After the position `ChipRow { … }`, add the filter chip row. It uses local state `var showTeams by remember { mutableStateOf(false) }` and `var showFilters by remember { mutableStateOf(false) }`, declared beside `showWeeks`:

```kotlin
            ChipRow {
                val teams = r.teams
                FilterChip(
                    selected = teams.isNotEmpty(),
                    onClick = { showTeams = true },
                    label = { Text(when (teams.size) { 0 -> "All teams"; 1 -> teams.single(); else -> "${teams.size} teams" }) },
                    modifier = Modifier.testTag("chip:teams"),
                )
                SnapChip(r.minSnapShare) { onEvent(GridEvent.MinSnapShareSelected(it)) }
                FilterChip(
                    selected = r.filters.isNotEmpty(),
                    onClick = { showFilters = true },
                    label = { Text(if (r.filters.isEmpty()) "Filters" else "Filters (${r.filters.size})") },
                    modifier = Modifier.testTag("chip:filters"),
                )
            }
```

2. Add below `ChipRow`:

```kotlin
@Composable
private fun SnapChip(share: Double?, onSelect: (Double?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun label(s: Double?) = if (s == null) "Any snaps" else "${(s * 100).toInt()}%+ snaps"
    Box {
        FilterChip(selected = share != null, onClick = { open = true }, label = { Text(label(share)) }, modifier = Modifier.testTag("chip:snaps"))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (listOf<Double?>(null) + GridRequest.SNAP_SHARE_CHOICES).forEach { s ->
                DropdownMenuItem(text = { Text(label(s)) }, onClick = { open = false; onSelect(s) })
            }
        }
    }
}
```

3. In `Summary`, add the filter text after the threshold:

```kotlin
        page?.threshold?.let(::add)
        state.request.filters.forEach { add(describeFilter(it, state.catalog)) }
```

4. After the `WeeksSheet` line at the bottom of `GridContent`, show the sheets:

```kotlin
    if (showTeams) {
        TeamSheet(state.catalog.teams, r.teams, onChange = { onEvent(GridEvent.TeamsSelected(it)) }, onDismiss = { showTeams = false })
    }
    if (showFilters) {
        FilterSheet(
            catalog = state.catalog,
            pack = r.pack,
            sort = r.sort,
            perGame = r.perGame,
            applied = r.filters,
            count = state.draftCount,
            onDraftChanged = { onEvent(GridEvent.FilterDraftChanged(it)) },
            onApply = {
                onEvent(GridEvent.FiltersApplied(it))
                showFilters = false
            },
            onDismiss = {
                onEvent(GridEvent.FilterSheetClosed)
                showFilters = false
            },
        )
    }
```

5. Pass sparklines to the table: `PlayerTable(page, state.heat, state.sparklines, …)`. Give `PlayerTable` a parameter `sparklines: ImmutableMap<String, Sparkline>`. Replace the frozen cell's detail `Text` with:

```kotlin
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(row.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        sparklines[row.playerId]?.takeIf { it.drawable }?.let { line ->
                            val values = remember(line) { line.values.map { it?.toFloat() }.toImmutableList() }
                            Sparkline(
                                values,
                                MaterialTheme.colorScheme.onSurfaceVariant,
                                Modifier.padding(start = 6.dp).size(44.dp, 14.dp).testTag("spark:${row.playerId}"),
                            )
                        }
                    }
```

   Append this to `rowDescription`, after the columns:

```kotlin
                sparklines[row.playerId]?.takeIf { it.drawable }?.let { line ->
                    append(". Last ${line.values.size} weeks: ").append(line.labels.joinToString(", "))
                }
```

New imports: `dev.gridiron.core.charts.Sparkline`, `dev.gridiron.core.data.Sparkline` (alias one of them, e.g. `import dev.gridiron.core.data.Sparkline as SparklineData`, and use `SparklineData` in the parameter type), `dev.gridiron.core.data.GridRequest`, `dev.gridiron.core.data.describeFilter`, `androidx.compose.foundation.layout.size`, `kotlinx.collections.immutable.ImmutableMap`.

- [ ] **Step 7: Write the screen tests**

In `GridScreenTest`, update `everyControlIsOnScreenWithoutScrolling`'s label list to include `"All teams"`, `"Any snaps"` and `"Filters"`. Then add:

```kotlin
    @Test
    fun filtersInTheChipsAndSummary() {
        val season = catalog.season(2025)
        val request = GridRequest(
            season, season.defaultWeeks, StatPack.RECEIVING, positions = PositionFilter.WR,
            teams = setOf("KC"), minSnapShare = 0.5,
            filters = listOf(Filter(StatColumn.TARGETS, Condition.AtLeast(40.0))),
        )
        show(ready(request))
        compose.onNodeWithText("KC").assertIsDisplayed()
        compose.onNodeWithText("50%+ snaps").assertIsDisplayed()
        compose.onNodeWithText("Filters (1)").assertIsDisplayed()
        compose.onNodeWithText("TGT ≥ 40", substring = true).assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/7_filters_chips.png")
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun teamSheetTogglesATeam() {
        val season = catalog.season(2025)
        val events = mutableListOf<GridEvent>()
        show(ready(GridRequest(season, season.defaultWeeks, StatPack.RECEIVING)), onEvent = { events += it })
        compose.onNodeWithTag("chip:teams").performClick()
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/8_team_sheet.png")
        compose.onNodeWithTag("team:KC").performClick()
        assertEquals(listOf<GridEvent>(GridEvent.TeamsSelected(setOf("KC"))), events)
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun filterSheetMarksBadRowsAndAppliesOnlyCompleteOnes() {
        val season = catalog.season(2025)
        val events = mutableListOf<GridEvent>()
        val applied = listOf(Filter(StatColumn.TARGETS, Condition.AtLeast(40.0)))
        show(
            ready(GridRequest(season, season.defaultWeeks, StatPack.RECEIVING, filters = applied))
                .copy(draftCount = DraftCount.Matches(57)),
            onEvent = { events += it },
        )
        compose.onNodeWithTag("chip:filters").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("filter:add").performClick()
        compose.onNodeWithTag("filter:value:1").performTextInput("abc") // incomplete: shown in error, skipped
        compose.onNodeWithText("57 players match").assertExists()
        captureScreenRoboImage("build/outputs/roborazzi/9_filter_sheet.png")
        compose.onNodeWithTag("filter:apply").performClick()
        assertEquals(GridEvent.FiltersApplied(applied), events.last())
    }

    @Test
    fun sparklinesDrawInRowsDark() {
        val season = catalog.season(2025)
        val request = GridRequest(season, season.defaultWeeks, StatPack.FANTASY, positions = PositionFilter.WR)
        val state = ready(request)
        val lines = runBlocking { repo.sparklines(state.page!!) }
        show(state.copy(sparklines = lines.toImmutableMap()), dark = true)
        val first = state.page!!.rows.first()
        compose.onNodeWithTag("spark:${first.playerId}", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Last 6 weeks:", substring = true, useUnmergedTree = false).assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/10_sparklines_dark.png")
    }
```

(imports: `dev.gridiron.core.statquery.Condition`, `dev.gridiron.core.statquery.Filter`, `androidx.compose.ui.test.performTextInput`, `kotlinx.collections.immutable.toImmutableMap`).

- [ ] **Step 8: Run the tests and record the screenshots**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :feature:players:testDebugUnitTest :feature:players:recordRoborazziDebug`
Expected: PASS.

Open screenshots 7–10 and 1 and check:
- Everything in the chip row fits on one screen width.
- The sparkline sits after "WR · KC · 17 g" and doesn't clip the name.
- The incomplete filter row is drawn in the error color.
- In dark mode, the sparklines are readable.

If the chip row doesn't fit (the old `everyControlIsOnScreenWithoutScrolling` fails for a chip), shorten labels: "Teams" instead of "All teams", "Snaps" instead of "Any snaps". Update the test's labels to match.

- [ ] **Step 9: Commit**

```bash
git add feature/players
git commit -m "grid: team, snap and filter chips; advanced filter sheet with live count; sparklines in rows"
```

---

### Task 8: CSV export action, FileProvider and release

**Files:**
- Create: `feature/players/src/main/kotlin/dev/gridiron/feature/players/CsvShare.kt`
- Modify: `feature/players/src/main/kotlin/dev/gridiron/feature/players/GridScreen.kt` (Export chip)
- Modify: `feature/players/build.gradle.kts` (`implementation(libs.androidx.core.ktx)`)
- Create: `app/src/main/res/xml/export_paths.xml`
- Modify: `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts` (version), `README.md`
- Test: `app/src/test/kotlin/dev/gridiron/app/CsvShareTest.kt` (new), `feature/players/src/test/kotlin/dev/gridiron/feature/players/GridScreenTest.kt` (label list)

**Interfaces:**
- Consumes: `CsvExport.build/fileName` (Task 4).
- Produces: `object CsvShare { suspend fun share(context: Context, fileName: String, csv: String): Boolean }` (public). It returns false on any write or provider failure.

- [ ] **Step 1: Write the failing app test**

Create `app/src/test/kotlin/dev/gridiron/app/CsvShareTest.kt`:

```kotlin
package dev.gridiron.app

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.gridiron.feature.players.CsvShare
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CsvShareTest {
    @Test
    fun writesTheFileAndOffersItToOtherApps() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val ok = runBlocking { CsvShare.share(app, "gridiron-2025-receiving.csv", "a,b\r\n1,2\r\n") }
        assertTrue(ok)
        assertEquals("a,b\r\n1,2\r\n", File(app.cacheDir, "exports/gridiron-2025-receiving.csv").readText())

        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/csv", send.type)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("${app.packageName}.exports", uri.authority)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
}
```

If `androidx.test:core` isn't on `:app`'s test classpath, use `org.robolectric.RuntimeEnvironment.getApplication()` instead of `ApplicationProvider`.

- [ ] **Step 2: Run and confirm it fails**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :app:testDebugUnitTest --tests '*CsvShareTest*'`
Expected: FAILS with unresolved `CsvShare`.

- [ ] **Step 3: Implement the share and the provider**

`feature/players/build.gradle.kts`: add `implementation(libs.androidx.core.ktx)`.

Create `CsvShare.kt`:

```kotlin
package dev.gridiron.feature.players

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Writes a CSV under cache/exports and hands it to the share sheet. The file
 * is written beside its final name and renamed into place, so a failed write
 * never leaves a partial file to share. The app declares the provider, with
 * authority `<applicationId>.exports`.
 */
public object CsvShare {
    public suspend fun share(context: Context, fileName: String, csv: String): Boolean {
        val file = try {
            withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                val tmp = File(dir, "$fileName.tmp")
                tmp.writeText(csv)
                val out = File(dir, fileName)
                if (!tmp.renameTo(out)) {
                    out.delete()
                    if (!tmp.renameTo(out)) throw IOException("couldn't move $tmp to $out")
                }
                out
            }
        } catch (e: IOException) {
            return false
        }
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        } catch (e: IllegalArgumentException) {
            return false
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Export CSV").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
```

Create `app/src/main/res/xml/export_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="exports" path="exports/" />
</paths>
```

In `app/src/main/AndroidManifest.xml`, inside `<application>`, after the activity:

```xml
        <!-- Shares Grid CSV exports; only cache/exports is exposed. -->
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.exports"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/export_paths" />
        </provider>
```

Update the manifest's `<!-- No permissions … -->` comment only if it becomes inaccurate. It doesn't: a FileProvider needs no permission.

- [ ] **Step 4: Add the Export chip**

In `GridScreen.kt`'s filter `ChipRow`, after the Filters chip:

```kotlin
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                AssistChip(
                    onClick = {
                        val page = state.page ?: return@AssistChip
                        scope.launch {
                            val csv = withContext(Dispatchers.Default) { CsvExport.build(page, state.catalog) }
                            if (!CsvShare.share(context, CsvExport.fileName(page.request), csv)) snackbar.showSnackbar("Couldn't export")
                        }
                    },
                    enabled = state.page != null,
                    label = { Text("Export") },
                    modifier = Modifier.testTag("chip:export"),
                )
```

This needs `snackbar` in scope. `GridContent` already has `val snackbar = remember { SnackbarHostState() }` above the chip rows. Imports: `androidx.compose.material3.AssistChip`, `androidx.compose.ui.platform.LocalContext`, `androidx.compose.runtime.rememberCoroutineScope`, `dev.gridiron.core.data.CsvExport`, `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.launch`, `kotlinx.coroutines.withContext`.

Add `"Export"` to `everyControlIsOnScreenWithoutScrolling`'s labels.

- [ ] **Step 5: Version and README**

- `app/build.gradle.kts`: `versionCode = 3`, `versionName = "0.3.0"`.
- `README.md`, **How to use**: add a paragraph: "The third chip row filters by team, minimum snap share and any stat (**Filters** opens a sheet with a live match count), and **Export** shares the current table as a CSV. Each row's small line is the sorted stat over the last six weeks played; a gap is a week he didn't play."
- `README.md`, the Status table's **The Grid** row: append "team and snap-share filters, advanced filters with a live count, last-6-week sparklines, CSV export".
- `docs/superpowers/specs/2026-09-23-grid-finish-design.md`, header: change "Status: approved design, not yet planned" to "Status: implemented (see plan docs/superpowers/plans/2026-09-23-grid-finish.md)".

- [ ] **Step 6: Run everything**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew test :feature:players:recordRoborazziDebug :app:assembleRelease`
Expected: every test PASSES and the APK builds.

- [ ] **Step 7: Commit**

```bash
git add feature/players app README.md docs/superpowers/specs/2026-09-23-grid-finish-design.md
git commit -m "grid: export the table as CSV through the share sheet; v0.3.0"
```
