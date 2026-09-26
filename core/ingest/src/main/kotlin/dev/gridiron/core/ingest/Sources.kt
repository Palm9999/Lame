package dev.gridiron.core.ingest

/** An upstream file the build reads. */
public enum class Input(public val perSeason: Boolean, public val label: String) {
    PBP(true, "play-by-play"),
    SNAP_COUNTS(true, "snap counts"),
    INJURIES(true, "injury reports"),
    EXPECTED(true, "expected points"),
    PLAYERS(false, "player list"),
}

/** Where each input lives: public nflverse and ffopportunity release assets, never this app's repository. */
public object Sources {
    private const val NFLVERSE = "https://github.com/nflverse/nflverse-data/releases/download"
    private const val FFOPPORTUNITY = "https://github.com/ffverse/ffopportunity/releases/download/latest-data"

    public fun url(input: Input, season: Int? = null): String {
        require(input.perSeason == (season != null)) { "$input: season must be given exactly when the input is per season" }
        return when (input) {
            Input.PBP -> "$NFLVERSE/pbp/play_by_play_$season.csv.gz"
            Input.SNAP_COUNTS -> "$NFLVERSE/snap_counts/snap_counts_$season.csv.gz"
            Input.INJURIES -> "$NFLVERSE/injuries/injuries_$season.csv.gz"
            // ffopportunity publishes no gzip variant of this file.
            Input.EXPECTED -> "$FFOPPORTUNITY/ep_weekly_$season.csv"
            Input.PLAYERS -> "$NFLVERSE/players/players.csv.gz"
        }
    }

    public fun fileName(input: Input, season: Int? = null): String = url(input, season).substringAfterLast('/')

    /** The `schema_meta` key under which a build records which version of this file it read. */
    public fun metaKey(input: Input, season: Int? = null): String = "source:${fileName(input, season)}"
}
