# K and D/ST Projections Implementation Plan (sub-project 4 of 4)

> **Executed, historical.** Shipped and merged; kept for lookup. It describes intent as written at the time, not current behavior. See `CLAUDE.md` for what exists.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Kickers and team defenses get stats, league scoring with editable points-allowed tiers, weekly and rest-of-season projections, K and D/ST tabs in ☰ → Projections, Player page cards, K and D/ST chips on the Grid, and accuracy rows. CI's gate holds K and D/ST to the same bar as the offense.

**Architecture:**
- **Stats (`:core:ingest`, `etl/`):** play-by-play gains kicking facts: field goals by distance, their totals, extra points and misses, for each kicker. Each team's defense becomes a pseudo-player `DST_<TEAM>`. Its weekly facts come from `team_week_defense`, which gains safeties and kickoff-return TDs. Points allowed are stored as a plain number. The profile's tiers score it, so no tier edges are baked into the database. The Python ETL gains the same facts, and CI's parity gate covers them.
- **Scoring (`:core:model`, `:core:statquery`, `:core:projections`, `:core:datastore`, `:feature:scoring`):**
  - There are 11 new `ScoringRule`s in two new groups.
  - Each `ScoringProfile` gains its own `pointsAllowedTiers`: tier starts and points, which the user can edit. They default to ESPN's nine tiers.
  - A real game scores the one tier its points allowed land in. A projection scores the tiers' expected points under a normal distribution.
  - Saved profiles are migrated once (prefs `formatVersion` 2), which writes the default K and D/ST rules and tiers into each. There's no hidden fallback: a profile shows exactly what it scores.
  - `StatQueryBuilder` scores kicking and defense from their own small pivot, so the offense's scoring query doesn't get wider.
- **Grid (`:core:statquery`, `:core:data`, `:feature:players`):** K and D/ST position chips, each with its own stat pack (Kicking, Defense). The All, FLEX and other offense chips leave kickers and D/STs out.
- **Model (`:core:forecast`):** a `UnitProjector` runs inside the existing walk-forward loop.
  - **Kickers:** a least-squares line from implied team points to field goal and extra point tries. The kicker's distance mix and make rates are shrunk toward the league's.
  - **D/STs:** their own recency-weighted rates times the opponent's. Points allowed are a projected mean from the opponent's implied points, with a measured spread (stored as the variance) and a `g` of 1 per game, so rest of season can be scored per game.
- **Phone (`:core:projections`, `:feature:projections`):**
  - `projectedScore` scores projections, with tiers in expectation.
  - The simulation draws each game's points allowed from a normal distribution and scores its tier.
  - The list gets K and D/ST tabs, and the accuracy page and CI's gate measure both. Each position gets a fitted floor-to-ceiling widening.

**Tech Stack:** Kotlin 2 (explicit API, warnings as errors), JUnit Jupiter (JVM modules), JUnit 4 + Robolectric (Android modules), Jetpack Compose, Roborazzi, bundled SQLite, kotlinx.serialization (prefs), Python 3 + polars (the parity reference ETL).

**Spec:** `docs/superpowers/specs/2026-09-26-projection-model-design.md`, §6 "K and DST (sub-project 4)", plus §1 (reuse, stages), §2 layer 7 and its range amendment, §3 (list tabs, Player page) and §4 (accuracy). The user amended §6 on 2026-09-27 (four rulings overturned while reviewing this plan); Task 11 writes the amendment into the spec.

## Global Constraints

- Warnings are errors: no unused parameters, variables or imports. Explicit API mode in JVM modules: every declaration states its visibility.
- Spec §6, as amended by the user's rulings on 2026-09-27:
  - Scoring: "K rules: FG made 0–39, 40–49 and 50+; XP made; FG missed; XP missed." "DST rules: sack, interception, fumble recovery, defensive/special-teams TD, safety, and points-allowed tiers." **Amended:** the tiers are the profile's own and editable, defaulting to ESPN's (0, 1–6, 7–13, 14–17, 18–21, 22–27, 28–34, 35–45, 46+ worth 5, 4, 3, 1, 0, −1, −4, −5, −5). "Presets get the common defaults, and the scoring editor shows the new rules. `score()` and `StatQueryBuilder` both learn them."
  - **Amended:** profiles saved before this change are migrated once to the default K and D/ST rules and tiers. After that a rule a profile doesn't list scores 0, as today.
  - Stats: "`:core:ingest` adds kicking metrics from play-by-play: FG attempts and makes by distance bucket, XP attempts and makes." "DST facts come from `team_week_defense`, keyed to a team pseudo-player (`DST_<TEAM>`, name "<TEAM> D/ST", position DST) written to `player`." "Parity: the Python ETL gains the same kicking metrics so the parity gate still covers everything."
  - K model: "Implied team points drive FG and XP attempts, using a linear model fit walk-forward. The kicker's distance mix and make rate per bucket are shrunk toward the league average. Empirical CV is 0.52."
  - DST model: "Sacks and turnovers are the unit's own EWMA rates times the opponent's offensive ratings." "Points allowed are modeled from the opponent's implied points; the tier probabilities follow from that." "Empirical CV is 0.85."
  - Screens: "K and DST tabs in the Projections list, and Player page cards." **Amended:** "The Grid is unchanged" becomes K and D/ST chips on the Grid, each with its own stat pack. Every other chip leaves them out.
- **Kotlin and Python agree** on every new fact, player row and `team_week_defense` column: `python etl/tools/parity.py` passes on a fresh 2025 build.
- **Versions:**
  - `INGEST_VERSION` 2 → 3: new facts, so every season rebuilds once.
  - `SCHEMA_VERSION` 7 → 8: `team_week_defense` gains two columns. Python's goes 5 → 6.
  - `FORECAST_VERSION` 3 → 4: new projections.
  - Prefs `FORMAT_VERSION` 1 → 2: the migration.
- **Walk-forward:** projecting week *w* reads only facts from before week *w*, like the players' model. Lines, schedules and who kicked that week are pre-game or participation facts and may be read, as the players' model already does.
- **Props stay offense-only**: The Odds API markets are unchanged; K and D/ST have no `market` factor.
- **Amended: CI's accuracy gate covers QB, RB, WR, TE, K and D/ST.** The model must beat the season-to-date average at all six. If K or D/ST loses, tune their constants (Task 11); never skip the gate.
- Copy rules (from existing UI): one sentence per status line, no exclamation marks. A D/ST shows as "KC D/ST". Its tab and chip read "D/ST".

## Review Focus

- **A kicker who also ran or threw** (a fake field goal). His week holds both his kicking and his offensive facts, `g` is 1, and his points count both. Test in Task 1 (`IngestPipelineTest`) and Task 4 (`ScoringQueryTest`).
- **Points allowed at a tier edge** (0/1, 6/7, 13/14, 17/18, 21/22, 27/28, 34/35, 45/46 under ESPN's tiers), and **a week with no points allowed** (a kicker's). Each lands in exactly one tier. A kicker scores no tier, in SQL, in `score()` and in the contract test's reference scorer alike. Tests in Task 4 (`PointsAllowedTest`, `ScoringQueryTest`, `ScorerTest`).
- **A scoring profile saved before this change.** It's migrated once to the default K and D/ST rules and ESPN's tiers. A user who then sets a rule to 0 or removes every tier keeps that after the next load. Tests in Task 5 (`UserPrefsJsonTest`, `ScoringEditViewModelTest`).
- **A projection's points allowed, week and rest of season.** The expected tier points come from the normal distribution, never from the tier of the mean. Rest of season scores each of its `g` games, never the tier of the season's summed points. Tests in Task 10 (`ProjectedScoreTest`, `MonteCarloTest`, `ProjectionCardTest`).
- **The Grid's chips.** K and D/ST rows appear only under their own chips, and each chip brings its own pack. Every other chip, a search and a roster in the All view leave them out. Tests in Task 6 (`StatsRepositoryTest`, `GridViewModelTest`).
- **A team that changes kickers, or a kicker who moves on.** Each team has at most one kicker a week, no kicker is projected for two teams, and a released kicker isn't projected for his old team. Test in Task 9 (`ForecastEngineTest`).
- **A week with no line posted, or a team on bye.** Kickers and D/STs are still projected: final equals the matchup stage, there's no `game_script` factor, and a bye still counts toward rest of season. Tests in Task 9.

## File Structure

**New**

| File | Responsibility |
|---|---|
| `core/ingest/.../pbp/Kicking.kt` | `KICKING_METRICS`, `fgBucket`, `KickingAggregator` (field goals and extra points per kicker-week) |
| `core/ingest/.../Dst.kt` | `dstPlayerId`, `dstPlayer`, `dstWeeks` (team-weeks as D/ST weeks) |
| `core/model/.../PointsAllowed.kt` | `PointsAllowedTier`, `normalCdf`, `ESPN_POINTS_ALLOWED` |
| `core/projections/.../ProjectedScore.kt` | `projectedScore` (a projection's points, tiers in expectation, per game) |
| `core/forecast/.../Kicker.kt` | `FG_BUCKETS`, `TeamKicks`, `AttemptFit`, `KickLeague`, `KickerRates`, `kickLeague`, `kickerRates`, `kickStats`, `teamPoints` |
| `core/forecast/.../Defense.kt` | `DST_STATS`, `paSpread`, `DefenseLeague`, `defenseLeague`, `unitRate`, `opponentFactor`, `DefenseStages`, `defenseStages` |
| `core/forecast/.../Units.kt` | `UnitProjector`: picks each team's kicker and D/ST, projects them week by week, emits rows, factors and rest of season |
| `etl/tests/test_kicking.py` | Python kicking tests |

**Modified**

| File | Change |
|---|---|
| `core/ingest/.../pbp/Play.kt` | Kicking and safety columns |
| `core/ingest/.../pbp/TeamDefense.kt` | `safeties`, `kickReturnTds` |
| `core/ingest/.../Metrics.kt` | 12 kicking and 6 D/ST metrics, their families |
| `core/ingest/.../db/Schema.kt`, `db/StatsDbWriter.kt` | Versions; `team_week_defense` columns; D/ST players |
| `core/ingest/.../validate/DatabaseChecks.kt` | Kicking coherence, points allowed on every D/ST week |
| `core/ingest/.../IngestPipeline.kt` | Writes kicking and D/ST facts |
| `core/model/.../Position.kt`, `Scoring.kt` | `Position.DST`; `KICKING` and `DEFENSE` groups, 11 rules, `pointsAllowedTiers`, presets |
| `core/statquery/.../Component.kt`, `Scoring.kt`, `StatColumn.kt`, `StatQuerySpec.kt`, `StatQueryBuilder.kt` | Components, rule inputs, K and D/ST columns, `excludedPositions`, the special pivot and its tier `CASE` |
| `core/projections/.../Scorer.kt`, `MonteCarlo.kt`, `ProjectedPoints.kt`, `FactorAttribution.kt`, `Backtest.kt` | The tier in `score()`; `NORMAL` points allowed per game; `projectedScore`; `widening`; K and D/ST measured |
| `core/datastore/.../UserPrefsJson.kt` | Tiers stored; the version-2 migration |
| `feature/scoring/.../ScoringEditViewModel.kt`, `ScoringEditScreen.kt` | The points-allowed tier editor |
| `core/data/.../StatPack.kt`, `StatsRepository.kt`, `SampleThreshold.kt` | `KICKING` and `DEFENSE` packs, `PositionFilter.K`/`DST`, their exclusion elsewhere |
| `feature/players/.../GridViewModel.kt`, `GridScreen.kt` | A chip brings its pack; packs shown for the chip |
| `core/forecast/build.gradle.kts` | `implementation(projects.core.model)` |
| `core/forecast/.../Inputs.kt`, `ForecastConstants.kt`, `Kinds.kt`, `GameScript.kt`, `Projector.kt` | Unit inputs, constants, reference points, `averageImplied`, the unit hook |
| `core/data/.../AccuracyRepository.kt` | `widening` pass-through |
| `feature/projections/.../ProjectionListViewModel.kt`, `ProjectionCard.kt`, `ProjectionsViewModel.kt` | `PositionTab.K`, `PositionTab.DST`; scoring through `projectedScore` |
| `etl/gridiron_etl/transform.py`, `teams.py`, `metrics.py`, `schema.py`, `build.py`, `validate.py`, `etl/tools/parity.py` | The Python twin |
| `CLAUDE.md`, the spec, `docs/superpowers/HANDOFF.md` | Docs |

## Sessions

The user runs 4 tasks per session:
- **Session A:** Tasks 1–4 (stats, parity and scoring).
- **Session B:** Tasks 5–8 (prefs and the tier editor, the Grid's chips, the kicker and D/ST models).
- **Session C:** Tasks 9–11 (the walk-forward, the phone, the gate and calibration, docs), then the final whole-branch review.

**Rebuild the test database before Session A's Task 4, and again in Tasks 6, 9 and 11**, so real-data tests see the new facts: `./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025 2026" -Pout=etl/build/stats.db`.

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
- Produces: `KICKING_METRICS: List<String>` (the 12 ids below), `fgBucket(distance: Double?): String` (`"0_39"`, `"40_49"` or `"50"`), `KickingAggregator.add(Play)`, `KickingAggregator.rows(): List<PlayerWeek>`. Metric ids: `fg_att`, `fg_made`, `fg_att_0_39`, `fg_att_40_49`, `fg_att_50`, `fg_made_0_39`, `fg_made_40_49`, `fg_made_50`, `fg_missed`, `xp_att`, `xp_made`, `xp_missed` (group `kicking`, positions `K`, sparse, family `poisson`). `fg_made` (FGM), `fg_att` (FGA), `fg_made_50` (FG50), `xp_made` (XPM) and `xp_att` (XPA) are visible, with those abbreviations (the Grid's Kicking pack shows them, Task 6); the other seven are internal, abbreviated by their upper-cased ids. `Play` gains `kicker`, `fieldGoalAttempt`, `fieldGoalResult`, `kickDistance`, `extraPointAttempt`, `extraPointResult`.

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
        assertEquals(listOf(5.0, 4.0), listOf(r["fg_att"], r["fg_made"]))
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

In `MetricsTest.kt`, change the count in `ids are unique and every Python metric is here` from `81` to `93`, and add:

```kotlin
    @Test
    fun `kicking metrics are sparse, kicker-only counts, and only the Grid's are visible`() {
        val visible = listOf("fg_made", "fg_att", "fg_made_50", "xp_made", "xp_att")
        val kicking = visible + listOf(
            "fg_att_0_39", "fg_att_40_49", "fg_att_50", "fg_made_0_39", "fg_made_40_49", "fg_missed", "xp_missed",
        )
        for (id in kicking) {
            val m = byId.getValue(id)
            assertEquals(id !in visible, m.isInternal, id)
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

In `IngestPipelineTest.kt`'s `a first build downloads everything and writes a validated database` and in `StatsDbWriterTest.kt`'s `a finished database has the schema, rows and provenance`, change `assertEquals("2", meta["ingest_version"])` to `assertEquals("3", meta["ingest_version"])`. In the latter, change the metric count `"81"` to `"93"`.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :core:ingest:test`
Expected: FAIL to compile with "Unresolved reference: KickingAggregator".

- [ ] **Step 4: Write the kicking aggregator**

`core/ingest/src/main/kotlin/dev/gridiron/core/ingest/pbp/Kicking.kt`:

```kotlin
package dev.gridiron.core.ingest.pbp

/** Every kicking metric, zero until a kick adds to it. */
internal val KICKING_METRICS: List<String> = listOf(
    "fg_att", "fg_made", "fg_att_0_39", "fg_att_40_49", "fg_att_50", "fg_made_0_39", "fg_made_40_49", "fg_made_50",
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
            count("fg_att")
            count("fg_att_$bucket")
            if (p.fieldGoalResult == "made") {
                count("fg_made")
                count("fg_made_$bucket")
            } else {
                count("fg_missed")
            }
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
/** Kicking metrics the Grid shows (its Kicking pack), with their column abbreviations; the rest are scoring and forecast inputs. */
private val KICKING_GRID_ABBRS = mapOf("fg_made" to "FGM", "fg_att" to "FGA", "fg_made_50" to "FG50", "xp_made" to "XPM", "xp_att" to "XPA")

/** Field goals, by distance and in total, extra points and misses: the kicker's scoring and forecast inputs. */
private val KICKING: List<Metric> = listOf(
    "fg_att" to ("FG Attempts" to "Field goal tries, any distance, blocked kicks included."),
    "fg_made" to ("FGs Made" to "Field goals made, any distance."),
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
    Metric(
        id, text.first, KICKING_GRID_ABBRS[id] ?: id.uppercase(), "kicking", text.second,
        positions = KICKERS, decimals = 0, isInternal = id !in KICKING_GRID_ABBRS, sparse = true,
    )
}
```

Append `+ KICKING` to the end of `REGISTRY`'s expression (after the `RANGE_COMPONENTS.map { ... }` block). In `DIST_FAMILIES`, add before the closing brace:

```kotlin
    for (id in listOf(
        "fg_att", "fg_made", "fg_att_0_39", "fg_att_40_49", "fg_att_50", "fg_made_0_39", "fg_made_40_49", "fg_made_50",
        "fg_missed", "xp_att", "xp_made", "xp_missed",
    )) put(id, "poisson")
```

In `DatabaseChecks.kt`, append to `COHERENCE_CHECKS`:

```kotlin
    CoherenceCheck("field goals made within tries", "fg_made", "fg_att", "a <= b"),
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
git commit -m "ingest: kicking facts from play-by-play (field goals by distance and in total, extra points, misses)"
```

### Task 2: D/ST facts

**Files:**
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Dst.kt`
- Modify: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/pbp/Play.kt`, `pbp/TeamDefense.kt`
- Modify: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/db/Schema.kt`, `db/StatsDbWriter.kt`
- Modify: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Metrics.kt`, `validate/DatabaseChecks.kt`, `IngestPipeline.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/DstTest.kt` (new), `pbp/PlayFixtures.kt`, `pbp/TeamDefenseTest.kt`, `db/StatsDbWriterTest.kt`, `validate/DatabaseChecksTest.kt`, `MetricsTest.kt`, `IngestPipelineTest.kt`

**Interfaces:**
- Consumes: `TeamDefenseRow`, `PlayerWeek`, `PlayerInfo(playerId, fullName, searchName, position, team, pfrPlayerId, espnId)`, `normalizeSearch`.
- Produces (all internal to `:core:ingest`):
  - `TeamDefenseRow(…, defensiveTds, safeties, kickReturnTds)`. `team_week_defense` gains `safeties` and `kick_return_tds`.
  - `dstPlayerId(team) = "DST_$team"`, `dstPlayer(team): PlayerInfo` and `dstWeeks(rows: List<TeamDefenseRow>): List<PlayerWeek>`.
  - D/ST metric ids (abbreviations): `dst_sacks` (SACK), `dst_interceptions` (DINT), `dst_fumble_recoveries` (FR), `dst_tds` (DTD) and `dst_safeties` (SAF), all sparse, and `points_allowed` (PA; dense, so a shutout's 0 is stored; family `normal`, lower is better). All are group `defense`, positions `DST`, and visible (the Grid's Defense pack shows them, Task 6).
  - `Play` gains `safety`.
- **No tier facts.** Points allowed are stored as a number. The profile's tiers score them (Task 4), so any tier edges work on the same database.

- [ ] **Step 1: Write the failing tests**

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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DstTest {
    private fun row(pointsAllowed: Double) =
        TeamDefenseRow("KC", 2025, 1, pointsAllowed, 300.0, 3.0, 1.0, 2.0, 1.0, 1.0, 1.0)

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
            week.values,
        )
    }

    @Test
    fun `a shutout stores its zero points allowed`() {
        assertEquals(0.0, dstWeeks(listOf(row(0.0))).single().values["points_allowed"])
    }

    @Test
    fun `a team defense is named for its team and found by searching dst`() {
        assertEquals(PlayerInfo("DST_KC", "KC D/ST", "kc dst", "DST", "KC", null, null), dstPlayer("KC"))
    }
}
```

In `StatsDbWriterTest.kt`, the `build` helper's row becomes `TeamDefenseRow("AAA", s, 1, 10.0, 300.0, 2.0, 1.0, 0.0, 0.0, 0.0, 0.0)`. Change `"7"` for `schema_version` to `"8"` and the metric count `"93"` to `"99"`. Then add:

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
    fun `every team defense week has its points allowed`() {
        assertEquals(emptyList<String>(), dstProblems("g" to 1.0, "points_allowed" to 0.0))
        assertTrue(dstProblems("g" to 1.0, "dst_sacks" to 2.0).any { "points allowed" in it })
    }
```

In `MetricsTest.kt`, change the count from `93` to `99` and add:

```kotlin
    @Test
    fun `team defense metrics are visible, and only points allowed keeps its zeros`() {
        val defense = listOf("dst_sacks", "dst_interceptions", "dst_fumble_recoveries", "dst_tds", "dst_safeties")
        for (id in defense + "points_allowed") {
            val m = byId.getValue(id)
            assertFalse(m.isInternal, id)
            assertEquals(listOf("DST"), m.positions, id)
            assertEquals("defense", m.group, id)
            assertEquals(id != "points_allowed", id in SPARSE_METRIC_IDS, id)
        }
        assertEquals("negbinom", byId.getValue("dst_sacks").distFamily)
        assertEquals("poisson", byId.getValue("dst_tds").distFamily)
        assertEquals("normal", byId.getValue("points_allowed").distFamily)
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

        // The fixture's plays score nothing: both defenses allowed 0, a shutout, and nothing else.
        assertEquals(
            listOf(listOf("g", "1.0"), listOf("points_allowed", "0.0")),
            query(out, "SELECT metric_id, value FROM player_week_stat WHERE player_id = 'DST_BBB' ORDER BY 1"),
        )
        assertEquals(listOf(listOf("BBB D/ST", "DST", "BBB")), query(out, "SELECT full_name, position, team FROM player WHERE player_id = 'DST_BBB'"))
    }
```

(In `a first build …`, the other assertions on `player_week_stat` name their players, so D/ST rows don't disturb them.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:ingest:test`
Expected: FAIL to compile with "Unresolved reference: dstWeeks" and the new `TeamDefenseRow` arguments.

- [ ] **Step 3: Safeties and kickoff return TDs in team defense**

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

- [ ] **Step 4: D/ST players and weeks**

`core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Dst.kt`:

```kotlin
package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.pbp.PlayerWeek
import dev.gridiron.core.ingest.pbp.TeamDefenseRow
import dev.gridiron.core.statquery.normalizeSearch

/** A team's defense and special teams as one pseudo-player, so fantasy D/ST is scored like any player. */
internal fun dstPlayerId(team: String): String = "DST_$team"

internal fun dstPlayer(team: String): PlayerInfo {
    val name = "$team D/ST"
    return PlayerInfo(dstPlayerId(team), name, normalizeSearch(name), "DST", team, pfrPlayerId = null, espnId = null)
}

/**
 * Each team-week as its D/ST's week, reproducing `teams.dst_weekly`. TDs are
 * the defense's plus kickoff returns. Points allowed are stored as a number:
 * the scoring profile's own tiers score them.
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

/** The D/ST pseudo-players' facts: takeaways, scores, and points allowed, which the profile's tiers score. */
private val DEFENSE: List<Metric> = listOf(
    Triple("dst_sacks", "SACK", "D/ST Sacks" to "Sacks by the team's defense."),
    Triple("dst_interceptions", "DINT", "D/ST Interceptions" to "Passes the team's defense intercepted."),
    Triple("dst_fumble_recoveries", "FR", "D/ST Fumble Recoveries" to "Opponent fumbles the team recovered."),
    Triple("dst_tds", "DTD", "D/ST TDs" to "Touchdowns by the defense or on a return: interceptions, fumbles, punts, kickoffs and blocked kicks."),
    Triple("dst_safeties", "SAF", "D/ST Safeties" to "Safeties the team's defense scored."),
).map { (id, abbr, text) ->
    Metric(id, text.first, abbr, "defense", text.second, positions = TEAM_DEFENSE, decimals = 0, sparse = true)
} + Metric(
    "points_allowed", "Points Allowed", "PA", "defense", "Points the opponent scored, however it scored them.",
    positions = TEAM_DEFENSE, higherIsBetter = false, decimals = 0,
)
```

Append `+ DEFENSE` to `REGISTRY` (after `+ KICKING`). In `DIST_FAMILIES`, add:

```kotlin
    put("dst_sacks", "negbinom")
    for (id in listOf("dst_interceptions", "dst_fumble_recoveries", "dst_tds", "dst_safeties")) put(id, "poisson")
    // One game's points allowed: the simulation draws it from a normal distribution and scores its tier.
    put("points_allowed", "normal")
```

In `DatabaseChecks.kt`, add before the `computed` check:

```kotlin
    // The profile's tiers score points allowed, so every D/ST week needs it (a shutout stores 0).
    val unscored = conn.count(
        """SELECT COUNT(*) FROM (
             SELECT MAX(metric_id = 'points_allowed') AS scored
             FROM player_week_stat WHERE player_id LIKE 'DST\_%' ESCAPE '\'
             GROUP BY player_id, season, week)
           WHERE scored = 0""",
    )
    if (unscored > 0) problems += "D/ST weeks without their points allowed: $unscored"
```

In `IngestPipeline.crunch`, replace `writer.writeTeamDefense(defense.rows())` with:

```kotlin
            val defenseRows = defense.rows()
            writer.writeTeamDefense(defenseRows)
            writer.writeFacts(toFacts(dstWeeks(defenseRows)))
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:ingest:test`
Expected: PASS.

Then rebuild the test database to prove the build validates on real data:

Run: `./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025 2026" -Pout=etl/build/stats.db`
Expected: the build validates. A failure naming `points allowed` or a coherence check is a real transform bug: fix it before going on.

- [ ] **Step 6: Commit**

```bash
git add core/ingest
git commit -m "ingest: team defenses as D/ST pseudo-players (safeties, return TDs, points allowed)"
```

### Task 3: The Python ETL's twin, and parity

**Files:**
- Modify: `etl/gridiron_etl/transform.py`, `teams.py`, `metrics.py`, `schema.py`, `build.py`, `validate.py`
- Modify: `etl/tools/parity.py`
- Test: `etl/tests/test_kicking.py` (new), `etl/tests/test_teams.py`, `etl/tests/test_registry.py`, `etl/tests/test_parity.py`

**Interfaces:**
- Consumes: Task 1's and Task 2's definitions (ids, names, abbreviations, visibility, definitions, the kickoff and safety rules), which Python must reproduce exactly.
- Produces: `transform.kicking_stats(pbp_path)`, `transform.kicking_from(lf)`, `teams.dst_weekly(defense)`, `teams.dst_players(teams_)`; `team_defense_from` adds `safeties` and `kick_return_tds`.

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
    assert (r["fg_att"], r["fg_made"]) == (5, 4)
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


def test_a_team_week_becomes_its_team_defenses_week():
    row = teams.dst_weekly(_defense(17)).to_dicts()[0]
    assert (row["player_id"], row["team"], row["g"]) == ("DST_KC", "KC", 1)
    assert (row["dst_sacks"], row["dst_interceptions"], row["dst_fumble_recoveries"]) == (3, 1, 2)
    assert (row["dst_tds"], row["dst_safeties"], row["points_allowed"]) == (2, 1, 17)
    assert not any(k.startswith("pa_") for k in row), "tiers are the profile's, never stored"


def test_a_shutout_stores_its_zero_points_allowed():
    assert teams.dst_weekly(_defense(0)).to_dicts()[0]["points_allowed"] == 0


def test_a_team_defense_is_named_for_its_team():
    assert teams.dst_players(["KC"]).to_dicts() == [
        {"player_id": "DST_KC", "full_name": "KC D/ST", "position": "DST", "team": "KC", "pfr_player_id": None},
    ]
```

In `test_registry.py`, add:

```python
KICKING_VISIBLE = ["fg_made", "fg_att", "fg_made_50", "xp_made", "xp_att"]
KICKING = KICKING_VISIBLE + ["fg_att_0_39", "fg_att_40_49", "fg_att_50", "fg_made_0_39", "fg_made_40_49",
                             "fg_missed", "xp_missed"]
DEFENSE = ["dst_sacks", "dst_interceptions", "dst_fumble_recoveries", "dst_tds", "dst_safeties"]


def test_kicking_and_defense_metrics_are_sparse_theirs_alone_and_visible_where_the_grid_shows_them():
    for mid in KICKING + DEFENSE:
        m = METRICS[mid]
        assert not m.computed, mid
        assert m.internal == (mid in KICKING and mid not in KICKING_VISIBLE), mid
        assert mid in sparse_metric_ids(), mid
    assert all(METRICS[m].positions == ("K",) for m in KICKING)
    assert all(METRICS[m].positions == ("DST",) for m in DEFENSE + ["points_allowed"])
    assert not METRICS["points_allowed"].internal
    assert "points_allowed" not in sparse_metric_ids()
    assert METRICS["points_allowed"].dist_family == "normal"
    assert not any(mid.startswith("pa_") for mid in METRICS)
    assert len(METRICS) == 99
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
            fg_att=count(fg),
            fg_made=count(fg & made),
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
def dst_weekly(defense: pl.DataFrame) -> pl.DataFrame:
    """Each team-week as its D/ST pseudo-player's week (core/ingest's dstWeeks).

    Points allowed are stored as a number; the scoring profile's own tiers score them.
    """
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
        points_allowed=pl.col("points_allowed").cast(pl.Float64),
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
    # ---------------- Kicking (sparse; the Grid's Kicking pack shows five) ----------------
    *[
        Metric(mid, name, abbr or mid.upper(), "kicking", definition,
               positions=("K",), decimals=0, internal=abbr is None, sparse=True)
        for mid, abbr, name, definition in [
            ("fg_att", "FGA", "FG Attempts", "Field goal tries, any distance, blocked kicks included."),
            ("fg_made", "FGM", "FGs Made", "Field goals made, any distance."),
            ("fg_att_0_39", None, "FG Attempts 0-39", "Field goal tries from 39 yards or closer, blocked kicks included."),
            ("fg_att_40_49", None, "FG Attempts 40-49", "Field goal tries from 40 to 49 yards, blocked kicks included."),
            ("fg_att_50", None, "FG Attempts 50+", "Field goal tries from 50 yards or farther, blocked kicks included."),
            ("fg_made_0_39", None, "FGs Made 0-39", "Field goals made from 39 yards or closer."),
            ("fg_made_40_49", None, "FGs Made 40-49", "Field goals made from 40 to 49 yards."),
            ("fg_made_50", "FG50", "FGs Made 50+", "Field goals made from 50 yards or farther."),
            ("fg_missed", None, "FGs Missed", "Field goals missed or blocked, any distance."),
            ("xp_att", "XPA", "XP Attempts", "Extra point kicks tried."),
            ("xp_made", "XPM", "XPs Made", "Extra point kicks made."),
            ("xp_missed", None, "XPs Missed", "Extra point kicks missed, blocked or aborted."),
        ]
    ],

    # ---------------- Team defense (D/ST pseudo-players; the Grid's Defense pack) ----------------
    *[
        Metric(mid, name, abbr, "defense", definition,
               positions=("DST",), decimals=0, sparse=True)
        for mid, abbr, name, definition in [
            ("dst_sacks", "SACK", "D/ST Sacks", "Sacks by the team's defense."),
            ("dst_interceptions", "DINT", "D/ST Interceptions", "Passes the team's defense intercepted."),
            ("dst_fumble_recoveries", "FR", "D/ST Fumble Recoveries", "Opponent fumbles the team recovered."),
            ("dst_tds", "DTD", "D/ST TDs", "Touchdowns by the defense or on a return: interceptions, fumbles, punts, kickoffs and blocked kicks."),
            ("dst_safeties", "SAF", "D/ST Safeties", "Safeties the team's defense scored."),
        ]
    ],
    Metric("points_allowed", "Points Allowed", "PA", "defense",
           "Points the opponent scored, however it scored them.",
           positions=("DST",), higher_is_better=False, decimals=0),
```

The Kotlin registry puts kicking and defense *after* the range components; the table is keyed by id, so the order doesn't matter to parity.

In `DIST_FAMILIES`, add:

```python
    **{m: "poisson" for m in (
        "fg_att", "fg_made", "fg_att_0_39", "fg_att_40_49", "fg_att_50", "fg_made_0_39", "fg_made_40_49",
        "fg_made_50", "fg_missed", "xp_att", "xp_made", "xp_missed",
        "dst_interceptions", "dst_fumble_recoveries", "dst_tds", "dst_safeties",
    )},
    "dst_sacks": "negbinom",
    "points_allowed": "normal",
```

In `schema.py`, set `SCHEMA_VERSION = 6`, add `safeties REAL NOT NULL,` and `kick_return_tds REAL NOT NULL,` after `defensive_tds` in the `team_week_defense` DDL, and add `"safeties", "kick_return_tds"` to `load_team_defense`'s column list after `"defensive_tds"`.

In `validate.py`, append to `COHERENCE_CHECKS`:

```python
    CoherenceCheck("field goals made within tries", "fg_made", "fg_att", "a <= b"),
    CoherenceCheck("0-39 field goals made within tries", "fg_made_0_39", "fg_att_0_39", "a <= b"),
    CoherenceCheck("40-49 field goals made within tries", "fg_made_40_49", "fg_att_40_49", "a <= b"),
    CoherenceCheck("50+ field goals made within tries", "fg_made_50", "fg_att_50", "a <= b"),
    CoherenceCheck("extra points made within tries", "xp_made", "xp_att", "a <= b"),
    CoherenceCheck("extra points missed within tries", "xp_missed", "xp_att", "a <= b"),
```

and before the `computed` check:

```python
    # The profile's tiers score points allowed, so every D/ST week needs it (a shutout stores 0).
    unscored = conn.execute(
        """SELECT COUNT(*) FROM (
             SELECT MAX(metric_id = 'points_allowed') AS scored
             FROM player_week_stat WHERE player_id LIKE 'DST\\_%' ESCAPE '\\'
             GROUP BY player_id, season, week)
           WHERE scored = 0"""
    ).fetchone()[0]
    if unscored:
        problems.append(f"D/ST weeks without their points allowed: {unscored}")
```

and change the final log's `+ 8` to `+ 9`.

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

### Task 4: Kicking and D/ST scoring, with the profile's own points-allowed tiers

**Files:**
- Create: `core/model/src/main/kotlin/dev/gridiron/core/model/PointsAllowed.kt`
- Modify: `core/model/src/main/kotlin/dev/gridiron/core/model/Scoring.kt`, `Position.kt`
- Modify: `core/statquery/src/main/kotlin/dev/gridiron/core/statquery/Component.kt`, `Scoring.kt`, `StatColumn.kt`, `StatQueryBuilder.kt`
- Modify: `core/projections/src/main/kotlin/dev/gridiron/core/projections/Scorer.kt`, `Backtest.kt` (`ACTUAL_SCORING_COMPONENTS`)
- Test: `core/model/src/test/kotlin/dev/gridiron/core/model/PointsAllowedTest.kt` (new), `ScoringProfileTest.kt`; `core/statquery/src/test/kotlin/dev/gridiron/core/statquery/ScoringQueryTest.kt`, `RealDatabaseContractTest.kt`; `core/projections/src/test/kotlin/dev/gridiron/core/projections/ScorerTest.kt`

**Interfaces:**
- Consumes: Task 1's kicking metric ids and Task 2's D/ST metric ids.
- Produces:
  - `:core:model`:
    - `Position.DST` (code `"DST"`) and `ScoringGroup.KICKING` ("Kicking"), `ScoringGroup.DEFENSE` ("Team defense").
    - 11 rules: `FG_MADE_0_39`, `FG_MADE_40_49`, `FG_MADE_50`, `FG_MISSED`, `XP_MADE`, `XP_MISSED`, `DST_SACK`, `DST_INTERCEPTION`, `DST_FUMBLE_RECOVERY`, `DST_TD`, `DST_SAFETY`.
    - `PointsAllowedTier(min: Int, points: Double)` and `ESPN_POINTS_ALLOWED: List<PointsAllowedTier>`; `normalCdf(z: Double): Double`.
    - `ScoringProfile.pointsAllowedTiers: List<PointsAllowedTier>` (default empty; the first starts at 0, starts strictly rising), `pointsAllowedPoints(allowed: Double): Double` and `expectedPointsAllowedPoints(mean: Double, sd: Double): Double`.
    - `ScoringPresets.KICKING_AND_DEFENSE: Map<ScoringRule, Double>` (FG 3/4/5 by distance, FG missed −1, XP made 1, XP missed −1; sack 1, interception 2, fumble recovery 2, TD 6, safety 2). Every preset carries it and `ESPN_POINTS_ALLOWED`.
  - `:core:statquery`:
    - Components: `FG_ATT`, `FG_MADE`, `FG_MADE_0_39`, `FG_MADE_40_49`, `FG_MADE_50`, `FG_MISSED`, `XP_ATT`, `XP_MADE`, `XP_MISSED`, `DST_SACKS`, `DST_INTERCEPTIONS`, `DST_FUMBLE_RECOVERIES`, `DST_TDS`, `DST_SAFETIES`, `POINTS_ALLOWED`.
    - `StatColumn`s over the visible ones, all `Total`s: `FG_MADE`, `FG_ATT`, `FG_MADE_50`, `XP_MADE`, `XP_ATT`, `DST_SACKS`, `DST_INTERCEPTIONS`, `DST_FUMBLE_RECOVERIES`, `DST_TDS`, `DST_SAFETIES` and `POINTS_ALLOWED` (lower is better). Task 6 puts them in the Grid's packs; the contract tests check them from now on.
    - `SPECIAL_RULES` (internal): the kicking and defense rules. `SCORING_COMPONENTS` includes `POINTS_ALLOWED`.
  - `:core:projections`: `score()` adds the profile's tier when the map holds `POINTS_ALLOWED` (one game's points allowed). `ACTUAL_SCORING_COMPONENTS` includes it.

- [ ] **Step 1: Write the failing tests**

`core/model/src/test/kotlin/dev/gridiron/core/model/PointsAllowedTest.kt`:

```kotlin
package dev.gridiron.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class PointsAllowedTest {
    private val espn = ScoringPresets.PPR

    @Test
    fun `the normal CDF matches known values`() {
        assertEquals(0.5, normalCdf(0.0), 1e-7)
        assertEquals(0.975, normalCdf(1.959964), 1e-6)
        assertEquals(0.025, normalCdf(-1.959964), 1e-6)
        assertEquals(0.8413447, normalCdf(1.0), 1e-6)
    }

    @ParameterizedTest
    @CsvSource(
        "0,5", "1,4", "6,4", "7,3", "13,3", "14,1", "17,1", "18,0", "21,0",
        "22,-1", "27,-1", "28,-4", "34,-4", "35,-5", "45,-5", "46,-5", "70,-5",
    )
    fun `a game lands in exactly one of ESPN's tiers, edges included`(allowed: Double, points: Double) {
        assertEquals(points, espn.pointsAllowedPoints(allowed), 0.0)
    }

    @Test
    fun `a profile with no tiers scores points allowed as nothing`() {
        assertEquals(0.0, espn.copy(id = "u1", name = "Mine", pointsAllowedTiers = emptyList()).pointsAllowedPoints(0.0), 0.0)
    }

    @Test
    fun `expected tier points weigh each tier by its chance, on whole points`() {
        val two = espn.copy(id = "u1", name = "Mine", pointsAllowedTiers = listOf(PointsAllowedTier(0, 10.0), PointsAllowedTier(21, -4.0)))
        // Up to 20 points is everything below 20.5.
        val below = normalCdf((20.5 - 22.0) / 10.0)
        assertEquals(10.0 * below - 4.0 * (1 - below), two.expectedPointsAllowedPoints(22.0, 10.0), 1e-12)
        // A vanishing spread is the game's own tier; a zero one rounds to whole points.
        assertEquals(espn.pointsAllowedPoints(17.0), espn.expectedPointsAllowedPoints(17.0, 1e-6), 1e-9)
        assertEquals(0.0, espn.expectedPointsAllowedPoints(17.6, 0.0), 0.0) // 18: the 18-21 tier
    }

    @Test
    fun `more points expected means fewer tier points`() {
        assertEquals(true, espn.expectedPointsAllowedPoints(14.0, 10.0) > espn.expectedPointsAllowedPoints(30.0, 10.0))
    }

    @Test
    fun `tiers start at 0 and rise`() {
        val base = espn.copy(id = "u1", name = "Mine")
        assertThrows<IllegalArgumentException> { base.copy(pointsAllowedTiers = listOf(PointsAllowedTier(1, 4.0))) }
        assertThrows<IllegalArgumentException> { base.copy(pointsAllowedTiers = listOf(PointsAllowedTier(0, 5.0), PointsAllowedTier(0, 4.0))) }
        assertThrows<IllegalArgumentException> { base.copy(pointsAllowedTiers = listOf(PointsAllowedTier(0, 5.0), PointsAllowedTier(14, 1.0), PointsAllowedTier(7, 3.0))) }
        assertThrows<IllegalArgumentException> { PointsAllowedTier(-1, 0.0) }
        assertThrows<IllegalArgumentException> { PointsAllowedTier(0, Double.NaN) }
    }
}
```

(If `junit-jupiter-params` isn't on `:core:model`'s test classpath, add `testImplementation(libs.junit.jupiter.params)`, or whatever the catalog names it: `:core:ingest` already uses `@ParameterizedTest`.)

In `ScoringProfileTest.kt`'s `presets use ESPN defaults …`, add before `assertTrue(ScoringPresets.all.all { … })`:

```kotlin
        for (preset in ScoringPresets.all) {
            for ((rule, value) in ScoringPresets.KICKING_AND_DEFENSE) assertEquals(value, preset.weight(rule), "${preset.id} $rule")
            assertEquals(ESPN_POINTS_ALLOWED, preset.pointsAllowedTiers, preset.id)
        }
        assertEquals(3.0, ppr.weight(ScoringRule.FG_MADE_0_39))
        assertEquals(6.0, ppr.weight(ScoringRule.DST_TD))
```

In `ScoringQueryTest.kt`, add:

```kotlin
    @Test
    fun `a kicker's week scores field goals by distance, extra points and misses, and no points-allowed tier`() {
        db.player("k1", "Place Kicker", position = "K")
        db.week("k1", 1, C.FG_MADE_0_39 to 1, C.FG_MADE_40_49 to 1, C.FG_MADE_50 to 1, C.FG_MISSED to 1, C.XP_MADE to 3, C.XP_MISSED to 1)
        // 3 + 4 + 5 - 1 + 3 - 1. A tier for his missing points allowed would add ESPN's 5 for a shutout.
        assertEquals(13.0, db.grid(fantasy(ScoringPresets.PPR)).single().value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `a team defense scores takeaways, TDs, safeties and each week's points-allowed tier`() {
        db.player("DST_KC", "KC D/ST", position = "DST", team = "KC")
        db.week("DST_KC", 1, C.DST_SACKS to 3, C.DST_INTERCEPTIONS to 1, C.DST_FUMBLE_RECOVERIES to 1, C.DST_TDS to 1, C.DST_SAFETIES to 1, C.POINTS_ALLOWED to 10)
        db.week("DST_KC", 2, C.DST_SACKS to 1, C.POINTS_ALLOWED to 46)
        // Week 1: 3 + 2 + 2 + 6 + 2, and 7-13 allowed is 3: 18. Week 2: 1, and 46+ allowed is -5: -4.
        assertEquals(14.0, db.grid(fantasy(ScoringPresets.PPR)).single().value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `each week scores its own tier, never the tier of the weeks' total`() {
        db.player("DST_KC", "KC D/ST", position = "DST", team = "KC")
        db.week("DST_KC", 1, C.POINTS_ALLOWED to 17)
        db.week("DST_KC", 2, C.POINTS_ALLOWED to 18)
        db.week("DST_KC", 3, C.POINTS_ALLOWED to 0)
        // 1 + 0 + 5. The total, 35, would be one tier worth -5.
        assertEquals(6.0, db.grid(fantasy(ScoringPresets.PPR)).single().value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `a profile's own tiers score points allowed, and no tiers score none`() {
        db.player("DST_KC", "KC D/ST", position = "DST", team = "KC")
        db.week("DST_KC", 1, C.POINTS_ALLOWED to 20)
        db.week("DST_KC", 2, C.POINTS_ALLOWED to 21)
        val yahoo = custom().copy(pointsAllowedTiers = listOf(PointsAllowedTier(0, 10.0), PointsAllowedTier(14, 1.0), PointsAllowedTier(21, 0.0)))
        assertEquals(1.0, db.grid(fantasy(yahoo)).single().value(FANTASY_POINTS)!!, EPS)
        assertEquals(0.0, db.grid(fantasy(custom())).single().value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `a kicker who also ran scores both in the same week`() {
        db.player("k1", "Place Kicker", position = "K")
        db.week("k1", 1, C.FG_MADE_0_39 to 1, C.RUSHING_YARDS to 20)
        // 3 + 2
        assertEquals(5.0, db.grid(fantasy(ScoringPresets.PPR)).single().value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `a profile's own kicking weights replace the presets', zero included`() {
        db.player("k1", "Place Kicker", position = "K")
        db.week("k1", 1, C.FG_MADE_50 to 1, C.FG_MISSED to 2)
        val profile = custom(ScoringRule.FG_MADE_50 to 6.0, ScoringRule.FG_MISSED to 0.0)
        assertEquals(6.0, db.grid(fantasy(profile)).single().value(FANTASY_POINTS)!!, EPS)
    }
```

(import `dev.gridiron.core.model.PointsAllowedTier`).

In `ScorerTest.kt`, rename `an unsupported position (kicker) scores zero without throwing` to `a null position scores without throwing`, replace its comment with `// A null position must not crash score(); the reception rule still reads the map.`, and add:

```kotlin
    @Test
    fun `kicking and team defense score with the presets' values`() {
        val kicker = mapOf(Components.FG_MADE_50 to 1.0, Components.FG_MISSED to 1.0, Components.XP_MADE to 2.0)
        assertEquals(6.0, score(kicker, ScoringPresets.PPR, Position.K), 1e-9)
        val defense = mapOf(Components.DST_SACKS to 2.0, Components.POINTS_ALLOWED to 0.0)
        // 2 sacks and a shutout (5).
        assertEquals(7.0, score(defense, ScoringPresets.STANDARD, Position.DST), 1e-9)
    }

    @Test
    fun `a week without points allowed scores no tier`() {
        assertEquals(3.0, score(mapOf(Components.FG_MADE_0_39 to 1.0), ScoringPresets.PPR, Position.K), 1e-9)
    }
```

In `RealDatabaseContractTest.kt`, give `everyRule` tiers that differ from ESPN's, so the SQL's edges are really read:

```kotlin
        pointsAllowedTiers = listOf(PointsAllowedTier(0, 7.5), PointsAllowedTier(3, 4.25), PointsAllowedTier(10, 1.0), PointsAllowedTier(24, -2.5), PointsAllowedTier(31, -6.0)),
```

In `referenceWeek`, add before `for (b in profile.yardageBonuses)`:

```kotlin
        fp += w(ScoringRule.FG_MADE_0_39) * v("fg_made_0_39") + w(ScoringRule.FG_MADE_40_49) * v("fg_made_40_49") +
            w(ScoringRule.FG_MADE_50) * v("fg_made_50") + w(ScoringRule.FG_MISSED) * v("fg_missed") +
            w(ScoringRule.XP_MADE) * v("xp_made") + w(ScoringRule.XP_MISSED) * v("xp_missed") +
            w(ScoringRule.DST_SACK) * v("dst_sacks") + w(ScoringRule.DST_INTERCEPTION) * v("dst_interceptions") +
            w(ScoringRule.DST_FUMBLE_RECOVERY) * v("dst_fumble_recoveries") + w(ScoringRule.DST_TD) * v("dst_tds") +
            w(ScoringRule.DST_SAFETY) * v("dst_safeties")
        // Written by hand, not with pointsAllowedPoints: the highest tier starting at or below the points allowed.
        s["points_allowed"]?.let { allowed -> fp += profile.pointsAllowedTiers.last { allowed >= it.min }.points }
```

`everyRule` already sets a weight for every `ScoringRule`, so the new rules are exercised. The grid spec there has no position filter, so kickers and D/STs are scored and checked. `s` must hold `points_allowed`: if the loader above `referenceWeek` reads a fixed metric list rather than `SCORING_COMPONENTS`, add `"points_allowed"` to it. (Import `dev.gridiron.core.model.PointsAllowedTier`.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:model:test :core:statquery:test :core:projections:test`
Expected: FAIL to compile with "Unresolved reference: FG_MADE_0_39", "Unresolved reference: PointsAllowedTier" and "Unresolved reference: normalCdf".

- [ ] **Step 3: The rules, the tiers and the presets**

`core/model/src/main/kotlin/dev/gridiron/core/model/PointsAllowed.kt`:

```kotlin
package dev.gridiron.core.model

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * A D/ST points-allowed scoring tier: [points] for a game in which the
 * opponent scored at least [min], up to the next tier's [min]. A profile's
 * tiers start at 0, so every game lands in exactly one.
 */
public data class PointsAllowedTier(val min: Int, val points: Double) {
    init {
        require(min >= 0) { "a tier can't start below 0 points, was $min" }
        require(points.isFinite()) { "tier points must be finite, was $points" }
    }
}

/** ESPN's default tiers: 0, 1-6, 7-13, 14-17, 18-21, 22-27, 28-34, 35-45 and 46+ points allowed. */
public val ESPN_POINTS_ALLOWED: List<PointsAllowedTier> = listOf(
    PointsAllowedTier(0, 5.0), PointsAllowedTier(1, 4.0), PointsAllowedTier(7, 3.0),
    PointsAllowedTier(14, 1.0), PointsAllowedTier(18, 0.0), PointsAllowedTier(22, -1.0),
    PointsAllowedTier(28, -4.0), PointsAllowedTier(35, -5.0), PointsAllowedTier(46, -5.0),
)

/** The standard normal CDF, through Abramowitz and Stegun's 7.1.26 erf (error below 1.5e-7). */
public fun normalCdf(z: Double): Double {
    val x = abs(z) / sqrt(2.0)
    val t = 1.0 / (1.0 + 0.3275911 * x)
    val poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
    val erf = 1.0 - poly * exp(-x * x)
    return if (z >= 0) 0.5 * (1.0 + erf) else 0.5 * (1.0 - erf)
}
```

In `Position.kt`, change the KDoc to `/** Positions as stored in `player.position`: the offense, kickers, and DST (a team's defense and special teams). */` and add after `K("K"),`:

```kotlin
    DST("DST"),
```

In `Scoring.kt`, add to `ScoringGroup` after `TURNOVERS("Turnovers"),`:

```kotlin
    KICKING("Kicking"),
    DEFENSE("Team defense"),
```

Change `ScoringRule`'s KDoc to `/** Points per unit of one stat. IDP is absent on purpose. A D/ST's points allowed are scored by the profile's [ScoringProfile.pointsAllowedTiers], not a rule. */` and add after `FUMBLE_LOST(ScoringGroup.TURNOVERS, "Fumble lost"),`:

```kotlin
    FG_MADE_0_39(ScoringGroup.KICKING, "FG made, 0-39 yds"),
    FG_MADE_40_49(ScoringGroup.KICKING, "FG made, 40-49 yds"),
    FG_MADE_50(ScoringGroup.KICKING, "FG made, 50+ yds"),
    FG_MISSED(ScoringGroup.KICKING, "FG missed"),
    XP_MADE(ScoringGroup.KICKING, "Extra point made"),
    XP_MISSED(ScoringGroup.KICKING, "Extra point missed"),
    DST_SACK(ScoringGroup.DEFENSE, "Sack"),
    DST_INTERCEPTION(ScoringGroup.DEFENSE, "Interception"),
    DST_FUMBLE_RECOVERY(ScoringGroup.DEFENSE, "Fumble recovery"),
    DST_TD(ScoringGroup.DEFENSE, "Defensive or return TD"),
    DST_SAFETY(ScoringGroup.DEFENSE, "Safety"),
```

In `ScoringProfile`, document and add the property after `yardageBonuses`:

```kotlin
 * @property pointsAllowedTiers A D/ST's points-allowed tiers, lowest first. The
 *   first starts at 0, so every game lands in one; empty scores points allowed as nothing.
```

```kotlin
    val pointsAllowedTiers: List<PointsAllowedTier> = emptyList(),
```

add to `init`:

```kotlin
        require(pointsAllowedTiers.isEmpty() || pointsAllowedTiers.first().min == 0) {
            "the lowest points-allowed tier must start at 0: $pointsAllowedTiers"
        }
        require(pointsAllowedTiers.zipWithNext().all { (a, b) -> a.min < b.min }) {
            "points-allowed tiers must start at rising points: $pointsAllowedTiers"
        }
```

and add after `receptionWeight`:

```kotlin
    /** A D/ST's points for one game in which the opponent scored [allowed]: the highest tier starting at or below it. */
    public fun pointsAllowedPoints(allowed: Double): Double =
        pointsAllowedTiers.lastOrNull { allowed >= it.min }?.points ?: 0.0

    /**
     * The expected [pointsAllowedPoints] for one game whose points allowed are
     * about Normal([mean], [sd]) and land on whole points: a tier starting at 7
     * takes everything from 6.5 up, and anything below the second tier's start
     * is the first tier. A zero [sd] is the tier of [mean] rounded.
     */
    public fun expectedPointsAllowedPoints(mean: Double, sd: Double): Double {
        if (sd <= 0.0) return pointsAllowedPoints(Math.round(mean).toDouble())
        return pointsAllowedTiers.indices.sumOf { i ->
            val from = if (i == 0) 0.0 else normalCdf((pointsAllowedTiers[i].min - 0.5 - mean) / sd)
            val to = pointsAllowedTiers.getOrNull(i + 1)?.let { normalCdf((it.min - 0.5 - mean) / sd) } ?: 1.0
            pointsAllowedTiers[i].points * (to - from)
        }
    }
```

In `ScoringPresets`, add **above** `espn(...)` (an object initializes top to bottom, and the presets read it):

```kotlin
    /** The common kicking and team-defense values every preset scores, and the version-2 prefs migration writes into older profiles. */
    public val KICKING_AND_DEFENSE: Map<ScoringRule, Double> = mapOf(
        ScoringRule.FG_MADE_0_39 to 3.0,
        ScoringRule.FG_MADE_40_49 to 4.0,
        ScoringRule.FG_MADE_50 to 5.0,
        ScoringRule.FG_MISSED to -1.0,
        ScoringRule.XP_MADE to 1.0,
        ScoringRule.XP_MISSED to -1.0,
        ScoringRule.DST_SACK to 1.0,
        ScoringRule.DST_INTERCEPTION to 2.0,
        ScoringRule.DST_FUMBLE_RECOVERY to 2.0,
        ScoringRule.DST_TD to 6.0,
        ScoringRule.DST_SAFETY to 2.0,
    )
```

In `espn(...)`, change `weights = mapOf(…),` to `weights = mapOf(…) + KICKING_AND_DEFENSE,` and add `pointsAllowedTiers = ESPN_POINTS_ALLOWED,`. Change the object's KDoc to `/** ESPN's default scoring in its three reception flavors: offense, the common kicking and team-defense values, and ESPN's points-allowed tiers. Immutable; copy one to customize. */`.

- [ ] **Step 4: Rule inputs and the scoring query**

In `Component.kt`, add inside `Components` after `X_INTERCEPTIONS`:

```kotlin

    // Kicking: the kicker's scoring inputs. Internal and sparse, except the
    // 50+ makes and extra points made, which the Grid's Kicking pack shows too.
    public val FG_MADE_0_39: Component = Component("fg_made_0_39")
    public val FG_MADE_40_49: Component = Component("fg_made_40_49")
    public val FG_MADE_50: Component = Component("fg_made_50")
    public val FG_MISSED: Component = Component("fg_missed")
    public val XP_MADE: Component = Component("xp_made")
    public val XP_MISSED: Component = Component("xp_missed")
    // Totals the Grid's Kicking pack shows; no rule reads them.
    public val FG_ATT: Component = Component("fg_att")
    public val FG_MADE: Component = Component("fg_made")
    public val XP_ATT: Component = Component("xp_att")

    // Team defense and special teams (D/ST pseudo-players). Sparse, except
    // points allowed, whose zero is a shutout; the profile's tiers score it.
    public val DST_SACKS: Component = Component("dst_sacks")
    public val DST_INTERCEPTIONS: Component = Component("dst_interceptions")
    public val DST_FUMBLE_RECOVERIES: Component = Component("dst_fumble_recoveries")
    public val DST_TDS: Component = Component("dst_tds")
    public val DST_SAFETIES: Component = Component("dst_safeties")
    public val POINTS_ALLOWED: Component = Component("points_allowed")
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
```

After `BONUS_INPUTS`, add:

```kotlin
/**
 * Kicking and team-defense rules. Their facts belong to kickers and D/STs, a
 * few rows a week, so the scoring query pivots them on their own rather than
 * widening the offense's pivot.
 */
internal val SPECIAL_RULES: Set<ScoringRule> =
    ScoringRule.entries.filter { it.group == ScoringGroup.KICKING || it.group == ScoringGroup.DEFENSE }.toSet()
```

(import `dev.gridiron.core.model.ScoringGroup`), and add `+ C.POINTS_ALLOWED` to `SCORING_COMPONENTS`'s list, before `.distinct()`, with the comment `// Points allowed are read by the profile's tiers, not a rule.`

In `StatQueryBuilder.kt`, replace `ACTUAL_COMPONENTS` and add the special lists:

```kotlin
/** The offense's rules, in declaration order so equal profiles give identical SQL. */
private val OFFENSE_RULES: List<ScoringRule> = ScoringRule.entries.filter { it !in SPECIAL_RULES }

private val SPECIAL_RULE_LIST: List<ScoringRule> = ScoringRule.entries.filter { it in SPECIAL_RULES }

private val ACTUAL_COMPONENTS: List<Component> =
    (OFFENSE_RULES.flatMap { RULE_INPUTS.getValue(it).actual }.map { it.component } + BONUS_INPUTS.values.flatten())
        .distinct()
        .sortedBy { it.id }

/** Kicking and team-defense inputs, and points allowed for the tiers: pivoted apart (`ws`), so the offense's pivot stays as narrow as it was. */
private val SPECIAL_COMPONENTS: List<Component> =
    (SPECIAL_RULE_LIST.flatMap { RULE_INPUTS.getValue(it).actual }.map { it.component } + Components.POINTS_ALLOWED)
        .distinct()
        .sortedBy { it.id }
```

Update the class KDoc's step 0 to: "When a fantasy column is planned, `wk` pivots the offense's scoring components to one row per player-week, `ws` does the same for kicking, team-defense and points-allowed components, `fw` and `fs` apply the spec's scoring profile to each week (so per-game bonuses and points-allowed tiers see single games), and `fsum` totals them per player into fantasy points, expected fantasy points and FPOE."

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
        line("       , ${points(profile, SPECIAL_RULE_LIST, expected = false, bonuses = false, wSpecial)} + ${tiers(profile)} AS fp")
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
     * so the SQL shape depends only on the number of bonuses and tiers.
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

    /**
     * One week's points-allowed tier from the profile's own tiers, checked
     * highest first. A week with no points allowed (a kicker's) scores none:
     * the pivot's column is NULL there, while a shutout stores 0.
     */
    private fun tiers(profile: ScoringProfile): String {
        val tiers = profile.pointsAllowedTiers
        if (tiers.isEmpty()) return "0"
        val allowed = "ws.s${SPECIAL_COMPONENTS.indexOf(Components.POINTS_ALLOWED)}"
        val cases = tiers.asReversed().joinToString(" ") { "WHEN $allowed >= ${int(it.min)} THEN ${real(it.points)}" }
        return "(CASE WHEN $allowed IS NULL THEN 0 $cases ELSE 0 END)"
    }
```

Update the `scoring()` KDoc's first sentence to add "`ws` pivots kicking, team-defense and points-allowed components the same way, and `fs` scores them with each week's tier".

In `StatColumn.kt`, add before `// Fantasy: scored per player-week from the spec's profile.`:

```kotlin
    // Kicking (the Grid's K chip)
    FG_MADE("fg_made", Total(C.FG_MADE)),
    FG_ATT("fg_att", Total(C.FG_ATT)),
    FG_MADE_50("fg_made_50", Total(C.FG_MADE_50)),
    XP_MADE("xp_made", Total(C.XP_MADE)),
    XP_ATT("xp_att", Total(C.XP_ATT)),

    // Team defense (the Grid's D/ST chip)
    POINTS_ALLOWED("points_allowed", Total(C.POINTS_ALLOWED), higherIsBetter = false),
    DST_SACKS("dst_sacks", Total(C.DST_SACKS)),
    DST_INTERCEPTIONS("dst_interceptions", Total(C.DST_INTERCEPTIONS)),
    DST_FUMBLE_RECOVERIES("dst_fumble_recoveries", Total(C.DST_FUMBLE_RECOVERIES)),
    DST_TDS("dst_tds", Total(C.DST_TDS)),
    DST_SAFETIES("dst_safeties", Total(C.DST_SAFETIES)),
```

(Per game, `POINTS_ALLOWED` is the average allowed a game, which is what a D/ST column should show.)

- [ ] **Step 5: The tier in `score()`**

In `Scorer.kt`, after the bonuses loop and before `return total`:

```kotlin
    // One D/ST game's points allowed land in one of the profile's tiers. A map
    // without them (anyone else's week) scores no tier.
    components[Components.POINTS_ALLOWED]?.let { total += profile.pointsAllowedPoints(it) }
```

(import `dev.gridiron.core.statquery.Components`), and extend the KDoc: "[components] is one game's stats: a D/ST's points allowed score the profile's tier for that game. Projections go through `projectedScore`, which scores the tiers in expectation."

In `Backtest.kt`, `ACTUAL_SCORING_COMPONENTS` becomes:

```kotlin
/** Every stat a real game's fantasy score reads, points allowed (the tiers') included. */
public val ACTUAL_SCORING_COMPONENTS: List<Component> =
    (RULE_INPUTS.values.flatMap { inputs -> inputs.actual.map { it.component } } + BONUS_INPUTS.values.flatten() + Components.POINTS_ALLOWED)
        .distinct()
        .sortedBy { it.id }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :core:model:test :core:statquery:test :core:projections:test`
Expected: PASS (the real-data contract tests skip without `GRIDIRON_STATS_DB`). If a determinism test compares two profiles' SQL, both have nine tiers, so their shapes still match.

Then the real-data contract tests, against the database rebuilt in Task 2:

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:statquery:test :core:data:test`
Expected: PASS. This includes:
- `builder fantasy points match an independent per-week scorer for every player`, now with kickers, D/STs and odd tiers.
- `every scoring component is a registered internal metric with data`. `fg_made_50`, `xp_made`, the D/ST stats and `points_allowed` are visible metrics, which the test allows because each is now a `StatColumn`'s.
- `single-week recomputation reproduces every stored weekly value`, which now covers the 11 new columns. If a test elsewhere enumerates `StatColumn.entries` with a fixed count or a per-column expectation (formatting, the filter sheet's picker), extend it to the new columns: that's the intended change.
- Both timing tests: `a full-season, many-column grid is fast` and `scoring a full season for every player is fast`. If a timing test fails, rerun `:core:statquery:test` alone before concluding anything (the known CPU-contention flake).

- [ ] **Step 7: Commit**

```bash
git add core/model core/statquery core/projections core/data
git commit -m "scoring: kicking and team-defense rules, and each profile's own points-allowed tiers (ESPN's by default)"
```

### Task 5: Saved tiers, the one-time migration, and the tier editor

**Files:**
- Modify: `core/datastore/src/main/kotlin/dev/gridiron/core/datastore/UserPrefsJson.kt`
- Modify: `feature/scoring/src/main/kotlin/dev/gridiron/feature/scoring/ScoringEditViewModel.kt`, `ScoringEditScreen.kt`
- Test: `core/datastore/src/test/kotlin/dev/gridiron/core/datastore/UserPrefsStoreTest.kt`; `feature/scoring/src/test/kotlin/dev/gridiron/feature/scoring/ScoringEditViewModelTest.kt`, `ScoringScreenTest.kt`

**Interfaces:**
- Consumes: Task 4's `PointsAllowedTier`, `ScoringProfile.pointsAllowedTiers`, `ScoringPresets.KICKING_AND_DEFENSE`, `ESPN_POINTS_ALLOWED`, the two new `ScoringGroup`s.
- Produces:
  - Prefs `FORMAT_VERSION` 2. `ProfileDto.pointsAllowed: List<TierDto>` (`TierDto(min: Int, points: Double)`).
  - Reading a file older than version 2 adds `KICKING_AND_DEFENSE` (for any rule the profile doesn't list) and `ESPN_POINTS_ALLOWED` to every profile. Writing always writes version 2, so the migration runs once.
  - Editor: `TierDraft(key, min, points)`; `FieldKey.TierMin(key)`, `FieldKey.TierPoints(key)`; `EditEvent.TierAdded`, `TierChanged(key, draft)`, `TierRemoved(key)`; `EditState.Editing.tiers`. The screen's "Points allowed" section follows the Team defense rules. Test tags: `tier:min:<key>`, `tier:points:<key>`, `tier:remove:<key>`, `addTier`.

- [ ] **Step 1: Write the failing tests**

In `UserPrefsStoreTest.kt`, add (import `dev.gridiron.core.model.ESPN_POINTS_ALLOWED` and `dev.gridiron.core.model.PointsAllowedTier`):

```kotlin
    @Test
    fun `a profile saved before kicking and defense scoring gets the defaults once`() {
        file.writeText(
            """{"formatVersion": 1, "profiles": [{"id": "u1", "name": "Old league", "weights": {"PASS_TD": 6.0}}], "activeProfileId": "u1"}""",
        )
        val migrated = withStore { it.prefs.first() }.profiles.single()

        assertEquals(6.0, migrated.weight(ScoringRule.PASS_TD))
        for ((rule, value) in ScoringPresets.KICKING_AND_DEFENSE) assertEquals(value, migrated.weight(rule), rule.name)
        assertEquals(ESPN_POINTS_ALLOWED, migrated.pointsAllowedTiers)
    }

    @Test
    fun `after the migration, a zeroed rule and no tiers stay that way`() {
        file.writeText("""{"formatVersion": 1, "profiles": [{"id": "u1", "name": "Old league"}], "activeProfileId": "u1"}""")
        withStore { store ->
            store.update { p ->
                val old = p.profiles.single()
                p.copy(profiles = listOf(old.copy(weights = old.weights + (ScoringRule.FG_MISSED to 0.0) - ScoringRule.DST_SAFETY, pointsAllowedTiers = emptyList())))
            }
        }
        val reread = withStore { it.prefs.first() }.profiles.single()

        assertEquals(0.0, reread.weight(ScoringRule.FG_MISSED))
        assertEquals(0.0, reread.weight(ScoringRule.DST_SAFETY))
        assertEquals(emptyList<PointsAllowedTier>(), reread.pointsAllowedTiers)
        assertTrue(file.readText().contains("\"formatVersion\":2"), file.readText())
    }

    @Test
    fun `a profile's tiers survive a reopen, and invalid tiers are dropped with the profile kept`() {
        val tiers = listOf(PointsAllowedTier(0, 10.0), PointsAllowedTier(14, 1.0), PointsAllowedTier(21, 0.0))
        withStore { store -> store.update { it.copy(profiles = listOf(espnLeague.copy(pointsAllowedTiers = tiers))) } }
        assertEquals(tiers, withStore { it.prefs.first() }.profiles.single().pointsAllowedTiers)

        file.writeText(
            """{"formatVersion": 2, "profiles": [{"id": "u1", "name": "Odd", "pointsAllowed": [{"min": 7, "points": 3.0}]}]}""",
        )
        val odd = withStore { it.prefs.first() }.profiles.single()
        assertEquals("Odd", odd.name)
        assertEquals(emptyList<PointsAllowedTier>(), odd.pointsAllowedTiers)
    }
```

In `ScoringEditViewModelTest.kt`, add (import `dev.gridiron.core.model.PointsAllowedTier`):

```kotlin
    @Test
    fun editingTiersAndSavingPersistsThemInOrder() = runTest(dispatcher) {
        repo.duplicate(ScoringPresets.PPR.id, "Mine")
        val vm = ScoringEditViewModel("u1", repo)
        advanceUntilIdle()
        val drafts = (vm.state.value as EditState.Editing).tiers
        assertEquals(9, drafts.size)
        // Keep 0, 14 and 21 (changing 14's points), drop the rest.
        for (d in drafts.filter { it.min !in setOf("0", "14", "21") }) vm.onEvent(EditEvent.TierRemoved(d.key))
        val fourteen = (vm.state.value as EditState.Editing).tiers.single { it.min == "14" }
        vm.onEvent(EditEvent.TierChanged(fourteen.key, fourteen.copy(points = "2")))
        vm.onEvent(EditEvent.Save)
        advanceUntilIdle()

        assertEquals(
            listOf(PointsAllowedTier(0, 5.0), PointsAllowedTier(14, 2.0), PointsAllowedTier(21, 0.0)),
            prefs.current.profiles.single().pointsAllowedTiers,
        )
    }

    @Test
    fun oneTierMustStartAtZeroAndNoneRepeat() = runTest(dispatcher) {
        repo.duplicate(ScoringPresets.PPR.id, "Mine")
        val vm = ScoringEditViewModel("u1", repo)
        advanceUntilIdle()
        val tiers = (vm.state.value as EditState.Editing).tiers
        val zero = tiers.first()
        vm.onEvent(EditEvent.TierChanged(zero.key, zero.copy(min = "3")))
        var s = vm.state.value as EditState.Editing
        assertEquals("One tier must start at 0", s.errors[FieldKey.TierMin(zero.key)])
        assertNull(s.profile)

        vm.onEvent(EditEvent.TierChanged(zero.key, zero))
        vm.onEvent(EditEvent.TierChanged(tiers[2].key, tiers[2].copy(min = "1")))
        s = vm.state.value as EditState.Editing
        assertEquals("Another tier starts at 1", s.errors[FieldKey.TierMin(tiers[2].key)])

        vm.onEvent(EditEvent.TierChanged(tiers[2].key, tiers[2].copy(min = "x")))
        assertEquals("Whole points, 0–99", (vm.state.value as EditState.Editing).errors[FieldKey.TierMin(tiers[2].key)])
    }

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
        assertEquals(3.0, saved.weight(ScoringRule.FG_MADE_0_39), 0.0)
    }

    @Test
    fun resetToPresetRestoresTheTiers() = runTest(dispatcher) {
        repo.duplicate(ScoringPresets.PPR.id, "Mine")
        val vm = ScoringEditViewModel("u1", repo)
        advanceUntilIdle()
        (vm.state.value as EditState.Editing).tiers.forEach { vm.onEvent(EditEvent.TierRemoved(it.key)) }
        vm.onEvent(EditEvent.ResetToPreset)
        assertEquals(ScoringPresets.PPR.pointsAllowedTiers, (vm.state.value as EditState.Editing).profile!!.pointsAllowedTiers)
    }
```

In `ScoringScreenTest.kt`, add (imports: `androidx.compose.ui.test.hasTestTag`, `androidx.compose.ui.test.performScrollToNode`):

```kotlin
    @Test
    fun tiersLight() {
        val state = custom.toEditing(readOnly = false)
        val events = mutableListOf<EditEvent>()
        compose.setContent { GridironTheme(darkTheme = false) { ScoringEditScreen(state, { events += it }, {}) } }

        compose.onNodeWithTag("editorFields").performScrollToNode(hasTestTag("addTier"))
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/scoring_4_tiers.png")
        compose.onNodeWithTag("tier:remove:8").performClick() // the 46+ tier
        compose.onNodeWithTag("addTier").performClick()

        assertEquals(listOf(EditEvent.TierRemoved(8), EditEvent.TierAdded), events)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:datastore:test :feature:scoring:testDebugUnitTest`
Expected: FAIL to compile with "Unresolved reference: tiers", "Unresolved reference: TierRemoved"; the migration tests fail on missing K/DST weights.

- [ ] **Step 3: Store the tiers, and migrate once**

In `UserPrefsJson.kt`:
- `UserPrefsDto.formatVersion` defaults to `1`, not `FORMAT_VERSION`. A file with no version is the oldest shape. `toDto()` writes `formatVersion = FORMAT_VERSION` explicitly.
- `FORMAT_VERSION` becomes `2`, with the KDoc `/** 2: profiles carry points-allowed tiers, and version-1 profiles gain the kicking and team-defense defaults once. */`.
- `ProfileDto` gains `val pointsAllowed: List<TierDto> = emptyList(),` after `bonuses`, and add `@Serializable internal data class TierDto(val min: Int, val points: Double)`.

In `toDomain()`, build each profile with its tiers and, for an old file, the defaults:

```kotlin
    val migrating = formatVersion < 2
    val profiles = profiles.mapNotNull { p ->
        orNull {
            val weights = p.weights.mapNotNull { (k, v) -> ScoringRule.entries.firstOrNull { it.name == k }?.let { it to v } }.toMap()
            ScoringProfile(
                id = p.id,
                name = p.name,
                // Version 1 predates kicking and team defense: give them the presets' values, once.
                weights = if (migrating) ScoringPresets.KICKING_AND_DEFENSE + weights else weights,
                receptionByPosition = …, // unchanged
                yardageBonuses = …, // unchanged
                basedOn = p.basedOn,
                pointsAllowedTiers = if (migrating) ESPN_POINTS_ALLOWED else tiersOrNone(p.pointsAllowed),
            )
        }
    }
```

and add:

```kotlin
/** A profile's stored tiers, or none when they don't start at 0 and rise: a bad list must not cost the whole profile. */
private fun tiersOrNone(stored: List<TierDto>): List<PointsAllowedTier> {
    val tiers = stored.mapNotNull { orNull { PointsAllowedTier(it.min, it.points) } }
    val valid = tiers.isEmpty() || (tiers.first().min == 0 && tiers.zipWithNext().all { (a, b) -> a.min < b.min })
    return if (valid && tiers.size == stored.size) tiers else emptyList()
}
```

In `toDto()`'s `ProfileDto(...)`, add `pointsAllowed = p.pointsAllowedTiers.map { TierDto(it.min, it.points) },`. Import `dev.gridiron.core.model.ESPN_POINTS_ALLOWED`, `PointsAllowedTier` and `ScoringPresets`.

- [ ] **Step 4: The tier editor**

In `ScoringEditViewModel.kt`:

```kotlin
/** One points-allowed tier's fields as typed. */
internal data class TierDraft(val key: Int, val min: String, val points: String)
```

`FieldKey` gains `data class TierMin(val key: Int) : FieldKey` and `data class TierPoints(val key: Int) : FieldKey`. `EditState.Editing` gains `val tiers: ImmutableList<TierDraft>,` after `bonuses`. `EditEvent` gains `data object TierAdded : EditEvent`, `data class TierChanged(val key: Int, val draft: TierDraft) : EditEvent` and `data class TierRemoved(val key: Int) : EditEvent`.

In `validate()`, before `if (errors.isNotEmpty())`:

```kotlin
    val starts = tiers.map { it.min.trim().toIntOrNull()?.takeIf { m -> m in 0..99 } }
    val tiers = tiers.mapIndexedNotNull { i, t ->
        val min = starts[i]
        when {
            min == null -> errors[FieldKey.TierMin(t.key)] = "Whole points, 0–99"
            starts.take(i).contains(min) -> errors[FieldKey.TierMin(t.key)] = "Another tier starts at $min"
        }
        val points = (DecimalInput.parse(t.points) as? DecimalInput.Result.Value)?.value
        if (points == null) errors[FieldKey.TierPoints(t.key)] = NUMBER
        if (min == null || points == null) null else PointsAllowedTier(min, points)
    }.sortedBy { it.min }
    // Every game must land in a tier, so one has to start at 0. The first row carries the message.
    if (tiers.isNotEmpty() && tiers.first().min != 0) errors[FieldKey.TierMin(this@validate.tiers.first().key)] = "One tier must start at 0"
```

and pass `pointsAllowedTiers = tiers,` in the final `original.copy(...)`. (Rows may be typed in any order; the profile keeps them sorted.)

In `toEditing`, add:

```kotlin
    tiers = pointsAllowedTiers.mapIndexed { i, t -> TierDraft(i, t.min.toString(), DecimalInput.format(t.points)) }.toImmutableList(),
```

In `onEvent`'s `when`:

```kotlin
            EditEvent.TierAdded -> {
                val next = (s.tiers.mapNotNull { it.min.trim().toIntOrNull() }.maxOrNull() ?: -7) + 7
                s.copy(tiers = (s.tiers + TierDraft(nextKey++, next.toString(), "0")).toImmutableList())
            }
            is EditEvent.TierChanged -> s.copy(tiers = s.tiers.map { if (it.key == event.key) event.draft else it }.toImmutableList())
            is EditEvent.TierRemoved -> s.copy(tiers = s.tiers.filterNot { it.key == event.key }.toImmutableList())
```

(`ResetToPreset` already rebuilds from the preset through `toEditing`, so it restores the tiers.)

In `ScoringEditScreen.kt`, inside the `LazyColumn`, right after the `ScoringGroup.entries.forEach { … }` block (Team defense is the last group, so the tiers follow its rules):

```kotlin
            item(key = "tiersHeader") {
                GroupHeader("Points allowed")
                Text(
                    "Each tier runs from its start up to the next tier's. 0, 1 and 7 mean 0, 1–6 and 7–13 points allowed.",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(state.tiers, key = { "tier:${it.key}" }) { draft ->
                TierRow(
                    draft = draft,
                    errors = state.errors,
                    enabled = !state.readOnly,
                    onChange = { onEvent(EditEvent.TierChanged(draft.key, it)) },
                    onRemove = { onEvent(EditEvent.TierRemoved(draft.key)) },
                )
            }
            if (!state.readOnly) {
                item(key = "addTier") {
                    TextButton(onClick = { onEvent(EditEvent.TierAdded) }, modifier = Modifier.padding(horizontal = 8.dp).testTag("addTier")) {
                        Text("+ Add tier")
                    }
                }
            }
```

and add below `BonusCard`:

```kotlin
@Composable
private fun TierRow(
    draft: TierDraft,
    errors: Map<FieldKey, String>,
    enabled: Boolean,
    onChange: (TierDraft) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft.min,
            onValueChange = { onChange(draft.copy(min = it)) },
            label = { Text("From") },
            suffix = { Text("pts") },
            singleLine = true,
            enabled = enabled,
            isError = errors[FieldKey.TierMin(draft.key)] != null,
            supportingText = errors[FieldKey.TierMin(draft.key)]?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f).testTag("tier:min:${draft.key}"),
        )
        OutlinedTextField(
            value = draft.points,
            onValueChange = { onChange(draft.copy(points = it)) },
            label = { Text("Scores") },
            singleLine = true,
            enabled = enabled,
            isError = errors[FieldKey.TierPoints(draft.key)] != null,
            supportingText = errors[FieldKey.TierPoints(draft.key)]?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f).testTag("tier:points:${draft.key}"),
        )
        if (enabled) TextButton(onClick = onRemove, modifier = Modifier.testTag("tier:remove:${draft.key}")) { Text("Remove") }
    }
}
```

- [ ] **Step 5: Run the tests, and record the new screenshot**

Run: `./gradlew :core:datastore:test :feature:scoring:testDebugUnitTest`
Expected: PASS.

Run: `./gradlew :feature:scoring:recordRoborazziDebug`, then look at `scoring_2_editor_dark.png` and `scoring_4_tiers.png`. The editor now lists Kicking and Team defense, then the tiers, with nothing clipped at phone width. Commit the recorded images only if the repo tracks them.

- [ ] **Step 6: Commit**

```bash
git add core/datastore feature/scoring
git commit -m "scoring: save points-allowed tiers, migrate older profiles once, and edit tiers in the scoring editor"
```

### Task 6: K and D/ST chips on the Grid

**Files:**
- Modify: `core/statquery/src/main/kotlin/dev/gridiron/core/statquery/StatQuerySpec.kt`, `StatQueryBuilder.kt` (`excludedPositions`)
- Modify: `core/data/src/main/kotlin/dev/gridiron/core/data/StatPack.kt`, `StatsRepository.kt`, `SampleThreshold.kt`
- Modify: `feature/players/src/main/kotlin/dev/gridiron/feature/players/GridViewModel.kt`, `GridScreen.kt`
- Test: `core/statquery/src/test/kotlin/dev/gridiron/core/statquery/StatQueryBuilderTest.kt`; `core/data/src/test/kotlin/dev/gridiron/core/data/StatsRepositoryTest.kt`; `feature/players/src/test/kotlin/dev/gridiron/feature/players/GridViewModelTest.kt` (`GridReduceTest`), `GridScreenTest.kt`

**Interfaces:**
- Consumes: Task 4's `Position.DST` and K and D/ST `StatColumn`s.
- Produces:
  - `StatQuerySpec.excludedPositions: Set<Position>`. Players with no position are kept.
  - `StatPack.KICKING` ("Kicking": `FANTASY_POINTS`, `FG_MADE`, `FG_ATT`, `FG_MADE_50`, `XP_MADE`, `XP_ATT`, population `FG_ATT`) and `StatPack.DEFENSE` ("Defense": `FANTASY_POINTS`, `POINTS_ALLOWED`, `DST_SACKS`, `DST_INTERCEPTIONS`, `DST_FUMBLE_RECOVERIES`, `DST_TDS`, `DST_SAFETIES`, no population), both sorted by fantasy points.
  - `PositionFilter.K` ("K") and `PositionFilter.DST` ("D/ST"), after `FLEX`. `PositionFilter.packs: List<StatPack>` is the packs a chip offers: its own for K and D/ST, the offense's for the rest. `StatPack.unit: PositionFilter?` is the chip a K or D/ST pack belongs to.
  - The Grid's spec leaves K and D/ST out unless its pack or chip is theirs, and ignores the snap-share chip there (kickers and defenses have no offensive snaps).
  - `SampleThreshold`: `FG_ATT` at 1 a week ("field goal tries").

- [ ] **Step 1: Write the failing tests**

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

In `StatsRepositoryTest.kt`, add (it runs against the rebuilt `GRIDIRON_STATS_DB`; import `dev.gridiron.core.statquery.SqlQuery` if the file doesn't already):

```kotlin
    @Test
    fun `the K and D-ST chips list kickers and team defenses with their own packs`() = runTest {
        val season = catalog.season(2025)
        val kickers = repo.grid(GridRequest(season, season.defaultWeeks, StatPack.KICKING, positions = PositionFilter.K), catalog)
        val defenses = repo.grid(GridRequest(season, season.defaultWeeks, StatPack.DEFENSE, positions = PositionFilter.DST), catalog)

        assertTrue(kickers.rows.size in 25..45, "${kickers.rows.size} kickers")
        assertTrue(kickers.rows.all { it.position == "K" })
        assertEquals(32, defenses.rows.size)
        assertTrue(defenses.rows.all { it.position == "DST" && it.name.endsWith(" D/ST") })
        val points = defenses.rows.map { it.cells.first().text }
        assertTrue(points.isNotEmpty() && points.none { it.isBlank() }, "$points")
    }

    @Test
    fun `every other chip, a search and a roster in the All view leave kickers and team defenses out`() = runTest {
        val season = catalog.season(2025)
        val special = executor.query(
            SqlQuery("SELECT player_id FROM player WHERE position IN ('K', 'DST')", emptyList()),
        ) { it.text(0) }.toSet()
        assertTrue(special.any { it.startsWith("DST_") } && special.any { !it.startsWith("DST_") }, "no kickers or D/STs in the database")

        for (positions in PositionFilter.entries - PositionFilter.K - PositionFilter.DST) {
            val page = repo.grid(GridRequest(season, season.defaultWeeks, StatPack.FANTASY, positions = positions), catalog)
            assertTrue(page.rows.none { it.playerId in special }, "$positions")
        }
        val roster = repo.grid(GridRequest(season, season.defaultWeeks, StatPack.FANTASY, onlyPlayers = special), catalog)
        val search = repo.grid(GridRequest(season, season.defaultWeeks, StatPack.FANTASY, name = "dst"), catalog)
        assertEquals(emptyList<String>(), roster.rows.map { it.playerId })
        assertTrue(search.rows.none { it.playerId in special }, "${search.rows.map { it.playerId }}")
        assertEquals(0, repo.count(GridRequest(season, season.defaultWeeks, StatPack.FANTASY, onlyPlayers = special)))

        // The same roster under the K chip shows its kickers.
        val rosterKickers = repo.grid(GridRequest(season, season.defaultWeeks, StatPack.KICKING, positions = PositionFilter.K, onlyPlayers = special), catalog)
        assertTrue(rosterKickers.rows.isNotEmpty() && rosterKickers.rows.all { it.position == "K" })
    }
```

`every pack renders a complete, sorted page` iterates `StatPack.entries` with the default All chip; the new packs bring their own chip, so it covers them without change. If it asserts a minimum row count, kickers and D/STs still clear it.

In `GridViewModelTest.kt`'s `GridReduceTest`, add:

```kotlin
    @Test
    fun `the K and D-ST chips bring their own pack, and an offense chip brings the offense back`() {
        val kickers = reduce(GridEvent.PositionsSelected(PositionFilter.K))
        assertEquals(StatPack.KICKING, kickers.pack)
        assertEquals(StatColumn.FANTASY_POINTS, kickers.sort)

        val defenses = reduce(GridEvent.PositionsSelected(PositionFilter.K), GridEvent.PositionsSelected(PositionFilter.DST))
        assertEquals(StatPack.DEFENSE, defenses.pack)

        val back = reduce(GridEvent.PositionsSelected(PositionFilter.DST), GridEvent.PositionsSelected(PositionFilter.RB))
        assertEquals(StatPack.FANTASY, back.pack)
        assertEquals(PositionFilter.RB, back.positions)

        // Between offense chips the pack stays.
        assertEquals(StatPack.OPPORTUNITY, reduce(GridEvent.PositionsSelected(PositionFilter.WR)).pack)
    }

    @Test
    fun `a K or D-ST pack brings its chip`() {
        assertEquals(PositionFilter.DST, reduce(GridEvent.PackSelected(StatPack.DEFENSE)).positions)
        val offense = reduce(GridEvent.PackSelected(StatPack.KICKING), GridEvent.PackSelected(StatPack.RUSHING))
        assertEquals(PositionFilter.ALL, offense.positions)
    }

    @Test
    fun `each chip offers its packs`() {
        assertEquals(listOf(StatPack.KICKING), PositionFilter.K.packs)
        assertEquals(listOf(StatPack.DEFENSE), PositionFilter.DST.packs)
        assertEquals(StatPack.entries - StatPack.KICKING - StatPack.DEFENSE, PositionFilter.FLEX.packs)
    }
```

In `GridScreenTest.kt`, add a test built like its neighbors, with the request set to `positions = PositionFilter.DST, pack = StatPack.DEFENSE`. It asserts the pack row shows "Defense" and not "Opportunity", that the snap chip (`chip:snaps`, or whatever tag `SnapChip` carries; add `testTag("chip:snaps")` if it has none) doesn't exist, and captures `build/outputs/roborazzi/grid_dst.png`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:statquery:test :feature:players:testDebugUnitTest`
Expected: FAIL to compile with "No parameter with name 'excludedPositions'", "Unresolved reference: KICKING" and "Unresolved reference: packs".

- [ ] **Step 3: Excluded positions in the query**

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

- [ ] **Step 4: The packs and chips**

In `StatPack.kt`, import the eleven new columns and add to `StatPack` after `EFFICIENCY(...)`:

```kotlin
    KICKING(
        "Kicking",
        listOf(FANTASY_POINTS, FG_MADE, FG_ATT, FG_MADE_50, XP_MADE, XP_ATT),
        FANTASY_POINTS,
        FG_ATT,
    ),
    DEFENSE(
        "Defense",
        listOf(FANTASY_POINTS, POINTS_ALLOWED, DST_SACKS, DST_INTERCEPTIONS, DST_FUMBLE_RECOVERIES, DST_TDS, DST_SAFETIES),
        FANTASY_POINTS,
        null,
    ),
    ;

    /** The chip a kicker or D/ST pack belongs to; null for the offense's packs. */
    public val unit: PositionFilter?
        get() = when (this) {
            KICKING -> PositionFilter.K
            DEFENSE -> PositionFilter.DST
            else -> null
        }
```

`PositionFilter` gains, after `FLEX("FLEX", Position.FLEX),`:

```kotlin
    K("K", setOf(Position.K)),
    DST("D/ST", setOf(Position.DST)),
    ;

    /** The packs this chip offers: kickers and D/STs have their own, and every other chip shares the offense's. */
    public val packs: List<StatPack>
        get() = StatPack.entries.filter { it.unit == this || (it.unit == null && this != K && this != DST) }
```

Change the `PositionFilter` KDoc (add one if it has none) to `/** The Grid's position chips. All and the offense's chips never list kickers or D/STs; K and D/ST list only theirs. */`.

In `SampleThreshold.kt`, add `StatColumn.FG_ATT to 1` to `PER_WEEK` and `StatColumn.FG_ATT to "field goal tries"` to `NOUN`.

In `StatsRepository.kt`, add above the class:

```kotlin
/** Kickers and team defenses: listed only under their own chips (or their own packs), never among the offense. */
private val UNITS: Set<Position> = setOf(Position.K, Position.DST)
```

and in `spec(...)`:

```kotlin
        // A K or D/ST pack is its chip's, even if a request pairs it with another chip.
        val chip = request.pack.unit ?: request.positions
        val units = chip == PositionFilter.K || chip == PositionFilter.DST
        val snap = request.minSnapShare?.takeUnless { units }?.let { Filter(StatColumn.SNAP_SHARE, Condition.AtLeast(it)) }
```

replacing the existing `val snap = …` line. Then set `positions = chip.positions,` and add `excludedPositions = if (units) emptySet() else UNITS,` after it. Import `dev.gridiron.core.model.Position` if needed.

In `GridViewModel.reduce`:

```kotlin
            // A new pack brings its own lead stat as the sort, and a K or D/ST pack its chip.
            is GridEvent.PackSelected -> r.copy(
                pack = event.pack,
                positions = event.pack.unit ?: r.positions.takeIf { event.pack in it.packs } ?: PositionFilter.ALL,
                sort = event.pack.defaultSort,
                direction = GridRequest.defaultDirection(event.pack.defaultSort),
            )
            // A chip keeps the pack when it offers it; otherwise it brings its first.
            is GridEvent.PositionsSelected -> if (r.pack in event.positions.packs) {
                r.copy(positions = event.positions)
            } else {
                val pack = event.positions.packs.first()
                r.copy(positions = event.positions, pack = pack, sort = pack.defaultSort, direction = GridRequest.defaultDirection(pack.defaultSort))
            }
```

In `GridScreen.kt`, the pack chip row iterates `r.positions.packs` instead of `StatPack.entries`, and the snap chip is hidden under K and D/ST: wrap `SnapChip(…)` in `if (r.positions != PositionFilter.K && r.positions != PositionFilter.DST)`. Give `SnapChip`'s `FilterChip` `Modifier.testTag("chip:snaps")` if it has no tag.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:statquery:test :core:data:test :feature:players:testDebugUnitTest`
Expected: PASS.

Then on real data (the database rebuilt in Task 2):

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:statquery:test :core:data:test --tests "dev.gridiron.core.data.StatsRepositoryTest"`
Expected: PASS, both new tests included.

Re-record the Grid's screenshots and look at them: `./gradlew :feature:players:recordRoborazziDebug`. The position chip row now ends with K and D/ST, and nothing else in the existing screenshots changes.

- [ ] **Step 6: Commit**

```bash
git add core/statquery core/data feature/players
git commit -m "grid: K and D/ST chips with Kicking and Defense packs; every other chip leaves them out"
```

### Task 7: Kicker inputs and model

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
            db.week("DST_AAA", 2025, 1, "AAA", "dst_sacks" to 3.0, "points_allowed" to 17.0)

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

### Task 8: D/ST model

**Files:**
- Create: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Defense.kt`
- Modify: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/ForecastConstants.kt`
- Test: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/DefenseTest.kt` (new)

**Interfaces:**
- Consumes: `ewma`, `shrink`, `capAround`; `PlayerGame`.
- Produces:
  - `DST_STATS = listOf("dst_sacks", "dst_interceptions", "dst_fumble_recoveries", "dst_tds", "dst_safeties")`.
  - `paSpread(misses: List<Double>): Double`: the spread of real team scores around their implied points, which the D/ST's projected points allowed carry as their variance (Task 9).
  - `DefenseLeague(perGame: Map<String, Double>, pointsAllowed: Double)`, `defenseLeague(games: List<PlayerGame>): DefenseLeague?`.
  - `unitRate(values: List<Double>, league: Double, k: Double): Double`; `opponentFactor(allowed: List<Double>, league: Double): Double`.
  - `DefenseStages(baseline, afterMatchup, final)`, each a `Map<String, Double>` of `DST_STATS` and `points_allowed` (the projected mean); `defenseStages(own, ownAllowed, factors, opponentScores, league, implied: Double?): DefenseStages`. No tiers: the profile scores them on the phone.
  - Constants `K.DST_HALF_LIFE`, `K.DST_K`, `K.DST_PA_K`, `K.DST_OPP_K`, `K.DST_CAP`, `K.PA_SD_DEFAULT`, `K.PA_SD_MIN_GAMES`.

- [ ] **Step 1: Write the failing tests**

`core/forecast/src/test/kotlin/dev/gridiron/core/forecast/DefenseTest.kt`:

```kotlin
package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DefenseTest {
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

        val stages = defenseStages(own, 20.0, mapOf("dst_sacks" to 1.2), opponentScores = 33.0, league = league, implied = 17.0)

        assertEquals(2.0, stages.baseline.getValue("dst_sacks"), 1e-12)
        assertEquals(2.4, stages.afterMatchup.getValue("dst_sacks"), 1e-12)
        assertEquals(2.0, stages.afterMatchup.getValue("dst_interceptions"), 1e-12)
        assertEquals(2.4, stages.final.getValue("dst_sacks"), 1e-12)
        assertEquals(20.0, stages.baseline.getValue("points_allowed"), 1e-12)
        // 20 allowed against an offense scoring 33 where the league scores 22: 30.
        assertEquals(30.0, stages.afterMatchup.getValue("points_allowed"), 1e-12)
        assertEquals(17.0, stages.final.getValue("points_allowed"), 1e-12)
        assertEquals(DST_STATS.toSet() + "points_allowed", stages.final.keys)

        val noLine = defenseStages(own, 20.0, emptyMap(), 33.0, league, implied = null)
        assertEquals(noLine.afterMatchup, noLine.final)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:forecast:test --tests "dev.gridiron.core.forecast.DefenseTest"`
Expected: FAIL to compile with "Unresolved reference: paSpread" and "Unresolved reference: defenseStages".

- [ ] **Step 3: The constants**

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
    // Points allowed are about normal around their mean. Its spread, stored as the projection's variance,
    // is real team scores' spread around their implied points, measured once this many lined team-games
    // are in; about 10 points before that.
    const val PA_SD_DEFAULT = 10.0
    const val PA_SD_MIN_GAMES = 100
```

- [ ] **Step 4: The D/ST model**

`core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Defense.kt`:

```kotlin
package dev.gridiron.core.forecast

import kotlin.math.sqrt

/** The D/ST stats projected as rates. Points allowed are projected beside them, as a mean and a spread. */
internal val DST_STATS: List<String> = listOf("dst_sacks", "dst_interceptions", "dst_fumble_recoveries", "dst_tds", "dst_safeties")

internal const val POINTS_ALLOWED: String = "points_allowed"

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

/** A D/ST's projection, stage by stage: its own rates and points allowed, then the matchup, then the line. */
internal class DefenseStages(val baseline: Map<String, Double>, val afterMatchup: Map<String, Double>, val final: Map<String, Double>)

/**
 * [own] per-game rates and [ownAllowed] points allowed are the baseline. The
 * matchup multiplies each stat by its opponent [factors] and scales points
 * allowed by how the opponent scores ([opponentScores]) against the league's
 * average. Game script replaces points allowed with the opponent's [implied]
 * points when a line is posted (spec §6). Points allowed are a mean; the
 * profile's tiers score them on the phone, in expectation.
 */
internal fun defenseStages(
    own: Map<String, Double>,
    ownAllowed: Double,
    factors: Map<String, Double>,
    opponentScores: Double,
    league: DefenseLeague,
    implied: Double?,
): DefenseStages {
    val matchupPoints = if (league.pointsAllowed > 0.0) ownAllowed * opponentScores / league.pointsAllowed else ownAllowed
    val matched = own.mapValues { (stat, rate) -> rate * (factors[stat] ?: 1.0) }
    return DefenseStages(
        baseline = own + (POINTS_ALLOWED to ownAllowed),
        afterMatchup = matched + (POINTS_ALLOWED to matchupPoints),
        final = matched + (POINTS_ALLOWED to (implied ?: matchupPoints)),
    )
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:forecast:test`
Expected: PASS, `DefenseTest` 5/5.

- [ ] **Step 6: Commit**

```bash
git add core/forecast
git commit -m "forecast: the D/ST model (own rates times the opponent's, points allowed from the opponent's implied points)"
```

### Task 9: Kickers and D/STs in the walk-forward

**Files:**
- Create: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Units.kt`
- Modify: `core/forecast/build.gradle.kts`, `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Projector.kt`, `GameScript.kt`, `Kinds.kt`, `ForecastConstants.kt`
- Test: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/ForecastEngineTest.kt`, `KindsTest.kt`; `core/data/src/test/kotlin/dev/gridiron/core/data/ProjectionsContractTest.kt`

**Interfaces:**
- Consumes: Tasks 7 and 8 (`ForecastInputs.units`, `unitHistory`, the kicker and D/ST models); Task 4's `ScoringPresets.PPR.expectedPointsAllowedPoints`; `ProjectionSink`; `Game.impliedPoints(team)`, `Game.opponentOf(team)`; `varianceFor`; `referencePoints`.
- Produces:
  - `internal enum class WeekKind { PAST, UPCOMING, REST }` (top level in `Projector.kt`, was private).
  - `averageImplied(games: List<Game>, season: Int): Double` (`GameScript.kt`), used by both projectors.
  - `UnitProjector(inputs, gameOf, sink)` with `week(season, week, kind, ros)` and `rest(season, week, ros)`.
  - `referencePoints` also scores K and D/ST stats with the presets' values. A D/ST's reference points add the preset tiers' expected points for its projected points allowed and spread (`UnitProjector` does this, since only it knows the spread).
  - `K.EMPIRICAL_CV` gains `"K" to 0.52` and `"DST" to 0.85`. `FORECAST_VERSION` = 4.
  - Stored rows:
    - A kicker's `fg_made_0_39`, `fg_made_40_49`, `fg_made_50`, `fg_missed`, `xp_made`, `xp_missed`.
    - A D/ST's `DST_STATS`, `points_allowed` (mean: the projected points allowed; variance: the measured spread squared) and `g` (mean 1, variance 0). Rest of season sums each, so its `g` counts the games left and its `points_allowed` mean and variance divide back to one game's.
  - Factors: a kicker's `game_script` when his game has a line; a D/ST's `matchup` always and `game_script` when the line is posted.

- [ ] **Step 1: Write the failing tests**

In `ForecastEngineTest.kt`, give `league(...)` a `units: Boolean = false` parameter and a `dstA2025Week2Sacks: Double = 2.0` parameter, and add unit players and weeks when `units` is true. After the player loop's `db.player("WR_$letter", "WR", team)`:

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
            "dst_sacks" to sacks, "dst_interceptions" to 1.0, "points_allowed" to allowed,
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
    fun `a team defense's points allowed carry the measured spread, one game at a time`() {
        league("allowed.db", units = true).use { db ->
            run(db)
            val rows = db.query(
                "SELECT player_id, week, stage, mean, variance FROM player_week_projection " +
                    "WHERE player_id LIKE 'DST%' AND metric_id = 'points_allowed'",
            )
            assertTrue(rows.size >= 8, "$rows")
            // The synthetic league has too few lined games to measure the spread: the default.
            for (row in rows) {
                assertTrue(row[3]!!.toDouble() in 5.0..40.0, "$row")
                assertEquals(K.PA_SD_DEFAULT * K.PA_SD_DEFAULT, row[4]!!.toDouble(), 1e-9, "$row")
            }
            val games = db.query("SELECT DISTINCT mean, variance FROM player_week_projection WHERE player_id LIKE 'DST%' AND metric_id = 'g'")
            assertEquals(listOf(listOf("1.0", "0.0")), games)
            assertEquals(emptyList<List<String?>>(), db.query("SELECT metric_id FROM player_week_projection WHERE metric_id LIKE 'pa\\_%' ESCAPE '\\'"))
        }
    }

    @Test
    fun `rest of season for kickers and defenses sums the remaining games and skips byes`() {
        league("unit-ros.db", units = true).use { db ->
            run(db)
            // BBB is on bye in week 4: its rest of season is week 3 alone.
            assertEquals(finalMean(db, "DST_BBB", 3, "dst_sacks"), rosMean(db, "DST_BBB", "dst_sacks"), 1e-9)
            assertEquals(finalMean(db, "K_B", 3, "xp_made"), rosMean(db, "K_B", "xp_made"), 1e-9)
            // AAA plays weeks 3 and 4: two games, whose points allowed sum.
            assertTrue(rosMean(db, "DST_AAA", "dst_sacks") > 1.5 * finalMean(db, "DST_AAA", 3, "dst_sacks"))
            assertEquals(2.0, rosMean(db, "DST_AAA", "g"), 1e-9)
            assertEquals(1.0, rosMean(db, "DST_BBB", "g"), 1e-9)
            assertTrue(rosMean(db, "DST_AAA", "points_allowed") > 1.5 * finalMean(db, "DST_AAA", 3, "points_allowed"))
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
    fun `reference points score kickers' and defenses' stats with the presets' values`() {
        assertEquals(3.0 + 4.0 + 5.0 - 1.0 + 2.0 - 1.0, referencePoints(mapOf("fg_made_0_39" to 1.0, "fg_made_40_49" to 1.0, "fg_made_50" to 1.0, "fg_missed" to 1.0, "xp_made" to 2.0, "xp_missed" to 1.0)), 1e-12)
        // Points allowed need their spread, so UnitProjector adds the tiers; referencePoints ignores them.
        assertEquals(2.0 + 2.0 + 2.0 + 6.0 + 2.0, referencePoints(mapOf("dst_sacks" to 2.0, "dst_interceptions" to 1.0, "dst_fumble_recoveries" to 1.0, "dst_tds" to 1.0, "dst_safeties" to 1.0, "points_allowed" to 20.0, "g" to 1.0)), 1e-12)
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
                val allowed = d.components.single { it.metricId == "points_allowed" }
                assertTrue(allowed.mean in 10.0..40.0 && allowed.variance in 25.0..400.0, "${d.name}: $allowed")
                assertEquals(1.0, d.components.single { it.metricId == "g" }.mean, 0.0, d.name)
            }
            // Scored as the phone will (Task 10's projectedScore): tiers in expectation, never the tier of the mean.
            fun points(p: ListedProjection): Double {
                val means = p.components.filter { it.metricId != "points_allowed" }.associate { Component(it.metricId) to it.mean }
                val allowed = p.components.firstOrNull { it.metricId == "points_allowed" }
                return score(means, ScoringPresets.PPR, Position.fromCode(p.position!!)) +
                    (allowed?.let { ScoringPresets.PPR.expectedPointsAllowedPoints(it.mean, sqrt(it.variance)) } ?: 0.0)
            }
            fun top(position: String) = listed.filter { it.position == position }.map(::points).sortedDescending().take(12).average()
            // Loose bands under the default kicking and D/ST scoring: a broken model lands far outside them.
            assertTrue(top("K") in 6.0..12.0, "K1-12 average ${top("K")}")
            assertTrue(top("DST") in 4.0..14.0, "DST1-12 average ${top("DST")}")
        }
    }
```

(imports: `dev.gridiron.core.projections.ListedProjection`, `dev.gridiron.core.projections.score`, `dev.gridiron.core.statquery.Bind`, `dev.gridiron.core.statquery.Component`, `kotlin.math.sqrt`).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:forecast:test`
Expected: FAIL: the new unit tests find no `K_`/`DST_` rows (`expected: <[baseline, final]> but was: <[]>`), and `KindsTest`'s new test gets 0.

- [ ] **Step 3: Constants, reference points and the shared league average**

In `core/forecast/build.gradle.kts`, add to `dependencies` (after `api(libs.androidx.sqlite)`), so the forecast scores D/STs' tiers with the presets':

```kotlin
    implementation(projects.core.model)
```

In `ForecastConstants.kt`, set `FORECAST_VERSION` to 4 and extend `EMPIRICAL_CV`:

```kotlin
    // Layer 7, spread: sigma = a * mu^0.75, CV at mu = 10 by position (projections.py EMPIRICAL_CV; K and DST from spec §6).
    const val VARIANCE_EXPONENT = 0.75
    val EMPIRICAL_CV: Map<String, Double> = mapOf("QB" to 0.40, "RB" to 0.57, "WR" to 0.70, "TE" to 0.77, "K" to 0.52, "DST" to 0.85)
```

In `Kinds.kt`, extend `referencePoints`'s KDoc with "Kickers' and D/STs' stats use the presets' values (`ScoringPresets.KICKING_AND_DEFENSE`). Points allowed need their spread to score, so `UnitProjector` adds their tiers." and its expression:

```kotlin
    return 0.04 * v("passing_yards") + 4 * v("passing_tds") - 2 * v("interceptions") +
        0.1 * v("rushing_yards") + 6 * v("rushing_tds") +
        v("receptions") + 0.1 * v("receiving_yards") + 6 * v("receiving_tds") +
        2 * (v("passing_2pt") + v("rushing_2pt") + v("receiving_2pt")) - 2 * v("fumbles_lost") +
        3 * v("fg_made_0_39") + 4 * v("fg_made_40_49") + 5 * v("fg_made_50") - v("fg_missed") + v("xp_made") - v("xp_missed") +
        v("dst_sacks") + 2 * (v("dst_interceptions") + v("dst_fumble_recoveries") + v("dst_safeties")) + 6 * v("dst_tds")
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

import dev.gridiron.core.model.ScoringPresets
import java.util.Locale
import kotlin.math.ln

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

/**
 * One unit's projection for one game, stage by stage, with the waterfall's
 * notes (null: no such factor). [sd] is a D/ST's points-allowed spread; a
 * kicker's is 0 (he has no points allowed).
 */
private class UnitStages(
    val baseline: Map<String, Double>,
    val afterMatchup: Map<String, Double>,
    val final: Map<String, Double>,
    val matchupNote: String?,
    val scriptNote: String?,
    val sd: Double = 0.0,
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
                WeekKind.PAST -> if (points(stages.final, stages.sd) >= K.PAST_WEEK_MIN_POINTS) emit(u, season, week, "final", stages.final, stages.sd)
                WeekKind.UPCOMING -> if (points(stages.final, stages.sd) >= K.UPCOMING_MIN_POINTS) {
                    emit(u, season, week, "baseline", stages.baseline, stages.sd)
                    emit(u, season, week, "final", stages.final, stages.sd)
                    stages.matchupNote?.let { sink.factor(id, season, week, "matchup", logRatio(stages.afterMatchup, stages.baseline, stages.sd), it) }
                    stages.scriptNote?.let { sink.factor(id, season, week, "game_script", logRatio(stages.final, stages.afterMatchup, stages.sd), it) }
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
            val s = defenseStages(u.own, u.ownAllowed, factors, opponentScores, league, implied)
            UnitStages(
                s.baseline, s.afterMatchup, s.final,
                matchupNote = defenseNote(opponent, against, league),
                scriptNote = implied?.let { impliedNote("$opponent implied", it, state.leagueImplied) },
                sd = state.sd,
            )
        }
    }

    private fun emit(u: TeamUnit, season: Int, week: Int, stage: String, components: Map<String, Double>, sd: Double) {
        val cv = K.EMPIRICAL_CV.getValue(u.player.position)
        for ((metric, mean) in withGame(components)) {
            check(mean.isFinite()) { "${u.player.playerId}'s $season week $week $metric projection is $mean" }
            if (mean > 0.0) sink.projection(u.player.playerId, season, week, metric, stage, mean, variance(metric, mean, cv, sd))
        }
    }

    private fun addRest(state: UnitWeek, u: TeamUnit, season: Int, week: Int, ros: MutableMap<Pair<String, String>, DoubleArray>) {
        val game = gameOf[Triple(u.team, season, week)] ?: return // a bye
        val stages = stages(state, u, game)
        if (points(stages.final, stages.sd) < K.UPCOMING_MIN_POINTS) return
        val cv = K.EMPIRICAL_CV.getValue(u.player.position)
        for ((metric, mean) in withGame(stages.final)) {
            if (mean <= 0.0) continue
            val sum = ros.getOrPut(u.player.playerId to metric) { DoubleArray(2) }
            sum[0] += mean
            sum[1] += variance(metric, mean, cv, stages.sd)
        }
    }

    /**
     * A D/ST's projection carries `g` = 1, so rest of season (a sum) counts its
     * games and the phone can score each game's points-allowed tier.
     */
    private fun withGame(components: Map<String, Double>): Map<String, Double> =
        if (POINTS_ALLOWED in components) components + ("g" to 1.0) else components

    /**
     * Points allowed are about Normal(mean, [sd]): the variance is the spread
     * squared. A game count is exact. Everything else follows layer 7.
     */
    private fun variance(metric: String, mean: Double, cv: Double, sd: Double): Double = when (metric) {
        POINTS_ALLOWED -> sd * sd
        "g" -> 0.0
        else -> varianceFor(mean, cv)
    }

    /**
     * A unit's reference points for one game: its stats at the presets' values
     * and, for a D/ST, the preset tiers' expected points for its points allowed.
     */
    private fun points(components: Map<String, Double>, sd: Double): Double =
        referencePoints(components) +
            (components[POINTS_ALLOWED]?.let { ScoringPresets.PPR.expectedPointsAllowedPoints(it, sd) } ?: 0.0)

    private fun logRatio(after: Map<String, Double>, before: Map<String, Double>, sd: Double): Double {
        val a = points(after, sd)
        val b = points(before, sd)
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
Expected: PASS: the five new engine tests, `KindsTest`, and every existing engine test unchanged (their leagues have no units).

Then rebuild the test database and run the real-data projection tests:

Run: `./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025 2026" -Pout=etl/build/stats.db && GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test --tests "dev.gridiron.core.data.ProjectionsContractTest"`
Expected: PASS, all three tests. Note the K and DST top-12 averages from a quick `println` if a band fails: a band miss is a model bug (units, a missing factor, points allowed or their spread off) to find before going on, not a band to widen.

Also run the timing test, which now includes units: `./gradlew :core:forecast:test --tests "dev.gridiron.core.forecast.ForecastTimingTest"`. Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add core/forecast core/data
git commit -m "forecast: project kickers and D/STs walk-forward, with factors and rest of season"
```

### Task 10: The phone: tiers in expectation, a tier per simulated game, K and D/ST tabs, accuracy rows

**Files:**
- Create: `core/projections/src/main/kotlin/dev/gridiron/core/projections/ProjectedScore.kt`
- Modify: `core/projections/src/main/kotlin/dev/gridiron/core/projections/MonteCarlo.kt`, `ProjectedPoints.kt`, `FactorAttribution.kt`, `Backtest.kt`
- Modify: `core/data/src/main/kotlin/dev/gridiron/core/data/AccuracyRepository.kt`
- Modify: `feature/projections/src/main/kotlin/dev/gridiron/feature/projections/ProjectionListViewModel.kt`, `ProjectionCard.kt`, `ProjectionsViewModel.kt`
- Test: `core/projections/src/test/kotlin/dev/gridiron/core/projections/ProjectedScoreTest.kt` (new), `MonteCarloTest.kt`, `ProjectedPointsTest.kt`, `BacktestTest.kt`, `FactorAttributionTest.kt`; `core/data/src/test/kotlin/dev/gridiron/core/data/AccuracyGateTest.kt`; `feature/projections/src/test/kotlin/dev/gridiron/feature/projections/ProjectionListTest.kt`, `ProjectionCardTest.kt`

**Interfaces:**
- Consumes: Task 4's `Position.DST`, `pointsAllowedPoints`, `expectedPointsAllowedPoints` and `score()`'s tier; Task 9's stored rows (`points_allowed` with `dist_family` `normal` and variance = spread², `g` = 1 a game).
- Produces:
  - `projectedScore(components: List<ProjectionComponent>, profile, position): Double`: every screen and the backtest score projections through it.
  - `DistributionFamily.NORMAL`; `familyOf("normal")`. `simulate` draws each of a D/ST's `g` games' points allowed (whole points, never below 0) and adds its tier.
  - `attributeFactors(baselinePoints: Double, finalPoints: Double, factors)`. The map version delegates to it.
  - `calibratedRange(…, widening: Map<Position, Double> = RANGE_WIDENING)`, `projectPoints(…, draws, widening = RANGE_WIDENING)`, `backtest(…, draws, widening = RANGE_WIDENING)`, `AccuracyRepository.backtest(season, profile, draws, widening = RANGE_WIDENING)`.
  - `ACCURACY_POSITIONS = listOf("QB", "RB", "WR", "TE", "K", "DST")`. The gate holds every one of them.
  - `PositionTab.K` ("K") and `PositionTab.DST` ("D/ST"), after `FLEX`.

- [ ] **Step 1: Write the failing tests**

`core/projections/src/test/kotlin/dev/gridiron/core/projections/ProjectedScoreTest.kt`:

```kotlin
package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ProjectedScoreTest {
    private val ppr = ScoringPresets.PPR

    @Test
    fun `a D-ST's tiers are scored in expectation, never as the tier of the mean`() {
        val week = listOf(
            ProjectionComponent("dst_sacks", 2.0, 2.0, "negbinom"),
            ProjectionComponent("points_allowed", 17.6, 100.0, "normal"),
            ProjectionComponent("g", 1.0, 0.0),
        )
        val expected = 2.0 + ppr.expectedPointsAllowedPoints(17.6, 10.0)
        assertEquals(expected, projectedScore(week, ppr, Position.DST), 1e-9)
        assertNotEquals(2.0 + ppr.pointsAllowedPoints(17.6), projectedScore(week, ppr, Position.DST), 1e-3)
    }

    @Test
    fun `rest of season scores each of its games' tiers`() {
        // Three games: 60 points allowed in all, a variance of 300 in all: 20 and 10 a game.
        val ros = listOf(ProjectionComponent("points_allowed", 60.0, 300.0, "normal"), ProjectionComponent("g", 3.0, 0.0))
        assertEquals(3 * ppr.expectedPointsAllowedPoints(20.0, 10.0), projectedScore(ros, ppr, Position.DST), 1e-9)
    }

    @Test
    fun `without points allowed it's the means scored`() {
        val kicker = listOf(ProjectionComponent("fg_made_0_39", 1.5, 1.5, "poisson"), ProjectionComponent("xp_made", 2.0, 2.0, "poisson"))
        assertEquals(6.5, projectedScore(kicker, ppr, Position.K), 1e-9)
    }
}
```

In `MonteCarloTest.kt`, add (import `org.junit.jupiter.api.Assertions.assertEquals`, `dev.gridiron.core.model.PointsAllowedTier`):

```kotlin
    private val twoTiers = ScoringPresets.PPR.copy(
        id = "u1", name = "Two tiers",
        pointsAllowedTiers = listOf(PointsAllowedTier(0, 10.0), PointsAllowedTier(21, -4.0)),
    )

    @Test
    fun `each simulated game's points allowed land in one tier`() {
        val game = listOf(
            DistributionSpec(Components.POINTS_ALLOWED, DistributionFamily.NORMAL, mean = 20.0, variance = 100.0),
            DistributionSpec(Components.GAMES, DistributionFamily.GAMMA, mean = 1.0, variance = 0.0),
        )
        val result = simulate(game, twoTiers, Position.DST, draws = 2_000)
        // About half the games allow 20 or fewer (+10), the rest 21 or more (-4): nothing in between.
        assertEquals(-4.0, result.p10, 1e-9)
        assertEquals(10.0, result.p90, 1e-9)
        assertTrue(result.p50 == -4.0 || result.p50 == 10.0, "p50 was ${result.p50}")
    }

    @Test
    fun `rest of season simulates each of its games`() {
        val twoGames = listOf(
            DistributionSpec(Components.POINTS_ALLOWED, DistributionFamily.NORMAL, mean = 40.0, variance = 200.0),
            DistributionSpec(Components.GAMES, DistributionFamily.GAMMA, mean = 2.0, variance = 0.0),
        )
        val result = simulate(twoGames, twoTiers, Position.DST, draws = 2_000)
        // Two games of 20 ± 10: 20, 6 or -8 in all. The tier of their sum, 40, would always be -4.
        assertEquals(-8.0, result.p10, 1e-9)
        assertEquals(20.0, result.p90, 1e-9)
    }
```

In `ProjectedPointsTest.kt`, add:

```kotlin
    @Test
    fun `points allowed simulate as normal`() {
        assertEquals(DistributionFamily.NORMAL, familyOf("normal"))
    }

    @Test
    fun `a caller can try other widening factors`() {
        assertEquals(2.0 to 20.0, calibratedRange(10.0, 6.0, 15.0, Position.K, widening = mapOf(Position.K to 2.0)))
    }

    @Test
    fun `a D-ST's projected points are its tiers in expectation`() {
        val week = listOf(ProjectionComponent("points_allowed", 17.6, 100.0, "normal"), ProjectionComponent("g", 1.0, 0.0))
        assertEquals(ScoringPresets.PPR.expectedPointsAllowedPoints(17.6, 10.0), projectPoints(week, ScoringPresets.PPR, Position.DST).points, 1e-9)
    }
```

In `FactorAttributionTest.kt`, add:

```kotlin
    @Test
    fun `factors can split points the caller already scored`() {
        val factors = listOf(ProjectionFactor("matchup", 0.2, null), ProjectionFactor("game_script", 0.1, null))
        val split = attributeFactors(baselinePoints = 6.0, finalPoints = 9.0, factors = factors)
        assertEquals(listOf(2.0, 1.0), split.map { it.points })
    }
```

In `BacktestTest.kt`, add:

```kotlin
    @Test
    fun `kickers and team defenses are measured like everyone else`() {
        fun kick(made: Double) = mapOf(Component("g") to 1.0, Component("fg_made_0_39") to made, Component("xp_made") to 3.0)
        val played = listOf(
            PlayedWeek("k", 2025, 1, kick(1.0)), // 3 + 3
            PlayedWeek("k", 2025, 2, kick(2.0)), // 6 + 3
            PlayedWeek("d", 2025, 1, mapOf(Component("g") to 1.0, Component("dst_sacks") to 3.0, Component("points_allowed") to 20.0)), // 3 + 0
            PlayedWeek("d", 2025, 2, mapOf(Component("g") to 1.0, Component("dst_sacks") to 5.0, Component("points_allowed") to 10.0)), // 5 + 3
        )
        val projected = listOf(
            ProjectedWeek("k", "K", 2, listOf(ProjectionComponent("fg_made_0_39", 1.0, 0.0), ProjectionComponent("xp_made", 3.0, 0.0))),
            ProjectedWeek(
                "d", "DST", 2,
                listOf(
                    ProjectionComponent("dst_sacks", 3.0, 0.0),
                    // Exactly 10 allowed, so the projection is the 7-13 tier's 3.
                    ProjectionComponent("points_allowed", 10.0, 0.0, "normal"),
                    ProjectionComponent("g", 1.0, 0.0),
                ),
            ),
        )

        val results = backtest(2025, projected, played, ScoringPresets.PPR)

        assertEquals(listOf("K", "DST"), results.map { it.position })
        assertEquals(3.0, results[0].model.mae, 1e-9) // projected 6, scored 9
        assertEquals(2.0, results[1].model.mae, 1e-9) // projected 6, scored 8
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
                listOf("DST_KC", "points_allowed", "final", 10.0, 0.0, "normal"),
                listOf("DST_KC", "g", "final", 1.0, 0.0, null),
            )
            "FROM player_ros_projection" in sql && defense -> listOf(
                listOf("DST_KC", "dst_sacks", 9.0, 9.0, "negbinom"),
                listOf("DST_KC", "points_allowed", 30.0, 0.0, "normal"),
                listOf("DST_KC", "g", 3.0, 0.0, null),
            )
```

and add:

```kotlin
    @Test
    fun `a team defense's card scores each game's points-allowed tier`() = runTest {
        val card = loadProjectionCard(ProjectionsRepository(CardExecutor(defense = true)), "DST_KC", "KC", ScoringPresets.PPR, Position.DST, null)!!

        // 3 sacks, and 10 allowed: the 7-13 tier's 3.
        assertEquals(6.0, card.points, 1e-9)
        assertTrue(card.floor < 6.0 && card.ceiling > 6.0)
        // Three games of 3 sacks and 10 allowed. The tier of 30 allowed would be -4.
        assertEquals(18.0, card.rosPoints!!, 1e-9)
        assertEquals(6.0, card.rosPerGame!!, 1e-9)
    }
```

(`CardExecutor` answers `remainingGames` with 3 for the existing card tests. If it answers another count, use that count in the per-game expectation.)

In `AccuracyGateTest.kt`, change the KDoc's "at QB, RB, WR and TE" to "at QB, RB, WR, TE, K and D/ST (every one of [ACCURACY_POSITIONS])". The test already asserts `ACCURACY_POSITIONS` come back and none loses, so it gates K and D/ST as soon as they're in the list.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:projections:test :feature:projections:testDebugUnitTest`
Expected: FAIL to compile with "Unresolved reference: projectedScore", "Unresolved reference: NORMAL", "No parameter with name 'widening'" and "Unresolved reference: K" (the tab).

- [ ] **Step 3: Score projections with the tiers in expectation**

`core/projections/src/main/kotlin/dev/gridiron/core/projections/ProjectedScore.kt`:

```kotlin
package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.statquery.Component
import dev.gridiron.core.statquery.Components
import kotlin.math.sqrt

/**
 * A projection's points under [profile]: the projected means scored, except a
 * D/ST's points allowed, whose tiers are scored in expectation. Points
 * allowed are about normal (their variance is the spread squared), and a
 * projection of `g` games (rest of season) scores each game's tier from the
 * per-game mean and spread. The tier of the mean would put every game in one
 * tier, and the tier of a season's summed points allowed means nothing.
 */
public fun projectedScore(components: List<ProjectionComponent>, profile: ScoringProfile, position: Position?): Double {
    val allowed = components.firstOrNull { it.metricId == Components.POINTS_ALLOWED.id }
    val means = components.filter { it.metricId != Components.POINTS_ALLOWED.id }.associate { Component(it.metricId) to it.mean }
    val scored = score(means, profile, position)
    if (allowed == null) return scored
    val games = (means[Components.GAMES] ?: 1.0).coerceAtLeast(1.0)
    return scored + games * profile.expectedPointsAllowedPoints(allowed.mean / games, sqrt(allowed.variance.coerceAtLeast(0.0) / games))
}
```

In `MonteCarlo.kt`:

```kotlin
public enum class DistributionFamily {
    NEGBINOM,
    BINOMIAL,
    GAMMA,
    POISSON,

    /** About normal, drawn on whole points and never below 0: a D/ST's points allowed in one game. */
    NORMAL,
}
```

In `simulate`, replace the draw loop:

```kotlin
    // A D/ST's points allowed score a tier per game, so each of its `g` games is drawn on its own
    // and scored through the profile's tiers; everything else is drawn once and scored by score().
    val allowed = distributions.firstOrNull { it.component == Components.POINTS_ALLOWED }
    val independent = distributions.filter { it.component != Components.POINTS_ALLOWED }
    val games = distributions.firstOrNull { it.component == Components.GAMES }?.mean?.roundToInt()?.coerceAtLeast(1) ?: 1
    val perGameMean = (allowed?.mean ?: 0.0) / games
    val perGameSd = sqrt((allowed?.variance ?: 0.0).coerceAtLeast(0.0) / games)

    for (i in 0 until draws) {
        for (spec in independent) {
            componentMap[spec.component] = drawOne(spec, rng)
        }
        var points = score(componentMap, profile, position)
        if (allowed != null) repeat(games) { points += profile.pointsAllowedPoints(drawAllowed(perGameMean, perGameSd, rng)) }
        samples[i] = points
    }
```

In `drawOne`'s `when`, add `DistributionFamily.NORMAL -> drawAllowed(spec.mean, sqrt(spec.variance), rng)`, and add:

```kotlin
/** One game's points allowed: Normal([mean], [sd]) on whole points, never below 0. */
private fun drawAllowed(mean: Double, sd: Double, rng: SplittableRandom): Double =
    Math.round(mean + sd * gaussian(rng)).toDouble().coerceAtLeast(0.0)
```

(imports: `dev.gridiron.core.statquery.Components`, `kotlin.math.roundToInt`, `kotlin.math.sqrt`). Extend `simulate`'s KDoc: "A D/ST's points allowed are drawn per game (`g` of them) and scored through the profile's tiers."

In `FactorAttribution.kt`, split the scoring from the split:

```kotlin
public fun attributeFactors(
    baselineComponents: Map<Component, Double>,
    finalComponents: Map<Component, Double>,
    factors: List<ProjectionFactor>,
    profile: ScoringProfile,
    position: Position?,
): List<AttributedFactor> =
    attributeFactors(score(baselineComponents, profile, position), score(finalComponents, profile, position), factors)

/** Apportions [finalPoints] − [baselinePoints], already scored by the caller, across [factors] (as above). */
public fun attributeFactors(baselinePoints: Double, finalPoints: Double, factors: List<ProjectionFactor>): List<AttributedFactor> {
    val delta = finalPoints - baselinePoints
    // … the rest of the existing body, unchanged from `val totalLogMult = …` on.
}
```

- [ ] **Step 4: Every screen and the backtest score through `projectedScore`**

In `ProjectedPoints.kt`:
- `familyOf` gains `"normal" -> DistributionFamily.NORMAL`.
- `projectPoints` computes `val points = projectedScore(components, profile, position)`, and its `means` local goes.
- `calibratedRange` gains `widening: Map<Position, Double> = RANGE_WIDENING` as its last parameter and reads `val k = position?.let { widening[it] } ?: 1.0`.
- `projectPoints` gains `widening: Map<Position, Double> = RANGE_WIDENING` after `draws` and passes it: `calibratedRange(points, simulated.p10, simulated.p90, position, widening)`.
- Add to `calibratedRange`'s KDoc: "[widening] is for fitting the factors; everyone else uses [RANGE_WIDENING]."

In `ProjectionCard.kt`, `rosPoints` becomes `ros?.let { r -> withContext(compute) { projectedScore(r.components, profile, position) } }`.

In `ProjectionsViewModel.kt`, score both stages through `projectedScore` and split the factors on those points:

```kotlin
                val baselinePoints = projectedScore(projection.baseline, profile, position)
                val finalPoints = projectedScore(mergedComponents, profile, position)
                val attributed = attributeFactors(baselinePoints, finalPoints, projection.factors)
```

`baselineMap` stays only if something else still reads it; otherwise remove it (warnings are errors). `finalMap` still feeds the TD share.

In `Backtest.kt`:

```kotlin
/** The positions the backtest measures, in the page's order. CI's gate holds every one to beating the season-to-date average. */
public val ACCURACY_POSITIONS: List<String> = listOf("QB", "RB", "WR", "TE", "K", "DST")
```

`backtest` gains `widening: Map<Position, Double> = RANGE_WIDENING` after `draws` (documented as for fitting) and calls `projectPoints(p.components, profile, position, draws, widening)`. Its threshold becomes `if (projectedScore(p.components, profile, position) < ACCURACY_MIN_POINTS) continue`, and the `means` local goes.

In `AccuracyRepository.backtest`, add the same parameter after `draws` and pass it: `return backtest(season, projected, played, profile, draws, widening)` (import `dev.gridiron.core.model.Position` and `dev.gridiron.core.projections.RANGE_WIDENING`).

In `ProjectionListViewModel.kt`, `PositionTab` gains, after `FLEX(...)`:

```kotlin
    K("K", setOf("K")),
    DST("D/ST", setOf("DST")),
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:projections:test :core:data:test :feature:projections:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS. If a screen test counts the position chips or the accuracy page's positions (`ProjectionListScreenTest`, `AccuracyScreenTest`, `AccuracyViewModelTest`), update it for the two new tabs or rows: that's the intended change, and nothing else about those screens changes.

Then on real data (the database rebuilt in Task 9):

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test`
Expected: PASS, `AccuracyContractTest` included (it now expects K and D/ST rows). `ProjectionsContractTest`'s DST band can now use `projectedScore` directly: replace its hand-written `points` helper with `projectedScore(p.components, ScoringPresets.PPR, Position.fromCode(p.position!!))`.

- [ ] **Step 6: Commit**

```bash
git add core/projections core/data feature/projections
git commit -m "projections: D/ST tiers in expectation and per simulated game; K and D/ST tabs and accuracy rows"
```

### Task 11: The gate at K and D/ST, their range widening, and docs

**Files:**
- Modify (only if the gate fails at K or D/ST): `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/ForecastConstants.kt`
- Modify: `core/projections/src/main/kotlin/dev/gridiron/core/projections/ProjectedPoints.kt` (`RANGE_WIDENING`)
- Modify: `core/projections/src/test/kotlin/dev/gridiron/core/projections/ProjectedPointsTest.kt`
- Temporary (not committed): `core/data/src/test/kotlin/dev/gridiron/core/data/RangeFitTest.kt`
- Modify: `CLAUDE.md`, `docs/superpowers/specs/2026-09-26-projection-model-design.md`, `docs/superpowers/HANDOFF.md`

**Interfaces:**
- Consumes: Task 10's `widening` parameter and six-position backtest; `AccuracyRepository`; `JdbcQueryExecutor`, `StatsDb`.
- Produces:
  - K and D/ST constants that beat the season-to-date average in 2025 under PPR (unchanged if they already do).
  - `RANGE_WIDENING` with `Position.K` and `Position.DST` factors, or without either one if the simulation alone already holds 80% of its games.

- [ ] **Step 1: Rebuild the accuracy database (2024–2025)**

Run: `./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025" -Pout=etl/build/accuracy.db`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run the gate at all six positions**

Run: `GRIDIRON_STATS_DB=etl/build/accuracy.db GRIDIRON_ACCURACY_GATE=2025 ./gradlew :core:data:test --tests "dev.gridiron.core.data.AccuracyGateTest"; cat core/data/build/reports/accuracy-gate.txt`
Expected: six rows. QB–TE's MAE, bias and held should equal the handoff's (QB 6.42, RB 5.88, WR 5.40, TE 4.87; held 78/78/81/83%): nothing in this sub-project changes an offensive projection. A difference is either a regression or republished nflverse files. Tell them apart by building a 2024–2025 database and running the gate from the base commit in a worktree (`git worktree add ../base e188dc2`) before concluding.

If the gate passes, go to Step 3. If K or D/ST loses (its model MAE isn't below its season-average MAE), tune that position's constants in `ForecastConstants.kt`. Never skip the gate or drop the position from `ACCURACY_POSITIONS`.
- **Read the row first.** A large bias (say beyond ±0.5) points at volume: kickers' tries fit (`KICK_MIN_FIT_ROWS`, the no-line fallback `TEAM_POINTS_K_GAMES`), or D/STs' points allowed (`DST_PA_K`). A small bias with a high MAE points at noise: shrink harder toward the league.
- **Kickers:** raise `KICK_MAKE_K`, `KICK_MIX_K` and `XP_MAKE_K` (more shrinkage) before anything else. Kicking accuracy is mostly noise week to week.
- **D/STs:** raise the rare events' `DST_K` (`dst_tds`, `dst_safeties`, `dst_fumble_recoveries`) and `DST_OPP_K`, or lower `DST_CAP`. Try `DST_HALF_LIFE` last.
- **One constant per try.** Rebuild `etl/build/accuracy.db` (Step 1) and rerun the gate. Keep a change only if it lowers that position's model MAE, and record each try's numbers for the handoff.
- **Stop after eight rebuilds without a pass.** Don't commit a failing gate. Report the table and the tries to the user, and ask whether to keep tuning or change the model.
- `FORECAST_VERSION` stays 4: none of this has shipped.

- [ ] **Step 3: Fit each range factor**

Create `core/data/src/test/kotlin/dev/gridiron/core/data/RangeFitTest.kt` (a measuring tool; it is deleted in Step 5):

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

A D/ST's range comes from sacks and takeaways drawn once and each game's points allowed drawn through the tiers. If D/ST holds far more than 80% alone (say over 90%), note it in the handoff: the tier steps make the range lumpy, and the factor can't narrow it.

- [ ] **Step 4: Record the factors**

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

- [ ] **Step 5: The gate's final table**

Delete `RangeFitTest.kt`. Then:

Run: `GRIDIRON_STATS_DB=etl/build/accuracy.db GRIDIRON_ACCURACY_GATE=2025 ./gradlew :core:data:test --tests "dev.gridiron.core.data.AccuracyGateTest" && cat core/data/build/reports/accuracy-gate.txt`
Expected: PASS, with six rows. K's and D/ST's held read about 80%. Copy the table into the handoff (Step 7).

- [ ] **Step 6: Docs**

In `CLAUDE.md`:
- `:core:projections`: "Pure `score()` (the in-memory twin of `StatQueryBuilder`'s SQL scoring; a D/ST's points allowed score the profile's tier) and `projectedScore` (projections: tiers in expectation, per game), factor attribution, and single-player Monte Carlo (floor/ceiling, widened per position by `RANGE_WIDENING` so the range holds about 80% of games; a D/ST's points allowed are drawn per game and scored through the tiers)…"
- `:core:ingest`: after "Kotlin ports of the ETL's transforms and validation", add "(kicking facts from play-by-play; each team's defense as a `DST_<TEAM>` pseudo-player from `team_week_defense`, with points allowed stored as a number for the profile's tiers)".
- `:core:forecast`: "writes weekly, rest-of-season and waterfall-factor projections for QB/RB/WR/TE, K and D/ST". After the seven layers, add: "K and D/ST have their own models (`Kicker.kt`, `Defense.kt`, run by `UnitProjector`). Kickers' field goal and extra point tries come from implied team points, with their distance mix and accuracy shrunk toward the league's. D/STs get their own sacks and takeaways times the opponent's, and points allowed from the opponent's implied points with a measured spread (stored as the variance, with `g` = 1 a game)."
- `:feature:projections`: "(the upcoming week or rest of season by position, K and D/ST included, scored with the active profile)".
- `:feature:players` / `:core:data`: the Grid's K and D/ST chips bring their own packs (Kicking, Defense). All and the offense's chips leave kickers and D/STs out (`StatsRepository`).
- Add `:core:datastore` if it isn't listed: "User prefs (profiles, rosters, settings) as one JSON document. `formatVersion` 2 migrated older profiles once to the kicking and D/ST defaults and ESPN's points-allowed tiers."
- Scoring editor: note the points-allowed tier editor.
- Schema heading: "Version 8". `player`: "Players with at least one stat in the built seasons, plus a `DST_<TEAM>` pseudo-player per team". `team_week_defense`: "…defensive TDs, safeties, kickoff-return TDs".
- Accuracy gate paragraph: "…isn't below the season-to-date average's at QB, RB, WR, TE, K and D/ST…".
- Known gaps: remove "Projection model sub-project 4 (K/DST) is not built yet…" and "K/DST fantasy scoring is out of scope for `:core:projections`'s `score()`…". Add:
  - "**K and D/ST constants are judgments** (`ForecastConstants`), tuned only as far as the gate needs."
  - "**ESPN's default D/ST also scores yards allowed and blocked kicks**, which aren't modeled: `team_week_defense` has yards allowed, so a yards-allowed tier editor would be the next step."
  - "**Compare and the Player page's season stats show offense columns for a kicker or D/ST.**"
- The metric count: update "Only 39 of ~450 catalogued metrics are implemented" to the new number of visible columns (`StatColumn.entries.size`).

In the spec, add after "Amendment: layer 2":

```markdown
## Amendment: sub-project 4 (2026-09-27, the user's rulings on the K and DST plan)

- **Points-allowed tiers are the profile's own.** Each D/ST week stores `points_allowed` as a number. `ScoringProfile.pointsAllowedTiers` (tier starts and points, editable in the scoring editor) scores it: one tier per game in SQL and in `score()`. The presets carry ESPN's tiers (0, 1–6, 7–13, 14–17, 18–21, 22–27, 28–34, 35–45, 46+: 5, 4, 3, 1, 0, −1, −4, −5, −5). This replaces §6's fixed seven tiers.
- **Projections** store points allowed as a mean with the measured spread as its variance, and `g` = 1 a game. The phone scores the tiers in expectation under a normal distribution, per game, for rest of season too. The simulation draws each game's points allowed and scores its tier. The spread is real team scores around their implied points, measured walk-forward (about 10 points before there are 100 lined games).
- **Defaults and migration.** Presets score FG 3/4/5 by distance, FG missed −1, XP made 1, XP missed −1; sack 1, interception 2, fumble recovery 2, TD 6, safety 2; and ESPN's tiers. Profiles saved before this change were migrated once (prefs `formatVersion` 2) to those values. After that, a rule a profile doesn't list scores 0, as before.
- **D/ST TDs** are the defense's (including punt and blocked-kick returns) plus kickoff returns; `team_week_defense` gains `safeties` and `kick_return_tds`.
- **The Grid** has K and D/ST chips with their own packs (Kicking, Defense). All and the offense's chips leave kickers and D/STs out. This replaces §6's "The Grid is unchanged."
- **Accuracy.** The accuracy page measures K and D/ST, and **CI's gate holds them to the same bar as QB–TE** (this replaces §4's QB–TE-only gate). `RANGE_WIDENING` gains K <K factor> and DST <DST factor>, fitted like the others on pooled 2024–2025. <Any constants the gate made us tune, with before and after.>
```

(fill in the two factors, or write "no factor" for a position that needed none; list tuned constants, or drop that sentence if none were).

- [ ] **Step 7: The whole suite, then commit**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew test && ./gradlew :app:assembleRelease lint`
Expected: BUILD SUCCESSFUL. (If `:core:statquery`'s timing test fails under the full parallel build, rerun `./gradlew :core:statquery:test` alone: the known flake.)

Update `docs/superpowers/HANDOFF.md` with the fitted factors, the gate's table (all six rows), any tuning tries and any finding from Steps 2–3, then:

```bash
git add core/forecast core/projections CLAUDE.md docs
git commit -m "projections: K and D/ST pass the accuracy gate; fit their range widening; docs for K and D/ST"
```

---

## Plan self-review

**Spec coverage (§6, as amended by the user on 2026-09-27).**

| Spec line | Task |
|---|---|
| K rules: FG made 0–39, 40–49, 50+; XP made; FG missed; XP missed | 4 |
| DST rules: sack, interception, fumble recovery, TD, safety | 4 |
| **Amended:** points-allowed tiers are the profile's own and editable, ESPN's by default | 4 (model, SQL, `score()`), 5 (saved, edited) |
| Presets get the common defaults; the editor shows the new rules | 4 (presets), 5 (the editor lists every `ScoringGroup`, so the two new groups appear, plus the tier section) |
| **Amended:** saved profiles migrate once to the defaults | 5 |
| `score()` and `StatQueryBuilder` learn them | 4; 10 (`projectedScore` for projections) |
| Kicking metrics from play-by-play | 1 |
| DST facts from `team_week_defense`, `DST_<TEAM>` pseudo-player named "<TEAM> D/ST", position DST | 2 |
| Python parity for the new facts | 3 |
| K model: implied points → tries (linear, walk-forward); mix and make rates shrunk; CV 0.52 | 7, 9 |
| DST model: own EWMA rates × opponent's; points allowed from the opponent's implied points; tier probabilities follow; CV 0.85 | 8, 9 (mean and spread stored), 10 (tier probabilities on the phone) |
| K and DST tabs in the Projections list; Player page cards | 10 (tabs; the card is position-generic once `Position.DST` exists, pinned by `ProjectionCardTest`) |
| **Amended:** K and D/ST chips on the Grid | 6 |
| **Amended:** the gate covers K and D/ST | 10 (`ACCURACY_POSITIONS`), 11 (tuning if needed) |

**Rulings.** The user overturned four of the first draft's (2026-09-27): fixed one-hot tiers, a hidden `fallback`, a Grid without K and D/ST, and a QB–TE-only gate. What replaced them, and what each costs:
1. **Editable tiers, stored points allowed.** The database holds `points_allowed` as a number, and each profile's `pointsAllowedTiers` scores it: a `CASE` per week in SQL, a lookup in `score()`. Cost: projections can't be scored linearly. `projectedScore` scores the tiers in expectation from a normal distribution, and every screen and the backtest go through it (Task 10). A caller that scores projected means with plain `score()` gets the tier of the mean, which is biased. `score()`'s KDoc says so.
2. **Rest of season per game.** A D/ST projection carries `g` = 1, so rest of season (a sum) knows its game count. Its tiers are scored as `g` games of the average mean and spread. Cost: a schedule mixing easy and hard opponents is scored as `g` average games. The tiers are a step function, so this is slightly off (well under a point over a season).
3. **Migration, not fallback.** Version-1 prefs gain `KICKING_AND_DEFENSE` and ESPN's tiers once. Cost: none for existing users. Changing a default later won't reach saved profiles (they hold their own values), which is the point.
4. **ESPN's tiers only.** ESPN's default D/ST also scores yards allowed and blocked kicks, which aren't modeled. The kicking values (3/4/5, −1, 1, −1) are the common ones, not checked against ESPN's; the user can edit any of them.
5. **The Grid's K and D/ST chips.** Each brings its own pack, and packs follow the chip. Kickers qualify at 1 field goal try a week, and the snap-share chip is hidden there. Eleven new visible columns (all `Total`s) come with display abbreviations (FGM, FGA, FG50, XPM, XPA, PA, SACK, DINT, FR, DTD, SAF). Cost: Compare and the Player page's season stats still show offense columns for a kicker or D/ST (noted in CLAUDE.md's known gaps).
6. **The gate covers K and D/ST.** If either loses in 2025, Task 11 tunes its constants, one per rebuild, up to eight rebuilds. After that it stops and asks rather than committing a red gate.
7. **Kicking definitions:** a blocked field goal is a miss; an aborted extra point is a miss; a field goal with no recorded distance counts as 0–39; a kick week sets `g` = 1. Cost: rare edge rows.
8. **D/ST TDs** add kickoff returns (nflverse lists the receiving team as `posteam` on kickoffs) to the existing `defensive_tds` (which already holds punt and blocked-kick returns). **Points allowed** are all of the opponent's points, however scored. Cost if nflverse ever flips kickoff `posteam`: return TDs would move between teams. The parity gate wouldn't catch it (both sides share the rule); the Task 2 and Task 3 tests pin the assumption.
9. **Kickers need a kick before the week** to be projected, like players. A debut week falls back to the team's previous kicker, who then isn't counted by the backtest because he didn't play. Cost: one missed kicker-week per kicker change.
10. **Tries fit:** least squares on lined team-games once there are 64; a flat average before that (and in the synthetic test league, which has few lines). Cost: none on real data, where nflverse posts lines for every past game.
11. **The forecast's own reference points** (its thresholds and factor ratios) score D/STs with the PPR preset's tiers in expectation. They never reach the user; the phone rescores with the active profile.
12. **No props for K or D/ST.** The Odds API markets are the offense's (spec §5).
13. **A separate `ws`/`fs` pivot** for kicking, defense and points allowed keeps the offense's pivot at its measured width (the `StatQueryBuilder` comment's 190 ms → 100 ms fix). Cost: two short CTEs and a `CASE` of up to a dozen tiers.
14. **Versions:** `INGEST_VERSION` 3 (every season rebuilds once on the next refresh), `SCHEMA_VERSION` 8, `FORECAST_VERSION` 4, prefs `FORMAT_VERSION` 2.

**Placeholder scan.** Task 11's `<K factor>`, `<DST factor>` and the tuned-constants sentence are measured values that don't exist until Task 11 runs. Two tests are described rather than written in full, because they follow a neighbor's setup exactly: the Grid screen test (Task 6) and the tier screenshot's scroll (Task 5). Everything else is given in full.

**Type consistency.**
- `PointsAllowedTier`, `pointsAllowedPoints`, `expectedPointsAllowedPoints` and `normalCdf` (Task 4) are used by `StatQueryBuilder.tiers`, `score()` and the contract test (Task 4), the prefs DTO and editor (Task 5), `UnitProjector.points` (Task 9), and `projectedScore` and `simulate` (Task 10).
- `Components.POINTS_ALLOWED` and `Components.GAMES` (the existing `"g"`) key the tier everywhere.
- `TeamKicks(implied: Double?, …)`, `AttemptFit.fit`, `KickLeague`, `KickerRates`, `kickStats` and `teamPoints` (Task 7) match their uses in `Units.kt` (Task 9), as do `DefenseLeague`, `unitRate`, `opponentFactor`, `defenseStages` and `POINTS_ALLOWED` (Task 8).
- `WeekKind` becomes internal in Task 9 before `UnitProjector` uses it.
- `StatPack.unit` and `PositionFilter.packs` (Task 6) are used by the repository, the reducer and the screen.
- `widening` (Task 10) is the parameter Task 11's tool passes.

**Review Focus check.** Each of its seven lines has a test in the task that owns the code:
- The kicker who ran: Task 1 (`a kicker's field goals and extra points are stored beside any play he ran`) and Task 4 (`a kicker who also ran scores both in the same week`).
- Tier edges and weeks without points allowed: Task 4 (`PointsAllowedTest`'s edges, `each week scores its own tier …`, `a kicker's week scores … no points-allowed tier`, `a week without points allowed scores no tier`, and the contract test's hand-written tiers).
- Old profiles: Task 5 (`a profile saved before kicking and defense scoring gets the defaults once`, `after the migration, a zeroed rule and no tiers stay that way`).
- Projected tiers, week and rest of season: Task 10 (`ProjectedScoreTest`, `rest of season simulates each of its games`, `a team defense's card scores each game's points-allowed tier`).
- The Grid's chips: Task 6 (`the K and D-ST chips list kickers and team defenses …`, `every other chip, a search and a roster …`, the reducer tests).
- Kicker changes: Task 9 (`each team has one kicker, never one kicker for two teams …`).
- No line, and byes: Task 9 (the BBB–CCC game without a line in `kickers and team defenses get …`, and the bye in `rest of season for kickers and defenses …`).
