# D/ST Yards-Allowed Scoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Score a D/ST's yards allowed through editable per-profile tiers (ESPN's by default), in real games, projections, the profile editor and the screens.

**Architecture:** `ScoringProfile` gains `yardsAllowedTiers` beside the points-allowed tiers, sharing one tier implementation. `yards_allowed` becomes a D/ST weekly stat (already in `team_week_defense`), scored in SQL and in `score()`. The forecast projects it as its own stat (rate, matchup, game script, fitted spread); the phone scores it in expectation and the Monte Carlo draws it jointly with points allowed. The editor gets a second tier section and prefs migrate to `formatVersion` 3.

**Tech Stack:** Kotlin (JVM modules + Compose), SQLite, JUnit 5, Python/polars for the ETL parity reference.

**Spec:** `docs/superpowers/specs/2026-09-29-dst-yards-allowed-design.md`

## Global Constraints

- ESPN's default yards tiers (unverified, editable): 0–99: 5, 100–199: 3, 200–299: 2, 300–349: 0, 350–399: −1, 400–449: −3, 450–499: −5, 500–549: −6, 550+: −7. They are on in every preset and every saved profile (migrated once).
- Yards allowed are **net yards** (what `team_week_defense.yards_allowed` stores). Stored as a number; a D/ST week always has one; a kicker's week has none.
- `INGEST_VERSION` 4 → 5. `SCHEMA_VERSION` stays 8. `FORECAST_VERSION` 4 → 5. Prefs `formatVersion` 2 → 3.
- `PointsAllowedTier` stays as a `typealias` of the renamed `ScoringTier`; existing references compile unchanged.
- No `!!`-driven shortcuts, no model identifiers in code, comments or commit messages. Commit messages end with the two attribution lines from the session reminder.
- Yards constants are measured on `etl/build/stats.db` (2024–2025 pooled, 1,140 team-games): correlation of points and yards allowed 0.666, residual spread of yards around each team-season's own average 0.239 of the league mean. `DST_POINTS_YARDS_CORRELATION` = 0.67, `DST_YA_CV` = 0.25.
- Accuracy gate: the model's 2025 D/ST MAE under PPR must stay below the season-to-date average's. If it doesn't, tune `DST_YA_K`, `DST_YA_SCRIPT_ELASTICITY`, `DST_YA_CV` (up to 8 rebuilds, then stop and ask).
- Container: `etl/build/stats.db` and `etl/build/accuracy.db` exist but predate this work. Task 2 rebuilds them with the Kotlin builder. `RealDatabaseContractTest > scoring a full season for every player is fast` fails here on time (ignore).

## Review Focus

- **A D/ST week with no `yards_allowed` row** (kicker, an old database, a test fixture): scores no yards tier, and a projection without a yards component scores none. Pinned in Tasks 1 and 3.
- **A shutout's 0 yards** would be dropped if the metric were sparse: it must stay stored and score the top tier. Pinned in Task 2 (`MetricsTest`).
- **A saved v2 profile with an empty yards list vs a saved v3 profile with an empty list:** the first gets ESPN's once, the second stays empty. Pinned in Task 4.
- **A profile with no yards tiers (empty list) beside the points tiers:** yards score 0, points tiers still score; equal profiles give identical SQL. Pinned in Task 1.
- **Rest of season (`g` > 1) yards:** each game's tier is scored from the per-game mean and spread, never the tier of the summed yards. Pinned in Task 3.

## File Structure

- `core/model/.../PointsAllowed.kt` — `ScoringTier`, `ESPN_POINTS_ALLOWED`, `ESPN_YARDS_ALLOWED`, `tierPoints`, `expectedTierPoints`, `normalCdf`.
- `core/model/.../Scoring.kt` — `ScoringProfile.yardsAllowedTiers` and its two functions; presets carry ESPN's yards tiers.
- `core/statquery/...` — `Components.YARDS_ALLOWED`, `StatColumn.YARDS_ALLOWED`, `StatQueryBuilder.tiers()`.
- `core/projections/...` — `Scorer`, `Backtest`, `ProjectedScore`, `MonteCarlo`, `ProjectedPoints` (correlation constant, widening).
- `core/ingest/...`, `etl/gridiron_etl/...` — write, register and range-check the stat; version bump.
- `core/forecast/...` — `Defense.kt`, `Units.kt`, `Inputs.kt`, `ForecastConstants.kt`.
- `core/datastore/.../UserPrefsJson.kt`, `feature/scoring/...` — prefs migration and the editor.
- `core/data/...` — Defense pack, Compare set, Player page log.

---

### Task 1: Tiers in the model, the SQL and `score()`

**Files:**
- Modify: `core/model/src/main/kotlin/dev/gridiron/core/model/PointsAllowed.kt`, `core/model/src/main/kotlin/dev/gridiron/core/model/Scoring.kt`
- Modify: `core/statquery/src/main/kotlin/dev/gridiron/core/statquery/Component.kt`, `Scoring.kt`, `StatQueryBuilder.kt`
- Modify: `core/projections/src/main/kotlin/dev/gridiron/core/projections/Scorer.kt`, `Backtest.kt`
- Test: `core/model/src/test/kotlin/dev/gridiron/core/model/YardsAllowedTest.kt` (create), `core/model/src/test/kotlin/dev/gridiron/core/model/ScoringProfileTest.kt`, `core/statquery/src/test/kotlin/dev/gridiron/core/statquery/ScoringQueryTest.kt`, `core/projections/src/test/kotlin/dev/gridiron/core/projections/ScorerTest.kt`

**Interfaces:**
- Produces (model): `public data class ScoringTier(val min: Int, val points: Double)`; `public typealias PointsAllowedTier = ScoringTier`; `public val ESPN_YARDS_ALLOWED: List<ScoringTier>`; `ScoringProfile.yardsAllowedTiers: List<ScoringTier>` (default empty, after `pointsAllowedTiers`); `ScoringProfile.yardsAllowedPoints(allowed: Double): Double`; `ScoringProfile.expectedYardsAllowedPoints(mean: Double, sd: Double): Double`.
- Produces (statquery): `Components.YARDS_ALLOWED = Component("yards_allowed")`.
- Produces (projections): `score()` adds `profile.yardsAllowedPoints(...)` when the map has `Components.YARDS_ALLOWED`.

- [ ] **Step 1: Write the failing model tests**

Create `core/model/src/test/kotlin/dev/gridiron/core/model/YardsAllowedTest.kt`:

```kotlin
package dev.gridiron.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class YardsAllowedTest {
    private val ppr = ScoringPresets.PPR

    @Test
    fun `ESPN's yards tiers are on in every preset`() {
        assertEquals(
            listOf(0 to 5.0, 100 to 3.0, 200 to 2.0, 300 to 0.0, 350 to -1.0, 400 to -3.0, 450 to -5.0, 500 to -6.0, 550 to -7.0),
            ESPN_YARDS_ALLOWED.map { it.min to it.points },
        )
        for (preset in ScoringPresets.all) assertEquals(ESPN_YARDS_ALLOWED, preset.yardsAllowedTiers, preset.id)
    }

    @ParameterizedTest
    @CsvSource(
        "0,5", "99,5", "100,3", "199,3", "200,2", "299,2", "300,0", "349,0",
        "350,-1", "399,-1", "400,-3", "449,-3", "450,-5", "499,-5", "500,-6", "549,-6", "550,-7", "900,-7",
    )
    fun `a game lands in exactly one yards tier, edges included`(allowed: Double, points: Double) {
        assertEquals(points, ppr.yardsAllowedPoints(allowed), 0.0)
    }

    @Test
    fun `a profile with no yards tiers scores yards as nothing, and its points tiers still score`() {
        val noYards = ppr.copy(id = "u1", name = "Mine", yardsAllowedTiers = emptyList())
        assertEquals(0.0, noYards.yardsAllowedPoints(0.0), 0.0)
        assertEquals(5.0, noYards.pointsAllowedPoints(0.0), 0.0)
    }

    @Test
    fun `expected yards points weigh each tier by its chance, on whole yards`() {
        val two = ppr.copy(id = "u1", name = "Mine", yardsAllowedTiers = listOf(ScoringTier(0, 10.0), ScoringTier(300, -4.0)))
        val below = normalCdf((299.5 - 320.0) / 80.0)
        assertEquals(10.0 * below - 4.0 * (1 - below), two.expectedYardsAllowedPoints(320.0, 80.0), 1e-12)
        assertEquals(ppr.yardsAllowedPoints(310.0), ppr.expectedYardsAllowedPoints(310.0, 1e-6), 1e-9)
        assertEquals(0.0, ppr.expectedYardsAllowedPoints(299.6, 0.0), 0.0) // 300: the 300-349 tier
    }

    @Test
    fun `more yards expected means fewer tier points`() {
        assertEquals(true, ppr.expectedYardsAllowedPoints(250.0, 80.0) > ppr.expectedYardsAllowedPoints(420.0, 80.0))
    }

    @Test
    fun `yards tiers start at 0 and rise, like points tiers`() {
        val base = ppr.copy(id = "u1", name = "Mine")
        assertThrows<IllegalArgumentException> { base.copy(yardsAllowedTiers = listOf(ScoringTier(100, 3.0))) }
        assertThrows<IllegalArgumentException> { base.copy(yardsAllowedTiers = listOf(ScoringTier(0, 5.0), ScoringTier(0, 3.0))) }
        assertThrows<IllegalArgumentException> { base.copy(yardsAllowedTiers = listOf(ScoringTier(0, 5.0), ScoringTier(200, 2.0), ScoringTier(100, 3.0))) }
        assertThrows<IllegalArgumentException> { ScoringTier(-1, 0.0) }
    }

    @Test
    fun `PointsAllowedTier is ScoringTier under its old name`() {
        assertEquals(ScoringTier(7, 3.0), PointsAllowedTier(7, 3.0))
    }
}
```

In `ScoringProfileTest.kt` line 32 the loop already asserts `ESPN_POINTS_ALLOWED`; leave it.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:model:test --tests "dev.gridiron.core.model.YardsAllowedTest"`
Expected: compile FAIL (`ScoringTier`, `ESPN_YARDS_ALLOWED`, `yardsAllowedTiers` not defined).

- [ ] **Step 3: Implement the model**

Replace `PointsAllowedTier` and add the shared helpers in `PointsAllowed.kt` (keep `normalCdf` and `ESPN_POINTS_ALLOWED` as they are, but written with `ScoringTier`):

```kotlin
/**
 * A D/ST scoring tier: [points] for a game in which the opponent scored or
 * gained at least [min] (points, or yards), up to the next tier's [min]. A
 * profile's tiers start at 0, so every game lands in exactly one.
 */
public data class ScoringTier(val min: Int, val points: Double) {
    init {
        require(min >= 0) { "a tier can't start below 0, was $min" }
        require(points.isFinite()) { "tier points must be finite, was $points" }
    }
}

/** The points-allowed tiers' original name. */
public typealias PointsAllowedTier = ScoringTier

/** ESPN's default tiers: 0, 1-6, 7-13, 14-17, 18-21, 22-27, 28-34, 35-45 and 46+ points allowed. */
public val ESPN_POINTS_ALLOWED: List<ScoringTier> = listOf(
    ScoringTier(0, 5.0), ScoringTier(1, 4.0), ScoringTier(7, 3.0),
    ScoringTier(14, 1.0), ScoringTier(18, 0.0), ScoringTier(22, -1.0),
    ScoringTier(28, -4.0), ScoringTier(35, -5.0), ScoringTier(46, -5.0),
)

/** ESPN's default yards-allowed tiers (net yards): 0-99, 100-199, 200-299, 300-349, 350-399, 400-449, 450-499, 500-549 and 550+. */
public val ESPN_YARDS_ALLOWED: List<ScoringTier> = listOf(
    ScoringTier(0, 5.0), ScoringTier(100, 3.0), ScoringTier(200, 2.0),
    ScoringTier(300, 0.0), ScoringTier(350, -1.0), ScoringTier(400, -3.0),
    ScoringTier(450, -5.0), ScoringTier(500, -6.0), ScoringTier(550, -7.0),
)

/** A tier list is empty, or starts at 0 and rises. */
internal fun tiersAreValid(tiers: List<ScoringTier>): Boolean =
    (tiers.isEmpty() || tiers.first().min == 0) && tiers.zipWithNext().all { (a, b) -> a.min < b.min }

/** The points of the highest tier starting at or below [x]; no tiers score nothing. */
internal fun tierPoints(tiers: List<ScoringTier>, x: Double): Double = tiers.lastOrNull { x >= it.min }?.points ?: 0.0

/**
 * The expected [tierPoints] for a game whose value is about Normal([mean], [sd])
 * and lands on whole units: a tier starting at 7 takes everything from 6.5 up,
 * and anything below the second tier's start is the first tier. A zero [sd] is
 * the tier of [mean] rounded.
 */
internal fun expectedTierPoints(tiers: List<ScoringTier>, mean: Double, sd: Double): Double {
    if (sd <= 0.0) return tierPoints(tiers, Math.round(mean).toDouble())
    return tiers.indices.sumOf { i ->
        val from = if (i == 0) 0.0 else normalCdf((tiers[i].min - 0.5 - mean) / sd)
        val to = tiers.getOrNull(i + 1)?.let { normalCdf((it.min - 0.5 - mean) / sd) } ?: 1.0
        tiers[i].points * (to - from)
    }
}
```

In `Scoring.kt`: change the `pointsAllowedTiers` type to `List<ScoringTier>`, add the new property, validations and functions, and the preset field:

```kotlin
 * @property yardsAllowedTiers A D/ST's yards-allowed tiers, lowest first, under the same rules.
...
    val pointsAllowedTiers: List<ScoringTier> = emptyList(),
    val yardsAllowedTiers: List<ScoringTier> = emptyList(),
...
        require(tiersAreValid(pointsAllowedTiers)) { "points-allowed tiers must start at 0 and rise: $pointsAllowedTiers" }
        require(tiersAreValid(yardsAllowedTiers)) { "yards-allowed tiers must start at 0 and rise: $yardsAllowedTiers" }
...
    public fun pointsAllowedPoints(allowed: Double): Double = tierPoints(pointsAllowedTiers, allowed)

    public fun expectedPointsAllowedPoints(mean: Double, sd: Double): Double = expectedTierPoints(pointsAllowedTiers, mean, sd)

    /** A D/ST's points for one game in which the opponent gained [allowed] net yards. */
    public fun yardsAllowedPoints(allowed: Double): Double = tierPoints(yardsAllowedTiers, allowed)

    /** The expected [yardsAllowedPoints] for one game whose yards allowed are about Normal([mean], [sd]), on whole yards. */
    public fun expectedYardsAllowedPoints(mean: Double, sd: Double): Double = expectedTierPoints(yardsAllowedTiers, mean, sd)
```

The two `require`s for points tiers become the one line `require(tiersAreValid(pointsAllowedTiers)) { "points-allowed tiers must start at 0 and rise: $pointsAllowedTiers" }` (`tiersAreValid` covers both the start-at-0 and rising rules), so delete the old pair. Keep the KDoc on `expectedPointsAllowedPoints` (move its text to say "See [expectedTierPoints]"). In `ScoringPresets.espn(...)` add `yardsAllowedTiers = ESPN_YARDS_ALLOWED,` under `pointsAllowedTiers = ESPN_POINTS_ALLOWED,`.

- [ ] **Step 4: Run model tests**

Run: `./gradlew :core:model:test`
Expected: PASS (existing `PointsAllowedTest` still passes through the typealias).

- [ ] **Step 5: Write the failing query and scorer tests**

Append to `ScoringQueryTest.kt`, next to the points-allowed tests:

```kotlin
    @Test
    fun `a team defense's week scores its yards tier as well as its points tier`() {
        db.player("DST_KC", "KC D/ST", position = "DST", team = "KC")
        db.week("DST_KC", 1, C.DST_SACKS to 3, C.POINTS_ALLOWED to 10, C.YARDS_ALLOWED to 250)
        db.week("DST_KC", 2, C.POINTS_ALLOWED to 46, C.YARDS_ALLOWED to 560)
        db.week("DST_KC", 3, C.POINTS_ALLOWED to 0, C.YARDS_ALLOWED to 0)
        // Week 1: 3 sacks, 7-13 allowed is 3, 200-299 yards is 2: 8. Week 2: -5 and -7: -12. Week 3, a shutout of 0 yards: 5 and 5: 10.
        assertEquals(6.0, db.grid(fantasy(ScoringPresets.PPR)).single().value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `each week scores its own yards tier, never the tier of the weeks' total`() {
        db.player("DST_KC", "KC D/ST", position = "DST", team = "KC")
        db.week("DST_KC", 1, C.YARDS_ALLOWED to 299)
        db.week("DST_KC", 2, C.YARDS_ALLOWED to 300)
        // 2 + 0, and the points-allowed CASE sees NULL and adds nothing. The total, 599, would be one -7 tier.
        assertEquals(2.0, db.grid(fantasy(ScoringPresets.PPR.copy(pointsAllowedTiers = emptyList()))).single().value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `no yards tiers score no yards, and a week without yards scores none`() {
        db.player("DST_KC", "KC D/ST", position = "DST", team = "KC")
        db.player("k1", "Place Kicker", position = "K")
        db.week("DST_KC", 1, C.POINTS_ALLOWED to 20, C.YARDS_ALLOWED to 100)
        db.week("k1", 1, C.FG_MADE_0_39 to 1)
        val none = ScoringPresets.PPR.copy(yardsAllowedTiers = emptyList())
        val rows = db.grid(fantasy(none)).associate { it.playerId to it.value(FANTASY_POINTS)!! }
        assertEquals(0.0, rows.getValue("DST_KC"), EPS) // 18-21 points allowed: 0, and no yards tiers
        assertEquals(3.0, rows.getValue("k1"), EPS)
        // With ESPN's tiers a kicker still scores no yards: his pivot column is NULL, not a shutout.
        assertEquals(3.0, db.grid(fantasy(ScoringPresets.PPR)).single { it.playerId == "k1" }.value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `a profile's own yards tiers score yards allowed, and equal profiles give identical SQL`() {
        db.player("DST_KC", "KC D/ST", position = "DST", team = "KC")
        db.week("DST_KC", 1, C.YARDS_ALLOWED to 320)
        val custom = ScoringPresets.PPR.copy(pointsAllowedTiers = emptyList(), yardsAllowedTiers = listOf(ScoringTier(0, 10.0), ScoringTier(300, -2.0)))
        assertEquals(-2.0, db.grid(fantasy(custom)).single().value(FANTASY_POINTS)!!, EPS)
        val spec = { p: ScoringProfile -> StatQuerySpec(2025, WeekRange(1, 18), listOf(FANTASY_POINTS), scoring = p) }
        assertEquals(StatQueryBuilder.build(spec(custom)).sql, StatQueryBuilder.build(spec(custom.copy())).sql)
    }
```

Add `import dev.gridiron.core.model.ScoringTier` to that file. If `StatQueryBuilder.build` or `fantasy(...)`'s row type differs from the above, mirror how the neighbouring tests in that file build a query and read a row (open the file's `fantasy` helper and the existing "equal profiles give identical SQL" test in `SqlSafetyTest.kt` and copy their calls); the assertions stay the same.

Append to `ScorerTest.kt`:

```kotlin
    @Test
    fun `a D-ST week scores its yards tier, and a week without yards scores none`() {
        val defense = mapOf(Components.DST_SACKS to 2.0, Components.POINTS_ALLOWED to 0.0, Components.YARDS_ALLOWED to 250.0)
        // 2 sacks, 5 for a shutout, 2 for 200-299 yards.
        assertEquals(9.0, score(defense, ScoringPresets.STANDARD, Position.DST), 1e-9)
        assertEquals(7.0, score(defense - Components.YARDS_ALLOWED, ScoringPresets.STANDARD, Position.DST), 1e-9)
    }
```

- [ ] **Step 6: Run to verify failure**

Run: `./gradlew :core:statquery:test --tests "dev.gridiron.core.statquery.ScoringQueryTest" :core:projections:test --tests "dev.gridiron.core.projections.ScorerTest"`
Expected: compile FAIL (`Components.YARDS_ALLOWED`).

- [ ] **Step 7: Implement the component, SQL and scorer**

`Component.kt`, after `POINTS_ALLOWED`:

```kotlin
    public val YARDS_ALLOWED: Component = Component("yards_allowed")
```
and update the comment above the D/ST components to "Sparse, except points and yards allowed, whose zero is a shutout; the profile's tiers score them."

`Scoring.kt` (`SCORING_COMPONENTS`): change the trailing part to

```kotlin
            // Points and yards allowed are read by the profile's tiers, not a rule.
            C.POINTS_ALLOWED + C.YARDS_ALLOWED
```
written as list elements: `listOf(C.POINTS_ALLOWED, C.YARDS_ALLOWED)` added with `+` in the same expression (keep `.distinct().sortedBy { it.id }`).

`StatQueryBuilder.kt`:

```kotlin
private val SPECIAL_COMPONENTS: List<Component> =
    (SPECIAL_RULE_LIST.flatMap { RULE_INPUTS.getValue(it).actual }.map { it.component } + Components.POINTS_ALLOWED + Components.YARDS_ALLOWED)
        .distinct()
        .sortedBy { it.id }
```
Replace `tiers(profile)` with:

```kotlin
    /**
     * One week's tier from a profile's own [tiers] for [component], checked
     * highest first. A week with none (a kicker's) scores none: the pivot's
     * column is NULL there, while a shutout stores 0.
     */
    private fun tiers(tiers: List<ScoringTier>, component: Component): String {
        if (tiers.isEmpty()) return "0"
        val value = "ws.s${SPECIAL_COMPONENTS.indexOf(component)}"
        val cases = tiers.asReversed().joinToString(" ") { "WHEN $value >= ${int(it.min)} THEN ${real(it.points)}" }
        return "(CASE WHEN $value IS NULL THEN 0 $cases ELSE 0 END)"
    }
```
and the `fs` line becomes:

```kotlin
        line("       , ${points(profile, SPECIAL_RULE_LIST, expected = false, bonuses = false, wSpecial)} + ${tiers(profile.pointsAllowedTiers, Components.POINTS_ALLOWED)} + ${tiers(profile.yardsAllowedTiers, Components.YARDS_ALLOWED)} AS fp")
```
Add `import dev.gridiron.core.model.ScoringTier`. Update the class KDoc mention of "points-allowed tiers see single games" to "points- and yards-allowed tiers".

`Scorer.kt`, after the points-allowed line:

```kotlin
    components[Components.YARDS_ALLOWED]?.let { total += profile.yardsAllowedPoints(it) }
```
`Backtest.kt` line 32: add `+ Components.YARDS_ALLOWED` beside `Components.POINTS_ALLOWED`.

- [ ] **Step 8: Run tests**

Run: `./gradlew :core:model:test :core:statquery:test :core:projections:test`
Expected: PASS. (Real-database tests skip or pass on the old `stats.db`: it has no yards rows, so no D/ST week scores yards until Task 2 rebuilds it.)

- [ ] **Step 9: Commit**

```bash
git add -A core/model core/statquery core/projections
git commit -m "feat: score a D/ST's yards allowed through per-profile tiers"
```
(with the two attribution lines)

---

### Task 2: The stat in the data, the Grid column and Python parity

**Files:**
- Modify: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Dst.kt`, `Metrics.kt`, `db/Schema.kt`, `validate/DatabaseChecks.kt`
- Modify: `core/statquery/src/main/kotlin/dev/gridiron/core/statquery/StatColumn.kt`
- Modify: `etl/gridiron_etl/teams.py`, `metrics.py`, `validate.py`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/DstTest.kt`, `MetricsTest.kt`, `IngestPipelineTest.kt`, `validate/DatabaseChecksTest.kt`, `core/statquery/src/test/kotlin/dev/gridiron/core/statquery/RealDatabaseContractTest.kt`, `etl/tests/test_teams.py`, `etl/tests/test_registry.py`

**Interfaces:**
- Consumes: `Components.YARDS_ALLOWED`, `ScoringTier` (Task 1).
- Produces: metric id `yards_allowed` in `player_week_stat` for every D/ST week; `StatColumn.YARDS_ALLOWED("yards_allowed", Total(C.YARDS_ALLOWED), higherIsBetter = false)`; a rebuilt `etl/build/stats.db` and `etl/build/accuracy.db` with yards (needed by Tasks 3–4's real-database tests).

- [ ] **Step 1: Write the failing ingest tests**

`DstTest.kt`: in the test asserting the exact `week.values` map add `"yards_allowed" to 300.0,` after `"points_allowed" to 17.0,` (the `row(...)` helper already has yards 300.0), and add:

```kotlin
    @Test
    fun `a shutout of no yards is still stored`() {
        val week = dstWeeks(listOf(row(0.0).copy(yardsAllowed = 0.0))).single()
        assertEquals(0.0, week.values["yards_allowed"])
    }
```

`MetricsTest.kt`: add `"yards_allowed"` to the `projected` list (line 77) and update the defense test:

```kotlin
    @Test
    fun `team defense metrics are visible, and only points and yards allowed keep their zeros`() {
        val defense = listOf("dst_sacks", "dst_interceptions", "dst_fumble_recoveries", "dst_tds", "dst_safeties")
        val allowed = listOf("points_allowed", "yards_allowed")
        for (id in defense + allowed) {
            val m = byId.getValue(id)
            assertFalse(m.isInternal, id)
            assertEquals(listOf("DST"), m.positions, id)
            assertEquals("defense", m.group, id)
            assertEquals(id !in allowed, id in SPARSE_METRIC_IDS, id)
        }
        assertEquals("negbinom", byId.getValue("dst_sacks").distFamily)
        assertEquals("poisson", byId.getValue("dst_tds").distFamily)
        for (id in allowed) {
            assertEquals("normal", byId.getValue(id).distFamily, id)
            assertFalse(byId.getValue(id).higherIsBetter, id)
        }
        assertEquals("YA", byId.getValue("yards_allowed").abbreviation)
    }
```
(If the `Metric` property for the abbreviation isn't named `abbreviation`, use the one `Metrics.kt` declares for "PA".) Replace the old test of the same purpose (remove `team defense metrics are visible, and only points allowed keeps its zeros`).

`IngestPipelineTest.kt` line ~524: the shutout D/ST rows now include yards:

```kotlin
        // The fixture's plays gain nothing: both defenses allowed 0 points and 0 yards, a shutout, and nothing else.
        assertEquals(
            listOf(listOf("g", "1.0"), listOf("points_allowed", "0.0"), listOf("yards_allowed", "0.0")),
```
If the fixture's plays gain yards (check the query result on failure), use the yards the run reports after confirming them against the fixture's play rows.

`DatabaseChecksTest.kt`: extend the D/ST check test:

```kotlin
    @Test
    fun `every team defense week has its points and yards allowed`() {
        assertEquals(emptyList<String>(), dstProblems("g" to 1.0, "points_allowed" to 0.0, "yards_allowed" to 0.0))
        assertTrue(dstProblems("g" to 1.0, "dst_sacks" to 2.0).any { "points allowed" in it })
        assertTrue(dstProblems("g" to 1.0, "points_allowed" to 3.0).any { "yards allowed" in it })
    }
```
and add a range check test: `assertTrue(dstProblems("g" to 1.0, "points_allowed" to 3.0, "yards_allowed" to 900.0).any { "yards_allowed" in it })`.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:ingest:test`
Expected: FAIL (`yards_allowed` unwritten, unregistered).

- [ ] **Step 3: Implement ingest**

`Dst.kt`: KDoc "Points and yards allowed are stored as numbers", and add `"yards_allowed" to r.yardsAllowed,` after the points line.

`Metrics.kt` — extend the defense block:

```kotlin
} + listOf(
    Metric(
        "points_allowed", "Points Allowed", "PA", "defense", "Points the opponent scored, however it scored them.",
        positions = TEAM_DEFENSE, higherIsBetter = false, decimals = 0,
    ),
    Metric(
        "yards_allowed", "Yards Allowed", "YA", "defense", "Net yards the opponent gained: rushing plus passing, sacks subtracted.",
        positions = TEAM_DEFENSE, higherIsBetter = false, decimals = 0,
    ),
)
```
(the surrounding `DEFENSE` value is a `List<Metric>`; the trailing `+ Metric(...)` becomes `+ listOf(...)`.) In `DIST_FAMILIES`:

```kotlin
    // One game's points and yards allowed: the simulation draws them from a joint normal and scores their tiers.
    put("points_allowed", "normal")
    put("yards_allowed", "normal")
```

`db/Schema.kt`: `INGEST_VERSION = 5`.

`validate/DatabaseChecks.kt`: add `RangeCheck("yards_allowed", 0.0, 800.0, "net yards in one game"),` to `RANGE_CHECKS`, and generalize the unscored check:

```kotlin
    // The profile's tiers score points and yards allowed, so every D/ST week needs both (a shutout stores 0).
    for ((id, label) in listOf("points_allowed" to "points allowed", "yards_allowed" to "yards allowed")) {
        val unscored = conn.count(
            """SELECT COUNT(*) FROM (
                 SELECT MAX(metric_id = '$id') AS scored
                 FROM player_week_stat WHERE player_id LIKE 'DST\_%' ESCAPE '\'
                 GROUP BY player_id, season, week)
               WHERE scored = 0""",
        )
        if (unscored > 0) problems += "D/ST weeks without their $label: $unscored"
    }
```

`StatColumn.kt`, after `POINTS_ALLOWED`:

```kotlin
    YARDS_ALLOWED("yards_allowed", Total(C.YARDS_ALLOWED), higherIsBetter = false),
```

- [ ] **Step 4: Python parity**

`teams.py` `dst_weekly`, after `points_allowed=...`:

```python
        yards_allowed=pl.col("yards_allowed").cast(pl.Float64),
```
`metrics.py`, after the `points_allowed` Metric:

```python
    Metric("yards_allowed", "Yards Allowed", "YA", "defense",
           "Net yards the opponent gained: rushing plus passing, sacks subtracted.",
           positions=("DST",), higher_is_better=False, decimals=0),
```
and `"yards_allowed": "normal",` beside `"points_allowed": "normal",` in the distribution table (line ~342). `validate.py`: `RangeCheck("yards_allowed", 0.0, 800.0, "net yards in one game"),` and the same two-metric unscored loop as Kotlin (mirror the existing query at ~line 200 for `yards_allowed`, message "D/ST weeks without their yards allowed: N").

Tests: `etl/tests/test_teams.py`:

```python
def test_a_team_week_carries_its_yards_allowed_and_a_shutout_keeps_its_zero():
    row = teams.dst_weekly(_defense(17)).to_dicts()[0]
    assert row["yards_allowed"] == 300
    zero = _defense(0).with_columns(yards_allowed=pl.lit(0.0))
    assert teams.dst_weekly(zero).to_dicts()[0]["yards_allowed"] == 0
```
`etl/tests/test_registry.py` line 77–80: change the `all(...)` list to `DEFENSE + ["points_allowed", "yards_allowed"]` and add:

```python
    assert not METRICS["yards_allowed"].internal
    assert "yards_allowed" not in sparse_metric_ids()
    assert METRICS["yards_allowed"].dist_family == "normal"
    assert not METRICS["yards_allowed"].higher_is_better
```

- [ ] **Step 5: Run unit tests**

Run: `./gradlew :core:ingest:test :core:statquery:test` and `cd etl && python -m pytest tests/ -q`
Expected: PASS (statquery real-DB tests still read the old database; `StatColumn.YARDS_ALLOWED` shows no rows there, so `RealDatabaseContractTest` may report the column missing from `metric`. That is fixed by the rebuild in Step 6.)

- [ ] **Step 6: Rebuild the databases and pin the contract**

```bash
./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025 2026" -Pout=etl/build/stats.db
./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025" -Pout=etl/build/accuracy.db
sqlite3 etl/build/stats.db "select count(*), min(value), max(value) from player_week_stat where metric_id='yards_allowed'"
```
Expected: about 1,140+ rows for 2024–2025 plus 2026's; min ≥ 0, max ≤ 800. (No network in the container? Then the builder fails on download: use the previous `stats.db` sources cached by the builder if any, otherwise run the parity job in CI and skip the local rebuild, noting it in the PR.)

In `RealDatabaseContractTest.kt` line ~223 add `yardsAllowedTiers = listOf(ScoringTier(0, 6.0), ScoringTier(250, 2.5), ScoringTier(400, -1.5), ScoringTier(520, -6.5)),` to the custom profile, and beside line ~254:

```kotlin
        s["yards_allowed"]?.let { allowed -> fp += profile.yardsAllowedTiers.last { allowed >= it.min }.points }
```
(written by hand, like the points line above it; add `import dev.gridiron.core.model.ScoringTier`).

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:statquery:test :core:data:test`
Expected: PASS except the known speed test. Then parity: `python etl/tools/parity.py etl/build/parity/py.db etl/build/parity/kt.db` after building both for 2025 (`python -m gridiron_etl.build --seasons 2025 --out etl/build/parity/py.db` and the Kotlin task with `-Pout=etl/build/parity/kt.db`); expected: agree, including `yards_allowed`. If offline, leave parity to CI.

- [ ] **Step 7: Commit**

```bash
git add -A core/ingest core/statquery etl
git commit -m "feat: D/ST yards allowed as a weekly stat, in the Kotlin builder and the Python parity reference"
```

---

### Task 3: Projections: forecast, expectation and the joint Monte Carlo

**Files:**
- Modify: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/ForecastConstants.kt`, `Defense.kt`, `Units.kt`, `Inputs.kt`
- Modify: `core/projections/src/main/kotlin/dev/gridiron/core/projections/ProjectedScore.kt`, `MonteCarlo.kt`, `ProjectedPoints.kt`
- Test: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/DefenseTest.kt`, `ForecastEngineTest.kt`, `core/projections/src/test/kotlin/dev/gridiron/core/projections/ProjectedScoreTest.kt`, `MonteCarloTest.kt`, `core/data/src/test/kotlin/dev/gridiron/core/data/DstCorrelationTest.kt` (create), `AccuracyRepositoryTest.kt`

**Interfaces:**
- Consumes: `Components.YARDS_ALLOWED`, `ScoringProfile.expectedYardsAllowedPoints` (Task 1); `yards_allowed` in the database (Task 2).
- Produces: `K.DST_YA_K`, `K.DST_YA_SCRIPT_ELASTICITY`, `K.DST_YA_CV`; `internal const val YARDS_ALLOWED = "yards_allowed"` (forecast); `DefenseLeague(perGame, pointsAllowed, yardsAllowed = 0.0)`; `defenseStages(own, ownAllowed, factors, opponentScores, league, implied, ownYards = 0.0, yardsFactor = 1.0)`; `public const val DST_POINTS_YARDS_CORRELATION: Double` in `:core:projections`; a stored `yards_allowed` projection (mean per game, variance = (`DST_YA_CV` × mean)², `g` = 1).

- [ ] **Step 1: Write the failing forecast tests**

`DefenseTest.kt`:

```kotlin
    @Test
    fun `yards allowed follow the same stages as points allowed, and are absent without a yards history`() {
        val league = DefenseLeague(DST_STATS.associateWith { 1.0 }, 22.0, yardsAllowed = 330.0)
        val own = DST_STATS.associateWith { 2.0 }
        // Expected points after the matchup: 20 * 33 / 22 = 30; a line of 24 is 0.8 of that.
        val stages = defenseStages(own, 20.0, emptyMap(), opponentScores = 33.0, league = league, implied = 24.0, ownYards = 300.0, yardsFactor = 1.1)
        assertEquals(300.0, stages.baseline.getValue(YARDS_ALLOWED), 1e-12)
        assertEquals(330.0, stages.afterMatchup.getValue(YARDS_ALLOWED), 1e-12)
        assertEquals(330.0 * Math.pow(0.8, K.DST_YA_SCRIPT_ELASTICITY), stages.final.getValue(YARDS_ALLOWED), 1e-9)

        // No line: the final stage is the matchup's. A wild line is capped like every game script.
        val noLine = defenseStages(own, 20.0, emptyMap(), 33.0, league, implied = null, ownYards = 300.0, yardsFactor = 1.1)
        assertEquals(330.0, noLine.final.getValue(YARDS_ALLOWED), 1e-12)
        val wild = defenseStages(own, 20.0, emptyMap(), 33.0, league, implied = 100.0, ownYards = 300.0, yardsFactor = 1.0)
        assertEquals(300.0 * Math.pow(K.IMPLIED_RATIO_MAX, K.DST_YA_SCRIPT_ELASTICITY), wild.final.getValue(YARDS_ALLOWED), 1e-9)

        // Without yards (an old database, a fixture): nothing is emitted.
        val none = defenseStages(own, 20.0, emptyMap(), 33.0, DefenseLeague(DST_STATS.associateWith { 1.0 }, 22.0), 17.0)
        assertEquals(false, YARDS_ALLOWED in none.final.keys)
    }
```
The existing test asserting `stages.final.keys == DST_STATS.toSet() + "points_allowed"` must still pass (defaults).

`ForecastEngineTest.kt`: in `unitWeek` add the yards to the D/ST row:

```kotlin
        db.week(
            "DST_$team", season, week, team,
            "dst_sacks" to sacks, "dst_interceptions" to 1.0, "points_allowed" to allowed, "yards_allowed" to allowed * 15.0,
        )
```
Update the note regex (line ~472) to `vs CCC: scores \d+\.\d pts, \d+ yards, gives up \d+\.\d sacks, \d+\.\d turnovers a game` and add:

```kotlin
    @Test
    fun `a team defense's yards allowed are projected as their own stat with a spread that scales with the mean`() {
        league("yards.db", units = true).use { db ->
            run(db)
            val rows = db.query(
                "SELECT player_id, week, stage, mean, variance FROM player_week_projection " +
                    "WHERE player_id LIKE 'DST%' AND metric_id = 'yards_allowed'",
            )
            assertTrue(rows.size >= 8, "$rows")
            for (row in rows) {
                val mean = row[3]!!.toDouble()
                assertTrue(mean in 200.0..450.0, "$row")
                assertEquals(K.DST_YA_CV * mean * (K.DST_YA_CV * mean), row[4]!!.toDouble(), 1e-6, "$row")
            }
            // Rest of season sums the remaining games, like points allowed.
            assertTrue(rosMean(db, "DST_AAA", "yards_allowed") > 1.5 * finalMean(db, "DST_AAA", 3, "yards_allowed"))
            // A kicker has none.
            assertEquals(emptyList<List<String?>>(), db.query("SELECT 1 FROM player_week_projection WHERE player_id LIKE 'K%' AND metric_id = 'yards_allowed'"))
        }
    }

    @Test
    fun `a defense with no yards history is projected without yards`() {
        league("no-yards.db", units = true).use { db ->
            db.exec("DELETE FROM player_week_stat WHERE metric_id = 'yards_allowed'")
            run(db)
            assertEquals(emptyList<List<String?>>(), db.query("SELECT 1 FROM player_week_projection WHERE metric_id = 'yards_allowed'"))
            assertTrue(finalMean(db, "DST_AAA", 3, "points_allowed") > 0.0)
        }
    }
```
(If `TestDb` has no `exec`, use the helper the other tests use to run raw SQL — grep `TestDb` for its write method — `db.conn.execSQL(...)`-style; the assertions stay.)

- [ ] **Step 2: Write the failing projection tests**

`ProjectedScoreTest.kt`:

```kotlin
    @Test
    fun `a D-ST's yards tiers are scored in expectation beside its points tiers, per game`() {
        val week = listOf(
            ProjectionComponent("points_allowed", 20.0, 100.0, "normal"),
            ProjectionComponent("yards_allowed", 330.0, 82.5 * 82.5, "normal"),
            ProjectionComponent("g", 1.0, 0.0),
        )
        val expected = ppr.expectedPointsAllowedPoints(20.0, 10.0) + ppr.expectedYardsAllowedPoints(330.0, 82.5)
        assertEquals(expected, projectedScore(week, ppr, Position.DST), 1e-9)

        // Three games: 990 yards in all, a variance of 3 * 82.5^2: 330 and 82.5 a game, never the tier of 990.
        val ros = listOf(
            ProjectionComponent("yards_allowed", 990.0, 3 * 82.5 * 82.5, "normal"),
            ProjectionComponent("g", 3.0, 0.0),
        )
        assertEquals(3 * ppr.expectedYardsAllowedPoints(330.0, 82.5), projectedScore(ros, ppr, Position.DST), 1e-9)
    }

    @Test
    fun `a projection without yards scores none`() {
        val week = listOf(ProjectionComponent("points_allowed", 17.6, 100.0, "normal"), ProjectionComponent("g", 1.0, 0.0))
        assertEquals(ppr.expectedPointsAllowedPoints(17.6, 10.0), projectedScore(week, ppr, Position.DST), 1e-9)
    }
```

`MonteCarloTest.kt` (uses the file's `twoTiers` pattern):

```kotlin
    private val yardTiers = ScoringPresets.PPR.copy(
        id = "u2", name = "Yards only",
        pointsAllowedTiers = listOf(ScoringTier(0, 10.0), ScoringTier(21, -4.0)),
        yardsAllowedTiers = listOf(ScoringTier(0, 3.0), ScoringTier(330, -3.0)),
    )

    private fun defenseGame(games: Double = 1.0) = listOf(
        DistributionSpec(Components.POINTS_ALLOWED, DistributionFamily.NORMAL, mean = 20.0 * games, variance = 100.0 * games),
        DistributionSpec(Components.YARDS_ALLOWED, DistributionFamily.NORMAL, mean = 330.0 * games, variance = 80.0 * 80.0 * games),
        DistributionSpec(Components.GAMES, DistributionFamily.GAMMA, mean = games, variance = 0.0),
    )

    @Test
    fun `a game's points and yards allowed are drawn together, each scored once`() {
        val result = simulate(defenseGame(), yardTiers, Position.DST, draws = 20_000)
        // Points +10 / -4, yards +3 / -3: 13, 7, -1 or -7, and nothing in between.
        for (p in listOf(result.p10, result.p25, result.p50, result.p90)) assertTrue(p in setOf(-7.0, -1.0, 7.0, 13.0), "$p")
    }

    @Test
    fun `rest of season draws each game's points and yards`() {
        val result = simulate(defenseGame(2.0), yardTiers, Position.DST, draws = 20_000)
        // Two games of +13, +7, -1 or -7 each, so only sums of two of those. The tier of the summed 660 yards and 40 points
        // would be one -3 and one -4: -7, which no pair of games adds up to.
        val each = listOf(13.0, 7.0, -1.0, -7.0)
        val possible = each.flatMap { a -> each.map { b -> a + b } }.toSet()
        for (p in listOf(result.p10, result.p25, result.p50, result.p90)) assertTrue(p in possible, "$p")
        assertTrue(result.p10 < result.p90, "$result")
    }

    @Test
    fun `without yards the draws are exactly what they were`() {
        val pointsOnly = listOf(
            DistributionSpec(Components.POINTS_ALLOWED, DistributionFamily.NORMAL, mean = 20.0, variance = 100.0),
            DistributionSpec(Components.GAMES, DistributionFamily.GAMMA, mean = 1.0, variance = 0.0),
        )
        assertEquals(simulate(pointsOnly, twoTiers, Position.DST, draws = 2_000), simulate(pointsOnly, twoTiers, Position.DST, draws = 2_000))
        assertEquals(-4.0, simulate(pointsOnly, twoTiers, Position.DST, draws = 2_000).p10, 1e-9)
    }
```
Add `import dev.gridiron.core.model.ScoringTier`. To make the correlation actually measurable, also add a `internal fun drawPair(...)` seam test: 

```kotlin
    @Test
    fun `the joint draw reproduces the correlation`() {
        val rng = java.util.SplittableRandom(11L)
        val n = 40_000
        val xs = DoubleArray(n); val ys = DoubleArray(n)
        for (i in 0 until n) { val (a, b) = drawJointAllowed(20.0, 10.0, 330.0, 80.0, DST_POINTS_YARDS_CORRELATION, rng); xs[i] = a; ys[i] = b }
        val mx = xs.average(); val my = ys.average()
        val cov = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / n
        val corr = cov / (Math.sqrt(xs.sumOf { (it - mx) * (it - mx) } / n) * Math.sqrt(ys.sumOf { (it - my) * (it - my) } / n))
        assertEquals(DST_POINTS_YARDS_CORRELATION, corr, 0.03)
    }
```
(The draws are rounded and floored at 0; at these means the floor almost never binds, so the tolerance holds.)

`core/data/src/test/kotlin/dev/gridiron/core/data/DstCorrelationTest.kt` (create; skipped without a database):

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.projections.DST_POINTS_YARDS_CORRELATION
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

class DstCorrelationTest {
    @Test
    fun `the simulation's correlation is the one the real games show, within 0-05`() = runTest {
        val path = StatsDb.path
        assumeTrue(path != null)
        JdbcQueryExecutor(path!!).use { executor ->
            val pairs = executor.query(
                "SELECT points_allowed, yards_allowed FROM team_week_defense WHERE season IN (2024, 2025)", emptyList(),
            ) { row -> row.getDouble(0) to row.getDouble(1) }
            assertEquals(true, pairs.size > 500, "${pairs.size} team-games")
            val mx = pairs.map { it.first }.average(); val my = pairs.map { it.second }.average()
            val cov = pairs.sumOf { (it.first - mx) * (it.second - my) }
            val corr = cov / (sqrt(pairs.sumOf { (it.first - mx) * (it.first - mx) }) * sqrt(pairs.sumOf { (it.second - my) * (it.second - my) }))
            assertEquals(DST_POINTS_YARDS_CORRELATION, corr, 0.05)
        }
    }
}
```
Use the same `JdbcQueryExecutor` calling convention as neighbouring tests in `core/data/src/test` (open `TeamsRepositoryTest.kt` or `AccuracyRepositoryTest.kt` and copy how they run a query and read a row; adapt only the query and row mapping). The database is required (`assumeTrue`), and CI's parity job sets `GRIDIRON_STATS_DB`.

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew :core:forecast:test :core:projections:test`
Expected: compile FAIL (`YARDS_ALLOWED`, `K.DST_YA_*`, `drawJointAllowed`, `DST_POINTS_YARDS_CORRELATION`).

- [ ] **Step 4: Implement the forecast**

`ForecastConstants.kt`: `FORECAST_VERSION = 5`; after `DST_CAP` add:

```kotlin
    // Yards allowed (net): a defense's own rate is shrunk this many games toward the league's. The opponent's yards
    // use DST_OPP_K and DST_CAP. A line scales them by (its ratio to expected points) ^ DST_YA_SCRIPT_ELASTICITY,
    // since yards move about half as far as points do. The spread is DST_YA_CV of the mean: real games' residual
    // around each team-season's own average was 0.239 of the league mean (2024-2025, 1,140 team-games); there is no
    // posted yards line to measure misses against.
    const val DST_YA_K = 6.0
    const val DST_YA_SCRIPT_ELASTICITY = 0.5
    const val DST_YA_CV = 0.25
```
`Inputs.kt` `UNIT_METRICS`: add `"yards_allowed"` after `"points_allowed"`.

`Defense.kt`:

```kotlin
internal const val YARDS_ALLOWED: String = "yards_allowed"

/** League per-game averages of every D/ST stat, of points allowed and of yards allowed (0 when the database has none), before a week. */
internal class DefenseLeague(val perGame: Map<String, Double>, val pointsAllowed: Double, val yardsAllowed: Double = 0.0)

internal fun defenseLeague(games: List<PlayerGame>): DefenseLeague? {
    if (games.isEmpty()) return null
    return DefenseLeague(
        DST_STATS.associateWith { stat -> games.sumOf { it[stat] } / games.size },
        games.sumOf { it["points_allowed"] } / games.size,
        games.sumOf { it[YARDS_ALLOWED] } / games.size,
    )
}

/** How a line moves a defense's yards: the opponent's implied points over the matchup's expected points, capped, damped. */
internal fun yardsScript(implied: Double?, expectedPoints: Double): Double {
    if (implied == null || expectedPoints <= 0.0) return 1.0
    return (implied / expectedPoints).coerceIn(K.IMPLIED_RATIO_MIN, K.IMPLIED_RATIO_MAX).pow(K.DST_YA_SCRIPT_ELASTICITY)
}
```
`defenseStages` gains two trailing parameters and yards in each stage (only when [ownYards] > 0):

```kotlin
internal fun defenseStages(
    own: Map<String, Double>,
    ownAllowed: Double,
    factors: Map<String, Double>,
    opponentScores: Double,
    league: DefenseLeague,
    implied: Double?,
    ownYards: Double = 0.0,
    yardsFactor: Double = 1.0,
): DefenseStages {
    val matchupPoints = if (league.pointsAllowed > 0.0) ownAllowed * opponentScores / league.pointsAllowed else ownAllowed
    val matched = own.mapValues { (stat, rate) -> rate * (factors[stat] ?: 1.0) }
    val matchupYards = ownYards * yardsFactor
    val yards = { yardsAllowed: Double -> if (ownYards > 0.0) mapOf(YARDS_ALLOWED to yardsAllowed) else emptyMap() }
    return DefenseStages(
        baseline = own + (POINTS_ALLOWED to ownAllowed) + yards(ownYards),
        afterMatchup = matched + (POINTS_ALLOWED to matchupPoints) + yards(matchupYards),
        final = matched + (POINTS_ALLOWED to (implied ?: matchupPoints)) + yards(matchupYards * yardsScript(implied, matchupPoints)),
    )
}
```
Add `import kotlin.math.pow`. Update the KDoc: "Yards allowed follow the same stages: the rate, the opponent's yards, and a damped game script."

`Units.kt`:
- `Defense` gains `val ownYards: Double`; `defense()` passes `unitRate(own.map { it[YARDS_ALLOWED] }, league.yardsAllowed, K.DST_YA_K)` as the last argument (with `league.yardsAllowed = 0` and no yards it is 0, so nothing is emitted).
- `UnitWeek.init`: inside the `for (g in defenses)` loop add `byStat.getOrPut(YARDS_ALLOWED) { ArrayList() } += g[YARDS_ALLOWED]`.
- In `stages()` for `Defense`: 

```kotlin
            val yardsFactor = opponentFactor(against[YARDS_ALLOWED].orEmpty(), league.yardsAllowed)
            val s = defenseStages(u.own, u.ownAllowed, factors, opponentScores, league, implied, u.ownYards, yardsFactor)
```
- `variance()`: add `YARDS_ALLOWED -> (K.DST_YA_CV * mean).let { it * it }`.
- `points()`:

```kotlin
    private fun points(components: Map<String, Double>, sd: Double): Double =
        referencePoints(components) +
            (components[POINTS_ALLOWED]?.let { ScoringPresets.PPR.expectedPointsAllowedPoints(it, sd) } ?: 0.0) +
            (components[YARDS_ALLOWED]?.let { ScoringPresets.PPR.expectedYardsAllowedPoints(it, K.DST_YA_CV * it) } ?: 0.0)
```
and update its KDoc ("...for a D/ST, the preset tiers' expected points for its points and yards allowed").
- `defenseNote`: the opponent's yards join the note only when the league has yards:

```kotlin
private fun defenseNote(opponent: String, scores: Double, against: Map<String, List<Double>>, league: DefenseLeague): String {
    fun rate(stat: String) = unitRate(against[stat].orEmpty(), league.perGame.getValue(stat), K.DST_OPP_K)
    val yards = if (league.yardsAllowed > 0.0) {
        String.format(Locale.US, ", %.0f yards", unitRate(against[YARDS_ALLOWED].orEmpty(), league.yardsAllowed, K.DST_OPP_K))
    } else {
        ""
    }
    return String.format(
        Locale.US, "vs %s: scores %.1f pts%s, gives up %.1f sacks, %.1f turnovers a game",
        opponent, scores, yards, rate("dst_sacks"), rate("dst_interceptions") + rate("dst_fumble_recoveries"),
    )
}
```
Its KDoc example becomes "vs KC: scores 24.1 pts, 331 yards, gives up 2.9 sacks, 1.6 turnovers a game".

`withGame` needs no change (yards only appear with points allowed).

- [ ] **Step 5: Implement the phone side**

`ProjectedScore.kt`:

```kotlin
public fun projectedScore(components: List<ProjectionComponent>, profile: ScoringProfile, position: Position?): Double {
    val allowed = components.firstOrNull { it.metricId == Components.POINTS_ALLOWED.id }
    val yards = components.firstOrNull { it.metricId == Components.YARDS_ALLOWED.id }
    val tiered = setOf(Components.POINTS_ALLOWED.id, Components.YARDS_ALLOWED.id)
    val means = components.filter { it.metricId !in tiered }.associate { Component(it.metricId) to it.mean }
    val scored = score(means, profile, position)
    if (allowed == null && yards == null) return scored
    val games = (means[Components.GAMES] ?: 1.0).coerceAtLeast(1.0)
    fun perGame(c: ProjectionComponent, expected: (Double, Double) -> Double) =
        games * expected(c.mean / games, sqrt(c.variance.coerceAtLeast(0.0) / games))
    return scored +
        (allowed?.let { perGame(it, profile::expectedPointsAllowedPoints) } ?: 0.0) +
        (yards?.let { perGame(it, profile::expectedYardsAllowedPoints) } ?: 0.0)
}
```
Update the KDoc: "a D/ST's points and yards allowed, whose tiers are scored in expectation…".

`ProjectedPoints.kt`, beside `RANGE_WIDENING`:

```kotlin
/**
 * Correlation between one game's points allowed and yards allowed, measured
 * on every team-game of 2024-2025 (0.666, 1,140 games) and pinned by
 * `DstCorrelationTest` within 0.05. The Monte Carlo draws the two jointly.
 */
public const val DST_POINTS_YARDS_CORRELATION: Double = 0.67
```
`MonteCarlo.kt`: rewrite the D/ST branch of `simulate` and `drawAllowed`:

```kotlin
    // A D/ST's points and yards allowed score a tier per game, so each of its `g` games is drawn on its own,
    // the two together, and scored through the profile's tiers; everything else is drawn once and scored by score().
    val allowed = distributions.firstOrNull { it.component == Components.POINTS_ALLOWED }
    val yards = distributions.firstOrNull { it.component == Components.YARDS_ALLOWED }
    val independent = distributions.filter { it.component != Components.POINTS_ALLOWED && it.component != Components.YARDS_ALLOWED }
    val games = distributions.firstOrNull { it.component == Components.GAMES }?.mean?.roundToInt()?.coerceAtLeast(1) ?: 1
    fun perGameMean(d: DistributionSpec?) = (d?.mean ?: 0.0) / games
    fun perGameSd(d: DistributionSpec?) = sqrt((d?.variance ?: 0.0).coerceAtLeast(0.0) / games)

    for (i in 0 until draws) {
        for (spec in independent) {
            componentMap[spec.component] = drawOne(spec, rng)
        }
        var points = score(componentMap, profile, position)
        if (allowed != null || yards != null) {
            repeat(games) {
                val (pa, ya) = drawJointAllowed(
                    perGameMean(allowed), perGameSd(allowed), perGameMean(yards), perGameSd(yards), DST_POINTS_YARDS_CORRELATION, rng,
                )
                if (allowed != null) points += profile.pointsAllowedPoints(pa)
                if (yards != null) points += profile.yardsAllowedPoints(ya)
            }
        }
        samples[i] = points
    }
```
and:

```kotlin
/** One game's points allowed: Normal([mean], [sd]) on whole points, never below 0. */
private fun drawAllowed(mean: Double, sd: Double, rng: SplittableRandom): Double =
    Math.round(mean + sd * gaussian(rng)).toDouble().coerceAtLeast(0.0)

/**
 * One game's points and yards allowed, Normal on whole units, never below 0,
 * correlated at [correlation]. One gaussian draw decides the points, and the
 * yards take [correlation] of it plus an independent draw for the rest. With
 * no yards (a zero spread and mean) the draw is the same as [drawAllowed]'s.
 */
internal fun drawJointAllowed(
    pointsMean: Double, pointsSd: Double, yardsMean: Double, yardsSd: Double, correlation: Double, rng: SplittableRandom,
): Pair<Double, Double> {
    val z1 = gaussian(rng)
    val points = Math.round(pointsMean + pointsSd * z1).toDouble().coerceAtLeast(0.0)
    if (yardsSd <= 0.0 && yardsMean <= 0.0) return points to 0.0
    val z2 = correlation * z1 + sqrt(1.0 - correlation * correlation) * gaussian(rng)
    return points to Math.round(yardsMean + yardsSd * z2).toDouble().coerceAtLeast(0.0)
}
```
Keep `drawOne`'s `NORMAL` case calling `drawAllowed(spec.mean, sqrt(spec.variance), rng)` (the single-stat path is unchanged). A points-only D/ST draws `gaussian` once per game, in the same order as before, so seeded points-only results don't move.

- [ ] **Step 6: Run tests**

Run: `./gradlew :core:forecast:test :core:projections:test`
Expected: PASS. Then with the rebuilt database: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test --tests "dev.gridiron.core.data.DstCorrelationTest"` → PASS.

- [ ] **Step 7: Rebuild with the new forecast and refit the range widening**

```bash
./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025" -Pout=etl/build/accuracy.db
GRIDIRON_STATS_DB=etl/build/accuracy.db GRIDIRON_ACCURACY_GATE=2025 ./gradlew :core:data:test --tests "dev.gridiron.core.data.AccuracyGateTest"
cat core/data/build/reports/accuracy-gate.txt
```
Expected: the D/ST row's model MAE below the season average's. If not, tune `DST_YA_K`, `DST_YA_SCRIPT_ELASTICITY`, `DST_YA_CV` in `ForecastConstants.kt` (rebuild between changes; up to 8 rebuilds, then stop and ask the user).

Then refit `RANGE_WIDENING[Position.DST]`: add a temporary scan test to `AccuracyRepositoryTest.kt` (not committed):

```kotlin
    @Test
    fun `scan DST widening`() = runTest {
        val path = assumeNotNull(StatsDb.path)
        JdbcQueryExecutor(path).use { executor ->
            val repo = AccuracyRepository(executor)
            for (season in listOf(2024, 2025)) {
                val row = (1..20).map { 1.0 + it * 0.05 }.joinToString { k ->
                    "%.2f=%.3f".format(k, repo.backtest(season, ScoringPresets.PPR, widening = mapOf(Position.DST to k)).single { it.position == "DST" }.calibration)
                }
                println("DST $season: $row")
            }
        }
    }
```
Pick the `k` where the pooled held share is about 0.80 (the same target every position was fitted to), set `Position.DST to k` in `RANGE_WIDENING` with a one-line comment giving the pooled held share, delete the scan test, and confirm the accuracy page's "held" figure reads 78–83% for D/ST on 2025 through `AccuracyRepositoryTest`'s existing widening test plus a rerun of the gate.

Run: `./gradlew :core:projections:test :core:data:test` (with `GRIDIRON_STATS_DB=etl/build/stats.db`; rebuild `stats.db` for 2024–2026 first with the command from Task 2).
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add -A core/forecast core/projections core/data
git commit -m "feat: project a D/ST's yards allowed and draw them jointly with points allowed"
```

---

### Task 4: Editor, saved profiles, screens and docs

**Files:**
- Modify: `core/datastore/src/main/kotlin/dev/gridiron/core/datastore/UserPrefsJson.kt`
- Modify: `feature/scoring/src/main/kotlin/dev/gridiron/feature/scoring/ScoringEditViewModel.kt`, `ScoringEditScreen.kt`
- Modify: `core/data/src/main/kotlin/dev/gridiron/core/data/StatPack.kt`, `CompareMetricSets.kt`, `PlayerStatSets.kt`
- Modify: `CLAUDE.md`, `README.md`, `docs/superpowers/HANDOFF.md`
- Test: `core/datastore/src/test/kotlin/dev/gridiron/core/datastore/UserPrefsStoreTest.kt`, `feature/scoring/src/test/kotlin/dev/gridiron/feature/scoring/ScoringEditViewModelTest.kt`, `ScoringScreenTest.kt`, `core/data/src/test/kotlin/dev/gridiron/core/data/CompareMetricSetsTest.kt`, `CompareRepositoryTest.kt`, `PlayerStatSetsTest.kt`

**Interfaces:**
- Consumes: `ScoringTier`, `ESPN_YARDS_ALLOWED`, `ScoringProfile.yardsAllowedTiers`, `StatColumn.YARDS_ALLOWED`.
- Produces: `EditState.Editing.yardTiers: ImmutableList<TierDraft>`; `FieldKey.YardTierMin(key)`, `FieldKey.YardTierPoints(key)`; `EditEvent.YardTierAdded`, `YardTierChanged(key, draft)`, `YardTierRemoved(key)`; yard draft keys start at 100 (points tier keys are 0..n, new rows 1000+), so keys are unique across both lists; test tags `ytier:min:<key>`, `ytier:points:<key>`, `ytier:remove:<key>`, `addYTier`; `UserPrefs` `formatVersion` 3.

- [ ] **Step 1: Write the failing prefs tests**

In `UserPrefsStoreTest.kt`, change the existing assertion `"formatVersion\":2` (line ~185) to `3`, and add:

```kotlin
    @Test
    fun `a version-2 profile gets ESPN's yards tiers once, and a saved empty list stays empty at version 3`() {
        file.writeText(
            """{"formatVersion": 2, "profiles": [{"id": "u1", "name": "League", "pointsAllowed": [{"min": 0, "points": 8.0}]}], "activeProfileId": "u1"}""",
        )
        val migrated = withStore { it.prefs.first() }.profiles.single()
        assertEquals(ESPN_YARDS_ALLOWED, migrated.yardsAllowedTiers)
        assertEquals(listOf(ScoringTier(0, 8.0)), migrated.pointsAllowedTiers) // points tiers untouched

        withStore { store -> store.update { p -> p.copy(profiles = listOf(migrated.copy(yardsAllowedTiers = emptyList()))) } }
        assertTrue(file.readText().contains("\"formatVersion\":3"), file.readText())
        assertEquals(emptyList<ScoringTier>(), withStore { it.prefs.first() }.profiles.single().yardsAllowedTiers)
    }

    @Test
    fun `a version-1 profile gets both tier lists, and yards tiers survive a reopen`() {
        file.writeText("""{"formatVersion": 1, "profiles": [{"id": "u1", "name": "Old league"}], "activeProfileId": "u1"}""")
        val migrated = withStore { it.prefs.first() }.profiles.single()
        assertEquals(ESPN_POINTS_ALLOWED, migrated.pointsAllowedTiers)
        assertEquals(ESPN_YARDS_ALLOWED, migrated.yardsAllowedTiers)

        val tiers = listOf(ScoringTier(0, 6.0), ScoringTier(250, 1.5), ScoringTier(400, -2.0))
        withStore { store -> store.update { it.copy(profiles = listOf(migrated.copy(yardsAllowedTiers = tiers))) } }
        assertEquals(tiers, withStore { it.prefs.first() }.profiles.single().yardsAllowedTiers)
    }

    @Test
    fun `an invalid stored yards list is dropped with the profile kept`() {
        file.writeText(
            """{"formatVersion": 3, "profiles": [{"id": "u1", "name": "Odd", "yardsAllowed": [{"min": 100, "points": 3.0}]}]}""",
        )
        val odd = withStore { it.prefs.first() }.profiles.single()
        assertEquals("Odd", odd.name)
        assertEquals(emptyList<ScoringTier>(), odd.yardsAllowedTiers)
    }
```
Add imports `dev.gridiron.core.model.ESPN_YARDS_ALLOWED` and `dev.gridiron.core.model.ScoringTier`. The existing test at line 82 (`{"formatVersion":2,"activeProfileId":"u2","profiles":[...`) now migrates its v2 profiles' yards to ESPN's: if it compares whole profiles, add `yardsAllowedTiers = ESPN_YARDS_ALLOWED` to the expected value; if it checks fields, no change.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :core:datastore:test`
Expected: FAIL (`yardsAllowed` unknown, version 2 written).

- [ ] **Step 3: Implement the prefs**

`UserPrefsJson.kt`: `import dev.gridiron.core.model.ESPN_YARDS_ALLOWED` and `dev.gridiron.core.model.ScoringTier` (replace `PointsAllowedTier`); update the constant:

```kotlin
/**
 * 2: profiles carry points-allowed tiers, and version-1 profiles gain the kicking and team-defense defaults once.
 * 3: profiles carry yards-allowed tiers, and older profiles gain ESPN's once.
 */
internal const val FORMAT_VERSION = 3
```
`ProfileDto`: add `val yardsAllowed: List<TierDto> = emptyList(),` after `pointsAllowed`. In `toDomain()`: `val migratingYards = formatVersion < 3` and, in the profile constructor: `yardsAllowedTiers = if (migratingYards) ESPN_YARDS_ALLOWED else tiersOrNone(p.yardsAllowed),`. `tiersOrNone` returns `List<ScoringTier>` (`ScoringTier(it.min, it.points)`). In `toDto()`: `yardsAllowed = p.yardsAllowedTiers.map { TierDto(it.min, it.points) },`.

Run: `./gradlew :core:datastore:test` → PASS.

- [ ] **Step 4: Write the failing editor tests**

`ScoringEditViewModelTest.kt`:

```kotlin
    @Test
    fun editingYardsTiersAndSavingPersistsThemInOrder() = runTest(dispatcher) {
        repo.duplicate(ScoringPresets.PPR.id, "Mine")
        val vm = ScoringEditViewModel("u1", repo)
        advanceUntilIdle()
        val drafts = (vm.state.value as EditState.Editing).yardTiers
        assertEquals(9, drafts.size)
        assertEquals(100, drafts.first().key)
        // Keep 0, 200 and 300 (changing 200's points), drop the rest.
        for (d in drafts.filter { it.min !in setOf("0", "200", "300") }) vm.onEvent(EditEvent.YardTierRemoved(d.key))
        val two = (vm.state.value as EditState.Editing).yardTiers.single { it.min == "200" }
        vm.onEvent(EditEvent.YardTierChanged(two.key, two.copy(points = "2.5")))
        vm.onEvent(EditEvent.YardTierAdded)
        val added = (vm.state.value as EditState.Editing).yardTiers.last()
        assertEquals("350", added.min) // 50 above the highest start
        vm.onEvent(EditEvent.YardTierChanged(added.key, added.copy(min = "400", points = "-3")))
        vm.onEvent(EditEvent.Save)
        advanceUntilIdle()

        assertEquals(
            listOf(ScoringTier(0, 5.0), ScoringTier(200, 2.5), ScoringTier(300, 0.0), ScoringTier(400, -3.0)),
            prefs.current.profiles.single().yardsAllowedTiers,
        )
        assertEquals(ScoringPresets.PPR.pointsAllowedTiers, prefs.current.profiles.single().pointsAllowedTiers)
    }

    @Test
    fun yardsTiersNeedAZeroStartAndNoRepeatsAndSayWholeYards() = runTest(dispatcher) {
        repo.duplicate(ScoringPresets.PPR.id, "Mine")
        val vm = ScoringEditViewModel("u1", repo)
        advanceUntilIdle()
        val tiers = (vm.state.value as EditState.Editing).yardTiers
        val zero = tiers.first()
        vm.onEvent(EditEvent.YardTierChanged(zero.key, zero.copy(min = "50")))
        var s = vm.state.value as EditState.Editing
        assertEquals("One tier must start at 0", s.errors[FieldKey.YardTierMin(zero.key)])
        assertNull(s.profile)

        vm.onEvent(EditEvent.YardTierChanged(zero.key, zero))
        vm.onEvent(EditEvent.YardTierChanged(tiers[2].key, tiers[2].copy(min = "100")))
        s = vm.state.value as EditState.Editing
        assertEquals("Another tier starts at 100", s.errors[FieldKey.YardTierMin(tiers[2].key)])

        vm.onEvent(EditEvent.YardTierChanged(tiers[2].key, tiers[2].copy(min = "10000")))
        assertEquals("Whole yards, 0–9999", (vm.state.value as EditState.Editing).errors[FieldKey.YardTierMin(tiers[2].key)])

        // A points tier that is fine at 100 (its own list allows 0–99 only) keeps its own message.
        vm.onEvent(EditEvent.YardTierChanged(tiers[2].key, tiers[2]))
        val points = (vm.state.value as EditState.Editing).tiers.first()
        vm.onEvent(EditEvent.TierChanged(points.key, points.copy(min = "100")))
        assertEquals("Whole points, 0–99", (vm.state.value as EditState.Editing).errors[FieldKey.TierMin(points.key)])
    }

    @Test
    fun resetToPresetRestoresTheYardsTiers() = runTest(dispatcher) {
        repo.duplicate(ScoringPresets.PPR.id, "Mine")
        val vm = ScoringEditViewModel("u1", repo)
        advanceUntilIdle()
        (vm.state.value as EditState.Editing).yardTiers.forEach { vm.onEvent(EditEvent.YardTierRemoved(it.key)) }
        vm.onEvent(EditEvent.ResetToPreset)
        assertEquals(ScoringPresets.PPR.yardsAllowedTiers, (vm.state.value as EditState.Editing).profile!!.yardsAllowedTiers)
    }
```
Add `import dev.gridiron.core.model.ScoringTier` and drop `PointsAllowedTier` if it becomes unused (the existing points tests still use it, keep it).

`ScoringScreenTest.kt`:

```kotlin
    @Test
    fun yardsTiersLight() {
        val state = custom.toEditing(readOnly = false)
        val events = mutableListOf<EditEvent>()
        compose.setContent { GridironTheme(darkTheme = false) { ScoringEditScreen(state, { events += it }, {}) } }

        compose.onNodeWithTag("editorFields").performScrollToNode(hasTestTag("addYTier"))
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/scoring_5_yards_tiers.png")
        compose.onNodeWithTag("ytier:remove:108").performClick() // the 550+ tier
        compose.onNodeWithTag("addYTier").performClick()

        assertEquals(listOf(EditEvent.YardTierRemoved(108), EditEvent.YardTierAdded), events)
    }
```
(`custom` in that file is a preset copy with ESPN's tiers; if it isn't, mirror `tiersLight`'s assumptions — its own removal of `tier:remove:8` shows it has nine points tiers.)

- [ ] **Step 5: Implement the editor**

`ScoringEditViewModel.kt`:
- Replace the `PointsAllowedTier` import with `ScoringTier`.
- `TierDraft` KDoc: "One tier's fields as typed (points allowed or yards allowed)."
- `FieldKey`: add `data class YardTierMin(val key: Int) : FieldKey` and `data class YardTierPoints(val key: Int) : FieldKey`.
- `EditState.Editing`: add `val yardTiers: ImmutableList<TierDraft>,` after `tiers`.
- `EditEvent`: add `data object YardTierAdded`, `data class YardTierChanged(val key: Int, val draft: TierDraft)`, `data class YardTierRemoved(val key: Int)`.
- Replace the inline tier validation with a shared helper and call it twice:

```kotlin
/** Validates one tier list's drafts: a whole start in 0..[maxStart], no repeats, one at 0, and a number for the points. */
private fun tierList(
    drafts: List<TierDraft>,
    maxStart: Int,
    unit: String,
    minKey: (Int) -> FieldKey,
    pointsKey: (Int) -> FieldKey,
    errors: MutableMap<FieldKey, String>,
): List<ScoringTier> {
    val starts = drafts.map { it.min.trim().toIntOrNull()?.takeIf { m -> m in 0..maxStart } }
    val tiers = drafts.mapIndexedNotNull { i, t ->
        val min = starts[i]
        when {
            min == null -> errors[minKey(t.key)] = "Whole $unit, 0–$maxStart"
            starts.take(i).contains(min) -> errors[minKey(t.key)] = "Another tier starts at $min"
        }
        val points = (DecimalInput.parse(t.points) as? DecimalInput.Result.Value)?.value
        if (points == null) errors[pointsKey(t.key)] = NUMBER
        if (min == null || points == null) null else ScoringTier(min, points)
    }.sortedBy { it.min }
    // Every game must land in a tier, so one has to start at 0. The first row carries the message, unless it has its own.
    if (tiers.isNotEmpty() && tiers.first().min != 0) {
        val firstRow = minKey(drafts.first().key)
        if (firstRow !in errors) errors[firstRow] = "One tier must start at 0"
    }
    return tiers
}
```
In `validate()` replace the old block (`val starts…` through the `if (tiers.isNotEmpty()…)` block) with:

```kotlin
    val pointsTiers = tierList(tiers, 99, "points", FieldKey::TierMin, FieldKey::TierPoints, errors)
    val yardsTiers = tierList(yardTiers, 9999, "yards", FieldKey::YardTierMin, FieldKey::YardTierPoints, errors)
```
and in the returned `copy(...)`: `pointsAllowedTiers = pointsTiers, yardsAllowedTiers = yardsTiers,`.
- `toEditing`: `yardTiers = yardsAllowedTiers.mapIndexed { i, t -> TierDraft(YARD_KEY_BASE + i, t.min.toString(), DecimalInput.format(t.points)) }.toImmutableList(),` with `private const val YARD_KEY_BASE = 100` (top of file, doc: "Yards-tier drafts index from here, so no draft key repeats across the two lists; new rows count from 1000").
- Events in `onEvent`:

```kotlin
            EditEvent.YardTierAdded -> {
                val next = (s.yardTiers.mapNotNull { it.min.trim().toIntOrNull() }.maxOrNull() ?: -50) + 50
                s.copy(yardTiers = (s.yardTiers + TierDraft(nextKey++, next.toString(), "0")).toImmutableList())
            }
            is EditEvent.YardTierChanged -> s.copy(yardTiers = s.yardTiers.map { if (it.key == event.key) event.draft else it }.toImmutableList())
            is EditEvent.YardTierRemoved -> s.copy(yardTiers = s.yardTiers.filterNot { it.key == event.key }.toImmutableList())
```
`ResetToPreset` already goes through `toEditing`, so it restores both lists.

`ScoringEditScreen.kt`: extract the points section into a reusable list-scope function and add the yards section under it. Replace the `tiersHeader` item, the `items(state.tiers…)` block and the `addTier` block with:

```kotlin
            tierSection(
                id = "tier", title = "Points allowed", unit = "pts", addTag = "addTier",
                help = "Each tier runs from its start up to the next tier's. 0, 1 and 7 mean 0, 1–6 and 7–13 points allowed.",
                drafts = state.tiers, errors = state.errors, enabled = !state.readOnly,
                minKey = FieldKey::TierMin, pointsKey = FieldKey::TierPoints,
                onChange = { onEvent(EditEvent.TierChanged(it.key, it)) },
                onRemove = { onEvent(EditEvent.TierRemoved(it)) },
                onAdd = { onEvent(EditEvent.TierAdded) },
            )
            tierSection(
                id = "ytier", title = "Yards allowed", unit = "yds", addTag = "addYTier",
                help = "Net yards. 0, 100 and 200 mean 0–99, 100–199 and 200–299 yards allowed.",
                drafts = state.yardTiers, errors = state.errors, enabled = !state.readOnly,
                minKey = FieldKey::YardTierMin, pointsKey = FieldKey::YardTierPoints,
                onChange = { onEvent(EditEvent.YardTierChanged(it.key, it)) },
                onRemove = { onEvent(EditEvent.YardTierRemoved(it)) },
                onAdd = { onEvent(EditEvent.YardTierAdded) },
            )
```
with:

```kotlin
private fun LazyListScope.tierSection(
    id: String,
    title: String,
    unit: String,
    addTag: String,
    help: String,
    drafts: List<TierDraft>,
    errors: Map<FieldKey, String>,
    enabled: Boolean,
    minKey: (Int) -> FieldKey,
    pointsKey: (Int) -> FieldKey,
    onChange: (TierDraft) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    item(key = "${id}sHeader") {
        GroupHeader(title)
        Text(
            help,
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    items(drafts, key = { "$id:${it.key}" }) { draft ->
        TierRow(draft, id, unit, errors, enabled, minKey, pointsKey, onChange = onChange, onRemove = { onRemove(draft.key) })
    }
    if (enabled) {
        item(key = "add-$id") {
            TextButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 8.dp).testTag(addTag)) { Text("+ Add tier") }
        }
    }
}
```
`TierRow` gains `tagPrefix: String, unit: String, minKey: (Int) -> FieldKey, pointsKey: (Int) -> FieldKey` and uses them: `suffix = { Text(unit) }`, `errors[minKey(draft.key)]`, `errors[pointsKey(draft.key)]`, tags `"$tagPrefix:min:${draft.key}"`, `"$tagPrefix:points:${draft.key}"`, `"$tagPrefix:remove:${draft.key}"`. Keep the existing tag names for points (`tier:*`), so `tiersLight` is unchanged. Add `import androidx.compose.foundation.lazy.LazyListScope` if missing.

Run: `./gradlew :feature:scoring:testDebugUnitTest`
Expected: PASS. (If a Roborazzi verify task compares stored images, run `./gradlew :feature:scoring:recordRoborazziDebug` for the two tier screenshots as CI does for the Grid screen; commit only the new `scoring_5_yards_tiers.png` if the repo tracks these images.)

- [ ] **Step 6: Write the failing screen-data tests, then implement**

`CompareMetricSetsTest.kt`, in `kickers and defenses have their own sets…`: add `assertTrue(StatColumn.YARDS_ALLOWED in CompareMetricSets.groupsFor(Position.DST).getValue(CompareGroup.EFFICIENCY))` and `assertEquals(7, CompareMetricSets.radarAxes(Position.DST).size)`. `CompareRepositoryTest.kt` line 261: `assertEquals(7, page.radar!!.axes.size)`, and add above it:

```kotlin
        val yards = page.groups.flatMap { it.rows }.first { it.column == StatColumn.YARDS_ALLOWED }
        assertTrue(yards.cells.all { it.percentile != null })
```
`PlayerStatSetsTest.kt`, add:

```kotlin
    @Test
    fun `a defense's game log reads fantasy points, points allowed, yards allowed, sacks and interceptions`() {
        assertEquals(
            listOf(StatColumn.FANTASY_POINTS, StatColumn.POINTS_ALLOWED, StatColumn.YARDS_ALLOWED, StatColumn.DST_SACKS, StatColumn.DST_INTERCEPTIONS),
            PlayerStatSets.logColumns(Position.DST),
        )
    }
```
Also add to `PlayerStatsRepositoryTest.kt` (line ~107, beside the D/ST log check): `assertLogAddsUp(id, Position.DST, StatColumn.YARDS_ALLOWED)`.

Run `./gradlew :core:data:test` with `GRIDIRON_STATS_DB=etl/build/stats.db` → FAIL. Then implement:
- `StatPack.kt` line 126: `listOf(FANTASY_POINTS, POINTS_ALLOWED, YARDS_ALLOWED, DST_SACKS, …)` (import `StatColumn.YARDS_ALLOWED`).
- `CompareMetricSets.kt`: `CompareGroup.EFFICIENCY to listOf(POINTS_ALLOWED, YARDS_ALLOWED)` in `DST_SET`; radar `Position.DST -> listOf(POINTS_ALLOWED, YARDS_ALLOWED, DST_SACKS, DST_INTERCEPTIONS, DST_FUMBLE_RECOVERIES, DST_TDS, FANTASY_POINTS)`; import `StatColumn.YARDS_ALLOWED`.
- `PlayerStatSets.kt` line 34: `private val DST = listOf(FANTASY_POINTS, POINTS_ALLOWED, YARDS_ALLOWED, DST_SACKS, DST_INTERCEPTIONS)`; drop the now-unused `DST_TDS` import if nothing else uses it.

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test :feature:compare:testDebugUnitTest :feature:players:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS (Grid, Compare and Player-page screens read the columns through the catalog; `GridViewModelTest` and `GridScreenTest` reference the pack by name only).

- [ ] **Step 7: Docs, full verification and commit**

`CLAUDE.md`: in the module list mention yards where points allowed appear — `:core:projections` ("a D/ST's points and yards allowed score the profile's tiers", "single-player Monte Carlo… draws points and yards allowed jointly"), `:core:datastore` (`formatVersion` 3: ESPN's yards tiers once), `:core:forecast` (D/STs get yards allowed as their own projected stat: rate, opponent, damped script, fitted spread), `:feature:scoring` (both tier editors), and `:core:ingest` (`INGEST_VERSION` 5 writes `yards_allowed`). Remove the Known Gaps bullet "ESPN's default D/ST also scores yards allowed and blocked kicks…" and replace it with: "**Blocked kicks aren't scored** for a D/ST. Yards allowed are (ESPN's default tiers, unverified against ESPN's own table, editable per profile). `DST_YA_K`, `DST_YA_SCRIPT_ELASTICITY` and `DST_YA_CV` are judgments, and there is no posted yards line to backtest against." Add `ForecastConstants` mention if the file lists constants by name. `README.md`: update any sentence about D/ST scoring (grep `points allowed`). `HANDOFF.md`: replace the "In flight" paragraph with a "Just shipped" one for yards allowed, list the measured constants and fitted `RANGE_WIDENING[DST]`, and keep the open phone checks; remove the "Unverified defaults" clause "ESPN's yards-allowed … isn't modeled" (blocked kicks still aren't).

Full verification:

```bash
export GRIDIRON_STATS_DB=etl/build/stats.db
./gradlew test
GRIDIRON_STATS_DB=etl/build/accuracy.db GRIDIRON_ACCURACY_GATE=2025 ./gradlew :core:data:test --tests "dev.gridiron.core.data.AccuracyGateTest"
./gradlew :app:assembleRelease lint
(cd etl && python -m pytest tests/ -q)
```
Expected: everything passes except the known speed test; the gate table shows D/ST's model MAE below the season average's.

```bash
git add -A core feature app CLAUDE.md README.md docs
git commit -m "feat: edit and migrate yards-allowed tiers, and show yards allowed on the D/ST screens"
git push -u origin claude/dreamy-euler-phbdq1
```
Then make sure the open draft PR's description covers the implementation (it starts as docs-only), and subscribe to its activity.

---

## Self-Review

**Spec coverage.**
- Scoring model (tiers, typealias, shared helpers, ESPN defaults, net yards, `Components`/`StatColumn`, SQL, `score()`, backtest and query component lists): Tasks 1–2.
- Data (`Dst.kt`, registry, Python parity, range checks, `INGEST_VERSION` 5): Task 2. `SCHEMA_VERSION` untouched.
- Forecast (storage, own rate, matchup, script, spread, waterfall `points()`, note, `FORECAST_VERSION` 5): Task 3. Phone (`projectedScore`, joint Monte Carlo, correlation constant and pin test, widening refit, gate): Task 3.
- Editor (second section, tags, validation messages, Reset to preset, unique keys), prefs v3, new-profile copies (duplicate copies the whole profile; `basedOn` reset goes through `toEditing`): Task 4.
- Screens (Defense pack, Compare set and radar 7 axes, Player page D/ST log, Team defense unchanged): Task 4. Docs: Task 4.
- Testing list: each named test class has a step (ScoringQueryTest, ScorerTest, ProjectedScoreTest, MonteCarloTest, BacktestTest via the component list and the gate, DstTest/MetricsTest/DatabaseChecksTest/IngestPipelineTest, test_teams/test_registry/parity, DefenseTest/ForecastEngineTest, UserPrefsStoreTest, ScoringEditViewModelTest/ScoringScreenTest, RealDatabaseContractTest, DstCorrelationTest, gate).

**Deliberate small deviations from the spec:** the "spread" is a fixed CV constant (already stated in the spec); the editor's add-step is +50 for yards (a UX detail); yards draft keys start at 100 so keys are unique across lists (the spec's requirement, made concrete). No dedicated `BacktestTest` case is added: the backtest scores actuals through `ACTUAL_SCORING_COMPONENTS` and `score()` (both changed and tested in Task 1) and its D/ST projections through `projectedScore` (Task 3).

**Placeholder scan.** No TBDs. Two steps say to "mirror the neighbouring test's call" (`StatQueryBuilder.build`/row type in Task 1 Step 5, `JdbcQueryExecutor` query call in Task 3 Step 2): each names the file to copy from and keeps the assertions fixed, because those two signatures were not re-read while writing. The BacktestTest-style scan test in Task 3 Step 7 is temporary by design and deleted before commit.

**Type consistency.** `ScoringTier`, `ESPN_YARDS_ALLOWED`, `yardsAllowedTiers`, `yardsAllowedPoints`, `expectedYardsAllowedPoints` (Task 1) are the names used in Tasks 2–4. `Components.YARDS_ALLOWED` (Task 1) → `StatColumn.YARDS_ALLOWED` (Task 2) → screens (Task 4). `YARDS_ALLOWED` (forecast const, string) is distinct from the `Components` value and used only in `:core:forecast`. `DefenseLeague.yardsAllowed`, `defenseStages(..., ownYards, yardsFactor)`, `yardsScript`, `drawJointAllowed`, `DST_POINTS_YARDS_CORRELATION`, `K.DST_YA_*` are defined in Task 3 and used only there. `EditEvent.YardTier*`, `FieldKey.YardTier*`, `EditState.Editing.yardTiers` are defined and used in Task 4.

**Review Focus coverage.** Missing yards row: Task 1 (`no yards tiers score no yards, and a week without yards scores none`), Task 3 (`a projection without yards scores none`, `a defense with no yards history is projected without yards`). Shutout's 0: Task 2 (`MetricsTest`, `DstTest`, `DatabaseChecksTest`) and Task 1's shutout week. v2 vs v3 empty list: Task 4. Empty yards list beside points tiers and identical SQL: Task 1. Rest-of-season yards per game: Task 3 (`ProjectedScoreTest`, `MonteCarloTest`).
