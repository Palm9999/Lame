package dev.gridiron.core.projections

/**
 * What a freely available player is worth at each position: in a league of [teams] teams starting [slots] each, the
 * best players are handed out slot by slot (each position's own slots first, then the flex slots to the best players
 * left who fit them), and a position's replacement level is the points of its best player left over. A player's value
 * is his points over that.
 */
public object ReplacementLevel {
    public fun of(players: List<LineupCandidate>, teams: Int, slots: Map<String, Int>): Map<String, Double> {
        val byPosition = players.groupBy { it.position }.mapValues { (_, ps) -> ps.sortedByDescending { it.points } }
        val taken = HashMap<String, Int>()
        val flex = mutableListOf<String>()
        for ((slot, count) in slots) {
            val own = byPosition.keys.filter { Lineups.fits(slot, it) }
            if (own.size == 1) taken.merge(own.single(), count * teams, Int::plus) else repeat(count * teams) { flex += slot }
        }
        // Flex slots, narrowest first, each to the best player left who fits.
        for (slot in flex.sortedBy { s -> byPosition.keys.count { Lineups.fits(s, it) } }) {
            val pick = byPosition.filter { (pos, ps) -> Lineups.fits(slot, pos) && (taken[pos] ?: 0) < ps.size }
                .maxByOrNull { (pos, ps) -> ps[taken[pos] ?: 0].points }?.key ?: continue
            taken.merge(pick, 1, Int::plus)
        }
        return byPosition.mapValues { (pos, ps) -> ps.getOrNull(taken[pos] ?: 0)?.points ?: 0.0 }
    }

    /** Each player's points over his position's [levels] (zero for a position it doesn't know). */
    public fun values(players: List<LineupCandidate>, levels: Map<String, Double>): Map<String, Double> =
        players.associate { it.playerId to it.points - (levels[it.position] ?: 0.0) }
}
