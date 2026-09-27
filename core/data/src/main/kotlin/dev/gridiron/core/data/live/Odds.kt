package dev.gridiron.core.data.live

import dev.gridiron.core.forecast.ANYTIME_TD
import dev.gridiron.core.forecast.PROP_MARKETS
import dev.gridiron.core.forecast.PropQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.zip.GZIPInputStream

/** A response of any status. Header names are lower case. */
public data class HttpResponse(val code: Int, val body: String, val headers: Map<String, String> = emptyMap()) {
    public fun header(name: String): String? = headers[name.lowercase()]
}

/**
 * Fetches a URL whatever its status: The Odds API reports credits in headers
 * and errors in the body. Throws [java.io.IOException] only when there's no
 * response at all. Tests substitute canned responses.
 */
public fun interface HttpClient {
    public suspend fun get(url: String): HttpResponse
}

/**
 * [HttpClient] over the JDK's connection. Its [IOException]s name only the
 * host: a URL can carry an API key, so it never goes into a message.
 */
public class UrlConnectionHttpClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpClient {
    override suspend fun get(url: String): HttpResponse = withContext(Dispatchers.IO) {
        val uri = URI(url)
        try {
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.setRequestProperty("Accept-Encoding", "gzip")
                val code = connection.responseCode
                val stream = if (code < 400) connection.inputStream else connection.errorStream
                val body = stream?.let { s ->
                    val input = if ("gzip".equals(connection.contentEncoding, ignoreCase = true)) GZIPInputStream(s) else s
                    input.use { it.readBytes().decodeToString() }
                }.orEmpty()
                val headers = connection.headerFields.entries
                    .mapNotNull { (name, values) -> name?.let { it.lowercase() to values.lastOrNull().orEmpty() } }
                    .toMap()
                HttpResponse(code, body, headers)
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            // The original message can hold the whole URL, key included, so only the host is kept.
            throw IOException("couldn't reach ${uri.host}")
        }
    }
}

/** A game on The Odds API's schedule, with its teams' full names ("Kansas City Chiefs"). */
internal data class OddsEvent(val id: String, val commence: Instant, val home: String, val away: String)

/** The Odds API's team names as nflverse abbreviations. */
internal val NFL_TEAMS: Map<String, String> = mapOf(
    "Arizona Cardinals" to "ARI", "Atlanta Falcons" to "ATL", "Baltimore Ravens" to "BAL", "Buffalo Bills" to "BUF",
    "Carolina Panthers" to "CAR", "Chicago Bears" to "CHI", "Cincinnati Bengals" to "CIN", "Cleveland Browns" to "CLE",
    "Dallas Cowboys" to "DAL", "Denver Broncos" to "DEN", "Detroit Lions" to "DET", "Green Bay Packers" to "GB",
    "Houston Texans" to "HOU", "Indianapolis Colts" to "IND", "Jacksonville Jaguars" to "JAX", "Kansas City Chiefs" to "KC",
    "Las Vegas Raiders" to "LV", "Los Angeles Chargers" to "LAC", "Los Angeles Rams" to "LA", "Miami Dolphins" to "MIA",
    "Minnesota Vikings" to "MIN", "New England Patriots" to "NE", "New Orleans Saints" to "NO", "New York Giants" to "NYG",
    "New York Jets" to "NYJ", "Philadelphia Eagles" to "PHI", "Pittsburgh Steelers" to "PIT", "San Francisco 49ers" to "SF",
    "Seattle Seahawks" to "SEA", "Tampa Bay Buccaneers" to "TB", "Tennessee Titans" to "TEN", "Washington Commanders" to "WAS",
)

private data class QuoteKey(val book: String, val market: String, val player: String, val point: Double?)

/**
 * The Odds API v4: URLs and parsing. Parsing walks the JSON tree like
 * [EspnParser]: unknown fields and markets are ignored, entries missing
 * what's needed are skipped, and a response that isn't the expected shape
 * at all is a [LiveFormatException].
 */
internal object OddsApi {
    private const val BASE = "https://api.the-odds-api.com/v4/sports/americanfootball_nfl"

    /** What one odds call can cost (spec §5): a credit per market returned, for one region. */
    val ODDS_CALL_COST: Int = PROP_MARKETS.size

    /** The schedule; free. */
    fun eventsUrl(key: String): String = "$BASE/events?apiKey=${encode(key)}&dateFormat=iso"

    fun oddsUrl(key: String, eventId: String): String =
        "$BASE/events/${encode(eventId)}/odds?apiKey=${encode(key)}&regions=us" +
            "&markets=${PROP_MARKETS.joinToString(",")}&oddsFormat=decimal&dateFormat=iso"

    fun events(text: String): List<OddsEvent> {
        val list = parse(text) as? JsonArray ?: throw LiveFormatException("The Odds API changed its events format")
        return list.mapNotNull { (it as? JsonObject)?.let(::event) }
            .also { if (it.isEmpty() && list.isNotEmpty()) throw LiveFormatException("The Odds API changed its events format") }
    }

    /** One quote per book, market, player and line; Over/Yes is [PropQuote.over], Under/No is [PropQuote.under]. */
    fun quotes(text: String): List<PropQuote> {
        val root = parse(text) as? JsonObject ?: throw LiveFormatException("The Odds API changed its odds format")
        val sides = LinkedHashMap<QuoteKey, Array<Double?>>()
        for (book in root.listAt("bookmakers").mapNotNull { it as? JsonObject }) {
            val bookKey = book.textAt("key") ?: continue
            for (market in book.listAt("markets").mapNotNull { it as? JsonObject }) {
                val marketKey = market.textAt("key")?.takeIf { it in PROP_MARKETS } ?: continue
                for (outcome in market.listAt("outcomes").mapNotNull { it as? JsonObject }) {
                    val player = outcome.textAt("description") ?: continue
                    val price = outcome.numberAt("price") ?: continue
                    val side = when (outcome.textAt("name")) {
                        "Over", "Yes" -> 0
                        "Under", "No" -> 1
                        else -> continue
                    }
                    val point = if (marketKey == ANYTIME_TD) null else outcome.numberAt("point") ?: continue
                    sides.getOrPut(QuoteKey(bookKey, marketKey, player, point)) { arrayOfNulls(2) }[side] = price
                }
            }
        }
        return sides.map { (k, s) -> PropQuote(k.book, k.market, k.player, k.point, s[0], s[1]) }
    }

    /** An error response's `message`, if it has one. */
    fun errorMessage(text: String): String? =
        (runCatching { parse(text) }.getOrNull() as? JsonObject)?.textAt("message")

    private fun event(o: JsonObject): OddsEvent? {
        val commence = o.textAt("commence_time")?.let {
            try {
                OffsetDateTime.parse(it).toInstant()
            } catch (_: DateTimeParseException) {
                null
            }
        }
        return OddsEvent(
            o.textAt("id") ?: return null,
            commence ?: return null,
            o.textAt("home_team") ?: return null,
            o.textAt("away_team") ?: return null,
        )
    }

    private fun parse(text: String): JsonElement =
        try {
            Json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            throw LiveFormatException("The Odds API sent something that isn't JSON")
        }

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
}

private fun JsonObject.textAt(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.numberAt(name: String): Double? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

private fun JsonObject.listAt(name: String): List<JsonElement> = (this[name] as? JsonArray).orEmpty()
