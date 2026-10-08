package dev.gridiron.core.ingest.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.gridiron.core.ingest.Fact
import dev.gridiron.core.ingest.GameRow
import dev.gridiron.core.ingest.InjuryRow
import dev.gridiron.core.ingest.Metric
import dev.gridiron.core.ingest.PlayerInfo
import dev.gridiron.core.ingest.dstPlayer
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

    /** ESPN's projections, keyed by our player id: (player, season, week, metric) to value. */
    fun writeEspnProjections(rows: List<Fact>) = insert(
        "INSERT OR REPLACE INTO espn_projection (player_id, season, week, metric_id, value) VALUES (?, ?, ?, ?, ?)",
        rows.sortedWith(compareBy({ it.playerId }, { it.season }, { it.week }, { it.metricId })),
    ) { st, f ->
        st.bindText(1, f.playerId)
        st.bindLong(2, f.season.toLong())
        st.bindLong(3, f.week.toLong())
        st.bindText(4, f.metricId)
        st.bindDouble(5, f.value)
    }

    /**
     * Copies [season]'s ESPN projections out of [previous], and whether it had any. A previous database
     * without the table (an older build) has none, and so does a damaged one: the caller downloads them.
     */
    fun copyEspnProjectionsFrom(previous: File, season: Int): Boolean = try {
        connection.prepare("ATTACH DATABASE ? AS prev").use {
            it.bindText(1, previous.path)
            it.step()
        }
        try {
            connection.prepare("INSERT OR REPLACE INTO espn_projection SELECT * FROM prev.espn_projection WHERE season = ?").use {
                it.bindLong(1, season.toLong())
                it.step()
            }
            connection.prepare("SELECT COUNT(*) FROM espn_projection WHERE season = ?").use {
                it.bindLong(1, season.toLong())
                it.step()
                it.getLong(0) > 0
            }
        } finally {
            connection.execSQL("DETACH DATABASE prev")
        }
    } catch (e: Exception) {
        false
    }

    fun writeTeamDefense(rows: List<TeamDefenseRow>) = insert(
        """INSERT OR REPLACE INTO team_week_defense (team, season, week, points_allowed, yards_allowed,
           sacks, interceptions, fumbles_recovered, defensive_tds, safeties, kick_return_tds)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
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
        st.bindDouble(10, r.safeties)
        st.bindDouble(11, r.kickReturnTds)
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

    /**
     * Copies one season's facts, team defense and injury report out of
     * [previous]. Only valid when [previous] has this [INGEST_VERSION], which
     * guarantees identical table shapes. If the copy fails (a damaged
     * [previous]), it throws with nothing of the season left behind, so the
     * caller can rebuild it.
     */
    fun copySeasonFrom(previous: File, season: Int) {
        connection.prepare("ATTACH DATABASE ? AS prev").use {
            it.bindText(1, previous.path)
            it.step()
        }
        val tables = listOf("player_week_stat", "team_week_defense", "injury_report")
        var failure: Throwable? = null
        try {
            transaction {
                for (table in tables) {
                    connection.prepare("INSERT OR REPLACE INTO $table SELECT * FROM prev.$table WHERE season = ?").use {
                        it.bindLong(1, season.toLong())
                        it.step()
                    }
                }
            }
        } catch (t: Throwable) {
            failure = t
            // The journal is off, so ROLLBACK is undefined: close the transaction and remove what the copy
            // wrote, leaving the season empty for the caller to rebuild.
            runCatching { connection.execSQL("COMMIT") }
            for (table in tables) {
                connection.prepare("DELETE FROM $table WHERE season = ?").use {
                    it.bindLong(1, season.toLong())
                    it.step()
                }
            }
            throw t
        } finally {
            // A DETACH that fails after the copy failed must not hide why the copy failed.
            try {
                connection.execSQL("DETACH DATABASE prev")
            } catch (e: Exception) {
                failure?.addSuppressed(e) ?: throw e
            }
        }
    }

    /**
     * Keeps players with stats in `player`, each team's D/ST among them, drops facts for ids nflverse doesn't
     * list (as the Python build does), and maps every ESPN id. Returns how many
     * facts were dropped.
     */
    fun writePlayers(players: List<PlayerInfo>): Int {
        var dropped = 0
        transaction {
            connection.execSQL(
                """CREATE TEMP TABLE all_player (player_id TEXT PRIMARY KEY, full_name TEXT NOT NULL,
                   search_name TEXT NOT NULL, position TEXT, team TEXT, pfr_player_id TEXT, espn_id TEXT, birth_date TEXT)""",
            )
            connection.prepare("INSERT OR IGNORE INTO all_player VALUES (?, ?, ?, ?, ?, ?, ?, ?)").use { st ->
                for (p in players + defenseTeams().map(::dstPlayer)) {
                    st.bindText(1, p.playerId)
                    st.bindText(2, p.fullName)
                    st.bindText(3, p.searchName)
                    st.bindTextOrNull(4, p.position)
                    st.bindTextOrNull(5, p.team)
                    st.bindTextOrNull(6, p.pfrPlayerId)
                    st.bindTextOrNull(7, p.espnId)
                    st.bindTextOrNull(8, p.birthDate)
                    st.step()
                    st.reset()
                }
            }
            connection.execSQL("DELETE FROM player_week_stat WHERE player_id NOT IN (SELECT player_id FROM all_player)")
            dropped = connection.prepare("SELECT changes()").use { it.step(); it.getLong(0).toInt() }
            connection.execSQL(
                """INSERT OR REPLACE INTO player (player_id, full_name, search_name, position, team, pfr_player_id, birth_date)
                   SELECT player_id, full_name, search_name, position, team, pfr_player_id, birth_date FROM all_player
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

    private fun defenseTeams(): List<String> =
        connection.prepare("SELECT DISTINCT team FROM team_week_defense ORDER BY team").use { st ->
            buildList { while (st.step()) add(st.getText(0)) }
        }

    /**
     * Fills `window_def` and `player_window_stat` from `player_week_stat` for every season: `S` is weeks 1 through
     * the last regular-season week played (the Grid's default range), `L<N>` the N weeks ending there, clipped at
     * week 1. The last week played is the newest `g` row, as the Grid's season list reads it. Playoff weeks are
     * outside every window, and so are [UNWINDOWED_METRICS].
     */
    fun writeWindows() {
        val lastPlayed = connection.prepare("SELECT season, MAX(week) FROM player_week_stat WHERE metric_id = 'g' GROUP BY season").use { st ->
            buildList { while (st.step()) add(st.getLong(0).toInt() to st.getLong(1).toInt()) }
        }
        transaction {
            for ((season, played) in lastPlayed) {
                val last = minOf(played, lastRegularSeasonWeek(season))
                val windows = listOf(WINDOW_SEASON to 1) + WINDOWS_LAST.map { "L$it" to maxOf(1, last - it + 1) }
                for ((window, first) in windows) {
                    execute("INSERT INTO window_def (season, window, first_week, last_week) VALUES (?, ?, ?, ?)", season, window, first, last)
                    execute(
                        """INSERT INTO player_window_stat (player_id, season, window, metric_id, value)
                           SELECT player_id, season, ?, metric_id, SUM(value) FROM player_week_stat
                           WHERE season = ? AND week BETWEEN ? AND ?
                             AND metric_id NOT IN (${UNWINDOWED_METRICS.joinToString { "?" }})
                           GROUP BY player_id, metric_id""",
                        window, season, first, last, *UNWINDOWED_METRICS.toTypedArray(),
                    )
                }
            }
        }
    }

    /** Windows, indexes, provenance, then ANALYZE so the planner has statistics on the first query. */
    fun finish(seasons: Collection<Int>, meta: Map<String, String>, builtAt: Instant) {
        writeWindows()
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
        // The bundled driver is built with STAT4; Python's sqlite3 isn't. Its
        // samples steer scored grids off the metric index onto a primary-key
        // scan (2x slower), so keep only sqlite_stat1, as the ETL does.
        connection.execSQL("DROP TABLE IF EXISTS sqlite_stat4")
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

private fun SQLiteStatement.bindLongOrNull(index: Int, value: Int?) {
    if (value == null) bindNull(index) else bindLong(index, value.toLong())
}

private fun SQLiteStatement.bindDoubleOrNull(index: Int, value: Double?) {
    if (value == null) bindNull(index) else bindDouble(index, value)
}

private fun Boolean.toLong(): Long = if (this) 1L else 0L
