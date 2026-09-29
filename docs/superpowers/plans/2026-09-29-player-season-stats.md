# Player Page Season Stats, and K/D/ST on Compare: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every Player page gets a "Season stats" section (season chips, a season line with percentiles, a week-by-week game log), and Compare works for kickers and team defenses.

**Architecture:** Compare's metric sets gain K and D/ST entries, which the Player page's season line reuses. A new `PlayerStatsRepository` in `:core:data` runs the Grid's own query builder (a total query and a ranked per-game query for the season line, one query per played week for the game log), and the app renders the result in a new `PlayerStatsSection`. No schema, ingest, forecast or module changes.

**Tech Stack:** Kotlin, Jetpack Compose, `:core:statquery` (`StatQuerySpec`, `StatQueryBuilder`), JUnit 5 in the JVM modules, Robolectric and Compose UI tests in the Android modules.

**Spec:** `docs/superpowers/specs/2026-09-29-player-season-stats-design.md`

## Global Constraints

- The section sits on the Player page after "This week" and "Rosters", before "Status".
- Empty state: `No games in the built seasons.` Load failure: `Season stats aren't available.` Below the ranking bar: `Below the ranking bar`. One sentence per status line, no exclamation marks.
- The percentile is the per-game percentile among qualified players at the position, over the season's regular weeks (`SeasonInfo.defaultWeeks`), using `SampleThreshold.forSample(qualifier, playedWeeks, perGame = true)`, as Compare does.
- Rates are recomputed over the season, never averaged. Fantasy points use the active scoring profile.
- K and D/ST show no expected points (`EXPECTED_FANTASY_POINTS`, `FPOE`), and Compare hides the Scatter tab when the page has no scatter.
- No schema, ingest or forecast change; no new module. All SQL is parameterized (`?` binds) and lives in `:core:statquery`.
- Kotlin official style. No new `!!` outside tests. Tests that read the real database use `assumeTrue(StatsDb.path != null, ...)` like the existing ones.
- Commit messages end with the attribution lines from the session's reminder (`Co-Authored-By:` and `Claude-Session:`).

## Review Focus

- **A player with no regular-season games in a season he has stats in** (playoffs only): the section shows chips and an empty line, and never crashes. Pinned by Task 3's `aSeasonWithNoGamesShowsOnlyTheChips`.
- **A traded player**: each week's opponent comes from that week's team in `player_week_stat`, not his current team (the Grid's team column is `player.team`). Pinned by Task 2's `weekTeams` query and the D/ST test asserting every week has an opponent.
- **A rate with no attempts in a week** (CPOE, catch rate): the log cell reads `–`, never `NaN` or a crash. Pinned by Task 2's QB log test (formatting goes through `StatFormat`).
- **A kicker's zero-try week** (he only kicked off): the week is in the log with `0` cells. Pinned by Task 2's K test, which asserts the log has one row per game.
- **Switching season chips quickly**: an older load must not overwrite a newer one. Task 3 keys the load on the chosen season, so Compose cancels the old load; `switchingSeasonsAsksForTheChosenOne` pins the request.
- **An unknown position code**: falls back to the WR/TE sets, like Compare. Pinned by Task 2's `PlayerStatSetsTest`.

---

### Task 1: Compare for kickers and defenses

**Files:**
- Modify: `core/data/src/main/kotlin/dev/gridiron/core/data/CompareMetricSets.kt`
- Modify: `core/data/src/main/kotlin/dev/gridiron/core/data/CompareRepository.kt` (scatter guard, header label)
- Modify: `feature/compare/src/main/kotlin/dev/gridiron/feature/compare/CompareScreen.kt` (hide the Scatter tab)
- Test: `core/data/src/test/kotlin/dev/gridiron/core/data/CompareMetricSetsTest.kt`, `core/data/src/test/kotlin/dev/gridiron/core/data/CompareRepositoryTest.kt`, `feature/compare/src/test/kotlin/dev/gridiron/feature/compare/CompareScreenTest.kt`

**Interfaces:**
- Consumes: `CompareMetricSets.groupsFor/union/radarAxes/qualifier`, `Position.K`, `Position.DST`, `StatColumn.FG_ATT, FG_MADE, FG_MADE_50, XP_ATT, XP_MADE, POINTS_ALLOWED, DST_SACKS, DST_INTERCEPTIONS, DST_FUMBLE_RECOVERIES, DST_TDS, DST_SAFETIES`.
- Produces: `CompareMetricSets.groupsFor(Position.K)` and `(Position.DST)` return their own maps (a group with no stats is absent from the map); `union` omits empty groups; `radarAxes` and `qualifier` handle both; `ComparePage.scatter` is null for a K or D/ST first slot. Task 2 reads `groupsFor` and `qualifier` for these positions.

- [ ] **Step 1: Write the failing metric-set tests**

Append these tests inside `class CompareMetricSetsTest` (before the closing brace). `CompareGroup` needs no import: it is in the same package.

```kotlin
    @Test
    fun `kickers and defenses have their own sets without expected points`() {
        for (p in listOf(Position.K, Position.DST)) {
            val columns = CompareMetricSets.groupsFor(p).values.flatten()
            assertTrue(StatColumn.FANTASY_POINTS in columns, "$p")
            assertTrue(StatColumn.EXPECTED_FANTASY_POINTS !in columns && StatColumn.FPOE !in columns, "$p")
            assertTrue(CompareMetricSets.radarAxes(p).size >= 3, "$p")
        }
        assertEquals(StatColumn.FG_ATT, CompareMetricSets.qualifier(Position.K))
        assertEquals(StatColumn.POINTS_ALLOWED, CompareMetricSets.qualifier(Position.DST))
    }

    @Test
    fun `a union leaves out groups no compared position has rows for`() {
        assertEquals(
            listOf(CompareGroup.OPPORTUNITY, CompareGroup.EFFICIENCY, CompareGroup.SCORING),
            CompareMetricSets.union(listOf(Position.K)).map { it.first },
        )
        assertEquals(
            listOf(CompareGroup.EFFICIENCY, CompareGroup.SCORING, CompareGroup.CONTEXT),
            CompareMetricSets.union(listOf(Position.DST)).map { it.first },
        )
    }

    @Test
    fun `a kicker beside a quarterback compares on both sets`() {
        val union = CompareMetricSets.union(listOf(Position.K, Position.QB))
        assertEquals(CompareGroup.entries, union.map { it.first })
        val opportunity = union.first().second
        assertTrue(StatColumn.FG_ATT in opportunity && StatColumn.DROPBACKS in opportunity)
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:data:test --tests "dev.gridiron.core.data.CompareMetricSetsTest"`
Expected: the three new tests FAIL (K and D/ST fall through to the WR set, so `FG_ATT` is missing and `EXPECTED_FANTASY_POINTS` is present).

- [ ] **Step 3: Add the K and D/ST sets**

In `CompareMetricSets.kt`, add these imports in alphabetical position with the others:

```kotlin
import dev.gridiron.core.statquery.StatColumn.DST_FUMBLE_RECOVERIES
import dev.gridiron.core.statquery.StatColumn.DST_INTERCEPTIONS
import dev.gridiron.core.statquery.StatColumn.DST_SACKS
import dev.gridiron.core.statquery.StatColumn.DST_SAFETIES
import dev.gridiron.core.statquery.StatColumn.DST_TDS
import dev.gridiron.core.statquery.StatColumn.FG_ATT
import dev.gridiron.core.statquery.StatColumn.FG_MADE
import dev.gridiron.core.statquery.StatColumn.FG_MADE_50
import dev.gridiron.core.statquery.StatColumn.POINTS_ALLOWED
import dev.gridiron.core.statquery.StatColumn.XP_ATT
import dev.gridiron.core.statquery.StatColumn.XP_MADE
```

After `WR_SET`, add:

```kotlin
    // Kickers and defenses have no expected points, and a group with no stats is simply absent.
    private val K_SET = mapOf(
        CompareGroup.OPPORTUNITY to listOf(FG_ATT, XP_ATT),
        CompareGroup.EFFICIENCY to listOf(FG_MADE_50),
        CompareGroup.SCORING to listOf(FANTASY_POINTS, FG_MADE, XP_MADE),
    )
    private val DST_SET = mapOf(
        CompareGroup.EFFICIENCY to listOf(POINTS_ALLOWED),
        CompareGroup.SCORING to listOf(FANTASY_POINTS, DST_TDS, DST_SAFETIES),
        CompareGroup.CONTEXT to listOf(DST_SACKS, DST_INTERCEPTIONS, DST_FUMBLE_RECOVERIES),
    )
```

Replace `groupsFor`, `union`, `radarAxes` and `qualifier` with:

```kotlin
    public fun groupsFor(position: Position?): Map<CompareGroup, List<StatColumn>> = when (position) {
        Position.QB -> QB_SET
        Position.RB, Position.FB -> RB_SET
        Position.K -> K_SET
        Position.DST -> DST_SET
        else -> WR_SET
    }

    /** The compared positions' stats per group, in group order; a group nobody has rows for is left out. */
    public fun union(positions: List<Position?>): List<Pair<CompareGroup, List<StatColumn>>> =
        CompareGroup.entries
            .map { g -> g to positions.flatMap { groupsFor(it)[g].orEmpty() }.distinct() }
            .filter { (_, columns) -> columns.isNotEmpty() }

    public fun radarAxes(position: Position?): List<StatColumn> = when (position) {
        Position.QB -> listOf(EPA_PER_DROPBACK, CPOE, DROPBACKS, CARRIES, PASSING_TDS, FPOE)
        Position.RB, Position.FB -> listOf(CARRY_SHARE, TARGET_SHARE, RUSH_SUCCESS_RATE, RUSH_EPA_PER_CARRY, GL_CARRIES, SNAP_SHARE, FPOE)
        Position.K -> listOf(FG_ATT, FG_MADE, FG_MADE_50, XP_MADE, FANTASY_POINTS)
        Position.DST -> listOf(POINTS_ALLOWED, DST_SACKS, DST_INTERCEPTIONS, DST_FUMBLE_RECOVERIES, DST_TDS, FANTASY_POINTS)
        else -> listOf(TARGET_SHARE, AIR_YARDS_SHARE, ADOT, RACR, YAC, RZ_TARGETS, FPOE)
    }

    /** Who is ranked at each position: the spec's population qualifiers. Every team's defense is ranked. */
    public fun qualifier(position: Position?): StatColumn = when (position) {
        Position.QB -> DROPBACKS
        Position.RB, Position.FB -> CARRIES
        Position.K -> FG_ATT
        Position.DST -> POINTS_ALLOWED
        else -> TARGETS
    }
```

(Delete the old versions of those four functions and the old `union` doc comment, if any.)

- [ ] **Step 4: Run the metric-set tests to verify they pass**

Run: `./gradlew :core:data:test --tests "dev.gridiron.core.data.CompareMetricSetsTest"`
Expected: PASS (all tests, including the four existing ones).

- [ ] **Step 5: Write the failing repository tests**

In `CompareRepositoryTest.kt`, add these tests inside the class (the class already has `topIds`, `request`, `season2025`, `catalog`, `compare`). If `assertNull`, `assertNotNull`, `assertTrue` aren't imported they already are in this file.

```kotlin
    @Test
    fun `two kickers are ranked on their own stats, with no scatter`() = runTest {
        val (a, b) = topIds(StatPack.KICKING, PositionFilter.K, 2)
        val page = compare.compare(request(CompareSlot(a, 2025, season2025), CompareSlot(b, 2025, season2025)), catalog)
        assertEquals(listOf(SlotStatus.OK, SlotStatus.OK), page.slots.map { it.status })
        val rows = page.groups.flatMap { it.rows }
        assertTrue(StatColumn.FG_ATT in rows.map { it.column })
        assertTrue(StatColumn.EXPECTED_FANTASY_POINTS !in rows.map { it.column })
        assertTrue(rows.all { r -> r.cells.all { it.text != "—" } })
        assertNull(page.scatter)
        assertEquals(5, page.radar!!.axes.size)
    }

    @Test
    fun `two defenses are ranked on points allowed and read D-ST`() = runTest {
        val (a, b) = topIds(StatPack.DEFENSE, PositionFilter.DST, 2)
        val page = compare.compare(request(CompareSlot(a, 2025, season2025), CompareSlot(b, 2025, season2025)), catalog)
        assertEquals(listOf(SlotStatus.OK, SlotStatus.OK), page.slots.map { it.status })
        val allowed = page.groups.flatMap { it.rows }.first { it.column == StatColumn.POINTS_ALLOWED }
        assertTrue(allowed.cells.all { it.percentile != null })
        assertNull(page.scatter)
        assertEquals(6, page.radar!!.axes.size)
        assertTrue(page.slots.all { "D/ST" in it.detail }, page.slots.map { it.detail }.toString())
    }

    @Test
    fun `a kicker beside a receiver shows dashes where a stat does not apply`() = runTest {
        val kicker = topIds(StatPack.KICKING, PositionFilter.K, 1).single()
        val receiver = topIds(StatPack.RECEIVING, PositionFilter.WR, 1).single()
        val page = compare.compare(request(CompareSlot(kicker, 2025, season2025), CompareSlot(receiver, 2025, season2025)), catalog)
        val fgAtt = page.groups.flatMap { it.rows }.first { it.column == StatColumn.FG_ATT }
        assertNotEquals("—", fgAtt.cells[0].text)
        assertEquals("—", fgAtt.cells[1].text)
        val targets = page.groups.flatMap { it.rows }.first { it.column == StatColumn.TARGETS }
        assertEquals("—", targets.cells[0].text)
    }
```

- [ ] **Step 6: Run them to verify they fail**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test --tests "dev.gridiron.core.data.CompareRepositoryTest"`
Expected: the kicker and defense tests FAIL. (If `etl/build/stats.db` is missing or has no kickers, rebuild it first: `./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025 2026" -Pout=etl/build/stats.db`.) The scatter assertions and the `D/ST` detail fail; the kicker rows may already work because Task 1's sets are in place, which is fine.

- [ ] **Step 7: Guard the scatter and label D/ST in the header**

In `CompareRepository.kt`, in `scatter(...)`, replace `if (position == null) return null` with:

```kotlin
        // Kickers and defenses have no expected points, so there is nothing to plot.
        if (position == null || position == Position.K || position == Position.DST) return null
```

In `header(...)`, replace `append(position?.code ?: "–")` with:

```kotlin
                append(position?.let { Position.label(it.code) } ?: "–")
```

- [ ] **Step 8: Run the repository tests to verify they pass**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test --tests "dev.gridiron.core.data.CompareRepositoryTest"`
Expected: PASS.

- [ ] **Step 9: Write the failing screen test**

In `CompareScreenTest.kt`, add inside the class:

```kotlin
    @Test
    fun twoKickersHaveNoScatterTab() {
        val (a, b) = topIds(StatPack.KICKING, PositionFilter.K, 2)
        show(ready(CompareSlot(a, 2025, season2025), CompareSlot(b, 2025, season2025)))
        compose.onNodeWithText("Bars").assertExists()
        compose.onNodeWithText("Scatter").assertDoesNotExist()
    }

    @Test
    fun receiversStillHaveTheScatterTab() {
        val (a, b) = topIds(StatPack.RECEIVING, PositionFilter.WR, 2)
        show(ready(CompareSlot(a, 2025, season2025), CompareSlot(b, 2025, season2025)))
        compose.onNodeWithText("Scatter").assertExists()
    }

    @Test
    fun aScatterTabLeftSelectedByAKickerSwapFallsBackToBars() {
        val (a, b) = topIds(StatPack.KICKING, PositionFilter.K, 2)
        show(ready(CompareSlot(a, 2025, season2025), CompareSlot(b, 2025, season2025), tab = CompareTab.SCATTER))
        compose.onNodeWithText("Scatter").assertDoesNotExist()
        compose.onNodeWithText("Not enough data for a scatter.").assertDoesNotExist()
    }
```

- [ ] **Step 10: Run them to verify they fail**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :feature:compare:testDebugUnitTest --tests "dev.gridiron.feature.compare.CompareScreenTest"`
Expected: `twoKickersHaveNoScatterTab` and `aScatterTabLeftSelected…` FAIL (the tab is shown); `receiversStillHaveTheScatterTab` passes.

- [ ] **Step 11: Hide the tab when there is no scatter**

In `CompareScreen.kt`, in `CompareContent`, replace these lines:

```kotlin
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val tabs = if (landscape) listOf(CompareTab.BARS, CompareTab.RADAR, CompareTab.SCATTER) else CompareTab.entries.toList()
    val combinedLandscapeBars = landscape && (state.tab == CompareTab.BARS || state.tab == CompareTab.TABLE)
    val selectedIndex = tabs.indexOf(if (combinedLandscapeBars) CompareTab.BARS else state.tab).coerceAtLeast(0)
```

with:

```kotlin
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Kickers and defenses have no expected points, so a page without a scatter has no Scatter tab.
    val hasScatter = state.page.scatter != null
    val tab = if (state.tab == CompareTab.SCATTER && !hasScatter) CompareTab.BARS else state.tab
    val tabs = (if (landscape) listOf(CompareTab.BARS, CompareTab.RADAR, CompareTab.SCATTER) else CompareTab.entries.toList())
        .filter { it != CompareTab.SCATTER || hasScatter }
    val combinedLandscapeBars = landscape && (tab == CompareTab.BARS || tab == CompareTab.TABLE)
    val selectedIndex = tabs.indexOf(if (combinedLandscapeBars) CompareTab.BARS else tab).coerceAtLeast(0)
```

In the same function, change `selected = t == (if (combinedLandscapeBars) CompareTab.BARS else state.tab),` to `selected = t == (if (combinedLandscapeBars) CompareTab.BARS else tab),`, and in the `when` below, change `state.tab == CompareTab.BARS ->`, `state.tab == CompareTab.TABLE ->`, `state.tab == CompareTab.RADAR ->` and `state.tab == CompareTab.SCATTER ->` to use `tab` instead of `state.tab`.

- [ ] **Step 12: Run everything this task touched**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test :feature:compare:testDebugUnitTest`
Expected: PASS. (If `CompareViewModelTest` fails because it assumed a kicker page shows a scatter, fix that assumption; nothing else should change.)

- [ ] **Step 13: Commit**

```bash
git add core/data feature/compare
git commit -m "compare: kickers and defenses get their own metric sets, and no scatter"
```

---

### Task 2: The Player page's stats, as data

**Files:**
- Create: `core/statquery/src/main/kotlin/dev/gridiron/core/statquery/PlayerStatsQueries.kt`
- Create: `core/data/src/main/kotlin/dev/gridiron/core/data/PlayerStatSets.kt`
- Create: `core/data/src/main/kotlin/dev/gridiron/core/data/PlayerStatsModels.kt`
- Create: `core/data/src/main/kotlin/dev/gridiron/core/data/PlayerStatsRepository.kt`
- Test: `core/data/src/test/kotlin/dev/gridiron/core/data/PlayerStatSetsTest.kt`, `PlayerMatchupTest.kt`, `PlayerStatsRepositoryTest.kt` (same directory)

**Interfaces:**
- Consumes: `CompareMetricSets.groupsFor(Position?)` and `qualifier(Position?)` (Task 1), `SampleThreshold.forSample`, `StatQueryBuilder.grid`, `CatalogQueries.seasons/metrics`, `StatFormat`, `SeasonInfo.defaultWeeks`.
- Produces:
  - `PlayerStatsQueries.seasons(playerId): SqlQuery` (column 0: season); `weekTeams(playerId, season): SqlQuery` (columns: week, team); `games(season): SqlQuery` (columns: week, home_team, away_team, home_score, away_score).
  - `PlayerStatSets.logColumns(position: Position?): List<StatColumn>`.
  - `PlayerStats`, `SeasonLineRow`, `GameLogRow` (below).
  - `PlayerStatsRepository(executor: QueryExecutor, locale: Locale = Locale.getDefault())` with `suspend fun stats(playerId: String, position: Position?, scoring: ScoringProfile, season: Int? = null): PlayerStats`.
  - `internal fun matchup(team: String?, week: Int, schedule: List<ScheduleGame>): Matchup`.

- [ ] **Step 1: Write the failing pure tests**

Create `core/data/src/test/kotlin/dev/gridiron/core/data/PlayerStatSetsTest.kt`:

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.model.Position
import dev.gridiron.core.statquery.StatColumn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlayerStatSetsTest {
    @Test
    fun `every position has a game log that leads with fantasy points`() {
        for (p in Position.entries) {
            val log = PlayerStatSets.logColumns(p)
            assertEquals(StatColumn.FANTASY_POINTS, log.first(), "$p")
            assertEquals(5, log.size, "$p")
            assertEquals(log.distinct(), log, "$p")
        }
    }

    @Test
    fun `kickers and defenses log no expected points`() {
        for (p in listOf(Position.K, Position.DST)) {
            val log = PlayerStatSets.logColumns(p)
            assertTrue(StatColumn.EXPECTED_FANTASY_POINTS !in log && StatColumn.FPOE !in log, "$p")
        }
    }

    @Test
    fun `an unknown position logs like a receiver, and a fullback like a back`() {
        assertEquals(PlayerStatSets.logColumns(Position.WR), PlayerStatSets.logColumns(null))
        assertEquals(PlayerStatSets.logColumns(Position.RB), PlayerStatSets.logColumns(Position.FB))
    }

    @Test
    fun `each position's game log is scored under its own stats`() {
        assertTrue(StatColumn.PASSING_YARDS in PlayerStatSets.logColumns(Position.QB))
        assertTrue(StatColumn.CARRIES in PlayerStatSets.logColumns(Position.RB))
        assertTrue(StatColumn.TARGETS in PlayerStatSets.logColumns(Position.TE))
        assertTrue(StatColumn.FG_ATT in PlayerStatSets.logColumns(Position.K))
        assertTrue(StatColumn.POINTS_ALLOWED in PlayerStatSets.logColumns(Position.DST))
    }
}
```

Create `core/data/src/test/kotlin/dev/gridiron/core/data/PlayerMatchupTest.kt`:

```kotlin
package dev.gridiron.core.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlayerMatchupTest {
    private val schedule = listOf(
        ScheduleGame(1, "KC", "BAL", 27, 20),
        ScheduleGame(2, "DAL", "KC", 24, 17),
        ScheduleGame(3, "KC", "LV", 20, 20),
        ScheduleGame(4, "KC", "NO", null, null),
    )

    @Test
    fun `a home win reads vs and W`() = assertEquals(Matchup("vs BAL", "W 27–20"), matchup("KC", 1, schedule))

    @Test
    fun `an away loss reads at and L, with his own score first`() = assertEquals(Matchup("@ DAL", "L 17–24"), matchup("KC", 2, schedule))

    @Test
    fun `the same game from the other side`() = assertEquals(Matchup("@ KC", "L 20–27"), matchup("BAL", 1, schedule))

    @Test
    fun `a tie reads T`() = assertEquals(Matchup("vs LV", "T 20–20"), matchup("KC", 3, schedule))

    @Test
    fun `a game with no score yet has an opponent and no result`() = assertEquals(Matchup("vs NO", null), matchup("KC", 4, schedule))

    @Test
    fun `a week with no game, or no team, has a dash`() {
        assertEquals(Matchup("–", null), matchup("KC", 9, schedule))
        assertEquals(Matchup("–", null), matchup(null, 1, schedule))
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:data:test --tests "dev.gridiron.core.data.PlayerStatSetsTest" --tests "dev.gridiron.core.data.PlayerMatchupTest"`
Expected: FAIL to compile (`PlayerStatSets`, `ScheduleGame`, `Matchup`, `matchup` are not defined).

- [ ] **Step 3: Write the queries, sets, models and matchup logic**

Create `core/statquery/src/main/kotlin/dev/gridiron/core/statquery/PlayerStatsQueries.kt`:

```kotlin
package dev.gridiron.core.statquery

/** The two small reads behind the Player page's game log and season chips. Every value is a bound `?`. */
public object PlayerStatsQueries {
    /** The seasons [playerId] has games in, oldest first: season. Reads the games component through the metric index. */
    public fun seasons(playerId: String): SqlQuery = SqlQuery(
        "SELECT DISTINCT season FROM player_week_stat WHERE metric_id = ? AND player_id = ? ORDER BY season",
        listOf(Bind.Text(Components.GAMES.id), Bind.Text(playerId)),
    )

    /** The weeks [playerId] played in [season] and his team each week: week, team. */
    public fun weekTeams(playerId: String, season: Int): SqlQuery = SqlQuery(
        "SELECT week, team FROM player_week_stat WHERE metric_id = ? AND player_id = ? AND season = ? ORDER BY week",
        listOf(Bind.Text(Components.GAMES.id), Bind.Text(playerId), Bind.Integer(season.toLong())),
    )

    /** [season]'s regular-season games: week, home_team, away_team, home_score, away_score. */
    public fun games(season: Int): SqlQuery = SqlQuery(
        "SELECT week, home_team, away_team, home_score, away_score FROM game WHERE season = ? AND game_type = 'REG' ORDER BY week",
        listOf(Bind.Integer(season.toLong())),
    )
}
```

Create `core/data/src/main/kotlin/dev/gridiron/core/data/PlayerStatSets.kt`:

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.model.Position
import dev.gridiron.core.statquery.StatColumn
import dev.gridiron.core.statquery.StatColumn.CARRIES
import dev.gridiron.core.statquery.StatColumn.DST_INTERCEPTIONS
import dev.gridiron.core.statquery.StatColumn.DST_SACKS
import dev.gridiron.core.statquery.StatColumn.DST_TDS
import dev.gridiron.core.statquery.StatColumn.FANTASY_POINTS
import dev.gridiron.core.statquery.StatColumn.FG_ATT
import dev.gridiron.core.statquery.StatColumn.FG_MADE
import dev.gridiron.core.statquery.StatColumn.FG_MADE_50
import dev.gridiron.core.statquery.StatColumn.INTERCEPTIONS
import dev.gridiron.core.statquery.StatColumn.PASSING_TDS
import dev.gridiron.core.statquery.StatColumn.PASSING_YARDS
import dev.gridiron.core.statquery.StatColumn.POINTS_ALLOWED
import dev.gridiron.core.statquery.StatColumn.RECEIVING_TDS
import dev.gridiron.core.statquery.StatColumn.RECEIVING_YARDS
import dev.gridiron.core.statquery.StatColumn.RECEPTIONS
import dev.gridiron.core.statquery.StatColumn.RUSHING_YARDS
import dev.gridiron.core.statquery.StatColumn.TARGETS
import dev.gridiron.core.statquery.StatColumn.XP_MADE

/**
 * The stats on the Player page's game log, one list per position, in one table
 * so they can be tuned without touching the UI. The season line's stats come
 * from [CompareMetricSets]. Fantasy points lead every log, then four stats.
 */
public object PlayerStatSets {
    private val QB = listOf(FANTASY_POINTS, PASSING_YARDS, PASSING_TDS, INTERCEPTIONS, RUSHING_YARDS)
    private val RB = listOf(FANTASY_POINTS, CARRIES, RUSHING_YARDS, RECEPTIONS, RECEIVING_YARDS)
    private val WR_TE = listOf(FANTASY_POINTS, TARGETS, RECEPTIONS, RECEIVING_YARDS, RECEIVING_TDS)
    private val K = listOf(FANTASY_POINTS, FG_MADE, FG_ATT, FG_MADE_50, XP_MADE)
    private val DST = listOf(FANTASY_POINTS, POINTS_ALLOWED, DST_SACKS, DST_INTERCEPTIONS, DST_TDS)

    /** An unknown position logs like a receiver, as Compare treats it. */
    public fun logColumns(position: Position?): List<StatColumn> = when (position) {
        Position.QB -> QB
        Position.RB, Position.FB -> RB
        Position.K -> K
        Position.DST -> DST
        else -> WR_TE
    }
}
```

Create `core/data/src/main/kotlin/dev/gridiron/core/data/PlayerStatsModels.kt`:

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.statquery.StatColumn
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** One stat on the Player page's season line. */
public data class SeasonLineRow(
    val column: StatColumn,
    val label: String,
    val total: String,
    /** Blank for a rate, whose per-game value is its total. */
    val perGame: String,
    /** 0..1 among the position's qualified players, or null when he is below the ranking bar. */
    val percentile: Float?,
)

/** One played week: [opponent] reads "vs DAL", "@ DAL" or a dash; [result] reads "W 27–20", or is null before a score exists. */
public data class GameLogRow(val week: Int, val opponent: String, val result: String?, val cells: ImmutableList<String>)

/** Everything the Player page's "Season stats" section shows for one player and season. */
public data class PlayerStats(
    val season: Int,
    /** The seasons he has games in, oldest first: the chips. Empty when he has none. */
    val seasons: ImmutableList<Int>,
    val games: Int,
    /** The qualifying bar in use ("min 3 targets per game, 4+ games"), or null where every player is ranked. */
    val bar: String?,
    /** Whether he clears the bar, so his percentiles exist. */
    val ranked: Boolean,
    val line: ImmutableList<SeasonLineRow>,
    /** The game log's column headers, one per cell. */
    val logHeaders: ImmutableList<String>,
    val log: ImmutableList<GameLogRow>,
) {
    public companion object {
        /** A player with no games in any built season. */
        public val EMPTY: PlayerStats = PlayerStats(
            season = 0,
            seasons = persistentListOf(),
            games = 0,
            bar = null,
            ranked = false,
            line = persistentListOf(),
            logHeaders = persistentListOf(),
            log = persistentListOf(),
        )
    }
}
```

Create `core/data/src/main/kotlin/dev/gridiron/core/data/PlayerStatsRepository.kt` with only the matchup pieces for now (the repository class comes in Step 6):

```kotlin
package dev.gridiron.core.data

/** One regular-season game from the `game` table. */
internal data class ScheduleGame(val week: Int, val home: String, val away: String, val homeScore: Int?, val awayScore: Int?)

/** A week's opponent and result from a player's side. */
internal data class Matchup(val opponent: String, val result: String?)

/** [team]'s game in [week], from its own side: "vs BAL" and "W 27–20", or a dash when there is no such game. */
internal fun matchup(team: String?, week: Int, schedule: List<ScheduleGame>): Matchup {
    val game = team?.let { t -> schedule.firstOrNull { it.week == week && (it.home == t || it.away == t) } }
        ?: return Matchup(StatFormat.MISSING, null)
    val home = game.home == team
    val opponent = if (home) "vs ${game.away}" else "@ ${game.home}"
    val own = if (home) game.homeScore else game.awayScore
    val theirs = if (home) game.awayScore else game.homeScore
    if (own == null || theirs == null) return Matchup(opponent, null)
    val outcome = when {
        own > theirs -> "W"
        own < theirs -> "L"
        else -> "T"
    }
    return Matchup(opponent, "$outcome $own–$theirs")
}
```

- [ ] **Step 4: Run the pure tests to verify they pass**

Run: `./gradlew :core:data:test --tests "dev.gridiron.core.data.PlayerStatSetsTest" --tests "dev.gridiron.core.data.PlayerMatchupTest"`
Expected: PASS.

- [ ] **Step 5: Write the failing repository tests**

Create `core/data/src/test/kotlin/dev/gridiron/core/data/PlayerStatsRepositoryTest.kt`:

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.SqlQuery
import dev.gridiron.core.statquery.StatColumn
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale

/** The Player page's stats against the real ETL-built database. */
class PlayerStatsRepositoryTest {
    private lateinit var executor: JdbcQueryExecutor
    private lateinit var stats: StatsRepository
    private lateinit var repo: PlayerStatsRepository
    private lateinit var catalog: Catalog

    @BeforeEach
    fun setUp() = runTest {
        assumeTrue(StatsDb.path != null, "GRIDIRON_STATS_DB not set")
        executor = JdbcQueryExecutor(StatsDb.path!!)
        stats = StatsRepository(executor, Locale.US)
        repo = PlayerStatsRepository(executor, Locale.US)
        catalog = stats.catalog()
    }

    @AfterEach
    fun tearDown() {
        if (::executor.isInitialized) executor.close()
    }

    private val ppr = ScoringPresets.PPR

    private suspend fun topId(pack: StatPack, filter: PositionFilter): String {
        val s = catalog.season(2025)
        return stats.grid(GridRequest(s, s.defaultWeeks, pack, filter), catalog).rows.first().playerId
    }

    /** The game log's cells for [column] add up to the season line's total, one row per game, oldest week first. */
    private suspend fun assertLogAddsUp(id: String, position: Position, column: StatColumn) {
        val s = repo.stats(id, position, ppr, season = 2025)
        assertEquals(2025, s.season)
        val index = PlayerStatSets.logColumns(position).indexOf(column)
        assertTrue(index >= 0, "$column is not in $position's log")
        val fromLog = s.log.sumOf { it.cells[index].toDouble() }
        val line = s.line.first { it.column == column }
        assertEquals(line.total.toDouble(), fromLog, 0.0, "$position $column")
        assertEquals(s.games, s.log.size, "$position: one log row per game")
        assertEquals(s.log.map { it.week }.sorted(), s.log.map { it.week })
        assertEquals(PlayerStatSets.logColumns(position).size, s.logHeaders.size)
    }

    @Test
    fun `a receivers season line has totals, per-game values and percentiles`() = runTest {
        val id = topId(StatPack.RECEIVING, PositionFilter.WR)
        val s = repo.stats(id, Position.WR, ppr, season = 2025)
        assertTrue(2025 in s.seasons)
        assertTrue(s.ranked)
        assertTrue(s.bar!!.startsWith("min "), s.bar)
        val targets = s.line.first { it.column == StatColumn.TARGETS }
        assertTrue(targets.total.toInt() > 0)
        assertTrue('.' in targets.perGame, targets.perGame)
        assertTrue(targets.percentile!! in 0f..1f)
        val share = s.line.first { it.column == StatColumn.TARGET_SHARE }
        assertEquals("", share.perGame)
        assertTrue(share.total.endsWith("%"), share.total)
    }

    @Test
    fun `a receivers game log adds up to his season line`() = runTest {
        assertLogAddsUp(topId(StatPack.RECEIVING, PositionFilter.WR), Position.WR, StatColumn.TARGETS)
    }

    @Test
    fun `a backs game log adds up to his season line`() = runTest {
        assertLogAddsUp(topId(StatPack.RUSHING, PositionFilter.RB), Position.RB, StatColumn.CARRIES)
    }

    @Test
    fun `a quarterbacks game log adds up to his season line`() = runTest {
        assertLogAddsUp(topId(StatPack.PASSING, PositionFilter.QB), Position.QB, StatColumn.PASSING_YARDS)
    }

    @Test
    fun `a kickers line has no expected points, and his game log adds up`() = runTest {
        val id = topId(StatPack.KICKING, PositionFilter.K)
        assertLogAddsUp(id, Position.K, StatColumn.FG_ATT)
        val s = repo.stats(id, Position.K, ppr, season = 2025)
        assertTrue(s.line.none { it.column == StatColumn.EXPECTED_FANTASY_POINTS || it.column == StatColumn.FPOE })
        assertTrue(s.line.any { it.column == StatColumn.FG_MADE })
    }

    @Test
    fun `a defenses log has an opponent and a result every week, and adds up`() = runTest {
        val id = topId(StatPack.DEFENSE, PositionFilter.DST)
        assertLogAddsUp(id, Position.DST, StatColumn.POINTS_ALLOWED)
        val s = repo.stats(id, Position.DST, ppr, season = 2025)
        assertNull(s.bar)
        assertTrue(s.ranked)
        assertTrue(s.log.all { it.opponent != "–" && it.result != null }, s.log.toString())
    }

    @Test
    fun `a light-usage player shows values but no percentiles`() = runTest {
        val light = executor.query(
            SqlQuery(
                "SELECT s.player_id FROM player_week_stat s JOIN player p USING (player_id) " +
                    "WHERE s.metric_id = ? AND s.season = ? AND p.position = ? " +
                    "GROUP BY s.player_id HAVING SUM(s.value) BETWEEN 1 AND 5 LIMIT 1",
                listOf(Bind.Text("targets"), Bind.Integer(2025), Bind.Text("WR")),
            ),
        ) { it.text(0) }.single()
        val s = repo.stats(light, Position.WR, ppr, season = 2025)
        assertFalse(s.ranked)
        val targets = s.line.first { it.column == StatColumn.TARGETS }
        assertNotEquals("–", targets.total)
        assertNull(targets.percentile)
    }

    @Test
    fun `a season he has no games in falls back to his latest`() = runTest {
        val id = topId(StatPack.RECEIVING, PositionFilter.WR)
        val s = repo.stats(id, Position.WR, ppr, season = 1999)
        assertEquals(s.seasons.last(), s.season)
    }

    @Test
    fun `an unknown player has no stats`() = runTest {
        assertEquals(PlayerStats.EMPTY, repo.stats("00-0000000", Position.WR, ppr))
    }
}
```

- [ ] **Step 6: Run them to verify they fail**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test --tests "dev.gridiron.core.data.PlayerStatsRepositoryTest"`
Expected: FAIL to compile (`PlayerStatsRepository` is not defined).

- [ ] **Step 7: Write the repository**

In `PlayerStatsRepository.kt`, replace the whole file with the following (it keeps the matchup pieces from Step 3):

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.ResultRow
import dev.gridiron.core.database.doubleOrNull
import dev.gridiron.core.database.textOrNull
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.Aggregate
import dev.gridiron.core.statquery.CatalogQueries
import dev.gridiron.core.statquery.GridLayout
import dev.gridiron.core.statquery.PlayerStatsQueries
import dev.gridiron.core.statquery.StatColumn
import dev.gridiron.core.statquery.StatQueryBuilder
import dev.gridiron.core.statquery.StatQuerySpec
import dev.gridiron.core.statquery.ValueMode
import kotlinx.collections.immutable.toImmutableList
import java.util.Locale

/**
 * The Player page's "Season stats": a season line (totals, per-game values and
 * position percentiles) and a game log, both from the Grid's own query builder,
 * so every number matches what the Grid shows for the same player.
 */
public class PlayerStatsRepository(
    private val executor: QueryExecutor,
    locale: Locale = Locale.getDefault(),
) {
    private val format = StatFormat(locale)

    private class Names(val name: String, val abbr: String)

    /** One Grid row for the player: his games, each column's value, and each column's percentile if ranked. */
    private class Values(val games: Int, val values: Map<StatColumn, Double?>, val percentiles: Map<StatColumn, Float?>)

    /**
     * [playerId]'s stats for [season], or for his latest season when [season] is
     * null or isn't one he has games in; [PlayerStats.EMPTY] if he has none.
     * Scored with [scoring].
     */
    public suspend fun stats(playerId: String, position: Position?, scoring: ScoringProfile, season: Int? = null): PlayerStats {
        val seasons = executor.query(PlayerStatsQueries.seasons(playerId)) { it.long(0).toInt() }
        if (seasons.isEmpty()) return PlayerStats.EMPTY
        val chosen = season?.takeIf { it in seasons } ?: seasons.last()
        val info = executor.query(CatalogQueries.seasons) { SeasonInfo(it.long(0).toInt(), it.long(1).toInt()) }
            .firstOrNull { it.season == chosen } ?: return PlayerStats.EMPTY
        val names = executor.query(CatalogQueries.metrics) { it.text(0) to Names(it.text(1), it.text(2)) }.toMap()

        val weeks = info.defaultWeeks
        val playedWeeks = weeks.last - weeks.first + 1
        val lineColumns = CompareMetricSets.groupsFor(position).values.flatten()
        val qualifier = CompareMetricSets.qualifier(position)
        val columns = (lineColumns + qualifier).distinct()

        val total = seasonValues(playerId, position, chosen, weeks, columns, qualifier, playedWeeks, perGame = false, scoring)
        val perGame = seasonValues(playerId, position, chosen, weeks, columns, qualifier, playedWeeks, perGame = true, scoring)
        val line = lineColumns.map { column ->
            SeasonLineRow(
                column = column,
                label = names[column.metricId]?.name ?: column.metricId,
                total = format.format(column, zeroFilled(column, total?.values?.get(column)), perGame = false),
                perGame = if (hasPerGame(column)) {
                    format.format(column, zeroFilled(column, perGame?.values?.get(column)), perGame = true)
                } else {
                    ""
                },
                percentile = perGame?.percentiles?.get(column),
            )
        }

        val logColumns = PlayerStatSets.logColumns(position)
        val played = executor.query(PlayerStatsQueries.weekTeams(playerId, chosen)) { it.long(0).toInt() to it.textOrNull(1) }
            .filter { (week, _) -> week in weeks.first..weeks.last }
        val schedule = executor.query(PlayerStatsQueries.games(chosen)) {
            ScheduleGame(it.long(0).toInt(), it.text(1), it.text(2), it.intOrNull(3), it.intOrNull(4))
        }
        val log = played.map { (week, team) ->
            val values = weekValues(playerId, position, chosen, week, logColumns, scoring)
            val m = matchup(team, week, schedule)
            GameLogRow(
                week = week,
                opponent = m.opponent,
                result = m.result,
                cells = logColumns.map { c -> format.format(c, zeroFilled(c, values[c]), perGame = false) }.toImmutableList(),
            )
        }

        return PlayerStats(
            season = chosen,
            seasons = seasons.toImmutableList(),
            games = (perGame ?: total)?.games ?: 0,
            bar = SampleThreshold.forSample(qualifier, playedWeeks, perGame = true)?.description,
            ranked = perGame?.percentiles?.get(qualifier) != null,
            line = line.toImmutableList(),
            logHeaders = logColumns.map { names[it.metricId]?.abbr ?: it.metricId }.toImmutableList(),
            log = log.toImmutableList(),
        )
    }

    /**
     * One Grid row for [playerId] over [weeks]. The per-game row is ranked
     * against the position's qualified players (the way Compare ranks), and
     * falls back to an unranked row when the games floor would drop him.
     */
    private suspend fun seasonValues(
        playerId: String,
        position: Position?,
        season: Int,
        weeks: WeekRange,
        columns: List<StatColumn>,
        qualifier: StatColumn,
        playedWeeks: Int,
        perGame: Boolean,
        scoring: ScoringProfile,
    ): Values? {
        val threshold = SampleThreshold.forSample(qualifier, playedWeeks, perGame)
        fun spec(ranked: Boolean) = StatQuerySpec(
            season = season,
            weeks = weeks,
            columns = columns,
            positions = setOfNotNull(position),
            qualifiers = if (ranked) listOfNotNull(threshold?.qualifier) else emptyList(),
            includeUnqualified = true,
            minGames = if (ranked) threshold?.minGames ?: 1 else 1,
            mode = if (perGame) ValueMode.PER_GAME else ValueMode.TOTAL,
            percentiles = ranked && perGame,
            playerIds = setOf(playerId),
            limit = 1,
            scoring = scoring,
        )
        return run(spec(ranked = true), columns) ?: run(spec(ranked = false), columns)
    }

    /** [playerId]'s one-week totals for [columns]; empty if he has no row that week. */
    private suspend fun weekValues(
        playerId: String,
        position: Position?,
        season: Int,
        week: Int,
        columns: List<StatColumn>,
        scoring: ScoringProfile,
    ): Map<StatColumn, Double?> {
        val spec = StatQuerySpec(
            season = season,
            weeks = WeekRange.single(week),
            columns = columns,
            positions = setOfNotNull(position),
            includeUnqualified = true,
            playerIds = setOf(playerId),
            limit = 1,
            scoring = scoring,
        )
        return run(spec, columns)?.values.orEmpty()
    }

    private suspend fun run(spec: StatQuerySpec, columns: List<StatColumn>): Values? {
        val q = StatQueryBuilder.grid(spec)
        val layout = q.layout
        return executor.query(q.query) { r ->
            Values(
                games = r.long(GridLayout.GAMES).toInt(),
                values = columns.associateWith { r.doubleOrNull(layout.valueIndex(it)) },
                percentiles = if (spec.percentiles) {
                    columns.associateWith { r.doubleOrNull(layout.percentileIndex(it))?.toFloat() }
                } else {
                    emptyMap()
                },
            )
        }.singleOrNull()
    }

    /** A total with no stored fact is a zero the database stores sparsely, not a missing value. */
    private fun zeroFilled(column: StatColumn, value: Double?): Double? =
        value ?: if (column.aggregate is Aggregate.Total) 0.0 else null

    /** Counting stats and fantasy points have a per-game value; a rate's is just its total. */
    private fun hasPerGame(column: StatColumn): Boolean = column.aggregate is Aggregate.Total || column.aggregate is Aggregate.Scored

    private fun ResultRow.intOrNull(index: Int): Int? = if (isNull(index)) null else long(index).toInt()
}

/** One regular-season game from the `game` table. */
internal data class ScheduleGame(val week: Int, val home: String, val away: String, val homeScore: Int?, val awayScore: Int?)

/** A week's opponent and result from a player's side. */
internal data class Matchup(val opponent: String, val result: String?)

/** [team]'s game in [week], from its own side: "vs BAL" and "W 27–20", or a dash when there is no such game. */
internal fun matchup(team: String?, week: Int, schedule: List<ScheduleGame>): Matchup {
    val game = team?.let { t -> schedule.firstOrNull { it.week == week && (it.home == t || it.away == t) } }
        ?: return Matchup(StatFormat.MISSING, null)
    val home = game.home == team
    val opponent = if (home) "vs ${game.away}" else "@ ${game.home}"
    val own = if (home) game.homeScore else game.awayScore
    val theirs = if (home) game.awayScore else game.homeScore
    if (own == null || theirs == null) return Matchup(opponent, null)
    val outcome = when {
        own > theirs -> "W"
        own < theirs -> "L"
        else -> "T"
    }
    return Matchup(opponent, "$outcome $own–$theirs")
}
```

- [ ] **Step 8: Run the repository tests to verify they pass**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test --tests "dev.gridiron.core.data.PlayerStatsRepositoryTest"`
Expected: PASS. Likely fixes, if something fails:
- `assertLogAddsUp` off by a rounding: the compared stat must be a counting stat (`TARGETS`, `CARRIES`, `PASSING_YARDS`, `FG_ATT`, `POINTS_ALLOWED` are). Don't compare fantasy points.
- A D/ST week with `opponent == "–"`: the `week_team` for `DST_<TEAM>` rows must be the team abbreviation. If it isn't (for example it is null), read the D/ST's team from the `player` table for that pseudo-player instead, and add a test for it.
- `s.games != s.log.size` for a player traded mid-season: the log must still have one row per game; find out why before changing the assertion.

- [ ] **Step 9: Run the whole `:core:statquery` and `:core:data` suites**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:statquery:test :core:data:test`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add core/statquery core/data
git commit -m "data: PlayerStatsRepository, the Player page's season line and game log"
```

---

### Task 3: The Player page's Season stats section

**Files:**
- Create: `app/src/main/kotlin/dev/gridiron/app/PlayerStatsSection.kt`
- Modify: `app/src/main/kotlin/dev/gridiron/app/PlayerScreen.kt` (page model, route loading, section placement, `SectionTitle` visibility)
- Modify: `app/src/main/kotlin/dev/gridiron/app/GridironNavHost.kt` (`Deps.playerStats`, pass to `PlayerRoute`)
- Modify: `app/src/main/kotlin/dev/gridiron/app/GridironApplication.kt` (construct the repository)
- Modify: `CLAUDE.md`, `README.md`, `docs/superpowers/HANDOFF.md`
- Test: `app/src/test/kotlin/dev/gridiron/app/PlayerStatsSectionTest.kt` (create), `app/src/test/kotlin/dev/gridiron/app/NavigationTest.kt` (modify)

**Interfaces:**
- Consumes: `PlayerStats`, `SeasonLineRow`, `GameLogRow`, `PlayerStatsRepository.stats(playerId, position, scoring, season)` (Task 2); `NumberStyle` from `:core:designsystem`.
- Produces: `PlayerPage.stats: PlayerStats?` and `PlayerPage.statsUnavailable: Boolean` (both defaulted, at the end of the constructor); `PlayerScreen(..., onSeason: (Int) -> Unit = {})`; `PlayerRoute(..., playerStats: PlayerStatsRepository? = null)`; `Deps.playerStats: PlayerStatsRepository? = null`; `internal fun LazyListScope.playerStatsItems(stats: PlayerStats, onSeason: (Int) -> Unit)`; test tags `season:<year>`, `seasonLine:<label>` and `gameLog:<week>`.

- [ ] **Step 1: Write the failing section tests**

Create `app/src/test/kotlin/dev/gridiron/app/PlayerStatsSectionTest.kt`:

```kotlin
package dev.gridiron.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.gridiron.core.data.GameLogRow
import dev.gridiron.core.data.PlayerHeader
import dev.gridiron.core.data.PlayerStats
import dev.gridiron.core.data.SeasonLineRow
import dev.gridiron.core.designsystem.GridironTheme
import dev.gridiron.core.statquery.StatColumn
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Player page's Season stats section, fed plain data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class PlayerStatsSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private fun stats(ranked: Boolean = true, games: Int = 2) = PlayerStats(
        season = 2025,
        seasons = persistentListOf(2024, 2025),
        games = games,
        bar = "min 3 targets per game, 4+ games",
        ranked = ranked,
        line = persistentListOf(
            SeasonLineRow(StatColumn.TARGETS, "Targets", "120", "8.0", if (ranked) 0.83f else null),
            SeasonLineRow(StatColumn.TARGET_SHARE, "Target share", "27.5%", "", if (ranked) 0.9f else null),
        ),
        logHeaders = persistentListOf("FPTS", "TAR"),
        log = persistentListOf(
            GameLogRow(1, "vs BAL", "W 27–20", persistentListOf("18.4", "9")),
            GameLogRow(2, "@ DAL", "L 17–24", persistentListOf("7.1", "5")),
        ),
    )

    private fun show(page: PlayerPage, onSeason: (Int) -> Unit = {}) {
        compose.setContent {
            GridironTheme { PlayerScreen("P1", page, liveAvailable = true, onBack = {}, onOpen = {}, onSeason = onSeason) }
        }
    }

    private fun page(stats: PlayerStats? = null, unavailable: Boolean = false) = PlayerPage(
        header = PlayerHeader("P1", "Test Player", "WR", "KC"),
        status = null,
        notes = emptyList(),
        news = emptyList(),
        asOf = null,
        stats = stats,
        statsUnavailable = unavailable,
    )

    @Test
    fun theSectionShowsChipsTheSeasonLineAndTheGameLog() {
        show(page(stats()))
        compose.onNodeWithText("Season stats").assertExists()
        compose.onNodeWithTag("season:2024").assertExists()
        compose.onNodeWithTag("season:2025").assertExists()
        compose.onNodeWithText("2 games · min 3 targets per game, 4+ games").assertExists()
        compose.onNodeWithTag("seasonLine:Targets").assertExists()
        compose.onNodeWithText("120").assertExists()
        compose.onNodeWithText("27.5%").assertExists()
        compose.onNodeWithTag("gameLog:1").assertExists()
        compose.onNodeWithText("vs BAL W 27–20").assertExists()
        compose.onNodeWithText("@ DAL L 17–24").assertExists()
        compose.onNodeWithText("18.4").assertExists()
    }

    @Test
    fun aPlayerBelowTheBarSaysSo() {
        show(page(stats(ranked = false)))
        compose.onNodeWithText("Below the ranking bar").assertExists()
        compose.onNodeWithText("120").assertExists()
    }

    @Test
    fun aSeasonWithNoGamesShowsOnlyTheChips() {
        show(page(stats(games = 0).copy(line = persistentListOf(), log = persistentListOf())))
        compose.onNodeWithTag("season:2025").assertExists()
        compose.onNodeWithText("Targets").assertDoesNotExist()
        compose.onNodeWithTag("gameLog:1").assertDoesNotExist()
    }

    @Test
    fun aPlayerWithNoGamesAnywhereSaysSo() {
        show(page(PlayerStats.EMPTY))
        compose.onNodeWithText("No games in the built seasons.").assertExists()
        compose.onNodeWithTag("season:2025").assertDoesNotExist()
    }

    @Test
    fun aFailedLoadSaysSoAndTheRestOfThePageStays() {
        show(page(unavailable = true))
        compose.onNodeWithText("Season stats aren't available.").assertExists()
        compose.onNodeWithText("No injury designation.").assertExists()
    }

    @Test
    fun noStatsRepositoryMeansNoSection() {
        show(page())
        compose.onNodeWithText("Season stats").assertDoesNotExist()
    }

    @Test
    fun switchingSeasonsAsksForTheChosenOne() {
        val asked = mutableListOf<Int>()
        show(page(stats()), onSeason = { asked += it })
        compose.onNodeWithTag("season:2024").performClick()
        assertEquals(listOf(2024), asked)
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.gridiron.app.PlayerStatsSectionTest"`
Expected: FAIL to compile (`PlayerPage.stats`, `statsUnavailable` and `PlayerScreen(onSeason = ...)` don't exist).

- [ ] **Step 3: Write the section**

Create `app/src/main/kotlin/dev/gridiron/app/PlayerStatsSection.kt`:

```kotlin
package dev.gridiron.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.GameLogRow
import dev.gridiron.core.data.PlayerStats
import dev.gridiron.core.data.SeasonLineRow
import dev.gridiron.core.designsystem.NumberStyle
import kotlin.math.roundToInt

/**
 * The Player page's "Season stats": season chips, the season line with each
 * stat's percentile at his position, and a week-by-week game log. Emits its
 * own list items, so it lays out inside the page's `LazyColumn`.
 */
internal fun LazyListScope.playerStatsItems(stats: PlayerStats, onSeason: (Int) -> Unit) {
    item { SectionTitle("Season stats") }
    if (stats.seasons.isEmpty()) {
        item { Text("No games in the built seasons.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium) }
        return
    }
    item { SeasonChips(stats, onSeason) }
    if (stats.line.isEmpty() && stats.log.isEmpty()) return
    item {
        val summary = listOfNotNull("${stats.games} games", stats.bar).joinToString(" · ")
        Text(
            summary,
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!stats.ranked) {
            Text(
                "Below the ranking bar",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
    item { SeasonLineHeader() }
    items(stats.line.size, key = { "line:${stats.line[it].label}" }) { SeasonLine(stats.line[it]) }
    if (stats.log.isNotEmpty()) {
        item { GameLogHeader(stats.logHeaders) }
        items(stats.log.size, key = { "log:${stats.log[it].week}" }) { GameLog(stats.log[it]) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeasonChips(stats: PlayerStats, onSeason: (Int) -> Unit) {
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (season in stats.seasons.asReversed()) {
            FilterChip(
                selected = season == stats.season,
                onClick = { onSeason(season) },
                label = { Text(season.toString()) },
                modifier = Modifier.testTag("season:$season"),
            )
        }
    }
}

@Composable
private fun SeasonLineHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Stat", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
        HeaderCell("Total", 60)
        HeaderCell("Per game", 60)
        HeaderCell("Pctl", 56)
    }
}

@Composable
private fun HeaderCell(text: String, width: Int) {
    Text(
        text,
        Modifier.width(width.dp),
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.End,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SeasonLine(row: SeasonLineRow) {
    val percent = row.percentile?.let { (it * 100).roundToInt() }
    val spoken = buildString {
        append("${row.label}: ${row.total}")
        if (row.perGame.isNotEmpty()) append(", ${row.perGame} per game")
        append(if (percent != null) ", percentile $percent" else ", not ranked")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .testTag("seasonLine:${row.label}")
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(row.total, Modifier.width(60.dp), style = NumberStyle, textAlign = TextAlign.End, maxLines = 1)
        Text(row.perGame, Modifier.width(60.dp), style = NumberStyle, textAlign = TextAlign.End, maxLines = 1)
        Row(Modifier.width(56.dp).padding(start = 8.dp), horizontalArrangement = Arrangement.End) {
            // Nothing where he isn't ranked; the spoken description says "not ranked".
            row.percentile?.let { p -> LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun GameLogHeader(headers: List<String>) {
    Text(
        "Game log",
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Wk", Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Game", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        headers.forEach { HeaderCell(it, 40) }
    }
}

@Composable
private fun GameLog(row: GameLogRow) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("gameLog:${row.week}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.week.toString(), Modifier.width(28.dp), style = NumberStyle)
        Text(
            listOfNotNull(row.opponent, row.result).joinToString(" "),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        row.cells.forEach { Text(it, Modifier.width(40.dp), style = NumberStyle, textAlign = TextAlign.End, maxLines = 1) }
    }
}
```

`NumberStyle` is used as a `TextStyle` with `style = NumberStyle` exactly as `PercentileBarRow` does.

- [ ] **Step 4: Put the section in the page and load it**

In `PlayerScreen.kt`:

1. Add imports:

```kotlin
import dev.gridiron.core.data.PlayerStats
import dev.gridiron.core.data.PlayerStatsRepository
```

2. Change `PlayerPage` to end with two defaulted fields:

```kotlin
data class PlayerPage(
    val header: PlayerHeader?,
    val status: LiveStatus?,
    val notes: List<InjuryNote>,
    val news: List<NewsItem>,
    val asOf: Instant?,
    val projection: ProjectionCard? = null,
    /** The Season stats section, or null when there is no repository, profile or load yet. */
    val stats: PlayerStats? = null,
    /** The section failed to load: it says so and the rest of the page stays. */
    val statsUnavailable: Boolean = false,
)
```

3. In `PlayerRoute`'s parameters, add `playerStats: PlayerStatsRepository? = null,` after `onManageRosters`. Inside `PlayerRoute`, after the existing `LaunchedEffect(playerId, version, stats, profile) { ... }` block, add:

```kotlin
    var season by remember(playerId) { mutableStateOf<Int?>(null) }
    var loaded by remember(playerId) { mutableStateOf<PlayerStats?>(null) }
    var loadFailed by remember(playerId) { mutableStateOf(false) }
    val header = page?.header
    val pageLoaded = page != null
    // Reloads on a refresh, a profile change and a season chip; a newer key cancels an older load.
    LaunchedEffect(playerId, stats, profile, season, pageLoaded, header) {
        val repo = playerStats
        val active = profile
        if (repo == null || active == null || !pageLoaded) return@LaunchedEffect
        try {
            loaded = repo.stats(playerId, header?.position?.let(Position::fromCode), active, season)
            loadFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loaded = null
            loadFailed = true // never blocks the rest of the page
        }
    }
```

4. In the `PlayerScreen(...)` call at the end of `PlayerRoute`, change the `page` argument from `page` to `page?.copy(stats = loaded, statsUnavailable = loadFailed)` and add `onSeason = { season = it },`.

5. In `PlayerScreen`'s parameters add `onSeason: (Int) -> Unit = {},` after `onProjection`. In its `LazyColumn`, right after the `rosters?.let { list -> ... }` block and before `item { SectionTitle("Status") }`, add:

```kotlin
                page.stats?.let { s -> playerStatsItems(s, onSeason) }
                if (page.statsUnavailable) {
                    item { SectionTitle("Season stats") }
                    item { Text("Season stats aren't available.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium) }
                }
```

6. Change `private fun SectionTitle(text: String)` to `internal fun SectionTitle(text: String)` (the new file uses it).

- [ ] **Step 5: Run the section tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.gridiron.app.PlayerStatsSectionTest"`
Expected: PASS. If a lazy item isn't found because it is below the fold, the sections are compact enough at 892dp; if not, check that the `Text("Season stats")` is not duplicated (the empty-state and failed-state branches are exclusive: `stats` and `statsUnavailable` are never both set).

- [ ] **Step 6: Wire the repository**

In `GridironNavHost.kt`:
- Add `import dev.gridiron.core.data.PlayerStatsRepository`.
- In `Deps`, add after `props`:

```kotlin
    /** The Player page's season stats; null where a test doesn't need them. */
    val playerStats: PlayerStatsRepository? = null,
```

- In the `entry<PlayerKey>` block, add `playerStats = deps.playerStats,` to the `PlayerRoute(...)` call.

In `GridironApplication.kt`: add `import dev.gridiron.core.data.PlayerStatsRepository` and, in `Deps(...)`, add `playerStats = PlayerStatsRepository(executor),` after `props = propsRepo,`.

- [ ] **Step 7: Write the failing navigation test**

In `NavigationTest.kt`: add `import dev.gridiron.core.data.PlayerStatsRepository`, and in `setUp`'s `Deps(...)` add `playerStats = PlayerStatsRepository(executor),`. Add this test inside the class:

```kotlin
    @Test
    fun aPlayerPageShowsSeasonStats() {
        val (first, _) = firstTwoPlayerNames()
        compose.setContent { GridironTheme { GridironNavHost(deps) } }
        settle()

        compose.onNodeWithContentDescription(first, substring = true).performClick()
        settle()

        compose.onNodeWithText("Season stats").assertExists()
        compose.onNodeWithText("Game log").assertExists()
    }
```

If "Game log" is below the fold in the Robolectric viewport, assert on `compose.onNodeWithText("Season stats")` and the chip only (`compose.onAllNodesWithText("2025").assertCountEquals(1)` is not needed).

- [ ] **Step 8: Run the navigation test**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :app:testDebugUnitTest --tests "dev.gridiron.app.NavigationTest"`
Expected: PASS. (`ReloadOnSwapTest` and `LiveScreensTest` keep compiling because the new parameters and fields are defaulted.)

- [ ] **Step 9: Update the docs**

`CLAUDE.md`:
- In the `:app` bullet, change "News, Player page, live Injury report, Settings…" to "News, Player page (status, injury notes, news, a 'This week' card, and Season stats: chips, a season line with position percentiles, a game log), live Injury report, Settings…".
- In the `:core:data` bullet, add "`PlayerStatsRepository` (the Player page's season line and game log, built with the Grid's query builder)" after "`PlayerDirectory` (…)".
- In "Known Gaps", delete the bullet "**Compare shows offense columns for a kicker or D/ST** …".
- In "Grid entry points", after "and a 'This week' projection card that opens the waterfall" add ", Season stats".

`README.md`: in the Status table's **Kickers and D/ST** row, remove "Compare and the Player page's season stats don't cover them yet." and add "Compare and the Player page's season stats cover them too."

`docs/superpowers/HANDOFF.md`: in "In flight", replace the sentence about the design awaiting approval with "Built on this branch (plan `plans/2026-09-29-player-season-stats.md`); phone timing for the section (up to 20 queries) isn't measured yet." and move "Player page season stats" out of the list of remaining gaps.

- [ ] **Step 10: Run the full verification**

Run each and confirm PASS:

```bash
GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew test
./gradlew :app:assembleRelease lint
```

Then check the diff is only what the plan touched: `git status --short` lists the Kotlin files above, the three docs, and nothing else.

- [ ] **Step 11: Commit**

```bash
git add app docs CLAUDE.md README.md
git commit -m "app: Season stats on the Player page, for every position"
```

---

## Self-Review

**Spec coverage.**
- Season chips (latest by default, only seasons with games) → Task 2 (`seasons`, `season` fallback), Task 3 (`SeasonChips`, `season:` tags).
- Season line (total, per game, percentile, games and bar header, below-the-bar note, rates recomputed, per-game blank for rates) → Task 2 `seasonValues`/`hasPerGame`, Task 3 `SeasonLine`.
- Game log (week, opponent home/away, result, fantasy points and four stats, byes omitted, missing schedule dash) → Task 2 `weekTeams`, `games`, `matchup`, `weekValues`; Task 3 `GameLog`.
- Per-position stat sets, K/D/ST without expected points → Task 1 (`K_SET`, `DST_SET`), Task 2 (`PlayerStatSets`).
- Hidden without repository or profile; failure line; empty state → Task 3 (`PlayerRoute` gating, `statsUnavailable`, `EMPTY`).
- Compare: K/D/ST sets, qualifiers, radar axes, empty groups omitted, no scatter, mixed kinds → Task 1.
- Tests listed in the spec (`PlayerStatSetsTest`, repository tests, matchup tests, Compare tests, Compose test) → Tasks 1–3. The spec's separate contract test ("season line totals equal the sum of the game log and the Grid's row") is covered by `assertLogAddsUp` (log equals season line); equality with the Grid's row holds because both come from the same query builder, and is not asserted separately.
- Docs (Known Gaps removal, module notes) → Task 3 Step 9.

**Placeholder scan.** No TBD/TODO; every code step has code. The two conditional notes (Step 8's D/ST team fallback, Step 7's below-the-fold assertion) name the exact condition and the change.

**Type consistency.** `PlayerStats(season, seasons, games, bar, ranked, line, logHeaders, log)`, `SeasonLineRow(column, label, total, perGame, percentile)`, `GameLogRow(week, opponent, result, cells)` are defined in Task 2 and used identically in Task 3's tests and composables. `PlayerStatsRepository.stats(playerId, position, scoring, season)` matches its call in `PlayerRoute`. `matchup`, `ScheduleGame`, `Matchup` are defined once (Task 2, Step 3, and the same code re-emitted in Step 7's full-file replacement).
