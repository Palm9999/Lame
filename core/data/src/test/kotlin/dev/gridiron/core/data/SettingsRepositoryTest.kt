package dev.gridiron.core.data

import dev.gridiron.core.datastore.SeasonChoice
import dev.gridiron.core.datastore.UserPrefs
import dev.gridiron.core.testing.FakePrefsSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsRepositoryTest {
    private var current = 2026

    private fun repo(choice: SeasonChoice? = null): Pair<SettingsRepository, FakePrefsSource> {
        val prefs = FakePrefsSource(UserPrefs.DEFAULT.copy(seasons = choice))
        return SettingsRepository(prefs) { current } to prefs
    }

    @Test
    fun `the default is the current season and the two before it, and rolls forward`() = runTest {
        val (settings, _) = repo()
        assertEquals(listOf(2024, 2025, 2026), settings.seasons.first())
        current = 2027
        assertEquals(listOf(2025, 2026, 2027), settings.seasons.first())
    }

    @Test
    fun `choices run from 2012 through the current season`() {
        val (settings, _) = repo()
        assertEquals(2012, settings.choices.first())
        assertEquals(2026, settings.choices.last())
        assertEquals(15, settings.choices.size)
    }

    @Test
    fun `checking and unchecking saves the choice`() = runTest {
        val (settings, prefs) = repo()
        assertTrue(settings.setSelected(2012, true))
        assertTrue(settings.setSelected(2025, false))
        assertEquals(listOf(2012, 2024, 2026), settings.seasons.first())
        assertEquals(SeasonChoice(listOf(2012, 2024, 2026), 2026), prefs.current.seasons)
    }

    @Test
    fun `the last season can't be unchecked`() = runTest {
        val (settings, prefs) = repo(SeasonChoice(listOf(2026), 2026))
        assertFalse(settings.setSelected(2026, false))
        assertEquals(listOf(2026), settings.seasons.first())
        assertEquals(SeasonChoice(listOf(2026), 2026), prefs.current.seasons)
    }

    @Test
    fun `a choice that included the current season follows it into the next`() = runTest {
        val (settings, _) = repo(SeasonChoice(listOf(2024, 2026), 2026))
        current = 2028
        assertEquals(listOf(2024, 2026, 2027, 2028), settings.seasons.first())
    }

    @Test
    fun `a choice of past seasons only stays exactly as chosen`() = runTest {
        val (settings, _) = repo(SeasonChoice(listOf(2015, 2016), 2026))
        current = 2027
        assertEquals(listOf(2015, 2016), settings.seasons.first())
    }

    @Test
    fun `seasons outside the range are dropped, and an empty result falls back to the default`() = runTest {
        val (settings, _) = repo(SeasonChoice(listOf(2030), 2030))
        assertEquals(listOf(2024, 2025, 2026), settings.seasons.first())
    }
}
