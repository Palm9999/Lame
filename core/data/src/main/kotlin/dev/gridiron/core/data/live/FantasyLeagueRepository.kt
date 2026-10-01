package dev.gridiron.core.data.live

import dev.gridiron.core.data.PlayerDirectory
import dev.gridiron.core.datastore.EspnLeagueConfig
import dev.gridiron.core.datastore.PrefsSource
import dev.gridiron.core.model.Roster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
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

/**
 * An ESPN fantasy league: standings and every team's roster, read from ESPN's
 * fantasy API and kept in [file] so it shows offline. The user's own team also
 * becomes a [Roster] (id `espn-<league>`), replaced on each sync, so the Grid's
 * roster chip and stars work on it unchanged.
 *
 * Nothing here throws to a screen: a sync reports why it failed, and a failed
 * sync keeps the last league.
 */
public class FantasyLeagueRepository(
    private val prefs: PrefsSource,
    private val http: HeaderHttpGet,
    private val players: PlayerDirectory,
    private val file: File,
    private val clock: () -> Instant = Instant::now,
) {
    private val syncing = Mutex()
    private val _league = MutableStateFlow<FantasyLeague?>(null)
    private var loaded = false

    /** The last synced league; null before the first sync. */
    public val league: StateFlow<FantasyLeague?> = _league.asStateFlow()

    public val config: Flow<EspnLeagueConfig?> = prefs.prefs.map { it.espnLeague }.distinctUntilChanged()

    /** Reads the saved league from disk, once. */
    public suspend fun load() {
        if (loaded) return
        loaded = true
        withContext(Dispatchers.IO) {
            if (_league.value == null && file.isFile) _league.value = fantasyLeagueFromJson(file.readText())
        }
    }

    /** Saves the league to import; a different league drops the old one. Cookies are trimmed, blank means none. */
    public suspend fun setConfig(leagueId: String, espnS2: String?, swid: String?) {
        val id = leagueId.trim()
        val next = if (id.isEmpty()) null else EspnLeagueConfig(id, espnS2?.trim()?.ifEmpty { null }, swid?.trim()?.ifEmpty { null })
        prefs.update { p ->
            val old = p.espnLeague
            p.copy(
                espnLeague = next?.copy(teamId = if (old?.leagueId == next.leagueId) old.teamId else null),
                rosters = if (old != null && old.leagueId != next?.leagueId) p.rosters.filterNot { it.id == rosterId(old.leagueId) } else p.rosters,
            )
        }
        if (next == null || _league.value?.leagueId != next.leagueId) {
            _league.value = null
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    /** Marks [teamId] as the user's own and saves its roster. */
    public suspend fun chooseTeam(teamId: Int) {
        prefs.update { p -> p.copy(espnLeague = p.espnLeague?.copy(teamId = teamId)) }
        _league.value?.let { saveRoster(it) }
    }

    /** Reads the league for [season] from ESPN now. */
    public suspend fun sync(season: Int): LeagueSync = syncing.withLock {
        val cfg = prefs.prefs.first().espnLeague
            ?: return@withLock LeagueSync("no league id set")
        try {
            val cookies = listOfNotNull(cfg.espnS2?.let { "espn_s2=$it" }, cfg.swid?.let { "SWID=$it" }).joinToString("; ")
            val body = http.get(
                EspnFantasyParser.url(cfg.leagueId, season),
                if (cookies.isEmpty()) emptyMap() else mapOf("Cookie" to cookies),
            )
            val parsed = EspnFantasyParser.parse(body, cfg.leagueId, clock().toEpochMilli())
            val ids = players.playerIds(parsed.teams.flatMap { t -> t.players.map { it.espnId } }.filter { it.toIntOrNull()?.let { n -> n > 0 } == true })
            val league = parsed.copy(
                teams = parsed.teams.map { t ->
                    t.copy(
                        players = t.players.map { p -> p.copy(playerId = ids[p.espnId] ?: EspnFantasyParser.dstPlayerId(p.espnId)) },
                    )
                },
            )
            withContext(Dispatchers.IO) { file.writeText(league.toJson()) }
            _league.value = league
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

    private suspend fun saveRoster(league: FantasyLeague): LeagueSync {
        val cfg = prefs.prefs.first().espnLeague ?: return LeagueSync(null)
        val team = league.teams.firstOrNull { it.id == cfg.teamId }
            ?: cfg.swid?.let { swid -> league.teams.firstOrNull { t -> t.ownerIds.any { it.equals(swid, ignoreCase = true) } } }
            ?: return LeagueSync(null)
        val mapped = team.players.mapNotNull { it.playerId }.distinct()
        val id = rosterId(league.leagueId)
        prefs.update { p ->
            val roster = Roster(id, team.name.ifBlank { league.name }, mapped)
            val existing = p.rosters.any { it.id == id }
            p.copy(
                rosters = if (existing) p.rosters.map { if (it.id == id) roster else it } else p.rosters + roster,
                espnLeague = p.espnLeague?.copy(teamId = team.id),
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
