package dev.gridiron.core.forecast

import kotlin.math.pow

/**
 * Exponentially weighted mean, oldest value first, so the newest counts most.
 * Starts at the first value (no bias correction), like polars' `adjust=False`.
 */
internal fun ewma(values: List<Double>, halfLife: Double): Double? {
    if (values.isEmpty()) return null
    val alpha = 1.0 - 0.5.pow(1.0 / halfLife)
    var e = values[0]
    for (i in 1 until values.size) e = alpha * values[i] + (1 - alpha) * e
    return e
}

/** A rate as the ratio of two EWMAs, so a 2-target game can't swing it as much as a 12-target one. */
internal fun ewmaRatio(numerators: List<Double>, denominators: List<Double>, halfLife: Double): Double? {
    val den = ewma(denominators, halfLife) ?: return null
    if (den <= 0.0) return null
    return ewma(numerators, halfLife)!! / den
}

/** James-Stein: a sample of size [n] carries n/(n+k) of the weight, and [baseline] the rest. */
internal fun shrink(observed: Double?, n: Double, baseline: Double, k: Double): Double =
    if (observed == null || n <= 0.0) baseline else (n * observed + k * baseline) / (n + k)

/** Last season's weight entering [week]: 0.55 in week 1, falling linearly to 0 by week 6. */
internal fun carryoverWeight(week: Int): Double {
    if (week >= K.CARRYOVER_LAST_WEEK) return 0.0
    val span = (K.CARRYOVER_LAST_WEEK - 1).toDouble()
    return K.CARRYOVER_START * (K.CARRYOVER_LAST_WEEK - maxOf(week, 1)) / span
}

/** `sigma = a * mean^0.75`, with `a` set so the CV at a mean of 10 is [cv]; returned as a variance. */
internal fun varianceFor(mean: Double, cv: Double): Double {
    if (mean <= 0.0) return 0.0
    val a = cv * 10.0.pow(1 - K.VARIANCE_EXPONENT)
    val sigma = a * mean.pow(K.VARIANCE_EXPONENT)
    return sigma * sigma
}

/** Clamps a multiplier to `1 ± cap`. */
internal fun Double.capAround(cap: Double): Double = coerceIn(1.0 - cap, 1.0 + cap)
