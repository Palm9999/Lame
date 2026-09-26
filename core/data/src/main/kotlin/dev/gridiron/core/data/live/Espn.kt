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
private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
