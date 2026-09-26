# Projection Engine Implementation Plan (sub-project 1 of 4)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refresh computes weekly and rest-of-season projections for QB, RB, WR and TE on the phone, and the app shows them on the Player page and a new Projections list, scored under the user's own league.

**Architecture:** A new pure-JVM module, `:core:forecast`, reads the freshly built `stats.db.new` (facts, players, a new `game` table from nflverse's `games.csv`) and writes the existing v6 projection tables walk-forward: each week is projected only from games before it. `:core:ingest`'s `IngestPipeline` downloads the schedule and runs the forecast after validation. A forecast failure leaves the tables empty and records the reason; the stats still swap in. The phone scores the stored stat components with the active scoring profile, as the Grid does.

**Tech Stack:** Kotlin 2 (explicit API, warnings as errors), androidx.sqlite bundled driver, JUnit Jupiter (JVM modules) and JUnit 4 + Robolectric (Android modules), Jetpack Compose, Navigation 3.

**Spec:** `docs/superpowers/specs/2026-09-26-projection-model-design.md` (sections 1–3; sections 4–6 are later sub-projects).

## Global Constraints

- `:core:forecast` is pure JVM: `alias(libs.plugins.gridiron.jvm.library)`, no Android dependency, explicit API mode. Every declaration is `internal` unless another module calls it.
- Warnings are errors: no unused parameters, variables or imports.
- Never `java.net.URL(String)`.
- Walk-forward: projecting week *w* of season *s* reads only facts with `(season, week)` before `(s, w)`, plus game-table rows (schedule, lines, QBs and coaches are pre-game information).
- Stored values are stat components (the scoring inputs), never fantasy points.
- `SCHEMA_VERSION` 6 → 7, `INGEST_VERSION` 1 → 2, `FORECAST_VERSION` = 1. Bump `FORECAST_VERSION` whenever a constant in `ForecastConstants.kt` changes.
- Schema v7: add the `game` table; drop `projection_snapshot` and `accuracy_summary`. `player_week_projection`, `player_week_projection_factor` and `player_ros_projection` keep their v6 shape.
- nflverse's `spread_line` is positive when the home team is favored. Implied points: home `total/2 + spread/2`, away `total/2 − spread/2`.
- Constants, verbatim from the spec: team-volume half-life 4 games; share half-lives 4.5 (target, carry, QB pass share); share k = 5 games; carryover 0.55 at week 1, linear to 0 by week 6; efficiency half-life 10 and k = 15 games; interception k = 150 attempts; TD k = 200 opportunities; matchup caps efficiency ±15%, volume ±5%, TD ±20%; pass-rate shift 0.6 percentage points per point of spread; variance `σ = a·μ^0.75` with CV at μ=10 of QB 0.40, RB 0.57, WR 0.70, TE 0.77.
- A forecast failure never fails the refresh.
- The phone-built database is the only data the app reads; the Python ETL stays only as the parity reference.
- Copy rules (from existing UI): Player page status lines and toasts are one sentence each, no exclamation marks.

## Review Focus

- **ffopportunity lagging the newest week** (its expected TDs are stored sparse, so a missing week reads as zero): the TD rate must ignore weeks after `expected_through_week:<season>`, never count them as zero-TD games. Test in Task 5.
- **A team on bye, or a player whose team has no game that week**: no projection row, and rest-of-season skips the bye instead of adding a zero week. Test in Task 7.
- **No line posted yet** (nflverse's spread and total are empty two or more weeks out): game script is a no-op with no factor row, and nothing becomes NaN or infinite. Tests in Tasks 6 and 7.
- **Too little history** (a single season built, before its week 1 is played): the forecast reports "no games to project from yet", writes nothing, and the stats still swap in. Tests in Tasks 7 and 8.
- **A player on a new team, or a team with a new head coach or starting QB**: last season's share must not carry over, and the upcoming week uses the player's current team (`player.team`), not last season's. Tests in Tasks 5 and 7.

## File Structure

**New module `core/forecast/`** (package `dev.gridiron.core.forecast`)

| File | Responsibility |
|---|---|
| `build.gradle.kts` | JVM library; `api(libs.androidx.sqlite)` because `Forecast.run` takes a `SQLiteConnection` |
| `ForecastConstants.kt` | `FORECAST_VERSION`, every tuning constant with provenance |
| `ForecastMath.kt` | `ewma`, `ewmaRatio`, `shrink`, `carryoverWeight`, `varianceFor`, `capAround` |
| `Ridge.kt` | `fitRidge` (offense + defense + home, L2) and `choleskySolve` |
| `Inputs.kt` | `PlayerGame`, `TeamGame`, `Game`, `PlayerInfo`, `ForecastInputs`, `loadInputs(conn)` |
| `League.kt` | `LeagueTotals` (running sums by position) and `Rates` (positional baselines, rare-event rates) |
| `Baseline.kt` | Layers 1–4: `TeamVolume`, `teamVolume`, `PlayerContext`, `BaselineModel.project` |
| `Kinds.kt` | `Side`, `StatType`, `KINDS`, `adjust`, `referencePoints` |
| `Matchup.kt` | Layer 5: `Outcome`, `MatchupModel` (ratings, capped multipliers, notes) |
| `GameScript.kt` | Layer 6: `GameScript`, `gameScript` |
| `Projector.kt` | Walk-forward loop: which weeks, which players, which team, regime breaks, ROS |
| `ProjectionWriter.kt` | `ProjectionSink` and the SQLite writer |
| `Forecast.kt` | Public entry: `Forecast.run`, `Forecast.fail`, `ForecastReport`, `SeasonCopy`, `FORECAST_OK` |

**Modified**

| File | Change |
|---|---|
| `settings.gradle.kts` | `include(":core:forecast")` |
| `core/ingest/build.gradle.kts` | `implementation(projects.core.forecast)` |
| `core/ingest/.../Sources.kt` | `Input.GAMES` → `schedules/games.csv` |
| `core/ingest/.../Games.kt` (new) | `readGames` parser |
| `core/ingest/.../db/Schema.kt` | v7 DDL, versions |
| `core/ingest/.../db/StatsDbWriter.kt` | `writeGames` |
| `core/ingest/.../Metrics.kt` + `etl/gridiron_etl/metrics.py` | `DIST_FAMILIES` for every projected component |
| `core/ingest/.../IngestPipeline.kt` | fetch schedule, write games, run forecast, `IngestProgress.Projecting`, `IngestReport.forecast` |
| `app/.../RefreshText.kt` | progress and summary lines for the forecast |
| `core/projections/.../ProjectionModels.kt`, `ProjectionQueries.kt`, new `ProjectedPoints.kt` | distribution family per component; list, ROS-list, status and game queries; `projectPoints` |
| `core/data/.../ProjectionsRepository.kt` | `status`, `weekAll`, `rosAll`, `game`, `remainingGames` |
| `feature/projections/...` | ViewModel uses real families; route uses the active profile and real position; new list screen and Player-page card |
| `app/...` | `ProjectionListKey`, ☰ → Projections, Player page card, nav wiring |
| `etl/gridiron_etl/build.py` + projection modules and tests | Python projection code deleted |
| `CLAUDE.md`, `docs/superpowers/HANDOFF.md` | docs |

## Sessions

Per `docs/superpowers/HANDOFF.md`, one session runs four tasks: **Session A** Tasks 1–4, **Session B** Tasks 5–8, **Session C** Tasks 9–12.

---
### Task 1: `:core:forecast` module, math and ridge solver

**Files:**
- Create: `core/forecast/build.gradle.kts`
- Create: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/ForecastConstants.kt`
- Create: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/ForecastMath.kt`
- Create: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Ridge.kt`
- Modify: `settings.gradle.kts` (after `include(":core:ingest")`)
- Test: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/ForecastMathTest.kt`
- Test: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/RidgeTest.kt`

**Interfaces:**
- Produces: `FORECAST_VERSION: Int` (public); `internal object K` with every constant; `ewma(values: List<Double>, halfLife: Double): Double?`; `ewmaRatio(numerators: List<Double>, denominators: List<Double>, halfLife: Double): Double?`; `shrink(observed: Double?, n: Double, baseline: Double, k: Double): Double`; `carryoverWeight(week: Int): Double`; `varianceFor(mean: Double, cv: Double): Double`; `Double.capAround(cap: Double): Double`; `RidgeRow(offense: String, defense: String, home: Boolean, value: Double)`; `RidgeFit(mean, offense: Map<String, Double>, defense: Map<String, Double>, home: Double, defenseGames: Map<String, Int>)`; `fitRidge(rows: List<RidgeRow>, lambda: Double = K.RIDGE_LAMBDA): RidgeFit`; `choleskySolve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray`.

- [ ] **Step 1: Create the module**

`settings.gradle.kts`, below `include(":core:ingest")`:

```kotlin
include(":core:forecast")
```

`core/forecast/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.gridiron.jvm.library)
}

// Pure JVM, like :core:ingest: the phone runs this exact code at the end of a
// refresh, and CI runs it on the JVM when it builds the test database.
dependencies {
    api(libs.androidx.sqlite)

    testImplementation(libs.androidx.sqlite.bundled)
}
```

- [ ] **Step 2: Write the failing tests**

`core/forecast/src/test/kotlin/dev/gridiron/core/forecast/ForecastMathTest.kt`:

```kotlin
package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ForecastMathTest {
    @Test
    fun `ewma of nothing is null and of one value is that value`() {
        assertNull(ewma(emptyList(), 4.0))
        assertEquals(3.0, ewma(listOf(3.0), 4.0))
    }

    @Test
    fun `ewma with a half-life of one game moves halfway to each new value`() {
        assertEquals(0.5, ewma(listOf(1.0, 0.0), 1.0)!!, 1e-12)
        assertEquals(0.75, ewma(listOf(1.0, 0.0, 1.0), 1.0)!!, 1e-12)
    }

    @Test
    fun `ewmaRatio divides the two averages and needs a positive denominator`() {
        assertEquals(0.5, ewmaRatio(listOf(2.0, 4.0), listOf(4.0, 8.0), 2.0)!!, 1e-12)
        assertNull(ewmaRatio(listOf(1.0), listOf(0.0), 4.0))
        assertNull(ewmaRatio(emptyList(), emptyList(), 4.0))
    }

    @Test
    fun `shrink weighs a sample of n against k pseudo-observations of the baseline`() {
        assertEquals(0.2, shrink(0.3, 5.0, 0.1, 5.0), 1e-12)
        assertEquals(0.1, shrink(null, 3.0, 0.1, 5.0), 1e-12)
        assertEquals(0.1, shrink(0.3, 0.0, 0.1, 5.0), 1e-12)
    }

    @Test
    fun `carryover starts at 0_55 in week 1 and is gone by week 6`() {
        assertEquals(0.55, carryoverWeight(1), 1e-12)
        assertEquals(0.33, carryoverWeight(3), 1e-12)
        assertEquals(0.0, carryoverWeight(6), 1e-12)
        assertEquals(0.0, carryoverWeight(12), 1e-12)
    }

    @Test
    fun `variance is calibrated so the CV at a mean of 10 is the position's`() {
        assertEquals(49.0, varianceFor(10.0, 0.7), 1e-9)
        assertEquals(0.0, varianceFor(0.0, 0.7))
        assertEquals(0.0, varianceFor(-1.0, 0.7))
    }

    @Test
    fun `capAround clamps a multiplier to one plus or minus the cap`() {
        assertEquals(1.15, 1.3.capAround(0.15), 1e-12)
        assertEquals(0.85, 0.5.capAround(0.15), 1e-12)
        assertEquals(1.02, 1.02.capAround(0.05), 1e-12)
    }
}
```

`core/forecast/src/test/kotlin/dev/gridiron/core/forecast/RidgeTest.kt`:

```kotlin
package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class RidgeTest {
    // Every offense gains 10 against A and B and 12 against C: C's defense allows 2 more.
    private val rows = listOf(
        RidgeRow("A", "B", home = false, value = 10.0),
        RidgeRow("A", "C", home = false, value = 12.0),
        RidgeRow("B", "A", home = false, value = 10.0),
        RidgeRow("B", "C", home = false, value = 12.0),
        RidgeRow("C", "A", home = false, value = 10.0),
        RidgeRow("C", "B", home = false, value = 10.0),
    )

    @Test
    fun `cholesky solves a small positive-definite system`() {
        val x = choleskySolve(arrayOf(doubleArrayOf(4.0, 2.0), doubleArrayOf(2.0, 3.0)), doubleArrayOf(2.0, 1.0))
        assertArrayEquals(doubleArrayOf(0.5, 0.0), x, 1e-12)
    }

    @Test
    fun `a light penalty recovers the defensive difference`() {
        val fit = fitRidge(rows, lambda = 1e-6)
        assertEquals(64.0 / 6, fit.mean, 1e-12)
        assertEquals(2.0, fit.defense.getValue("C") - fit.defense.getValue("A"), 1e-3)
        assertEquals(0.0, fit.defense.getValue("B") - fit.defense.getValue("A"), 1e-3)
        assertEquals(2, fit.defenseGames.getValue("C"))
    }

    @Test
    fun `a heavy penalty pulls every rating to zero`() {
        val fit = fitRidge(rows, lambda = 1e6)
        assertTrue(fit.defense.values.all { abs(it) < 1e-3 })
        assertTrue(fit.offense.values.all { abs(it) < 1e-3 })
    }

    @Test
    fun `home advantage is its own coefficient`() {
        val homeRows = listOf(
            RidgeRow("A", "B", home = true, value = 12.0),
            RidgeRow("B", "A", home = false, value = 10.0),
            RidgeRow("B", "A", home = true, value = 12.0),
            RidgeRow("A", "B", home = false, value = 10.0),
        )
        assertEquals(2.0, fitRidge(homeRows, lambda = 1e-6).home, 1e-3)
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :core:forecast:test`
Expected: FAIL to compile (`ewma`, `fitRidge` and the rest are unresolved).

- [ ] **Step 4: Write the constants**

`core/forecast/src/main/kotlin/dev/gridiron/core/forecast/ForecastConstants.kt`:

```kotlin
package dev.gridiron.core.forecast

/**
 * Bump whenever a constant below, a layer's formula or what gets stored
 * changes: a refresh only copies a season's projections out of a previous
 * database built with the same version.
 */
public const val FORECAST_VERSION: Int = 1

/**
 * Every tuning number the model uses. Sources: the Python ETL's
 * `shrinkage.py` and `projections.py` (deleted in Task 12; their values are
 * carried here verbatim) and `docs/research/research-prediction-models.md`.
 */
internal object K {
    // Layer 1, team volume: play counts are sticky, so a 4-game half-life.
    const val TEAM_HALF_LIFE = 4.0

    // Layer 2, player share (shrinkage.py HALF_LIVES and SHRINKAGE_K; k in games).
    const val SHARE_HALF_LIFE = 4.5
    const val SHARE_K_GAMES = 5.0
    const val CARRYOVER_START = 0.55
    const val CARRYOVER_LAST_WEEK = 6

    // Layer 3, efficiency (shrinkage.py: half-life 10, catch_rate k = 15 games, int_rate k = 150 attempts).
    const val EFFICIENCY_HALF_LIFE = 10.0
    const val EFFICIENCY_K_GAMES = 15.0
    const val INT_K_ATTEMPTS = 150.0

    // Layer 4, touchdowns from expected TDs (shrinkage.py td_rate, k in opportunities).
    const val TD_K_OPPORTUNITIES = 200.0

    // Layer 5, matchup: ridge penalty, extra shrinkage by games rated, minimum rows to rate at all, caps.
    const val RIDGE_LAMBDA = 4.0
    const val MATCHUP_K_GAMES = 6.0
    const val MIN_MATCHUP_ROWS = 16
    const val CAP_EFFICIENCY = 0.15
    const val CAP_VOLUME = 0.05
    const val CAP_TD = 0.20

    // Layer 6, game script (research doc §1.6): implied points move TDs most, yards less, attempts least.
    const val ELASTICITY_TD = 1.0
    const val ELASTICITY_YARDS = 0.5
    const val ELASTICITY_ATTEMPTS = 0.25
    const val IMPLIED_RATIO_MIN = 0.6
    const val IMPLIED_RATIO_MAX = 1.5
    const val LEAGUE_IMPLIED_DEFAULT = 22.0
    const val PASS_RATE_PER_POINT = 0.006
    const val PASS_RATE_MIN = 0.30
    const val PASS_RATE_MAX = 0.80

    // Layer 7, spread: sigma = a * mu^0.75, CV at mu = 10 by position (projections.py EMPIRICAL_CV).
    const val VARIANCE_EXPONENT = 0.75
    val EMPIRICAL_CV: Map<String, Double> = mapOf("QB" to 0.40, "RB" to 0.57, "WR" to 0.70, "TE" to 0.77)

    // Storage: past weeks keep only players the model gave at least this many reference points.
    const val PAST_WEEK_MIN_POINTS = 1.0
    // The upcoming week and rest of season keep players above this.
    const val UPCOMING_MIN_POINTS = 0.1
}
```

- [ ] **Step 5: Write the math**

`core/forecast/src/main/kotlin/dev/gridiron/core/forecast/ForecastMath.kt`:

```kotlin
package dev.gridiron.core.forecast

import kotlin.math.pow

/**
 * Exponentially weighted mean, oldest value first, so the newest counts most.
 * Starts at the first value (no bias correction), like polars' `adjust=False`.
 */
internal fun ewma(values: List<Double>, halfLife: Double): Double? {
    if (values.isEmpty()) return null
    val alpha = 1.0 - 0.5.pow(1.0 / halfLife)
    var e = values[0]
    for (i in 1 until values.size) e = alpha * values[i] + (1 - alpha) * e
    return e
}

/** A rate as the ratio of two EWMAs, so a 2-target game can't swing it as much as a 12-target one. */
internal fun ewmaRatio(numerators: List<Double>, denominators: List<Double>, halfLife: Double): Double? {
    val den = ewma(denominators, halfLife) ?: return null
    if (den <= 0.0) return null
    return ewma(numerators, halfLife)!! / den
}

/** James-Stein: a sample of size [n] carries n/(n+k) of the weight, and [baseline] the rest. */
internal fun shrink(observed: Double?, n: Double, baseline: Double, k: Double): Double =
    if (observed == null || n <= 0.0) baseline else (n * observed + k * baseline) / (n + k)

/** Last season's weight entering [week]: 0.55 in week 1, falling linearly to 0 by week 6. */
internal fun carryoverWeight(week: Int): Double {
    if (week >= K.CARRYOVER_LAST_WEEK) return 0.0
    val span = (K.CARRYOVER_LAST_WEEK - 1).toDouble()
    return K.CARRYOVER_START * (K.CARRYOVER_LAST_WEEK - maxOf(week, 1)) / span
}

/** `sigma = a * mean^0.75`, with `a` set so the CV at a mean of 10 is [cv]; returned as a variance. */
internal fun varianceFor(mean: Double, cv: Double): Double {
    if (mean <= 0.0) return 0.0
    val a = cv * 10.0.pow(1 - K.VARIANCE_EXPONENT)
    val sigma = a * mean.pow(K.VARIANCE_EXPONENT)
    return sigma * sigma
}

/** Clamps a multiplier to `1 ± cap`. */
internal fun Double.capAround(cap: Double): Double = coerceIn(1.0 - cap, 1.0 + cap)
```

- [ ] **Step 6: Write the ridge solver**

`core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Ridge.kt`:

```kotlin
package dev.gridiron.core.forecast

import kotlin.math.sqrt

/** One team-game from the offense's side: [offense] produced [value] against [defense]. */
internal data class RidgeRow(val offense: String, val defense: String, val home: Boolean, val value: Double)

/** `value = mean + offense + defense + home`, each coefficient relative to the mean. */
internal class RidgeFit(
    val mean: Double,
    val offense: Map<String, Double>,
    val defense: Map<String, Double>,
    val home: Double,
    /** Rows each team appears in as the defense: how much evidence its rating has. */
    val defenseGames: Map<String, Int>,
)

/**
 * Two-way ridge regression: one dummy per offense, one per defense and a home
 * flag, fitted on `value - mean` with an L2 penalty [lambda] on every
 * coefficient, so a team seen twice stays near zero. At most 65 parameters,
 * solved directly from the normal equations.
 */
internal fun fitRidge(rows: List<RidgeRow>, lambda: Double = K.RIDGE_LAMBDA): RidgeFit {
    require(rows.isNotEmpty()) { "no rows to fit" }
    val teams = rows.flatMap { listOf(it.offense, it.defense) }.distinct().sorted()
    val index = teams.withIndex().associate { (i, team) -> team to i }
    val t = teams.size
    val p = 2 * t + 1
    val homeCol = p - 1
    val mean = rows.sumOf { it.value } / rows.size
    val xtx = Array(p) { DoubleArray(p) }
    val xty = DoubleArray(p)
    for (r in rows) {
        // Each row has a 1 in its offense column and its defense column, and h in the home column.
        val cols = intArrayOf(index.getValue(r.offense), t + index.getValue(r.defense))
        val h = if (r.home) 1.0 else 0.0
        val y = r.value - mean
        for (a in cols) {
            for (b in cols) xtx[a][b] += 1.0
            xtx[a][homeCol] += h
            xtx[homeCol][a] += h
            xty[a] += y
        }
        xtx[homeCol][homeCol] += h * h
        xty[homeCol] += h * y
    }
    for (i in 0 until p) xtx[i][i] += lambda
    val beta = choleskySolve(xtx, xty)
    return RidgeFit(
        mean = mean,
        offense = teams.associateWith { beta[index.getValue(it)] },
        defense = teams.associateWith { beta[t + index.getValue(it)] },
        home = beta[homeCol],
        defenseGames = rows.groupingBy { it.defense }.eachCount(),
    )
}

/** Solves `a x = b` for a symmetric positive-definite `a`; the ridge penalty guarantees that. */
internal fun choleskySolve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
    val n = b.size
    val l = Array(n) { DoubleArray(n) }
    for (i in 0 until n) {
        for (j in 0..i) {
            var sum = a[i][j]
            for (k in 0 until j) sum -= l[i][k] * l[j][k]
            if (i == j) {
                check(sum > 0.0) { "matrix is not positive definite" }
                l[i][i] = sqrt(sum)
            } else {
                l[i][j] = sum / l[j][j]
            }
        }
    }
    val y = DoubleArray(n)
    for (i in 0 until n) {
        var sum = b[i]
        for (k in 0 until i) sum -= l[i][k] * y[k]
        y[i] = sum / l[i][i]
    }
    val x = DoubleArray(n)
    for (i in n - 1 downTo 0) {
        var sum = y[i]
        for (k in i + 1 until n) sum -= l[k][i] * x[k]
        x[i] = sum / l[i][i]
    }
    return x
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :core:forecast:test`
Expected: PASS, 11 tests.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts core/forecast
git commit -m "forecast: module, EWMA/shrinkage math and ridge solver"
```

---
### Task 2: Schedule download, `game` table and schema v7

**Files:**
- Modify: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Sources.kt`
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Games.kt`
- Modify: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/db/Schema.kt`
- Modify: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/db/StatsDbWriter.kt`
- Modify: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/IngestPipeline.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/GamesTest.kt` (new)
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/IngestPipelineTest.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/db/StatsDbWriterTest.kt`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `Input.GAMES`; `GameRow` (internal data class, fields below); `readGames(input: InputStream, source: String, seasons: Set<Int>): List<GameRow>`; `StatsDbWriter.writeGames(rows: List<GameRow>)`; a `game` table in every built database with this exact shape (Task 4 reads it):

```sql
CREATE TABLE game (
    game_id TEXT PRIMARY KEY, season INTEGER NOT NULL, week INTEGER NOT NULL, game_type TEXT NOT NULL,
    home_team TEXT NOT NULL, away_team TEXT NOT NULL, home_score INTEGER, away_score INTEGER,
    spread_line REAL, total_line REAL, roof TEXT, home_qb_id TEXT, away_qb_id TEXT,
    home_coach TEXT, away_coach TEXT) WITHOUT ROWID
```

- [ ] **Step 1: Write the failing parser test**

`core/ingest/src/test/kotlin/dev/gridiron/core/ingest/GamesTest.kt`:

```kotlin
package dev.gridiron.core.ingest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GamesTest {
    private val header = listOf(
        "game_id", "season", "game_type", "week", "gameday", "home_team", "away_team", "home_score", "away_score",
        "spread_line", "total_line", "roof", "home_qb_id", "away_qb_id", "home_coach", "away_coach",
    )

    private val csv = Fixtures.csv(
        header,
        listOf(
            mapOf(
                "game_id" to "2026_01_NE_SEA", "season" to 2026, "game_type" to "REG", "week" to 1, "gameday" to "2026-09-09",
                "home_team" to "SEA", "away_team" to "NE", "home_score" to 13, "away_score" to 10, "spread_line" to 3,
                "total_line" to 44.5, "roof" to "outdoors", "home_qb_id" to "00-0034869", "away_qb_id" to "00-0039851",
                "home_coach" to "Mike Macdonald", "away_coach" to "Mike Vrabel",
            ),
            mapOf(
                "game_id" to "2026_05_KC_BUF", "season" to 2026, "game_type" to "REG", "week" to 5,
                "home_team" to "BUF", "away_team" to "KC", "spread_line" to "NA", "home_qb_id" to "NA",
            ),
            mapOf("game_id" to "2019_01_OAK_DEN", "season" to 2019, "game_type" to "REG", "week" to 1, "home_team" to "DEN", "away_team" to "OAK"),
        ),
    )

    @Test
    fun `keeps the requested seasons with results, lines, starting QBs and coaches`() {
        val games = readGames(csv.byteInputStream(), "games.csv", setOf(2026))

        assertEquals(listOf("2026_01_NE_SEA", "2026_05_KC_BUF"), games.map { it.gameId })
        val played = games[0]
        assertEquals(2026, played.season)
        assertEquals(1, played.week)
        assertEquals("REG", played.gameType)
        assertEquals("SEA", played.homeTeam)
        assertEquals("NE", played.awayTeam)
        assertEquals(13, played.homeScore)
        assertEquals(10, played.awayScore)
        assertEquals(3.0, played.spreadLine)
        assertEquals(44.5, played.totalLine)
        assertEquals("outdoors", played.roof)
        assertEquals("00-0034869", played.homeQbId)
        assertEquals("Mike Vrabel", played.awayCoach)
    }

    @Test
    fun `an unplayed game has no scores, and NA reads as missing`() {
        val future = readGames(csv.byteInputStream(), "games.csv", setOf(2026))[1]
        assertNull(future.homeScore)
        assertNull(future.awayScore)
        assertNull(future.spreadLine)
        assertNull(future.totalLine)
        assertNull(future.homeQbId)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:ingest:test --tests "dev.gridiron.core.ingest.GamesTest"`
Expected: FAIL to compile (`readGames` unresolved).

- [ ] **Step 3: Add the source and the parser**

In `Sources.kt`, add to `Input` after `PLAYERS(false, "player list"),`:

```kotlin
    GAMES(false, "schedule"),
```

and to `Sources.url`'s `when`, after the `Input.PLAYERS` branch:

```kotlin
            // Every season since 1999 in one file: opponents, results, lines, starting QBs, coaches.
            Input.GAMES -> "$NFLVERSE/schedules/games.csv"
```

`core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Games.kt`:

```kotlin
package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.csv.readCsv
import java.io.InputStream

/** One game from nflverse's schedule: who played whom, the result once played, and the pre-game line. */
internal data class GameRow(
    val gameId: String,
    val season: Int,
    val week: Int,
    /** REG, WC, DIV, CON or SB. */
    val gameType: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int?,
    val awayScore: Int?,
    /** Points the home team is favored by; negative when the away team is. */
    val spreadLine: Double?,
    val totalLine: Double?,
    val roof: String?,
    val homeQbId: String?,
    val awayQbId: String?,
    val homeCoach: String?,
    val awayCoach: String?,
)

private val REQUIRED = listOf("game_id", "season", "game_type", "week", "home_team", "away_team")
private val OPTIONAL = listOf(
    "home_score", "away_score", "spread_line", "total_line", "roof", "home_qb_id", "away_qb_id", "home_coach", "away_coach",
)

/** nflverse's `games.csv`, which covers every season since 1999, cut down to [seasons]. */
internal fun readGames(input: InputStream, source: String, seasons: Set<Int>): List<GameRow> {
    val games = ArrayList<GameRow>()
    readCsv(input, source, REQUIRED + OPTIONAL, required = REQUIRED) { row ->
        // nflverse writes missing values as empty fields, but older rows of this file use NA.
        fun text(name: String): String? = row.text(name)?.takeUnless { it == "NA" }
        fun number(name: String): Double? = text(name)?.toDoubleOrNull()

        val season = number("season")?.toInt() ?: return@readCsv
        if (season !in seasons) return@readCsv
        games += GameRow(
            gameId = text("game_id") ?: return@readCsv,
            season = season,
            week = number("week")?.toInt() ?: return@readCsv,
            gameType = text("game_type") ?: return@readCsv,
            homeTeam = text("home_team") ?: return@readCsv,
            awayTeam = text("away_team") ?: return@readCsv,
            homeScore = number("home_score")?.toInt(),
            awayScore = number("away_score")?.toInt(),
            spreadLine = number("spread_line"),
            totalLine = number("total_line"),
            roof = text("roof"),
            homeQbId = text("home_qb_id"),
            awayQbId = text("away_qb_id"),
            homeCoach = text("home_coach"),
            awayCoach = text("away_coach"),
        )
    }
    return games
}
```

- [ ] **Step 4: Run the parser test to verify it passes**

Run: `./gradlew :core:ingest:test --tests "dev.gridiron.core.ingest.GamesTest"`
Expected: PASS, 2 tests.

- [ ] **Step 5: Write the failing writer and pipeline tests**

In `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/db/StatsDbWriterTest.kt`, change the expected schema version from `"6"` to `"7"` and add:

```kotlin
    @Test
    fun `games are written with nulls for what isn't known yet`(@TempDir dir: File) {
        val file = File(dir, "stats.db")
        StatsDbWriter.create(file).use { w ->
            w.writeGames(
                listOf(
                    GameRow("2026_01_NE_SEA", 2026, 1, "REG", "SEA", "NE", 13, 10, 3.0, 44.5, "outdoors", "q1", "q2", "c1", "c2"),
                    GameRow("2026_05_KC_BUF", 2026, 5, "REG", "BUF", "KC", null, null, null, null, null, null, null, null, null),
                ),
            )
        }
        assertEquals(
            listOf(
                listOf("2026_01_NE_SEA", "13", "3.0", "q1", "c2"),
                listOf("2026_05_KC_BUF", null, null, null, null),
            ),
            query(file, "SELECT game_id, home_score, spread_line, home_qb_id, away_coach FROM game ORDER BY game_id"),
        )
    }

    @Test
    fun `schema 7 drops the Python ETL's projection bookkeeping tables`(@TempDir dir: File) {
        val file = File(dir, "stats.db")
        StatsDbWriter.create(file).close()
        val tables = query(file, "SELECT name FROM sqlite_master WHERE type = 'table'").map { it[0] }
        assertTrue("game" in tables)
        assertFalse("projection_snapshot" in tables)
        assertFalse("accuracy_summary" in tables)
    }
```

Add any of these imports the file lacks: `dev.gridiron.core.ingest.GameRow`, `dev.gridiron.core.ingest.query`, `org.junit.jupiter.api.Assertions.assertFalse`, `org.junit.jupiter.api.Assertions.assertTrue`, `org.junit.jupiter.api.io.TempDir`, `java.io.File`.

In `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/IngestPipelineTest.kt`:

1. Add a schedule header and serve a schedule from `servePlayers`, so every existing test has one (otherwise the schedule's 404 warning and its missing validators break their assertions):

```kotlin
    private val gamesHeader = listOf(
        "game_id", "season", "game_type", "week", "home_team", "away_team", "home_score", "away_score", "spread_line", "total_line",
    )

    private fun serveGames(version: String = "g1") {
        val rows = listOf(2023, 2024, 2025).map { season ->
            mapOf(
                "game_id" to "${season}_01_BBB_AAA", "season" to season, "game_type" to "REG", "week" to 1,
                "home_team" to "AAA", "away_team" to "BBB", "home_score" to 20, "away_score" to 17,
                "spread_line" to 2.5, "total_line" to 41.5,
            )
        }
        fetcher.serve(Sources.url(Input.GAMES), Fixtures.csv(gamesHeader, rows).toByteArray(), version)
    }
```

and make the last line of `servePlayers` call `serveGames()`.

2. In `a first build downloads everything and writes a validated database`, change `assertEquals("6", meta["schema_version"])` to `"7"` and `assertEquals("1", meta["ingest_version"])` to `"2"`.

3. Add:

```kotlin
    @Test
    fun `the schedule is stored for the built seasons only`() = runTest {
        servePlayers()
        serveSeason(2024)
        serveSeason(2025)
        val out = File(dir, "stats.db")

        pipeline.build(listOf(2024, 2025), previous = null, out = out)

        assertEquals(listOf(listOf("2024"), listOf("2025")), query(out, "SELECT season FROM game ORDER BY season"))
        assertNotNull(readMeta(out)!![Sources.metaKey(Input.GAMES)])
    }

    @Test
    fun `a missing schedule is a warning, not a failed build`() = runTest {
        servePlayers()
        fetcher.remove(Sources.url(Input.GAMES))
        serveSeason(2025)
        val out = File(dir, "stats.db")

        val report = pipeline.build(listOf(2025), previous = null, out = out)

        assertEquals(listOf(2025), report.built)
        assertTrue(report.warnings.any { "schedule" in it }, "${report.warnings}")
        assertEquals(listOf(listOf("0")), query(out, "SELECT COUNT(*) FROM game"))
    }

    @Test
    fun `an unchanged schedule is read from the kept copy`() = runTest {
        servePlayers()
        serveSeason(2025)
        pipeline.build(listOf(2025), null, File(dir, "first.db"))
        fetcher.calls.clear()

        val second = File(dir, "second.db")
        pipeline.build(listOf(2025), File(dir, "first.db"), second)

        assertNotNull(fetcher.calls.single { it.first == Sources.url(Input.GAMES) }.second)
        assertEquals(listOf(listOf("1")), query(second, "SELECT COUNT(*) FROM game"))
    }
```

- [ ] **Step 6: Run them to verify they fail**

Run: `./gradlew :core:ingest:test`
Expected: FAIL. `writeGames` is unresolved, so the test sources don't compile.

- [ ] **Step 7: Schema v7**

In `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/db/Schema.kt`:

- Replace the `SCHEMA_VERSION` declaration and its comment with:

```kotlin
/**
 * Written into `schema_meta`: the Python ETL's schema 5, plus `player_xref`
 * (6), plus `game`, minus the Python ETL's projection bookkeeping tables (7).
 */
public const val SCHEMA_VERSION: Int = 7
```

- Change `INGEST_VERSION` from `1` to `2`.
- Replace the comment above `SCHEMA` with `/** `etl/gridiron_etl/schema.py`'s DDL, one statement per entry, plus `player_xref` and `game`, minus `projection_snapshot` and `accuracy_summary`. */`
- Delete the `CREATE TABLE projection_snapshot` and `CREATE TABLE accuracy_summary` entries.
- Add at the end of `SCHEMA`, after the `player_xref` entry:

```kotlin
    // nflverse's schedule for the built seasons: opponents, results, pre-game lines, starting QBs and coaches.
    """CREATE TABLE game (
        game_id TEXT PRIMARY KEY, season INTEGER NOT NULL, week INTEGER NOT NULL, game_type TEXT NOT NULL,
        home_team TEXT NOT NULL, away_team TEXT NOT NULL, home_score INTEGER, away_score INTEGER,
        spread_line REAL, total_line REAL, roof TEXT, home_qb_id TEXT, away_qb_id TEXT,
        home_coach TEXT, away_coach TEXT) WITHOUT ROWID""",
```

- Add to `INDEXES`:

```kotlin
    "CREATE INDEX idx_game_week ON game (season, week)",
```

- [ ] **Step 8: `writeGames`**

In `StatsDbWriter.kt`, add the import `dev.gridiron.core.ingest.GameRow`, add this method after `writeInjuries`:

```kotlin
    fun writeGames(rows: List<GameRow>) = insert(
        """INSERT OR REPLACE INTO game (game_id, season, week, game_type, home_team, away_team, home_score,
           away_score, spread_line, total_line, roof, home_qb_id, away_qb_id, home_coach, away_coach)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        rows,
    ) { st, g ->
        st.bindText(1, g.gameId)
        st.bindLong(2, g.season.toLong())
        st.bindLong(3, g.week.toLong())
        st.bindText(4, g.gameType)
        st.bindText(5, g.homeTeam)
        st.bindText(6, g.awayTeam)
        st.bindLongOrNull(7, g.homeScore)
        st.bindLongOrNull(8, g.awayScore)
        st.bindDoubleOrNull(9, g.spreadLine)
        st.bindDoubleOrNull(10, g.totalLine)
        st.bindTextOrNull(11, g.roof)
        st.bindTextOrNull(12, g.homeQbId)
        st.bindTextOrNull(13, g.awayQbId)
        st.bindTextOrNull(14, g.homeCoach)
        st.bindTextOrNull(15, g.awayCoach)
    }
```

and these beside the existing `bindTextOrNull` at the bottom of the file:

```kotlin
private fun SQLiteStatement.bindLongOrNull(index: Int, value: Int?) {
    if (value == null) bindNull(index) else bindLong(index, value.toLong())
}

private fun SQLiteStatement.bindDoubleOrNull(index: Int, value: Double?) {
    if (value == null) bindNull(index) else bindDouble(index, value)
}
```

- [ ] **Step 9: Fetch and store the schedule in the pipeline**

In `IngestPipeline.kt`:

- Add the import `dev.gridiron.core.ingest.csv.MissingColumnsException`.
- Add a constructor parameter after `playersFile`, and update the class comment's last paragraph:

```kotlin
    private val gamesFile: File = playersFile.resolveSibling("games.csv"),
```

```kotlin
 * [playersFile] and [gamesFile] (the player list and the schedule, kept
 * between builds so an unchanged one needn't be downloaded again) must live
 * outside [workDir], which is emptied when a build ends.
```

- In `Run.build`, between `writer.writePlayers(players)` and `writer.finish(...)`:

```kotlin
                writer.writeGames(readSchedule((built + reused).toSet()))
```

- Add to `Run`, after `fetchPlayers`:

```kotlin
        /** Null, with a warning, when there's no schedule: projections need it, stats don't. */
        private suspend fun fetchGames(): File? {
            val key = Sources.metaKey(Input.GAMES)
            val known = prior?.get(key)?.let(Validators::decode)?.takeIf { gamesFile.isFile }
            return when (val r = fetch(Input.GAMES, null, known, gamesFile)) {
                is FetchResult.Downloaded -> gamesFile.also { meta[key] = r.validators.encode() }
                FetchResult.NotModified -> gamesFile.also { meta[key] = checkNotNull(prior).getValue(key) }
                FetchResult.NotPublished -> {
                    warnings += "nflverse's schedule isn't available right now; no projections this time"
                    null
                }
            }
        }

        private suspend fun readSchedule(seasons: Set<Int>): List<GameRow> {
            val file = fetchGames() ?: return emptyList()
            return try {
                openInput(file).use { readGames(it, file.name, seasons) }
            } catch (e: IOException) {
                warnings += "the schedule file is unreadable (${e.message}); no projections this time"
                emptyList()
            } catch (e: MissingColumnsException) {
                warnings += "${e.message}; no projections this time"
                emptyList()
            }
        }
```

- [ ] **Step 10: Run the ingest tests**

Run: `./gradlew :core:ingest:test`
Expected: PASS. If a pre-existing test asserts an exact list of fetched URLs or warnings, add the schedule URL (`Sources.url(Input.GAMES)`) to its expectation; don't loosen the assertion.

- [ ] **Step 11: Commit**

```bash
git add core/ingest
git commit -m "ingest: download nflverse's schedule into a game table (schema 7)"
```

---
### Task 3: Distribution family for every projected stat (Kotlin and Python registries)

The `metric` table's `dist_family` column exists but is empty. The phone's floor/ceiling simulation needs it, and CI's parity job compares the column between the Python and Kotlin builds, so both registries get the same map.

**Files:**
- Modify: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Metrics.kt`
- Modify: `etl/gridiron_etl/metrics.py`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/MetricsTest.kt`
- Test: `etl/tests/test_registry.py`

**Interfaces:**
- Produces: `DIST_FAMILIES: Map<String, String>` (internal, `:core:ingest`) and `DIST_FAMILIES: dict[str, str]` (Python), identical; every projected stat's `metric.dist_family` row in a built database is one of `negbinom`, `binomial`, `gamma`, `poisson`. Task 9 reads it through a join.

- [ ] **Step 1: Write the failing tests**

Append to `MetricsTest`:

```kotlin
    @Test
    fun `every projected stat has a distribution family the simulation knows`() {
        val projected = listOf(
            "attempts", "completions", "passing_yards", "passing_tds", "passing_tds_40", "passing_tds_50",
            "interceptions", "sacks_taken", "passing_first_downs", "passing_2pt",
            "carries", "rushing_yards", "rushing_tds", "rushing_tds_40", "rushing_tds_50", "rushing_first_downs", "rushing_2pt",
            "targets", "receptions", "receiving_yards", "receiving_tds", "receiving_tds_40", "receiving_tds_50",
            "receiving_first_downs", "receiving_2pt", "fumbles_lost",
        )
        for (id in projected) {
            assertTrue(byId.getValue(id).distFamily in setOf("negbinom", "binomial", "gamma", "poisson"), id)
        }
        assertEquals("negbinom", byId.getValue("targets").distFamily)
        assertEquals("binomial", byId.getValue("receptions").distFamily)
        assertEquals("gamma", byId.getValue("receiving_yards").distFamily)
        assertEquals("poisson", byId.getValue("receiving_tds").distFamily)
        assertEquals(null, byId.getValue("target_share").distFamily)
        assertEquals(projected.toSet(), DIST_FAMILIES.keys)
    }
```

(Import `org.junit.jupiter.api.Assertions.assertTrue` and `assertEquals` if the file lacks them.)

Append to `etl/tests/test_registry.py`:

```python
def test_projected_stats_have_distribution_families():
    from gridiron_etl.metrics import DIST_FAMILIES

    assert METRICS["targets"].dist_family == "negbinom"
    assert METRICS["receptions"].dist_family == "binomial"
    assert METRICS["receiving_yards"].dist_family == "gamma"
    assert METRICS["receiving_tds"].dist_family == "poisson"
    assert METRICS["target_share"].dist_family is None
    for mid, family in DIST_FAMILIES.items():
        assert METRICS[mid].dist_family == family, mid
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:ingest:test --tests "dev.gridiron.core.ingest.MetricsTest"` and `cd etl && python -m pytest tests/test_registry.py -q`
Expected: both FAIL (`DIST_FAMILIES` unresolved / ImportError).

- [ ] **Step 3: Kotlin registry**

In `Metrics.kt`, rename `internal val METRICS: List<Metric> = listOf(` to `private val REGISTRY: List<Metric> = listOf(`, and add, directly after that list's closing (before `SPARSE_METRIC_IDS`):

```kotlin
/**
 * The distribution the phone's floor/ceiling simulation draws each projected
 * stat from: counts of opportunities are negative binomial, successes out of
 * them binomial, yards gamma, and rare events Poisson. Mirrors
 * `etl/gridiron_etl/metrics.py`'s DIST_FAMILIES, which CI's parity job checks.
 */
internal val DIST_FAMILIES: Map<String, String> = buildMap {
    for (id in listOf("attempts", "carries", "targets")) put(id, "negbinom")
    for (id in listOf("completions", "receptions")) put(id, "binomial")
    for (id in listOf("passing_yards", "rushing_yards", "receiving_yards")) put(id, "gamma")
    listOf(
        "passing_tds", "passing_tds_40", "passing_tds_50", "interceptions", "sacks_taken", "passing_first_downs", "passing_2pt",
        "rushing_tds", "rushing_tds_40", "rushing_tds_50", "rushing_first_downs", "rushing_2pt",
        "receiving_tds", "receiving_tds_40", "receiving_tds_50", "receiving_first_downs", "receiving_2pt", "fumbles_lost",
    ).forEach { put(it, "poisson") }
}

internal val METRICS: List<Metric> = REGISTRY.map { it.copy(distFamily = DIST_FAMILIES[it.id] ?: it.distFamily) }
```

- [ ] **Step 4: Python registry**

In `etl/gridiron_etl/metrics.py`, change the import to `from dataclasses import asdict, dataclass, replace`, and replace `METRICS: dict[str, Metric] = {m.id: m for m in _M}` with:

```python
# The distribution the phone's floor/ceiling simulation draws each projected
# stat from. Mirrors core/ingest's Metrics.kt DIST_FAMILIES; the parity job
# compares the metric table's dist_family column between the two builds.
DIST_FAMILIES: dict[str, str] = {
    **{m: "negbinom" for m in ("attempts", "carries", "targets")},
    **{m: "binomial" for m in ("completions", "receptions")},
    **{m: "gamma" for m in ("passing_yards", "rushing_yards", "receiving_yards")},
    **{m: "poisson" for m in (
        "passing_tds", "passing_tds_40", "passing_tds_50", "interceptions", "sacks_taken",
        "passing_first_downs", "passing_2pt",
        "rushing_tds", "rushing_tds_40", "rushing_tds_50", "rushing_first_downs", "rushing_2pt",
        "receiving_tds", "receiving_tds_40", "receiving_tds_50", "receiving_first_downs",
        "receiving_2pt", "fumbles_lost",
    )},
}

METRICS: dict[str, Metric] = {
    m.id: replace(m, dist_family=DIST_FAMILIES.get(m.id, m.dist_family)) for m in _M
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:ingest:test` and `cd etl && python -m pytest tests/ -q`
Expected: PASS.

- [ ] **Step 6: Prove parity still holds**

Run:

```bash
cd etl && python -m gridiron_etl.build --seasons 2025 --out build/parity/py.db && cd ..
./gradlew :core:ingest:buildStatsDb -Pseasons=2025 -Pout=etl/build/parity/kt.db
python etl/tools/parity.py etl/build/parity/py.db etl/build/parity/kt.db
```

Expected: `metric: … 0 differing` and exit code 0. (Needs network. If the session has none, say so in the handoff and let CI's parity job prove it.)

- [ ] **Step 7: Commit**

```bash
git add core/ingest etl/gridiron_etl/metrics.py etl/tests/test_registry.py
git commit -m "metrics: distribution family for every projected stat, in both registries"
```

---
### Task 4: Forecast inputs and league rates

**Files:**
- Create: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Inputs.kt`
- Create: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/League.kt`
- Create: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/TestDb.kt`
- Test: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/InputsTest.kt`
- Test: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/LeagueTest.kt`

**Interfaces:**
- Consumes: the `game` table shape from Task 2; `player`, `player_week_stat`, `schema_meta` as `:core:ingest` writes them.
- Produces (all internal):
  - `class PlayerGame(playerId: String, season: Int, week: Int, team: String, stats: Map<String, Double>)` with `order: Int` and `operator fun get(metric: String): Double` (absent = 0.0)
  - `fun order(season: Int, week: Int): Int` = `season * 100 + week`
  - `data class PlayerInfo(playerId: String, name: String, position: String, team: String?)`
  - `data class Game(season, week, regular: Boolean, home, away, played: Boolean, spread: Double?, total: Double?, homeQb: String?, awayQb: String?, homeCoach: String?, awayCoach: String?)` with `involves`, `opponentOf`, `isHome`, `qbOf`, `coachOf`, `favoredBy(team): Double?`, `impliedPoints(team): Double?`
  - `class TeamGame(team, season, week)` with `var passAttempts, targets, carries, passYards, rushYards, passTds, rushTds: Double` and `order`
  - `class ForecastInputs(players: Map<String, PlayerInfo>, history: Map<String, List<PlayerGame>>, games: List<Game>, teamGames: Map<Triple<String, Int, Int>, TeamGame>, expectedThrough: Map<Int, Int>)`
  - `val POSITIONS = listOf("QB", "RB", "WR", "TE")`
  - `fun loadInputs(conn: SQLiteConnection): ForecastInputs`
  - `class LeagueTotals` with `add(position: String, g: PlayerGame, team: TeamGame, expectedCovered: Boolean)` and `rates(position: String): Rates?` (a snapshot)
  - `class Rates` with `targetShare, carryShare, passShare, catchRate, yardsPerTarget, yardsPerCarry, completionRate, yardsPerAttempt, interceptionRate, sackRate, xReceivingTdPerTarget, xRushingTdPerCarry, xPassingTdPerAttempt, recFirstDownsPerReception, rushFirstDownsPerCarry, passFirstDownsPerCompletion, rec2ptPerTarget, rush2ptPerCarry, pass2ptPerAttempt, fumblesPerTouch: Double` and `longTdShare(kind: String, yards: Int): Double`; `Rates(sums: Map<String, Double>)` is constructible in tests.
  - Test helper `TestDb(file: File)` with `conn`, `meta`, `player`, `week`, `game`, `exec`, `query`.

- [ ] **Step 1: Write the test database helper**

`core/forecast/src/test/kotlin/dev/gridiron/core/forecast/TestDb.kt`:

```kotlin
package dev.gridiron.core.forecast

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File

/**
 * A stats database holding only the tables the forecast reads and writes,
 * filled by hand. The DDL is `:core:ingest`'s `Schema.kt` for these tables.
 */
internal class TestDb(val file: File) : AutoCloseable {
    val conn: SQLiteConnection = BundledSQLiteDriver().open(file.path)

    init {
        for (ddl in DDL) conn.execSQL(ddl)
    }

    fun meta(key: String, value: String) = exec("INSERT OR REPLACE INTO schema_meta (key, value) VALUES (?, ?)", key, value)

    fun player(id: String, position: String, team: String?) = exec(
        "INSERT INTO player (player_id, full_name, search_name, position, team) VALUES (?, ?, ?, ?, ?)",
        id, "Player $id", "player ${id.lowercase()}", position, team,
    )

    /** One played week for [id]: `g` = 1 plus [values]. */
    fun week(id: String, season: Int, week: Int, team: String, vararg values: Pair<String, Double>) {
        for ((metric, value) in listOf("g" to 1.0) + values) {
            exec(
                "INSERT OR REPLACE INTO player_week_stat (player_id, season, week, team, metric_id, value) VALUES (?, ?, ?, ?, ?, ?)",
                id, season, week, team, metric, value,
            )
        }
    }

    fun game(
        season: Int,
        week: Int,
        home: String,
        away: String,
        played: Boolean = true,
        spread: Double? = null,
        total: Double? = null,
        homeQb: String? = null,
        awayQb: String? = null,
        homeCoach: String? = null,
        awayCoach: String? = null,
        type: String = "REG",
    ) = exec(
        """INSERT INTO game (game_id, season, week, game_type, home_team, away_team, home_score, away_score,
           spread_line, total_line, home_qb_id, away_qb_id, home_coach, away_coach)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        "${season}_${week}_${away}_$home", season, week, type, home, away,
        if (played) 21 else null, if (played) 17 else null, spread, total, homeQb, awayQb, homeCoach, awayCoach,
    )

    fun query(sql: String): List<List<String?>> = conn.prepare(sql).use { st ->
        buildList {
            while (st.step()) add((0 until st.getColumnCount()).map { if (st.isNull(it)) null else st.getText(it) })
        }
    }

    fun exec(sql: String, vararg binds: Any?) {
        conn.prepare(sql).use { st ->
            binds.forEachIndexed { i, b ->
                when (b) {
                    null -> st.bindNull(i + 1)
                    is String -> st.bindText(i + 1, b)
                    is Int -> st.bindLong(i + 1, b.toLong())
                    is Long -> st.bindLong(i + 1, b)
                    is Double -> st.bindDouble(i + 1, b)
                    else -> error("can't bind $b")
                }
            }
            st.step()
        }
    }

    override fun close() = conn.close()

    companion object {
        val DDL = listOf(
            "CREATE TABLE schema_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            """CREATE TABLE player (
                player_id TEXT PRIMARY KEY, full_name TEXT NOT NULL, search_name TEXT NOT NULL,
                position TEXT, team TEXT, pfr_player_id TEXT)""",
            """CREATE TABLE player_week_stat (
                player_id TEXT NOT NULL, season INTEGER NOT NULL, week INTEGER NOT NULL, team TEXT,
                metric_id TEXT NOT NULL, value REAL NOT NULL,
                PRIMARY KEY (player_id, season, week, metric_id)) WITHOUT ROWID""",
            """CREATE TABLE player_week_projection (
                player_id TEXT NOT NULL, season INTEGER NOT NULL, week INTEGER NOT NULL,
                metric_id TEXT NOT NULL, stage TEXT NOT NULL, mean REAL NOT NULL, variance REAL NOT NULL,
                PRIMARY KEY (player_id, season, week, metric_id, stage)) WITHOUT ROWID""",
            """CREATE TABLE player_week_projection_factor (
                player_id TEXT NOT NULL, season INTEGER NOT NULL, week INTEGER NOT NULL,
                factor TEXT NOT NULL, log_multiplier REAL NOT NULL, note TEXT,
                PRIMARY KEY (player_id, season, week, factor)) WITHOUT ROWID""",
            """CREATE TABLE player_ros_projection (
                player_id TEXT NOT NULL, season INTEGER NOT NULL, as_of_week INTEGER NOT NULL,
                metric_id TEXT NOT NULL, mean REAL NOT NULL, variance REAL NOT NULL,
                PRIMARY KEY (player_id, season, as_of_week, metric_id)) WITHOUT ROWID""",
            """CREATE TABLE game (
                game_id TEXT PRIMARY KEY, season INTEGER NOT NULL, week INTEGER NOT NULL, game_type TEXT NOT NULL,
                home_team TEXT NOT NULL, away_team TEXT NOT NULL, home_score INTEGER, away_score INTEGER,
                spread_line REAL, total_line REAL, roof TEXT, home_qb_id TEXT, away_qb_id TEXT,
                home_coach TEXT, away_coach TEXT) WITHOUT ROWID""",
        )
    }
}
```

- [ ] **Step 2: Write the failing tests**

`core/forecast/src/test/kotlin/dev/gridiron/core/forecast/InputsTest.kt`:

```kotlin
package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class InputsTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `players, their weeks, team totals, games and expected-points coverage load`() {
        TestDb(File(dir, "stats.db")).use { db ->
            db.player("QB1", "QB", "AAA")
            db.player("WR1", "WR", "AAA")
            db.player("K1", "K", "AAA")
            db.week("QB1", 2025, 1, "AAA", "attempts" to 30.0, "passing_yards" to 250.0, "passing_tds" to 2.0)
            db.week("WR1", 2025, 1, "AAA", "targets" to 8.0, "receptions" to 5.0, "x_receiving_tds" to 0.4)
            db.week("WR1", 2025, 2, "AAA", "targets" to 6.0)
            // Kickers aren't projected: this target must not reach AAA's team total.
            db.week("K1", 2025, 1, "AAA", "targets" to 1.0)
            db.game(2025, 1, "AAA", "BBB", spread = 3.0, total = 44.0, homeQb = "QB1", homeCoach = "Coach A")
            db.game(2025, 5, "BBB", "AAA", played = false)
            db.meta("expected_through_week:2025", "1")

            val inputs = loadInputs(db.conn)

            assertEquals(setOf("QB1", "WR1"), inputs.players.keys)
            val wr = inputs.history.getValue("WR1")
            assertEquals(listOf(1, 2), wr.map { it.week })
            assertEquals(8.0, wr[0]["targets"])
            assertEquals(0.0, wr[1]["receptions"])
            val team = inputs.teamGames.getValue(Triple("AAA", 2025, 1))
            assertEquals(30.0, team.passAttempts)
            assertEquals(8.0, team.targets)
            assertEquals(250.0, team.passYards)
            assertEquals(2.0, team.passTds)
            assertEquals(2, inputs.games.size)
            val first = inputs.games[0]
            assertTrue(first.played)
            assertFalse(inputs.games[1].played)
            assertEquals("BBB", first.opponentOf("AAA"))
            assertEquals("QB1", first.qbOf("AAA"))
            assertEquals("Coach A", first.coachOf("AAA"))
            assertEquals(mapOf(2025 to 1), inputs.expectedThrough)
        }
    }

    @Test
    fun `implied points split the total by the home-favored spread`() {
        val game = Game(2026, 1, true, "SEA", "NE", false, 3.0, 44.5, null, null, null, null)
        assertEquals(23.75, game.impliedPoints("SEA"))
        assertEquals(20.75, game.impliedPoints("NE"))
        assertEquals(-3.0, game.favoredBy("NE"))
        assertNull(game.copy(total = null).impliedPoints("SEA"))
        assertNull(game.copy(spread = null).impliedPoints("SEA"))
    }
}
```

`core/forecast/src/test/kotlin/dev/gridiron/core/forecast/LeagueTest.kt`:

```kotlin
package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LeagueTest {
    private fun game(id: String, week: Int, vararg values: Pair<String, Double>) =
        PlayerGame(id, 2025, week, "AAA", mapOf("g" to 1.0) + values)

    private fun team(week: Int, targets: Double, carries: Double = 0.0) =
        TeamGame("AAA", 2025, week).apply {
            this.targets = targets
            this.carries = carries
        }

    @Test
    fun `rates pool every game at the position`() {
        val totals = LeagueTotals()
        totals.add("WR", game("W1", 1, "targets" to 10.0, "receptions" to 7.0, "receiving_yards" to 90.0), team(1, 40.0), expectedCovered = true)
        totals.add("WR", game("W2", 2, "targets" to 5.0, "receptions" to 2.0, "receiving_yards" to 20.0), team(2, 20.0), expectedCovered = true)

        val rates = totals.rates("WR")!!
        assertEquals(0.25, rates.targetShare, 1e-12) // 15 of 60
        assertEquals(0.6, rates.catchRate, 1e-12) // 9 of 15
        assertEquals(110.0 / 15, rates.yardsPerTarget, 1e-12)
        assertNull(totals.rates("TE"))
    }

    @Test
    fun `the expected TD rate counts covered weeks only, and falls back to actual TDs with none`() {
        val covered = LeagueTotals()
        covered.add("WR", game("W1", 1, "targets" to 10.0, "x_receiving_tds" to 0.5, "receiving_tds" to 2.0), team(1, 40.0), expectedCovered = true)
        covered.add("WR", game("W1", 2, "targets" to 10.0, "receiving_tds" to 1.0), team(2, 40.0), expectedCovered = false)
        assertEquals(0.05, covered.rates("WR")!!.xReceivingTdPerTarget, 1e-12)

        val none = LeagueTotals()
        none.add("WR", game("W1", 1, "targets" to 10.0, "receiving_tds" to 2.0), team(1, 40.0), expectedCovered = false)
        assertEquals(0.2, none.rates("WR")!!.xReceivingTdPerTarget, 1e-12)
    }

    @Test
    fun `rare events are rates per opportunity, and long TDs a share of TDs`() {
        val totals = LeagueTotals()
        totals.add(
            "RB",
            game(
                "R1", 1, "carries" to 20.0, "receptions" to 4.0, "fumbles_lost" to 1.0, "rushing_tds" to 2.0,
                "rushing_tds_40" to 1.0, "rushing_first_downs" to 5.0,
            ),
            team(1, 30.0, carries = 25.0),
            expectedCovered = true,
        )
        val rates = totals.rates("RB")!!
        assertEquals(1.0 / 24, rates.fumblesPerTouch, 1e-12)
        assertEquals(0.5, rates.longTdShare("rushing", 40), 1e-12)
        assertEquals(0.0, rates.longTdShare("rushing", 50), 1e-12)
        assertEquals(0.25, rates.rushFirstDownsPerCarry, 1e-12)
        assertEquals(0.8, rates.carryShare, 1e-12)
    }

    @Test
    fun `rates are a snapshot that later games don't move`() {
        val totals = LeagueTotals()
        totals.add("WR", game("W1", 1, "targets" to 10.0), team(1, 40.0), expectedCovered = true)
        val before = totals.rates("WR")!!
        totals.add("WR", game("W1", 2, "targets" to 30.0), team(2, 40.0), expectedCovered = true)
        assertEquals(0.25, before.targetShare, 1e-12)
    }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./gradlew :core:forecast:test`
Expected: FAIL to compile (`loadInputs`, `LeagueTotals` unresolved).

- [ ] **Step 4: Write the inputs**

`core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Inputs.kt`:

```kotlin
package dev.gridiron.core.forecast

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement

internal val POSITIONS: List<String> = listOf("QB", "RB", "WR", "TE")

/** Sorts weeks chronologically across seasons: 2025 week 18 comes before 2026 week 1. */
internal fun order(season: Int, week: Int): Int = season * 100 + week

/** One week a player played: every stat the model reads. Scoring inputs are stored sparse, so absent means zero. */
internal class PlayerGame(
    val playerId: String,
    val season: Int,
    val week: Int,
    val team: String,
    private val stats: Map<String, Double>,
) {
    val order: Int get() = order(season, week)

    operator fun get(metric: String): Double = stats[metric] ?: 0.0
}

internal data class PlayerInfo(val playerId: String, val name: String, val position: String, val team: String?)

/** A scheduled game. Lines, QBs and coaches are pre-game information, so any week may read any game. */
internal data class Game(
    val season: Int,
    val week: Int,
    val regular: Boolean,
    val home: String,
    val away: String,
    val played: Boolean,
    /** Points the home team is favored by (nflverse's spread_line); null until posted. */
    val spread: Double?,
    val total: Double?,
    val homeQb: String?,
    val awayQb: String?,
    val homeCoach: String?,
    val awayCoach: String?,
) {
    fun involves(team: String): Boolean = team == home || team == away

    fun opponentOf(team: String): String = if (team == home) away else home

    fun isHome(team: String): Boolean = team == home

    fun qbOf(team: String): String? = if (team == home) homeQb else awayQb

    fun coachOf(team: String): String? = if (team == home) homeCoach else awayCoach

    fun favoredBy(team: String): Double? = spread?.let { if (team == home) it else -it }

    fun impliedPoints(team: String): Double? {
        val favored = favoredBy(team) ?: return null
        val t = total ?: return null
        return t / 2 + favored / 2
    }
}

/** A team's week, summed from its QBs, RBs, WRs and TEs: team volume and the matchup ratings' rows. */
internal class TeamGame(val team: String, val season: Int, val week: Int) {
    var passAttempts = 0.0
    var targets = 0.0
    var carries = 0.0
    var passYards = 0.0
    var rushYards = 0.0
    var passTds = 0.0
    var rushTds = 0.0

    val order: Int get() = order(season, week)
}

internal class ForecastInputs(
    val players: Map<String, PlayerInfo>,
    /** Per player id, oldest first. */
    val history: Map<String, List<PlayerGame>>,
    /** Every scheduled game of the built seasons, in (season, week) order. */
    val games: List<Game>,
    val teamGames: Map<Triple<String, Int, Int>, TeamGame>,
    /** Last week with expected-points data, per season; a missing season has none. */
    val expectedThrough: Map<Int, Int>,
)

private val READ_METRICS = listOf(
    "g", "targets", "receptions", "receiving_yards", "receiving_tds", "carries", "rushing_yards", "rushing_tds",
    "attempts", "completions", "passing_yards", "passing_tds", "interceptions", "sacks_taken",
    "passing_first_downs", "rushing_first_downs", "receiving_first_downs",
    "passing_2pt", "rushing_2pt", "receiving_2pt", "fumbles_lost",
    "passing_tds_40", "passing_tds_50", "rushing_tds_40", "rushing_tds_50", "receiving_tds_40", "receiving_tds_50",
    "x_passing_tds", "x_rushing_tds", "x_receiving_tds",
)

internal fun loadInputs(conn: SQLiteConnection): ForecastInputs {
    val history = readHistory(conn)
    val teamGames = HashMap<Triple<String, Int, Int>, TeamGame>()
    for (g in history.values.flatten()) {
        val team = teamGames.getOrPut(Triple(g.team, g.season, g.week)) { TeamGame(g.team, g.season, g.week) }
        team.passAttempts += g["attempts"]
        team.targets += g["targets"]
        team.carries += g["carries"]
        team.passYards += g["passing_yards"]
        team.rushYards += g["rushing_yards"]
        team.passTds += g["passing_tds"]
        team.rushTds += g["rushing_tds"]
    }
    return ForecastInputs(readPlayers(conn), history, readGames(conn), teamGames, readExpectedThrough(conn))
}

private fun readPlayers(conn: SQLiteConnection): Map<String, PlayerInfo> =
    conn.prepare("SELECT player_id, full_name, position, team FROM player WHERE position IN ('QB', 'RB', 'WR', 'TE')").use { st ->
        buildMap {
            while (st.step()) put(st.getText(0), PlayerInfo(st.getText(0), st.getText(1), st.getText(2), st.textOrNull(3)))
        }
    }

private fun readHistory(conn: SQLiteConnection): Map<String, List<PlayerGame>> {
    val history = HashMap<String, MutableList<PlayerGame>>()
    val placeholders = READ_METRICS.joinToString(",") { "?" }
    conn.prepare(
        """SELECT s.player_id, s.season, s.week, s.team, s.metric_id, s.value FROM player_week_stat s
           JOIN player p ON p.player_id = s.player_id
           WHERE p.position IN ('QB', 'RB', 'WR', 'TE') AND s.team IS NOT NULL AND s.metric_id IN ($placeholders)
           ORDER BY s.player_id, s.season, s.week""",
    ).use { st ->
        READ_METRICS.forEachIndexed { i, m -> st.bindText(i + 1, m) }
        var key: Triple<String, Int, Int>? = null
        var team = ""
        var stats = HashMap<String, Double>()
        fun flush() {
            val k = key ?: return
            // A week counts only if the player recorded a play (g = 1).
            if ((stats["g"] ?: 0.0) > 0.0) history.getOrPut(k.first) { ArrayList() }.add(PlayerGame(k.first, k.second, k.third, team, stats))
        }
        while (st.step()) {
            val k = Triple(st.getText(0), st.getLong(1).toInt(), st.getLong(2).toInt())
            if (k != key) {
                flush()
                key = k
                team = st.getText(3)
                stats = HashMap()
            }
            stats[st.getText(4)] = st.getDouble(5)
        }
        flush()
    }
    return history
}

private fun readGames(conn: SQLiteConnection): List<Game> = conn.prepare(
    """SELECT season, week, game_type, home_team, away_team, home_score, away_score, spread_line, total_line,
       home_qb_id, away_qb_id, home_coach, away_coach FROM game ORDER BY season, week, game_id""",
).use { st ->
    buildList {
        while (st.step()) {
            add(
                Game(
                    season = st.getLong(0).toInt(),
                    week = st.getLong(1).toInt(),
                    regular = st.getText(2) == "REG",
                    home = st.getText(3),
                    away = st.getText(4),
                    played = !st.isNull(5) && !st.isNull(6),
                    spread = st.doubleOrNull(7),
                    total = st.doubleOrNull(8),
                    homeQb = st.textOrNull(9),
                    awayQb = st.textOrNull(10),
                    homeCoach = st.textOrNull(11),
                    awayCoach = st.textOrNull(12),
                ),
            )
        }
    }
}

private fun readExpectedThrough(conn: SQLiteConnection): Map<Int, Int> =
    conn.prepare("SELECT key, value FROM schema_meta WHERE key LIKE 'expected_through_week:%'").use { st ->
        buildMap {
            while (st.step()) {
                val season = st.getText(0).substringAfter(':').toIntOrNull()
                val week = st.getText(1).toIntOrNull()
                if (season != null && week != null) put(season, week)
            }
        }
    }

private fun SQLiteStatement.textOrNull(index: Int): String? = if (isNull(index)) null else getText(index)

private fun SQLiteStatement.doubleOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)
```

- [ ] **Step 5: Write the league rates**

`core/forecast/src/main/kotlin/dev/gridiron/core/forecast/League.kt`:

```kotlin
package dev.gridiron.core.forecast

/** Stats summed straight across games at a position. */
private val SUMMED = listOf(
    "targets", "receptions", "receiving_yards", "receiving_tds", "receiving_tds_40", "receiving_tds_50",
    "carries", "rushing_yards", "rushing_tds", "rushing_tds_40", "rushing_tds_50",
    "attempts", "completions", "passing_yards", "passing_tds", "passing_tds_40", "passing_tds_50",
    "interceptions", "sacks_taken", "passing_first_downs", "rushing_first_downs", "receiving_first_downs",
    "passing_2pt", "rushing_2pt", "receiving_2pt", "fumbles_lost",
)

/**
 * Running league sums by position, over every game before the week being
 * projected: the shrinkage baselines and the rare-event rates. The projector
 * adds games in chronological order and takes a [rates] snapshot per week.
 */
internal class LeagueTotals {
    private val byPosition = HashMap<String, HashMap<String, Double>>()

    /** [expectedCovered]: whether ffopportunity had processed this week, so its expected TDs are real zeros, not missing. */
    fun add(position: String, g: PlayerGame, team: TeamGame, expectedCovered: Boolean) {
        val sums = byPosition.getOrPut(position) { HashMap() }
        fun plus(key: String, value: Double) {
            sums[key] = (sums[key] ?: 0.0) + value
        }
        plus("team_targets", team.targets)
        plus("team_carries", team.carries)
        plus("team_attempts", team.passAttempts)
        for (metric in SUMMED) plus(metric, g[metric])
        plus("touches", g["carries"] + g["receptions"] + g["attempts"])
        if (expectedCovered) {
            plus("x_rec_td", g["x_receiving_tds"])
            plus("x_rec_td_targets", g["targets"])
            plus("x_rush_td", g["x_rushing_tds"])
            plus("x_rush_td_carries", g["carries"])
            plus("x_pass_td", g["x_passing_tds"])
            plus("x_pass_td_attempts", g["attempts"])
        }
    }

    fun rates(position: String): Rates? = byPosition[position]?.let { Rates(HashMap(it)) }
}

/** Positional baselines and rare-event rates from a snapshot of [LeagueTotals]. Zero where there's no denominator. */
internal class Rates(private val sums: Map<String, Double>) {
    private fun ratio(numerator: String, denominator: String): Double {
        val d = sums[denominator] ?: 0.0
        return if (d > 0.0) (sums[numerator] ?: 0.0) / d else 0.0
    }

    /** Expected TDs per opportunity from covered weeks; actual TDs when ffopportunity covered none. */
    private fun expectedOr(x: String, xDenominator: String, actual: String, denominator: String): Double =
        if ((sums[xDenominator] ?: 0.0) > 0.0) ratio(x, xDenominator) else ratio(actual, denominator)

    val targetShare: Double = ratio("targets", "team_targets")
    val carryShare: Double = ratio("carries", "team_carries")
    val passShare: Double = ratio("attempts", "team_attempts")
    val catchRate: Double = ratio("receptions", "targets")
    val yardsPerTarget: Double = ratio("receiving_yards", "targets")
    val yardsPerCarry: Double = ratio("rushing_yards", "carries")
    val completionRate: Double = ratio("completions", "attempts")
    val yardsPerAttempt: Double = ratio("passing_yards", "attempts")
    val interceptionRate: Double = ratio("interceptions", "attempts")
    val sackRate: Double = ratio("sacks_taken", "attempts")
    val xReceivingTdPerTarget: Double = expectedOr("x_rec_td", "x_rec_td_targets", "receiving_tds", "targets")
    val xRushingTdPerCarry: Double = expectedOr("x_rush_td", "x_rush_td_carries", "rushing_tds", "carries")
    val xPassingTdPerAttempt: Double = expectedOr("x_pass_td", "x_pass_td_attempts", "passing_tds", "attempts")
    val recFirstDownsPerReception: Double = ratio("receiving_first_downs", "receptions")
    val rushFirstDownsPerCarry: Double = ratio("rushing_first_downs", "carries")
    val passFirstDownsPerCompletion: Double = ratio("passing_first_downs", "completions")
    val rec2ptPerTarget: Double = ratio("receiving_2pt", "targets")
    val rush2ptPerCarry: Double = ratio("rushing_2pt", "carries")
    val pass2ptPerAttempt: Double = ratio("passing_2pt", "attempts")
    val fumblesPerTouch: Double = ratio("fumbles_lost", "touches")

    /** Share of [kind] ("passing", "rushing", "receiving") TDs that went for at least [yards] (40 or 50). */
    fun longTdShare(kind: String, yards: Int): Double = ratio("${kind}_tds_$yards", "${kind}_tds")
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :core:forecast:test`
Expected: PASS, 17 tests.

- [ ] **Step 7: Commit**

```bash
git add core/forecast
git commit -m "forecast: load inputs from stats.db and pool league rates by position"
```

---
### Task 5: Baseline model (layers 1–4)

**Files:**
- Create: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Baseline.kt`
- Test: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/BaselineModelTest.kt`

**Interfaces:**
- Consumes: `ewma`, `ewmaRatio`, `shrink`, `carryoverWeight`, `K` (Task 1); `PlayerGame`, `TeamGame`, `Rates` (Task 4).
- Produces (internal):
  - `data class TeamVolume(passAttempts: Double, targets: Double, carries: Double)` with `passRate: Double`
  - `fun teamVolume(games: List<TeamGame>, fallback: TeamVolume): TeamVolume`
  - `class PlayerContext(position: String, season: Int, week: Int, history: List<PlayerGame>, regimeBreak: Boolean)`
  - `class BaselineModel(teamGames: Map<Triple<String, Int, Int>, TeamGame>, expectedThrough: Map<Int, Int>)` with `project(ctx, rates, volume): Map<String, Double>` (keys are metric ids: the rushing set for everyone, the passing set for QBs, the receiving set for everyone else, plus `fumbles_lost`) and `share(...)` (internal, for tests)

- [ ] **Step 1: Write the failing tests**

`core/forecast/src/test/kotlin/dev/gridiron/core/forecast/BaselineModelTest.kt`:

```kotlin
package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.pow

class BaselineModelTest {
    private val teamGames = HashMap<Triple<String, Int, Int>, TeamGame>()

    private fun team(season: Int, week: Int, targets: Double = 30.0, carries: Double = 25.0, attempts: Double = 32.0): TeamGame =
        TeamGame("AAA", season, week).apply {
            this.targets = targets
            this.carries = carries
            passAttempts = attempts
        }.also { teamGames[Triple("AAA", season, week)] = it }

    private fun game(season: Int, week: Int, vararg values: Pair<String, Double>): PlayerGame {
        team(season, week)
        return PlayerGame("P1", season, week, "AAA", mapOf("g" to 1.0) + values)
    }

    // Target share 0.1, catch rate 0.6, 8 yards per target, carry share 0.01, 0.05 expected TDs per target.
    private val rates = Rates(
        mapOf(
            "targets" to 10.0, "team_targets" to 100.0, "receptions" to 6.0, "receiving_yards" to 80.0,
            "carries" to 1.0, "team_carries" to 100.0, "x_rec_td" to 0.5, "x_rec_td_targets" to 10.0,
        ),
    )
    private val volume = TeamVolume(passAttempts = 32.0, targets = 30.0, carries = 25.0)

    private fun model(expectedThrough: Map<Int, Int> = emptyMap()) = BaselineModel(teamGames, expectedThrough)

    private fun targetShare(ctx: PlayerContext): Double = model().share(ctx, rates.targetShare, { it["targets"] }, { it.targets })

    @Test
    fun `two games at a 30 percent share are shrunk toward the position's 10`() {
        val history = listOf(game(2025, 1, "targets" to 9.0), game(2025, 2, "targets" to 9.0))
        val ctx = PlayerContext("WR", 2025, 3, history, regimeBreak = false)

        assertEquals(1.1 / 7, targetShare(ctx), 1e-12) // (2 x 0.3 + 5 x 0.1) / (2 + 5)
        assertEquals(30 * 1.1 / 7, model().project(ctx, rates, volume).getValue("targets"), 1e-12)
    }

    @Test
    fun `last season carries 0_44 of the weight in week 2 unless the regime broke`() {
        val history = listOf(
            game(2024, 16, "targets" to 7.5),
            game(2024, 17, "targets" to 7.5),
            game(2025, 1, "targets" to 9.0),
        )
        val shrunk = (0.3 + 5 * 0.1) / 6

        assertEquals(0.44 * 0.25 + 0.56 * shrunk, targetShare(PlayerContext("WR", 2025, 2, history, regimeBreak = false)), 1e-12)
        assertEquals(shrunk, targetShare(PlayerContext("WR", 2025, 2, history, regimeBreak = true)), 1e-12)
        assertEquals(shrunk, targetShare(PlayerContext("WR", 2025, 6, history, regimeBreak = false)), 1e-12)
    }

    @Test
    fun `a week ffopportunity hasn't processed doesn't count as a week without expected TDs`() {
        val history = listOf(
            game(2025, 1, "targets" to 10.0, "x_receiving_tds" to 1.0),
            game(2025, 2, "targets" to 10.0), // expected TDs for week 2 aren't published yet
        )
        val ctx = PlayerContext("WR", 2025, 3, history, regimeBreak = false)

        val out = model(expectedThrough = mapOf(2025 to 1)).project(ctx, rates, volume)

        // Only week 1 counts: 1.0 expected TD on 10 targets, shrunk with k = 200 toward 0.05.
        assertEquals((10 * 0.1 + 200 * 0.05) / 210, out.getValue("receiving_tds") / out.getValue("targets"), 1e-12)
    }

    @Test
    fun `a player with no history gets the position's rates`() {
        val out = model().project(PlayerContext("WR", 2025, 3, emptyList(), regimeBreak = false), rates, volume)

        assertEquals(3.0, out.getValue("targets"), 1e-12)
        assertEquals(1.8, out.getValue("receptions"), 1e-12)
        assertEquals(24.0, out.getValue("receiving_yards"), 1e-12)
        assertEquals(0.25, out.getValue("carries"), 1e-12)
    }

    @Test
    fun `quarterbacks get passing stats, everyone else receiving stats, and all get rushing`() {
        val qb = model().project(PlayerContext("QB", 2025, 3, emptyList(), regimeBreak = false), rates, volume)
        val wr = model().project(PlayerContext("WR", 2025, 3, emptyList(), regimeBreak = false), rates, volume)

        assertTrue("attempts" in qb && "passing_yards" in qb && "carries" in qb)
        assertFalse("targets" in qb)
        assertTrue("targets" in wr && "carries" in wr)
        assertFalse("attempts" in wr)
        assertTrue(qb.keys.containsAll(listOf("interceptions", "sacks_taken", "passing_first_downs", "passing_2pt", "fumbles_lost")))
    }

    @Test
    fun `team volume is a 4-game EWMA, or the league's average team without games`() {
        val fallback = TeamVolume(30.0, 28.0, 26.0)
        val games = listOf(team(2025, 1, targets = 30.0), team(2025, 2, targets = 40.0))

        assertEquals(30 + (1 - 0.5.pow(0.25)) * 10, teamVolume(games, fallback).targets, 1e-12)
        assertEquals(fallback, teamVolume(emptyList(), fallback))
        assertEquals(32.0 / 57, volume.passRate, 1e-12)
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:forecast:test --tests "dev.gridiron.core.forecast.BaselineModelTest"`
Expected: FAIL to compile (`BaselineModel` unresolved).

- [ ] **Step 3: Write the baseline model**

`core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Baseline.kt`:

```kotlin
package dev.gridiron.core.forecast

/** A team's plays per game, recency-weighted. */
internal data class TeamVolume(val passAttempts: Double, val targets: Double, val carries: Double) {
    /** Share of plays that are passes, before game script. */
    val passRate: Double get() = if (passAttempts + carries > 0.0) passAttempts / (passAttempts + carries) else 0.5
}

/** Layer 1: an EWMA (half-life 4) of the team's earlier games, or [fallback], the league's average team, without any. */
internal fun teamVolume(games: List<TeamGame>, fallback: TeamVolume): TeamVolume {
    if (games.isEmpty()) return fallback
    return TeamVolume(
        passAttempts = ewma(games.map { it.passAttempts }, K.TEAM_HALF_LIFE)!!,
        targets = ewma(games.map { it.targets }, K.TEAM_HALF_LIFE)!!,
        carries = ewma(games.map { it.carries }, K.TEAM_HALF_LIFE)!!,
    )
}

/** What the model knows about one player for one week. */
internal class PlayerContext(
    val position: String,
    val season: Int,
    val week: Int,
    /** The player's games before this week, oldest first. */
    val history: List<PlayerGame>,
    /** Last season shouldn't count: a new team, a new head coach, or (pass catchers) a new starting QB. */
    val regimeBreak: Boolean,
)

/**
 * Layers 1-4 of the spec's model: team volume times the player's share,
 * times shrunk efficiency, with touchdowns from expected TDs and rare events
 * at league rates. Its output is the "baseline" stage, before matchup and
 * game script.
 */
internal class BaselineModel(
    private val teamGames: Map<Triple<String, Int, Int>, TeamGame>,
    private val expectedThrough: Map<Int, Int>,
) {
    fun project(ctx: PlayerContext, rates: Rates, volume: TeamVolume): Map<String, Double> {
        val h = ctx.history
        val out = LinkedHashMap<String, Double>()

        val carries = volume.carries * share(ctx, rates.carryShare, { it["carries"] }, { it.carries })
        val rushTds = carries * tdRate(h, "x_rushing_tds", "carries", rates.xRushingTdPerCarry)
        out["carries"] = carries
        out["rushing_yards"] = carries * efficiency(h, "rushing_yards", "carries", rates.yardsPerCarry)
        out["rushing_tds"] = rushTds
        out["rushing_tds_40"] = rushTds * rates.longTdShare("rushing", 40)
        out["rushing_tds_50"] = rushTds * rates.longTdShare("rushing", 50)
        out["rushing_first_downs"] = carries * rates.rushFirstDownsPerCarry
        out["rushing_2pt"] = carries * rates.rush2ptPerCarry

        var receptions = 0.0
        var attempts = 0.0
        if (ctx.position == "QB") {
            attempts = volume.passAttempts * share(ctx, rates.passShare, { it["attempts"] }, { it.passAttempts })
            val completions = attempts * efficiency(h, "completions", "attempts", rates.completionRate)
            val passTds = attempts * tdRate(h, "x_passing_tds", "attempts", rates.xPassingTdPerAttempt)
            out["attempts"] = attempts
            out["completions"] = completions
            out["passing_yards"] = attempts * efficiency(h, "passing_yards", "attempts", rates.yardsPerAttempt)
            out["passing_tds"] = passTds
            out["passing_tds_40"] = passTds * rates.longTdShare("passing", 40)
            out["passing_tds_50"] = passTds * rates.longTdShare("passing", 50)
            out["interceptions"] = attempts * interceptionRate(h, rates.interceptionRate)
            out["sacks_taken"] = attempts * rates.sackRate
            out["passing_first_downs"] = completions * rates.passFirstDownsPerCompletion
            out["passing_2pt"] = attempts * rates.pass2ptPerAttempt
        } else {
            val targets = volume.targets * share(ctx, rates.targetShare, { it["targets"] }, { it.targets })
            receptions = targets * efficiency(h, "receptions", "targets", rates.catchRate)
            val recTds = targets * tdRate(h, "x_receiving_tds", "targets", rates.xReceivingTdPerTarget)
            out["targets"] = targets
            out["receptions"] = receptions
            out["receiving_yards"] = targets * efficiency(h, "receiving_yards", "targets", rates.yardsPerTarget)
            out["receiving_tds"] = recTds
            out["receiving_tds_40"] = recTds * rates.longTdShare("receiving", 40)
            out["receiving_tds_50"] = recTds * rates.longTdShare("receiving", 50)
            out["receiving_first_downs"] = receptions * rates.recFirstDownsPerReception
            out["receiving_2pt"] = targets * rates.rec2ptPerTarget
        }
        out["fumbles_lost"] = (carries + receptions + attempts) * rates.fumblesPerTouch
        return out
    }

    /**
     * Layer 2: this season's recency-weighted share, shrunk toward the
     * position's, blended with last season's final share early in the season
     * unless the regime broke.
     */
    internal fun share(ctx: PlayerContext, baseline: Double, part: (PlayerGame) -> Double, whole: (TeamGame) -> Double): Double {
        fun series(games: List<PlayerGame>): List<Double> = games.mapNotNull { g ->
            val team = whole(teamGames.getValue(Triple(g.team, g.season, g.week)))
            if (team > 0.0) part(g) / team else null
        }
        val current = series(ctx.history.filter { it.season == ctx.season })
        val shrunk = shrink(ewma(current, K.SHARE_HALF_LIFE), current.size.toDouble(), baseline, K.SHARE_K_GAMES)
        val prior = if (ctx.regimeBreak) null else ewma(series(ctx.history.filter { it.season == ctx.season - 1 }), K.SHARE_HALF_LIFE)
        val w = if (prior == null) 0.0 else carryoverWeight(ctx.week)
        return w * (prior ?: 0.0) + (1 - w) * shrunk
    }

    /** Layer 3: a rate over every earlier game (half-life 10), shrunk toward the position's with k = 15 games. */
    private fun efficiency(games: List<PlayerGame>, numerator: String, denominator: String, baseline: Double): Double {
        val used = games.filter { it[denominator] > 0.0 }
        val observed = ewmaRatio(used.map { it[numerator] }, used.map { it[denominator] }, K.EFFICIENCY_HALF_LIFE)
        return shrink(observed, used.size.toDouble(), baseline, K.EFFICIENCY_K_GAMES)
    }

    /** Interceptions are rarer still: k counts attempts (150), not games. */
    private fun interceptionRate(games: List<PlayerGame>, baseline: Double): Double {
        val used = games.filter { it["attempts"] > 0.0 }
        val observed = ewmaRatio(used.map { it["interceptions"] }, used.map { it["attempts"] }, K.EFFICIENCY_HALF_LIFE)
        return shrink(observed, used.sumOf { it["attempts"] }, baseline, K.INT_K_ATTEMPTS)
    }

    /**
     * Layer 4: expected TDs per opportunity from the weeks ffopportunity has
     * processed (a lagging week's expected TDs are absent, not zero), shrunk
     * hard toward the position's (k = 200 opportunities).
     */
    private fun tdRate(games: List<PlayerGame>, expected: String, denominator: String, baseline: Double): Double {
        val covered = games.filter { it[denominator] > 0.0 && it.week <= (expectedThrough[it.season] ?: 0) }
        val observed = ewmaRatio(covered.map { it[expected] }, covered.map { it[denominator] }, K.EFFICIENCY_HALF_LIFE)
        return shrink(observed, covered.sumOf { it[denominator] }, baseline, K.TD_K_OPPORTUNITIES)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:forecast:test`
Expected: PASS, 23 tests.

- [ ] **Step 5: Commit**

```bash
git add core/forecast
git commit -m "forecast: baseline model (volume, shrunk share and efficiency, expected TDs)"
```

---
### Task 6: Matchup and game script (layers 5–6)

**Files:**
- Create: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Kinds.kt`
- Create: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Matchup.kt`
- Create: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/GameScript.kt`
- Test: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/KindsTest.kt`
- Test: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/MatchupTest.kt`
- Test: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/GameScriptTest.kt`

**Interfaces:**
- Consumes: `fitRidge`, `RidgeRow`, `RidgeFit`, `capAround`, `K` (Task 1); `TeamGame`, `Game` (Task 4); `BaselineModel`, `PlayerContext`, `TeamVolume`, `Rates` (Tasks 4–5, in a test).
- Produces (internal):
  - `enum class Side { PASS, RUSH, TOUCH }`, `enum class StatType { VOLUME, COUNT, YARDS, TD }`, `val KINDS: Map<String, Pair<Side, StatType>>`
  - `fun adjust(components: Map<String, Double>, multiplier: (Side, StatType) -> Double): Map<String, Double>`
  - `fun referencePoints(components: Map<String, Double>): Double`
  - `enum class Outcome { PASS_YARDS, RUSH_YARDS, PASS_TD, RUSH_TD, PLAYS }`
  - `class MatchupGame(game: TeamGame, opponent: String, home: Boolean)`
  - `class MatchupModel` with `companion fun fit(games: List<MatchupGame>): MatchupModel`, `multiplier(outcome, opponent, home)`, `multiplier(side, type, opponent, home)`, `note(position, opponent): String`
  - `class GameScript` with `multiplier(side, type)`, `note: String`, `passAdjust`, `rushAdjust`; `fun gameScript(game: Game, team: String, leagueImplied: Double, passRate: Double): GameScript?`
  - `fun ordinal(n: Int): String`

- [ ] **Step 1: Write the failing tests**

`core/forecast/src/test/kotlin/dev/gridiron/core/forecast/KindsTest.kt`:

```kotlin
package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KindsTest {
    @Test
    fun `every stat the baseline model produces has a kind`() {
        val model = BaselineModel(emptyMap(), emptyMap())
        val rates = Rates(emptyMap())
        val volume = TeamVolume(32.0, 30.0, 25.0)
        for (position in POSITIONS) {
            val out = model.project(PlayerContext(position, 2025, 3, emptyList(), regimeBreak = false), rates, volume)
            assertTrue(KINDS.keys.containsAll(out.keys), "$position: ${out.keys - KINDS.keys}")
        }
    }

    @Test
    fun `adjust multiplies each stat by its kind's multiplier`() {
        val out = adjust(mapOf("targets" to 10.0, "receiving_yards" to 80.0, "rushing_tds" to 0.5)) { side, type ->
            when {
                type == StatType.YARDS -> 1.25
                side == Side.RUSH -> 2.0
                else -> 1.0
            }
        }
        assertEquals(mapOf("targets" to 10.0, "receiving_yards" to 100.0, "rushing_tds" to 1.0), out)
    }

    @Test
    fun `reference points are full PPR`() {
        val points = referencePoints(
            mapOf("receptions" to 5.0, "receiving_yards" to 60.0, "receiving_tds" to 0.5, "fumbles_lost" to 0.1, "passing_yards" to 250.0),
        )
        assertEquals(5 + 6 + 3 - 0.2 + 10, points, 1e-12)
    }

    @Test
    fun `ordinals`() {
        assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "32nd"), listOf(1, 2, 3, 4, 11, 12, 13, 21, 32).map(::ordinal))
    }
}
```

`core/forecast/src/test/kotlin/dev/gridiron/core/forecast/MatchupTest.kt`:

```kotlin
package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MatchupTest {
    /** A double round robin of four teams (24 team-games): every offense gains 7 yards per pass, except against DDD. */
    private fun league(dddAllows: Double): List<MatchupGame> {
        val pairs = listOf("AAA" to "BBB", "CCC" to "DDD", "AAA" to "CCC", "BBB" to "DDD", "AAA" to "DDD", "BBB" to "CCC")
        return (0 until 2).flatMap { round ->
            pairs.withIndex().flatMap { (i, pair) ->
                val week = round * 3 + i / 2 + 1
                val (home, away) = if (round == 0) pair else pair.second to pair.first
                listOf(side(home, away, week, true, dddAllows), side(away, home, week, false, dddAllows))
            }
        }
    }

    private fun side(team: String, opponent: String, week: Int, home: Boolean, dddAllows: Double): MatchupGame {
        val ypa = if (opponent == "DDD") dddAllows else 7.0
        val game = TeamGame(team, 2025, week).apply {
            passAttempts = 30.0
            passYards = 30.0 * ypa
            passTds = 1.5
            carries = 25.0
            rushYards = 100.0
            rushTds = 0.5
        }
        return MatchupGame(game, opponent, home)
    }

    @Test
    fun `a defense that allows more lifts the multiplier, within the cap`() {
        val model = MatchupModel.fit(league(dddAllows = 8.4))

        val vsDdd = model.multiplier(Outcome.PASS_YARDS, "DDD", home = false)
        assertTrue(vsDdd > 1.0 && vsDdd < 1.15, "$vsDdd")
        assertTrue(model.multiplier(Outcome.PASS_YARDS, "AAA", home = false) < 1.0)
        // Every team ran for 4 yards a carry: nothing to adjust.
        assertEquals(1.0, model.multiplier(Outcome.RUSH_YARDS, "DDD", home = false), 1e-9)
        assertEquals("vs DDD: 1st-most pass yards per attempt allowed", model.note("WR", "DDD"))
    }

    @Test
    fun `a stat's multiplier is plays times its efficiency or TD rating`() {
        val model = MatchupModel.fit(league(dddAllows = 8.4))
        val plays = model.multiplier(Outcome.PLAYS, "DDD", home = false)

        assertEquals(plays * model.multiplier(Outcome.PASS_YARDS, "DDD", false), model.multiplier(Side.PASS, StatType.YARDS, "DDD", false), 1e-12)
        assertEquals(plays * model.multiplier(Outcome.RUSH_TD, "DDD", false), model.multiplier(Side.RUSH, StatType.TD, "DDD", false), 1e-12)
        assertEquals(plays, model.multiplier(Side.TOUCH, StatType.COUNT, "DDD", false), 1e-12)
    }

    @Test
    fun `an extreme defense is capped at 15 percent`() {
        val model = MatchupModel.fit(league(dddAllows = 30.0))
        assertEquals(1.15, model.multiplier(Outcome.PASS_YARDS, "DDD", home = false), 1e-12)
    }

    @Test
    fun `too few games means no adjustment`() {
        val model = MatchupModel.fit(league(dddAllows = 8.4).take(12))
        assertEquals(1.0, model.multiplier(Outcome.PASS_YARDS, "DDD", home = false))
        assertEquals("vs DDD: too early to rate defenses", model.note("WR", "DDD"))
    }

    @Test
    fun `an unrated opponent is neutral`() {
        val model = MatchupModel.fit(league(dddAllows = 8.4))
        assertEquals(1.0, model.multiplier(Outcome.PASS_YARDS, "EEE", home = true))
        assertEquals("vs EEE: not rated yet", model.note("WR", "EEE"))
    }
}
```

`core/forecast/src/test/kotlin/dev/gridiron/core/forecast/GameScriptTest.kt`:

```kotlin
package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.pow

class GameScriptTest {
    // AAA at home, favored by 3, total 44: AAA is implied for 23.5, BBB for 20.5.
    private val game = Game(2025, 3, true, "AAA", "BBB", false, 3.0, 44.0, null, null, null, null)

    @Test
    fun `the favorite scores more and passes a little less`() {
        val script = gameScript(game, "AAA", leagueImplied = 22.0, passRate = 0.6)!!
        val ratio = 23.5 / 22

        assertEquals(0.97, script.passAdjust, 1e-12) // (0.6 - 3 x 0.006) / 0.6
        assertEquals(1.045, script.rushAdjust, 1e-12) // 0.418 / 0.4
        assertEquals(ratio * 0.97, script.multiplier(Side.PASS, StatType.TD), 1e-12)
        assertEquals(ratio.pow(0.5) * 1.045, script.multiplier(Side.RUSH, StatType.YARDS), 1e-12)
        assertEquals(ratio.pow(0.25), script.multiplier(Side.TOUCH, StatType.COUNT), 1e-12)
        assertEquals("Implied 23.5 pts (+1.5)", script.note)
    }

    @Test
    fun `the underdog passes more`() {
        val script = gameScript(game, "BBB", leagueImplied = 22.0, passRate = 0.6)!!
        assertEquals(1.03, script.passAdjust, 1e-12)
        assertEquals("Implied 20.5 pts (-1.5)", script.note)
    }

    @Test
    fun `no line posted means no game script`() {
        assertNull(gameScript(game.copy(spread = null), "AAA", 22.0, 0.6))
        assertNull(gameScript(game.copy(total = null), "AAA", 22.0, 0.6))
    }

    @Test
    fun `an extreme total is capped at one and a half times the league's`() {
        val script = gameScript(game.copy(total = 120.0, spread = 0.0), "AAA", leagueImplied = 22.0, passRate = 0.6)!!
        assertEquals(1.5, script.multiplier(Side.TOUCH, StatType.TD), 1e-12)
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:forecast:test`
Expected: FAIL to compile (`KINDS`, `MatchupModel`, `gameScript` unresolved).

- [ ] **Step 3: Write the stat kinds**

`core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Kinds.kt`:

```kotlin
package dev.gridiron.core.forecast

/** Which part of the offense a stat comes from: game script moves passing and rushing apart. */
internal enum class Side { PASS, RUSH, TOUCH }

/** How a stat responds to an adjustment: TDs most, yards less, opportunities and counts least. */
internal enum class StatType { VOLUME, COUNT, YARDS, TD }

internal val KINDS: Map<String, Pair<Side, StatType>> = buildMap {
    fun kind(side: Side, type: StatType, vararg ids: String) = ids.forEach { put(it, side to type) }
    kind(Side.PASS, StatType.VOLUME, "attempts", "targets")
    kind(Side.PASS, StatType.COUNT, "completions", "interceptions", "sacks_taken", "passing_first_downs", "receptions", "receiving_first_downs")
    kind(Side.PASS, StatType.YARDS, "passing_yards", "receiving_yards")
    kind(
        Side.PASS, StatType.TD,
        "passing_tds", "passing_tds_40", "passing_tds_50", "passing_2pt",
        "receiving_tds", "receiving_tds_40", "receiving_tds_50", "receiving_2pt",
    )
    kind(Side.RUSH, StatType.VOLUME, "carries")
    kind(Side.RUSH, StatType.COUNT, "rushing_first_downs")
    kind(Side.RUSH, StatType.YARDS, "rushing_yards")
    kind(Side.RUSH, StatType.TD, "rushing_tds", "rushing_tds_40", "rushing_tds_50", "rushing_2pt")
    kind(Side.TOUCH, StatType.COUNT, "fumbles_lost")
}

/** Multiplies each stat by what [multiplier] gives its kind. */
internal fun adjust(components: Map<String, Double>, multiplier: (Side, StatType) -> Double): Map<String, Double> =
    components.mapValues { (id, value) ->
        val (side, type) = KINDS.getValue(id)
        value * multiplier(side, type)
    }

/**
 * Fixed full-PPR points, used only to split a projection's change between
 * matchup and game script and to decide which rows are worth storing. The app
 * scores every projection with the user's own profile.
 */
internal fun referencePoints(components: Map<String, Double>): Double {
    fun v(id: String) = components[id] ?: 0.0
    return 0.04 * v("passing_yards") + 4 * v("passing_tds") - 2 * v("interceptions") +
        0.1 * v("rushing_yards") + 6 * v("rushing_tds") +
        v("receptions") + 0.1 * v("receiving_yards") + 6 * v("receiving_tds") +
        2 * (v("passing_2pt") + v("rushing_2pt") + v("receiving_2pt")) - 2 * v("fumbles_lost")
}

internal fun ordinal(n: Int): String {
    val suffix = if (n % 100 in 11..13) {
        "th"
    } else {
        when (n % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
    }
    return "$n$suffix"
}
```

- [ ] **Step 4: Write the matchup model**

`core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Matchup.kt`:

```kotlin
package dev.gridiron.core.forecast

/** What a defense is rated on, from the offense's side of each team-game. */
internal enum class Outcome(val label: String) {
    PASS_YARDS("pass yards per attempt"),
    RUSH_YARDS("rush yards per carry"),
    PASS_TD("pass TDs per attempt"),
    RUSH_TD("rush TDs per carry"),
    PLAYS("plays"),
    ;

    /** This outcome for one team-game, or null without a denominator. */
    fun of(g: TeamGame): Double? = when (this) {
        PASS_YARDS -> if (g.passAttempts > 0.0) g.passYards / g.passAttempts else null
        RUSH_YARDS -> if (g.carries > 0.0) g.rushYards / g.carries else null
        PASS_TD -> if (g.passAttempts > 0.0) g.passTds / g.passAttempts else null
        RUSH_TD -> if (g.carries > 0.0) g.rushTds / g.carries else null
        PLAYS -> g.passAttempts + g.carries
    }

    val cap: Double
        get() = when (this) {
            PASS_YARDS, RUSH_YARDS -> K.CAP_EFFICIENCY
            PASS_TD, RUSH_TD -> K.CAP_TD
            PLAYS -> K.CAP_VOLUME
        }
}

/** One team-game and whom it was against. */
internal class MatchupGame(val game: TeamGame, val opponent: String, val home: Boolean)

/**
 * Layer 5: opponent-adjusted defense ratings, one ridge fit per [Outcome] on
 * the season's earlier games, shrunk again by games rated so weeks 2-3 sit
 * near league average, and turned into capped multipliers. It adjusts only
 * for the opponent: the player's own offense is already in his rates.
 */
internal class MatchupModel private constructor(private val fits: Map<Outcome, RidgeFit>) {

    fun multiplier(outcome: Outcome, opponent: String, home: Boolean): Double {
        val fit = fits[outcome] ?: return 1.0
        if (fit.mean <= 0.0) return 1.0
        val rating = fit.defense[opponent] ?: return 1.0
        val n = (fit.defenseGames[opponent] ?: 0).toDouble()
        val effect = rating * n / (n + K.MATCHUP_K_GAMES) + fit.home * (if (home) 0.5 else -0.5)
        return ((fit.mean + effect) / fit.mean).capAround(outcome.cap)
    }

    /** A stat's multiplier: plays for every stat, times the efficiency rating for yards or the TD rating for TDs. */
    fun multiplier(side: Side, type: StatType, opponent: String, home: Boolean): Double {
        val volume = multiplier(Outcome.PLAYS, opponent, home)
        val extra = when (type) {
            StatType.YARDS -> multiplier(if (side == Side.RUSH) Outcome.RUSH_YARDS else Outcome.PASS_YARDS, opponent, home)
            StatType.TD -> multiplier(if (side == Side.RUSH) Outcome.RUSH_TD else Outcome.PASS_TD, opponent, home)
            StatType.VOLUME, StatType.COUNT -> 1.0
        }
        return volume * extra
    }

    /** "vs DAL: 3rd-most pass yards per attempt allowed", from the rating that matters most to [position]. */
    fun note(position: String, opponent: String): String {
        val outcome = if (position == "RB") Outcome.RUSH_YARDS else Outcome.PASS_YARDS
        val fit = fits[outcome] ?: return "vs $opponent: too early to rate defenses"
        val ranked = fit.defense.entries.sortedByDescending { it.value }.map { it.key }
        val rank = ranked.indexOf(opponent) + 1
        if (rank == 0) return "vs $opponent: not rated yet"
        return if (rank <= (ranked.size + 1) / 2) {
            "vs $opponent: ${ordinal(rank)}-most ${outcome.label} allowed"
        } else {
            "vs $opponent: ${ordinal(ranked.size - rank + 1)}-fewest ${outcome.label} allowed"
        }
    }

    companion object {
        fun fit(games: List<MatchupGame>): MatchupModel {
            val fits = Outcome.entries.mapNotNull { outcome ->
                val rows = games.mapNotNull { m -> outcome.of(m.game)?.let { RidgeRow(m.game.team, m.opponent, m.home, it) } }
                if (rows.size < K.MIN_MATCHUP_ROWS) null else outcome to fitRidge(rows)
            }.toMap()
            return MatchupModel(fits)
        }
    }
}
```

- [ ] **Step 5: Write the game script**

`core/forecast/src/main/kotlin/dev/gridiron/core/forecast/GameScript.kt`:

```kotlin
package dev.gridiron.core.forecast

import java.util.Locale
import kotlin.math.pow

/** Layer 6 for one team's game: how its line moves each kind of stat. */
internal class GameScript(
    val implied: Double,
    val leagueImplied: Double,
    /** Implied points over the league's average, capped to [K.IMPLIED_RATIO_MIN]..[K.IMPLIED_RATIO_MAX]. */
    private val ratio: Double,
    val passAdjust: Double,
    val rushAdjust: Double,
) {
    fun multiplier(side: Side, type: StatType): Double {
        val sideAdjust = when (side) {
            Side.PASS -> passAdjust
            Side.RUSH -> rushAdjust
            Side.TOUCH -> 1.0
        }
        val elasticity = when (type) {
            StatType.TD -> K.ELASTICITY_TD
            StatType.YARDS -> K.ELASTICITY_YARDS
            StatType.VOLUME, StatType.COUNT -> K.ELASTICITY_ATTEMPTS
        }
        return ratio.pow(elasticity) * sideAdjust
    }

    /** "Implied 27.5 pts (+4.3)": the team's implied points and how far they are from the league's average. */
    val note: String get() = String.format(Locale.US, "Implied %.1f pts (%+.1f)", implied, implied - leagueImplied)
}

/**
 * [team]'s game script in [game], or null while the line isn't posted.
 * [passRate] is the team's usual share of pass plays; underdogs pass more.
 */
internal fun gameScript(game: Game, team: String, leagueImplied: Double, passRate: Double): GameScript? {
    val implied = game.impliedPoints(team) ?: return null
    val favoredBy = game.favoredBy(team) ?: return null
    val ratio = (implied / leagueImplied).coerceIn(K.IMPLIED_RATIO_MIN, K.IMPLIED_RATIO_MAX)
    val usual = passRate.coerceIn(K.PASS_RATE_MIN, K.PASS_RATE_MAX)
    val scripted = (usual - K.PASS_RATE_PER_POINT * favoredBy).coerceIn(K.PASS_RATE_MIN, K.PASS_RATE_MAX)
    return GameScript(implied, leagueImplied, ratio, scripted / usual, (1 - scripted) / (1 - usual))
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :core:forecast:test`
Expected: PASS, 36 tests.

- [ ] **Step 7: Commit**

```bash
git add core/forecast
git commit -m "forecast: matchup ratings and game script (layers 5-6)"
```

---
### Task 7: The walk-forward engine

**Files:**
- Create: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/ProjectionWriter.kt`
- Create: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Projector.kt`
- Create: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Forecast.kt`
- Test: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/ForecastEngineTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1 and 4–6.
- Produces (public, for `:core:ingest` in Task 8):
  - `const val FORECAST_OK: String = "ok"`
  - `data class ForecastReport(status: String, upcoming: Map<Int, Int>, weeks: Int, rows: Long)`
  - `data class SeasonCopy(previous: File, seasons: Set<Int>)`
  - `object Forecast { fun run(conn: SQLiteConnection, builtAt: Instant, copy: SeasonCopy? = null, onWeek: (season: Int, week: Int) -> Unit = { _, _ -> }): ForecastReport; fun fail(conn: SQLiteConnection, builtAt: Instant, reason: String) }`
  - `schema_meta` keys written: `forecast_version`, `forecast_status` (`ok`, `no schedule`, `no games to project from yet`, or `failed: <reason>`), `forecast_built_at` (ISO instant), `forecast_week:<season>` (only when there is an upcoming week).
  - Rows: past weeks → `final` stage only, for players with at least 1 reference point; the upcoming week → `baseline` and `final` plus `matchup` and (when a line is posted) `game_script` factor rows, for players with at least 0.1; `player_ros_projection` for the latest season at `as_of_week` = upcoming week − 1, summing the upcoming week and every later regular-season week the player's team plays.

- [ ] **Step 1: Write the failing tests**

`core/forecast/src/test/kotlin/dev/gridiron/core/forecast/ForecastEngineTest.kt`:

```kotlin
package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

class ForecastEngineTest {
    @TempDir
    lateinit var dir: File

    private val builtAt = Instant.parse("2025-09-20T12:00:00Z")
    private val teams = listOf("AAA", "BBB", "CCC", "DDD")

    /**
     * Four teams with a QB, RB and WR each. 2024 weeks 1-3 and 2025 weeks 1-2
     * are played. 2025 week 3 is next: AAA-BBB has a line, CCC-DDD doesn't.
     * In 2025 week 4 only AAA-CCC play: BBB and DDD are on bye.
     */
    private fun league(name: String, allPlayed: Boolean = false, wrA2025Week2Targets: Double = 9.0): TestDb {
        val db = TestDb(File(dir, name))
        for (team in teams) {
            val letter = team.first()
            db.player("QB_$letter", "QB", team)
            db.player("RB_$letter", "RB", team)
            db.player("WR_$letter", "WR", team)
        }
        val schedule = listOf(
            listOf("AAA" to "BBB", "CCC" to "DDD"),
            listOf("AAA" to "CCC", "BBB" to "DDD"),
            listOf("AAA" to "DDD", "BBB" to "CCC"),
        )
        for (season in listOf(2024, 2025)) {
            for ((w, pairs) in schedule.withIndex()) {
                val week = w + 1
                val played = season == 2024 || week <= 2 || allPlayed
                for ((home, away) in pairs) {
                    val line = season == 2025 && week == 3 && home == "AAA"
                    db.game(
                        season, week, home, away, played = played,
                        spread = if (line) 3.0 else null, total = if (line) 44.0 else null,
                        homeCoach = "Coach $home", awayCoach = "Coach $away",
                    )
                    if (played) {
                        playWeek(db, home, season, week, wrA2025Week2Targets)
                        playWeek(db, away, season, week, wrA2025Week2Targets)
                    }
                }
            }
        }
        db.game(2025, 4, "AAA", "CCC", played = allPlayed, spread = -1.0, total = 41.0, homeCoach = "Coach AAA", awayCoach = "Coach CCC")
        if (allPlayed) {
            playWeek(db, "AAA", 2025, 4, wrA2025Week2Targets)
            playWeek(db, "CCC", 2025, 4, wrA2025Week2Targets)
        }
        db.meta("expected_through_week:2024", "3")
        db.meta("expected_through_week:2025", if (allPlayed) "4" else "2")
        return db
    }

    private fun playWeek(db: TestDb, team: String, season: Int, week: Int, wrA2025Week2Targets: Double) {
        val k = 1.0 + 0.1 * teams.indexOf(team) + 0.05 * week // teams and weeks differ a little
        val letter = team.first()
        db.week(
            "QB_$letter", season, week, team,
            "attempts" to 32 * k, "completions" to 21 * k, "passing_yards" to 240 * k, "passing_tds" to 1.5,
            "x_passing_tds" to 1.4, "interceptions" to 1.0, "carries" to 3.0, "rushing_yards" to 12.0,
        )
        db.week(
            "RB_$letter", season, week, team,
            "carries" to 18 * k, "rushing_yards" to 80 * k, "rushing_tds" to 0.6, "x_rushing_tds" to 0.55,
            "targets" to 4.0, "receptions" to 3.0, "receiving_yards" to 22.0,
        )
        val wrTargets = if (team == "AAA" && season == 2025 && week == 2) wrA2025Week2Targets else 9 * k
        db.week(
            "WR_$letter", season, week, team,
            "targets" to wrTargets, "receptions" to 6 * k, "receiving_yards" to 80 * k, "receiving_tds" to 0.5, "x_receiving_tds" to 0.45,
        )
    }

    private fun run(db: TestDb, copy: SeasonCopy? = null, onWeek: (Int, Int) -> Unit = { _, _ -> }): ForecastReport =
        Forecast.run(db.conn, builtAt, copy, onWeek)

    private fun projections(db: TestDb) =
        db.query("SELECT player_id, season, week, metric_id, stage, mean, variance FROM player_week_projection ORDER BY 1, 2, 3, 4, 5")

    private fun targets(db: TestDb, player: String, week: Int): Double = db.query(
        "SELECT mean FROM player_week_projection WHERE player_id = '$player' AND season = 2025 AND week = $week AND stage = 'final' AND metric_id = 'targets'",
    ).single()[0]!!.toDouble()

    private fun rosTargets(db: TestDb, player: String): Double = db.query(
        "SELECT mean FROM player_ros_projection WHERE player_id = '$player' AND season = 2025 AND as_of_week = 2 AND metric_id = 'targets'",
    ).single()[0]!!.toDouble()

    private fun factors(db: TestDb, player: String) = db.query(
        "SELECT factor FROM player_week_projection_factor WHERE player_id = '$player' AND season = 2025 AND week = 3 ORDER BY factor",
    ).map { it[0] }

    @Test
    fun `the upcoming week gets both stages and the waterfall's factors`() {
        league("a.db").use { db ->
            val seen = mutableListOf<Pair<Int, Int>>()
            val report = run(db) { season, week -> seen += season to week }

            assertEquals(FORECAST_OK, report.status)
            assertEquals(mapOf(2025 to 3), report.upcoming)
            assertEquals(listOf(2024 to 2, 2024 to 3, 2025 to 1, 2025 to 2, 2025 to 3, 2025 to 4), seen)
            assertEquals(listOf(listOf("3")), db.query("SELECT value FROM schema_meta WHERE key = 'forecast_week:2025'"))
            assertEquals(listOf(listOf("ok")), db.query("SELECT value FROM schema_meta WHERE key = 'forecast_status'"))
            assertEquals(listOf(listOf(FORECAST_VERSION.toString())), db.query("SELECT value FROM schema_meta WHERE key = 'forecast_version'"))
            assertEquals(
                listOf(listOf("baseline"), listOf("final")),
                db.query("SELECT DISTINCT stage FROM player_week_projection WHERE player_id = 'WR_A' AND season = 2025 AND week = 3 ORDER BY stage"),
            )
            // AAA's game has a line; CCC's doesn't.
            assertEquals(listOf("game_script", "matchup"), factors(db, "WR_A"))
            assertEquals(listOf("matchup"), factors(db, "WR_C"))
            assertEquals(
                "vs BBB: too early to rate defenses",
                db.query("SELECT note FROM player_week_projection_factor WHERE player_id = 'WR_A' AND season = 2025 AND week = 3 AND factor = 'matchup'").single()[0],
            )
            // No defense ratings yet and no line: CCC's final is its baseline.
            val wrC = db.query(
                "SELECT mean FROM player_week_projection WHERE player_id = 'WR_C' AND season = 2025 AND week = 3 AND metric_id = 'targets' ORDER BY stage",
            )
            assertEquals(wrC[0][0], wrC[1][0])
        }
    }

    @Test
    fun `past weeks keep only the final stage, and the first week has nothing before it`() {
        league("a.db").use { db ->
            run(db)
            assertEquals(
                listOf(
                    listOf("2024", "2", "final"), listOf("2024", "3", "final"),
                    listOf("2025", "1", "final"), listOf("2025", "2", "final"),
                    listOf("2025", "3", "baseline"), listOf("2025", "3", "final"),
                ),
                db.query("SELECT DISTINCT season, week, stage FROM player_week_projection ORDER BY season, week, stage"),
            )
        }
    }

    @Test
    fun `rest of season sums the remaining games and skips byes`() {
        league("a.db").use { db ->
            run(db)
            // BBB is on bye in week 4: its rest of season is week 3 alone, not week 3 plus a zero.
            assertEquals(targets(db, "WR_B", 3), rosTargets(db, "WR_B"), 1e-9)
            assertTrue(rosTargets(db, "WR_A") > 1.5 * targets(db, "WR_A", 3))
            assertEquals(
                listOf(listOf("2")),
                db.query("SELECT DISTINCT as_of_week FROM player_ros_projection"),
            )
        }
    }

    @Test
    fun `a later week's stats never change an earlier week's projection`() {
        val before = league("a.db").use { db -> run(db); projections(db) }
        val after = league("b.db", wrA2025Week2Targets = 20.0).use { db -> run(db); projections(db) }
        val throughWeek2 = { row: List<String?> -> order(row[1]!!.toInt(), row[2]!!.toInt()) <= order(2025, 2) }

        assertEquals(before.filter(throughWeek2), after.filter(throughWeek2))
        val wrAWeek3 = { row: List<String?> -> row[0] == "WR_A" && row[1] == "2025" && row[2] == "3" }
        assertNotEquals(before.filter(wrAWeek3), after.filter(wrAWeek3))
    }

    @Test
    fun `every stored value is finite and every mean positive`() {
        league("a.db").use { db ->
            run(db)
            val values = db.query("SELECT mean, variance FROM player_week_projection") +
                db.query("SELECT mean, variance FROM player_ros_projection") +
                db.query("SELECT log_multiplier, 0 FROM player_week_projection_factor")
            assertTrue(values.isNotEmpty())
            for (row in values) for (v in row) assertTrue(v!!.toDouble().isFinite(), "$row")
            assertTrue(db.query("SELECT mean FROM player_week_projection").all { it[0]!!.toDouble() > 0.0 })
        }
    }

    @Test
    fun `off-season there is no upcoming week and no rest of season`() {
        league("a.db", allPlayed = true).use { db ->
            val report = run(db)
            assertEquals(FORECAST_OK, report.status)
            assertEquals(emptyMap<Int, Int>(), report.upcoming)
            assertEquals(emptyList<List<String?>>(), db.query("SELECT key FROM schema_meta WHERE key LIKE 'forecast_week:%'"))
            assertEquals(listOf(listOf("0")), db.query("SELECT COUNT(*) FROM player_ros_projection"))
            assertEquals(listOf(listOf("final")), db.query("SELECT DISTINCT stage FROM player_week_projection WHERE season = 2025 AND week = 4"))
        }
    }

    @Test
    fun `with no game played yet there is nothing to project from`() {
        TestDb(File(dir, "a.db")).use { db ->
            db.player("WR_A", "WR", "AAA")
            db.game(2025, 1, "AAA", "BBB", played = false, spread = 3.0, total = 44.0)

            val report = run(db)

            assertEquals("no games to project from yet", report.status)
            assertEquals(0L, report.rows)
            assertEquals(listOf(listOf("no games to project from yet")), db.query("SELECT value FROM schema_meta WHERE key = 'forecast_status'"))
        }
    }

    @Test
    fun `without a schedule there are no projections`() {
        TestDb(File(dir, "a.db")).use { db ->
            db.player("WR_A", "WR", "AAA")
            db.week("WR_A", 2025, 1, "AAA", "targets" to 5.0)
            assertEquals("no schedule", run(db).status)
            assertEquals(listOf(listOf("0")), db.query("SELECT COUNT(*) FROM player_week_projection"))
        }
    }

    @Test
    fun `a copied season comes from the previous database, and later seasons don't change`() {
        val previous = TestDb(File(dir, "previous.db")).apply {
            exec("INSERT INTO player_week_projection VALUES ('WR_A', 2024, 2, 'targets', 'final', 99.0, 1.0)")
            close()
        }
        val normal = league("normal.db").use { db -> run(db); projections(db) }

        league("copy.db").use { db ->
            val report = run(db, SeasonCopy(previous.file, setOf(2024)))
            val rows = projections(db)

            assertEquals(listOf(listOf("WR_A", "2024", "2", "targets", "final", "99.0", "1.0")), rows.filter { it[1] == "2024" })
            assertEquals(normal.filter { it[1] == "2025" }, rows.filter { it[1] == "2025" })
            assertEquals(3, report.weeks) // 2025 weeks 1-3; 2024 was copied and week 4 is rest of season
        }
    }

    @Test
    fun `a player who changed teams is projected with his new team`() {
        league("a.db").use { db ->
            db.player("WR_T", "WR", "CCC")
            for (week in 1..3) db.week("WR_T", 2024, week, "AAA", "targets" to 5.0, "receptions" to 3.0, "receiving_yards" to 40.0)

            run(db)

            // CCC's game has no line, so a CCC player has no game-script factor; an AAA player would.
            assertEquals(listOf("matchup"), factors(db, "WR_T"))
            assertEquals(
                "vs DDD: too early to rate defenses",
                db.query("SELECT note FROM player_week_projection_factor WHERE player_id = 'WR_T' AND season = 2025 AND week = 3").single()[0],
            )
        }
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:forecast:test --tests "dev.gridiron.core.forecast.ForecastEngineTest"`
Expected: FAIL to compile (`Forecast`, `SeasonCopy`, `FORECAST_OK` unresolved).

- [ ] **Step 3: Write the writer**

`core/forecast/src/main/kotlin/dev/gridiron/core/forecast/ProjectionWriter.kt`:

```kotlin
package dev.gridiron.core.forecast

import androidx.sqlite.SQLiteConnection

/** Where the projector's rows go. */
internal interface ProjectionSink {
    fun projection(playerId: String, season: Int, week: Int, metricId: String, stage: String, mean: Double, variance: Double)

    fun factor(playerId: String, season: Int, week: Int, factor: String, logMultiplier: Double, note: String?)

    fun ros(playerId: String, season: Int, asOfWeek: Int, metricId: String, mean: Double, variance: Double)
}

/** Inserts into the projection tables through three prepared statements. The caller owns the transaction. */
internal class ProjectionWriter(conn: SQLiteConnection) : ProjectionSink, AutoCloseable {
    private val projectionInsert = conn.prepare(
        "INSERT OR REPLACE INTO player_week_projection (player_id, season, week, metric_id, stage, mean, variance) VALUES (?, ?, ?, ?, ?, ?, ?)",
    )
    private val factorInsert = conn.prepare(
        "INSERT OR REPLACE INTO player_week_projection_factor (player_id, season, week, factor, log_multiplier, note) VALUES (?, ?, ?, ?, ?, ?)",
    )
    private val rosInsert = conn.prepare(
        "INSERT OR REPLACE INTO player_ros_projection (player_id, season, as_of_week, metric_id, mean, variance) VALUES (?, ?, ?, ?, ?, ?)",
    )

    var rows: Long = 0
        private set

    override fun projection(playerId: String, season: Int, week: Int, metricId: String, stage: String, mean: Double, variance: Double) {
        with(projectionInsert) {
            bindText(1, playerId)
            bindLong(2, season.toLong())
            bindLong(3, week.toLong())
            bindText(4, metricId)
            bindText(5, stage)
            bindDouble(6, mean)
            bindDouble(7, variance)
            step()
            reset()
        }
        rows++
    }

    override fun factor(playerId: String, season: Int, week: Int, factor: String, logMultiplier: Double, note: String?) {
        with(factorInsert) {
            bindText(1, playerId)
            bindLong(2, season.toLong())
            bindLong(3, week.toLong())
            bindText(4, factor)
            bindDouble(5, logMultiplier)
            if (note == null) bindNull(6) else bindText(6, note)
            step()
            reset()
        }
        rows++
    }

    override fun ros(playerId: String, season: Int, asOfWeek: Int, metricId: String, mean: Double, variance: Double) {
        with(rosInsert) {
            bindText(1, playerId)
            bindLong(2, season.toLong())
            bindLong(3, asOfWeek.toLong())
            bindText(4, metricId)
            bindDouble(5, mean)
            bindDouble(6, variance)
            step()
            reset()
        }
        rows++
    }

    override fun close() {
        projectionInsert.close()
        factorInsert.close()
        rosInsert.close()
    }
}
```

- [ ] **Step 4: Write the projector**

`core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Projector.kt`:

```kotlin
package dev.gridiron.core.forecast

import kotlin.math.ln

internal class ProjectionOutcome(val status: String, val upcoming: Map<Int, Int>, val weeks: Int)

private enum class WeekKind { PAST, UPCOMING, REST }

/** A player's upcoming-week baseline and team: reused, with each remaining week's opponent, for rest of season. */
private class Prepared(val player: PlayerInfo, val team: String, val baseline: Map<String, Double>, val passRate: Double)

/**
 * The walk-forward loop over every regular-season week of the built seasons,
 * oldest first, each projected only from the games before it. Past weeks
 * keep their final projection, for the accuracy backtest. The upcoming week
 * keeps both stages and the waterfall's factors. Every week from the upcoming
 * one on is summed into rest of season, using what's known as of the upcoming
 * week and each week's own opponent and line.
 */
internal class Projector(
    private val inputs: ForecastInputs,
    /** Seasons copied from the previous database: their weeks aren't recomputed. */
    private val copied: Set<Int>,
    private val sink: ProjectionSink,
    private val onWeek: (season: Int, week: Int) -> Unit,
) {
    private val model = BaselineModel(inputs.teamGames, inputs.expectedThrough)
    private val teamHistory: Map<String, List<TeamGame>> =
        inputs.teamGames.values.groupBy { it.team }.mapValues { (_, games) -> games.sortedBy { it.order } }
    private val gameOf: Map<Triple<String, Int, Int>, Game> = buildMap {
        for (g in inputs.games) {
            put(Triple(g.home, g.season, g.week), g)
            put(Triple(g.away, g.season, g.week), g)
        }
    }
    private val teamsIn: Map<Int, Set<String>> =
        inputs.games.groupBy { it.season }.mapValues { (_, games) -> games.flatMap { listOf(it.home, it.away) }.toSet() }
    private val chronological: List<PlayerGame> = inputs.history.values.flatten().sortedBy { it.order }
    private val totals = LeagueTotals()
    private var added = 0

    fun run(): ProjectionOutcome {
        val regular = inputs.games.filter { it.regular }
        if (regular.isEmpty()) return ProjectionOutcome("no schedule", emptyMap(), 0)
        val latest = regular.maxOf { it.season }
        val upcomingWeek = regular.filter { it.season == latest && !it.played }.minOfOrNull { it.week }
        val weeks = regular.map { it.season to it.week }.distinct().sortedWith(compareBy({ it.first }, { it.second }))

        var projected = 0
        var upcoming: Pair<WeekState, List<Prepared>>? = null
        val ros = HashMap<Pair<String, String>, DoubleArray>()
        for ((season, week) in weeks) {
            val kind = when {
                upcomingWeek == null || season < latest || week < upcomingWeek -> WeekKind.PAST
                week == upcomingWeek -> WeekKind.UPCOMING
                else -> WeekKind.REST
            }
            if (kind == WeekKind.REST) {
                val (state, prepared) = upcoming ?: continue
                onWeek(season, week)
                for (p in prepared) addRest(state, p, season, week, ros)
                continue
            }
            advanceTo(order(season, week))
            if (added == 0 || (kind == WeekKind.PAST && season in copied)) continue
            onWeek(season, week)
            projected++
            val state = WeekState(season, week)
            val prepared = candidates(state.order).mapNotNull { projectPlayer(state, it, kind) }
            if (kind == WeekKind.UPCOMING) {
                upcoming = state to prepared
                for (p in prepared) addRest(state, p, season, week, ros)
            }
        }
        if (upcoming != null && upcomingWeek != null) {
            for ((key, sum) in ros) sink.ros(key.first, latest, upcomingWeek - 1, key.second, sum[0], sum[1])
        }
        val status = if (projected == 0) "no games to project from yet" else FORECAST_OK
        val upcomingMap = if (upcoming != null && upcomingWeek != null) mapOf(latest to upcomingWeek) else emptyMap()
        return ProjectionOutcome(status, upcomingMap, projected)
    }

    /** What every player's projection for one week shares: league rates, the average team, defense ratings. */
    private inner class WeekState(val season: Int, val week: Int) {
        val order = order(season, week)
        val rates: Map<String, Rates> = POSITIONS.mapNotNull { p -> totals.rates(p)?.let { p to it } }.toMap()
        val leagueTeam: TeamVolume
        val matchup: MatchupModel
        val leagueImplied: Double

        init {
            val earlier = inputs.teamGames.values.filter { it.order < order }
            leagueTeam = TeamVolume(
                earlier.map { it.passAttempts }.average(),
                earlier.map { it.targets }.average(),
                earlier.map { it.carries }.average(),
            )
            matchup = MatchupModel.fit(
                earlier.filter { it.season == season }.mapNotNull { tg ->
                    gameOf[Triple(tg.team, tg.season, tg.week)]?.let { g -> MatchupGame(tg, g.opponentOf(tg.team), g.isHome(tg.team)) }
                },
            )
            val totalsPosted = inputs.games.filter { it.season == season && it.regular }.mapNotNull { it.total }
            leagueImplied = if (totalsPosted.isEmpty()) K.LEAGUE_IMPLIED_DEFAULT else totalsPosted.average() / 2
        }
    }

    /** Adds every game before [order] to the league totals. */
    private fun advanceTo(order: Int) {
        while (added < chronological.size && chronological[added].order < order) {
            val g = chronological[added++]
            val position = inputs.players[g.playerId]?.position ?: continue
            val team = inputs.teamGames.getValue(Triple(g.team, g.season, g.week))
            totals.add(position, g, team, g.week <= (inputs.expectedThrough[g.season] ?: 0))
        }
    }

    /** Players with a game this season or last, before this week. */
    private fun candidates(order: Int): List<PlayerInfo> {
        val season = order / 100
        return inputs.players.values
            .filter { p -> inputs.history[p.playerId]?.any { it.order < order && it.season >= season - 1 } == true }
            .sortedBy { it.playerId }
    }

    private fun projectPlayer(state: WeekState, player: PlayerInfo, kind: WeekKind): Prepared? {
        val rates = state.rates[player.position] ?: return null
        val all = inputs.history[player.playerId].orEmpty()
        val before = all.takeWhile { it.order < state.order }
        val team = teamFor(player, all, before, state.season, state.week, kind) ?: return null
        val game = gameOf[Triple(team, state.season, state.week)] ?: return null // a bye
        val ctx = PlayerContext(player.position, state.season, state.week, before, regimeBreak(player, team, before, state.season, state.week))
        val volume = teamVolume(teamHistory[team].orEmpty().takeWhile { it.order < state.order }, state.leagueTeam)
        val prepared = Prepared(player, team, model.project(ctx, rates, volume), volume.passRate)
        val (afterMatchup, final) = finalFor(state, prepared, game)
        val cv = K.EMPIRICAL_CV.getValue(player.position)
        when (kind) {
            WeekKind.PAST -> if (referencePoints(final) >= K.PAST_WEEK_MIN_POINTS) {
                emit(player.playerId, state.season, state.week, "final", final, cv)
            }
            WeekKind.UPCOMING -> if (referencePoints(final) >= K.UPCOMING_MIN_POINTS) {
                emit(player.playerId, state.season, state.week, "baseline", prepared.baseline, cv)
                emit(player.playerId, state.season, state.week, "final", final, cv)
                emitFactors(state, prepared, game, afterMatchup, final)
            }
            WeekKind.REST -> Unit
        }
        return prepared
    }

    /**
     * The team a player is projected with: the one he played for that week if
     * he did; for the upcoming week and after, nflverse's current team if it
     * plays this season; otherwise his most recent team.
     */
    private fun teamFor(p: PlayerInfo, all: List<PlayerGame>, before: List<PlayerGame>, season: Int, week: Int, kind: WeekKind): String? {
        all.firstOrNull { it.season == season && it.week == week }?.let { return it.team }
        val current = p.team
        if (kind != WeekKind.PAST && current != null && current in teamsIn[season].orEmpty()) return current
        return before.lastOrNull()?.team
    }

    /** Last season stops counting after a move to a new team, a new head coach, or (pass catchers) a new starting QB. */
    private fun regimeBreak(p: PlayerInfo, team: String, before: List<PlayerGame>, season: Int, week: Int): Boolean {
        val prior = before.filter { it.season == season - 1 }
        if (prior.isEmpty()) return false
        val lastTeam = prior.last().team
        if (lastTeam != team) return true
        val coachNow = gameOf[Triple(team, season, week)]?.coachOf(team)
        val coachThen = gameOf[Triple(lastTeam, season - 1, prior.last().week)]?.coachOf(lastTeam)
        if (coachNow != null && coachThen != null && coachNow != coachThen) return true
        if (p.position == "QB") return false
        val qbNow = startingQb(team, season, week)
        val qbThen = prior.mapNotNull { gameOf[Triple(lastTeam, it.season, it.week)]?.qbOf(lastTeam) }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return qbNow != null && qbThen != null && qbNow != qbThen
    }

    /** This week's listed starter, else the team's most recent one this season. */
    private fun startingQb(team: String, season: Int, week: Int): String? =
        gameOf[Triple(team, season, week)]?.qbOf(team)
            ?: inputs.games.lastOrNull { it.season == season && it.week < week && it.involves(team) && it.qbOf(team) != null }?.qbOf(team)

    /** Matchup, then game script: (after matchup, final). */
    private fun finalFor(state: WeekState, p: Prepared, game: Game): Pair<Map<String, Double>, Map<String, Double>> {
        val opponent = game.opponentOf(p.team)
        val home = game.isHome(p.team)
        val afterMatchup = adjust(p.baseline) { side, type -> state.matchup.multiplier(side, type, opponent, home) }
        val script = gameScript(game, p.team, state.leagueImplied, p.passRate) ?: return afterMatchup to afterMatchup
        return afterMatchup to adjust(afterMatchup) { side, type -> script.multiplier(side, type) }
    }

    private fun emitFactors(state: WeekState, p: Prepared, game: Game, afterMatchup: Map<String, Double>, final: Map<String, Double>) {
        val baselinePoints = referencePoints(p.baseline)
        val matchupPoints = referencePoints(afterMatchup)
        val id = p.player.playerId
        sink.factor(
            id, state.season, state.week, "matchup", logRatio(matchupPoints, baselinePoints),
            state.matchup.note(p.player.position, game.opponentOf(p.team)),
        )
        gameScript(game, p.team, state.leagueImplied, p.passRate)?.let { script ->
            sink.factor(id, state.season, state.week, "game_script", logRatio(referencePoints(final), matchupPoints), script.note)
        }
    }

    private fun addRest(state: WeekState, p: Prepared, season: Int, week: Int, ros: MutableMap<Pair<String, String>, DoubleArray>) {
        val game = gameOf[Triple(p.team, season, week)] ?: return // a bye
        val final = finalFor(state, p, game).second
        if (referencePoints(final) < K.UPCOMING_MIN_POINTS) return
        val cv = K.EMPIRICAL_CV.getValue(p.player.position)
        for ((metric, mean) in final) {
            if (mean <= 0.0) continue
            val sum = ros.getOrPut(p.player.playerId to metric) { DoubleArray(2) }
            sum[0] += mean
            sum[1] += varianceFor(mean, cv)
        }
    }

    private fun emit(playerId: String, season: Int, week: Int, stage: String, components: Map<String, Double>, cv: Double) {
        for ((metric, mean) in components) {
            check(mean.isFinite()) { "$playerId's $season week $week $metric projection is $mean" }
            if (mean > 0.0) sink.projection(playerId, season, week, metric, stage, mean, varianceFor(mean, cv))
        }
    }

    private fun logRatio(after: Double, before: Double): Double = if (after > 0.0 && before > 0.0) ln(after / before) else 0.0
}
```

- [ ] **Step 5: Write the entry point**

`core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Forecast.kt`:

```kotlin
package dev.gridiron.core.forecast

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import java.io.File
import java.time.Instant

/** `schema_meta`'s `forecast_status` when projections were built. */
public const val FORECAST_OK: String = "ok"

/** What a forecast did, for the refresh report. */
public data class ForecastReport(
    /** [FORECAST_OK], or why there are no projections. */
    public val status: String,
    /** The upcoming week projected, by season. Empty off-season. */
    public val upcoming: Map<Int, Int>,
    /** Weeks projected here, not counting copied seasons or rest-of-season weeks. */
    public val weeks: Int,
    public val rows: Long,
)

/** Seasons whose weekly projections are copied from [previous] instead of recomputed. */
public data class SeasonCopy(public val previous: File, public val seasons: Set<Int>)

private val PROJECTION_TABLES = listOf("player_week_projection", "player_week_projection_factor", "player_ros_projection")

public object Forecast {
    /**
     * Projects the built seasons into [conn]'s (empty) projection tables and
     * records the outcome in `schema_meta`. [onWeek] is called before each
     * week and may throw to cancel.
     */
    public fun run(
        conn: SQLiteConnection,
        builtAt: Instant,
        copy: SeasonCopy? = null,
        onWeek: (season: Int, week: Int) -> Unit = { _, _ -> },
    ): ForecastReport {
        // ATTACH can't run inside a transaction, so copy first.
        copy?.let { copySeasons(conn, it) }
        val inputs = loadInputs(conn)
        conn.execSQL("BEGIN")
        val report = ProjectionWriter(conn).use { writer ->
            val outcome = Projector(inputs, copy?.seasons.orEmpty(), writer, onWeek).run()
            ForecastReport(outcome.status, outcome.upcoming, outcome.weeks, writer.rows)
        }
        writeMeta(conn, report.status, report.upcoming, builtAt)
        conn.execSQL("COMMIT")
        return report
    }

    /** After [run] threw: no projection rows at all, and why. */
    public fun fail(conn: SQLiteConnection, builtAt: Instant, reason: String) {
        // The build runs with the journal off, where ROLLBACK is undefined: close any open transaction, then delete.
        runCatching { conn.execSQL("COMMIT") }
        for (table in PROJECTION_TABLES) conn.execSQL("DELETE FROM $table")
        writeMeta(conn, "failed: $reason", emptyMap(), builtAt)
    }

    private fun copySeasons(conn: SQLiteConnection, copy: SeasonCopy) {
        if (copy.seasons.isEmpty()) return
        conn.prepare("ATTACH DATABASE ? AS prev").use {
            it.bindText(1, copy.previous.path)
            it.step()
        }
        try {
            for (table in listOf("player_week_projection", "player_week_projection_factor")) {
                for (season in copy.seasons) {
                    conn.prepare("INSERT OR REPLACE INTO $table SELECT * FROM prev.$table WHERE season = ?").use {
                        it.bindLong(1, season.toLong())
                        it.step()
                    }
                }
            }
        } finally {
            conn.execSQL("DETACH DATABASE prev")
        }
    }

    private fun writeMeta(conn: SQLiteConnection, status: String, upcoming: Map<Int, Int>, builtAt: Instant) {
        conn.execSQL("DELETE FROM schema_meta WHERE key LIKE 'forecast%'")
        val rows = listOf(
            "forecast_version" to FORECAST_VERSION.toString(),
            "forecast_status" to status,
            "forecast_built_at" to builtAt.toString(),
        ) + upcoming.map { (season, week) -> "forecast_week:$season" to week.toString() }
        conn.prepare("INSERT INTO schema_meta (key, value) VALUES (?, ?)").use { st ->
            for ((key, value) in rows) {
                st.bindText(1, key)
                st.bindText(2, value)
                st.step()
                st.reset()
            }
        }
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :core:forecast:test`
Expected: PASS, 46 tests. If `a later week's stats never change an earlier week's projection` fails, something reads a fact at or after the week it projects: find it; never relax the test.

- [ ] **Step 7: Commit**

```bash
git add core/forecast
git commit -m "forecast: walk-forward engine writing weekly, factor and rest-of-season rows"
```

---
### Task 8: Run the forecast at the end of every refresh

**Files:**
- Modify: `core/ingest/build.gradle.kts`
- Modify: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/IngestPipeline.kt`
- Modify: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/cli/IngestCli.kt`
- Modify: `app/src/main/kotlin/dev/gridiron/app/RefreshText.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/IngestPipelineTest.kt`
- Test: `app/src/test/kotlin/dev/gridiron/app/RefreshTextTest.kt`

**Interfaces:**
- Consumes: `Forecast.run`, `Forecast.fail`, `SeasonCopy`, `FORECAST_OK`, `FORECAST_VERSION` (Task 7); the `game` table (Task 2).
- Produces: `IngestProgress.Projecting(season: Int, week: Int)`; `IngestReport.forecast: String` (last constructor parameter, default `"ok"`) and `IngestReport.projectionsOk: Boolean`; the refresh toast says why projections are missing.

- [ ] **Step 1: Write the failing pipeline tests**

Add to `IngestPipelineTest` (imports: `dev.gridiron.core.forecast.FORECAST_OK` is not visible to tests of `:core:ingest` unless the dependency is added in Step 3, so compare with the literal `"ok"`):

```kotlin
    @Test
    fun `a build projects its seasons and records how`() = runTest {
        servePlayers()
        serveSeason(2024)
        serveSeason(2025)
        val out = File(dir, "stats.db")
        val progress = mutableListOf<IngestProgress>()

        val report = pipeline.build(listOf(2024, 2025), previous = null, out = out) { progress += it }

        assertEquals("ok", report.forecast)
        assertTrue(report.projectionsOk)
        assertEquals("ok", readMeta(out)!!["forecast_status"])
        assertTrue(IngestProgress.Projecting(2025, 1) in progress, "$progress")
        // 2025 week 1 is projected from 2024's games; 2024 week 1 has nothing before it.
        assertEquals(listOf(listOf("2025", "1")), query(out, "SELECT DISTINCT season, week FROM player_week_projection"))
    }

    @Test
    fun `a forecast failure keeps the stats and says why`() = runTest {
        servePlayers()
        serveSeason(2024)
        serveSeason(2025)
        val first = File(dir, "first.db")
        pipeline.build(listOf(2024, 2025), null, first)
        // Break the table the next build copies 2024's projections from.
        BundledSQLiteDriver().open(first.path).use { it.execSQL("DROP TABLE player_week_projection_factor") }

        val second = File(dir, "second.db")
        val report = pipeline.build(listOf(2024, 2025), first, second)

        assertEquals(listOf(2024, 2025), report.reused)
        assertTrue(report.forecast.startsWith("failed:"), report.forecast)
        assertFalse(report.projectionsOk)
        assertTrue(readMeta(second)!!.getValue("forecast_status").startsWith("failed:"))
        assertEquals(listOf(listOf("0")), query(second, "SELECT COUNT(*) FROM player_week_projection"))
        assertEquals(facts(first), facts(second))
    }

    @Test
    fun `a build without a schedule has stats but no projections`() = runTest {
        servePlayers()
        fetcher.remove(Sources.url(Input.GAMES))
        serveSeason(2025)

        val report = pipeline.build(listOf(2025), null, File(dir, "stats.db"))

        assertEquals("no schedule", report.forecast)
    }

    @Test
    fun `a single season with nothing before it has no projections yet, and still builds`() = runTest {
        servePlayers()
        serveSeason(2025)

        val report = pipeline.build(listOf(2025), null, File(dir, "stats.db"))

        assertEquals(listOf(2025), report.built)
        assertEquals("no games to project from yet", report.forecast)
    }
```

Add the imports `androidx.sqlite.driver.bundled.BundledSQLiteDriver`, `androidx.sqlite.execSQL` and `org.junit.jupiter.api.Assertions.assertFalse` if missing.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:ingest:test --tests "dev.gridiron.core.ingest.IngestPipelineTest"`
Expected: FAIL to compile (`report.forecast`, `IngestProgress.Projecting` unresolved).

- [ ] **Step 3: Depend on the forecast module**

`core/ingest/build.gradle.kts`, in `dependencies`:

```kotlin
    implementation(projects.core.forecast)
```

- [ ] **Step 4: Report and progress types**

In `IngestPipeline.kt`, add to `IngestProgress`, after `Validating`:

```kotlin
    public data class Projecting(public val season: Int, public val week: Int) : IngestProgress
```

and replace `IngestReport` with:

```kotlin
public data class IngestReport(
    public val built: List<Int>,
    public val reused: List<Int>,
    public val skipped: Map<Int, String>,
    public val warnings: List<String>,
    public val facts: Long,
    /** "ok", or why the new database has no projections (the stats are fine either way). */
    public val forecast: String = FORECAST_OK,
) {
    public val projectionsOk: Boolean get() = forecast == FORECAST_OK
}
```

- [ ] **Step 5: Run the forecast after validation**

In `IngestPipeline.kt`, add the imports:

```kotlin
import dev.gridiron.core.forecast.FORECAST_OK
import dev.gridiron.core.forecast.FORECAST_VERSION
import dev.gridiron.core.forecast.Forecast
import dev.gridiron.core.forecast.SeasonCopy
import kotlinx.coroutines.CancellationException
```

In `Run.build`, replace the two lines after `onProgress(IngestProgress.Validating)` with:

```kotlin
                val problems = validateDatabase(writer.connection)
                if (problems.isNotEmpty()) throw ValidationException(problems)
                val forecast = forecast(writer)
                IngestReport(built.sorted(), reused.sorted(), skipped.toMap(), warnings.toList(), writer.factCount(), forecast)
```

and add to `Run`:

```kotlin
        /** Projections for the new database. A failure leaves none and says why; it never fails the build. */
        private fun forecast(writer: StatsDbWriter): String = try {
            Forecast.run(writer.connection, now(), forecastCopy()) { season, week ->
                job.ensureActive()
                onProgress(IngestProgress.Projecting(season, week))
            }.status
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: e::class.simpleName ?: "unknown error"
            Forecast.fail(writer.connection, now(), reason)
            "failed: $reason"
        }

        /**
         * Past seasons whose projections can be copied from the previous
         * database: reused seasons, from the oldest up to the first rebuilt
         * one (a season's projections depend on every season before it),
         * never the latest, and only when the previous build projected them
         * with this forecast version from exactly the same earlier seasons.
         */
        private fun forecastCopy(): SeasonCopy? {
            val p = prior ?: return null
            val prev = previous ?: return null
            if (p["forecast_version"] != FORECAST_VERSION.toString() || p["forecast_status"] != FORECAST_OK) return null
            val copyable = (built + reused).sorted().dropLast(1).takeWhile { it in reused }
            if (copyable.isEmpty() || priorSeasons.sorted().takeWhile { it <= copyable.last() } != copyable) return null
            return SeasonCopy(prev, copyable.toSet())
        }
```

In `cli/IngestCli.kt`, after the `println("built …")` line:

```kotlin
        println("projections: ${report.forecast}")
```

- [ ] **Step 6: Run the ingest tests**

Run: `./gradlew :core:ingest:test`
Expected: PASS.

- [ ] **Step 7: Write the failing refresh-text tests**

Add to `app/src/test/kotlin/dev/gridiron/app/RefreshTextTest.kt`:

```kotlin
    @Test
    fun `projecting shows the season and week`() {
        assertEquals("Projecting 2026 week 4…", progressText(IngestProgress.Projecting(2026, 4)))
    }

    @Test
    fun `the summary says why projections are missing, and nothing when they're fine`() {
        val ok = IngestReport(listOf(2026), emptyList(), emptyMap(), emptyList(), 1L)
        val missing = ok.copy(forecast = "failed: disk I/O error")

        assertEquals("Stats updated for 2026 in 5 s.", summary(ok, 5_000))
        assertEquals("Stats updated for 2026 in 5 s. Projections unavailable: failed: disk I/O error.", summary(missing, 5_000))
    }
```

(Match the file's JUnit flavor and imports; `IngestProgress` and `IngestReport` are already imported there.)

- [ ] **Step 8: Run them to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.gridiron.app.RefreshTextTest"`
Expected: FAIL to compile. `progressText`'s `when` is no longer exhaustive.

- [ ] **Step 9: The refresh text**

In `app/src/main/kotlin/dev/gridiron/app/RefreshText.kt`, add to `progressText`'s `when`:

```kotlin
    is IngestProgress.Projecting -> "Projecting ${p.season} week ${p.week}…"
```

and at the end of `summary`'s `buildString`:

```kotlin
    if (!report.projectionsOk) append(" Projections unavailable: ").append(report.forecast).append('.')
```

- [ ] **Step 10: Run the app and ingest tests**

Run: `./gradlew :app:testDebugUnitTest :core:ingest:test :core:forecast:test`
Expected: PASS.

- [ ] **Step 11: Prove it on real data**

Run: `./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025" -Pout=etl/build/forecast-check.db`
Expected: the last line is `projections: ok`. Then check the output with:

```bash
sqlite3 etl/build/forecast-check.db "SELECT stage, COUNT(*) FROM player_week_projection GROUP BY stage; SELECT key, value FROM schema_meta WHERE key LIKE 'forecast%';"
```

It should show thousands of `final` rows and a `forecast_status` of `ok`. Say so in the handoff (with the time the build took). Needs network; if the session has none, say so and let CI prove it.

- [ ] **Step 12: Commit**

```bash
git add core/ingest app/src/main/kotlin/dev/gridiron/app/RefreshText.kt app/src/test/kotlin/dev/gridiron/app/RefreshTextTest.kt
git commit -m "ingest: project every refresh's seasons; a failed forecast keeps the stats"
```

---
### Task 9: Read projections on the phone with real distributions and the user's scoring

**Files:**
- Modify: `core/projections/src/main/kotlin/dev/gridiron/core/projections/ProjectionModels.kt`
- Modify: `core/projections/src/main/kotlin/dev/gridiron/core/projections/ProjectionQueries.kt`
- Create: `core/projections/src/main/kotlin/dev/gridiron/core/projections/ProjectedPoints.kt`
- Modify: `core/data/src/main/kotlin/dev/gridiron/core/data/ProjectionsRepository.kt`
- Modify: `feature/projections/src/main/kotlin/dev/gridiron/feature/projections/ProjectionsViewModel.kt`
- Modify: `feature/projections/src/main/kotlin/dev/gridiron/feature/projections/ProjectionsRoute.kt`
- Modify: `app/src/main/kotlin/dev/gridiron/app/GridironNavHost.kt` (the `ProjectionsKey` entry)
- Test: `core/projections/src/test/kotlin/dev/gridiron/core/projections/ProjectedPointsTest.kt` (new)
- Test: `core/projections/src/test/kotlin/dev/gridiron/core/projections/ProjectionQueriesTest.kt`
- Test: `core/data/src/test/kotlin/dev/gridiron/core/data/ProjectionsRepositoryTest.kt`
- Test: `feature/projections/src/test/kotlin/dev/gridiron/feature/projections/ProjectionsViewModelTest.kt`

**Interfaces:**
- Consumes: the tables and `schema_meta` keys from Tasks 2, 3 and 7.
- Produces:
  - `ProjectionComponent(metricId: String, mean: Double, variance: Double, family: String? = null)`
  - `ListedProjection(playerId: String, name: String, position: String?, team: String?, components: List<ProjectionComponent>)`
  - `ForecastStatus(status: String?, builtAt: Instant?, upcoming: Map<Int, Int>)`
  - `GameLine(team: String, opponent: String, home: Boolean, spread: Double?, total: Double?)` with `favoredBy: Double?`
  - `ProjectedPoints(points: Double, floor: Double, ceiling: Double)`, `familyOf(name: String?): DistributionFamily`, `projectPoints(components: List<ProjectionComponent>, profile: ScoringProfile, position: Position?, draws: Int = 10_000): ProjectedPoints`
  - `ProjectionQueries.weekAll(season, week)`, `rosAll(season)`, `status()`, `game(season, week, team)`, `remainingGames(season, fromWeek, team)`
  - `ProjectionsRepository.status(): ForecastStatus`, `weekAll(season, week): List<ListedProjection>`, `rosAll(season): List<ListedProjection>`, `game(season, week, team): GameLine?`, `remainingGames(season, fromWeek, team): Int`
  - `ProjectionsRoute(playerId, season, week, repository, scoring: ScoringRepository, players: PlayerDirectory?, onBack, modifier)`

- [ ] **Step 1: Write the failing core tests**

`core/projections/src/test/kotlin/dev/gridiron/core/projections/ProjectedPointsTest.kt`:

```kotlin
package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProjectedPointsTest {
    @Test
    fun `family names map to the simulation's families, and unknown ones to gamma`() {
        assertEquals(DistributionFamily.NEGBINOM, familyOf("negbinom"))
        assertEquals(DistributionFamily.BINOMIAL, familyOf("binomial"))
        assertEquals(DistributionFamily.POISSON, familyOf("poisson"))
        assertEquals(DistributionFamily.GAMMA, familyOf("gamma"))
        assertEquals(DistributionFamily.GAMMA, familyOf(null))
    }

    @Test
    fun `points are the scored means, with the floor below and the ceiling above`() {
        val components = listOf(
            ProjectionComponent("receptions", 5.0, 6.0, "binomial"),
            ProjectionComponent("receiving_yards", 60.0, 900.0, "gamma"),
            ProjectionComponent("receiving_tds", 0.4, 0.4, "poisson"),
        )

        val points = projectPoints(components, ScoringPresets.PPR, Position.WR)

        assertEquals(5 + 6 + 2.4, points.points, 1e-9)
        assertTrue(points.floor < points.points && points.points < points.ceiling, "$points")
    }
}
```

Add to `ProjectionQueriesTest`:

```kotlin
    @Test
    fun `every query binds exactly its placeholders`() {
        // SqlQuery's init rejects a mismatch, so constructing each is the test.
        ProjectionQueries.weekAll(2026, 3)
        ProjectionQueries.rosAll(2026)
        ProjectionQueries.status()
        ProjectionQueries.game(2026, 3, "KC")
        ProjectionQueries.remainingGames(2026, 3, "KC")
        assertTrue(ProjectionQueries.weekly(setOf("P1"), 2026, 3).sql.contains("dist_family"))
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:projections:test`
Expected: FAIL to compile.

- [ ] **Step 3: Models**

Replace `core/projections/src/main/kotlin/dev/gridiron/core/projections/ProjectionModels.kt` with:

```kotlin
package dev.gridiron.core.projections

import java.time.Instant

/** One projected stat. [family] is the metric registry's `dist_family`; null simulates as gamma. */
public data class ProjectionComponent(val metricId: String, val mean: Double, val variance: Double, val family: String? = null)

public data class ProjectionFactor(val factor: String, val logMultiplier: Double, val note: String?)

public data class PlayerProjection(
    val playerId: String,
    val season: Int,
    val week: Int,
    val baseline: List<ProjectionComponent>,
    val final: List<ProjectionComponent>,
    val factors: List<ProjectionFactor>,
)

public data class RosProjection(val playerId: String, val components: List<ProjectionComponent>)

public data class ProjectionsRequest(val playerIds: Set<String>, val season: Int, val week: Int)

public data class RosProjectionsRequest(val playerIds: Set<String>, val season: Int)

/** One player's projected stats and who he is: a row of the Projections list. */
public data class ListedProjection(
    val playerId: String,
    val name: String,
    val position: String?,
    val team: String?,
    val components: List<ProjectionComponent>,
)

/** The forecast as the last refresh left it. */
public data class ForecastStatus(
    /** "ok", why there are no projections, or null for a database built before projections existed. */
    val status: String?,
    val builtAt: Instant?,
    /** The upcoming week projected, by season. */
    val upcoming: Map<Int, Int>,
)

/** A game's line from [team]'s side. */
public data class GameLine(val team: String, val opponent: String, val home: Boolean, val spread: Double?, val total: Double?) {
    /** Points [team] is favored by; negative when it's the underdog. nflverse's spread is the home team's. */
    val favoredBy: Double? get() = spread?.let { if (home) it else -it }
}
```

- [ ] **Step 4: Points and families**

`core/projections/src/main/kotlin/dev/gridiron/core/projections/ProjectedPoints.kt`:

```kotlin
package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.statquery.Component

public data class ProjectedPoints(val points: Double, val floor: Double, val ceiling: Double)

/** The metric registry's `dist_family` as the simulation's family; unknown or missing is gamma. */
public fun familyOf(name: String?): DistributionFamily = when (name) {
    "negbinom" -> DistributionFamily.NEGBINOM
    "binomial" -> DistributionFamily.BINOMIAL
    "poisson" -> DistributionFamily.POISSON
    else -> DistributionFamily.GAMMA
}

/**
 * A projection's points under [profile]: the projected means scored, and the
 * floor and ceiling (10th and 90th percentiles) from simulating each stat
 * from its own distribution family. Fewer [draws] for long lists.
 */
public fun projectPoints(
    components: List<ProjectionComponent>,
    profile: ScoringProfile,
    position: Position?,
    draws: Int = 10_000,
): ProjectedPoints {
    val means = components.associate { Component(it.metricId) to it.mean }
    val specs = components.map { DistributionSpec(Component(it.metricId), familyOf(it.family), it.mean, it.variance) }
    val simulated = simulate(specs, profile, position, draws)
    return ProjectedPoints(score(means, profile, position), simulated.p10, simulated.p90)
}
```

- [ ] **Step 5: Queries**

In `ProjectionQueries.kt`, change `weekly`'s non-empty query to join the registry (the empty-set query gains the same column so both shapes match):

```kotlin
            return SqlQuery(
                "SELECT player_id, metric_id, stage, mean, variance, NULL AS dist_family " +
                    "FROM player_week_projection WHERE 0 = 1",
                emptyList(),
            )
```

```kotlin
        return SqlQuery(
            """
            SELECT p.player_id, p.metric_id, p.stage, p.mean, p.variance, m.dist_family
            FROM player_week_projection p
            LEFT JOIN metric m ON m.id = p.metric_id
            WHERE p.player_id IN (${placeholders(ids.size)}) AND p.season = ? AND p.week = ?
            """.trimIndent(),
            ids.map { Bind.Text(it) } + listOf(Bind.Integer(season.toLong()), Bind.Integer(week.toLong())),
        )
```

Do the same for `ros` (empty-set query `SELECT player_id, metric_id, mean, variance, NULL AS dist_family FROM player_ros_projection WHERE 0 = 1`):

```kotlin
        return SqlQuery(
            """
            SELECT r.player_id, r.metric_id, r.mean, r.variance, m.dist_family
            FROM player_ros_projection r
            LEFT JOIN metric m ON m.id = r.metric_id
            WHERE r.player_id IN (${placeholders(ids.size)}) AND r.season = ?
              AND r.as_of_week = (SELECT MAX(as_of_week) FROM player_ros_projection WHERE season = ?)
            """.trimIndent(),
            ids.map { Bind.Text(it) } + listOf(Bind.Integer(season.toLong()), Bind.Integer(season.toLong())),
        )
```

and add to the object:

```kotlin
    /** Every player's final projection for one week, with who he is. */
    public fun weekAll(season: Int, week: Int): SqlQuery = SqlQuery(
        """
        SELECT p.player_id, pl.full_name, pl.position, pl.team, p.metric_id, p.mean, p.variance, m.dist_family
        FROM player_week_projection p
        JOIN player pl ON pl.player_id = p.player_id
        LEFT JOIN metric m ON m.id = p.metric_id
        WHERE p.season = ? AND p.week = ? AND p.stage = 'final'
        """.trimIndent(),
        listOf(Bind.Integer(season.toLong()), Bind.Integer(week.toLong())),
    )

    /** Every player's rest of season as of the latest week it was built for. */
    public fun rosAll(season: Int): SqlQuery = SqlQuery(
        """
        SELECT r.player_id, pl.full_name, pl.position, pl.team, r.metric_id, r.mean, r.variance, m.dist_family
        FROM player_ros_projection r
        JOIN player pl ON pl.player_id = r.player_id
        LEFT JOIN metric m ON m.id = r.metric_id
        WHERE r.season = ? AND r.as_of_week = (SELECT MAX(as_of_week) FROM player_ros_projection WHERE season = ?)
        """.trimIndent(),
        listOf(Bind.Integer(season.toLong()), Bind.Integer(season.toLong())),
    )

    /** The forecast's `schema_meta` keys: status, build time, upcoming week per season. */
    public fun status(): SqlQuery = SqlQuery("SELECT key, value FROM schema_meta WHERE key LIKE 'forecast%'", emptyList())

    public fun game(season: Int, week: Int, team: String): SqlQuery = SqlQuery(
        """
        SELECT home_team, away_team, spread_line, total_line FROM game
        WHERE season = ? AND week = ? AND game_type = 'REG' AND (home_team = ? OR away_team = ?)
        """.trimIndent(),
        listOf(Bind.Integer(season.toLong()), Bind.Integer(week.toLong()), Bind.Text(team), Bind.Text(team)),
    )

    /** Regular-season games [team] plays from [fromWeek] on: rest of season's divisor for points per game. */
    public fun remainingGames(season: Int, fromWeek: Int, team: String): SqlQuery = SqlQuery(
        "SELECT COUNT(*) FROM game WHERE season = ? AND week >= ? AND game_type = 'REG' AND (home_team = ? OR away_team = ?)",
        listOf(Bind.Integer(season.toLong()), Bind.Integer(fromWeek.toLong()), Bind.Text(team), Bind.Text(team)),
    )
```

- [ ] **Step 6: Run the core tests**

Run: `./gradlew :core:projections:test`
Expected: PASS.

- [ ] **Step 7: Write the failing repository tests**

In `ProjectionsRepositoryTest`, add these statements to the fixture's `CREATE TABLE` list (before the inserts):

```kotlin
                st.executeUpdate("CREATE TABLE metric (id TEXT PRIMARY KEY, dist_family TEXT)")
                st.executeUpdate("CREATE TABLE player (player_id TEXT PRIMARY KEY, full_name TEXT NOT NULL, position TEXT, team TEXT)")
                st.executeUpdate("CREATE TABLE schema_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                st.executeUpdate(
                    """CREATE TABLE game (game_id TEXT PRIMARY KEY, season INTEGER NOT NULL, week INTEGER NOT NULL,
                         game_type TEXT NOT NULL, home_team TEXT NOT NULL, away_team TEXT NOT NULL,
                         spread_line REAL, total_line REAL)""",
                )
```

and add these tests:

```kotlin
    @Test
    fun `projections carry each stat's distribution family`() = runTest {
        jdbcFixtureWithSchema(
            listOf(
                "INSERT INTO metric VALUES ('targets', 'negbinom')",
                "INSERT INTO player_week_projection VALUES ('P1', 2026, 3, 'targets', 'final', 7.2, 4.0)",
                "INSERT INTO player_week_projection VALUES ('P1', 2026, 3, 'mystery', 'final', 1.0, 1.0)",
            ),
        ).use { executor ->
            val final = ProjectionsRepository(executor).projections(ProjectionsRequest(setOf("P1"), 2026, 3)).single().final
            assertEquals("negbinom", final.single { it.metricId == "targets" }.family)
            assertEquals(null, final.single { it.metricId == "mystery" }.family)
        }
    }

    @Test
    fun `status reads the forecast's outcome, build time and upcoming week`() = runTest {
        jdbcFixtureWithSchema(
            listOf(
                "INSERT INTO schema_meta VALUES ('forecast_status', 'ok')",
                "INSERT INTO schema_meta VALUES ('forecast_built_at', '2026-09-22T11:02:00Z')",
                "INSERT INTO schema_meta VALUES ('forecast_week:2026', '4')",
                "INSERT INTO schema_meta VALUES ('seasons', '2026')",
            ),
        ).use { executor ->
            val status = ProjectionsRepository(executor).status()
            assertEquals("ok", status.status)
            assertEquals(Instant.parse("2026-09-22T11:02:00Z"), status.builtAt)
            assertEquals(mapOf(2026 to 4), status.upcoming)
        }
    }

    @Test
    fun `a database built before projections existed has no status`() = runTest {
        jdbcFixtureWithSchema(emptyList()).use { executor ->
            assertEquals(ForecastStatus(null, null, emptyMap()), ProjectionsRepository(executor).status())
        }
    }

    @Test
    fun `weekAll and rosAll list final projections with who each player is`() = runTest {
        jdbcFixtureWithSchema(
            listOf(
                "INSERT INTO player VALUES ('P1', 'Pat One', 'WR', 'KC')",
                "INSERT INTO player_week_projection VALUES ('P1', 2026, 4, 'targets', 'baseline', 6.0, 3.0)",
                "INSERT INTO player_week_projection VALUES ('P1', 2026, 4, 'targets', 'final', 7.0, 4.0)",
                "INSERT INTO player_ros_projection VALUES ('P1', 2026, 2, 'targets', 90.0, 40.0)",
                "INSERT INTO player_ros_projection VALUES ('P1', 2026, 3, 'targets', 80.0, 35.0)",
            ),
        ).use { executor ->
            val repo = ProjectionsRepository(executor)
            val week = repo.weekAll(2026, 4).single()
            assertEquals("Pat One", week.name)
            assertEquals("WR", week.position)
            assertEquals(listOf(7.0), week.components.map { it.mean })
            assertEquals(listOf(80.0), repo.rosAll(2026).single().components.map { it.mean })
        }
    }

    @Test
    fun `a game's line and the games left are read from the team's side`() = runTest {
        jdbcFixtureWithSchema(
            listOf(
                "INSERT INTO game VALUES ('g4', 2026, 4, 'REG', 'BUF', 'KC', 2.5, 47.5)",
                "INSERT INTO game VALUES ('g5', 2026, 5, 'REG', 'KC', 'DEN', NULL, NULL)",
                "INSERT INTO game VALUES ('g19', 2026, 19, 'WC', 'KC', 'MIA', NULL, NULL)",
            ),
        ).use { executor ->
            val repo = ProjectionsRepository(executor)
            val line = repo.game(2026, 4, "KC")!!
            assertEquals("BUF", line.opponent)
            assertEquals(false, line.home)
            assertEquals(-2.5, line.favoredBy)
            assertEquals(47.5, line.total)
            assertEquals(null, repo.game(2026, 6, "KC"))
            assertEquals(2, repo.remainingGames(2026, 4, "KC"))
        }
    }
```

(Imports: `dev.gridiron.core.projections.ForecastStatus`, `java.time.Instant`.)

- [ ] **Step 8: Run them to verify they fail**

Run: `./gradlew :core:data:test --tests "dev.gridiron.core.data.ProjectionsRepositoryTest"`
Expected: FAIL to compile (`status`, `weekAll`, `game` unresolved).

- [ ] **Step 9: Repository**

In `ProjectionsRepository.kt`:

- In `projections`, give `Row` a `val family: String?`, read it with `it.textOrNull(5)`, and pass it on: `ProjectionComponent(it.metricId, it.mean, it.variance, it.family)` in both the baseline and final maps.
- In `rosProjections`, the same with `it.textOrNull(4)`.
- Add (imports `dev.gridiron.core.database.doubleOrNull`, `dev.gridiron.core.projections.ForecastStatus`, `dev.gridiron.core.projections.GameLine`, `dev.gridiron.core.projections.ListedProjection`, `dev.gridiron.core.statquery.SqlQuery`, `java.time.Instant`):

```kotlin
    /** How the last refresh's forecast went; a null status means the database predates projections. */
    public suspend fun status(): ForecastStatus {
        val meta = executor.query(ProjectionQueries.status()) { it.text(0) to it.text(1) }.toMap()
        val upcoming = meta.mapNotNull { (key, value) ->
            val season = key.takeIf { it.startsWith("forecast_week:") }?.substringAfter(':')?.toIntOrNull()
            val week = value.toIntOrNull()
            if (season != null && week != null) season to week else null
        }.toMap()
        return ForecastStatus(
            status = meta["forecast_status"],
            builtAt = meta["forecast_built_at"]?.let { runCatching { Instant.parse(it) }.getOrNull() },
            upcoming = upcoming,
        )
    }

    public suspend fun weekAll(season: Int, week: Int): List<ListedProjection> = listed(ProjectionQueries.weekAll(season, week))

    public suspend fun rosAll(season: Int): List<ListedProjection> = listed(ProjectionQueries.rosAll(season))

    /** [team]'s regular-season game that week, or null on a bye. */
    public suspend fun game(season: Int, week: Int, team: String): GameLine? =
        executor.query(ProjectionQueries.game(season, week, team)) { row ->
            val home = row.text(0)
            val away = row.text(1)
            GameLine(team, if (team == home) away else home, team == home, row.doubleOrNull(2), row.doubleOrNull(3))
        }.firstOrNull()

    public suspend fun remainingGames(season: Int, fromWeek: Int, team: String): Int =
        executor.query(ProjectionQueries.remainingGames(season, fromWeek, team)) { it.long(0).toInt() }.single()

    private suspend fun listed(query: SqlQuery): List<ListedProjection> {
        data class Row(val playerId: String, val name: String, val position: String?, val team: String?, val component: ProjectionComponent)

        val rows = executor.query(query) {
            Row(it.text(0), it.text(1), it.textOrNull(2), it.textOrNull(3), ProjectionComponent(it.text(4), it.double(5), it.double(6), it.textOrNull(7)))
        }
        return rows.groupBy { it.playerId }.map { (playerId, playerRows) ->
            val first = playerRows.first()
            ListedProjection(playerId, first.name, first.position, first.team, playerRows.map { it.component })
        }
    }
```

- [ ] **Step 10: Run the repository tests**

Run: `./gradlew :core:data:test`
Expected: PASS.

- [ ] **Step 11: The ViewModel simulates each stat's real family**

In `ProjectionsViewModel.kt`, replace the block from the comment `// Every component simulated as Gamma pending per-metric dist_family` through the `distributions` declaration with:

```kotlin
                val distributions = mergedComponents.map {
                    DistributionSpec(Component(it.metricId), familyOf(it.family), it.mean, it.variance)
                }
```

Replace the import `dev.gridiron.core.projections.DistributionFamily` with `dev.gridiron.core.projections.familyOf`. In the comment above the merge (`// Today's ETL only ships a final-stage row for a handful of…`), change the first sentence to `// A component the final stage left untouched may have no final row;` and keep the rest.

In `ProjectionsViewModelTest`, every row the fake executor returns for the weekly query gains a sixth, `null` column (the distribution family), because `ProjectionQueries.weekly` now selects six columns.

- [ ] **Step 12: The route uses the active profile and the player's real position**

Replace the body of `ProjectionsRoute.kt` from `@Composable` down with:

```kotlin
/** Hosts [WaterfallCard] for one player's week, scored with the active profile and the player's own position. */
@Composable
public fun ProjectionsRoute(
    playerId: String,
    season: Int,
    week: Int,
    repository: ProjectionsRepository,
    scoring: ScoringRepository,
    players: PlayerDirectory?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: ProjectionsViewModel = viewModel(factory = ProjectionsViewModel.factory(repository))
    val state by vm.state.collectAsStateWithLifecycle()
    val profile by scoring.active.collectAsStateWithLifecycle(initialValue = null)

    LaunchedEffect(playerId, season, week, profile) {
        val active = profile ?: return@LaunchedEffect
        val position = players?.header(playerId)?.position?.let(Position::fromCode)
        vm.load(playerId, season, week, active, position)
    }
```

keeping the existing `Surface { … }` block after it unchanged. Replace the import `dev.gridiron.core.model.ScoringPresets` with `dev.gridiron.core.model.Position`, and add `dev.gridiron.core.data.PlayerDirectory`, `dev.gridiron.core.data.ScoringRepository` and `dev.gridiron.core.model.ScoringProfile` (for the `null` initial value's type, write `collectAsStateWithLifecycle<ScoringProfile?>(initialValue = null)` if inference needs it).

In `GridironNavHost.kt`, replace the `ProjectionsKey` entry and the comment above it with:

```kotlin
                    entry<ProjectionsKey> { key ->
                        ProjectionsRoute(key.playerId, key.season, key.week, deps.projections, deps.scoring, deps.players, onBack = back)
                    }
```

- [ ] **Step 13: Run every affected module's tests**

Run: `./gradlew :core:projections:test :core:data:test :feature:projections:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 14: Commit**

```bash
git add core/projections core/data feature/projections app/src/main/kotlin/dev/gridiron/app/GridironNavHost.kt
git commit -m "projections: real distribution families, list and status queries, the active scoring profile"
```

---
### Task 10: ☰ → Projections list

**Files:**
- Create: `feature/projections/src/main/kotlin/dev/gridiron/feature/projections/ProjectionListViewModel.kt`
- Create: `feature/projections/src/main/kotlin/dev/gridiron/feature/projections/ProjectionListScreen.kt`
- Modify: `app/src/main/kotlin/dev/gridiron/app/NavKeys.kt`
- Modify: `app/src/main/kotlin/dev/gridiron/app/GridironNavHost.kt`
- Test: `feature/projections/src/test/kotlin/dev/gridiron/feature/projections/ProjectionListTest.kt`
- Test: `feature/projections/src/test/kotlin/dev/gridiron/feature/projections/ProjectionListViewModelTest.kt`
- Test: `feature/projections/src/test/kotlin/dev/gridiron/feature/projections/ProjectionListScreenTest.kt`

**Interfaces:**
- Consumes: `ProjectionsRepository.status/weekAll/rosAll`, `ListedProjection`, `projectPoints` (Task 9); `ScoringRepository.active`; `LiveRepository.badges: Flow<Map<String, String>>` (player id → ESPN status abbreviation: Q, D, O, IR).
- Produces: `ProjectionRow`, `PositionTab`, `ProjectionListState`, `visibleRows`, `statusLine`, `ProjectionListViewModel`, `ProjectionListRoute(season, repository, scoring, badges, onPlayer, onBack)`, `ProjectionListScreen(state, badges, onPlayer, onBack)`, and `ProjectionListKey(season: Int)` in the app.

- [ ] **Step 1: Write the failing tests**

`feature/projections/src/test/kotlin/dev/gridiron/feature/projections/ProjectionListTest.kt`:

```kotlin
package dev.gridiron.feature.projections

import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.projections.ListedProjection
import dev.gridiron.core.projections.ProjectionComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class ProjectionListTest {
    private fun row(id: String, position: String, points: Double) =
        ProjectionRow(id, "Player $id", position, "KC", points, points - 5, points + 5)

    private val rows = listOf(row("q", "QB", 20.0), row("r", "RB", 14.0), row("w", "WR", 16.0), row("t", "TE", 9.0))

    @Test
    fun `a tab shows its positions, best first`() {
        assertEquals(listOf("w", "r", "t"), visibleRows(rows, PositionTab.FLEX, emptyMap(), week = true).map { it.playerId })
        assertEquals(listOf("q"), visibleRows(rows, PositionTab.QB, emptyMap(), week = true).map { it.playerId })
    }

    @Test
    fun `an Out or IR player scores zero this week but keeps his rest of season`() {
        val badges = mapOf("w" to "O", "r" to "Q")

        val week = visibleRows(rows, PositionTab.FLEX, badges, week = true)
        assertEquals(listOf("r", "t", "w"), week.map { it.playerId })
        assertTrue(week.last().out)
        assertEquals(0.0, week.last().points, 0.0)

        assertEquals(16.0, visibleRows(rows, PositionTab.WR, badges, week = false).single().points, 0.0)
    }

    @Test
    fun `the status line names the week and when it was built`() {
        assertEquals(
            "Projections for week 4 · built Tue 11:02 AM",
            statusLine(4, Instant.parse("2026-09-22T11:02:00Z"), ZoneOffset.UTC),
        )
        assertEquals("Projections for week 4", statusLine(4, null, ZoneOffset.UTC))
    }

    @Test
    fun `listed projections are scored with the profile and each player's position`() {
        val listed = listOf(
            ListedProjection("w", "Wide Out", "WR", "KC", listOf(ProjectionComponent("receptions", 5.0, 5.0, "binomial"), ProjectionComponent("receiving_yards", 60.0, 900.0, "gamma"))),
            ListedProjection("k", "Kicker", null, "KC", listOf(ProjectionComponent("receptions", 1.0, 1.0))),
        )

        val scored = toRows(listed, ScoringPresets.PPR)

        assertEquals(listOf("w"), scored.map { it.playerId }) // no position, no row
        assertEquals(11.0, scored.single().points, 1e-9)
        assertTrue(scored.single().floor < 11.0 && scored.single().ceiling > 11.0)
    }
}
```

`feature/projections/src/test/kotlin/dev/gridiron/feature/projections/ProjectionListViewModelTest.kt`:

```kotlin
package dev.gridiron.feature.projections

import dev.gridiron.core.data.ProjectionsRepository
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

private class ListRow(private val columns: List<Any?>) : ResultRow {
    override fun isNull(index: Int): Boolean = columns[index] == null
    override fun text(index: Int): String = columns[index] as String
    override fun long(index: Int): Long = (columns[index] as Number).toLong()
    override fun double(index: Int): Double = (columns[index] as Number).toDouble()
}

/** Answers the list's queries by table: `schema_meta`, then the week's and rest of season's rows. */
private class ListExecutor(
    private val meta: List<Pair<String, String>>,
    private val week: List<List<Any?>> = emptyList(),
    private val ros: List<List<Any?>> = emptyList(),
) : QueryExecutor {
    override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> {
        val rows = when {
            "schema_meta" in query.sql -> meta.map { listOf(it.first, it.second) }
            "FROM player_week_projection" in query.sql -> week
            "FROM player_ros_projection" in query.sql -> ros
            else -> emptyList()
        }
        return rows.map { map(ListRow(it)) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectionListViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `lists the upcoming week and rest of season`() = runTest(dispatcher) {
        val executor = ListExecutor(
            meta = listOf("forecast_status" to "ok", "forecast_week:2026" to "4"),
            week = listOf(listOf("w", "Wide Out", "WR", "KC", "receptions", 5.0, 5.0, "binomial")),
            ros = listOf(listOf("w", "Wide Out", "WR", "KC", "receptions", 60.0, 50.0, "binomial")),
        )
        val vm = ProjectionListViewModel(ProjectionsRepository(executor), dispatcher)

        vm.load(2026, ScoringPresets.PPR)
        advanceUntilIdle()

        val loaded = vm.state.value as ProjectionListState.Loaded
        assertEquals(4, loaded.week)
        assertEquals(5.0, loaded.weekRows.single().points, 1e-9)
        assertEquals(60.0, loaded.rosRows.single().points, 1e-9)
    }

    @Test
    fun `says why there's nothing to list`() = runTest(dispatcher) {
        suspend fun message(meta: List<Pair<String, String>>, season: Int = 2026): String {
            val vm = ProjectionListViewModel(ProjectionsRepository(ListExecutor(meta)), dispatcher)
            vm.load(season, ScoringPresets.PPR)
            advanceUntilIdle()
            return (vm.state.value as ProjectionListState.Unavailable).message
        }

        assertEquals("No projections yet. Refresh stats to build them.", message(emptyList()))
        assertEquals("Projections unavailable: no schedule.", message(listOf("forecast_status" to "no schedule")))
        assertEquals("No upcoming games in 2026.", message(listOf("forecast_status" to "ok")))
    }
}
```

`feature/projections/src/test/kotlin/dev/gridiron/feature/projections/ProjectionListScreenTest.kt`:

```kotlin
package dev.gridiron.feature.projections

import androidx.compose.ui.test.assertIsDisplayed
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
class ProjectionListScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val loaded = ProjectionListState.Loaded(
        week = 4,
        builtAt = null,
        weekRows = listOf(
            ProjectionRow("w", "Wide Out", "WR", "KC", 16.2, 9.1, 25.4),
            ProjectionRow("q", "Quarter Back", "QB", "KC", 21.0, 14.0, 29.0),
        ),
        rosRows = listOf(ProjectionRow("w", "Wide Out", "WR", "KC", 180.0, 140.0, 220.0)),
    )

    @Test
    fun `shows FLEX rows for the week and switches to rest of season`() {
        compose.setContent { GridironTheme { ProjectionListScreen(loaded, emptyMap(), onPlayer = {}, onBack = {}) } }

        compose.onNodeWithText("Projections for week 4").assertIsDisplayed()
        compose.onNodeWithText("16.2").assertIsDisplayed()
        compose.onNodeWithText("9.1–25.4").assertIsDisplayed()
        compose.onNodeWithText("Rest of season").performClick()
        compose.onNodeWithText("180.0").assertIsDisplayed()
    }

    @Test
    fun `an Out player reads Out, and a row opens the player`() {
        var opened: String? = null
        compose.setContent { GridironTheme { ProjectionListScreen(loaded, mapOf("w" to "O"), onPlayer = { opened = it }, onBack = {}) } }

        compose.onNodeWithText("Out").assertIsDisplayed()
        compose.onNodeWithText("Wide Out").performClick()
        assertEquals("w", opened)
    }

    @Test
    fun `an unavailable forecast says why`() {
        compose.setContent {
            GridironTheme { ProjectionListScreen(ProjectionListState.Unavailable("No upcoming games in 2026."), emptyMap(), onPlayer = {}, onBack = {}) }
        }
        compose.onNodeWithText("No upcoming games in 2026.").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :feature:projections:testDebugUnitTest`
Expected: FAIL to compile (`ProjectionRow`, `ProjectionListScreen` unresolved).

- [ ] **Step 3: The ViewModel and its pure helpers**

`feature/projections/src/main/kotlin/dev/gridiron/feature/projections/ProjectionListViewModel.kt`:

```kotlin
package dev.gridiron.feature.projections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.projections.ListedProjection
import dev.gridiron.core.projections.projectPoints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

public data class ProjectionRow(
    val playerId: String,
    val name: String,
    val position: String,
    val team: String?,
    val points: Double,
    val floor: Double,
    val ceiling: Double,
    /** ESPN lists him Out or on IR: this week's points show as zero. */
    val out: Boolean = false,
)

public enum class PositionTab(public val label: String, public val codes: Set<String>) {
    QB("QB", setOf("QB")),
    RB("RB", setOf("RB")),
    WR("WR", setOf("WR")),
    TE("TE", setOf("TE")),
    FLEX("FLEX", setOf("RB", "WR", "TE")),
}

public sealed interface ProjectionListState {
    public data object Loading : ProjectionListState

    public data class Unavailable(val message: String) : ProjectionListState

    public data class Loaded(
        val week: Int,
        val builtAt: Instant?,
        val weekRows: List<ProjectionRow>,
        val rosRows: List<ProjectionRow>,
    ) : ProjectionListState
}

private val OUT = setOf("O", "IR")

/** The rows a tab shows, best first. In [week] mode an Out or IR player scores zero; rest of season keeps his projection. */
public fun visibleRows(rows: List<ProjectionRow>, tab: PositionTab, badges: Map<String, String>, week: Boolean): List<ProjectionRow> =
    rows.filter { it.position in tab.codes }
        .map { row -> if (week && badges[row.playerId] in OUT) row.copy(points = 0.0, floor = 0.0, ceiling = 0.0, out = true) else row }
        .sortedByDescending { it.points }

private val BUILT = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)

/** "Projections for week 4 · built Tue 7:02 AM". */
public fun statusLine(week: Int, builtAt: Instant?, zone: ZoneId = ZoneId.systemDefault()): String {
    val built = builtAt?.let { " · built " + BUILT.format(it.atZone(zone)) }.orEmpty()
    return "Projections for week $week$built"
}

/** A list scores hundreds of players, so it simulates each with fewer draws than the single-player waterfall. */
private const val LIST_DRAWS = 2_000

internal fun toRows(listed: List<ListedProjection>, profile: ScoringProfile): List<ProjectionRow> = listed.mapNotNull { p ->
    val position = p.position ?: return@mapNotNull null
    val points = projectPoints(p.components, profile, Position.fromCode(position), draws = LIST_DRAWS)
    ProjectionRow(p.playerId, p.name, position, p.team, points.points, points.floor, points.ceiling)
}

/** Loads the upcoming week's and rest of season's projections and scores them with the active profile. */
public class ProjectionListViewModel(
    private val repository: ProjectionsRepository,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow<ProjectionListState>(ProjectionListState.Loading)
    public val state: StateFlow<ProjectionListState> = _state.asStateFlow()

    // A load superseded by a newer one (a profile switch mid-load) must not overwrite it.
    private var latest = 0

    public fun load(season: Int, profile: ScoringProfile) {
        val request = ++latest
        _state.value = ProjectionListState.Loading
        viewModelScope.launch {
            val next = try {
                build(season, profile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ProjectionListState.Unavailable("Couldn't load projections: ${e.message}.")
            }
            if (request == latest) _state.value = next
        }
    }

    private suspend fun build(season: Int, profile: ScoringProfile): ProjectionListState {
        val status = repository.status()
        val week = status.upcoming[season]
        return when {
            status.status == null -> ProjectionListState.Unavailable("No projections yet. Refresh stats to build them.")
            status.status != "ok" -> ProjectionListState.Unavailable("Projections unavailable: ${status.status}.")
            week == null -> ProjectionListState.Unavailable("No upcoming games in $season.")
            else -> {
                val weekListed = repository.weekAll(season, week)
                val rosListed = repository.rosAll(season)
                withContext(compute) {
                    ProjectionListState.Loaded(week, status.builtAt, toRows(weekListed, profile), toRows(rosListed, profile))
                }
            }
        }
    }

    public companion object {
        public fun factory(repository: ProjectionsRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { ProjectionListViewModel(repository) } }
    }
}
```

- [ ] **Step 4: The screen**

`feature/projections/src/main/kotlin/dev/gridiron/feature/projections/ProjectionListScreen.kt`:

```kotlin
package dev.gridiron.feature.projections

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.model.ScoringProfile
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/** ☰ → Projections: the upcoming week or rest of season, by position, scored with the active profile. */
@Composable
public fun ProjectionListRoute(
    season: Int,
    repository: ProjectionsRepository,
    scoring: ScoringRepository,
    badges: Flow<Map<String, String>>,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
) {
    val vm: ProjectionListViewModel = viewModel(factory = ProjectionListViewModel.factory(repository))
    val state by vm.state.collectAsStateWithLifecycle()
    val profile by scoring.active.collectAsStateWithLifecycle<ScoringProfile?>(initialValue = null)
    val injuries by badges.collectAsStateWithLifecycle(initialValue = emptyMap())
    LaunchedEffect(season, profile) { profile?.let { vm.load(season, it) } }
    ProjectionListScreen(state, injuries, onPlayer, onBack)
}

@Composable
public fun ProjectionListScreen(
    state: ProjectionListState,
    badges: Map<String, String>,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(PositionTab.FLEX) }
    var weekMode by rememberSaveable { mutableStateOf(true) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("Projections", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            when (state) {
                ProjectionListState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is ProjectionListState.Unavailable -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                }
                is ProjectionListState.Loaded -> {
                    Text(
                        statusLine(state.week, state.builtAt),
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = weekMode, onClick = { weekMode = true }, label = { Text("Week ${state.week}") })
                        FilterChip(selected = !weekMode, onClick = { weekMode = false }, label = { Text("Rest of season") })
                    }
                    Row(
                        Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (t in PositionTab.entries) FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) })
                    }
                    val rows = visibleRows(if (weekMode) state.weekRows else state.rosRows, tab, badges, weekMode)
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(rows, key = { _, row -> row.playerId }) { i, row ->
                            ProjectionListRow(i + 1, row, badges[row.playerId], onPlayer)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectionListRow(rank: Int, row: ProjectionRow, badge: String?, onPlayer: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onPlayer(row.playerId) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$rank", Modifier.width(32.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                badge?.takeIf { it != "A" }?.let {
                    Text(
                        "  $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (it in setOf("O", "IR", "D")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Text(
                listOfNotNull(row.position, row.team).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(if (row.out) "Out" else points(row.points), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            if (!row.out) {
                Text(
                    "${points(row.floor)}–${points(row.ceiling)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun points(value: Double): String = String.format(Locale.US, "%.1f", value)
```

- [ ] **Step 5: Run the feature tests**

Run: `./gradlew :feature:projections:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Wire it into the app**

`NavKeys.kt`, after `ProjectionsKey`:

```kotlin
@Serializable data class ProjectionListKey(val season: Int) : NavKey
```

`GridironNavHost.kt`:

- In the Grid's `menu`, make this the first entry (before `News`):

```kotlin
                                add("Projections" to { s: Int -> backStack.push(ProjectionListKey(s)) })
```

- Add after the `ProjectionsKey` entry:

```kotlin
                    entry<ProjectionListKey> { key ->
                        ProjectionListRoute(
                            key.season, deps.projections, deps.scoring, deps.live?.badges ?: flowOf(emptyMap()),
                            onPlayer = { backStack.push(PlayerKey(it)) }, onBack = back,
                        )
                    }
```

(Import `dev.gridiron.feature.projections.ProjectionListRoute`.) If `NavigationTest` asserts the exact menu, add "Projections" to its expectation.

- [ ] **Step 7: Run the app tests**

Run: `./gradlew :app:testDebugUnitTest :feature:projections:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add feature/projections app/src/main/kotlin/dev/gridiron/app
git commit -m "projections: ☰ → Projections list by position, week or rest of season"
```

---
### Task 11: The Player page's "This week" card

**Files:**
- Create: `feature/projections/src/main/kotlin/dev/gridiron/feature/projections/ProjectionCard.kt`
- Modify: `app/src/main/kotlin/dev/gridiron/app/PlayerScreen.kt`
- Modify: `app/src/main/kotlin/dev/gridiron/app/GridironNavHost.kt` (the `PlayerKey` entry)
- Test: `feature/projections/src/test/kotlin/dev/gridiron/feature/projections/ProjectionCardTest.kt`
- Test: `feature/projections/src/test/kotlin/dev/gridiron/feature/projections/ThisWeekCardTest.kt`
- Test: `app/src/test/kotlin/dev/gridiron/app/LiveScreensTest.kt`

**Interfaces:**
- Consumes: `ProjectionsRepository.status/projections/rosProjections/game/remainingGames`, `projectPoints`, `GameLine` (Task 9); `score` (`:core:projections`); `LiveStatus.abbr`.
- Produces: `ProjectionCard` (data class), `loadProjectionCard(repository, playerId, team, profile, position, injuryAbbr): ProjectionCard?`, `matchupText(line: GameLine): String`, `lineText(line: GameLine): String?`, `ThisWeekCard(card, onOpen, modifier)`; `PlayerPage.projection: ProjectionCard?` (default null); `PlayerRoute(…, projections: ProjectionsRepository? = null, scoring: ScoringRepository? = null, onProjection: (season: Int, week: Int) -> Unit = { _, _ -> })`; `PlayerScreen(…, onProjection: (Int, Int) -> Unit = { _, _ -> })`.

- [ ] **Step 1: Write the failing tests**

`feature/projections/src/test/kotlin/dev/gridiron/feature/projections/ProjectionCardTest.kt`:

```kotlin
package dev.gridiron.feature.projections

import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.ResultRow
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.projections.GameLine
import dev.gridiron.core.statquery.SqlQuery
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class CardRow(private val columns: List<Any?>) : ResultRow {
    override fun isNull(index: Int): Boolean = columns[index] == null
    override fun text(index: Int): String = columns[index] as String
    override fun long(index: Int): Long = (columns[index] as Number).toLong()
    override fun double(index: Int): Double = (columns[index] as Number).toDouble()
}

/** Answers the card's queries by what they read. KC is at BUF in week 4, three games left. */
private class CardExecutor(private val status: String = "ok") : QueryExecutor {
    override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> {
        val sql = query.sql
        val rows: List<List<Any?>> = when {
            "schema_meta" in sql -> listOf(listOf("forecast_status", status), listOf("forecast_week:2026", "4"))
            "player_week_projection_factor" in sql -> emptyList()
            "FROM player_week_projection" in sql -> listOf(
                listOf("W1", "receptions", "final", 5.0, 5.0, "binomial"),
                listOf("W1", "receiving_yards", "final", 60.0, 900.0, "gamma"),
            )
            "FROM player_ros_projection" in sql -> listOf(
                listOf("W1", "receptions", 40.0, 30.0, "binomial"),
                listOf("W1", "receiving_yards", 480.0, 5000.0, "gamma"),
            )
            "COUNT(*)" in sql -> listOf(listOf(3L))
            "FROM game" in sql -> listOf(listOf("BUF", "KC", 2.5, 47.5))
            else -> emptyList()
        }
        return rows.map { map(CardRow(it)) }
    }
}

class ProjectionCardTest {
    @Test
    fun `the card scores this week and rest of season with the profile`() = runTest {
        val card = loadProjectionCard(ProjectionsRepository(CardExecutor()), "W1", "KC", ScoringPresets.PPR, Position.WR, injuryAbbr = "Q")!!

        assertEquals(2026, card.season)
        assertEquals(4, card.week)
        assertEquals("@ BUF", card.matchup)
        assertEquals("KC +2.5 · O/U 47.5", card.line)
        assertEquals(11.0, card.points, 1e-9)
        assertTrue(card.floor < 11.0 && card.ceiling > 11.0)
        assertEquals(88.0, card.rosPoints!!, 1e-9)
        assertEquals(88.0 / 3, card.rosPerGame!!, 1e-9)
        assertEquals(false, card.out)
    }

    @Test
    fun `an Out or IR player's week is zero`() = runTest {
        val card = loadProjectionCard(ProjectionsRepository(CardExecutor()), "W1", "KC", ScoringPresets.PPR, Position.WR, injuryAbbr = "IR")!!
        assertTrue(card.out)
        assertEquals(0.0, card.points, 0.0)
    }

    @Test
    fun `no card without a successful forecast`() = runTest {
        assertNull(loadProjectionCard(ProjectionsRepository(CardExecutor(status = "no schedule")), "W1", "KC", ScoringPresets.PPR, Position.WR, null))
    }

    @Test
    fun `lines read from the player's team's side`() {
        assertEquals("vs KC", matchupText(GameLine("BUF", "KC", home = true, spread = 2.5, total = 47.5)))
        assertEquals("BUF −2.5 · O/U 47.5", lineText(GameLine("BUF", "KC", home = true, spread = 2.5, total = 47.5)))
        assertEquals("Pick'em · O/U 44", lineText(GameLine("BUF", "KC", home = true, spread = 0.0, total = 44.0)))
        assertEquals(null, lineText(GameLine("BUF", "KC", home = true, spread = null, total = null)))
    }
}
```

`feature/projections/src/test/kotlin/dev/gridiron/feature/projections/ThisWeekCardTest.kt`:

```kotlin
package dev.gridiron.feature.projections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.gridiron.core.designsystem.GridironTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h892dp-xxhdpi")
class ThisWeekCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val card = ProjectionCard(2026, 4, "@ BUF", "KC +2.5 · O/U 47.5", 11.0, 5.2, 18.9, 88.0, 88.0 / 3, out = false)

    @Test
    fun `shows the week's points, range and rest of season, and opens the waterfall`() {
        var opened = false
        compose.setContent { GridironTheme { ThisWeekCard(card, onOpen = { opened = true }) } }

        compose.onNodeWithText("Week 4 · @ BUF · KC +2.5 · O/U 47.5").assertIsDisplayed()
        compose.onNodeWithText("11.0 pts").assertIsDisplayed()
        compose.onNodeWithText("Floor 5.2 · Ceiling 18.9").assertIsDisplayed()
        compose.onNodeWithText("Rest of season 88.0 pts (29.3 per game)").assertIsDisplayed()
        compose.onNodeWithText("See why →").performClick()
        assertTrue(opened)
    }

    @Test
    fun `an Out player reads Out`() {
        compose.setContent { GridironTheme { ThisWeekCard(card.copy(out = true, points = 0.0), onOpen = {}) } }
        compose.onNodeWithText("Out this week").assertIsDisplayed()
    }
}
```

Add to `app/src/test/kotlin/dev/gridiron/app/LiveScreensTest.kt` (import `dev.gridiron.feature.projections.ProjectionCard`):

```kotlin
    @Test
    fun `the player page shows this week's projection and opens its waterfall`() {
        var opened: Pair<Int, Int>? = null
        val card = ProjectionCard(2026, 4, "@ BUF", null, 11.0, 5.2, 18.9, null, null, out = false)
        val page = PlayerPage(null, null, emptyList(), emptyList(), null, projection = card)
        compose.setContent {
            GridironTheme {
                PlayerScreen("P1", page, liveAvailable = true, onBack = {}, onOpen = {}, onProjection = { s, w -> opened = s to w })
            }
        }

        compose.onNodeWithText("11.0 pts").performClick()
        assertEquals(2026 to 4, opened)
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :feature:projections:testDebugUnitTest :app:testDebugUnitTest`
Expected: FAIL to compile (`ProjectionCard`, `loadProjectionCard` unresolved).

- [ ] **Step 3: The card**

`feature/projections/src/main/kotlin/dev/gridiron/feature/projections/ProjectionCard.kt`:

```kotlin
package dev.gridiron.feature.projections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.projections.GameLine
import dev.gridiron.core.projections.ProjectionsRequest
import dev.gridiron.core.projections.RosProjectionsRequest
import dev.gridiron.core.projections.projectPoints
import dev.gridiron.core.projections.score
import dev.gridiron.core.statquery.Component
import java.util.Locale
import kotlin.math.abs

/** What the Player page's "This week" card shows. */
public data class ProjectionCard(
    val season: Int,
    val week: Int,
    /** "vs DAL" or "@ DAL". */
    val matchup: String?,
    /** "KC −3.5 · O/U 47.5", or null until the line is posted. */
    val line: String?,
    val points: Double,
    val floor: Double,
    val ceiling: Double,
    val rosPoints: Double?,
    val rosPerGame: Double?,
    /** ESPN lists him Out or on IR. */
    val out: Boolean,
)

private val OUT_ABBRS = setOf("O", "IR")

/**
 * The card for [playerId] in the latest season's upcoming week, scored with
 * [profile]; null when the forecast has nothing for him (it failed, it's the
 * off-season, or he has no projection this week).
 */
public suspend fun loadProjectionCard(
    repository: ProjectionsRepository,
    playerId: String,
    team: String?,
    profile: ScoringProfile,
    position: Position?,
    injuryAbbr: String?,
): ProjectionCard? {
    val status = repository.status()
    if (status.status != "ok") return null
    val (season, week) = status.upcoming.maxByOrNull { it.key }?.toPair() ?: return null
    val final = repository.projections(ProjectionsRequest(setOf(playerId), season, week)).firstOrNull()?.final
    if (final.isNullOrEmpty()) return null
    val out = injuryAbbr in OUT_ABBRS
    val points = projectPoints(final, profile, position)
    val game = team?.let { repository.game(season, week, it) }
    val ros = repository.rosProjections(RosProjectionsRequest(setOf(playerId), season)).firstOrNull()
    val rosPoints = ros?.let { r -> score(r.components.associate { Component(it.metricId) to it.mean }, profile, position) }
    val gamesLeft = team?.let { repository.remainingGames(season, week, it) } ?: 0
    return ProjectionCard(
        season = season,
        week = week,
        matchup = game?.let(::matchupText),
        line = game?.let(::lineText),
        points = if (out) 0.0 else points.points,
        floor = if (out) 0.0 else points.floor,
        ceiling = if (out) 0.0 else points.ceiling,
        rosPoints = rosPoints,
        rosPerGame = rosPoints?.takeIf { gamesLeft > 0 }?.let { it / gamesLeft },
        out = out,
    )
}

public fun matchupText(line: GameLine): String = if (line.home) "vs ${line.opponent}" else "@ ${line.opponent}"

/** "KC −3.5 · O/U 47.5" from [GameLine.team]'s side; a favorite gets the minus sign. */
public fun lineText(line: GameLine): String? {
    val parts = buildList {
        line.favoredBy?.let { f -> add(if (f == 0.0) "Pick'em" else "${line.team} ${if (f > 0) "−" else "+"}${trim(abs(f))}") }
        line.total?.let { add("O/U ${trim(it)}") }
    }
    return parts.joinToString(" · ").ifEmpty { null }
}

private fun trim(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.1f", value)

private fun onePlace(value: Double): String = String.format(Locale.US, "%.1f", value)

/** The Player page's projection card; tapping it opens the waterfall. */
@Composable
public fun ThisWeekCard(card: ProjectionCard, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            listOfNotNull("Week ${card.week}", card.matchup, card.line).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (card.out) {
            Text("Out this week", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        } else {
            Text("${onePlace(card.points)} pts", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Floor ${onePlace(card.floor)} · Ceiling ${onePlace(card.ceiling)}", style = MaterialTheme.typography.bodySmall)
        }
        card.rosPoints?.let { ros ->
            val perGame = card.rosPerGame?.let { " (${onePlace(it)} per game)" }.orEmpty()
            Text("Rest of season ${onePlace(ros)} pts$perGame", style = MaterialTheme.typography.bodySmall)
        }
        Text("See why →", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}
```

- [ ] **Step 4: Put it on the Player page**

In `app/src/main/kotlin/dev/gridiron/app/PlayerScreen.kt`:

- `PlayerPage` gains a last property `val projection: ProjectionCard? = null`, and its KDoc becomes `/** Everything the Player page shows. */`.
- Replace `PlayerRoute` with:

```kotlin
@Composable
fun PlayerRoute(
    playerId: String,
    players: PlayerDirectory?,
    live: LiveRepository?,
    onBack: () -> Unit,
    projections: ProjectionsRepository? = null,
    scoring: ScoringRepository? = null,
    onProjection: (season: Int, week: Int) -> Unit = { _, _ -> },
) {
    val version by (live?.changes ?: NO_CHANGES).collectAsState()
    val profile by remember(scoring) { scoring?.active ?: flowOf(null) }.collectAsState(initial = null)
    var page by remember(playerId) { mutableStateOf<PlayerPage?>(null) }
    LaunchedEffect(Unit) { live?.refreshIfStale() }
    LaunchedEffect(playerId, version, profile) {
        val header = players?.header(playerId)
        val status = live?.status(playerId)
        val active = profile
        val card = if (projections != null && active != null) {
            try {
                loadProjectionCard(projections, playerId, header?.team, active, header?.position?.let(Position::fromCode), status?.abbr)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // a projection that can't be read never blocks the rest of the page
            }
        } else {
            null
        }
        page = PlayerPage(
            header = header,
            status = status,
            notes = live?.notes(playerId).orEmpty(),
            news = live?.playerNews(playerId).orEmpty(),
            asOf = live?.fetchedAt(),
            projection = card,
        )
    }
    val uri = LocalUriHandler.current
    PlayerScreen(playerId, page, liveAvailable = live != null, onBack = onBack, onOpen = { uri.openSafely(it) }, onProjection = onProjection)
}
```

- Add `onProjection: (season: Int, week: Int) -> Unit = { _, _ -> }` as `PlayerScreen`'s last parameter, and in its `LazyColumn`, between the header `item { … }` and `item { SectionTitle("Status") }`:

```kotlin
                page.projection?.let { card ->
                    item { SectionTitle("This week") }
                    item { ThisWeekCard(card, onOpen = { onProjection(card.season, card.week) }) }
                }
```

- Imports: `dev.gridiron.core.data.ProjectionsRepository`, `dev.gridiron.core.data.ScoringRepository`, `dev.gridiron.core.model.Position`, `dev.gridiron.feature.projections.ProjectionCard`, `dev.gridiron.feature.projections.ThisWeekCard`, `dev.gridiron.feature.projections.loadProjectionCard`, `kotlinx.coroutines.CancellationException`, `kotlinx.coroutines.flow.flowOf`.

In `GridironNavHost.kt`, replace the `PlayerKey` entry with:

```kotlin
                    entry<PlayerKey> { key ->
                        PlayerRoute(
                            key.playerId, deps.players, deps.live, onBack = back,
                            projections = deps.projections, scoring = deps.scoring,
                            onProjection = { season, week -> backStack.push(ProjectionsKey(key.playerId, season, week)) },
                        )
                    }
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :feature:projections:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add feature/projections app/src
git commit -m "player page: this week's projection card, opening the waterfall"
```

---
### Task 12: Real-data checks, retire the Python projection code, docs

**Files:**
- Create: `core/data/src/test/kotlin/dev/gridiron/core/data/ProjectionsContractTest.kt`
- Create: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/ForecastTimingTest.kt`
- Delete: `etl/gridiron_etl/projections.py`, `etl/gridiron_etl/shrinkage.py`, `etl/gridiron_etl/odds.py` and the tests that import them
- Modify: `etl/gridiron_etl/build.py`, `etl/gridiron_etl/schema.py`, `etl/requirements.txt`
- Modify: `CLAUDE.md`, `docs/superpowers/HANDOFF.md`

**Interfaces:**
- Consumes: everything above. CI's build job already builds the current season and the two before it with `:core:ingest:buildStatsDb`, which now runs the forecast, and points `GRIDIRON_STATS_DB` at the result.
- Produces: no new code interfaces.

- [ ] **Step 1: The contract test**

`core/data/src/test/kotlin/dev/gridiron/core/data/ProjectionsContractTest.kt`:

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.projections.projectPoints
import dev.gridiron.core.statquery.SqlQuery
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * The phone's projection reads, end to end, against the database CI builds
 * with the same Kotlin code the phone runs: the gap the old fixture-only
 * projection tests left open.
 */
@EnabledIfEnvironmentVariable(named = "GRIDIRON_STATS_DB", matches = ".+")
class ProjectionsContractTest {
    @Test
    fun `the refresh-built database projects a full week that scores sensibly`() = runTest {
        JdbcQueryExecutor(StatsDb.path!!).use { executor ->
            val repo = ProjectionsRepository(executor)
            val status = repo.status()
            assertEquals("ok", status.status)

            // In season, the upcoming week; off-season, the last week projected.
            val (season, week) = status.upcoming.maxByOrNull { it.key }?.toPair()
                ?: executor.query(
                    SqlQuery(
                        "SELECT season, MAX(week) FROM player_week_projection " +
                            "WHERE season = (SELECT MAX(season) FROM player_week_projection)",
                        emptyList(),
                    ),
                ) { it.long(0).toInt() to it.long(1).toInt() }.single()

            val scored = repo.weekAll(season, week).mapNotNull { p ->
                val position = p.position ?: return@mapNotNull null
                position to projectPoints(p.components, ScoringPresets.PPR, Position.fromCode(position), draws = 500).points
            }
            assertTrue(scored.size >= 150, "only ${scored.size} players projected for $season week $week")
            assertTrue(scored.all { it.second.isFinite() })

            fun topAverage(position: String, n: Int) = scored.filter { it.first == position }.map { it.second }.sortedDescending().take(n).average()
            // Loose sanity bands for full PPR, 4-point passing TDs: a broken layer lands far outside them.
            assertTrue(topAverage("QB", 12) in 12.0..30.0, "QB1-12 average ${topAverage("QB", 12)}")
            assertTrue(topAverage("RB", 24) in 8.0..25.0, "RB1-24 average ${topAverage("RB", 24)}")
            assertTrue(topAverage("WR", 24) in 8.0..25.0, "WR1-24 average ${topAverage("WR", 24)}")
            assertTrue(topAverage("TE", 12) in 5.0..20.0, "TE1-12 average ${topAverage("TE", 12)}")
        }
    }
}
```

- [ ] **Step 2: The timing test**

`core/forecast/src/test/kotlin/dev/gridiron/core/forecast/ForecastTimingTest.kt`:

```kotlin
package dev.gridiron.core.forecast

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.util.Locale

/**
 * Re-runs the forecast on a copy of CI's three-season database and prints
 * how long it took: the JVM stand-in for the spec's under-30-seconds-on-the-
 * phone target, which the refresh toast measures on the device.
 */
@EnabledIfEnvironmentVariable(named = "GRIDIRON_STATS_DB", matches = ".+")
class ForecastTimingTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a full forecast of the CI database finishes well under a minute`() {
        val source = File(System.getenv("GRIDIRON_STATS_DB"))
        assumeTrue(source.isFile, "no database at $source")
        val copy = source.copyTo(File(dir, "stats.db"))
        BundledSQLiteDriver().open(copy.path).use { conn ->
            for (table in listOf("player_week_projection", "player_week_projection_factor", "player_ros_projection")) {
                conn.execSQL("DELETE FROM $table")
            }
            val started = System.nanoTime()
            val report = Forecast.run(conn, Instant.now())
            val seconds = (System.nanoTime() - started) / 1e9
            println(String.format(Locale.US, "forecast: %d weeks, %d rows in %.1f s", report.weeks, report.rows, seconds))

            assertEquals(FORECAST_OK, report.status)
            assertTrue(seconds < 60, "took $seconds s")
        }
    }
}
```

- [ ] **Step 3: Run both against a real database**

Run:

```bash
./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025 2026" -Pout=etl/build/stats.db
GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test --tests "dev.gridiron.core.data.ProjectionsContractTest" :core:forecast:test --tests "dev.gridiron.core.forecast.ForecastTimingTest" -i | grep -E "forecast:|PASSED|FAILED"
```

Expected: both PASS, and a `forecast: … weeks, … rows in … s` line. Record the time in the handoff. If a sanity band fails, the model has a bug: find the layer that moved the numbers (compare `baseline` against `final` for a few top players) and fix it. Never widen a band to pass. Needs network; without it, say so in the handoff and rely on CI.

- [ ] **Step 4: Retire the Python projection code**

The Kotlin model is now the reference (spec: "The Python projections code … is deleted in sub-project 1"). Parity covers only stats tables, so nothing it checks changes.

```bash
git rm etl/gridiron_etl/projections.py etl/gridiron_etl/shrinkage.py etl/gridiron_etl/odds.py
git rm etl/tests/test_accuracy.py etl/tests/test_build_projection_guards.py etl/tests/test_build_projections.py \
  etl/tests/test_distribution_assembly.py etl/tests/test_game_script.py etl/tests/test_market_blend.py \
  etl/tests/test_matchup.py etl/tests/test_odds.py etl/tests/test_projection_schema.py \
  etl/tests/test_projections_shrinkage.py etl/tests/test_shrinkage.py etl/tests/test_volume_cascade.py \
  etl/tests/test_walk_forward_backtest.py etl/tests/test_xtd.py
```

Before deleting, confirm with `grep -ln "projections\|shrinkage\|odds\|_drop_nonfinite\|load_projection\|load_ros\|load_accuracy\|snapshot" etl/tests/*.py` that each listed file tests only projection code; keep (and report) any test of something else.

Then:
- `etl/gridiron_etl/build.py`: delete the block from `from . import projections as proj_module` through the `except Exception as exc:  # projections are additive, not a blocker` handler (inclusive), and `_drop_nonfinite` with its helper imports if nothing else uses them.
- `etl/gridiron_etl/schema.py`: delete `load_projections`, `load_projection_factors`, `load_ros_projections`, the `projection_snapshot` loader and `load_accuracy_summary` if nothing references them any more (`grep -rn` first). Keep the DDL: the Python schema stays at version 5, and the parity job ignores those tables.
- `etl/requirements.txt`: remove `numpy>=1.26` (only `projections.py` used it; `grep -rn numpy etl/` should find nothing afterwards).

Run: `cd etl && python -m pytest tests/ -q && python -m gridiron_etl.build --seasons 2025 --out build/parity/py.db && cd .. && python etl/tools/parity.py etl/build/parity/py.db etl/build/parity/kt.db`
Expected: tests PASS; parity exits 0 (rebuild `kt.db` with `./gradlew :core:ingest:buildStatsDb -Pseasons=2025 -Pout=etl/build/parity/kt.db` first if it's stale).

- [ ] **Step 5: CLAUDE.md**

Update, keeping the file's voice:

- **Module Structure → JVM Modules:** add after `:core:ingest`: "`:core:forecast` — The projection model. Reads a freshly built stats.db and writes weekly, rest-of-season and waterfall-factor projections for QB/RB/WR/TE, walk-forward (each week only from the games before it). Seven layers: team volume, shrunk share, shrunk efficiency, expected TDs, opponent ratings (ridge), game script from nflverse's lines, distributions. Every constant is in `ForecastConstants.kt`; bump `FORECAST_VERSION` when one changes."
- **`:core:ingest`**'s entry: add "downloads nflverse's schedule (`games.csv`) into the `game` table and runs `:core:forecast` after validation; a forecast failure leaves the stats and records why".
- **Data Flow:** after step 1, insert "**Forecast** — the same build projects every regular-season week of the chosen seasons into the projection tables (the upcoming week with both stages and factors, past weeks' final stage for the backtest, rest of season summed); the refresh toast says if projections are unavailable". Renumber.
- **Database Schema:** "Version 6" → "Version 7"; add a `game` row ("nflverse schedule for the built seasons: opponents, results, spread and total, starting QBs, head coaches; the forecast's matchups and game script"); delete the `projection_snapshot` and `accuracy_summary` rows; in the `player_week_projection` row, say they're written by `:core:forecast`.
- **Common Commands:** add `./gradlew :core:forecast:test`.
- **Known Gaps:** delete the bullets "Projections are hidden …", "`ProjectionsRoute` hardcodes …", "Every projection component is simulated as `DistributionFamily.GAMMA` …", and "No contract test runs real ETL-shaped projection output …" (all fixed here). In "Grid entry points", add ☰ → Projections and the Player page's "This week" card, and drop "`ProjectionsKey`/`AccuracyKey` stay registered but unreachable until on-device projections" in favor of "`AccuracyKey` stays unreachable until the accuracy sub-project". Add:
  - "**Projection model sub-projects 2–4 are not built yet**: the accuracy page (backtest), Odds API props and K/DST. See `docs/superpowers/specs/2026-09-26-projection-model-design.md`."
  - "**Not modeled:** weather (wind is only known after kickoff) and shifting an injured player's share to teammates; an Out/IR player just shows Out."

- [ ] **Step 6: HANDOFF.md**

In `docs/superpowers/HANDOFF.md`, mark sub-project 1 complete (with the timing from Step 3 and any rulings), and set **Next step** to "brainstorming is done; write the plan for sub-project 2 (accuracy page) from spec §4 with the writing-plans skill, in a fresh session".

- [ ] **Step 7: Run everything**

Run: `./gradlew test` (with `GRIDIRON_STATS_DB` set) and `cd etl && python -m pytest tests/ -q`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add -A core/data/src/test core/forecast/src/test etl CLAUDE.md docs/superpowers/HANDOFF.md
git commit -m "projections: real-data contract and timing tests; retire the Python model; docs"
```

---

## Plan self-review (done while writing)

- **Spec coverage (sections 1–3):**

  | Spec requirement | Task |
  |---|---|
  | `:core:forecast` module | 1 |
  | Schedule fetch and `game` table | 2 |
  | Schema v7 | 2 |
  | Metric families threaded to Monte Carlo | 3, 9 |
  | Layers 1–4 | 5 |
  | Layers 5–6 | 6 |
  | Layer 7 (variances) | 7, using Task 1's `varianceFor` |
  | Walk-forward | 7 |
  | Stages and factors | 7 |
  | Rest of season | 7 |
  | Reuse via copy | 7, 8 |
  | Failure isolation | 8 |
  | `forecast_*` meta | 7 |
  | Active profile and position | 9 |
  | Player page card | 11 |
  | Projections list | 10 |
  | Status line | 10 |
  | Out/IR | 10, 11 |
  | Contract test | 12 |
  | Performance measurement | 8 (step 11), 12 |
  | Python projections deleted | 12 |

- **Rulings where the plan departs from the spec's wording:**
  - **Families come from a join.** Spec §1 threads `dist_family` through `Catalog`/`MetricInfo`. The plan instead joins `metric` in the projection queries, which is where Monte Carlo gets its inputs. The outcome is the same and it touches fewer files.
  - **Dependency direction.** Spec §1 has `:core:forecast` depend on `:core:ingest`. The plan reverses it: the forecast takes a `SQLiteConnection`, and `:core:ingest` calls it. That avoids a cycle, because ingest runs the forecast.
  - **Copy rule.** Spec §1 re-projects a season when "its facts, `games.csv` or `forecast_version` changed". The plan copies only reused past seasons, from the oldest up to the first rebuilt one, and never the latest. It ignores `games.csv` changes for past seasons, because their games don't change.
  - **Spread sign.** Corrected in the spec itself: nflverse's `spread_line` favors the home team.
