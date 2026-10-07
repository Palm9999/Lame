package dev.gridiron.core.ingest

/** An upstream file the build reads. */
public enum class Input(public val perSeason: Boolean, public val label: String) {
    PBP(true, "play-by-play"),
    SNAP_COUNTS(true, "snap counts"),
    INJURIES(true, "injury reports"),
    EXPECTED(true, "expected points"),
    FTN(true, "FTN charting"),
    PLAYERS(false, "player list"),
    GAMES(false, "schedule"),
    NGS_PASSING(false, "NGS passing"),
    NGS_RUSHING(false, "NGS rushing"),
    NGS_RECEIVING(false, "NGS receiving"),
    ESPN_PROJECTIONS(true, "ESPN projections"),
}

/** Where each input lives: public nflverse and ffopportunity release assets, never this app's repository. */
public object Sources {
    private const val NFLVERSE = "https://github.com/nflverse/nflverse-data/releases/download"
    private const val FFOPPORTUNITY = "https://github.com/ffverse/ffopportunity/releases/download/latest-data"
    private const val ESPN = "https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl/seasons"

    /**
     * ESPN's filter: its weekly projections (source 1, weekly split) for the 700 most-owned QBs, RBs, WRs
     * and TEs. Without it ESPN answers with 50 players.
     */
    private const val ESPN_FILTER = """{"players":{"limit":700,"sortPercOwned":{"sortPriority":1,"sortAsc":false},""" +
        """"filterSlotIds":{"value":[0,2,4,6]},"filterStatsForSourceIds":{"value":[1]},"filterStatsForSplitTypeIds":{"value":[1]}}}"""

    public fun url(input: Input, season: Int? = null): String {
        require(input.perSeason == (season != null)) { "$input: season must be given exactly when the input is per season" }
        return when (input) {
            Input.PBP -> "$NFLVERSE/pbp/play_by_play_$season.csv.gz"
            Input.SNAP_COUNTS -> "$NFLVERSE/snap_counts/snap_counts_$season.csv.gz"
            // nflverse publishes 2021 and 2022 uncompressed only.
            Input.INJURIES -> "$NFLVERSE/injuries/injuries_$season.csv" + if (season!! >= 2023) ".gz" else ""
            // ffopportunity publishes no gzip variant of this file.
            Input.EXPECTED -> "$FFOPPORTUNITY/ep_weekly_$season.csv"
            // FTN publishes 2022 onward, one uncompressed csv per season.
            Input.FTN -> "$NFLVERSE/ftn_charting/ftn_charting_$season.csv"
            Input.PLAYERS -> "$NFLVERSE/players/players.csv.gz"
            // Every season since 1999 in one file (gzip only since October 2026): opponents, results, lines, starting QBs, coaches.
            Input.GAMES -> "$NFLVERSE/schedules/games.csv.gz"
            // Next Gen Stats: every season in one file, so no season in the name.
            Input.NGS_PASSING -> "$NFLVERSE/nextgen_stats/ngs_passing.csv.gz"
            Input.NGS_RUSHING -> "$NFLVERSE/nextgen_stats/ngs_rushing.csv.gz"
            Input.NGS_RECEIVING -> "$NFLVERSE/nextgen_stats/ngs_receiving.csv.gz"
            // ESPN's unofficial, keyless fantasy API; its default league scores PPR.
            Input.ESPN_PROJECTIONS -> "$ESPN/$season/segments/0/leaguedefaults/3?view=kona_player_info"
        }
    }

    /** Request headers [input] needs. */
    public fun headers(input: Input): Map<String, String> =
        if (input == Input.ESPN_PROJECTIONS) mapOf("X-Fantasy-Filter" to ESPN_FILTER) else emptyMap()

    public fun fileName(input: Input, season: Int? = null): String =
        if (input == Input.ESPN_PROJECTIONS) "espn_projections_$season.json" else url(input, season).substringAfterLast('/')

    /** The `schema_meta` key under which a build records which version of this file it read. */
    public fun metaKey(input: Input, season: Int? = null): String = "source:${fileName(input, season)}"
}
