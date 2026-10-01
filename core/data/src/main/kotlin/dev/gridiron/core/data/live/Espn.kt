package dev.gridiron.core.data.live

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

internal data class NewsArticle(
    val id: String,
    val published: Instant,
    val headline: String,
    val description: String?,
    val url: String,
    val athletes: List<TaggedAthlete>,
)

internal data class TaggedAthlete(val espnId: String, val name: String)

internal data class EspnInjury(
    val espnId: String,
    val name: String,
    val team: String?,
    val position: String?,
    val status: String,
    /** ESPN's one- or two-letter code: A, Q, D, O, IR, … */
    val abbr: String,
    val shortComment: String?,
    val longComment: String?,
    val date: Instant?,
)

/** One game on ESPN's scoreboard, with teams written as nflverse writes them. Scores are null before kickoff. */
internal data class EspnGame(
    val espnId: String,
    val home: String,
    val away: String,
    val kickoff: Instant?,
    val state: State,
    /** ESPN's short status: "Final", "Final/OT", "4:14 - 3rd", "Halftime", or the kickoff in Eastern time. */
    val detail: String?,
    val homeScore: Int?,
    val awayScore: Int?,
) {
    enum class State { SCHEDULED, LIVE, FINAL }
}

/** ESPN answered, but not in the shape this app reads. */
public class LiveFormatException(message: String) : Exception(message)

/**
 * ESPN's public (unofficial, keyless) NFL feeds. Parsing walks the JSON tree
 * rather than binding classes, so fields ESPN adds or drops never break it.
 * Entries missing what the app needs are skipped; a response that isn't the
 * expected shape at all, or whose entries all fail to read, is a
 * [LiveFormatException], so the last good data is kept rather than wiped.
 */
internal object EspnParser {
    const val NEWS_URL: String = "https://site.api.espn.com/apis/site/v2/sports/football/nfl/news?limit=50"
    const val INJURIES_URL: String = "https://site.api.espn.com/apis/site/v2/sports/football/nfl/injuries"

    private val SCOREBOARD = "https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard"

    /**
     * One week's scoreboard. nflverse numbers the playoffs 19 to 22 straight after week 18 (the Pro Bowl is skipped);
     * ESPN restarts at 1 in its own postseason type, where the Super Bowl is its fifth week.
     */
    fun scoreboardUrl(season: Int, week: Int): String {
        val (type, espnWeek) = when {
            week <= 18 -> 2 to week
            week == 22 -> 3 to 5
            else -> 3 to week - 18
        }
        return "$SCOREBOARD?seasontype=$type&week=$espnWeek&dates=$season"
    }

    /** ESPN's team abbreviations where nflverse writes them differently. */
    private val TEAM_CODES = mapOf("WSH" to "WAS", "LAR" to "LA")

    fun scoreboard(text: String): List<EspnGame> {
        val events = root(text, "a scoreboard")["events"] as? JsonArray
            ?: throw LiveFormatException("ESPN changed its scoreboard format (no events list)")
        return events.mapNotNull { (it as? JsonObject)?.let(::game) }
            .also { if (it.isEmpty() && events.isNotEmpty()) throw LiveFormatException("ESPN changed its scoreboard format (no game could be read)") }
    }

    private fun game(e: JsonObject): EspnGame? {
        val competition = e.array("competitions")?.firstOrNull() as? JsonObject ?: return null
        val sides = competition.array("competitors").orEmpty().mapNotNull { it as? JsonObject }
        fun team(side: String): JsonObject? = sides.firstOrNull { it.string("homeAway") == side }
        fun code(c: JsonObject?): String? =
            c?.obj("team")?.string("abbreviation")?.let { TEAM_CODES[it] ?: it }
        val home = team("home")
        val away = team("away")
        val homeCode = code(home) ?: return null
        val awayCode = code(away) ?: return null
        val type = e.obj("status")?.obj("type") ?: return null
        val state = when (type.string("state")) {
            "pre" -> EspnGame.State.SCHEDULED
            "in" -> EspnGame.State.LIVE
            "post" -> EspnGame.State.FINAL
            else -> return null
        }
        return EspnGame(
            espnId = e.string("id") ?: return null,
            home = homeCode,
            away = awayCode,
            kickoff = (competition.string("date") ?: e.string("date"))?.let(::parseEspnTime),
            state = state,
            detail = type.string("shortDetail"),
            homeScore = home?.string("score")?.toIntOrNull().takeIf { state != EspnGame.State.SCHEDULED },
            awayScore = away?.string("score")?.toIntOrNull().takeIf { state != EspnGame.State.SCHEDULED },
        )
    }

    private val ATHLETE_ID = Regex("/id/(\\d+)(/|$)")

    private val STATUS_ABBR = mapOf(
        "Active" to "A", "Questionable" to "Q", "Doubtful" to "D", "Out" to "O", "Injured Reserve" to "IR",
    )

    fun news(text: String): List<NewsArticle> {
        val articles = root(text, "news")["articles"] as? JsonArray
            ?: throw LiveFormatException("ESPN changed its news format (no articles list)")
        return articles.mapNotNull { (it as? JsonObject)?.let(::article) }
            .also { if (it.isEmpty() && articles.isNotEmpty()) throw LiveFormatException("ESPN changed its news format (no article could be read)") }
    }

    fun injuries(text: String): List<EspnInjury> {
        val teams = root(text, "injuries")["injuries"] as? JsonArray
            ?: throw LiveFormatException("ESPN changed its injuries format (no injuries list)")
        val entries = teams.flatMap { team -> (team as? JsonObject)?.array("injuries").orEmpty() }
        return entries
            .mapNotNull { (it as? JsonObject)?.let(::injury) }
            .distinctBy { it.espnId }
            .also { if (it.isEmpty() && entries.isNotEmpty()) throw LiveFormatException("ESPN changed its injuries format (no entry could be read)") }
    }

    private fun article(a: JsonObject): NewsArticle? {
        val id = a.string("id") ?: return null
        val headline = a.string("headline") ?: return null
        val published = a.string("published")?.let(::parseEspnTime) ?: return null
        val url = a.obj("links")?.obj("web")?.string("href") ?: return null
        val athletes = a.array("categories").orEmpty().mapNotNull { c ->
            val category = c as? JsonObject ?: return@mapNotNull null
            if (category.string("type") != "athlete") return@mapNotNull null
            val espnId = category.string("athleteId") ?: return@mapNotNull null
            TaggedAthlete(espnId, category.string("description") ?: "")
        }.distinctBy { it.espnId }
        return NewsArticle(id, published, headline, a.string("description"), url, athletes)
    }

    private fun injury(e: JsonObject): EspnInjury? {
        val athlete = e.obj("athlete") ?: return null
        val espnId = athlete.array("links").orEmpty().firstNotNullOfOrNull { link ->
            (link as? JsonObject)?.string("href")?.let { ATHLETE_ID.find(it)?.groupValues?.get(1) }
        } ?: return null
        val name = athlete.string("displayName") ?: return null
        val status = e.string("status") ?: return null
        return EspnInjury(
            espnId = espnId,
            name = name,
            team = athlete.obj("team")?.string("abbreviation"),
            position = athlete.obj("position")?.string("abbreviation"),
            status = status,
            abbr = e.obj("type")?.string("abbreviation") ?: STATUS_ABBR[status] ?: status,
            shortComment = e.string("shortComment"),
            longComment = e.string("longComment"),
            date = e.string("date")?.let(::parseEspnTime),
        )
    }

    private fun root(text: String, what: String): JsonObject =
        try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: throw LiveFormatException("ESPN sent something that isn't $what JSON")
}

/**
 * ESPN writes times both with seconds (`2026-09-25T23:01:03Z`, news) and
 * without (`2026-09-25T21:57Z`, injuries). `Instant.parse` rejects the second
 * form; `OffsetDateTime.parse` reads both.
 */
internal fun parseEspnTime(text: String): Instant? =
    try {
        OffsetDateTime.parse(text).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }

/** A non-blank string (numbers as their text), or null. */
internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
