# Projection Accuracy Implementation Plan (sub-project 2 of 4)

> **Executed, historical.** Shipped and merged; kept for lookup. It describes intent as written at the time, not current behavior. See `CLAUDE.md` for what exists.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A ☰ → Projection accuracy page shows how the model's past-week projections did against what players scored, by position and season, beside two simple baselines, and CI fails if the model stops beating the season-to-date average.

**Architecture:** Nothing new is stored. The forecast already keeps every past week's `final` projection. A pure `backtest()` in `:core:projections` joins those rows to the real games in `player_week_stat`, scores both under the active profile with the existing `score()` and `projectPoints()`, and reports MAE, bias, R² and floor-to-ceiling calibration per position, for the model, the season-to-date average and the last-4-games average, all on the same player-weeks. `AccuracyRepository` feeds it from SQL. A ViewModel runs it off the main thread. A JUnit gate, switched on by an environment variable in CI's parity job, runs the same code on a 2024–2025 build.

**Tech Stack:** Kotlin 2 (explicit API, warnings as errors), JUnit Jupiter (JVM modules), JUnit 4 + Robolectric (Android modules), Jetpack Compose, Navigation 3, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-26-projection-model-design.md`, §4 "Accuracy (sub-project 2)", plus "Amendment: layer 2" (its acceptance note hands the accuracy gate to this sub-project).

## Global Constraints

- Warnings are errors: no unused parameters, variables or imports. Explicit API mode in JVM modules (`:core:projections`, `:core:data`): every declaration states its visibility.
- Scoring happens on the phone under the active profile. Nothing new is stored, and there is no schema change: `SCHEMA_VERSION` stays 7 and `FORECAST_VERSION` stays 2.
- Spec §4, verbatim: "Only player-weeks where the model projected at least 5 points and the player appeared count, so benched players don't flatter the numbers."
- Spec §4: per position and season, the page shows MAE, bias (mean error) and R²; calibration, "the share of actual scores that fell between floor and ceiling. The target is about 80%"; and the same error numbers for "the season-to-date average and the last-4-games average", computed on the fly from `player_week_stat`.
- Spec §4: "The page notes that past weeks are projected without props."
- Spec §4 gate: "a new check in the parity job builds 2024–2025. It fails if, for 2025 under PPR, the model's MAE isn't below the season-to-date baseline's for each of QB, RB, WR and TE." Spec Risks: "the gate is never skipped."
- Floor and ceiling are the 10th and 90th percentiles of the existing Monte Carlo (`projectPoints`), as on the Player page and the Projections list.
- Error is projected minus actual, so a positive bias means the predictor ran high.
- Copy rules (from existing UI): status lines are one sentence each, no exclamation marks.

## Review Focus

- **The upcoming week is partly played** (a refresh on Friday, after Thursday's game): the upcoming week isn't finished, so none of its player-weeks count, even the ones whose game is over. Test in Task 2.
- **A projected player who didn't play** (inactive, injured, a healthy scratch): the player-week doesn't count; it is never scored as a zero. Tests in Tasks 1 and 2.
- **A player's first game of the season**: there's no season-to-date average yet, so the player-week is left out for all three predictors, and the model and both baselines are always measured on the same player-weeks. Test in Task 1.
- **A season with nothing to measure**: a database built before projections, a failed forecast, the current season in week 1 or 2, or a custom profile under which nobody is projected 5 points. The page says why in one sentence, still offers the other seasons, and never shows NaN. Tests in Tasks 2 and 3.
- **A custom profile with position rules** (TE premium): projections and real games are scored with the same position, so a TE's reception bonus is on both sides. Test in Task 1.

## Measured while planning (2026-09-27)

A Python stand-in for Task 1's rules, run on a fresh Kotlin build of 2024–2025 (`etl/build/accuracy.db`), gave these MAEs under PPR. Each cell is model / season-to-date / last 4:

| Season | QB | RB | WR | TE |
|---|---|---|---|---|
| 2025 (gate) | 6.42 / 7.14 / 7.07 (n 498) | 5.88 / 6.01 / 6.18 (n 804) | 5.40 / 5.79 / 5.81 (n 1203) | 4.87 / 5.19 / 5.42 (n 455) |
| 2024 (no 2023 history) | 6.28 / 6.72 / 6.99 | 5.44 / 5.48 / 5.56 | 6.00 / 5.88 / 6.10 | 5.49 / 5.25 / 5.43 |

- The gate passes today at every position. RB has the thinnest margin (0.13).
- 2024 loses at WR and TE. Task 2 uses that season to prove the gate can fail.
- The model's bias is negative at every position (−0.2 to −1.6 in 2025). This sample only counts players who played.
- Timing: 3,595 player-weeks of 2025 took 2.1 s on the JVM to simulate at 500 draws each, and the queries took 1.2 s. The plan uses 250 draws and simulates only counted player-weeks (about 2,960).

## File Structure

**New**

| File | Responsibility |
|---|---|
| `core/projections/.../Backtest.kt` | `PlayedWeek`, `ProjectedWeek`, `ErrorStats`, `PositionAccuracy`, `errorStats`, `backtest`, the sample and draw constants |
| `core/projections/.../AccuracyQueries.kt` | SQL: each season's first projected week, a season's past final projections, games and scoring stats |
| `core/data/src/test/.../AccuracyContractTest.kt` | The backtest end to end on the CI-built database |
| `core/data/src/test/.../AccuracyGateTest.kt` | CI's gate, on only when `GRIDIRON_ACCURACY_GATE` names a season |
| `feature/projections/.../AccuracyViewModel.kt` | `AccuracyState`, `AccuracyViewModel` |
| `feature/projections/.../AccuracyText.kt` | Number formatting for the page |

**Modified**

| File | Change |
|---|---|
| `core/data/.../AccuracyRepository.kt` | `status`, `seasons`, `backtest`; `summary` and `AccuracyRow` removed (Task 3) |
| `core/data/src/test/.../AccuracyRepositoryTest.kt` | Rewritten for the backtest |
| `feature/projections/.../AccuracyRoute.kt`, `AccuracyScreen.kt` | Rewritten: ViewModel, season chips, a table per position |
| `build-logic/convention/src/main/kotlin/ProjectExtensions.kt` | Test tasks receive `GRIDIRON_ACCURACY_GATE` as an input |
| `.github/workflows/ci.yml` | Parity job: build 2024–2025, run the gate, print the table |
| `app/.../GridironNavHost.kt` | ☰ → Projection accuracy; the route's new arguments |
| `app/src/test/.../NavigationTest.kt` | The menu opens the page |
| `CLAUDE.md` | Modules, commands, testing, known gaps |

## Sessions

Per `docs/superpowers/HANDOFF.md`, one session runs four tasks: **Session A** runs Tasks 1–4.

---

### Task 1: The backtest

**Files:**
- Create: `core/projections/src/main/kotlin/dev/gridiron/core/projections/Backtest.kt`
- Test: `core/projections/src/test/kotlin/dev/gridiron/core/projections/BacktestTest.kt`

**Interfaces:**
- Consumes: `score(components: Map<Component, Double>, profile: ScoringProfile, position: Position?): Double` (`Scorer.kt`); `projectPoints(components: List<ProjectionComponent>, profile: ScoringProfile, position: Position?, draws: Int): ProjectedPoints` (`ProjectedPoints.kt`); `ProjectionComponent(metricId, mean, variance, family)`; `RULE_INPUTS`, `BONUS_INPUTS` (`:core:statquery`).
- Produces (all public, package `dev.gridiron.core.projections`):
  - `ACCURACY_POSITIONS: List<String>` = QB, RB, WR, TE; `ACCURACY_MIN_POINTS: Double` = 5.0; `BACKTEST_DRAWS: Int` = 250; `ACTUAL_SCORING_COMPONENTS: List<Component>`.
  - `data class PlayedWeek(playerId: String, season: Int, week: Int, stats: Map<Component, Double>)`.
  - `data class ProjectedWeek(playerId: String, position: String, week: Int, components: List<ProjectionComponent>)`.
  - `data class ErrorStats(mae: Double, bias: Double, r2: Double?)`.
  - `data class PositionAccuracy(position: String, playerWeeks: Int, model: ErrorStats, seasonAverage: ErrorStats, lastFour: ErrorStats, calibration: Double)`.
  - `fun errorStats(pairs: List<Pair<Double, Double>>): ErrorStats` (pairs are predicted to actual).
  - `fun backtest(season: Int, projected: List<ProjectedWeek>, played: List<PlayedWeek>, profile: ScoringProfile, draws: Int = BACKTEST_DRAWS): List<PositionAccuracy>`.

- [ ] **Step 1: Write the failing tests**

`core/projections/src/test/kotlin/dev/gridiron/core/projections/BacktestTest.kt`:

```kotlin
package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.statquery.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BacktestTest {
    private fun stats(receptions: Double, yards: Double, tds: Double = 0.0) = mapOf(
        Component("receptions") to receptions,
        Component("receiving_yards") to yards,
        Component("receiving_tds") to tds,
    )

    /** Zero variance simulates as a point mass, so the floor and ceiling equal the projected points. */
    private fun projection(id: String, position: String, week: Int, receptions: Double, yards: Double) = ProjectedWeek(
        id, position, week,
        listOf(ProjectionComponent("receptions", receptions, 0.0), ProjectionComponent("receiving_yards", yards, 0.0)),
    )

    // Full PPR: a reception is 1 point, 10 receiving yards 1 point, a receiving TD 6.
    private val played = listOf(
        PlayedWeek("w", 2024, 17, stats(2.0, 20.0)), // 4
        PlayedWeek("w", 2025, 1, stats(5.0, 50.0)), // 10
        PlayedWeek("w", 2025, 2, stats(8.0, 100.0, 1.0)), // 24
        PlayedWeek("w", 2025, 4, stats(4.0, 40.0)), // 8
    )

    @Test
    fun `errorStats is the mean absolute error, the mean error and R squared`() {
        val stats = errorStats(listOf(12.0 to 24.0, 8.0 to 8.0))
        assertEquals(6.0, stats.mae, 1e-9)
        assertEquals(-6.0, stats.bias, 1e-9)
        // Actuals average 16: total sum of squares 64 + 64, residual 144 + 0.
        assertEquals(1.0 - 144.0 / 128.0, stats.r2!!, 1e-9)
    }

    @Test
    fun `R squared is null when every actual score is the same`() {
        assertNull(errorStats(listOf(5.0 to 7.0)).r2)
        // 0.3 three times doesn't average to exactly 0.3 in floating point: still no spread.
        assertNull(errorStats(listOf(0.1 to 0.3, 0.2 to 0.3, 0.9 to 0.3)).r2)
    }

    @Test
    fun `the model and both baselines are measured on the same player-weeks`() {
        val projected = listOf(
            projection("w", "WR", 1, 6.0, 60.0), // his first game of 2025: no season-to-date average, so left out
            projection("w", "WR", 2, 6.0, 60.0), // 12 projected, 24 scored
            projection("w", "WR", 3, 6.0, 60.0), // he didn't play: left out, never scored as zero
            projection("w", "WR", 4, 4.0, 40.0), // 8 projected, 8 scored
        )

        val wr = backtest(2025, projected, played, ScoringPresets.PPR).single()

        assertEquals("WR", wr.position)
        assertEquals(2, wr.playerWeeks)
        assertEquals(6.0, wr.model.mae, 1e-9)
        assertEquals(-6.0, wr.model.bias, 1e-9)
        // Season to date: week 2 has week 1's 10; week 4 has (10 + 24) / 2 = 17.
        assertEquals((14.0 + 9.0) / 2, wr.seasonAverage.mae, 1e-9)
        assertEquals((-14.0 + 9.0) / 2, wr.seasonAverage.bias, 1e-9)
        // Last four reaches back into 2024: week 2 has (4 + 10) / 2 = 7; week 4 has (4 + 10 + 24) / 3.
        val week4 = 38.0 / 3 - 8.0
        assertEquals((17.0 + week4) / 2, wr.lastFour.mae, 1e-9)
        assertEquals((-17.0 + week4) / 2, wr.lastFour.bias, 1e-9)
        // Floor and ceiling were 12 (scored 24, outside) and 8 (scored 8, inside).
        assertEquals(0.5, wr.calibration, 1e-9)
    }

    @Test
    fun `last four keeps only the four most recent games`() {
        val games = (1..6).map { PlayedWeek("r", 2025, it, stats(it.toDouble(), 0.0)) } // week n scores n

        val rb = backtest(2025, listOf(projection("r", "RB", 6, 6.0, 0.0)), games, ScoringPresets.PPR).single()

        assertEquals(-2.5, rb.lastFour.bias, 1e-9) // (2 + 3 + 4 + 5) / 4 against 6
        assertEquals(-3.0, rb.seasonAverage.bias, 1e-9) // (1 + 2 + 3 + 4 + 5) / 5 against 6
    }

    @Test
    fun `a projection under the minimum doesn't count, and a position with nothing to count is left out`() {
        val projected = listOf(projection("w", "WR", 2, 2.0, 20.0)) // 4 points
        assertTrue(backtest(2025, projected, played, ScoringPresets.PPR).isEmpty())
    }

    @Test
    fun `projections and real games are scored with the player's position`() {
        val tePremium = ScoringPresets.PPR.copy(id = "te", name = "TE premium", receptionByPosition = mapOf(Position.TE to 1.5))
        val te = listOf(PlayedWeek("t", 2025, 1, stats(4.0, 0.0)), PlayedWeek("t", 2025, 2, stats(4.0, 0.0)))

        // Four receptions at 1.5: 6 projected, 6 scored. Under plain PPR the projection is 4 and wouldn't count.
        val result = backtest(2025, listOf(projection("t", "TE", 2, 4.0, 0.0)), te, tePremium).single()

        assertEquals(0.0, result.model.mae, 1e-9)
        assertEquals(0.0, result.seasonAverage.mae, 1e-9)
        assertTrue(backtest(2025, listOf(projection("t", "TE", 2, 4.0, 0.0)), te, ScoringPresets.PPR).isEmpty())
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :core:projections:test --tests "*BacktestTest*"`
Expected: FAIL to compile with `Unresolved reference 'ProjectedWeek'` (and `PlayedWeek`, `backtest`, `errorStats`).

- [ ] **Step 3: Write the backtest**

`core/projections/src/main/kotlin/dev/gridiron/core/projections/Backtest.kt`:

```kotlin
package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.statquery.BONUS_INPUTS
import dev.gridiron.core.statquery.Component
import dev.gridiron.core.statquery.RULE_INPUTS
import kotlin.math.abs

/** The positions the backtest measures, in the page's order. */
public val ACCURACY_POSITIONS: List<String> = listOf("QB", "RB", "WR", "TE")

/** A player-week counts only when the model projected at least this many points (spec §4). */
public const val ACCURACY_MIN_POINTS: Double = 5.0

/**
 * Draws per player-week for the floor and ceiling. A season is about 3,000
 * counted player-weeks, and calibration is a share over all of them, so each
 * needs far fewer draws than a single player's card.
 */
public const val BACKTEST_DRAWS: Int = 250

/** The short-memory baseline's window, in games played. */
private const val LAST_GAMES = 4

/** Every stat a real game's fantasy score reads. */
public val ACTUAL_SCORING_COMPONENTS: List<Component> =
    (RULE_INPUTS.values.flatMap { inputs -> inputs.actual.map { it.component } } + BONUS_INPUTS.values.flatten())
        .distinct()
        .sortedBy { it.id }

/** A week a player played, with his scoring stats. */
public data class PlayedWeek(val playerId: String, val season: Int, val week: Int, val stats: Map<Component, Double>)

/** The model's final projection for one player-week of the season being measured. */
public data class ProjectedWeek(val playerId: String, val position: String, val week: Int, val components: List<ProjectionComponent>)

/** How far one predictor's points were from what players scored. Error is predicted minus actual. */
public data class ErrorStats(
    val mae: Double,
    /** The mean error: positive when the predictor ran high. */
    val bias: Double,
    /** Null when the actual scores have no spread (a single player-week, say). */
    val r2: Double?,
)

/** One position's season: the model and the two baselines, on the same player-weeks. */
public data class PositionAccuracy(
    val position: String,
    val playerWeeks: Int,
    val model: ErrorStats,
    val seasonAverage: ErrorStats,
    val lastFour: ErrorStats,
    /** The share of actual scores between the model's floor and ceiling: about 0.8 when the spread is right. */
    val calibration: Double,
)

/** Mean absolute error, mean error and R² of (predicted, actual) pairs. */
public fun errorStats(pairs: List<Pair<Double, Double>>): ErrorStats {
    require(pairs.isNotEmpty()) { "no player-weeks to measure" }
    val n = pairs.size
    val mae = pairs.sumOf { (predicted, actual) -> abs(predicted - actual) } / n
    val bias = pairs.sumOf { (predicted, actual) -> predicted - actual } / n
    val meanActual = pairs.sumOf { it.second } / n
    val total = pairs.sumOf { (_, actual) -> (actual - meanActual) * (actual - meanActual) }
    val residual = pairs.sumOf { (predicted, actual) -> (actual - predicted) * (actual - predicted) }
    // Rounding leaves a tiny total when every actual is equal; that is still no spread.
    return ErrorStats(mae, bias, if (total > 1e-9) 1.0 - residual / total else null)
}

private class Sample(
    val actual: Double,
    val model: Double,
    val floor: Double,
    val ceiling: Double,
    val seasonAverage: Double,
    val lastFour: Double,
)

/**
 * The walk-forward backtest of [season] under [profile] (spec §4).
 *
 * A player-week counts when the model projected at least
 * [ACCURACY_MIN_POINTS], the player played that week, and he had already
 * played earlier that season. The last rule keeps the season-to-date average
 * defined, so all three predictors are measured on the same player-weeks.
 * [projected] holds [season]'s past weeks only. [played] holds the weeks
 * actually played, and must include the previous season, because the
 * last-four average reaches back into it. A position with nothing to count
 * is left out.
 */
public fun backtest(
    season: Int,
    projected: List<ProjectedWeek>,
    played: List<PlayedWeek>,
    profile: ScoringProfile,
    draws: Int = BACKTEST_DRAWS,
): List<PositionAccuracy> {
    val positionOf = projected.associate { it.playerId to it.position }
    // Each projected player's games, oldest first, with the points he scored in each.
    val games: Map<String, List<Pair<PlayedWeek, Double>>> = played
        .filter { it.playerId in positionOf }
        .groupBy { it.playerId }
        .mapValues { (id, weeks) ->
            val position = Position.fromCode(positionOf.getValue(id))
            weeks.sortedWith(compareBy({ it.season }, { it.week })).map { it to score(it.stats, profile, position) }
        }

    val samples = HashMap<String, MutableList<Sample>>()
    for (p in projected) {
        if (p.position !in ACCURACY_POSITIONS) continue
        val mine = games[p.playerId] ?: continue
        val at = mine.indexOfFirst { (game, _) -> game.season == season && game.week == p.week }
        if (at < 0) continue // he didn't play
        val earlier = mine.subList(0, at)
        val seasonToDate = earlier.filter { (game, _) -> game.season == season }.map { it.second }
        if (seasonToDate.isEmpty()) continue // his first game of the season
        val position = Position.fromCode(p.position)
        val means = p.components.associate { Component(it.metricId) to it.mean }
        if (score(means, profile, position) < ACCURACY_MIN_POINTS) continue
        val model = projectPoints(p.components, profile, position, draws)
        samples.getOrPut(p.position) { mutableListOf() } += Sample(
            actual = mine[at].second,
            model = model.points,
            floor = model.floor,
            ceiling = model.ceiling,
            seasonAverage = seasonToDate.average(),
            lastFour = earlier.takeLast(LAST_GAMES).map { it.second }.average(),
        )
    }

    return ACCURACY_POSITIONS.mapNotNull { position ->
        val s = samples[position] ?: return@mapNotNull null
        PositionAccuracy(
            position = position,
            playerWeeks = s.size,
            model = errorStats(s.map { it.model to it.actual }),
            seasonAverage = errorStats(s.map { it.seasonAverage to it.actual }),
            lastFour = errorStats(s.map { it.lastFour to it.actual }),
            calibration = s.count { it.actual >= it.floor && it.actual <= it.ceiling }.toDouble() / s.size,
        )
    }
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./gradlew :core:projections:test`
Expected: PASS, with the 7 new `BacktestTest` tests and every existing test in the module.

- [ ] **Step 5: Commit**

```bash
git add core/projections/src/main/kotlin/dev/gridiron/core/projections/Backtest.kt \
        core/projections/src/test/kotlin/dev/gridiron/core/projections/BacktestTest.kt
git commit -m "projections: backtest past weeks against the season-to-date and last-4 averages"
```

---

### Task 2: The repository, the contract test and CI's accuracy gate

**Files:**
- Create: `core/projections/src/main/kotlin/dev/gridiron/core/projections/AccuracyQueries.kt`
- Modify: `core/data/src/main/kotlin/dev/gridiron/core/data/AccuracyRepository.kt` (add `status`, `seasons`, `backtest`; `summary` stays until Task 3)
- Replace: `core/data/src/test/kotlin/dev/gridiron/core/data/AccuracyRepositoryTest.kt`
- Create: `core/data/src/test/kotlin/dev/gridiron/core/data/AccuracyContractTest.kt`
- Create: `core/data/src/test/kotlin/dev/gridiron/core/data/AccuracyGateTest.kt`
- Modify: `build-logic/convention/src/main/kotlin/ProjectExtensions.kt` (`configureTests`)
- Modify: `.github/workflows/ci.yml` (header comment, parity job)

**Interfaces:**
- Consumes: Task 1's `backtest`, `ProjectedWeek`, `PlayedWeek`, `PositionAccuracy`, `ErrorStats`, `BACKTEST_DRAWS`, `ACCURACY_POSITIONS`, `ACTUAL_SCORING_COMPONENTS`; `ProjectionsRepository.status(): ForecastStatus` (its `upcoming: Map<Int, Int>` is the upcoming week by season); `Components.GAMES` (`g`: 1 for a week with a recorded play).
- Produces:
  - `AccuracyQueries.firstProjectedWeeks(): SqlQuery` (columns season, min week), `AccuracyQueries.projected(season: Int, beforeWeek: Int): SqlQuery` (player_id, position, week, metric_id, mean, variance, dist_family), and `AccuracyQueries.played(fromSeason: Int, toSeason: Int): SqlQuery` (player_id, season, week, metric_id, value).
  - `AccuracyRepository.status(): ForecastStatus`, `AccuracyRepository.seasons(): List<Int>` (seasons with a finished projected week, oldest first), and `AccuracyRepository.backtest(season: Int, profile: ScoringProfile, draws: Int = BACKTEST_DRAWS): List<PositionAccuracy>`.
  - `GRIDIRON_ACCURACY_GATE`, the season CI's gate checks. It is passed to every test task.

- [ ] **Step 1: Write the failing repository tests**

Replace `core/data/src/test/kotlin/dev/gridiron/core/data/AccuracyRepositoryTest.kt` with:

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.testing.JdbcQueryExecutor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager

class AccuracyRepositoryTest {

    /**
     * A fresh on-disk SQLite database with the tables the backtest reads, in
     * their v7 shape, seeded with [inserts], then reopened read-only through
     * [JdbcQueryExecutor].
     */
    private fun fixture(inserts: List<String>): JdbcQueryExecutor {
        val file = File.createTempFile("accuracy-fixture", ".db")
        file.deleteOnExit()
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """CREATE TABLE player_week_projection (
                         player_id TEXT NOT NULL, season INTEGER NOT NULL, week INTEGER NOT NULL,
                         metric_id TEXT NOT NULL, stage TEXT NOT NULL, mean REAL NOT NULL, variance REAL NOT NULL,
                         PRIMARY KEY (player_id, season, week, metric_id, stage)) WITHOUT ROWID""",
                )
                st.executeUpdate(
                    """CREATE TABLE player_week_stat (
                         player_id TEXT NOT NULL, season INTEGER NOT NULL, week INTEGER NOT NULL, team TEXT NOT NULL,
                         metric_id TEXT NOT NULL, value REAL NOT NULL,
                         PRIMARY KEY (player_id, season, week, metric_id)) WITHOUT ROWID""",
                )
                st.executeUpdate("CREATE TABLE metric (id TEXT PRIMARY KEY, dist_family TEXT)")
                st.executeUpdate("CREATE TABLE player (player_id TEXT PRIMARY KEY, full_name TEXT NOT NULL, position TEXT, team TEXT)")
                st.executeUpdate("CREATE TABLE schema_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                inserts.forEach { st.executeUpdate(it) }
            }
        }
        return JdbcQueryExecutor(file.path)
    }

    private fun meta(key: String, value: String) = "INSERT INTO schema_meta VALUES ('$key', '$value')"

    private fun player(id: String, position: String) = "INSERT INTO player VALUES ('$id', 'Player $id', '$position', 'KC')"

    /** A zero-variance final projection of [receptions] catches for 10 yards each. */
    private fun projected(id: String, season: Int, week: Int, receptions: Double) = listOf(
        "INSERT INTO player_week_projection VALUES ('$id', $season, $week, 'receptions', 'final', $receptions, 0.0)",
        "INSERT INTO player_week_projection VALUES ('$id', $season, $week, 'receiving_yards', 'final', ${receptions * 10}, 0.0)",
    )

    /** A week played ([Components.GAMES] = 1) with [receptions] catches for 10 yards each, plus [tds] receiving TDs. */
    private fun played(id: String, season: Int, week: Int, receptions: Double, tds: Double = 0.0) = listOf(
        "INSERT INTO player_week_stat VALUES ('$id', $season, $week, 'KC', 'g', 1.0)",
        "INSERT INTO player_week_stat VALUES ('$id', $season, $week, 'KC', 'receptions', $receptions)",
        "INSERT INTO player_week_stat VALUES ('$id', $season, $week, 'KC', 'receiving_yards', ${receptions * 10})",
        "INSERT INTO player_week_stat VALUES ('$id', $season, $week, 'KC', 'receiving_tds', $tds)",
    )

    @Test
    fun `seasons are the ones with a finished week projected`() = runTest {
        fixture(
            listOf(meta("forecast_status", "ok"), meta("forecast_week:2026", "1"), player("w", "WR")) +
                projected("w", 2025, 2, 6.0) +
                // 2026 has only its upcoming week so far.
                projected("w", 2026, 1, 6.0) +
                "INSERT INTO player_week_projection VALUES ('w', 2026, 1, 'receptions', 'baseline', 6.0, 0.0)",
        ).use { executor ->
            assertEquals(listOf(2025), AccuracyRepository(executor).seasons())
        }
    }

    @Test
    fun `the backtest reads past final projections and the weeks actually played`() = runTest {
        fixture(
            listOf(meta("forecast_status", "ok"), meta("forecast_week:2025", "5"), player("w", "WR"), player("k", "K")) +
                played("w", 2024, 17, 2.0) + // 4 points: last four reaches back to it
                played("w", 2025, 1, 5.0) + // 10
                played("w", 2025, 2, 8.0, tds = 1.0) + // 24
                played("w", 2025, 4, 4.0) + // 8
                played("w", 2025, 5, 10.0) + // Thursday of the upcoming week: not finished, so it doesn't count
                projected("w", 2025, 1, 6.0) + // first game of the season
                projected("w", 2025, 2, 6.0) + // 12 against 24
                projected("w", 2025, 3, 6.0) + // didn't play
                projected("w", 2025, 4, 4.0) + // 8 against 8
                projected("w", 2025, 5, 7.0) +
                // A kicker who played twice isn't measured.
                played("k", 2025, 1, 9.0) + played("k", 2025, 2, 9.0) + projected("k", 2025, 2, 9.0),
        ).use { executor ->
            val wr = AccuracyRepository(executor).backtest(2025, ScoringPresets.PPR).single()

            assertEquals("WR", wr.position)
            assertEquals(2, wr.playerWeeks)
            assertEquals(6.0, wr.model.mae, 1e-9)
            assertEquals((17.0 + (38.0 / 3 - 8.0)) / 2, wr.lastFour.mae, 1e-9)
            assertEquals(0.5, wr.calibration, 1e-9)
        }
    }

    @Test
    fun `a database built before projections has no seasons and nothing to measure`() = runTest {
        fixture(emptyList()).use { executor ->
            val repo = AccuracyRepository(executor)
            assertEquals(null, repo.status().status)
            assertEquals(emptyList<Int>(), repo.seasons())
            assertEquals(emptyList<Any>(), repo.backtest(2025, ScoringPresets.PPR))
        }
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :core:data:test --tests "*AccuracyRepositoryTest*"`
Expected: FAIL to compile with `Unresolved reference 'seasons'` (and `backtest`, `status`).

- [ ] **Step 3: Write the queries**

`core/projections/src/main/kotlin/dev/gridiron/core/projections/AccuracyQueries.kt`:

```kotlin
package dev.gridiron.core.projections

import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.Components
import dev.gridiron.core.statquery.SqlQuery

/** The backtest's reads. Every value is bound, as in [ProjectionQueries]. */
public object AccuracyQueries {
    private fun placeholders(n: Int): String = List(n) { "?" }.joinToString(",")

    /** Each season's first week with a final projection. */
    public fun firstProjectedWeeks(): SqlQuery = SqlQuery(
        "SELECT season, MIN(week) FROM player_week_projection WHERE stage = 'final' GROUP BY season ORDER BY season",
        emptyList(),
    )

    /** [season]'s final projections for weeks before [beforeWeek], at the positions the backtest measures. */
    public fun projected(season: Int, beforeWeek: Int): SqlQuery = SqlQuery(
        """
        SELECT p.player_id, pl.position, p.week, p.metric_id, p.mean, p.variance, m.dist_family
        FROM player_week_projection p
        JOIN player pl ON pl.player_id = p.player_id
        LEFT JOIN metric m ON m.id = p.metric_id
        WHERE p.season = ? AND p.week < ? AND p.stage = 'final'
          AND pl.position IN (${placeholders(ACCURACY_POSITIONS.size)})
        """.trimIndent(),
        listOf(Bind.Integer(season.toLong()), Bind.Integer(beforeWeek.toLong())) + ACCURACY_POSITIONS.map { Bind.Text(it) },
    )

    /** Every week with a recorded play ([Components.GAMES]) and the scoring stats, [fromSeason] through [toSeason]. */
    public fun played(fromSeason: Int, toSeason: Int): SqlQuery {
        val metrics = listOf(Components.GAMES) + ACTUAL_SCORING_COMPONENTS
        return SqlQuery(
            """
            SELECT player_id, season, week, metric_id, value
            FROM player_week_stat
            WHERE season BETWEEN ? AND ? AND metric_id IN (${placeholders(metrics.size)})
            """.trimIndent(),
            listOf(Bind.Integer(fromSeason.toLong()), Bind.Integer(toSeason.toLong())) + metrics.map { Bind.Text(it.id) },
        )
    }
}
```

- [ ] **Step 4: Add the repository's backtest**

In `core/data/src/main/kotlin/dev/gridiron/core/data/AccuracyRepository.kt`, keep `AccuracyRow` and `summary` for now (Task 3 removes them with the screen that calls them). Replace the class header and add the new members above `summary`, so the file reads:

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.doubleOrNull
import dev.gridiron.core.database.textOrNull
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.projections.AccuracyQueries
import dev.gridiron.core.projections.BACKTEST_DRAWS
import dev.gridiron.core.projections.ForecastStatus
import dev.gridiron.core.projections.PlayedWeek
import dev.gridiron.core.projections.PositionAccuracy
import dev.gridiron.core.projections.ProjectedWeek
import dev.gridiron.core.projections.ProjectionComponent
import dev.gridiron.core.projections.backtest
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.Component
import dev.gridiron.core.statquery.Components
import dev.gridiron.core.statquery.SqlQuery

public data class AccuracyRow(
    val position: String,
    val metricId: String,
    val baseline: String,
    val sampleN: Int,
    val mae: Double,
    val rmse: Double,
    val bias: Double,
    val r2: Double?,
)

/** The accuracy page's numbers: the stored past-week projections against the games actually played. */
public class AccuracyRepository(private val executor: QueryExecutor) {
    private val projections = ProjectionsRepository(executor)

    /** How the last refresh's forecast went. */
    public suspend fun status(): ForecastStatus = projections.status()

    /**
     * Seasons with at least one finished week projected, oldest first. The
     * upcoming week isn't finished, even after one of its games is played.
     */
    public suspend fun seasons(): List<Int> {
        val upcoming = status().upcoming
        return executor.query(AccuracyQueries.firstProjectedWeeks()) { it.long(0).toInt() to it.long(1).toInt() }
            .filter { (season, firstWeek) -> firstWeek < (upcoming[season] ?: Int.MAX_VALUE) }
            .map { it.first }
    }

    /**
     * [season]'s backtest under [profile], by position. It simulates every
     * counted player-week, which is seconds of work on a phone, so call it
     * off the main thread.
     */
    public suspend fun backtest(season: Int, profile: ScoringProfile, draws: Int = BACKTEST_DRAWS): List<PositionAccuracy> {
        val beforeWeek = status().upcoming[season] ?: Int.MAX_VALUE

        data class Row(val playerId: String, val position: String, val week: Int, val component: ProjectionComponent)

        val rows = executor.query(AccuracyQueries.projected(season, beforeWeek)) {
            Row(it.text(0), it.text(1), it.long(2).toInt(), ProjectionComponent(it.text(3), it.double(4), it.double(5), it.textOrNull(6)))
        }
        val projected = rows.groupBy { it.playerId to it.week }.map { (key, weekRows) ->
            ProjectedWeek(key.first, weekRows.first().position, key.second, weekRows.map { it.component })
        }

        data class Fact(val playerId: String, val season: Int, val week: Int, val metricId: String, val value: Double)

        val facts = executor.query(AccuracyQueries.played(season - 1, season)) {
            Fact(it.text(0), it.long(1).toInt(), it.long(2).toInt(), it.text(3), it.double(4))
        }
        // A week counts as played when it has a recorded play.
        val played = facts.groupBy { Triple(it.playerId, it.season, it.week) }
            .filter { (_, weekFacts) -> weekFacts.any { it.metricId == Components.GAMES.id && it.value > 0.0 } }
            .map { (key, weekFacts) ->
                PlayedWeek(key.first, key.second, key.third, weekFacts.associate { Component(it.metricId) to it.value })
            }
        return backtest(season, projected, played, profile, draws)
    }

    public suspend fun summary(season: Int): List<AccuracyRow> = executor.query(
        SqlQuery(
            """
            SELECT position, metric_id, baseline, sample_n, mae, rmse, bias, r2
            FROM accuracy_summary
            WHERE season = ?
            ORDER BY position, metric_id, baseline
            """.trimIndent(),
            listOf(Bind.Integer(season.toLong())),
        ),
    ) {
        AccuracyRow(
            position = it.text(0),
            metricId = it.text(1),
            baseline = it.text(2),
            sampleN = it.long(3).toInt(),
            mae = it.double(4),
            rmse = it.double(5),
            bias = it.double(6),
            r2 = it.doubleOrNull(7),
        )
    }
}
```

- [ ] **Step 5: Run the repository tests to see them pass**

Run: `./gradlew :core:data:test --tests "*AccuracyRepositoryTest*"`
Expected: PASS, 3/3.

The empty-database test works because `ProjectionsRepository.status()` reads `schema_meta` (empty here, so the status is null), and the projection queries find no rows.

- [ ] **Step 6: Write the real-data contract test**

`core/data/src/test/kotlin/dev/gridiron/core/data/AccuracyContractTest.kt`:

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.projections.ACCURACY_POSITIONS
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/** The accuracy page's backtest, end to end, on the database CI builds with the phone's code. */
@EnabledIfEnvironmentVariable(named = "GRIDIRON_STATS_DB", matches = ".+")
class AccuracyContractTest {
    @Test
    fun `a finished season backtests every position with sensible, finite numbers`() = runTest {
        JdbcQueryExecutor(StatsDb.path!!).use { executor ->
            val repo = AccuracyRepository(executor)
            val seasons = repo.seasons()
            // The newest season may be only a week or two old: measure the one before it.
            val season = seasons.dropLast(1).lastOrNull() ?: seasons.last()

            val started = System.nanoTime()
            val results = repo.backtest(season, ScoringPresets.PPR)
            val seconds = (System.nanoTime() - started) / 1e9

            assertEquals(ACCURACY_POSITIONS, results.map { it.position })
            for (r in results) {
                assertTrue(r.playerWeeks >= 100, "${r.position}: only ${r.playerWeeks} player-weeks in $season")
                for (stats in listOf(r.model, r.seasonAverage, r.lastFour)) {
                    // Half-point-per-reception misses land around 4 to 8 points; a scoring bug lands far outside.
                    assertTrue(stats.mae in 2.0..12.0, "${r.position}: MAE ${stats.mae}")
                    assertTrue(stats.bias.isFinite() && stats.r2?.isFinite() != false, "${r.position}: $stats")
                }
                assertTrue(r.calibration in 0.3..1.0, "${r.position}: floor to ceiling held ${r.calibration}")
            }
            // About 2 s on a CI JVM; the budget only catches a runaway.
            assertTrue(seconds < 15.0, "$season's backtest took $seconds s")
        }
    }
}
```

- [ ] **Step 7: Run it on the real database**

Build the database if `etl/build/stats.db` is missing or older than the branch's last forecast change:

```bash
./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025 2026" -Pout=etl/build/stats.db
```

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test --tests "*AccuracyContractTest*"`
Expected: PASS. Without `GRIDIRON_STATS_DB` it is skipped, like `ProjectionsContractTest`.

- [ ] **Step 8: Pass the gate's season to test tasks**

In `build-logic/convention/src/main/kotlin/ProjectExtensions.kt`, replace the whole `configureTests` KDoc and function with:

```kotlin
/**
 * Hands every test task the real ETL-built database named by GRIDIRON_STATS_DB,
 * for tests that run against it (they skip without it). A relative path
 * resolves from the repository root, since tests run with the module as their
 * working directory. The file is a declared input, so rebuilding the database
 * re-runs the tests rather than reporting them up to date. GRIDIRON_ACCURACY_GATE,
 * the season CI's accuracy gate checks, is passed on as an input too, so the
 * gate never comes back from the build cache.
 */
internal fun Project.configureTests() {
    val statsDb = providers.environmentVariable("GRIDIRON_STATS_DB").orNull?.let { rootDir.resolve(it) }
    val accuracyGate = providers.environmentVariable("GRIDIRON_ACCURACY_GATE").orNull
    tasks.withType<Test>().configureEach {
        inputs.property("statsDbPath", statsDb?.path ?: "")
        inputs.property("accuracyGate", accuracyGate ?: "")
        if (statsDb != null) {
            environment("GRIDIRON_STATS_DB", statsDb.path)
            if (statsDb.isFile) inputs.file(statsDb).withPropertyName("statsDb")
        }
        if (accuracyGate != null) environment("GRIDIRON_ACCURACY_GATE", accuracyGate)
        testLogging {
            events("failed", "skipped")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}
```

- [ ] **Step 9: Write the gate**

`core/data/src/test/kotlin/dev/gridiron/core/data/AccuracyGateTest.kt`:

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.projections.ACCURACY_POSITIONS
import dev.gridiron.core.projections.ErrorStats
import dev.gridiron.core.projections.PositionAccuracy
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.util.Locale

/**
 * CI's accuracy gate (spec §4): under PPR, the model's MAE must be below the
 * season-to-date average's at QB, RB, WR and TE. It runs only when
 * GRIDIRON_ACCURACY_GATE names the season, on a GRIDIRON_STATS_DB built with
 * that season and the one before it. The parity job sets both. The table
 * goes to build/reports/accuracy-gate.txt for the job log.
 */
@EnabledIfEnvironmentVariable(named = "GRIDIRON_ACCURACY_GATE", matches = "\\d{4}")
class AccuracyGateTest {
    @Test
    fun `the model beats the season-to-date average at every position`() = runTest {
        val season = System.getenv("GRIDIRON_ACCURACY_GATE").toInt()
        val path = checkNotNull(StatsDb.path) { "GRIDIRON_STATS_DB must name a database built with ${season - 1} and $season" }
        JdbcQueryExecutor(path).use { executor ->
            val results = AccuracyRepository(executor).backtest(season, ScoringPresets.PPR)
            val table = table(season, results)
            File("build/reports").mkdirs()
            File("build/reports/accuracy-gate.txt").writeText(table)

            assertEquals(ACCURACY_POSITIONS, results.map { it.position }, table)
            val losing = results.filter { it.model.mae >= it.seasonAverage.mae }.map { it.position }
            assertTrue(losing.isEmpty(), "the model doesn't beat the season-to-date average at $losing\n$table")
        }
    }

    private fun table(season: Int, results: List<PositionAccuracy>): String = buildString {
        fun cell(stats: ErrorStats) = String.format(Locale.US, "%.2f (%+.2f)", stats.mae, stats.bias)
        appendLine("$season, PPR: MAE (bias) by predictor")
        appendLine(String.format(Locale.US, "%-4s %6s %15s %15s %15s %6s", "pos", "n", "model", "season avg", "last 4", "held"))
        for (r in results) {
            appendLine(
                String.format(
                    Locale.US, "%-4s %6d %15s %15s %15s %5.0f%%",
                    r.position, r.playerWeeks, cell(r.model), cell(r.seasonAverage), cell(r.lastFour), r.calibration * 100,
                ),
            )
        }
    }
}
```

- [ ] **Step 10: Run the gate on a season it must fail, then on the gated season**

Build the gate's database (2024–2025; skip this if `etl/build/accuracy.db` was built from this branch's forecast):

```bash
./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025" -Pout=etl/build/accuracy.db
```

Prove the gate can fail. 2024 has no 2023 history in this database, and the model loses there at WR and TE (see "Measured while planning"):

Run: `GRIDIRON_STATS_DB=etl/build/accuracy.db GRIDIRON_ACCURACY_GATE=2024 ./gradlew :core:data:test --tests "dev.gridiron.core.data.AccuracyGateTest"`
Expected: FAIL with `the model doesn't beat the season-to-date average at [WR, TE]` followed by the table.

Run: `GRIDIRON_STATS_DB=etl/build/accuracy.db GRIDIRON_ACCURACY_GATE=2025 ./gradlew :core:data:test --tests "dev.gridiron.core.data.AccuracyGateTest"`
Expected: PASS. Then run `cat core/data/build/reports/accuracy-gate.txt`. The MAEs should match the planning table to within 0.01: QB 6.42 / 7.14 / 7.07, RB 5.88 / 6.01 / 6.18, WR 5.40 / 5.79 / 5.81, TE 4.87 / 5.19 / 5.42. Record the table in `docs/superpowers/HANDOFF.md`. If any value is off by more than 0.01, stop and find out why before going on.

Run: `./gradlew :core:data:test`
Expected: PASS, with `AccuracyGateTest` skipped because `GRIDIRON_ACCURACY_GATE` isn't set.

- [ ] **Step 11: Add the gate to CI's parity job**

In `.github/workflows/ci.yml`, change the header comment's last line from:

```yaml
# The parity job proves the Kotlin ingest reproduces the Python ETL exactly.
```

to:

```yaml
# The parity job proves the Kotlin ingest reproduces the Python ETL exactly,
# and gates the projection model: it must beat a season-to-date average.
```

At the end of the `parity` job, after the `Compare` step, add:

```yaml
      - name: Accuracy gate
        # Spec §4: on a 2024–2025 build, the model's 2025 PPR error must be
        # below the season-to-date average's at QB, RB, WR and TE.
        env:
          GRIDIRON_STATS_DB: etl/build/accuracy.db
          GRIDIRON_ACCURACY_GATE: "2025"
        run: |
          ./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025" -Pout=etl/build/accuracy.db
          ./gradlew :core:data:test --tests "dev.gridiron.core.data.AccuracyGateTest"
      - name: Accuracy table
        if: always()
        run: cat core/data/build/reports/accuracy-gate.txt || echo "No accuracy table was written."
```

Check the YAML parses: `python -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml'))"`
Expected: no output.

- [ ] **Step 12: Commit**

```bash
git add core/projections/src/main/kotlin/dev/gridiron/core/projections/AccuracyQueries.kt \
        core/data/src/main/kotlin/dev/gridiron/core/data/AccuracyRepository.kt \
        core/data/src/test/kotlin/dev/gridiron/core/data/AccuracyRepositoryTest.kt \
        core/data/src/test/kotlin/dev/gridiron/core/data/AccuracyContractTest.kt \
        core/data/src/test/kotlin/dev/gridiron/core/data/AccuracyGateTest.kt \
        build-logic/convention/src/main/kotlin/ProjectExtensions.kt \
        .github/workflows/ci.yml
git commit -m "data: accuracy backtest from the stored projections, and CI's accuracy gate"
```

---

### Task 3: The accuracy page

**Files:**
- Create: `feature/projections/src/main/kotlin/dev/gridiron/feature/projections/AccuracyViewModel.kt`
- Create: `feature/projections/src/main/kotlin/dev/gridiron/feature/projections/AccuracyText.kt`
- Replace: `feature/projections/src/main/kotlin/dev/gridiron/feature/projections/AccuracyRoute.kt`
- Replace: `feature/projections/src/main/kotlin/dev/gridiron/feature/projections/AccuracyScreen.kt`
- Replace: `core/data/src/main/kotlin/dev/gridiron/core/data/AccuracyRepository.kt` (drop `AccuracyRow` and `summary`)
- Modify: `app/src/main/kotlin/dev/gridiron/app/GridironNavHost.kt` (the `AccuracyKey` entry's call only)
- Test: `feature/projections/src/test/kotlin/dev/gridiron/feature/projections/AccuracyViewModelTest.kt`
- Test: `feature/projections/src/test/kotlin/dev/gridiron/feature/projections/AccuracyScreenTest.kt`

**Interfaces:**
- Consumes: Task 2's `AccuracyRepository.status()`, `seasons()` and `backtest(season, profile)`; Task 1's `PositionAccuracy`, `ErrorStats` and `ACCURACY_MIN_POINTS`; `ScoringRepository.active: Flow<ScoringProfile>`.
- Produces:
  - `sealed interface AccuracyState`, with `Loading`, `Unavailable(message: String)` and `Loaded(season: Int, seasons: List<Int>, profile: String, positions: List<PositionAccuracy>)`.
  - `class AccuracyViewModel(repository: AccuracyRepository, compute: CoroutineDispatcher = Dispatchers.Default)`, with `state: StateFlow<AccuracyState>`, `load(season: Int, profile: ScoringProfile)` and `companion fun factory(repository)`.
  - `@Composable AccuracyRoute(season: Int, repository: AccuracyRepository, scoring: ScoringRepository, onBack: () -> Unit)`.
  - `@Composable AccuracyScreen(state: AccuracyState, onSeason: (Int) -> Unit, onBack: () -> Unit)`. Its title carries test tag `accuracyTitle`.
  - Internal formatting: `fixed(value: Double, places: Int)`, `signed(value: Double)`, `r2Text(r2: Double?)`, `percent(share: Double)`.

- [ ] **Step 1: Write the failing ViewModel and formatting tests**

`feature/projections/src/test/kotlin/dev/gridiron/feature/projections/AccuracyViewModelTest.kt`:

```kotlin
package dev.gridiron.feature.projections

import dev.gridiron.core.data.AccuracyRepository
import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.ResultRow
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.statquery.SqlQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

private class FakeRow(private val columns: List<Any?>) : ResultRow {
    override fun isNull(index: Int): Boolean = columns[index] == null
    override fun text(index: Int): String = columns[index] as String
    override fun long(index: Int): Long = (columns[index] as Number).toLong()
    override fun double(index: Int): Double = (columns[index] as Number).toDouble()
}

/** Answers the backtest's queries by what they read. */
private class AccuracyExecutor(
    private val meta: List<Pair<String, String>>,
    private val firstWeeks: List<List<Any?>> = emptyList(),
    private val projected: List<List<Any?>> = emptyList(),
    private val facts: List<List<Any?>> = emptyList(),
) : QueryExecutor {
    override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> {
        val rows = when {
            "schema_meta" in query.sql -> meta.map { listOf(it.first, it.second) }
            "MIN(week)" in query.sql -> firstWeeks
            "FROM player_week_projection" in query.sql -> projected
            "FROM player_week_stat" in query.sql -> facts
            else -> emptyList()
        }
        return rows.map { map(FakeRow(it)) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccuracyViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private val ok = listOf("forecast_status" to "ok")

    // A WR projected for 6 catches in week 2, who caught 5 in week 1 and 8 in week 2.
    private val projected = listOf(
        listOf("w", "WR", 2, "receptions", 6.0, 0.0, null),
        listOf("w", "WR", 2, "receiving_yards", 60.0, 0.0, null),
    )
    private val facts = listOf(
        listOf("w", 2025, 1, "g", 1.0),
        listOf("w", 2025, 1, "receptions", 5.0),
        listOf("w", 2025, 2, "g", 1.0),
        listOf("w", 2025, 2, "receptions", 8.0),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `measures the season under the active profile`() = runTest(dispatcher) {
        val executor = AccuracyExecutor(ok, firstWeeks = listOf(listOf(2025, 1)), projected = projected, facts = facts)
        val vm = AccuracyViewModel(AccuracyRepository(executor), dispatcher)

        vm.load(2025, ScoringPresets.PPR)
        advanceUntilIdle()

        val loaded = vm.state.value as AccuracyState.Loaded
        assertEquals(2025, loaded.season)
        assertEquals(listOf(2025), loaded.seasons)
        assertEquals("PPR", loaded.profile)
        val wr = loaded.positions.single()
        assertEquals(1, wr.playerWeeks)
        assertEquals(4.0, wr.model.mae, 1e-9) // 12 projected, 8 scored
    }

    @Test
    fun `a season with no finished week shows the latest one that has some`() = runTest(dispatcher) {
        val executor = AccuracyExecutor(ok, firstWeeks = listOf(listOf(2024, 2), listOf(2025, 1)), projected = projected, facts = facts)
        val vm = AccuracyViewModel(AccuracyRepository(executor), dispatcher)

        vm.load(2026, ScoringPresets.PPR)
        advanceUntilIdle()

        val loaded = vm.state.value as AccuracyState.Loaded
        assertEquals(2025, loaded.season)
        assertEquals(listOf(2024, 2025), loaded.seasons)
    }

    @Test
    fun `says why there's nothing to measure`() = runTest(dispatcher) {
        suspend fun message(meta: List<Pair<String, String>>): String {
            val vm = AccuracyViewModel(AccuracyRepository(AccuracyExecutor(meta)), dispatcher)
            vm.load(2026, ScoringPresets.PPR)
            advanceUntilIdle()
            return (vm.state.value as AccuracyState.Unavailable).message
        }

        assertEquals("No projections yet. Refresh stats to build them.", message(emptyList()))
        assertEquals("Projections unavailable: no schedule.", message(listOf("forecast_status" to "no schedule")))
        assertEquals("No finished weeks have been projected yet.", message(ok))
    }

    @Test
    fun `numbers read cleanly`() {
        assertEquals("5.4", fixed(5.4049, 1))
        assertEquals("0.0", fixed(-0.04, 1))
        assertEquals("+0.4", signed(0.44))
        assertEquals("-1.2", signed(-1.24))
        assertEquals("0.0", signed(-0.01))
        assertEquals("0.31", r2Text(0.314))
        assertEquals("—", r2Text(null))
        assertEquals("79%", percent(0.794))
    }
}
```

- [ ] **Step 2: Write the failing screen tests**

`feature/projections/src/test/kotlin/dev/gridiron/feature/projections/AccuracyScreenTest.kt`:

```kotlin
package dev.gridiron.feature.projections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.gridiron.core.designsystem.GridironTheme
import dev.gridiron.core.projections.ErrorStats
import dev.gridiron.core.projections.PositionAccuracy
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
class AccuracyScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val loaded = AccuracyState.Loaded(
        season = 2025,
        seasons = listOf(2024, 2025),
        profile = "PPR",
        positions = listOf(
            PositionAccuracy(
                "WR", 1203,
                model = ErrorStats(5.40, -1.03, 0.31),
                seasonAverage = ErrorStats(5.79, 0.28, 0.22),
                lastFour = ErrorStats(6.02, 0.43, 0.18),
                calibration = 0.79,
            ),
        ),
    )

    @Test
    fun `shows each predictor's error and how often floor to ceiling held`() {
        compose.setContent { GridironTheme { AccuracyScreen(loaded, onSeason = {}, onBack = {}) } }

        compose.onNodeWithText("Scored with PPR").assertIsDisplayed()
        compose.onNodeWithText("WR · 1203 player-weeks").assertIsDisplayed()
        compose.onNodeWithText("Floor to ceiling held 79% of scores (target about 80%)").assertIsDisplayed()
        compose.onNodeWithText("5.4").assertIsDisplayed()
        compose.onNodeWithText("-1.0").assertIsDisplayed()
        compose.onNodeWithText("0.31").assertIsDisplayed()
        compose.onNodeWithText("5.8").assertIsDisplayed()
        compose.onNodeWithText("+0.3").assertIsDisplayed()
        compose.onNodeWithText("6.0").assertIsDisplayed()
    }

    @Test
    fun `a season chip asks for that season`() {
        var asked: Int? = null
        compose.setContent { GridironTheme { AccuracyScreen(loaded, onSeason = { asked = it }, onBack = {}) } }

        compose.onNodeWithText("2024").performClick()

        assertEquals(2024, asked)
    }

    @Test
    fun `a season with nothing to measure says so and still offers the others`() {
        compose.setContent { GridironTheme { AccuracyScreen(loaded.copy(positions = emptyList()), onSeason = {}, onBack = {}) } }

        compose.onNodeWithText("No player-weeks to measure in 2025 yet.").assertIsDisplayed()
        compose.onNodeWithText("2024").assertIsDisplayed()
    }

    @Test
    fun `an unavailable forecast says why`() {
        compose.setContent {
            GridironTheme { AccuracyScreen(AccuracyState.Unavailable("No finished weeks have been projected yet."), onSeason = {}, onBack = {}) }
        }

        compose.onNodeWithText("No finished weeks have been projected yet.").assertIsDisplayed()
    }
}
```

- [ ] **Step 3: Run them to see them fail**

Run: `./gradlew :feature:projections:testDebugUnitTest --tests "*Accuracy*"`
Expected: FAIL to compile with `Unresolved reference 'AccuracyViewModel'` (and `AccuracyState`, `fixed`, `signed`, `r2Text`, `percent`).

- [ ] **Step 4: Write the formatting**

`feature/projections/src/main/kotlin/dev/gridiron/feature/projections/AccuracyText.kt`:

```kotlin
package dev.gridiron.feature.projections

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** [value] to [places] decimals. A value that rounds to zero never shows as "-0.0". */
internal fun fixed(value: Double, places: Int): String {
    val clean = if (abs(value) < 0.5 / 10.0.pow(places)) 0.0 else value
    return String.format(Locale.US, "%.${places}f", clean)
}

/** A mean error with its sign: "+0.4", "-1.2", or "0.0". */
internal fun signed(value: Double): String = fixed(value, 1).let { if (it.startsWith("-") || it == "0.0") it else "+$it" }

/** R² to two decimals, or a dash when the actual scores had no spread. */
internal fun r2Text(r2: Double?): String = r2?.let { fixed(it, 2) } ?: "—"

internal fun percent(share: Double): String = "${(share * 100).roundToInt()}%"
```

- [ ] **Step 5: Write the ViewModel**

`feature/projections/src/main/kotlin/dev/gridiron/feature/projections/AccuracyViewModel.kt`:

```kotlin
package dev.gridiron.feature.projections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.gridiron.core.data.AccuracyRepository
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.projections.PositionAccuracy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

public sealed interface AccuracyState {
    public data object Loading : AccuracyState

    public data class Unavailable(val message: String) : AccuracyState

    public data class Loaded(
        val season: Int,
        /** Seasons with a finished week projected, oldest first. */
        val seasons: List<Int>,
        /** The scoring profile's name: every number is in its points. */
        val profile: String,
        /** By position. Empty until some player has two games in [season]. */
        val positions: List<PositionAccuracy>,
    ) : AccuracyState
}

/** Runs a season's backtest under the active profile, off the main thread. */
public class AccuracyViewModel(
    private val repository: AccuracyRepository,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow<AccuracyState>(AccuracyState.Loading)
    public val state: StateFlow<AccuracyState> = _state.asStateFlow()

    // A load superseded by a newer one (another season, a profile switch) must not overwrite it.
    private var latest = 0

    public fun load(season: Int, profile: ScoringProfile) {
        val request = ++latest
        _state.value = AccuracyState.Loading
        viewModelScope.launch {
            val next = try {
                build(season, profile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AccuracyState.Unavailable("Couldn't measure accuracy: ${e.message}.")
            }
            if (request == latest) _state.value = next
        }
    }

    private suspend fun build(season: Int, profile: ScoringProfile): AccuracyState {
        val status = repository.status().status
            ?: return AccuracyState.Unavailable("No projections yet. Refresh stats to build them.")
        if (status != "ok") return AccuracyState.Unavailable("Projections unavailable: $status.")
        val seasons = repository.seasons()
        if (seasons.isEmpty()) return AccuracyState.Unavailable("No finished weeks have been projected yet.")
        val shown = if (season in seasons) season else seasons.last()
        val positions = withContext(compute) { repository.backtest(shown, profile) }
        return AccuracyState.Loaded(shown, seasons, profile.name, positions)
    }

    public companion object {
        public fun factory(repository: AccuracyRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { AccuracyViewModel(repository) } }
    }
}
```

- [ ] **Step 6: Write the screen and route**

Replace `feature/projections/src/main/kotlin/dev/gridiron/feature/projections/AccuracyScreen.kt` with:

```kotlin
package dev.gridiron.feature.projections

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.gridiron.core.projections.ACCURACY_MIN_POINTS
import dev.gridiron.core.projections.ErrorStats
import dev.gridiron.core.projections.PositionAccuracy

/**
 * ☰ → Projection accuracy: how past weeks' projections did against what
 * players scored, next to two simple baselines (spec §4).
 */
@Composable
public fun AccuracyScreen(state: AccuracyState, onSeason: (Int) -> Unit, onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text(
                    "Projection accuracy",
                    Modifier.testTag("accuracyTitle"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            when (state) {
                AccuracyState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text("Scoring every projected week…", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                is AccuracyState.Unavailable -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                }
                is AccuracyState.Loaded -> Measured(state, onSeason)
            }
        }
    }
}

@Composable
private fun Measured(state: AccuracyState.Loaded, onSeason: (Int) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "Scored with ${state.profile}",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (s in state.seasons) FilterChip(selected = s == state.season, onClick = { onSeason(s) }, label = { Text("$s") })
            }
        }
        if (state.positions.isEmpty()) {
            item {
                Text("No player-weeks to measure in ${state.season} yet.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(state.positions, key = { it.position }) { PositionTable(it) }
        item {
            Text(
                "Counts weeks where the model projected at least ${ACCURACY_MIN_POINTS.toInt()} points and the player played, " +
                    "from the player's second game of the season. Bias is projected minus actual. " +
                    "Past weeks are projected without betting props.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PositionTable(p: PositionAccuracy) {
    val best = minOf(p.model.mae, p.seasonAverage.mae, p.lastFour.mae)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("${p.position} · ${p.playerWeeks} player-weeks", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            "Floor to ceiling held ${percent(p.calibration)} of scores (target about 80%)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Cells("", "MAE", "Bias", "R²", header = true)
        Predictor("Model", p.model, best)
        Predictor("Season avg", p.seasonAverage, best)
        Predictor("Last 4", p.lastFour, best)
    }
}

/** One predictor's row; the lowest MAE of the three is bold. */
@Composable
private fun Predictor(label: String, stats: ErrorStats, best: Double) {
    Cells(label, fixed(stats.mae, 1), signed(stats.bias), r2Text(stats.r2), header = false, boldMae = stats.mae == best)
}

@Composable
private fun Cells(label: String, mae: String, bias: String, r2: String, header: Boolean, boldMae: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall
    val color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f), style = style, color = color)
        Text(
            mae,
            Modifier.width(56.dp),
            style = style,
            color = color,
            textAlign = TextAlign.End,
            fontWeight = if (boldMae) FontWeight.Bold else FontWeight.Normal,
        )
        Text(bias, Modifier.width(56.dp), style = style, color = color, textAlign = TextAlign.End)
        Text(r2, Modifier.width(56.dp), style = style, color = color, textAlign = TextAlign.End)
    }
}
```

Replace `feature/projections/src/main/kotlin/dev/gridiron/feature/projections/AccuracyRoute.kt` with:

```kotlin
package dev.gridiron.feature.projections

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.gridiron.core.data.AccuracyRepository
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.model.ScoringProfile

/** ☰ → Projection accuracy, starting at [season] and scored with the active profile. */
@Composable
public fun AccuracyRoute(season: Int, repository: AccuracyRepository, scoring: ScoringRepository, onBack: () -> Unit) {
    val vm: AccuracyViewModel = viewModel(factory = AccuracyViewModel.factory(repository))
    val state by vm.state.collectAsStateWithLifecycle()
    val profile by scoring.active.collectAsStateWithLifecycle<ScoringProfile?>(initialValue = null)
    var shown by rememberSaveable { mutableIntStateOf(season) }
    LaunchedEffect(shown, profile) { profile?.let { vm.load(shown, it) } }
    AccuracyScreen(state, onSeason = { shown = it }, onBack = onBack)
}
```

- [ ] **Step 7: Drop the old summary read and update the one caller**

Replace `core/data/src/main/kotlin/dev/gridiron/core/data/AccuracyRepository.kt` with the Task 2 version minus `AccuracyRow`, `summary` and the imports only they used (`doubleOrNull`, `Bind`, `SqlQuery`):

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.textOrNull
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.projections.AccuracyQueries
import dev.gridiron.core.projections.BACKTEST_DRAWS
import dev.gridiron.core.projections.ForecastStatus
import dev.gridiron.core.projections.PlayedWeek
import dev.gridiron.core.projections.PositionAccuracy
import dev.gridiron.core.projections.ProjectedWeek
import dev.gridiron.core.projections.ProjectionComponent
import dev.gridiron.core.projections.backtest
import dev.gridiron.core.statquery.Component
import dev.gridiron.core.statquery.Components

/** The accuracy page's numbers: the stored past-week projections against the games actually played. */
public class AccuracyRepository(private val executor: QueryExecutor) {
    private val projections = ProjectionsRepository(executor)

    /** How the last refresh's forecast went. */
    public suspend fun status(): ForecastStatus = projections.status()

    /**
     * Seasons with at least one finished week projected, oldest first. The
     * upcoming week isn't finished, even after one of its games is played.
     */
    public suspend fun seasons(): List<Int> {
        val upcoming = status().upcoming
        return executor.query(AccuracyQueries.firstProjectedWeeks()) { it.long(0).toInt() to it.long(1).toInt() }
            .filter { (season, firstWeek) -> firstWeek < (upcoming[season] ?: Int.MAX_VALUE) }
            .map { it.first }
    }

    /**
     * [season]'s backtest under [profile], by position. It simulates every
     * counted player-week, which is seconds of work on a phone, so call it
     * off the main thread.
     */
    public suspend fun backtest(season: Int, profile: ScoringProfile, draws: Int = BACKTEST_DRAWS): List<PositionAccuracy> {
        val beforeWeek = status().upcoming[season] ?: Int.MAX_VALUE

        data class Row(val playerId: String, val position: String, val week: Int, val component: ProjectionComponent)

        val rows = executor.query(AccuracyQueries.projected(season, beforeWeek)) {
            Row(it.text(0), it.text(1), it.long(2).toInt(), ProjectionComponent(it.text(3), it.double(4), it.double(5), it.textOrNull(6)))
        }
        val projected = rows.groupBy { it.playerId to it.week }.map { (key, weekRows) ->
            ProjectedWeek(key.first, weekRows.first().position, key.second, weekRows.map { it.component })
        }

        data class Fact(val playerId: String, val season: Int, val week: Int, val metricId: String, val value: Double)

        val facts = executor.query(AccuracyQueries.played(season - 1, season)) {
            Fact(it.text(0), it.long(1).toInt(), it.long(2).toInt(), it.text(3), it.double(4))
        }
        // A week counts as played when it has a recorded play.
        val played = facts.groupBy { Triple(it.playerId, it.season, it.week) }
            .filter { (_, weekFacts) -> weekFacts.any { it.metricId == Components.GAMES.id && it.value > 0.0 } }
            .map { (key, weekFacts) ->
                PlayedWeek(key.first, key.second, key.third, weekFacts.associate { Component(it.metricId) to it.value })
            }
        return backtest(season, projected, played, profile, draws)
    }
}
```

In `app/src/main/kotlin/dev/gridiron/app/GridironNavHost.kt`, change:

```kotlin
                    entry<AccuracyKey> { key -> AccuracyRoute(key.season, deps.accuracy, onBack = back) }
```

to:

```kotlin
                    entry<AccuracyKey> { key -> AccuracyRoute(key.season, deps.accuracy, deps.scoring, onBack = back) }
```

Confirm nothing else read the old API: `grep -rn "AccuracyRow\|\.summary(\|accuracy_summary" --include=*.kt app core feature`
Expected: exactly two lines, both recording that schema v7 dropped the table, and both stay: `core/ingest/.../db/Schema.kt:17` (KDoc) and `core/ingest/src/test/.../db/StatsDbWriterTest.kt:139` (`assertFalse("accuracy_summary" in tables)`).

- [ ] **Step 8: Run the tests to see them pass**

Run: `./gradlew :feature:projections:testDebugUnitTest :core:data:test :app:compileDebugKotlin`
Expected: PASS: 4 `AccuracyViewModelTest` and 4 `AccuracyScreenTest` tests plus every existing test in both modules, and `:app` compiles.

- [ ] **Step 9: Commit**

```bash
git add feature/projections/src/main/kotlin/dev/gridiron/feature/projections/AccuracyViewModel.kt \
        feature/projections/src/main/kotlin/dev/gridiron/feature/projections/AccuracyText.kt \
        feature/projections/src/main/kotlin/dev/gridiron/feature/projections/AccuracyRoute.kt \
        feature/projections/src/main/kotlin/dev/gridiron/feature/projections/AccuracyScreen.kt \
        feature/projections/src/test/kotlin/dev/gridiron/feature/projections/AccuracyViewModelTest.kt \
        feature/projections/src/test/kotlin/dev/gridiron/feature/projections/AccuracyScreenTest.kt \
        core/data/src/main/kotlin/dev/gridiron/core/data/AccuracyRepository.kt \
        app/src/main/kotlin/dev/gridiron/app/GridironNavHost.kt
git commit -m "projections: the accuracy page, scored with the active profile"
```

---

### Task 4: ☰ → Projection accuracy, and docs

**Files:**
- Modify: `app/src/main/kotlin/dev/gridiron/app/GridironNavHost.kt` (the ☰ menu list)
- Modify: `app/src/test/kotlin/dev/gridiron/app/NavigationTest.kt` (replace `theMenuNoLongerOffersProjections`)
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: `AccuracyKey(season: Int)` (`NavKeys.kt`, unchanged); Task 3's `AccuracyRoute` and its `accuracyTitle` test tag.
- Produces: the ☰ entry "Projection accuracy", right after "Projections".

- [ ] **Step 1: Write the failing navigation test**

In `app/src/test/kotlin/dev/gridiron/app/NavigationTest.kt`, replace the whole `theMenuNoLongerOffersProjections` test with:

```kotlin
    @Test
    fun theMenuOpensProjectionAccuracy() {
        compose.setContent { GridironTheme { GridironNavHost(deps) } }
        settle()

        compose.onNodeWithTag("menu").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Time a stats build").assertDoesNotExist()
        compose.onNodeWithText("Projection accuracy").performClick()
        settle()
        compose.onNodeWithTag("accuracyTitle").assertExists()

        // The backtest runs on the real database off the main thread: let it finish, so tearDown
        // doesn't close the database under it, and so the page is known to load, not just open.
        compose.waitUntil(timeoutMillis = 60_000) {
            settle()
            compose.onAllNodesWithText("Scoring every projected week…").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Scored with", substring = true).assertExists()
    }
```

Add `import androidx.compose.ui.test.onAllNodesWithText` to the file's imports, in alphabetical order after `longClick`.

- [ ] **Step 2: Run it to see it fail**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :app:testDebugUnitTest --tests "*NavigationTest.theMenuOpensProjectionAccuracy"`
Expected: FAIL with `AssertionError: Failed to perform a gesture... could not find any node that satisfies: (Text + InputText + EditableText contains 'Projection accuracy'`.

- [ ] **Step 3: Add the menu entry**

In `app/src/main/kotlin/dev/gridiron/app/GridironNavHost.kt`, below:

```kotlin
                                add("Projections" to { s: Int -> backStack.push(ProjectionListKey(s)) })
```

add:

```kotlin
                                add("Projection accuracy" to { s: Int -> backStack.push(AccuracyKey(s)) })
```

- [ ] **Step 4: Run the app tests to see them pass**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :app:testDebugUnitTest`
Expected: PASS, every test including `theMenuOpensProjectionAccuracy`.

- [ ] **Step 5: Update CLAUDE.md**

Make these five edits.

1. In **JVM Modules**, the `:core:projections` line, replace:

```markdown
- `:core:projections` — Pure `score()` (the in-memory twin of `StatQueryBuilder`'s SQL scoring), factor attribution, and single-player Monte Carlo (floor/ceiling) for the Projections feature
```

with:

```markdown
- `:core:projections` — Pure `score()` (the in-memory twin of `StatQueryBuilder`'s SQL scoring), factor attribution, and single-player Monte Carlo (floor/ceiling) for the Projections feature; `backtest()`, which scores the stored past-week projections against real games beside the season-to-date and last-4 averages (MAE, bias, R², floor-to-ceiling calibration)
```

2. In **Android Modules**, replace the `:feature:projections` line:

```markdown
- `:feature:projections` — The Projections list (☰ → Projections), the Player page's "This week" card, the waterfall screen (`ProjectionsKey`), and the accuracy ("trust page") screen (`AccuracyKey`, not yet reachable; see Known Gaps)
```

with:

```markdown
- `:feature:projections` — The Projections list (☰ → Projections), the Player page's "This week" card, the waterfall screen (`ProjectionsKey`), and the accuracy page (☰ → Projection accuracy, `AccuracyKey`): each position's backtest for a season under the active profile, computed when the page opens
```

3. In **Android Tests & Builds**, below the `--tests "StatQueryBuilderTest"` line, add:

```bash

# CI's accuracy gate: the model must beat the season-to-date average in 2025 under PPR
./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025" -Pout=etl/build/accuracy.db
GRIDIRON_STATS_DB=etl/build/accuracy.db GRIDIRON_ACCURACY_GATE=2025 ./gradlew :core:data:test --tests "dev.gridiron.core.data.AccuracyGateTest"
```

4. In **Testing Strategy**, after the line `To run contract tests locally, set ...`, add:

```markdown

**Accuracy gate** — CI's parity job builds 2024–2025 and runs `AccuracyGateTest`. The gate fails if the model's 2025 MAE under PPR isn't below the season-to-date average's at QB, RB, WR and TE, and the job prints the table. It's never skipped: if it fails, tune the forecast's constants.
```

5. In **Known Gaps & Next Steps**, in the **Grid entry points** item, replace:

```markdown
the ☰ menu opens Projections (the upcoming week or rest of season by position, scored with the active profile), News, Injury report (ESPN's live list with nflverse practice for the current season; the official list for past seasons), Team defense, Settings and Refresh stats. `AccuracyKey` stays unreachable until the accuracy sub-project.
```

with:

```markdown
the ☰ menu opens Projections (the upcoming week or rest of season by position, scored with the active profile), Projection accuracy, News, Injury report (ESPN's live list with nflverse practice for the current season; the official list for past seasons), Team defense, Settings and Refresh stats.
```

Then replace:

```markdown
- **Projection model sub-projects 2–4 are not built yet**: the accuracy page (backtest), Odds API props and K/DST. See `docs/superpowers/specs/2026-09-26-projection-model-design.md`.
```

with:

```markdown
- **Projection model sub-projects 3–4 are not built yet**: Odds API props and K/DST. See `docs/superpowers/specs/2026-09-26-projection-model-design.md`.
- **The accuracy page recomputes on every open** (a few seconds on a phone, off the main thread); nothing is cached between visits.
```

- [ ] **Step 6: Full check**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew test`
Expected: BUILD SUCCESSFUL. If the only failure is `:core:statquery`'s "scoring a full season … is fast" timing test, that's the known CPU-contention flake: run `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:statquery:test` alone and expect it to pass.

Run: `./gradlew :app:assembleRelease`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/gridiron/app/GridironNavHost.kt \
        app/src/test/kotlin/dev/gridiron/app/NavigationTest.kt \
        CLAUDE.md
git commit -m "app: ☰ → Projection accuracy; docs"
```

- [ ] **Step 8: Checkpoint for the user (non-blocking)**

Once CI publishes the APK from this branch, open ☰ → Projection accuracy on the phone and report two things: how long "Scoring every projected week…" shows for 2025, and whether the numbers match the CI gate's table under PPR. If it takes more than about 10 seconds, record it in `docs/superpowers/HANDOFF.md`. The fix would be to cache results per season and profile, which is out of this plan's scope.

---

## Plan self-review (done while writing)

- **Spec coverage (§4 and the layer-2 amendment's acceptance note):**

  | Spec requirement | Task |
  |---|---|
  | Past-week `final` rows joined to `player_week_stat` on the phone | 2 |
  | Scored under the active profile | 1 (`score`, `projectPoints`), 3 (route reads `ScoringRepository.active`) |
  | MAE, bias, R² per position and season | 1, 3 |
  | Calibration: share of actuals between floor and ceiling, target about 80% | 1, 3 |
  | Baselines: season-to-date and last-4 averages, on the fly | 1 |
  | Only ≥5 projected points and the player appeared | 1, 2 |
  | The page notes past weeks are projected without props | 3 |
  | `AccuracyRepository` switches from `accuracy_summary` | 2, 3 |
  | The page goes into ☰ | 4 |
  | CI gate: 2024–2025 build, 2025 PPR, model MAE below season-to-date at each of QB, RB, WR, TE | 2 |
  | The re-measure the handoff asked for (the model against the season-to-date average after the layer-2 fix) | "Measured while planning"; Task 2 Step 10 re-checks it in Kotlin |

- **Rulings where the plan departs from, or adds to, the spec's wording:**
  - **The player's first game of the season doesn't count.** The spec's sample rule is at least 5 projected points and the player appeared. The season-to-date baseline doesn't exist before a player's first game, so without this rule the baselines would be measured on fewer player-weeks than the model. It removes about 8% of player-weeks. Cost if wrong: week-1 and new-player errors go unmeasured.
  - **"Appeared" means a `g` row** (a recorded play). On 2025, no projected player-week had offense snaps without a `g` row, so snap counts would add nothing.
  - **Last 4 reaches into the previous season**, which the backtest loads for that reason. Season to date doesn't.
  - **The upcoming week never counts**, even after some of its games have been played. The spec says "past weeks", and a half-played week would mix in a projection the page can't mark as finished.
  - **Bias is projected minus actual.** The spec says "mean error" without a direction. The page says which way it runs.
  - **Floor and ceiling use 250 draws** (the list uses 2,000, a Player card 10,000). A season is about 3,000 counted player-weeks. 500 draws took 2.1 s on the JVM, and calibration is a share across all of them, so per-player precision matters little.
  - **Computed on open, not at refresh.** The spec has the phone join and score, and scoring depends on the profile, which can change without a refresh. The cost is a few seconds with a spinner. Caching is left out (see Task 4 Step 8).
  - **The gate is a JUnit test switched on by `GRIDIRON_ACCURACY_GATE`**, not a CLI. It reuses the repository and the JDBC fixture, and the variable is a declared test input, so the build cache can't replay an old result. The table goes to a file that CI prints.
  - **The gate needs no tuning now.** The planning measurement shows the model ahead at all four positions in 2025. RB's margin is thin (0.13 MAE). If a future upstream data change turns the gate red, tuning `ForecastConstants` (and bumping `FORECAST_VERSION`) is the fix, per the spec. It's never skipped.
- **Type consistency:** `ProjectedWeek`, `PlayedWeek`, `ErrorStats`, `PositionAccuracy`, `backtest`, `BACKTEST_DRAWS`, `ACCURACY_POSITIONS`, `ACCURACY_MIN_POINTS` and `ACTUAL_SCORING_COMPONENTS` are defined in Task 1 and used with the same names and shapes in Tasks 2–3. `AccuracyRepository.status/seasons/backtest` are defined in Task 2 and used in Task 3. `AccuracyState` and `AccuracyRoute(season, repository, scoring, onBack)` are defined in Task 3 and wired in Task 3 Step 7 and Task 4. The `accuracyTitle` tag is set in Task 3 and read in Task 4.
- **Placeholders:** none. Every code step carries its full code.
- **Review Focus coverage:** each of the five lines has a test in the task that owns it. Partly-played upcoming week: Task 2's repository test. Didn't play: Tasks 1 and 2. First game: Task 1. Nothing to measure: Task 2's empty database and Task 3's ViewModel messages and screen. TE premium: Task 1.
