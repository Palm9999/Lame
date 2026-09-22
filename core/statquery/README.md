# :core:statquery

Pure-Kotlin, Android-free query builder for the Grid. Turns a `StatQuerySpec` into SQL over the ETL's long/narrow fact table.

```kotlin
val q = StatQueryBuilder.grid(
    StatQuerySpec(
        season = 2025,
        weeks = WeekRange(1, 8),
        columns = listOf(StatColumn.TARGET_SHARE, StatColumn.WOPR, StatColumn.ADOT),
        positions = Position.FLEX,
        filters = listOf(Filter(StatColumn.TARGETS, Condition.AtLeast(30.0))),
        sort = listOf(Sort(StatColumn.WOPR)),
        percentiles = true,
    ),
)
// q.query.sql, q.query.binds  -> Room 3 RoomRawQuery, binding by index
// q.layout.valueIndex(StatColumn.WOPR), q.layout.percentileIndex(...)
```

Also `CatalogQueries` (seasons, metric metadata), `StatQueryBuilder.count(spec)` for the filter sheet's live match count, and `StatQueryBuilder.search(text)` for player lookup.

## What it guarantees

**Rates are recomputed over the range, never averaged.** Target share over weeks 1–8 is `Σ targets / Σ team targets`. Columns declare how they aggregate (`Total`, `Ratio`, `ClampedWeightedSum`), and per-game mode divides counting columns by games while leaving rates alone.

**Nothing from outside reaches the SQL text.** Every value, including metric ids, is a bound `?`, and identifiers are index-derived aliases. `SqlQuery` refuses to construct if placeholders and binds disagree. A fuzz test with 2,000 randomized hostile specs checks that the generated SQL never contains a quote, semicolon or comment marker.

**Deterministic.** Equal specs produce identical SQL and binds regardless of set ordering, so results can be cached by spec.

**Percentiles rank a meaningful population, and don't move when you filter.** Positional percentiles (1.0 = best, inverted for lower-is-better stats) are computed among players who meet the spec's *qualifiers* ("54+ targets"). Without that, a starter ranks against backups with one target, and every regular's cell is top-half. Qualifiers define who is ranked; *filters* narrow what you see, and run afterwards, so narrowing the view never changes anyone's percentile. `includeUnqualified` returns players below the bar, unranked, which is how name search finds anyone. Players without a value get no percentile and don't dilute everyone else's.

**Stable paging.** NULLs sort last in both directions; ties break on name then id.

## Tests

```bash
./gradlew :core:statquery:test
```

Three tiers:

- **SQL execution** (`StatQueryBuilderTest`) against in-memory SQLite, with hand-computed expected values.
- **Safety and determinism** (`SqlSafetyTest`), no database.
- **Contract** (`RealDatabaseContractTest`) against a real ETL-built database. Skipped unless `GRIDIRON_STATS_DB` is set:

  ```bash
  GRIDIRON_STATS_DB=/path/to/stats.db ./gradlew :core:statquery:test
  ```

  The Kotlin column registry duplicates definitions that live in Python, so this tier checks them against each other: every column recomputed over each single week must reproduce the weekly value the ETL stored. On 2024–2025 that is **413,179 player-week-column values, all matching**. It also asserts that every component exists, that denominators are flagged internal, that search normalization matches the ETL for every player, and that the query plan reads the fact table only through the covering index.

CI runs all three tiers on every change, building a fresh database first.

## Performance

A full-regular-season, 12-column FLEX grid with percentiles runs in **~85 ms** on the CI-class JVM, down from 173 ms before the ETL's index became covering. It has not yet been measured on the target device. It must run off the main thread, like any database query. Precomputed season rollups are the next step for the common full-season view.

## Decisions

**Text keys, not integer surrogates.** Deferred without lock-in: ids reach SQL only as bound values, so switching later changes the ETL and the id bind type, not the query shape. At 6.3 MB shipped, and with the covering index removing lookup cost, the size saving isn't needed yet.

**No FTS.** ~800 players. Full-name prefixes use the `>= q AND < q￿` range trick on an indexed column, and later-word prefixes use a `LIKE` whose pattern can't contain wildcards, because normalized text is `[a-z0-9 ]` only.

**Requires SQLite 3.25+** for `PERCENT_RANK`. Satisfied by `androidx.sqlite:sqlite-bundled` and by the platform SQLite on the target device.
