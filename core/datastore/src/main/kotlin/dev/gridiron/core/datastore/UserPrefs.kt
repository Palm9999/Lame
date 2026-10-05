package dev.gridiron.core.datastore

import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.Roster
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringProfile

/**
 * Everything the user sets up, in one small document.
 *
 * @property profiles User profiles only; presets live in code and are never stored.
 * @property resetNotice Set when an unreadable file was replaced with defaults,
 *   so the app can say so once; cleared when the notice has been shown.
 * @property seasons The seasons to build; null means the default (the current season and the two before it).
 * @property rosters The user's fantasy teams, in the order created.
 * @property gridPresets Saved Grid views, in the order created; at most [MAX_PRESETS], names unique ignoring case.
 * @property gridDensity The Grid's row height; absent or unknown reads as [RowDensity.COMFORTABLE].
 * @property oddsApiKey The user's key for The Odds API (spec §5); null when none is set. Sent only to api.the-odds-api.com.
 * @property espnLeagues The user's ESPN fantasy leagues, in the order added.
 * @property espnActive The id of the league every screen follows; a missing or unknown id reads as the first league.
 * @property espnLogin The one pair of ESPN cookies every league shares. They are sent only to ESPN.
 * @property injuryAlerts Notify when a rostered player's ESPN injury status changes; on unless turned off.
 */
public data class UserPrefs(
    val profiles: List<ScoringProfile>,
    val activeProfileId: String,
    val tray: List<CompareSlot>,
    val resetNotice: Boolean = false,
    val seasons: SeasonChoice? = null,
    val rosters: List<Roster> = emptyList(),
    val oddsApiKey: String? = null,
    val gridPresets: List<GridPreset> = emptyList(),
    val gridDensity: RowDensity = RowDensity.COMFORTABLE,
    val espnLeagues: List<EspnLeagueEntry> = emptyList(),
    val espnActive: String? = null,
    val espnLogin: EspnLogin? = null,
    val injuryAlerts: Boolean = true,
) {
    /** The active league with the shared cookies; null when no league is set. */
    public val espnLeague: EspnLeagueConfig?
        get() = (espnLeagues.firstOrNull { it.leagueId == espnActive } ?: espnLeagues.firstOrNull())
            ?.let { EspnLeagueConfig(it.leagueId, espnLogin?.espnS2, espnLogin?.swid, it.teamId) }

    /** The active profile, falling back to PPR if its id no longer exists. */
    public val active: ScoringProfile
        get() = ScoringPresets.byId(activeProfileId)
            ?: profiles.firstOrNull { it.id == activeProfileId }
            ?: ScoringPresets.PPR

    /** Like the generated one, but the key shows only as set or not: prefs must be safe to log. */
    override fun toString(): String =
        "UserPrefs(profiles=$profiles, activeProfileId=$activeProfileId, tray=$tray, resetNotice=$resetNotice, " +
            "seasons=$seasons, rosters=$rosters, oddsApiKey=${if (oddsApiKey == null) "null" else "…"}, espnLeagues=$espnLeagues, espnActive=$espnActive, espnLogin=$espnLogin, injuryAlerts=$injuryAlerts)"

    public companion object {
        public val DEFAULT: UserPrefs = UserPrefs(emptyList(), ScoringPresets.PPR.id, emptyList())
    }
}

/**
 * The seasons the phone builds, as the user left them.
 *
 * @property chosenIn The season that was current when this was saved. A choice
 *   that included it keeps following the current season as new ones start.
 */
public data class SeasonChoice(val seasons: List<Int>, val chosenIn: Int)

/** One ESPN league the user added: its id, the user's team in it (null to find it from the SWID) and ESPN's name for it once synced. */
public data class EspnLeagueEntry(
    val leagueId: String,
    val teamId: Int? = null,
    val name: String? = null,
    val keeperRule: KeeperRule = KeeperRule(),
) {
    init {
        require(leagueId.isNotBlank() && leagueId.all { it.isDigit() }) { "an ESPN league id is digits" }
    }
}

/**
 * How a keeper league prices a keeper: [keepers] per team, [penalty] rounds off a pick that was already a keeper,
 * [undraftedRound] for a player never drafted (null: the league's last round), and cost-round [overrides] by player id.
 */
public data class KeeperRule(
    val keepers: Int = 2,
    val penalty: Int = 1,
    val undraftedRound: Int? = null,
    val overrides: Map<String, Int> = emptyMap(),
)

/** The login cookies a private league needs, shared by every league; null reads a public league. Safe to log. */
public data class EspnLogin(val espnS2: String? = null, val swid: String? = null) {
    override fun toString(): String =
        "EspnLogin(espnS2=${if (espnS2 == null) "null" else "…"}, swid=${if (swid == null) "null" else "…"})"
}

/**
 * One ESPN league to import, with the login cookies it needs: the active league as [UserPrefs.espnLeague] resolves it.
 * [espnS2] and [swid] are the login
 * cookies a private league needs; both null reads a public league. [teamId] is
 * the user's own team, or null to find it from [swid].
 */
public data class EspnLeagueConfig(
    val leagueId: String,
    val espnS2: String? = null,
    val swid: String? = null,
    val teamId: Int? = null,
) {
    init {
        require(leagueId.isNotBlank() && leagueId.all { it.isDigit() }) { "an ESPN league id is digits" }
    }

    /** Safe to log: the cookies show only as set or not. */
    override fun toString(): String =
        "EspnLeagueConfig(leagueId=$leagueId, espnS2=${if (espnS2 == null) "null" else "…"}, " +
            "swid=${if (swid == null) "null" else "…"}, teamId=$teamId)"
}
