# Injured players' share redistribution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A QB/RB/WR/TE that nflverse lists Out or Doubtful gets no projection for that week, and his team's target and carry shares (and, for a QB, the passing share) go to teammates.

**Architecture:** `loadInputs` reads `injury_report` into `ForecastInputs.absent`. `Projector.prepareWeek` drops absent players before shares are worked out, so the existing `normalizeShares` spends their volume on the players left. Rest of season is built from a second, healthy roster so an injury this week doesn't leak into later weeks.

**Tech Stack:** Kotlin (JVM module `:core:forecast`), JUnit 5, bundled SQLite driver.

**Spec:** `docs/superpowers/specs/2026-09-29-injury-share-redistribution-design.md`

## Global Constraints

- Only status `Out` and `Doubtful` (exact text, as stored in `injury_report.status`) count as absent. `Questionable`, `Note` and null count as playing.
- Only QB, RB, WR and TE are affected (`POSITIONS`). Kickers and D/STs are untouched.
- No new constants in `ForecastConstants.kt`; `FORECAST_VERSION` goes from 5 to 6.
- Rest of season never reads `absent` for weeks after the upcoming one.
- CI's accuracy gate stays on for QB, RB, WR, TE, K and D/ST; it is never skipped.
- Kotlin official style; commit messages end with the two attribution lines from the session (`Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01NAmSfMG3LfdCw9DC5pxByG`).
- Branch: `claude/dreamy-euler-phbdq1`. Push to it; PR #16 already exists as a draft.

## Review Focus

1. **The only QB on a team is Out:** no QB is projected for that week and the team's pass catchers are still projected (tested in Task 2).
2. **An Out player's later weeks:** his rest-of-season keeps every week after this one, and a teammate's rest of season isn't inflated for those weeks (tested in Task 2).
3. **A past week with an injury row:** the backtest week has no row for the Out player and the other weeks are unaffected (tested in Task 2).
4. **Injury rows for kickers, or for a player who isn't in `player`:** ignored, no crash (tested in Task 1).
5. **A database with no injury rows at all:** every existing test still passes with no change in numbers (the whole `:core:forecast` suite, run in Task 2 and Task 3).

---

### Task 1: Read Out and Doubtful players into the inputs

**Files:**
- Modify: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Inputs.kt` (class `ForecastInputs` at line 77, `loadInputs` at line 111)
- Modify: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/TestDb.kt` (helpers and `DDL`)
- Test: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/InputsTest.kt`

**Interfaces:**
- Consumes: `POSITIONS: List<String>` (in `Inputs.kt`).
- Produces:
  - `ForecastInputs.absent: Set<Triple<String, Int, Int>>` — (player id, season, week); defaults to `emptySet()`.
  - `TestDb.injury(id: String, season: Int, week: Int, status: String?)`.

- [ ] **Step 1: Record the baseline accuracy table before any change**

Run from the repo root (both databases already exist in a warm container; rebuild if it is fresh, commands are in `CLAUDE.md`):

```bash
export GRIDIRON_STATS_DB=etl/build/accuracy.db
GRIDIRON_ACCURACY_GATE=2025 ./gradlew :core:data:test --tests "dev.gridiron.core.data.AccuracyGateTest" -i 2>&1 | tee /tmp/claude-0/-home-user-Lame/23273d6f-59f8-4237-a0da-9a801e705a41/scratchpad/gate-before.txt | grep -i -A12 "MAE"
```

Expected: PASS, and a table of 2025 MAE by position (model vs season-to-date average) is printed. Keep `gate-before.txt`; Task 3 compares against it.

- [ ] **Step 2: Add the injury table and helper to `TestDb`**

In `TestDb.kt`, add this helper next to `game(...)`:

```kotlin
    fun injury(id: String, season: Int, week: Int, status: String?) = exec(
        "INSERT OR REPLACE INTO injury_report (player_id, season, week, status) VALUES (?, ?, ?, ?)",
        id, season, week, status,
    )
```

and append this entry to the end of the `DDL` list (after the `game` table):

```kotlin
            """CREATE TABLE injury_report (
                player_id TEXT NOT NULL, season INTEGER NOT NULL, week INTEGER NOT NULL,
                team TEXT, name TEXT, position TEXT, status TEXT, injury TEXT, practice TEXT,
                PRIMARY KEY (player_id, season, week)) WITHOUT ROWID""",
```

- [ ] **Step 3: Write the failing test**

Add to `InputsTest.kt`:

```kotlin
    @Test
    fun `only Out and Doubtful offensive players are absent`() {
        TestDb(File(dir, "stats.db")).use { db ->
            db.player("WR1", "WR", "AAA")
            db.player("WR2", "WR", "AAA")
            db.player("WR3", "WR", "AAA")
            db.player("K1", "K", "AAA")
            db.injury("WR1", 2025, 3, "Out")
            db.injury("WR2", 2025, 3, "Doubtful")
            db.injury("WR3", 2025, 3, "Questionable")
            db.injury("WR3", 2025, 4, null)
            db.injury("K1", 2025, 3, "Out")
            db.injury("GHOST", 2025, 3, "Out") // not in `player`

            assertEquals(setOf(Triple("WR1", 2025, 3), Triple("WR2", 2025, 3)), loadInputs(db.conn).absent)
        }
    }
```

- [ ] **Step 4: Run it to see it fail**

Run: `./gradlew :core:forecast:test --tests "dev.gridiron.core.forecast.InputsTest"`
Expected: FAIL to compile (`absent` is unresolved).

- [ ] **Step 5: Implement**

In `Inputs.kt`, add the field to `ForecastInputs` after `unitHistory`:

```kotlin
    /** (player id, season, week) for every QB, RB, WR or TE nflverse listed Out or Doubtful: none of them has ever played that week. */
    val absent: Set<Triple<String, Int, Int>> = emptySet(),
```

Change the `ForecastInputs(...)` call in `loadInputs` to pass it:

```kotlin
    return ForecastInputs(
        readPlayers(conn, POSITIONS), history, readGames(conn), teamGames, readExpectedThrough(conn),
        units = readPlayers(conn, UNIT_POSITIONS),
        unitHistory = readHistory(conn, UNIT_POSITIONS, UNIT_METRICS),
        absent = readAbsent(conn),
    )
```

Add this function beside `readGames`:

```kotlin
private fun readAbsent(conn: SQLiteConnection): Set<Triple<String, Int, Int>> = conn.prepare(
    """SELECT i.player_id, i.season, i.week FROM injury_report i
       JOIN player p ON p.player_id = i.player_id
       WHERE i.status IN ('Out', 'Doubtful') AND p.position IN (${POSITIONS.joinToString(",") { "?" }})""",
).use { st ->
    POSITIONS.forEachIndexed { i, p -> st.bindText(i + 1, p) }
    buildSet {
        while (st.step()) add(Triple(st.getText(0), st.getLong(1).toInt(), st.getLong(2).toInt()))
    }
}
```

- [ ] **Step 6: Run the module's tests**

Run: `./gradlew :core:forecast:test`
Expected: PASS (the new test and every existing test; the added `injury_report` table is empty in the others).

- [ ] **Step 7: Commit**

```bash
git add core/forecast docs/superpowers/specs docs/superpowers/plans
git commit -m "feat(forecast): read Out and Doubtful players into the inputs

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NAmSfMG3LfdCw9DC5pxByG"
```

---

### Task 2: Drop absent players and redistribute their share

**Files:**
- Modify: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Projector.kt` (`prepareWeek` at lines 152-180)
- Test: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/ForecastEngineTest.kt`

**Interfaces:**
- Consumes: `ForecastInputs.absent`, `TestDb.injury(...)` (Task 1); existing `Projector` members `expectedStarter`, `isActive`, `finish`, `model.shares`, `normalizeShares`, `teamVolume`, `Prepared`, `Draft`.
- Produces: nothing later tasks call; behavior only.

- [ ] **Step 1: Write the failing tests**

Add to `ForecastEngineTest.kt`, next to the other engine tests (the file's helpers `league`, `run`, `targets`, `rosTargets` and `db.query` are used as they are):

```kotlin
    private fun baselineTargets(db: TestDb, player: String): Double = db.query(
        "SELECT mean FROM player_week_projection WHERE player_id = '$player' AND season = 2025 AND week = 3 AND stage = 'baseline' AND metric_id = 'targets'",
    ).single()[0]!!.toDouble()

    private fun weekRows(db: TestDb, player: String, season: Int, week: Int): Int = db.query(
        "SELECT COUNT(*) FROM player_week_projection WHERE player_id = '$player' AND season = $season AND week = $week",
    ).single()[0]!!.toInt()

    @Test
    fun `a player listed Out has no row that week and his team takes his targets, but keeps his later weeks`() {
        var baseWr = 0.0
        var baseRb = 0.0
        var baseRbWeek3 = 0.0
        var baseRbRos = 0.0
        var baseWrRos = 0.0
        league("base.db").use { db ->
            run(db)
            baseWr = baselineTargets(db, "WR_A")
            baseRb = baselineTargets(db, "RB_A")
            baseRbWeek3 = targets(db, "RB_A", 3)
            baseRbRos = rosTargets(db, "RB_A")
            baseWrRos = rosTargets(db, "WR_A")
        }
        league("hurt.db").use { db ->
            db.injury("WR_A", 2025, 3, "Out")
            run(db)

            assertEquals(0, weekRows(db, "WR_A", 2025, 3))
            // AAA's two target-getters split all its targets; with the WR out, the RB has them all.
            assertEquals(baseWr + baseRb, baselineTargets(db, "RB_A"), 1e-9)
            assertTrue(targets(db, "RB_A", 3) > baseRbWeek3)
            // Week 4 doesn't know about the injury: the RB's rest of season past this week matches a healthy roster's.
            assertEquals(baseRbRos - baseRbWeek3, rosTargets(db, "RB_A") - targets(db, "RB_A", 3), 1e-9)
            // The WR keeps week 4 and loses only week 3.
            val wrRos = rosTargets(db, "WR_A")
            assertTrue(wrRos > 0.0 && wrRos < baseWrRos)
        }
    }

    @Test
    fun `a Questionable player is projected as usual`() {
        var base = 0.0
        league("base.db").use { db ->
            run(db)
            base = baselineTargets(db, "WR_A")
        }
        league("q.db").use { db ->
            db.injury("WR_A", 2025, 3, "Questionable")
            run(db)
            assertEquals(base, baselineTargets(db, "WR_A"), 1e-12)
        }
    }

    @Test
    fun `a past week's Out player is missing from that week only`() {
        league("past.db").use { db ->
            db.injury("WR_A", 2025, 2, "Doubtful")
            run(db)
            assertEquals(0, weekRows(db, "WR_A", 2025, 2))
            assertTrue(weekRows(db, "WR_A", 2025, 1) > 0)
            assertTrue(weekRows(db, "WR_A", 2025, 3) > 0)
        }
    }

    @Test
    fun `an Out starting QB hands the passing to the next QB, and with no other QB nobody passes`() {
        league("qb.db").use { db ->
            db.player("QB2_A", "QB", "AAA")
            db.week("QB2_A", 2025, 2, "AAA", "attempts" to 5.0, "completions" to 3.0, "passing_yards" to 30.0)
            db.injury("QB_A", 2025, 3, "Out")
            run(db)
            assertEquals(0, weekRows(db, "QB_A", 2025, 3))
            assertTrue(weekRows(db, "QB2_A", 2025, 3) > 0)
        }
        league("only.db").use { db ->
            db.injury("QB_A", 2025, 3, "Out")
            run(db)
            assertEquals(0, weekRows(db, "QB_A", 2025, 3))
            assertTrue(weekRows(db, "WR_A", 2025, 3) > 0)
        }
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :core:forecast:test --tests "dev.gridiron.core.forecast.ForecastEngineTest"`
Expected: FAIL. The Out WR, Doubtful WR and Out QB still have rows; `Questionable` passes already.

- [ ] **Step 3: Implement in `Projector.kt`**

Replace `prepareWeek` (the whole function, its doc comment included) with:

```kotlin
    /** One team's week: the QB who passes, who is projected, and their shares. */
    private class Roster(val starter: String?, val kept: List<Draft>, val shares: Map<String, Shares>)

    /**
     * One week's projections, a team at a time: the expected starting QB and
     * the active players, with the team's target and carry shares scaled to
     * sum to one (spec amendment to layer 2). Players nflverse lists Out or
     * Doubtful that week are left out first, so their volume goes to the rest.
     * The upcoming week's players are matched to [props] first.
     */
    private fun prepareWeek(state: WeekState, kind: WeekKind): List<Prepared> {
        val drafts = candidates(state.order).mapNotNull { draft(state, it, kind) }
        val market = if (kind == WeekKind.UPCOMING && props != null) {
            MarketMatch(
                props,
                inputs.games.filter { it.season == state.season && it.week == state.week }.map { it.home to it.away },
                drafts.map { PropCandidate(it.player.playerId, it.player.name, it.team) },
            ).also { unmatched = it.unmatched }
        } else {
            null
        }
        return drafts.groupBy { it.team }.flatMap { (team, onTeam) ->
            val volume = teamVolume(teamHistory[team].orEmpty().takeWhile { it.order < state.order }, state.leagueTeam)
            val roster = roster(team, onTeam, state, kind, respectAbsent = true)
            val prepared = roster.kept.map { d -> finish(state, d, roster.shares.getValue(d.player.playerId), volume, kind, market) }
            if (kind != WeekKind.UPCOMING) return@flatMap prepared
            // Rest of season starts from this list. An injury this week says nothing about later weeks, so it
            // gets the healthy roster's baseline; this week's own contribution is what was just projected.
            val healthy = roster(team, onTeam, state, kind, respectAbsent = false)
            if (healthy.starter == roster.starter && healthy.kept.size == roster.kept.size) return@flatMap prepared
            val projected = prepared.associateBy { it.player.playerId }
            healthy.kept.map { d ->
                val id = d.player.playerId
                Prepared(
                    d.player, d.team, model.project(d.ctx, d.rates, volume, healthy.shares.getValue(id)), volume.passRate,
                    upcoming = projected[id]?.upcoming ?: emptyMap(),
                )
            }
        }
    }

    private fun roster(team: String, onTeam: List<Draft>, state: WeekState, kind: WeekKind, respectAbsent: Boolean): Roster {
        val available = if (respectAbsent) onTeam.filterNot { isAbsent(it, state) } else onTeam
        val starter = expectedStarter(team, available, state, kind)
        val kept = available.filter { d ->
            if (d.player.position == "QB") d.player.playerId == starter else isActive(d, team, state, kind)
        }
        val shares = normalizeShares(
            kept.associate { d -> d.player.playerId to model.shares(d.ctx, d.rates, starter = d.player.playerId == starter) },
        )
        return Roster(starter, kept, shares)
    }

    /** Whether nflverse lists him Out or Doubtful this week. */
    private fun isAbsent(d: Draft, state: WeekState): Boolean = Triple(d.player.playerId, state.season, state.week) in inputs.absent
```

- [ ] **Step 4: Run the module's tests**

Run: `./gradlew :core:forecast:test`
Expected: PASS: the four new tests and every existing engine, baseline and inputs test unchanged. If the QB test fails on `weekRows(db, "QB2_A", 2025, 3) > 0`, print the `QB2_A` rows for week 3 before changing anything: the backup's projection must clear `K.UPCOMING_MIN_POINTS`, and the fix is a larger `attempts` value in the test's `db.week(...)` call, not a change to the model.

- [ ] **Step 5: Commit**

```bash
git add core/forecast
git commit -m "feat(forecast): redistribute an Out or Doubtful player's share to teammates

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NAmSfMG3LfdCw9DC5pxByG"
```

---

### Task 3: Version, accuracy gate and docs

**Files:**
- Modify: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/ForecastConstants.kt:8`
- Modify: `docs/superpowers/specs/2026-09-26-projection-model-design.md` (line 23 and the out-of-scope list near line 262)
- Modify: `CLAUDE.md` ("Known Gaps" → the **Not modeled** bullet, and the `:core:forecast` module bullet)
- Modify: `docs/superpowers/HANDOFF.md`
- Modify: `docs/superpowers/specs/2026-09-29-injury-share-redistribution-design.md` (status line)

**Interfaces:**
- Consumes: Tasks 1 and 2.
- Produces: `FORECAST_VERSION = 6`.

- [ ] **Step 1: Bump the version**

In `ForecastConstants.kt` change line 8 to:

```kotlin
public const val FORECAST_VERSION: Int = 6
```

- [ ] **Step 2: Rebuild the accuracy database and run the gate**

```bash
./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025" -Pout=etl/build/accuracy.db
GRIDIRON_STATS_DB=etl/build/accuracy.db GRIDIRON_ACCURACY_GATE=2025 ./gradlew :core:data:test --tests "dev.gridiron.core.data.AccuracyGateTest" -i 2>&1 | tee /tmp/claude-0/-home-user-Lame/23273d6f-59f8-4237-a0da-9a801e705a41/scratchpad/gate-after.txt | grep -i -A12 "MAE"
```

Expected: PASS. Compare with `gate-before.txt` (Task 1, Step 1): the model's 2025 MAE at QB, RB, WR and TE should be equal or lower, and the floor-to-ceiling "held" figures should stay about 78–83%.

If the gate fails, or a position's MAE is worse than before: stop and diagnose before touching docs. Compare the two builds' final-stage rows for the player-weeks both project (the difference should come from teammates of Out players, not from the players who were dropped); if teammates got worse, the proportional redistribution is too crude for that position, and the options are to limit the change to the positions where it helps or to tune constants in `ForecastConstants.kt`. Report the numbers and the choice to the user before shipping.

- [ ] **Step 3: Run the whole test suite**

```bash
export GRIDIRON_STATS_DB=etl/build/stats.db
./gradlew test
```

Expected: PASS, except `RealDatabaseContractTest > scoring a full season for every player is fast`, which is known to fail in this container (see `HANDOFF.md`); nothing else may fail.

- [ ] **Step 4: Update the docs**

In `docs/superpowers/specs/2026-09-26-projection-model-design.md`:
- Replace the table row on line 23 (`| Injury redistribution | Not modeled. ... |`) with:
  `| Injury redistribution | Built (2026-09-29, see `2026-09-29-injury-share-redistribution-design.md`). Out and Doubtful players get no projection and their teams' shares renormalize. |`
- Delete the out-of-scope bullet `- Redistributing an injured player's share to teammates.`

In `CLAUDE.md`:
- In the **Not modeled** bullet, replace `weather (out of scope by the user's call) and shifting an injured player's share to teammates; an Out/IR player just shows Out, and a player returning from injury isn't projected until he plays again.` with `weather (out of scope by the user's call) and a player's chance of playing: an Out or Doubtful player from nflverse's injury report gets no projection and his teammates take his share, while a Questionable one is projected as playing; a player returning from injury isn't projected until he plays again.`
- In the `:core:forecast` bullet, after the sentence ending `a \`market\` factor in the waterfall).` add: ` Players nflverse lists Out or Doubtful for a week are left out of that week (\`ForecastInputs.absent\`), so \`normalizeShares\` gives their volume to teammates; rest of season is built from the healthy roster.`

In the spec `2026-09-29-injury-share-redistribution-design.md`, change the status line to `**Status:** built (2026-09-29). Gate numbers are in the PR.`

In `docs/superpowers/HANDOFF.md`: move "Injured player's share to teammates" out of the candidate gaps; under "Where things stand" add a short "Just shipped" paragraph for this change (what it does, the before/after 2025 MAE at QB, RB, WR and TE from `gate-before.txt` and `gate-after.txt`, `FORECAST_VERSION` 6); update the PR list; keep the rest of the file as it is.

- [ ] **Step 5: Commit and push**

```bash
git add -A
git commit -m "feat(forecast): version 6, docs for injury redistribution

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NAmSfMG3LfdCw9DC5pxByG"
git push -u origin claude/dreamy-euler-phbdq1
```

Then update PR #16's title and body (it is the spec's PR): title `Injured players' share to teammates`, body with a summary, the before/after gate table, and the test plan.
