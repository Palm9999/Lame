package dev.gridiron.core.data.live

import dev.gridiron.core.model.Roster
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

/** A player's ESPN injury listing as last seen: [abbr] ("Q", "O", "IR"…), [status] ("Questionable"), [name]. */
public data class SeenStatus(val abbr: String, val status: String, val name: String)

/**
 * A rostered player's ESPN status changed from [from] to [to] (null: not on ESPN's injury list, so healthy).
 * [starting]: he sits in a starting slot of the user's ESPN lineup, as of the last league sync.
 */
public data class InjuryAlert(val playerId: String, val name: String, val from: SeenStatus?, val to: SeenStatus?, val starting: Boolean) {
    /** "Pat Star: Out", or "Pat Star: off the injury report". */
    public val title: String get() = "$name: ${to?.status ?: "off the injury report"}"

    /** What it was, and what to do when a starter is now unlikely to play. */
    public val text: String
        get() = buildString {
            append(from?.let { "Was ${it.status}." } ?: "Was healthy.")
            if (starting && to?.abbr in SIT) append(" He's in your ESPN lineup: start someone else.")
        }

    private companion object {
        val SIT = setOf("O", "IR", "D", "SUSP")
    }
}

public object InjuryAlerts {
    /** ESPN's injury list as [SeenStatus] by app player id; players it couldn't match are left out. */
    public fun seen(injuries: List<LiveInjury>): Map<String, SeenStatus> =
        injuries.mapNotNull { i -> i.playerId?.let { it to SeenStatus(i.abbr, i.status, i.name) } }.toMap()

    /**
     * Every player in [rostered] whose listing differs between [before] and [now] (by abbreviation, so a reworded
     * comment isn't news), by name. [starters] are the players in a starting slot of the user's ESPN lineup.
     */
    public fun changes(
        before: Map<String, SeenStatus>,
        now: Map<String, SeenStatus>,
        rostered: Set<String>,
        starters: Set<String>,
    ): List<InjuryAlert> =
        rostered.mapNotNull { id ->
            val from = before[id]
            val to = now[id]
            if (from?.abbr == to?.abbr) return@mapNotNull null
            InjuryAlert(id, (to ?: from)!!.name, from, to, id in starters)
        }.sortedBy { it.name }
}

/**
 * Checks ESPN's injury list against what it last saw and returns the alerts for rostered players ([rosters], every
 * one of the user's, ESPN league teams included; [starters] from the active league's lineup). The listing seen is kept
 * in [stateFile] for every player, not only rostered ones, so adding an already-injured player to a roster isn't news.
 * The first check only stores; a failed fetch, or a list with no player the app knows (an empty response, no stats
 * yet), keeps the old listing and alerts nothing rather than reading as everyone recovered.
 */
public class InjuryAlertChecker(
    private val live: LiveRepository,
    private val rosters: Flow<List<Roster>>,
    private val starters: suspend () -> Set<String>,
    private val stateFile: File,
) {
    public suspend fun check(): List<InjuryAlert> {
        if (live.refresh().injuriesError != null) return emptyList()
        val now = InjuryAlerts.seen(live.injuries())
        if (now.isEmpty()) return emptyList()
        val before = read()
        write(now)
        if (before == null) return emptyList()
        val rostered = rosters.first().flatMapTo(HashSet()) { it.playerIds }
        return InjuryAlerts.changes(before, now, rostered, starters())
    }

    /** Drops the listing seen, so the next check only stores: alerts turned off and on again don't replay the gap. */
    public suspend fun forget() {
        withContext(Dispatchers.IO) { stateFile.delete() }
    }

    private suspend fun read(): Map<String, SeenStatus>? = withContext(Dispatchers.IO) {
        if (!stateFile.isFile) return@withContext null
        try {
            (Json.parseToJsonElement(stateFile.readText()) as JsonObject).mapNotNull { (id, e) ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val abbr = (o["abbr"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                id to SeenStatus(abbr, (o["status"] as? JsonPrimitive)?.content ?: abbr, (o["name"] as? JsonPrimitive)?.content ?: id)
            }.toMap()
        } catch (e: SerializationException) {
            null
        } catch (e: ClassCastException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    private suspend fun write(seen: Map<String, SeenStatus>) = withContext(Dispatchers.IO) {
        val text = buildJsonObject {
            for ((id, s) in seen) {
                put(id, buildJsonObject { put("abbr", s.abbr); put("status", s.status); put("name", s.name) })
            }
        }.toString()
        val tmp = File(stateFile.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(stateFile)) {
            stateFile.writeText(text)
            tmp.delete()
        }
    }
}

/** The ESPN lineup's starters: players in any slot but the bench and IR. */
public fun MyTeam.starterIds(): Set<String> =
    players.filter { it.slot != "BE" && it.slot != "IR" }.mapNotNullTo(HashSet()) { it.playerId }
