package dev.gridiron.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import dev.gridiron.core.model.BonusStat
import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.ScoringRule
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
    val formatVersion: Int = FORMAT_VERSION,
    val profiles: List<ProfileDto> = emptyList(),
    val activeProfileId: String? = null,
    val tray: List<SlotDto> = emptyList(),
    val resetNotice: Boolean = false,
)

@Serializable
internal data class ProfileDto(
    val id: String,
    val name: String,
    val weights: Map<String, Double> = emptyMap(),
    val receptionByPosition: Map<String, Double> = emptyMap(),
    val bonuses: List<BonusDto> = emptyList(),
    val basedOn: String? = null,
)

@Serializable
internal data class BonusDto(val stat: String, val min: Int, val maxExclusive: Int? = null, val points: Double)

@Serializable
internal data class SlotDto(val playerId: String, val season: Int, val firstWeek: Int, val lastWeek: Int)

internal const val FORMAT_VERSION = 1

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
    val profiles = profiles.mapNotNull { p ->
        orNull {
            ScoringProfile(
                id = p.id,
                name = p.name,
                weights = p.weights.mapNotNull { (k, v) -> ScoringRule.entries.firstOrNull { it.name == k }?.let { it to v } }.toMap(),
                receptionByPosition = p.receptionByPosition
                    .mapNotNull { (k, v) -> Position.fromCode(k)?.takeIf { it in ScoringProfile.RECEPTION_POSITIONS }?.let { it to v } }
                    .toMap(),
                yardageBonuses = p.bonuses.mapNotNull { b ->
                    val stat = BonusStat.entries.firstOrNull { it.name == b.stat } ?: return@mapNotNull null
                    orNull { YardageBonus(stat, b.min, b.maxExclusive, b.points) }
                },
                basedOn = p.basedOn,
            )
        }
    }
    val tray = tray.mapNotNull { s -> orNull { CompareSlot(s.playerId, s.season, WeekRange(s.firstWeek, s.lastWeek)) } }
    return UserPrefs(profiles, activeProfileId ?: UserPrefs.DEFAULT.activeProfileId, tray, resetNotice)
}

internal fun UserPrefs.toDto(): UserPrefsDto = UserPrefsDto(
    profiles = profiles.map { p ->
        ProfileDto(
            id = p.id,
            name = p.name,
            weights = p.weights.mapKeys { it.key.name },
            receptionByPosition = p.receptionByPosition.mapKeys { it.key.code },
            bonuses = p.yardageBonuses.map { BonusDto(it.stat.name, it.min, it.maxExclusive, it.points) },
            basedOn = p.basedOn,
        )
    },
    activeProfileId = activeProfileId,
    tray = tray.map { SlotDto(it.playerId, it.season, it.weeks.first, it.weeks.last) },
    resetNotice = resetNotice,
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
