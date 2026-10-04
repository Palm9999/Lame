package dev.gridiron.core.projections

/**
 * What a trade does to both lineups: each team's best lineup ([Lineups.best]) on the league's slots, before and after,
 * with every player valued at his rest-of-season points. A player without a projection counts as no one.
 */
public data class TradeOutcome(
    val mineBefore: Double,
    val mineAfter: Double,
    val theirsBefore: Double,
    val theirsAfter: Double,
) {
    val myGain: Double get() = mineAfter - mineBefore
    val theirGain: Double get() = theirsAfter - theirsBefore
}

/** One suggested trade with [partner]: the user sends [give] and receives [get]. */
public data class TradeIdea(
    val partner: String,
    val give: List<LineupCandidate>,
    val get: List<LineupCandidate>,
    val outcome: TradeOutcome,
)

public object Trades {
    /** [mine] sends [give] (player ids) to [theirs] for [get]; ids on the wrong roster are ignored. */
    public fun evaluate(
        slots: Map<String, Int>,
        mine: List<LineupCandidate>,
        theirs: List<LineupCandidate>,
        give: Set<String>,
        get: Set<String>,
    ): TradeOutcome {
        val sent = mine.filter { it.playerId in give }
        val received = theirs.filter { it.playerId in get }
        return TradeOutcome(
            mineBefore = Lineups.best(slots, mine).total,
            mineAfter = Lineups.best(slots, mine.filterNot { it.playerId in give } + received).total,
            theirsBefore = Lineups.best(slots, theirs).total,
            theirsAfter = Lineups.best(slots, theirs.filterNot { it.playerId in get } + sent).total,
        )
    }

    /**
     * Trades that raise both best lineups by at least [MIN_GAIN] points, the user's gain first (then theirs, then the
     * fewer players), at most [perPartner]
     * per team and [limit] in all. Tries one-for-one, two-for-one and one-for-two among each roster's [POOL] best
     * players, so a search over a twelve-team league stays well under a second.
     */
    public fun ideas(
        slots: Map<String, Int>,
        mine: List<LineupCandidate>,
        partners: List<Pair<String, List<LineupCandidate>>>,
        limit: Int = 8,
        perPartner: Int = 2,
    ): List<TradeIdea> {
        val myPool = pool(mine)
        val myBefore = Lineups.best(slots, mine).total
        val found = partners.flatMap { (name, theirs) ->
            val theirPool = pool(theirs)
            val theirBefore = Lineups.best(slots, theirs).total
            val gives = groups(myPool)
            val gets = groups(theirPool)
            buildList {
                for (give in gives) {
                    for (get in gets) {
                        if (give.size == 2 && get.size == 2) continue
                        val giveIds = give.mapTo(HashSet()) { it.playerId }
                        val getIds = get.mapTo(HashSet()) { it.playerId }
                        val theirAfter = Lineups.best(slots, theirs.filterNot { it.playerId in getIds } + give).total
                        if (theirAfter - theirBefore < MIN_GAIN) continue
                        val myAfter = Lineups.best(slots, mine.filterNot { it.playerId in giveIds } + get).total
                        if (myAfter - myBefore < MIN_GAIN) continue
                        add(TradeIdea(name, give, get, TradeOutcome(myBefore, myAfter, theirBefore, theirAfter)))
                    }
                }
            }.sortedWith(ORDER).distinctBy { idea -> idea.give.map { it.playerId } }.take(perPartner)
        }
        return found.sortedWith(ORDER).take(limit)
    }

    private val ORDER = compareByDescending<TradeIdea> { it.outcome.myGain }
        .thenByDescending { it.outcome.theirGain }
        // Equal gains: the simpler trade, which also spares a roster spot.
        .thenBy { it.give.size + it.get.size }
        .thenBy { it.partner }
        .thenBy { idea -> idea.give.joinToString { it.playerId } + idea.get.joinToString { it.playerId } }

    private fun pool(roster: List<LineupCandidate>): List<LineupCandidate> =
        roster.filter { it.points > 0.0 }.sortedWith(compareByDescending<LineupCandidate> { it.points }.thenBy { it.playerId }).take(POOL)

    /** Every single player and every pair from [pool]. */
    private fun groups(pool: List<LineupCandidate>): List<List<LineupCandidate>> = buildList {
        for (i in pool.indices) {
            add(listOf(pool[i]))
            for (j in i + 1 until pool.size) add(listOf(pool[i], pool[j]))
        }
    }

    /** The smallest rest-of-season lift that counts: a point or so is noise in a projection that long. */
    public const val MIN_GAIN: Double = 2.0

    private const val POOL = 12
}
