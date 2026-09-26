# Projection Share Fix Implementation Plan (layer 2 amendment)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (the user's chosen method) to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix three problems in the projections: backups projected like starters, team totals about double, and stars pulled down early in the season. Layer 2 is rebuilt as the spec's amendment says.

**Architecture:** Two changes.
- **`BaselineModel` splits into two steps.** Step one works out a player's raw shares: target and carry shares shrunk toward his own last season (or a newcomer share), and a pass share for the starting QB only. Step two turns those shares into stats.
- **The projector works a team at a time.** For each team it picks the expected starting QB, keeps only active players, and scales their target and carry shares to sum to one before turning them into stats.

`FORECAST_VERSION` goes to 2.

**Tech Stack:** Kotlin 2 (explicit API, warnings as errors), androidx.sqlite bundled driver, JUnit Jupiter.

**Spec:** `docs/superpowers/specs/2026-09-26-projection-model-design.md`, section "Amendment: layer 2 (2026-09-26 …)" and layer 2 in §2.

## Global Constraints

- The Global Constraints of `docs/superpowers/plans/2026-09-26-projection-engine.md` still hold. Walk-forward matters most: projecting week *w* reads only facts from before *w*, plus game-table rows.
- New constants, verbatim from the amendment: `NEWCOMER_SHARE_FACTOR = 0.5`, `STARTER_PASS_SHARE = 0.97`, `ACTIVE_WINDOW = 2`. The week 1–6 carryover (`CARRYOVER_START`, `CARRYOVER_LAST_WEEK`, `carryoverWeight`) is removed.
- `FORECAST_VERSION` 1 → 2.
- Share k (5 games) and half-life (4.5) are unchanged.

## Review Focus

- **A team whose listed starter isn't a candidate** (a rookie's first start, no history): the fallback picks the QB with the most attempts in the team's latest game. Nothing crashes, and at most one QB passes. Tests in Task 2.
- **A team with no active non-QB** (very early in a season built on its own): normalization divides by zero. Shares stay zero rather than NaN. Test in Task 1 (`normalizeShares` on all-zero input).
- **A player traded mid-season who hasn't debuted:** the newcomer rule keeps him. The existing engine test "a player who changed teams is projected with his new team" must still pass.
- **A starter returning from injury** who missed the team's last two games isn't projected. That's accepted for now; it's the "injury redistribution not modeled" gap. Documented in CLAUDE.md, not tested.
- **Team totals on real data:** the contract test in Task 2 is the net.

---

### Task 1: Shares shrunk toward the player, a starter-only pass share, and team normalization

**Files:**
- Modify: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/ForecastConstants.kt`
- Modify: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/ForecastMath.kt`
- Modify: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Baseline.kt`
- Test: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/BaselineModelTest.kt`
- Test: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/ForecastMathTest.kt`

**Interfaces:**
- Produces (internal):
  - `data class Shares(pass: Double, target: Double, carry: Double)`
  - `fun normalizeShares(shares: Map<String, Shares>): Map<String, Shares>`
  - `BaselineModel.shares(ctx: PlayerContext, rates: Rates, starter: Boolean): Shares`
  - `BaselineModel.project(ctx, rates, volume, shares: Shares): Map<String, Double>`
  - `BaselineModel.project(ctx, rates, volume)` stays, using `shares(ctx, rates, starter = true)`
  - `BaselineModel.share(ctx, fallback: Double, part, whole, usePrior: Boolean = true): Double`
  - `K.NEWCOMER_SHARE_FACTOR`, `K.STARTER_PASS_SHARE`, `K.ACTIVE_WINDOW`

- [ ] **Step 1: Rewrite the share tests (they fail against the old behavior)**

In `BaselineModelTest.kt`, replace the `targetShare` helper and the first, second and fourth tests (`two games at a 30 percent share …`, `last season carries 0_44 …`, `a player with no history gets the position's rates`) with:

```kotlin
    private fun targetShare(ctx: PlayerContext): Double =
        model().share(ctx, rates.targetShare * 0.5, { it["targets"] }, { it.targets })

    @Test
    fun `a newcomer's two games at 30 percent are shrunk toward half the position's 10`() {
        val history = listOf(game(2025, 1, "targets" to 9.0), game(2025, 2, "targets" to 9.0))
        val ctx = PlayerContext("WR", 2025, 3, history, regimeBreak = false)

        assertEquals(0.85 / 7, targetShare(ctx), 1e-12) // (2 x 0.3 + 5 x 0.05) / (2 + 5)
        assertEquals(30 * 0.85 / 7, model().project(ctx, rates, volume).getValue("targets"), 1e-12)
    }

    @Test
    fun `this season is shrunk toward the player's own last season, unless the regime broke`() {
        val history = listOf(
            game(2024, 16, "targets" to 7.5),
            game(2024, 17, "targets" to 7.5),
            game(2025, 1, "targets" to 9.0),
        )

        assertEquals((0.3 + 5 * 0.25) / 6, targetShare(PlayerContext("WR", 2025, 2, history, regimeBreak = false)), 1e-12)
        assertEquals((0.3 + 5 * 0.05) / 6, targetShare(PlayerContext("WR", 2025, 2, history, regimeBreak = true)), 1e-12)
        // No fade by week number: last season keeps its pseudo-games until this season's games outweigh them.
        assertEquals((0.3 + 5 * 0.25) / 6, targetShare(PlayerContext("WR", 2025, 12, history, regimeBreak = false)), 1e-12)
    }

    @Test
    fun `a player with no history gets a newcomer's share and the position's rates`() {
        val out = model().project(PlayerContext("WR", 2025, 3, emptyList(), regimeBreak = false), rates, volume)

        assertEquals(1.5, out.getValue("targets"), 1e-12) // 30 x (0.1 x 0.5)
        assertEquals(0.9, out.getValue("receptions"), 1e-12)
        assertEquals(12.0, out.getValue("receiving_yards"), 1e-12)
        assertEquals(0.125, out.getValue("carries"), 1e-12) // 25 x (0.01 x 0.5)
    }

    @Test
    fun `only the starting QB passes, shrunk toward a starter's share rather than his backup history`() {
        // One relief appearance last season: 3.2 of the team's 32 attempts, a 0.1 share.
        val ctx = PlayerContext("QB", 2025, 1, listOf(game(2024, 5, "attempts" to 3.2)), regimeBreak = false)
        val m = model()

        assertEquals(0.97, m.shares(ctx, rates, starter = true).pass, 1e-12)
        assertEquals(0.0, m.shares(ctx, rates, starter = false).pass)
        assertEquals(0.0, m.project(ctx, rates, volume, m.shares(ctx, rates, starter = false)).getValue("attempts"))
    }

    @Test
    fun `a team's target and carry shares are scaled to sum to one`() {
        val n = normalizeShares(
            mapOf("a" to Shares(0.0, 0.6, 0.1), "b" to Shares(0.0, 0.6, 0.3), "q" to Shares(0.97, 0.0, 0.0)),
        )
        assertEquals(0.5, n.getValue("a").target, 1e-12)
        assertEquals(0.25, n.getValue("a").carry, 1e-12)
        assertEquals(0.75, n.getValue("b").carry, 1e-12)
        assertEquals(0.97, n.getValue("q").pass, 1e-12)
        assertEquals(Shares(0.0, 0.0, 0.0), normalizeShares(mapOf("x" to Shares(0.0, 0.0, 0.0))).getValue("x"))
    }
```

In `ForecastMathTest.kt`, delete the test `carryover starts at 0_55 in week 1 and is gone by week 6`.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:forecast:test`
Expected: FAIL to compile (`Shares`, `normalizeShares`, `shares` unresolved).

- [ ] **Step 3: Constants**

In `ForecastConstants.kt`, set `public const val FORECAST_VERSION: Int = 2`, and replace the two lines

```kotlin
    const val CARRYOVER_START = 0.55
    const val CARRYOVER_LAST_WEEK = 6
```

with:

```kotlin
    // Layer 2 amendment (spec, 2026-09-26): shrink toward the player's own last season; newcomers
    // toward half the position's average share; the starting QB toward a starter's share. Only
    // players who played for their team in one of its last ACTIVE_WINDOW games are projected.
    const val NEWCOMER_SHARE_FACTOR = 0.5
    const val STARTER_PASS_SHARE = 0.97
    const val ACTIVE_WINDOW = 2
```

In `ForecastMath.kt`, delete `carryoverWeight` and its KDoc.

- [ ] **Step 4: Shares in `Baseline.kt`**

Add after `PlayerContext`:

```kotlin
/** A player's expected shares of his team's volume. [pass] is nonzero only for the expected starting QB, [target] only for non-QBs. */
internal data class Shares(val pass: Double, val target: Double, val carry: Double)

/**
 * Scales one team's shares so its target shares sum to one and its carry
 * shares sum to one: the players projected for a team split exactly its
 * volume. Pass shares are left alone, because one starter takes them.
 */
internal fun normalizeShares(shares: Map<String, Shares>): Map<String, Shares> {
    val targets = shares.values.sumOf { it.target }
    val carries = shares.values.sumOf { it.carry }
    return shares.mapValues { (_, s) ->
        s.copy(
            target = if (targets > 0.0) s.target / targets else 0.0,
            carry = if (carries > 0.0) s.carry / carries else 0.0,
        )
    }
}
```

In `BaselineModel`, replace `project`'s signature line and its three share-based volume lines, and replace `share`. The new members:

```kotlin
    /** A lone player's projection, as a starter, with no team normalization. */
    fun project(ctx: PlayerContext, rates: Rates, volume: TeamVolume): Map<String, Double> =
        project(ctx, rates, volume, shares(ctx, rates, starter = true))

    /** Layer 2's raw shares, before [normalizeShares]. [starter]: whether this QB is his team's expected starter. */
    fun shares(ctx: PlayerContext, rates: Rates, starter: Boolean): Shares {
        val carry = share(ctx, rates.carryShare * K.NEWCOMER_SHARE_FACTOR, { it["carries"] }, { it.carries })
        if (ctx.position != "QB") {
            val target = share(ctx, rates.targetShare * K.NEWCOMER_SHARE_FACTOR, { it["targets"] }, { it.targets })
            return Shares(pass = 0.0, target = target, carry = carry)
        }
        // A starter is shrunk toward a starter's share, never toward his own backup history.
        val pass = if (starter) share(ctx, K.STARTER_PASS_SHARE, { it["attempts"] }, { it.passAttempts }, usePrior = false) else 0.0
        return Shares(pass = pass, target = 0.0, carry = carry)
    }
```

and in `project(ctx, rates, volume, shares: Shares)` (the old body, with its signature changed to take `shares`):

```kotlin
        val carries = volume.carries * shares.carry
```

```kotlin
            attempts = volume.passAttempts * shares.pass
```

```kotlin
            val targets = volume.targets * shares.target
```

replacing the three `share(ctx, …)` calls. The new `share`:

```kotlin
    /**
     * Layer 2: this season's recency-weighted share, shrunk (k = 5 games)
     * toward the player's own last-season share, or toward [fallback] when
     * he has none, his regime broke, or [usePrior] is false.
     */
    internal fun share(
        ctx: PlayerContext,
        fallback: Double,
        part: (PlayerGame) -> Double,
        whole: (TeamGame) -> Double,
        usePrior: Boolean = true,
    ): Double {
        fun series(games: List<PlayerGame>): List<Double> = games.mapNotNull { g ->
            val team = whole(teamGames.getValue(Triple(g.team, g.season, g.week)))
            if (team > 0.0) part(g) / team else null
        }
        val current = series(ctx.history.filter { it.season == ctx.season })
        val prior = if (!usePrior || ctx.regimeBreak) null else ewma(series(ctx.history.filter { it.season == ctx.season - 1 }), K.SHARE_HALF_LIFE)
        return shrink(ewma(current, K.SHARE_HALF_LIFE), current.size.toDouble(), prior ?: fallback, K.SHARE_K_GAMES)
    }
```

Update `PlayerContext.regimeBreak`'s KDoc to: `/** Last season's share isn't this season's target: a new team, a new head coach, or (pass catchers) a new starting QB. */`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:forecast:test`
Expected: `BaselineModelTest`, `ForecastMathTest` and `KindsTest` PASS. `ForecastEngineTest` may also pass at this point. The projector still calls the 3-argument `project`, so it runs unchanged until Task 2.

- [ ] **Step 6: Commit**

```bash
git add core/forecast
git commit -m "forecast: shares shrink toward the player's last season; starter-only passing; team normalization"
```

---

### Task 2: The projector projects a team at a time; real-data contract

**Files:**
- Modify: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Projector.kt`
- Test: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/ForecastEngineTest.kt`
- Test: `core/data/src/test/kotlin/dev/gridiron/core/data/ProjectionsContractTest.kt`
- Modify: `CLAUDE.md`, `docs/superpowers/HANDOFF.md`

**Interfaces:**
- Consumes: `Shares`, `normalizeShares`, `BaselineModel.shares`, the 4-argument `project`, `K.ACTIVE_WINDOW` (Task 1).
- Produces: no new public API.

- [ ] **Step 1: Write the failing engine tests**

Add to `ForecastEngineTest`:

```kotlin
    @Test
    fun `only the starting QB is projected to pass`() {
        league("a.db").use { db ->
            db.player("QB2_A", "QB", "AAA")
            db.week("QB2_A", 2024, 1, "AAA", "attempts" to 3.0, "completions" to 2.0, "passing_yards" to 15.0)

            run(db)

            assertEquals(
                listOf(listOf("QB_A")),
                db.query(
                    "SELECT player_id FROM player_week_projection WHERE season = 2025 AND week = 3 AND stage = 'final' " +
                        "AND metric_id = 'attempts' AND player_id IN ('QB_A', 'QB2_A')",
                ),
            )
        }
    }

    @Test
    fun `a team's players split exactly its targets and carries`() {
        league("a.db").use { db ->
            run(db)
            // AAA is team index 0, so playWeek's k is 1 + 0.05 x week; its 2025 week 2 WR had 9 targets.
            val weeks = listOf(2024 to 1, 2024 to 2, 2024 to 3, 2025 to 1, 2025 to 2)
            val targets = weeks.map { (s, w) -> 4.0 + if (s == 2025 && w == 2) 9.0 else 9 * (1.0 + 0.05 * w) }
            val carries = weeks.map { (_, w) -> 3.0 + 18 * (1.0 + 0.05 * w) }
            fun baseline(metric: String) = db.query(
                "SELECT SUM(mean) FROM player_week_projection WHERE season = 2025 AND week = 3 AND stage = 'baseline' " +
                    "AND metric_id = '$metric' AND player_id IN ('QB_A', 'RB_A', 'WR_A')",
            ).single()[0]!!.toDouble()

            assertEquals(ewma(targets, 4.0)!!, baseline("targets"), 1e-6)
            assertEquals(ewma(carries, 4.0)!!, baseline("carries"), 1e-6)
        }
    }

    @Test
    fun `a player who hasn't played for his team lately isn't projected`() {
        league("a.db").use { db ->
            db.player("WR_X", "WR", "AAA")
            for (week in 1..3) db.week("WR_X", 2024, week, "AAA", "targets" to 3.0, "receptions" to 2.0, "receiving_yards" to 20.0)

            run(db)

            assertEquals(
                listOf(listOf("0")),
                db.query("SELECT COUNT(*) FROM player_week_projection WHERE player_id = 'WR_X' AND season = 2025 AND week = 3"),
            )
        }
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:forecast:test --tests "dev.gridiron.core.forecast.ForecastEngineTest"`
Expected: the three new tests FAIL. QB2_A passes too, AAA's baseline sums miss the EWMA, and WR_X is projected.

- [ ] **Step 3: Project a team at a time**

In `Projector.kt`:

- In `run()`, replace `val prepared = candidates(state.order).mapNotNull { projectPlayer(state, it, kind) }` with `val prepared = prepareWeek(state, kind)`.
- Replace the whole `projectPlayer` function with:

```kotlin
    /** A player placed on a team for one week, before his team's shares are worked out. */
    private class Draft(val player: PlayerInfo, val team: String, val game: Game?, val ctx: PlayerContext, val rates: Rates)

    /**
     * One week's projections, a team at a time: the expected starting QB and
     * the active players, with the team's target and carry shares scaled to
     * sum to one (spec amendment to layer 2).
     */
    private fun prepareWeek(state: WeekState, kind: WeekKind): List<Prepared> =
        candidates(state.order).mapNotNull { draft(state, it, kind) }.groupBy { it.team }.flatMap { (team, onTeam) ->
            val starter = expectedStarter(team, onTeam, state, kind)
            val kept = onTeam.filter { d ->
                if (d.player.position == "QB") d.player.playerId == starter else isActive(d, team, state, kind)
            }
            val shares = normalizeShares(
                kept.associate { d -> d.player.playerId to model.shares(d.ctx, d.rates, starter = d.player.playerId == starter) },
            )
            val volume = teamVolume(teamHistory[team].orEmpty().takeWhile { it.order < state.order }, state.leagueTeam)
            kept.map { d -> finish(state, d, shares.getValue(d.player.playerId), volume, kind) }
        }

    private fun draft(state: WeekState, player: PlayerInfo, kind: WeekKind): Draft? {
        val rates = state.rates[player.position] ?: return null
        val all = inputs.history[player.playerId].orEmpty()
        val before = all.takeWhile { it.order < state.order }
        val team = teamFor(player, all, before, state.season, state.week, kind) ?: return null
        val game = gameOf[Triple(team, state.season, state.week)]
        // A past bye has nothing to project. An upcoming bye has no weekly rows, but its later games still make rest of season.
        if (game == null && kind != WeekKind.UPCOMING) return null
        val ctx = PlayerContext(player.position, state.season, state.week, before, regimeBreak(player, team, before, state.season, state.week))
        return Draft(player, team, game, ctx, rates)
    }

    /**
     * Whether a non-QB is on the field for [team] as of this week: he played
     * for it in one of its last [K.ACTIVE_WINDOW] games, or, from the upcoming
     * week on, nflverse lists him on [team] and he hasn't played for it yet (a
     * signing or trade).
     */
    private fun isActive(d: Draft, team: String, state: WeekState, kind: WeekKind): Boolean {
        val recent = teamHistory[team].orEmpty().filter { it.order < state.order }.takeLast(K.ACTIVE_WINDOW).map { it.order }.toSet()
        if (d.ctx.history.any { it.team == team && it.order in recent }) return true
        return kind != WeekKind.PAST && d.player.team == team && d.ctx.history.lastOrNull()?.team != team
    }

    /**
     * The QB who gets [team]'s passing this week. In order: the starter
     * nflverse lists for the game; else the most recent listed starter who is
     * still with the team; else the team's QB with the most attempts in its
     * latest game. Null when the team has no QB candidate.
     */
    private fun expectedStarter(team: String, onTeam: List<Draft>, state: WeekState, kind: WeekKind): String? {
        val qbs = onTeam.filter { it.player.position == "QB" }
        if (qbs.isEmpty()) return null
        val ids = qbs.map { it.player.playerId }.toSet()
        gameOf[Triple(team, state.season, state.week)]?.qbOf(team)?.takeIf { it in ids }?.let { return it }
        inputs.games
            .filter { it.involves(team) && order(it.season, it.week) < state.order }
            .mapNotNull { it.qbOf(team) }
            .lastOrNull { it in ids && (kind == WeekKind.PAST || inputs.players[it]?.team == team) }
            ?.let { return it }
        val latest = teamHistory[team].orEmpty().lastOrNull { it.order < state.order }?.order
        return qbs.maxByOrNull { d -> d.ctx.history.lastOrNull { it.team == team && it.order == latest }?.get("attempts") ?: 0.0 }?.player?.playerId
    }

    private fun finish(state: WeekState, d: Draft, shares: Shares, volume: TeamVolume, kind: WeekKind): Prepared {
        val prepared = Prepared(d.player, d.team, model.project(d.ctx, d.rates, volume, shares), volume.passRate)
        val game = d.game ?: return prepared
        val (afterMatchup, final) = finalFor(state, prepared, game)
        val cv = K.EMPIRICAL_CV.getValue(d.player.position)
        when (kind) {
            WeekKind.PAST -> if (referencePoints(final) >= K.PAST_WEEK_MIN_POINTS) {
                emit(d.player.playerId, state.season, state.week, "final", final, cv)
            }
            WeekKind.UPCOMING -> if (referencePoints(final) >= K.UPCOMING_MIN_POINTS) {
                emit(d.player.playerId, state.season, state.week, "baseline", prepared.baseline, cv)
                emit(d.player.playerId, state.season, state.week, "final", final, cv)
                emitFactors(state, prepared, game, afterMatchup, final)
            }
            WeekKind.REST -> Unit
        }
        return prepared
    }
```

- [ ] **Step 4: Run the forecast tests**

Run: `./gradlew :core:forecast:test`
Expected: PASS, including every earlier `ForecastEngineTest` test. If `a later week's stats never change an earlier week's projection` fails, a new rule is reading at or after the projected week; fix that, never the test. If `a player who changed teams is projected with his new team` fails, the newcomer clause of `isActive` is wrong.

- [ ] **Step 5: Add the real-data team check**

In `core/data/src/test/kotlin/dev/gridiron/core/data/ProjectionsContractTest.kt`, move the week selection into a helper and add a second test. The file becomes:

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
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
 * with the same Kotlin code the phone runs.
 */
@EnabledIfEnvironmentVariable(named = "GRIDIRON_STATS_DB", matches = ".+")
class ProjectionsContractTest {
    /** In season, the upcoming week; off-season, the last week projected. */
    private suspend fun projectedWeek(repo: ProjectionsRepository, executor: QueryExecutor): Pair<Int, Int> {
        val status = repo.status()
        assertEquals("ok", status.status)
        return status.upcoming.maxByOrNull { it.key }?.toPair()
            ?: executor.query(
                SqlQuery(
                    "SELECT season, MAX(week) FROM player_week_projection " +
                        "WHERE season = (SELECT MAX(season) FROM player_week_projection)",
                    emptyList(),
                ),
            ) { it.long(0).toInt() to it.long(1).toInt() }.single()
    }

    @Test
    fun `the refresh-built database projects a full week that scores sensibly`() = runTest {
        JdbcQueryExecutor(StatsDb.path!!).use { executor ->
            val repo = ProjectionsRepository(executor)
            val (season, week) = projectedWeek(repo, executor)

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

    @Test
    fun `every team's projected week adds up to one game, with one passer`() = runTest {
        JdbcQueryExecutor(StatsDb.path!!).use { executor ->
            val repo = ProjectionsRepository(executor)
            val (season, week) = projectedWeek(repo, executor)

            // Grouped by nflverse's current team: a traded player can land on his old team here, which the bounds allow for.
            for ((team, players) in repo.weekAll(season, week).groupBy { it.team }) {
                fun total(metric: String) = players.sumOf { p -> p.components.filter { it.metricId == metric }.sumOf { it.mean } }
                val passers = players.filter { p -> p.components.any { it.metricId == "attempts" && it.mean > 5.0 } }.map { it.name }
                assertTrue(passers.size <= 1, "$team has ${passers.size} passers: $passers")
                // Real teams average about 34 pass attempts, 30 targets and 27 carries; matchup and script move them ±20%.
                assertTrue(total("attempts") <= 50.0, "$team: ${total("attempts")} pass attempts")
                assertTrue(total("targets") <= 50.0, "$team: ${total("targets")} targets")
                assertTrue(total("carries") <= 45.0, "$team: ${total("carries")} carries")
            }
        }
    }
}
```

- [ ] **Step 6: Prove it on real data**

Run:

```bash
./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025 2026" -Pout=etl/build/stats.db
GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test --tests "dev.gridiron.core.data.ProjectionsContractTest" :core:forecast:test
```

Expected: PASS. Then run this check, which reproduces the review's evidence:

```bash
python3 - <<'PY'
import sqlite3
c = sqlite3.connect('etl/build/stats.db')
s, w = [(int(k.split(':')[1]), int(v)) for k, v in c.execute("select key, value from schema_meta where key like 'forecast_week:%'")][0]
print('QBs over 20 attempts:', c.execute("select count(*) from player_week_projection where season=? and week=? and stage='final' and metric_id='attempts' and mean>20", (s, w)).fetchone()[0])
print('max team attempts:', c.execute("select max(t) from (select sum(p.mean) t from player_week_projection p join player pl using(player_id) where p.season=? and p.week=? and p.stage='final' and p.metric_id='attempts' group by pl.team)", (s, w)).fetchone()[0])
PY
```

Expected: "QBs over 20 attempts" is at most 32 (it was 73) and "max team attempts" is under 50 (it was 123). Record both numbers in the handoff. Also record Ja'Marr Chase's projected targets for the upcoming week (`player_id` from `SELECT player_id FROM player WHERE full_name LIKE 'Ja''Marr Chase'`); it was 6.8.

- [ ] **Step 7: Docs**

`CLAUDE.md`: delete the Known Gaps bullet that starts "**Projections over-count backups**" (added when this plan was written). In the `:core:forecast` module line, change "shrunk share" to "shrunk share (toward the player's last season; starting QB only; each team's shares sum to one)". In the "Not modeled" bullet, add "a player returning from injury isn't projected until he plays again".

`docs/superpowers/HANDOFF.md`: record both tasks under **Execution progress**, with the real-data numbers from Step 6. Set **Next step** to "Sub-project 1 is complete; merge PR #4 when CI is green, then plan sub-project 2 (accuracy page, spec §4) with writing-plans in a fresh session".

- [ ] **Step 8: Run everything and commit**

Run: `./gradlew :core:forecast:test :core:ingest:test :core:data:test` (with `GRIDIRON_STATS_DB` set) and `./gradlew :app:testDebugUnitTest :feature:projections:testDebugUnitTest`
Expected: PASS.

```bash
git add core/forecast core/data/src/test CLAUDE.md docs/superpowers/HANDOFF.md
git commit -m "forecast: project a team at a time (one passer, active players, shares sum to one)"
```
