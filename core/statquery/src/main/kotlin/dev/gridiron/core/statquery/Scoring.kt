package dev.gridiron.core.statquery

import dev.gridiron.core.model.BonusStat
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.ScoringRule
import dev.gridiron.core.statquery.Components as C

/** A component and the sign it enters a rule with: incompletions are attempts minus completions. */
internal data class Term(val component: Component, val sign: Double = 1.0)

/**
 * What a rule is scored on. [expected] is empty when the opportunity model has
 * no counterpart (sacks, fumbles, carries, incompletions, long-TD bonuses);
 * those rules add nothing to xFP, so FPOE credits big plays and charges fumbles.
 */
internal data class RuleInputs(val actual: List<Term>, val expected: List<Term>)

private fun on(actual: Component, expected: Component? = null) =
    RuleInputs(listOf(Term(actual)), listOfNotNull(expected?.let { Term(it) }))

internal val RULE_INPUTS: Map<ScoringRule, RuleInputs> = mapOf(
    ScoringRule.PASS_YARD to on(C.PASSING_YARDS, C.X_PASSING_YARDS),
    ScoringRule.PASS_TD to on(C.PASSING_TDS, C.X_PASSING_TDS),
    ScoringRule.INTERCEPTION to on(C.INTERCEPTIONS, C.X_INTERCEPTIONS),
    ScoringRule.PASS_2PT to on(C.PASSING_2PT, C.X_PASSING_2PT),
    ScoringRule.COMPLETION to on(C.COMPLETIONS, C.X_COMPLETIONS),
    ScoringRule.INCOMPLETION to RuleInputs(listOf(Term(C.ATTEMPTS), Term(C.COMPLETIONS, -1.0)), emptyList()),
    ScoringRule.PASS_FIRST_DOWN to on(C.PASSING_FIRST_DOWNS, C.X_PASSING_FIRST_DOWNS),
    ScoringRule.SACK_TAKEN to on(C.SACKS_TAKEN),
    ScoringRule.PASS_TD_40 to on(C.PASSING_TDS_40),
    ScoringRule.PASS_TD_50 to on(C.PASSING_TDS_50),
    ScoringRule.RUSH_YARD to on(C.RUSHING_YARDS, C.X_RUSHING_YARDS),
    ScoringRule.RUSH_TD to on(C.RUSHING_TDS, C.X_RUSHING_TDS),
    ScoringRule.RUSH_2PT to on(C.RUSHING_2PT, C.X_RUSHING_2PT),
    ScoringRule.CARRY to on(C.CARRIES),
    ScoringRule.RUSH_FIRST_DOWN to on(C.RUSHING_FIRST_DOWNS, C.X_RUSHING_FIRST_DOWNS),
    ScoringRule.RUSH_TD_40 to on(C.RUSHING_TDS_40),
    ScoringRule.RUSH_TD_50 to on(C.RUSHING_TDS_50),
    ScoringRule.RECEPTION to on(C.RECEPTIONS, C.X_RECEPTIONS),
    ScoringRule.REC_YARD to on(C.RECEIVING_YARDS, C.X_RECEIVING_YARDS),
    ScoringRule.REC_TD to on(C.RECEIVING_TDS, C.X_RECEIVING_TDS),
    ScoringRule.REC_2PT to on(C.RECEIVING_2PT, C.X_RECEIVING_2PT),
    ScoringRule.REC_FIRST_DOWN to on(C.RECEIVING_FIRST_DOWNS, C.X_RECEIVING_FIRST_DOWNS),
    ScoringRule.REC_TD_40 to on(C.RECEIVING_TDS_40),
    ScoringRule.REC_TD_50 to on(C.RECEIVING_TDS_50),
    ScoringRule.FUMBLE_LOST to on(C.FUMBLES_LOST),
)

internal val BONUS_INPUTS: Map<BonusStat, List<Component>> = mapOf(
    BonusStat.PASSING_YARDS to listOf(C.PASSING_YARDS),
    BonusStat.RUSHING_YARDS to listOf(C.RUSHING_YARDS),
    BonusStat.RECEIVING_YARDS to listOf(C.RECEIVING_YARDS),
    BonusStat.RUSH_REC_YARDS to listOf(C.RUSHING_YARDS, C.RECEIVING_YARDS),
)

/** Every component the scoring step reads, sorted so equal profiles give identical SQL. */
internal val SCORING_COMPONENTS: List<Component> =
    (RULE_INPUTS.values.flatMap { it.actual + it.expected }.map { it.component } + BONUS_INPUTS.values.flatten())
        .distinct()
        .sortedBy { it.id }
