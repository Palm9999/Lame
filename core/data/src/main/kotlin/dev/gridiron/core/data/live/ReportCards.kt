package dev.gridiron.core.data.live

/** The report card's categories, each a place in the league. */
public enum class Grade(public val label: String) {
    LINEUPS("Lineups"),
    STRENGTH("Strength"),
    LUCK("Luck"),
    DRAFT("Draft"),
    MOVES("Moves"),
}

/**
 * One manager's season by ESPN's points. [lineupShare]: points scored over the best lineup each week allowed;
 * [allPlay]: the share of the league it outscored each week; [luck]: wins less all-play wins; [draftPoints]: starting
 * points from the players it drafted, on whichever team started them; [movesPoints]: its own starters' points from
 * players it didn't draft (pickups and trades). The last two are null without a draft. [places] are 1 for the best
 * (ties share it); [overall] ranks [averagePlace].
 */
public data class ReportCard(
    val teamId: Int,
    val name: String,
    val lineupShare: Double?,
    val allPlay: Double,
    val luck: Double,
    val draftPoints: Double?,
    val movesPoints: Double?,
    val places: Map<Grade, Int>,
    val averagePlace: Double,
    val overall: Int,
)

public object ReportCards {
    private val NOT_STARTING = setOf("BE", "IR")

    /**
     * Every team's card from the finished [weeks]' matchups, the league's draft [picks] (null when there is none) and
     * its starting [slots]; best overall first. [positionOf] places a player for the best-lineup check, as in
     * [LineupReview.of].
     */
    public fun of(
        weeks: Map<Int, List<LeagueMatchup>>,
        picks: List<DraftPick>?,
        slots: Map<String, Int>,
        positionOf: (MatchupPlayer) -> String?,
        names: Map<Int, String>,
    ): List<ReportCard> {
        if (weeks.isEmpty()) return emptyList()
        val luck = LeagueRecaps.of(weeks, names).luck.associateBy { it.teamId }
        val sides = weeks.toSortedMap().flatMap { (week, ms) -> ms.flatMap { m -> listOfNotNull(m.home, m.away).map { week to it } } }
        val scored = HashMap<Int, Double>()
        val best = HashMap<Int, Double>()
        for ((week, side) in sides) {
            val review = LineupReview.of(week, side, slots, positionOf)
            scored.merge(side.teamId, review.scored, Double::plus)
            best.merge(side.teamId, review.best, Double::plus)
        }
        val drafter = picks?.associate { it.espnId to it.teamId }
        val drafted = HashMap<Int, Double>()
        val moves = HashMap<Int, Double>()
        if (drafter != null) {
            for ((_, side) in sides) {
                for (p in side.lineup) {
                    if (p.slot in NOT_STARTING) continue
                    val points = p.espnPoints ?: 0.0
                    val by = drafter[p.espnId]
                    if (by != null) drafted.merge(by, points, Double::plus)
                    if (by != side.teamId) moves.merge(side.teamId, points, Double::plus)
                }
            }
        }
        val teams = luck.keys.ifEmpty { sides.map { it.second.teamId }.toSet() }
        val raw = teams.map { id ->
            val l = luck[id]
            ReportCard(
                teamId = id,
                name = names[id] ?: "Team $id",
                lineupShare = best[id]?.takeIf { it > 0 }?.let { scored.getValue(id) / it },
                allPlay = l?.let { if (it.games > 0) it.allPlayWins / it.games else 0.0 } ?: 0.0,
                luck = l?.luck ?: 0.0,
                draftPoints = if (drafter == null) null else drafted[id] ?: 0.0,
                movesPoints = if (drafter == null) null else moves[id] ?: 0.0,
                places = emptyMap(),
                averagePlace = 0.0,
                overall = 0,
            )
        }
        val values: Map<Grade, (ReportCard) -> Double?> = mapOf(
            Grade.LINEUPS to { it.lineupShare },
            Grade.STRENGTH to { it.allPlay },
            Grade.LUCK to { it.luck },
            Grade.DRAFT to { it.draftPoints },
            Grade.MOVES to { it.movesPoints },
        )
        val places = values.mapValues { (_, value) -> places(raw, value) }
        val placed = raw.map { c ->
            val mine = Grade.entries.mapNotNull { g -> places.getValue(g)[c.teamId]?.let { g to it } }.toMap()
            c.copy(places = mine, averagePlace = mine.values.average())
        }
        val order = placed.sortedWith(compareBy<ReportCard> { it.averagePlace }.thenByDescending { it.allPlay }.thenBy { it.teamId })
        return order.mapIndexed { i, c -> c.copy(overall = i + 1) }
    }

    /** Each team's place by [value], highest first; a tie shares the better place; a team without a value has none. */
    private fun places(cards: List<ReportCard>, value: (ReportCard) -> Double?): Map<Int, Int> {
        val known = cards.mapNotNull { c -> value(c)?.let { c.teamId to it } }
        return known.associate { (id, v) -> id to 1 + known.count { it.second > v + EPSILON } }
    }

    private const val EPSILON = 1e-9
}
