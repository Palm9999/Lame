package dev.gridiron.core.ingest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InjuriesTest {
    @Test
    fun `keeps the last report per player-week and drops rows without an id`() {
        val header = "season,season_type,game_type,team,week,gsis_id,position,full_name,first_name,last_name," +
            "report_primary_injury,report_secondary_injury,report_status,practice_primary_injury,practice_secondary_injury,practice_status\n"
        val csv = header +
            "2025,REG,REG,ARI,1,00-9,WR,Some Guy,Some,Guy,Hamstring,,Questionable,,,Limited Participation in Practice\n" +
            "2025,REG,REG,ARI,1,00-9,WR,Some Guy,Some,Guy,Hamstring,,Out,,,Did Not Participate In Practice\n" +
            "2025,REG,REG,ARI,1,,WR,No Id,No,Id,,,,,,\n"
        assertEquals(
            listOf(InjuryRow("00-9", 2025, 1, "ARI", "Some Guy", "WR", "Out", "Hamstring", "Did Not Participate In Practice")),
            readInjuries(csv.byteInputStream(), "injuries_2025.csv.gz"),
        )
    }
}
