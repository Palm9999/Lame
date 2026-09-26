# Kotlin Ingest Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A pure-JVM Kotlin module, `:core:ingest`, that builds `stats.db` from nflverse and ffopportunity. It produces the same numbers as the Python ETL, verified by a CI parity gate, so the phone can build its own database.

**Architecture:**
- Stream each upstream CSV with a small column-selecting reader, and fold play-by-play into per player-week accumulators. These are line-for-line ports of `etl/gridiron_etl/transform.py`, `teams.py`, `metrics.py` and `validate.py`.
- Write the same schema the app already reads, through the androidx bundled SQLite driver that `:core:database` already uses, plus a new `player_xref` table.
- An `IngestPipeline` uses conditional GETs (ETag/Last-Modified stored in `schema_meta`) to copy unchanged seasons from the previous database instead of recomputing them.
- This plan also ships an early phone-timing menu item. Plan 2 (`2026-09-25-live-refresh-app.md`) wires the pipeline into the app.

**Tech Stack:** Kotlin 2.4 (JVM 17, explicit API mode), androidx.sqlite 2.7.1 bundled driver, kotlinx-coroutines 1.11, JUnit Jupiter 6, JDK `HttpURLConnection`, and `com.sun.net.httpserver` for tests.

**Spec:** `docs/superpowers/specs/2026-09-25-live-data-refresh-design.md`

## Global Constraints

**Module setup:**
- `:core:ingest` uses the `gridiron.jvm.library` convention plugin. That means JVM 17, **explicit API mode** (every declaration that would be public needs a visibility modifier; mark module-private things `internal`), `allWarningsAsErrors = true`, and JUnit Jupiter tests (`org.junit.jupiter.api.*`).
- Never use `java.net.URL(String)`. It is deprecated in the JDK that CI compiles with, and deprecation is a warning, which is an error here. Use `URI(...).toURL()`.
- SQLite goes only through `androidx.sqlite` and `androidx.sqlite.driver.bundled.BundledSQLiteDriver`, the same engine the app reads with. `androidx.sqlite.execSQL` runs exactly one statement.

**Output database:**
- The schema is the Python ETL's schema 5 DDL verbatim, plus `player_xref`.
- `schema_meta` holds:
  - `schema_version` = `6`
  - `ingest_version` = `1`
  - `seasons` = comma-joined and sorted
  - `source` = `nflverse-data (CC BY 4.0); ffopportunity expected points (GPL >= 3)`
  - `built_at`
  - `expected_through_week:<season>`
  - `source:<file name>` = the encoded validators
- Every value must equal the Python ETL's within `1e-9` absolute or relative. The CI parity gate compares a full 2025 build.

**Upstream URLs** (exact; nothing is ever read from this repository's releases):
- `https://github.com/nflverse/nflverse-data/releases/download/pbp/play_by_play_{season}.csv.gz`
- `https://github.com/nflverse/nflverse-data/releases/download/snap_counts/snap_counts_{season}.csv.gz`
- `https://github.com/nflverse/nflverse-data/releases/download/injuries/injuries_{season}.csv.gz`
- `https://github.com/ffverse/ffopportunity/releases/download/latest-data/ep_weekly_{season}.csv`
- `https://github.com/nflverse/nflverse-data/releases/download/players/players.csv.gz`

**Missing values and errors:**
- An empty CSV field is null. nflverse writes missing values as empty fields, never `NA`.
- A required column missing upstream fails with a message containing `nflverse changed column`.

## Review Focus

1. **An interrupted download** (connection drop mid-file) must never leave a truncated file at the destination. It leaves nothing. Test: Task 5.
2. **A failed or cancelled build** must leave no output file and must never touch the previous database. Test: Task 11.
3. **A previous database that is unreadable, or from the Python ETL** (no `ingest_version`), triggers a full rebuild rather than a crash or a bad copy. Test: Task 11.
4. **A season with play-by-play but no expected-points file** (ffopportunity lags a few hours after MNF) still builds, records `expected_through_week` = 0 and warns. Test: Task 11.
5. **A player renamed in `players.csv`** while their season is unchanged shows the new name. The player table is always rebuilt, even when seasons are copied. Test: Task 11.

---

### Task 1: `:core:ingest` module and a streaming CSV reader

**Files:**
- Modify: `settings.gradle.kts` (add the include)
- Create: `core/ingest/build.gradle.kts`
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/csv/Csv.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/csv/CsvTest.kt`

**Interfaces:**
- Produces (all in package `dev.gridiron.core.ingest.csv`):
  - `public class MissingColumnsException(source: String, missing: List<String>)`
  - `internal class CsvRow` with `hasColumn(name): Boolean`, `text(name): String?`, `double(name): Double?` and `int(name): Int?`
  - `internal fun readCsv(input: InputStream, source: String, columns: Collection<String>, required: Collection<String> = columns, onRow: (CsvRow) -> Unit)`
  - `internal fun openInput(file: File): InputStream`, which gunzips when the name ends in `.gz`

- [ ] **Step 1: Register the module**

In `settings.gradle.kts`, add after `include(":core:database")`:

```kotlin
include(":core:ingest")
```

Create `core/ingest/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.gridiron.jvm.library)
}

// Pure JVM, like :core:database: the phone runs this exact code to build
// stats.db from nflverse, and CI runs it on the JVM against the Python ETL.
dependencies {
    implementation(projects.core.statquery)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 2: Write the failing tests**

```kotlin
// core/ingest/src/test/kotlin/dev/gridiron/core/ingest/csv/CsvTest.kt
package dev.gridiron.core.ingest.csv

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.GZIPOutputStream

class CsvTest {
    private fun rows(csv: String, columns: List<String>, required: List<String> = columns): List<Map<String, String?>> {
        val out = mutableListOf<Map<String, String?>>()
        readCsv(csv.byteInputStream(), "test.csv", columns, required) { row -> out += columns.associateWith { row.text(it) } }
        return out
    }

    @Test
    fun `keeps only the requested columns and reads empty fields as null`() {
        val out = rows("a,b,c\n1,,3\n4,5,6\n", listOf("a", "b"))
        assertEquals(listOf(mapOf("a" to "1", "b" to null), mapOf("a" to "4", "b" to "5")), out)
    }

    @Test
    fun `quoted fields keep commas, doubled quotes and line breaks`() {
        val out = rows("id,desc,n\n1,\"Pass, short \"\"left\"\"\nto X\",7\n", listOf("desc", "n"))
        assertEquals("Pass, short \"left\"\nto X", out.single()["desc"])
        assertEquals("7", out.single()["n"])
    }

    @Test
    fun `windows line endings and a missing final newline`() {
        assertEquals(
            listOf(mapOf("a" to "1", "b" to "2"), mapOf("a" to "3", "b" to "4")),
            rows("a,b\r\n1,2\r\n3,4", listOf("a", "b")),
        )
    }

    @Test
    fun `a short row reads its missing trailing fields as null`() {
        assertEquals(listOf(mapOf("a" to "1", "b" to null)), rows("a,b\n1\n", listOf("a", "b")))
    }

    @Test
    fun `a missing required column is named in the error`() {
        val e = assertThrows<MissingColumnsException> { rows("a,c\n1,2\n", listOf("a", "b")) }
        assertEquals(listOf("b"), e.missing)
        assertTrue("nflverse changed column" in e.message!!)
    }

    @Test
    fun `an absent optional column reads as null`() {
        var present = true
        val out = mutableListOf<String?>()
        readCsv("a\n1\n".byteInputStream(), "t", listOf("a", "b"), required = listOf("a")) { row ->
            present = row.hasColumn("b")
            out += row.text("b")
        }
        assertFalse(present)
        assertEquals(listOf<String?>(null), out)
    }

    @Test
    fun `numeric accessors`() {
        readCsv("i,d,e\n3,-2.5,\n".byteInputStream(), "t", listOf("i", "d", "e")) { row ->
            assertEquals(3, row.int("i"))
            assertEquals(-2.5, row.double("d"))
            assertNull(row.double("e"))
        }
    }

    @Test
    fun `a byte order mark does not hide the first column`() {
        assertEquals(listOf(mapOf("a" to "1")), rows("﻿a,b\n1,2\n", listOf("a")))
    }

    @Test
    fun `gzip files are decompressed`(@TempDir dir: File) {
        val file = File(dir, "x.csv.gz")
        GZIPOutputStream(file.outputStream()).use { it.write("a\n1\n".toByteArray()) }
        val out = mutableListOf<String?>()
        openInput(file).use { input -> readCsv(input, file.name, listOf("a")) { out += it.text("a") } }
        assertEquals(listOf<String?>("1"), out)
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :core:ingest:test --tests "*CsvTest*"`
Expected: FAIL, because `readCsv`, `openInput` and `MissingColumnsException` are unresolved.

- [ ] **Step 4: Implement the reader**

```kotlin
// core/ingest/src/main/kotlin/dev/gridiron/core/ingest/csv/Csv.kt
package dev.gridiron.core.ingest.csv

import java.io.File
import java.io.InputStream
import java.io.Reader
import java.util.zip.GZIPInputStream

/** A column the build needs is missing from an upstream file: nflverse renamed or dropped it. */
public class MissingColumnsException(
    public val source: String,
    public val missing: List<String>,
) : IllegalStateException("nflverse changed column(s) ${missing.joinToString()} in $source")

/**
 * The current data row. Only requested columns are held. An empty field, or a
 * requested column the file doesn't have, reads as null. The same instance is
 * reused for every record, so read what you need inside the callback.
 */
internal class CsvRow(
    private val slots: Map<String, Int>,
    private val present: Set<String>,
) {
    val values: Array<String?> = arrayOfNulls(slots.size)

    fun hasColumn(name: String): Boolean = name in present

    fun text(name: String): String? = values[slots[name] ?: error("column $name was not requested")]

    fun double(name: String): Double? = text(name)?.toDoubleOrNull()

    fun int(name: String): Int? = double(name)?.toInt()
}

/**
 * Streams RFC 4180 CSV from [input], calling [onRow] once per data row. Quoted
 * fields may hold commas, doubled quotes and line breaks. Every name in
 * [required] must be in the header; other [columns] read as null when absent.
 * Only requested fields are copied out of the stream, so a 372-column
 * play-by-play file costs little more than its bytes.
 */
internal fun readCsv(
    input: InputStream,
    source: String,
    columns: Collection<String>,
    required: Collection<String> = columns,
    onRow: (CsvRow) -> Unit,
) {
    val tokens = Tokenizer(input.reader(Charsets.UTF_8))
    val header = tokens.header() ?: throw MissingColumnsException(source, required.toList())
    val missing = required.filter { it !in header }
    if (missing.isNotEmpty()) throw MissingColumnsException(source, missing)

    val wanted = columns.distinct()
    val slots = wanted.withIndex().associate { (i, name) -> name to i }
    val slotOf = IntArray(header.size) { slots[header[it]] ?: -1 }
    val row = CsvRow(slots, wanted.filterTo(HashSet()) { it in header })
    while (true) {
        row.values.fill(null)
        if (!tokens.record(slotOf, row.values)) break
        onRow(row)
    }
}

/** Opens [file], decompressing it when its name ends in `.gz`. */
internal fun openInput(file: File): InputStream {
    val raw = file.inputStream().buffered(BUFFER)
    return if (file.name.endsWith(".gz")) GZIPInputStream(raw, BUFFER) else raw
}

private const val BUFFER = 1 shl 16
private const val EOF = -1

private class Tokenizer(private val reader: Reader) {
    private val buf = CharArray(BUFFER)
    private var len = 0
    private var pos = 0
    private val field = StringBuilder()

    fun header(): List<String>? {
        val names = mutableListOf<String>()
        if (!next({ true }) { _, value -> names += value }) return null
        names[0] = names[0].removePrefix("﻿")
        return names
    }

    fun record(slotOf: IntArray, out: Array<String?>): Boolean =
        next({ it < slotOf.size && slotOf[it] >= 0 }) { i, value -> out[slotOf[i]] = value.ifEmpty { null } }

    private fun read(): Int {
        if (pos == len) {
            len = reader.read(buf, 0, buf.size)
            pos = 0
            if (len <= 0) {
                len = 0
                return EOF
            }
        }
        return buf[pos++].code
    }

    /** Reads one record; false at end of input. Blank lines are skipped. */
    private inline fun next(keep: (Int) -> Boolean, emit: (Int, String) -> Unit): Boolean {
        var c = read()
        while (c == '\n'.code || c == '\r'.code) c = read()
        if (c == EOF) return false
        var index = 0
        while (true) {
            val kept = keep(index)
            field.setLength(0)
            if (c == '"'.code) {
                while (true) {
                    c = read()
                    if (c == EOF) break
                    if (c == '"'.code) {
                        c = read()
                        if (c != '"'.code) break
                    }
                    if (kept) field.append(c.toChar())
                }
            } else {
                while (c != EOF && c != ','.code && c != '\n'.code && c != '\r'.code) {
                    if (kept) field.append(c.toChar())
                    c = read()
                }
            }
            if (kept) emit(index, field.toString())
            index++
            when (c) {
                ','.code -> c = read()
                '\r'.code -> {
                    val after = read()
                    if (after != '\n'.code && after != EOF) pos--
                    return true
                }
                else -> return true
            }
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:ingest:test --tests "*CsvTest*"`
Expected: PASS (9 tests).

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts core/ingest
git commit -m "ingest: add :core:ingest with a streaming, column-selecting CSV reader"
```

---

### Task 2: Metric registry

A line-for-line port of `etl/gridiron_etl/metrics.py`. It is written verbatim into the `metric` table, so strings must match exactly. The parity gate (Task 12) compares every column.

**Files:**
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Metrics.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/MetricsTest.kt`

**Interfaces:**
- Produces (package `dev.gridiron.core.ingest`):
  - `internal data class Metric(id, name, abbr, group, definition, formula: String? = null, positions: List<String> = ALL, tier: String = "A", predicts: String? = null, stability: Double? = null, higherIsBetter: Boolean = true, decimals: Int = 1, hot: Boolean = false, isInternal: Boolean = false, computed: Boolean = false, sparse: Boolean = false, distFamily: String? = null, zeroInflated: Boolean = false)`
  - `internal val METRICS: List<Metric>`, 81 entries in Python's order
  - `internal val SPARSE_METRIC_IDS: Set<String>`

- [ ] **Step 1: Write the failing tests** (ported from `etl/tests/test_registry.py`)

```kotlin
// core/ingest/src/test/kotlin/dev/gridiron/core/ingest/MetricsTest.kt
package dev.gridiron.core.ingest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetricsTest {
    private val byId = METRICS.associateBy { it.id }

    private val actual = listOf(
        "passing_first_downs", "rushing_first_downs", "receiving_first_downs",
        "passing_2pt", "rushing_2pt", "receiving_2pt", "fumbles_lost",
        "passing_tds_40", "passing_tds_50", "rushing_tds_40", "rushing_tds_50",
        "receiving_tds_40", "receiving_tds_50",
    )
    private val expected = listOf(
        "x_completions", "x_receptions", "x_passing_yards", "x_rushing_yards",
        "x_receiving_yards", "x_passing_tds", "x_rushing_tds", "x_receiving_tds",
        "x_passing_2pt", "x_rushing_2pt", "x_receiving_2pt", "x_passing_first_downs",
        "x_rushing_first_downs", "x_receiving_first_downs", "x_interceptions",
    )

    @Test
    fun `ids are unique and every Python metric is here`() {
        assertEquals(METRICS.size, byId.size)
        assertEquals(81, METRICS.size)
    }

    @Test
    fun `scoring inputs are internal, sparse and not computed`() {
        for (id in actual + expected) {
            val m = byId.getValue(id)
            assertTrue(m.isInternal, id)
            assertFalse(m.computed, id)
            assertTrue(id in SPARSE_METRIC_IDS, id)
            assertEquals(id.uppercase(), m.abbr)
        }
    }

    @Test
    fun `fantasy columns are visible and computed`() {
        for ((id, abbr) in mapOf("fantasy_points" to "FPTS", "expected_fantasy_points" to "xFP", "fpoe" to "FPOE")) {
            val m = byId.getValue(id)
            assertTrue(m.computed && !m.isInternal, id)
            assertEquals(abbr, m.abbr)
            assertEquals("fantasy", m.group)
            assertFalse(id in SPARSE_METRIC_IDS)
        }
    }

    @Test
    fun `existing metrics keep their zeros`() {
        for (id in listOf("targets", "carries", "interceptions", "g", "team_targets", "carries_eff")) {
            assertFalse(id in SPARSE_METRIC_IDS, id)
        }
    }

    @Test
    fun `carries_eff is an internal denominator like its peers`() {
        val m = byId.getValue("carries_eff")
        assertTrue(m.isInternal)
        assertFalse(m.computed)
        assertEquals("context", m.group)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:ingest:test --tests "*MetricsTest*"`
Expected: FAIL, because `METRICS` is unresolved.

- [ ] **Step 3: Implement the registry**

```kotlin
// core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Metrics.kt
package dev.gridiron.core.ingest

/**
 * Every metric the app knows about: a line-for-line port of
 * `etl/gridiron_etl/metrics.py`, written verbatim into the `metric` table.
 * Adding a metric is a data change, not a schema migration.
 */
internal data class Metric(
    val id: String,
    val name: String,
    val abbr: String,
    val group: String,
    val definition: String,
    val formula: String? = null,
    val positions: List<String> = ALL_POSITIONS,
    /** A = free from play-by-play, B = free auxiliary feed. */
    val tier: String = "A",
    val predicts: String? = null,
    val stability: Double? = null,
    val higherIsBetter: Boolean = true,
    val decimals: Int = 1,
    val hot: Boolean = false,
    /** Range-aggregation components; never offered as a visible column. */
    val isInternal: Boolean = false,
    /** Computed on the device from other components; never stored as facts. */
    val computed: Boolean = false,
    /** Zero values aren't stored: absent means zero. */
    val sparse: Boolean = false,
    val distFamily: String? = null,
    val zeroInflated: Boolean = false,
)

private val ALL_POSITIONS = listOf("QB", "RB", "WR", "TE")
private val PASS_CATCHERS = listOf("RB", "WR", "TE")
private val RUSHERS = listOf("QB", "RB", "WR")
private val QB_RB = listOf("QB", "RB")
private val QB = listOf("QB")
private val RB = listOf("RB")

private class Component(val id: String, val name: String, val definition: String, val decimals: Int)

private val SCORING_INPUTS = listOf(
    Component("passing_first_downs", "Passing First Downs", "First downs gained by completions, credited to the passer.", 0),
    Component("rushing_first_downs", "Rushing First Downs", "First downs gained on carries.", 0),
    Component("receiving_first_downs", "Receiving First Downs", "First downs gained on receptions.", 0),
    Component("passing_2pt", "Passing 2-pt Conversions", "Successful two-point passes.", 0),
    Component("rushing_2pt", "Rushing 2-pt Conversions", "Successful two-point runs.", 0),
    Component("receiving_2pt", "Receiving 2-pt Conversions", "Successful two-point catches.", 0),
    Component("fumbles_lost", "Fumbles Lost", "Fumbles lost by the ball carrier, including sack fumbles.", 0),
    Component("passing_tds_40", "40+ Yd Passing TDs", "Passing touchdowns of at least 40 yards.", 0),
    Component("passing_tds_50", "50+ Yd Passing TDs", "Passing touchdowns of at least 50 yards.", 0),
    Component("rushing_tds_40", "40+ Yd Rushing TDs", "Rushing touchdowns of at least 40 yards.", 0),
    Component("rushing_tds_50", "50+ Yd Rushing TDs", "Rushing touchdowns of at least 50 yards.", 0),
    Component("receiving_tds_40", "40+ Yd Receiving TDs", "Receiving touchdowns of at least 40 yards.", 0),
    Component("receiving_tds_50", "50+ Yd Receiving TDs", "Receiving touchdowns of at least 50 yards.", 0),
    Component("x_completions", "Expected Completions", "Opportunity-model expected completions.", 2),
    Component("x_receptions", "Expected Receptions", "Opportunity-model expected receptions.", 2),
    Component("x_passing_yards", "Expected Passing Yards", "Opportunity-model expected passing yards.", 2),
    Component("x_rushing_yards", "Expected Rushing Yards", "Opportunity-model expected rushing yards.", 2),
    Component("x_receiving_yards", "Expected Receiving Yards", "Opportunity-model expected receiving yards.", 2),
    Component("x_passing_tds", "Expected Passing TDs", "Opportunity-model expected passing touchdowns.", 2),
    Component("x_rushing_tds", "Expected Rushing TDs", "Opportunity-model expected rushing touchdowns.", 2),
    Component("x_receiving_tds", "Expected Receiving TDs", "Opportunity-model expected receiving touchdowns.", 2),
    Component("x_passing_2pt", "Expected Passing 2-pt", "Opportunity-model expected two-point passes.", 2),
    Component("x_rushing_2pt", "Expected Rushing 2-pt", "Opportunity-model expected two-point runs.", 2),
    Component("x_receiving_2pt", "Expected Receiving 2-pt", "Opportunity-model expected two-point catches.", 2),
    Component("x_passing_first_downs", "Expected Passing First Downs", "Opportunity-model expected passing first downs.", 2),
    Component("x_rushing_first_downs", "Expected Rushing First Downs", "Opportunity-model expected rushing first downs.", 2),
    Component("x_receiving_first_downs", "Expected Receiving First Downs", "Opportunity-model expected receiving first downs.", 2),
    Component("x_interceptions", "Expected Interceptions", "Opportunity-model expected interceptions thrown.", 2),
)

private val RANGE_COMPONENTS = listOf(
    Component("g", "Games", "1 for each week the player recorded a play. Summed " +
        "over a range it gives games played.", 0),
    Component("team_targets", "Team Targets", "Team targets in games this player appeared in.", 0),
    Component("team_air_yards", "Team Air Yards", "Team air yards in games this player appeared in.", 0),
    Component("team_carries", "Team Carries", "Team carries in games this player appeared in.", 0),
    Component("carries_eff", "Efficiency Carries",
        "Carries excluding QB kneels and spikes — the denominator behind " +
            "carry_share, rush_success_rate and rush_epa_per_carry, so those " +
            "rates recompute correctly over a range instead of drifting once " +
            "a kneel enters the box-score carries total. Weighted " +
            "opportunities are built on it too.", 0),
    Component("team_offense_snaps", "Team Offensive Snaps", "Team offensive snaps in games this player appeared in.", 0),
    Component("rush_successes", "Rush Successes", "Carries with positive EPA.", 0),
    Component("rush_epa", "Rush EPA", "Summed EPA on carries.", 3),
    Component("rec_epa", "Receiving EPA", "Summed EPA on targets.", 3),
    Component("pass_epa", "Pass EPA", "Summed EPA on dropbacks.", 3),
    Component("cpoe_sum", "CPOE Sum", "Summed per-attempt CPOE.", 3),
    Component("cpoe_n", "CPOE Attempts", "Attempts with a CPOE value.", 0),
)

internal val METRICS: List<Metric> = listOf(
    // ---------------- Receiving volume ----------------
    Metric("targets", "Targets", "TGT", "volume",
        "Pass attempts thrown in this player's direction, including incompletions.",
        positions = PASS_CATCHERS, predicts = "Receiving production floor",
        stability = 0.70, decimals = 0, hot = true),
    Metric("receptions", "Receptions", "REC", "volume",
        "Completed catches.", positions = PASS_CATCHERS, decimals = 0, hot = true),
    Metric("receiving_yards", "Receiving Yards", "REC YDS", "volume",
        "Total yards gained on receptions.", positions = PASS_CATCHERS, decimals = 0, hot = true),
    Metric("receiving_tds", "Receiving TDs", "REC TD", "volume",
        "Touchdowns scored as a receiver.", positions = PASS_CATCHERS,
        predicts = "Weak — TD rate regresses hard", stability = 0.28, decimals = 0, hot = true),
    Metric("air_yards", "Air Yards", "AY", "volume",
        "Total distance the ball travelled in the air on all targets, caught or not.",
        positions = PASS_CATCHERS, predicts = "Opportunity independent of catch outcome",
        stability = 0.62, decimals = 0, hot = true),
    Metric("target_share", "Target Share", "TGT%", "volume",
        "Share of the team's targets that went to this player.",
        formula = "player_targets / team_targets", positions = PASS_CATCHERS,
        predicts = "Most predictive raw receiving stat", stability = 0.72, decimals = 3, hot = true),
    Metric("air_yards_share", "Air Yards Share", "AY%", "volume",
        "Share of the team's air yards directed at this player. Can fall " +
            "below 0 or above 1 in a single week: about 18% of pass attempts " +
            "carry negative air yards, so a screen-heavy role produces a " +
            "negative share and a teammate can exceed the team total.",
        formula = "player_air_yards / team_air_yards", positions = PASS_CATCHERS,
        stability = 0.65, decimals = 3, hot = true),
    Metric("wopr", "Weighted Opportunity Rating", "WOPR", "volume",
        "Composite of target share and air yards share. The best single " +
            "opportunity number for pass catchers; above 0.70 is elite.",
        formula = "1.5 * target_share + 0.7 * air_yards_share", positions = PASS_CATCHERS,
        predicts = "Receiving fantasy points", stability = 0.71, decimals = 3, hot = true),
    Metric("adot", "Average Depth of Target", "aDOT", "efficiency",
        "Mean air yards per target. A role classifier, not a quality measure — " +
            "it makes catch rate interpretable.",
        formula = "air_yards / targets", positions = PASS_CATCHERS,
        stability = 0.76, decimals = 1, hot = true),
    Metric("racr", "Receiver Air Conversion Ratio", "RACR", "efficiency",
        "Receiving yards produced per air yard thrown. Pairs with aDOT to " +
            "separate deep threats from YAC producers.",
        formula = "receiving_yards / air_yards", positions = PASS_CATCHERS,
        stability = 0.35, decimals = 2),
    Metric("yac", "Yards After Catch", "YAC", "efficiency",
        "Yards gained after the catch.", positions = PASS_CATCHERS, decimals = 0),
    Metric("catch_rate", "Catch Rate", "CTCH%", "efficiency",
        "Receptions per target. Only interpretable alongside aDOT.",
        formula = "receptions / targets", positions = PASS_CATCHERS, decimals = 3),
    Metric("rz_targets", "Red Zone Targets", "RZ TGT", "usage",
        "Targets on snaps starting inside the opponent 20.",
        positions = PASS_CATCHERS, predicts = "Touchdown opportunity", decimals = 0, hot = true),
    Metric("ez_targets", "End Zone Targets", "EZ TGT", "usage",
        "Targets thrown to or beyond the goal line. Worth roughly 3.0 expected " +
            "points versus 1.8 for a target from the 19.",
        formula = "targets where air_yards >= yardline_100",
        positions = PASS_CATCHERS, predicts = "Touchdown opportunity", decimals = 0),

    // ---------------- Rushing ----------------
    Metric("carries", "Carries", "CAR", "volume",
        "Rushing attempts, QB kneels included, matching the box score.",
        positions = RUSHERS, decimals = 0, hot = true),
    Metric("rushing_yards", "Rushing Yards", "RUSH YDS", "volume",
        "Total rushing yards.", positions = RUSHERS, decimals = 0, hot = true),
    Metric("rushing_tds", "Rushing TDs", "RUSH TD", "volume",
        "Rushing touchdowns.", positions = RUSHERS, stability = 0.30, decimals = 0, hot = true),
    Metric("carry_share", "Carry Share", "CAR%", "volume",
        "Share of the team's carries taken by this player.",
        formula = "player_carries / team_carries", positions = RB,
        stability = 0.68, decimals = 3, hot = true),
    Metric("weighted_opportunities", "Weighted Opportunities", "WO", "volume",
        "Carries plus targets weighted by their relative PPR value. QB kneels " +
            "are not opportunities and are excluded.",
        formula = "carries (kneels excluded) + 2.6 * targets", positions = RB,
        predicts = "PPR fantasy points", decimals = 1, hot = true),
    Metric("opportunity_share", "Opportunity Share", "OPP%", "volume",
        "Share of the team's backfield carries and targets. Above 70% is a bellcow.",
        formula = "(carries + targets) / (team_rb_carries + team_rb_targets)",
        positions = RB, stability = 0.66, decimals = 3),
    Metric("rz_carries", "Red Zone Carries", "RZ CAR", "usage",
        "Carries starting inside the opponent 20, QB kneels excluded.",
        positions = QB_RB, decimals = 0, hot = true),
    Metric("gz_carries", "Green Zone Carries", "GZ CAR", "usage",
        "Carries starting inside the opponent 10, QB kneels excluded. Roughly " +
            "74% of rushing touchdowns originate here.",
        positions = QB_RB, predicts = "Rushing touchdowns", decimals = 0, hot = true),
    Metric("gl_carries", "Goal Line Carries", "GL CAR", "usage",
        "Carries starting inside the opponent 5, QB kneels excluded. Roughly " +
            "68% of rushing touchdowns originate here.",
        positions = QB_RB, predicts = "Rushing touchdowns", decimals = 0),
    Metric("rush_success_rate", "Rush Success Rate", "RSR", "efficiency",
        "Share of carries producing positive EPA. Far more stable than yards " +
            "per carry, which is notoriously noisy.",
        formula = "rushes with epa > 0 / carries", positions = QB_RB,
        stability = 0.44, decimals = 3),
    Metric("rush_epa_per_carry", "Rush EPA per Carry", "EPA/CAR", "efficiency",
        "Expected points added per rushing attempt.", positions = QB_RB, decimals = 3),

    // ---------------- Passing ----------------
    Metric("attempts", "Pass Attempts", "ATT", "passing",
        "Pass attempts.", positions = QB, decimals = 0, hot = true),
    Metric("completions", "Completions", "CMP", "passing",
        "Completed passes.", positions = QB, decimals = 0),
    Metric("passing_yards", "Passing Yards", "PASS YDS", "passing",
        "Total passing yards.", positions = QB, decimals = 0, hot = true),
    Metric("passing_tds", "Passing TDs", "PASS TD", "passing",
        "Passing touchdowns.", positions = QB, stability = 0.32, decimals = 0, hot = true),
    Metric("interceptions", "Interceptions", "INT", "passing",
        "Interceptions thrown.", positions = QB, higherIsBetter = false, decimals = 0, hot = true),
    Metric("sacks_taken", "Sacks Taken", "SK", "passing",
        "Times sacked.", positions = QB, higherIsBetter = false, decimals = 0),
    Metric("dropbacks", "Dropbacks", "DB", "passing",
        "Pass attempts plus sacks plus scrambles.", positions = QB, decimals = 0),
    Metric("epa_per_dropback", "EPA per Dropback", "EPA/DB", "efficiency",
        "Expected points added per dropback. The single best QB quality measure.",
        positions = QB, stability = 0.55, decimals = 3, hot = true),
    Metric("cpoe", "Completion % Over Expected", "CPOE", "efficiency",
        "Completion percentage above what the throw's difficulty predicts.",
        positions = QB, stability = 0.47, decimals = 2, hot = true),
    Metric("qb_rush_inside_5", "QB Carries Inside 5", "QB GL", "usage",
        "Designed QB runs inside the opponent 5 (no scrambles or kneels). The " +
            "biggest single source of QB fantasy separation.",
        positions = QB, predicts = "QB rushing touchdowns", decimals = 0),

    // ---------------- Snaps (tier B — auxiliary feed) ----------------
    Metric("offense_snaps", "Offensive Snaps", "SNAP", "volume",
        "Offensive snaps played.", tier = "B", decimals = 0, hot = true),
    Metric("snap_share", "Snap Share", "SNAP%", "volume",
        "Share of the team's offensive snaps played. The earliest reliable " +
            "signal of a role change, and the strongest waiver indicator.",
        formula = "offense_snaps / team_offense_snaps", tier = "B",
        predicts = "Role change before box score reflects it",
        stability = 0.79, decimals = 3, hot = true),

    // ---------------- Fantasy (computed on the device) ----------------
    Metric("fantasy_points", "Fantasy Points", "FPTS", "fantasy",
        "Points under the active scoring profile, scored game by game so " +
            "per-game bonuses apply to single games.", decimals = 1, computed = true),
    Metric("expected_fantasy_points", "Expected Fantasy Points", "xFP", "fantasy",
        "Points an average player would score from the same opportunities: " +
            "the active profile applied to the opportunity model's expected " +
            "receptions, yards, touchdowns and first downs.",
        predicts = "Future fantasy points, better than past points do",
        decimals = 1, computed = true),
    Metric("fpoe", "Fantasy Points Over Expected", "FPOE", "fantasy",
        "Actual fantasy points minus expected. Positive is a sell-high " +
            "signal, negative a buy-low signal. Long plays and fumbles have no " +
            "expectation, so they land here.",
        formula = "fantasy_points - expected_fantasy_points",
        predicts = "Negative regression when high", stability = 0.12,
        decimals = 1, computed = true),
    Metric("total_epa", "Total EPA", "EPA", "efficiency",
        "Expected points added across all touches.", decimals = 2),
) + SCORING_INPUTS.map {
    Metric(it.id, it.name, it.id.uppercase(), "fantasy", it.definition,
        decimals = it.decimals, isInternal = true, sparse = true)
} + RANGE_COMPONENTS.map {
    Metric(it.id, it.name, it.id.uppercase(), "context", it.definition,
        positions = ALL_POSITIONS, decimals = it.decimals, isInternal = true)
}

internal val SPARSE_METRIC_IDS: Set<String> = METRICS.filter { it.sparse }.mapTo(HashSet()) { it.id }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:ingest:test --tests "*MetricsTest*"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add core/ingest/src
git commit -m "ingest: port the metric registry from metrics.py"
```

---
### Task 3: Play-by-play reader and per player-week aggregation

This ports `transform.py`'s `load_pbp` filter and `_receiving`, `_rushing`, `_passing`, `_fumbles`, `_two_point` and `_team_context`. The derived rates come in Task 4. The semantics that must carry over exactly:

**Filters**
- The scrimmage filter is: `season_type` in {REG, POST}, `play_type` in {pass, run, qb_kneel, qb_spike}, and `posteam` not null.
- Two-point tries (`two_point_attempt == 1`) are excluded from everything except two-point credit.

**Kneels and spikes**
- These are the non-efficiency plays.
- They count toward box-score totals: carries, rushing yards, pass attempts.
- They are excluded from rates, usage and team denominators: zone carries, `qb_rush_inside_5`, `rush_epa`, `rush_successes`, `carries_eff`, `dropbacks`, `pass_epa`, CPOE, `team_targets`, `team_air_yards` and `team_carries`.

**Nulls**
- Null reads as 0 in sums, like polars' `fill_null(0)`.
- A comparison involving a null is false.

**Which columns are nullable**
- Nullable: `rec_epa`, `rush_epa`, `pass_epa`, `cpoe`, `cpoe_sum`, and the team columns. The EPA and CPOE columns are null when the player has no play of that kind.
- `cpoe` is also null when there are no CPOE values.
- Every other column is 0 when absent.

**Files:**
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/pbp/Play.kt`
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/pbp/PlayerWeek.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/pbp/PlayFixtures.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/pbp/PlayerWeekAggregatorTest.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/pbp/ReadPlaysTest.kt`

**Interfaces:**
- Consumes: `readCsv`, `CsvRow` (Task 1).
- Produces (package `dev.gridiron.core.ingest.pbp`):
  - `internal val SEASON_TYPES`, `SCRIMMAGE_PLAY_TYPES`, `RATE_EXCLUDED_PLAY_TYPES: Set<String>`
  - `internal val PBP_COLUMNS: List<String>`
  - `internal class Play(...)` with the fields listed in Step 3, plus `isEfficiency: Boolean`
  - `internal fun readPlays(input: InputStream, source: String, onPlay: (Play) -> Unit)`
  - `internal class PlayerWeek(season: Int, week: Int, team: String?, playerId: String, values: MutableMap<String, Double?>)`
  - `internal class PlayerWeekAggregator` with `add(Play)` and `rows(): List<PlayerWeek>`

- [ ] **Step 1: Write the test fixtures** (Kotlin twins of the Python tests' `play`, `target`, `carry`, `kneel` and `spike`)

```kotlin
// core/ingest/src/test/kotlin/dev/gridiron/core/ingest/pbp/PlayFixtures.kt
package dev.gridiron.core.ingest.pbp

/** One play with the same neutral defaults as `etl/tests/test_transform.py`'s `play()`. */
internal fun play(
    season: Int = 2025, week: Int = 1, seasonType: String? = "REG", gameId: String? = "g1",
    posteam: String? = "AAA", defteam: String? = "BBB", playType: String? = "pass",
    passAttempt: Double? = 0.0, completePass: Double? = 0.0, airYards: Double? = null,
    yardsAfterCatch: Double? = null, yardsGained: Double? = 0.0, passingYards: Double? = null,
    receivingYards: Double? = null, rushingYards: Double? = null, passTouchdown: Double? = 0.0,
    rushTouchdown: Double? = 0.0, interception: Double? = 0.0, sack: Double? = 0.0,
    qbScramble: Double? = 0.0, receiver: String? = null, rusher: String? = null,
    passer: String? = null, yardline100: Double? = 50.0, epa: Double? = 0.0,
    success: Double? = 0.0, cpoe: Double? = null, twoPointAttempt: Double? = 0.0,
    firstDownPass: Double? = 0.0, firstDownRush: Double? = 0.0, fumbleLost: Double? = 0.0,
    fumbler: String? = null, twoPointResult: String? = null, touchdown: Double? = 0.0,
    tdTeam: String? = null, homeTeam: String? = "AAA", awayTeam: String? = "BBB",
    totalHomeScore: Double? = 0.0, totalAwayScore: Double? = 0.0,
): Play = Play(
    season = season, week = week, seasonType = seasonType, gameId = gameId, posteam = posteam,
    defteam = defteam, playType = playType, passAttempt = passAttempt, completePass = completePass,
    airYards = airYards, yardsAfterCatch = yardsAfterCatch, yardsGained = yardsGained,
    passingYards = passingYards, receivingYards = receivingYards, rushingYards = rushingYards,
    passTouchdown = passTouchdown, rushTouchdown = rushTouchdown, interception = interception,
    sack = sack, qbScramble = qbScramble, receiver = receiver, rusher = rusher, passer = passer,
    yardline100 = yardline100, epa = epa, success = success, cpoe = cpoe,
    twoPointAttempt = twoPointAttempt, firstDownPass = firstDownPass, firstDownRush = firstDownRush,
    fumbleLost = fumbleLost, fumbler = fumbler, twoPointResult = twoPointResult,
    touchdown = touchdown, tdTeam = tdTeam, homeTeam = homeTeam, awayTeam = awayTeam,
    totalHomeScore = totalHomeScore, totalAwayScore = totalAwayScore,
)

internal fun target(
    rec: String, air: Double, complete: Boolean = false, yds: Double = 0.0, yl: Double = 50.0,
    td: Double = 0.0, qb: String = "QB1", epa: Double? = 0.0, cpoe: Double? = null,
    seasonType: String = "REG", twoPointAttempt: Double = 0.0, twoPointResult: String? = null,
    firstDownPass: Double = 0.0, fumbleLost: Double = 0.0, fumbler: String? = null,
): Play = play(
    playType = "pass", passAttempt = 1.0, completePass = if (complete) 1.0 else 0.0, airYards = air,
    receivingYards = if (complete) yds else null, passingYards = if (complete) yds else null,
    passTouchdown = td, receiver = rec, passer = qb, yardline100 = yl, epa = epa, cpoe = cpoe,
    seasonType = seasonType, twoPointAttempt = twoPointAttempt, twoPointResult = twoPointResult,
    firstDownPass = firstDownPass, fumbleLost = fumbleLost, fumbler = fumbler,
)

internal fun carry(
    rb: String, yds: Double = 0.0, yl: Double = 50.0, td: Double = 0.0, success: Double = 0.0,
    epa: Double = 0.0, qbScramble: Double = 0.0, twoPointAttempt: Double = 0.0,
    twoPointResult: String? = null, firstDownRush: Double = 0.0, fumbleLost: Double = 0.0,
    fumbler: String? = null,
): Play = play(
    playType = "run", rushingYards = yds, rusher = rb, rushTouchdown = td, yardline100 = yl,
    success = success, epa = epa, qbScramble = qbScramble, twoPointAttempt = twoPointAttempt,
    twoPointResult = twoPointResult, firstDownRush = firstDownRush, fumbleLost = fumbleLost,
    fumbler = fumbler,
)

/** A QB kneel: always a loss, like a real clock-killer. */
internal fun kneel(qb: String, yds: Double = -1.0, epa: Double = -0.5, yl: Double = 50.0): Play =
    play(playType = "qb_kneel", rushingYards = yds, rusher = qb, success = 0.0, epa = epa, yardline100 = yl)

/** A QB spike: a zero-yard incompletion. */
internal fun spike(qb: String, epa: Double = -0.1): Play =
    play(playType = "qb_spike", passAttempt = 1.0, completePass = 0.0, passer = qb, epa = epa)

internal fun base(plays: List<Play>): List<PlayerWeek> =
    PlayerWeekAggregator().apply { plays.forEach(::add) }.rows()

internal fun List<PlayerWeek>.row(pid: String): Map<String, Double?> {
    val hits = filter { it.playerId == pid }
    check(hits.size == 1) { "expected one row for $pid, got ${hits.size}" }
    return hits.single().values
}
```

- [ ] **Step 2: Write the failing tests** (ported from `etl/tests/test_transform.py`; the rate and share tests come in Task 4)

```kotlin
// core/ingest/src/test/kotlin/dev/gridiron/core/ingest/pbp/PlayerWeekAggregatorTest.kt
package dev.gridiron.core.ingest.pbp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class PlayerWeekAggregatorTest {

    @ParameterizedTest
    @CsvSource("25,0,0,0", "20,1,0,0", "10,1,1,0", "5,1,1,1", "1,1,1,1")
    fun `carry zone thresholds are inclusive`(yl: Double, rz: Double, gz: Double, gl: Double) {
        val r = base(listOf(carry("RB1", yl = yl))).row("RB1")
        assertEquals(listOf(rz, gz, gl), listOf(r["rz_carries"], r["gz_carries"], r["gl_carries"]))
    }

    @Test
    fun `end zone target when air yards reach the goal line`() {
        val rows = base(listOf(target("WR1", 15.0, yl = 15.0), target("WR1", 14.0, yl = 15.0), target("WR1", 30.0, yl = 15.0)))
        assertEquals(2.0, rows.row("WR1")["ez_targets"])
    }

    @Test
    fun `designed QB runs exclude scrambles`() {
        val r = base(listOf(carry("QB1", yl = 3.0), carry("QB1", yl = 3.0, qbScramble = 1.0))).row("QB1")
        assertEquals(2.0, r["gl_carries"])
        assertEquals(1.0, r["qb_rush_inside_5"])
    }

    @Test
    fun `two-point attempts, non-scrimmage plays and preseason do not count`() {
        val rows = base(listOf(
            target("WR1", 5.0),
            target("WR1", 2.0, twoPointAttempt = 1.0),
            play(playType = "no_play", receiver = "WR1"),
            target("WR1", 10.0, seasonType = "PRE"),
        ))
        assertEquals(1.0, rows.row("WR1")["targets"])
    }

    @Test
    fun `a kneel counts as a carry and its yards count`() {
        val r = base(listOf(carry("QB1", 5.0), kneel("QB1", -2.0))).row("QB1")
        assertEquals(2.0, r["carries"])
        assertEquals(3.0, r["rushing_yards"])
        assertEquals(1.0, r["carries_eff"])
    }

    @Test
    fun `a kneel at the 3 is a carry but not goal-line usage`() {
        val r = base(listOf(carry("QB1", 2.0, yl = 3.0), kneel("QB1", -1.0, yl = 3.0))).row("QB1")
        assertEquals(2.0, r["carries"])
        assertEquals(1.0, r["rushing_yards"])
        assertEquals(1.0, r["carries_eff"])
        assertEquals(listOf(1.0, 1.0, 1.0), listOf(r["rz_carries"], r["gz_carries"], r["gl_carries"]))
        assertEquals(1.0, r["qb_rush_inside_5"])
    }

    @Test
    fun `a spike counts as a pass attempt but not a dropback`() {
        val r = base(listOf(target("WR1", 10.0, complete = true, yds = 10.0, epa = 0.8), spike("QB1"))).row("QB1")
        assertEquals(2.0, r["attempts"])
        assertEquals(1.0, r["dropbacks"])
        assertEquals(0.8, r["pass_epa"]!!, 1e-12)
    }

    @Test
    fun `a player with rushing and receiving is one row`() {
        val r = base(listOf(carry("RB1", 5.0), target("RB1", 3.0, complete = true, yds = 8.0))).row("RB1")
        assertEquals(listOf(1.0, 1.0, 1.0), listOf(r["carries"], r["targets"], r["receptions"]))
    }

    @Test
    fun `team denominators are stored and exclude kneels`() {
        val r = base(listOf(target("WR1", 10.0), target("WR1", 20.0), target("WR2", 30.0), carry("RB1"), kneel("QB1"))).row("WR1")
        assertEquals(3.0, r["team_targets"])
        assertEquals(60.0, r["team_air_yards"])
        assertEquals(1.0, r["team_carries"])
    }

    @Test
    fun `CPOE components allow attempt weighting`() {
        val qb = base(listOf(target("WR1", 5.0, cpoe = 10.0), target("WR1", 5.0, cpoe = -4.0), target("WR1", 5.0, cpoe = null))).row("QB1")
        assertEquals(6.0, qb["cpoe_sum"])
        assertEquals(2.0, qb["cpoe_n"])
        assertEquals(3.0, qb["cpoe"])
    }

    @Test
    fun `EPA and CPOE columns are null for players without that kind of play`() {
        val rb = base(listOf(carry("RB1", 3.0, epa = 0.4))).row("RB1")
        assertEquals(0.4, rb["rush_epa"]!!, 1e-12)
        assertNull(rb["rec_epa"])
        assertNull(rb["pass_epa"])
        assertNull(rb["cpoe"])
        assertNull(rb["cpoe_sum"])
        assertEquals(0.0, rb["cpoe_n"])
    }

    @Test
    fun `first downs credit passer, receiver and rusher`() {
        val rows = base(listOf(
            target("WR1", 8.0, complete = true, yds = 12.0, firstDownPass = 1.0),
            target("WR1", 3.0, complete = true, yds = 4.0),
            carry("RB1", 11.0, firstDownRush = 1.0),
        ))
        assertEquals(1.0, rows.row("WR1")["receiving_first_downs"])
        assertEquals(1.0, rows.row("QB1")["passing_first_downs"])
        assertEquals(1.0, rows.row("RB1")["rushing_first_downs"])
    }

    @Test
    fun `long touchdowns count at 40 and 50 and nest`() {
        val rows = base(listOf(
            target("WR1", 30.0, complete = true, yds = 55.0, td = 1.0),
            target("WR1", 20.0, complete = true, yds = 42.0, td = 1.0),
            target("WR1", 5.0, complete = true, yds = 39.0, td = 1.0),
            target("WR1", 45.0, complete = true, yds = 60.0),
            carry("RB1", 61.0, td = 1.0),
        ))
        val wr = rows.row("WR1")
        val qb = rows.row("QB1")
        val rb = rows.row("RB1")
        assertEquals(listOf(2.0, 1.0), listOf(wr["receiving_tds_40"], wr["receiving_tds_50"]))
        assertEquals(listOf(2.0, 1.0), listOf(qb["passing_tds_40"], qb["passing_tds_50"]))
        assertEquals(listOf(1.0, 1.0), listOf(rb["rushing_tds_40"], rb["rushing_tds_50"]))
    }

    @Test
    fun `fumbles lost are credited to the ball carrier`() {
        val rows = base(listOf(
            carry("RB1", 3.0, fumbleLost = 1.0, fumbler = "RB1"),
            target("WR1", 5.0, complete = true, yds = 9.0, fumbleLost = 1.0, fumbler = "WR1"),
            play(playType = "pass", sack = 1.0, passer = "QB1", fumbleLost = 1.0, fumbler = "QB1"),
        ))
        assertEquals(1.0, rows.row("RB1")["fumbles_lost"])
        assertEquals(1.0, rows.row("WR1")["fumbles_lost"])
        assertEquals(1.0, rows.row("QB1")["fumbles_lost"])
    }

    @Test
    fun `recovered fumbles and defender fumbles do not count`() {
        val rows = base(listOf(
            carry("RB1", 3.0, fumbleLost = 0.0, fumbler = "RB1"),
            target("WR1", 5.0, complete = true, yds = 9.0, fumbleLost = 1.0, fumbler = "CB9"),
        ))
        assertEquals(0.0, rows.row("RB1")["fumbles_lost"])
        assertEquals(0.0, rows.row("WR1")["fumbles_lost"])
        assertTrue(rows.none { it.playerId == "CB9" })
    }

    @Test
    fun `successful two-point conversions are credited and add no targets`() {
        val rows = base(listOf(
            target("WR1", 10.0),
            target("WR1", 2.0, complete = true, yds = 2.0, twoPointAttempt = 1.0, twoPointResult = "success"),
            target("WR1", 2.0, twoPointAttempt = 1.0, twoPointResult = "failure"),
            carry("RB1", 2.0, twoPointAttempt = 1.0, twoPointResult = "success"),
        ))
        val wr = rows.row("WR1")
        assertEquals(1.0, wr["receiving_2pt"])
        assertEquals(1.0, wr["targets"])
        assertEquals(1.0, rows.row("QB1")["passing_2pt"])
        assertEquals(1.0, rows.row("RB1")["rushing_2pt"])
    }
}
```

```kotlin
// core/ingest/src/test/kotlin/dev/gridiron/core/ingest/pbp/ReadPlaysTest.kt
package dev.gridiron.core.ingest.pbp

import dev.gridiron.core.ingest.csv.MissingColumnsException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ReadPlaysTest {
    private fun csv(columns: List<String>, values: Map<String, String>): String =
        columns.joinToString(",") + "\n" + columns.joinToString(",") { values[it] ?: "" } + "\n"

    @Test
    fun `reads a play-by-play row into a play`() {
        val values = mapOf(
            "season" to "2025", "week" to "3", "season_type" to "REG", "posteam" to "AAA",
            "play_type" to "pass", "receiver_player_id" to "00-1", "air_yards" to "12", "epa" to "-0.25",
        )
        val plays = mutableListOf<Play>()
        readPlays(csv(PBP_COLUMNS, values).byteInputStream(), "pbp.csv") { plays += it }
        val p = plays.single()
        assertEquals(2025, p.season)
        assertEquals(3, p.week)
        assertEquals("00-1", p.receiver)
        assertEquals(12.0, p.airYards)
        assertEquals(-0.25, p.epa)
        assertNull(p.cpoe)
        assertNull(p.rusher)
    }

    @Test
    fun `a play-by-play file missing a needed column is rejected by name`() {
        val e = assertThrows<MissingColumnsException> {
            readPlays(csv(PBP_COLUMNS - "epa", mapOf("season" to "2025", "week" to "1")).byteInputStream(), "pbp.csv") {}
        }
        assertEquals(listOf("epa"), e.missing)
    }
}
```

The `@ParameterizedTest` and `@CsvSource` annotations are in `junit-jupiter-params`, which the `junit-jupiter` aggregate the convention plugin adds already includes.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :core:ingest:test --tests "*PlayerWeekAggregatorTest*" --tests "*ReadPlaysTest*"`
Expected: FAIL, because `Play`, `PlayerWeekAggregator` and `readPlays` are unresolved.

- [ ] **Step 4: Implement `Play` and `readPlays`**

```kotlin
// core/ingest/src/main/kotlin/dev/gridiron/core/ingest/pbp/Play.kt
package dev.gridiron.core.ingest.pbp

import dev.gridiron.core.ingest.csv.CsvRow
import dev.gridiron.core.ingest.csv.readCsv
import java.io.InputStream

internal val SEASON_TYPES: Set<String> = setOf("REG", "POST")

/**
 * Scrimmage plays that carry official rushing/passing stats. Kneels and spikes
 * are real attempts (nflverse marks them rush_attempt/pass_attempt = 1), so
 * box-score totals and scoring inputs include them.
 */
internal val SCRIMMAGE_PLAY_TYPES: Set<String> = setOf("pass", "run", "qb_kneel", "qb_spike")

/** Counted for box-score volume, excluded from every rate, share and usage signal. */
internal val RATE_EXCLUDED_PLAY_TYPES: Set<String> = setOf("qb_kneel", "qb_spike")

/** Play-by-play columns the build reads. Every one must exist in the file. */
internal val PBP_COLUMNS: List<String> = listOf(
    "season", "week", "season_type", "game_id", "posteam", "defteam", "play_type",
    "pass_attempt", "complete_pass", "air_yards", "yards_after_catch", "yards_gained",
    "passing_yards", "receiving_yards", "rushing_yards", "pass_touchdown", "rush_touchdown",
    "interception", "sack", "qb_scramble", "receiver_player_id", "rusher_player_id",
    "passer_player_id", "yardline_100", "epa", "success", "cpoe", "two_point_attempt",
    "first_down_pass", "first_down_rush", "fumble_lost", "fumbled_1_player_id",
    "two_point_conv_result", "touchdown", "td_team", "home_team", "away_team",
    "total_home_score", "total_away_score",
)

internal class Play(
    val season: Int,
    val week: Int,
    val seasonType: String?,
    val gameId: String?,
    val posteam: String?,
    val defteam: String?,
    val playType: String?,
    val passAttempt: Double?,
    val completePass: Double?,
    val airYards: Double?,
    val yardsAfterCatch: Double?,
    val yardsGained: Double?,
    val passingYards: Double?,
    val receivingYards: Double?,
    val rushingYards: Double?,
    val passTouchdown: Double?,
    val rushTouchdown: Double?,
    val interception: Double?,
    val sack: Double?,
    val qbScramble: Double?,
    val receiver: String?,
    val rusher: String?,
    val passer: String?,
    val yardline100: Double?,
    val epa: Double?,
    val success: Double?,
    val cpoe: Double?,
    val twoPointAttempt: Double?,
    val firstDownPass: Double?,
    val firstDownRush: Double?,
    val fumbleLost: Double?,
    val fumbler: String?,
    val twoPointResult: String?,
    val touchdown: Double?,
    val tdTeam: String?,
    val homeTeam: String?,
    val awayTeam: String?,
    val totalHomeScore: Double?,
    val totalAwayScore: Double?,
) {
    /** False for kneels and spikes: see [RATE_EXCLUDED_PLAY_TYPES]. */
    val isEfficiency: Boolean get() = playType !in RATE_EXCLUDED_PLAY_TYPES
}

private fun CsvRow.toPlay(): Play? {
    val season = int("season") ?: return null
    val week = int("week") ?: return null
    return Play(
        season = season, week = week, seasonType = text("season_type"), gameId = text("game_id"),
        posteam = text("posteam"), defteam = text("defteam"), playType = text("play_type"),
        passAttempt = double("pass_attempt"), completePass = double("complete_pass"),
        airYards = double("air_yards"), yardsAfterCatch = double("yards_after_catch"),
        yardsGained = double("yards_gained"), passingYards = double("passing_yards"),
        receivingYards = double("receiving_yards"), rushingYards = double("rushing_yards"),
        passTouchdown = double("pass_touchdown"), rushTouchdown = double("rush_touchdown"),
        interception = double("interception"), sack = double("sack"),
        qbScramble = double("qb_scramble"), receiver = text("receiver_player_id"),
        rusher = text("rusher_player_id"), passer = text("passer_player_id"),
        yardline100 = double("yardline_100"), epa = double("epa"), success = double("success"),
        cpoe = double("cpoe"), twoPointAttempt = double("two_point_attempt"),
        firstDownPass = double("first_down_pass"), firstDownRush = double("first_down_rush"),
        fumbleLost = double("fumble_lost"), fumbler = text("fumbled_1_player_id"),
        twoPointResult = text("two_point_conv_result"), touchdown = double("touchdown"),
        tdTeam = text("td_team"), homeTeam = text("home_team"), awayTeam = text("away_team"),
        totalHomeScore = double("total_home_score"), totalAwayScore = double("total_away_score"),
    )
}

/** Streams every play in a play-by-play file. Each aggregator applies its own filter. */
internal fun readPlays(input: InputStream, source: String, onPlay: (Play) -> Unit) {
    readCsv(input, source, PBP_COLUMNS) { row -> row.toPlay()?.let(onPlay) }
}
```

- [ ] **Step 5: Implement the aggregator**

```kotlin
// core/ingest/src/main/kotlin/dev/gridiron/core/ingest/pbp/PlayerWeek.kt
package dev.gridiron.core.ingest.pbp

/**
 * One player's week: the Kotlin twin of a row of the Python ETL's weekly
 * frame. A missing key and a null value both mean "no value" and are never
 * stored.
 */
internal class PlayerWeek(
    val season: Int,
    val week: Int,
    val team: String?,
    val playerId: String,
    val values: MutableMap<String, Double?>,
)

/**
 * Folds play-by-play into per player-week components, reproducing
 * `transform.weekly_player_stats` up to its derived columns (see `derive`).
 * Keys include the team, as in Python, so a player's row is per team.
 */
internal class PlayerWeekAggregator {
    private data class PlayerKey(val season: Int, val week: Int, val team: String, val playerId: String)
    private data class TeamKey(val season: Int, val week: Int, val team: String)

    private val players = LinkedHashMap<PlayerKey, Acc>()
    private val teams = HashMap<TeamKey, TeamAcc>()

    fun add(p: Play) {
        val team = p.posteam ?: return
        if (p.seasonType !in SEASON_TYPES || p.playType !in SCRIMMAGE_PLAY_TYPES) return
        val twoPoint = p.twoPointAttempt ?: 0.0
        if (twoPoint == 0.0) scrimmage(p, team) else if (twoPoint == 1.0) twoPoint(p, team)
    }

    fun rows(): List<PlayerWeek> = players.map { (k, acc) ->
        PlayerWeek(k.season, k.week, k.team, k.playerId, acc.columns(teams[TeamKey(k.season, k.week, k.team)]))
    }

    private fun acc(p: Play, team: String, playerId: String): Acc =
        players.getOrPut(PlayerKey(p.season, p.week, team, playerId)) { Acc() }

    private fun scrimmage(p: Play, team: String) {
        val eff = p.isEfficiency
        // Share denominators, on the same play set as their numerators: no dead-clock plays.
        val t = teams.getOrPut(TeamKey(p.season, p.week, team)) { TeamAcc() }
        if (p.receiver != null && eff) t.targets++
        if (eff) t.airYards += p.airYards ?: 0.0
        if (p.rusher != null && eff) t.carries++

        p.receiver?.let { acc(p, team, it).receiving(p) }
        p.rusher?.let { acc(p, team, it).rushing(p, eff) }
        p.passer?.let { acc(p, team, it).passing(p, eff) }

        // Credited to the ball carrier; a defender fumbling a return is not an offensive stat.
        val fumbler = p.fumbler
        if ((p.fumbleLost ?: 0.0) == 1.0 && fumbler != null &&
            (fumbler == p.rusher || fumbler == p.receiver || fumbler == p.passer)
        ) {
            acc(p, team, fumbler).fumblesLost++
        }
    }

    /** Successful two-point tries credit the passer and receiver, or the rusher. No targets or carries. */
    private fun twoPoint(p: Play, team: String) {
        if (p.twoPointResult != "success") return
        p.passer?.let { acc(p, team, it).passing2pt++ }
        p.receiver?.let { acc(p, team, it).receiving2pt++ }
        p.rusher?.let { acc(p, team, it).rushing2pt++ }
    }

    private class TeamAcc {
        var targets = 0.0
        var airYards = 0.0
        var carries = 0.0
    }

    private class Acc {
        var hasReceiving = false
        var targets = 0.0
        var receptions = 0.0
        var receivingYards = 0.0
        var airYards = 0.0
        var yac = 0.0
        var receivingTds = 0.0
        var rzTargets = 0.0
        var ezTargets = 0.0
        var recEpa = 0.0
        var receivingFirstDowns = 0.0
        var receivingTds40 = 0.0
        var receivingTds50 = 0.0

        var hasRushing = false
        var carries = 0.0
        var rushingYards = 0.0
        var rushingTds = 0.0
        var rushingFirstDowns = 0.0
        var rushingTds40 = 0.0
        var rushingTds50 = 0.0
        var rzCarries = 0.0
        var gzCarries = 0.0
        var glCarries = 0.0
        var qbRushInside5 = 0.0
        var rushEpa = 0.0
        var rushSuccesses = 0.0
        var carriesEff = 0.0

        var hasPassing = false
        var attempts = 0.0
        var completions = 0.0
        var passingYards = 0.0
        var passingTds = 0.0
        var interceptions = 0.0
        var sacksTaken = 0.0
        var passingFirstDowns = 0.0
        var passingTds40 = 0.0
        var passingTds50 = 0.0
        var dropbacks = 0.0
        var passEpa = 0.0
        var cpoeSum = 0.0
        var cpoeN = 0.0

        var fumblesLost = 0.0
        var passing2pt = 0.0
        var rushing2pt = 0.0
        var receiving2pt = 0.0

        fun receiving(p: Play) {
            hasReceiving = true
            targets++
            receptions += p.completePass ?: 0.0
            receivingYards += p.receivingYards ?: 0.0
            airYards += p.airYards ?: 0.0
            yac += p.yardsAfterCatch ?: 0.0
            receivingTds += p.passTouchdown ?: 0.0
            val yl = p.yardline100
            if (yl != null && yl <= 20) rzTargets++
            // A target thrown to or past the goal line.
            val air = p.airYards
            if (air != null && yl != null && air >= yl) ezTargets++
            recEpa += p.epa ?: 0.0
            receivingFirstDowns += p.firstDownPass ?: 0.0
            if (longTd(p.passTouchdown, p.receivingYards, 40)) receivingTds40++
            if (longTd(p.passTouchdown, p.receivingYards, 50)) receivingTds50++
        }

        fun rushing(p: Play, eff: Boolean) {
            hasRushing = true
            carries++
            rushingYards += p.rushingYards ?: 0.0
            rushingTds += p.rushTouchdown ?: 0.0
            rushingFirstDowns += p.firstDownRush ?: 0.0
            if (longTd(p.rushTouchdown, p.rushingYards, 40)) rushingTds40++
            if (longTd(p.rushTouchdown, p.rushingYards, 50)) rushingTds50++
            if (!eff) return
            val yl = p.yardline100
            if (yl != null && yl <= 20) rzCarries++
            if (yl != null && yl <= 10) gzCarries++
            if (yl != null && yl <= 5) glCarries++
            // Scrambles are rushing production but not designed usage.
            if (yl != null && yl <= 5 && (p.qbScramble ?: 0.0) == 0.0) qbRushInside5++
            rushEpa += p.epa ?: 0.0
            rushSuccesses += p.success ?: 0.0
            carriesEff++
        }

        fun passing(p: Play, eff: Boolean) {
            hasPassing = true
            attempts += p.passAttempt ?: 0.0
            completions += p.completePass ?: 0.0
            passingYards += p.passingYards ?: 0.0
            passingTds += p.passTouchdown ?: 0.0
            interceptions += p.interception ?: 0.0
            sacksTaken += p.sack ?: 0.0
            passingFirstDowns += p.firstDownPass ?: 0.0
            if (longTd(p.passTouchdown, p.passingYards, 40)) passingTds40++
            if (longTd(p.passTouchdown, p.passingYards, 50)) passingTds50++
            if (!eff) return
            val scramble = if ((p.qbScramble ?: 0.0) == 1.0) 1.0 else 0.0
            dropbacks += (p.passAttempt ?: 0.0) + (p.sack ?: 0.0) + scramble
            passEpa += p.epa ?: 0.0
            p.cpoe?.let {
                cpoeSum += it
                cpoeN++
            }
        }

        fun columns(t: TeamAcc?): MutableMap<String, Double?> = linkedMapOf<String, Double?>(
            "targets" to targets, "receptions" to receptions, "receiving_yards" to receivingYards,
            "air_yards" to airYards, "yac" to yac, "receiving_tds" to receivingTds,
            "rz_targets" to rzTargets, "ez_targets" to ezTargets,
            "receiving_first_downs" to receivingFirstDowns,
            "receiving_tds_40" to receivingTds40, "receiving_tds_50" to receivingTds50,
            "rec_epa" to recEpa.takeIf { hasReceiving },
            "carries" to carries, "rushing_yards" to rushingYards, "rushing_tds" to rushingTds,
            "rushing_first_downs" to rushingFirstDowns,
            "rushing_tds_40" to rushingTds40, "rushing_tds_50" to rushingTds50,
            "rz_carries" to rzCarries, "gz_carries" to gzCarries, "gl_carries" to glCarries,
            "qb_rush_inside_5" to qbRushInside5,
            "rush_epa" to rushEpa.takeIf { hasRushing },
            "rush_successes" to rushSuccesses, "carries_eff" to carriesEff,
            "attempts" to attempts, "completions" to completions, "passing_yards" to passingYards,
            "passing_tds" to passingTds, "interceptions" to interceptions, "sacks_taken" to sacksTaken,
            "passing_first_downs" to passingFirstDowns,
            "passing_tds_40" to passingTds40, "passing_tds_50" to passingTds50,
            "dropbacks" to dropbacks,
            "pass_epa" to passEpa.takeIf { hasPassing },
            "cpoe" to (if (hasPassing && cpoeN > 0) cpoeSum / cpoeN else null),
            "cpoe_sum" to cpoeSum.takeIf { hasPassing },
            "cpoe_n" to cpoeN,
            "fumbles_lost" to fumblesLost,
            "passing_2pt" to passing2pt, "rushing_2pt" to rushing2pt, "receiving_2pt" to receiving2pt,
            "team_targets" to t?.targets, "team_air_yards" to t?.airYards, "team_carries" to t?.carries,
        )
    }
}

/** A touchdown of at least [threshold] yards. A 55-yard score counts at 40 and at 50. */
private fun longTd(td: Double?, yards: Double?, threshold: Int): Boolean =
    (td ?: 0.0) == 1.0 && (yards ?: 0.0) >= threshold
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :core:ingest:test --tests "*PlayerWeekAggregatorTest*" --tests "*ReadPlaysTest*"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add core/ingest/src
git commit -m "ingest: port play-by-play filtering and per player-week aggregation"
```

---
### Task 4: Derived rates and the long fact shape

This ports the second half of `weekly_player_stats` and `to_long`.

**Ratios**
- `ratio(num, den)` is null unless `den > 0` and both values are non-null.
- `carry_share` uses `carries_eff` as its numerator, not `carries`.

**Composites**
- `weighted_opportunities = carries_eff + 2.6 * targets`
- `total_epa = rec_epa? + rush_epa?`, where null counts as 0.
- WOPR clamps both shares to [0, 1]. The raw `air_yards_share` stays unclamped and may be negative.
- `g = 1` for every row.

**`toFacts`**
- Drops nulls.
- Drops zeros for sparse metrics.
- Emits metrics in registry order.

**Files:**
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/pbp/Derived.kt`
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Facts.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/pbp/DerivedTest.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/FactsTest.kt`

**Interfaces:**
- Consumes: `PlayerWeek`, `PlayerWeekAggregator` and `Play` (Task 3); `METRICS` and `SPARSE_METRIC_IDS` (Task 2).
- Produces:
  - `internal fun PlayerWeek.derive()`, which mutates `values`
  - `internal fun weeklyPlayerStats(plays: Iterable<Play>): List<PlayerWeek>` (package `pbp`)
  - `internal data class Fact(playerId: String, season: Int, week: Int, team: String?, metricId: String, value: Double)`
  - `internal fun toFacts(rows: List<PlayerWeek>, metricIds: List<String> = METRICS ids, sparse: Set<String> = SPARSE_METRIC_IDS): List<Fact>` (package `dev.gridiron.core.ingest`)

- [ ] **Step 1: Write the failing tests**

```kotlin
// core/ingest/src/test/kotlin/dev/gridiron/core/ingest/pbp/DerivedTest.kt
package dev.gridiron.core.ingest.pbp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DerivedTest {
    private fun run(vararg plays: Play) = weeklyPlayerStats(plays.toList())

    @Test
    fun `target share is player over team`() {
        val rows = run(target("WR1", 10.0), target("WR1", 10.0), target("WR1", 10.0), target("WR2", 10.0))
        assertEquals(0.75, rows.row("WR1")["target_share"]!!, 1e-12)
        assertEquals(0.25, rows.row("WR2")["target_share"]!!, 1e-12)
    }

    @Test
    fun `air yards share can go negative and is preserved`() {
        val rows = run(target("WR1", 20.0), target("RB1", -5.0))
        assertEquals(-5.0 / 15, rows.row("RB1")["air_yards_share"]!!, 1e-12)
        assertEquals(20.0 / 15, rows.row("WR1")["air_yards_share"]!!, 1e-12)
    }

    @Test
    fun `WOPR clamps shares so it never goes negative`() {
        val rows = run(target("WR1", 20.0), target("RB1", -5.0))
        assertEquals(1.5 * 0.5 + 0.7 * 0.0, rows.row("RB1")["wopr"]!!, 1e-12)
        assertEquals(1.5 * 0.5 + 0.7 * 1.0, rows.row("WR1")["wopr"]!!, 1e-12)
    }

    @Test
    fun `carry share`() {
        assertEquals(0.75, run(carry("RB1"), carry("RB1"), carry("RB1"), carry("RB2")).row("RB1")["carry_share"]!!, 1e-12)
    }

    @Test
    fun `aDOT, RACR and catch rate`() {
        val r = run(target("WR1", 10.0, complete = true, yds = 15.0), target("WR1", 30.0)).row("WR1")
        assertEquals(20.0, r["adot"]!!, 1e-12)
        assertEquals(15.0 / 40, r["racr"]!!, 1e-12)
        assertEquals(0.5, r["catch_rate"]!!, 1e-12)
    }

    @Test
    fun `a zero denominator yields null, not an error or a zero`() {
        val r = run(carry("RB1", 5.0)).row("RB1")
        assertNull(r["adot"])
        assertNull(r["catch_rate"])
        assertNull(r["epa_per_dropback"])
    }

    @Test
    fun `rush success rate`() {
        val r = run(carry("RB1", success = 1.0), carry("RB1"), carry("RB1", success = 1.0), carry("RB1", success = 1.0)).row("RB1")
        assertEquals(0.75, r["rush_success_rate"]!!, 1e-12)
    }

    @Test
    fun `weighted opportunities`() {
        assertEquals(2 + 2.6, run(carry("RB1"), carry("RB1"), target("RB1", 2.0)).row("RB1")["weighted_opportunities"]!!, 1e-12)
    }

    @Test
    fun `a kneel does not change success rate, EPA per carry or carry share`() {
        val rows = run(
            carry("RB1", 10.0, success = 1.0, epa = 1.0),
            carry("RB1", 5.0, success = 0.0, epa = -0.2),
            kneel("QB1", -2.0),
            carry("RB2", 3.0, success = 1.0, epa = 0.5),
        )
        val rb1 = rows.row("RB1")
        assertEquals(0.5, rb1["rush_success_rate"]!!, 1e-12)
        assertEquals((1.0 - 0.2) / 2, rb1["rush_epa_per_carry"]!!, 1e-12)
        assertEquals(2.0 / 3, rb1["carry_share"]!!, 1e-12)
    }

    @Test
    fun `a kneel is not a weighted opportunity`() {
        val r = run(carry("QB1", 2.0, yl = 3.0), kneel("QB1", -1.0, yl = 3.0), target("QB1", 2.0, qb = "QB2")).row("QB1")
        assertEquals(1 + 2.6, r["weighted_opportunities"]!!, 1e-12)
    }

    @Test
    fun `a spike does not dilute EPA per dropback`() {
        val r = run(target("WR1", 10.0, complete = true, yds = 10.0, epa = 0.8), spike("QB1")).row("QB1")
        assertEquals(0.8, r["epa_per_dropback"]!!, 1e-12)
    }

    @Test
    fun `every player-week gets one game and a total EPA`() {
        val rows = run(target("WR1", 5.0, epa = 0.3), carry("RB1", epa = -0.1))
        assertTrue(rows.all { it.values["g"] == 1.0 })
        assertEquals(0.3, rows.row("WR1")["total_epa"]!!, 1e-12)
        assertEquals(0.0, rows.row("QB1")["total_epa"]!!, 1e-12)
    }
}
```

```kotlin
// core/ingest/src/test/kotlin/dev/gridiron/core/ingest/FactsTest.kt
package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.pbp.carry
import dev.gridiron.core.ingest.pbp.kneel
import dev.gridiron.core.ingest.pbp.target
import dev.gridiron.core.ingest.pbp.weeklyPlayerStats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FactsTest {
    private fun value(facts: List<Fact>, pid: String, metric: String): Double? =
        facts.singleOrNull { it.playerId == pid && it.metricId == metric }?.value

    @Test
    fun `nulls are dropped and values kept`() {
        val facts = toFacts(weeklyPlayerStats(listOf(carry("RB1", 5.0))), listOf("carries", "adot"), emptySet())
        assertTrue(facts.any { it.metricId == "carries" })
        assertFalse(facts.any { it.metricId == "adot" })
    }

    @Test
    fun `sparse metrics drop zeros, others keep them`() {
        val facts = toFacts(weeklyPlayerStats(listOf(target("WR1", 10.0), carry("RB1", 3.0))), listOf("targets", "fumbles_lost"), setOf("fumbles_lost"))
        assertTrue(facts.none { it.metricId == "fumbles_lost" })
        assertEquals(0.0, value(facts, "RB1", "targets"))
    }

    @Test
    fun `carries_eff is stored and excludes kneels`() {
        val facts = toFacts(weeklyPlayerStats(listOf(carry("RB1", 5.0), kneel("QB1", -2.0))))
        assertEquals(1.0, value(facts, "QB1", "carries"))
        assertEquals(0.0, value(facts, "QB1", "carries_eff"))
        assertEquals(1.0, value(facts, "RB1", "carries_eff"))
    }

    @Test
    fun `the default metric list stores only registered metrics`() {
        val facts = toFacts(weeklyPlayerStats(listOf(target("WR1", 10.0))))
        val registered = METRICS.map { it.id }.toSet()
        assertTrue(facts.all { it.metricId in registered })
        assertNull(value(facts, "WR1", "team_plays"))
        assertEquals("AAA", facts.first().team)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:ingest:test --tests "*DerivedTest*" --tests "*FactsTest*"`
Expected: FAIL, because `weeklyPlayerStats`, `toFacts` and `Fact` are unresolved.

- [ ] **Step 3: Implement**

```kotlin
// core/ingest/src/main/kotlin/dev/gridiron/core/ingest/pbp/Derived.kt
package dev.gridiron.core.ingest.pbp

/** Adds the rate and composite columns `transform.weekly_player_stats` derives after its joins. */
internal fun PlayerWeek.derive() {
    val v = values
    // Guarded division: a zero or missing denominator yields null, not an error or a zero.
    fun ratio(num: String, den: String): Double? {
        val d = v[den] ?: return null
        val n = v[num] ?: return null
        return if (d > 0) n / d else null
    }

    v["g"] = 1.0
    val targetShare = ratio("targets", "team_targets")
    val airYardsShare = ratio("air_yards", "team_air_yards")
    v["target_share"] = targetShare
    v["air_yards_share"] = airYardsShare
    // carries_eff (kneels excluded), so the numerator matches team_carries.
    v["carry_share"] = ratio("carries_eff", "team_carries")
    v["adot"] = ratio("air_yards", "targets")
    v["racr"] = ratio("receiving_yards", "air_yards")
    v["catch_rate"] = ratio("receptions", "targets")
    v["rush_success_rate"] = ratio("rush_successes", "carries_eff")
    v["rush_epa_per_carry"] = ratio("rush_epa", "carries_eff")
    v["epa_per_dropback"] = ratio("pass_epa", "dropbacks")
    v["weighted_opportunities"] = (v["carries_eff"] ?: 0.0) + 2.6 * (v["targets"] ?: 0.0)
    v["total_epa"] = (v["rec_epa"] ?: 0.0) + (v["rush_epa"] ?: 0.0)
    // WOPR's coefficients assume shares in [0, 1]; clamp for this composite only.
    v["wopr"] = 1.5 * (targetShare ?: 0.0).coerceIn(0.0, 1.0) + 0.7 * (airYardsShare ?: 0.0).coerceIn(0.0, 1.0)
}

/** Play-by-play to weekly player stats with every derived column. */
internal fun weeklyPlayerStats(plays: Iterable<Play>): List<PlayerWeek> {
    val aggregator = PlayerWeekAggregator()
    plays.forEach(aggregator::add)
    return aggregator.rows().onEach { it.derive() }
}
```

```kotlin
// core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Facts.kt
package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.pbp.PlayerWeek

/** One row of `player_week_stat`. */
internal data class Fact(
    val playerId: String,
    val season: Int,
    val week: Int,
    val team: String?,
    val metricId: String,
    val value: Double,
)

/**
 * Unpivots to the long fact shape the database stores, like `transform.to_long`:
 * nulls are dropped, and zeros are dropped for [sparse] metrics (absent means zero).
 */
internal fun toFacts(
    rows: List<PlayerWeek>,
    metricIds: List<String> = METRICS.map { it.id },
    sparse: Set<String> = SPARSE_METRIC_IDS,
): List<Fact> = buildList {
    for (row in rows) {
        for (id in metricIds) {
            val value = row.values[id] ?: continue
            if (value == 0.0 && id in sparse) continue
            add(Fact(row.playerId, row.season, row.week, row.team, id, value))
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:ingest:test --tests "*DerivedTest*" --tests "*FactsTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/ingest/src
git commit -m "ingest: derive rate metrics and unpivot to facts"
```

---

### Task 5: Upstream sources and an HTTP fetcher that asks "changed since?"

GitHub release downloads redirect (302) to a file host, and that host answers `304 Not Modified` to `If-None-Match` or `If-Modified-Since` (verified against `injuries_2025.csv.gz`). The fetcher follows redirects itself so those headers reach every hop. It writes to `<dest>.part` and renames only after the whole body arrived, so an interrupted download leaves nothing behind (Review Focus 1).

**Files:**
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Sources.kt`
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Fetcher.kt`
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/HttpFetcher.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/SourcesTest.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/HttpFetcherTest.kt`

**Interfaces:**
- Produces (package `dev.gridiron.core.ingest`, all public):
  - `enum class Input(perSeason: Boolean, label: String) { PBP, SNAP_COUNTS, INJURIES, EXPECTED, PLAYERS }`
  - `object Sources { fun url(input, season: Int? = null): String; fun fileName(input, season: Int? = null): String; fun metaKey(input, season: Int? = null): String }`
  - `data class Validators(etag: String?, lastModified: String?)` with `encode(): String` and `Validators.decode(text): Validators?`
  - `sealed interface FetchResult { data class Downloaded(file, validators, bytes); data object NotModified; data object NotPublished }`
  - `fun interface Fetcher { suspend fun fetch(url: String, dest: File, previous: Validators?, onBytes: (read: Long, total: Long) -> Unit): FetchResult }`
  - `class HttpFetcher(connectTimeoutMs: Int = 15_000, readTimeoutMs: Int = 60_000) : Fetcher`

- [ ] **Step 1: Write the failing tests**

```kotlin
// core/ingest/src/test/kotlin/dev/gridiron/core/ingest/SourcesTest.kt
package dev.gridiron.core.ingest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SourcesTest {
    @Test
    fun `urls point at nflverse and ffopportunity, never at this repository`() {
        assertEquals("https://github.com/nflverse/nflverse-data/releases/download/pbp/play_by_play_2025.csv.gz", Sources.url(Input.PBP, 2025))
        assertEquals("https://github.com/nflverse/nflverse-data/releases/download/snap_counts/snap_counts_2025.csv.gz", Sources.url(Input.SNAP_COUNTS, 2025))
        assertEquals("https://github.com/nflverse/nflverse-data/releases/download/injuries/injuries_2025.csv.gz", Sources.url(Input.INJURIES, 2025))
        assertEquals("https://github.com/ffverse/ffopportunity/releases/download/latest-data/ep_weekly_2025.csv", Sources.url(Input.EXPECTED, 2025))
        assertEquals("https://github.com/nflverse/nflverse-data/releases/download/players/players.csv.gz", Sources.url(Input.PLAYERS))
    }

    @Test
    fun `file names and meta keys`() {
        assertEquals("play_by_play_2024.csv.gz", Sources.fileName(Input.PBP, 2024))
        assertEquals("source:players.csv.gz", Sources.metaKey(Input.PLAYERS))
    }

    @Test
    fun `validators survive encoding`() {
        val v = Validators("\"0x8DF\"", "Thu, 13 Aug 2026 12:26:09 GMT")
        assertEquals(v, Validators.decode(v.encode()))
        assertEquals(Validators("\"e\"", null), Validators.decode(Validators("\"e\"", null).encode()))
        assertNull(Validators.decode("\n"))
    }
}
```

```kotlin
// core/ingest/src/test/kotlin/dev/gridiron/core/ingest/HttpFetcherTest.kt
package dev.gridiron.core.ingest

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Collections

class HttpFetcherTest {
    @TempDir
    lateinit var dir: File

    private lateinit var server: HttpServer
    private val body = "a,b\n1,2\n".repeat(20_000).toByteArray()
    private val seenOnFile: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private val fetcher = HttpFetcher()

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file") { ex ->
            val tag = ex.requestHeaders.getFirst("If-None-Match")
            seenOnFile += tag
            if (tag == "\"v1\"") {
                ex.sendResponseHeaders(304, -1)
            } else {
                ex.responseHeaders.add("ETag", "\"v1\"")
                ex.responseHeaders.add("Last-Modified", "Thu, 13 Aug 2026 12:26:09 GMT")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            ex.close()
        }
        server.createContext("/redirect") { ex ->
            ex.responseHeaders.add("Location", "/file")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/missing") { ex ->
            ex.sendResponseHeaders(404, -1)
            ex.close()
        }
        server.createContext("/broken") { ex ->
            ex.sendResponseHeaders(500, -1)
            ex.close()
        }
        server.createContext("/short") { ex ->
            // Promises 1000 bytes, sends 10, hangs up: a dropped connection.
            ex.sendResponseHeaders(200, 1000)
            runCatching {
                ex.responseBody.write(ByteArray(10))
                ex.responseBody.flush()
            }
            ex.close()
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    @Test
    fun `downloads the file and returns its validators`() = runTest {
        val dest = File(dir, "x.csv")
        var last = 0L
        val result = fetcher.fetch(url("/file"), dest, previous = null) { read, _ -> last = read }
        result as FetchResult.Downloaded
        assertArrayEquals(body, dest.readBytes())
        assertEquals(Validators("\"v1\"", "Thu, 13 Aug 2026 12:26:09 GMT"), result.validators)
        assertEquals(body.size.toLong(), result.bytes)
        assertEquals(body.size.toLong(), last)
    }

    @Test
    fun `an unchanged file is not downloaded again`() = runTest {
        val dest = File(dir, "x.csv")
        val result = fetcher.fetch(url("/file"), dest, Validators("\"v1\"", null)) { _, _ -> }
        assertEquals(FetchResult.NotModified, result)
        assertFalse(dest.exists())
    }

    @Test
    fun `redirects carry the conditional headers to the final host`() = runTest {
        val result = fetcher.fetch(url("/redirect"), File(dir, "x.csv"), Validators("\"v1\"", null)) { _, _ -> }
        assertEquals(FetchResult.NotModified, result)
        assertEquals(listOf<String?>("\"v1\""), seenOnFile.toList())
    }

    @Test
    fun `a 404 means not published and leaves the destination alone`() = runTest {
        val dest = File(dir, "x.csv").apply { writeText("old") }
        assertEquals(FetchResult.NotPublished, fetcher.fetch(url("/missing"), dest, null) { _, _ -> })
        assertEquals("old", dest.readText())
    }

    @Test
    fun `a server error fails`() = runTest {
        val e = runCatching { fetcher.fetch(url("/broken"), File(dir, "x.csv"), null) { _, _ -> } }.exceptionOrNull()
        assertTrue(e is IOException, "got $e")
    }

    @Test
    fun `an interrupted download leaves nothing behind`() = runTest {
        val dest = File(dir, "x.csv")
        val e = runCatching { fetcher.fetch(url("/short"), dest, null) { _, _ -> } }.exceptionOrNull()
        assertTrue(e is IOException, "got $e")
        assertFalse(dest.exists())
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:ingest:test --tests "*SourcesTest*" --tests "*HttpFetcherTest*"`
Expected: FAIL, because `Sources`, `Validators` and `HttpFetcher` are unresolved.

- [ ] **Step 3: Implement**

```kotlin
// core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Sources.kt
package dev.gridiron.core.ingest

/** An upstream file the build reads. */
public enum class Input(public val perSeason: Boolean, public val label: String) {
    PBP(true, "play-by-play"),
    SNAP_COUNTS(true, "snap counts"),
    INJURIES(true, "injury reports"),
    EXPECTED(true, "expected points"),
    PLAYERS(false, "player list"),
}

/** Where each input lives: public nflverse and ffopportunity release assets, never this app's repository. */
public object Sources {
    private const val NFLVERSE = "https://github.com/nflverse/nflverse-data/releases/download"
    private const val FFOPPORTUNITY = "https://github.com/ffverse/ffopportunity/releases/download/latest-data"

    public fun url(input: Input, season: Int? = null): String {
        require(input.perSeason == (season != null)) { "$input: season must be given exactly when the input is per season" }
        return when (input) {
            Input.PBP -> "$NFLVERSE/pbp/play_by_play_$season.csv.gz"
            Input.SNAP_COUNTS -> "$NFLVERSE/snap_counts/snap_counts_$season.csv.gz"
            Input.INJURIES -> "$NFLVERSE/injuries/injuries_$season.csv.gz"
            // ffopportunity publishes no gzip variant of this file.
            Input.EXPECTED -> "$FFOPPORTUNITY/ep_weekly_$season.csv"
            Input.PLAYERS -> "$NFLVERSE/players/players.csv.gz"
        }
    }

    public fun fileName(input: Input, season: Int? = null): String = url(input, season).substringAfterLast('/')

    /** The `schema_meta` key under which a build records which version of this file it read. */
    public fun metaKey(input: Input, season: Int? = null): String = "source:${fileName(input, season)}"
}
```

```kotlin
// core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Fetcher.kt
package dev.gridiron.core.ingest

import java.io.File

/** An HTTP response's version tags, sent back later to ask "changed since?". */
public data class Validators(public val etag: String?, public val lastModified: String?) {
    public fun encode(): String = "${etag.orEmpty()}\n${lastModified.orEmpty()}"

    public companion object {
        public fun decode(text: String): Validators? {
            val parts = text.split('\n', limit = 2)
            val etag = parts[0].ifEmpty { null }
            val lastModified = parts.getOrElse(1) { "" }.ifEmpty { null }
            return if (etag == null && lastModified == null) null else Validators(etag, lastModified)
        }
    }
}

public sealed interface FetchResult {
    public data class Downloaded(public val file: File, public val validators: Validators, public val bytes: Long) : FetchResult
    public data object NotModified : FetchResult

    /** 404: the file doesn't exist yet, e.g. a season before kickoff. */
    public data object NotPublished : FetchResult
}

public fun interface Fetcher {
    /**
     * Downloads [url] to [dest] unless [previous] still describes the server's
     * copy. [dest] only ever appears complete: an interrupted download leaves
     * nothing behind and throws.
     */
    public suspend fun fetch(
        url: String,
        dest: File,
        previous: Validators?,
        onBytes: (read: Long, total: Long) -> Unit,
    ): FetchResult
}
```

```kotlin
// core/ingest/src/main/kotlin/dev/gridiron/core/ingest/HttpFetcher.kt
package dev.gridiron.core.ingest

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * [Fetcher] over HttpURLConnection. Follows redirects itself so the "changed
 * since?" headers reach every hop: GitHub release downloads redirect to a file
 * host, and that host is the one that answers 304.
 */
public class HttpFetcher(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : Fetcher {

    override suspend fun fetch(
        url: String,
        dest: File,
        previous: Validators?,
        onBytes: (read: Long, total: Long) -> Unit,
    ): FetchResult = withContext(Dispatchers.IO) {
        var location = URI(url)
        repeat(MAX_REDIRECTS + 1) {
            val conn = (location.toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                // Stored bytes with a true Content-Length; .gz assets stay compressed.
                setRequestProperty("Accept-Encoding", "identity")
                previous?.etag?.let { setRequestProperty("If-None-Match", it) }
                previous?.lastModified?.let { setRequestProperty("If-Modified-Since", it) }
            }
            try {
                val code = conn.responseCode
                when {
                    code == HttpURLConnection.HTTP_NOT_MODIFIED -> return@withContext FetchResult.NotModified
                    code in 300..399 -> {
                        val next = conn.getHeaderField("Location")
                            ?: throw IOException("HTTP $code without a Location from $location")
                        location = location.resolve(next)
                    }
                    code == HttpURLConnection.HTTP_NOT_FOUND -> return@withContext FetchResult.NotPublished
                    code == HttpURLConnection.HTTP_OK -> return@withContext save(conn, dest, onBytes)
                    else -> throw IOException("HTTP $code from $location")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("too many redirects from $url")
    }

    private fun CoroutineScope.save(
        conn: HttpURLConnection,
        dest: File,
        onBytes: (read: Long, total: Long) -> Unit,
    ): FetchResult {
        val total = conn.contentLengthLong
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        var read = 0L
        try {
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        onBytes(read, total)
                    }
                }
            }
            if (total >= 0 && read != total) throw IOException("${dest.name} stopped at $read of $total bytes")
            dest.delete()
            if (!part.renameTo(dest)) throw IOException("couldn't move ${dest.name} into place")
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
        val validators = Validators(conn.getHeaderField("ETag"), conn.getHeaderField("Last-Modified"))
        return FetchResult.Downloaded(dest, validators, read)
    }

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:ingest:test --tests "*SourcesTest*" --tests "*HttpFetcherTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/ingest/src
git commit -m "ingest: upstream sources and a conditional-GET HTTP fetcher"
```

---
### Task 6: Early phone timing — "Time a stats build" menu item

The spec calls for measuring on-device crunch time early, before the whole port lands. This task adds a benchmark to `:core:ingest` and a ☰ menu item to the app. The benchmark downloads one full season of play-by-play (the heaviest input, about 19 MB) and streams it into weekly player stats, then reports how long each half took. It keeps nothing. Plan 2 replaces the menu item with the real refresh.

**Checkpoint (non-blocking):** once this commit reaches CI, the published APK has the menu item. The controller tells the user to install it, tap **☰ → Time a stats build**, and report the numbers. Execution does not wait. If crunch time exceeds about 60 s, record it for the Plan 2 design.

**Files:**
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Benchmark.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/Fixtures.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/FakeFetcher.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/BenchmarkTest.kt`
- Modify: `app/build.gradle.kts` (add the dependency)
- Modify: `app/src/main/kotlin/dev/gridiron/app/GridironApplication.kt`
- Modify: `app/src/main/kotlin/dev/gridiron/app/GridironNavHost.kt`

**Interfaces:**
- Consumes: `Fetcher`, `FetchResult`, `Sources`, `Input` (Task 5); `readPlays` and `PlayerWeekAggregator` (Task 3); `derive` (Task 4); `openInput` (Task 1).
- Produces:
  - `public data class BenchmarkResult(season: Int, bytes: Long, downloadMs: Long, crunchMs: Long, plays: Int, playerWeeks: Int)`
  - `public suspend fun benchmarkSeason(fetcher: Fetcher, season: Int, workDir: File): BenchmarkResult`
  - `public fun currentSeason(today: LocalDate = LocalDate.now()): Int`
  - Test helpers reused by Tasks 7–11:
    - `Fixtures.csv(header, rows)`, `Fixtures.gzip(text)`, `Fixtures.pbp(vararg overrides)` and `Fixtures.pbpCsv(rows)`
    - `FakeFetcher` with `serve(url, bytes, version)`, `calls` and `onFetch`
  - App: `Deps.benchmark: (suspend () -> Result<String>)? = null`

- [ ] **Step 1: Write the shared test helpers**

```kotlin
// core/ingest/src/test/kotlin/dev/gridiron/core/ingest/Fixtures.kt
package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.pbp.PBP_COLUMNS
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

internal object Fixtures {
    fun csv(header: List<String>, rows: List<Map<String, Any?>>): String = buildString {
        append(header.joinToString(",")).append('\n')
        for (row in rows) append(header.joinToString(",") { cell(row[it]) }).append('\n')
    }

    private fun cell(value: Any?): String {
        val s = value?.toString() ?: return ""
        return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun gzip(text: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(text.toByteArray()) }
        return bytes.toByteArray()
    }

    private val PBP_DEFAULTS: Map<String, Any?> = mapOf(
        "season" to 2025, "week" to 1, "season_type" to "REG", "game_id" to "g1",
        "posteam" to "AAA", "defteam" to "BBB", "play_type" to "pass", "pass_attempt" to 0,
        "complete_pass" to 0, "yards_gained" to 0, "pass_touchdown" to 0, "rush_touchdown" to 0,
        "interception" to 0, "sack" to 0, "qb_scramble" to 0, "yardline_100" to 50, "epa" to 0.0,
        "success" to 0, "two_point_attempt" to 0, "first_down_pass" to 0, "first_down_rush" to 0,
        "fumble_lost" to 0, "touchdown" to 0, "home_team" to "AAA", "away_team" to "BBB",
        "total_home_score" to 0, "total_away_score" to 0,
    )

    /** A play-by-play row with the Python tests' neutral defaults. */
    fun pbp(vararg overrides: Pair<String, Any?>): Map<String, Any?> = PBP_DEFAULTS + overrides

    fun pbpCsv(rows: List<Map<String, Any?>>): String = csv(PBP_COLUMNS, rows)
}
```

```kotlin
// core/ingest/src/test/kotlin/dev/gridiron/core/ingest/FakeFetcher.kt
package dev.gridiron.core.ingest

import java.io.File

/** Serves canned bytes by URL; unknown URLs are 404s. Records every call. */
internal class FakeFetcher : Fetcher {
    private val files = mutableMapOf<String, Pair<ByteArray, Validators>>()
    val calls = mutableListOf<Pair<String, Validators?>>()
    var onFetch: suspend (url: String) -> Unit = {}

    fun serve(url: String, bytes: ByteArray, version: String) {
        files[url] = bytes to Validators("\"$version\"", null)
    }

    fun remove(url: String) {
        files.remove(url)
    }

    override suspend fun fetch(
        url: String,
        dest: File,
        previous: Validators?,
        onBytes: (read: Long, total: Long) -> Unit,
    ): FetchResult {
        calls += url to previous
        onFetch(url)
        val (bytes, validators) = files[url] ?: return FetchResult.NotPublished
        if (previous == validators) return FetchResult.NotModified
        dest.parentFile?.mkdirs()
        dest.writeBytes(bytes)
        onBytes(bytes.size.toLong(), bytes.size.toLong())
        return FetchResult.Downloaded(dest, validators, bytes.size.toLong())
    }
}
```

- [ ] **Step 2: Write the failing tests**

```kotlin
// core/ingest/src/test/kotlin/dev/gridiron/core/ingest/BenchmarkTest.kt
package dev.gridiron.core.ingest

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

class BenchmarkTest {
    @Test
    fun `times a season's download and crunch and keeps nothing`(@TempDir dir: File) = runTest {
        val fetcher = FakeFetcher()
        val plays = listOf(
            Fixtures.pbp("receiver_player_id" to "WR1", "passer_player_id" to "QB1", "pass_attempt" to 1, "air_yards" to 10),
            Fixtures.pbp("play_type" to "run", "rusher_player_id" to "RB1", "rushing_yards" to 4),
            Fixtures.pbp("play_type" to "kickoff"),
        )
        fetcher.serve(Sources.url(Input.PBP, 2025), Fixtures.gzip(Fixtures.pbpCsv(plays)), "v1")

        val result = benchmarkSeason(fetcher, 2025, dir)

        assertEquals(2025, result.season)
        assertEquals(3, result.plays)
        assertEquals(3, result.playerWeeks) // WR1, QB1 and RB1; the kickoff is filtered out
        assertTrue(result.bytes > 0)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun `an unpublished season fails with a clear message`(@TempDir dir: File) = runTest {
        val e = runCatching { benchmarkSeason(FakeFetcher(), 2030, dir) }.exceptionOrNull()
        assertTrue(e?.message.orEmpty().contains("2030 play-by-play isn't published"), "got $e")
    }

    @Test
    fun `a season is current from September`() {
        assertEquals(2026, currentSeason(LocalDate.of(2026, 9, 1)))
        assertEquals(2025, currentSeason(LocalDate.of(2026, 8, 31)))
        assertEquals(2025, currentSeason(LocalDate.of(2026, 1, 15)))
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :core:ingest:test --tests "*BenchmarkTest*"`
Expected: FAIL, because `benchmarkSeason` and `currentSeason` are unresolved.

- [ ] **Step 4: Implement the benchmark**

```kotlin
// core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Benchmark.kt
package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.csv.openInput
import dev.gridiron.core.ingest.pbp.PlayerWeekAggregator
import dev.gridiron.core.ingest.pbp.derive
import dev.gridiron.core.ingest.pbp.readPlays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

public data class BenchmarkResult(
    public val season: Int,
    public val bytes: Long,
    public val downloadMs: Long,
    public val crunchMs: Long,
    public val plays: Int,
    public val playerWeeks: Int,
)

/**
 * Times the expensive half of an on-device build for one season: downloading
 * its play-by-play and streaming it into weekly player stats. Nothing is kept.
 */
public suspend fun benchmarkSeason(fetcher: Fetcher, season: Int, workDir: File): BenchmarkResult =
    withContext(Dispatchers.IO) {
        workDir.mkdirs()
        val dest = File(workDir, Sources.fileName(Input.PBP, season))
        try {
            val start = System.nanoTime()
            val fetched = fetcher.fetch(Sources.url(Input.PBP, season), dest, previous = null) { _, _ -> }
            check(fetched is FetchResult.Downloaded) { "$season play-by-play isn't published" }
            val downloaded = System.nanoTime()
            var plays = 0
            val aggregator = PlayerWeekAggregator()
            openInput(dest).use { input ->
                readPlays(input, dest.name) {
                    plays++
                    aggregator.add(it)
                }
            }
            val weeks = aggregator.rows().onEach { it.derive() }.size
            val done = System.nanoTime()
            BenchmarkResult(season, dest.length(), (downloaded - start) / 1_000_000, (done - downloaded) / 1_000_000, plays, weeks)
        } finally {
            dest.delete()
        }
    }

/** The NFL season in progress on [today]: a season is current from September. */
public fun currentSeason(today: LocalDate = LocalDate.now()): Int =
    if (today.monthValue >= 9) today.year else today.year - 1
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:ingest:test --tests "*BenchmarkTest*"`
Expected: PASS.

- [ ] **Step 6: Wire the menu item into the app**

In `app/build.gradle.kts`, add `implementation(projects.core.ingest)` after `implementation(projects.core.database)`.

In `app/src/main/kotlin/dev/gridiron/app/GridironNavHost.kt`:

1. Add a field to `Deps`, after `refresh`:

```kotlin
    /** Times downloading and crunching last season's play-by-play on this phone; null in tests. */
    val benchmark: (suspend () -> Result<String>)? = null,
```

2. Add these imports:

```kotlin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
```

3. Inside `GridironNavHost`, directly after the `val refresh: () -> Unit = { ... }` block, add:

```kotlin
    var timing by remember { mutableStateOf<String?>(null) }
    val timeBuild: () -> Unit = {
        deps.benchmark?.let { run ->
            Toast.makeText(context, "Timing a stats build… about a minute", Toast.LENGTH_SHORT).show()
            scope.launch { timing = run().getOrElse { "Timing failed: ${it.message}" } }
        }
    }
    timing?.let { text ->
        AlertDialog(
            onDismissRequest = { timing = null },
            confirmButton = { TextButton(onClick = { timing = null }) { Text("OK") } },
            title = { Text("Stats build timing") },
            text = { Text(text) },
        )
    }
```

4. Add a menu entry after `"Refresh stats" to { _: Int -> refresh() },`:

```kotlin
                        "Time a stats build" to { _: Int -> timeBuild() },
```

In `app/src/main/kotlin/dev/gridiron/app/GridironApplication.kt`:

1. Add these imports:

```kotlin
import dev.gridiron.core.ingest.HttpFetcher
import dev.gridiron.core.ingest.benchmarkSeason
import dev.gridiron.core.ingest.currentSeason
```

2. Pass the benchmark into `Deps(...)`, after `refresh = { StatsDbInstaller(this).refresh() },`:

```kotlin
            benchmark = { benchmark() },
```

3. Add this member function to the class:

```kotlin
    /** Last complete season: the worst case for a single season's build. */
    private suspend fun benchmark(): Result<String> = runCatching {
        val r = benchmarkSeason(HttpFetcher(), currentSeason() - 1, File(cacheDir, "benchmark"))
        "${r.season} play-by-play: ${"%.1f".format(r.bytes / 1e6)} MB downloaded in ${r.downloadMs / 1000.0} s, " +
            "${r.plays} plays crunched into ${r.playerWeeks} player-weeks in ${r.crunchMs / 1000.0} s."
    }
```

- [ ] **Step 7: Verify the app still builds and its tests pass**

Run: `./gradlew :core:ingest:test :app:testDebugUnitTest :app:assembleRelease`
Expected: BUILD SUCCESSFUL. `NavigationTest` constructs `Deps` with named arguments, so the new defaulted field needs no change there.

- [ ] **Step 8: Commit**

```bash
git add core/ingest/src app/build.gradle.kts app/src/main/kotlin/dev/gridiron/app/GridironApplication.kt app/src/main/kotlin/dev/gridiron/app/GridironNavHost.kt
git commit -m "ingest: benchmark a season's build and expose it on the phone's menu"
```

---
### Task 7: Snap share

This ports `team_offense_snaps` and `add_snap_share`.

**Team offensive snaps per (game, team):**
- Solve for the integer `D` from `max(offense_snaps)` to `max + 25` that is consistent with every published percentage.
- Choose the `D` with the fewest rows where `|snaps / D − pct| > 0.0051`, then the least squared error, then the smallest `D`.
- Only rows with `offense_snaps > 0` take part.

**Attaching to player-weeks:**
- Snap rows key on PFR ids, so each routes through the players crosswalk (PFR id → gsis ids) to the (season, week, player) row.
- An unmatched row keeps no snap data. It is never dropped.
- The row gets `offense_snaps`, `team_offense_snaps` and `snap_share` (the published `offense_pct`).

**Files:**
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Snaps.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/SnapsTest.kt`

**Interfaces:**
- Consumes: `readCsv` (Task 1); `PlayerWeek` (Task 3); `weeklyPlayerStats` (Task 4, in tests).
- Produces (package `dev.gridiron.core.ingest`):
  - `internal data class SnapRow(gameId: String, season: Int, week: Int, team: String, pfrPlayerId: String?, offenseSnaps: Double?, offensePct: Double?)`
  - `internal fun readSnaps(input: InputStream, source: String): List<SnapRow>`
  - `internal fun teamOffenseSnaps(snaps: List<SnapRow>): Map<Pair<String, String>, Int>`, keyed by (game id, team)
  - `internal fun attachSnapShare(rows: List<PlayerWeek>, snaps: List<SnapRow>, crosswalk: Map<String, List<String>>)`

- [ ] **Step 1: Write the failing tests** (ported from `etl/tests/test_transform.py`)

```kotlin
// core/ingest/src/test/kotlin/dev/gridiron/core/ingest/SnapsTest.kt
package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.pbp.carry
import dev.gridiron.core.ingest.pbp.row
import dev.gridiron.core.ingest.pbp.target
import dev.gridiron.core.ingest.pbp.weeklyPlayerStats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SnapsTest {
    private fun snap(pfr: String, snaps: Double, pct: Double, team: String = "AAA", game: String = "g1") =
        SnapRow(game, 2025, 1, team, pfr, snaps, pct)

    private val crosswalk = mapOf("pWR1" to listOf("WR1"), "pRB1" to listOf("RB1"))

    @Test
    fun `team snaps are solved when nobody plays every snap`() {
        // 2025 SF week 11 ran 55 plays; the most any player logged was 53.
        val snaps = listOf(53.0 to 0.96, 50.0 to 0.91, 48.0 to 0.87, 39.0 to 0.71)
            .mapIndexed { i, (s, p) -> SnapRow("g", 2025, 11, "SF", "p$i", s, p) }
        assertEquals(mapOf(("g" to "SF") to 55), teamOffenseSnaps(snaps))
    }

    @Test
    fun `the snap join does not inflate or drop rows`() {
        val weekly = weeklyPlayerStats(listOf(target("WR1", 10.0), carry("RB1", 3.0)))
        val before = weekly.size
        attachSnapShare(weekly, listOf(snap("pWR1", 50.0, 0.8), snap("pRB1", 30.0, 0.5)), crosswalk)
        assertEquals(before, weekly.size)
        assertEquals(0.8, weekly.row("WR1")["snap_share"])
        assertEquals(50.0, weekly.row("WR1")["offense_snaps"])
    }

    @Test
    fun `unmapped snaps leave the player without snap data`() {
        val weekly = weeklyPlayerStats(listOf(target("WR1", 10.0)))
        attachSnapShare(weekly, listOf(snap("unknown", 50.0, 0.8)), crosswalk)
        assertEquals(2, weekly.size) // receiver and passer rows both survive
        assertNull(weekly.row("WR1")["snap_share"])
    }

    @Test
    fun `team offensive snaps come from the player's own team that game`() {
        val weekly = weeklyPlayerStats(listOf(target("WR1", 10.0), carry("RB1", 3.0)))
        val snaps = listOf(
            snap("pWR1", 50.0, 0.78), snap("pRB1", 30.0, 0.47), snap("pOL1", 64.0, 1.0),
            snap("pOTHER", 80.0, 1.0, team = "BBB"),
        )
        attachSnapShare(weekly, snaps, crosswalk)
        assertEquals(64.0, weekly.row("WR1")["team_offense_snaps"])
        assertEquals(64.0, weekly.row("RB1")["team_offense_snaps"])
    }

    @Test
    fun `reads the snap counts file`() {
        val csv = "game_id,pfr_game_id,season,game_type,week,player,pfr_player_id,position,team,opponent,offense_snaps,offense_pct\n" +
            "2025_01_ARI_NO,x,2025,REG,1,Kelvin Banks,BankKe01,T,NO,ARI,75,1\n"
        assertEquals(
            listOf(SnapRow("2025_01_ARI_NO", 2025, 1, "NO", "BankKe01", 75.0, 1.0)),
            readSnaps(csv.byteInputStream(), "snap_counts_2025.csv.gz"),
        )
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:ingest:test --tests "*SnapsTest*"`
Expected: FAIL, because `SnapRow`, `teamOffenseSnaps` and `attachSnapShare` are unresolved.

- [ ] **Step 3: Implement**

```kotlin
// core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Snaps.kt
package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.csv.readCsv
import dev.gridiron.core.ingest.pbp.PlayerWeek
import java.io.InputStream
import kotlin.math.abs

internal data class SnapRow(
    val gameId: String,
    val season: Int,
    val week: Int,
    val team: String,
    val pfrPlayerId: String?,
    val offenseSnaps: Double?,
    val offensePct: Double?,
)

private val SNAP_COLUMNS = listOf("game_id", "season", "week", "team", "pfr_player_id", "offense_snaps", "offense_pct")

internal fun readSnaps(input: InputStream, source: String): List<SnapRow> = buildList {
    readCsv(input, source, SNAP_COLUMNS) { row ->
        val gameId = row.text("game_id") ?: return@readCsv
        val season = row.int("season") ?: return@readCsv
        val week = row.int("week") ?: return@readCsv
        val team = row.text("team") ?: return@readCsv
        add(SnapRow(gameId, season, week, team, row.text("pfr_player_id"), row.double("offense_snaps"), row.double("offense_pct")))
    }
}

/** Candidate offsets above the observed max when solving for team snaps. */
private const val SNAP_SEARCH = 25

/** Half of the published 0.01 rounding step, plus float slack. */
private const val PCT_TOLERANCE = 0.0051

/**
 * Each team's offensive snaps in each game. Not simply the max any player
 * logged (sometimes nobody plays every snap), nor one back-solve of
 * snaps / pct (the two-decimal rounding makes that ambiguous): the integer D,
 * at or above the observed max, most consistent with every published
 * percentage. Ties go to the least squared error, then the smallest D.
 */
internal fun teamOffenseSnaps(snaps: List<SnapRow>): Map<Pair<String, String>, Int> =
    snaps.filter { (it.offenseSnaps ?: 0.0) > 0 }
        .groupBy { it.gameId to it.team }
        .mapValues { (_, played) ->
            val max = played.maxOf { it.offenseSnaps!! }.toInt()
            (max..max + SNAP_SEARCH).minWith(
                compareBy<Int>(
                    { d -> played.count { (pctError(it, d) ?: 0.0) > PCT_TOLERANCE } },
                    { d -> played.sumOf { row -> pctError(row, d)?.let { it * it } ?: 0.0 } },
                    { it },
                ),
            )
        }

private fun pctError(row: SnapRow, d: Int): Double? = row.offensePct?.let { abs(row.offenseSnaps!! / d - it) }

/**
 * Attaches snap counts to [rows] in place. Snap counts key on PFR ids, so they
 * route through [crosswalk] (PFR id to gsis ids). A row nothing maps to keeps
 * no snap data rather than being dropped.
 */
internal fun attachSnapShare(rows: List<PlayerWeek>, snaps: List<SnapRow>, crosswalk: Map<String, List<String>>) {
    val teamSnaps = teamOffenseSnaps(snaps)
    val byPlayerWeek = HashMap<Triple<Int, Int, String>, SnapRow>()
    for (s in snaps) {
        val pfr = s.pfrPlayerId ?: continue
        for (id in crosswalk[pfr].orEmpty()) byPlayerWeek[Triple(s.season, s.week, id)] = s
    }
    for (row in rows) {
        val s = byPlayerWeek[Triple(row.season, row.week, row.playerId)] ?: continue
        row.values["offense_snaps"] = s.offenseSnaps
        row.values["team_offense_snaps"] = teamSnaps[s.gameId to s.team]?.toDouble()
        row.values["snap_share"] = s.offensePct
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:ingest:test --tests "*SnapsTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/ingest/src
git commit -m "ingest: solve team snaps and attach snap share"
```

---
### Task 8: Players, injury reports, expected points and team defense

This task ports four readers.

**Players** (`build.build_players`):
- Probe for renamed id, name, position, team and PFR columns.
- Drop rows without an id or name, and keep the first row per id.
- `search_name` is `normalizeSearch(full_name)` from `:core:statquery`, the function a contract test already holds the ETL to.
- Also read `espn_id`, normalized to integer text: `"4361411.0"` becomes `"4361411"`. It feeds `player_xref`, which Plan 2 uses to match ESPN news and injuries.

**Injuries** (`teams.injuries`):
- Rows without a `gsis_id` are dropped.
- Keep the last row per (player, season, week).

**Expected points** (`transform.expected_components` and the columns `validate` cross-checks):
- Drop rows without a `player_id`.
- Season and week parse as integers even when written `"3.0"`.
- The `x_*` components treat null as 0.

**Team defense** (`teams.team_defense_from`):
- Every REG/POST play counts, not just scrimmage plays.
- Points allowed are the opponent's max running score in the game.
- Yards allowed count only on pass and run plays.
- Defensive TDs are touchdowns where `td_team` is the defense.
- Every team that played gets a row, with 0 for anything missing.
- Rows are sorted by season, week and team.

**Files:**
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Players.kt`
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Injuries.kt`
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Expected.kt`
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/pbp/TeamDefense.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/PlayersTest.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/InjuriesTest.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/ExpectedTest.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/pbp/TeamDefenseTest.kt`

**Interfaces:**
- Consumes: `readCsv`, `CsvRow` and `MissingColumnsException` (Task 1); `Play` and `SEASON_TYPES` (Task 3); `PlayerWeek` (Task 3); `normalizeSearch` from `dev.gridiron.core.statquery`.
- Produces:
  - `internal data class PlayerInfo(playerId, fullName, searchName, position: String?, team: String?, pfrPlayerId: String?, espnId: String?)` and `internal fun readPlayers(input, source): List<PlayerInfo>`
  - `internal data class InjuryRow(playerId, season: Int, week: Int, team: String?, name: String?, position: String?, status: String?, injury: String?, practice: String?)` and `internal fun readInjuries(input, source): List<InjuryRow>`
  - `internal val EXPECTED_COLUMNS: Map<String, String>` (ffopportunity column → our metric) and `internal val EXPECTED_ACTUAL_COLUMNS: List<String>`
  - `internal class ExpectedRow(playerId, season, week, team: String?, values: Map<String, Double?>)` with `value(column): Double` (null reads as 0) and `toPlayerWeek(): PlayerWeek`
  - `internal fun readExpected(input, source): List<ExpectedRow>`
  - `internal data class TeamDefenseRow(team, season, week, pointsAllowed, yardsAllowed, sacks, interceptions, fumblesRecovered, defensiveTds: Double)` and `internal class TeamDefenseAggregator` with `add(Play)` and `rows(): List<TeamDefenseRow>` (package `pbp`)

- [ ] **Step 1: Write the failing tests**

```kotlin
// core/ingest/src/test/kotlin/dev/gridiron/core/ingest/PlayersTest.kt
package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.csv.MissingColumnsException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PlayersTest {
    private fun read(csv: String) = readPlayers(csv.byteInputStream(), "players.csv.gz")

    @Test
    fun `reads current nflverse columns, folds names and keeps the first row per id`() {
        val players = read(
            "gsis_id,display_name,position,latest_team,pfr_id,espn_id\n" +
                "00-1,Zoë Émile-Smith,WR,KC,EmilZo00,4361411.0\n" +
                "00-1,Duplicate,QB,NE,,\n" +
                "00-2,,RB,NE,,\n" +
                ",No Id,RB,NE,,\n",
        )
        assertEquals(
            listOf(PlayerInfo("00-1", "Zoë Émile-Smith", "zoe emilesmith", "WR", "KC", "EmilZo00", "4361411")),
            players,
        )
    }

    @Test
    fun `falls back to older column names`() {
        val players = read("player_id,full_name,position_group,team,pfr_player_id\n00-3,Old Name,TE,GB,OldNa00\n")
        assertEquals(listOf(PlayerInfo("00-3", "Old Name", "old name", "TE", "GB", "OldNa00", null)), players)
    }

    @Test
    fun `a file without id or name columns is rejected`() {
        assertThrows<MissingColumnsException> { read("x,y\n1,2\n") }
    }
}
```

```kotlin
// core/ingest/src/test/kotlin/dev/gridiron/core/ingest/InjuriesTest.kt
package dev.gridiron.core.ingest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InjuriesTest {
    @Test
    fun `keeps the last report per player-week and drops rows without an id`() {
        val header = "season,season_type,game_type,team,week,gsis_id,position,full_name,first_name,last_name," +
            "report_primary_injury,report_secondary_injury,report_status,practice_primary_injury,practice_secondary_injury,practice_status\n"
        val csv = header +
            "2025,REG,REG,ARI,1,00-9,WR,Some Guy,Some,Guy,Hamstring,,Questionable,,,Limited Participation in Practice\n" +
            "2025,REG,REG,ARI,1,00-9,WR,Some Guy,Some,Guy,Hamstring,,Out,,,Did Not Participate In Practice\n" +
            "2025,REG,REG,ARI,1,,WR,No Id,No,Id,,,,,,\n"
        assertEquals(
            listOf(InjuryRow("00-9", 2025, 1, "ARI", "Some Guy", "WR", "Out", "Hamstring", "Did Not Participate In Practice")),
            readInjuries(csv.byteInputStream(), "injuries_2025.csv.gz"),
        )
    }
}
```

```kotlin
// core/ingest/src/test/kotlin/dev/gridiron/core/ingest/ExpectedTest.kt
package dev.gridiron.core.ingest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExpectedTest {
    private val header = listOf("season", "posteam", "week", "game_id", "player_id") +
        EXPECTED_COLUMNS.keys + EXPECTED_ACTUAL_COLUMNS

    @Test
    fun `expected components are renamed, keyed and zero-filled`() {
        val rows = listOf(
            mapOf("season" to "2025", "posteam" to "AAA", "week" to "3.0", "player_id" to "WR1",
                "receptions_exp" to 5.25, "rec_yards_gained_exp" to 61.4),
            mapOf("season" to "2025", "posteam" to "AAA", "week" to "3.0", "player_id" to null, "receptions_exp" to 9.0),
        )
        val out = readExpected(Fixtures.csv(header, rows).byteInputStream(), "ep_weekly_2025.csv")
        val week = out.single().toPlayerWeek()
        assertEquals(listOf(2025, 3, "AAA", "WR1"), listOf(week.season, week.week, week.team, week.playerId))
        assertEquals(5.25, week.values["x_receptions"])
        assertEquals(61.4, week.values["x_receiving_yards"])
        assertEquals(0.0, week.values["x_passing_tds"])
        assertEquals(15, week.values.size)
    }
}
```

```kotlin
// core/ingest/src/test/kotlin/dev/gridiron/core/ingest/pbp/TeamDefenseTest.kt
package dev.gridiron.core.ingest.pbp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TeamDefenseTest {
    @Test
    fun `counts what each defense allowed and took away`() {
        val agg = TeamDefenseAggregator()
        listOf(
            play(defteam = "KC", playType = "pass", yardsGained = 20.0, homeTeam = "KC", awayTeam = "BUF",
                totalHomeScore = 0.0, totalAwayScore = 7.0),
            play(defteam = "KC", playType = "pass", yardsGained = 0.0, interception = 1.0, touchdown = 1.0,
                tdTeam = "KC", homeTeam = "KC", awayTeam = "BUF", totalHomeScore = 7.0, totalAwayScore = 7.0),
            play(defteam = "BUF", playType = "run", yardsGained = -3.0, sack = 1.0, fumbleLost = 1.0,
                homeTeam = "KC", awayTeam = "BUF", totalHomeScore = 10.0, totalAwayScore = 7.0),
            play(defteam = "BUF", playType = "punt", yardsGained = 40.0, seasonType = "PRE",
                homeTeam = "KC", awayTeam = "BUF", totalHomeScore = 99.0, totalAwayScore = 99.0),
        ).forEach(agg::add)
        val out = agg.rows().associateBy { it.team }

        assertEquals(TeamDefenseRow("BUF", 2025, 1, 10.0, -3.0, 1.0, 0.0, 1.0, 0.0), out["BUF"])
        assertEquals(TeamDefenseRow("KC", 2025, 1, 7.0, 20.0, 0.0, 1.0, 0.0, 1.0), out["KC"])
        assertEquals(listOf("BUF", "KC"), agg.rows().map { it.team })
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:ingest:test --tests "*PlayersTest*" --tests "*InjuriesTest*" --tests "*ExpectedTest*" --tests "*TeamDefenseTest*"`
Expected: FAIL, because `readPlayers`, `readInjuries`, `readExpected` and `TeamDefenseAggregator` are unresolved.

- [ ] **Step 3: Implement players**

```kotlin
// core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Players.kt
package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.csv.MissingColumnsException
import dev.gridiron.core.ingest.csv.readCsv
import dev.gridiron.core.statquery.normalizeSearch
import java.io.InputStream

internal data class PlayerInfo(
    val playerId: String,
    val fullName: String,
    val searchName: String,
    val position: String?,
    val team: String?,
    val pfrPlayerId: String?,
    val espnId: String?,
)

// nflverse has renamed these over time; probe rather than assume.
private val ID_COLUMNS = listOf("gsis_id", "player_id", "gsis_it_id")
private val NAME_COLUMNS = listOf("display_name", "full_name", "football_name")
private val POSITION_COLUMNS = listOf("position", "position_group")
private val TEAM_COLUMNS = listOf("latest_team", "team_abbr", "team")
private val PFR_COLUMNS = listOf("pfr_id", "pfr_player_id")
private const val ESPN_COLUMN = "espn_id"

/** nflverse's player list: one row per gsis id, the first one when a file repeats an id. */
internal fun readPlayers(input: InputStream, source: String): List<PlayerInfo> {
    val all = (ID_COLUMNS + NAME_COLUMNS + POSITION_COLUMNS + TEAM_COLUMNS + PFR_COLUMNS + ESPN_COLUMN).distinct()
    val players = LinkedHashMap<String, PlayerInfo>()
    var columns: List<String?>? = null
    readCsv(input, source, all, required = emptyList()) { row ->
        val (idCol, nameCol, posCol, teamCol, pfrCol) = columns ?: listOf(ID_COLUMNS, NAME_COLUMNS, POSITION_COLUMNS, TEAM_COLUMNS, PFR_COLUMNS)
            .map { candidates -> candidates.firstOrNull(row::hasColumn) }
            .also { found ->
                if (found[0] == null || found[1] == null) throw MissingColumnsException(source, listOf("gsis_id", "display_name"))
                columns = found
            }
        val id = row.text(idCol!!) ?: return@readCsv
        val name = row.text(nameCol!!) ?: return@readCsv
        if (id in players) return@readCsv
        players[id] = PlayerInfo(
            playerId = id,
            fullName = name,
            searchName = normalizeSearch(name),
            position = posCol?.let(row::text),
            team = teamCol?.let(row::text),
            pfrPlayerId = pfrCol?.let(row::text),
            espnId = row.text(ESPN_COLUMN)?.let(::integerText),
        )
    }
    return players.values.toList()
}

/** "4361411.0" and "4361411" both mean ESPN athlete 4361411. */
private fun integerText(raw: String): String {
    val n = raw.toDoubleOrNull() ?: return raw
    return if (n % 1.0 == 0.0) n.toLong().toString() else raw
}
```

- [ ] **Step 4: Implement injuries and expected points**

```kotlin
// core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Injuries.kt
package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.csv.readCsv
import java.io.InputStream

internal data class InjuryRow(
    val playerId: String,
    val season: Int,
    val week: Int,
    val team: String?,
    val name: String?,
    val position: String?,
    val status: String?,
    val injury: String?,
    val practice: String?,
)

private val INJURY_COLUMNS = listOf(
    "gsis_id", "season", "week", "team", "full_name", "position",
    "report_status", "report_primary_injury", "practice_status",
)

/** nflverse's weekly injury report, trimmed to what the app shows; the last row per player-week wins. */
internal fun readInjuries(input: InputStream, source: String): List<InjuryRow> {
    val rows = LinkedHashMap<Triple<String, Int, Int>, InjuryRow>()
    readCsv(input, source, INJURY_COLUMNS) { row ->
        val id = row.text("gsis_id") ?: return@readCsv
        val season = row.int("season") ?: return@readCsv
        val week = row.int("week") ?: return@readCsv
        rows[Triple(id, season, week)] = InjuryRow(
            id, season, week, row.text("team"), row.text("full_name"), row.text("position"),
            row.text("report_status"), row.text("report_primary_injury"), row.text("practice_status"),
        )
    }
    return rows.values.toList()
}
```

```kotlin
// core/ingest/src/main/kotlin/dev/gridiron/core/ingest/Expected.kt
package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.csv.readCsv
import dev.gridiron.core.ingest.pbp.PlayerWeek
import java.io.InputStream

/**
 * ffopportunity column to our expected component. Actual counterparts come
 * from play-by-play; only the model's expectations are taken from this file.
 * rec_interception_exp is deliberately not charged to receivers.
 */
internal val EXPECTED_COLUMNS: Map<String, String> = linkedMapOf(
    "pass_completions_exp" to "x_completions",
    "receptions_exp" to "x_receptions",
    "pass_yards_gained_exp" to "x_passing_yards",
    "rush_yards_gained_exp" to "x_rushing_yards",
    "rec_yards_gained_exp" to "x_receiving_yards",
    "pass_touchdown_exp" to "x_passing_tds",
    "rush_touchdown_exp" to "x_rushing_tds",
    "rec_touchdown_exp" to "x_receiving_tds",
    "pass_two_point_conv_exp" to "x_passing_2pt",
    "rush_two_point_conv_exp" to "x_rushing_2pt",
    "rec_two_point_conv_exp" to "x_receiving_2pt",
    "pass_first_down_exp" to "x_passing_first_downs",
    "rush_first_down_exp" to "x_rushing_first_downs",
    "rec_first_down_exp" to "x_receiving_first_downs",
    "pass_interception_exp" to "x_interceptions",
)

/** ffopportunity's own actuals and totals, read only to cross-check ours. */
internal val EXPECTED_ACTUAL_COLUMNS: List<String> = listOf(
    "pass_completions", "receptions", "pass_yards_gained", "rec_yards_gained",
    "rush_yards_gained", "pass_touchdown", "rec_touchdown", "rush_touchdown",
    "pass_two_point_conv", "rec_two_point_conv", "rush_two_point_conv",
    "pass_first_down", "rec_first_down", "rush_first_down", "pass_interception",
    "rec_fumble_lost", "rush_fumble_lost", "total_fantasy_points", "total_fantasy_points_exp",
)

internal class ExpectedRow(
    val playerId: String,
    val season: Int,
    val week: Int,
    val team: String?,
    val values: Map<String, Double?>,
) {
    /** An ffopportunity column, null as 0 like polars' `fill_null(0)`. */
    fun value(column: String): Double = values[column] ?: 0.0

    /** Our expected components, keyed like a weekly row, for the fact table. */
    fun toPlayerWeek(): PlayerWeek = PlayerWeek(
        season, week, team, playerId,
        EXPECTED_COLUMNS.entries.associateTo(LinkedHashMap<String, Double?>()) { (src, dst) -> dst to value(src) },
    )
}

/** ffopportunity's weekly file. Season is text and week a float there; rows without a player are dropped. */
internal fun readExpected(input: InputStream, source: String): List<ExpectedRow> = buildList {
    val numeric = EXPECTED_COLUMNS.keys.toList() + EXPECTED_ACTUAL_COLUMNS
    readCsv(input, source, listOf("season", "week", "player_id", "posteam") + numeric) { row ->
        val playerId = row.text("player_id") ?: return@readCsv
        val season = row.int("season") ?: return@readCsv
        val week = row.int("week") ?: return@readCsv
        add(ExpectedRow(playerId, season, week, row.text("posteam"), numeric.associateWith { row.double(it) }))
    }
}
```

- [ ] **Step 5: Implement team defense**

```kotlin
// core/ingest/src/main/kotlin/dev/gridiron/core/ingest/pbp/TeamDefense.kt
package dev.gridiron.core.ingest.pbp

internal data class TeamDefenseRow(
    val team: String,
    val season: Int,
    val week: Int,
    val pointsAllowed: Double,
    val yardsAllowed: Double,
    val sacks: Double,
    val interceptions: Double,
    val fumblesRecovered: Double,
    val defensiveTds: Double,
)

/** One row per (team, season, week): what the defense allowed and took away. Ports `teams.team_defense_from`. */
internal class TeamDefenseAggregator {
    private class Game(val season: Int, val week: Int) {
        var home: String? = null
        var away: String? = null
        var homeScore = 0.0
        var awayScore = 0.0
    }

    private class Allowed {
        var yards = 0.0
        var sacks = 0.0
        var interceptions = 0.0
        var fumbles = 0.0
        var tds = 0.0
    }

    private val games = LinkedHashMap<String, Game>()
    private val allowed = HashMap<Triple<String, Int, Int>, Allowed>()

    fun add(p: Play) {
        if (p.seasonType !in SEASON_TYPES) return
        p.gameId?.let { id ->
            val g = games.getOrPut(id) { Game(p.season, p.week) }
            if (g.home == null) g.home = p.homeTeam
            if (g.away == null) g.away = p.awayTeam
            g.homeScore = maxOf(g.homeScore, p.totalHomeScore ?: 0.0)
            g.awayScore = maxOf(g.awayScore, p.totalAwayScore ?: 0.0)
        }
        val defense = p.defteam ?: return
        val a = allowed.getOrPut(Triple(defense, p.season, p.week)) { Allowed() }
        if (p.playType == "pass" || p.playType == "run") a.yards += p.yardsGained ?: 0.0
        a.sacks += p.sack ?: 0.0
        a.interceptions += p.interception ?: 0.0
        a.fumbles += p.fumbleLost ?: 0.0
        if (p.tdTeam != null && p.tdTeam == defense) a.tds += p.touchdown ?: 0.0
    }

    fun rows(): List<TeamDefenseRow> = games.values
        .flatMap { g ->
            listOfNotNull(
                g.home?.let { row(it, g, g.awayScore) },
                g.away?.let { row(it, g, g.homeScore) },
            )
        }
        .sortedWith(compareBy({ it.season }, { it.week }, { it.team }))

    private fun row(team: String, g: Game, pointsAllowed: Double): TeamDefenseRow {
        val a = allowed[Triple(team, g.season, g.week)]
        return TeamDefenseRow(
            team, g.season, g.week, pointsAllowed,
            a?.yards ?: 0.0, a?.sacks ?: 0.0, a?.interceptions ?: 0.0, a?.fumbles ?: 0.0, a?.tds ?: 0.0,
        )
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :core:ingest:test --tests "*PlayersTest*" --tests "*InjuriesTest*" --tests "*ExpectedTest*" --tests "*TeamDefenseTest*"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add core/ingest/src
git commit -m "ingest: read players, injury reports and expected points; aggregate team defense"
```

---
### Task 9: The database writer

This writes the Python ETL's schema verbatim (`schema.py` DDL and `INDEXES`), plus `player_xref`.

**Schema and speed**
- `player_xref` maps ESPN athlete id → player id for every player in nflverse's list, not only players with stats, so Plan 2 can link news about rookies and injured players.
- Facts are inserted sorted by primary key, one transaction per batch. The build runs with the journal off; a failed build deletes the file, so nothing relies on rollback.

**Reusing a season**
- A season copied from a previous database comes across with `ATTACH` and `INSERT ... SELECT` for its facts, team defense and injury rows.

**Players**
- `writePlayers` loads every player into a temp table.
- It deletes facts whose player id isn't in nflverse's list, just as the Python build drops them before insert.
- It keeps only referenced players in `player`, and writes `player_xref` for every player with an ESPN id.

**Finishing**
- `finish` creates the indexes, writes `schema_meta` and runs `ANALYZE`.
- No `VACUUM`: facts go in sorted and the file is freshly built, so there is nothing to reclaim, and `VACUUM` would double the disk needed.

**Files:**
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/db/Schema.kt`
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/db/StatsDbWriter.kt`
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/db/Meta.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/DbQuery.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/db/StatsDbWriterTest.kt`

**Interfaces:**
- Consumes: `Metric`/`METRICS` (Task 2), `Fact` (Task 4), `PlayerInfo`/`InjuryRow` (Task 8), `TeamDefenseRow` (Task 8).
- Produces (package `dev.gridiron.core.ingest.db`):
  - `public const val SCHEMA_VERSION: Int = 6`, `public const val INGEST_VERSION: Int = 1`
  - `public fun readMeta(file: File): Map<String, String>?` — null when the file can't be read as a stats database.
  - `internal class StatsDbWriter : AutoCloseable` with `companion fun create(file: File)`, `internal val connection: SQLiteConnection`, `writeMetrics(List<Metric>)`, `writeFacts(List<Fact>)`, `writeTeamDefense(List<TeamDefenseRow>)`, `writeInjuries(List<InjuryRow>)`, `copySeasonFrom(previous: File, season: Int)`, `writePlayers(List<PlayerInfo>): Int` (facts dropped), `finish(seasons: Collection<Int>, meta: Map<String, String>, builtAt: Instant)`, `factCount(): Long`, `execute(sql: String, vararg binds: Any?)` (tests and raw inserts).
  - Test helper `internal fun query(file: File, sql: String): List<List<String?>>` (package `dev.gridiron.core.ingest`).

- [ ] **Step 1: Write the test helper and failing tests**

```kotlin
// core/ingest/src/test/kotlin/dev/gridiron/core/ingest/DbQuery.kt
package dev.gridiron.core.ingest

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

/** Every row of [sql] against [file], each column as text (null stays null). */
internal fun query(file: File, sql: String): List<List<String?>> =
    BundledSQLiteDriver().open(file.path).use { conn ->
        conn.prepare(sql).use { st ->
            buildList {
                while (st.step()) add((0 until st.getColumnCount()).map { if (st.isNull(it)) null else st.getText(it) })
            }
        }
    }
```

```kotlin
// core/ingest/src/test/kotlin/dev/gridiron/core/ingest/db/StatsDbWriterTest.kt
package dev.gridiron.core.ingest.db

import dev.gridiron.core.ingest.Fact
import dev.gridiron.core.ingest.InjuryRow
import dev.gridiron.core.ingest.METRICS
import dev.gridiron.core.ingest.PlayerInfo
import dev.gridiron.core.ingest.pbp.TeamDefenseRow
import dev.gridiron.core.ingest.query
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

class StatsDbWriterTest {
    @TempDir
    lateinit var dir: File

    private val wr1 = PlayerInfo("WR1", "Wide Receiver", "wide receiver", "WR", "AAA", "pWR1", "102")
    private val old = PlayerInfo("OLD1", "Retired Guy", "retired guy", "WR", "XXX", null, "105")

    private fun fact(pid: String, season: Int, metric: String, value: Double) = Fact(pid, season, 1, "AAA", metric, value)

    private fun build(file: File, seasons: List<Int>) {
        StatsDbWriter.create(file).use { w ->
            w.writeMetrics(METRICS)
            for (s in seasons) {
                w.writeFacts(listOf(fact("WR1", s, "targets", 5.0), fact("WR1", s, "target_share", 0.5)))
                w.writeTeamDefense(listOf(TeamDefenseRow("AAA", s, 1, 10.0, 300.0, 2.0, 1.0, 0.0, 0.0)))
                w.writeInjuries(listOf(InjuryRow("WR1", s, 1, "AAA", "Wide Receiver", "WR", "Questionable", "Knee", "Limited")))
            }
            w.writePlayers(listOf(wr1, old))
            w.finish(seasons, mapOf("expected_through_week:${seasons.last()}" to "3"), Instant.parse("2026-09-25T12:00:00Z"))
        }
    }

    @Test
    fun `a finished database has the schema, rows and provenance`() {
        val file = File(dir, "stats.db")
        build(file, listOf(2025))

        val meta = readMeta(file)!!
        assertEquals("6", meta["schema_version"])
        assertEquals("1", meta["ingest_version"])
        assertEquals("2025", meta["seasons"])
        assertEquals("3", meta["expected_through_week:2025"])
        assertEquals("2026-09-25T12:00:00Z", meta["built_at"])
        assertTrue("ffopportunity" in meta.getValue("source"))

        assertEquals(listOf(listOf("WR1", "Wide Receiver", "wide receiver")), query(file, "SELECT player_id, full_name, search_name FROM player"))
        assertEquals(listOf(listOf("102", "WR1"), listOf("105", "OLD1")), query(file, "SELECT espn_id, player_id FROM player_xref ORDER BY espn_id"))
        assertEquals(listOf(listOf("2")), query(file, "SELECT COUNT(*) FROM player_week_stat"))
        assertEquals(listOf(listOf("81")), query(file, "SELECT COUNT(*) FROM metric"))
        assertEquals(1, query(file, "SELECT name FROM sqlite_master WHERE name = 'idx_pws_metric_season_week'").size)
        assertEquals(1, query(file, "SELECT name FROM sqlite_master WHERE name = 'sqlite_stat1'").size)
        assertEquals(listOf(listOf("Questionable")), query(file, "SELECT status FROM injury_report"))
        assertEquals(listOf(listOf("300.0")), query(file, "SELECT yards_allowed FROM team_week_defense"))
    }

    @Test
    fun `facts for players nflverse doesn't list are dropped`() {
        val file = File(dir, "stats.db")
        StatsDbWriter.create(file).use { w ->
            w.writeMetrics(METRICS)
            w.writeFacts(listOf(fact("WR1", 2025, "targets", 5.0), fact("GHOST", 2025, "targets", 1.0)))
            assertEquals(1, w.writePlayers(listOf(wr1)))
            assertEquals(1L, w.factCount())
        }
    }

    @Test
    fun `copying a season takes only that season's rows`() {
        val previous = File(dir, "previous.db")
        build(previous, listOf(2024, 2025))
        val file = File(dir, "stats.db")
        StatsDbWriter.create(file).use { w ->
            w.writeMetrics(METRICS)
            w.copySeasonFrom(previous, 2024)
        }
        assertEquals(listOf(listOf("2024", "2")), query(file, "SELECT season, COUNT(*) FROM player_week_stat GROUP BY season"))
        assertEquals(listOf(listOf("2024")), query(file, "SELECT season FROM team_week_defense"))
        assertEquals(listOf(listOf("2024")), query(file, "SELECT season FROM injury_report"))
    }

    @Test
    fun `creating over an existing file starts fresh`() {
        val file = File(dir, "stats.db")
        build(file, listOf(2025))
        StatsDbWriter.create(file).use { it.writeMetrics(METRICS) }
        assertEquals(listOf(listOf("0")), query(file, "SELECT COUNT(*) FROM player_week_stat"))
    }

    @Test
    fun `meta of something that isn't a stats database is null`() {
        val junk = File(dir, "junk.db").apply { writeText("not a database") }
        assertNull(readMeta(junk))
        assertNull(readMeta(File(dir, "missing.db")))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:ingest:test --tests "*StatsDbWriterTest*"`
Expected: FAIL — `StatsDbWriter`, `readMeta` unresolved.

- [ ] **Step 3: Implement the schema**

```kotlin
// core/ingest/src/main/kotlin/dev/gridiron/core/ingest/db/Schema.kt
package dev.gridiron.core.ingest.db

/** Written into `schema_meta`: the Python ETL's schema 5 plus `player_xref`. */
public const val SCHEMA_VERSION: Int = 6

/**
 * Bump whenever a transform, the schema or an input's meaning changes: a build
 * only copies a season out of a previous database built with the same version.
 */
public const val INGEST_VERSION: Int = 1

internal const val SOURCE_NOTE: String = "nflverse-data (CC BY 4.0); ffopportunity expected points (GPL >= 3)"

/** `etl/gridiron_etl/schema.py`'s DDL, one statement per entry, plus `player_xref`. */
internal val SCHEMA: List<String> = listOf(
    "PRAGMA journal_mode = OFF",
    "PRAGMA synchronous = OFF",
    "CREATE TABLE schema_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
    """CREATE TABLE metric (
        id TEXT PRIMARY KEY, name TEXT NOT NULL, abbr TEXT NOT NULL, "group" TEXT NOT NULL,
        definition TEXT NOT NULL, formula TEXT, positions TEXT NOT NULL, tier TEXT NOT NULL,
        predicts TEXT, stability REAL, higher_is_better INTEGER NOT NULL DEFAULT 1,
        decimals INTEGER NOT NULL DEFAULT 1, hot INTEGER NOT NULL DEFAULT 0,
        internal INTEGER NOT NULL DEFAULT 0, computed INTEGER NOT NULL DEFAULT 0,
        dist_family TEXT, zero_inflated INTEGER NOT NULL DEFAULT 0)""",
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
    """CREATE TABLE projection_snapshot (
        player_id TEXT NOT NULL, season INTEGER NOT NULL, week INTEGER NOT NULL,
        metric_id TEXT NOT NULL, projected_mean REAL NOT NULL, projected_variance REAL NOT NULL,
        snapshot_at TEXT NOT NULL,
        PRIMARY KEY (player_id, season, week, metric_id, snapshot_at)) WITHOUT ROWID""",
    """CREATE TABLE accuracy_summary (
        position TEXT NOT NULL, season INTEGER NOT NULL, metric_id TEXT NOT NULL,
        baseline TEXT NOT NULL, sample_n INTEGER NOT NULL, mae REAL NOT NULL, rmse REAL NOT NULL,
        bias REAL NOT NULL, r2 REAL,
        PRIMARY KEY (position, season, metric_id, baseline)) WITHOUT ROWID""",
    """CREATE TABLE team_week_defense (
        team TEXT NOT NULL, season INTEGER NOT NULL, week INTEGER NOT NULL,
        points_allowed REAL NOT NULL, yards_allowed REAL NOT NULL, sacks REAL NOT NULL,
        interceptions REAL NOT NULL, fumbles_recovered REAL NOT NULL, defensive_tds REAL NOT NULL,
        PRIMARY KEY (team, season, week)) WITHOUT ROWID""",
    """CREATE TABLE injury_report (
        player_id TEXT NOT NULL, season INTEGER NOT NULL, week INTEGER NOT NULL,
        team TEXT, name TEXT, position TEXT, status TEXT, injury TEXT, practice TEXT,
        PRIMARY KEY (player_id, season, week)) WITHOUT ROWID""",
    // ESPN athlete id to player id for every player nflverse lists, stats or not:
    // live news and injuries arrive keyed by ESPN id.
    """CREATE TABLE player_xref (
        espn_id TEXT PRIMARY KEY, player_id TEXT NOT NULL, full_name TEXT NOT NULL,
        position TEXT, team TEXT) WITHOUT ROWID""",
)

/** One covering index for the Grid's metric-over-range reads; see `schema.py` for why only these. */
internal val INDEXES: List<String> = listOf(
    "CREATE INDEX idx_pws_metric_season_week ON player_week_stat (metric_id, season, week, value)",
    "CREATE INDEX idx_player_search ON player (search_name)",
    "CREATE INDEX idx_player_position ON player (position)",
)
```

- [ ] **Step 4: Implement the writer and `readMeta`**

```kotlin
// core/ingest/src/main/kotlin/dev/gridiron/core/ingest/db/StatsDbWriter.kt
package dev.gridiron.core.ingest.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.gridiron.core.ingest.Fact
import dev.gridiron.core.ingest.InjuryRow
import dev.gridiron.core.ingest.Metric
import dev.gridiron.core.ingest.PlayerInfo
import dev.gridiron.core.ingest.pbp.TeamDefenseRow
import java.io.File
import java.time.Instant

/**
 * Writes a fresh stats.db. The build runs with the journal off, so a failed
 * build leaves a broken file: the caller deletes it rather than rolling back.
 */
internal class StatsDbWriter private constructor(internal val connection: SQLiteConnection) : AutoCloseable {

    companion object {
        fun create(file: File): StatsDbWriter {
            file.parentFile?.mkdirs()
            file.delete()
            val conn = BundledSQLiteDriver().open(file.path)
            for (statement in SCHEMA) conn.execSQL(statement)
            return StatsDbWriter(conn)
        }
    }

    fun writeMetrics(metrics: List<Metric>) = insert(
        """INSERT INTO metric (id, name, abbr, "group", definition, formula, positions, tier,
           predicts, stability, higher_is_better, decimals, hot, internal, computed, dist_family,
           zero_inflated) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        metrics,
    ) { st, m ->
        st.bindText(1, m.id)
        st.bindText(2, m.name)
        st.bindText(3, m.abbr)
        st.bindText(4, m.group)
        st.bindText(5, m.definition)
        st.bindTextOrNull(6, m.formula)
        st.bindText(7, m.positions.joinToString(","))
        st.bindText(8, m.tier)
        st.bindTextOrNull(9, m.predicts)
        if (m.stability == null) st.bindNull(10) else st.bindDouble(10, m.stability)
        st.bindLong(11, m.higherIsBetter.toLong())
        st.bindLong(12, m.decimals.toLong())
        st.bindLong(13, m.hot.toLong())
        st.bindLong(14, m.isInternal.toLong())
        st.bindLong(15, m.computed.toLong())
        st.bindTextOrNull(16, m.distFamily)
        st.bindLong(17, m.zeroInflated.toLong())
    }

    /** Sorted by primary key first, so the WITHOUT ROWID table fills its pages in order. */
    fun writeFacts(facts: List<Fact>) = insert(
        "INSERT OR REPLACE INTO player_week_stat (player_id, season, week, team, metric_id, value) VALUES (?, ?, ?, ?, ?, ?)",
        facts.sortedWith(compareBy({ it.playerId }, { it.season }, { it.week }, { it.metricId })),
    ) { st, f ->
        st.bindText(1, f.playerId)
        st.bindLong(2, f.season.toLong())
        st.bindLong(3, f.week.toLong())
        st.bindTextOrNull(4, f.team)
        st.bindText(5, f.metricId)
        st.bindDouble(6, f.value)
    }

    fun writeTeamDefense(rows: List<TeamDefenseRow>) = insert(
        """INSERT OR REPLACE INTO team_week_defense (team, season, week, points_allowed, yards_allowed,
           sacks, interceptions, fumbles_recovered, defensive_tds) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        rows,
    ) { st, r ->
        st.bindText(1, r.team)
        st.bindLong(2, r.season.toLong())
        st.bindLong(3, r.week.toLong())
        st.bindDouble(4, r.pointsAllowed)
        st.bindDouble(5, r.yardsAllowed)
        st.bindDouble(6, r.sacks)
        st.bindDouble(7, r.interceptions)
        st.bindDouble(8, r.fumblesRecovered)
        st.bindDouble(9, r.defensiveTds)
    }

    fun writeInjuries(rows: List<InjuryRow>) = insert(
        """INSERT OR REPLACE INTO injury_report (player_id, season, week, team, name, position, status,
           injury, practice) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        rows,
    ) { st, r ->
        st.bindText(1, r.playerId)
        st.bindLong(2, r.season.toLong())
        st.bindLong(3, r.week.toLong())
        st.bindTextOrNull(4, r.team)
        st.bindTextOrNull(5, r.name)
        st.bindTextOrNull(6, r.position)
        st.bindTextOrNull(7, r.status)
        st.bindTextOrNull(8, r.injury)
        st.bindTextOrNull(9, r.practice)
    }

    /**
     * Copies one season's facts, team defense and injury report out of
     * [previous]. Only valid when [previous] has this [INGEST_VERSION], which
     * guarantees identical table shapes.
     */
    fun copySeasonFrom(previous: File, season: Int) {
        connection.prepare("ATTACH DATABASE ? AS prev").use {
            it.bindText(1, previous.path)
            it.step()
        }
        try {
            transaction {
                for (table in listOf("player_week_stat", "team_week_defense", "injury_report")) {
                    connection.prepare("INSERT OR REPLACE INTO $table SELECT * FROM prev.$table WHERE season = ?").use {
                        it.bindLong(1, season.toLong())
                        it.step()
                    }
                }
            }
        } finally {
            connection.execSQL("DETACH DATABASE prev")
        }
    }

    /**
     * Keeps players with stats in `player`, drops facts for ids nflverse doesn't
     * list (as the Python build does), and maps every ESPN id. Returns how many
     * facts were dropped.
     */
    fun writePlayers(players: List<PlayerInfo>): Int {
        var dropped = 0
        transaction {
            connection.execSQL(
                """CREATE TEMP TABLE all_player (player_id TEXT PRIMARY KEY, full_name TEXT NOT NULL,
                   search_name TEXT NOT NULL, position TEXT, team TEXT, pfr_player_id TEXT, espn_id TEXT)""",
            )
            connection.prepare("INSERT OR IGNORE INTO all_player VALUES (?, ?, ?, ?, ?, ?, ?)").use { st ->
                for (p in players) {
                    st.bindText(1, p.playerId)
                    st.bindText(2, p.fullName)
                    st.bindText(3, p.searchName)
                    st.bindTextOrNull(4, p.position)
                    st.bindTextOrNull(5, p.team)
                    st.bindTextOrNull(6, p.pfrPlayerId)
                    st.bindTextOrNull(7, p.espnId)
                    st.step()
                    st.reset()
                }
            }
            connection.execSQL("DELETE FROM player_week_stat WHERE player_id NOT IN (SELECT player_id FROM all_player)")
            dropped = connection.prepare("SELECT changes()").use { it.step(); it.getLong(0).toInt() }
            connection.execSQL(
                """INSERT OR REPLACE INTO player (player_id, full_name, search_name, position, team, pfr_player_id)
                   SELECT player_id, full_name, search_name, position, team, pfr_player_id FROM all_player
                   WHERE player_id IN (SELECT player_id FROM player_week_stat)""",
            )
            connection.execSQL(
                """INSERT OR IGNORE INTO player_xref (espn_id, player_id, full_name, position, team)
                   SELECT espn_id, player_id, full_name, position, team FROM all_player WHERE espn_id IS NOT NULL""",
            )
            connection.execSQL("DROP TABLE all_player")
        }
        return dropped
    }

    /** Indexes, provenance, then ANALYZE so the planner has statistics on the first query. */
    fun finish(seasons: Collection<Int>, meta: Map<String, String>, builtAt: Instant) {
        for (statement in INDEXES) connection.execSQL(statement)
        val rows = linkedMapOf(
            "schema_version" to SCHEMA_VERSION.toString(),
            "ingest_version" to INGEST_VERSION.toString(),
            "seasons" to seasons.sorted().joinToString(","),
            "source" to SOURCE_NOTE,
            "built_at" to builtAt.toString(),
        ) + meta.toSortedMap()
        insert("INSERT OR REPLACE INTO schema_meta (key, value) VALUES (?, ?)", rows.entries.toList()) { st, (k, v) ->
            st.bindText(1, k)
            st.bindText(2, v)
        }
        connection.execSQL("ANALYZE")
    }

    fun factCount(): Long = connection.prepare("SELECT COUNT(*) FROM player_week_stat").use {
        it.step()
        it.getLong(0)
    }

    /** One statement with positional binds (String, Int, Long, Double or null). */
    fun execute(sql: String, vararg binds: Any?) {
        connection.prepare(sql).use { st ->
            binds.forEachIndexed { i, b ->
                when (b) {
                    null -> st.bindNull(i + 1)
                    is String -> st.bindText(i + 1, b)
                    is Int -> st.bindLong(i + 1, b.toLong())
                    is Long -> st.bindLong(i + 1, b)
                    is Double -> st.bindDouble(i + 1, b)
                    else -> error("can't bind ${b::class}")
                }
            }
            st.step()
        }
    }

    override fun close() {
        connection.close()
    }

    private fun <T> insert(sql: String, rows: List<T>, bind: (SQLiteStatement, T) -> Unit) = transaction {
        connection.prepare(sql).use { st ->
            for (row in rows) {
                bind(st, row)
                st.step()
                st.reset()
            }
        }
    }

    private inline fun transaction(block: () -> Unit) {
        connection.execSQL("BEGIN")
        block()
        connection.execSQL("COMMIT")
    }
}

private fun SQLiteStatement.bindTextOrNull(index: Int, value: String?) {
    if (value == null) bindNull(index) else bindText(index, value)
}

private fun Boolean.toLong(): Long = if (this) 1L else 0L
```

```kotlin
// core/ingest/src/main/kotlin/dev/gridiron/core/ingest/db/Meta.kt
package dev.gridiron.core.ingest.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import java.io.File

/** A stats database's `schema_meta`, or null if [file] is missing or isn't a stats database. */
public fun readMeta(file: File): Map<String, String>? {
    if (!file.isFile) return null
    return runCatching {
        BundledSQLiteDriver().open(file.path, SQLITE_OPEN_READONLY).use { conn ->
            conn.prepare("SELECT key, value FROM schema_meta").use { st ->
                buildMap { while (st.step()) put(st.getText(0), st.getText(1)) }
            }
        }
    }.getOrNull()
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:ingest:test --tests "*StatsDbWriterTest*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/ingest/src
git commit -m "ingest: write stats.db with the ETL's schema plus player_xref"
```

---
### Task 10: Validation

This ports all of `validate.py`. There are two parts.

**The database-level checks (`validate`)** run on the finished database:
- **Range checks.** A metric with no rows is skipped.
- **Coherence checks.** These use the same SQL, predicates included.
- **Orphans.** Facts for unknown players, and facts for unregistered metrics.
- **Null values.**
- **Empty weeks.** Weeks with no `target_share`.
- **Lonely long TDs.** 50+ yard TDs with no 40+ row.
- **Computed metrics with stored facts.**

**The ffopportunity comparisons** (`cross_check`, `fantasy_contract`, `expected_coverage`) use the shared three-tier `ComparisonPolicy`:
- A row beyond `tight` is an outlier. It becomes a warning, not a failure.
- These fail the build:
  - any row beyond `hardCap`;
  - more than `seasonBudget` outliers in one season.

Kotlin collects warnings into a list where Python logs them. The failure messages keep Python's wording: `"hard cap"` and `"budget"` are what the tests match.

**Files:**
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/ValidationException.kt`
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/validate/DatabaseChecks.kt`
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/validate/CrossCheck.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/validate/DatabaseChecksTest.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/validate/CrossCheckTest.kt`

**Interfaces:**
- Consumes: `StatsDbWriter` (Task 9), `PlayerWeek` (Task 3), `ExpectedRow` and `EXPECTED_COLUMNS` (Task 8), `METRICS` (Task 2).
- Produces:
  - `public class ValidationException(problems: List<String>)` in package `dev.gridiron.core.ingest`.
  - In package `dev.gridiron.core.ingest.validate`:
    - `internal fun validateDatabase(conn: SQLiteConnection): List<String>`
    - `internal data class ComparisonPolicy(name, tight, hardCap, seasonBudget)`
    - `internal fun <R> applyPolicy(rows: List<R>, season: (R) -> Int, magnitude: (R) -> Double, describe: (R) -> String, policy: ComparisonPolicy, warnings: MutableList<String>): List<String>`
    - `internal val FUMBLE_POLICY: ComparisonPolicy`
    - `internal fun crossCheck(weekly: List<PlayerWeek>, ep: List<ExpectedRow>, warnings: MutableList<String>): List<String>`
    - `internal fun fantasyContract(weekly: List<PlayerWeek>, ep: List<ExpectedRow>, warnings: MutableList<String>): List<String>`
    - `internal fun expectedCoverage(season: Int, weekly: List<PlayerWeek>, ep: List<ExpectedRow>?): Pair<Int, String?>`, which returns (through week, warning or null)

- [ ] **Step 1: Write the failing tests** (ported from `etl/tests/test_validate.py` and `etl/tests/test_expected.py`)

```kotlin
// core/ingest/src/test/kotlin/dev/gridiron/core/ingest/validate/DatabaseChecksTest.kt
package dev.gridiron.core.ingest.validate

import dev.gridiron.core.ingest.METRICS
import dev.gridiron.core.ingest.db.StatsDbWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DatabaseChecksTest {
    @TempDir
    lateinit var dir: File

    private fun problems(vararg facts: Pair<String, Double>): List<String> =
        StatsDbWriter.create(File(dir, "t.db")).use { w ->
            w.writeMetrics(METRICS)
            w.execute("INSERT INTO player (player_id, full_name, search_name, position, team) VALUES ('p1', 'Test Player', 'test player', 'WR', 'AAA')")
            for ((metric, value) in facts) {
                w.execute("INSERT INTO player_week_stat VALUES (?, ?, ?, ?, ?, ?)", "p1", 2025, 1, "AAA", metric, value)
            }
            validateDatabase(w.connection)
        }

    @Test
    fun `a computed metric with facts fails`() {
        assertTrue(problems("g" to 1.0, "target_share" to 0.2, "fantasy_points" to 12.0).any { "computed" in it })
    }

    @Test
    fun `long TD counts must nest`() {
        val p = problems("g" to 1.0, "target_share" to 0.2, "receiving_tds" to 1.0, "receiving_tds_40" to 1.0, "receiving_tds_50" to 2.0)
        assertTrue(p.any { "50+ receiving" in it }, "$p")
    }

    @Test
    fun `consistent scoring inputs pass`() {
        assertEquals(
            emptyList<String>(),
            problems("g" to 1.0, "target_share" to 0.2, "receptions" to 3.0, "receiving_tds" to 2.0,
                "receiving_tds_40" to 1.0, "receiving_first_downs" to 2.0),
        )
    }

    @Test
    fun `efficiency carries exceeding carries fail`() {
        val p = problems("g" to 1.0, "target_share" to 0.2, "carries" to 1.0, "carries_eff" to 2.0, "team_carries" to 2.0)
        assertTrue(p.any { "efficiency carries within carries" in it }, "$p")
    }

    @Test
    fun `efficiency carries within carries and team carries pass`() {
        assertEquals(emptyList<String>(), problems("g" to 1.0, "target_share" to 0.2, "carries" to 2.0, "carries_eff" to 1.0, "team_carries" to 3.0))
    }

    @Test
    fun `an out-of-range share fails`() {
        val p = problems("g" to 1.0, "target_share" to 1.4)
        assertTrue(p.any { it.startsWith("target_share: observed [1.400, 1.400] outside expected [0.0, 1.0]") }, "$p")
    }

    @Test
    fun `a week with no target share fails`() {
        assertTrue(problems("g" to 1.0).any { "weeks with no target_share rows" in it })
    }
}
```

```kotlin
// core/ingest/src/test/kotlin/dev/gridiron/core/ingest/validate/CrossCheckTest.kt
package dev.gridiron.core.ingest.validate

import dev.gridiron.core.ingest.EXPECTED_ACTUAL_COLUMNS
import dev.gridiron.core.ingest.EXPECTED_COLUMNS
import dev.gridiron.core.ingest.ExpectedRow
import dev.gridiron.core.ingest.pbp.PlayerWeek
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CrossCheckTest {
    private fun ep(week: Int = 1, values: Map<String, Double> = emptyMap()): ExpectedRow =
        ExpectedRow("WR1", 2025, week, "AAA", (EXPECTED_COLUMNS.keys + EXPECTED_ACTUAL_COLUMNS).associateWith { 0.0 } + values)

    private fun weekly(week: Int = 1, values: Map<String, Double> = emptyMap()): PlayerWeek {
        val zeros = listOf(
            "completions", "receptions", "passing_yards", "receiving_yards", "rushing_yards",
            "passing_tds", "receiving_tds", "rushing_tds", "passing_2pt", "receiving_2pt",
            "rushing_2pt", "passing_first_downs", "receiving_first_downs", "rushing_first_downs",
            "interceptions", "fumbles_lost",
        ).associateWith { 0.0 }
        return PlayerWeek(2025, week, "AAA", "WR1", (zeros + values).toMutableMap())
    }

    @Test
    fun `agreeing sources pass silently`() {
        val warnings = mutableListOf<String>()
        val problems = crossCheck(
            listOf(weekly(values = mapOf("receptions" to 5.0, "receiving_yards" to 61.0))),
            listOf(ep(values = mapOf("receptions" to 5.0, "rec_yards_gained" to 61.0))),
            warnings,
        )
        assertEquals(emptyList<String>(), problems)
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun `a single mismatch is a warning, not a failure`() {
        val warnings = mutableListOf<String>()
        val problems = crossCheck(
            listOf(weekly(values = mapOf("receptions" to 5.0, "receiving_yards" to 61.0))),
            listOf(ep(values = mapOf("receptions" to 5.0, "rec_yards_gained" to 75.0))),
            warnings,
        )
        assertEquals(emptyList<String>(), problems)
        assertTrue(warnings.any { "receiving_yards" in it && "WR1" in it }, "$warnings")
    }

    @Test
    fun `our extra sack fumbles are allowed`() {
        val warnings = mutableListOf<String>()
        assertEquals(emptyList<String>(), crossCheck(listOf(weekly(values = mapOf("fumbles_lost" to 2.0))), listOf(ep(values = mapOf("rush_fumble_lost" to 1.0))), warnings))
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun `trailing ffopportunity's fumbles by one is reported`() {
        val warnings = mutableListOf<String>()
        assertEquals(emptyList<String>(), crossCheck(listOf(weekly()), listOf(ep(values = mapOf("rec_fumble_lost" to 1.0))), warnings))
        assertTrue(warnings.any { "fumbles_lost" in it })
    }

    @Test
    fun `a systematic fumble regression fails the season budget`() {
        val n = FUMBLE_POLICY.seasonBudget + 5
        val problems = crossCheck(
            (1..n).map { weekly(it) },
            (1..n).map { ep(it, mapOf("rec_fumble_lost" to 1.0)) },
            mutableListOf(),
        )
        assertTrue(problems.any { "fumbles_lost" in it && "budget" in it }, "$problems")
    }

    @Test
    fun `the fantasy contract matches the reference profile`() {
        // 5 rec, 61 yds, 1 TD, 1 fumble: 5 + 6.1 + 6 - 2 = 15.1 in the file.
        val ours = listOf(weekly(values = mapOf("receptions" to 5.0, "receiving_yards" to 61.0, "receiving_tds" to 1.0, "fumbles_lost" to 1.0)))
        val theirs = ep(values = mapOf(
            "receptions" to 5.0, "rec_yards_gained" to 61.0, "rec_touchdown" to 1.0, "rec_fumble_lost" to 1.0,
            "total_fantasy_points" to 15.1, "receptions_exp" to 4.0, "rec_yards_gained_exp" to 50.0,
            "rec_touchdown_exp" to 0.5, "total_fantasy_points_exp" to 12.0,
        ))
        assertEquals(emptyList<String>(), fantasyContract(ours, listOf(theirs), mutableListOf()))

        val off = ExpectedRow(theirs.playerId, theirs.season, theirs.week, theirs.team, theirs.values + ("total_fantasy_points" to 10.1))
        val warnings = mutableListOf<String>()
        assertEquals(emptyList<String>(), fantasyContract(ours, listOf(off), warnings))
        assertTrue(warnings.any { "total_fantasy_points" in it })
    }

    private data class Row(val season: Int, val m: Double)

    private fun policyProblems(rows: List<Row>, policy: ComparisonPolicy, warnings: MutableList<String> = mutableListOf()) =
        applyPolicy(rows, { it.season }, { it.m }, { "m=${it.m}" }, policy, warnings)

    @Test
    fun `an outlier within budget passes and warns`() {
        val warnings = mutableListOf<String>()
        assertEquals(emptyList<String>(), policyProblems(listOf(Row(2025, 2.0)), ComparisonPolicy("test", 1.0, 10.0, 3), warnings))
        assertTrue(warnings.any { "test" in it })
    }

    @Test
    fun `budget plus one outliers in a season fails`() {
        assertTrue(policyProblems(List(4) { Row(2025, 2.0) }, ComparisonPolicy("test", 1.0, 10.0, 3)).any { "budget" in it })
    }

    @Test
    fun `the budget is per season`() {
        val rows = List(3) { Row(2024, 2.0) } + List(3) { Row(2025, 2.0) }
        assertEquals(emptyList<String>(), policyProblems(rows, ComparisonPolicy("test", 1.0, 10.0, 3)))
    }

    @Test
    fun `a single row beyond the hard cap fails`() {
        assertTrue(policyProblems(listOf(Row(2025, 6.0)), ComparisonPolicy("test", 1.0, 5.0, 10)).any { "hard cap" in it })
    }

    @Test
    fun `expected coverage is complete when every week has expected rows`() {
        assertEquals(3 to null, expectedCoverage(2025, (1..3).map { weekly(it) }, (1..3).map { ep(it) }))
    }

    @Test
    fun `expected coverage warns when ffopportunity lags the newest week`() {
        val (through, warning) = expectedCoverage(2026, (1..3).map { weekly(it) }, (1..2).map { ep(it) })
        assertEquals(2, through)
        assertTrue(warning!!.contains("2026") && warning.contains("week 3"), warning)
    }

    @Test
    fun `expected coverage stops at the first gap`() {
        val (through, warning) = expectedCoverage(2025, (1..4).map { weekly(it) }, listOf(1, 3, 4).map { ep(it) })
        assertEquals(1, through)
        assertTrue(warning!!.contains("week 2"), warning)
    }

    @Test
    fun `no expected data at all is week zero`() {
        val (through, warning) = expectedCoverage(2026, (1..2).map { weekly(it) }, null)
        assertEquals(0, through)
        assertTrue(warning!!.contains("weeks 1, 2"), warning)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:ingest:test --tests "*DatabaseChecksTest*" --tests "*CrossCheckTest*"`
Expected: FAIL, because `validateDatabase`, `crossCheck` and `applyPolicy` are unresolved.

- [ ] **Step 3: Implement the exception and the database checks**

```kotlin
// core/ingest/src/main/kotlin/dev/gridiron/core/ingest/ValidationException.kt
package dev.gridiron.core.ingest

/** A build's numbers failed a check; the new database is discarded and the old one kept. */
public class ValidationException(public val problems: List<String>) :
    IllegalStateException("${problems.size} validation failure(s); first: ${problems.first()}")
```

```kotlin
// core/ingest/src/main/kotlin/dev/gridiron/core/ingest/validate/DatabaseChecks.kt
package dev.gridiron.core.ingest.validate

import androidx.sqlite.SQLiteConnection
import java.util.Locale

internal data class RangeCheck(val metricId: String, val lo: Double, val hi: Double, val note: String = "")

/** Expected ranges encode real football. Air yards share deliberately permits values outside [0, 1]. */
internal val RANGE_CHECKS: List<RangeCheck> = listOf(
    RangeCheck("target_share", 0.0, 1.0),
    RangeCheck("carry_share", 0.0, 1.0),
    RangeCheck("catch_rate", 0.0, 1.0),
    RangeCheck("snap_share", 0.0, 1.0),
    RangeCheck("rush_success_rate", 0.0, 1.0),
    RangeCheck("wopr", 0.0, 2.2, "clamped shares, so bounded by 1.5 + 0.7"),
    RangeCheck("air_yards_share", -3.0, 3.0, "screens produce negative air yards"),
    RangeCheck("adot", -20.0, 65.0, "one-target weeks can equal a single screen"),
    RangeCheck("cpoe", -100.0, 100.0),
    RangeCheck("receptions", 0.0, 30.0),
    RangeCheck("targets", 0.0, 35.0),
    RangeCheck("carries", 0.0, 50.0),
    RangeCheck("carries_eff", 0.0, 50.0),
    RangeCheck("passing_yards", -50.0, 800.0),
    RangeCheck("receiving_yards", -50.0, 400.0),
    RangeCheck("rushing_yards", -50.0, 400.0),
    RangeCheck("fumbles_lost", 0.0, 6.0),
    RangeCheck("passing_first_downs", 0.0, 40.0),
    RangeCheck("rushing_first_downs", 0.0, 30.0),
    RangeCheck("receiving_first_downs", 0.0, 20.0),
    RangeCheck("passing_2pt", 0.0, 4.0),
    RangeCheck("rushing_2pt", 0.0, 3.0),
    RangeCheck("receiving_2pt", 0.0, 3.0),
)

/** A per player-week relationship between two stored metrics; [predicate] is SQL over `a` and `b`. */
internal data class CoherenceCheck(val name: String, val a: String, val b: String, val predicate: String)

internal val COHERENCE_CHECKS: List<CoherenceCheck> = listOf(
    CoherenceCheck("targets within team targets", "targets", "team_targets", "a <= b"),
    CoherenceCheck("carries within team carries", "carries", "team_carries", "a <= b"),
    CoherenceCheck("efficiency carries within carries", "carries_eff", "carries", "a <= b"),
    CoherenceCheck("efficiency carries within team carries", "carries_eff", "team_carries", "a <= b"),
    CoherenceCheck("snaps within team snaps", "offense_snaps", "team_offense_snaps", "a <= b"),
    CoherenceCheck("cpoe attempts within attempts", "cpoe_n", "attempts", "a <= b"),
    // Solved team snaps agree with the published percentages to within one 0.01 rounding step.
    CoherenceCheck(
        "snap share matches its components", "offense_snaps", "team_offense_snaps",
        "b = 0 OR ABS(a / b - (SELECT value FROM player_week_stat x " +
            "WHERE x.player_id = pw.player_id AND x.season = pw.season " +
            "AND x.week = pw.week AND x.metric_id = 'snap_share')) <= 0.011",
    ),
    CoherenceCheck("50+ passing TDs within 40+", "passing_tds_50", "passing_tds_40", "a <= b"),
    CoherenceCheck("40+ passing TDs within passing TDs", "passing_tds_40", "passing_tds", "a <= b"),
    CoherenceCheck("50+ rushing TDs within 40+", "rushing_tds_50", "rushing_tds_40", "a <= b"),
    CoherenceCheck("40+ rushing TDs within rushing TDs", "rushing_tds_40", "rushing_tds", "a <= b"),
    CoherenceCheck("50+ receiving TDs within 40+", "receiving_tds_50", "receiving_tds_40", "a <= b"),
    CoherenceCheck("40+ receiving TDs within receiving TDs", "receiving_tds_40", "receiving_tds", "a <= b"),
    CoherenceCheck("receiving first downs within receptions", "receiving_first_downs", "receptions", "a <= b"),
    CoherenceCheck("rushing first downs within carries", "rushing_first_downs", "carries", "a <= b"),
)

/**
 * `validate.validate`: wrong numbers still insert cleanly and still render,
 * so these fail the build loudly. Returns every failure.
 */
internal fun validateDatabase(conn: SQLiteConnection): List<String> {
    val problems = mutableListOf<String>()

    for (chk in RANGE_CHECKS) {
        conn.prepare("SELECT MIN(value), MAX(value), COUNT(*) FROM player_week_stat WHERE metric_id = ?").use { st ->
            st.bindText(1, chk.metricId)
            st.step()
            if (st.getLong(2) == 0L) return@use
            val lo = st.getDouble(0)
            val hi = st.getDouble(1)
            if (lo < chk.lo || hi > chk.hi) {
                problems += "${chk.metricId}: observed [${"%.3f".format(Locale.ROOT, lo)}, ${"%.3f".format(Locale.ROOT, hi)}] " +
                    "outside expected [${chk.lo}, ${chk.hi}]" + if (chk.note.isEmpty()) "" else " (${chk.note})"
            }
        }
    }

    for (chk in COHERENCE_CHECKS) {
        val bad = conn.count(
            """SELECT COUNT(*) FROM (
                 SELECT player_id, season, week,
                        MAX(CASE WHEN metric_id = ? THEN value END) AS a,
                        MAX(CASE WHEN metric_id = ? THEN value END) AS b
                 FROM player_week_stat
                 WHERE metric_id IN (?, ?)
                 GROUP BY player_id, season, week
               ) pw
               WHERE a IS NOT NULL AND b IS NOT NULL AND NOT (${chk.predicate})""",
            chk.a, chk.b, chk.a, chk.b,
        )
        if (bad > 0) problems += "coherence: ${chk.name} violated in $bad player-weeks"
    }

    val orphanPlayers = conn.count("SELECT COUNT(*) FROM player_week_stat s LEFT JOIN player p USING(player_id) WHERE p.player_id IS NULL")
    if (orphanPlayers > 0) problems += "$orphanPlayers facts reference unknown player ids"

    val orphanMetrics = conn.count("SELECT COUNT(*) FROM player_week_stat s LEFT JOIN metric m ON m.id = s.metric_id WHERE m.id IS NULL")
    if (orphanMetrics > 0) problems += "$orphanMetrics facts reference unregistered metrics"

    val nulls = conn.count("SELECT COUNT(*) FROM player_week_stat WHERE value IS NULL")
    if (nulls > 0) problems += "$nulls facts have a null value (should be filtered out)"

    // A week with no target share at all means the share computation silently failed.
    val emptyWeeks = conn.prepare(
        "SELECT season, week FROM player_week_stat GROUP BY season, week " +
            "HAVING SUM(CASE WHEN metric_id='target_share' THEN 1 ELSE 0 END) = 0",
    ).use { st -> buildList { while (st.step()) add("(${st.getLong(0)}, ${st.getLong(1)})") } }
    if (emptyWeeks.isNotEmpty()) problems += "weeks with no target_share rows: ${emptyWeeks.take(5)}"

    // A 50+ count with no 40+ row is invisible to the pairwise check (sparse metrics drop zeros).
    for (kind in listOf("passing", "rushing", "receiving")) {
        val orphan = conn.count(
            """SELECT COUNT(*) FROM player_week_stat a
               WHERE a.metric_id = '${kind}_tds_50' AND NOT EXISTS (
                 SELECT 1 FROM player_week_stat b
                 WHERE b.player_id = a.player_id AND b.season = a.season
                   AND b.week = a.week AND b.metric_id = '${kind}_tds_40')""",
        )
        if (orphan > 0) problems += "coherence: 50+ $kind TDs without a 40+ count in $orphan player-weeks"
    }

    val computed = conn.prepare(
        "SELECT m.id FROM metric m JOIN player_week_stat s ON s.metric_id = m.id WHERE m.computed = 1 GROUP BY m.id",
    ).use { st -> buildList { while (st.step()) add(st.getText(0)) } }
    if (computed.isNotEmpty()) problems += "computed metrics have stored facts: $computed"

    return problems
}

private fun SQLiteConnection.count(sql: String, vararg binds: String): Long = prepare(sql).use { st ->
    binds.forEachIndexed { i, b -> st.bindText(i + 1, b) }
    st.step()
    st.getLong(0)
}
```

- [ ] **Step 4: Implement the ffopportunity comparisons**

```kotlin
// core/ingest/src/main/kotlin/dev/gridiron/core/ingest/validate/CrossCheck.kt
package dev.gridiron.core.ingest.validate

import dev.gridiron.core.ingest.ExpectedRow
import dev.gridiron.core.ingest.pbp.PlayerWeek
import kotlin.math.abs

/**
 * `validate.ComparisonPolicy`. A row beyond [tight] is an outlier: a warning
 * a person can look at, not a failure. A row beyond [hardCap], or more than
 * [seasonBudget] outliers in one season, fails the build: that's a dropped or
 * doubled category, or systematic drift, not an isolated upstream quirk.
 */
internal data class ComparisonPolicy(val name: String, val tight: Double, val hardCap: Double, val seasonBudget: Int)

internal fun <R> applyPolicy(
    rows: List<R>,
    season: (R) -> Int,
    magnitude: (R) -> Double,
    describe: (R) -> String,
    policy: ComparisonPolicy,
    warnings: MutableList<String>,
): List<String> {
    val outliers = rows.filter { magnitude(it) > policy.tight }
    if (outliers.isEmpty()) return emptyList()
    outliers.forEach { warnings += "ffopportunity outlier [${policy.name}]: ${describe(it)}" }

    val problems = mutableListOf<String>()
    val overCap = outliers.filter { magnitude(it) > policy.hardCap }
    if (overCap.isNotEmpty()) {
        problems += "${policy.name}: ${overCap.size} player-week(s) exceed the hard cap of ${policy.hardCap} " +
            "(tight tolerance ${policy.tight}), e.g. ${overCap.take(3).map(describe)}"
    }
    outliers.groupingBy(season).eachCount().toSortedMap().forEach { (s, n) ->
        if (n > policy.seasonBudget) {
            problems += "${policy.name}: season $s has $n outliers beyond tolerance ${policy.tight}, " +
                "exceeding the season budget of ${policy.seasonBudget} — likely systematic drift " +
                "rather than isolated upstream quirks"
        }
    }
    return problems
}

private class Joined(val ours: PlayerWeek, val theirs: ExpectedRow) {
    fun our(metric: String): Double = ours.values[metric] ?: 0.0
    fun their(column: String): Double = theirs.value(column)
    fun describe(vararg values: Pair<String, Double>): String =
        "player_id=${ours.playerId}, season=${ours.season}, week=${ours.week}, " +
            values.joinToString { (k, v) -> "$k=$v" }
}

private fun join(weekly: List<PlayerWeek>, ep: List<ExpectedRow>): List<Joined> {
    val theirs = ep.associateBy { Triple(it.playerId, it.season, it.week) }
    return weekly.mapNotNull { w -> theirs[Triple(w.playerId, w.season, w.week)]?.let { Joined(w, it) } }
}

private const val CROSS_CHECK_TOLERANCE = 1.0
/** Backward laterals: nflverse credits the passer with yards after the pitch; ffopportunity doesn't. */
private const val LATERAL_YARDS_TOLERANCE = 45.0
private const val COUNT_HARD_CAP = 3.0
private const val VOLUME_HARD_CAP = 5.0
private const val YARDAGE_HARD_CAP = 50.0
private const val LATERAL_HARD_CAP = 100.0
private const val DEFAULT_SEASON_BUDGET = 5

private fun pair(ours: String, theirs: String, tolerance: Double, hardCap: Double) =
    Triple(ours, theirs, ComparisonPolicy("cross-check $ours vs ffopportunity $theirs", tolerance, hardCap, DEFAULT_SEASON_BUDGET))

/** Our play-by-play actual, ffopportunity's actual for the same stat, and the policy for the pair. */
private val CROSS_CHECK_PAIRS = listOf(
    pair("receptions", "receptions", CROSS_CHECK_TOLERANCE, VOLUME_HARD_CAP),
    pair("completions", "pass_completions", CROSS_CHECK_TOLERANCE, VOLUME_HARD_CAP),
    pair("passing_yards", "pass_yards_gained", LATERAL_YARDS_TOLERANCE, LATERAL_HARD_CAP),
    pair("rushing_yards", "rush_yards_gained", CROSS_CHECK_TOLERANCE, YARDAGE_HARD_CAP),
    pair("receiving_yards", "rec_yards_gained", CROSS_CHECK_TOLERANCE, YARDAGE_HARD_CAP),
    pair("passing_tds", "pass_touchdown", CROSS_CHECK_TOLERANCE, COUNT_HARD_CAP),
    pair("rushing_tds", "rush_touchdown", CROSS_CHECK_TOLERANCE, COUNT_HARD_CAP),
    pair("receiving_tds", "rec_touchdown", CROSS_CHECK_TOLERANCE, COUNT_HARD_CAP),
    pair("passing_2pt", "pass_two_point_conv", CROSS_CHECK_TOLERANCE, COUNT_HARD_CAP),
    pair("rushing_2pt", "rush_two_point_conv", CROSS_CHECK_TOLERANCE, COUNT_HARD_CAP),
    pair("receiving_2pt", "rec_two_point_conv", CROSS_CHECK_TOLERANCE, COUNT_HARD_CAP),
    pair("passing_first_downs", "pass_first_down", CROSS_CHECK_TOLERANCE, VOLUME_HARD_CAP),
    pair("rushing_first_downs", "rush_first_down", CROSS_CHECK_TOLERANCE, VOLUME_HARD_CAP),
    pair("receiving_first_downs", "rec_first_down", CROSS_CHECK_TOLERANCE, VOLUME_HARD_CAP),
    pair("interceptions", "pass_interception", CROSS_CHECK_TOLERANCE, COUNT_HARD_CAP),
)

/**
 * Ours also counts sack fumbles, so only trailing theirs matters, and any
 * trailing is an outlier. Traced upstream misattributions trail by exactly 1,
 * about 7 a season at worst; a real regression blows the budget of 20.
 */
internal val FUMBLE_POLICY: ComparisonPolicy = ComparisonPolicy("cross-check fumbles_lost vs ffopportunity", 0.0, 3.0, 20)

private val FANTASY_CONTRACT_POLICY = ComparisonPolicy("fantasy contract: total_fantasy_points vs our components", 2.05, 5.5, 5)
private val EXPECTED_FANTASY_CONTRACT_POLICY =
    ComparisonPolicy("fantasy contract: total_fantasy_points_exp vs expected components", 0.22, 6.0, 5)

/** Our play-by-play actuals against ffopportunity's, per player-week. */
internal fun crossCheck(weekly: List<PlayerWeek>, ep: List<ExpectedRow>, warnings: MutableList<String>): List<String> {
    val joined = join(weekly, ep)
    val problems = mutableListOf<String>()
    for ((ours, theirs, policy) in CROSS_CHECK_PAIRS) {
        problems += applyPolicy(
            joined, { it.ours.season }, { abs(it.our(ours) - it.their(theirs)) },
            { it.describe(ours to it.our(ours), theirs to it.their(theirs)) }, policy, warnings,
        )
    }
    fun theirFumbles(j: Joined) = j.their("rec_fumble_lost") + j.their("rush_fumble_lost")
    problems += applyPolicy(
        joined, { it.ours.season }, { maxOf(0.0, theirFumbles(it) - it.our("fumbles_lost")) },
        { it.describe("fumbles_lost" to it.our("fumbles_lost"), "ffopportunity fumbles" to theirFumbles(it)) },
        FUMBLE_POLICY, warnings,
    )
    return problems
}

/** The profile ffopportunity's totals use, fumbles excluded. */
private fun referencePoints(
    rec: Double, recYds: Double, recTd: Double, rec2pt: Double,
    rushYds: Double, rushTd: Double, rush2pt: Double,
    passYds: Double, passTd: Double, pass2pt: Double, ints: Double,
): Double = rec + 0.1 * recYds + 6 * recTd + 2 * rec2pt +
    0.1 * rushYds + 6 * rushTd + 2 * rush2pt +
    0.04 * passYds + 4 * passTd + 2 * pass2pt - 2 * ints

/** Our components, scored with ffopportunity's rules, reproduce its totals; same for expected. */
internal fun fantasyContract(weekly: List<PlayerWeek>, ep: List<ExpectedRow>, warnings: MutableList<String>): List<String> {
    fun ourPoints(j: Joined) = referencePoints(
        j.our("receptions"), j.our("receiving_yards"), j.our("receiving_tds"), j.our("receiving_2pt"),
        j.our("rushing_yards"), j.our("rushing_tds"), j.our("rushing_2pt"),
        j.our("passing_yards"), j.our("passing_tds"), j.our("passing_2pt"), j.our("interceptions"),
    )
    // Fumbles are removed from both sides: ours include sack fumbles, theirs don't.
    fun theirPoints(j: Joined) = j.their("total_fantasy_points") + 2 * (j.their("rec_fumble_lost") + j.their("rush_fumble_lost"))

    val problems = applyPolicy(
        join(weekly, ep), { it.ours.season }, { abs(ourPoints(it) - theirPoints(it)) },
        { it.describe("total_fantasy_points" to it.their("total_fantasy_points"), "our_points" to ourPoints(it)) },
        FANTASY_CONTRACT_POLICY, warnings,
    ).toMutableList()

    fun expectedPoints(e: ExpectedRow): Double {
        val x = e.toPlayerWeek().values
        fun c(k: String) = x[k] ?: 0.0
        return referencePoints(
            c("x_receptions"), c("x_receiving_yards"), c("x_receiving_tds"), c("x_receiving_2pt"),
            c("x_rushing_yards"), c("x_rushing_tds"), c("x_rushing_2pt"),
            c("x_passing_yards"), c("x_passing_tds"), c("x_passing_2pt"), c("x_interceptions"),
        )
    }
    problems += applyPolicy(
        ep, { it.season }, { abs(expectedPoints(it) - it.value("total_fantasy_points_exp")) },
        {
            "player_id=${it.playerId}, season=${it.season}, week=${it.week}, " +
                "total_fantasy_points_exp=${it.value("total_fantasy_points_exp")}, our_expected_points=${expectedPoints(it)}"
        },
        EXPECTED_FANTASY_CONTRACT_POLICY, warnings,
    )
    return problems
}

/**
 * The week through which ffopportunity covers the season's play-by-play, and
 * a warning when it lags: after Monday Night Football, a build can run before
 * ffopportunity has processed the week, and xFP is 0 there until it catches up.
 */
internal fun expectedCoverage(season: Int, weekly: List<PlayerWeek>, ep: List<ExpectedRow>?): Pair<Int, String?> {
    val played = weekly.map { it.week }.distinct().sorted()
    val covered = ep.orEmpty().map { it.week }.toSet()
    val missing = played.filter { it !in covered }
    val warning = if (missing.isEmpty()) null else {
        val (label, verb) = if (missing.size == 1) "week" to "has" else "weeks" to "have"
        "season $season: play-by-play $label ${missing.joinToString(", ")} $verb games but no ffopportunity " +
            "expected rows; xFP is 0 (FPOE = FP) there until ffopportunity catches up"
    }
    var through = 0
    for (w in played) {
        if (w !in covered) break
        through = w
    }
    return through to warning
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:ingest:test --tests "*DatabaseChecksTest*" --tests "*CrossCheckTest*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/ingest/src
git commit -m "ingest: port database validation and the ffopportunity cross-checks"
```

---
### Task 11: The ingest pipeline

This task orchestrates a whole build.

**Previous database and players**
1. Read the previous database's `schema_meta`. It is usable only if its `ingest_version` matches, which rules out a corrupt file or a Python- or repo-built one (Review Focus 3).
2. Conditional-GET the player list into `playersFile`, which is kept between builds.

**Each season:**
1. Conditional-GET its four inputs, using the previous build's validators if that build had the season.
2. If play-by-play is 404, skip the season with a note.
3. Decide whether the season is reusable. It is when each input is either:
   - "not modified", or
   - still 404 and never recorded.

   A reusable season is copied from the previous database.
4. Otherwise, refetch any "not modified" inputs in full and crunch the season. Crunching means:
   - one streaming pass feeding both aggregators;
   - derive the rates, then attach snap counts;
   - the ffopportunity checks, which throw `ValidationException` on failure;
   - write the season's facts, team defense and injuries;
   - delete the season's downloads.

**Finishing**
1. `writePlayers` runs with the full list, so renames reach copied seasons too (Review Focus 5).
2. `finish`.
3. `validateDatabase`, which throws on failure.

**On any failure or cancellation**, delete `out` and leave `previous` untouched (Review Focus 2).

**Files:**
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/IngestPipeline.kt`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/IngestPipelineTest.kt`

**Interfaces:**
- Consumes: everything above. That is `Fetcher`, `FetchResult`, `Validators`, `Sources` and `Input` (Task 5); `readPlays`, `PlayerWeekAggregator` and `derive` (Tasks 3–4); `TeamDefenseAggregator`, `readPlayers`, `readInjuries` and `readExpected` (Task 8); `readSnaps` and `attachSnapShare` (Task 7); `toFacts` (Task 4); `StatsDbWriter`, `readMeta` and `INGEST_VERSION` (Task 9); `crossCheck`, `fantasyContract`, `expectedCoverage`, `validateDatabase` and `ValidationException` (Task 10); plus test helpers `FakeFetcher`, `Fixtures` and `query` (Tasks 6 and 9).
- Produces (package `dev.gridiron.core.ingest`, public — Plan 2 builds on these exact names):
  - `sealed interface IngestProgress`, with these cases:
    - `data class Checking(season: Int?)`
    - `data class Downloading(season: Int?, what: String, bytes: Long, total: Long)`
    - `data class Crunching(season: Int)`
    - `data object Validating`
  - `data class IngestReport(built: List<Int>, reused: List<Int>, skipped: Map<Int, String>, warnings: List<String>, facts: Long)`
  - `class IngestPipeline(fetcher: Fetcher, workDir: File, playersFile: File, now: () -> Instant = Instant::now)`. It has `suspend fun build(seasons: List<Int>, previous: File?, out: File, onProgress: (IngestProgress) -> Unit = {}): IngestReport`. `playersFile` must be outside `workDir`, because the build empties `workDir` when it ends.

- [ ] **Step 1: Write the failing tests**

```kotlin
// core/ingest/src/test/kotlin/dev/gridiron/core/ingest/IngestPipelineTest.kt
package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.db.readMeta
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

class IngestPipelineTest {
    @TempDir
    lateinit var dir: File

    private val fetcher = FakeFetcher()
    private val workDir by lazy { File(dir, "work") }
    private val pipeline by lazy {
        IngestPipeline(fetcher, workDir, File(dir, "players.csv.gz"), now = { Instant.parse("2026-09-25T12:00:00Z") })
    }

    private val snapHeader = listOf("game_id", "season", "week", "team", "pfr_player_id", "offense_snaps", "offense_pct")
    private val injuryHeader = listOf("gsis_id", "season", "week", "team", "full_name", "position", "report_status", "report_primary_injury", "practice_status")
    private val expectedHeader = listOf("season", "week", "player_id", "posteam") + EXPECTED_COLUMNS.keys + EXPECTED_ACTUAL_COLUMNS

    private fun servePlayers(wr1Name: String = "Wide Receiver One", version: String = "p1") {
        val csv = Fixtures.csv(
            listOf("gsis_id", "display_name", "position", "latest_team", "pfr_id", "espn_id"),
            listOf(
                mapOf("gsis_id" to "QB1", "display_name" to "Quarter Back", "position" to "QB", "latest_team" to "AAA", "pfr_id" to "pQB1", "espn_id" to "101"),
                mapOf("gsis_id" to "WR1", "display_name" to wr1Name, "position" to "WR", "latest_team" to "AAA", "pfr_id" to "pWR1", "espn_id" to "102"),
                mapOf("gsis_id" to "WR2", "display_name" to "Receiver Two", "position" to "WR", "latest_team" to "AAA", "pfr_id" to "pWR2", "espn_id" to "103"),
                mapOf("gsis_id" to "RB1", "display_name" to "Running Back", "position" to "RB", "latest_team" to "AAA", "pfr_id" to "pRB1", "espn_id" to "104"),
                mapOf("gsis_id" to "OLD1", "display_name" to "Retired Guy", "position" to "WR", "latest_team" to "XXX", "espn_id" to "105"),
            ),
        )
        fetcher.serve(Sources.url(Input.PLAYERS), Fixtures.gzip(csv), version)
    }

    private fun ep(season: Int, id: String, vararg values: Pair<String, Any?>): Map<String, Any?> =
        mapOf("season" to season, "week" to 1, "player_id" to id, "posteam" to "AAA") +
            (EXPECTED_COLUMNS.keys + EXPECTED_ACTUAL_COLUMNS).associateWith { 0.0 } + values

    private fun serveSeason(season: Int, version: String = "v1", snapsVersion: String = version, expected: Boolean = true, wr1Receptions: Int = 1) {
        val plays = listOf(
            Fixtures.pbp("season" to season, "receiver_player_id" to "WR1", "passer_player_id" to "QB1", "pass_attempt" to 1,
                "complete_pass" to 1, "air_yards" to 10, "receiving_yards" to 15, "passing_yards" to 15, "yards_gained" to 15),
            Fixtures.pbp("season" to season, "receiver_player_id" to "WR2", "passer_player_id" to "QB1", "pass_attempt" to 1, "air_yards" to 5),
            Fixtures.pbp("season" to season, "play_type" to "run", "rusher_player_id" to "RB1", "rushing_yards" to 4, "yards_gained" to 4),
        )
        fetcher.serve(Sources.url(Input.PBP, season), Fixtures.gzip(Fixtures.pbpCsv(plays)), version)
        fetcher.serve(
            Sources.url(Input.SNAP_COUNTS, season),
            Fixtures.gzip(Fixtures.csv(snapHeader, listOf(
                mapOf("game_id" to "g1", "season" to season, "week" to 1, "team" to "AAA", "pfr_player_id" to "pWR1", "offense_snaps" to 50, "offense_pct" to 0.78),
                mapOf("game_id" to "g1", "season" to season, "week" to 1, "team" to "AAA", "pfr_player_id" to "pRB1", "offense_snaps" to 30, "offense_pct" to 0.47),
                mapOf("game_id" to "g1", "season" to season, "week" to 1, "team" to "AAA", "pfr_player_id" to "pOL1", "offense_snaps" to 64, "offense_pct" to 1.0),
            ))),
            snapsVersion,
        )
        fetcher.serve(
            Sources.url(Input.INJURIES, season),
            Fixtures.gzip(Fixtures.csv(injuryHeader, listOf(mapOf(
                "gsis_id" to "WR2", "season" to season, "week" to 1, "team" to "AAA", "full_name" to "Receiver Two",
                "position" to "WR", "report_status" to "Questionable", "report_primary_injury" to "Hamstring",
                "practice_status" to "Limited Participation in Practice",
            )))),
            version,
        )
        if (expected) {
            val rows = listOf(
                ep(season, "WR1", "receptions" to wr1Receptions, "rec_yards_gained" to 15, "receptions_exp" to 0.8,
                    "rec_yards_gained_exp" to 11.0, "total_fantasy_points" to 2.5, "total_fantasy_points_exp" to 1.9),
                ep(season, "QB1", "pass_completions" to 1, "pass_yards_gained" to 15, "pass_completions_exp" to 0.7,
                    "pass_yards_gained_exp" to 11.0, "total_fantasy_points" to 0.6, "total_fantasy_points_exp" to 0.44),
                ep(season, "RB1", "rush_yards_gained" to 4, "rush_yards_gained_exp" to 3.5, "total_fantasy_points" to 0.4,
                    "total_fantasy_points_exp" to 0.35),
            )
            fetcher.serve(Sources.url(Input.EXPECTED, season), Fixtures.csv(expectedHeader, rows).toByteArray(), version)
        } else {
            fetcher.remove(Sources.url(Input.EXPECTED, season))
        }
    }

    private fun facts(file: File) = query(file, "SELECT player_id, season, week, metric_id, value FROM player_week_stat ORDER BY 1, 2, 3, 4")

    @Test
    fun `a first build downloads everything and writes a validated database`() = runTest {
        servePlayers()
        serveSeason(2024)
        serveSeason(2025)
        val out = File(dir, "stats.db")

        val report = pipeline.build(listOf(2025, 2024), previous = null, out = out)

        assertEquals(listOf(2024, 2025), report.built)
        assertEquals(emptyList<Int>(), report.reused)
        val meta = readMeta(out)!!
        assertEquals("6", meta["schema_version"])
        assertEquals("1", meta["ingest_version"])
        assertEquals("2024,2025", meta["seasons"])
        assertEquals("1", meta["expected_through_week:2025"])
        assertNotNull(meta[Sources.metaKey(Input.PBP, 2025)])
        assertEquals(listOf("QB1", "RB1", "WR1", "WR2"), query(out, "SELECT player_id FROM player ORDER BY 1").map { it[0] })
        assertEquals(listOf(listOf("OLD1")), query(out, "SELECT player_id FROM player_xref WHERE espn_id = '105'"))
        assertEquals(listOf(listOf("0.5")), query(out, "SELECT value FROM player_week_stat WHERE player_id = 'WR1' AND season = 2025 AND metric_id = 'target_share'"))
        assertEquals(listOf(listOf("0.78")), query(out, "SELECT value FROM player_week_stat WHERE player_id = 'WR1' AND season = 2025 AND metric_id = 'snap_share'"))
        assertEquals(listOf(listOf("0.8")), query(out, "SELECT value FROM player_week_stat WHERE player_id = 'WR1' AND season = 2025 AND metric_id = 'x_receptions'"))
        assertEquals(listOf(listOf("Questionable")), query(out, "SELECT status FROM injury_report WHERE season = 2025"))
        assertTrue(report.facts > 0)
        assertEquals(emptyList<String>(), workDir.list()!!.toList())
    }

    @Test
    fun `unchanged seasons are copied, not downloaded or recomputed`() = runTest {
        servePlayers()
        serveSeason(2024)
        serveSeason(2025)
        val first = File(dir, "first.db")
        pipeline.build(listOf(2024, 2025), null, first)
        fetcher.calls.clear()

        val second = File(dir, "second.db")
        val report = pipeline.build(listOf(2024, 2025), first, second)

        assertEquals(listOf(2024, 2025), report.reused)
        assertEquals(emptyList<Int>(), report.built)
        assertEquals(facts(first), facts(second))
        assertTrue(fetcher.calls.all { (_, previous) -> previous != null }, "${fetcher.calls}")
    }

    @Test
    fun `a changed input rebuilds its season and refetches the season's other files in full`() = runTest {
        servePlayers()
        serveSeason(2024)
        serveSeason(2025)
        val first = File(dir, "first.db")
        pipeline.build(listOf(2024, 2025), null, first)
        serveSeason(2025, snapsVersion = "v2")
        fetcher.calls.clear()

        val report = pipeline.build(listOf(2024, 2025), first, File(dir, "second.db"))

        assertEquals(listOf(2025), report.built)
        assertEquals(listOf(2024), report.reused)
        val pbpCalls = fetcher.calls.filter { it.first == Sources.url(Input.PBP, 2025) }.map { it.second }
        assertEquals(listOf(Validators("\"v1\"", null), null), pbpCalls)
    }

    @Test
    fun `an unpublished season is skipped with a note`() = runTest {
        servePlayers()
        serveSeason(2025)
        val report = pipeline.build(listOf(2025, 2026), null, File(dir, "stats.db"))
        assertEquals(listOf(2025), report.built)
        assertEquals(mapOf(2026 to "play-by-play isn't published yet"), report.skipped)
    }

    @Test
    fun `a season without expected points still builds, at week zero`() = runTest {
        servePlayers()
        serveSeason(2025, expected = false)
        val out = File(dir, "stats.db")
        val report = pipeline.build(listOf(2025), null, out)
        assertEquals(listOf(2025), report.built)
        assertEquals("0", readMeta(out)!!["expected_through_week:2025"])
        assertTrue(report.warnings.any { "expected-points" in it }, "${report.warnings}")
    }

    @Test
    fun `an unreadable or foreign previous database means a full rebuild`() = runTest {
        servePlayers()
        serveSeason(2025)
        val junk = File(dir, "junk.db").apply { writeText("not a database") }
        val report = pipeline.build(listOf(2025), junk, File(dir, "stats.db"))
        assertEquals(listOf(2025), report.built)
        assertTrue(fetcher.calls.all { (_, previous) -> previous == null })
    }

    @Test
    fun `a renamed player shows the new name even in a copied season`() = runTest {
        servePlayers()
        serveSeason(2025)
        val first = File(dir, "first.db")
        pipeline.build(listOf(2025), null, first)
        servePlayers(wr1Name = "Renamed Receiver", version = "p2")

        val second = File(dir, "second.db")
        val report = pipeline.build(listOf(2025), first, second)

        assertEquals(listOf(2025), report.reused)
        assertEquals(listOf(listOf("Renamed Receiver", "renamed receiver")), query(second, "SELECT full_name, search_name FROM player WHERE player_id = 'WR1'"))
    }

    @Test
    fun `a failed validation leaves no output behind`() = runTest {
        servePlayers()
        serveSeason(2025, wr1Receptions = 20) // 19 receptions off: beyond the hard cap
        val out = File(dir, "stats.db")
        val e = runCatching { pipeline.build(listOf(2025), null, out) }.exceptionOrNull()
        assertTrue(e is ValidationException, "got $e")
        assertFalse(out.exists())
    }

    @Test
    fun `a cancelled build leaves no output behind and the previous database untouched`() = runTest {
        servePlayers()
        serveSeason(2025)
        val previous = File(dir, "previous.db")
        pipeline.build(listOf(2025), null, previous)
        val before = previous.readBytes()
        serveSeason(2025, version = "v2")
        val reached = CompletableDeferred<Unit>()
        fetcher.onFetch = { url ->
            if (url == Sources.url(Input.EXPECTED, 2025)) {
                reached.complete(Unit)
                awaitCancellation()
            }
        }
        val out = File(dir, "stats.db")
        val job = launch { pipeline.build(listOf(2025), previous, out) }
        reached.await()
        job.cancelAndJoin()
        assertFalse(out.exists())
        assertTrue(before.contentEquals(previous.readBytes()))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:ingest:test --tests "*IngestPipelineTest*"`
Expected: FAIL, because `IngestPipeline` is unresolved.

- [ ] **Step 3: Implement**

```kotlin
// core/ingest/src/main/kotlin/dev/gridiron/core/ingest/IngestPipeline.kt
package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.csv.openInput
import dev.gridiron.core.ingest.db.INGEST_VERSION
import dev.gridiron.core.ingest.db.StatsDbWriter
import dev.gridiron.core.ingest.db.readMeta
import dev.gridiron.core.ingest.pbp.PlayerWeekAggregator
import dev.gridiron.core.ingest.pbp.TeamDefenseAggregator
import dev.gridiron.core.ingest.pbp.derive
import dev.gridiron.core.ingest.pbp.readPlays
import dev.gridiron.core.ingest.validate.crossCheck
import dev.gridiron.core.ingest.validate.expectedCoverage
import dev.gridiron.core.ingest.validate.fantasyContract
import dev.gridiron.core.ingest.validate.validateDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/** What a build is doing, for a progress line. */
public sealed interface IngestProgress {
    public data class Checking(public val season: Int?) : IngestProgress
    public data class Downloading(public val season: Int?, public val what: String, public val bytes: Long, public val total: Long) : IngestProgress
    public data class Crunching(public val season: Int) : IngestProgress
    public data object Validating : IngestProgress
}

public data class IngestReport(
    public val built: List<Int>,
    public val reused: List<Int>,
    public val skipped: Map<Int, String>,
    public val warnings: List<String>,
    public val facts: Long,
)

private val SEASON_INPUTS = listOf(Input.PBP, Input.SNAP_COUNTS, Input.INJURIES, Input.EXPECTED)

/**
 * Builds stats.db from nflverse and ffopportunity: the on-device replacement
 * for the Python ETL. A season whose inputs haven't changed since `previous`
 * was built is copied from it instead of downloaded and recomputed.
 *
 * [playersFile] (the player list, kept between builds) must live outside
 * [workDir], which is emptied when a build ends.
 */
public class IngestPipeline(
    private val fetcher: Fetcher,
    private val workDir: File,
    private val playersFile: File,
    private val now: () -> Instant = Instant::now,
) {
    /**
     * Writes a complete, validated database to [out], or throws and leaves no
     * [out] behind. [previous] is only ever read.
     */
    public suspend fun build(
        seasons: List<Int>,
        previous: File?,
        out: File,
        onProgress: (IngestProgress) -> Unit = {},
    ): IngestReport = withContext(Dispatchers.IO) {
        require(previous == null || previous.canonicalFile != out.canonicalFile) { "out must differ from previous" }
        val prior = previous?.let(::readMeta)?.takeIf { it["ingest_version"] == INGEST_VERSION.toString() }
        out.delete()
        workDir.mkdirs()
        try {
            Run(prior, previous, coroutineContext.job, onProgress).build(seasons.distinct().sorted(), out)
        } catch (t: Throwable) {
            out.delete()
            throw t
        } finally {
            workDir.listFiles()?.forEach { it.delete() }
        }
    }

    private inner class Run(
        private val prior: Map<String, String>?,
        private val previous: File?,
        private val job: Job,
        private val onProgress: (IngestProgress) -> Unit,
    ) {
        private val priorSeasons = prior?.get("seasons").orEmpty().split(',').mapNotNull { it.toIntOrNull() }.toSet()
        private val meta = LinkedHashMap<String, String>()
        private val warnings = mutableListOf<String>()
        private val built = mutableListOf<Int>()
        private val reused = mutableListOf<Int>()
        private val skipped = LinkedHashMap<Int, String>()

        suspend fun build(seasons: List<Int>, out: File): IngestReport {
            onProgress(IngestProgress.Checking(null))
            fetchPlayers()
            val players = openInput(playersFile).use { readPlayers(it, playersFile.name) }
            val crosswalk = players.filter { it.pfrPlayerId != null }.groupBy({ it.pfrPlayerId!! }, { it.playerId })
            return StatsDbWriter.create(out).use { writer ->
                writer.writeMetrics(METRICS)
                for (season in seasons) {
                    job.ensureActive()
                    season(season, writer, crosswalk)
                }
                check(built.isNotEmpty() || reused.isNotEmpty()) { "none of the seasons $seasons has published play-by-play" }
                writer.writePlayers(players)
                writer.finish(built + reused, meta, now())
                onProgress(IngestProgress.Validating)
                val problems = validateDatabase(writer.connection)
                if (problems.isNotEmpty()) throw ValidationException(problems)
                IngestReport(built.sorted(), reused.sorted(), skipped.toMap(), warnings.toList(), writer.factCount())
            }
        }

        private suspend fun fetch(input: Input, season: Int?, known: Validators?, dest: File = File(workDir, Sources.fileName(input, season))): FetchResult =
            fetcher.fetch(Sources.url(input, season), dest, known) { read, total ->
                onProgress(IngestProgress.Downloading(season, input.label, read, total))
            }

        private suspend fun fetchPlayers() {
            val key = Sources.metaKey(Input.PLAYERS)
            val known = prior?.get(key)?.let(Validators::decode)?.takeIf { playersFile.isFile }
            when (val r = fetch(Input.PLAYERS, null, known, playersFile)) {
                is FetchResult.Downloaded -> meta[key] = r.validators.encode()
                FetchResult.NotModified -> meta[key] = checkNotNull(prior).getValue(key)
                FetchResult.NotPublished -> error("nflverse's player list isn't available")
            }
        }

        private suspend fun season(season: Int, writer: StatsDbWriter, crosswalk: Map<String, List<String>>) {
            onProgress(IngestProgress.Checking(season))
            val knownSeason = season in priorSeasons
            val first = SEASON_INPUTS.associateWith { input ->
                val known = if (knownSeason) prior?.get(Sources.metaKey(input, season))?.let(Validators::decode) else null
                fetch(input, season, known)
            }
            if (first.getValue(Input.PBP) == FetchResult.NotPublished) {
                skipped[season] = "play-by-play isn't published yet"
                return
            }
            val unchanged = knownSeason && first.all { (input, r) ->
                r == FetchResult.NotModified ||
                    (r == FetchResult.NotPublished && prior?.containsKey(Sources.metaKey(input, season)) != true)
            }
            if (unchanged) reuse(season, writer) else crunch(season, first, writer, crosswalk)
        }

        private fun reuse(season: Int, writer: StatsDbWriter) {
            val p = checkNotNull(prior)
            writer.copySeasonFrom(checkNotNull(previous), season)
            for (input in SEASON_INPUTS) {
                val key = Sources.metaKey(input, season)
                p[key]?.let { meta[key] = it }
            }
            p["expected_through_week:$season"]?.let { meta["expected_through_week:$season"] = it }
            reused += season
        }

        private suspend fun crunch(
            season: Int,
            first: Map<Input, FetchResult>,
            writer: StatsDbWriter,
            crosswalk: Map<String, List<String>>,
        ) {
            // A season is rebuilt from all of its files, so refetch any the first pass found unchanged.
            val files = first.mapValues { (input, r) ->
                val result = if (r == FetchResult.NotModified) fetch(input, season, known = null) else r
                (result as? FetchResult.Downloaded)?.also { meta[Sources.metaKey(input, season)] = it.validators.encode() }?.file
            }

            onProgress(IngestProgress.Crunching(season))
            val players = PlayerWeekAggregator()
            val defense = TeamDefenseAggregator()
            val pbp = checkNotNull(files[Input.PBP])
            var n = 0
            openInput(pbp).use { input ->
                readPlays(input, pbp.name) { play ->
                    if (++n % 5_000 == 0) job.ensureActive()
                    players.add(play)
                    defense.add(play)
                }
            }
            val weekly = players.rows().onEach { it.derive() }

            val snaps = files[Input.SNAP_COUNTS]
            if (snaps != null) {
                attachSnapShare(weekly, openInput(snaps).use { readSnaps(it, snaps.name) }, crosswalk)
            } else {
                warnings += "$season: no snap counts published yet"
            }

            val expectedFile = files[Input.EXPECTED]
            val expected = expectedFile?.let { f -> openInput(f).use { readExpected(it, f.name) } }
            val (through, coverage) = expectedCoverage(season, weekly, expected)
            meta["expected_through_week:$season"] = through.toString()
            coverage?.let { warnings += it }
            if (expected != null) {
                val problems = crossCheck(weekly, expected, warnings) + fantasyContract(weekly, expected, warnings)
                if (problems.isNotEmpty()) throw ValidationException(problems.map { "$season: $it" })
                writer.writeFacts(toFacts(expected.map { it.toPlayerWeek() }))
            } else {
                warnings += "$season: no expected-points data yet; xFP will be missing"
            }

            writer.writeFacts(toFacts(weekly))
            writer.writeTeamDefense(defense.rows())
            val injuries = files[Input.INJURIES]
            if (injuries != null) {
                writer.writeInjuries(openInput(injuries).use { readInjuries(it, injuries.name) })
            } else {
                warnings += "$season: no injury report published yet"
            }
            files.values.forEach { it?.delete() }
            built += season
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:ingest:test --tests "*IngestPipelineTest*"`
Expected: PASS (9 tests).

- [ ] **Step 5: Run the whole module's tests**

Run: `./gradlew :core:ingest:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/ingest/src
git commit -m "ingest: pipeline that reuses unchanged seasons and validates every build"
```

---
### Task 12: CLI, parity gate and CI switch

The parity gate is what proves the port. CI builds the 2025 season twice, once with the Python ETL and once with the Kotlin pipeline, and fails if any value differs. 2025 is complete, so its inputs don't change between runs.

This task also switches the rest of CI to the Kotlin build: the contract tests and the bundled APK now use a Kotlin-built database. The Python ETL stays only as the parity reference. `etl.yml` and the `data` release are left untouched here, because the app still uses them until Plan 2.

**Files:**
- Create: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/cli/IngestCli.kt`
- Modify: `core/ingest/build.gradle.kts` (add the `buildStatsDb` task)
- Create: `etl/tools/__init__.py` (empty)
- Create: `etl/tools/parity.py`
- Test: `etl/tests/test_parity.py`
- Modify: `.github/workflows/ci.yml`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: `IngestPipeline`, `IngestProgress` and `HttpFetcher` (Tasks 5 and 11).
- Produces:
  - `./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025" -Pout=path/stats.db`
  - `python etl/tools/parity.py <py.db> <kt.db>`, which exits 1 on any mismatch

- [ ] **Step 1: Write the failing parity-script test**

```python
# etl/tests/test_parity.py
"""The parity script is the gate between the Python ETL and the Kotlin port."""

import sqlite3

from tools.parity import compare


def _db(path, value: float, team: str = "AAA"):
    conn = sqlite3.connect(path)
    conn.executescript(
        """
        CREATE TABLE schema_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE player_week_stat (player_id TEXT, season INTEGER, week INTEGER, team TEXT,
                                       metric_id TEXT, value REAL);
        CREATE TABLE player (player_id TEXT, full_name TEXT, search_name TEXT, position TEXT,
                             team TEXT, pfr_player_id TEXT);
        CREATE TABLE metric (id TEXT, name TEXT, abbr TEXT, "group" TEXT, definition TEXT,
                             formula TEXT, positions TEXT, tier TEXT, predicts TEXT, stability REAL,
                             higher_is_better INTEGER, decimals INTEGER, hot INTEGER, internal INTEGER,
                             computed INTEGER, dist_family TEXT, zero_inflated INTEGER);
        CREATE TABLE team_week_defense (team TEXT, season INTEGER, week INTEGER, points_allowed REAL,
                                        yards_allowed REAL, sacks REAL, interceptions REAL,
                                        fumbles_recovered REAL, defensive_tds REAL);
        CREATE TABLE injury_report (player_id TEXT, season INTEGER, week INTEGER, team TEXT, name TEXT,
                                    position TEXT, status TEXT, injury TEXT, practice TEXT);
        INSERT INTO schema_meta VALUES ('seasons', '2025');
        INSERT INTO player VALUES ('p1', 'P One', 'p one', 'WR', 'AAA', NULL);
        """
    )
    conn.execute("INSERT INTO player_week_stat VALUES ('p1', 2025, 1, ?, 'target_share', ?)", (team, value))
    conn.commit()
    conn.close()


def test_identical_databases_pass(tmp_path):
    _db(tmp_path / "a.db", 0.25)
    _db(tmp_path / "b.db", 0.25 + 1e-12)
    assert compare(str(tmp_path / "a.db"), str(tmp_path / "b.db")) == []


def test_a_differing_value_is_reported(tmp_path):
    _db(tmp_path / "a.db", 0.25)
    _db(tmp_path / "b.db", 0.26)
    problems = compare(str(tmp_path / "a.db"), str(tmp_path / "b.db"))
    assert any("player_week_stat" in p and "differ" in p for p in problems)


def test_a_differing_team_is_reported(tmp_path):
    _db(tmp_path / "a.db", 0.25)
    _db(tmp_path / "b.db", 0.25, team="BBB")
    assert compare(str(tmp_path / "a.db"), str(tmp_path / "b.db")) != []


def test_missing_rows_are_reported(tmp_path):
    _db(tmp_path / "a.db", 0.25)
    _db(tmp_path / "b.db", 0.25)
    conn = sqlite3.connect(tmp_path / "b.db")
    conn.execute("DELETE FROM player_week_stat")
    conn.commit()
    conn.close()
    problems = compare(str(tmp_path / "a.db"), str(tmp_path / "b.db"))
    assert any("only in Python" in p for p in problems)
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd etl && python -m pytest tests/test_parity.py -q`
Expected: FAIL, with `ModuleNotFoundError: No module named 'tools'`.

- [ ] **Step 3: Implement the parity script**

Create an empty `etl/tools/__init__.py`, then:

```python
# etl/tools/parity.py
"""Compare a Python-built and a Kotlin-built stats.db; exit 1 on any mismatch.

    python etl/tools/parity.py build/py.db build/kt.db

Floats must agree to 1e-9 (absolute or relative): the two builds sum the same
values in a different order, nothing more.
"""

from __future__ import annotations

import math
import sqlite3
import sys

# table -> (key columns, compared columns)
TABLES: dict[str, tuple[list[str], list[str]]] = {
    "player_week_stat": (["player_id", "season", "week", "metric_id"], ["team", "value"]),
    "player": (["player_id"], ["full_name", "search_name", "position", "team", "pfr_player_id"]),
    "metric": (["id"], ["name", "abbr", '"group"', "definition", "formula", "positions", "tier",
                        "predicts", "stability", "higher_is_better", "decimals", "hot", "internal",
                        "computed", "dist_family", "zero_inflated"]),
    "team_week_defense": (["team", "season", "week"],
                          ["points_allowed", "yards_allowed", "sacks", "interceptions",
                           "fumbles_recovered", "defensive_tds"]),
    "injury_report": (["player_id", "season", "week"],
                      ["team", "name", "position", "status", "injury", "practice"]),
}
META_PREFIXES = ("seasons", "expected_through_week:")


def _same(a, b) -> bool:
    if isinstance(a, float) or isinstance(b, float):
        if a is None or b is None:
            return a is b
        return math.isclose(a, b, rel_tol=1e-9, abs_tol=1e-9)
    return a == b


def _rows(conn: sqlite3.Connection, table: str, keys: list[str], values: list[str]) -> dict:
    cols = ", ".join(keys + values)
    return {tuple(r[:len(keys)]): tuple(r[len(keys):])
            for r in conn.execute(f"SELECT {cols} FROM {table}")}


def compare(py_path: str, kt_path: str) -> list[str]:
    py, kt = sqlite3.connect(py_path), sqlite3.connect(kt_path)
    problems: list[str] = []
    for table, (keys, values) in TABLES.items():
        a, b = _rows(py, table, keys, values), _rows(kt, table, keys, values)
        only_py = sorted(a.keys() - b.keys())
        only_kt = sorted(b.keys() - a.keys())
        if only_py:
            problems.append(f"{table}: {len(only_py)} row(s) only in Python, e.g. {only_py[:5]}")
        if only_kt:
            problems.append(f"{table}: {len(only_kt)} row(s) only in Kotlin, e.g. {only_kt[:5]}")
        diffs = [(k, a[k], b[k]) for k in sorted(a.keys() & b.keys())
                 if not all(_same(x, y) for x, y in zip(a[k], b[k]))]
        if diffs:
            problems.append(f"{table}: {len(diffs)} row(s) differ, e.g. {diffs[:5]}")
        print(f"{table}: {len(a)} Python rows, {len(b)} Kotlin rows, {len(diffs)} differing")
    ma = dict(py.execute("SELECT key, value FROM schema_meta"))
    mb = dict(kt.execute("SELECT key, value FROM schema_meta"))
    for key in sorted(k for k in ma.keys() | mb.keys() if k.startswith(META_PREFIXES)):
        if ma.get(key) != mb.get(key):
            problems.append(f"schema_meta {key}: Python {ma.get(key)!r}, Kotlin {mb.get(key)!r}")
    return problems


def main(argv: list[str]) -> int:
    problems = compare(argv[1], argv[2])
    for p in problems:
        print("MISMATCH:", p)
    print("parity: OK" if not problems else f"parity: {len(problems)} mismatch(es)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd etl && python -m pytest tests/test_parity.py -q`
Expected: PASS (4 tests). `etl/tests` runs from the `etl/` directory, so `tools` imports as a package, the same way `gridiron_etl` does.

- [ ] **Step 5: Add the CLI and the Gradle task**

```kotlin
// core/ingest/src/main/kotlin/dev/gridiron/core/ingest/cli/IngestCli.kt
package dev.gridiron.core.ingest.cli

import dev.gridiron.core.ingest.HttpFetcher
import dev.gridiron.core.ingest.IngestPipeline
import dev.gridiron.core.ingest.IngestProgress
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

/**
 * Builds stats.db on a desktop JVM with exactly the code the phone runs:
 *
 *     ./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025" -Pout=etl/build/stats.db
 *
 * An unpublished season is skipped with a note; any other failure exits non-zero.
 */
public fun main(args: Array<String>) {
    val seasons = mutableListOf<Int>()
    var out: File? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--seasons" -> while (i + 1 < args.size && !args[i + 1].startsWith("--")) seasons += args[++i].toInt()
            "--out" -> out = File(args[++i])
            else -> error("unknown argument ${args[i]}")
        }
        i++
    }
    val target = requireNotNull(out) { "--out is required" }
    require(seasons.isNotEmpty()) { "--seasons is required" }

    val scratch = Files.createTempDirectory("gridiron-ingest").toFile()
    try {
        val pipeline = IngestPipeline(HttpFetcher(), File(scratch, "work"), File(scratch, "players.csv.gz"))
        val report = runBlocking {
            pipeline.build(seasons, previous = null, out = target) { p -> if (p !is IngestProgress.Downloading) println(p) }
        }
        report.warnings.forEach { println("WARNING $it") }
        report.skipped.forEach { (season, why) -> println("SKIPPED $season: $why") }
        println("built ${report.built}, ${report.facts} facts -> $target")
    } finally {
        scratch.deleteRecursively()
    }
}
```

Append to `core/ingest/build.gradle.kts`:

```kotlin
// ./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025" -Pout=etl/build/stats.db
tasks.register<JavaExec>("buildStatsDb") {
    group = "gridiron"
    description = "Builds stats.db from nflverse with the on-device ingest code."
    classpath = the<SourceSetContainer>()["main"].runtimeClasspath
    mainClass.set("dev.gridiron.core.ingest.cli.IngestCliKt")
    val seasons = providers.gradleProperty("seasons").orElse("").get().split(" ").filter { it.isNotBlank() }
    val out = rootDir.resolve(providers.gradleProperty("out").orElse("etl/build/stats.db").get())
    args = listOf("--seasons") + seasons + listOf("--out", out.path)
}
```

- [ ] **Step 6: Build one real season locally and run the parity check**

Run:

```bash
./gradlew :core:ingest:buildStatsDb -Pseasons=2025 -Pout=etl/build/parity/kt.db
cd etl && python -m gridiron_etl.build --seasons 2025 --out build/parity/py.db && cd ..
python etl/tools/parity.py etl/build/parity/py.db etl/build/parity/kt.db
```

Expected: `parity: OK`.

**If parity fails**, find the root cause in the port before continuing, using superpowers:systematic-debugging. The Python ETL is the reference. Pick one mismatched player-week and recompute it by hand from the play-by-play rows. Never loosen the tolerance or skip a table to get green.

- [ ] **Step 7: Switch CI to the Kotlin build and add the parity job**

Replace `.github/workflows/ci.yml` with:

```yaml
name: Build and test

# Builds the stats database with the on-device ingest code, runs every test
# against it, renders the Grid screenshots, and builds the installable app. On
# anything but a pull request it then publishes the APK to a fixed link:
#   https://github.com/Palm9999/Lame/releases/download/app/gridiron.apk
# The parity job proves the Kotlin ingest reproduces the Python ETL exactly.

on:
  push:
    paths:
      - "app/**"
      - "core/**"
      - "feature/**"
      - "build-logic/**"
      - "gradle/**"
      - "*.gradle.kts"
      - "gradle.properties"
      - "compose_stability.conf"
      - "etl/**"
      - ".github/workflows/ci.yml"
  pull_request:
  schedule:
    # Tuesdays 10:00 UTC, after Monday Night Football.
    - cron: "0 10 * * 2"
  workflow_dispatch:

permissions:
  contents: write

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

env:
  # Relative paths resolve from the repository root in every Gradle task.
  GRIDIRON_STATS_DB: etl/build/stats.db

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      - uses: actions/setup-python@v7
        with:
          python-version: "3.11"
          cache: pip
          cache-dependency-path: etl/requirements.txt
      - run: pip install -r etl/requirements.txt pytest

      - name: ETL tests
        working-directory: etl
        run: python -m pytest tests/ -q

      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: "21"

      - name: Android SDK platform
        run: yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-37.0" > /dev/null

      - uses: gradle/actions/setup-gradle@v6

      - name: Build the stats database
        # The current season and the two before it, built by the same Kotlin
        # code the phone runs. A season nflverse hasn't published is skipped.
        run: |
          SEASONS=$(python -c "import datetime as d; t = d.date.today(); s = t.year if t.month >= 9 else t.year - 1; print(s - 2, s - 1, s)")
          echo "Seasons: $SEASONS"
          ./gradlew :core:ingest:buildStatsDb -Pseasons="$SEASONS" -Pout=etl/build/stats.db

      - name: Test
        run: ./gradlew test

      - name: Screenshots
        run: ./gradlew :feature:players:recordRoborazziDebug

      - uses: actions/upload-artifact@v7
        with:
          name: screenshots
          path: feature/players/build/outputs/roborazzi/
          if-no-files-found: error

      - name: Build the app
        run: ./gradlew :app:assembleRelease

      - name: Publish the app
        if: github.event_name != 'pull_request'
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          cp app/build/outputs/apk/release/app-release.apk gridiron.apk
          SEASONS=$(python -c "import sqlite3; print(sqlite3.connect('etl/build/stats.db').execute(\"SELECT value FROM schema_meta WHERE key = 'seasons'\").fetchone()[0])")
          NOTES="Built $(date -u +%Y-%m-%d) from ${GITHUB_SHA::7}. Seasons: ${SEASONS}. Install gridiron.apk on your phone."
          gh release view app > /dev/null 2>&1 || gh release create app --title "Gridiron app" --notes "$NOTES"
          gh release edit app --notes "$NOTES"
          gh release upload app gridiron.apk --clobber

  parity:
    # 2025 is a finished season, so both builds read identical inputs.
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-python@v7
        with:
          python-version: "3.11"
          cache: pip
          cache-dependency-path: etl/requirements.txt
      - run: pip install -r etl/requirements.txt
      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: "21"
      - name: Android SDK platform
        run: yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-37.0" > /dev/null
      - uses: gradle/actions/setup-gradle@v6
      - name: Python build (reference)
        working-directory: etl
        run: python -m gridiron_etl.build --seasons 2025 --out build/parity/py.db
      - name: Kotlin build
        run: ./gradlew :core:ingest:buildStatsDb -Pseasons=2025 -Pout=etl/build/parity/kt.db
      - name: Compare
        run: python etl/tools/parity.py etl/build/parity/py.db etl/build/parity/kt.db
```

- [ ] **Step 8: Document the module in CLAUDE.md**

In the **JVM Modules** list, after the `:core:projections` line, add:

```markdown
- `:core:ingest` — Builds `stats.db` from nflverse and ffopportunity: a streaming CSV reader, Kotlin ports of the ETL's transforms and validation, and a pipeline that re-downloads only files whose ETag changed and copies unchanged seasons from the previous build. Runs on the phone and on the JVM (`./gradlew :core:ingest:buildStatsDb -Pseasons="2025" -Pout=etl/build/stats.db`); CI's parity job holds it to the Python ETL's values
```

Under **Common Commands → Data Pipeline**, after the `python -m gridiron_etl.build ...` line, add:

```bash
# Same database, built by the Kotlin code the phone runs
./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025 2026" -Pout=etl/build/stats.db

# Prove the two agree (CI runs this for 2025)
python etl/tools/parity.py etl/build/parity/py.db etl/build/parity/kt.db
```

- [ ] **Step 9: Run everything**

Run:

```bash
cd etl && python -m pytest tests/ -q && cd ..
./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025 2026" -Pout=etl/build/stats.db
GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew build
```

Expected:
- All ETL tests pass.
- The database builds (an unpublished season is skipped).
- `./gradlew build` is green, including `:core:statquery`'s contract tests against the Kotlin-built database.

- [ ] **Step 10: Commit**

```bash
git add core/ingest etl/tools etl/tests/test_parity.py .github/workflows/ci.yml CLAUDE.md
git commit -m "ingest: CLI build, Python parity gate, and CI on the Kotlin-built database"
```
