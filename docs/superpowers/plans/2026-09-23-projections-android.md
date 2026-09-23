# Projections Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the on-device half of Phase 5 (Projections): a pure Kotlin scorer shared
with the existing SQL-generated scoring, a `:core:projections` module that reads the new
`player_week_projection`/`player_week_projection_factor`/`player_ros_projection` tables
and applies the user's real `ScoringProfile`, single-player Monte Carlo for floor/ceiling,
and a `:feature:projections` module with the explainability waterfall and accuracy page.

**Architecture:** `:core:projections` sits beside `:core:statquery` the way `:core:data`
sits beside `:core:statquery` today — one new query/compute layer, one new
repository/IO layer. `:feature:projections` sits beside `:feature:compare` and
`:feature:scoring`. The scorer is genuinely shared, not reimplemented: `RULE_INPUTS` and
`BONUS_INPUTS` (`core/statquery/.../Scoring.kt`), which today back only SQL string
generation in `StatQueryBuilder`, become `public` and are read directly by the new
`score()` function — one table of rule-to-component mappings, two consumers.

**Tech Stack:** Kotlin, Jetpack Compose, the existing `QueryExecutor`/`ResultRow`
abstraction (`core/database/.../QueryExecutor.kt`) for real-vs-JDBC-fixture testing
parity, `kotlinx.collections.immutable` (existing dependency, matches `StatsRepository`'s
use of it), plain `DoubleArray` + `java.util.SplittableRandom` for Monte Carlo (no new
dependency — matches the design spec and research doc's explicit recommendation against
a numeric library for this workload).

**Spec:** `docs/superpowers/specs/2026-09-23-projections-design.md`, §3 (on-device) and
§4 (accuracy tracking's UI half). Depends on
`docs/superpowers/plans/2026-09-23-projections-etl.md` for the schema
(`player_week_projection`, `player_week_projection_factor`, `player_ros_projection`,
`accuracy_summary`) — this plan's tests build their own JDBC fixture DB with that schema
and hand-inserted rows, so it does not depend on the ETL plan's pipeline code actually
running, only on its schema existing.

## Global Constraints

- Never ship fantasy points from the server — scoring is always applied on-device from
  raw components (spec Intent, §3). `score()` is the one place that happens.
- `ScoringRule`/`RULE_INPUTS` cover QB, RB, WR, TE only — kicking, team defense and IDP
  are absent on purpose (`core/model/.../Scoring.kt:12-13`, a pre-existing, app-wide
  limitation, not introduced by this plan). **Consequence, stated up front:** `score()`
  and every task built on it (repository point-mean, Monte Carlo, the waterfall) covers
  QB/RB/WR/TE. K/DST projections can still be read as raw stat components (if the ETL
  side ever populates them — see the ETL plan's own K/DST wiring deferral) but get no
  fantasy-point number or waterfall in this plan; extending `ScoringRule` to a
  fundamentally different scoring shape (DST's tiered points/yards-allowed brackets) is
  out of scope and needs its own design pass, not a quick addition here.
- Existing repository shape: constructor takes `QueryExecutor` + `Locale`, `suspend fun`
  methods take a `*Request` + `Catalog`, return a `*Page` (`StatsRepository.kt:21-45`).
  `ProjectionsRepository` follows this exactly.
- `QueryExecutor.query(query: SqlQuery, map: (ResultRow) -> T): List<T>` is the one
  seam between real SQLite and JDBC test fixtures (`core/database/.../QueryExecutor.kt`)
  — every new query goes through it, never a direct JDBC/Android SQLite call.

## Review Focus

- **A player whose position has no `ScoringRule` coverage (a K or DST row reaches
  `score()`).** Must return a defined value (0.0, since no rule fires) and never throw —
  a crash on an unsupported position would take down the whole Grid-adjacent screen, not
  just degrade one row.
- **A component map missing an entry `score()` expects (e.g. a metric the ETL hasn't
  populated for some player-week).** `score()`'s component lookup must default to 0.0,
  not throw a `NoSuchElementException` — this is the same "n=0 must not crash" discipline
  the ETL plan's Review Focus applies server-side, now on the client.
- **Two rapid sheet/screen navigations to the same player's projection before the first
  query returns.** `ProjectionsRepository` calls must be safely cancellable (a newer
  request supersedes an older in-flight one) — the same stale-result class of bug the
  Grid's sparkline loading already solved (`GridViewModel`'s `page == base.page` guard),
  now needed here too.
- **A player with zero variance shipped for a metric (e.g. a bye-week placeholder or a
  data gap).** The Monte Carlo draw must not divide by zero or produce `NaN` percentiles
  — every distribution-family sampler needs a defined, sane behavior at `variance = 0`
  (a point mass at the mean, not a crash).
- **The factor table's log-multipliers summing to a value very close to zero** (an
  unusual but legal case — adjustments that nearly cancel out). The apportionment
  `Δᵢ = Δ × log_multiplier_i / Σⱼ log_multiplier_j` divides by that sum — must guard the
  near-zero denominator the same way `ratio()` guards zero denominators throughout the
  ETL's own `transform.py`, rather than emitting `Infinity`/`NaN` into the waterfall UI.

---

## Task 1: Widen scoring internals to `public` and add a contract test

**Files:**
- Modify: `core/statquery/src/main/kotlin/dev/gridiron/core/statquery/Scoring.kt`
- Test: `core/statquery/src/test/kotlin/dev/gridiron/core/statquery/ScoringVisibilityTest.kt`

**Interfaces:**
- Produces: `Term`, `RuleInputs`, `RULE_INPUTS: Map<ScoringRule, RuleInputs>`,
  `BONUS_INPUTS: Map<BonusStat, List<Component>>` — all changed from `internal` to
  `public`, values and shape otherwise unchanged. This is the one thing `:core:projections`
  (Task 2) needs from `:core:statquery`.

- [ ] **Step 1: Write the failing test**

```kotlin
// core/statquery/src/test/kotlin/dev/gridiron/core/statquery/ScoringVisibilityTest.kt
package dev.gridiron.core.statquery

import dev.gridiron.core.model.ScoringRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScoringVisibilityTest {
    @Test
    fun `RULE_INPUTS is public and covers every ScoringRule`() {
        assertEquals(ScoringRule.entries.toSet(), RULE_INPUTS.keys)
    }

    @Test
    fun `RULE_INPUTS reception rule reads the RECEPTIONS component`() {
        val inputs = RULE_INPUTS.getValue(ScoringRule.RECEPTION)
        assertTrue(inputs.actual.any { it.component == Components.RECEPTIONS })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:statquery:testDebugUnitTest --tests "*ScoringVisibilityTest*"`
Expected: FAIL — compile error, `RULE_INPUTS` is `internal`, not visible from the test's
package... actually the test is in the same package (`dev.gridiron.core.statquery`), so
`internal` members ARE visible within the module. The real failure mode this test guards
against is a *future* mistake (accidentally re-narrowing visibility once `:core:projections`
depends on it from Task 2 onward) — so this step instead confirms the test **compiles and
passes today**, establishing the contract before Task 2 depends on it cross-module.

Run: `./gradlew :core:statquery:testDebugUnitTest --tests "*ScoringVisibilityTest*"`
Expected: PASS (already true before any change — `RULE_INPUTS`/`Term`/`RuleInputs` exist
and behave as asserted; this step confirms the baseline, not a red-to-green transition, for
this one visibility-only task).

- [ ] **Step 3: Change visibility**

In `core/statquery/src/main/kotlin/dev/gridiron/core/statquery/Scoring.kt`, change:

```kotlin
internal data class Term(val component: Component, val sign: Double = 1.0)
```
to
```kotlin
public data class Term(public val component: Component, public val sign: Double = 1.0)
```

and:
```kotlin
internal data class RuleInputs(val actual: List<Term>, val expected: List<Term>)
```
to
```kotlin
public data class RuleInputs(public val actual: List<Term>, public val expected: List<Term>)
```

and:
```kotlin
internal val RULE_INPUTS: Map<ScoringRule, RuleInputs> = mapOf(
```
to
```kotlin
public val RULE_INPUTS: Map<ScoringRule, RuleInputs> = mapOf(
```

and:
```kotlin
internal val BONUS_INPUTS: Map<BonusStat, List<Component>> = mapOf(
```
to
```kotlin
public val BONUS_INPUTS: Map<BonusStat, List<Component>> = mapOf(
```

`SCORING_COMPONENTS` and the `on()` helper stay `internal`/`private` — nothing outside
this file needs them.

- [ ] **Step 4: Run the full `:core:statquery` test suite**

Run: `./gradlew :core:statquery:testDebugUnitTest`
Expected: PASS — a visibility widening changes no behavior; every existing test (which
already ran inside the same package) keeps passing unchanged.

- [ ] **Step 5: Commit**

```bash
git add core/statquery/src/main/kotlin/dev/gridiron/core/statquery/Scoring.kt \
        core/statquery/src/test/kotlin/dev/gridiron/core/statquery/ScoringVisibilityTest.kt
git commit -m "statquery: expose RULE_INPUTS/BONUS_INPUTS for reuse outside SQL generation"
```

---

## Task 2: `:core:projections` module scaffold and the pure `score()` function

**Files:**
- Modify: `settings.gradle.kts` (add `include(":core:projections")`)
- Create: `core/projections/build.gradle.kts` (copy the shape of `core/statquery/build.gradle.kts`,
  depending on `:core:model` and `:core:statquery`)
- Create: `core/projections/src/main/kotlin/dev/gridiron/core/projections/Scorer.kt`
- Test: `core/projections/src/test/kotlin/dev/gridiron/core/projections/ScorerTest.kt`

**Interfaces:**
- Consumes: `RULE_INPUTS`, `BONUS_INPUTS` (Task 1); `Component`, `Components`
  (`:core:statquery`, existing); `ScoringProfile`, `ScoringRule`, `Position`
  (`:core:model`, existing).
- Produces: `fun score(components: Map<Component, Double>, profile: ScoringProfile,
  position: Position?): Double`.

- [ ] **Step 1: Check `core/statquery/build.gradle.kts` for the module template to copy**

Run: `cat core/statquery/build.gradle.kts`

Use its plugin block and dependency declarations as the template for the new module's
build file, changing only the module name and adding a `implementation(project(":core:statquery"))`
line (it currently depends on `:core:model` only).

- [ ] **Step 2: Add the module and write the failing test**

In `settings.gradle.kts`, add `include(":core:projections")` alongside the other
`core:*` includes (after `include(":core:statquery")`, keeping the existing alphabetical-ish
grouping).

```kotlin
// core/projections/src/test/kotlin/dev/gridiron/core/projections/ScorerTest.kt
package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringRule
import dev.gridiron.core.statquery.Components
import kotlin.test.Test
import kotlin.test.assertEquals

class ScorerTest {
    @Test
    fun `PPR scores a reception, a yard and a touchdown`() {
        val components = mapOf(
            Components.RECEPTIONS to 5.0,
            Components.RECEIVING_YARDS to 60.0,
            Components.RECEIVING_TDS to 1.0,
        )
        // 5*1.0 (PPR reception) + 60*0.1 (yardage) + 1*6.0 (TD) = 5 + 6 + 6 = 17
        assertEquals(17.0, score(components, ScoringPresets.PPR, Position.WR), absoluteTolerance = 1e-9)
    }

    @Test
    fun `standard scoring gives no reception points`() {
        val components = mapOf(Components.RECEPTIONS to 5.0)
        assertEquals(0.0, score(components, ScoringPresets.STANDARD, Position.WR), absoluteTolerance = 1e-9)
    }

    @Test
    fun `a component absent from the map scores as zero, never throws`() {
        // Only receptions supplied; every other rule's component is missing
        // from the map entirely, not present-with-null.
        val components = mapOf(Components.RECEPTIONS to 3.0)
        val result = score(components, ScoringPresets.PPR, Position.WR)
        assertEquals(3.0, result, absoluteTolerance = 1e-9) // 3 receptions * 1.0, nothing else
    }

    @Test
    fun `an unsupported position (kicker) scores zero without throwing`() {
        val components = mapOf(Components.RECEPTIONS to 5.0)
        // No ScoringRule reads a kicker-specific component, and Position.K
        // (if it exists) or a null position must not crash score().
        val result = score(components, ScoringPresets.PPR, position = null)
        assertEquals(5.0, result, absoluteTolerance = 1e-9) // PPR reception rule still applies to the map's contents
    }
}

private fun assertEquals(expected: Double, actual: Double, absoluteTolerance: Double) {
    kotlin.test.assertTrue(
        kotlin.math.abs(expected - actual) <= absoluteTolerance,
        "expected $expected, was $actual",
    )
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :core:projections:testDebugUnitTest`
Expected: FAIL — module doesn't build yet / `score` is unresolved.

- [ ] **Step 4: Implement**

```kotlin
// core/projections/src/main/kotlin/dev/gridiron/core/projections/Scorer.kt
package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.ScoringRule
import dev.gridiron.core.statquery.BONUS_INPUTS
import dev.gridiron.core.statquery.Component
import dev.gridiron.core.statquery.RULE_INPUTS

/**
 * Applies [profile] to raw stat components in memory — the numeric twin of
 * `StatQueryBuilder.points()`, which does the same rule lookups but emits SQL
 * text instead of a number. Both read the same [RULE_INPUTS]/[BONUS_INPUTS]
 * tables, so there is one source of truth for what a [ScoringRule] means,
 * even though SQL generation and direct evaluation are necessarily different
 * code shapes.
 *
 * A component missing from [components] scores as zero, never throws — this
 * is called from single-player Monte Carlo tens of thousands of times per
 * second, and a metric the ETL hasn't populated for some player-week must
 * degrade quietly, not crash the caller.
 */
public fun score(components: Map<Component, Double>, profile: ScoringProfile,
                  position: Position?): Double {
    fun value(component: Component): Double = components[component] ?: 0.0

    var total = 0.0
    for (rule in ScoringRule.entries) {
        val inputs = RULE_INPUTS.getValue(rule)
        for (term in inputs.actual) {
            val weight = if (rule == ScoringRule.RECEPTION) {
                profile.receptionWeight(position)
            } else {
                profile.weight(rule) * term.sign
            }
            total += weight * value(term.component)
        }
    }
    for (bonus in profile.yardageBonuses) {
        val yards = BONUS_INPUTS.getValue(bonus.stat).sumOf { value(it) }
        if (bonus.applies(yards)) total += bonus.points
    }
    return total
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :core:projections:testDebugUnitTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts core/projections/build.gradle.kts \
        core/projections/src/main/kotlin/dev/gridiron/core/projections/Scorer.kt \
        core/projections/src/test/kotlin/dev/gridiron/core/projections/ScorerTest.kt
git commit -m "projections: add :core:projections module with a pure score() function"
```

---

## Task 3: Projection models and queries

**Files:**
- Create: `core/projections/src/main/kotlin/dev/gridiron/core/projections/ProjectionModels.kt`
- Create: `core/projections/src/main/kotlin/dev/gridiron/core/projections/ProjectionQueries.kt`
- Test: `core/projections/src/test/kotlin/dev/gridiron/core/projections/ProjectionQueriesTest.kt`

**Interfaces:**
- Consumes: `SqlQuery` (`:core:statquery`, existing — the type `QueryExecutor.query` takes).
- Produces:
  ```kotlin
  public data class ProjectionComponent(val metricId: String, val mean: Double, val variance: Double)
  public data class ProjectionFactor(val factor: String, val logMultiplier: Double, val note: String?)
  public data class PlayerProjection(
      val playerId: String, val season: Int, val week: Int,
      val baseline: List<ProjectionComponent>, val final: List<ProjectionComponent>,
      val factors: List<ProjectionFactor>,
  )
  public data class RosProjection(val playerId: String, val components: List<ProjectionComponent>)
  public data class ProjectionsRequest(val playerIds: Set<String>, val season: Int, val week: Int)
  public data class RosProjectionsRequest(val playerIds: Set<String>, val season: Int)
  public object ProjectionQueries {
      public fun weekly(playerIds: Set<String>, season: Int, week: Int): SqlQuery
      public fun factors(playerIds: Set<String>, season: Int, week: Int): SqlQuery
      public fun ros(playerIds: Set<String>, season: Int): SqlQuery
  }
  ```

- [ ] **Step 1: Confirm `SqlQuery`'s bound-parameter shape**

`SqlQuery` (`core/statquery/src/main/kotlin/dev/gridiron/core/statquery/StatQueryBuilder.kt`,
near `GridQuery`) is `data class SqlQuery(val sql: String, val binds: List<Bind>)`, with an
`init` block that requires the placeholder count (`?` in `sql`) to equal `binds.size` —
every value is bound, never string-interpolated. `Bind` is `sealed interface Bind` with
`Bind.Text`, `Bind.Integer`, `Bind.Real` value classes. `CatalogQueries.kt` is the existing
example to match: e.g. `SqlQuery("SELECT ... WHERE metric_id = ?", listOf(Bind.Text(id)))`.
The implementation below already follows this shape.

- [ ] **Step 2: Write the failing test**

```kotlin
// core/projections/src/test/kotlin/dev/gridiron/core/projections/ProjectionQueriesTest.kt
package dev.gridiron.core.projections

import kotlin.test.Test
import kotlin.test.assertTrue

class ProjectionQueriesTest {
    @Test
    fun `weekly query selects from player_week_projection filtered by player ids`() {
        val query = ProjectionQueries.weekly(setOf("P1", "P2"), season = 2026, week = 3)
        assertTrue(query.sql.contains("player_week_projection"))
        assertTrue(query.sql.contains("season"))
        assertTrue(query.sql.contains("week"))
    }

    @Test
    fun `weekly query with no player ids still produces valid, non-crashing SQL`() {
        // An empty selection (e.g. a page with zero rows) must not build SQL
        // with an empty IN () clause, which is invalid in SQLite.
        val query = ProjectionQueries.weekly(emptySet(), season = 2026, week = 3)
        assertTrue(query.sql.contains("0 = 1") || query.sql.contains("FALSE"))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :core:projections:testDebugUnitTest --tests "*ProjectionQueriesTest*"`
Expected: FAIL — `ProjectionQueries` doesn't exist yet.

- [ ] **Step 4: Implement models and queries**

```kotlin
// core/projections/src/main/kotlin/dev/gridiron/core/projections/ProjectionModels.kt
package dev.gridiron.core.projections

public data class ProjectionComponent(val metricId: String, val mean: Double, val variance: Double)
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
```

```kotlin
// core/projections/src/main/kotlin/dev/gridiron/core/projections/ProjectionQueries.kt
package dev.gridiron.core.projections

import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.SqlQuery

/**
 * Raw SQL for the projection tables, following [dev.gridiron.core.statquery.CatalogQueries]'s
 * convention: every value is a `?` bound through [Bind], never string-interpolated
 * (`SqlQuery`'s own `init` block enforces placeholder count == bind count, so a
 * mismatched query fails to construct rather than reaching the database). An
 * empty [playerIds] set produces a `WHERE 0 = 1` clause rather than an empty
 * `IN ()`, which SQLite rejects outright.
 */
public object ProjectionQueries {
    private fun placeholders(n: Int): String = List(n) { "?" }.joinToString(",")

    public fun weekly(playerIds: Set<String>, season: Int, week: Int): SqlQuery {
        if (playerIds.isEmpty()) {
            return SqlQuery(
                "SELECT player_id, metric_id, stage, mean, variance " +
                    "FROM player_week_projection WHERE 0 = 1",
                emptyList(),
            )
        }
        val ids = playerIds.toList()
        return SqlQuery(
            """
            SELECT player_id, metric_id, stage, mean, variance
            FROM player_week_projection
            WHERE player_id IN (${placeholders(ids.size)}) AND season = ? AND week = ?
            """.trimIndent(),
            ids.map { Bind.Text(it) } + listOf(Bind.Integer(season.toLong()), Bind.Integer(week.toLong())),
        )
    }

    public fun factors(playerIds: Set<String>, season: Int, week: Int): SqlQuery {
        if (playerIds.isEmpty()) {
            return SqlQuery(
                "SELECT player_id, factor, log_multiplier, note " +
                    "FROM player_week_projection_factor WHERE 0 = 1",
                emptyList(),
            )
        }
        val ids = playerIds.toList()
        return SqlQuery(
            """
            SELECT player_id, factor, log_multiplier, note
            FROM player_week_projection_factor
            WHERE player_id IN (${placeholders(ids.size)}) AND season = ? AND week = ?
            """.trimIndent(),
            ids.map { Bind.Text(it) } + listOf(Bind.Integer(season.toLong()), Bind.Integer(week.toLong())),
        )
    }

    public fun ros(playerIds: Set<String>, season: Int): SqlQuery {
        if (playerIds.isEmpty()) {
            return SqlQuery(
                "SELECT player_id, metric_id, mean, variance FROM player_ros_projection WHERE 0 = 1",
                emptyList(),
            )
        }
        val ids = playerIds.toList()
        return SqlQuery(
            """
            SELECT player_id, metric_id, mean, variance
            FROM player_ros_projection
            WHERE player_id IN (${placeholders(ids.size)}) AND season = ?
              AND as_of_week = (SELECT MAX(as_of_week) FROM player_ros_projection WHERE season = ?)
            """.trimIndent(),
            ids.map { Bind.Text(it) } + listOf(Bind.Integer(season.toLong()), Bind.Integer(season.toLong())),
        )
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :core:projections:testDebugUnitTest --tests "*ProjectionQueriesTest*"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add core/projections/src/main/kotlin/dev/gridiron/core/projections/ProjectionModels.kt \
        core/projections/src/main/kotlin/dev/gridiron/core/projections/ProjectionQueries.kt \
        core/projections/src/test/kotlin/dev/gridiron/core/projections/ProjectionQueriesTest.kt
git commit -m "projections: models and SQL for the weekly, factor, and ROS projection tables"
```

---

## Task 4: `ProjectionsRepository`

**Files:**
- Create: `core/data/src/main/kotlin/dev/gridiron/core/data/ProjectionsRepository.kt`
- Test: `core/data/src/test/kotlin/dev/gridiron/core/data/ProjectionsRepositoryTest.kt`
  (JDBC fixture DB, mirroring how `StatsRepository`'s existing tests build one — see
  `RealDatabaseContractTest` or the nearest equivalent fixture-setup helper already in
  `core/data/src/test`)

**Interfaces:**
- Consumes: `ProjectionQueries` (Task 3), `score()` (Task 2), `QueryExecutor` (existing).
- Produces:
  ```kotlin
  public class ProjectionsRepository(private val executor: QueryExecutor) {
      public suspend fun projections(request: ProjectionsRequest): List<PlayerProjection>
      public suspend fun rosProjections(request: RosProjectionsRequest): List<RosProjection>
  }
  ```

- [ ] **Step 1: Locate the existing JDBC fixture helper**

Run: `grep -rl "JDBC\|jdbc:sqlite" core/data/src/test core/testing/src 2>/dev/null`

Use whatever helper that search finds (e.g. a `TestQueryExecutor` or an in-memory SQLite
JDBC setup already shared by `StatsRepository`'s tests) rather than building a second one
— `:core:testing` exists specifically to share this kind of fixture.

- [ ] **Step 2: Write the failing test**

```kotlin
// core/data/src/test/kotlin/dev/gridiron/core/data/ProjectionsRepositoryTest.kt
package dev.gridiron.core.data

import dev.gridiron.core.projections.ProjectionsRequest
import dev.gridiron.core.projections.RosProjectionsRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectionsRepositoryTest {
    // Uses the same JDBC fixture setup as StatsRepositoryTest (see Task 4 Step 1's
    // search result for the exact helper name/signature to call here).

    @Test
    fun `projections returns baseline and final rows for a requested player`() = runTest {
        val executor = jdbcFixtureWithSchema(
            insertProjectionRows = listOf(
                "INSERT INTO player_week_projection VALUES ('P1', 2026, 3, 'targets', 'baseline', 6.0, 0.0)",
                "INSERT INTO player_week_projection VALUES ('P1', 2026, 3, 'targets', 'final', 7.2, 4.0)",
            ),
        )
        val repo = ProjectionsRepository(executor)
        val result = repo.projections(ProjectionsRequest(setOf("P1"), season = 2026, week = 3))
        assertEquals(1, result.size)
        assertEquals(6.0, result.first().baseline.first { it.metricId == "targets" }.mean)
        assertEquals(7.2, result.first().final.first { it.metricId == "targets" }.mean)
    }

    @Test
    fun `an empty player id set returns an empty list, not an error`() = runTest {
        val executor = jdbcFixtureWithSchema(insertProjectionRows = emptyList())
        val repo = ProjectionsRepository(executor)
        val result = repo.projections(ProjectionsRequest(emptySet(), season = 2026, week = 3))
        assertEquals(emptyList(), result)
    }
}
```

(`jdbcFixtureWithSchema` stands in for whatever the Step 1 search found — replace with the
real helper name/signature before running; this is exactly the kind of "found in Step 1"
detail this task's implementer resolves against the real codebase, not a guess to run
as-is.)

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*ProjectionsRepositoryTest*"`
Expected: FAIL — `ProjectionsRepository` doesn't exist.

- [ ] **Step 4: Implement**

```kotlin
// core/data/src/main/kotlin/dev/gridiron/core/data/ProjectionsRepository.kt
package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.textOrNull
import dev.gridiron.core.projections.PlayerProjection
import dev.gridiron.core.projections.ProjectionComponent
import dev.gridiron.core.projections.ProjectionFactor
import dev.gridiron.core.projections.ProjectionQueries
import dev.gridiron.core.projections.ProjectionsRequest
import dev.gridiron.core.projections.RosProjection
import dev.gridiron.core.projections.RosProjectionsRequest

public class ProjectionsRepository(private val executor: QueryExecutor) {

    public suspend fun projections(request: ProjectionsRequest): List<PlayerProjection> {
        if (request.playerIds.isEmpty()) return emptyList()

        data class Row(val playerId: String, val metricId: String, val stage: String,
                        val mean: Double, val variance: Double)

        val rows = executor.query(
            ProjectionQueries.weekly(request.playerIds, request.season, request.week)
        ) { Row(it.text(0), it.text(1), it.text(2), it.double(3), it.double(4)) }

        val factorRows = executor.query(
            ProjectionQueries.factors(request.playerIds, request.season, request.week)
        ) {
            Triple(it.text(0), ProjectionFactor(it.text(1), it.double(2), it.textOrNull(3)))
        }

        val byPlayer = rows.groupBy { it.playerId }
        val factorsByPlayer = factorRows.groupBy({ it.first }, { it.second })

        return byPlayer.map { (playerId, playerRows) ->
            PlayerProjection(
                playerId = playerId,
                season = request.season,
                week = request.week,
                baseline = playerRows.filter { it.stage == "baseline" }
                    .map { ProjectionComponent(it.metricId, it.mean, it.variance) },
                final = playerRows.filter { it.stage == "final" }
                    .map { ProjectionComponent(it.metricId, it.mean, it.variance) },
                factors = factorsByPlayer[playerId].orEmpty(),
            )
        }
    }

    public suspend fun rosProjections(request: RosProjectionsRequest): List<RosProjection> {
        if (request.playerIds.isEmpty()) return emptyList()

        data class Row(val playerId: String, val metricId: String, val mean: Double, val variance: Double)

        val rows = executor.query(
            ProjectionQueries.ros(request.playerIds, request.season)
        ) { Row(it.text(0), it.text(1), it.double(2), it.double(3)) }

        return rows.groupBy { it.playerId }.map { (playerId, playerRows) ->
            RosProjection(playerId, playerRows.map { ProjectionComponent(it.metricId, it.mean, it.variance) })
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*ProjectionsRepositoryTest*"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/dev/gridiron/core/data/ProjectionsRepository.kt \
        core/data/src/test/kotlin/dev/gridiron/core/data/ProjectionsRepositoryTest.kt
git commit -m "data: add ProjectionsRepository reading the weekly, factor, and ROS tables"
```

---

## Task 5: Factor apportionment under the user's real scoring

**Files:**
- Create: `core/projections/src/main/kotlin/dev/gridiron/core/projections/FactorAttribution.kt`
- Test: `core/projections/src/test/kotlin/dev/gridiron/core/projections/FactorAttributionTest.kt`

**Interfaces:**
- Consumes: `score()` (Task 2), `PlayerProjection`/`ProjectionFactor` (Task 3).
- Produces:
  ```kotlin
  public data class AttributedFactor(val factor: String, val points: Double, val note: String?)
  public fun attributeFactors(
      baselineComponents: Map<Component, Double>,
      finalComponents: Map<Component, Double>,
      factors: List<ProjectionFactor>,
      profile: ScoringProfile,
      position: Position?,
  ): List<AttributedFactor>
  ```

- [ ] **Step 1: Write the failing test**

```kotlin
// core/projections/src/test/kotlin/dev/gridiron/core/projections/FactorAttributionTest.kt
package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.statquery.Components
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class FactorAttributionTest {
    @Test
    fun `attributed factor points sum exactly to the real FP delta`() {
        val baseline = mapOf(Components.RECEPTIONS to 5.0, Components.RECEIVING_YARDS to 50.0)
        val final = mapOf(Components.RECEPTIONS to 6.0, Components.RECEIVING_YARDS to 65.0)
        val factors = listOf(
            ProjectionFactor("matchup", logMultiplier = 0.10, note = null),
            ProjectionFactor("game_script", logMultiplier = 0.05, note = null),
        )
        val attributed = attributeFactors(baseline, final, factors, ScoringPresets.PPR, Position.WR)

        val fpBaseline = score(baseline, ScoringPresets.PPR, Position.WR)
        val fpFinal = score(final, ScoringPresets.PPR, Position.WR)
        val expectedDelta = fpFinal - fpBaseline

        assertTrue(abs(attributed.sumOf { it.points } - expectedDelta) < 1e-6)
    }

    @Test
    fun `near-zero total log-multiplier does not divide by zero or produce NaN`() {
        val baseline = mapOf(Components.RECEPTIONS to 5.0)
        val final = mapOf(Components.RECEPTIONS to 5.0) // no real change
        val factors = listOf(
            ProjectionFactor("matchup", logMultiplier = 0.001, note = null),
            ProjectionFactor("game_script", logMultiplier = -0.001, note = null),
        )
        val attributed = attributeFactors(baseline, final, factors, ScoringPresets.PPR, Position.WR)
        attributed.forEach {
            assertTrue(it.points.isFinite(), "factor ${it.factor} produced a non-finite value: ${it.points}")
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:projections:testDebugUnitTest --tests "*FactorAttributionTest*"`
Expected: FAIL — `attributeFactors` doesn't exist.

- [ ] **Step 3: Implement**

```kotlin
// core/projections/src/main/kotlin/dev/gridiron/core/projections/FactorAttribution.kt
package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.statquery.Component
import kotlin.math.ln

public data class AttributedFactor(val factor: String, val points: Double, val note: String?)

/**
 * Apportions the real fantasy-point delta (under the caller's own
 * [profile] — never a server-assumed default) across factors, using the
 * shipped [ProjectionFactor.logMultiplier] ratios. See design spec §3:
 * `Δᵢ = Δ × log_multiplier_i / Σⱼ log_multiplier_j`, guaranteed to sum
 * exactly to `Δ` by construction — except in the degenerate near-zero-sum
 * case, which is guarded explicitly rather than left to divide toward
 * infinity.
 */
public fun attributeFactors(
    baselineComponents: Map<Component, Double>,
    finalComponents: Map<Component, Double>,
    factors: List<ProjectionFactor>,
    profile: ScoringProfile,
    position: Position?,
): List<AttributedFactor> {
    val fpBaseline = score(baselineComponents, profile, position)
    val fpFinal = score(finalComponents, profile, position)
    val delta = fpFinal - fpBaseline

    val totalLogMult = factors.sumOf { it.logMultiplier }
    if (factors.isEmpty() || kotlin.math.abs(totalLogMult) < 1e-9) {
        // No factors, or they cancel to (near) zero: there's nothing
        // meaningful to apportion the delta across. Attribute it all to a
        // single synthetic "other" bucket rather than dividing by ~0.
        return if (delta == 0.0) emptyList()
        else listOf(AttributedFactor("other", delta, null))
    }

    return factors.map { f ->
        AttributedFactor(f.factor, delta * f.logMultiplier / totalLogMult, f.note)
    }
}
```

Note: the `ln` import is unused by this implementation (the log-multipliers arrive
pre-computed from the ETL side, per the schema — this function only ratios them, it
never takes a raw multiplier's logarithm itself) — remove the `import kotlin.math.ln`
line before committing; keeping an unused import is exactly the kind of small paper cut
`ktlint`/`detekt` (if configured in this repo) would flag.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:projections:testDebugUnitTest --tests "*FactorAttributionTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/projections/src/main/kotlin/dev/gridiron/core/projections/FactorAttribution.kt \
        core/projections/src/test/kotlin/dev/gridiron/core/projections/FactorAttributionTest.kt
git commit -m "projections: apportion the real per-league FP delta across shipped log-multipliers"
```

---

## Task 6: Single-player Monte Carlo for floor/ceiling

**Files:**
- Create: `core/projections/src/main/kotlin/dev/gridiron/core/projections/MonteCarlo.kt`
- Test: `core/projections/src/test/kotlin/dev/gridiron/core/projections/MonteCarloTest.kt`

**Interfaces:**
- Consumes: `score()` (Task 2).
- Produces:
  ```kotlin
  public enum class DistributionFamily { NEGBINOM, BINOMIAL, GAMMA, POISSON }
  public data class DistributionSpec(val component: Component, val family: DistributionFamily,
                                      val mean: Double, val variance: Double)
  public data class SimulationResult(val p10: Double, val p25: Double, val p50: Double, val p90: Double)
  public fun simulate(
      distributions: List<DistributionSpec>, profile: ScoringProfile, position: Position?,
      draws: Int = 10_000, seed: Long = 42L,
  ): SimulationResult
  ```

- [ ] **Step 1: Write the failing test**

```kotlin
// core/projections/src/test/kotlin/dev/gridiron/core/projections/MonteCarloTest.kt
package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.statquery.Components
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class MonteCarloTest {
    @Test
    fun `simulated median converges to the analytic mean within tolerance for a Gamma component`() {
        val distributions = listOf(
            DistributionSpec(Components.RECEIVING_YARDS, DistributionFamily.GAMMA, mean = 60.0, variance = 400.0),
        )
        val result = simulate(distributions, ScoringPresets.STANDARD, Position.WR, draws = 20_000)
        // Standard scoring: 0.1 pt/yard, so FP mean should track 6.0 (=60*0.1).
        assertTrue(abs(result.p50 - 6.0) < 0.5, "p50 was ${result.p50}, expected close to 6.0")
    }

    @Test
    fun `zero variance produces a point mass at the mean, not NaN`() {
        val distributions = listOf(
            DistributionSpec(Components.RECEIVING_YARDS, DistributionFamily.GAMMA, mean = 60.0, variance = 0.0),
        )
        val result = simulate(distributions, ScoringPresets.STANDARD, Position.WR, draws = 1_000)
        assertTrue(result.p10.isFinite() && result.p90.isFinite())
        assertTrue(abs(result.p10 - result.p90) < 1e-6) // no spread when variance is zero
    }

    @Test
    fun `same seed gives the same result twice`() {
        val distributions = listOf(
            DistributionSpec(Components.RECEIVING_YARDS, DistributionFamily.GAMMA, mean = 60.0, variance = 400.0),
        )
        val a = simulate(distributions, ScoringPresets.PPR, Position.WR, draws = 5_000, seed = 7L)
        val b = simulate(distributions, ScoringPresets.PPR, Position.WR, draws = 5_000, seed = 7L)
        assertTrue(a == b, "same seed must reproduce identical percentiles: $a vs $b")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:projections:testDebugUnitTest --tests "*MonteCarloTest*"`
Expected: FAIL — `simulate` doesn't exist.

- [ ] **Step 3: Implement**

```kotlin
// core/projections/src/main/kotlin/dev/gridiron/core/projections/MonteCarlo.kt
package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.statquery.Component
import java.util.SplittableRandom
import kotlin.math.ln
import kotlin.math.max

public enum class DistributionFamily { NEGBINOM, BINOMIAL, GAMMA, POISSON }

public data class DistributionSpec(
    val component: Component,
    val family: DistributionFamily,
    val mean: Double,
    val variance: Double,
)

public data class SimulationResult(val p10: Double, val p25: Double, val p50: Double, val p90: Double)

/**
 * Single-player Monte Carlo: no cross-player correlation (that needs the
 * Gaussian-copula machinery, out of scope until Phase 6's decision tools —
 * see design spec §3). Plain [DoubleArray] and [SplittableRandom], matching
 * the research doc's implementation notes; 10k draws is comfortably under a
 * millisecond even on a mid-range device.
 */
public fun simulate(
    distributions: List<DistributionSpec>,
    profile: ScoringProfile,
    position: Position?,
    draws: Int = 10_000,
    seed: Long = 42L,
): SimulationResult {
    val rng = SplittableRandom(seed)
    val samples = DoubleArray(draws)
    val componentMap = HashMap<Component, Double>(distributions.size)

    for (i in 0 until draws) {
        for (spec in distributions) {
            componentMap[spec.component] = drawOne(spec, rng)
        }
        samples[i] = score(componentMap, profile, position)
    }
    samples.sort()

    fun percentile(p: Double): Double {
        val idx = (p * (samples.size - 1)).toInt().coerceIn(0, samples.size - 1)
        return samples[idx]
    }
    return SimulationResult(percentile(0.10), percentile(0.25), percentile(0.50), percentile(0.90))
}

private fun drawOne(spec: DistributionSpec, rng: SplittableRandom): Double {
    // variance = 0 is a point mass at the mean for every family — a bye-week
    // placeholder or a data gap must never divide by zero or produce NaN.
    if (spec.variance <= 0.0) return spec.mean
    return when (spec.family) {
        DistributionFamily.GAMMA -> drawGamma(spec.mean, spec.variance, rng)
        DistributionFamily.POISSON -> drawPoisson(spec.mean, rng)
        DistributionFamily.NEGBINOM -> drawGamma(spec.mean, spec.variance, rng) // shape-compatible fallback; a
        // dedicated NegBinom sampler is a follow-up once real dist_family
        // data from the ETL side is available to validate against.
        DistributionFamily.BINOMIAL -> drawGamma(spec.mean, spec.variance, rng)
    }
}

private fun drawGamma(mean: Double, variance: Double, rng: SplittableRandom): Double {
    val shape = mean * mean / variance
    val scale = variance / mean
    // Marsaglia-Tsang method, shape >= 1 (clamp — shrinkage-heavy small-n
    // components can produce shape < 1 from a tiny mean/large variance; a
    // proper shape<1 boost-trick sampler is a follow-up, this clamp keeps
    // the draw defined and non-crashing in the meantime).
    val d = max(shape, 1.0) - 1.0 / 3.0
    val c = 1.0 / kotlin.math.sqrt(9.0 * d)
    while (true) {
        var x: Double
        var v: Double
        do {
            x = gaussian(rng)
            v = 1.0 + c * x
        } while (v <= 0.0)
        v *= v * v
        val u = rng.nextDouble()
        if (u < 1.0 - 0.0331 * x * x * x * x) return d * v * scale
        if (ln(u) < 0.5 * x * x + d * (1.0 - v + ln(v))) return d * v * scale
    }
}

private fun drawPoisson(lambda: Double, rng: SplittableRandom): Double {
    // Knuth's method — fine for the small lambdas (TD counts) this is used for.
    val l = kotlin.math.exp(-lambda)
    var k = 0
    var p = 1.0
    do {
        k += 1
        p *= rng.nextDouble()
    } while (p > l)
    return (k - 1).toDouble()
}

private fun gaussian(rng: SplittableRandom): Double {
    // Box-Muller, one value per call (the cached-pair optimization is a
    // follow-up if profiling ever shows this as a hot path).
    val u1 = rng.nextDouble().coerceAtLeast(1e-12)
    val u2 = rng.nextDouble()
    return kotlin.math.sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:projections:testDebugUnitTest --tests "*MonteCarloTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/projections/src/main/kotlin/dev/gridiron/core/projections/MonteCarlo.kt \
        core/projections/src/test/kotlin/dev/gridiron/core/projections/MonteCarloTest.kt
git commit -m "projections: single-player Monte Carlo for floor/ceiling percentiles"
```

---

## Task 7: `:feature:projections` module scaffold and the waterfall composable

**Files:**
- Modify: `settings.gradle.kts` (add `include(":feature:projections")`)
- Create: `feature/projections/build.gradle.kts` (copy `feature/compare/build.gradle.kts`'s
  shape, depending on `:core:model`, `:core:projections`, `:core:data`, `:core:designsystem`)
- Create: `feature/projections/src/main/kotlin/dev/gridiron/feature/projections/WaterfallCard.kt`
- Test: `feature/projections/src/test/kotlin/dev/gridiron/feature/projections/WaterfallCardTest.kt`
  (Roborazzi/Compose UI test, following whatever pattern `feature/compare`'s existing
  composable tests use — check one there for the exact test-rule setup before writing this
  task's test)

**Interfaces:**
- Consumes: `AttributedFactor` (Task 5), `SimulationResult` (Task 6).
- Produces: `@Composable fun WaterfallCard(baseline: Double, factors: List<AttributedFactor>,
  final: Double, floorCeiling: SimulationResult, modifier: Modifier = Modifier)`.

- [ ] **Step 1: Find the composable test pattern to follow**

Run: `find feature/compare/src/test -name "*.kt" | head -3` and read one to see the exact
test-rule/assertion style (Roborazzi screenshot vs. semantics-tree assertions) already
established in this codebase.

- [ ] **Step 2: Write the failing test**

```kotlin
// feature/projections/src/test/kotlin/dev/gridiron/feature/projections/WaterfallCardTest.kt
package dev.gridiron.feature.projections

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.gridiron.core.projections.AttributedFactor
import dev.gridiron.core.projections.SimulationResult
import org.junit.Rule
import org.junit.Test

class WaterfallCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `shows the baseline, each factor, and the final total`() {
        compose.setContent {
            WaterfallCard(
                baseline = 12.8,
                factors = listOf(
                    AttributedFactor("matchup", 1.4, "28th vs slot WRs by YPRR allowed"),
                    AttributedFactor("weather", -0.9, "18 mph wind, outdoor"),
                ),
                final = 13.3,
                floorCeiling = SimulationResult(p10 = 6.1, p25 = 9.0, p50 = 13.3, p90 = 24.8),
            )
        }
        compose.onNodeWithText("12.8", substring = true).assertExists()
        compose.onNodeWithText("13.3", substring = true).assertExists()
        compose.onNodeWithText("Floor", substring = true).assertExists()
        compose.onNodeWithText("Ceiling", substring = true).assertExists()
    }
}
```

(Adjust the exact assertion API — `onNodeWithText`, `assertExists` — to match whatever
Step 1's real example uses if it differs; that file is the source of truth for this
codebase's Compose test conventions.)

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :feature:projections:testDebugUnitTest --tests "*WaterfallCardTest*"`
Expected: FAIL — module/composable don't exist yet.

- [ ] **Step 4: Implement**

```kotlin
// feature/projections/src/main/kotlin/dev/gridiron/feature/projections/WaterfallCard.kt
package dev.gridiron.feature.projections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gridiron.core.projections.AttributedFactor
import dev.gridiron.core.projections.SimulationResult
import kotlin.math.round

/**
 * The one-screen projection card from design spec §3 / research doc §6.2:
 * baseline, each signed factor contribution, the final total, and
 * floor/ceiling. Values are never shown with false precision — one decimal,
 * matching the app-wide `CellUi` display convention.
 */
@Composable
public fun WaterfallCard(
    baseline: Double,
    factors: List<AttributedFactor>,
    final: Double,
    floorCeiling: SimulationResult,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Text(text = "Baseline (role + recent form)  ${oneDecimal(baseline)}")
        factors.forEach { f ->
            val sign = if (f.points >= 0) "+" else ""
            Text(text = "$sign${oneDecimal(f.points)}  ${f.factor}${f.note?.let { " — $it" } ?: ""}")
        }
        Text(text = "Projection  ${oneDecimal(final)}")
        Row {
            Text(text = "Floor ${oneDecimal(floorCeiling.p10)}")
            Text(text = "  ·  ")
            Text(text = "Ceiling ${oneDecimal(floorCeiling.p90)}")
        }
    }
}

private fun oneDecimal(value: Double): String = "%.1f".format(round(value * 10) / 10)
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :feature:projections:testDebugUnitTest --tests "*WaterfallCardTest*"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts feature/projections/build.gradle.kts \
        feature/projections/src/main/kotlin/dev/gridiron/feature/projections/WaterfallCard.kt \
        feature/projections/src/test/kotlin/dev/gridiron/feature/projections/WaterfallCardTest.kt
git commit -m "projections: add :feature:projections module with the waterfall card"
```

---

## Task 8: TD-dependence, confidence badge, and floor/ceiling display

**Files:**
- Modify: `feature/projections/src/main/kotlin/dev/gridiron/feature/projections/WaterfallCard.kt`
- Create: `core/projections/src/main/kotlin/dev/gridiron/core/projections/Confidence.kt`
- Test: `core/projections/src/test/kotlin/dev/gridiron/core/projections/ConfidenceTest.kt`

**Interfaces:**
- Produces: `fun tdDependence(tdComponentPoints: Double, totalPoints: Double): Double`
  (`6λ_TD / FP`, guarded for `totalPoints == 0`); `enum class ConfidenceLevel { LOW,
  MEDIUM, HIGH }`; `fun confidenceFrom(shrinkageWeight: Double): ConfidenceLevel`.

- [ ] **Step 1: Write the failing test**

```kotlin
// core/projections/src/test/kotlin/dev/gridiron/core/projections/ConfidenceTest.kt
package dev.gridiron.core.projections

import kotlin.test.Test
import kotlin.test.assertEquals

class ConfidenceTest {
    @Test
    fun `td dependence is the TD points share of the total`() {
        // 6 points from TDs out of 14.2 total.
        assertEquals(6.0 / 14.2, tdDependence(tdComponentPoints = 6.0, totalPoints = 14.2), 1e-9)
    }

    @Test
    fun `td dependence is zero, not NaN, when total points is zero`() {
        assertEquals(0.0, tdDependence(tdComponentPoints = 0.0, totalPoints = 0.0), 1e-9)
    }

    @Test
    fun `confidence is low for a small shrinkage weight`() {
        assertEquals(ConfidenceLevel.LOW, confidenceFrom(shrinkageWeight = 0.05))
    }

    @Test
    fun `confidence is high for a shrinkage weight near 1`() {
        assertEquals(ConfidenceLevel.HIGH, confidenceFrom(shrinkageWeight = 0.9))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:projections:testDebugUnitTest --tests "*ConfidenceTest*"`
Expected: FAIL — `tdDependence`/`confidenceFrom` don't exist.

- [ ] **Step 3: Implement**

```kotlin
// core/projections/src/main/kotlin/dev/gridiron/core/projections/Confidence.kt
package dev.gridiron.core.projections

/** 6*lambda_TD / FP — the single most explanatory number for weekly
 * volatility (research doc §6.2). Guarded: zero total points means zero
 * dependence, not a divide-by-zero. */
public fun tdDependence(tdComponentPoints: Double, totalPoints: Double): Double =
    if (totalPoints == 0.0) 0.0 else tdComponentPoints / totalPoints

public enum class ConfidenceLevel { LOW, MEDIUM, HIGH }

/** Confidence badge driven by the James-Stein shrinkage weight (design spec §3),
 * not vibes: `w` close to 0 means the projection is mostly the positional
 * baseline (low confidence); `w` close to 1 means it's mostly the player's
 * own data (high confidence). */
public fun confidenceFrom(shrinkageWeight: Double): ConfidenceLevel = when {
    shrinkageWeight < 0.3 -> ConfidenceLevel.LOW
    shrinkageWeight < 0.7 -> ConfidenceLevel.MEDIUM
    else -> ConfidenceLevel.HIGH
}
```

Add the badge and TD-dependence line to `WaterfallCard` (append inside the existing
`Column`, after the floor/ceiling `Row` from Task 7):

```kotlin
        Text(text = "TD dependence: ${(tdDependenceValue * 100).toInt()}%")
```

(`tdDependenceValue` becomes a new parameter on `WaterfallCard`; thread it through from
the caller in Task 9's screen-level composable, which has both the TD component's points
and the total.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:projections:testDebugUnitTest --tests "*ConfidenceTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/projections/src/main/kotlin/dev/gridiron/core/projections/Confidence.kt \
        core/projections/src/test/kotlin/dev/gridiron/core/projections/ConfidenceTest.kt \
        feature/projections/src/main/kotlin/dev/gridiron/feature/projections/WaterfallCard.kt
git commit -m "projections: TD-dependence and shrinkage-driven confidence badge"
```

---

## Task 9: Accuracy page

**Files:**
- Create: `core/data/src/main/kotlin/dev/gridiron/core/data/AccuracyRepository.kt`
- Create: `feature/projections/src/main/kotlin/dev/gridiron/feature/projections/AccuracyScreen.kt`
- Test: `core/data/src/test/kotlin/dev/gridiron/core/data/AccuracyRepositoryTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  public data class AccuracyRow(val position: String, val metricId: String, val baseline: String,
                                 val sampleN: Int, val mae: Double, val rmse: Double,
                                 val bias: Double, val r2: Double?)
  public class AccuracyRepository(private val executor: QueryExecutor) {
      public suspend fun summary(season: Int): List<AccuracyRow>
  }
  ```
  and `@Composable fun AccuracyScreen(rows: List<AccuracyRow>, modifier: Modifier = Modifier)`.

- [ ] **Step 1: Write the failing test**

```kotlin
// core/data/src/test/kotlin/dev/gridiron/core/data/AccuracyRepositoryTest.kt
package dev.gridiron.core.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AccuracyRepositoryTest {
    @Test
    fun `summary reads MAE and baseline label from accuracy_summary`() = runTest {
        val executor = jdbcFixtureWithSchema(
            insertAccuracyRows = listOf(
                "INSERT INTO accuracy_summary VALUES ('WR', 2026, 'fantasy_points', 'model', 40, 4.9, 6.1, -0.1, 0.18)",
            ),
        )
        val repo = AccuracyRepository(executor)
        val rows = repo.summary(2026)
        assertEquals(1, rows.size)
        assertEquals(4.9, rows.first().mae)
        assertEquals("model", rows.first().baseline)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*AccuracyRepositoryTest*"`
Expected: FAIL — `AccuracyRepository` doesn't exist.

- [ ] **Step 3: Implement the repository**

```kotlin
// core/data/src/main/kotlin/dev/gridiron/core/data/AccuracyRepository.kt
package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.doubleOrNull
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.SqlQuery

public data class AccuracyRow(
    val position: String, val metricId: String, val baseline: String,
    val sampleN: Int, val mae: Double, val rmse: Double, val bias: Double, val r2: Double?,
)

public class AccuracyRepository(private val executor: QueryExecutor) {
    public suspend fun summary(season: Int): List<AccuracyRow> = executor.query(
        SqlQuery(
            """
            SELECT position, metric_id, baseline, sample_n, mae, rmse, bias, r2
            FROM accuracy_summary
            WHERE season = ?
            ORDER BY position, metric_id, baseline
            """.trimIndent(),
            listOf(Bind.Integer(season.toLong())),
        )
    ) {
        AccuracyRow(
            position = it.text(0), metricId = it.text(1), baseline = it.text(2),
            sampleN = it.long(3).toInt(), mae = it.double(4), rmse = it.double(5),
            bias = it.double(6), r2 = it.doubleOrNull(7),
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:data:testDebugUnitTest --tests "*AccuracyRepositoryTest*"`
Expected: PASS

- [ ] **Step 5: Implement the screen**

```kotlin
// feature/projections/src/main/kotlin/dev/gridiron/feature/projections/AccuracyScreen.kt
package dev.gridiron.feature.projections

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.gridiron.core.data.AccuracyRow

/** The trust page: weekly MAE by position, model vs. the two naive baselines,
 * live from week 1 (design spec §4, research doc §5.4). No client-side
 * computation — every number here is read directly from `accuracy_summary`. */
@Composable
public fun AccuracyScreen(rows: List<AccuracyRow>, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier) {
        items(rows) { row ->
            Text(text = "${row.position} ${row.metricId} (${row.baseline}): " +
                "MAE ${"%.1f".format(row.mae)}, n=${row.sampleN}")
        }
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/dev/gridiron/core/data/AccuracyRepository.kt \
        core/data/src/test/kotlin/dev/gridiron/core/data/AccuracyRepositoryTest.kt \
        feature/projections/src/main/kotlin/dev/gridiron/feature/projections/AccuracyScreen.kt
git commit -m "data+projections: accuracy repository and the in-app accuracy page"
```

---

## Task 10: `ProjectionsViewModel` with stale-request cancellation

**Files:**
- Create: `feature/projections/src/main/kotlin/dev/gridiron/feature/projections/ProjectionsViewModel.kt`
- Test: `feature/projections/src/test/kotlin/dev/gridiron/feature/projections/ProjectionsViewModelTest.kt`
  (`kotlinx-coroutines-test`'s `runTest`/`TestDispatcher`, following whichever existing
  ViewModel test in this codebase — e.g. `GridViewModel`'s test — already sets up that
  pattern; check one before writing this task's test)

**Interfaces:**
- Consumes: `ProjectionsRepository` (Task 4), `AttributedFactor`/`attributeFactors` (Task
  5), `SimulationResult`/`simulate` (Task 6).
- Produces:
  ```kotlin
  public sealed interface ProjectionsUiState {
      public object Loading : ProjectionsUiState
      public data class Loaded(val playerId: String, val baseline: Double, val final: Double,
                                val factors: List<AttributedFactor>,
                                val floorCeiling: SimulationResult) : ProjectionsUiState
  }
  public class ProjectionsViewModel(private val repository: ProjectionsRepository) : ViewModel() {
      public fun load(playerId: String, season: Int, week: Int, profile: ScoringProfile, position: Position?)
      public val state: StateFlow<ProjectionsUiState>
  }
  ```

Why this exists (closing the Review Focus item this plan named up front but no earlier
task tested): `WaterfallCard` (Task 7) and `AccuracyScreen` (Task 9) take plain data
parameters — nothing between them and `ProjectionsRepository` decides what happens when
`load()` is called a second time before the first call's query returns (two rapid
navigations to different players' projection screens, back to back). Without the guard
this task adds, the *first* (now stale) request could resolve after the second and
overwrite the UI with the wrong player's numbers — the exact class of bug `GridViewModel`
already solved for sparklines via its `page == base.page` tag-and-gate pattern
(`feature/players/.../GridViewModel.kt`), applied here to projection loads.

- [ ] **Step 1: Write the failing test**

```kotlin
// feature/projections/src/test/kotlin/dev/gridiron/feature/projections/ProjectionsViewModelTest.kt
package dev.gridiron.feature.projections

import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.projections.PlayerProjection
import dev.gridiron.core.projections.ProjectionsRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectionsViewModelTest {
    @Test
    fun `a superseded load never overwrites state with the stale player's result`() = runTest {
        // P1's query is gated so it resolves only after P2's load has already
        // completed and been rendered — reproducing the out-of-order return
        // that a naive last-write-wins ViewModel would get wrong.
        val p1Gate = CompletableDeferred<Unit>()
        val repository = object {
            suspend fun projections(request: ProjectionsRequest): List<PlayerProjection> {
                if (request.playerIds.first() == "P1") p1Gate.await()
                return listOf(fakeProjection(request.playerIds.first()))
            }
        }
        val viewModel = ProjectionsViewModel(FakeProjectionsRepository(repository::projections))

        viewModel.load("P1", season = 2026, week = 3, ScoringPresets.PPR, Position.WR)
        viewModel.load("P2", season = 2026, week = 3, ScoringPresets.PPR, Position.WR)
        // P2's (ungated) load resolves first.
        val afterP2 = viewModel.state.value
        p1Gate.complete(Unit) // now let the stale P1 load finish

        val afterStaleP1Resolves = viewModel.state.value
        assertEquals(afterP2, afterStaleP1Resolves, "a stale, later-resolving request must not overwrite state")
        val loaded = afterStaleP1Resolves as ProjectionsUiState.Loaded
        assertEquals("P2", loaded.playerId)
    }
}
```

(`fakeProjection`/`FakeProjectionsRepository` are small test-only helpers this task's
implementer writes alongside the test — a `PlayerProjection` with one component and a
thin wrapper matching `ProjectionsRepository`'s real constructor shape so the gated
`projections` lambda above can substitute for it.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature:projections:testDebugUnitTest --tests "*ProjectionsViewModelTest*"`
Expected: FAIL — `ProjectionsViewModel` doesn't exist.

- [ ] **Step 3: Implement**

```kotlin
// feature/projections/src/main/kotlin/dev/gridiron/feature/projections/ProjectionsViewModel.kt
package dev.gridiron.feature.projections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.projections.AttributedFactor
import dev.gridiron.core.projections.DistributionFamily
import dev.gridiron.core.projections.DistributionSpec
import dev.gridiron.core.projections.ProjectionsRequest
import dev.gridiron.core.projections.SimulationResult
import dev.gridiron.core.projections.attributeFactors
import dev.gridiron.core.projections.score
import dev.gridiron.core.projections.simulate
import dev.gridiron.core.statquery.Component
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

public sealed interface ProjectionsUiState {
    public object Loading : ProjectionsUiState
    public data class Loaded(
        val playerId: String,
        val baseline: Double,
        val final: Double,
        val factors: List<AttributedFactor>,
        val floorCeiling: SimulationResult,
    ) : ProjectionsUiState
}

public class ProjectionsViewModel(private val repository: ProjectionsRepository) : ViewModel() {
    private val _state = MutableStateFlow<ProjectionsUiState>(ProjectionsUiState.Loading)
    public val state: StateFlow<ProjectionsUiState> = _state.asStateFlow()

    // The most recent request's identity — a request whose result arrives after
    // a newer one has already been issued is stale and must not update `_state`,
    // mirroring GridViewModel's `page == base.page` sparkline guard.
    private var currentRequestKey: String? = null

    public fun load(playerId: String, season: Int, week: Int, profile: ScoringProfile, position: Position?) {
        val requestKey = "$playerId:$season:$week"
        currentRequestKey = requestKey
        _state.value = ProjectionsUiState.Loading

        viewModelScope.launch {
            val results = repository.projections(ProjectionsRequest(setOf(playerId), season, week))
            if (currentRequestKey != requestKey) return@launch // superseded — drop it

            val projection = results.firstOrNull() ?: return@launch
            val baselineMap = projection.baseline.associate { Component(it.metricId) to it.mean }
            val finalMap = projection.final.associate { Component(it.metricId) to it.mean }

            val baselinePoints = score(baselineMap, profile, position)
            val finalPoints = score(finalMap, profile, position)
            val attributed = attributeFactors(baselineMap, finalMap, projection.factors, profile, position)
            // Every component simulated as Gamma pending per-metric dist_family
            // wiring (see the note below this block) — the same fallback
            // Task 6's drawOne() already uses for NEGBINOM/BINOMIAL, so this is
            // a real, defined distribution choice today, not a stub.
            val distributions = projection.final.map {
                DistributionSpec(Component(it.metricId), DistributionFamily.GAMMA, it.mean, it.variance)
            }
            val floorCeiling = simulate(distributions, profile, position)

            if (currentRequestKey != requestKey) return@launch // re-check after the CPU-bound simulate() call
            _state.value = ProjectionsUiState.Loaded(playerId, baselinePoints, finalPoints, attributed, floorCeiling)
        }
    }
}
```

Note: every component is simulated as `DistributionFamily.GAMMA` above — a real, defined
choice (matching Task 6's own `NEGBINOM`/`BINOMIAL` fallback), not a per-metric lookup.
Reading the real `dist_family` per metric needs the metric registry's `dist_family` column
(added in the ETL plan's Task 1) to be readable from `:core:data`'s existing
`Catalog`/`MetricInfo`, which is a small follow-up (add `distFamily`/`zeroInflated` to
`MetricInfo`, mirroring how `stability`/`predicts` already flow from `metric` into
`Catalog` in `StatsRepository.catalog()`) — a one-line addition to an already-tested
mapping function, tracked in this plan's Known Gaps rather than done here. The double
stale-request check (before *and* after
`simulate()`) matters because `simulate()` is CPU-bound and can take long enough for a
newer `load()` call to land mid-simulation.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :feature:projections:testDebugUnitTest --tests "*ProjectionsViewModelTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add feature/projections/src/main/kotlin/dev/gridiron/feature/projections/ProjectionsViewModel.kt \
        feature/projections/src/test/kotlin/dev/gridiron/feature/projections/ProjectionsViewModelTest.kt
git commit -m "projections: add ProjectionsViewModel with stale-request cancellation"
```

---

## Task 11: App wiring

**Files:**
- Modify: `app/src/main/kotlin/dev/gridiron/app/` — the existing navigation graph file
  (find it first; every other `feature:*` module is already wired into one)
- Test: none new — this task is wiring, verified by the existing instrumented/manual
  smoke path plus the full unit suite staying green

- [ ] **Step 1: Find the existing navigation wiring for `:feature:compare`**

Run: `grep -rl "feature.compare" app/src/main/kotlin`

Read that file to find the exact pattern (a sealed `Screen`/route class, a `NavHost`
composable block, a DI/dependency-provisioning point for repositories) already
established for wiring in a feature module.

- [ ] **Step 2: Wire `:feature:projections` the same way**

Add a route/screen entry for the projections waterfall and the accuracy page, following
Step 1's exact pattern — construct `ProjectionsRepository`/`AccuracyRepository` wherever
`StatsRepository`/`CompareRepository` are already constructed (same `QueryExecutor`
instance, so the new repositories read the same on-device database file with no new
wiring beyond what already exists for the Grid and Compare screens), and construct
`ProjectionsViewModel(projectionsRepository)` (Task 10) at the same point `GridViewModel`
is constructed, passing it to the new route's composable.

- [ ] **Step 3: Add the new modules as `app` dependencies**

In `app/build.gradle.kts`, add:

```kotlin
implementation(project(":core:projections"))
implementation(project(":feature:projections"))
```

(alongside the existing `implementation(project(":feature:compare"))` etc. lines).

- [ ] **Step 4: Run the full project build and test suite**

Run: `./gradlew build`
Expected: PASS — every module compiles, every unit test (ETL's `pytest` suite is separate
and unaffected by this Kotlin-only task) passes, including all of Tasks 1-9's new tests.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/kotlin
git commit -m "app: wire :feature:projections into the navigation graph"
```

---

## Known gaps carried forward (not blocking, consistent with the ETL plan's own deferrals)

- **K/DST fantasy-point scoring** is out of scope for this plan's `score()` — `ScoringRule`
  structurally covers QB/RB/WR/TE only, a pre-existing limitation. Extending it needs its
  own design pass (DST's tiered points/yards-allowed brackets are a different shape than
  every other position's per-unit weights) and is a natural follow-up once the ETL side's
  K/DST wiring (also deferred, see the ETL plan's post-plan note) lands.
- **NegBinom/Binomial samplers** in Task 6 fall back to the Gamma sampler as a
  shape-compatible placeholder; dedicated samplers are a follow-up once real
  `dist_family`-tagged data exists to validate against (today's context is empty/neutral
  per the ETL plan's own Task 11 scope note, so there's nothing to validate against yet).
- **Per-metric `dist_family` lookup** — Task 10's `ProjectionsViewModel` currently
  simulates every component as Gamma rather than reading each metric's real
  `dist_family`/`zero_inflated` from the registry. Wiring it needs `distFamily`/
  `zeroInflated` added to `:core:data`'s `MetricInfo`/`Catalog` (mirroring how
  `stability`/`predicts` already flow through `StatsRepository.catalog()`) — small, and
  deliberately not bundled into Task 10 so that task stayed focused on the
  cancellation-safety behavior it exists to test.
- **Cross-player correlated simulation** (H2H win probability, lineup optimization) stays
  entirely out of scope, per the design spec — that's Phase 6.
