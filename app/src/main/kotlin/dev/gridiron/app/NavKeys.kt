package dev.gridiron.app

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object GridKey : NavKey
@Serializable data object CompareKey : NavKey
@Serializable data object ScoringListKey : NavKey
@Serializable data class ScoringEditKey(val profileId: String) : NavKey
@Serializable data class ProjectionsKey(val playerId: String, val season: Int, val week: Int) : NavKey
@Serializable data class AccuracyKey(val season: Int) : NavKey
@Serializable data class InjuriesKey(val season: Int) : NavKey
@Serializable data class DefenseKey(val season: Int) : NavKey
