package dev.gridiron.feature.scoring

import java.math.BigDecimal
import kotlin.math.abs

/**
 * Strict decimal parsing for scoring fields. A comma is a decimal point in
 * every locale ("1,5" is 1.5), and grouping separators, exponents, NaN and
 * infinities are rejected, so no field can ever save a non-finite number.
 */
internal object DecimalInput {
    const val LIMIT: Double = 1000.0

    private val PATTERN = Regex("""-?(\d+([.,]\d*)?|[.,]\d+)""")

    sealed interface Result {
        data class Value(val value: Double) : Result
        data object Blank : Result
        data object Invalid : Result
    }

    fun parse(text: String): Result {
        val t = text.trim()
        if (t.isEmpty()) return Result.Blank
        if (!PATTERN.matches(t)) return Result.Invalid
        val value = t.replace(',', '.').toDoubleOrNull() ?: return Result.Invalid
        return if (value.isFinite() && abs(value) <= LIMIT) Result.Value(value) else Result.Invalid
    }

    fun format(value: Double): String =
        if (value == 0.0) "0" else BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}
