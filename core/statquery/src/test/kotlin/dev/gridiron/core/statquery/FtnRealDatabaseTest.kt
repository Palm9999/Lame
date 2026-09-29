package dev.gridiron.core.statquery

import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.StatColumn.FTN_CATCHABLE_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_DROP_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_PLAY_ACTION_RATE
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.io.Reader
import java.sql.Connection
import java.sql.DriverManager

/**
 * A Grid column over a week range is the ratio of FTN's own summed counts, computed here straight from the
 * published FTN csv joined to play-by-play, not the mean of the weekly rates. Needs the ETL's download cache
 * (`~/.cache/gridiron`, or GRIDIRON_CACHE), which the Python build fills; skipped without it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "GRIDIRON_STATS_DB", matches = ".+")
class FtnRealDatabaseTest {
    private lateinit var conn: Connection

    @BeforeAll
    fun open() {
        conn = DriverManager.getConnection("jdbc:sqlite:file:${System.getenv("GRIDIRON_STATS_DB")}?mode=ro")
    }

    @AfterAll
    fun close() = conn.close()

    private val season = 2025
    private val weeks = WeekRange(1, 8)

    private fun cached(name: String): File {
        val cache = System.getenv("GRIDIRON_CACHE")?.let(::File) ?: File(System.getProperty("user.home"), ".cache/gridiron")
        return File(cache, name).also { assumeTrue(it.isFile, "$it isn't cached; run the Python build once") }
    }

    /** Streams RFC 4180 records (quoted fields may hold commas, quotes and line breaks) to [onRecord]. */
    private fun forEachRecord(reader: Reader, onRecord: (List<String>) -> Unit) {
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var c = reader.read()
        while (c != -1) {
            val ch = c.toChar()
            when {
                quoted && ch == '"' -> {
                    c = reader.read()
                    if (c == '"'.code) field.append('"') else { quoted = false; continue }
                }
                quoted -> field.append(ch)
                ch == '"' -> quoted = true
                ch == ',' -> { fields += field.toString(); field.setLength(0) }
                ch == '\n' -> { fields += field.toString(); field.setLength(0); onRecord(fields.toList()); fields.clear() }
                ch != '\r' -> field.append(ch)
            }
            c = reader.read()
        }
        if (field.isNotEmpty() || fields.isNotEmpty()) { fields += field.toString(); onRecord(fields.toList()) }
    }

    private fun records(file: File, wanted: Set<String>): List<Map<String, String>> = buildList {
        var header: List<String>? = null
        file.bufferedReader().use { r ->
            forEachRecord(r) { rec ->
                val h = header
                if (h == null) header = rec
                else add(h.indices.filter { h[it] in wanted && it < rec.size }.associate { h[it] to rec[it] })
            }
        }
    }

    /** FTN's flags keyed by play, for the season. */
    private val flags: Map<Pair<String, String>, Map<String, String>> by lazy {
        val cols = setOf("nflverse_game_id", "nflverse_play_id", "is_catchable_ball", "is_drop", "is_play_action", "n_blitzers")
        records(cached("ftn_charting_$season.csv"), cols).associateBy { it.getValue("nflverse_game_id") to it.getValue("nflverse_play_id") }
    }

    /** Regular-season plays of weeks 1-8 that are eligible and that FTN charted, with FTN's flags folded in. */
    private val plays: List<Map<String, String>> by lazy {
        val cols = setOf(
            "game_id", "play_id", "season_type", "week", "play_type", "posteam", "two_point_attempt",
            "receiver_player_id", "passer_player_id", "pass_attempt", "sack", "qb_scramble",
        )
        records(cached("play_by_play_$season.csv"), cols).mapNotNull { p ->
            val ftn = flags[p.getValue("game_id") to p.getValue("play_id")] ?: return@mapNotNull null
            val eligible = p["season_type"] == "REG" && p["week"]!!.toDouble().toInt() in 1..8 &&
                p["play_type"] in setOf("pass", "run") && !p["posteam"].isNullOrEmpty() &&
                (p["two_point_attempt"]?.toDoubleOrNull() ?: 0.0) == 0.0
            if (eligible) p + ftn else null
        }
    }

    private fun gridValue(playerId: String, column: StatColumn): Double? {
        val spec = StatQuerySpec(season = season, weeks = weeks, columns = listOf(column), playerIds = setOf(playerId))
        val q = StatQueryBuilder.grid(spec)
        val rows = conn.prepareStatement(q.query.sql).use { ps ->
            ps.bindAll(q.query.binds)
            ps.executeQuery().use { rs ->
                val n = rs.metaData.columnCount
                buildList { while (rs.next()) add((1..n).map { rs.getObject(it) }) }
            }
        }
        return rows.singleOrNull()?.let { GridRow(it, q.layout).value(column) }
    }

    private fun Map<String, String>.flag(name: String) = if (this[name] == "TRUE") 1.0 else 0.0

    @Test
    fun `a receiver's catchable and drop rates over weeks 1-8 are summed counts over summed charted targets`() {
        val targets = plays.filter { !it["receiver_player_id"].isNullOrEmpty() }.groupBy { it.getValue("receiver_player_id") }
        val (id, mine) = targets.maxBy { it.value.size }
        val n = mine.size.toDouble()
        assertEquals(mine.sumOf { it.flag("is_catchable_ball") } / n, gridValue(id, FTN_CATCHABLE_RATE)!!, 1e-9)
        assertEquals(mine.sumOf { it.flag("is_drop") } / n, gridValue(id, FTN_DROP_RATE)!!, 1e-9)
    }

    @Test
    fun `a passer's play-action rate over weeks 1-8 is per charted dropback`() {
        fun weight(p: Map<String, String>) =
            (p["pass_attempt"]?.toDoubleOrNull() ?: 0.0) + (p["sack"]?.toDoubleOrNull() ?: 0.0) +
                if ((p["qb_scramble"]?.toDoubleOrNull() ?: 0.0) == 1.0) 1.0 else 0.0
        val dropbacks = plays.filter { !it["passer_player_id"].isNullOrEmpty() && weight(it) > 0 }.groupBy { it.getValue("passer_player_id") }
        val (id, mine) = dropbacks.maxBy { (_, v) -> v.sumOf(::weight) }
        val expected = mine.sumOf { weight(it) * it.flag("is_play_action") } / mine.sumOf(::weight)
        assertEquals(expected, gridValue(id, FTN_PLAY_ACTION_RATE)!!, 1e-9)
    }
}
