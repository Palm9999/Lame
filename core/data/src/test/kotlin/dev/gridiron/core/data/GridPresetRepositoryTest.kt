package dev.gridiron.core.data

import dev.gridiron.core.datastore.GridPreset
import dev.gridiron.core.datastore.MAX_PRESETS
import dev.gridiron.core.datastore.PresetFilter
import dev.gridiron.core.datastore.PresetFilterKind
import dev.gridiron.core.datastore.PresetWeeks
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.Condition
import dev.gridiron.core.statquery.Direction
import dev.gridiron.core.statquery.Filter
import dev.gridiron.core.statquery.StatColumn
import dev.gridiron.core.testing.FakePrefsSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GridPresetRepositoryTest {
    private var next = 0
    private val repo = GridPresetRepository(FakePrefsSource()) { "g${++next}" }

    private val scoring = ScoringPresets.PPR.copy(id = "u1", name = "League")
    private val open = GridRequest(
        season = SeasonInfo(2025, lastWeek = 22),
        weeks = WeekRange(1, 18),
        pack = StatPack.FANTASY,
        positions = PositionFilter.WR,
        sort = StatColumn.TARGETS,
        direction = Direction.DESCENDING,
        perGame = true,
        name = "mahomes",
        scoring = scoring,
        teams = setOf("KC"),
        minSnapShare = 0.5,
        filters = listOf(Filter(StatColumn.TARGETS, Condition.AtLeast(20.0)), Filter(StatColumn.SNAP_SHARE, Condition.Between(0.5, 0.9))),
        onlyPlayers = setOf("p1"),
    )

    private suspend fun all() = repo.presets.first()

    @Test
    fun `save stores the view and trims the name`() = runTest {
        val saved = repo.save("  Buy-low WRs ", open, PresetWeeks.LastN(4))
        assertEquals(
            GridPreset(
                "g1", "Buy-low WRs", "FANTASY", "TARGETS", "DESCENDING", "WR", true, setOf("KC"), 0.5,
                listOf(
                    PresetFilter("TARGETS", PresetFilterKind.AT_LEAST, 20.0),
                    PresetFilter("SNAP_SHARE", PresetFilterKind.BETWEEN, 0.5, 0.9),
                ),
                PresetWeeks.LastN(4),
            ),
            saved,
        )
        assertEquals(listOf(saved), all())
    }

    @Test
    fun `every filter condition survives save and resolve`() = runTest {
        val conditions = listOf(Condition.AtLeast(1.0), Condition.AtMost(2.0), Condition.GreaterThan(3.0), Condition.LessThan(4.0), Condition.Between(5.0, 6.0))
        val request = open.copy(filters = conditions.map { Filter(StatColumn.TARGETS, it) })
        val saved = repo.save("All kinds", request, PresetWeeks.WholeSeason)
        val ready = repo.resolve(saved, open) as Resolved.Ready
        assertEquals(request.filters, ready.request.filters)
    }

    @Test
    fun `a blank or long name is refused`() = runTest {
        assertThrows<IllegalArgumentException> { repo.save(" ", open, PresetWeeks.WholeSeason) }
        assertThrows<IllegalArgumentException> { repo.save("x".repeat(41), open, PresetWeeks.WholeSeason) }
        assertEquals(emptyList<GridPreset>(), all())
    }

    @Test
    fun `save with a used name in any case throws PresetNameTaken`() = runTest {
        repo.save("Deep", open, PresetWeeks.WholeSeason)
        val taken = assertThrows<PresetNameTaken> { repo.save("dEEp ", open, PresetWeeks.WholeSeason) }
        assertEquals("g1", taken.existingId)
        assertEquals(1, all().size)
    }

    @Test
    fun `overwrite keeps id and name`() = runTest {
        repo.save("Deep", open, PresetWeeks.WholeSeason)
        repo.overwrite("g1", open.copy(sort = StatColumn.CARRIES, positions = PositionFilter.RB), PresetWeeks.LastN(2))
        val p = all().single()
        assertEquals("g1", p.id)
        assertEquals("Deep", p.name)
        assertEquals("CARRIES", p.sort)
        assertEquals("RB", p.position)
        assertEquals(PresetWeeks.LastN(2), p.weeks)
    }

    @Test
    fun `rename trims, and a name used by another preset throws`() = runTest {
        repo.save("One", open, PresetWeeks.WholeSeason)
        repo.save("Two", open, PresetWeeks.WholeSeason)
        repo.rename("g1", "  Uno ")
        assertEquals(listOf("Uno", "Two"), all().map { it.name })
        assertThrows<PresetNameTaken> { repo.rename("g1", "two") }
        repo.rename("g1", "UNO") // its own name in another case is fine
        assertEquals("UNO", all().first().name)
        assertThrows<IllegalArgumentException> { repo.rename("g1", " ") }
    }

    @Test
    fun `delete removes, and restore puts it back`() = runTest {
        val one = repo.save("One", open, PresetWeeks.WholeSeason)
        repo.save("Two", open, PresetWeeks.WholeSeason)
        repo.delete("g1")
        assertEquals(listOf("Two"), all().map { it.name })
        repo.restore(one)
        assertEquals(listOf("Two", "One"), all().map { it.name })
        repo.restore(one) // already there: nothing changes
        assertEquals(2, all().size)
    }

    @Test
    fun `the 31st save throws PresetLimitReached`() = runTest {
        repeat(MAX_PRESETS) { repo.save("View $it", open, PresetWeeks.WholeSeason) }
        assertThrows<PresetLimitReached> { repo.save("One too many", open, PresetWeeks.WholeSeason) }
        assertEquals(MAX_PRESETS, all().size)
    }

    @Test
    fun `two saves at 29 end at 30`() = runTest {
        repeat(MAX_PRESETS - 1) { repo.save("View $it", open, PresetWeeks.WholeSeason) }
        repo.save("Thirtieth", open, PresetWeeks.WholeSeason)
        runCatching { repo.save("Thirty-first", open, PresetWeeks.WholeSeason) }
        assertEquals(MAX_PRESETS, all().size)
    }

    private fun stored(weeks: PresetWeeks, packId: String = "FANTASY", sort: String = "TARGETS", position: String = "WR", column: String = "TARGETS") =
        GridPreset(
            "g9", "Stored", packId, sort, "DESCENDING", position, false, emptySet(), null,
            listOf(PresetFilter(column, PresetFilterKind.AT_LEAST, 5.0)), weeks,
        )

    private fun ready(preset: GridPreset, base: GridRequest = open) = (repo.resolve(preset, base) as Resolved.Ready).request

    @Test
    fun `resolve WholeSeason uses the season's default weeks`() {
        assertEquals(WeekRange(1, 18), ready(stored(PresetWeeks.WholeSeason)).weeks)
        assertEquals(WeekRange(1, 8), ready(stored(PresetWeeks.WholeSeason), open.copy(season = SeasonInfo(2025, 8))).weeks)
    }

    @Test
    fun `resolve LastN counts back from the last played week`() {
        val inSeason = open.copy(season = SeasonInfo(2025, 8), weeks = WeekRange(1, 8))
        assertEquals(WeekRange(5, 8), ready(stored(PresetWeeks.LastN(4)), inSeason).weeks)
        assertEquals(WeekRange(5, 18), ready(stored(PresetWeeks.LastN(14))).weeks)
    }

    @Test
    fun `resolve LastN larger than the played weeks clamps to week 1`() {
        val week3 = open.copy(season = SeasonInfo(2025, 3), weeks = WeekRange(1, 3))
        assertEquals(WeekRange(1, 3), ready(stored(PresetWeeks.LastN(8)), week3).weeks)
        assertEquals(WeekRange(1, 18), ready(stored(PresetWeeks.LastN(22))).weeks)
    }

    @Test
    fun `resolve clears the name search and keeps season, scoring and roster`() {
        val request = ready(stored(PresetWeeks.WholeSeason))
        assertEquals("", request.name)
        assertEquals(open.season, request.season)
        assertEquals(scoring, request.scoring)
        assertEquals(setOf("p1"), request.onlyPlayers)
    }

    @Test
    fun `resolve replaces the view with the preset's`() {
        val preset = stored(PresetWeeks.WholeSeason, sort = "CARRIES", position = "RB").copy(direction = "ASCENDING", perGame = false, teams = setOf("BUF"), minSnapShare = 0.25)
        val request = ready(preset)
        assertEquals(StatColumn.CARRIES, request.sort)
        assertEquals(Direction.ASCENDING, request.direction)
        assertEquals(PositionFilter.RB, request.positions)
        assertEquals(false, request.perGame)
        assertEquals(setOf("BUF"), request.teams)
        assertEquals(0.25, request.minSnapShare)
        assertEquals(listOf(Filter(StatColumn.TARGETS, Condition.AtLeast(5.0))), request.filters)
    }

    private fun unavailable(preset: GridPreset): String = (repo.resolve(preset, open) as Resolved.Unavailable).reason

    @Test
    fun `resolve with an unknown pack is Unavailable`() {
        assertTrue(unavailable(stored(PresetWeeks.WholeSeason, packId = "GONE")).isNotBlank())
    }

    @Test
    fun `resolve with an unknown filter or sort column is Unavailable`() {
        assertTrue(unavailable(stored(PresetWeeks.WholeSeason, column = "GONE")).isNotBlank())
        assertTrue(unavailable(stored(PresetWeeks.WholeSeason, sort = "GONE")).isNotBlank())
    }

    @Test
    fun `resolve with a sort outside the pack is Unavailable`() {
        assertTrue(unavailable(stored(PresetWeeks.WholeSeason, sort = "PASSING_YARDS")).isNotBlank())
    }

    @Test
    fun `resolve with a position the pack does not offer is Unavailable`() {
        assertTrue(unavailable(stored(PresetWeeks.WholeSeason, position = "K")).isNotBlank())
        assertTrue(unavailable(stored(PresetWeeks.WholeSeason, position = "GONE")).isNotBlank())
    }

    @Test
    fun `weeksRule is WholeSeason for the default range, LastN for one ending at the last played week, else WholeSeason`() {
        val season = SeasonInfo(2025, 8)
        val r = open.copy(season = season)
        assertEquals(PresetWeeks.WholeSeason, repo.weeksRule(r.copy(weeks = WeekRange(1, 8))))
        assertEquals(PresetWeeks.LastN(4), repo.weeksRule(r.copy(weeks = WeekRange(5, 8))))
        assertEquals(PresetWeeks.WholeSeason, repo.weeksRule(r.copy(weeks = WeekRange(2, 5))))
    }
}
