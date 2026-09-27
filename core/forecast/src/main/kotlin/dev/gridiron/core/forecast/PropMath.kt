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
