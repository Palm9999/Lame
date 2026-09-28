package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.pbp.PlayerWeek
import dev.gridiron.core.ingest.pbp.TeamDefenseRow
import dev.gridiron.core.statquery.normalizeSearch

/** A team's defense and special teams as one pseudo-player, so fantasy D/ST is scored like any player. */
internal fun dstPlayerId(team: String): String = "DST_$team"

internal fun dstPlayer(team: String): PlayerInfo {
    val name = "$team D/ST"
    return PlayerInfo(dstPlayerId(team), name, normalizeSearch(name), "DST", team, pfrPlayerId = null, espnId = null)
}

/**
 * Each team-week as its D/ST's week, reproducing `teams.dst_weekly`. TDs are
 * the defense's plus kickoff returns. Points allowed are stored as a number:
 * the scoring profile's own tiers score them.
 */
internal fun dstWeeks(rows: List<TeamDefenseRow>): List<PlayerWeek> = rows.map { r ->
    val values = linkedMapOf<String, Double?>(
        "g" to 1.0,
        "dst_sacks" to r.sacks,
        "dst_interceptions" to r.interceptions,
        "dst_fumble_recoveries" to r.fumblesRecovered,
        "dst_tds" to r.defensiveTds + r.kickReturnTds,
        "dst_safeties" to r.safeties,
        "points_allowed" to r.pointsAllowed,
    )
    PlayerWeek(r.season, r.week, r.team, dstPlayerId(r.team), values)
}
