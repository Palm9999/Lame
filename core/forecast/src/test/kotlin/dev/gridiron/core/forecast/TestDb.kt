package dev.gridiron.core.forecast

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File

/**
 * A stats database holding only the tables the forecast reads and writes,
 * filled by hand. The DDL is `:core:ingest`'s `Schema.kt` for these tables.
 */
internal class TestDb(val file: File) : AutoCloseable {
    val conn: SQLiteConnection = BundledSQLiteDriver().open(file.path)

    init {
        for (ddl in DDL) conn.execSQL(ddl)
    }

    fun meta(key: String, value: String) = exec("INSERT OR REPLACE INTO schema_meta (key, value) VALUES (?, ?)", key, value)

    fun player(id: String, position: String, team: String?) = exec(
        "INSERT INTO player (player_id, full_name, search_name, position, team) VALUES (?, ?, ?, ?, ?)",
        id, "Player $id", "player ${id.lowercase()}", position, team,
    )

    /** One played week for [id]: `g` = 1 plus [values]. */
    fun week(id: String, season: Int, week: Int, team: String, vararg values: Pair<String, Double>) {
        for ((metric, value) in listOf("g" to 1.0) + values) {
            exec(
                "INSERT OR REPLACE INTO player_week_stat (player_id, season, week, team, metric_id, value) VALUES (?, ?, ?, ?, ?, ?)",
                id, season, week, team, metric, value,
            )
        }
    }

    fun game(
        season: Int,
        week: Int,
        home: String,
        away: String,
        played: Boolean = true,
        spread: Double? = null,
        total: Double? = null,
        homeQb: String? = null,
        awayQb: String? = null,
        homeCoach: String? = null,
        awayCoach: String? = null,
        type: String = "REG",
    ) = exec(
        """INSERT INTO game (game_id, season, week, game_type, home_team, away_team, home_score, away_score,
           spread_line, total_line, home_qb_id, away_qb_id, home_coach, away_coach)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        "${season}_${week}_${away}_$home", season, week, type, home, away,
        if (played) 21 else null, if (played) 17 else null, spread, total, homeQb, awayQb, homeCoach, awayCoach,
    )

    fun injury(id: String, season: Int, week: Int, status: String?) = exec(
        "INSERT OR REPLACE INTO injury_report (player_id, season, week, status) VALUES (?, ?, ?, ?)",
        id, season, week, status,
    )

    fun query(sql: String): List<List<String?>> = conn.prepare(sql).use { st ->
        buildList {
            while (st.step()) add((0 until st.getColumnCount()).map { if (st.isNull(it)) null else st.getText(it) })
        }
    }

    fun exec(sql: String, vararg binds: Any?) {
        conn.prepare(sql).use { st ->
            binds.forEachIndexed { i, b ->
                when (b) {
                    null -> st.bindNull(i + 1)
                    is String -> st.bindText(i + 1, b)
                    is Int -> st.bindLong(i + 1, b.toLong())
                    is Long -> st.bindLong(i + 1, b)
                    is Double -> st.bindDouble(i + 1, b)
                    else -> error("can't bind $b")
                }
            }
            st.step()
        }
    }

    override fun close() = conn.close()

    companion object {
        val DDL = listOf(
            "CREATE TABLE schema_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
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
            """CREATE TABLE player_week_signal (
                player_id TEXT NOT NULL, season INTEGER NOT NULL, week INTEGER NOT NULL,
                score REAL NOT NULL, usage_recent REAL NOT NULL, usage_base REAL NOT NULL,
                xp_recent REAL, xp_base REAL, vacated REAL NOT NULL, out_note TEXT,
                PRIMARY KEY (player_id, season, week)) WITHOUT ROWID""",
            """CREATE TABLE game (
                game_id TEXT PRIMARY KEY, season INTEGER NOT NULL, week INTEGER NOT NULL, game_type TEXT NOT NULL,
                home_team TEXT NOT NULL, away_team TEXT NOT NULL, home_score INTEGER, away_score INTEGER,
                spread_line REAL, total_line REAL, roof TEXT, home_qb_id TEXT, away_qb_id TEXT,
                home_coach TEXT, away_coach TEXT) WITHOUT ROWID""",
            """CREATE TABLE injury_report (
                player_id TEXT NOT NULL, season INTEGER NOT NULL, week INTEGER NOT NULL,
                team TEXT, name TEXT, position TEXT, status TEXT, injury TEXT, practice TEXT,
                PRIMARY KEY (player_id, season, week)) WITHOUT ROWID""",
        )
    }
}
