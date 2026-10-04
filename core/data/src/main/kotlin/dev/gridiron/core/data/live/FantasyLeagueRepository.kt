package dev.gridiron.core.data.live

import dev.gridiron.core.data.PlayerDirectory
import dev.gridiron.core.data.weekPoints
import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.datastore.EspnLeagueConfig
import dev.gridiron.core.datastore.EspnLeagueEntry
import dev.gridiron.core.datastore.EspnLogin
import dev.gridiron.core.datastore.PrefsSource
import dev.gridiron.core.model.Roster
import dev.gridiron.core.model.ScoringProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant

/** How a league sync went; [error] null means it landed. */
public data class LeagueSync(val error: String?, val rosterUpdated: Boolean = false, val unmatched: Int = 0) {
    public val ok: Boolean get() = error == null

    /** One line for a toast or the Settings screen. */
    public val message: String
        get() = when {
            error != null -> "League not updated: $error."
            rosterUpdated && unmatched > 0 -> "League updated; your roster is saved ($unmatched players not matched)."
            rosterUpdated -> "League updated; your roster is saved."
            else -> "League updated. Pick your team to save its roster."
        }
}

/** A week's league matchups as last read; [error] null means they landed. */
/** One league in the switcher. */
public data class LeagueChoice(val leagueId: String, val name: String, val active: Boolean)

public data class MatchupsResult(val matchups: List<LeagueMatchup>, val fetchedAtMillis: Long, val error: String?)

/**
 * The user's ESPN fantasy leagues: standings and every team's roster, read from ESPN's
 * fantasy API and kept in `league-<id>.json` under [dir] so each shows offline. One league
 * is *active* and every screen follows it; each league's user team also becomes a [Roster]
 * (id `espn-<league>`), replaced on each sync, so the Grid's roster chip and stars work
 * on it unchanged. All leagues share one login (the cookies).
 *
 * Nothing here throws to a screen: a sync reports why it failed, and a failed
 * sync keeps the last league.
 */
public class FantasyLeagueRepository(
    private val prefs: PrefsSource,
    private val http: HeaderHttpGet,
    private val players: PlayerDirectory,
    private val dir: File,
    /** `stats.db`, for the app's points in [matchups]; without it they stay null. */
    private val stats: QueryExecutor? = null,
    private val clock: () -> Instant = Instant::now,
) {
    private val syncing = Mutex()
    private val _league = MutableStateFlow<FantasyLeague?>(null)
    private var loaded = false

    /** The active league's last sync; null before the first sync. */
    public val league: StateFlow<FantasyLeague?> = _league.asStateFlow()

    /** The active league with the shared login. */
    public val config: Flow<EspnLeagueConfig?> = prefs.prefs.map { it.espnLeague }.distinctUntilChanged()

    /** Every league the user added, the active one marked. */
    public val leagues: Flow<List<LeagueChoice>> = prefs.prefs.map { p ->
        val active = p.espnLeague?.leagueId
        p.espnLeagues.map { LeagueChoice(it.leagueId, it.name ?: "League ${it.leagueId}", it.leagueId == active) }
    }.distinctUntilChanged()

    /** The shared login; null when none is set. */
    public val login: Flow<EspnLogin?> = prefs.prefs.map { it.espnLogin }.distinctUntilChanged()

    /** The user's chosen team with the league's starting slots; null with no league, no team chosen or another league's. */
    public val myTeam: Flow<MyTeam?> = flow {
        load()
        emitAll(combine(league, config) { l, c -> if (l != null && c != null && c.leagueId == l.leagueId) l.myTeam(c.teamId) else null })
    }.distinctUntilChanged()

    /** The active league's other teams, for trades; empty with no league or no team chosen. */
    public val otherTeams: Flow<List<MyTeam>> = flow {
        load()
        emitAll(combine(league, config) { l, c -> if (l != null && c != null && c.leagueId == l.leagueId) l.otherTeams(c.teamId) else emptyList() })
    }.distinctUntilChanged()

    /** Who is on a league team, from the saved snapshot (read from disk on first collect); null with no league synced. */
    public val rostered: Flow<LeagueRostered?> = flow {
        load()
        emitAll(league.map { it?.rostered() })
    }.distinctUntilChanged()

    /** Reads the active league's saved snapshot from disk, once. */
    public suspend fun load() {
        if (loaded) return
        loaded = true
        withContext(Dispatchers.IO) {
            val active = prefs.prefs.first().espnLeague?.leagueId
            migrateLegacyFile(active)
            if (_league.value == null && active != null) _league.value = read(active)
        }
    }

    /** Adds a league (or finds it again) and makes it active. A blank or non-digit id throws [IllegalArgumentException]. */
    public suspend fun addLeague(leagueId: String) {
        val entry = EspnLeagueEntry(leagueId.trim())
        prefs.update { p ->
            p.copy(
                espnLeagues = if (p.espnLeagues.any { it.leagueId == entry.leagueId }) p.espnLeagues else p.espnLeagues + entry,
                espnActive = entry.leagueId,
            )
        }
        switchTo(entry.leagueId)
    }

    /** Makes a league the active one; an id that isn't added does nothing. */
    public suspend fun setActive(leagueId: String) {
        if (prefs.prefs.first().espnLeagues.none { it.leagueId == leagueId }) return
        prefs.update { it.copy(espnActive = leagueId) }
        switchTo(leagueId)
    }

    /** Drops a league: its saved snapshot and its roster go too, and the first remaining league becomes active. */
    public suspend fun removeLeague(leagueId: String) {
        prefs.update { p ->
            val left = p.espnLeagues.filterNot { it.leagueId == leagueId }
            p.copy(
                espnLeagues = left,
                espnActive = if (p.espnLeague?.leagueId == leagueId) left.firstOrNull()?.leagueId else p.espnActive,
                rosters = p.rosters.filterNot { it.id == rosterId(leagueId) },
            )
        }
        withContext(Dispatchers.IO) { fileOf(leagueId).delete() }
        switchTo(prefs.prefs.first().espnLeague?.leagueId)
    }

    /** Saves the login every league shares; blank means none. */
    public suspend fun setLogin(espnS2: String?, swid: String?) {
        val s2 = espnS2?.trim()?.ifEmpty { null }
        val id = swid?.trim()?.ifEmpty { null }
        prefs.update { it.copy(espnLogin = if (s2 == null && id == null) null else EspnLogin(s2, id)) }
    }

    /** Marks [teamId] as the user's own in the active league and saves its roster. */
    public suspend fun chooseTeam(teamId: Int) {
        prefs.update { p ->
            val active = p.espnLeague?.leagueId
            p.copy(espnLeagues = p.espnLeagues.map { if (it.leagueId == active) it.copy(teamId = teamId) else it })
        }
        _league.value?.let { saveRoster(it) }
    }

    private suspend fun switchTo(leagueId: String?) {
        loaded = true
        _league.value = if (leagueId == null) null else withContext(Dispatchers.IO) { read(leagueId) }
    }

    private fun fileOf(leagueId: String) = File(dir, "league-$leagueId.json")

    private fun read(leagueId: String): FantasyLeague? =
        fileOf(leagueId).takeIf { it.isFile }?.let { fantasyLeagueFromJson(it.readText()) }?.takeIf { it.leagueId == leagueId }

    /** Before several leagues, one `league.json` held the league; it becomes that league's own file. */
    private fun migrateLegacyFile(active: String?) {
        val legacy = File(dir, "league.json")
        if (!legacy.isFile) return
        val id = fantasyLeagueFromJson(legacy.readText())?.leagueId ?: active
        if (id != null && !fileOf(id).exists()) legacy.renameTo(fileOf(id)) else legacy.delete()
    }

    /** Reads the league for [season] from ESPN now. */
    public suspend fun sync(season: Int): LeagueSync = syncing.withLock {
        val cfg = prefs.prefs.first().espnLeague
            ?: return@withLock LeagueSync("no league id set")
        load()
        try {
            val body = http.get(EspnFantasyParser.url(cfg.leagueId, season), headers(cfg))
            val parsed = EspnFantasyParser.parse(body, cfg.leagueId, clock().toEpochMilli())
            val ids = players.playerIds(parsed.teams.flatMap { t -> t.players.map { it.espnId } }.filter { it.toIntOrNull()?.let { n -> n > 0 } == true })
            val league = parsed.copy(
                teams = parsed.teams.map { t ->
                    t.copy(
                        players = t.players.map { p -> p.copy(playerId = ids[p.espnId] ?: EspnFantasyParser.dstPlayerId(p.espnId)) },
                    )
                },
            )
            withContext(Dispatchers.IO) { fileOf(league.leagueId).writeText(league.toJson()) }
            prefs.update { p -> p.copy(espnLeagues = p.espnLeagues.map { if (it.leagueId == league.leagueId) it.copy(name = league.name) else it }) }
            if (prefs.prefs.first().espnLeague?.leagueId == league.leagueId) _league.value = league
            saveRoster(league)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LiveFormatException) {
            LeagueSync(e.message ?: "ESPN changed its league format")
        } catch (e: IOException) {
            LeagueSync(friendly(e.message))
        } catch (e: Exception) {
            LeagueSync("couldn't read the league")
        }
    }

    /**
     * [week]'s matchups from ESPN, each lineup player carrying ESPN's points and the app's under [scoring]. Never
     * throws: a failure says why in [MatchupsResult.error].
     */
    public suspend fun matchups(season: Int, week: Int, scoring: ScoringProfile): MatchupsResult {
        val cfg = prefs.prefs.first().espnLeague ?: return MatchupsResult(emptyList(), 0, "no league id set")
        val now = clock().toEpochMilli()
        return try {
            val raw = EspnFantasyParser.matchups(http.get(EspnFantasyParser.matchupsUrl(cfg.leagueId, season, week), headers(cfg)), week)
            MatchupsResult(withAppPoints(raw, season, week, scoring), now, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LiveFormatException) {
            MatchupsResult(emptyList(), now, e.message ?: "ESPN changed its matchup format")
        } catch (e: IOException) {
            MatchupsResult(emptyList(), now, friendly(e.message))
        } catch (e: Exception) {
            MatchupsResult(emptyList(), now, "couldn't read the matchups")
        }
    }

    /**
     * The user's opponent in [week], from ESPN's schedule, with the roster from the last sync and the user's slots, so
     * the same lineup picker can rate both. Never throws: [OpponentResult.message] says why there is no opponent.
     */
    public suspend fun opponent(season: Int, week: Int): OpponentResult {
        load()
        val cfg = prefs.prefs.first().espnLeague ?: return OpponentResult(null, "no league id set")
        val league = _league.value?.takeIf { it.leagueId == cfg.leagueId } ?: return OpponentResult(null, "sync your league first")
        val mine = league.myTeam(cfg.teamId) ?: return OpponentResult(null, "choose your team first")
        return try {
            val raw = EspnFantasyParser.matchups(http.get(EspnFantasyParser.matchupsUrl(cfg.leagueId, season, week), headers(cfg)), week)
            val matchup = raw.firstOrNull { it.home.teamId == cfg.teamId || it.away?.teamId == cfg.teamId }
                ?: return OpponentResult(null, "ESPN lists no matchup for you in week $week")
            val opponentId = (if (matchup.home.teamId == cfg.teamId) matchup.away?.teamId else matchup.home.teamId)
                ?: return OpponentResult(null, "you have a bye in week $week")
            val team = league.teams.firstOrNull { it.id == opponentId }
                ?: return OpponentResult(null, "your opponent isn't in the last league sync")
            OpponentResult(MyTeam(team.name, league.season, team.players, mine.slots, mine.slotsAreDefault), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LiveFormatException) {
            OpponentResult(null, e.message ?: "ESPN changed its matchup format")
        } catch (e: IOException) {
            OpponentResult(null, friendly(e.message))
        } catch (e: Exception) {
            OpponentResult(null, "couldn't read the matchups")
        }
    }

    private suspend fun withAppPoints(raw: List<LeagueMatchup>, season: Int, week: Int, scoring: ScoringProfile): List<LeagueMatchup> {
        val lineups = raw.flatMap { listOfNotNull(it.home, it.away) }.flatMap { it.lineup }
        val ids = players.playerIds(lineups.map { it.espnId }.filter { it.toIntOrNull()?.let { n -> n > 0 } == true }.distinct())
        fun appId(p: MatchupPlayer) = ids[p.espnId] ?: EspnFantasyParser.dstPlayerId(p.espnId)
        val points = stats?.weekPoints(season, week, lineups.mapNotNull(::appId).toSet(), scoring).orEmpty().associate { it.playerId to it.points }
        fun side(s: MatchupSide): MatchupSide {
            val lineup = s.lineup.map { p -> appId(p).let { id -> p.copy(playerId = id, appPoints = id?.let(points::get)) } }
            val starters = lineup.filter { it.slot != "BE" && it.slot != "IR" }.mapNotNull { it.appPoints }
            return s.copy(lineup = lineup, appTotal = if (starters.isEmpty()) null else starters.sum())
        }
        return raw.map { it.copy(home = side(it.home), away = it.away?.let(::side)) }
    }

    private fun headers(cfg: EspnLeagueConfig): Map<String, String> {
        val cookies = listOfNotNull(cfg.espnS2?.let { "espn_s2=$it" }, cfg.swid?.let { "SWID=$it" }).joinToString("; ")
        return if (cookies.isEmpty()) emptyMap() else mapOf("Cookie" to cookies)
    }

    private suspend fun saveRoster(league: FantasyLeague): LeagueSync {
        val now = prefs.prefs.first()
        val entry = now.espnLeagues.firstOrNull { it.leagueId == league.leagueId } ?: return LeagueSync(null)
        val team = league.teams.firstOrNull { it.id == entry.teamId }
            ?: now.espnLogin?.swid?.let { swid -> league.teams.firstOrNull { t -> t.ownerIds.any { it.equals(swid, ignoreCase = true) } } }
            ?: return LeagueSync(null)
        val mapped = team.players.mapNotNull { it.playerId }.distinct()
        val id = rosterId(league.leagueId)
        prefs.update { p ->
            val roster = Roster(id, team.name.ifBlank { league.name }, mapped)
            val existing = p.rosters.any { it.id == id }
            p.copy(
                rosters = if (existing) p.rosters.map { if (it.id == id) roster else it } else p.rosters + roster,
                espnLeagues = p.espnLeagues.map { if (it.leagueId == league.leagueId) it.copy(teamId = team.id) else it },
            )
        }
        return LeagueSync(null, rosterUpdated = true, unmatched = team.players.size - mapped.size)
    }

    private fun friendly(message: String?): String = when {
        message == null -> "couldn't reach ESPN"
        "HTTP 401" in message || "HTTP 403" in message -> "ESPN says this league is private; add your espn_s2 and SWID cookies"
        "HTTP 404" in message -> "ESPN doesn't know that league for this season"
        else -> message
    }

    public companion object {
        public fun rosterId(leagueId: String): String = "espn-$leagueId"
    }
}
