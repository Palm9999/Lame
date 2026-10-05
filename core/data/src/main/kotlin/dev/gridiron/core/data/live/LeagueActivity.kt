package dev.gridiron.core.data.live

public enum class ActivityKind { ADD, TRADE }

/** One player moving between teams; team 0 is free agency. [playerId] and [name] are filled in by the repository. */
public data class ActivityMove(val espnId: String, val playerId: String?, val name: String?, val fromTeamId: Int, val toTeamId: Int)

/** An executed waiver claim, free-agent move or trade in [week]; [teamId] made it, [bid] is a waiver's FAAB bid. */
public data class ActivityItem(
    val id: String,
    val week: Int,
    val kind: ActivityKind,
    val teamId: Int,
    val bid: Int?,
    val processedAtMillis: Long?,
    val moves: List<ActivityMove>,
)

/** The league's moves, newest first, with the user's team and every team's name; [error] says why they can't be read. */
public data class ActivityResult(val items: List<ActivityItem>, val myTeamId: Int?, val teams: Map<Int, String>, val error: String?)
