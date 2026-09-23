package dev.gridiron.core.data

import dev.gridiron.core.datastore.PrefsSource
import dev.gridiron.core.datastore.UserPrefs
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringProfile
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID

/** Scoring profiles: the three presets, plus the user's own. */
public class ScoringRepository(
    private val prefs: PrefsSource,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    public val profiles: Flow<ImmutableList<ScoringProfile>> =
        prefs.prefs.map { (ScoringPresets.all + it.profiles).toImmutableList() }.distinctUntilChanged()

    public val active: Flow<ScoringProfile> = prefs.prefs.map { it.active }.distinctUntilChanged()

    public val resetNotice: Flow<Boolean> = prefs.prefs.map { it.resetNotice }.distinctUntilChanged()

    public suspend fun setActive(id: String) {
        prefs.update { p -> if (p.find(id) != null) p.copy(activeProfileId = id) else p }
    }

    /** Copies [sourceId] under [name]; the copy remembers the preset it descends from. */
    public suspend fun duplicate(sourceId: String, name: String): ScoringProfile {
        var created: ScoringProfile? = null
        prefs.update { p ->
            val source = p.find(sourceId) ?: return@update p
            val copy = source.copy(
                id = newId(),
                name = name,
                basedOn = source.basedOn ?: sourceId.takeIf { ScoringPresets.byId(it) != null },
            )
            created = copy
            p.copy(profiles = p.profiles + copy)
        }
        return checkNotNull(created) { "no profile $sourceId" }
    }

    public suspend fun save(profile: ScoringProfile) {
        require(!profile.isPreset) { "presets can't be changed; duplicate one first" }
        prefs.update { p ->
            val exists = p.profiles.any { it.id == profile.id }
            p.copy(profiles = if (exists) p.profiles.map { if (it.id == profile.id) profile else it } else p.profiles + profile)
        }
    }

    public suspend fun delete(id: String) {
        if (ScoringPresets.byId(id) != null) return
        prefs.update { p ->
            val rest = p.profiles.filterNot { it.id == id }
            val active = if (p.activeProfileId == id) rest.firstOrNull()?.id ?: ScoringPresets.PPR.id else p.activeProfileId
            p.copy(profiles = rest, activeProfileId = active)
        }
    }

    public suspend fun resetToPreset(id: String) {
        prefs.update { p ->
            p.copy(
                profiles = p.profiles.map { profile ->
                    val preset = profile.basedOn?.let(ScoringPresets::byId)
                    if (profile.id != id || preset == null) {
                        profile
                    } else {
                        preset.copy(id = profile.id, name = profile.name, basedOn = profile.basedOn)
                    }
                },
            )
        }
    }

    public suspend fun dismissResetNotice() {
        prefs.update { it.copy(resetNotice = false) }
    }

    private fun UserPrefs.find(id: String): ScoringProfile? = ScoringPresets.byId(id) ?: profiles.firstOrNull { it.id == id }
}
