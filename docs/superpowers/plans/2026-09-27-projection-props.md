# Projection Props Implementation Plan (sub-project 3 of 4)

> **Executed, historical.** Shipped and merged; kept for lookup. It describes intent as written at the time, not current behavior. See `CLAUDE.md` for what exists.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the user has entered an Odds API key in Settings, each refresh fetches the upcoming week's player props, and the forecast blends them into that week's projections, shown in the waterfall as a `market` factor.

**Architecture:**
- **Math and blend (`:core:forecast`):** the pure prop math (de-vig, line to mean through a Gamma, inverse-variance blend) and the name matching live in the forecast. `Forecast.run` takes an optional `PropsSnapshot` and blends it into the upcoming week only. The waterfall records it as a `market` factor, and rest of season includes the blended upcoming week.
- **Fetch and storage (`:core:data`):** `PropsRepository` in the `live` package talks to The Odds API through a small HTTP client that keeps status codes and headers. It stores props in two new `live.db` tables and guards the credit budget.
- **Wiring (`:app`):** `RefreshCoordinator` fetches props before the stats build and hands the snapshot through `IngestPipeline` to the forecast. Settings stores the key and shows the credits left, when props were last fetched and the last error.

**Tech Stack:** Kotlin 2 (explicit API, warnings as errors), JUnit Jupiter (JVM modules), JUnit 4 + Robolectric (Android modules), Jetpack Compose, kotlinx.serialization JSON (tree walking, as in `Espn.kt`), bundled SQLite.

**Spec:** `docs/superpowers/specs/2026-09-26-projection-model-design.md`, §5 "Props (sub-project 3)", plus §1 (the engine's `props: PropsSnapshot?` input) and the Testing and Risks sections.

## Global Constraints

- Warnings are errors: no unused parameters, variables or imports. Explicit API mode in JVM modules (`:core:forecast`, `:core:data`, `:core:ingest`): every declaration states its visibility.
- Spec §5, verbatim:
  - Key: "The key is stored in app-private preferences and sent only to `api.the-odds-api.com`." (`AndroidManifest.xml` already has `android:allowBackup="false"`.)
  - Fetch: "`/v4/sports/americanfootball_nfl/events` (free), then, for each game in the upcoming week that hasn't kicked off and wasn't fetched in the last 24 hours, `/events/{id}/odds` with `regions=us`."
  - Markets: "`player_anytime_td`, `player_receptions`, `player_reception_yds`, `player_rush_yds`, `player_pass_yds`. That is 5 credits per game."
  - Budget: "The `x-requests-remaining` header is recorded after every call. The next call is skipped if it would go below zero. Settings shows credits left and when props were last fetched."
  - Storage: "Props go in `live.db` as a new `prop_line` table, pruned after the game."
  - Matching: "Prop names are matched to players by normalized name plus team. Unmatched props are dropped and counted in the refresh report."
  - Conversion: "Each book is de-vigged proportionally, then: Anytime TD `p` becomes `λ = −ln(1−p)`. An over/under at a line becomes a mean via a Gamma with the position's CV."
  - Blend: "Inverse-variance weighting with the model's final mean. It is recorded as a `market` factor with a note like 'Props: 64.5 rec yds'."
  - Failures: "A bad key, no credits left, or a network error leaves the model's number untouched. Settings shows the reason."
- **The key never appears in any message**, toast, exception text or log line. Every error the Odds API path produces is fixed text plus the API's own `message` field.
- Props apply to the **upcoming week only**. Past weeks (the backtest) and the stored past seasons are never touched, so the CI accuracy gate is unaffected.
- `FORECAST_VERSION` goes from 2 to 3 (a new stored factor). `SCHEMA_VERSION` stays 7. `live.db` gains two tables through `CREATE TABLE IF NOT EXISTS`, so its `user_version` stays 1 and existing news and injuries are kept.
- Copy rules (from existing UI): status lines are one sentence each, with no exclamation marks.

## Review Focus

- **Two players with the same name in one game.** Neither is blended, and both count as unmatched; a prop is never given to the wrong player. Test in Task 2 (`MarketTest`).
- **The key is refused, or credits run out partway through a slate.** Props already fetched are kept, the model's numbers stand for the rest, and Settings and the toast say why. Tests in Task 4.
- **A refresh after a game has kicked off.** Kicked-off games are never fetched (live lines aren't pre-game props), and a game's props are pruned 12 hours after kickoff. Tests in Task 4.
- **The key in an error.** No error path (network failure, HTTP error, bad JSON) puts the key in text. Tests in Tasks 3 and 4.
- **A book offers only one side of a yardage line, or a nonsense price (≤ 1.0).** That quote is skipped, never NaN. A one-sided anytime-TD price is discounted by a fixed margin instead. Test in Task 1.

## File Structure

**New**

| File | Responsibility |
|---|---|
| `core/forecast/.../Props.kt` | Public `PropQuote`, `PropEvent`, `PropsSnapshot`, `PropsOutcome`, `PROP_MARKETS`, `ANYTIME_TD`; internal `normalizeName` |
| `core/forecast/.../PropMath.kt` | `lnGamma`, `regularizedGammaP`, `meanForLine`, `devig`, `anytimeTdChance`, `tdsFromChance`, `blendMean`, `MarketView`, `marketViews` |
| `core/forecast/.../Market.kt` | `PropCandidate`, `MarketMatch` (props → players for one week), `Blended`, `blend()` |
| `core/data/.../live/Odds.kt` | `HttpResponse`, `HttpClient`, `UrlConnectionHttpClient`, `OddsEvent`, `OddsApi` (URLs, parsing), `NFL_TEAMS` |
| `core/data/.../live/PropsStore.kt` | SQL for `prop_event` and `prop_line` |
| `core/data/.../live/PropsRepository.kt` | `PropsStatus`, `PropsRepository` (rationed fetch, snapshot, status), `upcomingWeek`, `nextWednesday` |

**Modified**

| File | Change |
|---|---|
| `core/forecast/.../ForecastConstants.kt` | `FORECAST_VERSION` 3; `K.MARKET_VARIANCE_RATIO`, `K.ONE_SIDED_OVERROUND` |
| `core/forecast/.../Forecast.kt` | `run(…, props)`, `ForecastReport.props` |
| `core/forecast/.../Projector.kt` | Blends the upcoming week, emits the `market` factor, and feeds the blended week into rest of season |
| `core/data/build.gradle.kts`, `core/ingest/build.gradle.kts` | `api(projects.core.forecast)` |
| `core/data/.../live/LiveDb.kt` | Two new tables |
| `core/datastore/.../UserPrefs.kt`, `UserPrefsJson.kt` | `oddsApiKey` |
| `core/data/.../SettingsRepository.kt` | `oddsApiKey`, `setOddsApiKey` |
| `core/ingest/.../IngestPipeline.kt` | `build(…, props)`, `IngestReport.props` |
| `app/.../RefreshCoordinator.kt`, `RefreshText.kt`, `SettingsScreen.kt`, `GridironApplication.kt`, `GridironNavHost.kt` | Wiring, toast and Settings |
| `CLAUDE.md` | Docs |

## Sessions

The user runs 4 tasks per session:
- **Session A:** Tasks 1–4.
- **Session B:** Tasks 5–6, then the final whole-branch review.

---

### Task 1: The prop math

**Files:**
- Create: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Props.kt`
- Create: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/PropMath.kt`
- Modify: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/ForecastConstants.kt`
- Test: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/PropMathTest.kt`

**Interfaces:**
- Consumes: `varianceFor(mean: Double, cv: Double): Double` (`ForecastMath.kt`), `K.EMPIRICAL_CV`.
- Produces (public): `PropQuote(book, market, player, point: Double?, over: Double?, under: Double?)`, `PropEvent(home, away, quotes)`, `PropsSnapshot(events)`, `PropsOutcome(blended: Int, unmatched: Int)`, `PROP_MARKETS: List<String>`, `ANYTIME_TD`.
- Produces (internal): `normalizeName(String): String`, `lnGamma`, `regularizedGammaP(a, x)`, `meanForLine(line, pOver, variance: (Double) -> Double): Double?`, `devig(first, second)`, `anytimeTdChance(yes, no: Double?)`, `tdsFromChance(p)`, `blendMean(model, market, cv)`, `MarketView(market, mean, label)`, `marketViews(quotes, cv): List<MarketView>`, `MARKET_STATS: Map<String, String>`.

- [ ] **Step 1: Write the failing tests**

`core/forecast/src/test/kotlin/dev/gridiron/core/forecast/PropMathTest.kt`:

```kotlin
package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.E
import kotlin.math.exp
import kotlin.math.ln

class PropMathTest {
    @Test
    fun `names compare without accents, punctuation, suffixes or case`() {
        assertEquals("amonra st brown", normalizeName("Amon-Ra St. Brown"))
        assertEquals("marvin harrison", normalizeName("Marvin Harrison Jr."))
        assertEquals(normalizeName("DJ Moore"), normalizeName("D.J. Moore"))
        assertEquals(normalizeName("Kenneth Walker"), normalizeName("Kenneth Walker III"))
        assertEquals("donta foreman", normalizeName("D'Onta  Foreman"))
        assertEquals("jose gonzalez", normalizeName("José González"))
    }

    @Test
    fun `log gamma matches known values`() {
        assertEquals(ln(24.0), lnGamma(5.0), 1e-12)
        assertEquals(0.5723649429247004, lnGamma(0.5), 1e-12) // ln √π
        assertEquals(0.0, lnGamma(1.0), 1e-12)
    }

    @Test
    fun `the regularized incomplete gamma matches closed forms on both of its branches`() {
        // Shape 1 is the exponential: P = 1 - e^-x. x = 0.5 takes the series, x = 3 the continued fraction.
        assertEquals(1 - exp(-0.5), regularizedGammaP(1.0, 0.5), 1e-12)
        assertEquals(1 - exp(-3.0), regularizedGammaP(1.0, 3.0), 1e-12)
        // Shape 2: P = 1 - e^-x (1 + x).
        assertEquals(1 - 2 / E, regularizedGammaP(2.0, 1.0), 1e-12)
        assertEquals(1 - 6 * exp(-5.0), regularizedGammaP(2.0, 5.0), 1e-12)
        assertEquals(0.0, regularizedGammaP(2.0, 0.0))
    }

    @Test
    fun `a line and its chance of going over give the mean`() {
        // Variance m² makes every Gamma an exponential: P(X > 10) = e^(-10/m), so m = 10 / ln(1/p).
        assertEquals(10 / ln(2.0), meanForLine(10.0, 0.5) { m -> m * m }!!, 1e-6)
        assertEquals(10 / ln(4.0), meanForLine(10.0, 0.25) { m -> m * m }!!, 1e-6)
        // With layer 7's variance, the mean found gives back the chance it was found from.
        val mean = meanForLine(64.5, 0.55) { m -> varianceFor(m, 0.70) }!!
        val v = varianceFor(mean, 0.70)
        assertEquals(0.55, 1 - regularizedGammaP(mean * mean / v, 64.5 * mean / v), 1e-9)
        assertTrue(mean > 64.5, "a Gamma's median is below its mean: $mean")
    }

    @Test
    fun `a chance of zero or one, or a line of zero, has no mean`() {
        assertNull(meanForLine(10.0, 0.0) { m -> m * m })
        assertNull(meanForLine(10.0, 1.0) { m -> m * m })
        assertNull(meanForLine(0.0, 0.5) { m -> m * m })
    }

    @Test
    fun `a two-way price loses its margin in proportion`() {
        assertEquals((1 / 1.87) / (1 / 1.87 + 1 / 1.95), devig(1.87, 1.95), 1e-12)
        assertEquals(0.5, devig(1.9, 1.9), 1e-12)
    }

    @Test
    fun `an anytime TD is de-vigged against No, or discounted when the book only offers Yes`() {
        assertEquals(devig(2.2, 1.65), anytimeTdChance(2.2, 1.65), 1e-12)
        assertEquals((1 / 1.5) / K.ONE_SIDED_OVERROUND, anytimeTdChance(1.5, null), 1e-12)
        assertEquals(-ln(1 - 0.4), tdsFromChance(0.4), 1e-12)
        // A price that says a TD is certain is capped, never infinite.
        assertTrue(tdsFromChance(1.0).isFinite())
    }

    @Test
    fun `the blend weighs each mean by the inverse of its variance`() {
        val vModel = varianceFor(60.0, 0.7)
        val vMarket = K.MARKET_VARIANCE_RATIO * varianceFor(70.0, 0.7)
        val expected = (60.0 / vModel + 70.0 / vMarket) / (1 / vModel + 1 / vMarket)
        assertEquals(expected, blendMean(60.0, 70.0, 0.7), 1e-9)
        assertTrue(blendMean(60.0, 70.0, 0.7) > 65.0, "the market is weighted more heavily")
        assertEquals(60.0, blendMean(60.0, 60.0, 0.7), 1e-9)
        // A side with nothing gives way to the other.
        assertEquals(70.0, blendMean(0.0, 70.0, 0.7))
        assertEquals(60.0, blendMean(60.0, 0.0, 0.7))
    }

    @Test
    fun `each market's books are averaged, and a line needs both sides and real prices`() {
        val quotes = listOf(
            PropQuote("draftkings", "player_reception_yds", "Puka Nacua", 84.5, 1.87, 1.95),
            PropQuote("fanduel", "player_reception_yds", "Puka Nacua", 85.5, 1.9, 1.9),
            PropQuote("betmgm", "player_reception_yds", "Puka Nacua", 90.5, 1.9, null), // one side: skipped
            PropQuote("caesars", "player_reception_yds", "Puka Nacua", 70.5, 1.0, 1.9), // no real price: skipped
            PropQuote("draftkings", "player_anytime_td", "Puka Nacua", null, 2.2, 1.65),
            PropQuote("fanduel", "player_anytime_td", "Puka Nacua", null, 2.3, null),
        )

        val views = marketViews(quotes, 0.7)

        val variance = { m: Double -> varianceFor(m, 0.7) }
        val yards = (meanForLine(84.5, devig(1.87, 1.95), variance)!! + meanForLine(85.5, 0.5, variance)!!) / 2
        val tds = (tdsFromChance(devig(2.2, 1.65)) + tdsFromChance(anytimeTdChance(2.3, null))) / 2
        assertEquals(listOf("player_reception_yds", "player_anytime_td"), views.map { it.market })
        assertEquals(yards, views[0].mean, 1e-9)
        assertEquals("85.0 rec yds", views[0].label)
        assertEquals(tds, views[1].mean, 1e-9)
        assertEquals("TD ${Math.round((1 - exp(-tds)) * 100)}%", views[1].label)
    }

    @Test
    fun `no usable quote means no view`() {
        val quotes = listOf(PropQuote("betmgm", "player_receptions", "Puka Nacua", 6.5, 1.9, null))
        assertTrue(marketViews(quotes, 0.7).isEmpty())
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew :core:forecast:test --tests "dev.gridiron.core.forecast.PropMathTest"`
Expected: compilation fails with unresolved references (`normalizeName`, `lnGamma`, `PropQuote`, `K.ONE_SIDED_OVERROUND`, …).

- [ ] **Step 3: Add the two constants**

In `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/ForecastConstants.kt`, insert this block after the Layer 7 lines (after `val EMPIRICAL_CV …`):

```kotlin

    // Props (spec §5). Props can't be backtested (no historical props), so these are judgments, not fits.
    // The market's variance is this share of layer 7's for its mean: markets are sharper than the model, so
    // with equal means the market gets 1 / (1 + 0.5) = two thirds of the weight.
    const val MARKET_VARIANCE_RATIO = 0.5
    // A book that offers only Yes on an anytime TD can't be de-vigged; its implied chance is divided by this.
    const val ONE_SIDED_OVERROUND = 1.08
```

- [ ] **Step 4: Write the public types**

`core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Props.kt`:

```kotlin
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
```

- [ ] **Step 5: Write the math**

`core/forecast/src/main/kotlin/dev/gridiron/core/forecast/PropMath.kt`:

```kotlin
package dev.gridiron.core.forecast

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

/** The stat each two-way market prices. [ANYTIME_TD] prices rushing plus receiving TDs, handled apart. */
internal val MARKET_STATS: Map<String, String> = mapOf(
    "player_receptions" to "receptions",
    "player_reception_yds" to "receiving_yards",
    "player_rush_yds" to "rushing_yards",
    "player_pass_yds" to "passing_yards",
)

private val SHORT_NAMES = mapOf(
    "player_receptions" to "rec",
    "player_reception_yds" to "rec yds",
    "player_rush_yds" to "rush yds",
    "player_pass_yds" to "pass yds",
)

private val LANCZOS = doubleArrayOf(
    0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
    -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7,
)

/** ln Γ([x]) for x > 0: Lanczos (g = 7), with the reflection formula below 0.5. */
internal fun lnGamma(x: Double): Double {
    if (x < 0.5) return ln(PI / abs(sin(PI * x))) - lnGamma(1.0 - x)
    val z = x - 1.0
    var a = LANCZOS[0]
    for (i in 1 until LANCZOS.size) a += LANCZOS[i] / (z + i)
    val t = z + 7.5
    return 0.5 * ln(2 * PI) + (z + 0.5) * ln(t) - t + ln(a)
}

/**
 * P(a, x) = γ(a, x) / Γ(a): the chance a Gamma with shape [a] and scale 1
 * is below [x]. A series below x = a + 1, Lentz's continued fraction above.
 */
internal fun regularizedGammaP(a: Double, x: Double): Double {
    if (x <= 0.0) return 0.0
    val front = exp(-x + a * ln(x) - lnGamma(a))
    if (x < a + 1.0) {
        var term = 1.0 / a
        var sum = term
        var ap = a
        var n = 0
        while (n++ < 1000) {
            ap += 1.0
            term *= x / ap
            sum += term
            if (abs(term) < abs(sum) * 1e-15) break
        }
        return (front * sum).coerceIn(0.0, 1.0)
    }
    val tiny = 1e-300
    var b = x + 1.0 - a
    var c = 1.0 / tiny
    var d = 1.0 / b
    var h = d
    for (i in 1..1000) {
        val an = -i * (i - a)
        b += 2.0
        d = an * d + b
        if (abs(d) < tiny) d = tiny
        c = b + an / c
        if (abs(c) < tiny) c = tiny
        d = 1.0 / d
        val delta = d * c
        h *= delta
        if (abs(delta - 1.0) < 1e-15) break
    }
    return (1.0 - front * h).coerceIn(0.0, 1.0)
}

/**
 * The mean of a Gamma whose chance of going over [line] is [pOver], when a
 * mean m has variance [variance](m) (spec §5: "a Gamma with the position's
 * CV"). Null when [pOver] isn't strictly between 0 and 1, or [line] isn't
 * positive.
 */
internal fun meanForLine(line: Double, pOver: Double, variance: (Double) -> Double): Double? {
    if (!(pOver > 0.0 && pOver < 1.0) || line <= 0.0) return null
    fun over(m: Double): Double {
        val v = variance(m)
        return 1.0 - regularizedGammaP(m * m / v, line * m / v)
    }
    var hi = line
    var doublings = 0
    while (over(hi) < pOver) {
        hi *= 2.0
        if (++doublings > 60) return null
    }
    var lo = 0.0
    repeat(100) {
        val mid = (lo + hi) / 2
        if (over(mid) < pOver) lo = mid else hi = mid
    }
    return (lo + hi) / 2
}

/** A two-way market's chance of its first side, with the book's margin removed in proportion. */
internal fun devig(first: Double, second: Double): Double {
    val a = 1.0 / first
    val b = 1.0 / second
    return a / (a + b)
}

/** An anytime-TD price as a chance: de-vigged against No when the book offers it, else discounted by [K.ONE_SIDED_OVERROUND]. */
internal fun anytimeTdChance(yes: Double, no: Double?): Double =
    if (no != null) devig(yes, no) else (1.0 / yes) / K.ONE_SIDED_OVERROUND

private const val MAX_TD_CHANCE = 0.99

/** Expected touchdowns from the chance of at least one, when they are Poisson: λ = −ln(1 − p). */
internal fun tdsFromChance(p: Double): Double = -ln(1.0 - p.coerceIn(0.0, MAX_TD_CHANCE))

/**
 * Inverse-variance weighting of the model's mean and the market's. The
 * model's variance is layer 7's for its mean. The market's is
 * [K.MARKET_VARIANCE_RATIO] times layer 7's for its own mean. A side with
 * nothing (a mean of zero or less) gives way to the other.
 */
internal fun blendMean(model: Double, market: Double, cv: Double): Double {
    if (model <= 0.0) return market.coerceAtLeast(0.0)
    if (market <= 0.0) return model
    val wModel = 1.0 / varianceFor(model, cv)
    val wMarket = 1.0 / (K.MARKET_VARIANCE_RATIO * varianceFor(market, cv))
    return (wModel * model + wMarket * market) / (wModel + wMarket)
}

/** The market's consensus on one stat for one player, and how the factor note shows it ("64.5 rec yds", "TD 38%"). */
internal data class MarketView(val market: String, val mean: Double, val label: String)

private fun realPrice(price: Double?): Boolean = price != null && price.isFinite() && price > 1.0

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}

/**
 * Each market's consensus for one player, in [PROP_MARKETS] order. Every
 * book's quote is de-vigged and turned into a mean, and the means are
 * averaged across books. A two-way line counts only with both sides at real
 * prices (above 1.0). [cv] is layer 7's for the player's position. Markets
 * with no usable quote are left out.
 */
internal fun marketViews(quotes: List<PropQuote>, cv: Double): List<MarketView> =
    PROP_MARKETS.mapNotNull { market ->
        val mine = quotes.filter { it.market == market && realPrice(it.over) }
        if (market == ANYTIME_TD) {
            if (mine.isEmpty()) return@mapNotNull null
            val tds = mine.map { tdsFromChance(anytimeTdChance(it.over!!, it.under?.takeIf(::realPrice))) }.average()
            MarketView(market, tds, "TD ${((1.0 - exp(-tds)) * 100).roundToInt()}%")
        } else {
            val priced = mine.filter { it.point != null && realPrice(it.under) }
            val means = priced.mapNotNull { q -> meanForLine(q.point!!, devig(q.over!!, q.under!!)) { m -> varianceFor(m, cv) } }
            if (means.isEmpty()) return@mapNotNull null
            val line = String.format(Locale.US, "%.1f", median(priced.map { it.point!! }))
            MarketView(market, means.average(), "$line ${SHORT_NAMES.getValue(market)}")
        }
    }
```

- [ ] **Step 6: Run the tests to see them pass**

Run: `./gradlew :core:forecast:test --tests "dev.gridiron.core.forecast.PropMathTest"`
Expected: PASS, 10 tests.

If `the regularized incomplete gamma …` fails near 1e-12, don't loosen it: check the series' stopping rule and the Lentz loop against the code above.

- [ ] **Step 7: Run the module and commit**

Run: `./gradlew :core:forecast:test`
Expected: PASS (every earlier test unchanged).

```bash
git add core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Props.kt \
        core/forecast/src/main/kotlin/dev/gridiron/core/forecast/PropMath.kt \
        core/forecast/src/main/kotlin/dev/gridiron/core/forecast/ForecastConstants.kt \
        core/forecast/src/test/kotlin/dev/gridiron/core/forecast/PropMathTest.kt
git commit -m "forecast: prop math (de-vig, line to mean, inverse-variance blend)"
```

---

### Task 2: The forecast blends props into the upcoming week

**Files:**
- Create: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Market.kt`
- Modify: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Projector.kt`
- Modify: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Forecast.kt`
- Modify: `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/ForecastConstants.kt:8` (`FORECAST_VERSION`)
- Test: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/MarketTest.kt`
- Test: `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/ForecastEngineTest.kt`

**Interfaces:**
- Consumes (Task 1): `PropsSnapshot`, `PropEvent`, `PropQuote`, `PropsOutcome`, `ANYTIME_TD`, `MARKET_STATS`, `marketViews`, `blendMean`, `normalizeName`.
- Produces: `Forecast.run(conn, builtAt, copy: SeasonCopy? = null, props: PropsSnapshot? = null, onWeek = …)`, and `ForecastReport.props: PropsOutcome?`, which is null when no snapshot was given. Internal: `PropCandidate`, `MarketMatch`, `Blended`, `blend()`.

- [ ] **Step 1: Write the failing unit tests**

`core/forecast/src/test/kotlin/dev/gridiron/core/forecast/MarketTest.kt`:

```kotlin
package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MarketTest {
    private fun quote(player: String, market: String = "player_receptions") = PropQuote("dk", market, player, 5.5, 1.9, 1.9)

    private val candidates = listOf(
        PropCandidate("p1", "Amon-Ra St. Brown", "DET"),
        PropCandidate("p2", "Josh Allen", "BUF"),
        PropCandidate("p3", "Josh Allen", "JAX"),
        PropCandidate("p4", "Mike Williams", "PIT"),
        PropCandidate("p5", "Mike Williams", "PIT"),
    )

    @Test
    fun `a name matches the one player with it on the game's two teams, in either team order`() {
        val snapshot = PropsSnapshot(
            listOf(
                PropEvent("DET", "GB", listOf(quote("Amon Ra St Brown Jr."))),
                PropEvent("MIA", "BUF", listOf(quote("Josh Allen"))), // listed MIA-BUF, scheduled BUF-MIA
            ),
        )

        val match = MarketMatch(snapshot, listOf("DET" to "GB", "BUF" to "MIA"), candidates)

        // "Amon Ra" and "Amon-Ra" differ once punctuation goes, so only an exact normalized match counts.
        assertNull(match.quotes("p1"))
        assertEquals(listOf(quote("Josh Allen")), match.quotes("p2"))
        assertNull(match.quotes("p3")) // the other Josh Allen isn't in that game
    }

    @Test
    fun `a suffix or punctuation difference still matches`() {
        val snapshot = PropsSnapshot(listOf(PropEvent("DET", "GB", listOf(quote("Amon-Ra St. Brown Jr.")))))
        val match = MarketMatch(snapshot, listOf("DET" to "GB"), candidates)
        assertEquals(1, match.quotes("p1")!!.size)
    }

    @Test
    fun `two players with one name in the game match neither`() {
        val snapshot = PropsSnapshot(listOf(PropEvent("PIT", "CLE", listOf(quote("Mike Williams")))))
        val match = MarketMatch(snapshot, listOf("PIT" to "CLE"), candidates)
        assertNull(match.quotes("p4"))
        assertNull(match.quotes("p5"))
    }

    @Test
    fun `an event that isn't one of the week's games matches nobody`() {
        val snapshot = PropsSnapshot(listOf(PropEvent("BUF", "NYJ", listOf(quote("Josh Allen")))))
        val match = MarketMatch(snapshot, listOf("BUF" to "MIA"), candidates)
        assertNull(match.quotes("p2"))
    }

    @Test
    fun `the blend moves each priced stat and splits the TD market by the model's own split`() {
        val final = mapOf("receptions" to 5.0, "receiving_yards" to 60.0, "receiving_tds" to 0.3, "rushing_tds" to 0.1)
        val quotes = listOf(
            PropQuote("dk", "player_receptions", "X", 5.5, 1.8, 2.0),
            PropQuote("dk", ANYTIME_TD, "X", null, 2.2, 1.65),
        )

        val blended = blend(final, quotes, "WR")!!

        val cv = K.EMPIRICAL_CV.getValue("WR")
        val views = marketViews(quotes, cv)
        val tds = blendMean(0.4, views[1].mean, cv)
        assertEquals(blendMean(5.0, views[0].mean, cv), blended.components.getValue("receptions"), 1e-12)
        assertEquals(60.0, blended.components.getValue("receiving_yards")) // no yardage market: unchanged
        assertEquals(0.3 * tds / 0.4, blended.components.getValue("receiving_tds"), 1e-12)
        assertEquals(0.1 * tds / 0.4, blended.components.getValue("rushing_tds"), 1e-12)
        assertEquals("Props: 5.5 rec, TD 43%", blended.note)
    }

    @Test
    fun `a TD market for a player the model gave no TDs goes to his position's usual kind`() {
        val quotes = listOf(PropQuote("dk", ANYTIME_TD, "X", null, 2.2, 1.65))
        val cv = K.EMPIRICAL_CV.getValue("RB")
        val tds = marketViews(quotes, cv).single().mean

        val rb = blend(mapOf("carries" to 12.0), quotes, "RB")!!
        val te = blend(mapOf("targets" to 4.0), quotes, "TE")!!

        assertEquals(tds, rb.components.getValue("rushing_tds"), 1e-12)
        assertEquals(null, rb.components["receiving_tds"])
        assertEquals(marketViews(quotes, K.EMPIRICAL_CV.getValue("TE")).single().mean, te.components.getValue("receiving_tds"), 1e-12)
    }

    @Test
    fun `no usable quote means no blend`() {
        val quotes = listOf(PropQuote("dk", "player_receptions", "X", 5.5, 1.9, null))
        assertNull(blend(mapOf("receptions" to 5.0), quotes, "WR"))
    }
}
```

- [ ] **Step 2: Write the failing engine tests**

In `core/forecast/src/test/kotlin/dev/gridiron/core/forecast/ForecastEngineTest.kt`:

(a) The `run` helper passes `onWeek` positionally, which the new `props` parameter would break. Replace it with:

```kotlin
    private fun run(db: TestDb, copy: SeasonCopy? = null, props: PropsSnapshot? = null, onWeek: (Int, Int) -> Unit = { _, _ -> }): ForecastReport =
        Forecast.run(db.conn, builtAt, copy, props, onWeek)
```

(b) Add these helpers below `factors(...)`:

```kotlin
    private fun finalMean(db: TestDb, player: String, week: Int, metric: String): Double = db.query(
        "SELECT mean FROM player_week_projection WHERE player_id = '$player' AND season = 2025 AND week = $week AND stage = 'final' AND metric_id = '$metric'",
    ).single()[0]!!.toDouble()

    private fun rosMean(db: TestDb, player: String, metric: String): Double = db.query(
        "SELECT mean FROM player_ros_projection WHERE player_id = '$player' AND season = 2025 AND as_of_week = 2 AND metric_id = '$metric'",
    ).single()[0]!!.toDouble()

    private val wrAProps = PropsSnapshot(
        listOf(
            PropEvent(
                "AAA", "DDD",
                listOf(
                    PropQuote("dk", "player_reception_yds", "Player WR_A", 120.5, 1.9, 1.9),
                    PropQuote("dk", "player_anytime_td", "Player WR_A", null, 1.5, null),
                ),
            ),
        ),
    )
```

(c) Add these tests at the end of the class:

```kotlin
    @Test
    fun `props blend into the upcoming week as a market factor, and into rest of season`() {
        league("a.db").use { plain ->
            league("b.db").use { priced ->
                assertNull(run(plain).props)
                val report = run(priced, props = wrAProps)

                assertEquals(PropsOutcome(blended = 1, unmatched = 0), report.props)
                val before = finalMean(plain, "WR_A", 3, "receiving_yards")
                val after = finalMean(priced, "WR_A", 3, "receiving_yards")
                assertTrue(after > before, "a 120.5-yard line pulls up a ${"%.1f".format(before)}-yard projection: $after")
                assertTrue(finalMean(priced, "WR_A", 3, "receiving_tds") > finalMean(plain, "WR_A", 3, "receiving_tds"))
                // The baseline stage is the model's alone.
                assertEquals(
                    plain.query("SELECT metric_id, mean FROM player_week_projection WHERE player_id = 'WR_A' AND week = 3 AND stage = 'baseline' ORDER BY 1"),
                    priced.query("SELECT metric_id, mean FROM player_week_projection WHERE player_id = 'WR_A' AND week = 3 AND stage = 'baseline' ORDER BY 1"),
                )
                assertEquals(listOf("game_script", "market", "matchup"), factors(priced, "WR_A"))
                assertEquals(
                    "Props: 120.5 rec yds, TD 62%",
                    priced.query("SELECT note FROM player_week_projection_factor WHERE player_id = 'WR_A' AND season = 2025 AND week = 3 AND factor = 'market'").single()[0],
                )
                // Rest of season carries the blended week 3 and an unchanged week 4.
                assertEquals(after - before, rosMean(priced, "WR_A", "receiving_yards") - rosMean(plain, "WR_A", "receiving_yards"), 1e-9)
                // Nobody else and no past week moved.
                val others = "SELECT player_id, season, week, metric_id, stage, mean FROM player_week_projection WHERE NOT (player_id = 'WR_A' AND week = 3) ORDER BY 1, 2, 3, 4, 5"
                assertEquals(plain.query(others), priced.query(others))
            }
        }
    }

    @Test
    fun `props for nobody projected, or for a game that isn't this week's, are counted and dropped`() {
        league("a.db").use { plain ->
            league("b.db").use { priced ->
                run(plain)
                val props = PropsSnapshot(
                    listOf(
                        PropEvent("AAA", "DDD", listOf(PropQuote("dk", "player_receptions", "Nobody Here", 4.5, 1.9, 1.9))),
                        PropEvent("AAA", "BBB", listOf(PropQuote("dk", "player_receptions", "Player WR_B", 4.5, 1.9, 1.9))),
                    ),
                )

                val report = run(priced, props = props)

                assertEquals(PropsOutcome(blended = 0, unmatched = 2), report.props)
                assertEquals(projections(plain), projections(priced))
                assertEquals(listOf("game_script", "matchup"), factors(priced, "WR_A"))
            }
        }
    }

    @Test
    fun `props with no upcoming week are all unmatched`() {
        league("a.db", allPlayed = true).use { db ->
            assertEquals(PropsOutcome(blended = 0, unmatched = 1), run(db, props = wrAProps).props)
        }
    }
```

Add `import org.junit.jupiter.api.Assertions.assertNull` to the imports.

- [ ] **Step 3: Run the tests to see them fail**

Run: `./gradlew :core:forecast:test --tests "dev.gridiron.core.forecast.MarketTest" --tests "dev.gridiron.core.forecast.ForecastEngineTest"`
Expected: compilation fails: unresolved `PropCandidate`, `MarketMatch`, `blend`, and `Forecast.run` has no `props` parameter.

- [ ] **Step 4: Write the matching and the blend**

`core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Market.kt`:

```kotlin
package dev.gridiron.core.forecast

/** A player placed on a team for the upcoming week, whom a prop could name. */
internal data class PropCandidate(val playerId: String, val name: String, val team: String)

/**
 * Props matched to players for one week (spec §5: "normalized name plus
 * team"). An event counts when it is one of [games], in either team order
 * (neutral sites). A name matches when exactly one candidate on the event's
 * two teams has it after [normalizeName]; two players with one name match
 * neither, so a prop is never given to the wrong player.
 */
internal class MarketMatch(snapshot: PropsSnapshot, games: Collection<Pair<String, String>>, candidates: List<PropCandidate>) {
    private val byPlayer = HashMap<String, MutableList<PropQuote>>()

    init {
        val pairs = games.map { (home, away) -> setOf(home, away) }.toSet()
        for (event in snapshot.events) {
            val teams = setOf(event.home, event.away)
            if (teams !in pairs) continue
            val onTeams = candidates.filter { it.team in teams }.groupBy { normalizeName(it.name) }
            for ((name, quotes) in event.quotes.groupBy { normalizeName(it.player) }) {
                val who = onTeams[name]?.singleOrNull() ?: continue
                byPlayer.getOrPut(who.playerId) { mutableListOf() } += quotes
            }
        }
    }

    /** Every quote naming [playerId], or null when none does. */
    fun quotes(playerId: String): List<PropQuote>? = byPlayer[playerId]
}

/** A projection after props, and the `market` factor's note ("Props: 64.5 rec yds, TD 38%"). */
internal class Blended(val components: Map<String, Double>, val note: String)

/**
 * [final] with each priced stat blended toward the market ([blendMean]).
 * The anytime-TD market prices rushing plus receiving TDs: the blended total
 * is split by the model's own split, or, when the model had none, given to
 * rushing for QBs and RBs and receiving for WRs and TEs. Null when no quote
 * is usable.
 */
internal fun blend(final: Map<String, Double>, quotes: List<PropQuote>, position: String): Blended? {
    val cv = K.EMPIRICAL_CV.getValue(position)
    val views = marketViews(quotes, cv)
    if (views.isEmpty()) return null
    val out = final.toMutableMap()
    for (view in views) {
        if (view.market == ANYTIME_TD) {
            val rushing = final["rushing_tds"] ?: 0.0
            val receiving = final["receiving_tds"] ?: 0.0
            val model = rushing + receiving
            val total = blendMean(model, view.mean, cv)
            if (model > 0.0) {
                if (rushing > 0.0) out["rushing_tds"] = rushing * total / model
                if (receiving > 0.0) out["receiving_tds"] = receiving * total / model
            } else {
                out[if (position == "WR" || position == "TE") "receiving_tds" else "rushing_tds"] = total
            }
        } else {
            val stat = MARKET_STATS.getValue(view.market)
            out[stat] = blendMean(final[stat] ?: 0.0, view.mean, cv)
        }
    }
    return Blended(out, "Props: " + views.joinToString(", ") { it.label })
}
```

- [ ] **Step 5: Let the projector blend the upcoming week**

In `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Projector.kt`:

(a) Replace the `ProjectionOutcome` and `Prepared` declarations at the top with:

```kotlin
internal class ProjectionOutcome(val status: String, val upcoming: Map<Int, Int>, val weeks: Int, val props: PropsOutcome?)

private enum class WeekKind { PAST, UPCOMING, REST }

/**
 * A player's upcoming-week baseline and team: reused, with each remaining
 * week's opponent, for rest of season. [upcoming] is the upcoming week's
 * final projection with props blended in, when he has one.
 */
private class Prepared(
    val player: PlayerInfo,
    val team: String,
    val baseline: Map<String, Double>,
    val passRate: Double,
    val upcoming: Map<String, Double>? = null,
)
```

(b) Add a `props` constructor parameter after `sink`, and two counters after `private var added = 0`:

```kotlin
internal class Projector(
    private val inputs: ForecastInputs,
    /** Seasons copied from the previous database: their weeks aren't recomputed. */
    private val copied: Set<Int>,
    private val sink: ProjectionSink,
    /** Props for the upcoming week; null when the user has none. */
    private val props: PropsSnapshot?,
    private val onWeek: (season: Int, week: Int) -> Unit,
) {
```

```kotlin
    private var added = 0
    private var blended = 0

    /** Distinct names per event in [props]: whoever isn't blended is unmatched. */
    private val propNames = props?.events?.sumOf { e -> e.quotes.map { normalizeName(it.player) }.distinct().size } ?: 0
```

(c) In `run()`, replace the final `return ProjectionOutcome(status, upcomingMap, projected)` with:

```kotlin
        return ProjectionOutcome(status, upcomingMap, projected, props?.let { PropsOutcome(blended, propNames - blended) })
```

(d) Replace `prepareWeek` with:

```kotlin
    /**
     * One week's projections, a team at a time: the expected starting QB and
     * the active players, with the team's target and carry shares scaled to
     * sum to one (spec amendment to layer 2). The upcoming week's players are
     * matched to [props] first.
     */
    private fun prepareWeek(state: WeekState, kind: WeekKind): List<Prepared> {
        val drafts = candidates(state.order).mapNotNull { draft(state, it, kind) }
        val market = if (kind == WeekKind.UPCOMING && props != null) {
            MarketMatch(
                props,
                inputs.games.filter { it.season == state.season && it.week == state.week }.map { it.home to it.away },
                drafts.map { PropCandidate(it.player.playerId, it.player.name, it.team) },
            )
        } else {
            null
        }
        return drafts.groupBy { it.team }.flatMap { (team, onTeam) ->
            val starter = expectedStarter(team, onTeam, state, kind)
            val kept = onTeam.filter { d ->
                if (d.player.position == "QB") d.player.playerId == starter else isActive(d, team, state, kind)
            }
            val shares = normalizeShares(
                kept.associate { d -> d.player.playerId to model.shares(d.ctx, d.rates, starter = d.player.playerId == starter) },
            )
            val volume = teamVolume(teamHistory[team].orEmpty().takeWhile { it.order < state.order }, state.leagueTeam)
            kept.map { d -> finish(state, d, shares.getValue(d.player.playerId), volume, kind, market) }
        }
    }
```

(e) Replace `finish` with:

```kotlin
    private fun finish(state: WeekState, d: Draft, shares: Shares, volume: TeamVolume, kind: WeekKind, market: MarketMatch?): Prepared {
        val prepared = Prepared(d.player, d.team, model.project(d.ctx, d.rates, volume, shares), volume.passRate)
        val game = d.game ?: return prepared
        val (afterMatchup, final) = finalFor(state, prepared, game)
        val cv = K.EMPIRICAL_CV.getValue(d.player.position)
        when (kind) {
            WeekKind.PAST -> if (referencePoints(final) >= K.PAST_WEEK_MIN_POINTS) {
                emit(d.player.playerId, state.season, state.week, "final", final, cv)
            }
            WeekKind.UPCOMING -> {
                val withProps = market?.quotes(d.player.playerId)?.let { blend(final, it, d.player.position) }
                val shown = withProps?.components ?: final
                if (referencePoints(shown) >= K.UPCOMING_MIN_POINTS) {
                    emit(d.player.playerId, state.season, state.week, "baseline", prepared.baseline, cv)
                    emit(d.player.playerId, state.season, state.week, "final", shown, cv)
                    emitFactors(state, prepared, game, afterMatchup, final, withProps)
                    if (withProps != null) blended++
                }
                return Prepared(d.player, d.team, prepared.baseline, prepared.passRate, upcoming = shown)
            }
            WeekKind.REST -> Unit
        }
        return prepared
    }
```

(f) Replace `emitFactors` with:

```kotlin
    private fun emitFactors(
        state: WeekState,
        p: Prepared,
        game: Game,
        afterMatchup: Map<String, Double>,
        final: Map<String, Double>,
        withProps: Blended?,
    ) {
        val baselinePoints = referencePoints(p.baseline)
        val matchupPoints = referencePoints(afterMatchup)
        val id = p.player.playerId
        sink.factor(
            id, state.season, state.week, "matchup", logRatio(matchupPoints, baselinePoints),
            state.matchup.note(p.player.position, game.opponentOf(p.team)),
        )
        gameScript(game, p.team, state.leagueImplied, p.passRate)?.let { script ->
            sink.factor(id, state.season, state.week, "game_script", logRatio(referencePoints(final), matchupPoints), script.note)
        }
        withProps?.let {
            sink.factor(id, state.season, state.week, "market", logRatio(referencePoints(it.components), referencePoints(final)), it.note)
        }
    }
```

(g) In `addRest`, replace `val final = finalFor(state, p, game).second` with:

```kotlin
        // The upcoming week itself uses what was stored for it, props included.
        val final = p.upcoming?.takeIf { week == state.week && season == state.season } ?: finalFor(state, p, game).second
```

- [ ] **Step 6: Take props in `Forecast.run` and bump the version**

In `core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Forecast.kt`:

(a) Add a field to `ForecastReport`, after `rows`:

```kotlin
    public val rows: Long,
    /** How props went; null when none were given. */
    public val props: PropsOutcome? = null,
)
```

(b) Replace `run`'s signature, KDoc and body with:

```kotlin
    /**
     * Projects the built seasons into [conn]'s (empty) projection tables and
     * records the outcome in `schema_meta`. [props], when given, are blended
     * into the upcoming week (spec §5). [onWeek] is called before each week
     * and may throw to cancel.
     */
    public fun run(
        conn: SQLiteConnection,
        builtAt: Instant,
        copy: SeasonCopy? = null,
        props: PropsSnapshot? = null,
        onWeek: (season: Int, week: Int) -> Unit = { _, _ -> },
    ): ForecastReport {
        // ATTACH can't run inside a transaction, so copy first.
        copy?.let { copySeasons(conn, it) }
        val inputs = loadInputs(conn)
        conn.execSQL("BEGIN")
        val report = ProjectionWriter(conn).use { writer ->
            val outcome = Projector(inputs, copy?.seasons.orEmpty(), writer, props, onWeek).run()
            ForecastReport(outcome.status, outcome.upcoming, outcome.weeks, writer.rows, outcome.props)
        }
        writeMeta(conn, report.status, report.upcoming, builtAt)
        conn.execSQL("COMMIT")
        return report
    }
```

In `ForecastConstants.kt`, change `public const val FORECAST_VERSION: Int = 2` to `3`.

- [ ] **Step 7: Run the tests to see them pass**

Run: `./gradlew :core:forecast:test`
Expected: PASS, including `MarketTest` (7) and the 3 new `ForecastEngineTest` tests. `ForecastTimingTest` calls `Forecast.run(conn, Instant.now())` and needs no change.

If the note assertion fails, check the rounding: 1/1.5 = 0.6667; divided by 1.08 that's 0.6173, so λ = 0.9605 and 1 − e^−λ = 0.6173, which is "62%".

- [ ] **Step 8: Check the ingest still builds, and commit**

Run: `./gradlew :core:ingest:test`
Expected: PASS. `IngestPipeline` calls `Forecast.run(conn, now(), forecastCopy()) { … }`; the trailing lambda still binds to `onWeek`.

```bash
git add core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Market.kt \
        core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Projector.kt \
        core/forecast/src/main/kotlin/dev/gridiron/core/forecast/Forecast.kt \
        core/forecast/src/main/kotlin/dev/gridiron/core/forecast/ForecastConstants.kt \
        core/forecast/src/test/kotlin/dev/gridiron/core/forecast/MarketTest.kt \
        core/forecast/src/test/kotlin/dev/gridiron/core/forecast/ForecastEngineTest.kt
git commit -m "forecast: blend props into the upcoming week as a market factor"
```

---
### Task 3: The Odds API client and parser

**Files:**
- Modify: `core/data/build.gradle.kts`
- Create: `core/data/src/main/kotlin/dev/gridiron/core/data/live/Odds.kt`
- Create: `core/data/src/test/resources/odds/events.json`, `odds-e1.json`, `odds-e2.json`, `error.json`
- Test: `core/data/src/test/kotlin/dev/gridiron/core/data/live/OddsTest.kt`
- Test: `core/data/src/test/kotlin/dev/gridiron/core/data/live/UrlConnectionHttpClientTest.kt`

**Interfaces:**
- Consumes (Tasks 1–2): `PropQuote`, `PROP_MARKETS`, `ANYTIME_TD` from `:core:forecast`.
- Produces (public): `HttpResponse(code: Int, body: String, headers: Map<String, String> = emptyMap())` with `header(name)`, `fun interface HttpClient { suspend fun get(url: String): HttpResponse }`, `UrlConnectionHttpClient`.
- Produces (internal): `OddsEvent(id, commence: Instant, home, away)`, `NFL_TEAMS: Map<String, String>`, and `OddsApi` with `ODDS_CALL_COST`, `eventsUrl(key)`, `oddsUrl(key, eventId)`, `events(text)`, `quotes(text)` and `errorMessage(text)`.

The fixtures are hand-built from the documented v4 shape (https://the-odds-api.com/liveapi/guides/v4/): the event list, and an event's odds with `bookmakers → markets → outcomes { name, description, price, point }`. There's no key to record real responses with; see the ruling at the end.

- [ ] **Step 1: Let `:core:data` see the forecast's prop types**

In `core/data/build.gradle.kts`, add a line after `api(projects.core.projections)`:

```kotlin
    api(projects.core.forecast)
```

- [ ] **Step 2: Add the fixtures**

`core/data/src/test/resources/odds/events.json`:

```json
[
  {"id": "e4", "sport_key": "americanfootball_nfl", "sport_title": "NFL", "commence_time": "2026-09-27T17:00:00Z", "home_team": "Buffalo Bills", "away_team": "Miami Dolphins"},
  {"id": "e1", "sport_key": "americanfootball_nfl", "sport_title": "NFL", "commence_time": "2026-10-02T00:15:00Z", "home_team": "Kansas City Chiefs", "away_team": "Los Angeles Chargers"},
  {"id": "e2", "sport_key": "americanfootball_nfl", "sport_title": "NFL", "commence_time": "2026-10-04T17:00:00Z", "home_team": "Los Angeles Rams", "away_team": "San Francisco 49ers"},
  {"id": "e3", "sport_key": "americanfootball_nfl", "sport_title": "NFL", "commence_time": "2026-10-09T00:15:00Z", "home_team": "Washington Commanders", "away_team": "New York Giants"}
]
```

`core/data/src/test/resources/odds/odds-e1.json`:

```json
{"id": "e1", "sport_key": "americanfootball_nfl", "sport_title": "NFL", "commence_time": "2026-10-02T00:15:00Z", "home_team": "Kansas City Chiefs", "away_team": "Los Angeles Chargers", "bookmakers": []}
```

`core/data/src/test/resources/odds/odds-e2.json`:

```json
{
  "id": "e2", "sport_key": "americanfootball_nfl", "sport_title": "NFL", "commence_time": "2026-10-04T17:00:00Z",
  "home_team": "Los Angeles Rams", "away_team": "San Francisco 49ers",
  "bookmakers": [
    {"key": "draftkings", "title": "DraftKings", "markets": [
      {"key": "player_reception_yds", "last_update": "2026-10-02T15:00:00Z", "outcomes": [
        {"name": "Over", "description": "Puka Nacua", "price": 1.87, "point": 84.5},
        {"name": "Under", "description": "Puka Nacua", "price": 1.95, "point": 84.5}
      ]},
      {"key": "player_anytime_td", "last_update": "2026-10-02T15:00:00Z", "outcomes": [
        {"name": "Yes", "description": "Puka Nacua", "price": 2.3},
        {"name": "Yes", "description": "Christian McCaffrey", "price": 1.62}
      ]}
    ]},
    {"key": "fanduel", "title": "FanDuel", "markets": [
      {"key": "player_reception_yds", "last_update": "2026-10-02T15:05:00Z", "outcomes": [
        {"name": "Over", "description": "Puka Nacua", "price": 1.9, "point": 85.5},
        {"name": "Under", "description": "Puka Nacua", "price": 1.9, "point": 85.5}
      ]},
      {"key": "player_rush_attempts", "last_update": "2026-10-02T15:05:00Z", "outcomes": [
        {"name": "Over", "description": "Christian McCaffrey", "price": 1.9, "point": 17.5}
      ]}
    ]}
  ]
}
```

`core/data/src/test/resources/odds/error.json`:

```json
{"message": "API key is not valid"}
```

- [ ] **Step 3: Write the failing tests**

`core/data/src/test/kotlin/dev/gridiron/core/data/live/OddsTest.kt`:

```kotlin
package dev.gridiron.core.data.live

import dev.gridiron.core.forecast.ANYTIME_TD
import dev.gridiron.core.forecast.PropQuote
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.time.Instant

class OddsTest {
    private fun recorded(name: String): String = checkNotNull(javaClass.getResource("/odds/$name")) { name }.readText()

    @Test
    fun `the event list gives each game's id, kickoff and teams`() {
        val events = OddsApi.events(recorded("events.json"))

        assertEquals(listOf("e4", "e1", "e2", "e3"), events.map { it.id })
        assertEquals(OddsEvent("e1", Instant.parse("2026-10-02T00:15:00Z"), "Kansas City Chiefs", "Los Angeles Chargers"), events[1])
    }

    @Test
    fun `an event's odds become one quote per book, market, player and line`() {
        assertEquals(
            listOf(
                PropQuote("draftkings", "player_reception_yds", "Puka Nacua", 84.5, 1.87, 1.95),
                PropQuote("draftkings", ANYTIME_TD, "Puka Nacua", null, 2.3, null),
                PropQuote("draftkings", ANYTIME_TD, "Christian McCaffrey", null, 1.62, null),
                PropQuote("fanduel", "player_reception_yds", "Puka Nacua", 85.5, 1.9, 1.9),
            ),
            OddsApi.quotes(recorded("odds-e2.json")),
        )
    }

    @Test
    fun `a game with no props posted yet has no quotes`() {
        assertTrue(OddsApi.quotes(recorded("odds-e1.json")).isEmpty())
    }

    @Test
    fun `a response that isn't the expected shape is a format error`() {
        assertThrows<LiveFormatException> { OddsApi.events("{}") }
        assertThrows<LiveFormatException> { OddsApi.events("not json") }
        assertThrows<LiveFormatException> { OddsApi.events("""[{"id": 1}]""") }
        assertThrows<LiveFormatException> { OddsApi.quotes("[]") }
    }

    @Test
    fun `an error body's message is read, and anything else has none`() {
        assertEquals("API key is not valid", OddsApi.errorMessage(recorded("error.json")))
        assertNull(OddsApi.errorMessage("<html>Bad gateway</html>"))
        assertNull(OddsApi.errorMessage("[]"))
    }

    @Test
    fun `every URL goes to the Odds API, asks for the five markets, and encodes the key`() {
        assertEquals(
            "https://api.the-odds-api.com/v4/sports/americanfootball_nfl/events/e1/odds?apiKey=k+y%26z&regions=us" +
                "&markets=player_receptions,player_reception_yds,player_rush_yds,player_pass_yds,player_anytime_td" +
                "&oddsFormat=decimal&dateFormat=iso",
            OddsApi.oddsUrl("k y&z", "e1"),
        )
        assertEquals("api.the-odds-api.com", URI(OddsApi.eventsUrl("k")).host)
        assertEquals(5, OddsApi.ODDS_CALL_COST)
    }

    @Test
    fun `every team maps to its own nflverse abbreviation`() {
        assertEquals(32, NFL_TEAMS.size)
        assertEquals(32, NFL_TEAMS.values.toSet().size)
        assertEquals("LA", NFL_TEAMS["Los Angeles Rams"])
        assertEquals("LAC", NFL_TEAMS["Los Angeles Chargers"])
        assertEquals("WAS", NFL_TEAMS["Washington Commanders"])
    }
}
```

`core/data/src/test/kotlin/dev/gridiron/core/data/live/UrlConnectionHttpClientTest.kt`:

```kotlin
package dev.gridiron.core.data.live

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

class UrlConnectionHttpClientTest {
    private lateinit var server: HttpServer

    @BeforeEach
    fun start() {
        server = HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun respond(path: String, status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        server.createContext(path) { ex ->
            headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }

    @Test
    fun `a response keeps its status, body and headers`() = runTest {
        respond("/ok", 200, "[]", mapOf("X-Requests-Remaining" to "480"))

        val response = UrlConnectionHttpClient().get(url("/ok"))

        assertEquals(200, response.code)
        assertEquals("[]", response.body)
        assertEquals("480", response.header("x-requests-remaining"))
        assertEquals("480", response.header("X-Requests-Remaining"))
    }

    @Test
    fun `an error status is returned with its body, not thrown`() = runTest {
        respond("/denied", 401, """{"message": "API key is not valid"}""")

        val response = UrlConnectionHttpClient().get(url("/denied"))

        assertEquals(401, response.code)
        assertEquals("""{"message": "API key is not valid"}""", response.body)
    }

    @Test
    fun `no response at all is an IOException that doesn't repeat the URL`() = runTest {
        val dead = url("/events?apiKey=SECRET123")
        server.stop(0)

        val e = runCatching { UrlConnectionHttpClient(connectTimeoutMs = 2_000).get(dead) }.exceptionOrNull()

        assertEquals(IOException::class, e!!::class)
        assertEquals("couldn't reach 127.0.0.1", e.message)
        assertFalse("SECRET123" in e.toString())
    }
}
```

Note: `stop()` in `@AfterEach` runs `server.stop(0)` a second time for the last test. That is harmless: `HttpServer.stop` on a stopped server is a no-op.

- [ ] **Step 4: Run the tests to see them fail**

Run: `./gradlew :core:data:test --tests "dev.gridiron.core.data.live.OddsTest" --tests "dev.gridiron.core.data.live.UrlConnectionHttpClientTest"`
Expected: compilation fails: unresolved `OddsApi`, `OddsEvent`, `NFL_TEAMS`, `UrlConnectionHttpClient`.

- [ ] **Step 5: Write the client and the parser**

`core/data/src/main/kotlin/dev/gridiron/core/data/live/Odds.kt`:

```kotlin
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
```

The helpers are named `textAt`, `numberAt` and `listAt` so they can't be confused with `Espn.kt`'s private `string` and `array`.

- [ ] **Step 6: Run the tests to see them pass**

Run: `./gradlew :core:data:test --tests "dev.gridiron.core.data.live.OddsTest" --tests "dev.gridiron.core.data.live.UrlConnectionHttpClientTest"`
Expected: PASS, 7 + 3 tests.

- [ ] **Step 7: Run the module and commit**

Run: `./gradlew :core:data:test`
Expected: PASS.

```bash
git add core/data/build.gradle.kts core/data/src/main/kotlin/dev/gridiron/core/data/live/Odds.kt \
        core/data/src/test/resources/odds core/data/src/test/kotlin/dev/gridiron/core/data/live/OddsTest.kt \
        core/data/src/test/kotlin/dev/gridiron/core/data/live/UrlConnectionHttpClientTest.kt
git commit -m "live: an Odds API client and parser"
```

---

### Task 4: Props in live.db, fetched within the credit budget

**Files:**
- Modify: `core/data/src/main/kotlin/dev/gridiron/core/data/live/LiveDb.kt` (`LIVE_SCHEMA`)
- Create: `core/data/src/main/kotlin/dev/gridiron/core/data/live/PropsStore.kt`
- Create: `core/data/src/main/kotlin/dev/gridiron/core/data/live/PropsRepository.kt`
- Test: `core/data/src/test/kotlin/dev/gridiron/core/data/live/PropsRepositoryTest.kt`

**Interfaces:**
- Consumes (Task 3): `HttpClient`, `HttpResponse`, `OddsApi`, `OddsEvent`, `NFL_TEAMS`. Existing: `LiveDb.read/write`, `SQLiteConnection.meta/setMeta` (`LiveStore.kt`), `LiveFormatException`.
- Produces (public): `PropsStatus(creditsLeft: Int?, fetchedAt: Instant?, error: String?)`, `PropsRepository(db: LiveDb, http: HttpClient, clock: () -> Instant = Instant::now)` with `status: Flow<PropsStatus>`, `suspend fun refresh(key: String): String?` and `suspend fun snapshot(): PropsSnapshot?`.
- Produces (internal): `StoredEvent`, `upcomingWeek(events, now)`, `nextWednesday(t)`.

- [ ] **Step 1: Write the failing tests**

`core/data/src/test/kotlin/dev/gridiron/core/data/live/PropsRepositoryTest.kt`:

```kotlin
package dev.gridiron.core.data.live

import dev.gridiron.core.forecast.ANYTIME_TD
import dev.gridiron.core.forecast.PropEvent
import dev.gridiron.core.forecast.PropQuote
import dev.gridiron.core.forecast.PropsSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant

class PropsRepositoryTest {
    @TempDir
    lateinit var dir: File

    /** A Monday: e4 (Sunday) has kicked off; e1 (Thursday night) and e2 (Sunday) are this week; e3 is next week. */
    private var now = Instant.parse("2026-09-28T12:00:00Z")
    private val responses = mutableMapOf<String, () -> HttpResponse>()
    private val urls = mutableListOf<String>()
    private val http = HttpClient { url ->
        urls += url
        responses[url]?.invoke() ?: throw IOException("GET $url failed")
    }
    private val db by lazy { LiveDb(File(dir, "live.db")) }
    private val props by lazy { PropsRepository(db, http) { now } }

    @AfterEach
    fun close() {
        db.close()
    }

    private fun recorded(name: String): String = checkNotNull(javaClass.getResource("/odds/$name")) { name }.readText()

    private fun ok(body: String, left: Int? = null) =
        HttpResponse(200, body, left?.let { mapOf("x-requests-remaining" to "$it") }.orEmpty())

    private val events = OddsApi.eventsUrl(KEY)

    private fun odds(id: String) = OddsApi.oddsUrl(KEY, id)

    /** The events list, then each odds call costing 5 credits from [left]. */
    private fun serveWeek(left: Int = 480) {
        responses[events] = { ok(recorded("events.json"), left) }
        responses[odds("e1")] = { ok(recorded("odds-e1.json"), left - 5) }
        responses[odds("e2")] = { ok(recorded("odds-e2.json"), left - 10) }
    }

    private val e2Quotes = listOf(
        PropQuote("draftkings", ANYTIME_TD, "Christian McCaffrey", null, 1.62, null),
        PropQuote("draftkings", ANYTIME_TD, "Puka Nacua", null, 2.3, null),
        PropQuote("draftkings", "player_reception_yds", "Puka Nacua", 84.5, 1.87, 1.95),
        PropQuote("fanduel", "player_reception_yds", "Puka Nacua", 85.5, 1.9, 1.9),
    )

    @Test
    fun `this week's games are fetched, not ones that kicked off or next week's`() = runTest {
        serveWeek()

        assertNull(props.refresh(KEY))

        assertEquals(listOf(events, odds("e1"), odds("e2")), urls)
        // e1 has no props posted yet, so only e2 is in the snapshot, with nflverse's team codes.
        assertEquals(PropsSnapshot(listOf(PropEvent("LA", "SF", e2Quotes))), props.snapshot())
        assertEquals(PropsStatus(creditsLeft = 470, fetchedAt = now, error = null), props.status.first())
    }

    @Test
    fun `a game fetched in the last 24 hours isn't fetched again`() = runTest {
        serveWeek()
        props.refresh(KEY)
        urls.clear()

        now = now.plus(Duration.ofHours(23))
        assertNull(props.refresh(KEY))
        assertEquals(listOf(events), urls)

        now = now.plus(Duration.ofHours(2))
        props.refresh(KEY)
        assertEquals(listOf(events, events, odds("e1"), odds("e2")), urls)
    }

    @Test
    fun `a call that could overdraw the credits is skipped, and Settings says why`() = runTest {
        serveWeek(left = 7) // e1's answer leaves 2, less than one call's 5

        val error = props.refresh(KEY)

        assertEquals("out of Odds API credits (2 left)", error)
        assertEquals(listOf(events, odds("e1")), urls)
        assertEquals(PropsStatus(creditsLeft = 2, fetchedAt = now, error = error), props.status.first())
    }

    @Test
    fun `a refused key says so and keeps the props already fetched`() = runTest {
        serveWeek()
        props.refresh(KEY)
        val kept = props.snapshot()
        responses[events] = { HttpResponse(401, recorded("error.json")) }
        now = now.plus(Duration.ofHours(1))

        val error = props.refresh(KEY)

        assertEquals("the Odds API refused the key (API key is not valid)", error)
        assertNotNull(kept)
        assertEquals(kept, props.snapshot())
        assertEquals(error, props.status.first().error)
    }

    @Test
    fun `no connection or an error body is a reason, never a throw, and never shows the key`() = runTest {
        val offline = props.refresh(KEY)
        assertEquals("couldn't reach the Odds API", offline)
        assertNull(props.snapshot())

        responses[events] = { HttpResponse(500, """{"message": "no such key $KEY"}""") }
        val echoed = props.refresh(KEY)!!
        assertEquals("the Odds API answered HTTP 500 (no such key …)", echoed)
        assertFalse(KEY in echoed)
        assertFalse(KEY in props.status.first().error!!)
    }

    @Test
    fun `a game's props are kept through the game and dropped 12 hours after kickoff`() = runTest {
        serveWeek()
        props.refresh(KEY)
        responses[events] = { ok("[]") }
        val kickoff = Instant.parse("2026-10-04T17:00:00Z")

        now = kickoff.plus(Duration.ofHours(11))
        props.refresh(KEY)
        assertNotNull(props.snapshot())

        now = kickoff.plus(Duration.ofHours(13))
        props.refresh(KEY)
        assertNull(props.snapshot())
    }

    @Test
    fun `a game with a team the app doesn't know is left out`() = runTest {
        responses[events] = {
            ok("""[{"id": "x1", "commence_time": "2026-10-02T00:15:00Z", "home_team": "London Monarchs", "away_team": "Kansas City Chiefs"}]""")
        }

        assertNull(props.refresh(KEY))

        assertEquals(listOf(events), urls)
    }

    @Test
    fun `the week runs to the Wednesday after its first kickoff`() {
        assertEquals(Instant.parse("2026-10-07T00:00:00Z"), nextWednesday(Instant.parse("2026-10-02T00:15:00Z")))
        // A Wednesday game (Christmas) starts a week that runs to the next Wednesday.
        assertEquals(Instant.parse("2026-12-30T00:00:00Z"), nextWednesday(Instant.parse("2026-12-23T18:00:00Z")))
    }

    private companion object {
        const val KEY = "k3y-SECRET"
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew :core:data:test --tests "dev.gridiron.core.data.live.PropsRepositoryTest"`
Expected: compilation fails: unresolved `PropsRepository`, `PropsStatus`, `nextWednesday`.

- [ ] **Step 3: Add the two tables**

In `core/data/src/main/kotlin/dev/gridiron/core/data/live/LiveDb.kt`, add two entries at the end of the `LIVE_SCHEMA` list (after the `idx_injury_note_player` line):

```kotlin
    """CREATE TABLE IF NOT EXISTS prop_event (
        id TEXT PRIMARY KEY, commence INTEGER NOT NULL, home TEXT NOT NULL, away TEXT NOT NULL,
        fetched_at INTEGER) WITHOUT ROWID""",
    """CREATE TABLE IF NOT EXISTS prop_line (
        event_id TEXT NOT NULL, book TEXT NOT NULL, market TEXT NOT NULL, player TEXT NOT NULL,
        point REAL NOT NULL, over REAL, under REAL,
        PRIMARY KEY (event_id, book, market, player, point)) WITHOUT ROWID""",
```

`LIVE_VERSION` stays 1. Every open runs the `CREATE … IF NOT EXISTS` list, so an existing `live.db` gains the tables and keeps its news and injuries.

- [ ] **Step 4: Write the SQL**

`core/data/src/main/kotlin/dev/gridiron/core/data/live/PropsStore.kt`:

```kotlin
package dev.gridiron.core.data.live

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import dev.gridiron.core.forecast.PropEvent
import dev.gridiron.core.forecast.PropQuote
import dev.gridiron.core.forecast.PropsSnapshot
import java.time.Instant

/** A game of the upcoming week, with its teams as nflverse abbreviations. */
internal data class StoredEvent(val id: String, val commence: Instant, val home: String, val away: String)

/** An anytime TD has no line, and a key column can't be null: lines are never negative, so this stands for none. */
private const val NO_POINT = -1.0

private fun SQLiteStatement.bindNullableDouble(index: Int, value: Double?) {
    if (value == null) bindNull(index) else bindDouble(index, value)
}

private fun SQLiteStatement.nullableDouble(index: Int): Double? = if (isNull(index)) null else getDouble(index)

/** Adds or updates [events], keeping each one's fetch time. */
internal fun SQLiteConnection.saveEvents(events: List<StoredEvent>) {
    prepare(
        """INSERT INTO prop_event (id, commence, home, away) VALUES (?, ?, ?, ?)
           ON CONFLICT (id) DO UPDATE SET commence = excluded.commence, home = excluded.home, away = excluded.away""",
    ).use { st ->
        for (e in events) {
            st.reset()
            st.bindText(1, e.id)
            st.bindLong(2, e.commence.toEpochMilli())
            st.bindText(3, e.home)
            st.bindText(4, e.away)
            st.step()
        }
    }
}

/** When each stored game's props last arrived; null for never. */
internal fun SQLiteConnection.propFetchTimes(): Map<String, Instant?> =
    prepare("SELECT id, fetched_at FROM prop_event").use { st ->
        buildMap { while (st.step()) put(st.getText(0), if (st.isNull(1)) null else Instant.ofEpochMilli(st.getLong(1))) }
    }

/** Replaces one game's lines with [quotes] and marks it fetched at [at]. */
internal fun SQLiteConnection.saveLines(eventId: String, quotes: List<PropQuote>, at: Instant) {
    prepare("DELETE FROM prop_line WHERE event_id = ?").use {
        it.bindText(1, eventId)
        it.step()
    }
    prepare("INSERT OR REPLACE INTO prop_line (event_id, book, market, player, point, over, under) VALUES (?, ?, ?, ?, ?, ?, ?)").use { st ->
        for (q in quotes) {
            st.reset()
            st.clearBindings()
            st.bindText(1, eventId)
            st.bindText(2, q.book)
            st.bindText(3, q.market)
            st.bindText(4, q.player)
            st.bindDouble(5, q.point ?: NO_POINT)
            st.bindNullableDouble(6, q.over)
            st.bindNullableDouble(7, q.under)
            st.step()
        }
    }
    prepare("UPDATE prop_event SET fetched_at = ? WHERE id = ?").use {
        it.bindLong(1, at.toEpochMilli())
        it.bindText(2, eventId)
        it.step()
    }
}

/** Drops games that kicked off before [before], with their lines. */
internal fun SQLiteConnection.pruneProps(before: Instant) {
    for (sql in listOf(
        "DELETE FROM prop_line WHERE event_id IN (SELECT id FROM prop_event WHERE commence < ?)",
        "DELETE FROM prop_event WHERE commence < ?",
    )) {
        prepare(sql).use {
            it.bindLong(1, before.toEpochMilli())
            it.step()
        }
    }
}

/** Every stored game that has lines, in kickoff order, each with its lines by book, market, player and line. */
internal fun SQLiteConnection.propsSnapshot(): PropsSnapshot {
    val lines = prepare(
        "SELECT event_id, book, market, player, point, over, under FROM prop_line ORDER BY event_id, book, market, player, point",
    ).use { st ->
        buildList {
            while (st.step()) {
                val point = st.getDouble(4).takeIf { it >= 0.0 }
                add(st.getText(0) to PropQuote(st.getText(1), st.getText(2), st.getText(3), point, st.nullableDouble(5), st.nullableDouble(6)))
            }
        }
    }.groupBy({ it.first }, { it.second })
    val events = prepare("SELECT id, home, away FROM prop_event ORDER BY commence, id").use { st ->
        buildList { while (st.step()) add(Triple(st.getText(0), st.getText(1), st.getText(2))) }
    }
    return PropsSnapshot(events.mapNotNull { (id, home, away) -> lines[id]?.let { PropEvent(home, away, it) } })
}
```

- [ ] **Step 5: Write the repository**

`core/data/src/main/kotlin/dev/gridiron/core/data/live/PropsRepository.kt`:

```kotlin
package dev.gridiron.core.data.live

import androidx.sqlite.SQLiteConnection
import dev.gridiron.core.forecast.PropsSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/** What Settings shows about props (spec §5). */
public data class PropsStatus(
    /** The Odds API's `x-requests-remaining` after the last call; null before any. */
    val creditsLeft: Int?,
    /** When props last arrived for a game; null before any. */
    val fetchedAt: Instant?,
    /** Why the last refresh stopped short; null when it didn't. */
    val error: String?,
)

/**
 * 00:00 UTC on the first Wednesday after [t]. An NFL week runs from Thursday
 * night to Monday night, and Monday night ends on Tuesday in UTC.
 */
internal fun nextWednesday(t: Instant): Instant =
    t.atZone(ZoneOffset.UTC).toLocalDate()
        .with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY))
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()

/**
 * The upcoming week's games: those not yet kicked off at [now] that start
 * before the Wednesday after the first of them. Games with a team the app
 * doesn't know are left out.
 */
internal fun upcomingWeek(events: List<OddsEvent>, now: Instant): List<StoredEvent> {
    val ahead = events.filter { it.commence > now }
    val first = ahead.minOfOrNull { it.commence } ?: return emptyList()
    val cutoff = nextWednesday(first)
    return ahead.filter { it.commence < cutoff }.mapNotNull { e ->
        val home = NFL_TEAMS[e.home] ?: return@mapNotNull null
        val away = NFL_TEAMS[e.away] ?: return@mapNotNull null
        StoredEvent(e.id, e.commence, home, away)
    }
}

/**
 * The upcoming week's player props from The Odds API, kept in [db] beside
 * ESPN's news and injuries. Fetching is rationed (spec §5): the free events
 * list, then each of the week's games that hasn't kicked off and wasn't
 * fetched in the last 24 hours, and never a call that could overdraw the
 * credits `x-requests-remaining` reported.
 *
 * Nothing here throws but to cancel. A refresh that stops short returns why,
 * and Settings shows it. Whatever was fetched before stays. The key goes
 * only into Odds API URLs; it is replaced in every message.
 */
public class PropsRepository(
    private val db: LiveDb,
    private val http: HttpClient,
    private val clock: () -> Instant = Instant::now,
) {
    private val fetching = Mutex()
    private val changes = MutableStateFlow(0L)

    /** Credits left, when props last arrived, and the last refresh's error. Empty if live.db can't be read. */
    public val status: Flow<PropsStatus> = changes.map {
        readOr(PropsStatus(null, null, null)) { c ->
            PropsStatus(
                c.meta(CREDITS)?.toIntOrNull(),
                c.meta(FETCHED_AT)?.toLongOrNull()?.let(Instant::ofEpochMilli),
                c.meta(ERROR)?.takeIf { it.isNotEmpty() },
            )
        }
    }

    /** Fetches what's due with [key]. Returns why it stopped short, or null when it didn't. */
    public suspend fun refresh(key: String): String? = fetching.withLock {
        val error = try {
            fetch(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            describe(e)
        }?.replace(key, "…")
        try {
            db.write { it.setMeta(ERROR, error.orEmpty()) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // live.db can't be written: the error still goes back for the toast.
        }
        changes.value += 1
        error
    }

    /** Props for every stored game that has any, or null when none does. */
    public suspend fun snapshot(): PropsSnapshot? =
        readOr(null) { c -> c.propsSnapshot().takeIf { it.events.isNotEmpty() } }

    private suspend fun fetch(key: String): String? {
        val now = clock()
        val listing = call(OddsApi.eventsUrl(key))
        problem(listing)?.let { return it }
        val week = upcomingWeek(OddsApi.events(listing.body), now)
        db.write { c ->
            c.pruneProps(now.minus(PRUNE_AFTER))
            c.saveEvents(week)
        }
        val fetched = db.read { it.propFetchTimes() }
        for (event in week.sortedBy { it.commence }) {
            val last = fetched[event.id]
            if (last != null && Duration.between(last, now) < REFETCH_AFTER) continue
            val left = db.read { it.meta(CREDITS)?.toIntOrNull() }
            if (left != null && left < OddsApi.ODDS_CALL_COST) return "out of Odds API credits ($left left)"
            val response = call(OddsApi.oddsUrl(key, event.id))
            if (response.code == HTTP_NOT_FOUND) continue // taken off the board
            problem(response)?.let { return it }
            val quotes = OddsApi.quotes(response.body)
            db.write { c ->
                c.saveLines(event.id, quotes, now)
                c.setMeta(FETCHED_AT, now.toEpochMilli().toString())
            }
        }
        return null
    }

    /** One GET, recording the credits it reports. */
    private suspend fun call(url: String): HttpResponse {
        val response = http.get(url)
        response.header("x-requests-remaining")?.trim()?.toDoubleOrNull()?.let { left ->
            db.write { it.setMeta(CREDITS, left.toInt().toString()) }
        }
        return response
    }

    private fun problem(r: HttpResponse): String? {
        if (r.code == HTTP_OK) return null
        val detail = OddsApi.errorMessage(r.body)?.let { " ($it)" }.orEmpty()
        return when (r.code) {
            HTTP_UNAUTHORIZED -> "the Odds API refused the key$detail"
            HTTP_TOO_MANY -> "the Odds API is limiting requests$detail"
            else -> "the Odds API answered HTTP ${r.code}$detail"
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is LiveFormatException -> e.message ?: "the Odds API changed its format"
        is IOException -> "couldn't reach the Odds API"
        else -> "couldn't save props (${e::class.simpleName})"
    }

    private suspend fun <T> readOr(fallback: T, block: (SQLiteConnection) -> T): T =
        try {
            db.read(block)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fallback
        }

    private companion object {
        val REFETCH_AFTER: Duration = Duration.ofHours(24)

        /** Spec §5, "pruned after the game": half a day after kickoff, when every game is over. */
        val PRUNE_AFTER: Duration = Duration.ofHours(12)

        const val CREDITS = "props_credits_left"
        const val FETCHED_AT = "props_fetched_at"
        const val ERROR = "props_error"
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val HTTP_TOO_MANY = 429
    }
}
```

- [ ] **Step 6: Run the tests to see them pass**

Run: `./gradlew :core:data:test --tests "dev.gridiron.core.data.live.PropsRepositoryTest"`
Expected: PASS, 8 tests.

- [ ] **Step 7: Run the module and commit**

Run: `./gradlew :core:data:test --tests "dev.gridiron.core.data.live.*"`
Expected: PASS. `LiveStoreTest` and `LiveRepositoryTest` are unchanged by the two new tables.

```bash
git add core/data/src/main/kotlin/dev/gridiron/core/data/live/LiveDb.kt \
        core/data/src/main/kotlin/dev/gridiron/core/data/live/PropsStore.kt \
        core/data/src/main/kotlin/dev/gridiron/core/data/live/PropsRepository.kt \
        core/data/src/test/kotlin/dev/gridiron/core/data/live/PropsRepositoryTest.kt
git commit -m "live: props in live.db, fetched within the Odds API credit budget"
```

---
### Task 5: The key in Settings

**Files:**
- Modify: `core/datastore/src/main/kotlin/dev/gridiron/core/datastore/UserPrefs.kt`
- Modify: `core/datastore/src/main/kotlin/dev/gridiron/core/datastore/UserPrefsJson.kt`
- Modify: `core/data/src/main/kotlin/dev/gridiron/core/data/SettingsRepository.kt`
- Modify: `app/src/main/kotlin/dev/gridiron/app/SettingsScreen.kt`
- Test: `core/datastore/src/test/kotlin/dev/gridiron/core/datastore/UserPrefsStoreTest.kt`
- Test: `core/data/src/test/kotlin/dev/gridiron/core/data/SettingsRepositoryTest.kt`
- Test: `app/src/test/kotlin/dev/gridiron/app/SettingsScreenTest.kt`

**Interfaces:**
- Consumes (Task 4): `PropsStatus`. Existing: `formatWhen(instant, zone, locale)` (`LiveFormat.kt`).
- Produces: `UserPrefs.oddsApiKey: String?`, `SettingsRepository.oddsApiKey: Flow<String?>`, `suspend fun SettingsRepository.setOddsApiKey(key: String)`, and `SettingsScreen(settings, onBack, props: Flow<PropsStatus>? = null)`. Internal: `propsStatusText(status, zone, locale)`.

- [ ] **Step 1: Write the failing tests**

(a) In `core/datastore/src/test/kotlin/dev/gridiron/core/datastore/UserPrefsStoreTest.kt`, add at the end of the class (add `import org.junit.jupiter.api.Assertions.assertNull` if it isn't imported):

```kotlin
    @Test
    fun `the Odds API key survives a restart`() {
        withStore { it.update { p -> p.copy(oddsApiKey = "abc123") } }
        assertEquals("abc123", withStore { it.prefs.first().oddsApiKey })
    }

    @Test
    fun `a file from before the key existed reads as no key`() {
        file.writeText("""{"formatVersion": 1}""")
        assertNull(withStore { it.prefs.first().oddsApiKey })
    }
```

(b) In `core/data/src/test/kotlin/dev/gridiron/core/data/SettingsRepositoryTest.kt`, add at the end of the class (add `import org.junit.jupiter.api.Assertions.assertNull` if it isn't imported):

```kotlin
    @Test
    fun `the Odds API key is saved trimmed, and a blank one removes it`() = runTest {
        val (settings, prefs) = repo()
        assertNull(settings.oddsApiKey.first())

        settings.setOddsApiKey("  abc123 \n")
        assertEquals("abc123", settings.oddsApiKey.first())
        assertEquals("abc123", prefs.current.oddsApiKey)

        settings.setOddsApiKey("   ")
        assertNull(settings.oddsApiKey.first())
    }
```

(c) In `app/src/test/kotlin/dev/gridiron/app/SettingsScreenTest.kt`, add at the end of the class:

```kotlin
    @Test
    fun savingAnOddsApiKeyStoresIt() {
        val prefs = FakePrefsSource()
        show(prefs)

        compose.onNodeWithTag("oddsKey").performTextInput("abc123")
        compose.onNodeWithTag("saveOddsKey").performClick()
        compose.waitForIdle()

        assertEquals("abc123", prefs.current.oddsApiKey)
    }

    @Test
    fun propsStatusShowsUnderTheKey() {
        compose.setContent {
            GridironTheme {
                SettingsScreen(SettingsRepository(FakePrefsSource()) { 2026 }, onBack = {}, props = flowOf(PropsStatus(412, null, null)))
            }
        }
        compose.onNodeWithText("412 Odds API credits left.").assertExists()
    }

    @Test
    fun propsStatusSaysCreditsWhenAndWhy() {
        val at = Instant.parse("2026-09-28T19:10:00Z")
        assertEquals(
            "412 Odds API credits left. Props fetched Sep 28, 7:10 PM.",
            propsStatusText(PropsStatus(412, at, null), ZoneOffset.UTC, Locale.US),
        )
        assertEquals(
            "2 Odds API credits left. Last refresh: out of Odds API credits (2 left).",
            propsStatusText(PropsStatus(2, null, "out of Odds API credits (2 left)"), ZoneOffset.UTC, Locale.US),
        )
        assertEquals("Props are fetched on the next refresh.", propsStatusText(PropsStatus(null, null, null), ZoneOffset.UTC, Locale.US))
    }
```

Add these imports to `SettingsScreenTest.kt`:

```kotlin
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import dev.gridiron.core.data.live.PropsStatus
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew :core:datastore:test :core:data:test --tests "dev.gridiron.core.data.SettingsRepositoryTest"`
Expected: compilation fails: unresolved `oddsApiKey`, `setOddsApiKey`.

- [ ] **Step 3: Store the key**

In `core/datastore/src/main/kotlin/dev/gridiron/core/datastore/UserPrefs.kt`, add the property and its KDoc line:

```kotlin
 * @property seasons The seasons to build; null means the default (the current season and the two before it).
 * @property oddsApiKey The user's key for The Odds API (spec §5); null when none is set. Sent only to api.the-odds-api.com.
 */
public data class UserPrefs(
    val profiles: List<ScoringProfile>,
    val activeProfileId: String,
    val tray: List<CompareSlot>,
    val resetNotice: Boolean = false,
    val seasons: SeasonChoice? = null,
    val oddsApiKey: String? = null,
) {
```

In `core/datastore/src/main/kotlin/dev/gridiron/core/datastore/UserPrefsJson.kt`:

(a) Add a field at the end of `UserPrefsDto`:

```kotlin
    val seasonsChosenIn: Int? = null,
    val oddsApiKey: String? = null,
)
```

(b) In `toDomain()`, replace the `return UserPrefs(...)` line with:

```kotlin
    return UserPrefs(
        profiles,
        activeProfileId ?: UserPrefs.DEFAULT.activeProfileId,
        tray,
        resetNotice,
        choice,
        oddsApiKey?.takeIf { it.isNotBlank() },
    )
```

(c) In `toDto()`, add after `seasonsChosenIn = seasons?.chosenIn,`:

```kotlin
    oddsApiKey = oddsApiKey,
```

In `core/data/src/main/kotlin/dev/gridiron/core/data/SettingsRepository.kt`, add after `setSelected`:

```kotlin
    /** The user's Odds API key; null when none is set. */
    public val oddsApiKey: Flow<String?> = prefs.prefs.map { it.oddsApiKey }.distinctUntilChanged()

    /** Saves [key], trimmed; a blank one removes the key. */
    public suspend fun setOddsApiKey(key: String) {
        val clean = key.trim().takeIf { it.isNotEmpty() }
        prefs.update { it.copy(oddsApiKey = clean) }
    }
```

Also update the class KDoc's first sentence from "Which seasons the phone builds." to "Which seasons the phone builds, and the Odds API key."

- [ ] **Step 4: Run the JVM tests to see them pass**

Run: `./gradlew :core:datastore:test :core:data:test --tests "dev.gridiron.core.data.SettingsRepositoryTest"`
Expected: PASS.

- [ ] **Step 5: Add the props section to Settings**

In `app/src/main/kotlin/dev/gridiron/app/SettingsScreen.kt`:

(a) Change the signature and KDoc, and call the new section right after the title `Row`:

```kotlin
/**
 * The Odds API key and how props went, then the seasons the phone builds,
 * newest first. Changes apply on the next refresh.
 */
@Composable
fun SettingsScreen(settings: SettingsRepository, onBack: () -> Unit, props: Flow<PropsStatus>? = null) {
```

```kotlin
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            PropsSection(settings, props)
            Text(
                "Seasons",
```

(b) Add below `SettingsScreen`:

```kotlin
/** The Odds API key, and how the last props fetch went. */
@Composable
private fun PropsSection(settings: SettingsRepository, props: Flow<PropsStatus>?) {
    val saved by settings.oddsApiKey.collectAsState(initial = null)
    var draft by remember(saved) { mutableStateOf(saved.orEmpty()) }
    val scope = rememberCoroutineScope()
    Text(
        "Betting props",
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )
    Text(
        "With a free key from the-odds-api.com, each refresh blends the coming week's player props into the projections. " +
            "The key is sent only to The Odds API.",
        Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f).testTag("oddsKey"),
            label = { Text("Odds API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        TextButton(onClick = { scope.launch { settings.setOddsApiKey(draft) } }, modifier = Modifier.testTag("saveOddsKey")) { Text("Save") }
    }
    val status = props?.collectAsState(initial = null)?.value
    if (status != null) {
        Text(
            propsStatusText(status),
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One line on props, e.g. "412 Odds API credits left. Props fetched Sep 28, 3:10 PM." */
internal fun propsStatusText(status: PropsStatus, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    listOfNotNull(
        status.creditsLeft?.let { "$it Odds API credits left." },
        status.fetchedAt?.let { "Props fetched ${formatWhen(it, zone, locale)}." },
        status.error?.let { "Last refresh: $it." },
    ).joinToString(" ").ifEmpty { "Props are fetched on the next refresh." }
```

(c) Add these imports (keep the file's order: `androidx`, `dev.gridiron`, `kotlinx`, then `java`):

```kotlin
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.gridiron.core.data.live.PropsStatus
import kotlinx.coroutines.flow.Flow
import java.time.ZoneId
import java.util.Locale
```

- [ ] **Step 6: Run the tests to see them pass, and commit**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.gridiron.app.SettingsScreenTest"`
Expected: PASS: the 3 existing tests (the seasons list is still on screen above the fold) and the 3 new ones.

```bash
git add core/datastore/src/main/kotlin/dev/gridiron/core/datastore/UserPrefs.kt \
        core/datastore/src/main/kotlin/dev/gridiron/core/datastore/UserPrefsJson.kt \
        core/datastore/src/test/kotlin/dev/gridiron/core/datastore/UserPrefsStoreTest.kt \
        core/data/src/main/kotlin/dev/gridiron/core/data/SettingsRepository.kt \
        core/data/src/test/kotlin/dev/gridiron/core/data/SettingsRepositoryTest.kt \
        app/src/main/kotlin/dev/gridiron/app/SettingsScreen.kt \
        app/src/test/kotlin/dev/gridiron/app/SettingsScreenTest.kt
git commit -m "settings: an Odds API key, with credits left and the last props error"
```

---

### Task 6: Props flow from Refresh into the forecast; docs

**Files:**
- Modify: `core/ingest/build.gradle.kts`
- Modify: `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/IngestPipeline.kt`
- Modify: `app/src/main/kotlin/dev/gridiron/app/RefreshCoordinator.kt`
- Modify: `app/src/main/kotlin/dev/gridiron/app/RefreshText.kt`
- Modify: `app/src/main/kotlin/dev/gridiron/app/GridironApplication.kt`
- Modify: `app/src/main/kotlin/dev/gridiron/app/GridironNavHost.kt`
- Modify: `CLAUDE.md`
- Test: `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/IngestPipelineTest.kt`
- Test: `app/src/test/kotlin/dev/gridiron/app/RefreshCoordinatorTest.kt`
- Test: `app/src/test/kotlin/dev/gridiron/app/RefreshTextTest.kt`

**Interfaces:**
- Consumes: `Forecast.run(…, props)` and `ForecastReport.props` (Task 2); `PropsRepository`, `UrlConnectionHttpClient` (Tasks 3–4); `SettingsRepository.oddsApiKey`, `SettingsScreen(…, props)` (Task 5).
- Produces:
  - `IngestPipeline.build(seasons, previous, out, props: PropsSnapshot? = null, onProgress = {})` and `IngestReport.props: PropsOutcome?`.
  - In `:app`: `PropsFetch(snapshot: PropsSnapshot?, error: String?)`, `StatsBuilder.build(seasons, previous, out, props, onProgress)`, `RefreshCoordinator(…, props: (suspend () -> PropsFetch)? = null, …)`, `summary(report, elapsedMs, propsError: String? = null)`, and `Deps.props: PropsRepository?`.

- [ ] **Step 1: Write the failing tests**

(a) In `core/ingest/src/test/kotlin/dev/gridiron/core/ingest/IngestPipelineTest.kt`, add after `a build projects its seasons and records how` (and add the imports `dev.gridiron.core.forecast.PropEvent`, `dev.gridiron.core.forecast.PropQuote`, `dev.gridiron.core.forecast.PropsOutcome`, `dev.gridiron.core.forecast.PropsSnapshot`, and `org.junit.jupiter.api.Assertions.assertNull` if missing):

```kotlin
    @Test
    fun `props go to the forecast, and the report says how they went`() = runTest {
        servePlayers()
        serveSeason(2024)
        serveSeason(2025)
        val props = PropsSnapshot(listOf(PropEvent("AAA", "BBB", listOf(PropQuote("dk", "player_receptions", "Nobody Known", 4.5, 1.9, 1.9)))))

        val with = pipeline.build(listOf(2024, 2025), previous = null, out = File(dir, "a.db"), props = props)
        val without = pipeline.build(listOf(2024, 2025), previous = null, out = File(dir, "b.db"))

        // Both seasons are played, so there's no upcoming week to blend: the one name is unmatched.
        assertEquals(PropsOutcome(blended = 0, unmatched = 1), with.props)
        assertNull(without.props)
    }
```

(b) In `app/src/test/kotlin/dev/gridiron/app/RefreshCoordinatorTest.kt`, replace the `builds` field and the `coordinator` helper with the following. The `StatsBuilder` lambda gains the snapshot parameter.

```kotlin
    /** The `previous` file and the props snapshot each build was given. */
    private val builds = mutableListOf<File?>()
    private val snapshots = mutableListOf<PropsSnapshot?>()

    private fun TestScope.coordinator(
        live: (suspend () -> LiveResult)? = null,
        props: (suspend () -> PropsFetch)? = null,
        build: suspend (out: File, onProgress: (IngestProgress) -> Unit) -> IngestReport = { out, _ ->
            out.writeText("new")
            report
        },
    ) = RefreshCoordinator(
        dir = tmp.root,
        executor = executor,
        stats = StatsBuilder { _, previous, out, snapshot, onProgress ->
            builds += previous
            snapshots += snapshot
            build(out, onProgress)
        },
        seasons = { listOf(2025, 2026) },
        scope = this,
        live = live,
        props = props,
        millis = { 0L },
    )
```

Add these tests at the end of the class, and the imports `dev.gridiron.core.forecast.PropsSnapshot` and `java.io.IOException`:

```kotlin
    @Test
    fun propsAreFetchedBeforeTheBuildAndHandedToIt() = runTest {
        val order = mutableListOf<String>()
        val snapshot = PropsSnapshot(emptyList())
        val refresher = coordinator(
            props = {
                order += "props"
                PropsFetch(snapshot, null)
            },
            build = { out, _ ->
                order += "build"
                out.writeText("new")
                report
            },
        )

        refresher.refresh()
        advanceUntilIdle()

        assertEquals(listOf("props", "build"), order)
        assertEquals(listOf<PropsSnapshot?>(snapshot), snapshots)
        assertEquals(RefreshState.Finished("Stats updated for 2025, 2026 in 0 s.", ok = true), refresher.state.value)
    }

    @Test
    fun aPropsFailureStillBuildsAndTheToastSaysWhy() = runTest {
        val refresher = coordinator(props = { throw IOException("offline") })

        refresher.refresh()
        advanceUntilIdle()

        assertEquals(listOf<PropsSnapshot?>(null), snapshots)
        assertEquals("new", db.readText())
        assertEquals(
            RefreshState.Finished("Stats updated for 2025, 2026 in 0 s. Props not updated: offline.", ok = true),
            refresher.state.value,
        )
    }
```

(c) In `app/src/test/kotlin/dev/gridiron/app/RefreshTextTest.kt`, add at the end of the class (and the import `dev.gridiron.core.forecast.PropsOutcome`):

```kotlin
    @Test
    fun `the summary says how props went`() {
        val ok = IngestReport(listOf(2026), emptyList(), emptyMap(), emptyList(), 1L)

        assertEquals(
            "Stats updated for 2026 in 5 s. Props moved 38 projections; 5 names didn't match a player.",
            summary(ok.copy(props = PropsOutcome(38, 5)), 5_000),
        )
        assertEquals("Stats updated for 2026 in 5 s. Props moved 1 projection.", summary(ok.copy(props = PropsOutcome(1, 0)), 5_000))
        assertEquals(
            "Stats updated for 2026 in 5 s. Props moved 0 projections; 1 name didn't match a player.",
            summary(ok.copy(props = PropsOutcome(0, 1)), 5_000),
        )
        // Props given but empty (the off-season): nothing to say.
        assertEquals("Stats updated for 2026 in 5 s.", summary(ok.copy(props = PropsOutcome(0, 0)), 5_000))
        assertEquals(
            "Stats updated for 2026 in 5 s. Props not updated: out of Odds API credits (2 left).",
            summary(ok, 5_000, propsError = "out of Odds API credits (2 left)"),
        )
    }
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew :core:ingest:test --tests "dev.gridiron.core.ingest.IngestPipelineTest"`
Expected: compilation fails: `build` has no `props` parameter, and `IngestReport` has no `props`.

- [ ] **Step 3: Pass props through the pipeline**

In `core/ingest/build.gradle.kts`, change `implementation(projects.core.forecast)` to:

```kotlin
    api(projects.core.forecast)
```

(`build` and `IngestReport` now expose `PropsSnapshot` and `PropsOutcome`.)

In `core/ingest/src/main/kotlin/dev/gridiron/core/ingest/IngestPipeline.kt`:

(a) Add the imports `dev.gridiron.core.forecast.PropsOutcome` and `dev.gridiron.core.forecast.PropsSnapshot`.

(b) Add a field at the end of `IngestReport`, after `forecast`:

```kotlin
    public val forecast: String = FORECAST_OK,
    /** How props went in the forecast; null when none were given, or the forecast failed. */
    public val props: PropsOutcome? = null,
) {
```

(c) Replace `build`'s signature and its `Run(...)` call:

```kotlin
    public suspend fun build(
        seasons: List<Int>,
        previous: File?,
        out: File,
        props: PropsSnapshot? = null,
        onProgress: (IngestProgress) -> Unit = {},
    ): IngestReport = withContext(Dispatchers.IO) {
```

```kotlin
            Run(prior, previous, props, coroutineContext.job, onProgress).build(seasons.distinct().sorted(), out)
```

Also add a sentence to `build`'s KDoc: "[props], when given, are blended into the upcoming week's projections."

(d) Add the `props` parameter to `Run`:

```kotlin
    private inner class Run(
        private val prior: Map<String, String>?,
        private val previous: File?,
        private val props: PropsSnapshot?,
        private val job: Job,
        private val onProgress: (IngestProgress) -> Unit,
    ) {
```

(e) In `Run.build`, replace the last two lines of the `use` block:

```kotlin
                val (forecast, propsOutcome) = forecast(writer)
                IngestReport(built.sorted(), reused.sorted(), skipped.toMap(), warnings.toList(), writer.factCount(), forecast, propsOutcome)
```

(f) Replace `forecast(writer)`:

```kotlin
        /** Projections for the new database, and how props went. A failure leaves none and says why; it never fails the build. */
        private fun forecast(writer: StatsDbWriter): Pair<String, PropsOutcome?> = try {
            val report = Forecast.run(writer.connection, now(), forecastCopy(), props) { season, week ->
                job.ensureActive()
                onProgress(IngestProgress.Projecting(season, week))
            }
            report.status to report.props
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: e::class.simpleName ?: "unknown error"
            Forecast.fail(writer.connection, now(), reason)
            "failed: $reason" to null
        }
```

Run: `./gradlew :core:ingest:test`
Expected: PASS, including the new test. The CLI (`cli/IngestCli.kt`) passes `onProgress` as a trailing lambda, so it needs no change.

- [ ] **Step 4: Fetch props in the refresh**

In `app/src/main/kotlin/dev/gridiron/app/RefreshCoordinator.kt`:

(a) Add the import `dev.gridiron.core.forecast.PropsSnapshot`. Replace `StatsBuilder` and add `PropsFetch` above it:

```kotlin
/** What fetching props gave a refresh: the snapshot to blend (null for none) and, when the fetch fell short, why. */
data class PropsFetch(val snapshot: PropsSnapshot?, val error: String?)

/** `IngestPipeline.build`, as a seam the tests can fake. */
fun interface StatsBuilder {
    suspend fun build(
        seasons: List<Int>,
        previous: File?,
        out: File,
        props: PropsSnapshot?,
        onProgress: (IngestProgress) -> Unit,
    ): IngestReport
}
```

(b) Add a constructor parameter after `live`:

```kotlin
    private val live: (suspend () -> LiveResult)? = null,
    /** Fetches the upcoming week's props before the build; null when the app has no props at all (tests). */
    private val props: (suspend () -> PropsFetch)? = null,
```

Also update the class KDoc: "Builds stats on the phone, blending in betting props when the user has an Odds API key, and swaps them in without restarting the app, then refreshes ESPN's injuries and news."

(c) In `run()`, replace the `statsLine` assignment's `try` body:

```kotlin
        val statsLine = try {
            val fetched = fetchProps()
            summary(buildAndSwap(fetched?.snapshot), millis() - start, fetched?.error)
        } catch (e: CancellationException) {
```

(d) Add `fetchProps` above `buildAndSwap`, and give `buildAndSwap` the snapshot:

```kotlin
    /** Props for the build. A failure is reported in the toast, never fatal (spec §5: the model's number stands). */
    private suspend fun fetchProps(): PropsFetch? {
        val fetch = props ?: return null
        _state.value = RefreshState.Running("Fetching betting props…")
        return try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PropsFetch(null, e.message ?: e::class.simpleName ?: "unknown error")
        }
    }

    private suspend fun buildAndSwap(snapshot: PropsSnapshot?): IngestReport {
        val report = try {
            stats.build(seasons(), db.takeIf { it.isFile }, next, snapshot) { _state.value = RefreshState.Running(progressText(it)) }
```

In `app/src/main/kotlin/dev/gridiron/app/RefreshText.kt`, replace `summary`:

```kotlin
/**
 * "Stats updated for 2024, 2025, 2026 in 1 min 5 s." plus any skipped
 * seasons, missing projections, and how props went.
 */
internal fun summary(report: IngestReport, elapsedMs: Long, propsError: String? = null): String = buildString {
    append("Stats updated for ").append((report.built + report.reused).sorted().joinToString(", "))
    append(" in ").append(formatDuration(elapsedMs)).append('.')
    for ((season, why) in report.skipped.toSortedMap()) append(' ').append(season).append(" skipped: ").append(why).append('.')
    if (!report.projectionsOk) append(" Projections unavailable: ").append(report.forecast).append('.')
    report.props?.takeIf { it.blended > 0 || it.unmatched > 0 }?.let { p ->
        append(" Props moved ").append(p.blended).append(if (p.blended == 1) " projection" else " projections")
        if (p.unmatched > 0) {
            append("; ").append(p.unmatched).append(if (p.unmatched == 1) " name didn't" else " names didn't").append(" match a player")
        }
        append('.')
    }
    propsError?.let { append(" Props not updated: ").append(it).append('.') }
}
```

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :app:testDebugUnitTest --tests "dev.gridiron.app.RefreshCoordinatorTest" --tests "dev.gridiron.app.RefreshTextTest"`
Expected: compilation fails in `GridironApplication.kt` (the `stats` lambda now takes 5 parameters). Step 5 fixes it.

- [ ] **Step 5: Wire the app**

In `app/src/main/kotlin/dev/gridiron/app/GridironApplication.kt`:

(a) Add the imports `dev.gridiron.core.data.live.PropsRepository` and `dev.gridiron.core.data.live.UrlConnectionHttpClient`.

(b) Share one `LiveDb` between the two live repositories. Replace the `live` line with:

```kotlin
    // One connection to live.db, shared by ESPN's feeds and the props.
    private val liveDb by lazy { LiveDb(File(noBackupFilesDir, "live.db")) }
    private val live by lazy { LiveRepository(liveDb, UrlConnectionHttpGet(), players) }
    private val propsRepo by lazy { PropsRepository(liveDb, UrlConnectionHttpClient()) }
```

(c) In `refresher`, replace the `stats` lambda and add `props` after `live`:

```kotlin
            stats = { seasons, previous, out, props, onProgress ->
                IngestPipeline(HttpFetcher(), File(noBackupFilesDir, "ingest-work"), File(noBackupFilesDir, "players.csv.gz"))
                    .build(seasons, previous, out, props, onProgress)
            },
```

```kotlin
            live = { live.refresh() },
            props = {
                val key = settings.oddsApiKey.first()
                if (key == null) {
                    PropsFetch(null, null)
                } else {
                    val error = propsRepo.refresh(key)
                    PropsFetch(propsRepo.snapshot(), error)
                }
            },
```

(d) In `deps`, add `props = propsRepo,` after `refresher = refresher,`.

Also update the class KDoc's last sentence: "ESPN's injuries and news, and the Odds API's props, live beside it in `live.db`."

In `app/src/main/kotlin/dev/gridiron/app/GridironNavHost.kt`:

(a) Add a field at the end of `Deps`, and the import `dev.gridiron.core.data.live.PropsRepository`:

```kotlin
    /** Builds stats on the phone. Null in tests, which read a prebuilt database. */
    val refresher: Refresher? = null,
    /** Odds API props, for Settings. Null in tests, like [live]. */
    val props: PropsRepository? = null,
)
```

(b) Pass the status to both `SettingsScreen` calls:

```kotlin
        SettingsScreen(settings, onBack = { settingsOpen = false }, props = deps.props?.status)
```

```kotlin
                    entry<SettingsKey> { deps.settings?.let { SettingsScreen(it, onBack = back, props = deps.props?.status) } }
```

- [ ] **Step 6: Run the tests to see them pass**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :app:testDebugUnitTest`
Expected: PASS, including the 2 new `RefreshCoordinatorTest` tests and the new `RefreshTextTest` test.

- [ ] **Step 7: Update the docs**

In `CLAUDE.md`, make four replacements:

(a) In the `:core:forecast` line, replace `game script from nflverse's lines, distributions. Every constant` with:

```
game script from nflverse's lines, distributions; then, for the upcoming week only, an inverse-variance blend with betting props when the user has an Odds API key (a `market` factor in the waterfall). Every constant
```

(b) In the `:core:data` line, replace `the writable \`live.db\` store, and \`LiveRepository\`` with:

```
the writable `live.db` store, `LiveRepository`, and `PropsRepository` (The Odds API: the upcoming week's player props, fetched within the credit budget)
```

(c) Replace Data Flow item 2 with:

```
2. **Forecast** — before the build, if Settings has an Odds API key, the upcoming week's player props are fetched into `live.db` (games not yet kicked off and not fetched in 24 hours, never overdrawing the credits). The build then projects every regular-season week of the chosen seasons into the projection tables (the upcoming week with both stages and factors, blended with props when there are any; past weeks' final stage for the backtest; rest of season summed). The refresh toast says if projections are unavailable, how many projections props moved, and why props weren't updated
```

(d) Replace the `**\`live.db\`**` paragraph's table list `\`live_meta\` (fetch times)` with:

```
`prop_event` and `prop_line` (the upcoming week's player props by game, book, market, player and line; pruned 12 hours after kickoff), `live_meta` (fetch times, Odds API credits left, the last props error)
```

(e) Replace the Known Gaps line `- **Projection model sub-projects 3–4 are not built yet**: Odds API props and K/DST. See …` with:

```
- **Projection model sub-project 4 (K/DST) is not built yet.** See `docs/superpowers/specs/2026-09-26-projection-model-design.md`.
- **Props can't be backtested**, because there are no historical props. The blend's weight (`MARKET_VARIANCE_RATIO`) and the one-sided anytime-TD margin (`ONE_SIDED_OVERROUND`) are judgments, not fits, and the accuracy page and CI gate measure the model alone.
```

- [ ] **Step 8: Run everything and commit**

Run: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew test`
Expected: BUILD SUCCESSFUL. If only `:core:statquery`'s "scoring a full season … is fast" fails, run `./gradlew :core:statquery:test` alone to confirm the known CPU-contention flake.

Run: `./gradlew :app:assembleRelease`
Expected: BUILD SUCCESSFUL.

Run the gate, to confirm props left past weeks alone:
`./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025" -Pout=etl/build/accuracy.db`, then `GRIDIRON_STATS_DB=etl/build/accuracy.db GRIDIRON_ACCURACY_GATE=2025 ./gradlew :core:data:test --tests "dev.gridiron.core.data.AccuracyGateTest"`.
Expected: PASS, with the same MAE table as before (the build has no props, and `FORECAST_VERSION` 3 only forces a recompute).

```bash
git add core/ingest/build.gradle.kts core/ingest/src/main/kotlin/dev/gridiron/core/ingest/IngestPipeline.kt \
        core/ingest/src/test/kotlin/dev/gridiron/core/ingest/IngestPipelineTest.kt \
        app/src/main/kotlin/dev/gridiron/app/RefreshCoordinator.kt app/src/main/kotlin/dev/gridiron/app/RefreshText.kt \
        app/src/main/kotlin/dev/gridiron/app/GridironApplication.kt app/src/main/kotlin/dev/gridiron/app/GridironNavHost.kt \
        app/src/test/kotlin/dev/gridiron/app/RefreshCoordinatorTest.kt app/src/test/kotlin/dev/gridiron/app/RefreshTextTest.kt \
        CLAUDE.md
git commit -m "app: fetch props before each refresh and blend them into the forecast"
```

---

## Plan self-review (done while writing)

**Spec coverage (§5):**

| Requirement | Task |
|---|---|
| Key field in Settings, app-private, sent only to the Odds API | 5 (field, prefs); 3 (only `api.the-odds-api.com` URLs); 4 (key scrubbed from messages) |
| Free events call, then odds per upcoming game not kicked off and not fetched in 24 h, `regions=us`, 5 markets | 3 (URLs), 4 (window, 24 h, kickoff) |
| `x-requests-remaining` recorded; skip a call that would go below zero; Settings shows credits and last fetch | 4 (budget), 5 (Settings) |
| `prop_line` in `live.db`, pruned after the game; `PropsSnapshot` handed to the engine | 4 (tables, prune), 6 (hand-off) |
| Normalized name plus team; unmatched dropped and counted in the refresh report | 2 (matching, count), 6 (toast) |
| De-vig per book; anytime TD λ = −ln(1−p); over/under → Gamma mean with the position's CV | 1 |
| Inverse-variance blend with the final mean; `market` factor with a note | 1 (math), 2 (engine) |
| A bad key, no credits or no network leaves the model untouched; Settings shows why | 4, 5, 6 |
| Testing: parser tests against responses; budget tests with a fake fetcher | 3, 4 |

**Rulings (the executor copies these into HANDOFF.md):**
- **Fixtures are hand-built from the v4 docs**, not recorded, because there is no key in this environment. Anytime TD is assumed to use `Yes`/`No` outcome names, as the other markets use `Over`/`Under`. If real responses differ, the parser skips the outcomes it doesn't recognize, so the cost is fewer quotes (seen as unmatched or unchanged projections), never a crash. The first refresh with the user's key will tell.
- **Blend weights.** The spec says "inverse-variance" but names no variance for the market. The model's side uses layer 7's variance for its mean. The market's uses `MARKET_VARIANCE_RATIO` (0.5) times layer 7's variance for its own mean, which gives the market about two thirds of the weight. There are no historical props to fit this, and CLAUDE.md says so.
- **One-sided anytime-TD prices.** Many US books offer Yes only, so there's nothing to de-vig against. Such a price is divided by `ONE_SIDED_OVERROUND` (1.08) instead of being dropped.
- **Only the priced stats move.** That's receptions, receiving, rushing and passing yards, and rushing plus receiving TDs, split by the model's own split. Targets, first downs and the 40+/50+ TD bonus inputs are left alone, because props don't price them.
- **"The upcoming week"** for fetching is every game not yet kicked off before the Wednesday (00:00 UTC) after the first of them. The forecast still decides which games belong to its upcoming week, and props for any other game count as unmatched.
- **"Pruned after the game"** means 12 hours after kickoff.
- **Rest of season** includes the blended upcoming week, so the weekly and rest-of-season numbers agree.
- **`live.db` keeps `user_version` 1.** The tables come from `CREATE TABLE IF NOT EXISTS`, which every open runs, so news and injuries survive the update.
- **`FORECAST_VERSION` goes to 3** for the new stored factor. Past seasons are recomputed once on the next refresh; their numbers don't change.

**Type consistency:** `PropQuote(book, market, player, point, over, under)`, `PropEvent(home, away, quotes)`, `PropsSnapshot(events)` and `PropsOutcome(blended, unmatched)` are used with those names in Tasks 1, 2, 3, 4 and 6. `Forecast.run(conn, builtAt, copy, props, onWeek)` is the same in Tasks 2 and 6. `IngestPipeline.build(seasons, previous, out, props, onProgress)` and `StatsBuilder.build(seasons, previous, out, props, onProgress)` match in Task 6. `PropsStatus(creditsLeft, fetchedAt, error)` is the same in Tasks 4 and 5.

**Placeholders:** none. Every code step has its code.

**Review Focus coverage:** same-name players (Task 2 `MarketTest`); a refused key, credits running out, no connection (Task 4); kicked-off games and pruning (Task 4); the key in errors (Tasks 3 and 4); one-sided lines and nonsense prices (Task 1).
