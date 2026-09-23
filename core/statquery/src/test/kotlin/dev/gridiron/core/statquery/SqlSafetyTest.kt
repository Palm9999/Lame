package dev.gridiron.core.statquery

import dev.gridiron.core.model.BonusStat
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.ScoringRule
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.model.YardageBonus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Locale
import kotlin.random.Random

/** Structural guarantees about generated SQL, checked without a database. */
class SqlSafetyTest {

    private val hostileAlphabet = "abcAZ09 '\";--/*?%_\\\n\t()=,.é漢\u0000"

    private fun Random.hostile(maxLength: Int = 24): String =
        String(CharArray(nextInt(1, maxLength)) { hostileAlphabet[nextInt(hostileAlphabet.length)] })

    private fun Random.spec(): StatQuerySpec {
        val all = StatColumn.entries
        val columns = all.shuffled(this).take(nextInt(1, 8))
        val sortCols = all.shuffled(this).take(nextInt(0, 3))
        val first = nextInt(1, WeekRange.MAX_WEEK + 1)
        return StatQuerySpec(
            season = nextInt(StatQuerySpec.MIN_SEASON, 2030),
            weeks = WeekRange(first, nextInt(first, WeekRange.MAX_WEEK + 1)),
            columns = columns,
            sort = sortCols.map { Sort(it, Direction.entries.random(this)) },
            positions = Position.entries.shuffled(this).take(nextInt(0, 3)).toSet(),
            teams = List(nextInt(0, 4)) { hostile() }.toSet(),
            filters = List(nextInt(0, 4)) {
                val lo = nextDouble(-100.0, 100.0)
                Filter(
                    all.random(this),
                    when (nextInt(5)) {
                        0 -> Condition.AtLeast(lo)
                        1 -> Condition.AtMost(lo)
                        2 -> Condition.GreaterThan(lo)
                        3 -> Condition.LessThan(lo)
                        else -> Condition.Between(lo, lo + nextDouble(0.0, 50.0))
                    },
                )
            },
            qualifiers = List(nextInt(0, 3)) { Filter(all.random(this), Condition.AtLeast(nextDouble(0.0, 50.0))) },
            includeUnqualified = nextBoolean(),
            minGames = nextInt(0, 10),
            mode = ValueMode.entries.random(this),
            percentiles = nextBoolean(),
            name = if (nextBoolean()) hostile() else null,
            limit = nextInt(1, StatQuerySpec.MAX_LIMIT + 1),
            offset = nextInt(0, 5000),
            scoring = ScoringProfile(
                id = "u${nextInt(1000)}",
                // ScoringProfile requires a non-blank name; hostile() can draw an
                // all-whitespace string, so fall back rather than crash the generator.
                name = hostile().ifBlank { "x" },
                weights = ScoringRule.entries.shuffled(this).take(nextInt(0, 8)).associateWith { nextDouble(-10.0, 10.0) },
                receptionByPosition = ScoringProfile.RECEPTION_POSITIONS.shuffled(this).take(nextInt(0, 3))
                    .associateWith { nextDouble(-2.0, 2.0) },
                yardageBonuses = List(nextInt(0, 4)) {
                    val min = nextInt(0, 400)
                    YardageBonus(
                        BonusStat.entries.random(this), min,
                        if (nextBoolean()) min + nextInt(1, 200) else null, nextDouble(-5.0, 5.0),
                    )
                },
            ),
        )
    }

    @Test
    fun `no generated SQL contains a string literal, statement separator or comment`() {
        val rnd = Random(20260922)
        repeat(2_000) {
            val spec = rnd.spec()
            for (sql in listOf(StatQueryBuilder.grid(spec).query.sql, StatQueryBuilder.count(spec).sql)) {
                // Nothing we emit is ever quoted, so a quote can only mean input leaked in.
                assertFalse('\'' in sql, "quote in SQL for $spec")
                assertFalse('"' in sql, "double quote in SQL for $spec")
                assertFalse(';' in sql, "semicolon in SQL for $spec")
                assertFalse("--" in sql, "line comment in SQL for $spec")
                assertFalse("/*" in sql, "block comment in SQL for $spec")
            }
        }
    }

    @Test
    fun `team values reach the query only as binds, verbatim`() {
        val rnd = Random(7)
        repeat(500) {
            val spec = rnd.spec()
            val texts = StatQueryBuilder.grid(spec).query.binds.filterIsInstance<Bind.Text>().map { it.value }
            assertTrue(texts.containsAll(spec.teams), "teams missing from binds for $spec")
        }
    }

    @Test
    fun `metric ids are bound, never written into SQL text`() {
        val spec = StatQuerySpec(2025, WeekRange(1, 18), StatColumn.entries, percentiles = true, scoring = ScoringPresets.PPR)
        val q = StatQueryBuilder.grid(spec).query
        val ids = (StatColumn.entries.flatMap { it.aggregate.components } + SCORING_COMPONENTS).map { it.id }.toSet()

        // "g" is too short to search for meaningfully; every other id is checked.
        for (id in ids.filter { it.length > 1 }) {
            assertFalse(Regex("\\b${Regex.escape(id)}\\b").containsMatchIn(q.sql), "$id appears in SQL")
            assertTrue(Bind.Text(id) in q.binds, "$id not bound")
        }
    }

    @Test
    fun `every column can be requested at once`() {
        val spec = StatQuerySpec(
            2025, WeekRange(1, 18), StatColumn.entries,
            sort = StatColumn.entries.map { Sort(it) },
            filters = StatColumn.entries.map { Filter(it, Condition.AtLeast(0.0)) },
            percentiles = true,
            mode = ValueMode.PER_GAME,
            scoring = ScoringPresets.PPR,
        )
        val q = StatQueryBuilder.grid(spec)
        assertEquals(GridLayout.FIRST_STAT + StatColumn.entries.size * 2, q.layout.width)
    }

    @Test
    fun `equal specs produce identical SQL and binds regardless of set order`() {
        val a = StatQuerySpec(
            2025, WeekRange(1, 8), listOf(StatColumn.WOPR, StatColumn.TARGETS),
            teams = linkedSetOf("KC", "BUF", "DET"),
            positions = linkedSetOf(Position.TE, Position.WR),
        )
        val b = a.copy(teams = linkedSetOf("DET", "KC", "BUF"), positions = linkedSetOf(Position.WR, Position.TE))

        assertEquals(StatQueryBuilder.grid(a), StatQueryBuilder.grid(b))
        assertEquals(StatQueryBuilder.count(a), StatQueryBuilder.count(b))
    }

    @Test
    fun `count only aggregates the components its filters need`() {
        val spec = StatQuerySpec(2025, WeekRange(1, 18), StatColumn.entries, scoring = ScoringPresets.PPR)
        val count = StatQueryBuilder.count(spec)
        // Only the games component: no filters, so nothing else is summed.
        assertEquals(1, Regex("SUM\\(").findAll(count.sql).count())
    }

    @Test
    fun `malformed queries cannot be constructed`() {
        assertThrows<IllegalArgumentException> { SqlQuery("SELECT ?", emptyList()) }
        assertThrows<IllegalArgumentException> { SqlQuery("SELECT 1", listOf(Bind.Integer(1))) }
    }
}

class SpecValidationTest {
    private val weeks = WeekRange(1, 18)

    @Test
    fun `rejects specs that cannot mean anything`() {
        assertThrows<IllegalArgumentException> { StatQuerySpec(2025, weeks, emptyList()) }
        assertThrows<IllegalArgumentException> {
            StatQuerySpec(2025, weeks, listOf(StatColumn.TARGETS, StatColumn.TARGETS))
        }
        assertThrows<IllegalArgumentException> {
            StatQuerySpec(2025, weeks, listOf(StatColumn.TARGETS), sort = listOf(Sort(StatColumn.TARGETS), Sort(StatColumn.TARGETS)))
        }
        assertThrows<IllegalArgumentException> { StatQuerySpec(2025, weeks, listOf(StatColumn.TARGETS), limit = 0) }
        assertThrows<IllegalArgumentException> {
            StatQuerySpec(2025, weeks, listOf(StatColumn.TARGETS), limit = StatQuerySpec.MAX_LIMIT + 1)
        }
        assertThrows<IllegalArgumentException> { StatQuerySpec(1998, weeks, listOf(StatColumn.TARGETS)) }
        assertThrows<IllegalArgumentException> { StatQuerySpec(2025, weeks, listOf(StatColumn.TARGETS), offset = -1) }
    }

    @Test
    fun `rejects filter values that cannot bind meaningfully`() {
        assertThrows<IllegalArgumentException> { Condition.AtLeast(Double.NaN) }
        assertThrows<IllegalArgumentException> { Condition.LessThan(Double.POSITIVE_INFINITY) }
        assertThrows<IllegalArgumentException> { Condition.Between(5.0, 1.0) }
    }
}

class SearchNormalizerTest {
    @Test
    fun `strips punctuation and folds accents`() {
        assertEquals("jamarr chase", normalizeSearch("Ja'Marr Chase"))
        assertEquals("dj moore", normalizeSearch("D.J. Moore"))
        assertEquals("tomas avila", normalizeSearch("Tomás Ávila"))
        assertEquals("amonra st brown", normalizeSearch("  Amon-Ra St. Brown  "))
    }

    @Test
    fun `is independent of the device locale`() {
        val saved = Locale.getDefault()
        try {
            // Turkish lowercases 'I' to dotless 'ı', which the filter would then drop.
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("ian thomas", normalizeSearch("IAN THOMAS"))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
