package dev.gridiron.app

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object GridKey : NavKey
@Serializable data object CompareKey : NavKey
@Serializable data object ScoringListKey : NavKey
@Serializable data class ScoringEditKey(val profileId: String) : NavKey
