package dev.gridiron.feature.scoring

import dev.gridiron.core.model.BonusStat
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringRule
import dev.gridiron.core.model.YardageBonus
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.testing.FakePrefsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The scoring editor's ViewModel, on virtual time with an in-memory prefs store. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScoringEditViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val prefs = FakePrefsSource()
    private val repo = ScoringRepository(prefs) { "u1" }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun editingAWeightAndSavingPersistsIt() = runTest(dispatcher) {
        repo.duplicate(ScoringPresets.PPR.id, "Mine")
        val vm = ScoringEditViewModel("u1", repo)
        advanceUntilIdle()
        vm.onEvent(EditEvent.WeightChanged(ScoringRule.PASS_TD, "6"))
        vm.onEvent(EditEvent.ReceptionChanged(Position.TE, "1,5"))
        vm.onEvent(EditEvent.BonusAdded)
        val key = (vm.state.value as EditState.Editing).bonuses.single().key
        vm.onEvent(EditEvent.BonusChanged(key, BonusDraft(key, BonusStat.RUSHING_YARDS, "100", "200", "3")))
        vm.onEvent(EditEvent.Save)
        advanceUntilIdle()
        val saved = prefs.current.profiles.single()
        assertEquals(6.0, saved.weight(ScoringRule.PASS_TD), 0.0)
        assertEquals(1.5, saved.receptionWeight(Position.TE), 0.0)
        assertEquals(listOf(YardageBonus(BonusStat.RUSHING_YARDS, 100, 200, 3.0)), saved.yardageBonuses)
        assertTrue((vm.state.value as EditState.Editing).saved)
    }

    @Test
    fun invalidInputBlocksSavingAndNamesTheField() = runTest(dispatcher) {
        repo.duplicate(ScoringPresets.PPR.id, "Mine")
        val vm = ScoringEditViewModel("u1", repo)
        advanceUntilIdle()
        vm.onEvent(EditEvent.WeightChanged(ScoringRule.PASS_TD, "-"))
        val s = vm.state.value as EditState.Editing
        assertNull(s.profile)
        assertEquals("Enter a number", s.errors[FieldKey.Weight(ScoringRule.PASS_TD)])
        vm.onEvent(EditEvent.Save)
        advanceUntilIdle()
        assertEquals(4.0, prefs.current.profiles.single().weight(ScoringRule.PASS_TD), 0.0)
    }

    @Test
    fun blankWeightMeansZeroAndBlankReceptionMeansUseTheBaseRule() = runTest(dispatcher) {
        repo.duplicate(ScoringPresets.PPR.id, "Mine")
        val vm = ScoringEditViewModel("u1", repo)
        advanceUntilIdle()
        vm.onEvent(EditEvent.WeightChanged(ScoringRule.PASS_TD, ""))
        vm.onEvent(EditEvent.ReceptionChanged(Position.TE, ""))
        val p = (vm.state.value as EditState.Editing).profile!!
        assertEquals(0.0, p.weight(ScoringRule.PASS_TD), 0.0)
        assertEquals(emptyMap<Position, Double>(), p.receptionByPosition)
    }

    @Test
    fun anEmptyBonusRangeIsAnError() = runTest(dispatcher) {
        repo.duplicate(ScoringPresets.PPR.id, "Mine")
        val vm = ScoringEditViewModel("u1", repo)
        advanceUntilIdle()
        vm.onEvent(EditEvent.BonusAdded)
        val key = (vm.state.value as EditState.Editing).bonuses.single().key
        vm.onEvent(EditEvent.BonusChanged(key, BonusDraft(key, BonusStat.PASSING_YARDS, "300", "300", "3")))
        val s = vm.state.value as EditState.Editing
        assertNull(s.profile)
        assertEquals("Must be above the minimum", s.errors[FieldKey.BonusMax(key)])
    }

    @Test
    fun aBonusMinimumStartsAtOneYard() = runTest(dispatcher) {
        // A 0-yard minimum would match every player with any scoring stat that
        // week but not one with none, so the editor asks for at least 1.
        repo.duplicate(ScoringPresets.PPR.id, "Mine")
        val vm = ScoringEditViewModel("u1", repo)
        advanceUntilIdle()
        vm.onEvent(EditEvent.BonusAdded)
        val key = (vm.state.value as EditState.Editing).bonuses.single().key
        vm.onEvent(EditEvent.BonusChanged(key, BonusDraft(key, BonusStat.RUSHING_YARDS, "0", "50", "1")))
        val zero = vm.state.value as EditState.Editing
        assertNull(zero.profile)
        assertEquals("Whole yards, 1–1000", zero.errors[FieldKey.BonusMin(key)])
        vm.onEvent(EditEvent.BonusChanged(key, BonusDraft(key, BonusStat.RUSHING_YARDS, "1", "50", "1")))
        val one = vm.state.value as EditState.Editing
        assertEquals(listOf(YardageBonus(BonusStat.RUSHING_YARDS, 1, 50, 1.0)), one.profile!!.yardageBonuses)
    }

    @Test
    fun presetsOpenReadOnly() = runTest(dispatcher) {
        val vm = ScoringEditViewModel(ScoringPresets.PPR.id, repo)
        advanceUntilIdle()
        assertTrue((vm.state.value as EditState.Editing).readOnly)
    }

    @Test
    fun resetToPresetRestoresTheFields() = runTest(dispatcher) {
        repo.duplicate(ScoringPresets.PPR.id, "Mine")
        val vm = ScoringEditViewModel("u1", repo)
        advanceUntilIdle()
        vm.onEvent(EditEvent.WeightChanged(ScoringRule.PASS_TD, "6"))
        vm.onEvent(EditEvent.ResetToPreset)
        assertEquals("4", (vm.state.value as EditState.Editing).weights.getValue(ScoringRule.PASS_TD))
    }

    @Test
    fun weightFieldNeverSavesANonFiniteNumber() = runTest(dispatcher) {
        repo.duplicate(ScoringPresets.PPR.id, "Mine")
        val vm = ScoringEditViewModel("u1", repo)
        advanceUntilIdle()
        for (bad in listOf("", "-", ".", "1,5", "1e3", "NaN", "99999")) {
            vm.onEvent(EditEvent.WeightChanged(ScoringRule.PASS_TD, bad))
            vm.onEvent(EditEvent.Save)
            advanceUntilIdle()
            val saved = prefs.current.profiles.single().weight(ScoringRule.PASS_TD)
            assertTrue("$bad produced a non-finite weight $saved", saved.isFinite())
        }
        // "1,5" is the one value in that list that is a valid number (1.5, comma decimal).
        vm.onEvent(EditEvent.WeightChanged(ScoringRule.PASS_TD, "1,5"))
        vm.onEvent(EditEvent.Save)
        advanceUntilIdle()
        assertEquals(1.5, prefs.current.profiles.single().weight(ScoringRule.PASS_TD), 0.0)
    }
}
