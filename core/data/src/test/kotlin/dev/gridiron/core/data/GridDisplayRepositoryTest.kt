package dev.gridiron.core.data

import dev.gridiron.core.datastore.RowDensity
import dev.gridiron.core.testing.FakePrefsSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GridDisplayRepositoryTest {
    private val prefs = FakePrefsSource()
    private val repo = GridDisplayRepository(prefs)

    @Test
    fun `density starts COMFORTABLE`() = runTest {
        assertEquals(RowDensity.COMFORTABLE, repo.density.first())
    }

    @Test
    fun `setDensity is read back through the flow`() = runTest {
        repo.setDensity(RowDensity.COMPACT)
        assertEquals(RowDensity.COMPACT, repo.density.first())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `the flow doesn't re-emit an unchanged value`() = runTest {
        val seen = mutableListOf<RowDensity>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { repo.density.toList(seen) }
        repo.setDensity(RowDensity.COMFORTABLE)
        repo.setDensity(RowDensity.COMPACT)
        repo.setDensity(RowDensity.COMPACT)
        job.cancel()
        assertEquals(listOf(RowDensity.COMFORTABLE, RowDensity.COMPACT), seen)
    }
}
