package dev.gridiron.core.projections

import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.ScoringRule
import dev.gridiron.core.statquery.BONUS_INPUTS
import dev.gridiron.core.statquery.Component
import dev.gridiron.core.statquery.RULE_INPUTS

/**
 * Applies [profile] to raw stat components in memory — the numeric twin of
 * `StatQueryBuilder.points()`, which does the same rule lookups but emits SQL
 * text instead of a number. Both read the same [RULE_INPUTS]/[BONUS_INPUTS]
 * tables, so there is one source of truth for what a [ScoringRule] means,
 * even though SQL generation and direct evaluation are necessarily different
 * code shapes.
 *
 * A component missing from [components] scores as zero, never throws — this
 * is called from single-player Monte Carlo tens of thousands of times per
 * second, and a metric the ETL hasn't populated for some player-week must
 * degrade quietly, not crash the caller.
 */
public fun score(components: Map<Component, Double>, profile: ScoringProfile,
                  position: Position?): Double {
    fun value(component: Component): Double = components[component] ?: 0.0

    var total = 0.0
    for (rule in ScoringRule.entries) {
        val inputs = RULE_INPUTS.getValue(rule)
        for (term in inputs.actual) {
            val weight = if (rule == ScoringRule.RECEPTION) {
                profile.receptionWeight(position)
            } else {
                profile.weight(rule) * term.sign
            }
            total += weight * value(term.component)
        }
    }
    for (bonus in profile.yardageBonuses) {
        val yards = BONUS_INPUTS.getValue(bonus.stat).sumOf { value(it) }
        if (bonus.applies(yards)) total += bonus.points
    }
    return total
}
