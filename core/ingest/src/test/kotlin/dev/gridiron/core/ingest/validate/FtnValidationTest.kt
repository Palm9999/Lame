package dev.gridiron.core.ingest.validate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FtnValidationTest {
    @Test
    fun `coverage under 90 percent warns once, naming the season and the share`() {
        val warning = ftnCoverageWarning(2025, attempts = 200, covered = 150)
        assertNotNull(warning)
        assertTrue("2025" in warning!! && "75%" in warning && "FTN charting covers" in warning, warning)
    }

    @Test
    fun `full or near-full coverage is silent`() {
        assertNull(ftnCoverageWarning(2025, attempts = 200, covered = 200))
        assertNull(ftnCoverageWarning(2025, attempts = 200, covered = 180))
    }

    @Test
    fun `a season with no attempts has nothing to compare`() {
        assertNull(ftnCoverageWarning(2025, attempts = 0, covered = 0))
        assertEquals(null, ftnCoverageWarning(2025, attempts = 0, covered = 0))
    }
}
