package dev.gridiron.core.model

/** Offensive positions as stored in `player.position`. */
public enum class Position(public val code: String) {
    QB("QB"),
    RB("RB"),
    FB("FB"),
    WR("WR"),
    TE("TE"),
    K("K"),
    ;

    public companion object {
        public val FLEX: Set<Position> = setOf(RB, WR, TE)
        public val SUPERFLEX: Set<Position> = setOf(QB, RB, WR, TE)

        public fun fromCode(code: String): Position? = entries.firstOrNull { it.code == code }
    }
}
