package dev.gridiron.app

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Test

/** A double tap must open one screen, not two stacked copies. */
class BackStackPushTest {
    @Test
    fun aSecondPushOfTheTopKeyIsIgnored() {
        val stack = mutableListOf<NavKey>(GridKey)
        stack.push(CompareKey)
        stack.push(CompareKey)
        assertEquals(listOf(GridKey, CompareKey), stack)
    }

    @Test
    fun aDifferentKeyStillPushes() {
        val stack = mutableListOf<NavKey>(GridKey, CompareKey)
        stack.push(ScoringListKey)
        stack.push(ScoringEditKey("user:1"))
        stack.push(ScoringEditKey("user:1"))
        assertEquals(listOf(GridKey, CompareKey, ScoringListKey, ScoringEditKey("user:1")), stack)
    }
}
