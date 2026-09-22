package dev.gridiron.core.statquery

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement

/**
 * An in-memory SQLite database with the ETL's schema, for executing generated
 * SQL against real SQLite rather than asserting on SQL strings.
 */
internal class FixtureDb : AutoCloseable {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    init {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """CREATE TABLE player (
                     player_id TEXT PRIMARY KEY, full_name TEXT NOT NULL,
                     search_name TEXT NOT NULL, position TEXT, team TEXT, pfr_player_id TEXT)""",
            )
            st.executeUpdate(
                """CREATE TABLE player_week_stat (
                     player_id TEXT NOT NULL, season INTEGER NOT NULL, week INTEGER NOT NULL,
                     team TEXT, metric_id TEXT NOT NULL, value REAL NOT NULL,
                     PRIMARY KEY (player_id, season, week, metric_id)) WITHOUT ROWID""",
            )
            st.executeUpdate("CREATE INDEX idx_pws_metric_season_week ON player_week_stat (metric_id, season, week)")
            st.executeUpdate("CREATE INDEX idx_player_search ON player (search_name)")
        }
    }

    fun player(id: String, name: String, position: String = "WR", team: String = "AAA") {
        conn.prepareStatement("INSERT INTO player VALUES (?, ?, ?, ?, ?, NULL)").use {
            it.setString(1, id)
            it.setString(2, name)
            it.setString(3, normalizeSearch(name))
            it.setString(4, position)
            it.setString(5, team)
            it.executeUpdate()
        }
    }

    /** One played week: records `g = 1` plus the given component values. */
    fun week(id: String, week: Int, vararg stats: Pair<Component, Number>, season: Int = 2025) {
        val all = listOf(Components.GAMES to 1) + stats
        conn.prepareStatement("INSERT INTO player_week_stat VALUES (?, ?, ?, 'AAA', ?, ?)").use { ps ->
            for ((component, value) in all) {
                ps.setString(1, id)
                ps.setInt(2, season)
                ps.setInt(3, week)
                ps.setString(4, component.id)
                ps.setDouble(5, value.toDouble())
                ps.executeUpdate()
            }
        }
    }

    fun rows(query: SqlQuery): List<List<Any?>> =
        conn.prepareStatement(query.sql).use { ps ->
            ps.bindAll(query.binds)
            ps.executeQuery().use { rs ->
                val width = rs.metaData.columnCount
                buildList {
                    while (rs.next()) add((1..width).map { rs.getObject(it) })
                }
            }
        }

    fun grid(spec: StatQuerySpec): List<GridRow> {
        val q = StatQueryBuilder.grid(spec)
        return rows(q.query).map { GridRow(it, q.layout) }
    }

    fun count(spec: StatQuerySpec): Int = (rows(StatQueryBuilder.count(spec)).single().single() as Number).toInt()

    fun scalar(sql: String): Any? = conn.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> rs.next(); rs.getObject(1) }
    }

    override fun close() = conn.close()
}

internal fun PreparedStatement.bindAll(binds: List<Bind>) {
    binds.forEachIndexed { i, bind ->
        when (bind) {
            is Bind.Text -> setString(i + 1, bind.value)
            is Bind.Integer -> setLong(i + 1, bind.value)
            is Bind.Real -> setDouble(i + 1, bind.value)
        }
    }
}

internal class GridRow(private val cells: List<Any?>, private val layout: GridLayout) {
    val playerId: String get() = cells[GridLayout.PLAYER_ID] as String
    val name: String get() = cells[GridLayout.FULL_NAME] as String
    val games: Double get() = (cells[GridLayout.GAMES] as Number).toDouble()

    fun value(column: StatColumn): Double? = (cells[layout.valueIndex(column)] as Number?)?.toDouble()

    fun percentile(column: StatColumn): Double? = (cells[layout.percentileIndex(column)] as Number?)?.toDouble()
}
