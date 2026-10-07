package dev.gridiron.core.projections

/**
 * A league team worth trading with: [theyHave] are its bench players who would start for you (above your weakest
 * starter at their position), [youOffer] yours who would start for it, at the positions in [theyNeed]. [fit] sums both
 * sides' gains: each position's best such bench player over the weakest starter he'd replace.
 */
public data class PartnerFit(
    val partner: String,
    val fit: Double,
    val theyHave: List<LineupCandidate>,
    val theyNeed: List<String>,
    val youOffer: List<LineupCandidate>,
)

public object TradePartners {
    private val POSITIONS = listOf("QB", "RB", "WR", "TE")

    /** The teams in [others] with something to trade both ways or one, best [fit] first, at most [limit]. */
    public fun rank(
        slots: Map<String, Int>,
        mine: List<LineupCandidate>,
        others: List<Pair<String, List<LineupCandidate>>>,
        limit: Int = 5,
    ): List<PartnerFit> {
        val me = Depth.of(slots, mine)
        return others.mapNotNull { (name, roster) ->
            val them = Depth.of(slots, roster)
            var fit = 0.0
            val theyHave = mutableListOf<LineupCandidate>()
            val youOffer = mutableListOf<LineupCandidate>()
            val theyNeed = mutableListOf<String>()
            for (p in POSITIONS) {
                me.weakest[p]?.let { weak ->
                    val better = them.bench(p).filter { it.points > weak }
                    if (better.isNotEmpty()) {
                        fit += better.first().points - weak
                        theyHave += better
                    }
                }
                them.weakest[p]?.let { weak ->
                    val better = me.bench(p).filter { it.points > weak }
                    if (better.isNotEmpty()) {
                        fit += better.first().points - weak
                        youOffer += better
                        theyNeed += p
                    }
                }
            }
            if (fit <= 0.0) null else PartnerFit(name, fit, theyHave, theyNeed, youOffer)
        }.sortedWith(compareByDescending<PartnerFit> { it.fit }.thenBy { it.partner }).take(limit)
    }

    /** A roster's weakest starter by position in its best lineup, and its bench best first. */
    private class Depth(val weakest: Map<String, Double>, private val benchPlayers: List<LineupCandidate>) {
        fun bench(position: String) = benchPlayers.filter { it.position == position }

        companion object {
            fun of(slots: Map<String, Int>, roster: List<LineupCandidate>): Depth {
                val best = Lineups.best(slots, roster)
                val starters = best.spots.mapNotNull { it.player }
                return Depth(
                    starters.groupBy { it.position }.mapValues { (_, ps) -> ps.minOf { it.points } },
                    best.bench.sortedByDescending { it.points },
                )
            }
        }
    }
}
