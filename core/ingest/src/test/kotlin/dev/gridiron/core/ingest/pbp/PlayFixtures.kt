package dev.gridiron.core.ingest.pbp

/** One play with the same neutral defaults as `etl/tests/test_transform.py`'s `play()`. */
internal fun play(
    season: Int = 2025, week: Int = 1, seasonType: String? = "REG", gameId: String? = "g1",
    posteam: String? = "AAA", defteam: String? = "BBB", playType: String? = "pass",
    passAttempt: Double? = 0.0, completePass: Double? = 0.0, airYards: Double? = null,
    yardsAfterCatch: Double? = null, yardsGained: Double? = 0.0, passingYards: Double? = null,
    receivingYards: Double? = null, rushingYards: Double? = null, passTouchdown: Double? = 0.0,
    rushTouchdown: Double? = 0.0, interception: Double? = 0.0, sack: Double? = 0.0,
    qbScramble: Double? = 0.0, receiver: String? = null, rusher: String? = null,
    passer: String? = null, yardline100: Double? = 50.0, epa: Double? = 0.0,
    success: Double? = 0.0, cpoe: Double? = null, twoPointAttempt: Double? = 0.0,
    firstDownPass: Double? = 0.0, firstDownRush: Double? = 0.0, fumbleLost: Double? = 0.0,
    fumbler: String? = null, twoPointResult: String? = null, touchdown: Double? = 0.0,
    tdTeam: String? = null, homeTeam: String? = "AAA", awayTeam: String? = "BBB",
    totalHomeScore: Double? = 0.0, totalAwayScore: Double? = 0.0,
): Play = Play(
    season = season, week = week, seasonType = seasonType, gameId = gameId, posteam = posteam,
    defteam = defteam, playType = playType, passAttempt = passAttempt, completePass = completePass,
    airYards = airYards, yardsAfterCatch = yardsAfterCatch, yardsGained = yardsGained,
    passingYards = passingYards, receivingYards = receivingYards, rushingYards = rushingYards,
    passTouchdown = passTouchdown, rushTouchdown = rushTouchdown, interception = interception,
    sack = sack, qbScramble = qbScramble, receiver = receiver, rusher = rusher, passer = passer,
    yardline100 = yardline100, epa = epa, success = success, cpoe = cpoe,
    twoPointAttempt = twoPointAttempt, firstDownPass = firstDownPass, firstDownRush = firstDownRush,
    fumbleLost = fumbleLost, fumbler = fumbler, twoPointResult = twoPointResult,
    touchdown = touchdown, tdTeam = tdTeam, homeTeam = homeTeam, awayTeam = awayTeam,
    totalHomeScore = totalHomeScore, totalAwayScore = totalAwayScore,
)

internal fun target(
    rec: String, air: Double, complete: Boolean = false, yds: Double = 0.0, yl: Double = 50.0,
    td: Double = 0.0, qb: String = "QB1", epa: Double? = 0.0, cpoe: Double? = null,
    seasonType: String = "REG", twoPointAttempt: Double = 0.0, twoPointResult: String? = null,
    firstDownPass: Double = 0.0, fumbleLost: Double = 0.0, fumbler: String? = null,
): Play = play(
    playType = "pass", passAttempt = 1.0, completePass = if (complete) 1.0 else 0.0, airYards = air,
    receivingYards = if (complete) yds else null, passingYards = if (complete) yds else null,
    passTouchdown = td, receiver = rec, passer = qb, yardline100 = yl, epa = epa, cpoe = cpoe,
    seasonType = seasonType, twoPointAttempt = twoPointAttempt, twoPointResult = twoPointResult,
    firstDownPass = firstDownPass, fumbleLost = fumbleLost, fumbler = fumbler,
)

internal fun carry(
    rb: String, yds: Double = 0.0, yl: Double = 50.0, td: Double = 0.0, success: Double = 0.0,
    epa: Double = 0.0, qbScramble: Double = 0.0, twoPointAttempt: Double = 0.0,
    twoPointResult: String? = null, firstDownRush: Double = 0.0, fumbleLost: Double = 0.0,
    fumbler: String? = null,
): Play = play(
    playType = "run", rushingYards = yds, rusher = rb, rushTouchdown = td, yardline100 = yl,
    success = success, epa = epa, qbScramble = qbScramble, twoPointAttempt = twoPointAttempt,
    twoPointResult = twoPointResult, firstDownRush = firstDownRush, fumbleLost = fumbleLost,
    fumbler = fumbler,
)

/** A QB kneel: always a loss, like a real clock-killer. */
internal fun kneel(qb: String, yds: Double = -1.0, epa: Double = -0.5, yl: Double = 50.0): Play =
    play(playType = "qb_kneel", rushingYards = yds, rusher = qb, success = 0.0, epa = epa, yardline100 = yl)

/** A QB spike: a zero-yard incompletion. */
internal fun spike(qb: String, epa: Double = -0.1): Play =
    play(playType = "qb_spike", passAttempt = 1.0, completePass = 0.0, passer = qb, epa = epa)

internal fun base(plays: List<Play>): List<PlayerWeek> =
    PlayerWeekAggregator().apply { plays.forEach(::add) }.rows()

internal fun List<PlayerWeek>.row(pid: String): Map<String, Double?> {
    val hits = filter { it.playerId == pid }
    check(hits.size == 1) { "expected one row for $pid, got ${hits.size}" }
    return hits.single().values
}
