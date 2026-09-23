package dev.gridiron.core.charts

import androidx.compose.ui.geometry.Offset
import kotlin.math.ceil
import kotlin.math.floor

/**
 * One scale for both axes, so the y = x line is a true diagonal and "above the
 * line" means the same thing everywhere on the chart.
 */
internal object ScatterScale {
    private const val STEP = 5f

    fun bounds(points: List<ScatterPoint>): ClosedFloatingPointRange<Float> {
        if (points.isEmpty()) return 0f..20f
        val lo = minOf(0f, points.minOf { minOf(it.x, it.y) })
        val hi = points.maxOf { maxOf(it.x, it.y) }
        val min = floor(lo / STEP) * STEP
        val max = maxOf(ceil(hi / STEP) * STEP, min + STEP)
        return min..max
    }

    fun ticks(bounds: ClosedFloatingPointRange<Float>): List<Float> {
        val step = if (bounds.endInclusive - bounds.start > 30f) 10f else STEP
        val first = ceil(bounds.start / step) * step
        return generateSequence(first) { it + step }.takeWhile { it <= bounds.endInclusive + 1e-3f }.toList()
    }

    fun nearest(points: List<ScatterPoint>, toScreen: (ScatterPoint) -> Offset, at: Offset, radiusPx: Float): ScatterPoint? =
        points.map { it to (toScreen(it) - at).getDistance() }
            .filter { it.second <= radiusPx }
            .minByOrNull { it.second }
            ?.first
}
