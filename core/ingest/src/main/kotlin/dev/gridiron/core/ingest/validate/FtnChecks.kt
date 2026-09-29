package dev.gridiron.core.ingest.validate

/** Below this share of pass attempts charted, FTN's rates describe part of the season. */
private const val MIN_COVERAGE = 0.9

/**
 * A warning when FTN's file charts under 90% of a season's pass attempts (it lags play-by-play mid-season, and
 * misses the odd game), null otherwise. Rates still use FTN's own denominators, so they stay right for the
 * charted plays; the warning says they are not the whole season.
 */
internal fun ftnCoverageWarning(season: Int, attempts: Int, covered: Int): String? {
    if (attempts <= 0 || covered.toDouble() / attempts >= MIN_COVERAGE) return null
    return "$season: FTN charting covers ${Math.round(100.0 * covered / attempts)}% of pass attempts; its rates use only the charted plays"
}
