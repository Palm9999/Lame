package dev.gridiron.core.statquery

/**
 * How a column's value is derived from components summed over a week range.
 *
 * The distinction matters. Target share over weeks 1-8 is
 * `sum(targets) / sum(team_targets)`, not the mean of eight weekly shares; the
 * mean gives a 3-target blowout the same weight as a 12-target shootout.
 */
public sealed interface Aggregate {
    public val components: Set<Component>

    /** Counting aggregates divide by games in per-game mode; rates never do. */
    public val scalesWithGames: Boolean

    /** Render as SQL, where [ref] yields the expression for a component's range sum. */
    public fun toSql(ref: (Component) -> String): String

    /** A plain sum over the range. */
    public data class Total(val component: Component) : Aggregate {
        override val components: Set<Component> get() = setOf(component)
        override val scalesWithGames: Boolean get() = true
        override fun toSql(ref: (Component) -> String): String = ref(component)
    }

    /**
     * Summed numerator over summed denominator. Null unless the denominator is
     * positive, which mirrors the ETL's weekly guard: a non-positive denominator
     * (a receiver with net-negative air yards, say) has no meaningful ratio.
     */
    public data class Ratio(val numerator: Component, val denominator: Component) : Aggregate {
        override val components: Set<Component> get() = setOf(numerator, denominator)
        override val scalesWithGames: Boolean get() = false
        override fun toSql(ref: (Component) -> String): String {
            val den = ref(denominator)
            return "(CASE WHEN $den > 0 THEN 1.0 * ${ref(numerator)} / $den END)"
        }
    }

    /**
     * `sum(weight * clamp(ratio, 0, 1))`, a missing ratio counting as zero.
     *
     * This is WOPR. Air yards share legitimately leaves [0, 1] because screens
     * carry negative air yards, but a composite *rating* must not go negative,
     * so its inputs are clamped exactly as the ETL clamps them.
     */
    public data class ClampedWeightedSum(val terms: List<Term>) : Aggregate {
        init {
            require(terms.isNotEmpty()) { "a weighted sum needs at least one term" }
        }

        public data class Term(val weight: Double, val ratio: Ratio) {
            init {
                require(weight.isFinite()) { "weight must be finite" }
            }
        }

        override val components: Set<Component>
            get() = terms.flatMapTo(linkedSetOf()) { it.ratio.components }
        override val scalesWithGames: Boolean get() = false
        override fun toSql(ref: (Component) -> String): String =
            terms.joinToString(separator = " + ", prefix = "(", postfix = ")") {
                "${it.weight} * MIN(MAX(COALESCE(${it.ratio.toSql(ref)}, 0.0), 0.0), 1.0)"
            }
    }

    /**
     * A total from the scoring step, which applies the spec's scoring profile
     * to each player-week before summing, so per-game bonuses see single games
     * rather than range totals. Reads no stored components directly.
     */
    public data class Scored(val output: ScoredOutput) : Aggregate {
        override val components: Set<Component> get() = emptySet()
        override val scalesWithGames: Boolean get() = true
        override fun toSql(ref: (Component) -> String): String = ref(output.pseudo)
    }
}

/** The scoring step's outputs, summed per player over the range. */
public enum class ScoredOutput(internal val alias: String) {
    FANTASY_POINTS("fp"),
    EXPECTED_FANTASY_POINTS("xfp"),
    OVER_EXPECTED("oe"),
    ;

    /**
     * Stands in for this output where [Aggregate.toSql] expects a component.
     * `@` never appears in a metric id, so it can't collide with a stored one.
     */
    internal val pseudo: Component get() = Component("@$alias")
}
