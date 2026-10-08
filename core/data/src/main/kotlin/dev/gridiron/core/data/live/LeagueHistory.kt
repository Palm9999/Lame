package dev.gridiron.core.data.live

/** One person across seasons: [key] is the ESPN owner id (or `team:<season>:<id>` for a team without one). */
public data class Manager(val key: String, val name: String)

public data class StandingRow(val manager: Manager, val teamName: String, val wins: Int, val losses: Int, val ties: Int, val pointsFor: Double, val place: Int)

/** A season's [champion] (null while in progress) and standings, final place first. */
public data class SeasonSummary(val season: Int, val champion: Manager?, val championTeam: String?, val standings: List<StandingRow>)

/** A manager's regular seasons summed, with [titles], [playoffs] trips and [averageFinish] over seasons with a final place. */
public data class ManagerRow(
    val manager: Manager,
    val seasons: Int,
    val wins: Int,
    val losses: Int,
    val ties: Int,
    val titles: Int,
    val playoffs: Int,
    val pointsFor: Double,
    val averageFinish: Double?,
) {
    val winPct: Double get() = (wins + ties / 2.0) / (wins + losses + ties).coerceAtLeast(1)
}

/** The user's decided games against [rival], playoffs included. */
public data class RivalRow(val rival: Manager, val wins: Int, val losses: Int, val ties: Int, val pointsFor: Double, val pointsAgainst: Double)

/** One league record: [value] by [manager] in [season] ([week] for a single game); [detail] says against whom or how. */
public data class HistoryRecord(val label: String, val manager: Manager, val season: Int, val week: Int?, val value: Double, val detail: String?)

public data class HistoryTables(
    val seasons: List<SeasonSummary>,
    val allTime: List<ManagerRow>,
    val headToHead: List<RivalRow>,
    val records: List<HistoryRecord>,
    /** The playoff simulator checked on finished seasons ([playoffOddsCheck]); empty when none can be. */
    val oddsChecks: List<OddsCheck> = emptyList(),
)

public object LeagueHistory {
    /** The tables for [seasons]; [me] is the user's owner id (null: no head-to-head). */
    public fun of(seasons: List<HistorySeason>, me: String?): HistoryTables {
        val ordered = seasons.sortedBy { it.season }
        val newest = ordered.lastOrNull()?.season
        fun key(s: HistorySeason, t: HistoryTeam) = t.ownerId ?: "team:${s.season}:${t.id}"
        // The latest display name, else the latest team name.
        val names = HashMap<String, String>()
        for (s in ordered) for (t in s.teams) names[key(s, t)] = t.ownerId?.let(s.members::get) ?: t.name
        fun manager(s: HistorySeason, t: HistoryTeam) = key(s, t).let { Manager(it, names.getValue(it)) }

        val summaries = ordered.map { s ->
            val byId = s.teams.associateBy { it.id }
            val ranked = s.teams.firstOrNull { it.finalRank == 1 }
            val bracket = if (ranked == null && s.season != newest) s.games.filter { it.playoff && it.decided && it.winner != "TIE" }.maxByOrNull { it.week } else null
            val champ = ranked ?: bracket?.let { g -> byId[if (g.winner == "HOME") g.homeId else g.awayId] }
            val standings = s.teams.sortedWith(
                compareBy<HistoryTeam> { it.finalRank ?: Int.MAX_VALUE }.thenByDescending { it.wins }.thenByDescending { it.pointsFor },
            ).mapIndexed { i, t -> StandingRow(manager(s, t), t.name, t.wins, t.losses, t.ties, t.pointsFor, t.finalRank ?: (i + 1)) }
            SeasonSummary(s.season, champ?.let { manager(s, it) }, champ?.name, standings)
        }
        val champions = summaries.mapNotNull { it.champion?.key }

        val allTime = ordered.flatMap { s -> s.teams.map { t -> key(s, t) to (s to t) } }.groupBy({ it.first }, { it.second }).map { (k, rows) ->
            val finishes = rows.mapNotNull { (_, t) -> t.finalRank }
            ManagerRow(
                manager = Manager(k, names.getValue(k)),
                seasons = rows.size,
                wins = rows.sumOf { it.second.wins },
                losses = rows.sumOf { it.second.losses },
                ties = rows.sumOf { it.second.ties },
                titles = champions.count { it == k },
                playoffs = rows.count { (s, t) -> t.playoffSeed != null && s.playoffTeams != null && t.playoffSeed <= s.playoffTeams },
                pointsFor = rows.sumOf { it.second.pointsFor },
                averageFinish = finishes.takeIf { it.isNotEmpty() }?.average(),
            )
        }.sortedWith(compareByDescending<ManagerRow> { it.titles }.thenByDescending { it.winPct }.thenBy { it.manager.name })

        // Every decided game from each side: (season, week, manager, points, opponent, their points).
        data class Side(val season: Int, val week: Int, val manager: Manager, val points: Double, val opponent: Manager, val against: Double)
        val sides = ordered.flatMap { s ->
            val byId = s.teams.associateBy { it.id }
            s.games.filter { it.decided }.flatMap { g ->
                val home = byId[g.homeId] ?: return@flatMap emptyList()
                val away = g.awayId?.let(byId::get) ?: return@flatMap emptyList()
                val h = manager(s, home)
                val a = manager(s, away)
                listOf(Side(s.season, g.week, h, g.homePoints, a, g.awayPoints), Side(s.season, g.week, a, g.awayPoints, h, g.homePoints))
            }
        }

        val headToHead = if (me == null) {
            emptyList()
        } else {
            sides.filter { it.manager.key == me && it.opponent.key != me }.groupBy { it.opponent.key }.map { (_, games) ->
                RivalRow(
                    rival = games.first().opponent,
                    wins = games.count { it.points > it.against },
                    losses = games.count { it.points < it.against },
                    ties = games.count { it.points == it.against },
                    pointsFor = games.sumOf { it.points },
                    pointsAgainst = games.sumOf { it.against },
                )
            }.sortedWith(compareByDescending<RivalRow> { it.wins + it.losses + it.ties }.thenBy { it.rival.name })
        }

        // A season still being played can't hold a season record.
        val finished = ordered.filter { s -> s.season != newest || summaries.last().champion != null }
        val records = listOfNotNull(
            sides.maxByOrNull { it.points }?.let { HistoryRecord("Highest week", it.manager, it.season, it.week, it.points, "against ${it.opponent.name}") },
            sides.minByOrNull { it.points }?.let { HistoryRecord("Lowest week", it.manager, it.season, it.week, it.points, "against ${it.opponent.name}") },
            sides.maxByOrNull { it.points - it.against }?.let {
                HistoryRecord("Biggest win", it.manager, it.season, it.week, it.points - it.against, "${fmt(it.points)}–${fmt(it.against)} over ${it.opponent.name}")
            },
            finished.flatMap { s -> s.teams.map { s to it } }.filter { (_, t) -> t.wins + t.losses + t.ties > 0 }
                .maxByOrNull { (_, t) -> (t.wins + t.ties / 2.0) / (t.wins + t.losses + t.ties) }
                ?.let { (s, t) -> HistoryRecord("Best record", manager(s, t), s.season, null, (t.wins + t.ties / 2.0) / (t.wins + t.losses + t.ties), "${t.wins}–${t.losses}" + if (t.ties > 0) "–${t.ties}" else "") },
            finished.flatMap { s -> s.teams.map { s to it } }.maxByOrNull { (_, t) -> t.pointsFor }
                ?.let { (s, t) -> HistoryRecord("Most points in a season", manager(s, t), s.season, null, t.pointsFor, t.name) },
        )
        return HistoryTables(summaries.reversed(), allTime, headToHead, records)
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)
}
