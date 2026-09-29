package dev.gridiron.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import dev.gridiron.core.model.BonusStat
import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.ESPN_POINTS_ALLOWED
import dev.gridiron.core.model.ESPN_YARDS_ALLOWED
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.Roster
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.ScoringRule
import dev.gridiron.core.model.ScoringTier
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.model.YardageBonus
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/*
 * The stored shape. Deliberately separate from the domain types: names are
 * plain strings so a file written by a newer build (a rule this build doesn't
 * know) still reads, dropping only what it can't understand.
 */

@Serializable
internal data class UserPrefsDto(
    val formatVersion: Int = 1,
    val profiles: List<ProfileDto> = emptyList(),
    val activeProfileId: String? = null,
    val tray: List<SlotDto> = emptyList(),
    val resetNotice: Boolean = false,
    val seasons: List<Int>? = null,
    val seasonsChosenIn: Int? = null,
    val rosters: List<RosterDto> = emptyList(),
    val oddsApiKey: String? = null,
    val gridPresets: List<PresetDto> = emptyList(),
)

@Serializable
internal data class ProfileDto(
    val id: String,
    val name: String,
    val weights: Map<String, Double> = emptyMap(),
    val receptionByPosition: Map<String, Double> = emptyMap(),
    val bonuses: List<BonusDto> = emptyList(),
    val basedOn: String? = null,
    val pointsAllowed: List<TierDto> = emptyList(),
    val yardsAllowed: List<TierDto> = emptyList(),
)

@Serializable
internal data class TierDto(val min: Int, val points: Double)

@Serializable
internal data class BonusDto(val stat: String, val min: Int, val maxExclusive: Int? = null, val points: Double)

@Serializable
internal data class SlotDto(val playerId: String, val season: Int, val firstWeek: Int, val lastWeek: Int)

@Serializable
internal data class RosterDto(val id: String, val name: String, val playerIds: List<String> = emptyList())

@Serializable
internal data class PresetDto(
    val id: String,
    val name: String,
    val packId: String,
    val sort: String,
    val direction: String,
    val position: String,
    val perGame: Boolean = false,
    val teams: List<String> = emptyList(),
    val minSnapShare: Double? = null,
    val filters: List<PresetFilterDto> = emptyList(),
    val weeks: PresetWeeksDto = PresetWeeksDto(),
)

@Serializable
internal data class PresetFilterDto(val column: String, val kind: String, val a: Double? = null, val b: Double? = null)

@Serializable
internal data class PresetWeeksDto(val kind: String = "WHOLE_SEASON", val n: Int? = null)

/**
 * 2: profiles carry points-allowed tiers, and version-1 profiles gain the kicking and team-defense defaults once.
 * 3: profiles carry yards-allowed tiers, and older profiles gain ESPN's once.
 */
internal const val FORMAT_VERSION = 3

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Runs [block], or returns null if it throws a validation error (an invalid stored entry). */
private inline fun <T> orNull(block: () -> T): T? = try {
    block()
} catch (_: IllegalArgumentException) {
    null
}

internal fun UserPrefsDto.toDomain(): UserPrefs {
    val migrating = formatVersion < 2
    val migratingYards = formatVersion < 3
    val profiles = profiles.mapNotNull { p ->
        orNull {
            val weights = p.weights.mapNotNull { (k, v) -> ScoringRule.entries.firstOrNull { it.name == k }?.let { it to v } }.toMap()
            ScoringProfile(
                id = p.id,
                name = p.name,
                // Version 1 predates kicking and team defense: give them the presets' values, once.
                weights = if (migrating) ScoringPresets.KICKING_AND_DEFENSE + weights else weights,
                receptionByPosition = p.receptionByPosition
                    .mapNotNull { (k, v) -> Position.fromCode(k)?.takeIf { it in ScoringProfile.RECEPTION_POSITIONS }?.let { it to v } }
                    .toMap(),
                yardageBonuses = p.bonuses.mapNotNull { b ->
                    val stat = BonusStat.entries.firstOrNull { it.name == b.stat } ?: return@mapNotNull null
                    orNull { YardageBonus(stat, b.min, b.maxExclusive, b.points) }
                },
                basedOn = p.basedOn,
                pointsAllowedTiers = if (migrating) ESPN_POINTS_ALLOWED else tiersOrNone(p.pointsAllowed),
                yardsAllowedTiers = if (migratingYards) ESPN_YARDS_ALLOWED else tiersOrNone(p.yardsAllowed),
            )
        }
    }
    val tray = tray.mapNotNull { s -> orNull { CompareSlot(s.playerId, s.season, WeekRange(s.firstWeek, s.lastWeek)) } }
    val choice = seasons?.let { s -> seasonsChosenIn?.let { SeasonChoice(s.distinct().sorted(), it) } }
    val rosters = rosters.mapNotNull { r -> orNull { Roster(r.id, r.name, r.playerIds.filter { it.isNotBlank() }.distinct()) } }
        .distinctBy { it.id }
    val presets = gridPresets.mapNotNull { p -> orNull { p.toDomain() } }
        .distinctBy { it.name.lowercase() }
        .take(MAX_PRESETS)
    return UserPrefs(
        profiles,
        activeProfileId ?: UserPrefs.DEFAULT.activeProfileId,
        tray,
        resetNotice,
        choice,
        rosters = rosters,
        oddsApiKey = oddsApiKey?.takeIf { it.isNotBlank() },
        gridPresets = presets,
    )
}

/** One stored preset; throws [IllegalArgumentException] when any part is invalid, so the caller drops the entry. */
private fun PresetDto.toDomain(): GridPreset = GridPreset(
    id = id,
    name = name.trim(),
    packId = packId,
    sort = sort,
    direction = direction,
    position = position,
    perGame = perGame,
    teams = teams.filter { it.isNotBlank() }.toSet(),
    minSnapShare = minSnapShare,
    filters = filters.map { f ->
        val kind = PresetFilterKind.entries.firstOrNull { it.name == f.kind } ?: throw IllegalArgumentException("unknown filter kind ${f.kind}")
        PresetFilter(f.column, kind, requireNotNull(f.a) { "a filter needs a value" }, f.b)
    },
    weeks = when (weeks.kind) {
        "WHOLE_SEASON" -> PresetWeeks.WholeSeason
        "LAST_N" -> PresetWeeks.LastN(requireNotNull(weeks.n) { "last-N weeks needs n" })
        else -> throw IllegalArgumentException("unknown weeks rule ${weeks.kind}")
    },
)

private fun GridPreset.toDto(): PresetDto = PresetDto(
    id = id,
    name = name,
    packId = packId,
    sort = sort,
    direction = direction,
    position = position,
    perGame = perGame,
    teams = teams.sorted(),
    minSnapShare = minSnapShare,
    filters = filters.map { PresetFilterDto(it.column, it.kind.name, it.a, it.b) },
    weeks = when (val w = weeks) {
        PresetWeeks.WholeSeason -> PresetWeeksDto("WHOLE_SEASON")
        is PresetWeeks.LastN -> PresetWeeksDto("LAST_N", w.n)
    },
)

/** A profile's stored tiers, or none when they don't start at 0 and rise: a bad list must not cost the whole profile. */
private fun tiersOrNone(stored: List<TierDto>): List<ScoringTier> {
    val tiers = stored.mapNotNull { orNull { ScoringTier(it.min, it.points) } }
    val valid = tiers.isEmpty() || (tiers.first().min == 0 && tiers.zipWithNext().all { (a, b) -> a.min < b.min })
    return if (valid && tiers.size == stored.size) tiers else emptyList()
}

internal fun UserPrefs.toDto(): UserPrefsDto = UserPrefsDto(
    formatVersion = FORMAT_VERSION,
    profiles = profiles.map { p ->
        ProfileDto(
            id = p.id,
            name = p.name,
            weights = p.weights.mapKeys { it.key.name },
            receptionByPosition = p.receptionByPosition.mapKeys { it.key.code },
            bonuses = p.yardageBonuses.map { BonusDto(it.stat.name, it.min, it.maxExclusive, it.points) },
            basedOn = p.basedOn,
            pointsAllowed = p.pointsAllowedTiers.map { TierDto(it.min, it.points) },
            yardsAllowed = p.yardsAllowedTiers.map { TierDto(it.min, it.points) },
        )
    },
    activeProfileId = activeProfileId,
    tray = tray.map { SlotDto(it.playerId, it.season, it.weeks.first, it.weeks.last) },
    resetNotice = resetNotice,
    seasons = seasons?.seasons,
    seasonsChosenIn = seasons?.chosenIn,
    rosters = rosters.map { RosterDto(it.id, it.name, it.playerIds) },
    oddsApiKey = oddsApiKey,
    gridPresets = gridPresets.map { it.toDto() },
)

internal object UserPrefsSerializer : Serializer<UserPrefs> {
    override val defaultValue: UserPrefs get() = UserPrefs.DEFAULT

    override suspend fun readFrom(input: InputStream): UserPrefs = try {
        json.decodeFromString(UserPrefsDto.serializer(), input.readBytes().decodeToString()).toDomain()
    } catch (e: SerializationException) {
        throw CorruptionException("unreadable user preferences", e)
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("unreadable user preferences", e)
    }

    override suspend fun writeTo(t: UserPrefs, output: OutputStream) {
        output.write(json.encodeToString(UserPrefsDto.serializer(), t.toDto()).encodeToByteArray())
    }
}
