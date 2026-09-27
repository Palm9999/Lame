package dev.gridiron.core.forecast

import java.text.Normalizer

/** The market that prices a touchdown of any kind but passing. */
public const val ANYTIME_TD: String = "player_anytime_td"

/**
 * The Odds API player-prop markets the model reads (spec §5), in the order a
 * factor note lists them.
 */
public val PROP_MARKETS: List<String> =
    listOf("player_receptions", "player_reception_yds", "player_rush_yds", "player_pass_yds", ANYTIME_TD)

/**
 * One book's price on one player's market, as decimal odds. For [ANYTIME_TD],
 * [over] is Yes, [under] is No (null when the book offers only Yes), and
 * [point] is null.
 */
public data class PropQuote(
    val book: String,
    val market: String,
    val player: String,
    val point: Double?,
    val over: Double?,
    val under: Double?,
)

/** One game's props, with its teams as nflverse abbreviations (`KC`, `LA`). */
public data class PropEvent(val home: String, val away: String, val quotes: List<PropQuote>)

/** Every prop fetched for the upcoming week, handed to [Forecast.run]. */
public data class PropsSnapshot(val events: List<PropEvent>)

/** How props went in one forecast, for the refresh report. */
public data class PropsOutcome(
    /** Players whose upcoming-week projection was blended with props. */
    val blended: Int,
    /** Players named in props that no projected player matched; their props were dropped. */
    val unmatched: Int,
)

private val NAME_SUFFIXES = setOf("jr", "sr", "ii", "iii", "iv", "v")
private val MARKS = Regex("\\p{M}+")
private val NOT_NAME = Regex("[^a-z0-9 ]")

/**
 * A name as the matcher compares it: accents, punctuation and suffixes (Jr.,
 * III) removed, lower case, single spaces. "Amon-Ra St. Brown" becomes
 * "amonra st brown", like `player.search_name`.
 */
internal fun normalizeName(name: String): String =
    Normalizer.normalize(name, Normalizer.Form.NFD).replace(MARKS, "")
        .lowercase()
        .replace(NOT_NAME, "")
        .split(' ')
        .filter { it.isNotEmpty() && it !in NAME_SUFFIXES }
        .joinToString(" ")
