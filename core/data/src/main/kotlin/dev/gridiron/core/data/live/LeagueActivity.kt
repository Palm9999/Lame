package dev.gridiron.core.data.live

public enum class ActivityKind { ADD, TRADE }

/** One player moving between teams; team 0 is free agency. [playerId], [name] and [position] are filled in by the repository. */
public data class ActivityMove(
    val espnId: String,
    val playerId: String?,
    val name: String?,
    val fromTeamId: Int,
    val toTeamId: Int,
    val position: String? = null,
)

/** An executed waiver claim, free-agent move or trade in [week]; [teamId] made it, [bid] is a waiver's FAAB bid. */
public data class ActivityItem(
    val id: String,
    val week: Int,
    val kind: ActivityKind,
    val teamId: Int,
    val bid: Int?,
    val processedAtMillis: Long?,
    val moves: List<ActivityMove>,
    /** A waiver claim (ESPN's WAIVER), not a free-agent pickup. */
    val waiver: Boolean = false,
)

/**
 * The league's moves, newest first, with the user's team and every team's name; [error] says why they can't be read.
 * [faabBudget] is each team's FAAB budget, null when the league doesn't bid.
 */
public data class ActivityResult(
    val items: List<ActivityItem>,
    val myTeamId: Int?,
    val teams: Map<Int, String>,
    val error: String?,
    val faabBudget: Int? = null,
)

/** One position's winning waiver bids this season: how many [claims], the [median] and [top] bid in dollars. */
public data class FaabPrice(val position: String, val claims: Int, val median: Int, val top: Int)

/**
 * What winning waiver claims cost by position: each claim's added player's position (a claim adding no one known is
 * left out), a claim without a bid counting $0. Most claims first.
 */
public fun faabMarket(items: List<ActivityItem>): List<FaabPrice> =
    items.filter { it.waiver }
        .mapNotNull { item -> item.moves.firstOrNull { it.toTeamId == item.teamId }?.position?.let { it to (item.bid ?: 0) } }
        .groupBy({ it.first }, { it.second })
        .map { (position, bids) ->
            val sorted = bids.sorted()
            FaabPrice(position, sorted.size, sorted[(sorted.size - 1) / 2], sorted.last())
        }
        .sortedWith(compareByDescending<FaabPrice> { it.claims }.thenBy { it.position })

/** One player's adds from free agency and drops to it in the user's own league over a window. */
public data class LeagueTrend(val espnId: String, val playerId: String?, val name: String?, val adds: Int, val drops: Int)

/**
 * Waiver trends inside the user's league: every move processed at or after [sinceMillis] (a move with no processing
 * time is left out), adds (free agency to a team) and drops (a team to free agency) counted by player.
 */
public fun leagueTrends(items: List<ActivityItem>, sinceMillis: Long): List<LeagueTrend> =
    items.filter { (it.processedAtMillis ?: return@filter false) >= sinceMillis }
        .flatMap { it.moves }
        .filter { (it.fromTeamId == 0) != (it.toTeamId == 0) }
        .groupBy { it.espnId }
        .map { (id, moves) ->
            LeagueTrend(id, moves.firstNotNullOfOrNull { it.playerId }, moves.firstNotNullOfOrNull { it.name }, moves.count { it.fromTeamId == 0 }, moves.count { it.toTeamId == 0 })
        }
