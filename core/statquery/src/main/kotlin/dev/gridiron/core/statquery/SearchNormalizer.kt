package dev.gridiron.core.statquery

import java.text.Normalizer

private val NON_SEARCH_CHARS = Regex("[^a-z0-9 ]")

/**
 * Normalizes a name for the indexed `player.search_name` column.
 *
 * Must stay identical to `_search_name` in the ETL (etl/gridiron_etl/build.py);
 * a contract test checks this against every player in a real database. NFKD
 * decomposition first, so "Tomás" folds to "tomas" rather than dropping the
 * accented letter. `lowercase()` is locale-independent, so a Turkish device
 * locale can't change the result.
 */
public fun normalizeSearch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFKD)
        .lowercase()
        .replace(NON_SEARCH_CHARS, "")
        .trim()
