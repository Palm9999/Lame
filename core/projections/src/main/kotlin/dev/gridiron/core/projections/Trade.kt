package dev.gridiron.core.projections

/**
 * What a trade does to both rosters' rest-of-season value ([Trades.value]), before and after. A side that receives
 * more players than it sends cuts players to keep its roster size: [myDrops] and [theirDrops] (the user's own choice
 * first, then the lowest-valued).
 */
public data class TradeOutcome(
    val mineBefore: Double,
    val mineAfter: Double,
    val theirsBefore: Double,
    val theirsAfter: Double,
    val myDrops: List<LineupCandidate> = emptyList(),
    val theirDrops: List<LineupCandidate> = emptyList(),
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
    /**
     * A roster's rest-of-season value: each remaining week's best lineup ([Lineups.best]) on [slots], summed, plus
     * [BENCH_WEIGHT] of that week's best bench player (depth for the weeks a starter misses, which projections don't
     * foresee). Byes count because a player scores nothing in a week he has no game. Without weekly points (a database
     * built before them) it is the same sum over the season totals, as one "week".
     */
    public fun value(slots: Map<String, Int>, roster: List<LineupCandidate>): Double {
        val weeks = roster.flatMapTo(sortedSetOf()) { it.weekly.keys }
        if (weeks.isEmpty()) return weekValue(slots, roster)
        return weeks.sumOf { w -> weekValue(slots, roster.map { it.copy(points = it.weekly[w] ?: 0.0) }) }
    }

    private fun weekValue(slots: Map<String, Int>, roster: List<LineupCandidate>): Double {
        val best = Lineups.best(slots, roster)
        return best.total + BENCH_WEIGHT * (best.bench.maxOfOrNull { it.points } ?: 0.0)
    }

    /**
     * [mine] sends [give] (player ids) to [theirs] for [get]; ids on the wrong roster are ignored. When the user must
     * cut players to make room, [myCuts] go first (those still on the roster after the trade), then the lowest-valued.
     */
    public fun evaluate(
        slots: Map<String, Int>,
        mine: List<LineupCandidate>,
        theirs: List<LineupCandidate>,
        give: Set<String>,
        get: Set<String>,
        myCuts: Set<String> = emptySet(),
    ): TradeOutcome = outcome(slots, mine, theirs, mine.filter { it.playerId in give }, theirs.filter { it.playerId in get }, ::value, myCuts = myCuts)

    private fun outcome(
        slots: Map<String, Int>,
        mine: List<LineupCandidate>,
        theirs: List<LineupCandidate>,
        sent: List<LineupCandidate>,
        received: List<LineupCandidate>,
        valueOf: (Map<String, Int>, List<LineupCandidate>) -> Double,
        mineBefore: Double = valueOf(slots, mine),
        theirsBefore: Double = valueOf(slots, theirs),
        myCuts: Set<String> = emptySet(),
    ): TradeOutcome {
        val (myRoster, myDrops) = keepSize(mine.size, mine.filterNot { it in sent } + received, myCuts)
        val (theirRoster, theirDrops) = keepSize(theirs.size, theirs.filterNot { it in received } + sent)
        return TradeOutcome(mineBefore, valueOf(slots, myRoster), theirsBefore, valueOf(slots, theirRoster), myDrops, theirDrops)
    }

    /** [roster] cut back to [size] players: [first] (ids) before anyone, then the lowest rest-of-season totals (ties by id). */
    private fun keepSize(size: Int, roster: List<LineupCandidate>, first: Set<String> = emptySet()): Pair<List<LineupCandidate>, List<LineupCandidate>> {
        if (roster.size <= size) return roster to emptyList()
        val drops = roster.sortedWith(compareBy<LineupCandidate> { it.playerId !in first }.thenBy { it.points }.thenBy { it.playerId })
            .take(roster.size - size)
        return roster.filterNot { it in drops } to drops
    }

    /**
     * Trades that raise both rosters' [value] by at least [MIN_GAIN] points, the user's gain first (then theirs, then
     * the fewer players), at most [perPartner] per team and [limit] in all. Tries one-for-one, two-for-one, one-for-two
     * among each roster's [POOL] best players and two-for-two among the [PAIR_POOL] best. Every trade is first scored
     * on season totals; the [SCREEN] best per team that help both sides there are then valued week by week, so a
     * twelve-team search stays around a second.
     */
    public fun ideas(
        slots: Map<String, Int>,
        mine: List<LineupCandidate>,
        partners: List<Pair<String, List<LineupCandidate>>>,
        limit: Int = 8,
        perPartner: Int = 2,
    ): List<TradeIdea> {
        val myPool = pool(mine)
        val myTotalBefore = weekValue(slots, mine)
        val myBefore = value(slots, mine)
        val myPairs = myPool.take(PAIR_POOL).toSet()
        val found = partners.flatMap { (name, theirs) ->
            val theirPool = pool(theirs)
            val theirPairs = theirPool.take(PAIR_POOL).toSet()
            val theirTotalBefore = weekValue(slots, theirs)
            val gives = groups(myPool)
            val gets = groups(theirPool)
            val screened = buildList {
                for (give in gives) {
                    for (get in gets) {
                        if (give.size == 2 && get.size == 2 && !(myPairs.containsAll(give) && theirPairs.containsAll(get))) continue
                        val o = outcome(slots, mine, theirs, give, get, ::weekValue, myTotalBefore, theirTotalBefore)
                        if (o.myGain > 0.0 && o.theirGain > 0.0) add(Triple(give, get, minOf(o.myGain, o.theirGain)))
                    }
                }
            }.sortedByDescending { it.third }.take(SCREEN)
            val theirBefore = value(slots, theirs)
            screened.mapNotNull { (give, get) ->
                val o = outcome(slots, mine, theirs, give, get, ::value, myBefore, theirBefore)
                if (o.myGain >= MIN_GAIN && o.theirGain >= MIN_GAIN) TradeIdea(name, give, get, o) else null
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

    /**
     * How much of each week's best bench player counts: about how often one of a lineup's starters misses a game he
     * was projected to play. A judgment, not a fit (there are no historical rosters to fit it on).
     */
    public const val BENCH_WEIGHT: Double = 0.1

    private const val POOL = 12
    private const val PAIR_POOL = 8
    private const val SCREEN = 30
}
