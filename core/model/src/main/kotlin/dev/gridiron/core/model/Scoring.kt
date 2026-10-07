package dev.gridiron.core.model

/** Where a rule appears in the scoring editor. */
public enum class ScoringGroup(public val label: String) {
    PASSING("Passing"),
    RUSHING("Rushing"),
    RECEIVING("Receiving"),
    TURNOVERS("Turnovers"),
    KICKING("Kicking"),
    DEFENSE("Team defense"),
}

/** Points per unit of one stat. IDP is absent on purpose. A D/ST's points allowed are scored by the profile's [ScoringProfile.pointsAllowedTiers], not a rule. */
public enum class ScoringRule(public val group: ScoringGroup, public val label: String) {
    PASS_YARD(ScoringGroup.PASSING, "Per passing yard"),
    PASS_TD(ScoringGroup.PASSING, "Passing TD"),
    INTERCEPTION(ScoringGroup.PASSING, "Interception thrown"),
    PASS_2PT(ScoringGroup.PASSING, "2-pt conversion pass"),
    COMPLETION(ScoringGroup.PASSING, "Completion"),
    INCOMPLETION(ScoringGroup.PASSING, "Incompletion"),
    PASS_FIRST_DOWN(ScoringGroup.PASSING, "Passing first down"),
    SACK_TAKEN(ScoringGroup.PASSING, "Sack taken"),
    PASS_TD_40(ScoringGroup.PASSING, "40+ yd TD pass bonus"),
    PASS_TD_50(ScoringGroup.PASSING, "50+ yd TD pass bonus"),
    RUSH_YARD(ScoringGroup.RUSHING, "Per rushing yard"),
    RUSH_TD(ScoringGroup.RUSHING, "Rushing TD"),
    RUSH_2PT(ScoringGroup.RUSHING, "2-pt conversion run"),
    CARRY(ScoringGroup.RUSHING, "Carry"),
    RUSH_FIRST_DOWN(ScoringGroup.RUSHING, "Rushing first down"),
    RUSH_TD_40(ScoringGroup.RUSHING, "40+ yd TD run bonus"),
    RUSH_TD_50(ScoringGroup.RUSHING, "50+ yd TD run bonus"),
    RECEPTION(ScoringGroup.RECEIVING, "Reception"),
    REC_YARD(ScoringGroup.RECEIVING, "Per receiving yard"),
    REC_TD(ScoringGroup.RECEIVING, "Receiving TD"),
    REC_2PT(ScoringGroup.RECEIVING, "2-pt conversion catch"),
    REC_FIRST_DOWN(ScoringGroup.RECEIVING, "Receiving first down"),
    REC_TD_40(ScoringGroup.RECEIVING, "40+ yd TD catch bonus"),
    REC_TD_50(ScoringGroup.RECEIVING, "50+ yd TD catch bonus"),
    FUMBLE_LOST(ScoringGroup.TURNOVERS, "Fumble lost"),
    FG_MADE_0_39(ScoringGroup.KICKING, "FG made, 0-39 yds"),
    FG_MADE_40_49(ScoringGroup.KICKING, "FG made, 40-49 yds"),
    FG_MADE_50(ScoringGroup.KICKING, "FG made, 50+ yds"),
    FG_MISSED(ScoringGroup.KICKING, "FG missed"),
    XP_MADE(ScoringGroup.KICKING, "Extra point made"),
    XP_MISSED(ScoringGroup.KICKING, "Extra point missed"),
    DST_SACK(ScoringGroup.DEFENSE, "Sack"),
    DST_INTERCEPTION(ScoringGroup.DEFENSE, "Interception"),
    DST_FUMBLE_RECOVERY(ScoringGroup.DEFENSE, "Fumble recovery"),
    DST_TD(ScoringGroup.DEFENSE, "Defensive or return TD"),
    DST_SAFETY(ScoringGroup.DEFENSE, "Safety"),
    DST_BLOCKED_KICK(ScoringGroup.DEFENSE, "Blocked kick"),
}

public enum class BonusStat(public val label: String) {
    PASSING_YARDS("Passing yards"),
    RUSHING_YARDS("Rushing yards"),
    RECEIVING_YARDS("Receiving yards"),
    RUSH_REC_YARDS("Rushing + receiving yards"),
}

/**
 * [points] for a game in which [stat] lands in `min until maxExclusive`, or
 * `min` and up when [maxExclusive] is null. ESPN and Sleeper both define tiers
 * as ranges ("100-199", "200+"), so ranges are the primitive. Overlapping
 * ranges are allowed; each one that matches applies.
 */
public data class YardageBonus(
    val stat: BonusStat,
    val min: Int,
    val maxExclusive: Int?,
    val points: Double,
) {
    init {
        require(min >= 0) { "bonus minimum must not be negative, was $min" }
        require(maxExclusive == null || maxExclusive > min) { "bonus range $min until $maxExclusive is empty" }
        require(points.isFinite()) { "bonus points must be finite, was $points" }
    }

    public fun applies(yards: Double): Boolean = yards >= min && (maxExclusive == null || yards < maxExclusive)
}

/**
 * One league's scoring. Fantasy points are never stored; every query applies
 * the active profile to stat components, so switching leagues is instant and
 * works for any week range.
 *
 * @property receptionByPosition Reception points by position (TE premium);
 *   positions without an entry use [ScoringRule.RECEPTION].
 * @property basedOn The preset this profile was copied from, for "Reset to preset".
 * @property pointsAllowedTiers A D/ST's points-allowed tiers, lowest first. The
 *   first starts at 0, so every game lands in one; empty scores points allowed as nothing.
 * @property yardsAllowedTiers A D/ST's yards-allowed tiers, lowest first, under the same rules.
 */
public data class ScoringProfile(
    val id: String,
    val name: String,
    val weights: Map<ScoringRule, Double>,
    val receptionByPosition: Map<Position, Double> = emptyMap(),
    val yardageBonuses: List<YardageBonus> = emptyList(),
    val basedOn: String? = null,
    val pointsAllowedTiers: List<ScoringTier> = emptyList(),
    val yardsAllowedTiers: List<ScoringTier> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "profile id must not be blank" }
        require(name.isNotBlank()) { "profile name must not be blank" }
        require(weights.values.all { it.isFinite() }) { "weights must be finite: $weights" }
        require(receptionByPosition.keys.all { it in RECEPTION_POSITIONS }) {
            "reception overrides apply to RB, WR and TE only: ${receptionByPosition.keys}"
        }
        require(receptionByPosition.values.all { it.isFinite() }) { "reception weights must be finite" }
        require(tiersAreValid(pointsAllowedTiers)) { "points-allowed tiers must start at 0 and rise: $pointsAllowedTiers" }
        require(tiersAreValid(yardsAllowedTiers)) { "yards-allowed tiers must start at 0 and rise: $yardsAllowedTiers" }
    }

    public fun weight(rule: ScoringRule): Double = weights[rule] ?: 0.0

    public fun receptionWeight(position: Position?): Double =
        receptionByPosition[position] ?: weight(ScoringRule.RECEPTION)

    /** A D/ST's points for one game in which the opponent scored [allowed]: the highest tier starting at or below it. */
    public fun pointsAllowedPoints(allowed: Double): Double = tierPoints(pointsAllowedTiers, allowed)

    /**
     * The expected [pointsAllowedPoints] for one game whose points allowed are
     * about Normal([mean], [sd]) and land on whole points (see [expectedTierPoints]).
     */
    public fun expectedPointsAllowedPoints(mean: Double, sd: Double): Double = expectedTierPoints(pointsAllowedTiers, mean, sd)

    /** A D/ST's points for one game in which the opponent gained [allowed] net yards. */
    public fun yardsAllowedPoints(allowed: Double): Double = tierPoints(yardsAllowedTiers, allowed)

    /** The expected [yardsAllowedPoints] for one game whose yards allowed are about Normal([mean], [sd]), on whole yards. */
    public fun expectedYardsAllowedPoints(mean: Double, sd: Double): Double = expectedTierPoints(yardsAllowedTiers, mean, sd)

    public val isPreset: Boolean get() = ScoringPresets.byId(id) != null

    public companion object {
        public val RECEPTION_POSITIONS: Set<Position> = setOf(Position.RB, Position.WR, Position.TE)
    }
}

/** ESPN's default scoring in its three reception flavors: offense, the common kicking and team-defense values, and ESPN's points-allowed tiers. Immutable; copy one to customize. */
public object ScoringPresets {
    /** The common kicking and team-defense values every preset scores, and the version-2 prefs migration writes into older profiles. */
    public val KICKING_AND_DEFENSE: Map<ScoringRule, Double> = mapOf(
        ScoringRule.FG_MADE_0_39 to 3.0,
        ScoringRule.FG_MADE_40_49 to 4.0,
        ScoringRule.FG_MADE_50 to 5.0,
        ScoringRule.FG_MISSED to -1.0,
        ScoringRule.XP_MADE to 1.0,
        ScoringRule.XP_MISSED to -1.0,
        ScoringRule.DST_SACK to 1.0,
        ScoringRule.DST_INTERCEPTION to 2.0,
        ScoringRule.DST_FUMBLE_RECOVERY to 2.0,
        ScoringRule.DST_TD to 6.0,
        ScoringRule.DST_SAFETY to 2.0,
        ScoringRule.DST_BLOCKED_KICK to 2.0,
    )

    private fun espn(id: String, name: String, reception: Double) = ScoringProfile(
        id = id,
        name = name,
        weights = mapOf(
            ScoringRule.PASS_YARD to 0.04,
            ScoringRule.PASS_TD to 4.0,
            ScoringRule.INTERCEPTION to -2.0,
            ScoringRule.PASS_2PT to 2.0,
            ScoringRule.RUSH_YARD to 0.1,
            ScoringRule.RUSH_TD to 6.0,
            ScoringRule.RUSH_2PT to 2.0,
            ScoringRule.RECEPTION to reception,
            ScoringRule.REC_YARD to 0.1,
            ScoringRule.REC_TD to 6.0,
            ScoringRule.REC_2PT to 2.0,
            ScoringRule.FUMBLE_LOST to -2.0,
        ) + KICKING_AND_DEFENSE,
        pointsAllowedTiers = ESPN_POINTS_ALLOWED,
        yardsAllowedTiers = ESPN_YARDS_ALLOWED,
    )

    public val PPR: ScoringProfile = espn("preset:ppr", "PPR", 1.0)
    public val HALF_PPR: ScoringProfile = espn("preset:half", "Half PPR", 0.5)
    public val STANDARD: ScoringProfile = espn("preset:standard", "Standard", 0.0)

    public val all: List<ScoringProfile> = listOf(PPR, HALF_PPR, STANDARD)

    public fun byId(id: String): ScoringProfile? = all.firstOrNull { it.id == id }
}
