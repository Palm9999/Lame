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
