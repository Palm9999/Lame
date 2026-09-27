# K and D/ST Projections Implementation Plan (sub-project 4 of 4)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Kickers and team defenses get stats, league scoring, weekly and rest-of-season projections, K and D/ST tabs in ☰ → Projections, Player page cards and accuracy rows, while the Grid stays as it is.

**Architecture:**
- **Stats (`:core:ingest`, `etl/`):** play-by-play gains kicking facts (field goals by distance, extra points, misses) for each kicker. Each team's defense becomes a pseudo-player `DST_<TEAM>` whose weekly facts come from `team_week_defense` (which gains safeties and kickoff-return TDs). Points allowed are stored as a number and as one-hot tier facts (`pa_0` … `pa_35`), so every scoring rule stays linear. The Python ETL gains the same facts, and CI's parity gate covers them.
- **Scoring (`:core:model`, `:core:statquery`, `:core:projections`):** 18 new `ScoringRule`s in two new groups. A rule the profile never set takes its `fallback` (the common default), so profiles saved before this change score K and D/ST sensibly. `StatQueryBuilder` scores them from their own small pivot so the offense's scoring query doesn't get wider.
- **Model (`:core:forecast`):** a `UnitProjector` runs inside the existing walk-forward loop. Kickers: a least-squares line from implied team points to field-goal and extra-point tries, the kicker's distance mix and make rates shrunk toward the league's. D/STs: their own recency-weighted rates times the opponent's, and points allowed from the opponent's implied points, turned into tier chances through a normal distribution.
- **Phone (`:core:projections`, `:feature:projections`):** the simulation draws the points-allowed tiers as one categorical draw. The list gets K and D/ST tabs, the accuracy page measures both, and each gets a fitted floor-to-ceiling widening.

**Tech Stack:** Kotlin 2 (explicit API, warnings as errors), JUnit Jupiter (JVM modules), JUnit 4 + Robolectric (Android modules), Jetpack Compose, bundled SQLite, Python 3 + polars (the parity reference ETL).

**Spec:** `docs/superpowers/specs/2026-09-26-projection-model-design.md`, §6 "K and DST (sub-project 4)", plus §1 (reuse, stages), §2 layer 7 and its range amendment, §3 (list tabs, Player page) and §4 (accuracy).

## Global Constraints

- Warnings are errors: no unused parameters, variables or imports. Explicit API mode in JVM modules: every declaration states its visibility.
- Spec §6, verbatim:
  - Scoring: "K rules: FG made 0–39, 40–49 and 50+; XP made; FG missed; XP missed." "DST rules: sack, interception, fumble recovery, defensive/special-teams TD, safety, and points-allowed tiers (0, 1–6, 7–13, 14–20, 21–27, 28–34, 35+)." "Presets get the common defaults, and the scoring editor shows the new rules. `score()` and `StatQueryBuilder` both learn them."
  - Stats: "`:core:ingest` adds kicking metrics from play-by-play: FG attempts and makes by distance bucket, XP attempts and makes." "DST facts come from `team_week_defense`, keyed to a team pseudo-player (`DST_<TEAM>`, name "<TEAM> D/ST", position DST) written to `player`." "Parity: the Python ETL gains the same kicking metrics so the parity gate still covers everything."
  - K model: "Implied team points drive FG and XP attempts, using a linear model fit walk-forward. The kicker's distance mix and make rate per bucket are shrunk toward the league average. Empirical CV is 0.52."
  - DST model: "Sacks and turnovers are the unit's own EWMA rates times the opponent's offensive ratings." "Points allowed are modeled from the opponent's implied points; the tier probabilities follow from that." "Empirical CV is 0.85."
  - Screens: "K and DST tabs in the Projections list, and Player page cards. The Grid is unchanged."
- **The Grid is unchanged:** no kicker or D/ST row ever appears in it, whatever the filter, search or roster.
- **Kotlin and Python agree** on every new fact, player row and `team_week_defense` column: `python etl/tools/parity.py` passes on a fresh 2025 build.
- **Versions:** `INGEST_VERSION` 2 → 3 (new facts; every season rebuilds once), `SCHEMA_VERSION` 7 → 8 (`team_week_defense` gains two columns; Python's goes 5 → 6), `FORECAST_VERSION` 3 → 4 (new projections).
- **Walk-forward:** projecting week *w* reads only facts from before week *w*, like the players' model. Lines, schedules and who kicked that week are pre-game or participation facts and may be read, as the players' model already does.
- **Props stay offense-only**: The Odds API markets are unchanged; K and D/ST have no `market` factor.
- **CI's accuracy gate stays QB, RB, WR and TE** (spec §4). K and D/ST rows are printed in its table but don't fail it.
- Copy rules (from existing UI): one sentence per status line, no exclamation marks. A D/ST shows as "KC D/ST"; its tab reads "D/ST".

## Review Focus

- **A kicker who also ran or threw** (a fake field goal). His week holds both his kicking and his offensive facts, `g` is 1, and his points count both. Test in Task 1 (`IngestPipelineTest`) and Task 4 (`ScoringQueryTest`).
- **A shutout, or a game at a tier edge** (6/7, 13/14, 20/21, 27/28, 34/35 points). The game lands in exactly one tier, in Kotlin and Python alike, and the build's validation fails a D/ST week that doesn't. Tests in Task 2 (`DstTest`, `DatabaseChecksTest`) and Task 3 (`test_teams.py`).
- **A scoring profile saved before this change.** K and D/ST rules score with the common defaults. A user who sets a K or D/ST rule to 0 keeps 0 after saving. Tests in Task 4 (`ScorerTest`, `ScoringEditViewModelTest`).
- **A team that changes kickers, or a kicker who moves on.** Each team has at most one kicker a week, no kicker is projected for two teams, and a released kicker isn't projected for his old team. Test in Task 7 (`ForecastEngineTest`).
- **A week with no line posted, or a team on bye.** Kickers and D/STs are still projected: final equals the matchup stage, there's no `game_script` factor, the tiers still sum to one, and a bye still counts toward rest of season. Tests in Task 7.

## File Structure

**New**

| File | Responsibility |
|---|---|
| `core/ingest/.../pbp/Kicking.kt` | `KICKING_METRICS`, `fgBucket`, `KickingAggregator` (field goals and extra points per kicker-week) |
| `core/ingest/.../Dst.kt` | `dstPlayerId`, `dstPlayer`, `dstWeeks` (team-weeks as D/ST weeks) |
| `core/statquery/.../PointsAllowed.kt` | `PaTier`, `PA_TIERS`, `paTier` (the one definition of the tiers) |
| `core/forecast/.../Kicker.kt` | `FG_BUCKETS`, `TeamKicks`, `AttemptFit`, `KickLeague`, `KickerRates`, `kickLeague`, `kickerRates`, `kickStats`, `teamPoints` |
| `core/forecast/.../Defense.kt` | `DST_STATS`, `normalCdf`, `tierChances`, `paSpread`, `DefenseLeague`, `defenseLeague`, `unitRate`, `opponentFactor`, `DefenseStages`, `defenseStages` |
| `core/forecast/.../Units.kt` | `UnitProjector`: picks each team's kicker and D/ST, projects them week by week, emits rows, factors and rest of season |
| `etl/tests/test_kicking.py` | Python kicking tests |

**Modified**

| File | Change |
|---|---|
| `core/ingest/.../pbp/Play.kt` | Kicking and safety columns |
| `core/ingest/.../pbp/TeamDefense.kt` | `safeties`, `kickReturnTds` |
| `core/ingest/.../Metrics.kt` | 10 kicking and 13 D/ST metrics, their families |
| `core/ingest/.../db/Schema.kt`, `db/StatsDbWriter.kt` | Versions; `team_week_defense` columns; D/ST players |
| `core/ingest/.../validate/DatabaseChecks.kt` | Kicking coherence, one tier per D/ST week |
| `core/ingest/.../IngestPipeline.kt` | Writes kicking and D/ST facts |
| `core/model/.../Position.kt`, `Scoring.kt` | `Position.DST`; `KICKING` and `DEFENSE` groups, 18 rules, `fallback` |
| `core/statquery/.../Component.kt`, `Scoring.kt`, `StatQuerySpec.kt`, `StatQueryBuilder.kt` | Components, rule inputs, `excludedPositions`, the special pivot |
| `core/data/.../StatsRepository.kt` | The Grid excludes K and D/ST |
| `feature/scoring/.../ScoringEditViewModel.kt` | Keeps a rule set away from its fallback |
| `core/forecast/build.gradle.kts` | `implementation(projects.core.statquery)` |
| `core/forecast/.../Inputs.kt`, `ForecastConstants.kt`, `Kinds.kt`, `GameScript.kt`, `Projector.kt` | Unit inputs, constants, reference points, `averageImplied`, the unit hook |
| `core/projections/.../MonteCarlo.kt`, `ProjectedPoints.kt`, `Backtest.kt` | `CATEGORICAL`; `widening`; K and D/ST measured |
| `core/data/.../AccuracyRepository.kt` | `widening` pass-through |
| `feature/projections/.../ProjectionListViewModel.kt` | `PositionTab.K`, `PositionTab.DST` |
| `etl/gridiron_etl/transform.py`, `teams.py`, `metrics.py`, `schema.py`, `build.py`, `validate.py`, `etl/tools/parity.py` | The Python twin |
| `CLAUDE.md`, the spec, `docs/superpowers/HANDOFF.md` | Docs |

## Sessions

The user runs 4 tasks per session:
- **Session A:** Tasks 1–4 (stats, parity and scoring).
- **Session B:** Tasks 5–8 (the model and the phone).
- **Session C:** Task 9 (real-data calibration and docs), then the final whole-branch review.

**Before Session A's Task 4, and again in Tasks 7 and 9, rebuild the test database** so real-data tests see the new facts: `./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025 2026" -Pout=etl/build/stats.db`.

---

### Task 1: Kicking facts

**Files:**
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/pbp/Kicking.kt`
- Modify: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/pbp/Play.kt`
- Modify: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Metrics.kt`
- Modify: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/validate/DatabaseChecks.kt`
- Modify: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/IngestPipeline.kt`
- Modify: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/db/Schema.kt` (`INGEST_VERSION`)
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/pbp/KickingTest.kt` (new), `pbp/PlayFixtures.kt`, `MetricsTest.kt`, `validate/DatabaseChecksTest.kt`, `IngestPipelineTest.kt`, `db/StatsDbWriterTest.kt`

**Interfaces:**
- Consumes: `Play`, `PlayerWeek(season, week, team, playerId, values)`, `SEASON_TYPES`, `toFacts(rows)`, `Metric(...)`.
- Produces: `KICKING_METRICS: List<String>` (the 10 ids below), `fgBucket(distance: Double?): String` (`"0_39"`, `"40_49"` or `"50"`), `KickingAggregator.add(Play)`, `KickingAggregator.rows(): List<PlayerWeek>`. Metric ids: `fg_att_0_39`, `fg_att_40_49`, `fg_att_50`, `fg_made_0_39`, `fg_made_40_49`, `fg_made_50`, `fg_missed`, `xp_att`, `xp_made`, `xp_missed` (group `kicking`, positions `K`, internal, sparse, family `poisson`). `Play` gains `kicker`, `fieldGoalAttempt`, `fieldGoalResult`, `kickDistance`, `extraPointAttempt`, `extraPointResult`.

- [ ] **Step 1: Give plays their kicking columns, and the test fixtures kicks**

In `Play.kt`, append to `PBP_COLUMNS` (after `"total_home_score", "total_away_score",`):

```kotlin
    "kicker_player_id", "field_goal_attempt", "field_goal_result", "kick_distance",
    "extra_point_attempt", "extra_point_result",
```

Append to the `Play` class's constructor, after `val totalAwayScore: Double?,`:

```kotlin
    val kicker: String?,
    val fieldGoalAttempt: Double?,
    /** "made", "missed" or "blocked". */
    val fieldGoalResult: String?,
    val kickDistance: Double?,
    val extraPointAttempt: Double?,
    /** "good", "failed", "blocked" or "aborted". */
    val extraPointResult: String?,
```

and to `toPlay()`, after `totalHomeScore = double("total_home_score"), totalAwayScore = double("total_away_score"),`:

```kotlin
        kicker = text("kicker_player_id"), fieldGoalAttempt = double("field_goal_attempt"),
        fieldGoalResult = text("field_goal_result"), kickDistance = double("kick_distance"),
        extraPointAttempt = double("extra_point_attempt"), extraPointResult = text("extra_point_result"),
```

In `PlayFixtures.kt`, add to `play()`'s parameters (after `totalAwayScore: Double? = 0.0,`):

```kotlin
    kicker: String? = null, fieldGoalAttempt: Double? = 0.0, fieldGoalResult: String? = null,
    kickDistance: Double? = null, extraPointAttempt: Double? = 0.0, extraPointResult: String? = null,
```

pass them through (after `totalHomeScore = totalHomeScore, totalAwayScore = totalAwayScore,`):

```kotlin
    kicker = kicker, fieldGoalAttempt = fieldGoalAttempt, fieldGoalResult = fieldGoalResult,
    kickDistance = kickDistance, extraPointAttempt = extraPointAttempt, extraPointResult = extraPointResult,
```

and add these helpers below `spike()`:

```kotlin
/** A field goal try by [kicker] for AAA: [result] is "made", "missed" or "blocked". */
internal fun fieldGoal(kicker: String, distance: Double?, result: String, seasonType: String = "REG"): Play = play(
    playType = "field_goal", kicker = kicker, fieldGoalAttempt = 1.0, kickDistance = distance,
    fieldGoalResult = result, seasonType = seasonType,
)

/** An extra point try by [kicker] for AAA: [result] is "good", "failed", "blocked" or "aborted". */
internal fun extraPoint(kicker: String, result: String): Play =
    play(playType = "extra_point", kicker = kicker, extraPointAttempt = 1.0, extraPointResult = result)
```

- [ ] **Step 2: Write the failing tests**

`core/ingest/src/test/kotlin/dev/gridiron/core/ingest/pbp/KickingTest.kt`:

```kotlin
package dev.gridiron.core.ingest.pbp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KickingTest {
    private fun kicks(plays: List<Play>): List<PlayerWeek> = KickingAggregator().apply { plays.forEach(::add) }.rows()

    @Test
    fun `field goals land in their distance bucket, edges included`() {
        val r = kicks(
            listOf(
                fieldGoal("K1", 39.0, "made"), fieldGoal("K1", 40.0, "made"), fieldGoal("K1", 49.0, "made"),
                fieldGoal("K1", 50.0, "made"), fieldGoal("K1", 63.0, "missed"),
            ),
        ).row("K1")
        assertEquals(listOf(1.0, 2.0, 2.0), listOf(r["fg_att_0_39"], r["fg_att_40_49"], r["fg_att_50"]))
        assertEquals(listOf(1.0, 2.0, 1.0), listOf(r["fg_made_0_39"], r["fg_made_40_49"], r["fg_made_50"]))
        assertEquals(1.0, r["fg_missed"])
        assertEquals(1.0, r["g"])
    }

    @Test
    fun `a blocked field goal is a miss, and a kick with no distance counts as short`() {
        val r = kicks(listOf(fieldGoal("K1", 45.0, "blocked"), fieldGoal("K1", null, "made"))).row("K1")
        assertEquals(1.0, r["fg_att_40_49"])
        assertEquals(0.0, r["fg_made_40_49"])
        assertEquals(1.0, r["fg_missed"])
        assertEquals(1.0, r["fg_att_0_39"])
        assertEquals(1.0, r["fg_made_0_39"])
    }

    @Test
    fun `an extra point is made only when it's good`() {
        val r = kicks(
            listOf(
                extraPoint("K1", "good"), extraPoint("K1", "good"), extraPoint("K1", "failed"),
                extraPoint("K1", "blocked"), extraPoint("K1", "aborted"),
            ),
        ).row("K1")
        assertEquals(listOf(5.0, 2.0, 3.0), listOf(r["xp_att"], r["xp_made"], r["xp_missed"]))
    }

    @Test
    fun `preseason kicks, kicks with no kicker and kickoffs don't count`() {
        val rows = kicks(
            listOf(
                fieldGoal("K1", 30.0, "made", seasonType = "PRE"),
                play(playType = "field_goal", fieldGoalAttempt = 1.0, kickDistance = 30.0, fieldGoalResult = "made"),
                play(playType = "kickoff", kicker = "K1"),
            ),
        )
        assertEquals(emptyList<PlayerWeek>(), rows)
    }

    @Test
    fun `each kicker's week is keyed by his team`() {
        val rows = kicks(
            listOf(
                fieldGoal("K1", 30.0, "made"),
                play(
                    playType = "field_goal", posteam = "BBB", defteam = "AAA", kicker = "K2",
                    fieldGoalAttempt = 1.0, kickDistance = 30.0, fieldGoalResult = "made",
                ),
            ),
        )
        assertEquals(mapOf("K1" to "AAA", "K2" to "BBB"), rows.associate { it.playerId to it.team })
    }
}
```

In `MetricsTest.kt`, change the count in `ids are unique and every Python metric is here` from `81` to `91`, and add:

```kotlin
    @Test
    fun `kicking metrics are internal, sparse, kicker-only counts`() {
        val kicking = listOf(
            "fg_att_0_39", "fg_att_40_49", "fg_att_50", "fg_made_0_39", "fg_made_40_49", "fg_made_50",
            "fg_missed", "xp_att", "xp_made", "xp_missed",
        )
        for (id in kicking) {
            val m = byId.getValue(id)
            assertTrue(m.isInternal, id)
            assertTrue(id in SPARSE_METRIC_IDS, id)
            assertEquals(listOf("K"), m.positions, id)
            assertEquals("kicking", m.group, id)
            assertEquals("poisson", m.distFamily, id)
        }
    }
```

In `DatabaseChecksTest.kt`, add:

```kotlin
    @Test
    fun `more kicks made than tried fail`() {
        val p = problems("g" to 1.0, "target_share" to 0.2, "fg_att_50" to 1.0, "fg_made_50" to 2.0)
        assertTrue(p.any { "50+ field goals made within tries" in it }, "$p")
        assertEquals(emptyList<String>(), problems("g" to 1.0, "target_share" to 0.2, "fg_att_50" to 2.0, "fg_made_50" to 1.0, "xp_att" to 3.0, "xp_made" to 2.0, "xp_missed" to 1.0))
    }
```

In `IngestPipelineTest.kt`, give `servePlayers` an `extra` parameter and `serveSeason` an `extraPlays` parameter:

```kotlin
    private fun servePlayers(wr1Name: String = "Wide Receiver One", version: String = "p1", extra: List<Map<String, Any?>> = emptyList()) {
```

with the `listOf(...)` of player rows becoming `listOf(...) + extra`, and

```kotlin
    private fun serveSeason(
        season: Int,
        version: String = "v1",
        snapsVersion: String = version,
        expected: Boolean = true,
        wr1Receptions: Int = 1,
        extraPlays: List<Map<String, Any?>> = emptyList(),
    ) {
```

with `val plays = listOf(...)` becoming `val plays = listOf(...) + extraPlays`. Then add:

```kotlin
    @Test
    fun `a kicker's field goals and extra points are stored beside any play he ran`() = runTest {
        servePlayers(
            extra = listOf(
                mapOf("gsis_id" to "K1", "display_name" to "Place Kicker", "position" to "K", "latest_team" to "AAA", "pfr_id" to "pK1", "espn_id" to "106"),
            ),
        )
        serveSeason(
            2025,
            extraPlays = listOf(
                Fixtures.pbp("play_type" to "field_goal", "kicker_player_id" to "K1", "field_goal_attempt" to 1, "kick_distance" to 47, "field_goal_result" to "made"),
                Fixtures.pbp("play_type" to "extra_point", "kicker_player_id" to "K1", "extra_point_attempt" to 1, "extra_point_result" to "good"),
                // A fake field goal: the kicker runs it himself.
                Fixtures.pbp("play_type" to "run", "rusher_player_id" to "K1", "rushing_yards" to 9, "yards_gained" to 9),
            ),
        )
        val out = File(dir, "stats.db")

        pipeline.build(listOf(2025), previous = null, out = out)

        assertEquals(
            listOf(listOf("carries", "1.0"), listOf("fg_made_40_49", "1.0"), listOf("g", "1.0"), listOf("xp_made", "1.0")),
            query(out, "SELECT metric_id, value FROM player_week_stat WHERE player_id = 'K1' AND metric_id IN ('g', 'fg_made_40_49', 'xp_made', 'carries') ORDER BY 1"),
        )
        assertEquals(listOf(listOf("K")), query(out, "SELECT position FROM player WHERE player_id = 'K1'"))
    }
```

In `IngestPipelineTest.kt`'s `a first build downloads everything and writes a validated database` and in `StatsDbWriterTest.kt`'s `a finished database has the schema, rows and provenance`, change `assertEquals("2", meta["ingest_version"])` to `assertEquals("3", meta["ingest_version"])`. In the latter, change the metric count `"81"` to `"91"`.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :core:ingest:test`
Expected: FAIL to compile with "Unresolved reference: KickingAggregator".

- [ ] **Step 4: Write the kicking aggregator**

`core/ingest/src/main/kotlin/dev/gridiron/core/ingest/pbp/Kicking.kt`:

```kotlin
package dev.gridiron.core.ingest.pbp

/** Every kicking metric, zero until a kick adds to it. */
internal val KICKING_METRICS: List<String> = listOf(
    "fg_att_0_39", "fg_att_40_49", "fg_att_50", "fg_made_0_39", "fg_made_40_49", "fg_made_50",
    "fg_missed", "xp_att", "xp_made", "xp_missed",
)

/** A field goal's distance bucket: `0_39`, `40_49` or `50`. An unknown distance counts as short. */
internal fun fgBucket(distance: Double?): String = when {
    distance == null || distance < 40 -> "0_39"
    distance < 50 -> "40_49"
    else -> "50"
}

/**
 * Folds field goals and extra points into kicker-weeks, reproducing
 * `transform.kicking_from`. Rows are keyed by team, like the other
 * aggregators', and every one has `g` = 1: a week with a kick is a week played.
 * A blocked or missed field goal is a miss; an extra point that isn't good
 * (failed, blocked or aborted) is a miss.
 */
internal class KickingAggregator {
    private data class Key(val season: Int, val week: Int, val team: String, val kicker: String)

    private val weeks = LinkedHashMap<Key, MutableMap<String, Double?>>()

    fun add(p: Play) {
        if (p.seasonType !in SEASON_TYPES) return
        val team = p.posteam ?: return
        val kicker = p.kicker ?: return
        val fieldGoal = p.fieldGoalAttempt == 1.0
        val extraPoint = p.extraPointAttempt == 1.0
        if (!fieldGoal && !extraPoint) return
        val v = weeks.getOrPut(Key(p.season, p.week, team, kicker)) {
            KICKING_METRICS.associateWithTo(LinkedHashMap<String, Double?>()) { 0.0 }.also { it["g"] = 1.0 }
        }
        fun count(id: String) {
            v[id] = (v[id] ?: 0.0) + 1.0
        }
        if (fieldGoal) {
            val bucket = fgBucket(p.kickDistance)
            count("fg_att_$bucket")
            if (p.fieldGoalResult == "made") count("fg_made_$bucket") else count("fg_missed")
        }
        if (extraPoint) {
            count("xp_att")
            if (p.extraPointResult == "good") count("xp_made") else count("xp_missed")
        }
    }

    fun rows(): List<PlayerWeek> = weeks.map { (k, v) -> PlayerWeek(k.season, k.week, k.team, k.kicker, v) }
}
```

- [ ] **Step 5: Register the metrics, check them, and write them**

In `Metrics.kt`, after `private val RB = listOf("RB")`, add:

```kotlin
private val KICKERS = listOf("K")
```

After the `RANGE_COMPONENTS` list, add:

```kotlin
/** Field goals by distance, extra points and misses: the kicker's scoring inputs. */
private val KICKING: List<Metric> = listOf(
    "fg_att_0_39" to ("FG Attempts 0-39" to "Field goal tries from 39 yards or closer, blocked kicks included."),
    "fg_att_40_49" to ("FG Attempts 40-49" to "Field goal tries from 40 to 49 yards, blocked kicks included."),
    "fg_att_50" to ("FG Attempts 50+" to "Field goal tries from 50 yards or farther, blocked kicks included."),
    "fg_made_0_39" to ("FGs Made 0-39" to "Field goals made from 39 yards or closer."),
    "fg_made_40_49" to ("FGs Made 40-49" to "Field goals made from 40 to 49 yards."),
    "fg_made_50" to ("FGs Made 50+" to "Field goals made from 50 yards or farther."),
    "fg_missed" to ("FGs Missed" to "Field goals missed or blocked, any distance."),
    "xp_att" to ("XP Attempts" to "Extra point kicks tried."),
    "xp_made" to ("XPs Made" to "Extra point kicks made."),
    "xp_missed" to ("XPs Missed" to "Extra point kicks missed, blocked or aborted."),
).map { (id, text) ->
    Metric(id, text.first, id.uppercase(), "kicking", text.second, positions = KICKERS, decimals = 0, isInternal = true, sparse = true)
}
```

Append `+ KICKING` to the end of `REGISTRY`'s expression (after the `RANGE_COMPONENTS.map { ... }` block). In `DIST_FAMILIES`, add before the closing brace:

```kotlin
    for (id in listOf(
        "fg_att_0_39", "fg_att_40_49", "fg_att_50", "fg_made_0_39", "fg_made_40_49", "fg_made_50",
        "fg_missed", "xp_att", "xp_made", "xp_missed",
    )) put(id, "poisson")
```

In `DatabaseChecks.kt`, append to `COHERENCE_CHECKS`:

```kotlin
    CoherenceCheck("0-39 field goals made within tries", "fg_made_0_39", "fg_att_0_39", "a <= b"),
    CoherenceCheck("40-49 field goals made within tries", "fg_made_40_49", "fg_att_40_49", "a <= b"),
    CoherenceCheck("50+ field goals made within tries", "fg_made_50", "fg_att_50", "a <= b"),
    CoherenceCheck("extra points made within tries", "xp_made", "xp_att", "a <= b"),
    CoherenceCheck("extra points missed within tries", "xp_missed", "xp_att", "a <= b"),
```

In `IngestPipeline.kt`, import `dev.gridiron.core.ingest.pbp.KickingAggregator`. In `crunch`, add `val kicking = KickingAggregator()` after `val defense = TeamDefenseAggregator()`, `kicking.add(play)` after `defense.add(play)`, and after `writer.writeFacts(toFacts(weekly))`:

```kotlin
            // Kickers are keyed like players, so one who also ran a play keeps both sets of facts under one `g`.
            writer.writeFacts(toFacts(kicking.rows()))
```

In `Schema.kt`, set `INGEST_VERSION` to 3.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :core:ingest:test`
Expected: PASS, including `KickingTest` (5), the new `MetricsTest`, `DatabaseChecksTest` and `IngestPipelineTest` tests.

- [ ] **Step 7: Commit**

```bash
git add core/ingest
git commit -m "ingest: kicking facts from play-by-play (field goals by distance, extra points, misses)"
```

### Task 2: D/ST facts, and a Grid without them

**Files:**
- Create: `core/statquery/src/main/kotlin/dev/gridiron/core/statquery/PointsAllowed.kt`
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Dst.kt`
- Modify: `core/statquery/src/main/kotlin/dev/gridiron/core/statquery/Component.kt`
- Modify: `core/statquery/src/main/kotlin/dev/gridiron/core/statquery/StatQuerySpec.kt`, `StatQueryBuilder.kt` (`excludedPositions`)
- Modify: `core/model/src/main/kotlin/dev/gridiron/core/model/Position.kt`
- Modify: `core/data/src/main/kotlin/dev/gridiron/core/data/StatsRepository.kt`
- Modify: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/pbp/Play.kt`, `pbp/TeamDefense.kt`
- Modify: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/db/Schema.kt`, `db/StatsDbWriter.kt`
- Modify: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Metrics.kt`, `validate/DatabaseChecks.kt`, `IngestPipeline.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/DstTest.kt` (new), `pbp/PlayFixtures.kt`, `pbp/TeamDefenseTest.kt`, `db/StatsDbWriterTest.kt`, `validate/DatabaseChecksTest.kt`, `MetricsTest.kt`, `IngestPipelineTest.kt`; `core/statquery/src/test/kotlin/dev/gridiron/core/statquery/StatQueryBuilderTest.kt`; `core/data/src/test/kotlin/dev/gridiron/core/data/StatsRepositoryTest.kt`

**Interfaces:**
- Consumes: `TeamDefenseRow`, `PlayerWeek`, `PlayerInfo(playerId, fullName, searchName, position, team, pfrPlayerId, espnId)`, `normalizeSearch`.
- Produces:
  - `:core:statquery` (public): `Components.DST_SACKS`, `DST_INTERCEPTIONS`, `DST_FUMBLE_RECOVERIES`, `DST_TDS`, `DST_SAFETIES`, `POINTS_ALLOWED`, `PA_0`, `PA_1_6`, `PA_7_13`, `PA_14_20`, `PA_21_27`, `PA_28_34`, `PA_35`; `PaTier(component: Component, most: Double)`; `PA_TIERS: List<PaTier>` (lowest first, the last `most` is `+∞`); `paTier(points: Double): Component`; `StatQuerySpec.excludedPositions: Set<Position>`.
  - `:core:model`: `Position.DST` (code `"DST"`).
  - `:core:data`: the Grid's spec always sets `excludedPositions = setOf(Position.K, Position.DST)`.
  - `:core:ingest` (internal): `TeamDefenseRow(…, defensiveTds, safeties, kickReturnTds)`; `dstPlayerId(team) = "DST_$team"`; `dstPlayer(team): PlayerInfo`; `dstWeeks(rows: List<TeamDefenseRow>): List<PlayerWeek>`. `team_week_defense` gains `safeties` and `kick_return_tds`. D/ST metric ids: `dst_sacks`, `dst_interceptions`, `dst_fumble_recoveries`, `dst_tds`, `dst_safeties` (sparse), `points_allowed` (kept at zero), `pa_0` … `pa_35` (sparse), all group `defense`, positions `DST`, internal.
  - `Play` gains `safety`.

- [ ] **Step 1: Write the failing ingest tests**

In `PlayFixtures.kt`, add `safety: Double? = 0.0,` to `play()`'s parameters (after `extraPointResult: String? = null,`) and `safety = safety,` to the `Play(...)` call.

In `TeamDefenseTest.kt`, the two expected rows gain `0.0, 0.0` for safeties and kickoff-return TDs:

```kotlin
        assertEquals(TeamDefenseRow("BUF", 2025, 1, 10.0, -3.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0), out["BUF"])
        assertEquals(TeamDefenseRow("KC", 2025, 1, 7.0, 20.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0.0), out["KC"])
```

and add:

```kotlin
    @Test
    fun `a safety goes to the defense, and a kickoff return TD to the receiving team`() {
        val agg = TeamDefenseAggregator()
        listOf(
            play(posteam = "BUF", defteam = "KC", playType = "run", safety = 1.0, homeTeam = "KC", awayTeam = "BUF", totalHomeScore = 2.0),
            // nflverse lists the receiving team as posteam on a kickoff.
            play(
                posteam = "BUF", defteam = "KC", playType = "kickoff", touchdown = 1.0, tdTeam = "BUF",
                homeTeam = "KC", awayTeam = "BUF", totalHomeScore = 2.0, totalAwayScore = 6.0,
            ),
            // A punt return TD scores for defteam, so it's already a defensive TD.
            play(
                posteam = "KC", defteam = "BUF", playType = "punt", touchdown = 1.0, tdTeam = "BUF",
                homeTeam = "KC", awayTeam = "BUF", totalHomeScore = 2.0, totalAwayScore = 12.0,
            ),
        ).forEach(agg::add)
        val out = agg.rows().associateBy { it.team }

        assertEquals(1.0, out.getValue("KC").safeties)
        assertEquals(0.0, out.getValue("KC").kickReturnTds)
        assertEquals(1.0, out.getValue("BUF").kickReturnTds)
        assertEquals(1.0, out.getValue("BUF").defensiveTds)
    }
```

`core/ingest/src/test/kotlin/dev/gridiron/core/ingest/DstTest.kt`:

```kotlin
package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.pbp.TeamDefenseRow
import dev.gridiron.core.statquery.PA_TIERS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class DstTest {
    private fun row(pointsAllowed: Double) =
        TeamDefenseRow("KC", 2025, 1, pointsAllowed, 300.0, 3.0, 1.0, 2.0, 1.0, 1.0, 1.0)

    @ParameterizedTest
    @CsvSource(
        "0,pa_0", "1,pa_1_6", "6,pa_1_6", "7,pa_7_13", "13,pa_7_13", "14,pa_14_20", "20,pa_14_20",
        "21,pa_21_27", "27,pa_21_27", "28,pa_28_34", "34,pa_28_34", "35,pa_35", "59,pa_35",
    )
    fun `a game lands in exactly one points-allowed tier`(points: Double, tier: String) {
        val values = dstWeeks(listOf(row(points))).single().values
        assertEquals(1.0, values[tier])
        assertEquals(1.0, PA_TIERS.sumOf { values[it.component.id] ?: 0.0 })
    }

    @Test
    fun `a team-week becomes its team defense's week`() {
        val week = dstWeeks(listOf(row(17.0))).single()
        assertEquals("DST_KC", week.playerId)
        assertEquals("KC", week.team)
        assertEquals(
            mapOf(
                "g" to 1.0, "dst_sacks" to 3.0, "dst_interceptions" to 1.0, "dst_fumble_recoveries" to 2.0,
                // One defensive TD and one kickoff return TD.
                "dst_tds" to 2.0, "dst_safeties" to 1.0, "points_allowed" to 17.0,
            ),
            week.values.filterKeys { !it.startsWith("pa_") },
        )
    }

    @Test
    fun `a team defense is named for its team and found by searching dst`() {
        assertEquals(PlayerInfo("DST_KC", "KC D/ST", "kc dst", "DST", "KC", null, null), dstPlayer("KC"))
    }
}
```

In `StatsDbWriterTest.kt`, the `build` helper's row becomes `TeamDefenseRow("AAA", s, 1, 10.0, 300.0, 2.0, 1.0, 0.0, 0.0, 0.0, 0.0)`, `"7"` for `schema_version` becomes `"8"`, the metric count `"91"` becomes `"104"`, and add:

```kotlin
    @Test
    fun `each team's defense is a player, kept when it has stats`() {
        val file = File(dir, "stats.db")
        StatsDbWriter.create(file).use { w ->
            w.writeMetrics(METRICS)
            w.writeTeamDefense(
                listOf(
                    TeamDefenseRow("AAA", 2025, 1, 10.0, 300.0, 2.0, 1.0, 0.0, 0.0, 0.0, 0.0),
                    TeamDefenseRow("BBB", 2025, 1, 20.0, 250.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0),
                ),
            )
            w.writeFacts(listOf(Fact("DST_AAA", 2025, 1, "AAA", "dst_sacks", 2.0)))
            assertEquals(0, w.writePlayers(listOf(wr1)))
        }
        assertEquals(
            listOf(listOf("DST_AAA", "AAA D/ST", "aaa dst", "DST", "AAA")),
            query(file, "SELECT player_id, full_name, search_name, position, team FROM player ORDER BY 1"),
        )
    }
```

In `DatabaseChecksTest.kt`, add:

```kotlin
    private fun dstProblems(vararg facts: Pair<String, Double>): List<String> =
        StatsDbWriter.create(File(dir, "d.db")).use { w ->
            w.writeMetrics(METRICS)
            w.execute("INSERT INTO player (player_id, full_name, search_name, position, team) VALUES ('p1', 'Test Player', 'test player', 'WR', 'AAA')")
            w.execute("INSERT INTO player (player_id, full_name, search_name, position, team) VALUES ('DST_AAA', 'AAA D/ST', 'aaa dst', 'DST', 'AAA')")
            w.execute("INSERT INTO player_week_stat VALUES ('p1', 2025, 1, 'AAA', 'target_share', 0.2)")
            for ((metric, value) in facts) {
                w.execute("INSERT INTO player_week_stat VALUES (?, ?, ?, ?, ?, ?)", "DST_AAA", 2025, 1, "AAA", metric, value)
            }
            validateDatabase(w.connection)
        }

    @Test
    fun `a team defense's week is in exactly one points-allowed tier`() {
        assertEquals(emptyList<String>(), dstProblems("g" to 1.0, "points_allowed" to 17.0, "pa_14_20" to 1.0))
        assertTrue(dstProblems("g" to 1.0, "points_allowed" to 17.0).any { "points-allowed tier" in it })
        assertTrue(dstProblems("g" to 1.0, "points_allowed" to 17.0, "pa_14_20" to 1.0, "pa_21_27" to 1.0).any { "points-allowed tier" in it })
        assertTrue(dstProblems("g" to 1.0, "pa_14_20" to 1.0).any { "points-allowed tier" in it })
    }
```

In `MetricsTest.kt`, change the count from `91` to `104` and add:

```kotlin
    @Test
    fun `team defense metrics are internal, and only points allowed keeps its zeros`() {
        val defense = listOf("dst_sacks", "dst_interceptions", "dst_fumble_recoveries", "dst_tds", "dst_safeties") +
            listOf("pa_0", "pa_1_6", "pa_7_13", "pa_14_20", "pa_21_27", "pa_28_34", "pa_35")
        for (id in defense + "points_allowed") {
            val m = byId.getValue(id)
            assertTrue(m.isInternal, id)
            assertEquals(listOf("DST"), m.positions, id)
            assertEquals("defense", m.group, id)
            assertEquals(id != "points_allowed", id in SPARSE_METRIC_IDS, id)
        }
        assertEquals("negbinom", byId.getValue("dst_sacks").distFamily)
        assertEquals("poisson", byId.getValue("dst_tds").distFamily)
        assertEquals("categorical", byId.getValue("pa_0").distFamily)
        assertFalse(byId.getValue("points_allowed").higherIsBetter)
    }
```

In `IngestPipelineTest.kt`'s first test, change `assertEquals("7", meta["schema_version"])` to `"8"` and the player list to `listOf("DST_AAA", "DST_BBB", "QB1", "RB1", "WR1", "WR2")`, and add:

```kotlin
    @Test
    fun `each team's defense is stored as its D-ST's week`() = runTest {
        servePlayers()
        serveSeason(2025)
        val out = File(dir, "stats.db")

        pipeline.build(listOf(2025), previous = null, out = out)

        // The fixture's plays score nothing: both defenses allowed 0, a shutout.
        assertEquals(
            listOf(listOf("g", "1.0"), listOf("pa_0", "1.0"), listOf("points_allowed", "0.0")),
            query(out, "SELECT metric_id, value FROM player_week_stat WHERE player_id = 'DST_BBB' ORDER BY 1"),
        )
        assertEquals(listOf(listOf("BBB D/ST", "DST", "BBB")), query(out, "SELECT full_name, position, team FROM player WHERE player_id = 'DST_BBB'"))
    }
```

(In `a first build …`, the other assertions on `player_week_stat` name their players, so D/ST rows don't disturb them.)

- [ ] **Step 2: Write the failing Grid tests**

In `StatQueryBuilderTest.kt`, next to `positions and teams narrow the result`, add:

```kotlin
        @Test
        fun `excluded positions leave those players out and keep players with no position`() {
            db.player("wr1", "Wideout", position = "WR")
            db.player("k1", "Place Kicker", position = "K")
            db.player("d1", "KC D/ST", position = "DST")
            listOf("wr1", "k1", "d1").forEach { db.week(it, 1, C.TARGETS to 1) }
            db.conn.createStatement().use { it.executeUpdate("INSERT INTO player VALUES ('x1', 'Unknown Spot', 'unknown spot', NULL, 'AAA', NULL)") }
            db.week("x1", 1, C.TARGETS to 1)

            val rows = db.grid(spec(TARGETS).copy(excludedPositions = setOf(Position.K, Position.DST)))

            assertEquals(setOf("wr1", "x1"), rows.map { it.playerId }.toSet())
        }
```

In `StatsRepositoryTest.kt`, add (it runs against the rebuilt `GRIDIRON_STATS_DB`):

```kotlin
    @Test
    fun `the Grid lists no kickers or team defenses, even on a roster or a search`() = runTest {
        val season = catalog.season(2025)
        val special = executor.query(
            SqlQuery("SELECT player_id FROM player WHERE position IN ('K', 'DST')", emptyList()),
        ) { it.text(0) }.toSet()
        assertTrue(special.any { it.startsWith("DST_") } && special.any { !it.startsWith("DST_") }, "no kickers or D/STs in the database")

        val roster = repo.grid(GridRequest(season, season.defaultWeeks, StatPack.FANTASY, onlyPlayers = special), catalog)
        val search = repo.grid(GridRequest(season, season.defaultWeeks, StatPack.FANTASY, name = "dst"), catalog)

        assertEquals(emptyList<String>(), roster.rows.map { it.playerId })
        assertTrue(search.rows.none { it.playerId in special }, "${search.rows.map { it.playerId }}")
        assertEquals(0, repo.count(GridRequest(season, season.defaultWeeks, StatPack.FANTASY, onlyPlayers = special)))
    }
```

(Import `dev.gridiron.core.statquery.SqlQuery` if the file doesn't already.)

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :core:ingest:test :core:statquery:test`
Expected: FAIL to compile with "Unresolved reference: dstWeeks", "No parameter with name 'excludedPositions'" and the new `TeamDefenseRow` arguments.

- [ ] **Step 4: Define the tiers and the D/ST components once, in `:core:statquery`**

In `Component.kt`, add inside `Components` after `X_INTERCEPTIONS`:

```kotlin

    // Team defense and special teams (D/ST pseudo-players). Internal and sparse,
    // except points allowed, whose zero is a shutout.
    public val DST_SACKS: Component = Component("dst_sacks")
    public val DST_INTERCEPTIONS: Component = Component("dst_interceptions")
    public val DST_FUMBLE_RECOVERIES: Component = Component("dst_fumble_recoveries")
    public val DST_TDS: Component = Component("dst_tds")
    public val DST_SAFETIES: Component = Component("dst_safeties")
    public val POINTS_ALLOWED: Component = Component("points_allowed")

    // Points-allowed tiers: 1 in the one tier a game lands in (see PA_TIERS).
    public val PA_0: Component = Component("pa_0")
    public val PA_1_6: Component = Component("pa_1_6")
    public val PA_7_13: Component = Component("pa_7_13")
    public val PA_14_20: Component = Component("pa_14_20")
    public val PA_21_27: Component = Component("pa_21_27")
    public val PA_28_34: Component = Component("pa_28_34")
    public val PA_35: Component = Component("pa_35")
```

`core/statquery/src/main/kotlin/dev/gridiron/core/statquery/PointsAllowed.kt`:

```kotlin
package dev.gridiron.core.statquery

/**
 * A points-allowed scoring tier: games in which the opponent scored at most
 * [most] points, and more than the tier below allows.
 */
public data class PaTier(public val component: Component, public val most: Double)

/**
 * The tiers, lowest first; a game lands in exactly one. Stored as one-hot
 * facts, so every scoring rule stays a weight times a sum. The ETL's
 * `teams.PA_TIERS` mirrors this list.
 */
public val PA_TIERS: List<PaTier> = listOf(
    PaTier(Components.PA_0, 0.0),
    PaTier(Components.PA_1_6, 6.0),
    PaTier(Components.PA_7_13, 13.0),
    PaTier(Components.PA_14_20, 20.0),
    PaTier(Components.PA_21_27, 27.0),
    PaTier(Components.PA_28_34, 34.0),
    PaTier(Components.PA_35, Double.POSITIVE_INFINITY),
)

/** The tier of a game in which the opponent scored [points]. */
public fun paTier(points: Double): Component = PA_TIERS.first { points <= it.most }.component
```

- [ ] **Step 5: Keep K and D/ST out of the Grid**

In `Position.kt`, change the KDoc to `/** Positions as stored in `player.position`: the offense, kickers, and DST (a team's defense and special teams). */` and add after `K("K"),`:

```kotlin
    DST("DST"),
```

In `StatQuerySpec.kt`, document and add the property after `positions`:

```kotlin
 * @property excludedPositions Never these positions. Players with no position are kept.
```

```kotlin
    val excludedPositions: Set<Position> = emptySet(),
```

In `StatQueryBuilder.kt`'s `where`, after the `positions` block:

```kotlin
        if (spec.excludedPositions.isNotEmpty()) {
            val codes = spec.excludedPositions.sortedBy { it.ordinal }
            conditions += "(position IS NULL OR position NOT IN (${codes.joinToString(", ") { text(it.code) }}))"
        }
```

In `StatsRepository.kt`, add above the class:

```kotlin
/** The Grid is for the offense: kickers and team defenses have their own projections, never Grid rows. */
private val GRID_EXCLUDED: Set<Position> = setOf(Position.K, Position.DST)
```

and in `spec(...)`, after `positions = request.positions.positions,`:

```kotlin
            excludedPositions = GRID_EXCLUDED,
```

(import `dev.gridiron.core.model.Position` if needed).

- [ ] **Step 6: Safeties and kickoff return TDs in team defense**

In `Play.kt`, append `"safety",` to `PBP_COLUMNS` (after `"extra_point_result",`), `val safety: Double?,` to the constructor (after `extraPointResult`), and `safety = double("safety"),` to `toPlay()`.

In `TeamDefense.kt`, the row gains two fields:

```kotlin
    val defensiveTds: Double,
    val safeties: Double,
    /** Kickoffs returned for a TD. Punt return TDs score for defteam, so they're already in [defensiveTds]. */
    val kickReturnTds: Double,
)
```

`Allowed` gains `var safeties = 0.0` and `var kickReturnTds = 0.0`. In `add`, before `val defense = p.defteam ?: return`:

```kotlin
        // nflverse lists the receiving team as posteam on a kickoff, so a return TD is posteam's.
        val receiving = p.posteam
        if (p.playType == "kickoff" && receiving != null && p.tdTeam == receiving) {
            allowed.getOrPut(Triple(receiving, p.season, p.week)) { Allowed() }.kickReturnTds += p.touchdown ?: 0.0
        }
```

and after `a.fumbles += p.fumbleLost ?: 0.0`:

```kotlin
        a.safeties += p.safety ?: 0.0
```

In `row(...)`, pass the new fields: `a?.yards ?: 0.0, a?.sacks ?: 0.0, a?.interceptions ?: 0.0, a?.fumbles ?: 0.0, a?.tds ?: 0.0, a?.safeties ?: 0.0, a?.kickReturnTds ?: 0.0,`.

In `Schema.kt`, set `SCHEMA_VERSION` to 8, extend its KDoc with `, plus `team_week_defense`'s safeties and kickoff-return TDs (8)`, and change the `team_week_defense` DDL's last line to:

```kotlin
        interceptions REAL NOT NULL, fumbles_recovered REAL NOT NULL, defensive_tds REAL NOT NULL,
        safeties REAL NOT NULL, kick_return_tds REAL NOT NULL,
        PRIMARY KEY (team, season, week)) WITHOUT ROWID""",
```

In `StatsDbWriter.writeTeamDefense`, the statement and binds become:

```kotlin
        """INSERT OR REPLACE INTO team_week_defense (team, season, week, points_allowed, yards_allowed,
           sacks, interceptions, fumbles_recovered, defensive_tds, safeties, kick_return_tds)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
```

```kotlin
        st.bindDouble(9, r.defensiveTds)
        st.bindDouble(10, r.safeties)
        st.bindDouble(11, r.kickReturnTds)
```

- [ ] **Step 7: D/ST players and weeks**

`core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Dst.kt`:

```kotlin
package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.pbp.PlayerWeek
import dev.gridiron.core.ingest.pbp.TeamDefenseRow
import dev.gridiron.core.statquery.PA_TIERS
import dev.gridiron.core.statquery.normalizeSearch
import dev.gridiron.core.statquery.paTier

/** A team's defense and special teams as one pseudo-player, so fantasy D/ST is scored like any player. */
internal fun dstPlayerId(team: String): String = "DST_$team"

internal fun dstPlayer(team: String): PlayerInfo {
    val name = "$team D/ST"
    return PlayerInfo(dstPlayerId(team), name, normalizeSearch(name), "DST", team, pfrPlayerId = null, espnId = null)
}

/**
 * Each team-week as its D/ST's week, reproducing `teams.dst_weekly`. TDs are
 * the defense's plus kickoff returns; points allowed are stored as a number
 * and as a 1 in the one tier the game lands in.
 */
internal fun dstWeeks(rows: List<TeamDefenseRow>): List<PlayerWeek> = rows.map { r ->
    val values = linkedMapOf<String, Double?>(
        "g" to 1.0,
        "dst_sacks" to r.sacks,
        "dst_interceptions" to r.interceptions,
        "dst_fumble_recoveries" to r.fumblesRecovered,
        "dst_tds" to r.defensiveTds + r.kickReturnTds,
        "dst_safeties" to r.safeties,
        "points_allowed" to r.pointsAllowed,
    )
    for (tier in PA_TIERS) values[tier.component.id] = 0.0
    values[paTier(r.pointsAllowed).id] = 1.0
    PlayerWeek(r.season, r.week, r.team, dstPlayerId(r.team), values)
}
```

In `StatsDbWriter.writePlayers`, add `dstPlayer` for every team with a defense row, so copied seasons get theirs too. Change the loop `for (p in players) {` to `for (p in players + defenseTeams().map(::dstPlayer)) {` (inside the existing `prepare(...).use`, after the temp table exists), import `dev.gridiron.core.ingest.dstPlayer`, update the KDoc's first line to `Keeps players with stats in `player`, each team's D/ST among them, …`, and add:

```kotlin
    private fun defenseTeams(): List<String> =
        connection.prepare("SELECT DISTINCT team FROM team_week_defense ORDER BY team").use { st ->
            buildList { while (st.step()) add(st.getText(0)) }
        }
```

In `Metrics.kt`, after `KICKING`, add:

```kotlin
private val TEAM_DEFENSE = listOf("DST")

/** The D/ST pseudo-players' facts: takeaways, scores, and points allowed with their scoring tiers. */
private val DEFENSE: List<Metric> = listOf(
    "dst_sacks" to ("D/ST Sacks" to "Sacks by the team's defense."),
    "dst_interceptions" to ("D/ST Interceptions" to "Passes the team's defense intercepted."),
    "dst_fumble_recoveries" to ("D/ST Fumble Recoveries" to "Opponent fumbles the team recovered."),
    "dst_tds" to ("D/ST TDs" to "Touchdowns by the defense or on a return: interceptions, fumbles, punts, kickoffs and blocked kicks."),
    "dst_safeties" to ("D/ST Safeties" to "Safeties the team's defense scored."),
).map { (id, text) ->
    Metric(id, text.first, id.uppercase(), "defense", text.second, positions = TEAM_DEFENSE, decimals = 0, isInternal = true, sparse = true)
} + Metric(
    "points_allowed", "Points Allowed", "POINTS_ALLOWED", "defense", "Points the opponent scored, however it scored them.",
    positions = TEAM_DEFENSE, higherIsBetter = false, decimals = 0, isInternal = true,
) + listOf(
    "pa_0" to "0 points", "pa_1_6" to "1-6 points", "pa_7_13" to "7-13 points", "pa_14_20" to "14-20 points",
    "pa_21_27" to "21-27 points", "pa_28_34" to "28-34 points", "pa_35" to "35+ points",
).map { (id, points) ->
    Metric(
        id, "Games Allowing $points", id.uppercase(), "defense",
        "1 for a game in which the opponent scored $points: the points-allowed scoring tier.",
        positions = TEAM_DEFENSE, decimals = 0, isInternal = true, sparse = true,
    )
}
```

Append `+ DEFENSE` to `REGISTRY` (after `+ KICKING`). In `DIST_FAMILIES`, add:

```kotlin
    put("dst_sacks", "negbinom")
    for (id in listOf("dst_interceptions", "dst_fumble_recoveries", "dst_tds", "dst_safeties")) put(id, "poisson")
    for (id in listOf("pa_0", "pa_1_6", "pa_7_13", "pa_14_20", "pa_21_27", "pa_28_34", "pa_35")) put(id, "categorical")
```

In `DatabaseChecks.kt`, import `dev.gridiron.core.statquery.PA_TIERS` and add before the `computed` check:

```kotlin
    // Scoring reads the tier, so every D/ST week needs its points allowed and exactly one tier.
    val tierIds = PA_TIERS.joinToString(", ") { "'${it.component.id}'" }
    val badTiers = conn.count(
        """SELECT COUNT(*) FROM (
             SELECT SUM(CASE WHEN metric_id IN ($tierIds) THEN value ELSE 0 END) AS tiers,
                    MAX(metric_id = 'points_allowed') AS scored
             FROM player_week_stat WHERE player_id LIKE 'DST\_%' ESCAPE '\'
             GROUP BY player_id, season, week)
           WHERE tiers <> 1 OR scored = 0""",
    )
    if (badTiers > 0) problems += "D/ST weeks without their points allowed and exactly one points-allowed tier: $badTiers"
```

In `IngestPipeline.crunch`, replace `writer.writeTeamDefense(defense.rows())` with:

```kotlin
            val defenseRows = defense.rows()
            writer.writeTeamDefense(defenseRows)
            writer.writeFacts(toFacts(dstWeeks(defenseRows)))
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew :core:ingest:test :core:statquery:test :core:model:test`
Expected: PASS.

Then rebuild the test database and run the Grid's real-data test:

Run: `./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025 2026" -Pout=etl/build/stats.db && GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test --tests "dev.gridiron.core.data.StatsRepositoryTest"`
Expected: the build validates and every `StatsRepositoryTest` test passes, the new one included. A build failure naming `points-allowed tier` or a coherence check is a real transform bug: fix it before going on.

- [ ] **Step 9: Commit**

```bash
git add core/ingest core/statquery core/model core/data
git commit -m "ingest: team defenses as D/ST pseudo-players (safeties, return TDs, points-allowed tiers); the Grid leaves K and D/ST out"
```

### Task 3: The Python ETL's twin, and parity

**Files:**
- Modify: `etl/gridiron_etl/transform.py`, `teams.py`, `metrics.py`, `schema.py`, `build.py`, `validate.py`
- Modify: `etl/tools/parity.py`
- Test: `etl/tests/test_kicking.py` (new), `etl/tests/test_teams.py`, `etl/tests/test_registry.py`, `etl/tests/test_parity.py`

**Interfaces:**
- Consumes: Task 1's and Task 2's definitions (ids, names, definitions, tiers, the kickoff and safety rules), which Python must reproduce exactly.
- Produces: `transform.kicking_stats(pbp_path)`, `transform.kicking_from(lf)`, `teams.PA_TIERS`, `teams.dst_weekly(defense)`, `teams.dst_players(teams_)`; `team_defense_from` adds `safeties` and `kick_return_tds`.

- [ ] **Step 1: Write the failing tests**

`etl/tests/test_kicking.py`:

```python
import polars as pl

from gridiron_etl import transform


def kick(**kw) -> dict:
    base = dict(season=2025, week=1, season_type="REG", posteam="AAA", kicker_player_id="K1",
                field_goal_attempt=0, field_goal_result=None, kick_distance=None,
                extra_point_attempt=0, extra_point_result=None)
    base.update(kw)
    return base


def fg(distance, result, **kw) -> dict:
    return kick(field_goal_attempt=1, kick_distance=distance, field_goal_result=result, **kw)


def xp(result, **kw) -> dict:
    return kick(extra_point_attempt=1, extra_point_result=result, **kw)


def rows(plays: list[dict]) -> dict[str, dict]:
    return {r["player_id"]: r for r in transform.kicking_from(pl.LazyFrame(plays)).to_dicts()}


def test_field_goals_land_in_their_distance_bucket_edges_included():
    r = rows([fg(39, "made"), fg(40, "made"), fg(49, "made"), fg(50, "made"), fg(63, "missed")])["K1"]
    assert [r["fg_att_0_39"], r["fg_att_40_49"], r["fg_att_50"]] == [1, 2, 2]
    assert [r["fg_made_0_39"], r["fg_made_40_49"], r["fg_made_50"]] == [1, 2, 1]
    assert r["fg_missed"] == 1
    assert r["g"] == 1


def test_a_blocked_kick_is_a_miss_and_no_distance_counts_as_short():
    r = rows([fg(45, "blocked"), fg(None, "made")])["K1"]
    assert (r["fg_att_40_49"], r["fg_made_40_49"], r["fg_missed"]) == (1, 0, 1)
    assert (r["fg_att_0_39"], r["fg_made_0_39"]) == (1, 1)


def test_an_extra_point_is_made_only_when_good():
    r = rows([xp("good"), xp("good"), xp("failed"), xp("blocked"), xp("aborted")])["K1"]
    assert (r["xp_att"], r["xp_made"], r["xp_missed"]) == (5, 2, 3)


def test_preseason_and_kicks_without_a_kicker_do_not_count():
    assert rows([fg(30, "made", season_type="PRE"), fg(30, "made", kicker_player_id=None)]) == {}


def test_each_kickers_week_is_keyed_by_his_team():
    out = rows([fg(30, "made"), fg(30, "made", posteam="BBB", kicker_player_id="K2")])
    assert {k: r["team"] for k, r in out.items()} == {"K1": "AAA", "K2": "BBB"}
```

In `test_teams.py`, give each of the three existing play dicts a `"posteam"` (the other team: `"BUF"` where `defteam` is `"KC"`, `"KC"` where it's `"BUF"`) and `"safety": 0`, and add:

```python
def test_a_safety_goes_to_the_defense_and_a_kickoff_return_td_to_the_receiving_team():
    base = {"season": 2025, "week": 1, "season_type": "REG", "game_id": "g1",
            "home_team": "KC", "away_team": "BUF", "yards_gained": 0, "sack": 0,
            "interception": 0, "fumble_lost": 0}
    plays = pl.LazyFrame([
        {**base, "posteam": "BUF", "defteam": "KC", "play_type": "run", "safety": 1, "touchdown": 0,
         "td_team": None, "total_home_score": 2, "total_away_score": 0},
        {**base, "posteam": "BUF", "defteam": "KC", "play_type": "kickoff", "safety": 0, "touchdown": 1,
         "td_team": "BUF", "total_home_score": 2, "total_away_score": 6},
        {**base, "posteam": "KC", "defteam": "BUF", "play_type": "punt", "safety": 0, "touchdown": 1,
         "td_team": "BUF", "total_home_score": 2, "total_away_score": 12},
    ])
    out = {r["team"]: r for r in teams.team_defense_from(plays).to_dicts()}

    assert out["KC"]["safeties"] == 1
    assert out["KC"]["kick_return_tds"] == 0
    assert out["BUF"]["kick_return_tds"] == 1
    assert out["BUF"]["defensive_tds"] == 1


def _defense(points: float) -> pl.DataFrame:
    return pl.DataFrame([{"team": "KC", "season": 2025, "week": 1, "points_allowed": points,
                          "yards_allowed": 300.0, "sacks": 3.0, "interceptions": 1.0,
                          "fumbles_recovered": 2.0, "defensive_tds": 1.0, "safeties": 1.0,
                          "kick_return_tds": 1.0}])


@pytest.mark.parametrize("points,tier", [
    (0, "pa_0"), (1, "pa_1_6"), (6, "pa_1_6"), (7, "pa_7_13"), (13, "pa_7_13"), (14, "pa_14_20"),
    (20, "pa_14_20"), (21, "pa_21_27"), (27, "pa_21_27"), (28, "pa_28_34"), (34, "pa_28_34"),
    (35, "pa_35"), (59, "pa_35"),
])
def test_a_game_lands_in_exactly_one_points_allowed_tier(points, tier):
    row = teams.dst_weekly(_defense(points)).to_dicts()[0]
    assert row[tier] == 1
    assert sum(row[mid] for mid, _ in teams.PA_TIERS) == 1


def test_a_team_week_becomes_its_team_defenses_week():
    row = teams.dst_weekly(_defense(17)).to_dicts()[0]
    assert (row["player_id"], row["team"], row["g"]) == ("DST_KC", "KC", 1)
    assert (row["dst_sacks"], row["dst_interceptions"], row["dst_fumble_recoveries"]) == (3, 1, 2)
    assert (row["dst_tds"], row["dst_safeties"], row["points_allowed"]) == (2, 1, 17)


def test_a_team_defense_is_named_for_its_team():
    assert teams.dst_players(["KC"]).to_dicts() == [
        {"player_id": "DST_KC", "full_name": "KC D/ST", "position": "DST", "team": "KC", "pfr_player_id": None},
    ]
```

(add `import pytest` at the top of `test_teams.py`).

In `test_registry.py`, add:

```python
KICKING = ["fg_att_0_39", "fg_att_40_49", "fg_att_50", "fg_made_0_39", "fg_made_40_49", "fg_made_50",
           "fg_missed", "xp_att", "xp_made", "xp_missed"]
DEFENSE = ["dst_sacks", "dst_interceptions", "dst_fumble_recoveries", "dst_tds", "dst_safeties",
           "pa_0", "pa_1_6", "pa_7_13", "pa_14_20", "pa_21_27", "pa_28_34", "pa_35"]


def test_kicking_and_defense_metrics_are_internal_sparse_and_theirs_alone():
    for mid in KICKING + DEFENSE:
        m = METRICS[mid]
        assert m.internal and not m.computed, mid
        assert mid in sparse_metric_ids(), mid
    assert all(METRICS[m].positions == ("K",) for m in KICKING)
    assert all(METRICS[m].positions == ("DST",) for m in DEFENSE + ["points_allowed"])
    assert "points_allowed" not in sparse_metric_ids()
    assert METRICS["pa_0"].dist_family == "categorical"
    assert len(METRICS) == 104
```

In `test_parity.py`'s `_db`, the `team_week_defense` DDL gains `safeties REAL, kick_return_tds REAL` after `defensive_tds REAL`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd etl && python -m pytest tests/ -q`
Expected: FAIL with "module 'gridiron_etl.transform' has no attribute 'kicking_from'" and the teams tests failing on `safeties`.

- [ ] **Step 3: Kicking in `transform.py`**

Append:

```python
_KICK_COLUMNS = ["season", "week", "season_type", "posteam", "kicker_player_id",
                 "field_goal_attempt", "field_goal_result", "kick_distance",
                 "extra_point_attempt", "extra_point_result"]

# Field goal distance buckets, shortest first. Mirrors core/ingest's fgBucket.
_FG_BUCKETS = ("0_39", "40_49", "50")


def kicking_stats(pbp_path: Path) -> pl.DataFrame:
    """Kicker-weeks from the full play-by-play file (load_pbp keeps only scrimmage plays)."""
    lf = pl.scan_csv(pbp_path, infer_schema_length=20_000)
    return kicking_from(lf.select(_KICK_COLUMNS))


def kicking_from(lf: pl.LazyFrame) -> pl.DataFrame:
    """One row per kicker-week: field goals by distance, misses and extra points.

    Blocked or missed field goals are misses; an extra point that isn't good
    (failed, blocked, aborted) is a miss. A field goal with no distance counts
    as short. Every row has g = 1. Reproduced by core/ingest's KickingAggregator.
    """
    num = lambda c: pl.col(c).cast(pl.Float64, strict=False).fill_null(0)  # noqa: E731
    fg = num("field_goal_attempt") == 1
    xp = num("extra_point_attempt") == 1
    dist = pl.col("kick_distance").cast(pl.Float64, strict=False).fill_null(0)
    made = (pl.col("field_goal_result") == "made").fill_null(False)
    good = (pl.col("extra_point_result") == "good").fill_null(False)
    bucket = {"0_39": dist < 40, "40_49": (dist >= 40) & (dist < 50), "50": dist >= 50}
    count = lambda cond: cond.sum().cast(pl.Float64)  # noqa: E731
    return (
        lf.filter(
            pl.col("season_type").is_in(["REG", "POST"])
            & pl.col("posteam").is_not_null()
            & pl.col("kicker_player_id").is_not_null()
            & (fg | xp)
        )
        .group_by(["season", "week", "posteam", "kicker_player_id"])
        .agg(
            *[count(fg & bucket[b]).alias(f"fg_att_{b}") for b in _FG_BUCKETS],
            *[count(fg & bucket[b] & made).alias(f"fg_made_{b}") for b in _FG_BUCKETS],
            fg_missed=count(fg & ~made),
            xp_att=count(xp),
            xp_made=count(xp & good),
            xp_missed=count(xp & ~good),
        )
        .rename({"posteam": "team", "kicker_player_id": "player_id"})
        .with_columns(
            pl.col("season", "week").cast(pl.Int64),
            g=pl.lit(1.0),
        )
        .collect()
    )
```

- [ ] **Step 4: Team defense and D/ST in `teams.py`**

`_DEF_COLUMNS` gains `"posteam"` and `"safety"`. In `team_defense_from`, add `safeties=num("safety").sum(),` to `plays`'s `agg`, then after `plays`:

```python
    # nflverse lists the receiving team as posteam on a kickoff, so a return TD
    # is posteam's. Punt return TDs score for defteam: already defensive_tds.
    returns = (
        lf.filter(pl.col("posteam").is_not_null() & (pl.col("play_type") == "kickoff")
                  & (pl.col("td_team") == pl.col("posteam")))
        .group_by(["posteam", "season", "week"])
        .agg(kick_return_tds=num("touchdown").sum())
        .rename({"posteam": "team"})
    )
```

and the final join becomes:

```python
    return (
        points.join(plays, on=["team", "season", "week"], how="left")
        .join(returns, on=["team", "season", "week"], how="left")
        .with_columns(pl.col("season", "week").cast(pl.Int64))
        .fill_null(0)
        .sort(["season", "week", "team"])
        .collect()
    )
```

Append:

```python
# Points-allowed tiers by the most points each allows, lowest first: a game lands
# in exactly one. Mirrors core/statquery's PA_TIERS.
PA_TIERS: list[tuple[str, float | None]] = [
    ("pa_0", 0), ("pa_1_6", 6), ("pa_7_13", 13), ("pa_14_20", 20),
    ("pa_21_27", 27), ("pa_28_34", 34), ("pa_35", None),
]


def dst_weekly(defense: pl.DataFrame) -> pl.DataFrame:
    """Each team-week as its D/ST pseudo-player's week (core/ingest's dstWeeks)."""
    pa = pl.col("points_allowed").cast(pl.Float64)
    tiers = {}
    below = None
    for mid, most in PA_TIERS:
        cond = pl.lit(True) if most is None else pa <= most
        if below is not None:
            cond = cond & (pa > below)
        tiers[mid] = cond.cast(pl.Float64)
        below = most
    return defense.select(
        player_id=pl.concat_str([pl.lit("DST_"), pl.col("team")]),
        season=pl.col("season").cast(pl.Int64),
        week=pl.col("week").cast(pl.Int64),
        team=pl.col("team"),
        g=pl.lit(1.0),
        dst_sacks=pl.col("sacks").cast(pl.Float64),
        dst_interceptions=pl.col("interceptions").cast(pl.Float64),
        dst_fumble_recoveries=pl.col("fumbles_recovered").cast(pl.Float64),
        dst_tds=(pl.col("defensive_tds") + pl.col("kick_return_tds")).cast(pl.Float64),
        dst_safeties=pl.col("safeties").cast(pl.Float64),
        points_allowed=pa,
        **tiers,
    )


def dst_players(teams_: list[str]) -> pl.DataFrame:
    """A player row per team's D/ST: "KC D/ST", position DST (core/ingest's dstPlayer)."""
    return pl.DataFrame(
        {
            "player_id": [f"DST_{t}" for t in teams_],
            "full_name": [f"{t} D/ST" for t in teams_],
            "position": ["DST"] * len(teams_),
            "team": list(teams_),
            "pfr_player_id": [None] * len(teams_),
        },
        schema={"player_id": pl.String, "full_name": pl.String, "position": pl.String,
                "team": pl.String, "pfr_player_id": pl.String},
    )
```

- [ ] **Step 5: The registry, schema, validation and parity**

In `metrics.py`, extend `Group` to `Literal["volume", "efficiency", "fantasy", "context", "passing", "usage", "kicking", "defense"]`. Before `# ---------------- Internal range-aggregation components`, add (inside the `_M` list, same style as the scoring inputs):

```python
    # ---------------- Kicking (internal, sparse) ----------------
    *[
        Metric(mid, name, mid.upper(), "kicking", definition,
               positions=("K",), decimals=0, internal=True, sparse=True)
        for mid, name, definition in [
            ("fg_att_0_39", "FG Attempts 0-39", "Field goal tries from 39 yards or closer, blocked kicks included."),
            ("fg_att_40_49", "FG Attempts 40-49", "Field goal tries from 40 to 49 yards, blocked kicks included."),
            ("fg_att_50", "FG Attempts 50+", "Field goal tries from 50 yards or farther, blocked kicks included."),
            ("fg_made_0_39", "FGs Made 0-39", "Field goals made from 39 yards or closer."),
            ("fg_made_40_49", "FGs Made 40-49", "Field goals made from 40 to 49 yards."),
            ("fg_made_50", "FGs Made 50+", "Field goals made from 50 yards or farther."),
            ("fg_missed", "FGs Missed", "Field goals missed or blocked, any distance."),
            ("xp_att", "XP Attempts", "Extra point kicks tried."),
            ("xp_made", "XPs Made", "Extra point kicks made."),
            ("xp_missed", "XPs Missed", "Extra point kicks missed, blocked or aborted."),
        ]
    ],

    # ---------------- Team defense (D/ST pseudo-players) ----------------
    *[
        Metric(mid, name, mid.upper(), "defense", definition,
               positions=("DST",), decimals=0, internal=True, sparse=True)
        for mid, name, definition in [
            ("dst_sacks", "D/ST Sacks", "Sacks by the team's defense."),
            ("dst_interceptions", "D/ST Interceptions", "Passes the team's defense intercepted."),
            ("dst_fumble_recoveries", "D/ST Fumble Recoveries", "Opponent fumbles the team recovered."),
            ("dst_tds", "D/ST TDs", "Touchdowns by the defense or on a return: interceptions, fumbles, punts, kickoffs and blocked kicks."),
            ("dst_safeties", "D/ST Safeties", "Safeties the team's defense scored."),
        ]
    ],
    Metric("points_allowed", "Points Allowed", "POINTS_ALLOWED", "defense",
           "Points the opponent scored, however it scored them.",
           positions=("DST",), higher_is_better=False, decimals=0, internal=True),
    *[
        Metric(mid, f"Games Allowing {points}", mid.upper(), "defense",
               f"1 for a game in which the opponent scored {points}: the points-allowed scoring tier.",
               positions=("DST",), decimals=0, internal=True, sparse=True)
        for mid, points in [
            ("pa_0", "0 points"), ("pa_1_6", "1-6 points"), ("pa_7_13", "7-13 points"),
            ("pa_14_20", "14-20 points"), ("pa_21_27", "21-27 points"), ("pa_28_34", "28-34 points"),
            ("pa_35", "35+ points"),
        ]
    ],
```

The Kotlin registry puts kicking and defense *after* the range components; the table is keyed by id, so the order doesn't matter to parity.

In `DIST_FAMILIES`, add:

```python
    **{m: "poisson" for m in (
        "fg_att_0_39", "fg_att_40_49", "fg_att_50", "fg_made_0_39", "fg_made_40_49", "fg_made_50",
        "fg_missed", "xp_att", "xp_made", "xp_missed",
        "dst_interceptions", "dst_fumble_recoveries", "dst_tds", "dst_safeties",
    )},
    "dst_sacks": "negbinom",
    **{m: "categorical" for m in ("pa_0", "pa_1_6", "pa_7_13", "pa_14_20", "pa_21_27", "pa_28_34", "pa_35")},
```

In `schema.py`, set `SCHEMA_VERSION = 6`, add `safeties REAL NOT NULL,` and `kick_return_tds REAL NOT NULL,` after `defensive_tds` in the `team_week_defense` DDL, and add `"safeties", "kick_return_tds"` to `load_team_defense`'s column list after `"defensive_tds"`.

In `validate.py`, append to `COHERENCE_CHECKS`:

```python
    CoherenceCheck("0-39 field goals made within tries", "fg_made_0_39", "fg_att_0_39", "a <= b"),
    CoherenceCheck("40-49 field goals made within tries", "fg_made_40_49", "fg_att_40_49", "a <= b"),
    CoherenceCheck("50+ field goals made within tries", "fg_made_50", "fg_att_50", "a <= b"),
    CoherenceCheck("extra points made within tries", "xp_made", "xp_att", "a <= b"),
    CoherenceCheck("extra points missed within tries", "xp_missed", "xp_att", "a <= b"),
```

and before the `computed` check:

```python
    # Scoring reads the tier, so every D/ST week needs its points allowed and exactly one tier.
    tier_ids = ", ".join(f"'{mid}'" for mid, _ in teams.PA_TIERS)
    bad_tiers = conn.execute(
        f"""SELECT COUNT(*) FROM (
              SELECT SUM(CASE WHEN metric_id IN ({tier_ids}) THEN value ELSE 0 END) AS tiers,
                     MAX(metric_id = 'points_allowed') AS scored
              FROM player_week_stat WHERE player_id LIKE 'DST\\_%' ESCAPE '\\'
              GROUP BY player_id, season, week)
            WHERE tiers <> 1 OR scored = 0"""
    ).fetchone()[0]
    if bad_tiers:
        problems.append(f"D/ST weeks without their points allowed and exactly one points-allowed tier: {bad_tiers}")
```

(import `teams` beside `transform`: `from . import teams, transform`), and change the final log's `+ 8` to `+ 9`.

In `tools/parity.py`, `team_week_defense`'s compared columns gain `"safeties", "kick_return_tds"`.

- [ ] **Step 6: Build kicking and D/ST facts in `build.py`**

In the season loop, replace `defense.append(teams.team_defense(pbp_path))` with:

```python
        season_defense = teams.team_defense(pbp_path)
        defense.append(season_defense)
        frames.append(transform.to_long(teams.dst_weekly(season_defense), metric_ids, sparse_metric_ids()))
        frames.append(transform.to_long(transform.kicking_stats(pbp_path), metric_ids, sparse_metric_ids()))
```

After the loop's `if not frames: raise ...`, before `long = pl.concat(...)`:

```python
    all_defense = pl.concat(defense, how="vertical_relaxed")
    # Each team's defense is a player (core/ingest adds them in writePlayers).
    dst = teams.dst_players(sorted(all_defense["team"].unique().to_list()))
    players = pl.concat(
        [players, dst.with_columns(search_name=_search_name(pl.col("full_name"))).select(players.columns)],
        how="vertical_relaxed",
    )
```

and load `all_defense` instead of re-concatenating: `schema.load_team_defense(conn, all_defense)`.

- [ ] **Step 7: Run the Python tests, then prove parity on real data**

Run: `cd etl && python -m pytest tests/ -q`
Expected: PASS (69 before, now 69 + the new ones).

Run:

```bash
cd etl && python -m gridiron_etl.build --seasons 2025 --out build/parity/py.db && cd ..
./gradlew :core:ingest:buildStatsDb -Pseasons="2025" -Pout=etl/build/parity/kt.db
python etl/tools/parity.py etl/build/parity/py.db etl/build/parity/kt.db
```

Expected: `parity: OK`, with `player_week_stat` and `player` row counts grown by the kicking and D/ST rows, and `team_week_defense` compared on 8 columns. Any mismatch is a real disagreement: find which side is wrong against the definitions in Tasks 1–2 and fix it before committing.

- [ ] **Step 8: Commit**

```bash
git add etl
git commit -m "etl: kicking and D/ST facts, safeties and return TDs, so parity covers K and D/ST"
```

### Task 4: Kicking and D/ST scoring

**Files:**
- Modify: `core/model/src/main/kotlin/dev/gridiron/core/model/Scoring.kt`
- Modify: `core/statquery/src/main/kotlin/dev/gridiron/core/statquery/Component.kt`, `Scoring.kt`, `StatQueryBuilder.kt`
- Modify: `feature/scoring/src/main/kotlin/dev/gridiron/feature/scoring/ScoringEditViewModel.kt`
- Test: `core/statquery/src/test/kotlin/dev/gridiron/core/statquery/ScoringQueryTest.kt`, `RealDatabaseContractTest.kt`; `core/projections/src/test/kotlin/dev/gridiron/core/projections/ScorerTest.kt`; `feature/scoring/src/test/kotlin/dev/gridiron/feature/scoring/ScoringEditViewModelTest.kt`

**Interfaces:**
- Consumes: Task 2's `Components.DST_*`, `Components.PA_*`.
- Produces:
  - `ScoringGroup.KICKING` ("Kicking"), `ScoringGroup.DEFENSE` ("Team defense").
  - `ScoringRule(group, label, fallback: Double = 0.0)` and 18 rules: `FG_MADE_0_39` (3), `FG_MADE_40_49` (4), `FG_MADE_50` (5), `FG_MISSED` (−1), `XP_MADE` (1), `XP_MISSED` (−1), `DST_SACK` (1), `DST_INTERCEPTION` (2), `DST_FUMBLE_RECOVERY` (2), `DST_TD` (6), `DST_SAFETY` (2), `PA_0` (10), `PA_1_6` (7), `PA_7_13` (4), `PA_14_20` (1), `PA_21_27` (0), `PA_28_34` (−1), `PA_35` (−4); the number is each rule's `fallback`.
  - `ScoringProfile.weight(rule)` = the profile's weight, else `rule.fallback`.
  - `Components.FG_MADE_0_39`, `FG_MADE_40_49`, `FG_MADE_50`, `FG_MISSED`, `XP_MADE`, `XP_MISSED`.
  - `SPECIAL_RULES: Set<ScoringRule>` (internal to `:core:statquery`): the kicking and defense rules.

- [ ] **Step 1: Write the failing tests**

In `ScoringQueryTest.kt`, add:

```kotlin
    @Test
    fun `a kicker's week scores field goals by distance, extra points and misses`() {
        db.player("k1", "Place Kicker", position = "K")
        db.week("k1", 1, C.FG_MADE_0_39 to 1, C.FG_MADE_40_49 to 1, C.FG_MADE_50 to 1, C.FG_MISSED to 1, C.XP_MADE to 3, C.XP_MISSED to 1)
        // 3 + 4 + 5 - 1 + 3 - 1
        assertEquals(13.0, db.grid(fantasy(ScoringPresets.PPR)).single().value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `a team defense scores takeaways, TDs, safeties and its points-allowed tier each week`() {
        db.player("DST_KC", "KC D/ST", position = "DST", team = "KC")
        db.week("DST_KC", 1, C.DST_SACKS to 3, C.DST_INTERCEPTIONS to 1, C.DST_FUMBLE_RECOVERIES to 1, C.DST_TDS to 1, C.DST_SAFETIES to 1, C.PA_7_13 to 1)
        db.week("DST_KC", 2, C.DST_SACKS to 1, C.PA_35 to 1)
        // Week 1: 3 + 2 + 2 + 6 + 2 + 4 = 19. Week 2: 1 - 4 = -3.
        assertEquals(16.0, db.grid(fantasy(ScoringPresets.PPR)).single().value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `a kicker who also ran scores both in the same week`() {
        db.player("k1", "Place Kicker", position = "K")
        db.week("k1", 1, C.FG_MADE_0_39 to 1, C.RUSHING_YARDS to 20)
        // 3 + 2
        assertEquals(5.0, db.grid(fantasy(ScoringPresets.PPR)).single().value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `a profile's own kicking weight replaces the default, zero included`() {
        db.player("k1", "Place Kicker", position = "K")
        db.week("k1", 1, C.FG_MADE_50 to 1, C.FG_MISSED to 2)
        val profile = custom(ScoringRule.FG_MADE_50 to 6.0, ScoringRule.FG_MISSED to 0.0)
        assertEquals(6.0, db.grid(fantasy(profile)).single().value(FANTASY_POINTS)!!, EPS)
    }
```

In `ScorerTest.kt`, replace the comment in `an unsupported position (kicker) scores zero without throwing` with `// A null position must not crash score(); the reception rule still reads the map.`, rename it `a null position scores without throwing`, and add:

```kotlin
    @Test
    fun `kicking and team defense score with the common defaults`() {
        val kicker = mapOf(Components.FG_MADE_50 to 1.0, Components.FG_MISSED to 1.0, Components.XP_MADE to 2.0)
        assertEquals(6.0, score(kicker, ScoringPresets.PPR, Position.K), 1e-9)
        val defense = mapOf(Components.DST_SACKS to 2.0, Components.PA_0 to 1.0)
        assertEquals(12.0, score(defense, ScoringPresets.STANDARD, Position.DST), 1e-9)
    }

    @Test
    fun `a profile saved before kicking and defense rules existed scores them with the defaults, and a zero stays zero`() {
        val old = ScoringProfile("u1", "Old", mapOf(ScoringRule.PASS_TD to 6.0))
        assertEquals(5.0, score(mapOf(Components.FG_MADE_50 to 1.0), old, Position.K), 1e-9)
        val noMisses = old.copy(weights = old.weights + (ScoringRule.FG_MISSED to 0.0))
        assertEquals(0.0, score(mapOf(Components.FG_MISSED to 1.0), noMisses, Position.K), 1e-9)
    }
```

(import `dev.gridiron.core.model.ScoringProfile` and `dev.gridiron.core.model.ScoringRule`).

In `ScoringEditViewModelTest.kt`, add:

```kotlin
    @Test
    fun aKickingRuleSetToZeroIsSavedAsZero() = runTest(dispatcher) {
        repo.duplicate(ScoringPresets.PPR.id, "Mine")
        val vm = ScoringEditViewModel("u1", repo)
        advanceUntilIdle()
        vm.onEvent(EditEvent.WeightChanged(ScoringRule.FG_MISSED, "0"))
        vm.onEvent(EditEvent.Save)
        advanceUntilIdle()
        val saved = prefs.current.profiles.single()
        assertEquals(0.0, saved.weight(ScoringRule.FG_MISSED), 0.0)
        // Rules left at their default still score it.
        assertEquals(10.0, saved.weight(ScoringRule.PA_0), 0.0)
    }
```

In `RealDatabaseContractTest.kt`'s `referenceWeek`, add before `for (b in profile.yardageBonuses)`:

```kotlin
        fp += w(ScoringRule.FG_MADE_0_39) * v("fg_made_0_39") + w(ScoringRule.FG_MADE_40_49) * v("fg_made_40_49") +
            w(ScoringRule.FG_MADE_50) * v("fg_made_50") + w(ScoringRule.FG_MISSED) * v("fg_missed") +
            w(ScoringRule.XP_MADE) * v("xp_made") + w(ScoringRule.XP_MISSED) * v("xp_missed") +
            w(ScoringRule.DST_SACK) * v("dst_sacks") + w(ScoringRule.DST_INTERCEPTION) * v("dst_interceptions") +
            w(ScoringRule.DST_FUMBLE_RECOVERY) * v("dst_fumble_recoveries") + w(ScoringRule.DST_TD) * v("dst_tds") +
            w(ScoringRule.DST_SAFETY) * v("dst_safeties") +
            w(ScoringRule.PA_0) * v("pa_0") + w(ScoringRule.PA_1_6) * v("pa_1_6") + w(ScoringRule.PA_7_13) * v("pa_7_13") +
            w(ScoringRule.PA_14_20) * v("pa_14_20") + w(ScoringRule.PA_21_27) * v("pa_21_27") +
            w(ScoringRule.PA_28_34) * v("pa_28_34") + w(ScoringRule.PA_35) * v("pa_35")
```

(`everyRule` already sets a weight for every `ScoringRule`, so the new rules are exercised; the grid spec there has no position filter, so kickers and D/STs are scored and checked).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:statquery:test :core:projections:test`
Expected: FAIL to compile with "Unresolved reference: FG_MADE_0_39".

- [ ] **Step 3: The rules and their defaults**

In `core/model/.../Scoring.kt`, add to `ScoringGroup` after `TURNOVERS("Turnovers"),`:

```kotlin
    KICKING("Kicking"),
    DEFENSE("Team defense"),
```

Replace `ScoringRule`'s KDoc and header:

```kotlin
/**
 * Points per unit of one stat. [fallback] is what a profile that never set
 * the rule scores: zero for the offense's rules, and the common default for
 * kicking and team defense, which arrived after profiles were already saved.
 * IDP is absent on purpose.
 */
public enum class ScoringRule(public val group: ScoringGroup, public val label: String, public val fallback: Double = 0.0) {
```

and add after `FUMBLE_LOST(ScoringGroup.TURNOVERS, "Fumble lost"),`:

```kotlin
    FG_MADE_0_39(ScoringGroup.KICKING, "FG made, 0-39 yds", 3.0),
    FG_MADE_40_49(ScoringGroup.KICKING, "FG made, 40-49 yds", 4.0),
    FG_MADE_50(ScoringGroup.KICKING, "FG made, 50+ yds", 5.0),
    FG_MISSED(ScoringGroup.KICKING, "FG missed", -1.0),
    XP_MADE(ScoringGroup.KICKING, "Extra point made", 1.0),
    XP_MISSED(ScoringGroup.KICKING, "Extra point missed", -1.0),
    DST_SACK(ScoringGroup.DEFENSE, "Sack", 1.0),
    DST_INTERCEPTION(ScoringGroup.DEFENSE, "Interception", 2.0),
    DST_FUMBLE_RECOVERY(ScoringGroup.DEFENSE, "Fumble recovery", 2.0),
    DST_TD(ScoringGroup.DEFENSE, "Defensive or return TD", 6.0),
    DST_SAFETY(ScoringGroup.DEFENSE, "Safety", 2.0),
    PA_0(ScoringGroup.DEFENSE, "0 points allowed", 10.0),
    PA_1_6(ScoringGroup.DEFENSE, "1-6 points allowed", 7.0),
    PA_7_13(ScoringGroup.DEFENSE, "7-13 points allowed", 4.0),
    PA_14_20(ScoringGroup.DEFENSE, "14-20 points allowed", 1.0),
    PA_21_27(ScoringGroup.DEFENSE, "21-27 points allowed", 0.0),
    PA_28_34(ScoringGroup.DEFENSE, "28-34 points allowed", -1.0),
    PA_35(ScoringGroup.DEFENSE, "35+ points allowed", -4.0),
```

In `ScoringProfile`, change `weight`:

```kotlin
    /** The profile's points for [rule], or the rule's [ScoringRule.fallback] when it never set one. */
    public fun weight(rule: ScoringRule): Double = weights[rule] ?: rule.fallback
```

Change `ScoringPresets`'s KDoc to `/** ESPN's default offense scoring in its three reception flavors, with the common kicking and team-defense defaults (each rule's fallback). Immutable; copy one to customize. */`.

In `ScoringEditViewModel.kt`'s `validate`, change `weights = weights.filterValues { it != 0.0 },` to:

```kotlin
        // A rule at its fallback needs no entry; anything else, a zero included, is kept.
        weights = weights.filter { (rule, weight) -> weight != rule.fallback },
```

- [ ] **Step 4: Rule inputs and the scoring query**

In `Component.kt`, add inside `Components` before the D/ST block:

```kotlin

    // Kicking: the kicker's scoring inputs. Internal and sparse; attempts by
    // distance are stored too, for the forecast, but no rule reads them.
    public val FG_MADE_0_39: Component = Component("fg_made_0_39")
    public val FG_MADE_40_49: Component = Component("fg_made_40_49")
    public val FG_MADE_50: Component = Component("fg_made_50")
    public val FG_MISSED: Component = Component("fg_missed")
    public val XP_MADE: Component = Component("xp_made")
    public val XP_MISSED: Component = Component("xp_missed")
```

In `core/statquery/.../Scoring.kt`, add to `RULE_INPUTS` after `ScoringRule.FUMBLE_LOST to on(C.FUMBLES_LOST),`:

```kotlin
    ScoringRule.FG_MADE_0_39 to on(C.FG_MADE_0_39),
    ScoringRule.FG_MADE_40_49 to on(C.FG_MADE_40_49),
    ScoringRule.FG_MADE_50 to on(C.FG_MADE_50),
    ScoringRule.FG_MISSED to on(C.FG_MISSED),
    ScoringRule.XP_MADE to on(C.XP_MADE),
    ScoringRule.XP_MISSED to on(C.XP_MISSED),
    ScoringRule.DST_SACK to on(C.DST_SACKS),
    ScoringRule.DST_INTERCEPTION to on(C.DST_INTERCEPTIONS),
    ScoringRule.DST_FUMBLE_RECOVERY to on(C.DST_FUMBLE_RECOVERIES),
    ScoringRule.DST_TD to on(C.DST_TDS),
    ScoringRule.DST_SAFETY to on(C.DST_SAFETIES),
    ScoringRule.PA_0 to on(C.PA_0),
    ScoringRule.PA_1_6 to on(C.PA_1_6),
    ScoringRule.PA_7_13 to on(C.PA_7_13),
    ScoringRule.PA_14_20 to on(C.PA_14_20),
    ScoringRule.PA_21_27 to on(C.PA_21_27),
    ScoringRule.PA_28_34 to on(C.PA_28_34),
    ScoringRule.PA_35 to on(C.PA_35),
```

and after `BONUS_INPUTS`:

```kotlin
/**
 * Kicking and team-defense rules. Their facts belong to kickers and D/STs, a
 * few rows a week, so the scoring query pivots them on their own rather than
 * widening the offense's pivot.
 */
internal val SPECIAL_RULES: Set<ScoringRule> =
    ScoringRule.entries.filter { it.group == ScoringGroup.KICKING || it.group == ScoringGroup.DEFENSE }.toSet()
```

(import `dev.gridiron.core.model.ScoringGroup`).

In `StatQueryBuilder.kt`, replace `ACTUAL_COMPONENTS` and add the special lists:

```kotlin
/** The offense's rules, in declaration order so equal profiles give identical SQL. */
private val OFFENSE_RULES: List<ScoringRule> = ScoringRule.entries.filter { it !in SPECIAL_RULES }

private val SPECIAL_RULE_LIST: List<ScoringRule> = ScoringRule.entries.filter { it in SPECIAL_RULES }

private val ACTUAL_COMPONENTS: List<Component> =
    (OFFENSE_RULES.flatMap { RULE_INPUTS.getValue(it).actual }.map { it.component } + BONUS_INPUTS.values.flatten())
        .distinct()
        .sortedBy { it.id }

/** Kicking and team-defense inputs: pivoted apart (`ws`), so the offense's pivot stays as narrow as it was. */
private val SPECIAL_COMPONENTS: List<Component> =
    SPECIAL_RULE_LIST.flatMap { RULE_INPUTS.getValue(it).actual }.map { it.component }.distinct().sortedBy { it.id }
```

Update the class KDoc's step 0 to: "When a fantasy column is planned, `wk` pivots the offense's scoring components to one row per player-week, `ws` does the same for kicking and team-defense components, `fw` and `fs` apply the spec's scoring profile to each week (so per-game bonuses see single games), and `fsum` totals them per player into fantasy points, expected fantasy points and FPOE."

In `scoring(...)`, add `val wSpecial = { c: Component -> "COALESCE(ws.s${SPECIAL_COMPONENTS.indexOf(c)}, 0)" }` beside `wActual`, and insert after the `wk` CTE's `GROUP BY s.player_id, s.week` line (before `line("), we AS (")`):

```kotlin
        line("), ws AS (")
        line("  SELECT s.player_id, s.week")
        SPECIAL_COMPONENTS.forEachIndexed { i, c ->
            line("       , SUM(s.value) FILTER (WHERE s.metric_id = ${text(c.id)}) AS s$i")
        }
        line("  FROM player_week_stat s")
        line("  WHERE s.metric_id IN (${SPECIAL_COMPONENTS.joinToString(", ") { text(it.id) }})")
        line("    AND s.season = ${int(spec.season)}")
        line("    AND s.week BETWEEN ${int(spec.weeks.first)} AND ${int(spec.weeks.last)}")
        line("  GROUP BY s.player_id, s.week")
```

Change the `fw` CTE's points call to `${points(profile, OFFENSE_RULES, expected = false, bonuses = true, wActual)}`, and insert after the `fw` CTE's `JOIN player p ON p.player_id = wk.player_id` line:

```kotlin
        line("), fs AS (")
        line("  SELECT ws.player_id")
        line("       , ${points(profile, SPECIAL_RULE_LIST, expected = false, bonuses = false, wSpecial)} AS fp")
        line("  FROM ws")
```

Change the `xf` CTE's call to `${points(profile, OFFENSE_RULES, expected = true, bonuses = false, wExpected)}`. In `players_scored`, add after `SELECT player_id FROM we`:

```kotlin
        line("  UNION")
        line("  SELECT player_id FROM ws")
```

and replace the `fp_agg` join line with:

```kotlin
        line("  LEFT JOIN (SELECT player_id, SUM(fp) AS fp")
        line("             FROM (SELECT player_id, fp FROM fw UNION ALL SELECT player_id, fp FROM fs)")
        line("             GROUP BY player_id) fp_agg")
```

Change `points`:

```kotlin
    /**
     * One week's points from [rules]. Every weight is bound, including zeros,
     * so the SQL shape depends only on the number of bonuses.
     */
    private fun points(profile: ScoringProfile, rules: List<ScoringRule>, expected: Boolean, bonuses: Boolean, w: (Component) -> String): String {
        val terms = mutableListOf<String>()
        for (rule in rules) {
            val inputs = RULE_INPUTS.getValue(rule)
            for (term in if (expected) inputs.expected else inputs.actual) {
                val weight = if (rule == ScoringRule.RECEPTION) {
                    receptionWeight(profile)
                } else {
                    real(profile.weight(rule) * term.sign)
                }
                terms += "$weight * ${w(term.component)}"
            }
        }
        if (bonuses) {
            // Bonuses have no expectation; they only ever add to the offense's actual points.
            for (bonus in profile.yardageBonuses) {
                val yards = BONUS_INPUTS.getValue(bonus.stat).joinToString(" + ", "(", ")") { w(it) }
                val lower = int(bonus.min)
                val upper = bonus.maxExclusive?.let { " AND $yards < ${int(it)}" }.orEmpty()
                val points = real(bonus.points)
                terms += "CASE WHEN $yards >= $lower$upper THEN $points ELSE 0 END"
            }
        }
        return terms.joinToString(" + ", "(", ")")
    }
```

Update the `scoring()` KDoc's first sentence to add "`ws` pivots kicking and team-defense components the same way, and `fs` scores them".

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:model:test :core:statquery:test :core:projections:test :feature:scoring:testDebugUnitTest`
Expected: PASS (the real-data contract tests skip without `GRIDIRON_STATS_DB`).

Then the real-data contract tests, against the database rebuilt in Task 2:

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:statquery:test :core:data:test`
Expected: PASS, including `builder fantasy points match an independent per-week scorer for every player` (now with kickers and D/STs), `every scoring component is a registered internal metric with data`, and both timing tests (`a full-season, many-column grid is fast`, `scoring a full season for every player is fast`). If a timing test fails, rerun `:core:statquery:test` alone before concluding anything (the known CPU-contention flake).

- [ ] **Step 6: Commit**

```bash
git add core/model core/statquery core/projections feature/scoring
git commit -m "scoring: kicking and team-defense rules with common defaults, scored from their own pivot"
```

### Task 5: Kicker inputs and model

**Files:**
- Create: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Kicker.kt`
- Modify: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Inputs.kt`, `ForecastConstants.kt`
- Test: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/KickerTest.kt` (new), `InputsTest.kt`

**Interfaces:**
- Consumes: `PlayerGame` (`get(metric)` is 0 when absent), `ewma(values, halfLife)`, `shrink(observed, n, baseline, k)`, `K.TEAM_HALF_LIFE`.
- Produces:
  - `UNIT_POSITIONS = listOf("K", "DST")`; `ForecastInputs.units: Map<String, PlayerInfo>` and `ForecastInputs.unitHistory: Map<String, List<PlayerGame>>` (both default to empty), loaded by `loadInputs`, apart from the offense (they never reach `teamGames`).
  - `FG_BUCKETS = listOf("0_39", "40_49", "50")`.
  - `TeamKicks(implied: Double?, fieldGoals: Double, extraPoints: Double)`.
  - `AttemptFit(fgIntercept, fgSlope, xpIntercept, xpSlope)` with `fieldGoals(implied): Double`, `extraPoints(implied): Double` (never negative) and `AttemptFit.fit(rows: List<TeamKicks>): AttemptFit?`.
  - `KickLeague(mix: List<Double>, make: List<Double>, xpMake: Double)`, `kickLeague(games: List<PlayerGame>): KickLeague?`.
  - `KickerRates(mix, make, xpMake)`, `kickerRates(games: List<PlayerGame>, league: KickLeague): KickerRates`.
  - `kickStats(rates: KickerRates, fieldGoals: Double, extraPoints: Double): Map<String, Double>` with keys `fg_made_0_39`, `fg_made_40_49`, `fg_made_50`, `fg_missed`, `xp_made`, `xp_missed`.
  - `teamPoints(recent: List<Double>, leagueAverage: Double): Double`.
  - Constants `K.KICK_MIN_FIT_ROWS`, `K.KICK_MIX_K`, `K.KICK_MAKE_K`, `K.XP_MAKE_K`, `K.TEAM_POINTS_K_GAMES`.

- [ ] **Step 1: Write the failing tests**

`core/forecast/src/test/kotlin/dev/gridiron/core/forecast/KickerTest.kt`:

```kotlin
package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class KickerTest {
    private fun game(week: Int, vararg values: Pair<String, Double>) =
        PlayerGame("K1", 2025, week, "AAA", mapOf("g" to 1.0) + values)

    @Test
    fun `tries rise with implied points along a least-squares line`() {
        val rows = (0 until 80).map { i ->
            val implied = 14.0 + i % 20
            TeamKicks(implied, fieldGoals = 0.5 + 0.06 * implied, extraPoints = 0.1 * implied)
        }
        val fit = AttemptFit.fit(rows)!!
        assertEquals(0.5 + 0.06 * 24, fit.fieldGoals(24.0), 1e-9)
        assertEquals(2.4, fit.extraPoints(24.0), 1e-9)
    }

    @Test
    fun `with few lined games the fit is the flat average of every game, and never negative`() {
        val fit = AttemptFit.fit(listOf(TeamKicks(20.0, 1.0, 2.0), TeamKicks(30.0, 3.0, 4.0), TeamKicks(null, 2.0, 3.0)))!!
        assertEquals(2.0, fit.fieldGoals(50.0), 1e-9)
        assertEquals(3.0, fit.extraPoints(0.0), 1e-9)
        assertNull(AttemptFit.fit(emptyList()))
        val steep = AttemptFit(fgIntercept = -2.0, fgSlope = 0.1, xpIntercept = -1.0, xpSlope = 0.1)
        assertEquals(0.0, steep.fieldGoals(10.0), 1e-9)
    }

    @Test
    fun `the league's distance mix and make rates pool every kick`() {
        val league = kickLeague(
            listOf(
                game(1, "fg_att_0_39" to 2.0, "fg_made_0_39" to 2.0, "fg_att_50" to 2.0, "fg_made_50" to 1.0, "xp_att" to 3.0, "xp_made" to 3.0),
                game(2, "fg_att_40_49" to 4.0, "fg_made_40_49" to 3.0, "xp_att" to 1.0),
            ),
        )!!
        assertEquals(listOf(0.25, 0.5, 0.25), league.mix)
        assertEquals(listOf(1.0, 0.75, 0.5), league.make)
        assertEquals(0.75, league.xpMake, 1e-12)
        assertNull(kickLeague(listOf(game(1, "xp_att" to 2.0))))
    }

    @Test
    fun `a kicker's mix and accuracy move off the league's only with many kicks`() {
        val league = KickLeague(listOf(0.5, 0.3, 0.2), listOf(0.9, 0.8, 0.6), 0.95)
        val rates = kickerRates(listOf(game(1, "fg_att_50" to 10.0, "fg_made_50" to 10.0, "xp_att" to 30.0, "xp_made" to 30.0)), league)
        // 10 kicks from 50+, all made, shrunk with k = 30 attempts toward the league's 60%.
        assertEquals((10 * 1.0 + K.KICK_MAKE_K * 0.6) / (10 + K.KICK_MAKE_K), rates.make[2], 1e-12)
        assertEquals(0.9, rates.make[0], 1e-12) // no kicks from there: the league's
        assertEquals((30 * 1.0 + K.XP_MAKE_K * 0.95) / (30 + K.XP_MAKE_K), rates.xpMake, 1e-12)
        assertEquals((10 * 1.0 + K.KICK_MIX_K * 0.2) / (10 + K.KICK_MIX_K), rates.mix[2], 1e-12)
        assertEquals(1.0, rates.mix.sum(), 1e-12)
    }

    @Test
    fun `a kicker's projected kicks split his team's tries by distance and accuracy`() {
        val stats = kickStats(KickerRates(listOf(0.5, 0.3, 0.2), listOf(0.9, 0.8, 0.5), 0.95), fieldGoals = 2.0, extraPoints = 3.0)
        assertEquals(0.9, stats.getValue("fg_made_0_39"), 1e-12)
        assertEquals(0.48, stats.getValue("fg_made_40_49"), 1e-12)
        assertEquals(0.2, stats.getValue("fg_made_50"), 1e-12)
        assertEquals(2.0 - 0.9 - 0.48 - 0.2, stats.getValue("fg_missed"), 1e-12)
        assertEquals(2.85, stats.getValue("xp_made"), 1e-12)
        assertEquals(0.15, stats.getValue("xp_missed"), 1e-12)
    }

    @Test
    fun `without a line a team's expected points are its recent scoring, shrunk toward the league's`() {
        assertEquals(22.0, teamPoints(emptyList(), 22.0), 1e-12)
        val scored = listOf(30.0, 30.0, 30.0, 30.0)
        val recent = ewma(scored, K.TEAM_HALF_LIFE)!!
        assertEquals((4 * recent + K.TEAM_POINTS_K_GAMES * 22.0) / (4 + K.TEAM_POINTS_K_GAMES), teamPoints(scored, 22.0), 1e-12)
    }
}
```

In `InputsTest.kt`, change the comment `// Kickers aren't projected: this target must not reach AAA's team total.` to `// Kickers are projected apart from the offense: this target must not reach AAA's team total.`, and add:

```kotlin
    @Test
    fun `kickers and team defenses load apart from the offense`() {
        TestDb(File(dir, "units.db")).use { db ->
            db.player("WR1", "WR", "AAA")
            db.player("K1", "K", "AAA")
            db.player("DST_AAA", "DST", "AAA")
            db.week("WR1", 2025, 1, "AAA", "targets" to 5.0)
            db.week("K1", 2025, 1, "AAA", "fg_att_50" to 1.0, "fg_made_50" to 1.0)
            db.week("DST_AAA", 2025, 1, "AAA", "dst_sacks" to 3.0, "points_allowed" to 17.0, "pa_14_20" to 1.0)

            val inputs = loadInputs(db.conn)

            assertEquals(setOf("WR1"), inputs.players.keys)
            assertEquals(setOf("K1", "DST_AAA"), inputs.units.keys)
            assertEquals(1.0, inputs.unitHistory.getValue("K1").single()["fg_made_50"])
            assertEquals(17.0, inputs.unitHistory.getValue("DST_AAA").single()["points_allowed"])
            assertEquals(3.0, inputs.unitHistory.getValue("DST_AAA").single()["dst_sacks"])
            assertEquals(setOf("WR1"), inputs.history.keys)
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:forecast:test`
Expected: FAIL to compile with "Unresolved reference: AttemptFit" and "Unresolved reference: units".

- [ ] **Step 3: The constants**

In `ForecastConstants.kt`, add inside `object K` after the props constants:

```kotlin

    // K model (spec §6). Judgments, not fits: field goal accuracy is noisy, so a kicker's own mix and
    // make rates need many kicks to move off the league's. The accuracy page measures the result.
    // Fewer lined team-games than this and field goal and extra point tries are flat averages.
    const val KICK_MIN_FIT_ROWS = 64
    const val KICK_MIX_K = 20.0 // field goal tries
    const val KICK_MAKE_K = 30.0 // tries in the bucket
    const val XP_MAKE_K = 60.0 // extra point tries
    // Without a line, a team's expected points: its recent scoring, shrunk this many games toward the league's.
    const val TEAM_POINTS_K_GAMES = 4.0
```

- [ ] **Step 4: The kicker model**

`core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Kicker.kt`:

```kotlin
package dev.gridiron.core.forecast

/** Field goal distance buckets, shortest first, as the kicking metrics name them. */
internal val FG_BUCKETS: List<String> = listOf("0_39", "40_49", "50")

/** One team-game's kicks, and its implied points when a line was posted: a row of the tries fit. */
internal class TeamKicks(val implied: Double?, val fieldGoals: Double, val extraPoints: Double)

/**
 * Field goal and extra point tries as a line in implied team points (spec
 * §6's linear model), fit walk-forward on the games before the week.
 */
internal class AttemptFit(val fgIntercept: Double, val fgSlope: Double, val xpIntercept: Double, val xpSlope: Double) {
    fun fieldGoals(implied: Double): Double = (fgIntercept + fgSlope * implied).coerceAtLeast(0.0)

    fun extraPoints(implied: Double): Double = (xpIntercept + xpSlope * implied).coerceAtLeast(0.0)

    companion object {
        /**
         * Least squares on the rows with a line. With fewer than
         * [K.KICK_MIN_FIT_ROWS] of them (or no spread in implied points), a
         * flat average over every row. Null with no rows at all.
         */
        fun fit(rows: List<TeamKicks>): AttemptFit? {
            if (rows.isEmpty()) return null
            val lined = rows.filter { it.implied != null }
            val sloped = lined.size >= K.KICK_MIN_FIT_ROWS
            val (fgA, fgB) = if (sloped) line(lined) { it.fieldGoals } else rows.map { it.fieldGoals }.average() to 0.0
            val (xpA, xpB) = if (sloped) line(lined) { it.extraPoints } else rows.map { it.extraPoints }.average() to 0.0
            return AttemptFit(fgA, fgB, xpA, xpB)
        }

        /** (intercept, slope) of [y] on implied points; flat when implied points don't vary. */
        private fun line(rows: List<TeamKicks>, y: (TeamKicks) -> Double): Pair<Double, Double> {
            val mx = rows.map { it.implied!! }.average()
            val my = rows.map(y).average()
            val sxx = rows.sumOf { (it.implied!! - mx) * (it.implied - mx) }
            if (sxx <= 1e-9) return my to 0.0
            val slope = rows.sumOf { (it.implied!! - mx) * (y(it) - my) } / sxx
            return (my - slope * mx) to slope
        }
    }
}

/** League kicking before a week: each bucket's share of field goal tries and make rate, and the extra point make rate. */
internal class KickLeague(val mix: List<Double>, val make: List<Double>, val xpMake: Double)

/** A kicker's own mix and accuracy, each shrunk toward the league's. The mix still sums to one. */
internal class KickerRates(val mix: List<Double>, val make: List<Double>, val xpMake: Double)

/** Pools every kick in [games]; null until there's at least one field goal and one extra point try. */
internal fun kickLeague(games: List<PlayerGame>): KickLeague? {
    val tries = FG_BUCKETS.map { b -> games.sumOf { it["fg_att_$b"] } }
    val made = FG_BUCKETS.map { b -> games.sumOf { it["fg_made_$b"] } }
    val total = tries.sum()
    val xpTries = games.sumOf { it["xp_att"] }
    if (total <= 0.0 || xpTries <= 0.0) return null
    return KickLeague(
        mix = tries.map { it / total },
        make = tries.indices.map { if (tries[it] > 0.0) made[it] / tries[it] else 0.0 },
        xpMake = games.sumOf { it["xp_made"] } / xpTries,
    )
}

/** A kicker's rates from his [games]: the mix shrunk by his field goal tries, each make rate by its bucket's. */
internal fun kickerRates(games: List<PlayerGame>, league: KickLeague): KickerRates {
    val tries = FG_BUCKETS.map { b -> games.sumOf { it["fg_att_$b"] } }
    val made = FG_BUCKETS.map { b -> games.sumOf { it["fg_made_$b"] } }
    val total = tries.sum()
    val xpTries = games.sumOf { it["xp_att"] }
    return KickerRates(
        mix = FG_BUCKETS.indices.map { shrink(if (total > 0.0) tries[it] / total else null, total, league.mix[it], K.KICK_MIX_K) },
        make = FG_BUCKETS.indices.map { shrink(if (tries[it] > 0.0) made[it] / tries[it] else null, tries[it], league.make[it], K.KICK_MAKE_K) },
        xpMake = shrink(if (xpTries > 0.0) games.sumOf { it["xp_made"] } / xpTries else null, xpTries, league.xpMake, K.XP_MAKE_K),
    )
}

/** A kicker's projected scoring stats in a game where his team tries [fieldGoals] field goals and [extraPoints] extra points. */
internal fun kickStats(rates: KickerRates, fieldGoals: Double, extraPoints: Double): Map<String, Double> = buildMap {
    var missed = 0.0
    FG_BUCKETS.forEachIndexed { i, bucket ->
        val tries = fieldGoals * rates.mix[i]
        put("fg_made_$bucket", tries * rates.make[i])
        missed += tries * (1 - rates.make[i])
    }
    put("fg_missed", missed)
    put("xp_made", extraPoints * rates.xpMake)
    put("xp_missed", extraPoints * (1 - rates.xpMake))
}

/** A team's expected points without a line: its [recent] scoring (oldest first), recency-weighted and shrunk toward [leagueAverage]. */
internal fun teamPoints(recent: List<Double>, leagueAverage: Double): Double =
    shrink(ewma(recent, K.TEAM_HALF_LIFE), recent.size.toDouble(), leagueAverage, K.TEAM_POINTS_K_GAMES)
```

- [ ] **Step 5: Load kickers and D/STs apart from the offense**

In `Inputs.kt`, add after `POSITIONS`:

```kotlin
/** Kickers and team defenses: projected by their own models, from their own stats, never counted in team volume. */
internal val UNIT_POSITIONS: List<String> = listOf("K", "DST")
```

Add two properties to `ForecastInputs` (after `expectedThrough`):

```kotlin
    /** Kickers and D/STs, by player id. */
    val units: Map<String, PlayerInfo> = emptyMap(),
    /** Their weeks, per player id, oldest first. */
    val unitHistory: Map<String, List<PlayerGame>> = emptyMap(),
```

Add after `READ_METRICS`:

```kotlin
private val UNIT_METRICS = listOf(
    "g", "fg_att_0_39", "fg_att_40_49", "fg_att_50", "fg_made_0_39", "fg_made_40_49", "fg_made_50",
    "fg_missed", "xp_att", "xp_made", "xp_missed",
    "dst_sacks", "dst_interceptions", "dst_fumble_recoveries", "dst_tds", "dst_safeties", "points_allowed",
)
```

In `loadInputs`, change `val history = readHistory(conn)` to `val history = readHistory(conn, POSITIONS, READ_METRICS)` and the return to:

```kotlin
    return ForecastInputs(
        readPlayers(conn, POSITIONS), history, readGames(conn), teamGames, readExpectedThrough(conn),
        units = readPlayers(conn, UNIT_POSITIONS),
        unitHistory = readHistory(conn, UNIT_POSITIONS, UNIT_METRICS),
    )
```

Give `readPlayers` and `readHistory` their positions (and metrics) as parameters:

```kotlin
private fun readPlayers(conn: SQLiteConnection, positions: List<String>): Map<String, PlayerInfo> =
    conn.prepare(
        "SELECT player_id, full_name, position, team FROM player WHERE position IN (${positions.joinToString(",") { "?" }})",
    ).use { st ->
        positions.forEachIndexed { i, p -> st.bindText(i + 1, p) }
        buildMap {
            while (st.step()) put(st.getText(0), PlayerInfo(st.getText(0), st.getText(1), st.getText(2), st.textOrNull(3)))
        }
    }
```

```kotlin
private fun readHistory(conn: SQLiteConnection, positions: List<String>, metrics: List<String>): Map<String, List<PlayerGame>> {
    val history = HashMap<String, MutableList<PlayerGame>>()
    conn.prepare(
        """SELECT s.player_id, s.season, s.week, s.team, s.metric_id, s.value FROM player_week_stat s
           JOIN player p ON p.player_id = s.player_id
           WHERE p.position IN (${positions.joinToString(",") { "?" }}) AND s.team IS NOT NULL
             AND s.metric_id IN (${metrics.joinToString(",") { "?" }})
           ORDER BY s.player_id, s.season, s.week""",
    ).use { st ->
        (positions + metrics).forEachIndexed { i, v -> st.bindText(i + 1, v) }
```

(the rest of `readHistory`'s body is unchanged).

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :core:forecast:test`
Expected: PASS, `KickerTest` 6/6 and both `InputsTest` tests.

- [ ] **Step 7: Commit**

```bash
git add core/forecast
git commit -m "forecast: load kickers and D/STs apart from the offense; the kicker model"
```

### Task 6: D/ST model

**Files:**
- Create: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Defense.kt`
- Modify: `core/forecast/build.gradle.kts`, `ForecastConstants.kt`
- Test: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/DefenseTest.kt` (new)

**Interfaces:**
- Consumes: `PA_TIERS`, `PaTier` (`:core:statquery`, Task 2); `ewma`, `shrink`, `capAround`; `PlayerGame`.
- Produces:
  - `DST_STATS = listOf("dst_sacks", "dst_interceptions", "dst_fumble_recoveries", "dst_tds", "dst_safeties")`.
  - `normalCdf(z: Double): Double`; `tierChances(mean: Double, sd: Double): Map<String, Double>` (keys in `PA_TIERS` order, summing to 1); `paSpread(misses: List<Double>): Double`.
  - `DefenseLeague(perGame: Map<String, Double>, pointsAllowed: Double)`, `defenseLeague(games: List<PlayerGame>): DefenseLeague?`.
  - `unitRate(values: List<Double>, league: Double, k: Double): Double`; `opponentFactor(allowed: List<Double>, league: Double): Double`.
  - `DefenseStages(baseline, afterMatchup, final)` (each `Map<String, Double>` of `DST_STATS` and tier ids); `defenseStages(own, ownAllowed, factors, opponentScores, league, implied: Double?, sd): DefenseStages`.
  - Constants `K.DST_HALF_LIFE`, `K.DST_K`, `K.DST_PA_K`, `K.DST_OPP_K`, `K.DST_CAP`, `K.PA_SD_DEFAULT`, `K.PA_SD_MIN_GAMES`.

- [ ] **Step 1: Write the failing tests**

`core/forecast/src/test/kotlin/dev/gridiron/core/forecast/DefenseTest.kt`:

```kotlin
package dev.gridiron.core.forecast

import dev.gridiron.core.statquery.PA_TIERS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefenseTest {
    @Test
    fun `the normal CDF matches known values`() {
        assertEquals(0.5, normalCdf(0.0), 1e-7)
        assertEquals(0.975, normalCdf(1.959964), 1e-6)
        assertEquals(0.025, normalCdf(-1.959964), 1e-6)
        assertEquals(0.8413447, normalCdf(1.0), 1e-6)
    }

    @Test
    fun `tier chances cover every outcome and sum to one`() {
        val chances = tierChances(22.0, 10.0)
        assertEquals(PA_TIERS.map { it.component.id }, chances.keys.toList())
        assertEquals(1.0, chances.values.sum(), 1e-12)
        // A shutout is everything below half a point: about 1.6% at a mean of 22.
        assertEquals(normalCdf((0.5 - 22.0) / 10.0), chances.getValue("pa_0"), 1e-12)
        assertEquals(normalCdf((27.5 - 22.0) / 10.0) - normalCdf((20.5 - 22.0) / 10.0), chances.getValue("pa_21_27"), 1e-12)
        assertEquals(1.0 - normalCdf((34.5 - 22.0) / 10.0), chances.getValue("pa_35"), 1e-12)
    }

    @Test
    fun `more points expected means worse tiers`() {
        val good = tierChances(14.0, 10.0)
        val bad = tierChances(30.0, 10.0)
        assertTrue(good.getValue("pa_7_13") > bad.getValue("pa_7_13"))
        assertTrue(good.getValue("pa_35") < bad.getValue("pa_35"))
    }

    @Test
    fun `the points spread is measured once there are enough games`() {
        assertEquals(K.PA_SD_DEFAULT, paSpread(List(K.PA_SD_MIN_GAMES - 1) { 3.0 }), 0.0)
        assertEquals(8.0, paSpread(List(K.PA_SD_MIN_GAMES) { if (it % 2 == 0) 8.0 else -8.0 }), 1e-12)
    }

    @Test
    fun `the league's per-game averages pool every D-ST game`() {
        val games = listOf(
            PlayerGame("DST_A", 2025, 1, "AAA", mapOf("g" to 1.0, "dst_sacks" to 4.0, "points_allowed" to 10.0)),
            PlayerGame("DST_B", 2025, 1, "BBB", mapOf("g" to 1.0, "dst_sacks" to 2.0, "dst_tds" to 1.0, "points_allowed" to 30.0)),
        )
        val league = defenseLeague(games)!!
        assertEquals(3.0, league.perGame.getValue("dst_sacks"), 1e-12)
        assertEquals(0.5, league.perGame.getValue("dst_tds"), 1e-12)
        assertEquals(0.0, league.perGame.getValue("dst_safeties"), 1e-12)
        assertEquals(20.0, league.pointsAllowed, 1e-12)
        assertNull(defenseLeague(emptyList()))
    }

    @Test
    fun `a unit's rate leans on its own games as they add up`() {
        assertEquals(2.5, unitRate(emptyList(), 2.5, 6.0), 1e-12)
        val own = ewma(listOf(4.0, 4.0, 4.0), K.DST_HALF_LIFE)!!
        assertEquals((3 * own + 6 * 2.5) / 9, unitRate(listOf(4.0, 4.0, 4.0), 2.5, 6.0), 1e-12)
    }

    @Test
    fun `an offense that gives up a lot raises the defense's rate, within the cap`() {
        assertEquals(1.0, opponentFactor(emptyList(), 2.5), 1e-12)
        assertEquals(1.0 + K.DST_CAP, opponentFactor(List(40) { 10.0 }, 2.5), 1e-12)
        assertEquals(1.0, opponentFactor(listOf(9.0), 0.0), 1e-12)
    }

    @Test
    fun `the matchup scales stats and points allowed, and a line replaces points allowed`() {
        val league = DefenseLeague(DST_STATS.associateWith { 1.0 }, 22.0)
        val own = DST_STATS.associateWith { 2.0 }

        val stages = defenseStages(own, 20.0, mapOf("dst_sacks" to 1.2), opponentScores = 33.0, league = league, implied = 17.0, sd = 10.0)

        assertEquals(2.0, stages.baseline.getValue("dst_sacks"), 1e-12)
        assertEquals(2.4, stages.afterMatchup.getValue("dst_sacks"), 1e-12)
        assertEquals(2.0, stages.afterMatchup.getValue("dst_interceptions"), 1e-12)
        assertEquals(2.4, stages.final.getValue("dst_sacks"), 1e-12)
        fun tiers(m: Map<String, Double>) = m.filterKeys { it.startsWith("pa_") }
        assertEquals(tierChances(20.0, 10.0), tiers(stages.baseline))
        // 20 allowed against an offense scoring 33 where the league scores 22: 30.
        assertEquals(tierChances(30.0, 10.0), tiers(stages.afterMatchup))
        assertEquals(tierChances(17.0, 10.0), tiers(stages.final))

        val noLine = defenseStages(own, 20.0, emptyMap(), 33.0, league, implied = null, sd = 10.0)
        assertEquals(noLine.afterMatchup, noLine.final)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:forecast:test --tests "dev.gridiron.core.forecast.DefenseTest"`
Expected: FAIL to compile with "Unresolved reference: PA_TIERS" (the forecast doesn't see `:core:statquery` yet) and "Unresolved reference: tierChances".

- [ ] **Step 3: Let the forecast see the one tier definition**

In `core/forecast/build.gradle.kts`, add to `dependencies` (after `api(libs.androidx.sqlite)`):

```kotlin
    implementation(projects.core.statquery)
```

- [ ] **Step 4: The constants**

In `ForecastConstants.kt`, add after the K model's constants:

```kotlin

    // D/ST model (spec §6). Judgments, not fits, measured by the accuracy page. A unit's own per-game
    // rates are recency-weighted and shrunk toward the league's by games: rarer, noisier events harder.
    const val DST_HALF_LIFE = 6.0
    val DST_K: Map<String, Double> = mapOf(
        "dst_sacks" to 6.0, "dst_interceptions" to 12.0, "dst_fumble_recoveries" to 20.0,
        "dst_tds" to 30.0, "dst_safeties" to 60.0,
    )
    const val DST_PA_K = 6.0
    // What defenses got against an offense, shrunk this many games, and capped to 1 ± DST_CAP of the league's.
    const val DST_OPP_K = 8.0
    const val DST_CAP = 0.30
    // Points allowed are about normal around their mean: the spread of real team scores around their
    // implied points, measured once this many lined team-games are in; about 10 points before that.
    const val PA_SD_DEFAULT = 10.0
    const val PA_SD_MIN_GAMES = 100
```

- [ ] **Step 5: The D/ST model**

`core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Defense.kt`:

```kotlin
package dev.gridiron.core.forecast

import dev.gridiron.core.statquery.PA_TIERS
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** The D/ST stats projected as rates; points allowed become tier chances. */
internal val DST_STATS: List<String> = listOf("dst_sacks", "dst_interceptions", "dst_fumble_recoveries", "dst_tds", "dst_safeties")

/** The standard normal CDF, through Abramowitz and Stegun's 7.1.26 erf (error below 1.5e-7). */
internal fun normalCdf(z: Double): Double {
    val x = abs(z) / sqrt(2.0)
    val t = 1.0 / (1.0 + 0.3275911 * x)
    val poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
    val erf = 1.0 - poly * exp(-x * x)
    return if (z >= 0) 0.5 * (1.0 + erf) else 0.5 * (1.0 - erf)
}

/**
 * Each points-allowed tier's chance when points allowed are about
 * Normal([mean], [sd]) and land on whole points: a tier up to 13 takes
 * everything below 13.5. Below half a point is a shutout. The chances sum to one.
 */
internal fun tierChances(mean: Double, sd: Double): Map<String, Double> {
    var below = 0.0
    return PA_TIERS.associate { tier ->
        val upTo = if (tier.most.isInfinite()) 1.0 else normalCdf((tier.most + 0.5 - mean) / sd)
        val chance = (upTo - below).coerceAtLeast(0.0)
        below = maxOf(below, upTo)
        tier.component.id to chance
    }
}

/** How far real team scores land from their implied points ([misses]): the spread of points allowed. */
internal fun paSpread(misses: List<Double>): Double =
    if (misses.size < K.PA_SD_MIN_GAMES) K.PA_SD_DEFAULT else sqrt(misses.sumOf { it * it } / misses.size)

/** League per-game averages of every D/ST stat and of points allowed, before a week. */
internal class DefenseLeague(val perGame: Map<String, Double>, val pointsAllowed: Double)

internal fun defenseLeague(games: List<PlayerGame>): DefenseLeague? {
    if (games.isEmpty()) return null
    return DefenseLeague(
        DST_STATS.associateWith { stat -> games.sumOf { it[stat] } / games.size },
        games.sumOf { it["points_allowed"] } / games.size,
    )
}

/** A per-game rate from [values] (oldest first): recency-weighted, and shrunk toward [league] by games. */
internal fun unitRate(values: List<Double>, league: Double, k: Double): Double =
    shrink(ewma(values, K.DST_HALF_LIFE), values.size.toDouble(), league, k)

/**
 * How much of a stat an offense gives up against the league's per-game
 * [league], from what defenses got against it ([allowed], oldest first). It
 * multiplies the defense's own rate, within 1 ± [K.DST_CAP].
 */
internal fun opponentFactor(allowed: List<Double>, league: Double): Double =
    if (league <= 0.0) 1.0 else (unitRate(allowed, league, K.DST_OPP_K) / league).capAround(K.DST_CAP)

/** A D/ST's projection, stage by stage: its own rates, then the matchup, then the line. */
internal class DefenseStages(val baseline: Map<String, Double>, val afterMatchup: Map<String, Double>, val final: Map<String, Double>)

/**
 * [own] per-game rates and [ownAllowed] points allowed are the baseline. The
 * matchup multiplies each stat by its opponent [factors] and scales points
 * allowed by how the opponent scores ([opponentScores]) against the league's
 * average. Game script replaces points allowed with the opponent's [implied]
 * points when a line is posted (spec §6).
 */
internal fun defenseStages(
    own: Map<String, Double>,
    ownAllowed: Double,
    factors: Map<String, Double>,
    opponentScores: Double,
    league: DefenseLeague,
    implied: Double?,
    sd: Double,
): DefenseStages {
    val matchupPoints = if (league.pointsAllowed > 0.0) ownAllowed * opponentScores / league.pointsAllowed else ownAllowed
    val matched = own.mapValues { (stat, rate) -> rate * (factors[stat] ?: 1.0) }
    return DefenseStages(
        baseline = own + tierChances(ownAllowed, sd),
        afterMatchup = matched + tierChances(matchupPoints, sd),
        final = matched + tierChances(implied ?: matchupPoints, sd),
    )
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :core:forecast:test`
Expected: PASS, `DefenseTest` 8/8.

- [ ] **Step 7: Commit**

```bash
git add core/forecast
git commit -m "forecast: the D/ST model (own rates times the opponent's, points-allowed tier chances)"
```

### Task 7: Kickers and D/STs in the walk-forward

**Files:**
- Create: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Units.kt`
- Modify: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Projector.kt`, `GameScript.kt`, `Kinds.kt`, `ForecastConstants.kt`
- Test: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/ForecastEngineTest.kt`, `KindsTest.kt`; `core/data/src/test/kotlin/dev/gridiron/core/data/ProjectionsContractTest.kt`

**Interfaces:**
- Consumes: Tasks 5 and 6 (`ForecastInputs.units`, `unitHistory`, the kicker and D/ST models); `ProjectionSink`; `Game.impliedPoints(team)`, `Game.opponentOf(team)`; `varianceFor`; `referencePoints`.
- Produces:
  - `internal enum class WeekKind { PAST, UPCOMING, REST }` (top level in `Projector.kt`, was private).
  - `averageImplied(games: List<Game>, season: Int): Double` (`GameScript.kt`), used by both projectors.
  - `UnitProjector(inputs, gameOf, sink)` with `week(season, week, kind, ros)` and `rest(season, week, ros)`.
  - `referencePoints` also scores K and D/ST components with the scoring defaults.
  - `K.EMPIRICAL_CV` gains `"K" to 0.52` and `"DST" to 0.85`. `FORECAST_VERSION` = 4.
  - Stored rows: a kicker's `fg_made_0_39`, `fg_made_40_49`, `fg_made_50`, `fg_missed`, `xp_made`, `xp_missed`; a D/ST's `DST_STATS` and `pa_*` tier chances (variance `p(1 − p)`). Factors: a kicker's `game_script` when his game has a line; a D/ST's `matchup` always and `game_script` when the line is posted.

- [ ] **Step 1: Write the failing tests**

In `ForecastEngineTest.kt`, import `dev.gridiron.core.statquery.paTier`, give `league(...)` a `units: Boolean = false` parameter and a `dstA2025Week2Sacks: Double = 2.0` parameter, and add unit players and weeks when `units` is true. After the player loop's `db.player("WR_$letter", "WR", team)`:

```kotlin
            if (units) {
                db.player("K_$letter", "K", team)
                db.player("DST_$team", "DST", team)
            }
```

In the schedule loop, after `playWeek(db, away, season, week, wrA2025Week2Targets)`:

```kotlin
                        if (units) {
                            // TestDb's played games end 21-17: the home defense allowed 17, the away one 21.
                            unitWeek(db, home, season, week, allowed = 17.0, dstA2025Week2Sacks)
                            unitWeek(db, away, season, week, allowed = 21.0, dstA2025Week2Sacks)
                        }
```

and after the week-4 `playWeek(db, "CCC", 2025, 4, wrA2025Week2Targets)`:

```kotlin
            if (units) {
                unitWeek(db, "AAA", 2025, 4, allowed = 17.0, dstA2025Week2Sacks)
                unitWeek(db, "CCC", 2025, 4, allowed = 21.0, dstA2025Week2Sacks)
            }
```

and add:

```kotlin
    private fun unitWeek(db: TestDb, team: String, season: Int, week: Int, allowed: Double, dstA2025Week2Sacks: Double) {
        val k = 1.0 + 0.1 * teams.indexOf(team)
        db.week(
            "K_${team.first()}", season, week, team,
            "fg_att_0_39" to 1.0, "fg_made_0_39" to 1.0, "fg_att_40_49" to k, "fg_made_40_49" to 1.0,
            "xp_att" to 2.0 * k, "xp_made" to 2.0 * k,
        )
        val sacks = if (team == "AAA" && season == 2025 && week == 2) dstA2025Week2Sacks else 2.0 * k
        db.week(
            "DST_$team", season, week, team,
            "dst_sacks" to sacks, "dst_interceptions" to 1.0, "points_allowed" to allowed, paTier(allowed).id to 1.0,
        )
    }

    private fun kickers(db: TestDb, season: Int, week: Int): List<String?> = db.query(
        "SELECT DISTINCT player_id FROM player_week_projection WHERE season = $season AND week = $week AND player_id LIKE 'K%' ORDER BY 1",
    ).map { it[0] }
```

Add the tests:

```kotlin
    @Test
    fun `kickers and team defenses get the upcoming week's stages and factors, and past weeks' final`() {
        league("units.db", units = true).use { db ->
            run(db)
            fun stages(id: String, week: Int) = db.query(
                "SELECT DISTINCT stage FROM player_week_projection WHERE player_id = '$id' AND season = 2025 AND week = $week ORDER BY stage",
            ).map { it[0] }
            for (id in listOf("K_A", "K_B", "DST_AAA", "DST_BBB")) {
                assertEquals(listOf("baseline", "final"), stages(id, 3), id)
                assertEquals(listOf("final"), stages(id, 2), id)
            }
            // AAA-DDD has a line: AAA's kicker follows it, and AAA's D/ST faces DDD's implied points.
            assertEquals(listOf("game_script"), factors(db, "K_A"))
            assertEquals(listOf("game_script", "matchup"), factors(db, "DST_AAA"))
            // BBB-CCC has none: no game script, so the final projection is the matchup's.
            assertEquals(emptyList<String?>(), factors(db, "K_B"))
            assertEquals(listOf("matchup"), factors(db, "DST_BBB"))
            assertEquals(
                db.query("SELECT metric_id, mean FROM player_week_projection WHERE player_id = 'K_B' AND season = 2025 AND week = 3 AND stage = 'baseline' ORDER BY 1"),
                db.query("SELECT metric_id, mean FROM player_week_projection WHERE player_id = 'K_B' AND season = 2025 AND week = 3 AND stage = 'final' ORDER BY 1"),
            )
            val stored = db.query("SELECT mean, variance FROM player_week_projection WHERE player_id LIKE 'K%' OR player_id LIKE 'DST%'")
            assertTrue(stored.isNotEmpty())
            for (row in stored) {
                assertTrue(row[0]!!.toDouble().isFinite() && row[0]!!.toDouble() > 0.0, "$row")
                assertTrue(row[1]!!.toDouble().isFinite() && row[1]!!.toDouble() >= 0.0, "$row")
            }
        }
    }

    @Test
    fun `a team defense's points-allowed tiers are chances that sum to one`() {
        league("tiers.db", units = true).use { db ->
            run(db)
            val sums = db.query(
                "SELECT player_id, season, week, stage, SUM(mean) FROM player_week_projection " +
                    "WHERE metric_id LIKE 'pa\\_%' ESCAPE '\\' GROUP BY 1, 2, 3, 4",
            )
            assertTrue(sums.size >= 8, "$sums")
            for (row in sums) assertEquals(1.0, row[4]!!.toDouble(), 1e-9, "$row")
            val (mean, variance) = db.query(
                "SELECT mean, variance FROM player_week_projection WHERE player_id = 'DST_AAA' AND season = 2025 AND week = 3 AND stage = 'final' AND metric_id = 'pa_14_20'",
            ).single().map { it!!.toDouble() }
            assertEquals(mean * (1 - mean), variance, 1e-12)
        }
    }

    @Test
    fun `rest of season for kickers and defenses sums the remaining games and skips byes`() {
        league("unit-ros.db", units = true).use { db ->
            run(db)
            // BBB is on bye in week 4: its rest of season is week 3 alone.
            assertEquals(finalMean(db, "DST_BBB", 3, "dst_sacks"), rosMean(db, "DST_BBB", "dst_sacks"), 1e-9)
            assertEquals(finalMean(db, "K_B", 3, "xp_made"), rosMean(db, "K_B", "xp_made"), 1e-9)
            // AAA plays weeks 3 and 4.
            assertTrue(rosMean(db, "DST_AAA", "dst_sacks") > 1.5 * finalMean(db, "DST_AAA", 3, "dst_sacks"))
            assertTrue(rosMean(db, "DST_AAA", "pa_14_20") > finalMean(db, "DST_AAA", 3, "pa_14_20"))
        }
    }

    @Test
    fun `a later week's stats never change an earlier kicker's or defense's projection`() {
        val before = league("u1.db", units = true).use { db -> run(db); projections(db) }
        val after = league("u2.db", units = true, dstA2025Week2Sacks = 9.0).use { db -> run(db); projections(db) }
        val throughWeek2 = { row: List<String?> -> order(row[1]!!.toInt(), row[2]!!.toInt()) <= order(2025, 2) }

        assertEquals(before.filter(throughWeek2), after.filter(throughWeek2))
        val dstAWeek3 = { row: List<String?> -> row[0] == "DST_AAA" && row[1] == "2025" && row[2] == "3" }
        assertNotEquals(before.filter(dstAWeek3), after.filter(dstAWeek3))
    }

    @Test
    fun `each team has one kicker, never one kicker for two teams, and a released kicker isn't his old team's`() {
        league("kickers.db", units = true).use { db ->
            // AAA signed K_A2 for 2025; nflverse now lists K_A on BBB, where he hasn't kicked.
            db.player("K_A2", "K", "AAA")
            db.exec("UPDATE player SET team = 'BBB' WHERE player_id = 'K_A'")
            db.exec("UPDATE player_week_stat SET player_id = 'K_A2' WHERE player_id = 'K_A' AND season = 2025")
            run(db)

            // Upcoming: each team's listed kicker with the latest kick. BBB keeps K_B, who kicked for it last week.
            assertEquals(listOf("K_A2", "K_B", "K_C", "K_D"), kickers(db, 2025, 3))
            // Week 2: whoever kicked. Week 1: K_A2 has no kick before it, so AAA's is its last kicker, K_A.
            assertEquals(listOf("K_A2", "K_B", "K_C", "K_D"), kickers(db, 2025, 2))
            assertEquals(listOf("K_A", "K_B", "K_C", "K_D"), kickers(db, 2025, 1))
        }
    }
```

In `KindsTest.kt`, add:

```kotlin
    @Test
    fun `reference points score kickers and defenses with the scoring defaults`() {
        assertEquals(3.0 + 4.0 + 5.0 - 1.0 + 2.0 - 1.0, referencePoints(mapOf("fg_made_0_39" to 1.0, "fg_made_40_49" to 1.0, "fg_made_50" to 1.0, "fg_missed" to 1.0, "xp_made" to 2.0, "xp_missed" to 1.0)), 1e-12)
        assertEquals(2.0 + 2.0 + 2.0 + 6.0 + 2.0 + 0.5 * 10.0 - 0.5 * 4.0, referencePoints(mapOf("dst_sacks" to 2.0, "dst_interceptions" to 1.0, "dst_fumble_recoveries" to 1.0, "dst_tds" to 1.0, "dst_safeties" to 1.0, "pa_0" to 0.5, "pa_35" to 0.5)), 1e-12)
    }
```

In `ProjectionsContractTest.kt` (runs on the rebuilt real database in Step 6), add:

```kotlin
    @Test
    fun `every team playing has one D-ST and at most one kicker, scoring sensibly`() = runTest {
        JdbcQueryExecutor(StatsDb.path!!).use { executor ->
            val repo = ProjectionsRepository(executor)
            val (season, week) = projectedWeek(repo, executor)
            val listed = repo.weekAll(season, week)
            val playing = executor.query(
                SqlQuery(
                    "SELECT home_team, away_team FROM game WHERE season = ? AND week = ? AND game_type = 'REG'",
                    listOf(Bind.Integer(season.toLong()), Bind.Integer(week.toLong())),
                ),
            ) { listOf(it.text(0), it.text(1)) }.flatten().toSet()
            val defenses = listed.filter { it.position == "DST" }
            val kickers = listed.filter { it.position == "K" }

            assertEquals(playing, defenses.map { it.team }.toSet())
            assertEquals(playing.size, defenses.size)
            assertTrue(kickers.size in playing.size - 2..playing.size, "${kickers.size} kickers for ${playing.size} teams")
            for (d in defenses) {
                assertEquals(1.0, d.components.filter { it.metricId.startsWith("pa_") }.sumOf { it.mean }, 1e-6, d.name)
            }
            fun top(position: String) = listed.filter { it.position == position }
                .map { p -> score(p.components.associate { Component(it.metricId) to it.mean }, ScoringPresets.PPR, Position.fromCode(position)) }
                .sortedDescending().take(12).average()
            // Loose bands under the default kicking and D/ST scoring: a broken model lands far outside them.
            assertTrue(top("K") in 6.0..12.0, "K1-12 average ${top("K")}")
            assertTrue(top("DST") in 4.0..14.0, "DST1-12 average ${top("DST")}")
        }
    }
```

(imports: `dev.gridiron.core.projections.score`, `dev.gridiron.core.statquery.Bind`, `dev.gridiron.core.statquery.Component`).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:forecast:test`
Expected: FAIL: the four unit tests find no `K_`/`DST_` rows (`expected: <[baseline, final]> but was: <[]>`), and `KindsTest`'s new test gets 0.

- [ ] **Step 3: Constants, reference points and the shared league average**

In `ForecastConstants.kt`, set `FORECAST_VERSION` to 4 and extend `EMPIRICAL_CV`:

```kotlin
    // Layer 7, spread: sigma = a * mu^0.75, CV at mu = 10 by position (projections.py EMPIRICAL_CV; K and DST from spec §6).
    const val VARIANCE_EXPONENT = 0.75
    val EMPIRICAL_CV: Map<String, Double> = mapOf("QB" to 0.40, "RB" to 0.57, "WR" to 0.70, "TE" to 0.77, "K" to 0.52, "DST" to 0.85)
```

In `Kinds.kt`, extend `referencePoints`'s KDoc with "Kickers and D/STs use the scoring defaults (each rule's fallback)." and its expression:

```kotlin
    return 0.04 * v("passing_yards") + 4 * v("passing_tds") - 2 * v("interceptions") +
        0.1 * v("rushing_yards") + 6 * v("rushing_tds") +
        v("receptions") + 0.1 * v("receiving_yards") + 6 * v("receiving_tds") +
        2 * (v("passing_2pt") + v("rushing_2pt") + v("receiving_2pt")) - 2 * v("fumbles_lost") +
        3 * v("fg_made_0_39") + 4 * v("fg_made_40_49") + 5 * v("fg_made_50") - v("fg_missed") + v("xp_made") - v("xp_missed") +
        v("dst_sacks") + 2 * (v("dst_interceptions") + v("dst_fumble_recoveries") + v("dst_safeties")) + 6 * v("dst_tds") +
        10 * v("pa_0") + 7 * v("pa_1_6") + 4 * v("pa_7_13") + v("pa_14_20") - v("pa_28_34") - 4 * v("pa_35")
```

In `GameScript.kt`, add:

```kotlin
/** A team's average implied points in [season]: half its average posted total, or [K.LEAGUE_IMPLIED_DEFAULT] before any. */
internal fun averageImplied(games: List<Game>, season: Int): Double {
    val totals = games.filter { it.season == season && it.regular }.mapNotNull { it.total }
    return if (totals.isEmpty()) K.LEAGUE_IMPLIED_DEFAULT else totals.average() / 2
}
```

and in `Projector.WeekState`'s `init`, replace the two lines computing `totalsPosted` and `leagueImplied` with `leagueImplied = averageImplied(inputs.games, season)`.

- [ ] **Step 4: The unit projector**

`core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Units.kt`:

```kotlin
package dev.gridiron.core.forecast

import dev.gridiron.core.statquery.PA_TIERS
import java.util.Locale
import kotlin.math.ln

private val TIER_IDS: Set<String> = PA_TIERS.mapTo(HashSet()) { it.component.id }

/** A kicker or D/ST placed on a team as of one week: what each of its games' projections starts from. */
private sealed interface TeamUnit {
    val player: PlayerInfo
    val team: String
}

private class Kicker(override val player: PlayerInfo, override val team: String, val rates: KickerRates, val usualPoints: Double) : TeamUnit

private class Defense(
    override val player: PlayerInfo,
    override val team: String,
    val own: Map<String, Double>,
    val ownAllowed: Double,
) : TeamUnit

/** One unit's projection for one game, stage by stage, with the waterfall's notes (null: no such factor). */
private class UnitStages(
    val baseline: Map<String, Double>,
    val afterMatchup: Map<String, Double>,
    val final: Map<String, Double>,
    val matchupNote: String?,
    val scriptNote: String?,
)

/**
 * Kickers and team defenses (spec §6), week by week beside the [Projector]'s
 * players and, like them, only from the games before each week. Past weeks
 * keep their final projection; the upcoming week keeps both stages and the
 * factors; every game from the upcoming week on is summed into rest of season.
 */
internal class UnitProjector(
    private val inputs: ForecastInputs,
    private val gameOf: Map<Triple<String, Int, Int>, Game>,
    private val sink: ProjectionSink,
) {
    private val chronological: List<PlayerGame> = inputs.unitHistory.values.flatten().sortedBy { it.order }
    private val kickers: List<PlayerInfo> = inputs.units.values.filter { it.position == "K" }.sortedBy { it.playerId }
    private var upcoming: Pair<UnitWeek, List<TeamUnit>>? = null

    /** Projects one week's units; for the upcoming week, also starts rest of season. */
    fun week(season: Int, week: Int, kind: WeekKind, ros: MutableMap<Pair<String, String>, DoubleArray>) {
        val state = UnitWeek(season, week)
        val units = prepare(state, kind)
        for (u in units) {
            val game = gameOf[Triple(u.team, season, week)] ?: continue // an upcoming bye: rest of season only
            val stages = stages(state, u, game)
            val id = u.player.playerId
            when (kind) {
                WeekKind.PAST -> if (referencePoints(stages.final) >= K.PAST_WEEK_MIN_POINTS) emit(u, season, week, "final", stages.final)
                WeekKind.UPCOMING -> if (referencePoints(stages.final) >= K.UPCOMING_MIN_POINTS) {
                    emit(u, season, week, "baseline", stages.baseline)
                    emit(u, season, week, "final", stages.final)
                    stages.matchupNote?.let { sink.factor(id, season, week, "matchup", logRatio(stages.afterMatchup, stages.baseline), it) }
                    stages.scriptNote?.let { sink.factor(id, season, week, "game_script", logRatio(stages.final, stages.afterMatchup), it) }
                }
                WeekKind.REST -> Unit
            }
        }
        if (kind == WeekKind.UPCOMING) {
            upcoming = state to units
            for (u in units) addRest(state, u, season, week, ros)
        }
    }

    /** Adds a week after the upcoming one to rest of season, from what was known as of the upcoming week. */
    fun rest(season: Int, week: Int, ros: MutableMap<Pair<String, String>, DoubleArray>) {
        val (state, units) = upcoming ?: return
        for (u in units) addRest(state, u, season, week, ros)
    }

    /** What every unit's projection for one week shares, from the games before it. */
    private inner class UnitWeek(val season: Int, val week: Int) {
        val order = order(season, week)
        private val before = chronological.takeWhile { it.order < order }
        private val kicks = before.filter { position(it) == "K" }
        private val defenses = before.filter { position(it) == "DST" }
        val kickLeague: KickLeague? = kickLeague(kicks)
        val attempts: AttemptFit? = AttemptFit.fit(teamKicks(kicks))
        val defenseLeague: DefenseLeague? = defenseLeague(defenses)
        val sd: Double = paSpread(
            defenses.mapNotNull { g -> opponentOf(g)?.let { (opponent, game) -> game.impliedPoints(opponent)?.let { g["points_allowed"] - it } } },
        )
        val leagueImplied: Double = averageImplied(inputs.games, season)

        /** Per offense, oldest first, this season and last: the points it scored, and each D/ST stat defenses got against it. */
        val scored = HashMap<String, MutableList<Double>>()
        val allowed = HashMap<String, HashMap<String, MutableList<Double>>>()

        init {
            for (g in defenses) {
                if (g.season < season - 1) continue
                val (offense, _) = opponentOf(g) ?: continue
                scored.getOrPut(offense) { ArrayList() } += g["points_allowed"]
                val byStat = allowed.getOrPut(offense) { HashMap() }
                for (stat in DST_STATS) byStat.getOrPut(stat) { ArrayList() } += g[stat]
            }
        }
    }

    private fun position(g: PlayerGame): String? = inputs.units[g.playerId]?.position

    /** The team [g]'s unit played against, and the game. */
    private fun opponentOf(g: PlayerGame): Pair<String, Game>? = gameOf[Triple(g.team, g.season, g.week)]?.let { it.opponentOf(g.team) to it }

    /** Each team-game's kicks, with its implied points where a line was posted. */
    private fun teamKicks(kicks: List<PlayerGame>): List<TeamKicks> =
        kicks.groupBy { Triple(it.team, it.season, it.week) }.map { (key, games) ->
            TeamKicks(
                gameOf[key]?.impliedPoints(key.first),
                games.sumOf { g -> FG_BUCKETS.sumOf { g["fg_att_$it"] } },
                games.sumOf { it["xp_att"] },
            )
        }

    /** This week's kicker and D/ST for every team in the season's schedule (past weeks: only teams that played). */
    private fun prepare(state: UnitWeek, kind: WeekKind): List<TeamUnit> {
        val scheduled = inputs.games.filter { it.season == state.season }.flatMap { listOf(it.home, it.away) }.toSortedSet()
        val teams = if (kind == WeekKind.PAST) scheduled.filter { gameOf[Triple(it, state.season, state.week)] != null } else scheduled.toList()
        val units = ArrayList<TeamUnit>()
        val kickLeague = state.kickLeague
        if (kickLeague != null && state.attempts != null) {
            for ((team, k) in kickersFor(teams, state, kind)) units += kicker(state, k, team, kickLeague)
        }
        state.defenseLeague?.let { league -> for (team in teams) defense(state, team, league)?.let(units::add) }
        return units
    }

    /**
     * Each team's kicker this week, never one kicker for two teams. In a past
     * week, the kicker who kicked for the team that week; from the upcoming
     * week on, the kicker nflverse lists on the team. Otherwise, the one who
     * kicked for the team most recently, unless nflverse lists him elsewhere
     * now. Only kickers with a kick this season or last, before this week, count.
     */
    private fun kickersFor(teams: List<String>, state: UnitWeek, kind: WeekKind): Map<String, PlayerInfo> {
        val last = kickers.mapNotNull { k ->
            inputs.unitHistory[k.playerId].orEmpty().lastOrNull { it.order < state.order }
                ?.takeIf { it.season >= state.season - 1 }
                ?.let { k to it }
        }
        val picked = LinkedHashMap<String, PlayerInfo>()
        for (team in teams) {
            val first = if (kind == WeekKind.PAST) {
                last.firstOrNull { (k, _) ->
                    inputs.unitHistory[k.playerId].orEmpty().any { it.season == state.season && it.week == state.week && it.team == team }
                }?.first
            } else {
                last.filter { (k, _) -> k.team == team }.maxByOrNull { it.second.order }?.first
            }
            first?.let { picked[team] = it }
        }
        for (team in teams) {
            if (team in picked) continue
            last.filter { (k, g) -> g.team == team && k !in picked.values && (kind == WeekKind.PAST || k.team == null || k.team == team) }
                .maxByOrNull { it.second.order }
                ?.let { picked[team] = it.first }
        }
        return picked
    }

    private fun recentGames(player: PlayerInfo, state: UnitWeek): List<PlayerGame> =
        inputs.unitHistory[player.playerId].orEmpty().filter { it.order < state.order && it.season >= state.season - 1 }

    private fun kicker(state: UnitWeek, player: PlayerInfo, team: String, league: KickLeague): Kicker {
        val usual = teamPoints(state.scored[team].orEmpty(), state.defenseLeague?.pointsAllowed ?: state.leagueImplied)
        return Kicker(player, team, kickerRates(recentGames(player, state), league), usual)
    }

    private fun defense(state: UnitWeek, team: String, league: DefenseLeague): Defense? {
        val player = inputs.units["DST_$team"] ?: return null
        val own = recentGames(player, state)
        if (own.isEmpty()) return null
        return Defense(
            player, team,
            DST_STATS.associateWith { stat -> unitRate(own.map { it[stat] }, league.perGame.getValue(stat), K.DST_K.getValue(stat)) },
            unitRate(own.map { it["points_allowed"] }, league.pointsAllowed, K.DST_PA_K),
        )
    }

    private fun stages(state: UnitWeek, u: TeamUnit, game: Game): UnitStages = when (u) {
        is Kicker -> {
            val fit = checkNotNull(state.attempts)
            val baseline = kickStats(u.rates, fit.fieldGoals(u.usualPoints), fit.extraPoints(u.usualPoints))
            val implied = game.impliedPoints(u.team)
            val final = implied?.let { kickStats(u.rates, fit.fieldGoals(it), fit.extraPoints(it)) } ?: baseline
            UnitStages(baseline, baseline, final, matchupNote = null, scriptNote = implied?.let { impliedNote("Implied", it, state.leagueImplied) })
        }
        is Defense -> {
            val league = checkNotNull(state.defenseLeague)
            val opponent = game.opponentOf(u.team)
            val against = state.allowed[opponent].orEmpty()
            val factors = DST_STATS.associateWith { stat -> opponentFactor(against[stat].orEmpty(), league.perGame.getValue(stat)) }
            val opponentScores = unitRate(state.scored[opponent].orEmpty(), league.pointsAllowed, K.DST_PA_K)
            val implied = game.impliedPoints(opponent)
            val s = defenseStages(u.own, u.ownAllowed, factors, opponentScores, league, implied, state.sd)
            UnitStages(
                s.baseline, s.afterMatchup, s.final,
                matchupNote = defenseNote(opponent, against, league),
                scriptNote = implied?.let { impliedNote("$opponent implied", it, state.leagueImplied) },
            )
        }
    }

    private fun emit(u: TeamUnit, season: Int, week: Int, stage: String, components: Map<String, Double>) {
        val cv = K.EMPIRICAL_CV.getValue(u.player.position)
        for ((metric, mean) in components) {
            check(mean.isFinite()) { "${u.player.playerId}'s $season week $week $metric projection is $mean" }
            if (mean > 0.0) sink.projection(u.player.playerId, season, week, metric, stage, mean, variance(metric, mean, cv))
        }
    }

    private fun addRest(state: UnitWeek, u: TeamUnit, season: Int, week: Int, ros: MutableMap<Pair<String, String>, DoubleArray>) {
        val game = gameOf[Triple(u.team, season, week)] ?: return // a bye
        val final = stages(state, u, game).final
        if (referencePoints(final) < K.UPCOMING_MIN_POINTS) return
        val cv = K.EMPIRICAL_CV.getValue(u.player.position)
        for ((metric, mean) in final) {
            if (mean <= 0.0) continue
            val sum = ros.getOrPut(u.player.playerId to metric) { DoubleArray(2) }
            sum[0] += mean
            sum[1] += variance(metric, mean, cv)
        }
    }

    /** A tier is 1 or 0, so its variance is p(1 − p); everything else follows layer 7. */
    private fun variance(metric: String, mean: Double, cv: Double): Double =
        if (metric in TIER_IDS) mean * (1 - mean) else varianceFor(mean, cv)

    private fun logRatio(after: Map<String, Double>, before: Map<String, Double>): Double {
        val a = referencePoints(after)
        val b = referencePoints(before)
        return if (a > 0.0 && b > 0.0) ln(a / b) else 0.0
    }
}

/** "Implied 27.5 pts (+4.3)", or "KC implied 27.5 pts (+4.3)" for a D/ST's opponent. */
private fun impliedNote(label: String, implied: Double, league: Double): String =
    String.format(Locale.US, "%s %.1f pts (%+.1f)", label, implied, implied - league)

/** "vs KC: gives up 2.9 sacks, 1.6 turnovers a game", shrunk like the matchup's factors. */
private fun defenseNote(opponent: String, against: Map<String, List<Double>>, league: DefenseLeague): String {
    fun rate(stat: String) = unitRate(against[stat].orEmpty(), league.perGame.getValue(stat), K.DST_OPP_K)
    return String.format(
        Locale.US, "vs %s: gives up %.1f sacks, %.1f turnovers a game",
        opponent, rate("dst_sacks"), rate("dst_interceptions") + rate("dst_fumble_recoveries"),
    )
}
```

- [ ] **Step 5: Run the units in the players' loop**

In `Projector.kt`, replace `private enum class WeekKind { PAST, UPCOMING, REST }` with:

```kotlin
/** Where a week sits relative to the upcoming one: projected and kept, projected with factors, or rest of season only. */
internal enum class WeekKind { PAST, UPCOMING, REST }
```

Add after the `gameOf` property:

```kotlin
    private val units = UnitProjector(inputs, gameOf, sink)
```

In `run()`, in the `REST` branch, add `units.rest(season, week, ros)` after `for (p in prepared) addRest(state, p, season, week, ros)`; and after `val prepared = prepareWeek(state, kind)` add:

```kotlin
            units.week(season, week, kind, ros)
```

Extend the class KDoc's first sentence: "…each projected only from the games before it. Kickers and D/STs are projected alongside by [UnitProjector]."

- [ ] **Step 6: Run the tests, then on real data**

Run: `./gradlew :core:forecast:test`
Expected: PASS: the four new engine tests, `KindsTest`, and every existing engine test unchanged (their leagues have no units).

Then rebuild the test database and run the real-data projection tests:

Run: `./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025 2026" -Pout=etl/build/stats.db && GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test --tests "dev.gridiron.core.data.ProjectionsContractTest"`
Expected: PASS, all three tests. Note the K and DST top-12 averages from a quick `println` if a band fails: a band miss is a model bug (units, a missing factor, tiers not summing) to find before going on, not a band to widen.

Also run the timing test, which now includes units: `./gradlew :core:forecast:test --tests "dev.gridiron.core.forecast.ForecastTimingTest"`. Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add core/forecast core/data
git commit -m "forecast: project kickers and D/STs walk-forward, with factors and rest of season"
```

### Task 8: The phone: one tier per draw, K and D/ST tabs, accuracy rows

**Files:**
- Modify: `core/projections/src/main/kotlin/dev/gridiron/core/projections/MonteCarlo.kt`, `ProjectedPoints.kt`, `Backtest.kt`
- Modify: `core/data/src/main/kotlin/dev/gridiron/core/data/AccuracyRepository.kt`
- Modify: `feature/projections/src/main/kotlin/dev/gridiron/feature/projections/ProjectionListViewModel.kt`
- Test: `core/projections/src/test/kotlin/dev/gridiron/core/projections/MonteCarloTest.kt`, `ProjectedPointsTest.kt`, `BacktestTest.kt`; `core/data/src/test/kotlin/dev/gridiron/core/data/AccuracyGateTest.kt`; `feature/projections/src/test/kotlin/dev/gridiron/feature/projections/ProjectionListTest.kt`, `ProjectionCardTest.kt`

**Interfaces:**
- Consumes: Task 2's `Position.DST` and tier components; Task 4's rules; Task 7's stored rows (`pa_*` with `dist_family` `categorical`).
- Produces:
  - `DistributionFamily.CATEGORICAL`; `familyOf("categorical")`. `simulate` draws every categorical spec together: exactly one is 1 per draw, chosen by their means.
  - `calibratedRange(points, p10, p90, position, widening: Map<Position, Double> = RANGE_WIDENING)`, `projectPoints(components, profile, position, draws, widening = RANGE_WIDENING)`, `backtest(season, projected, played, profile, draws, widening = RANGE_WIDENING)`, `AccuracyRepository.backtest(season, profile, draws, widening = RANGE_WIDENING)`.
  - `ACCURACY_POSITIONS = listOf("QB", "RB", "WR", "TE", "K", "DST")`; `GATE_POSITIONS = listOf("QB", "RB", "WR", "TE")`.
  - `PositionTab.K` ("K") and `PositionTab.DST` ("D/ST"), after `FLEX`.

- [ ] **Step 1: Write the failing tests**

In `MonteCarloTest.kt`, add (import `org.junit.jupiter.api.Assertions.assertEquals`):

```kotlin
    @Test
    fun `categorical components are one draw, so exactly one tier scores each time`() {
        val tiers = listOf(
            DistributionSpec(Components.PA_0, DistributionFamily.CATEGORICAL, mean = 0.5, variance = 0.25),
            DistributionSpec(Components.PA_35, DistributionFamily.CATEGORICAL, mean = 0.5, variance = 0.25),
        )
        val result = simulate(tiers, ScoringPresets.PPR, Position.DST, draws = 2_000)
        // Drawn apart they'd sometimes both score (+6) or neither (0); drawn as one, every game is +10 or -4.
        assertEquals(-4.0, result.p10, 1e-9)
        assertEquals(10.0, result.p90, 1e-9)
        assertTrue(result.p50 == -4.0 || result.p50 == 10.0, "p50 was ${result.p50}")
    }
```

In `ProjectedPointsTest.kt`, add:

```kotlin
    @Test
    fun `points-allowed tiers simulate as categorical`() {
        assertEquals(DistributionFamily.CATEGORICAL, familyOf("categorical"))
    }

    @Test
    fun `a caller can try other widening factors`() {
        assertEquals(2.0 to 20.0, calibratedRange(10.0, 6.0, 15.0, Position.K, widening = mapOf(Position.K to 2.0)))
    }
```

In `BacktestTest.kt`, add:

```kotlin
    @Test
    fun `kickers and team defenses are measured like everyone else`() {
        fun kick(made: Double) = mapOf(Component("fg_made_0_39") to made, Component("xp_made") to 3.0)
        val played = listOf(
            PlayedWeek("k", 2025, 1, kick(1.0)), // 3 + 3
            PlayedWeek("k", 2025, 2, kick(2.0)), // 6 + 3
            PlayedWeek("d", 2025, 1, mapOf(Component("dst_sacks") to 3.0, Component("pa_14_20") to 1.0)), // 3 + 1
            PlayedWeek("d", 2025, 2, mapOf(Component("dst_sacks") to 5.0, Component("pa_7_13") to 1.0)), // 5 + 4
        )
        val projected = listOf(
            ProjectedWeek("k", "K", 2, listOf(ProjectionComponent("fg_made_0_39", 1.0, 0.0), ProjectionComponent("xp_made", 3.0, 0.0))),
            ProjectedWeek(
                "d", "DST", 2,
                listOf(
                    ProjectionComponent("dst_sacks", 3.0, 0.0),
                    ProjectionComponent("pa_14_20", 0.5, 0.25, "categorical"),
                    ProjectionComponent("pa_7_13", 0.5, 0.25, "categorical"),
                ),
            ),
        )

        val results = backtest(2025, projected, played, ScoringPresets.PPR)

        assertEquals(listOf("K", "DST"), results.map { it.position })
        assertEquals(3.0, results[0].model.mae, 1e-9) // projected 6, scored 9
        assertEquals(3.5, results[1].model.mae, 1e-9) // projected 3 + 0.5 + 2, scored 9
    }
```

In `ProjectionListTest.kt`, add:

```kotlin
    @Test
    fun `kickers and team defenses have their own tabs, outside FLEX`() {
        val all = rows + row("k", "K", 8.0) + row("d", "DST", 7.0)
        assertEquals(listOf("k"), visibleRows(all, PositionTab.K, emptyMap(), week = true).map { it.playerId })
        assertEquals(listOf("d"), visibleRows(all, PositionTab.DST, emptyMap(), week = true).map { it.playerId })
        assertEquals(listOf("w", "r", "t"), visibleRows(all, PositionTab.FLEX, emptyMap(), week = true).map { it.playerId })
        assertEquals("D/ST", PositionTab.DST.label)
    }
```

In `ProjectionCardTest.kt`, give `CardExecutor` a `defense: Boolean = false` parameter, and put these two branches in its `when` right after the `player_week_projection_factor` branch (before the `bye` ones):

```kotlin
            "FROM player_week_projection" in sql && defense -> listOf(
                listOf("DST_KC", "dst_sacks", "final", 3.0, 3.0, "negbinom"),
                listOf("DST_KC", "pa_7_13", "final", 0.5, 0.25, "categorical"),
                listOf("DST_KC", "pa_21_27", "final", 0.5, 0.25, "categorical"),
            )
            "FROM player_ros_projection" in sql && defense -> listOf(
                listOf("DST_KC", "dst_sacks", 9.0, 9.0, "negbinom"),
                listOf("DST_KC", "pa_7_13", 1.5, 0.75, "categorical"),
                listOf("DST_KC", "pa_21_27", 1.5, 0.75, "categorical"),
            )
```

and add:

```kotlin
    @Test
    fun `a team defense's card scores its points-allowed tiers`() = runTest {
        val card = loadProjectionCard(ProjectionsRepository(CardExecutor(defense = true)), "DST_KC", "KC", ScoringPresets.PPR, Position.DST, null)!!

        // 3 sacks, and a coin flip between 7-13 allowed (4) and 21-27 allowed (0).
        assertEquals(5.0, card.points, 1e-9)
        assertTrue(card.floor < 5.0 && card.ceiling > 5.0)
        assertEquals(15.0, card.rosPoints!!, 1e-9)
        assertEquals(5.0, card.rosPerGame!!, 1e-9)
    }
```

In `AccuracyGateTest.kt`, import `dev.gridiron.core.projections.GATE_POSITIONS`, change the KDoc's "at QB, RB, WR and TE" to "at each of [GATE_POSITIONS]; K and D/ST are printed but not gated", and filter the losing positions: `val losing = results.filter { it.position in GATE_POSITIONS && it.model.mae >= it.seasonAverage.mae }.map { it.position }`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:projections:test :feature:projections:testDebugUnitTest`
Expected: FAIL to compile with "Unresolved reference: CATEGORICAL", "No parameter with name 'widening'" and "Unresolved reference: K" (the tab).

- [ ] **Step 3: Draw the tiers together**

In `MonteCarlo.kt`:

```kotlin
public enum class DistributionFamily {
    NEGBINOM,
    BINOMIAL,
    GAMMA,
    POISSON,

    /**
     * One of a set of outcomes, like a D/ST's points-allowed tier: every
     * categorical spec in a simulation is drawn together, exactly one of them
     * is 1, and their means are the chances.
     */
    CATEGORICAL,
}
```

In `simulate`, replace the draw loop:

```kotlin
    val (categories, independent) = distributions.partition { it.family == DistributionFamily.CATEGORICAL }
    val chances = categories.sumOf { it.mean.coerceAtLeast(0.0) }

    for (i in 0 until draws) {
        for (spec in independent) {
            componentMap[spec.component] = drawOne(spec, rng)
        }
        if (categories.isNotEmpty()) drawCategory(categories, chances, rng, componentMap)
        samples[i] = score(componentMap, profile, position)
    }
```

In `drawOne`'s `when`, add `DistributionFamily.CATEGORICAL -> spec.mean // simulate() draws these together`, and add:

```kotlin
/** Sets exactly one of [categories] to 1, chosen with their means as chances out of [total], and the rest to 0. */
private fun drawCategory(categories: List<DistributionSpec>, total: Double, rng: SplittableRandom, into: MutableMap<Component, Double>) {
    var u = rng.nextDouble() * total
    var chosen = categories.lastIndex
    for ((i, c) in categories.withIndex()) {
        u -= c.mean.coerceAtLeast(0.0)
        if (u < 0.0) {
            chosen = i
            break
        }
    }
    categories.forEachIndexed { i, c -> into[c.component] = if (total > 0.0 && i == chosen) 1.0 else 0.0 }
}
```

- [ ] **Step 4: A widening parameter, and K and D/ST in the backtest**

In `ProjectedPoints.kt`, `familyOf` gains `"categorical" -> DistributionFamily.CATEGORICAL`. `calibratedRange` gains `widening: Map<Position, Double> = RANGE_WIDENING` as its last parameter and reads `val k = position?.let { widening[it] } ?: 1.0`. `projectPoints` gains `widening: Map<Position, Double> = RANGE_WIDENING` after `draws` and passes it: `calibratedRange(points, simulated.p10, simulated.p90, position, widening)`. Add to `calibratedRange`'s KDoc: "[widening] is for fitting the factors; everyone else uses [RANGE_WIDENING]."

In `Backtest.kt`:

```kotlin
/** The positions the backtest measures, in the page's order. */
public val ACCURACY_POSITIONS: List<String> = listOf("QB", "RB", "WR", "TE", "K", "DST")

/** The positions CI's accuracy gate holds to beating the season-to-date average (spec §4). */
public val GATE_POSITIONS: List<String> = listOf("QB", "RB", "WR", "TE")
```

`backtest` gains `widening: Map<Position, Double> = RANGE_WIDENING` after `draws` (documented as for fitting) and calls `projectPoints(p.components, profile, position, draws, widening)`.

In `AccuracyRepository.backtest`, add the same parameter after `draws` and pass it: `return backtest(season, projected, played, profile, draws, widening)` (import `dev.gridiron.core.model.Position` and `dev.gridiron.core.projections.RANGE_WIDENING`).

In `ProjectionListViewModel.kt`, `PositionTab` gains, after `FLEX(...)`:

```kotlin
    K("K", setOf("K")),
    DST("D/ST", setOf("DST")),
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:projections:test :core:data:test :feature:projections:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS. If a screen test enumerates the position chips or the accuracy page's positions by count (`ProjectionListScreenTest`, `AccuracyScreenTest`, `AccuracyViewModelTest`), update its expectation to the two new tabs or rows: that's the intended change, and nothing else about those screens changes.

Then on real data (the database rebuilt in Task 7):

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test`
Expected: PASS, `AccuracyContractTest` included (it now expects K and D/ST rows).

- [ ] **Step 6: Commit**

```bash
git add core/projections core/data feature/projections
git commit -m "projections: points-allowed tiers as one draw; K and D/ST tabs and accuracy rows"
```

### Task 9: Calibrate K and D/ST ranges on real data; docs

**Files:**
- Modify: `core/projections/src/main/kotlin/dev/gridiron/core/projections/ProjectedPoints.kt` (`RANGE_WIDENING`)
- Modify: `core/projections/src/test/kotlin/dev/gridiron/core/projections/ProjectedPointsTest.kt`
- Temporary (not committed): `core/data/src/test/kotlin/dev/gridiron/core/data/RangeFitTest.kt`
- Modify: `CLAUDE.md`, `docs/superpowers/specs/2026-09-26-projection-model-design.md`, `docs/superpowers/HANDOFF.md`

**Interfaces:**
- Consumes: Task 8's `widening` parameter; `AccuracyRepository`; `JdbcQueryExecutor`, `StatsDb`.
- Produces: `RANGE_WIDENING` with `Position.K` and `Position.DST` factors (or without either one, if the simulation alone already holds 80% of its games).

- [ ] **Step 1: Rebuild the accuracy database (2024–2025)**

Run: `./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025" -Pout=etl/build/accuracy.db`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Fit each factor**

Create `core/data/src/test/kotlin/dev/gridiron/core/data/RangeFitTest.kt` (a measuring tool; it is deleted in Step 4):

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.projections.RANGE_WIDENING
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.Locale
import kotlin.time.Duration.Companion.minutes

/** The smallest factor (to 0.01) at which K's and D/ST's pooled 2024-2025 ranges hold 80% of games, as for QB-TE. */
@EnabledIfEnvironmentVariable(named = "GRIDIRON_STATS_DB", matches = ".+")
class RangeFitTest {
    @Test
    fun `fit K and DST widening`() = runTest(timeout = 30.minutes) {
        JdbcQueryExecutor(StatsDb.path!!).use { executor ->
            val repo = AccuracyRepository(executor)
            for (position in listOf(Position.K, Position.DST)) {
                suspend fun held(k: Double): Double {
                    val rows = listOf(2024, 2025).map { season ->
                        repo.backtest(season, ScoringPresets.PPR, widening = RANGE_WIDENING + (position to k)).single { it.position == position.code }
                    }
                    return rows.sumOf { it.calibration * it.playerWeeks } / rows.sumOf { it.playerWeeks }
                }
                val alone = held(1.0)
                if (alone >= 0.80) {
                    println(String.format(Locale.US, "%s: holds %.3f with no widening", position.code, alone))
                    continue
                }
                var lo = 1.0
                var hi = 3.0
                while (hi - lo > 0.005) {
                    val mid = (lo + hi) / 2
                    if (held(mid) >= 0.80) hi = mid else lo = mid
                }
                val factor = Math.ceil(hi * 100) / 100
                println(String.format(Locale.US, "%s: widen %.2f, holds %.3f (%.3f alone)", position.code, factor, held(factor), alone))
            }
        }
    }
}
```

Run: `GRIDIRON_STATS_DB=etl/build/accuracy.db ./gradlew :core:data:test --tests "dev.gridiron.core.data.RangeFitTest" -i | grep -E "^(K|DST):"`
Expected: two lines, e.g. `K: widen 1.34, holds 0.801 (0.702 alone)`. If it prints `hi` at 3.00, the range is badly off: stop and report the numbers instead of committing a factor of 3.

- [ ] **Step 3: Record the factors**

In `ProjectedPoints.kt`, add the fitted factors to `RANGE_WIDENING` (leave a position out if it printed "no widening"):

```kotlin
public val RANGE_WIDENING: Map<Position, Double> = mapOf(
    Position.QB to 1.40, Position.RB to 1.59, Position.WR to 1.52, Position.TE to 1.40,
    Position.K to <K factor>, Position.DST to <DST factor>,
)
```

and extend its KDoc: "K and D/ST were fitted the same way when they arrived (sub-project 4)." In `ProjectedPointsTest.kt`, any test that uses `Position.K` as "a position with no factor" switches to `Position.FB`.

Run: `./gradlew :core:projections:test`
Expected: PASS.

- [ ] **Step 4: The gate's table, with K and D/ST beside the offense**

Delete `RangeFitTest.kt`. Then:

Run: `GRIDIRON_STATS_DB=etl/build/accuracy.db GRIDIRON_ACCURACY_GATE=2025 ./gradlew :core:data:test --tests "dev.gridiron.core.data.AccuracyGateTest" && cat core/data/build/reports/accuracy-gate.txt`
Expected: PASS, with six rows. QB–TE's MAE, bias and held should equal the handoff's (QB 6.42, RB 5.88, WR 5.40, TE 4.87; held 78/78/81/83%): nothing in this sub-project changes an offensive projection. A difference is either a regression or republished nflverse files; tell them apart by building a 2024–2025 database and running the gate from the base commit in a worktree (`git worktree add ../base e188dc2`) before concluding. K's and D/ST's held should read about 80%. Copy the table into the handoff (Step 6). If the model's MAE at K or D/ST isn't below the season-to-date average's, say so in the handoff: it's a finding for the user, not a failure of the gate.

- [ ] **Step 5: Docs**

In `CLAUDE.md`:
- `:core:projections`: "…single-player Monte Carlo (floor/ceiling, widened per position by `RANGE_WIDENING` so the range holds about 80% of games; a D/ST's points-allowed tiers are one categorical draw)…"
- `:core:ingest`: after "Kotlin ports of the ETL's transforms and validation", add "(kicking facts from play-by-play; each team's defense as a `DST_<TEAM>` pseudo-player from `team_week_defense`)".
- `:core:forecast`: "writes weekly, rest-of-season and waterfall-factor projections for QB/RB/WR/TE, K and D/ST" and, after the seven layers, "K and D/ST have their own models (`Kicker.kt`, `Defense.kt`, run by `UnitProjector`): kickers' field goal and extra point tries from implied team points, with their distance mix and accuracy shrunk toward the league's; D/STs' own sacks and takeaways times the opponent's, and points-allowed tier chances from the opponent's implied points".
- `:feature:projections`: "(the upcoming week or rest of season by position, K and D/ST included, scored with the active profile)".
- `:core:data`: note `StatsRepository` keeps kickers and D/STs out of the Grid.
- Schema heading: "Version 8". `player`: "Players with at least one stat in the built seasons, plus a `DST_<TEAM>` pseudo-player per team". `team_week_defense`: "…defensive TDs, safeties, kickoff-return TDs".
- Accuracy gate paragraph: "…at QB, RB, WR and TE (K and D/ST are printed, not gated)…".
- Known gaps: remove "Projection model sub-project 4 (K/DST) is not built yet…" and "K/DST fantasy scoring is out of scope for `:core:projections`'s `score()`…"; add "**The Grid never lists kickers or D/STs** (`StatsRepository`'s `GRID_EXCLUDED`); they live in the Projections list and on the Player page." and "**K and D/ST constants are judgments** (`ForecastConstants`), measured by the accuracy page and the gate's printed table."

In the spec, add after "Amendment: layer 2":

```markdown
## Amendment: sub-project 4 (2026-09-27, while planning K and DST)

- **Points allowed are one-hot tier facts.** Each D/ST week stores `points_allowed` and a 1 in the one tier it lands in (`pa_0` … `pa_35`), so every scoring rule stays linear, `StatQueryBuilder` and `score()` need no special case, and a projection's tier means are the tier chances. The simulation draws the tiers as one categorical draw.
- **Tier chances** come from a normal distribution around the points-allowed projection, rounded to whole points. Its spread is measured walk-forward from real team scores around their implied points (about 10 points before there are 100 lined games).
- **Defaults.** Each new rule has a `fallback`: FG 3/4/5 by distance, FG missed −1, XP made 1, XP missed −1; sack 1, interception 2, fumble recovery 2, TD 6, safety 2; points allowed 10/7/4/1/0/−1/−4. These are the common (Yahoo-style) values on the spec's tiers; ESPN uses different tier edges, which a user can't reproduce with these tiers. A profile saved before this change scores K and D/ST with the fallbacks.
- **D/ST TDs** are the defense's (including punt and blocked-kick returns) plus kickoff returns; `team_week_defense` gains `safeties` and `kick_return_tds`.
- **Accuracy.** The accuracy page measures K and D/ST too. CI's gate stays QB–TE, and prints K and D/ST. `RANGE_WIDENING` gains K <K factor> and DST <DST factor>, fitted like the others on pooled 2024–2025.
- **The Grid** leaves kickers and D/STs out in every view.
```

(fill in the two factors, or write "no factor" for a position that needed none).

- [ ] **Step 6: The whole suite, then commit**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew test && ./gradlew :app:assembleRelease lint`
Expected: BUILD SUCCESSFUL. (If `:core:statquery`'s timing test fails under the full parallel build, rerun `./gradlew :core:statquery:test` alone: the known flake.)

Update `docs/superpowers/HANDOFF.md` with the fitted factors, the gate's table (all six rows) and any finding from Step 4, then:

```bash
git add core/projections CLAUDE.md docs
git commit -m "projections: fit K and D/ST range widening; docs for K and D/ST"
```

---

## Plan self-review

**Spec coverage (§6).**

| Spec line | Task |
|---|---|
| K rules: FG made 0–39, 40–49, 50+; XP made; FG missed; XP missed | 4 |
| DST rules: sack, interception, fumble recovery, TD, safety, 7 points-allowed tiers | 4 |
| Presets get the common defaults; the editor shows the new rules | 4 (`fallback`; the editor lists every `ScoringGroup`, so the two new groups appear with no screen change) |
| `score()` and `StatQueryBuilder` learn them | 4 |
| Kicking metrics from play-by-play | 1 |
| DST facts from `team_week_defense`, `DST_<TEAM>` pseudo-player named "<TEAM> D/ST", position DST | 2 |
| Python parity for the new facts | 3 |
| K model: implied points → tries (linear, walk-forward); mix and make rates shrunk; CV 0.52 | 5, 7 |
| DST model: own EWMA rates × opponent's; points allowed from the opponent's implied points; tier chances; CV 0.85 | 6, 7 |
| K and DST tabs in the Projections list; Player page cards | 8 (tabs; the card is position-generic once `Position.DST` exists, pinned by `ProjectionCardTest`) |
| The Grid is unchanged | 2 |

**Rulings made while planning** (the user can overturn any; each says what it costs if wrong):
1. **Tiers as one-hot facts plus `points_allowed`**, not a threshold rule on points allowed. A threshold rule would score a projection's *mean* points allowed into one tier, which is biased; one-hot facts make the expected tier points exact and keep scoring linear everywhere. Cost: 7 sparse facts per D/ST week (about 250 rows a season).
2. **`ScoringRule.fallback`** instead of rewriting stored profiles: old profiles score K and D/ST with the defaults, and the editor keeps any value that differs from the fallback, zero included. Cost if wrong: none for existing users; a rule's fallback can't change later without changing old profiles' scores.
3. **Default values:** the common Yahoo-style values listed in the spec amendment. ESPN's own points-allowed tiers have different edges (14–17, 18–21, 22–27, 28–34, 35–45, 46+), which these tiers can't express. Cost: an ESPN league's D/ST points differ slightly until the user edits them.
4. **Kicking definitions:** a blocked field goal is a miss; an aborted extra point is a miss; a field goal with no recorded distance counts as 0–39; a kick week sets `g` = 1. Cost: rare edge rows.
5. **D/ST TDs** add kickoff returns (nflverse lists the receiving team as `posteam` on kickoffs) to the existing `defensive_tds` (which already holds punt and blocked-kick returns). **Points allowed** are all of the opponent's points, however scored (the usual fantasy definition). Cost if nflverse ever flips kickoff `posteam`: return TDs would move between teams; the parity gate wouldn't catch it (both sides share the rule), the Task 2/3 tests pin the assumption.
6. **Kickers need a kick before the week** to be projected, like players. A debut week falls back to the team's previous kicker, who then isn't counted by the backtest because he didn't play. Cost: one missed kicker-week per kicker change.
7. **Tries fit:** least squares on lined team-games once there are 64; a flat average before that (and in the synthetic test league, which has few lines). Cost: none on real data, where nflverse posts lines for every past game.
8. **K and D/ST constants are judgments** (`KICK_*`, `DST_*`, `PA_SD_*`). They can't be backtested into place without a tuning loop; Task 9 measures them on the accuracy page and in the gate's printed table, and the handoff reports whether they beat the season-to-date average.
9. **The accuracy page measures K and D/ST; the gate doesn't.** Spec §4 names QB–TE for the gate. Cost: a weak K or D/ST model can't fail CI, only show on the page.
10. **No props for K or D/ST.** The Odds API markets are the offense's (spec §5).
11. **A separate `ws`/`fs` pivot** for kicking and defense scoring keeps the offense's pivot at its measured width (the `StatQueryBuilder` comment's 190 ms → 100 ms fix). Cost: two short CTEs.
12. **The Grid excludes K and D/ST with `excludedPositions`**, keeping players with no position (today's behavior for them). Kickers were already in the database only if they ran or threw; after this they're excluded from the Grid in every view.
13. **Versions:** `INGEST_VERSION` 3 (every season rebuilds once on the next refresh), `SCHEMA_VERSION` 8, `FORECAST_VERSION` 4.

**Placeholder scan.** Task 9's `<K factor>` and `<DST factor>` are measured values that don't exist until Step 2 runs; everything else is given in full.

**Type consistency.** `PaTier.component`/`most` (Task 2) are used by `Dst.kt`, `DatabaseChecks`, `Defense.kt` and `Units.kt`. `TeamKicks(implied: Double?, …)`, `AttemptFit.fit`, `KickLeague`, `KickerRates`, `kickStats`, `teamPoints` (Task 5) match their uses in `Units.kt` (Task 7). `DefenseLeague`, `unitRate`, `opponentFactor`, `defenseStages` (Task 6) likewise. `WeekKind` becomes internal in Task 7 before `UnitProjector` uses it. `widening` (Task 8) is the parameter Task 9's tool passes.

**Review Focus check.** Each of its five lines has a test in the task that owns the code: Task 1 (`a kicker's field goals and extra points are stored beside any play he ran`) and Task 4 (`a kicker who also ran scores both in the same week`); Task 2 (`a game lands in exactly one points-allowed tier`, `a team defense's week is in exactly one points-allowed tier`) and Task 3's Python twin; Task 4 (`a profile saved before …`, `aKickingRuleSetToZeroIsSavedAsZero`); Task 7 (`each team has one kicker, never one kicker for two teams …`); Task 7 (the BBB–CCC game without a line in `kickers and team defenses get …`, and the bye in `rest of season for kickers and defenses …`).
