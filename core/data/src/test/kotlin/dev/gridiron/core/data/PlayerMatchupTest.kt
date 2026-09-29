package dev.gridiron.core.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlayerMatchupTest {
    private val schedule = listOf(
        ScheduleGame(1, "KC", "BAL", 27, 20),
        ScheduleGame(2, "DAL", "KC", 24, 17),
        ScheduleGame(3, "KC", "LV", 20, 20),
        ScheduleGame(4, "KC", "NO", null, null),
    )

    @Test
    fun `a home win reads vs and W`() = assertEquals(Matchup("vs BAL", "W 27–20"), matchup("KC", 1, schedule))

    @Test
    fun `an away loss reads at and L, with his own score first`() = assertEquals(Matchup("@ DAL", "L 17–24"), matchup("KC", 2, schedule))

    @Test
    fun `the same game from the other side`() = assertEquals(Matchup("@ KC", "L 20–27"), matchup("BAL", 1, schedule))

    @Test
    fun `a tie reads T`() = assertEquals(Matchup("vs LV", "T 20–20"), matchup("KC", 3, schedule))

    @Test
    fun `a game with no score yet has an opponent and no result`() = assertEquals(Matchup("vs NO", null), matchup("KC", 4, schedule))

    @Test
    fun `a week with no game, or no team, has a dash`() {
        assertEquals(Matchup("–", null), matchup("KC", 9, schedule))
        assertEquals(Matchup("–", null), matchup(null, 1, schedule))
    }
}
