package dev.gridiron.feature.scoring

import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.model.ScoringPresets
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
import org.junit.Before
import org.junit.Test

/** The scoring profile list's ViewModel, on virtual time with an in-memory prefs store. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScoringListViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val prefs = FakePrefsSource()
    private var n = 0
    private val repo = ScoringRepository(prefs) { "u${++n}" }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun duplicatingOpensTheCopyForEditing() = runTest(dispatcher) {
        val vm = ScoringListViewModel(repo)
        advanceUntilIdle()
        vm.onEvent(ListEvent.Duplicate(ScoringPresets.HALF_PPR.id))
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals("u1", s.editRequest)
        assertEquals("Half PPR copy", prefs.current.profiles.single().name)
        vm.onEvent(ListEvent.EditOpened)
        assertNull(vm.state.value.editRequest)
    }

    @Test
    fun deletingTheActiveProfileFallsBackToPpr() = runTest(dispatcher) {
        repo.duplicate(ScoringPresets.PPR.id, "Mine")
        repo.setActive("u1")
        val vm = ScoringListViewModel(repo)
        advanceUntilIdle()
        vm.onEvent(ListEvent.Delete("u1"))
        advanceUntilIdle()
        assertEquals(ScoringPresets.PPR.id, vm.state.value.activeId)
    }
}
