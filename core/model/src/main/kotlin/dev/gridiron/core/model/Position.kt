package dev.gridiron.core.model

/** Positions as stored in `player.position`: the offense, kickers, and DST (a team's defense and special teams). */
public enum class Position(public val code: String) {
    QB("QB"),
    RB("RB"),
    FB("FB"),
    WR("WR"),
    TE("TE"),
    K("K"),
    DST("DST"),
    ;

    public companion object {
        public val FLEX: Set<Position> = setOf(RB, WR, TE)
        public val SUPERFLEX: Set<Position> = setOf(QB, RB, WR, TE)

        public fun fromCode(code: String): Position? = entries.firstOrNull { it.code == code }

        /** How a stored code reads on screen: "D/ST" for a team's defense, any other code as stored. */
        public fun label(code: String): String = if (code == DST.code) "D/ST" else code
    }
}
