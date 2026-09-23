package dev.gridiron.core.datastore

import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringProfile

/**
 * Everything the user sets up, in one small document.
 *
 * @property profiles User profiles only; presets live in code and are never stored.
 * @property resetNotice Set when an unreadable file was replaced with defaults,
 *   so the app can say so once; cleared when the notice has been shown.
 */
public data class UserPrefs(
    val profiles: List<ScoringProfile>,
    val activeProfileId: String,
    val tray: List<CompareSlot>,
    val resetNotice: Boolean = false,
) {
    /** The active profile, falling back to PPR if its id no longer exists. */
    public val active: ScoringProfile
        get() = ScoringPresets.byId(activeProfileId)
            ?: profiles.firstOrNull { it.id == activeProfileId }
            ?: ScoringPresets.PPR

    public companion object {
        public val DEFAULT: UserPrefs = UserPrefs(emptyList(), ScoringPresets.PPR.id, emptyList())
    }
}
