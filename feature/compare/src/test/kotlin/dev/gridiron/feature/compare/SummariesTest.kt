package dev.gridiron.feature.compare

import dev.gridiron.core.data.RadarUi
import dev.gridiron.core.data.ScatterPointUi
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Test

class SummariesTest {
    @Test
    fun radarSummaryCountsAxesWon() {
        val radar = RadarUi(
            persistentListOf("TGT%", "AY%", "aDOT", "RACR", "YAC", "RZ TGT", "FPOE"),
            persistentListOf(
                persistentListOf(0.9f, 0.8f, 0.6f, 0.7f, 0.6f, 0.9f, 0.5f),
                persistentListOf(0.5f, 0.6f, 0.4f, 0.3f, 0.2f, 0.95f, null),
            ),
        )
        assertEquals("Radar: Nacua higher on 5 of 7 axes than Adams", radarSummary(radar, listOf("Nacua", "Adams"), 0, 1))
    }

    @Test
    fun scatterSummaryDescribesEachComparedPlayer() {
        val s = scatterSummary(
            listOf(ScatterPointUi("a", "Nacua", 14.0, 17.2), ScatterPointUi("b", "Adams", 13.1, 11.0)),
            population = 88,
        )
        assertEquals(
            "Expected versus actual fantasy points per game for 88 players. " +
                "Nacua: 17.2 per game, 3.2 above expected. Adams: 11.0 per game, 2.1 below expected.",
            s,
        )
    }
}
