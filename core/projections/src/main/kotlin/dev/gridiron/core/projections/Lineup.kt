package dev.gridiron.core.projections

/** A rostered player who could start: [position] is a `player.position` code (`DST` for a team defense). */
public data class LineupCandidate(val playerId: String, val position: String, val points: Double)

/** One starting slot and who fills it; null when nobody on the roster can. */
public data class LineupSpot(val slot: String, val player: LineupCandidate?)

/** The best starting lineup: [spots] in slot order, then the [bench] best first. */
public data class BestLineup(val spots: List<LineupSpot>, val bench: List<LineupCandidate>) {
    public val total: Double get() = spots.sumOf { it.player?.points ?: 0.0 }
}

public object Lineups {
    /** A standard league: QB, 2 RB, 2 WR, TE, FLEX, K and D/ST. */
    public val DEFAULT_SLOTS: Map<String, Int> =
        mapOf("QB" to 1, "RB" to 2, "WR" to 2, "TE" to 1, "FLEX" to 1, "D/ST" to 1, "K" to 1)

    /** ESPN's starter slots, in the order a lineup reads, with the positions each takes. */
    private val ELIGIBLE: List<Pair<String, Set<String>>> = listOf(
        "QB" to setOf("QB"),
        "RB" to setOf("RB"),
        "WR" to setOf("WR"),
        "TE" to setOf("TE"),
        "RB/WR" to setOf("RB", "WR"),
        "WR/TE" to setOf("WR", "TE"),
        "RB/WR/TE" to setOf("RB", "WR", "TE"),
        "FLEX" to setOf("RB", "WR", "TE"),
        "OP" to setOf("QB", "RB", "WR", "TE"),
        "D/ST" to setOf("DST"),
        "K" to setOf("K"),
    )

    /**
     * The lineup that scores most. Candidates are taken best first, each kept if every kept player can still be given a
     * slot (an augmenting path moves earlier picks to other slots), which is optimal because the sets of fillable
     * players form a matroid. A candidate prefers the narrowest slot that takes him, so the best RB sits at RB before
     * FLEX. Slot labels this doesn't know are ignored; ties break by player id.
     */
    public fun best(slots: Map<String, Int>, candidates: List<LineupCandidate>): BestLineup {
        val spots = ELIGIBLE.flatMap { (label, positions) -> List(slots[label] ?: 0) { label to positions } }
        // Narrowest first; the sort is stable, so equal widths keep the reading order.
        val tryOrder = spots.indices.sortedBy { spots[it].second.size }
        val ranked = candidates.sortedWith(compareByDescending<LineupCandidate> { it.points }.thenBy { it.playerId })
        val taken = arrayOfNulls<Int>(spots.size)

        fun place(c: Int, seen: BooleanArray): Boolean {
            val fits = tryOrder.filter { ranked[c].position in spots[it].second }
            // A free slot first, so nobody moves needlessly; otherwise ask a holder to move on.
            fits.firstOrNull { taken[it] == null }?.let { taken[it] = c; return true }
            for (s in fits) {
                if (seen[s]) continue
                seen[s] = true
                if (place(taken[s]!!, seen)) {
                    taken[s] = c
                    return true
                }
            }
            return false
        }

        for (c in ranked.indices) place(c, BooleanArray(spots.size))
        val starters = taken.filterNotNull().toSet()
        return BestLineup(
            spots = spots.indices.map { s -> LineupSpot(spots[s].first, taken[s]?.let { ranked[it] }) },
            bench = ranked.filterIndexed { i, _ -> i !in starters },
        )
    }
}
