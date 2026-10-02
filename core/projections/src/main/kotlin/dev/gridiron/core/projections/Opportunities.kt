package dev.gridiron.core.projections

import java.time.Duration
import java.time.Instant

/**
 * A player's recent role: [usage] over the last few games (QB dropbacks; RB carries plus targets; WR and TE targets),
 * the [games] he played in them and his fantasy points a game, null with none.
 */
public data class UsageRow(
    val playerId: String,
    val name: String,
    val position: String,
    val team: String,
    val usage: Double,
    val games: Int,
    val pointsPerGame: Double?,
)

/** ESPN's status code for a player (`D`, `O`, `IR`, `Q`, …) and when ESPN dated it. */
public data class InjuryFlag(val abbr: String, val updatedAt: Instant?)

/** A hurt starter: [rank] is his place in his team's depth at the position by recent usage. */
public data class InjuredStarter(val playerId: String, val name: String, val rank: Int, val abbr: String)

/** A healthy player moving up from depth [fromRank] to [toRank] because the [injured] starters ahead of him are hurt. */
public data class Beneficiary(val player: UsageRow, val fromRank: Int, val toRank: Int, val injured: List<InjuredStarter>)

public object Opportunities {
    private val OUT = setOf("D", "O", "IR")
    private val FRESH: Duration = Duration.ofDays(7)

    /** Doubtful, Out or IR; Questionable only when ESPN dated the status within the last week. */
    public fun isHurt(flag: InjuryFlag?, now: Instant): Boolean = when {
        flag == null -> false
        flag.abbr in OUT -> true
        flag.abbr == "Q" -> flag.updatedAt != null && flag.updatedAt >= now.minus(FRESH)
        else -> false
    }

    /**
     * Who moves into a starting role. Each team's players at a position are ranked by [UsageRow.usage] (then points a
     * game, then id); the top one at QB and the top two at RB, WR and TE are its starters. When some are hurt
     * ([injuries] by player id), every healthy player who now ranks inside the top N higher than he did is a
     * beneficiary of the hurt starters ahead of him. Best new role first, then by team and id.
     */
    public fun find(usage: List<UsageRow>, injuries: Map<String, InjuryFlag>, now: Instant): List<Beneficiary> =
        usage.groupBy { it.team to it.position }.flatMap { (key, group) ->
            val starters = if (key.second == "QB") 1 else 2
            val ranked = group.sortedWith(compareByDescending<UsageRow> { it.usage }.thenByDescending { it.pointsPerGame ?: Double.NEGATIVE_INFINITY }.thenBy { it.playerId })
            fun hurt(p: UsageRow) = isHurt(injuries[p.playerId], now)
            val hurtStarters = ranked.take(starters).withIndex().filter { hurt(it.value) }
            if (hurtStarters.isEmpty()) return@flatMap emptyList()
            ranked.filterNot(::hurt).take(starters).mapIndexedNotNull { healthyIndex, player ->
                val was = ranked.indexOf(player)
                if (healthyIndex >= was) return@mapIndexedNotNull null
                Beneficiary(
                    player = player,
                    fromRank = was + 1,
                    toRank = healthyIndex + 1,
                    injured = hurtStarters.filter { it.index < was }
                        .map { InjuredStarter(it.value.playerId, it.value.name, it.index + 1, injuries.getValue(it.value.playerId).abbr) },
                )
            }
        }.sortedWith(compareBy<Beneficiary> { it.toRank }.thenBy { it.player.team }.thenBy { it.player.playerId })
}
