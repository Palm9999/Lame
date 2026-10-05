package dev.gridiron.app

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object GridKey : NavKey
@Serializable data object CompareKey : NavKey
@Serializable data object ScoringListKey : NavKey
@Serializable data class ScoringEditKey(val profileId: String) : NavKey
@Serializable data class ProjectionsKey(val playerId: String, val season: Int, val week: Int) : NavKey
@Serializable data class ProjectionListKey(val season: Int) : NavKey
@Serializable data class OpportunitiesKey(val season: Int) : NavKey
@Serializable data class BreakoutsKey(val season: Int) : NavKey
@Serializable data class WaiverTrendsKey(val season: Int) : NavKey
@Serializable data class DynastyKey(val season: Int) : NavKey
@Serializable data class HistoryKey(val season: Int) : NavKey
@Serializable data class ActivityKey(val season: Int) : NavKey
@Serializable data class AccuracyKey(val season: Int) : NavKey
@Serializable data class InjuriesKey(val season: Int) : NavKey
@Serializable data class DefenseKey(val season: Int) : NavKey
@Serializable data object SettingsKey : NavKey
@Serializable data object RostersKey : NavKey
@Serializable data object DraftKey : NavKey
@Serializable data object LeagueKey : NavKey
@Serializable data class MatchupsKey(val season: Int) : NavKey
@Serializable data object NewsKey : NavKey
@Serializable data class ScoresKey(val season: Int) : NavKey
@Serializable data class GameKey(val season: Int, val week: Int, val home: String, val away: String) : NavKey
@Serializable data class PlayerKey(val playerId: String) : NavKey
@Serializable data object MoreKey : NavKey
