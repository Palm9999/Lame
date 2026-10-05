package dev.gridiron.core.projections

/** Where a player was drafted: the [round], and whether that pick was itself a keeper. */
public data class KeeperPick(val round: Int, val keeper: Boolean)

/** A player you could keep: his draft [pick] (null when never drafted), his redraft rank and his dynasty value (null when unvalued). */
public data class KeeperCandidate(val playerId: String, val pick: KeeperPick?, val redraftRank: Int?, val dynasty: Int?)

/** A keeper choice: the [cost] round, the round he's [worth] (null when unvalued), the [surplus] between them, and whether to [keep] him. */
public data class KeeperRow(val playerId: String, val cost: Int, val worth: Int?, val surplus: Int?, val dynasty: Int?, val keep: Boolean)

/** Keepers priced by draft round: keep the players worth the most rounds more than they cost. */
public object Keepers {
    /** [override], else the drafted round less [penalty] for a pick that was already a keeper, else [undraftedRound]; at least round 1. */
    public fun cost(pick: KeeperPick?, penalty: Int, undraftedRound: Int, override: Int?): Int = maxOf(
        1,
        override ?: when {
            pick == null -> undraftedRound
            pick.keeper -> pick.round - penalty
            else -> pick.round
        },
    )

    /** The round a player of [redraftRank] goes in a [teams]-team draft. */
    public fun worth(redraftRank: Int, teams: Int): Int = (redraftRank + teams - 1) / teams

    /** [candidates] by surplus, then dynasty value; the first [keepers] with a worth are kept, unvalued players last. */
    public fun rank(
        candidates: List<KeeperCandidate>,
        penalty: Int,
        undraftedRound: Int,
        overrides: Map<String, Int>,
        teams: Int,
        keepers: Int,
    ): List<KeeperRow> {
        val rows = candidates.map { c ->
            val cost = cost(c.pick, penalty, undraftedRound, overrides[c.playerId])
            val worth = c.redraftRank?.let { worth(it, teams) }
            KeeperRow(c.playerId, cost, worth, worth?.let { cost - it }, c.dynasty, keep = false)
        }.sortedWith(
            compareBy<KeeperRow> { it.surplus == null }
                .thenByDescending { it.surplus ?: Int.MIN_VALUE }
                .thenByDescending { it.dynasty ?: Int.MIN_VALUE }
                .thenBy { it.playerId },
        )
        return rows.mapIndexed { i, r -> if (i < keepers && r.surplus != null) r.copy(keep = true) else r }
    }
}
