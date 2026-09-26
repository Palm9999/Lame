package dev.gridiron.core.forecast

import kotlin.math.sqrt

/** One team-game from the offense's side: [offense] produced [value] against [defense]. */
internal data class RidgeRow(val offense: String, val defense: String, val home: Boolean, val value: Double)

/** `value = mean + offense + defense + home`, each coefficient relative to the mean. */
internal class RidgeFit(
    val mean: Double,
    val offense: Map<String, Double>,
    val defense: Map<String, Double>,
    val home: Double,
    /** Rows each team appears in as the defense: how much evidence its rating has. */
    val defenseGames: Map<String, Int>,
)

/**
 * Two-way ridge regression: one dummy per offense, one per defense and a home
 * flag, fitted on `value - mean` with an L2 penalty [lambda] on every
 * coefficient, so a team seen twice stays near zero. At most 65 parameters,
 * solved directly from the normal equations.
 */
internal fun fitRidge(rows: List<RidgeRow>, lambda: Double = K.RIDGE_LAMBDA): RidgeFit {
    require(rows.isNotEmpty()) { "no rows to fit" }
    val teams = rows.flatMap { listOf(it.offense, it.defense) }.distinct().sorted()
    val index = teams.withIndex().associate { (i, team) -> team to i }
    val t = teams.size
    val p = 2 * t + 1
    val homeCol = p - 1
    val mean = rows.sumOf { it.value } / rows.size
    val xtx = Array(p) { DoubleArray(p) }
    val xty = DoubleArray(p)
    for (r in rows) {
        // Each row has a 1 in its offense column and its defense column, and h in the home column.
        val cols = intArrayOf(index.getValue(r.offense), t + index.getValue(r.defense))
        val h = if (r.home) 1.0 else 0.0
        val y = r.value - mean
        for (a in cols) {
            for (b in cols) xtx[a][b] += 1.0
            xtx[a][homeCol] += h
            xtx[homeCol][a] += h
            xty[a] += y
        }
        xtx[homeCol][homeCol] += h * h
        xty[homeCol] += h * y
    }
    for (i in 0 until p) xtx[i][i] += lambda
    val beta = choleskySolve(xtx, xty)
    return RidgeFit(
        mean = mean,
        offense = teams.associateWith { beta[index.getValue(it)] },
        defense = teams.associateWith { beta[t + index.getValue(it)] },
        home = beta[homeCol],
        defenseGames = rows.groupingBy { it.defense }.eachCount(),
    )
}

/** Solves `a x = b` for a symmetric positive-definite `a`; the ridge penalty guarantees that. */
internal fun choleskySolve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
    val n = b.size
    val l = Array(n) { DoubleArray(n) }
    for (i in 0 until n) {
        for (j in 0..i) {
            var sum = a[i][j]
            for (k in 0 until j) sum -= l[i][k] * l[j][k]
            if (i == j) {
                check(sum > 0.0) { "matrix is not positive definite" }
                l[i][i] = sqrt(sum)
            } else {
                l[i][j] = sum / l[j][j]
            }
        }
    }
    val y = DoubleArray(n)
    for (i in 0 until n) {
        var sum = b[i]
        for (k in 0 until i) sum -= l[i][k] * y[k]
        y[i] = sum / l[i][i]
    }
    val x = DoubleArray(n)
    for (i in n - 1 downTo 0) {
        var sum = y[i]
        for (k in i + 1 until n) sum -= l[k][i] * x[k]
        x[i] = sum / l[i][i]
    }
    return x
}
