package dev.gridiron.core.data

import java.math.BigDecimal
import kotlin.math.abs

/**
 * Strict decimal parsing for number fields. A comma is a decimal point in
 * every locale ("1,5" is 1.5), and grouping separators, exponents, NaN and
 * infinities are rejected, so no field can ever save a non-finite number.
 */
public object DecimalInput {
    /** The scoring editor's bound: no single weight is ever near it. */
    public const val LIMIT: Double = 1000.0

    private val PATTERN = Regex("""-?(\d+([.,]\d*)?|[.,]\d+)""")

    public sealed interface Result {
        public data class Value(val value: Double) : Result
        public data object Blank : Result
        public data object Invalid : Result
    }

    public fun parse(text: String, limit: Double = LIMIT): Result {
        val t = text.trim()
        if (t.isEmpty()) return Result.Blank
        if (!PATTERN.matches(t)) return Result.Invalid
        val value = t.replace(',', '.').toDoubleOrNull() ?: return Result.Invalid
        return if (value.isFinite() && abs(value) <= limit) Result.Value(value) else Result.Invalid
    }

    public fun format(value: Double): String =
        if (value == 0.0) "0" else BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}
