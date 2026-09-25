package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.statquery.Component

public data class AttributedFactor(val factor: String, val points: Double, val note: String?)

/**
 * Apportions the real fantasy-point delta (under the caller's own
 * [profile] — never a server-assumed default) across factors, using the
 * shipped [ProjectionFactor.logMultiplier] ratios. See design spec §3:
 * `Δᵢ = Δ × log_multiplier_i / Σⱼ log_multiplier_j`, guaranteed to sum
 * exactly to `Δ` by construction — except in the degenerate near-zero-sum
 * case, which is guarded explicitly rather than left to divide toward
 * infinity.
 */
public fun attributeFactors(
    baselineComponents: Map<Component, Double>,
    finalComponents: Map<Component, Double>,
    factors: List<ProjectionFactor>,
    profile: ScoringProfile,
    position: Position?,
): List<AttributedFactor> {
    val fpBaseline = score(baselineComponents, profile, position)
    val fpFinal = score(finalComponents, profile, position)
    val delta = fpFinal - fpBaseline

    val totalLogMult = factors.sumOf { it.logMultiplier }
    if (factors.isEmpty() || kotlin.math.abs(totalLogMult) < 1e-9) {
        // No factors, or they cancel to (near) zero: there's nothing
        // meaningful to apportion the delta across. Attribute it all to a
        // single synthetic "other" bucket rather than dividing by ~0.
        return if (delta == 0.0) emptyList()
        else listOf(AttributedFactor("other", delta, null))
    }

    return factors.map { f ->
        AttributedFactor(f.factor, delta * f.logMultiplier / totalLogMult, f.note)
    }
}
