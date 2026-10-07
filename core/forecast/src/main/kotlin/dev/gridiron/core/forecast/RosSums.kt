package dev.gridiron.core.forecast

/**
 * Rest of season as the projector sums it: each player's total per metric (`player_ros_projection`) and each
 * remaining week's own projection (`player_ros_week`), so the phone can value byes and fantasy playoff weeks.
 */
internal class RosSums {
    /** (player, metric) to mean and variance summed over the remaining weeks. */
    val totals = HashMap<Pair<String, String>, DoubleArray>()

    /** (player, week, metric) to that week's mean and variance. */
    val weeks = HashMap<Triple<String, Int, String>, DoubleArray>()

    fun add(playerId: String, week: Int, metric: String, mean: Double, variance: Double) {
        val total = totals.getOrPut(playerId to metric) { DoubleArray(2) }
        total[0] += mean
        total[1] += variance
        val one = weeks.getOrPut(Triple(playerId, week, metric)) { DoubleArray(2) }
        one[0] += mean
        one[1] += variance
    }
}
