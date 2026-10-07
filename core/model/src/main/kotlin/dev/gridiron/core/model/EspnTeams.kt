package dev.gridiron.core.model

/** ESPN's pro team ids, as nflverse writes the teams; a D/ST's ESPN player id is -16000 minus its team's. */
public object EspnTeams {
    private val PRO_TEAMS = mapOf(
        1 to "ATL", 2 to "BUF", 3 to "CHI", 4 to "CIN", 5 to "CLE", 6 to "DAL", 7 to "DEN", 8 to "DET",
        9 to "GB", 10 to "TEN", 11 to "IND", 12 to "KC", 13 to "LV", 14 to "LA", 15 to "MIA", 16 to "MIN",
        17 to "NE", 18 to "NO", 19 to "NYG", 20 to "NYJ", 21 to "PHI", 22 to "ARI", 23 to "PIT", 24 to "LAC",
        25 to "SF", 26 to "SEA", 27 to "TB", 28 to "WAS", 29 to "CAR", 30 to "JAX", 33 to "BAL", 34 to "HOU",
    )

    /** nflverse's team code for ESPN's pro team id. */
    public fun proTeam(id: Int): String? = PRO_TEAMS[id]

    /** The app's id for a D/ST, from its ESPN player id; null for anyone else. */
    public fun dstPlayerId(espnId: String): String? {
        val n = espnId.toIntOrNull() ?: return null
        return if (n < -16000) PRO_TEAMS[-16000 - n]?.let { "DST_$it" } else null
    }
}
