package dev.gridiron.feature.players

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromeScrollStateTest {
    private val hideAfter = 24f
    private val state = ChromeScrollState(hideAfter).also { it.heightPx = 100f }

    private fun scroll(dy: Float) = state.connection.onPreScroll(Offset(0f, dy), NestedScrollSource.UserInput)

    @Test
    fun `hides after 24 dp down`() {
        assertEquals(0f, state.offsetPx.value, 0f)
        scroll(-10f)
        scroll(-10f)
        assertFalse("20 of 24 is not enough", state.hidden)
        scroll(-5f)
        assertTrue(state.hidden)
        assertEquals(-100f, state.offsetPx.value, 0f)
    }

    @Test
    fun `any up scroll shows`() {
        scroll(-40f)
        assertTrue(state.hidden)
        scroll(1f)
        assertFalse(state.hidden)
        assertEquals(0f, state.offsetPx.value, 0f)
    }

    @Test
    fun `never consumes`() {
        assertEquals(Offset.Zero, scroll(-40f))
        assertEquals(Offset.Zero, scroll(40f))
    }

    @Test
    fun `travel toward the top resets the count`() {
        scroll(-20f)
        scroll(5f)
        scroll(-20f)
        assertFalse(state.hidden)
    }

    @Test
    fun `show forces it back`() {
        scroll(-40f)
        state.show()
        assertFalse(state.hidden)
    }
}
