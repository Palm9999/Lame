package dev.gridiron.core.statquery

import dev.gridiron.core.model.BonusStat
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringGroup
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.ScoringRule
import dev.gridiron.core.statquery.Components as C

/** A component and the sign it enters a rule with: incompletions are attempts minus completions. */
public data class Term(public val component: Component, public val sign: Double = 1.0)

/**
 * What a rule is scored on. [expected] is empty when the opportunity model has
 * no counterpart (sacks, fumbles, carries, incompletions, long-TD bonuses);
 * those rules add nothing to xFP, so FPOE credits big plays and charges fumbles.
 */
public data class RuleInputs(public val actual: List<Term>, public val expected: List<Term>)

private fun on(actual: Component, expected: Component? = null) =
    RuleInputs(listOf(Term(actual)), listOfNotNull(expected?.let { Term(it) }))

public val RULE_INPUTS: Map<ScoringRule, RuleInputs> = mapOf(
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
    ScoringRule.FG_MADE_0_39 to on(C.FG_MADE_0_39),
    ScoringRule.FG_MADE_40_49 to on(C.FG_MADE_40_49),
    ScoringRule.FG_MADE_50 to on(C.FG_MADE_50),
    ScoringRule.FG_MISSED to on(C.FG_MISSED),
    ScoringRule.XP_MADE to on(C.XP_MADE),
    ScoringRule.XP_MISSED to on(C.XP_MISSED),
    ScoringRule.DST_SACK to on(C.DST_SACKS),
    ScoringRule.DST_INTERCEPTION to on(C.DST_INTERCEPTIONS),
    ScoringRule.DST_FUMBLE_RECOVERY to on(C.DST_FUMBLE_RECOVERIES),
    ScoringRule.DST_TD to on(C.DST_TDS),
    ScoringRule.DST_SAFETY to on(C.DST_SAFETIES),
    ScoringRule.DST_BLOCKED_KICK to on(C.DST_BLOCKED_KICKS),
)

public val BONUS_INPUTS: Map<BonusStat, List<Component>> = mapOf(
    BonusStat.PASSING_YARDS to listOf(C.PASSING_YARDS),
    BonusStat.RUSHING_YARDS to listOf(C.RUSHING_YARDS),
    BonusStat.RECEIVING_YARDS to listOf(C.RECEIVING_YARDS),
    BonusStat.RUSH_REC_YARDS to listOf(C.RUSHING_YARDS, C.RECEIVING_YARDS),
)

/**
 * Kicking and team-defense rules. Their facts belong to kickers and D/STs, a
 * few rows a week, so the scoring query pivots them on their own rather than
 * widening the offense's pivot.
 */
internal val SPECIAL_RULES: Set<ScoringRule> =
    ScoringRule.entries.filter { it.group == ScoringGroup.KICKING || it.group == ScoringGroup.DEFENSE }.toSet()

/** Every component the scoring step reads, sorted so equal profiles give identical SQL. */
internal val SCORING_COMPONENTS: List<Component> =
    (
        RULE_INPUTS.values.flatMap { it.actual + it.expected }.map { it.component } + BONUS_INPUTS.values.flatten() +
            // Points and yards allowed are read by the profile's tiers, not a rule.
            listOf(C.POINTS_ALLOWED, C.YARDS_ALLOWED)
    )
        .distinct()
        .sortedBy { it.id }
