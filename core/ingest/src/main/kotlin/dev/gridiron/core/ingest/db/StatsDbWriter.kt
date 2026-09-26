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
