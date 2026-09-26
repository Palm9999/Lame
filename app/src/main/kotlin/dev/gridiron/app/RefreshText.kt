package dev.gridiron.app

import dev.gridiron.core.ingest.IngestProgress
import dev.gridiron.core.ingest.IngestReport
import dev.gridiron.core.ingest.ValidationException
import dev.gridiron.core.ingest.csv.MissingColumnsException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

/** The one line a running refresh shows, e.g. "Downloading 2026 play-by-play 12/19 MB". */
internal fun progressText(p: IngestProgress): String = when (p) {
    is IngestProgress.Checking -> if (p.season == null) "Checking for new stats…" else "Checking ${p.season}…"
    is IngestProgress.Downloading -> buildString {
        append("Downloading ")
        p.season?.let { append(it).append(' ') }
        append(p.what).append(' ')
        // Whole megabytes for big files; one decimal for small ones, so they don't read "0/0".
        val whole = maxOf(p.bytes, p.total) >= 10_000_000
        append(megabytes(p.bytes, whole))
        if (p.total > 0) append('/').append(megabytes(p.total, whole))
        append(" MB")
    }
    is IngestProgress.Crunching -> "Crunching ${p.season}…"
    IngestProgress.Validating -> "Checking the new stats…"
}

private fun megabytes(bytes: Long, whole: Boolean): String =
    if (whole) (bytes / 1_000_000).toString() else String.format(Locale.US, "%.1f", bytes / 1e6)

/** Why a stats build failed, in words; [kept] adds that the old stats stay. */
internal fun describeFailure(e: Throwable, kept: Boolean): String {
    val what = when {
        e is ValidationException -> "The new stats failed a check (${e.problems.first()})."
        e is MissingColumnsException -> "${e.message}."
        e.causes().any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException || it is SocketTimeoutException } ->
            "No connection."
        e.causes().any { t -> t.message.orEmpty().let { "No space left" in it || "ENOSPC" in it || "disk is full" in it } } ->
            "Not enough free storage to build stats."
        else -> "Refresh failed: ${e.message ?: e::class.simpleName}."
    }
    return if (kept) "$what Your current stats are kept." else what
}

private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

/** "Stats updated for 2024, 2025, 2026 in 1 min 5 s." plus any skipped seasons. */
internal fun summary(report: IngestReport, elapsedMs: Long): String = buildString {
    append("Stats updated for ").append((report.built + report.reused).sorted().joinToString(", "))
    append(" in ").append(formatDuration(elapsedMs)).append('.')
    for ((season, why) in report.skipped.toSortedMap()) append(' ').append(season).append(" skipped: ").append(why).append('.')
}

internal fun formatDuration(ms: Long): String {
    val s = (ms + 500) / 1000
    return if (s < 60) "$s s" else "${s / 60} min ${s % 60} s"
}
