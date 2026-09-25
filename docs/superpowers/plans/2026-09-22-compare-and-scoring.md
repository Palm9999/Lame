# Compare and Custom Scoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add custom fantasy scoring (profiles scored in SQL over any week range, with xFP and FPOE) and a Compare screen (tray, percentile bars, head-to-head table, radar, xFP scatter) to Gridiron.

**Architecture:** The ETL adds actual scoring components from play-by-play and expected components from ffopportunity. `:core:statquery` gains a per-week scoring step that applies a bound `ScoringProfile`, so fantasy points behave like every other column. Profiles and the compare tray persist in a DataStore JSON document. Compare runs one Grid query per slot, filtered to that player after percentiles are computed, and draws Canvas charts from `:core:charts`.

**Tech Stack:** Python 3 + polars (ETL); Kotlin 2.4.20, Jetpack Compose (BOM 2026.09.00), Navigation 3, DataStore, kotlinx.serialization; SQLite via androidx.sqlite bundled driver; JUnit 6 (JVM modules), JUnit 4 + Robolectric + Roborazzi (Android modules).

**Spec:** `docs/superpowers/specs/2026-09-22-compare-and-scoring-design.md`

## Global Constraints

- Toolchain unchanged: Kotlin 2.4.20, AGP 9.4.1, compileSdk 37, targetSdk 36, minSdk 34, Gradle wrapper 9.7.1. Warnings are errors. JVM modules use explicit API mode (every public declaration says `public`).
- New library versions (verified against Google Maven and Maven Central on 2026-09-22; use exactly these): `androidx.navigation3:navigation3-runtime` and `navigation3-ui` **1.1.7**; `androidx.lifecycle:lifecycle-viewmodel-navigation3` **2.11.0**; `androidx.datastore:datastore-core` **1.2.1**; `org.jetbrains.kotlinx:kotlinx-serialization-json` **1.11.0**; Kotlin serialization plugin `org.jetbrains.kotlin.plugin.serialization` **2.4.20**.
- No Hilt. Dependency injection stays manual in `GridironApplication`.
- SQL safety: every value, including metric ids and scoring weights, is a bound `?`. SQL text contains only fixed text and index-derived aliases. `SqlSafetyTest` keeps passing.
- The ETL ships stat components, never fantasy points. `SCHEMA_VERSION` becomes **3**.
- Reference profile (matches ffopportunity's totals): reception 1, passing yard 0.04, rushing/receiving yard 0.1, passing TD 4, rushing/receiving TD 6, two-point conversion 2, interception −2, fumble lost −2.
- Presets (ESPN defaults): passing yard 0.04, passing TD 4, interception −2, rushing/receiving yard 0.1, rushing/receiving TD 6, two-point conversion 2 (pass, rush, receive), fumble lost −2; reception 1 (PPR) / 0.5 (Half PPR) / 0 (Standard); everything else 0; no bonuses. Preset ids `preset:ppr`, `preset:half`, `preset:standard`. First-launch active profile: PPR.
- Compare population qualifiers: QB `DROPBACKS`, RB `CARRIES`, WR and TE `TARGETS`, with the existing per-week bars in `SampleThreshold`.
- Tray capacity 4. Radar shows at most 2 slots. Table "Only real differences" hides rows where max − min percentile ≤ 10 points.
- Charts are Compose Canvas; no charting library.
- Performance: a full regular season scored for every player with the Fantasy pack, percentiles on, median under **250 ms** on the JVM against the real database.
- Screenshots: Robolectric `sdk = [36]`, qualifiers `w412dp-h892dp-xxhdpi` (S24 Ultra portrait), light and dark.
- Commit messages follow the repo style `<area>: <summary>` (for example `statquery: score fantasy points per week in SQL`) and end with these two trailer lines:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01NAmSfMG3LfdCw9DC5pxByG
  ```

## Review Focus

1. **Negative and zero weights.** A profile like "−0.5 per incompletion, 0 per reception" produces negative fantasy points. Sorting, percentiles, heat and FPOE must behave (negative values sort below zero, nothing is nulled). Pinned in Task 4.
2. **Self-comparison.** The same player twice in the tray with different seasons or ranges is valid; the exact same slot twice is rejected. Every UI list keyed by slot must not crash on duplicate player ids. Pinned in Task 7 (repository) and Task 12 (screen).
3. **A saved tray slot whose season is no longer in the database.** The tray survives app updates, but the database keeps a rolling three seasons, so a 2024 slot eventually points at nothing. The slot shows "No 2024 data" instead of crashing. Pinned in Task 11.
4. **Editor input.** `""`, `"-"`, `"."`, `"1,5"`, `"1e3"`, `"NaN"`, `"99999"` in a weight field: never crash, never save a non-finite number; `"1,5"` means 1.5 in every locale. Pinned in Task 9.
5. **Bonus boundaries.** A 100–199 rushing bonus at exactly 99, 100, 199 and 200 yards; an open-ended 200+ tier; rushing + receiving yards when one of the two is absent that week. Pinned in Task 4.

---

## Working Environment (read before any task)

- **Repository:** the main checkout is `/home/user/Lame`. Tasks may run in a git worktree elsewhere; paths below are repository-relative.
- **Python (ETL):** run from `etl/`: `python3 -m pytest -q`. Build the database with `python3 -m gridiron_etl.build --seasons 2024 2025 2026 --skip-missing --out build/stats.db`. Downloads cache in `~/.cache/gridiron`.
- **The real database** is `etl/build/stats.db` in the main checkout. It is git-ignored, so **it does not exist inside a worktree**. Kotlin tests that need it skip silently without it. In a worktree, always export an absolute path before running Gradle:
  ```bash
  export GRIDIRON_STATS_DB=/home/user/Lame/etl/build/stats.db
  ```
  Tasks 1 and 2 rebuild it; from Task 6 on it must be the schema-3 build (check: `sqlite3 "$GRIDIRON_STATS_DB" "SELECT value FROM schema_meta WHERE key='schema_version'"` prints `3`).
- **Robolectric** needs its runtime jars from a mirror in this sandbox:
  ```bash
  export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Drobolectric.dependency.repo.url=https://maven-central.storage-download.googleapis.com/maven2/"
  ```
- **Android SDK:** a worktree needs `local.properties`; copy it: `cp /home/user/Lame/local.properties <worktree>/`.
- **Gradle:** `./gradlew <tasks> --console=plain`. Screenshots: `./gradlew :<module>:recordRoborazziDebug`; images land in `<module>/build/outputs/roborazzi/`. **Look at every screenshot you produce** (open the PNG) before claiming a UI task is done: last phase, screenshots caught black-on-black dark mode, a misleading heat map and off-screen controls that every test passed.

## Execution Waves

Tasks in the same wave touch disjoint files and may run in parallel worktrees (superpowers:dispatching-parallel-agents). A task starts only when every task it depends on is merged.

| Wave | Tasks | Depends on |
|---|---|---|
| 1 | 1 (ETL actuals + registry), 3 (scoring model), 10 (charts) | — |
| 2 | 2 (ETL expected), 4 (scoring SQL), 5 (datastore) | 2←1; 4←3; 5←3 |
| 3 | 6 (real-DB scoring contract), 7 (data layer) | 6←2,4; 7←4,5 |
| 4 | 8 (Grid: profile chip + tray), 9 (scoring editor), 11 (compare repository) | 8←7; 9←7,8 (shared `:core:ui`); 11←7 |
| 5 | 12 (Compare screen) | 12←8,10,11 |
| 6 | 13 (navigation, wiring, release checks) | 13←6,8,9,12 |

Task 9 depends on Task 8 only for the `:core:ui` module Task 8 creates; if run in parallel, Task 9 must wait for Task 8's first commit (`core/ui` scaffold).

After Task 2 merges, the controller rebuilds the database in the main checkout (`cd etl && python3 -m gridiron_etl.build --seasons 2024 2025 2026 --skip-missing --out build/stats.db`) before dispatching Task 6.

## File Map

| Path | Task | Responsibility |
|---|---|---|
| `etl/gridiron_etl/transform.py` | 1, 2 | First downs, fumbles, 2-pt, long TDs; expected components; sparse long format |
| `etl/gridiron_etl/metrics.py` | 1 | Registry entries for 28 scoring inputs and 3 computed fantasy columns; `sparse`, `computed` flags |
| `etl/gridiron_etl/schema.py` | 1 | `computed` column, schema v3 |
| `etl/gridiron_etl/validate.py` | 1, 2 | New range/coherence checks; cross-check and fantasy contract against ffopportunity |
| `etl/gridiron_etl/sources.py` | 2 | ffopportunity source with its own base URL |
| `etl/gridiron_etl/build.py` | 2 | Fetch, check and load expected components |
| `core/model/.../Scoring.kt`, `CompareSlot.kt` | 3 | `ScoringProfile`, rules, bonuses, presets, `CompareSlot` |
| `core/statquery/.../Scoring.kt` | 4 | Rule→component map; scoring CTE writer |
| `core/statquery/.../{Component,Aggregate,StatColumn,StatQuerySpec,StatQueryBuilder}.kt` | 4, 11 | Scored aggregate, 3 fantasy columns, `scoring` and `playerIds` on the spec |
| `core/datastore/` (new, JVM) | 5 | `UserPrefs` JSON document: profiles, active id, tray |
| `core/data/.../{ScoringRepository,CompareTrayRepository}.kt` | 7 | Profile and tray operations |
| `core/data/.../{StatPack,StatFormat,StatsRepository,GridModels}.kt` | 7 | Fantasy pack, one-decimal formatting, scoring in grid requests, player lookup |
| `core/data/.../{CompareMetricSets,CompareRepository,CompareModels}.kt` | 11 | Per-position stat sets, per-slot queries, composites, radar, scatter |
| `core/table/.../StatTable.kt` | 8 | Row long-press |
| `core/ui/` (new, Android) | 8 | Shared sheets (`MetricSheet`, `WeeksSheet`) and `ProfileChip` |
| `feature/players/` | 8 | Profile chip, tray bar, add-to-compare |
| `feature/scoring/` (new) | 9 | Profile list and editor |
| `core/charts/` (new, Android) | 10 | `PercentileBarRow`, `RadarChart`, `ScatterChart` |
| `core/designsystem/.../SlotColors.kt` | 10 | Four player colors, light and dark |
| `feature/compare/` (new) | 12 | Compare screen and view model |
| `app/` | 13 | Navigation 3, DI, version bump |

---

### Task 1: ETL actual scoring components, registry and schema v3

**Files:**
- Modify: `etl/gridiron_etl/transform.py`
- Modify: `etl/gridiron_etl/metrics.py`
- Modify: `etl/gridiron_etl/schema.py`
- Modify: `etl/gridiron_etl/validate.py`
- Modify: `etl/gridiron_etl/build.py` (only the `to_long` call)
- Test: `etl/tests/test_transform.py`, create `etl/tests/test_registry.py`, create `etl/tests/test_validate.py`

**Interfaces:**
- Produces (metric ids, used verbatim by Tasks 2, 4, 6):
  - actual, internal, sparse: `passing_first_downs`, `rushing_first_downs`, `receiving_first_downs`, `passing_2pt`, `rushing_2pt`, `receiving_2pt`, `fumbles_lost`, `passing_tds_40`, `passing_tds_50`, `rushing_tds_40`, `rushing_tds_50`, `receiving_tds_40`, `receiving_tds_50`
  - expected, internal, sparse (registered here, filled in Task 2): `x_completions`, `x_receptions`, `x_passing_yards`, `x_rushing_yards`, `x_receiving_yards`, `x_passing_tds`, `x_rushing_tds`, `x_receiving_tds`, `x_passing_2pt`, `x_rushing_2pt`, `x_receiving_2pt`, `x_passing_first_downs`, `x_rushing_first_downs`, `x_receiving_first_downs`, `x_interceptions`
  - computed (visible, never stored as facts): `fantasy_points` (abbr `FPTS`), `expected_fantasy_points` (abbr `xFP`), `fpoe` (abbr `FPOE`)
- Produces: `metric.computed INTEGER NOT NULL DEFAULT 0` column; `transform.to_long(df, metric_ids, sparse=frozenset())`; `metrics.sparse_metric_ids() -> frozenset[str]`.

**Why the spec changed on `fpoe`:** the spec said to remove the unused `fpoe` entry. The Kotlin contract test requires every column's `metricId` to be a visible registry row, because the app takes a column's name and definition from the registry. So `fpoe` stays, flagged `computed`, alongside two new computed rows.

**Why sparse:** `to_long` stores zeros today. Twenty-eight new components, mostly zero for any given player-week, would roughly double `stats.db`. Scoring inputs are stored only when non-zero; the scoring SQL (Task 4) reads a missing value as 0.

- [ ] **Step 1: Extend the test helpers and write the failing transform tests**

In `etl/tests/test_transform.py`, add the new play-by-play columns to `play()`'s defaults and to `run()`'s schema, and remove `run()`'s own two-point filter (the transform now splits two-point plays itself):

```python
def play(**kw) -> dict:
    """One play-by-play row with neutral defaults."""
    base = dict(
        season=2025, week=1, season_type="REG", game_id="g1", posteam="AAA",
        defteam="BBB", play_type="pass", pass_attempt=0, rush_attempt=0,
        complete_pass=0, air_yards=None, yards_after_catch=None, yards_gained=0,
        passing_yards=None, receiving_yards=None, rushing_yards=None,
        pass_touchdown=0, rush_touchdown=0, interception=0, sack=0, qb_scramble=0,
        receiver_player_id=None, rusher_player_id=None, passer_player_id=None,
        yardline_100=50, epa=0.0, success=0, cpoe=None, two_point_attempt=0,
        first_down_pass=0, first_down_rush=0, fumble_lost=0,
        fumbled_1_player_id=None, two_point_conv_result=None,
    )
    base.update(kw)
    return base
```

```python
def run(plays: list[dict]) -> pl.DataFrame:
    lf = pl.LazyFrame(plays, schema_overrides={
        "air_yards": pl.Float64, "yards_after_catch": pl.Float64,
        "passing_yards": pl.Float64, "receiving_yards": pl.Float64,
        "rushing_yards": pl.Float64, "cpoe": pl.Float64,
        "receiver_player_id": pl.String, "rusher_player_id": pl.String,
        "passer_player_id": pl.String, "fumbled_1_player_id": pl.String,
        "two_point_conv_result": pl.String,
    })
    lf = lf.filter(
        pl.col("season_type").is_in(["REG", "POST"])
        & pl.col("play_type").is_in(["pass", "run"])
        & pl.col("posteam").is_not_null()
    )
    return transform.weekly_player_stats(lf)
```

Append these tests:

```python
# ---------------------------------------------------------------- scoring inputs

def test_first_downs_credit_passer_receiver_and_rusher():
    df = run([
        target("WR1", 8, complete=True, yds=12, first_down_pass=1),
        target("WR1", 3, complete=True, yds=4),
        carry("RB1", 11, first_down_rush=1),
    ])
    assert row(df, "WR1")["receiving_first_downs"] == 1
    assert row(df, "QB1")["passing_first_downs"] == 1
    assert row(df, "RB1")["rushing_first_downs"] == 1


def test_long_touchdowns_count_at_40_and_50_and_nest():
    df = run([
        target("WR1", 30, complete=True, yds=55, td=1),
        target("WR1", 20, complete=True, yds=42, td=1),
        target("WR1", 5, complete=True, yds=39, td=1),
        target("WR1", 45, complete=True, yds=60),  # long, but not a touchdown
        carry("RB1", 61, td=1),
    ])
    wr, qb, rb = row(df, "WR1"), row(df, "QB1"), row(df, "RB1")
    assert (wr["receiving_tds_40"], wr["receiving_tds_50"]) == (2, 1)
    assert (qb["passing_tds_40"], qb["passing_tds_50"]) == (2, 1)
    assert (rb["rushing_tds_40"], rb["rushing_tds_50"]) == (1, 1)


def test_fumbles_lost_are_credited_to_the_ball_carrier():
    df = run([
        carry("RB1", 3, fumble_lost=1, fumbled_1_player_id="RB1"),
        target("WR1", 5, complete=True, yds=9, fumble_lost=1, fumbled_1_player_id="WR1"),
        # A sack fumble belongs to the passer.
        play(play_type="pass", sack=1, passer_player_id="QB1",
             fumble_lost=1, fumbled_1_player_id="QB1"),
    ])
    assert row(df, "RB1")["fumbles_lost"] == 1
    assert row(df, "WR1")["fumbles_lost"] == 1
    assert row(df, "QB1")["fumbles_lost"] == 1


def test_recovered_fumbles_and_defender_fumbles_do_not_count():
    df = run([
        carry("RB1", 3, fumble_lost=0, fumbled_1_player_id="RB1"),
        # A defender fumbling on the return is not an offensive player's fumble.
        target("WR1", 5, complete=True, yds=9, fumble_lost=1, fumbled_1_player_id="CB9"),
    ])
    assert row(df, "RB1")["fumbles_lost"] == 0
    assert row(df, "WR1")["fumbles_lost"] == 0
    assert df.filter(pl.col("player_id") == "CB9").height == 0


def test_successful_two_point_conversions_are_credited_and_add_no_targets():
    df = run([
        target("WR1", 10),
        target("WR1", 2, complete=True, yds=2,
               two_point_attempt=1, two_point_conv_result="success"),
        target("WR1", 2, two_point_attempt=1, two_point_conv_result="failure"),
        carry("RB1", 2, two_point_attempt=1, two_point_conv_result="success"),
    ])
    wr = row(df, "WR1")
    assert wr["receiving_2pt"] == 1
    assert wr["targets"] == 1
    assert row(df, "QB1")["passing_2pt"] == 1
    assert row(df, "RB1")["rushing_2pt"] == 1


def test_sparse_metrics_drop_zeros_in_long_format():
    df = run([target("WR1", 10), carry("RB1", 3)])
    long = transform.to_long(df, ["targets", "fumbles_lost"], sparse=frozenset({"fumbles_lost"}))
    assert long.filter(pl.col("metric_id") == "fumbles_lost").height == 0
    # Non-sparse metrics keep their zeros, as before.
    assert long.filter((pl.col("metric_id") == "targets") & (pl.col("player_id") == "RB1")).height == 1
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `cd etl && python3 -m pytest -q tests/test_transform.py`
Expected: the six new tests FAIL (missing columns such as `receiving_first_downs`, and `to_long` has no `sparse` argument). Existing tests still pass.

- [ ] **Step 3: Implement the transform changes**

In `etl/gridiron_etl/transform.py`:

Add the play-by-play columns to `PBP_COLUMNS` (append at the end of the list):

```python
    "first_down_pass", "first_down_rush", "fumble_lost", "fumbled_1_player_id",
    "two_point_conv_result",
```

In `load_pbp`, delete the block that filters out two-point attempts (the three lines starting `# Two-point conversions don't accrue normal stats`). Two-point plays now reach `weekly_player_stats`, which splits them off. Update the docstring to: `"""Scan play-by-play, keeping scrimmage and two-point plays that count for stats."""`

Add these helpers above `_receiving`:

```python
def _scrimmage(lf: pl.LazyFrame) -> pl.LazyFrame:
    """Plays that accrue normal stats. Two-point tries don't, and would distort shares."""
    if "two_point_attempt" not in lf.collect_schema().names():
        return lf
    return lf.filter(pl.col("two_point_attempt").fill_null(0) == 0)


def _long_td(td_col: str, yards_col: str, threshold: int) -> pl.Expr:
    """Touchdowns of at least `threshold` yards. A 55-yard score counts at 40 and at 50."""
    return (
        (pl.col(td_col).fill_null(0) == 1) & (pl.col(yards_col).fill_null(0) >= threshold)
    ).sum()
```

In `_receiving`'s `.agg(...)`, add:

```python
            receiving_first_downs=pl.col("first_down_pass").fill_null(0).sum(),
            receiving_tds_40=_long_td("pass_touchdown", "receiving_yards", 40),
            receiving_tds_50=_long_td("pass_touchdown", "receiving_yards", 50),
```

In `_rushing`'s `.agg(...)`, add:

```python
            rushing_first_downs=pl.col("first_down_rush").fill_null(0).sum(),
            rushing_tds_40=_long_td("rush_touchdown", "rushing_yards", 40),
            rushing_tds_50=_long_td("rush_touchdown", "rushing_yards", 50),
```

In `_passing`'s `aggs = dict(...)`, add:

```python
        passing_first_downs=pl.col("first_down_pass").fill_null(0).sum(),
        passing_tds_40=_long_td("pass_touchdown", "passing_yards", 40),
        passing_tds_50=_long_td("pass_touchdown", "passing_yards", 50),
```

Add after `_passing`:

```python
def _fumbles(lf: pl.LazyFrame) -> pl.LazyFrame:
    """Fumbles lost, credited to the ball carrier: the rusher, the receiver
    after a catch, or the passer (sack and scramble fumbles). A defender who
    fumbles during a return is not an offensive player and is not counted."""
    carrier = pl.col("fumbled_1_player_id")
    involved = (
        (carrier == pl.col("rusher_player_id"))
        | (carrier == pl.col("receiver_player_id"))
        | (carrier == pl.col("passer_player_id"))
    ).fill_null(False)
    return (
        lf.filter((pl.col("fumble_lost").fill_null(0) == 1) & carrier.is_not_null() & involved)
        .group_by(["season", "week", "posteam", "fumbled_1_player_id"])
        .agg(fumbles_lost=pl.len())
        .rename({"fumbled_1_player_id": "player_id", "posteam": "team"})
    )


def _two_point(lf: pl.LazyFrame) -> pl.LazyFrame | None:
    """Successful two-point conversions: the passer and receiver on a pass,
    the rusher on a run. None when the source predates the columns."""
    names = lf.collect_schema().names()
    if "two_point_attempt" not in names or "two_point_conv_result" not in names:
        return None
    ok = lf.filter(
        (pl.col("two_point_attempt").fill_null(0) == 1)
        & (pl.col("two_point_conv_result") == "success")
    )
    keys = ["season", "week", "team", "player_id"]

    def credit(id_col: str, name: str) -> pl.LazyFrame:
        return (
            ok.filter(pl.col(id_col).is_not_null())
            .group_by(["season", "week", "posteam", id_col])
            .agg(pl.len().alias(name))
            .rename({id_col: "player_id", "posteam": "team"})
        )

    return (
        credit("passer_player_id", "passing_2pt")
        .join(credit("receiver_player_id", "receiving_2pt"), on=keys, how="full", coalesce=True)
        .join(credit("rusher_player_id", "rushing_2pt"), on=keys, how="full", coalesce=True)
    )
```

Replace the start of `weekly_player_stats` up to and including the `counting = [...]` list with:

```python
def weekly_player_stats(lf: pl.LazyFrame) -> pl.DataFrame:
    """Join the usage frames, add team shares and derived rate metrics."""
    keys = ["season", "week", "team", "player_id"]
    plays = _scrimmage(lf)
    rec, rush, pas = _receiving(plays), _rushing(plays), _passing(plays)

    joined = (
        rec.join(rush, on=keys, how="full", coalesce=True)
        .join(pas, on=keys, how="full", coalesce=True)
        .join(_fumbles(plays), on=keys, how="full", coalesce=True)
    )
    two = _two_point(lf)
    if two is not None:
        joined = joined.join(two, on=keys, how="full", coalesce=True)
    df = joined.join(_team_context(plays), on=["season", "week", "team"], how="left").collect()

    counting = [
        "targets", "receptions", "receiving_yards", "air_yards", "yac", "receiving_tds",
        "rz_targets", "ez_targets", "carries", "rushing_yards", "rushing_tds",
        "rz_carries", "gz_carries", "gl_carries", "qb_rush_inside_5", "attempts",
        "completions", "passing_yards", "passing_tds", "interceptions", "sacks_taken",
        "dropbacks", "rush_successes", "cpoe_n",
        "passing_first_downs", "rushing_first_downs", "receiving_first_downs",
        "passing_2pt", "rushing_2pt", "receiving_2pt", "fumbles_lost",
        "passing_tds_40", "passing_tds_50", "rushing_tds_40", "rushing_tds_50",
        "receiving_tds_40", "receiving_tds_50",
    ]
```

(The rest of the function is unchanged.)

Replace `to_long` with:

```python
def to_long(df: pl.DataFrame, metric_ids: list[str],
            sparse: frozenset[str] = frozenset()) -> pl.DataFrame:
    """Unpivot to the long/narrow fact shape the database stores.

    Metrics in `sparse` drop zero values: absent means zero. The scoring inputs
    are zero for most player-weeks (a receiver's completions, anyone's
    fumbles), and storing those zeros would roughly double the database.
    """
    present = [m for m in metric_ids if m in df.columns]
    long = (
        df.select(["player_id", "season", "week", "team", *present])
        .unpivot(
            index=["player_id", "season", "week", "team"],
            on=present,
            variable_name="metric_id",
            value_name="value",
        )
        .filter(pl.col("value").is_not_null())
        .filter(~(pl.col("metric_id").is_in(list(sparse)) & (pl.col("value") == 0)))
        .with_columns(pl.col("value").cast(pl.Float64))
    )
    return long
```

- [ ] **Step 4: Run the transform tests and confirm they pass**

Run: `cd etl && python3 -m pytest -q tests/test_transform.py`
Expected: all tests PASS, including the 27 existing ones.

- [ ] **Step 5: Write the failing registry and validation tests**

Create `etl/tests/test_registry.py`:

```python
"""The registry is the contract with the app: ids the Kotlin side reads verbatim."""

from gridiron_etl.metrics import METRICS, sparse_metric_ids

ACTUAL = [
    "passing_first_downs", "rushing_first_downs", "receiving_first_downs",
    "passing_2pt", "rushing_2pt", "receiving_2pt", "fumbles_lost",
    "passing_tds_40", "passing_tds_50", "rushing_tds_40", "rushing_tds_50",
    "receiving_tds_40", "receiving_tds_50",
]
EXPECTED = [
    "x_completions", "x_receptions", "x_passing_yards", "x_rushing_yards",
    "x_receiving_yards", "x_passing_tds", "x_rushing_tds", "x_receiving_tds",
    "x_passing_2pt", "x_rushing_2pt", "x_receiving_2pt", "x_passing_first_downs",
    "x_rushing_first_downs", "x_receiving_first_downs", "x_interceptions",
]
COMPUTED = {"fantasy_points": "FPTS", "expected_fantasy_points": "xFP", "fpoe": "FPOE"}


def test_scoring_inputs_are_internal_sparse_and_not_computed():
    for mid in ACTUAL + EXPECTED:
        m = METRICS[mid]
        assert m.internal, mid
        assert not m.computed, mid
        assert mid in sparse_metric_ids(), mid


def test_fantasy_columns_are_visible_and_computed():
    for mid, abbr in COMPUTED.items():
        m = METRICS[mid]
        assert m.computed and not m.internal, mid
        assert m.abbr == abbr
        assert m.group == "fantasy"
        assert mid not in sparse_metric_ids()


def test_existing_metrics_are_not_sparse():
    # Zeros stay stored for everything the Grid already shows.
    for mid in ("targets", "carries", "interceptions", "g", "team_targets"):
        assert mid not in sparse_metric_ids()
```

Create `etl/tests/test_validate.py`:

```python
"""Validation must fail loudly on data that is wrong but inserts cleanly."""

import polars as pl
import pytest

from gridiron_etl import schema, validate
from gridiron_etl.metrics import metric_rows


def _db(tmp_path, facts: list[tuple]):
    conn = schema.create(tmp_path / "t.db")
    schema.load_metrics(conn, metric_rows())
    conn.execute("INSERT INTO player (player_id, full_name, search_name, position, team) "
                 "VALUES ('p1', 'Test Player', 'test player', 'WR', 'AAA')")
    rows = [("p1", 2025, wk, "AAA", mid, val) for wk, mid, val in facts]
    conn.executemany("INSERT INTO player_week_stat VALUES (?, ?, ?, ?, ?, ?)", rows)
    return conn


def test_computed_metric_with_facts_fails(tmp_path):
    conn = _db(tmp_path, [(1, "g", 1), (1, "target_share", 0.2), (1, "fantasy_points", 12.0)])
    problems = validate.validate(conn, strict=False)
    assert any("computed" in p for p in problems)


def test_long_td_counts_must_nest(tmp_path):
    conn = _db(tmp_path, [(1, "g", 1), (1, "target_share", 0.2),
                          (1, "receiving_tds", 1), (1, "receiving_tds_40", 1), (1, "receiving_tds_50", 2)])
    problems = validate.validate(conn, strict=False)
    assert any("50+ receiving" in p for p in problems)


def test_consistent_scoring_inputs_pass(tmp_path):
    conn = _db(tmp_path, [(1, "g", 1), (1, "target_share", 0.2), (1, "receptions", 3),
                          (1, "receiving_tds", 2), (1, "receiving_tds_40", 1),
                          (1, "receiving_first_downs", 2)])
    assert validate.validate(conn, strict=False) == []
```

Check how `player` is created in `schema.DDL` before relying on the column list in `_db`; if the table has more `NOT NULL` columns, add them to the INSERT.

- [ ] **Step 6: Run them and confirm they fail**

Run: `cd etl && python3 -m pytest -q tests/test_registry.py tests/test_validate.py`
Expected: FAIL (`KeyError` for new metric ids, missing `computed` attribute, missing `sparse_metric_ids`).

- [ ] **Step 7: Implement the registry, schema and validation changes**

In `etl/gridiron_etl/metrics.py`, add two fields to `Metric` after `internal`:

```python
    # Computed on the device from other components and the user's scoring
    # profile. Registered for display metadata only; never stored as facts.
    computed: bool = False
    # Zero values are not stored: absent means zero. For scoring inputs, which
    # are zero for most player-weeks. An ETL concern only; not in the schema.
    sparse: bool = False
```

Replace the existing `fpoe` entry with the three computed fantasy columns:

```python
    # ---------------- Fantasy (computed on the device) ----------------
    Metric("fantasy_points", "Fantasy Points", "FPTS", "fantasy",
           "Points under the active scoring profile, scored game by game so "
           "per-game bonuses apply to single games.", decimals=1, computed=True),
    Metric("expected_fantasy_points", "Expected Fantasy Points", "xFP", "fantasy",
           "Points an average player would score from the same opportunities: "
           "the active profile applied to the opportunity model's expected "
           "receptions, yards, touchdowns and first downs.",
           predicts="Future fantasy points, better than past points do",
           decimals=1, computed=True),
    Metric("fpoe", "Fantasy Points Over Expected", "FPOE", "fantasy",
           "Actual fantasy points minus expected. Positive is a sell-high "
           "signal, negative a buy-low signal. Long plays and fumbles have no "
           "expectation, so they land here.",
           formula="fantasy_points - expected_fantasy_points",
           predicts="Negative regression when high", stability=0.12,
           decimals=1, computed=True),
```

Before the internal range-aggregation block, add the scoring inputs:

```python
    # ---------------- Scoring inputs (internal, sparse) ----------------
    *[
        Metric(mid, name, mid.upper(), "fantasy", definition,
               decimals=dec, internal=True, sparse=True)
        for mid, name, definition, dec in [
            ("passing_first_downs", "Passing First Downs", "First downs gained by completions, credited to the passer.", 0),
            ("rushing_first_downs", "Rushing First Downs", "First downs gained on carries.", 0),
            ("receiving_first_downs", "Receiving First Downs", "First downs gained on receptions.", 0),
            ("passing_2pt", "Passing 2-pt Conversions", "Successful two-point passes.", 0),
            ("rushing_2pt", "Rushing 2-pt Conversions", "Successful two-point runs.", 0),
            ("receiving_2pt", "Receiving 2-pt Conversions", "Successful two-point catches.", 0),
            ("fumbles_lost", "Fumbles Lost", "Fumbles lost by the ball carrier, including sack fumbles.", 0),
            ("passing_tds_40", "40+ Yd Passing TDs", "Passing touchdowns of at least 40 yards.", 0),
            ("passing_tds_50", "50+ Yd Passing TDs", "Passing touchdowns of at least 50 yards.", 0),
            ("rushing_tds_40", "40+ Yd Rushing TDs", "Rushing touchdowns of at least 40 yards.", 0),
            ("rushing_tds_50", "50+ Yd Rushing TDs", "Rushing touchdowns of at least 50 yards.", 0),
            ("receiving_tds_40", "40+ Yd Receiving TDs", "Receiving touchdowns of at least 40 yards.", 0),
            ("receiving_tds_50", "50+ Yd Receiving TDs", "Receiving touchdowns of at least 50 yards.", 0),
            ("x_completions", "Expected Completions", "Opportunity-model expected completions.", 2),
            ("x_receptions", "Expected Receptions", "Opportunity-model expected receptions.", 2),
            ("x_passing_yards", "Expected Passing Yards", "Opportunity-model expected passing yards.", 2),
            ("x_rushing_yards", "Expected Rushing Yards", "Opportunity-model expected rushing yards.", 2),
            ("x_receiving_yards", "Expected Receiving Yards", "Opportunity-model expected receiving yards.", 2),
            ("x_passing_tds", "Expected Passing TDs", "Opportunity-model expected passing touchdowns.", 2),
            ("x_rushing_tds", "Expected Rushing TDs", "Opportunity-model expected rushing touchdowns.", 2),
            ("x_receiving_tds", "Expected Receiving TDs", "Opportunity-model expected receiving touchdowns.", 2),
            ("x_passing_2pt", "Expected Passing 2-pt", "Opportunity-model expected two-point passes.", 2),
            ("x_rushing_2pt", "Expected Rushing 2-pt", "Opportunity-model expected two-point runs.", 2),
            ("x_receiving_2pt", "Expected Receiving 2-pt", "Opportunity-model expected two-point catches.", 2),
            ("x_passing_first_downs", "Expected Passing First Downs", "Opportunity-model expected passing first downs.", 2),
            ("x_rushing_first_downs", "Expected Rushing First Downs", "Opportunity-model expected rushing first downs.", 2),
            ("x_receiving_first_downs", "Expected Receiving First Downs", "Opportunity-model expected receiving first downs.", 2),
            ("x_interceptions", "Expected Interceptions", "Opportunity-model expected interceptions thrown.", 2),
        ]
    ],
```

Add at the end of `metrics.py`:

```python
def sparse_metric_ids() -> frozenset[str]:
    """Metrics whose zero values are not stored."""
    return frozenset(m.id for m in METRICS.values() if m.sparse)
```

In `etl/gridiron_etl/schema.py`: set `SCHEMA_VERSION = 3`; add to the `metric` table DDL after `internal`:

```sql
    internal         INTEGER NOT NULL DEFAULT 0,
    -- Computed on the device (fantasy points); display metadata only, no facts.
    computed         INTEGER NOT NULL DEFAULT 0
```

(add the comma after the `internal` line). In `load_metrics`, add `computed` to the column list and `:computed` to the values, and add `"computed": int(r["computed"])` beside the existing `"internal"` conversion.

In `etl/gridiron_etl/validate.py`, append to `RANGE_CHECKS`:

```python
    RangeCheck("fumbles_lost", 0.0, 6.0),
    RangeCheck("passing_first_downs", 0.0, 40.0),
    RangeCheck("rushing_first_downs", 0.0, 30.0),
    RangeCheck("receiving_first_downs", 0.0, 20.0),
    RangeCheck("passing_2pt", 0.0, 4.0),
    RangeCheck("rushing_2pt", 0.0, 3.0),
    RangeCheck("receiving_2pt", 0.0, 3.0),
```

Append to `COHERENCE_CHECKS` (sparse inputs are absent when zero, and the coherence query only compares player-weeks where both sides exist, which is exactly when the constraint can be violated):

```python
    CoherenceCheck("50+ passing TDs within 40+", "passing_tds_50", "passing_tds_40", "a <= b"),
    CoherenceCheck("40+ passing TDs within passing TDs", "passing_tds_40", "passing_tds", "a <= b"),
    CoherenceCheck("50+ rushing TDs within 40+", "rushing_tds_50", "rushing_tds_40", "a <= b"),
    CoherenceCheck("40+ rushing TDs within rushing TDs", "rushing_tds_40", "rushing_tds", "a <= b"),
    CoherenceCheck("50+ receiving TDs within 40+", "receiving_tds_50", "receiving_tds_40", "a <= b"),
    CoherenceCheck("40+ receiving TDs within receiving TDs", "receiving_tds_40", "receiving_tds", "a <= b"),
    CoherenceCheck("receiving first downs within receptions", "receiving_first_downs", "receptions", "a <= b"),
    CoherenceCheck("rushing first downs within carries", "rushing_first_downs", "carries", "a <= b"),
```

A `50+` count with no `40+` row is also a violation the pairwise query cannot see (the `40+` side is absent). Add this check inside `validate()` after the coherence loop:

```python
    for kind in ("passing", "rushing", "receiving"):
        orphan = conn.execute(
            f"""SELECT COUNT(*) FROM player_week_stat a
                WHERE a.metric_id = '{kind}_tds_50' AND NOT EXISTS (
                  SELECT 1 FROM player_week_stat b
                  WHERE b.player_id = a.player_id AND b.season = a.season
                    AND b.week = a.week AND b.metric_id = '{kind}_tds_40')"""
        ).fetchone()[0]
        if orphan:
            problems.append(f"coherence: 50+ {kind} TDs without a 40+ count in {orphan} player-weeks")

    computed = conn.execute(
        "SELECT m.id FROM metric m JOIN player_week_stat s ON s.metric_id = m.id "
        "WHERE m.computed = 1 GROUP BY m.id"
    ).fetchall()
    if computed:
        problems.append(f"computed metrics have stored facts: {[r[0] for r in computed]}")
```

(`kind` is one of three fixed literals, never input.) Update the final "validation passed" count to `len(RANGE_CHECKS) + len(COHERENCE_CHECKS) + 8` (4 existing + 3 nesting + 1 computed).

The `test_long_td_counts_must_nest` test expects a message containing `"50+ receiving"`: the coherence name `"50+ receiving TDs within 40+"` provides it.

In `etl/gridiron_etl/build.py`, import `sparse_metric_ids` from `.metrics` and change the fact conversion to:

```python
        frames.append(transform.to_long(weekly, metric_ids, sparse_metric_ids()))
```

- [ ] **Step 8: Run all ETL tests**

Run: `cd etl && python3 -m pytest -q`
Expected: all PASS.

- [ ] **Step 9: Build the real database and confirm validation passes**

Run (main checkout or worktree; the download cache is shared):

```bash
cd etl && python3 -m gridiron_etl.build --seasons 2024 2025 2026 --skip-missing --out build/stats.db
```

Expected: ends with `validation passed — N checks` and `wrote build/stats.db — ... facts, ... MB`. Record the fact count and size in your report (the previous build was 493,145 facts, 36.4 MB). If a new range or coherence check fails on real data, investigate the rows (`sqlite3 build/stats.db`) and fix the transform; only widen a bound with a comment naming the real-football reason, as the existing checks do.

- [ ] **Step 10: Commit**

```bash
git add etl/gridiron_etl etl/tests
git commit -m "ETL: first downs, 2-pt, fumbles lost and long TDs for scoring; schema v3

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NAmSfMG3LfdCw9DC5pxByG"
```

---

### Task 2: ETL expected components from ffopportunity, cross-check and fantasy contract

**Files:**
- Modify: `etl/gridiron_etl/sources.py`
- Modify: `etl/gridiron_etl/transform.py`
- Modify: `etl/gridiron_etl/validate.py`
- Modify: `etl/gridiron_etl/build.py`
- Modify: `etl/README.md`
- Test: create `etl/tests/test_expected.py`

**Interfaces:**
- Consumes: Task 1's metric ids, `transform.to_long(..., sparse)`, `metrics.sparse_metric_ids()`.
- Produces: `sources.SOURCES["ep_weekly"]`; `transform.EXPECTED_COLUMNS: dict[str, str]`; `transform.expected_components(ep: pl.DataFrame) -> pl.DataFrame`; `validate.cross_check(weekly, ep) -> list[str]`; `validate.fantasy_contract(weekly, ep) -> list[str]`; x_* facts in `stats.db`.

Facts established while writing the spec (2026-09-22): files are `https://github.com/ffverse/ffopportunity/releases/download/latest-data/ep_weekly_{season}.parquet`; 2024–2026 exist, 2026 through week 2; weeks 1–22 (playoffs included); `season` is a string and `week` a float; 419 rows in 2024 have a null `player_id` (drop them); after that, `(player_id, week)` is unique; expected values are rounded to 2 decimals. Under the reference profile, the file's `*_fantasy_points` columns equal the components to machine precision, and the `*_exp` totals within 0.061.

- [ ] **Step 1: Write the failing tests**

Create `etl/tests/test_expected.py`:

```python
"""ffopportunity: expected components in, actual components cross-checked."""

import polars as pl

from gridiron_etl import transform, validate


def ep_row(**kw) -> dict:
    """One ffopportunity player-week with every checked column present."""
    base = {"season": "2025", "week": 1.0, "player_id": "WR1", "posteam": "AAA"}
    for c in [
        "pass_completions", "receptions", "pass_yards_gained", "rec_yards_gained",
        "rush_yards_gained", "pass_touchdown", "rec_touchdown", "rush_touchdown",
        "pass_two_point_conv", "rec_two_point_conv", "rush_two_point_conv",
        "pass_first_down", "rec_first_down", "rush_first_down", "pass_interception",
        "rec_fumble_lost", "rush_fumble_lost", "total_fantasy_points",
        "total_fantasy_points_exp",
    ]:
        base[c] = 0.0
    for src in transform.EXPECTED_COLUMNS:
        base[src] = 0.0
    base.update(kw)
    return base


def weekly_row(**kw) -> dict:
    base = {"season": 2025, "week": 1, "team": "AAA", "player_id": "WR1"}
    for c in [
        "completions", "receptions", "passing_yards", "receiving_yards", "rushing_yards",
        "passing_tds", "receiving_tds", "rushing_tds", "passing_2pt", "receiving_2pt",
        "rushing_2pt", "passing_first_downs", "receiving_first_downs",
        "rushing_first_downs", "interceptions", "fumbles_lost",
    ]:
        base[c] = 0
    base.update(kw)
    return base


def test_expected_components_are_renamed_cast_and_keyed():
    ep = pl.DataFrame([
        ep_row(receptions_exp=5.25, rec_yards_gained_exp=61.4, week=3.0),
        ep_row(player_id=None, receptions_exp=9.0),
    ])
    out = transform.expected_components(ep)
    assert out.height == 1
    r = out.row(0, named=True)
    assert (r["season"], r["week"], r["team"]) == (2025, 3, "AAA")
    assert r["x_receptions"] == 5.25
    assert r["x_receiving_yards"] == 61.4
    assert out.schema["season"] == pl.Int64 and out.schema["week"] == pl.Int64


def test_cross_check_passes_when_sources_agree_and_flags_a_mismatch():
    weekly = pl.DataFrame([weekly_row(receptions=5, receiving_yards=61)])
    good = pl.DataFrame([ep_row(receptions=5.0, rec_yards_gained=61.0)])
    assert validate.cross_check(weekly, good) == []

    bad = pl.DataFrame([ep_row(receptions=5.0, rec_yards_gained=75.0)])
    problems = validate.cross_check(weekly, bad)
    assert len(problems) == 1 and "receiving_yards" in problems[0]


def test_cross_check_allows_our_extra_sack_fumbles_but_not_fewer():
    weekly = pl.DataFrame([weekly_row(fumbles_lost=2)])
    assert validate.cross_check(weekly, pl.DataFrame([ep_row(rush_fumble_lost=1.0)])) == []
    weekly = pl.DataFrame([weekly_row(fumbles_lost=0)])
    problems = validate.cross_check(weekly, pl.DataFrame([ep_row(rec_fumble_lost=1.0)]))
    assert any("fumbles_lost" in p for p in problems)


def test_fantasy_contract_matches_the_reference_profile():
    # 5 rec, 61 yds, 1 TD, 1 fumble: 5 + 6.1 + 6 - 2 = 15.1 in the file.
    weekly = pl.DataFrame([weekly_row(receptions=5, receiving_yards=61, receiving_tds=1, fumbles_lost=1)])
    ep = pl.DataFrame([ep_row(
        receptions=5.0, rec_yards_gained=61.0, rec_touchdown=1.0, rec_fumble_lost=1.0,
        total_fantasy_points=15.1,
        receptions_exp=4.0, rec_yards_gained_exp=50.0, rec_touchdown_exp=0.5,
        total_fantasy_points_exp=12.0,  # 4 + 5 + 3
    )])
    assert validate.fantasy_contract(weekly, ep) == []

    off = ep.with_columns(total_fantasy_points=pl.lit(16.1))
    assert any("total_fantasy_points" in p for p in validate.fantasy_contract(weekly, off))
```

- [ ] **Step 2: Run and confirm failure**

Run: `cd etl && python3 -m pytest -q tests/test_expected.py`
Expected: FAIL (`EXPECTED_COLUMNS`, `expected_components`, `cross_check`, `fantasy_contract` don't exist).

- [ ] **Step 3: Implement**

`etl/gridiron_etl/sources.py`: add a `base` field to `Source` and use it in `url()`:

```python
    key: str
    release: str
    filename: str
    partitioned: bool = True
    base: str = BASE

    def url(self, season: int | None = None) -> str:
        name = self.filename.format(season=season) if self.partitioned else self.filename
        return f"{self.base}/{self.release}/{name}"
```

Below `BASE`, add:

```python
# ffverse's expected-points model, keyed on the same gsis ids as nflverse.
FFOPPORTUNITY = "https://github.com/ffverse/ffopportunity/releases/download"
```

and to `SOURCES`:

```python
    "ep_weekly": Source("ep_weekly", "latest-data", "ep_weekly_{season}.parquet", base=FFOPPORTUNITY),
```

`etl/gridiron_etl/transform.py`, append:

```python
# ffopportunity column -> our expected component. Actual counterparts come from
# play-by-play; only the model's expectations are taken from this source.
# rec_interception_exp (targets intercepted) is deliberately not charged to
# receivers, which matches every mainstream scoring system.
EXPECTED_COLUMNS: dict[str, str] = {
    "pass_completions_exp": "x_completions",
    "receptions_exp": "x_receptions",
    "pass_yards_gained_exp": "x_passing_yards",
    "rush_yards_gained_exp": "x_rushing_yards",
    "rec_yards_gained_exp": "x_receiving_yards",
    "pass_touchdown_exp": "x_passing_tds",
    "rush_touchdown_exp": "x_rushing_tds",
    "rec_touchdown_exp": "x_receiving_tds",
    "pass_two_point_conv_exp": "x_passing_2pt",
    "rush_two_point_conv_exp": "x_rushing_2pt",
    "rec_two_point_conv_exp": "x_receiving_2pt",
    "pass_first_down_exp": "x_passing_first_downs",
    "rush_first_down_exp": "x_rushing_first_downs",
    "rec_first_down_exp": "x_receiving_first_downs",
    "pass_interception_exp": "x_interceptions",
}


def expected_components(ep: pl.DataFrame) -> pl.DataFrame:
    """ffopportunity's weekly expectations in our key shape.

    The file stores season as text and week as a float, and has rows with no
    player id (unidentified ball carriers), which are dropped.
    """
    return ep.filter(pl.col("player_id").is_not_null()).select(
        pl.col("player_id"),
        pl.col("season").cast(pl.Int64),
        pl.col("week").cast(pl.Int64),
        pl.col("posteam").alias("team"),
        *[pl.col(src).fill_null(0.0).cast(pl.Float64).alias(dst)
          for src, dst in EXPECTED_COLUMNS.items()],
    )
```

`etl/gridiron_etl/validate.py`, append (add `import polars as pl` at the top):

```python
# Our play-by-play actual -> ffopportunity's actual column for the same stat.
CROSS_CHECK_PAIRS: tuple[tuple[str, str], ...] = (
    ("receptions", "receptions"),
    ("completions", "pass_completions"),
    ("passing_yards", "pass_yards_gained"),
    ("rushing_yards", "rush_yards_gained"),
    ("receiving_yards", "rec_yards_gained"),
    ("passing_tds", "pass_touchdown"),
    ("rushing_tds", "rush_touchdown"),
    ("receiving_tds", "rec_touchdown"),
    ("passing_2pt", "pass_two_point_conv"),
    ("rushing_2pt", "rush_two_point_conv"),
    ("receiving_2pt", "rec_two_point_conv"),
    ("passing_first_downs", "pass_first_down"),
    ("rushing_first_downs", "rush_first_down"),
    ("receiving_first_downs", "rec_first_down"),
    ("interceptions", "pass_interception"),
)
CROSS_CHECK_TOLERANCE = 1.0
KEYS = ["player_id", "season", "week"]


def _join_sources(weekly: pl.DataFrame, ep: pl.DataFrame) -> pl.DataFrame:
    theirs = ep.filter(pl.col("player_id").is_not_null()).with_columns(
        pl.col("season").cast(pl.Int64), pl.col("week").cast(pl.Int64),
    )
    return weekly.join(theirs, on=KEYS, how="inner", suffix="_ffo")


def _theirs(weekly: pl.DataFrame, col: str) -> str:
    """ffopportunity's column name after the join (suffixed when ours shares it)."""
    return f"{col}_ffo" if col in weekly.columns else col


def _examples(frame: pl.DataFrame, cols: list[str]) -> list:
    return frame.select(KEYS + cols).head(3).rows()


def cross_check(weekly: pl.DataFrame, ep: pl.DataFrame) -> list[str]:
    """Our play-by-play actuals against ffopportunity's, per player-week.

    Two independent derivations of the same stat from the same plays: a
    mismatch means one of them is wrong, most likely ours.
    """
    joined = _join_sources(weekly, ep)
    problems = []
    for ours, col in CROSS_CHECK_PAIRS:
        theirs = _theirs(weekly, col)
        bad = joined.filter(
            (pl.col(ours).fill_null(0) - pl.col(theirs).fill_null(0)).abs() > CROSS_CHECK_TOLERANCE
        )
        if bad.height:
            problems.append(
                f"cross-check {ours} vs ffopportunity {col}: {bad.height} player-weeks "
                f"differ by more than {CROSS_CHECK_TOLERANCE}, e.g. {_examples(bad, [ours, theirs])}"
            )
    # Ours also counts sack fumbles, so it may exceed theirs but never trail.
    theirs_fumbles = pl.col("rec_fumble_lost").fill_null(0) + pl.col("rush_fumble_lost").fill_null(0)
    bad = joined.filter(pl.col("fumbles_lost").fill_null(0) < theirs_fumbles)
    if bad.height:
        problems.append(
            f"cross-check fumbles_lost below ffopportunity's rush + receiving fumbles in "
            f"{bad.height} player-weeks, e.g. {_examples(bad, ['fumbles_lost'])}"
        )
    return problems


def _reference_points(receptions, rec_yds, rec_td, rec_2pt, rush_yds, rush_td, rush_2pt,
                      pass_yds, pass_td, pass_2pt, ints) -> pl.Expr:
    """The profile ffopportunity totals use, fumbles excluded."""
    return (
        receptions + 0.1 * rec_yds + 6 * rec_td + 2 * rec_2pt
        + 0.1 * rush_yds + 6 * rush_td + 2 * rush_2pt
        + 0.04 * pass_yds + 4 * pass_td + 2 * pass_2pt - 2 * ints
    )


def fantasy_contract(weekly: pl.DataFrame, ep: pl.DataFrame) -> list[str]:
    """Our components, scored with ffopportunity's rules, reproduce its totals.

    Fumbles are removed from both sides: ours include sack fumbles, which
    theirs don't. Expected totals are checked to 0.1 because the file rounds
    each expected component to two decimals.
    """
    joined = _join_sources(weekly, ep)
    c = lambda name: pl.col(name).fill_null(0)  # noqa: E731
    ours = _reference_points(
        c("receptions"), c("receiving_yards"), c("receiving_tds"), c("receiving_2pt"),
        c("rushing_yards"), c("rushing_tds"), c("rushing_2pt"),
        c("passing_yards"), c("passing_tds"), c("passing_2pt"), c("interceptions"),
    )
    theirs = c("total_fantasy_points") + 2 * (c("rec_fumble_lost") + c("rush_fumble_lost"))
    problems = []
    bad = joined.filter((ours - theirs).abs() > 0.01)
    if bad.height:
        problems.append(
            f"fantasy contract: total_fantasy_points differs from our components in "
            f"{bad.height} player-weeks, e.g. {_examples(bad, ['total_fantasy_points'])}"
        )
    x = transform.expected_components(ep).join(
        ep.filter(pl.col("player_id").is_not_null()).select(
            pl.col("player_id"), pl.col("season").cast(pl.Int64), pl.col("week").cast(pl.Int64),
            pl.col("total_fantasy_points_exp"),
        ),
        on=KEYS,
    )
    expected = _reference_points(
        c("x_receptions"), c("x_receiving_yards"), c("x_receiving_tds"), c("x_receiving_2pt"),
        c("x_rushing_yards"), c("x_rushing_tds"), c("x_rushing_2pt"),
        c("x_passing_yards"), c("x_passing_tds"), c("x_passing_2pt"), c("x_interceptions"),
    )
    bad = x.filter((expected - c("total_fantasy_points_exp")).abs() > 0.1)
    if bad.height:
        problems.append(
            f"fantasy contract: total_fantasy_points_exp differs from expected components in "
            f"{bad.height} player-weeks, e.g. {_examples(bad, ['total_fantasy_points_exp'])}"
        )
    return problems
```

Add `from . import transform` to `validate.py`'s imports.

`etl/gridiron_etl/build.py`: add a fetch helper beside `_published`:

```python
def _expected(season: int, cache: Path | None, force: bool) -> pl.DataFrame | None:
    """ffopportunity's weekly expectations, or None if not published for the season."""
    try:
        path = sources.fetch("ep_weekly", season, cache_dir=cache, force=force)
    except requests.HTTPError as exc:
        if exc.response is not None and exc.response.status_code == 404:
            return None
        raise
    return pl.read_parquet(path)
```

In `build()`, after the snap-count block and before `frames.append(transform.to_long(weekly, ...))`, add:

```python
        ep = _expected(season, cache, force)
        if ep is None:
            if not skip_missing:
                raise RuntimeError(f"no ffopportunity data published for {season}")
            log.warning("season %d: no expected-points data yet; xFP will be missing", season)
        else:
            problems = validation.cross_check(weekly, ep) + validation.fantasy_contract(weekly, ep)
            for p in problems:
                log.error("VALIDATION: season %d: %s", season, p)
            if problems:
                raise validation.ValidationError(f"{len(problems)} ffopportunity check(s) failed; first: {problems[0]}")
            expected = transform.expected_components(ep)
            log.info("season %d: %d expected player-weeks", season, expected.height)
            frames.append(transform.to_long(expected, metric_ids, sparse_metric_ids()))
```

`etl/README.md`: in its data-source section, add ffopportunity as a source beside nflverse: what it provides (weekly expected components), the URL above, and its license. Check the license at `https://raw.githubusercontent.com/ffverse/ffopportunity/main/LICENSE` (or the repository's `DESCRIPTION` file) and cite what it says; don't guess.

- [ ] **Step 4: Run the ETL tests**

Run: `cd etl && python3 -m pytest -q`
Expected: all PASS.

- [ ] **Step 5: Build the real database; both new checks must pass on real data**

Run: `cd etl && python3 -m gridiron_etl.build --seasons 2024 2025 2026 --skip-missing --out build/stats.db`

Expected: `validation passed`, plus a `season N: M expected player-weeks` line per season. Record facts and MB (compare with Task 1's numbers) in your report.

If `cross_check` or `fantasy_contract` fails, **do not loosen the tolerance first.** Pull the example player-weeks it prints, find the plays in the cached play-by-play (`~/.cache/gridiron/play_by_play_<season>.csv`), and determine which side is wrong. Fix our transform if it is ours. If the source is internally inconsistent (as with the 2024 TB week 19 snap count), document the case in a comment beside the tolerance, as `validate.py` does for snap share, and widen only as far as that case requires. Report what you found either way.

- [ ] **Step 6: Commit**

```bash
git add etl/gridiron_etl etl/tests etl/README.md
git commit -m "ETL: expected components from ffopportunity, cross-checked against play-by-play

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NAmSfMG3LfdCw9DC5pxByG"
```

---
### Task 3: Scoring model and compare slot (`:core:model`)

**Files:**
- Create: `core/model/src/main/kotlin/dev/gridiron/core/model/Scoring.kt`
- Create: `core/model/src/main/kotlin/dev/gridiron/core/model/CompareSlot.kt`
- Test: `core/model/src/test/kotlin/dev/gridiron/core/model/ScoringProfileTest.kt`

**Interfaces:**
- Produces: `ScoringGroup`, `ScoringRule` (25 entries, each with `group` and `label`), `BonusStat`, `YardageBonus(stat, min, maxExclusive, points)` with `applies(yards: Double)`, `ScoringProfile(id, name, weights, receptionByPosition, yardageBonuses, basedOn)` with `weight(rule)`, `receptionWeight(position: Position?)`, `isPreset`, `RECEPTION_POSITIONS`; `ScoringPresets.PPR / HALF_PPR / STANDARD / all / byId(id)`; `CompareSlot(playerId, season, weeks)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.gridiron.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ScoringProfileTest {

    @Test
    fun `presets use ESPN defaults and differ only in reception points`() {
        val ppr = ScoringPresets.PPR
        assertEquals(0.04, ppr.weight(ScoringRule.PASS_YARD))
        assertEquals(4.0, ppr.weight(ScoringRule.PASS_TD))
        assertEquals(-2.0, ppr.weight(ScoringRule.INTERCEPTION))
        assertEquals(0.1, ppr.weight(ScoringRule.RUSH_YARD))
        assertEquals(6.0, ppr.weight(ScoringRule.REC_TD))
        assertEquals(2.0, ppr.weight(ScoringRule.RUSH_2PT))
        assertEquals(-2.0, ppr.weight(ScoringRule.FUMBLE_LOST))
        assertEquals(0.0, ppr.weight(ScoringRule.PASS_FIRST_DOWN))
        assertEquals(1.0, ScoringPresets.PPR.weight(ScoringRule.RECEPTION))
        assertEquals(0.5, ScoringPresets.HALF_PPR.weight(ScoringRule.RECEPTION))
        assertEquals(0.0, ScoringPresets.STANDARD.weight(ScoringRule.RECEPTION))
        val others = ScoringRule.entries - ScoringRule.RECEPTION
        for (rule in others) {
            assertEquals(ppr.weight(rule), ScoringPresets.STANDARD.weight(rule), rule.name)
        }
        assertTrue(ScoringPresets.all.all { it.yardageBonuses.isEmpty() && it.isPreset })
        assertEquals(listOf("preset:ppr", "preset:half", "preset:standard"), ScoringPresets.all.map { it.id })
    }

    @Test
    fun `reception weight uses the position override, else the base rule`() {
        val tePremium = ScoringPresets.PPR.copy(id = "u1", name = "TE premium", receptionByPosition = mapOf(Position.TE to 1.5))
        assertEquals(1.5, tePremium.receptionWeight(Position.TE))
        assertEquals(1.0, tePremium.receptionWeight(Position.WR))
        assertEquals(1.0, tePremium.receptionWeight(null))
        assertFalse(tePremium.isPreset)
    }

    @Test
    fun `invalid profiles are rejected`() {
        val base = ScoringPresets.PPR.copy(id = "u1", name = "Mine")
        assertThrows<IllegalArgumentException> { base.copy(name = " ") }
        assertThrows<IllegalArgumentException> { base.copy(id = "") }
        assertThrows<IllegalArgumentException> { base.copy(weights = mapOf(ScoringRule.PASS_TD to Double.NaN)) }
        assertThrows<IllegalArgumentException> { base.copy(receptionByPosition = mapOf(Position.QB to 1.0)) }
        assertThrows<IllegalArgumentException> { base.copy(receptionByPosition = mapOf(Position.TE to Double.POSITIVE_INFINITY)) }
    }

    @Test
    fun `bonus ranges include the minimum and exclude the maximum`() {
        val tier = YardageBonus(BonusStat.RUSHING_YARDS, min = 100, maxExclusive = 200, points = 3.0)
        assertFalse(tier.applies(99.0))
        assertTrue(tier.applies(100.0))
        assertTrue(tier.applies(199.0))
        assertFalse(tier.applies(200.0))
        val open = YardageBonus(BonusStat.RUSHING_YARDS, min = 200, maxExclusive = null, points = 6.0)
        assertTrue(open.applies(200.0))
        assertTrue(open.applies(412.0))
    }

    @Test
    fun `invalid bonuses are rejected`() {
        assertThrows<IllegalArgumentException> { YardageBonus(BonusStat.PASSING_YARDS, -1, null, 1.0) }
        assertThrows<IllegalArgumentException> { YardageBonus(BonusStat.PASSING_YARDS, 300, 300, 1.0) }
        assertThrows<IllegalArgumentException> { YardageBonus(BonusStat.PASSING_YARDS, 300, 200, 1.0) }
        assertThrows<IllegalArgumentException> { YardageBonus(BonusStat.PASSING_YARDS, 300, null, Double.NaN) }
    }

    @Test
    fun `every rule belongs to a group and has a label`() {
        assertEquals(25, ScoringRule.entries.size)
        assertTrue(ScoringRule.entries.all { it.label.isNotBlank() })
        assertEquals(ScoringGroup.TURNOVERS, ScoringRule.FUMBLE_LOST.group)
    }

    @Test
    fun `presets are found by id`() {
        assertEquals(ScoringPresets.HALF_PPR, ScoringPresets.byId("preset:half"))
        assertNull(ScoringPresets.byId("u1"))
    }

    @Test
    fun `a compare slot needs a player`() {
        assertThrows<IllegalArgumentException> { CompareSlot("", 2025, WeekRange(1, 18)) }
        assertEquals(CompareSlot("p", 2025, WeekRange(1, 8)), CompareSlot("p", 2025, WeekRange(1, 8)))
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew :core:model:test --console=plain`
Expected: compilation FAILS (unresolved `ScoringPresets`, `ScoringRule`, and so on).

- [ ] **Step 3: Implement**

`core/model/src/main/kotlin/dev/gridiron/core/model/Scoring.kt`:

```kotlin
package dev.gridiron.core.model

/** Where a rule appears in the scoring editor. */
public enum class ScoringGroup(public val label: String) {
    PASSING("Passing"),
    RUSHING("Rushing"),
    RECEIVING("Receiving"),
    TURNOVERS("Turnovers"),
}

/**
 * Points per unit of one stat. Kicking, team defense and IDP are absent on
 * purpose: the database holds QB, RB, WR and TE stats only.
 */
public enum class ScoringRule(public val group: ScoringGroup, public val label: String) {
    PASS_YARD(ScoringGroup.PASSING, "Per passing yard"),
    PASS_TD(ScoringGroup.PASSING, "Passing TD"),
    INTERCEPTION(ScoringGroup.PASSING, "Interception thrown"),
    PASS_2PT(ScoringGroup.PASSING, "2-pt conversion pass"),
    COMPLETION(ScoringGroup.PASSING, "Completion"),
    INCOMPLETION(ScoringGroup.PASSING, "Incompletion"),
    PASS_FIRST_DOWN(ScoringGroup.PASSING, "Passing first down"),
    SACK_TAKEN(ScoringGroup.PASSING, "Sack taken"),
    PASS_TD_40(ScoringGroup.PASSING, "40+ yd TD pass bonus"),
    PASS_TD_50(ScoringGroup.PASSING, "50+ yd TD pass bonus"),
    RUSH_YARD(ScoringGroup.RUSHING, "Per rushing yard"),
    RUSH_TD(ScoringGroup.RUSHING, "Rushing TD"),
    RUSH_2PT(ScoringGroup.RUSHING, "2-pt conversion run"),
    CARRY(ScoringGroup.RUSHING, "Carry"),
    RUSH_FIRST_DOWN(ScoringGroup.RUSHING, "Rushing first down"),
    RUSH_TD_40(ScoringGroup.RUSHING, "40+ yd TD run bonus"),
    RUSH_TD_50(ScoringGroup.RUSHING, "50+ yd TD run bonus"),
    RECEPTION(ScoringGroup.RECEIVING, "Reception"),
    REC_YARD(ScoringGroup.RECEIVING, "Per receiving yard"),
    REC_TD(ScoringGroup.RECEIVING, "Receiving TD"),
    REC_2PT(ScoringGroup.RECEIVING, "2-pt conversion catch"),
    REC_FIRST_DOWN(ScoringGroup.RECEIVING, "Receiving first down"),
    REC_TD_40(ScoringGroup.RECEIVING, "40+ yd TD catch bonus"),
    REC_TD_50(ScoringGroup.RECEIVING, "50+ yd TD catch bonus"),
    FUMBLE_LOST(ScoringGroup.TURNOVERS, "Fumble lost"),
}

public enum class BonusStat(public val label: String) {
    PASSING_YARDS("Passing yards"),
    RUSHING_YARDS("Rushing yards"),
    RECEIVING_YARDS("Receiving yards"),
    RUSH_REC_YARDS("Rushing + receiving yards"),
}

/**
 * [points] for a game in which [stat] lands in `min until maxExclusive`, or
 * `min` and up when [maxExclusive] is null. ESPN and Sleeper both define tiers
 * as ranges ("100-199", "200+"), so ranges are the primitive. Overlapping
 * ranges are allowed; each one that matches applies.
 */
public data class YardageBonus(
    val stat: BonusStat,
    val min: Int,
    val maxExclusive: Int?,
    val points: Double,
) {
    init {
        require(min >= 0) { "bonus minimum must not be negative, was $min" }
        require(maxExclusive == null || maxExclusive > min) { "bonus range $min until $maxExclusive is empty" }
        require(points.isFinite()) { "bonus points must be finite, was $points" }
    }

    public fun applies(yards: Double): Boolean = yards >= min && (maxExclusive == null || yards < maxExclusive)
}

/**
 * One league's scoring. Fantasy points are never stored; every query applies
 * the active profile to stat components, so switching leagues is instant and
 * works for any week range.
 *
 * @property receptionByPosition Reception points by position (TE premium);
 *   positions without an entry use [ScoringRule.RECEPTION].
 * @property basedOn The preset this profile was copied from, for "Reset to preset".
 */
public data class ScoringProfile(
    val id: String,
    val name: String,
    val weights: Map<ScoringRule, Double>,
    val receptionByPosition: Map<Position, Double> = emptyMap(),
    val yardageBonuses: List<YardageBonus> = emptyList(),
    val basedOn: String? = null,
) {
    init {
        require(id.isNotBlank()) { "profile id must not be blank" }
        require(name.isNotBlank()) { "profile name must not be blank" }
        require(weights.values.all { it.isFinite() }) { "weights must be finite: $weights" }
        require(receptionByPosition.keys.all { it in RECEPTION_POSITIONS }) {
            "reception overrides apply to RB, WR and TE only: ${receptionByPosition.keys}"
        }
        require(receptionByPosition.values.all { it.isFinite() }) { "reception weights must be finite" }
    }

    public fun weight(rule: ScoringRule): Double = weights[rule] ?: 0.0

    public fun receptionWeight(position: Position?): Double =
        receptionByPosition[position] ?: weight(ScoringRule.RECEPTION)

    public val isPreset: Boolean get() = ScoringPresets.byId(id) != null

    public companion object {
        public val RECEPTION_POSITIONS: Set<Position> = setOf(Position.RB, Position.WR, Position.TE)
    }
}

/** ESPN's default scoring, in its three reception flavors. Immutable; copy one to customize. */
public object ScoringPresets {
    private fun espn(id: String, name: String, reception: Double) = ScoringProfile(
        id = id,
        name = name,
        weights = mapOf(
            ScoringRule.PASS_YARD to 0.04,
            ScoringRule.PASS_TD to 4.0,
            ScoringRule.INTERCEPTION to -2.0,
            ScoringRule.PASS_2PT to 2.0,
            ScoringRule.RUSH_YARD to 0.1,
            ScoringRule.RUSH_TD to 6.0,
            ScoringRule.RUSH_2PT to 2.0,
            ScoringRule.RECEPTION to reception,
            ScoringRule.REC_YARD to 0.1,
            ScoringRule.REC_TD to 6.0,
            ScoringRule.REC_2PT to 2.0,
            ScoringRule.FUMBLE_LOST to -2.0,
        ),
    )

    public val PPR: ScoringProfile = espn("preset:ppr", "PPR", 1.0)
    public val HALF_PPR: ScoringProfile = espn("preset:half", "Half PPR", 0.5)
    public val STANDARD: ScoringProfile = espn("preset:standard", "Standard", 0.0)

    public val all: List<ScoringProfile> = listOf(PPR, HALF_PPR, STANDARD)

    public fun byId(id: String): ScoringProfile? = all.firstOrNull { it.id == id }
}
```

`core/model/src/main/kotlin/dev/gridiron/core/model/CompareSlot.kt`:

```kotlin
package dev.gridiron.core.model

/**
 * One column on the Compare screen: a player over one season's week range.
 * The same player may fill several slots with different seasons or ranges,
 * which is how a player is compared with himself.
 */
public data class CompareSlot(val playerId: String, val season: Int, val weeks: WeekRange) {
    init {
        require(playerId.isNotBlank()) { "a compare slot needs a player" }
    }
}
```

- [ ] **Step 4: Run and confirm pass**

Run: `./gradlew :core:model:test --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/model
git commit -m "model: scoring profiles, presets, yardage bonuses and compare slots

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NAmSfMG3LfdCw9DC5pxByG"
```

---

### Task 4: Fantasy points in the query builder (`:core:statquery`)

**Files:**
- Modify: `core/statquery/src/main/kotlin/dev/gridiron/core/statquery/Component.kt`
- Modify: `core/statquery/src/main/kotlin/dev/gridiron/core/statquery/Aggregate.kt`
- Create: `core/statquery/src/main/kotlin/dev/gridiron/core/statquery/Scoring.kt`
- Modify: `core/statquery/src/main/kotlin/dev/gridiron/core/statquery/StatColumn.kt`
- Modify: `core/statquery/src/main/kotlin/dev/gridiron/core/statquery/StatQuerySpec.kt`
- Modify: `core/statquery/src/main/kotlin/dev/gridiron/core/statquery/StatQueryBuilder.kt`
- Test: create `core/statquery/src/test/kotlin/dev/gridiron/core/statquery/ScoringQueryTest.kt`; modify `SqlSafetyTest.kt`

**Interfaces:**
- Consumes: Task 3's `ScoringProfile`, `ScoringRule`, `BonusStat`, `Position`; Task 1's metric ids.
- Produces: `Components.PASSING_FIRST_DOWNS` … `Components.X_INTERCEPTIONS` (28 constants, names = metric id upper-cased); `Aggregate.Scored(output: ScoredOutput)`; `ScoredOutput { FANTASY_POINTS, EXPECTED_FANTASY_POINTS, OVER_EXPECTED }`; `StatColumn.FANTASY_POINTS` (`"fantasy_points"`), `EXPECTED_FANTASY_POINTS` (`"expected_fantasy_points"`), `FPOE` (`"fpoe"`); `StatQuerySpec.scoring: ScoringProfile? = null`; `StatColumn.isFantasy: Boolean`; internal `RULE_INPUTS`, `BONUS_INPUTS`, `SCORING_COMPONENTS`.

**Query shape** when a fantasy column is planned (ahead of the existing `agg`):

```sql
WITH wk AS (          -- one row per player-week: every scoring component pivoted
  SELECT s.player_id, SUM(CASE WHEN s.metric_id = ? THEN s.value END) AS w0, ...
  FROM player_week_stat s
  WHERE s.metric_id IN (?, ...) AND s.season = ? AND s.week BETWEEN ? AND ?
  GROUP BY s.player_id, s.week
), fw AS (            -- points per player-week, so bonuses see single games
  SELECT wk.player_id, (? * COALESCE(wk.w3, 0) + ...) AS fp, (...) AS xfp
  FROM wk JOIN player p ON p.player_id = wk.player_id
), fsum AS (
  SELECT player_id, SUM(fp) AS fp, SUM(xfp) AS xfp, SUM(fp - xfp) AS oe
  FROM fw GROUP BY player_id
), agg AS ( ...existing... ), base AS ( ... LEFT JOIN fsum ON fsum.player_id = agg.player_id ... )
```

Missing components (sparse zeros) read as 0 through `COALESCE`. A player with games in range but no scoring stats gets 0 points, not null.

- [ ] **Step 1: Write the failing tests**

Create `ScoringQueryTest.kt`:

```kotlin
package dev.gridiron.core.statquery

import dev.gridiron.core.model.BonusStat
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.ScoringRule
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.model.YardageBonus
import dev.gridiron.core.statquery.StatColumn.EXPECTED_FANTASY_POINTS
import dev.gridiron.core.statquery.StatColumn.FANTASY_POINTS
import dev.gridiron.core.statquery.StatColumn.FPOE
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import dev.gridiron.core.statquery.Components as C

private const val EPS = 1e-9

class ScoringQueryTest {
    private lateinit var db: FixtureDb

    @BeforeEach
    fun setUp() {
        db = FixtureDb()
    }

    @AfterEach
    fun tearDown() = db.close()

    private fun fantasy(
        profile: ScoringProfile,
        vararg columns: StatColumn = arrayOf(FANTASY_POINTS),
        weeks: WeekRange = WeekRange(1, 18),
        mode: ValueMode = ValueMode.TOTAL,
        percentiles: Boolean = false,
    ) = StatQuerySpec(
        season = 2025, weeks = weeks, columns = columns.toList(), scoring = profile,
        mode = mode, percentiles = percentiles,
    )

    private fun custom(vararg weights: Pair<ScoringRule, Double>, bonuses: List<YardageBonus> = emptyList(),
                       reception: Map<Position, Double> = emptyMap()) =
        ScoringProfile("u1", "Custom", weights.toMap(), reception, bonuses)

    @Test
    fun `PPR scores a receiver's week`() {
        db.player("wr1", "Alpha Receiver")
        db.week("wr1", 1, C.RECEPTIONS to 6, C.RECEIVING_YARDS to 85, C.RECEIVING_TDS to 1, C.FUMBLES_LOST to 1)
        val row = db.grid(fantasy(ScoringPresets.PPR)).single()
        // 6 + 8.5 + 6 - 2
        assertEquals(18.5, row.value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `reception points follow the player's position`() {
        val profile = custom(ScoringRule.RECEPTION to 1.0, reception = mapOf(Position.TE to 1.5))
        db.player("te1", "Tight End", position = "TE")
        db.player("wr1", "Wide Out", position = "WR")
        db.week("te1", 1, C.RECEPTIONS to 4)
        db.week("wr1", 1, C.RECEPTIONS to 4)
        val rows = db.grid(fantasy(profile)).associateBy { it.playerId }
        assertEquals(6.0, rows.getValue("te1").value(FANTASY_POINTS)!!, EPS)
        assertEquals(4.0, rows.getValue("wr1").value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `incompletions are attempts minus completions`() {
        db.player("qb1", "Quarter Back", position = "QB")
        db.week("qb1", 1, C.ATTEMPTS to 30, C.COMPLETIONS to 20)
        val row = db.grid(fantasy(custom(ScoringRule.INCOMPLETION to -0.5))).single()
        assertEquals(-5.0, row.value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `yardage bonuses apply per game at the range edges`() {
        val profile = custom(
            bonuses = listOf(
                YardageBonus(BonusStat.RUSHING_YARDS, 100, 200, 3.0),
                YardageBonus(BonusStat.RUSHING_YARDS, 200, null, 6.0),
            ),
        )
        db.player("rb1", "Running Back", position = "RB")
        db.week("rb1", 1, C.RUSHING_YARDS to 99)
        db.week("rb1", 2, C.RUSHING_YARDS to 100)
        db.week("rb1", 3, C.RUSHING_YARDS to 199)
        db.week("rb1", 4, C.RUSHING_YARDS to 200)
        val row = db.grid(fantasy(profile)).single()
        // weeks 2 and 3 earn 3; week 4 earns 6. The 598-yard range total earns nothing extra.
        assertEquals(12.0, row.value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `combined yardage bonus counts receiving yards when there are no rushing yards`() {
        val profile = custom(bonuses = listOf(YardageBonus(BonusStat.RUSH_REC_YARDS, 100, null, 2.0)))
        db.player("wr1", "Alpha Receiver")
        db.week("wr1", 1, C.RECEIVING_YARDS to 110)
        assertEquals(2.0, db.grid(fantasy(profile)).single().value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `expected points use expected components and FPOE is the difference`() {
        db.player("wr1", "Alpha Receiver")
        db.week(
            "wr1", 1,
            C.RECEPTIONS to 6, C.RECEIVING_YARDS to 85, C.RECEIVING_TDS to 1, C.FUMBLES_LOST to 1,
            C.X_RECEPTIONS to 5.5, C.X_RECEIVING_YARDS to 70, C.X_RECEIVING_TDS to 0.5,
        )
        val row = db.grid(fantasy(ScoringPresets.PPR, FANTASY_POINTS, EXPECTED_FANTASY_POINTS, FPOE)).single()
        assertEquals(18.5, row.value(FANTASY_POINTS)!!, EPS)
        // 5.5 + 7.0 + 3.0; fumbles have no expectation
        assertEquals(15.5, row.value(EXPECTED_FANTASY_POINTS)!!, EPS)
        assertEquals(3.0, row.value(FPOE)!!, EPS)
    }

    @Test
    fun `per game divides fantasy points by games`() {
        db.player("wr1", "Alpha Receiver")
        db.week("wr1", 1, C.RECEPTIONS to 4)
        db.week("wr1", 2, C.RECEPTIONS to 8)
        val row = db.grid(fantasy(ScoringPresets.PPR, mode = ValueMode.PER_GAME)).single()
        assertEquals(6.0, row.value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `a player who played but scored nothing has zero points, not null`() {
        db.player("wr1", "Alpha Receiver")
        db.week("wr1", 1, C.TARGETS to 1)
        assertEquals(0.0, db.grid(fantasy(ScoringPresets.PPR)).single().value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `negative points sort and rank below zero`() {
        val profile = custom(ScoringRule.INCOMPLETION to -1.0, ScoringRule.PASS_TD to 4.0)
        db.player("qb1", "Good Passer", position = "QB")
        db.player("qb2", "Bad Passer", position = "QB")
        db.week("qb1", 1, C.PASSING_TDS to 3, C.ATTEMPTS to 30, C.COMPLETIONS to 25) // 12 - 5 = 7
        db.week("qb2", 1, C.ATTEMPTS to 30, C.COMPLETIONS to 10) // -20
        val rows = db.grid(fantasy(profile, percentiles = true))
        assertEquals(listOf("qb1", "qb2"), rows.map { it.playerId })
        assertEquals(-20.0, rows[1].value(FANTASY_POINTS)!!, EPS)
        assertEquals(1.0, rows[0].percentile(FANTASY_POINTS)!!, EPS)
        assertEquals(0.0, rows[1].percentile(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `weeks outside the range are not scored`() {
        db.player("wr1", "Alpha Receiver")
        db.week("wr1", 1, C.RECEPTIONS to 4)
        db.week("wr1", 2, C.RECEPTIONS to 100)
        val row = db.grid(fantasy(ScoringPresets.PPR, weeks = WeekRange(1, 1))).single()
        assertEquals(4.0, row.value(FANTASY_POINTS)!!, EPS)
    }

    @Test
    fun `fantasy columns need a profile`() {
        assertThrows<IllegalArgumentException> {
            StatQuerySpec(2025, WeekRange(1, 18), listOf(StatColumn.TARGETS), sort = listOf(Sort(FANTASY_POINTS)))
        }
    }

    @Test
    fun `every rule is mapped to components`() {
        assertEquals(ScoringRule.entries.toSet(), RULE_INPUTS.keys)
        assertEquals(BonusStat.entries.toSet(), BONUS_INPUTS.keys)
        assertTrue(SCORING_COMPONENTS.none { it == C.GAMES })
    }

    @Test
    fun `count works with a fantasy filter`() {
        db.player("wr1", "Alpha Receiver")
        db.player("wr2", "Beta Receiver")
        db.week("wr1", 1, C.RECEPTIONS to 10)
        db.week("wr2", 1, C.RECEPTIONS to 2)
        val spec = fantasy(ScoringPresets.PPR, StatColumn.TARGETS)
            .copy(filters = listOf(Filter(FANTASY_POINTS, Condition.AtLeast(5.0))))
        assertEquals(1, db.count(spec))
    }
}
```

In `SqlSafetyTest.kt`, the random spec generator and the "every column" tests request fantasy columns, which now need a profile. Add a random profile to `Random.spec()`:

```kotlin
            scoring = ScoringProfile(
                id = "u${nextInt(1000)}",
                name = hostile(),
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
```

(A hostile profile name must never reach SQL: the name isn't used by the builder, and the quote/semicolon assertions prove it.) Add `scoring = ScoringPresets.PPR` to the specs in `metric ids are bound, never written into SQL text` and `every column can be requested at once`, and in the former also check the scoring components:

```kotlin
        val ids = (StatColumn.entries.flatMap { it.aggregate.components } + SCORING_COMPONENTS).map { it.id }.toSet()
```

Add the imports these need (`BonusStat`, `ScoringPresets`, `ScoringProfile`, `ScoringRule`, `YardageBonus`).

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew :core:statquery:test --console=plain`
Expected: compilation FAILS (unresolved `FANTASY_POINTS`, `C.FUMBLES_LOST`, `scoring`, `RULE_INPUTS`).

- [ ] **Step 3: Add the component constants**

In `Component.kt`, inside `object Components`, after `TOTAL_EPA`:

```kotlin
    // Scoring inputs. Internal and sparse (absent means zero); read only by
    // the scoring step, which applies the spec's profile per player-week.
    public val PASSING_FIRST_DOWNS: Component = Component("passing_first_downs")
    public val RUSHING_FIRST_DOWNS: Component = Component("rushing_first_downs")
    public val RECEIVING_FIRST_DOWNS: Component = Component("receiving_first_downs")
    public val PASSING_2PT: Component = Component("passing_2pt")
    public val RUSHING_2PT: Component = Component("rushing_2pt")
    public val RECEIVING_2PT: Component = Component("receiving_2pt")
    public val FUMBLES_LOST: Component = Component("fumbles_lost")
    public val PASSING_TDS_40: Component = Component("passing_tds_40")
    public val PASSING_TDS_50: Component = Component("passing_tds_50")
    public val RUSHING_TDS_40: Component = Component("rushing_tds_40")
    public val RUSHING_TDS_50: Component = Component("rushing_tds_50")
    public val RECEIVING_TDS_40: Component = Component("receiving_tds_40")
    public val RECEIVING_TDS_50: Component = Component("receiving_tds_50")

    // The opportunity model's expectations for the same player-week.
    public val X_COMPLETIONS: Component = Component("x_completions")
    public val X_RECEPTIONS: Component = Component("x_receptions")
    public val X_PASSING_YARDS: Component = Component("x_passing_yards")
    public val X_RUSHING_YARDS: Component = Component("x_rushing_yards")
    public val X_RECEIVING_YARDS: Component = Component("x_receiving_yards")
    public val X_PASSING_TDS: Component = Component("x_passing_tds")
    public val X_RUSHING_TDS: Component = Component("x_rushing_tds")
    public val X_RECEIVING_TDS: Component = Component("x_receiving_tds")
    public val X_PASSING_2PT: Component = Component("x_passing_2pt")
    public val X_RUSHING_2PT: Component = Component("x_rushing_2pt")
    public val X_RECEIVING_2PT: Component = Component("x_receiving_2pt")
    public val X_PASSING_FIRST_DOWNS: Component = Component("x_passing_first_downs")
    public val X_RUSHING_FIRST_DOWNS: Component = Component("x_rushing_first_downs")
    public val X_RECEIVING_FIRST_DOWNS: Component = Component("x_receiving_first_downs")
    public val X_INTERCEPTIONS: Component = Component("x_interceptions")
```

- [ ] **Step 4: Add the scored aggregate**

In `Aggregate.kt`, inside `sealed interface Aggregate`, after `ClampedWeightedSum`:

```kotlin
    /**
     * A total from the scoring step, which applies the spec's scoring profile
     * to each player-week before summing, so per-game bonuses see single games
     * rather than range totals. Reads no stored components directly.
     */
    public data class Scored(val output: ScoredOutput) : Aggregate {
        override val components: Set<Component> get() = emptySet()
        override val scalesWithGames: Boolean get() = true
        override fun toSql(ref: (Component) -> String): String = ref(output.pseudo)
    }
```

After the interface, at file level:

```kotlin
/** The scoring step's outputs, summed per player over the range. */
public enum class ScoredOutput(internal val alias: String) {
    FANTASY_POINTS("fp"),
    EXPECTED_FANTASY_POINTS("xfp"),
    OVER_EXPECTED("oe"),
    ;

    /**
     * Stands in for this output where [Aggregate.toSql] expects a component.
     * `@` never appears in a metric id, so it can't collide with a stored one.
     */
    internal val pseudo: Component get() = Component("@$alias")
}
```

- [ ] **Step 5: Add the three columns**

In `StatColumn.kt`, add the import `dev.gridiron.core.statquery.Aggregate.Scored`, and add after the `TOTAL_EPA` entry (before the `;`):

```kotlin
    // Fantasy: scored per player-week from the spec's profile.
    FANTASY_POINTS("fantasy_points", Scored(ScoredOutput.FANTASY_POINTS)),
    EXPECTED_FANTASY_POINTS("expected_fantasy_points", Scored(ScoredOutput.EXPECTED_FANTASY_POINTS)),
    FPOE("fpoe", Scored(ScoredOutput.OVER_EXPECTED)),
```

and inside the enum body, beside `sample`:

```kotlin
    /** Computed from the spec's scoring profile rather than stored components. */
    public val isFantasy: Boolean get() = aggregate is Scored
```

`sample` stays `null` for all three (they take the population's qualifier).

- [ ] **Step 6: Add `scoring` to the spec**

In `StatQuerySpec.kt`, add the property after `name` (and its KDoc line `@property scoring The profile fantasy columns are scored with. Required when any column, sort, filter or qualifier is a fantasy column.`):

```kotlin
    val scoring: ScoringProfile? = null,
```

(import `dev.gridiron.core.model.ScoringProfile`), and in `init`:

```kotlin
        val usesFantasy = (columns + sort.map { it.column } + filters.map { it.column } + qualifiers.map { it.column })
            .any { it.isFantasy }
        require(!usesFantasy || scoring != null) { "fantasy columns need a scoring profile" }
```

- [ ] **Step 7: Write the rule map and the scoring CTE**

Create `Scoring.kt`:

```kotlin
package dev.gridiron.core.statquery

import dev.gridiron.core.model.BonusStat
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.ScoringRule
import dev.gridiron.core.statquery.Components as C

/** A component and the sign it enters a rule with: incompletions are attempts minus completions. */
internal data class Term(val component: Component, val sign: Double = 1.0)

/**
 * What a rule is scored on. [expected] is empty when the opportunity model has
 * no counterpart (sacks, fumbles, carries, incompletions, long-TD bonuses);
 * those rules add nothing to xFP, so FPOE credits big plays and charges fumbles.
 */
internal data class RuleInputs(val actual: List<Term>, val expected: List<Term>)

private fun on(actual: Component, expected: Component? = null) =
    RuleInputs(listOf(Term(actual)), listOfNotNull(expected?.let { Term(it) }))

internal val RULE_INPUTS: Map<ScoringRule, RuleInputs> = mapOf(
    ScoringRule.PASS_YARD to on(C.PASSING_YARDS, C.X_PASSING_YARDS),
    ScoringRule.PASS_TD to on(C.PASSING_TDS, C.X_PASSING_TDS),
    ScoringRule.INTERCEPTION to on(C.INTERCEPTIONS, C.X_INTERCEPTIONS),
    ScoringRule.PASS_2PT to on(C.PASSING_2PT, C.X_PASSING_2PT),
    ScoringRule.COMPLETION to on(C.COMPLETIONS, C.X_COMPLETIONS),
    ScoringRule.INCOMPLETION to RuleInputs(listOf(Term(C.ATTEMPTS), Term(C.COMPLETIONS, -1.0)), emptyList()),
    ScoringRule.PASS_FIRST_DOWN to on(C.PASSING_FIRST_DOWNS, C.X_PASSING_FIRST_DOWNS),
    ScoringRule.SACK_TAKEN to on(C.SACKS_TAKEN),
    ScoringRule.PASS_TD_40 to on(C.PASSING_TDS_40),
    ScoringRule.PASS_TD_50 to on(C.PASSING_TDS_50),
    ScoringRule.RUSH_YARD to on(C.RUSHING_YARDS, C.X_RUSHING_YARDS),
    ScoringRule.RUSH_TD to on(C.RUSHING_TDS, C.X_RUSHING_TDS),
    ScoringRule.RUSH_2PT to on(C.RUSHING_2PT, C.X_RUSHING_2PT),
    ScoringRule.CARRY to on(C.CARRIES),
    ScoringRule.RUSH_FIRST_DOWN to on(C.RUSHING_FIRST_DOWNS, C.X_RUSHING_FIRST_DOWNS),
    ScoringRule.RUSH_TD_40 to on(C.RUSHING_TDS_40),
    ScoringRule.RUSH_TD_50 to on(C.RUSHING_TDS_50),
    ScoringRule.RECEPTION to on(C.RECEPTIONS, C.X_RECEPTIONS),
    ScoringRule.REC_YARD to on(C.RECEIVING_YARDS, C.X_RECEIVING_YARDS),
    ScoringRule.REC_TD to on(C.RECEIVING_TDS, C.X_RECEIVING_TDS),
    ScoringRule.REC_2PT to on(C.RECEIVING_2PT, C.X_RECEIVING_2PT),
    ScoringRule.REC_FIRST_DOWN to on(C.RECEIVING_FIRST_DOWNS, C.X_RECEIVING_FIRST_DOWNS),
    ScoringRule.REC_TD_40 to on(C.RECEIVING_TDS_40),
    ScoringRule.REC_TD_50 to on(C.RECEIVING_TDS_50),
    ScoringRule.FUMBLE_LOST to on(C.FUMBLES_LOST),
)

internal val BONUS_INPUTS: Map<BonusStat, List<Component>> = mapOf(
    BonusStat.PASSING_YARDS to listOf(C.PASSING_YARDS),
    BonusStat.RUSHING_YARDS to listOf(C.RUSHING_YARDS),
    BonusStat.RECEIVING_YARDS to listOf(C.RECEIVING_YARDS),
    BonusStat.RUSH_REC_YARDS to listOf(C.RUSHING_YARDS, C.RECEIVING_YARDS),
)

/** Every component the scoring step reads, sorted so equal profiles give identical SQL. */
internal val SCORING_COMPONENTS: List<Component> =
    (RULE_INPUTS.values.flatMap { it.actual + it.expected }.map { it.component } + BONUS_INPUTS.values.flatten())
        .distinct()
        .sortedBy { it.id }
```

In `StatQueryBuilder.kt`, change `Plan`:

```kotlin
private class Plan(columns: List<StatColumn>) {
    val columns: List<StatColumn> = columns.distinct()

    /** Whether the scoring step (`wk`, `fw`, `fsum`) runs. */
    val scored: Boolean = this.columns.any { it.isFantasy }

    // (components, games unchanged)

    fun ref(component: Component): String {
        ScoredOutput.entries.firstOrNull { it.pseudo == component }?.let {
            // Played but scored nothing: zero points, not unknown.
            return "COALESCE(fsum.${it.alias}, 0)"
        }
        val i = components.indexOf(component)
        check(i >= 0) { "$component is not planned" }
        return "agg.k$i"
    }
    // (index, valueExpr unchanged)
}
```

In `SqlWriter`, change the start and end of `aggregateAndBase`:

```kotlin
    fun aggregateAndBase(spec: StatQuerySpec, plan: Plan) {
        if (plan.scored) scoring(spec, checkNotNull(spec.scoring))
        line(if (plan.scored) ", agg AS (" else "WITH agg AS (")
        // ... unchanged through `JOIN player p ON p.player_id = agg.player_id` ...
        if (plan.scored) line("  LEFT JOIN fsum ON fsum.player_id = agg.player_id")
        line("  WHERE ${plan.games} >= ${int(spec.minGames)}")
        line(")")
    }
```

Add to `SqlWriter` (bind helpers append in call order, so every value is computed in the order its `?` appears in the text):

```kotlin
    /** Per-week points under [profile]: `wk` pivots, `fw` scores each week, `fsum` totals. */
    fun scoring(spec: StatQuerySpec, profile: ScoringProfile) {
        val w = { c: Component -> "COALESCE(wk.w${SCORING_COMPONENTS.indexOf(c)}, 0)" }
        line("WITH wk AS (")
        line("  SELECT s.player_id")
        SCORING_COMPONENTS.forEachIndexed { i, c ->
            line("       , SUM(CASE WHEN s.metric_id = ${text(c.id)} THEN s.value END) AS w$i")
        }
        line("  FROM player_week_stat s")
        line("  WHERE s.metric_id IN (${SCORING_COMPONENTS.joinToString(", ") { text(it.id) }})")
        line("    AND s.season = ${int(spec.season)}")
        line("    AND s.week BETWEEN ${int(spec.weeks.first)} AND ${int(spec.weeks.last)}")
        line("  GROUP BY s.player_id, s.week")
        line("), fw AS (")
        line("  SELECT wk.player_id")
        line("       , ${points(profile, expected = false, w)} AS fp")
        line("       , ${points(profile, expected = true, w)} AS xfp")
        line("  FROM wk")
        line("  JOIN player p ON p.player_id = wk.player_id")
        line("), fsum AS (")
        line("  SELECT player_id, SUM(fp) AS fp, SUM(xfp) AS xfp, SUM(fp - xfp) AS oe")
        line("  FROM fw")
        line("  GROUP BY player_id")
        line(")")
    }

    /**
     * One week's points. Every weight is bound, including zeros, so the SQL
     * shape depends only on the number of bonuses.
     */
    private fun points(profile: ScoringProfile, expected: Boolean, w: (Component) -> String): String {
        val terms = mutableListOf<String>()
        for (rule in ScoringRule.entries) {
            val inputs = RULE_INPUTS.getValue(rule)
            for (term in if (expected) inputs.expected else inputs.actual) {
                val weight = if (rule == ScoringRule.RECEPTION) {
                    receptionWeight(profile)
                } else {
                    real(profile.weight(rule) * term.sign)
                }
                terms += "$weight * ${w(term.component)}"
            }
        }
        if (!expected) {
            // Bonuses have no expectation; they only ever add to actual points.
            for (bonus in profile.yardageBonuses) {
                val yards = BONUS_INPUTS.getValue(bonus.stat).joinToString(" + ", "(", ")") { w(it) }
                val lower = int(bonus.min)
                val upper = bonus.maxExclusive?.let { " AND $yards < ${int(it)}" }.orEmpty()
                val points = real(bonus.points)
                terms += "CASE WHEN $yards >= $lower$upper THEN $points ELSE 0 END"
            }
        }
        return terms.joinToString(" + ", "(", ")")
    }

    /** Reception points by position: the TE-premium case. */
    private fun receptionWeight(profile: ScoringProfile): String {
        val rb = text(Position.RB.code)
        val rbPoints = real(profile.receptionWeight(Position.RB))
        val wr = text(Position.WR.code)
        val wrPoints = real(profile.receptionWeight(Position.WR))
        val te = text(Position.TE.code)
        val tePoints = real(profile.receptionWeight(Position.TE))
        val otherPoints = real(profile.weight(ScoringRule.RECEPTION))
        return "(CASE p.position WHEN $rb THEN $rbPoints WHEN $wr THEN $wrPoints " +
            "WHEN $te THEN $tePoints ELSE $otherPoints END)"
    }
```

Add imports in `StatQueryBuilder.kt`: `dev.gridiron.core.model.Position`, `dev.gridiron.core.model.ScoringProfile`, `dev.gridiron.core.model.ScoringRule`. Update the class KDoc's query-shape list with a step 0 describing `wk`/`fw`/`fsum`.

- [ ] **Step 8: Run all statquery tests**

Run: `./gradlew :core:statquery:test --console=plain`
Expected: all PASS, including `SqlSafetyTest`'s 2,000 fuzzed specs. (`RealDatabaseContractTest` runs only with `GRIDIRON_STATS_DB` set; it is updated in Task 6.)

- [ ] **Step 9: Commit**

```bash
git add core/statquery
git commit -m "statquery: score fantasy points per week in SQL from a bound profile

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NAmSfMG3LfdCw9DC5pxByG"
```

---

### Task 5: User preferences store (`:core:datastore`)

**Files:**
- Create: `core/datastore/build.gradle.kts`
- Create: `core/datastore/src/main/kotlin/dev/gridiron/core/datastore/UserPrefs.kt`
- Create: `core/datastore/src/main/kotlin/dev/gridiron/core/datastore/UserPrefsJson.kt`
- Create: `core/datastore/src/main/kotlin/dev/gridiron/core/datastore/UserPrefsStore.kt` (also holds `PrefsSource`)
- Modify: `settings.gradle.kts`, `gradle/libs.versions.toml`, `build.gradle.kts` (root)
- Test: `core/datastore/src/test/kotlin/dev/gridiron/core/datastore/UserPrefsStoreTest.kt`

**Interfaces:**
- Consumes: Task 3's `ScoringProfile`, `ScoringPresets`, `ScoringRule`, `YardageBonus`, `BonusStat`, `Position`, `CompareSlot`, `WeekRange`.
- Produces:
  - `public data class UserPrefs(val profiles: List<ScoringProfile>, val activeProfileId: String, val tray: List<CompareSlot>, val resetNotice: Boolean = false)` with `val active: ScoringProfile` and `companion object { val DEFAULT }`. `profiles` holds user profiles only; presets are never stored.
  - `public interface PrefsSource { val prefs: Flow<UserPrefs>; suspend fun update(transform: (UserPrefs) -> UserPrefs): UserPrefs }`. Repositories (Task 7) depend on this interface, so view-model tests on virtual time can use an in-memory fake instead of a file-backed DataStore, whose threads virtual time doesn't control.
  - `public class UserPrefsStore : PrefsSource { companion object { fun create(file: File, scope: CoroutineScope): UserPrefsStore } }`

- [ ] **Step 1: Add the module and dependencies**

`gradle/libs.versions.toml` — under `[versions]`:

```toml
datastore = "1.2.1"
serialization = "1.11.0"
```

under `[libraries]`:

```toml
androidx-datastore-core = { group = "androidx.datastore", name = "datastore-core", version.ref = "datastore" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }
```

under `[plugins]`:

```toml
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

Root `build.gradle.kts`: add `alias(libs.plugins.kotlin.serialization) apply false`.

`settings.gradle.kts`: add `include(":core:datastore")` after `:core:database`.

`core/datastore/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.gridiron.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

// Pure JVM: DataStore's core is multiplatform, so the store is tested on the
// JVM with the same code the phone runs.
dependencies {
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.datastore.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
}
```

Run `./gradlew :core:datastore:dependencies --configuration runtimeClasspath --console=plain | grep datastore` and confirm it resolves `datastore-core-jvm:1.2.1`.

- [ ] **Step 2: Write the failing tests**

```kotlin
package dev.gridiron.core.datastore

import dev.gridiron.core.model.BonusStat
import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringRule
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.model.YardageBonus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class UserPrefsStoreTest {
    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, "user_prefs.json")

    /** Opens a store, runs [block], and closes the store so the file can be reopened. */
    private fun <T> withStore(block: suspend (UserPrefsStore) -> T): T = runBlocking {
        val job = Job()
        try {
            block(UserPrefsStore.create(file, CoroutineScope(Dispatchers.IO + job)))
        } finally {
            job.cancelAndJoin()
        }
    }

    private val espnLeague = ScoringPresets.PPR.copy(
        id = "u1",
        name = "ESPN league",
        receptionByPosition = mapOf(Position.TE to 1.5),
        yardageBonuses = listOf(YardageBonus(BonusStat.RUSHING_YARDS, 100, 200, 3.0)),
        basedOn = ScoringPresets.PPR.id,
    )

    @Test
    fun `a missing file reads as the defaults`() {
        val prefs = withStore { it.prefs.first() }
        assertEquals(UserPrefs.DEFAULT, prefs)
        assertEquals(ScoringPresets.PPR, prefs.active)
    }

    @Test
    fun `profiles, active id and tray survive a reopen`() {
        val slot = CompareSlot("00-0039337", 2025, WeekRange(1, 8))
        withStore { store ->
            store.update { it.copy(profiles = listOf(espnLeague), activeProfileId = "u1", tray = listOf(slot, slot.copy(season = 2024))) }
        }
        val reread = withStore { it.prefs.first() }
        assertEquals(listOf(espnLeague), reread.profiles)
        assertEquals(espnLeague, reread.active)
        assertEquals(listOf(slot, slot.copy(season = 2024)), reread.tray)
        assertFalse(reread.resetNotice)
    }

    @Test
    fun `a corrupt file resets to defaults, keeps a copy and raises the notice`() {
        file.writeText("{ this is not json")
        val prefs = withStore { it.prefs.first() }
        assertEquals(UserPrefs.DEFAULT.copy(resetNotice = true), prefs)
        val kept = File(dir, "user_prefs.json.corrupt")
        assertTrue(kept.isFile)
        assertEquals("{ this is not json", kept.readText())
    }

    @Test
    fun `unknown rules and invalid entries are dropped, the rest kept`() {
        file.writeText(
            """
            {"formatVersion":1,"activeProfileId":"u2","profiles":[
              {"id":"u1","name":" ","weights":{"PASS_TD":6.0}},
              {"id":"u2","name":"Keeper","weights":{"PASS_TD":6.0,"KICK_FG_50":5.0},
               "receptionByPosition":{"TE":1.5,"K":9.0},
               "bonuses":[{"stat":"PASSING_YARDS","min":300,"points":3.0},{"stat":"PUNT_YARDS","min":1,"points":1.0}]}
            ],"tray":[{"playerId":"p1","season":2025,"firstWeek":1,"lastWeek":8},{"playerId":"p2","season":2025,"firstWeek":9,"lastWeek":3}],
            "futureField":true}
            """.trimIndent(),
        )
        val prefs = withStore { it.prefs.first() }
        val keeper = prefs.profiles.single()
        assertEquals("u2", keeper.id)
        assertEquals(mapOf(ScoringRule.PASS_TD to 6.0), keeper.weights)
        assertEquals(mapOf(Position.TE to 1.5), keeper.receptionByPosition)
        assertEquals(listOf(YardageBonus(BonusStat.PASSING_YARDS, 300, null, 3.0)), keeper.yardageBonuses)
        assertEquals(listOf(CompareSlot("p1", 2025, WeekRange(1, 8))), prefs.tray)
        assertEquals(keeper, prefs.active)
    }

    @Test
    fun `an active id that no longer exists falls back to PPR`() {
        val prefs = UserPrefs(profiles = emptyList(), activeProfileId = "gone", tray = emptyList())
        assertEquals(ScoringPresets.PPR, prefs.active)
    }
}
```

- [ ] **Step 3: Run and confirm failure**

Run: `./gradlew :core:datastore:test --console=plain`
Expected: compilation FAILS (no `UserPrefs`, `UserPrefsStore`).

- [ ] **Step 4: Implement**

`UserPrefs.kt`:

```kotlin
package dev.gridiron.core.datastore

import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringProfile

/**
 * Everything the user sets up, in one small document.
 *
 * @property profiles User profiles only; presets live in code and are never stored.
 * @property resetNotice Set when an unreadable file was replaced with defaults,
 *   so the app can say so once; cleared when the notice has been shown.
 */
public data class UserPrefs(
    val profiles: List<ScoringProfile>,
    val activeProfileId: String,
    val tray: List<CompareSlot>,
    val resetNotice: Boolean = false,
) {
    /** The active profile, falling back to PPR if its id no longer exists. */
    public val active: ScoringProfile
        get() = ScoringPresets.byId(activeProfileId)
            ?: profiles.firstOrNull { it.id == activeProfileId }
            ?: ScoringPresets.PPR

    public companion object {
        public val DEFAULT: UserPrefs = UserPrefs(emptyList(), ScoringPresets.PPR.id, emptyList())
    }
}
```

`UserPrefsJson.kt`:

```kotlin
package dev.gridiron.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import dev.gridiron.core.model.BonusStat
import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.ScoringRule
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.model.YardageBonus
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/*
 * The stored shape. Deliberately separate from the domain types: names are
 * plain strings so a file written by a newer build (a rule this build doesn't
 * know) still reads, dropping only what it can't understand.
 */

@Serializable
internal data class UserPrefsDto(
    val formatVersion: Int = FORMAT_VERSION,
    val profiles: List<ProfileDto> = emptyList(),
    val activeProfileId: String? = null,
    val tray: List<SlotDto> = emptyList(),
    val resetNotice: Boolean = false,
)

@Serializable
internal data class ProfileDto(
    val id: String,
    val name: String,
    val weights: Map<String, Double> = emptyMap(),
    val receptionByPosition: Map<String, Double> = emptyMap(),
    val bonuses: List<BonusDto> = emptyList(),
    val basedOn: String? = null,
)

@Serializable
internal data class BonusDto(val stat: String, val min: Int, val maxExclusive: Int? = null, val points: Double)

@Serializable
internal data class SlotDto(val playerId: String, val season: Int, val firstWeek: Int, val lastWeek: Int)

internal const val FORMAT_VERSION = 1

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Runs [block], or returns null if it throws a validation error (an invalid stored entry). */
private inline fun <T> orNull(block: () -> T): T? = try {
    block()
} catch (_: IllegalArgumentException) {
    null
}

internal fun UserPrefsDto.toDomain(): UserPrefs {
    val profiles = profiles.mapNotNull { p ->
        orNull {
            ScoringProfile(
                id = p.id,
                name = p.name,
                weights = p.weights.mapNotNull { (k, v) -> ScoringRule.entries.firstOrNull { it.name == k }?.let { it to v } }.toMap(),
                receptionByPosition = p.receptionByPosition
                    .mapNotNull { (k, v) -> Position.fromCode(k)?.takeIf { it in ScoringProfile.RECEPTION_POSITIONS }?.let { it to v } }
                    .toMap(),
                yardageBonuses = p.bonuses.mapNotNull { b ->
                    val stat = BonusStat.entries.firstOrNull { it.name == b.stat } ?: return@mapNotNull null
                    orNull { YardageBonus(stat, b.min, b.maxExclusive, b.points) }
                },
                basedOn = p.basedOn,
            )
        }
    }
    val tray = tray.mapNotNull { s -> orNull { CompareSlot(s.playerId, s.season, WeekRange(s.firstWeek, s.lastWeek)) } }
    return UserPrefs(profiles, activeProfileId ?: UserPrefs.DEFAULT.activeProfileId, tray, resetNotice)
}

internal fun UserPrefs.toDto(): UserPrefsDto = UserPrefsDto(
    profiles = profiles.map { p ->
        ProfileDto(
            id = p.id,
            name = p.name,
            weights = p.weights.mapKeys { it.key.name },
            receptionByPosition = p.receptionByPosition.mapKeys { it.key.code },
            bonuses = p.yardageBonuses.map { BonusDto(it.stat.name, it.min, it.maxExclusive, it.points) },
            basedOn = p.basedOn,
        )
    },
    activeProfileId = activeProfileId,
    tray = tray.map { SlotDto(it.playerId, it.season, it.weeks.first, it.weeks.last) },
    resetNotice = resetNotice,
)

internal object UserPrefsSerializer : Serializer<UserPrefs> {
    override val defaultValue: UserPrefs get() = UserPrefs.DEFAULT

    override suspend fun readFrom(input: InputStream): UserPrefs = try {
        json.decodeFromString(UserPrefsDto.serializer(), input.readBytes().decodeToString()).toDomain()
    } catch (e: SerializationException) {
        throw CorruptionException("unreadable user preferences", e)
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("unreadable user preferences", e)
    }

    override suspend fun writeTo(t: UserPrefs, output: OutputStream) {
        output.write(json.encodeToString(UserPrefsDto.serializer(), t.toDto()).encodeToByteArray())
    }
}
```

`UserPrefsStore.kt`:

```kotlin
package dev.gridiron.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.IOException

/** Where [UserPrefs] live. Repositories depend on this, so tests can hold prefs in memory. */
public interface PrefsSource {
    public val prefs: Flow<UserPrefs>

    /** Atomically replaces the document with [transform]'s result. */
    public suspend fun update(transform: (UserPrefs) -> UserPrefs): UserPrefs
}

/**
 * The user's scoring profiles, active profile and compare tray, persisted as
 * one JSON file. Create exactly one per file per process (DataStore's rule).
 */
public class UserPrefsStore private constructor(private val dataStore: DataStore<UserPrefs>) : PrefsSource {

    override val prefs: Flow<UserPrefs> get() = dataStore.data

    override suspend fun update(transform: (UserPrefs) -> UserPrefs): UserPrefs = dataStore.updateData { transform(it) }

    public companion object {
        public fun create(file: File, scope: CoroutineScope): UserPrefsStore =
            UserPrefsStore(
                DataStoreFactory.create(
                    serializer = UserPrefsSerializer,
                    corruptionHandler = ReplaceFileCorruptionHandler {
                        // Keep the unreadable file for inspection instead of
                        // silently losing what the user set up.
                        try {
                            file.copyTo(File(file.path + ".corrupt"), overwrite = true)
                        } catch (_: IOException) {
                            // Best effort; the reset itself must still happen.
                        }
                        UserPrefs.DEFAULT.copy(resetNotice = true)
                    },
                    scope = scope,
                    produceFile = { file },
                ),
            )
    }
}
```

If `DataStoreFactory.create`'s parameter names differ in 1.2.1, check the signature in the resolved `datastore-core-jvm-1.2.1-sources.jar` (in `~/.gradle/caches/modules-2/files-2.1/androidx.datastore/`) and adapt; keep the behavior.

- [ ] **Step 5: Run and confirm pass**

Run: `./gradlew :core:datastore:test --console=plain`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/datastore settings.gradle.kts gradle/libs.versions.toml build.gradle.kts
git commit -m "datastore: profiles, active profile and compare tray in one JSON document

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NAmSfMG3LfdCw9DC5pxByG"
```

---

### Task 6: Scoring contract and speed against the real database

**Files:**
- Modify: `core/statquery/src/test/kotlin/dev/gridiron/core/statquery/RealDatabaseContractTest.kt`
- Modify: `core/statquery/src/main/kotlin/dev/gridiron/core/statquery/StatQueryBuilder.kt` only if the speed target fails

**Interfaces:**
- Consumes: Tasks 2 and 4. The database at `$GRIDIRON_STATS_DB` must be the schema-3 build (see Working Environment).
- Produces: nothing new; contract coverage.

- [ ] **Step 1: Confirm the database is schema 3**

Run: `sqlite3 "$GRIDIRON_STATS_DB" "SELECT value FROM schema_meta WHERE key='schema_version'; SELECT COUNT(*) FROM player_week_stat WHERE metric_id LIKE 'x_%';"`
Expected: `3`, then a count well above zero. If not, stop and report: the controller rebuilds it after Task 2.

- [ ] **Step 2: Update the registry contract tests (they fail first)**

The test class reads the metric table into a `metrics` map (see its `metrics` property). Add `computed` to what it reads (`SELECT ..., computed FROM metric`), and change the two registry tests:

```kotlin
    @Test
    fun `every column is a visible metric and every denominator is internal`() {
        for (column in StatColumn.entries) {
            val m = metrics[column.metricId]
            assertTrue(m != null, "${column.metricId} is not in the metric table")
            assertFalse(m!!.internal, "${column.metricId} is a column but flagged internal")
            assertEquals(column.isFantasy, m.computed, "${column.metricId}: computed flag must match isFantasy")
        }
        val displayed = StatColumn.entries.map { it.metricId }.toSet()
        val internalOnly = StatColumn.entries.flatMap { it.aggregate.components }.map { it.id }.toSet() - displayed
        for (id in internalOnly + Components.GAMES.id) {
            assertTrue(metrics.getValue(id).internal, "$id is only a denominator but not flagged internal")
        }
    }

    @Test
    fun `every scoring component is a registered internal metric with data`() {
        for (c in SCORING_COMPONENTS) {
            val m = metrics[c.id]
            assertTrue(m != null, "scoring component ${c.id} is not in the metric table")
            assertTrue(m!!.facts > 0, "scoring component ${c.id} has no facts")
            if (c.id !in StatColumn.entries.map { it.metricId }) {
                assertTrue(m.internal, "scoring-only component ${c.id} must be internal")
            }
        }
    }

    @Test
    fun `computed metrics have no facts`() {
        for (column in StatColumn.entries.filter { it.isFantasy }) {
            assertEquals(0, metrics.getValue(column.metricId).facts, "${column.metricId} has stored facts")
        }
    }
```

Run: `./gradlew :core:statquery:test --console=plain` (with `GRIDIRON_STATS_DB` exported).
Expected: the new and changed tests PASS if Tasks 1–2 are correct. If the metrics query doesn't yet select `computed`, it fails to compile first; that is the failing step.

- [ ] **Step 3: Write the independent-scorer contract test**

This recomputes fantasy points in plain Kotlin from raw facts, with its own hand-written rule→metric mapping (deliberately not `RULE_INPUTS`), and compares every player's builder output. Add to `RealDatabaseContractTest`:

```kotlin
    /** Exercises every rule, a position override and overlapping, open and closed bonuses. */
    private val everyRule = ScoringProfile(
        id = "contract",
        name = "Every rule",
        weights = ScoringRule.entries.withIndex().associate { (i, rule) -> rule to (i % 7 - 3) * 0.25 + 0.05 },
        receptionByPosition = mapOf(Position.TE to 1.75, Position.RB to 0.4),
        yardageBonuses = listOf(
            YardageBonus(BonusStat.RUSH_REC_YARDS, 100, 150, 2.0),
            YardageBonus(BonusStat.RUSH_REC_YARDS, 150, null, 4.0),
            YardageBonus(BonusStat.PASSING_YARDS, 300, null, 3.0),
            YardageBonus(BonusStat.RECEIVING_YARDS, 100, 200, 1.5),
        ),
    )

    /** Written independently of `Scoring.kt`: metric ids by hand, per rule. */
    private fun referenceWeek(profile: ScoringProfile, position: String?, s: Map<String, Double>): Pair<Double, Double> {
        fun v(id: String) = s[id] ?: 0.0
        val pos = position?.let(Position::fromCode)
        val rec = profile.receptionWeight(pos)
        fun w(r: ScoringRule) = profile.weight(r)
        var fp = w(ScoringRule.PASS_YARD) * v("passing_yards") + w(ScoringRule.PASS_TD) * v("passing_tds") +
            w(ScoringRule.INTERCEPTION) * v("interceptions") + w(ScoringRule.PASS_2PT) * v("passing_2pt") +
            w(ScoringRule.COMPLETION) * v("completions") +
            w(ScoringRule.INCOMPLETION) * (v("attempts") - v("completions")) +
            w(ScoringRule.PASS_FIRST_DOWN) * v("passing_first_downs") + w(ScoringRule.SACK_TAKEN) * v("sacks_taken") +
            w(ScoringRule.PASS_TD_40) * v("passing_tds_40") + w(ScoringRule.PASS_TD_50) * v("passing_tds_50") +
            w(ScoringRule.RUSH_YARD) * v("rushing_yards") + w(ScoringRule.RUSH_TD) * v("rushing_tds") +
            w(ScoringRule.RUSH_2PT) * v("rushing_2pt") + w(ScoringRule.CARRY) * v("carries") +
            w(ScoringRule.RUSH_FIRST_DOWN) * v("rushing_first_downs") +
            w(ScoringRule.RUSH_TD_40) * v("rushing_tds_40") + w(ScoringRule.RUSH_TD_50) * v("rushing_tds_50") +
            rec * v("receptions") + w(ScoringRule.REC_YARD) * v("receiving_yards") +
            w(ScoringRule.REC_TD) * v("receiving_tds") + w(ScoringRule.REC_2PT) * v("receiving_2pt") +
            w(ScoringRule.REC_FIRST_DOWN) * v("receiving_first_downs") +
            w(ScoringRule.REC_TD_40) * v("receiving_tds_40") + w(ScoringRule.REC_TD_50) * v("receiving_tds_50") +
            w(ScoringRule.FUMBLE_LOST) * v("fumbles_lost")
        for (b in profile.yardageBonuses) {
            val yards = when (b.stat) {
                BonusStat.PASSING_YARDS -> v("passing_yards")
                BonusStat.RUSHING_YARDS -> v("rushing_yards")
                BonusStat.RECEIVING_YARDS -> v("receiving_yards")
                BonusStat.RUSH_REC_YARDS -> v("rushing_yards") + v("receiving_yards")
            }
            if (b.applies(yards)) fp += b.points
        }
        val xfp = w(ScoringRule.PASS_YARD) * v("x_passing_yards") + w(ScoringRule.PASS_TD) * v("x_passing_tds") +
            w(ScoringRule.INTERCEPTION) * v("x_interceptions") + w(ScoringRule.PASS_2PT) * v("x_passing_2pt") +
            w(ScoringRule.COMPLETION) * v("x_completions") +
            w(ScoringRule.PASS_FIRST_DOWN) * v("x_passing_first_downs") +
            w(ScoringRule.RUSH_YARD) * v("x_rushing_yards") + w(ScoringRule.RUSH_TD) * v("x_rushing_tds") +
            w(ScoringRule.RUSH_2PT) * v("x_rushing_2pt") + w(ScoringRule.RUSH_FIRST_DOWN) * v("x_rushing_first_downs") +
            rec * v("x_receptions") + w(ScoringRule.REC_YARD) * v("x_receiving_yards") +
            w(ScoringRule.REC_TD) * v("x_receiving_tds") + w(ScoringRule.REC_2PT) * v("x_receiving_2pt") +
            w(ScoringRule.REC_FIRST_DOWN) * v("x_receiving_first_downs")
        return fp to xfp
    }

    @Test
    fun `builder fantasy points match an independent per-week scorer for every player`() {
        val season = seasons.last()
        val weeks = WeekRange.regularSeason(season)
        val positions = query("SELECT player_id, position FROM player") { it.getString(1) to it.getString(2) }.toMap()
        val facts = query(
            "SELECT player_id, week, metric_id, value FROM player_week_stat WHERE season = ? AND week BETWEEN ? AND ?",
            season, weeks.first, weeks.last,
        ) { Triple(it.getString(1) to it.getInt(2), it.getString(3), it.getDouble(4)) }
        val byWeek = facts.groupBy({ it.first }, { it.second to it.third }).mapValues { (_, v) -> v.toMap() }
        // The SQL scores every player-week that has any scoring input (a
        // zero-yard week still meets a bonus whose minimum is 0), so the
        // reference does the same.
        val scoringIds = SCORING_COMPONENTS.map { it.id }.toSet()
        val expected = mutableMapOf<String, Pair<Double, Double>>()
        for ((key, stats) in byWeek) {
            if (stats.keys.none { it in scoringIds }) continue
            val (fp, xfp) = referenceWeek(everyRule, positions[key.first], stats)
            val (f0, x0) = expected[key.first] ?: (0.0 to 0.0)
            expected[key.first] = (f0 + fp) to (x0 + xfp)
        }

        var offset = 0
        var checked = 0
        while (true) {
            val spec = StatQuerySpec(
                season, weeks,
                listOf(StatColumn.FANTASY_POINTS, StatColumn.EXPECTED_FANTASY_POINTS, StatColumn.FPOE),
                scoring = everyRule, limit = StatQuerySpec.MAX_LIMIT, offset = offset,
            )
            val q = StatQueryBuilder.grid(spec)
            val rows = run(q.query)
            for (r in rows) {
                val id = r[GridLayout.PLAYER_ID] as String
                val (fp, xfp) = expected[id] ?: (0.0 to 0.0)
                val got = { c: StatColumn -> (r[q.layout.valueIndex(c)] as Number).toDouble() }
                assertEquals(fp, got(StatColumn.FANTASY_POINTS), 1e-6, "fantasy points for $id")
                assertEquals(xfp, got(StatColumn.EXPECTED_FANTASY_POINTS), 1e-6, "xFP for $id")
                assertEquals(fp - xfp, got(StatColumn.FPOE), 1e-6, "FPOE for $id")
                checked++
            }
            if (rows.size < StatQuerySpec.MAX_LIMIT) break
            offset += rows.size
        }
        println("scored and checked $checked players for $season")
        assertTrue(checked > 400, "only $checked players checked")
    }
```

The builder only returns players with games in the range. A player whose only rows are expected-only (no `g`) lands in `expected` but never in `rows`; the loop only looks up returned players, so that is not a failure. Mention it in your report if you see any.

Add the imports: `BonusStat`, `ScoringProfile`, `ScoringRule`, `YardageBonus`.

- [ ] **Step 4: Add the speed and index tests**

```kotlin
    @Test
    fun `scoring a full season for every player is fast`() {
        val season = seasons.last()
        val spec = StatQuerySpec(
            season, WeekRange.regularSeason(season),
            listOf(
                StatColumn.FANTASY_POINTS, StatColumn.EXPECTED_FANTASY_POINTS, StatColumn.FPOE,
                StatColumn.TARGETS, StatColumn.CARRIES, StatColumn.TARGET_SHARE,
                StatColumn.CARRY_SHARE, StatColumn.SNAP_SHARE,
            ),
            scoring = everyRule, percentiles = true, limit = StatQuerySpec.MAX_LIMIT,
            sort = listOf(Sort(StatColumn.FANTASY_POINTS)),
        )
        val q = StatQueryBuilder.grid(spec).query
        repeat(3) { run(q) }
        val times = (1..10).map {
            val t0 = System.nanoTime()
            run(q)
            (System.nanoTime() - t0) / 1e6
        }.sorted()
        val median = times[times.size / 2]
        println("full-season scored Fantasy pack, all players: median %.1f ms (min %.1f, max %.1f)"
            .format(median, times.first(), times.last()))
        assertTrue(median < 250, "median $median ms")
    }
```

Read the existing test `the grid query is served by the metric index, never a fact table scan` and add a sibling that runs the same `EXPLAIN QUERY PLAN` assertion on the scored spec above.

- [ ] **Step 5: Run the contract tests**

Run: `./gradlew :core:statquery:test --console=plain --tests '*RealDatabaseContractTest*'` (with `GRIDIRON_STATS_DB` exported).
Expected: all PASS. Put the printed median and player count in your report.

If the speed test fails, profile before changing anything (`EXPLAIN QUERY PLAN`, timings of the `wk` CTE alone). Likely fixes, in order: make sure `wk`'s predicate is served by `idx_pws_metric_season_week`; restrict `wk` to players present in `agg` only if the plan shows `fw`/`fsum` dominating. Do not raise the 250 ms threshold.

- [ ] **Step 6: Commit**

```bash
git add core/statquery
git commit -m "statquery: real-database contract and speed test for fantasy scoring

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NAmSfMG3LfdCw9DC5pxByG"
```

---
### Task 7: Data layer for scoring and the tray (`:core:data`, `:core:testing`)

**Files:**
- Modify: `core/data/build.gradle.kts`, `core/testing/build.gradle.kts`
- Modify: `core/data/src/main/kotlin/dev/gridiron/core/data/StatPack.kt`
- Modify: `core/data/src/main/kotlin/dev/gridiron/core/data/StatFormat.kt`
- Modify: `core/data/src/main/kotlin/dev/gridiron/core/data/GridModels.kt`
- Modify: `core/data/src/main/kotlin/dev/gridiron/core/data/StatsRepository.kt`
- Create: `core/data/src/main/kotlin/dev/gridiron/core/data/ScoringRepository.kt`
- Create: `core/data/src/main/kotlin/dev/gridiron/core/data/CompareTrayRepository.kt`
- Create: `core/data/src/main/kotlin/dev/gridiron/core/data/Labels.kt`
- Modify: `core/statquery/src/main/kotlin/dev/gridiron/core/statquery/StatQueryBuilder.kt` (add `players`)
- Create: `core/testing/src/main/kotlin/dev/gridiron/core/testing/FakePrefsSource.kt`
- Test: `core/data/src/test/kotlin/dev/gridiron/core/data/{ScoringRepositoryTest,CompareTrayRepositoryTest,LabelsTest}.kt`; add to `StatsRepositoryTest.kt`, `StatFormatTest.kt`; add to `core/statquery/.../StatQueryBuilderTest.kt`

**Interfaces:**
- Consumes: Task 3 model, Task 4 fantasy columns and `StatQuerySpec.scoring`, Task 5 `PrefsSource`/`UserPrefs`.
- Produces:
  - `StatPack.FANTASY` (first entry; the default pack stays `OPPORTUNITY`).
  - `GridRequest.scoring: ScoringProfile = ScoringPresets.PPR`.
  - `public data class PlayerHeader(val playerId: String, val name: String, val position: String?, val team: String?)`; `StatsRepository.players(ids: Collection<String>): Map<String, PlayerHeader>`; `StatQueryBuilder.players(ids: Collection<String>): SqlQuery?`.
  - `ScoringRepository(prefs: PrefsSource, newId: () -> String = { UUID.randomUUID().toString() })` with `profiles: Flow<ImmutableList<ScoringProfile>>` (presets first), `active: Flow<ScoringProfile>`, `resetNotice: Flow<Boolean>`, `suspend setActive(id)`, `suspend duplicate(sourceId, name): ScoringProfile`, `suspend save(profile)`, `suspend delete(id)`, `suspend resetToPreset(id)`, `suspend dismissResetNotice()`.
  - `CompareTrayRepository(prefs: PrefsSource)` with `slots: Flow<ImmutableList<CompareSlot>>`, `suspend add(slot): AddResult`, `suspend remove(slot)`, `suspend replace(old, new): Boolean`, `suspend clear()`, `CAPACITY = 4`, `enum class AddResult { ADDED, ALREADY_THERE, FULL }`.
  - `public data class TraySlotUi(val slot: CompareSlot, val name: String, val detail: String)`.
  - `weeksLabel(season: SeasonInfo, weeks: WeekRange): String`, `describeSlot(slot: CompareSlot, catalog: Catalog): String`.
  - `:core:testing`: `FakePrefsSource(initial: UserPrefs = UserPrefs.DEFAULT) : PrefsSource` with `val current: UserPrefs`.

- [ ] **Step 1: Wire the modules**

`core/data/build.gradle.kts`: add `api(projects.core.datastore)`. `core/testing/build.gradle.kts`: add `api(projects.core.datastore)` and `implementation(libs.kotlinx.coroutines.core)` if not present.

Create `FakePrefsSource.kt`:

```kotlin
package dev.gridiron.core.testing

import dev.gridiron.core.datastore.PrefsSource
import dev.gridiron.core.datastore.UserPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Preferences held in memory, for tests on virtual time. Updates are serialized, like DataStore's. */
public class FakePrefsSource(initial: UserPrefs = UserPrefs.DEFAULT) : PrefsSource {
    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()

    public val current: UserPrefs get() = state.value

    override val prefs: Flow<UserPrefs> get() = state

    override suspend fun update(transform: (UserPrefs) -> UserPrefs): UserPrefs =
        mutex.withLock { transform(state.value).also { state.value = it } }
}
```

- [ ] **Step 2: Write the failing tests**

`ScoringRepositoryTest.kt`:

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.datastore.UserPrefs
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringRule
import dev.gridiron.core.testing.FakePrefsSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ScoringRepositoryTest {
    private val prefs = FakePrefsSource()
    private var n = 0
    private val repo = ScoringRepository(prefs) { "u${++n}" }

    @Test
    fun `presets come first and PPR is active by default`() = runTest {
        assertEquals(ScoringPresets.all, repo.profiles.first().take(3))
        assertEquals(ScoringPresets.PPR, repo.active.first())
    }

    @Test
    fun `duplicating a preset makes an editable copy that remembers its preset`() = runTest {
        val copy = repo.duplicate(ScoringPresets.HALF_PPR.id, "Sleeper league")
        assertEquals("u1", copy.id)
        assertEquals("Sleeper league", copy.name)
        assertEquals(ScoringPresets.HALF_PPR.id, copy.basedOn)
        assertEquals(ScoringPresets.HALF_PPR.weights, copy.weights)
        assertFalse(copy.isPreset)
        assertEquals(copy, repo.profiles.first().last())
    }

    @Test
    fun `duplicating a user profile keeps the original preset lineage`() = runTest {
        val first = repo.duplicate(ScoringPresets.PPR.id, "A")
        val second = repo.duplicate(first.id, "B")
        assertEquals(ScoringPresets.PPR.id, second.basedOn)
    }

    @Test
    fun `saving replaces by id and presets can't be saved`() = runTest {
        val copy = repo.duplicate(ScoringPresets.PPR.id, "Mine")
        repo.save(copy.copy(weights = copy.weights + (ScoringRule.PASS_TD to 6.0)))
        assertEquals(6.0, prefs.current.profiles.single().weight(ScoringRule.PASS_TD))
        assertThrows<IllegalArgumentException> { repo.save(ScoringPresets.PPR) }
    }

    @Test
    fun `deleting the active profile activates the first remaining one, else PPR`() = runTest {
        val a = repo.duplicate(ScoringPresets.PPR.id, "A")
        val b = repo.duplicate(ScoringPresets.PPR.id, "B")
        repo.setActive(b.id)
        repo.delete(b.id)
        assertEquals(a, repo.active.first())
        repo.delete(a.id)
        assertEquals(ScoringPresets.PPR, repo.active.first())
    }

    @Test
    fun `presets can't be deleted and unknown ids can't be activated`() = runTest {
        repo.delete(ScoringPresets.PPR.id)
        assertEquals(ScoringPresets.all, repo.profiles.first())
        repo.setActive("nope")
        assertEquals(ScoringPresets.PPR, repo.active.first())
    }

    @Test
    fun `reset to preset restores the preset's rules but keeps id and name`() = runTest {
        val copy = repo.duplicate(ScoringPresets.PPR.id, "Mine")
        repo.save(copy.copy(weights = mapOf(ScoringRule.PASS_TD to 6.0)))
        repo.resetToPreset(copy.id)
        val reset = prefs.current.profiles.single()
        assertEquals(ScoringPresets.PPR.weights, reset.weights)
        assertEquals("Mine", reset.name)
        assertEquals(copy.id, reset.id)
    }

    @Test
    fun `the reset notice can be dismissed`() = runTest {
        val flagged = FakePrefsSource(UserPrefs.DEFAULT.copy(resetNotice = true))
        val r = ScoringRepository(flagged)
        assertTrue(r.resetNotice.first())
        r.dismissResetNotice()
        assertFalse(r.resetNotice.first())
    }
}
```

`CompareTrayRepositoryTest.kt`:

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.data.CompareTrayRepository.AddResult
import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.testing.FakePrefsSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class CompareTrayRepositoryTest {
    private val tray = CompareTrayRepository(FakePrefsSource())
    private fun slot(id: String, season: Int = 2025, weeks: WeekRange = WeekRange(1, 18)) = CompareSlot(id, season, weeks)

    @Test
    fun `adds up to four, then reports full`() = runTest {
        for (id in listOf("a", "b", "c", "d")) assertEquals(AddResult.ADDED, tray.add(slot(id)))
        assertEquals(AddResult.FULL, tray.add(slot("e")))
        assertEquals(listOf("a", "b", "c", "d"), tray.slots.first().map { it.playerId })
    }

    @Test
    fun `the same player with another season or range is a new slot, the identical slot is not`() = runTest {
        assertEquals(AddResult.ADDED, tray.add(slot("a")))
        assertEquals(AddResult.ADDED, tray.add(slot("a", season = 2024)))
        assertEquals(AddResult.ADDED, tray.add(slot("a", weeks = WeekRange(1, 8))))
        assertEquals(AddResult.ALREADY_THERE, tray.add(slot("a")))
        assertEquals(3, tray.slots.first().size)
    }

    @Test
    fun `replace edits a slot in place and refuses to create a duplicate`() = runTest {
        tray.add(slot("a"))
        tray.add(slot("b"))
        assertEquals(true, tray.replace(slot("a"), slot("a", weeks = WeekRange(9, 18))))
        assertEquals(listOf(slot("a", weeks = WeekRange(9, 18)), slot("b")), tray.slots.first())
        assertFalse(tray.replace(slot("b"), slot("a", weeks = WeekRange(9, 18))))
    }

    @Test
    fun `remove and clear`() = runTest {
        tray.add(slot("a"))
        tray.add(slot("b"))
        tray.remove(slot("a"))
        assertEquals(listOf(slot("b")), tray.slots.first())
        tray.clear()
        assertEquals(emptyList<CompareSlot>(), tray.slots.first())
    }
}
```

`LabelsTest.kt`:

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.WeekRange
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LabelsTest {
    private val s2025 = SeasonInfo(2025, 22)
    private val s2026 = SeasonInfo(2026, 2)
    private val catalog = Catalog(persistentListOf(s2025, s2026), persistentMapOf())

    @Test
    fun `week labels clip to the weeks played`() {
        assertEquals("Wk 1–18", weeksLabel(s2025, WeekRange(1, 18)))
        assertEquals("Week 3", weeksLabel(s2025, WeekRange(3, 3)))
        assertEquals("Wk 1–2", weeksLabel(s2026, WeekRange(1, 18)))
        assertEquals("Wk 10–18, not played yet", weeksLabel(s2026, WeekRange(10, 18)))
    }

    @Test
    fun `a slot describes its season and range, or says its season is gone`() {
        assertEquals("2025 · Wk 1–8", describeSlot(CompareSlot("p", 2025, WeekRange(1, 8)), catalog))
        assertEquals("2023 · no data", describeSlot(CompareSlot("p", 2023, WeekRange(1, 8)), catalog))
    }
}
```

Add to `StatsRepositoryTest.kt` (it already skips without the real database; follow its setup):

```kotlin
    @Test
    fun `the fantasy pack ranks by points under the requested profile`() = runTest {
        val season = catalog.season(2025)
        val ppr = repo.grid(GridRequest(season, season.defaultWeeks, StatPack.FANTASY, scoring = ScoringPresets.PPR), catalog)
        val std = repo.grid(GridRequest(season, season.defaultWeeks, StatPack.FANTASY, scoring = ScoringPresets.STANDARD), catalog)
        assertEquals(StatColumn.FANTASY_POINTS, ppr.columns.first().column)
        assertEquals("FPTS", ppr.columns.first().header)
        val points = ppr.rows.map { it.cells.first().text.replace(",", "").toDouble() }
        assertEquals(points.sortedDescending(), points)
        assertTrue(ppr.rows.first().cells.first().text.matches(Regex("""\d+\.\d""")))
        // Receptions are worth a point in PPR and nothing in Standard.
        assertTrue(points.first() > std.rows.first().cells.first().text.replace(",", "").toDouble())
    }

    @Test
    fun `players are looked up by id`() = runTest {
        val some = repo.grid(GridRequest(catalog.latest, catalog.latest.defaultWeeks, StatPack.OPPORTUNITY), catalog).rows.take(3)
        val found = repo.players(some.map { it.playerId } + "missing")
        assertEquals(some.map { it.playerId }.toSet(), found.keys)
        assertEquals(some.first().name, found.getValue(some.first().playerId).name)
    }
```

Add to `StatFormatTest.kt`:

```kotlin
    @Test
    fun `fantasy columns always show one decimal`() {
        val f = StatFormat(Locale.US)
        assertEquals("212.4", f.format(StatColumn.FANTASY_POINTS, 212.43, perGame = false))
        assertEquals("-3.0", f.format(StatColumn.FPOE, -3.0, perGame = false))
        assertEquals("14.2", f.format(StatColumn.EXPECTED_FANTASY_POINTS, 14.21, perGame = true))
    }
```

Add to `core/statquery/.../StatQueryBuilderTest.kt`:

```kotlin
    @Test
    fun `players looks up ids with every id bound`() {
        db.player("a", "Alpha One")
        db.player("b", "Beta Two", position = "RB")
        val q = StatQueryBuilder.players(listOf("b", "a", "zzz"))!!
        val rows = db.rows(q).map { it[0] as String to it[2] as String }
        assertEquals(listOf("a" to "WR", "b" to "RB"), rows.sortedBy { it.first })
        assertTrue(q.binds.containsAll(listOf(Bind.Text("a"), Bind.Text("b"), Bind.Text("zzz"))))
        assertNull(StatQueryBuilder.players(emptyList()))
    }
```

- [ ] **Step 3: Run and confirm failure**

Run: `./gradlew :core:statquery:test :core:data:test --console=plain` (with `GRIDIRON_STATS_DB` exported)
Expected: compilation FAILS on the new symbols.

- [ ] **Step 4: Implement**

`StatQueryBuilder.kt`, inside `object StatQueryBuilder`:

```kotlin
    /**
     * Name, position and team for [ids], in no particular order. Null when
     * [ids] is empty. Result columns: player_id, full_name, position, team.
     */
    public fun players(ids: Collection<String>): SqlQuery? {
        val distinct = ids.distinct().sorted()
        if (distinct.isEmpty()) return null
        require(distinct.size <= StatQuerySpec.MAX_LIMIT) { "at most ${StatQuerySpec.MAX_LIMIT} ids" }
        val w = SqlWriter()
        w.line("SELECT player_id, full_name, position, team")
        w.line("FROM player")
        w.line("WHERE player_id IN (${distinct.joinToString(", ") { w.text(it) }})")
        return w.build()
    }
```

`StatPack.kt`: import the three fantasy columns and `OFFENSE_SNAPS`, and add as the **first** entry:

```kotlin
    FANTASY(
        "Fantasy",
        listOf(FANTASY_POINTS, EXPECTED_FANTASY_POINTS, FPOE, TARGETS, CARRIES, TARGET_SHARE, CARRY_SHARE, SNAP_SHARE),
        FANTASY_POINTS,
        OFFENSE_SNAPS,
    ),
```

`StatFormat.kt`: add to `DECIMALS`:

```kotlin
            FANTASY_POINTS to 1,
            EXPECTED_FANTASY_POINTS to 1,
            FPOE to 1,
```

`GridModels.kt`: add `val scoring: ScoringProfile = ScoringPresets.PPR,` to `GridRequest` after `name`, and:

```kotlin
public data class PlayerHeader(val playerId: String, val name: String, val position: String?, val team: String?)

/** A tray chip: the player's name and a short "2025 · Wk 1–8". */
public data class TraySlotUi(val slot: CompareSlot, val name: String, val detail: String)
```

`StatsRepository.kt`: add `scoring = request.scoring,` to the `StatQuerySpec(...)` in `grid`, and:

```kotlin
    public suspend fun players(ids: Collection<String>): Map<String, PlayerHeader> {
        val q = StatQueryBuilder.players(ids) ?: return emptyMap()
        return executor.query(q) { r ->
            PlayerHeader(r.text(0), r.text(1), r.textOrNull(2), r.textOrNull(3))
        }.associateBy { it.playerId }
    }
```

`Labels.kt`:

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.WeekRange

/** "Wk 1–8", clipped to weeks that have been played; "Week 3" for one week. */
public fun weeksLabel(season: SeasonInfo, weeks: WeekRange): String {
    if (weeks.first > season.lastWeek) return "Wk ${weeks.first}–${weeks.last}, not played yet"
    val last = minOf(weeks.last, season.lastWeek)
    return if (weeks.first == last) "Week ${weeks.first}" else "Wk ${weeks.first}–$last"
}

/** "2025 · Wk 1–8", or "2023 · no data" once a season has left the database. */
public fun describeSlot(slot: CompareSlot, catalog: Catalog): String {
    val info = catalog.seasons.firstOrNull { it.season == slot.season } ?: return "${slot.season} · no data"
    return "${slot.season} · ${weeksLabel(info, slot.weeks)}"
}
```

`ScoringRepository.kt`:

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.datastore.PrefsSource
import dev.gridiron.core.datastore.UserPrefs
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringProfile
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID

/** Scoring profiles: the three presets, plus the user's own. */
public class ScoringRepository(
    private val prefs: PrefsSource,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    public val profiles: Flow<ImmutableList<ScoringProfile>> =
        prefs.prefs.map { (ScoringPresets.all + it.profiles).toImmutableList() }.distinctUntilChanged()

    public val active: Flow<ScoringProfile> = prefs.prefs.map { it.active }.distinctUntilChanged()

    public val resetNotice: Flow<Boolean> = prefs.prefs.map { it.resetNotice }.distinctUntilChanged()

    public suspend fun setActive(id: String) {
        prefs.update { p -> if (p.find(id) != null) p.copy(activeProfileId = id) else p }
    }

    /** Copies [sourceId] under [name]; the copy remembers the preset it descends from. */
    public suspend fun duplicate(sourceId: String, name: String): ScoringProfile {
        var created: ScoringProfile? = null
        prefs.update { p ->
            val source = p.find(sourceId) ?: return@update p
            val copy = source.copy(
                id = newId(),
                name = name,
                basedOn = source.basedOn ?: sourceId.takeIf { ScoringPresets.byId(it) != null },
            )
            created = copy
            p.copy(profiles = p.profiles + copy)
        }
        return checkNotNull(created) { "no profile $sourceId" }
    }

    public suspend fun save(profile: ScoringProfile) {
        require(!profile.isPreset) { "presets can't be changed; duplicate one first" }
        prefs.update { p ->
            val exists = p.profiles.any { it.id == profile.id }
            p.copy(profiles = if (exists) p.profiles.map { if (it.id == profile.id) profile else it } else p.profiles + profile)
        }
    }

    public suspend fun delete(id: String) {
        if (ScoringPresets.byId(id) != null) return
        prefs.update { p ->
            val rest = p.profiles.filterNot { it.id == id }
            val active = if (p.activeProfileId == id) rest.firstOrNull()?.id ?: ScoringPresets.PPR.id else p.activeProfileId
            p.copy(profiles = rest, activeProfileId = active)
        }
    }

    public suspend fun resetToPreset(id: String) {
        prefs.update { p ->
            p.copy(
                profiles = p.profiles.map { profile ->
                    val preset = profile.basedOn?.let(ScoringPresets::byId)
                    if (profile.id != id || preset == null) {
                        profile
                    } else {
                        preset.copy(id = profile.id, name = profile.name, basedOn = profile.basedOn)
                    }
                },
            )
        }
    }

    public suspend fun dismissResetNotice() {
        prefs.update { it.copy(resetNotice = false) }
    }

    private fun UserPrefs.find(id: String): ScoringProfile? = ScoringPresets.byId(id) ?: profiles.firstOrNull { it.id == id }
}
```

`CompareTrayRepository.kt`:

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.datastore.PrefsSource
import dev.gridiron.core.model.CompareSlot
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Players waiting to be compared. Slots are unique: the same player may appear
 * again with another season or range (comparing him with himself), but never
 * twice with the same one.
 */
public class CompareTrayRepository(private val prefs: PrefsSource) {

    public enum class AddResult { ADDED, ALREADY_THERE, FULL }

    public val slots: Flow<ImmutableList<CompareSlot>> =
        prefs.prefs.map { it.tray.toImmutableList() }.distinctUntilChanged()

    public suspend fun add(slot: CompareSlot): AddResult {
        var result = AddResult.ADDED
        prefs.update { p ->
            when {
                slot in p.tray -> p.also { result = AddResult.ALREADY_THERE }
                p.tray.size >= CAPACITY -> p.also { result = AddResult.FULL }
                else -> p.copy(tray = p.tray + slot)
            }
        }
        return result
    }

    public suspend fun remove(slot: CompareSlot) {
        prefs.update { it.copy(tray = it.tray - slot) }
    }

    /** Swaps [old] for [new] in place. False, and no change, if [new] is already in the tray. */
    public suspend fun replace(old: CompareSlot, new: CompareSlot): Boolean {
        var replaced = false
        prefs.update { p ->
            if (new != old && new in p.tray) {
                p
            } else {
                replaced = old in p.tray
                p.copy(tray = p.tray.map { if (it == old) new else it })
            }
        }
        return replaced
    }

    public suspend fun clear() {
        prefs.update { it.copy(tray = emptyList()) }
    }

    public companion object {
        public const val CAPACITY: Int = 4
    }
}
```

- [ ] **Step 5: Run and confirm pass**

Run: `./gradlew :core:statquery:test :core:datastore:test :core:data:test --console=plain` (with `GRIDIRON_STATS_DB` exported)
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/data core/testing core/statquery
git commit -m "data: fantasy pack, scoring profiles and compare tray repositories

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NAmSfMG3LfdCw9DC5pxByG"
```

---

### Task 8: Grid gets the profile chip, row long-press and the compare tray

**Files:**
- Create: `core/ui/build.gradle.kts`, `core/ui/src/main/kotlin/dev/gridiron/core/ui/{MetricSheet,WeeksSheet,ProfileChip}.kt`
- Modify: `settings.gradle.kts` (add `:core:ui`)
- Modify: `core/table/src/main/kotlin/dev/gridiron/core/table/StatTable.kt`
- Delete: `feature/players/src/main/kotlin/dev/gridiron/feature/players/Sheets.kt` (moved to `:core:ui`)
- Modify: `feature/players/build.gradle.kts`, `GridViewModel.kt`, `GridScreen.kt`
- Create: `feature/players/src/main/kotlin/dev/gridiron/feature/players/TrayBar.kt`
- Test: `feature/players/src/test/kotlin/dev/gridiron/feature/players/{GridViewModelTest,GridScreenTest,GridReduceTest}.kt`

**Interfaces:**
- Consumes: Task 7 (`ScoringRepository`, `CompareTrayRepository`, `TraySlotUi`, `StatsRepository.players`, `describeSlot`, `weeksLabel`, `StatPack.FANTASY`, `GridRequest.scoring`, `FakePrefsSource`).
- Produces:
  - `:core:ui` (public): `MetricSheet(info: MetricInfo, onDismiss)`; `WeeksSheet(season: SeasonInfo, weeks: WeekRange, onDismiss, onChange: (WeekRange) -> Unit)`; `ProfileChip(active: ScoringProfile, profiles: ImmutableList<ScoringProfile>, onSelect: (String) -> Unit, onEditProfiles: () -> Unit, modifier)`; `SeasonWeeksSheet(catalog: Catalog, slot: CompareSlot, onDismiss, onChange: (CompareSlot) -> Unit)` for editing a tray slot.
  - `StatTable(..., onRowLongClick: ((R) -> Unit)? = null, rowLongClickLabel: String? = null)`.
  - `GridRoute(repository: StatsRepository, scoring: ScoringRepository, tray: CompareTrayRepository, onCompare: () -> Unit, onEditProfiles: () -> Unit, modifier)`.
  - `GridEvent.ProfileSelected(id)`, `AddToCompare(playerId, name)`, `RemoveFromTray(slot)`, `ReplaceTraySlot(old, new)`, `MessageShown`.
  - `GridUiState.Ready` gains `profiles`, `tray`, `message` with defaults (`ScoringPresets.all`, empty, null) so existing constructions compile.

Why `:core:ui`: the Compare screen (Task 12) needs the same metric sheet, weeks sheet and profile chip, and one feature module must not depend on another. The spec's module list already names `:core:ui`.

- [ ] **Step 1: Create `:core:ui` and move the sheets**

`core/ui/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.gridiron.android.library)
    alias(libs.plugins.gridiron.android.compose)
}

android {
    namespace = "dev.gridiron.core.ui"
}

dependencies {
    api(projects.core.data)
    api(projects.core.designsystem)
}
```

Add `include(":core:ui")` to `settings.gradle.kts`.

Move `MetricSheet` from `Sheets.kt` into `core/ui/.../MetricSheet.kt` unchanged except `internal` → `public` and the package `dev.gridiron.core.ui`. Move `WeeksSheet` into `WeeksSheet.kt` with the signature changed from `(request: GridRequest, ...)` to `(season: SeasonInfo, weeks: WeekRange, onDismiss, onChange)`: replace `request.season` with `season` and `request.weeks` with `weeks` throughout; the body is otherwise unchanged. Delete `Sheets.kt`.

Add to `WeeksSheet.kt`, for editing a tray slot's season and range:

```kotlin
/** Season chips above the week slider; for changing a compare slot's season and range. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
public fun SeasonWeeksSheet(catalog: Catalog, slot: CompareSlot, onDismiss: () -> Unit, onChange: (CompareSlot) -> Unit) {
    var current by remember(slot) { mutableStateOf(slot) }
    val season = catalog.seasons.firstOrNull { it.season == current.season } ?: catalog.latest
    Column {
        FlowRow(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            catalog.seasons.asReversed().forEach { s ->
                FilterChip(
                    selected = s.season == current.season,
                    onClick = {
                        current = current.copy(season = s.season, weeks = s.defaultWeeks)
                        onChange(current)
                    },
                    label = { Text(s.season.toString()) },
                )
            }
        }
        WeeksSheet(season, current.weeks, onDismiss) { weeks ->
            current = current.copy(weeks = weeks)
            onChange(current)
        }
    }
}
```

`WeeksSheet` owns the `ModalBottomSheet`, so the season chips must go inside it: add an optional parameter `header: @Composable ColumnScope.() -> Unit = {}` to `WeeksSheet`, call it first inside the sheet's `Column`, and have `SeasonWeeksSheet` pass the `FlowRow` as `header` instead of wrapping it in an outer `Column`.

`ProfileChip.kt`:

```kotlin
package dev.gridiron.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import dev.gridiron.core.model.ScoringProfile
import kotlinx.collections.immutable.ImmutableList

/** The active scoring profile; tap to switch, or to edit profiles. */
@Composable
public fun ProfileChip(
    active: ScoringProfile,
    profiles: ImmutableList<ScoringProfile>,
    onSelect: (String) -> Unit,
    onEditProfiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        AssistChip(
            onClick = { open = true },
            label = { Text("${active.name} ▾", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = Modifier.testTag("profileChip"),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            profiles.forEach { p ->
                DropdownMenuItem(
                    text = { Text(if (p.id == active.id) "✓ ${p.name}" else p.name) },
                    onClick = {
                        open = false
                        onSelect(p.id)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Edit profiles…") }, onClick = { open = false; onEditProfiles() })
        }
    }
}
```

- [ ] **Step 2: Add row long-press to `StatTable`**

Add parameters after `rowDescription`:

```kotlin
    /** Long-press on a row. Also exposed to TalkBack as a custom long-click action. */
    onRowLongClick: ((R) -> Unit)? = null,
    rowLongClickLabel: String? = null,
```

and in the row `Modifier` chain, before `.clearAndSetSemantics`:

```kotlin
                        .then(
                            if (onRowLongClick == null) Modifier
                            else Modifier.pointerInput(row) { detectTapGestures(onLongPress = { onRowLongClick(row) }) },
                        )
```

and inside the `clearAndSetSemantics { ... }` block:

```kotlin
                            if (onRowLongClick != null) {
                                onLongClick(label = rowLongClickLabel) { onRowLongClick(row); true }
                            }
```

`detectTapGestures` doesn't consume drags, so vertical and horizontal scrolling still work; the screen test in Step 5 checks that a long press registers.

- [ ] **Step 3: Write the failing view model tests**

Read the existing `GridViewModelTest.kt` first and follow its setup (real database, `runTest`, virtual time). Change its view-model construction to:

```kotlin
    private val prefs = FakePrefsSource()
    private fun viewModel() = GridViewModel(repo, ScoringRepository(prefs), CompareTrayRepository(prefs), debounceMillis = 150)
```

and add:

```kotlin
    @Test
    fun switchingProfilesRescoresTheFantasyPack() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onEvent(GridEvent.PackSelected(StatPack.FANTASY))
        advanceUntilIdle()
        val ppr = (vm.state.value as GridUiState.Ready).page!!.rows.first().cells.first().text
        vm.onEvent(GridEvent.ProfileSelected(ScoringPresets.STANDARD.id))
        advanceUntilIdle()
        val ready = vm.state.value as GridUiState.Ready
        assertEquals(ScoringPresets.STANDARD, ready.request.scoring)
        assertNotEquals(ppr, ready.page!!.rows.first().cells.first().text)
    }

    @Test
    fun longPressAddsToTheTrayAndSaysSo() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        val first = (vm.state.value as GridUiState.Ready).page!!.rows.first()
        vm.onEvent(GridEvent.AddToCompare(first.playerId, first.name))
        advanceUntilIdle()
        val ready = vm.state.value as GridUiState.Ready
        assertEquals(first.playerId, ready.tray.single().slot.playerId)
        assertEquals(first.name, ready.tray.single().name)
        assertEquals("${first.name} added to compare", ready.message)
        vm.onEvent(GridEvent.AddToCompare(first.playerId, first.name))
        advanceUntilIdle()
        assertEquals("${first.name} is already in compare", (vm.state.value as GridUiState.Ready).message)
    }

    @Test
    fun aFullTrayRefusesAFifthPlayer() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        val rows = (vm.state.value as GridUiState.Ready).page!!.rows.take(5)
        rows.forEach { vm.onEvent(GridEvent.AddToCompare(it.playerId, it.name)); advanceUntilIdle() }
        val ready = vm.state.value as GridUiState.Ready
        assertEquals(4, ready.tray.size)
        assertEquals("Compare holds 4 players. Remove one first.", ready.message)
    }

    @Test
    fun aCorruptPrefsResetIsAnnouncedOnce() = runTest {
        val flagged = FakePrefsSource(UserPrefs.DEFAULT.copy(resetNotice = true))
        val vm = GridViewModel(repo, ScoringRepository(flagged), CompareTrayRepository(flagged))
        advanceUntilIdle()
        assertEquals("Saved scoring profiles couldn't be read, so they were reset.", (vm.state.value as GridUiState.Ready).message)
        assertFalse(flagged.current.resetNotice)
    }
```

- [ ] **Step 4: Implement the view model changes**

In `GridViewModel.kt`:

Add events:

```kotlin
    data class ProfileSelected(val id: String) : GridEvent
    data class AddToCompare(val playerId: String, val name: String) : GridEvent
    data class RemoveFromTray(val slot: CompareSlot) : GridEvent
    data class ReplaceTraySlot(val old: CompareSlot, val new: CompareSlot) : GridEvent
    data object MessageShown : GridEvent
```

Add to `GridUiState.Ready` (after `error`):

```kotlin
        val profiles: ImmutableList<ScoringProfile> = ScoringPresets.all.toImmutableList(),
        val tray: ImmutableList<TraySlotUi> = persistentListOf(),
        /** A one-off message for the snackbar; the screen sends [GridEvent.MessageShown] after showing it. */
        val message: String? = null,
```

Constructor: `class GridViewModel(private val repository: StatsRepository, private val scoring: ScoringRepository, private val tray: CompareTrayRepository, debounceMillis: Long = 150)`.

State: build the base state as today, then fold in the extras:

```kotlin
    private val message = MutableStateFlow<String?>(null)

    private val trayUi: Flow<ImmutableList<TraySlotUi>> =
        combine(tray.slots, catalogLoad) { slots, load -> slots to (load as? CatalogLoad.Loaded)?.catalog }
            .mapLatest { (slots, catalog) ->
                if (catalog == null) return@mapLatest persistentListOf()
                val names = try {
                    repository.players(slots.map { it.playerId })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyMap()
                }
                slots.map { TraySlotUi(it, names[it.playerId]?.name ?: it.playerId, describeSlot(it, catalog)) }.toImmutableList()
            }

    val state: StateFlow<GridUiState> =
        combine(catalogLoad, request, heat, lastPage, pageError) { load, r, h, page, err ->
            // ... unchanged ...
        }.combine(combine(scoring.profiles, trayUi, message, ::Triple)) { base, (profiles, slots, msg) ->
            if (base is GridUiState.Ready) base.copy(profiles = profiles, tray = slots, message = msg) else base
        }.stateIn(viewModelScope, SharingStarted.Eagerly, GridUiState.Loading)
```

Declare `message` and `trayUi` after `catalogLoad` and before `state`: Kotlin initializes properties in declaration order, and a flow built from a not-yet-initialized property is null at runtime.

In `init`, create the first request with the active profile, follow profile changes, and announce a reset:

```kotlin
            catalogLoad.value = CatalogLoad.Loaded(c)
            request.value = GridRequest(c.latest, c.latest.defaultWeeks, StatPack.OPPORTUNITY, scoring = scoring.active.first())
```

```kotlin
        viewModelScope.launch {
            scoring.active.collect { profile -> request.update { it?.copy(scoring = profile) } }
        }
        viewModelScope.launch {
            scoring.resetNotice.filter { it }.collect {
                message.value = "Saved scoring profiles couldn't be read, so they were reset."
                scoring.dismissResetNotice()
            }
        }
```

In `onEvent`, handle the side-effect events before the reducer:

```kotlin
        when (event) {
            is GridEvent.ProfileSelected -> {
                viewModelScope.launch { scoring.setActive(event.id) }
                return
            }
            is GridEvent.AddToCompare -> {
                val r = request.value ?: return
                viewModelScope.launch {
                    message.value = when (tray.add(CompareSlot(event.playerId, r.season.season, r.weeks))) {
                        CompareTrayRepository.AddResult.ADDED -> "${event.name} added to compare"
                        CompareTrayRepository.AddResult.ALREADY_THERE -> "${event.name} is already in compare"
                        CompareTrayRepository.AddResult.FULL -> "Compare holds ${CompareTrayRepository.CAPACITY} players. Remove one first."
                    }
                }
                return
            }
            is GridEvent.RemoveFromTray -> {
                viewModelScope.launch { tray.remove(event.slot) }
                return
            }
            is GridEvent.ReplaceTraySlot -> {
                viewModelScope.launch {
                    if (!tray.replace(event.old, event.new)) message.value = "That player and range is already in compare"
                }
                return
            }
            GridEvent.MessageShown -> {
                message.value = null
                return
            }
            else -> Unit
        }
```

In `reduce`, add `ProfileSelected, AddToCompare, RemoveFromTray, ReplaceTraySlot, MessageShown -> r` branches (they never change the request directly) so the `when` stays exhaustive. Update `factory(repository, scoring, tray)`.

- [ ] **Step 5: Implement the screen changes and write the screen tests**

`TrayBar.kt`:

```kotlin
package dev.gridiron.feature.players

// imports: foundation layout, material3 Button, InputChip, InputChipDefaults, Surface, Text, TextButton;
// Modifier.horizontalScroll, rememberScrollState, testTag; ImmutableList; TraySlotUi; CompareSlot

/** Players waiting to be compared. Tap a chip to change its season or weeks; ✕ removes it. */
@Composable
internal fun TrayBar(
    tray: ImmutableList<TraySlotUi>,
    onEdit: (TraySlotUi) -> Unit,
    onRemove: (CompareSlot) -> Unit,
    onCompare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("tray"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tray.forEach { t ->
                    // Keyed by the whole slot: the same player can appear twice.
                    key(t.slot) {
                        InputChip(
                            selected = false,
                            onClick = { onEdit(t) },
                            label = {
                                Column {
                                    Text(t.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                    Text(t.detail, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                            },
                            trailingIcon = {
                                Text("✕", Modifier.clickable { onRemove(t.slot) }.padding(4.dp).semantics { contentDescription = "Remove ${t.name}" })
                            },
                        )
                    }
                }
            }
            Button(onClick = onCompare, enabled = tray.size >= 2, modifier = Modifier.testTag("compareButton")) {
                Text("Compare ${tray.size}")
            }
        }
    }
}
```

In `GridScreen.kt`:
- `GridRoute(repository, scoring, tray, onCompare, onEditProfiles, modifier)` builds the view model with `GridViewModel.factory(repository, scoring, tray)` and passes the two callbacks to `GridScreen`.
- `GridScreen(state, onEvent, modifier, onCompare: () -> Unit = {}, onEditProfiles: () -> Unit = {})`.
- `TitleBar`: add `ProfileChip(state.request.scoring, state.profiles, onSelect = { onEvent(GridEvent.ProfileSelected(it)) }, onEditProfiles = onEditProfiles)` before the weeks button. Everything must still fit at 412 dp: shrink the "Gridiron" wordmark to `titleMedium` and drop the "Data through…" line to one line with ellipsis if needed; confirm with the screenshot.
- `PlayerTable`: pass `onRowLongClick = { row -> haptics.performHapticFeedback(HapticFeedbackType.LongPress); onEvent(GridEvent.AddToCompare(row.playerId, row.name)) }` and `rowLongClickLabel = "Add to compare"` (`val haptics = LocalHapticFeedback.current`). Change the frozen header's hint to `"hold a player to compare"`.
- Wrap `GridContent`'s `Column` in a `Box`; put `TrayBar` at the bottom of the `Column` (below the table) when `state.tray.isNotEmpty()`, and a `SnackbarHost` aligned to the bottom of the `Box`:

```kotlin
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onEvent(GridEvent.MessageShown)
        }
    }
```

- Tray chip tap opens `SeasonWeeksSheet(state.catalog, slot, onDismiss, onChange = { onEvent(GridEvent.ReplaceTraySlot(original, it)) })`.
- Replace uses of the old `WeeksSheet(r, ...)` with `WeeksSheet(r.season, r.weeks, ...)`, and `MetricSheet` from `dev.gridiron.core.ui`. `weeksLabel(r)` becomes `weeksLabel(r.season, r.weeks)` from `:core:data`; delete the local one.

`feature/players/build.gradle.kts`: add `implementation(projects.core.ui)`.

Update `GridScreenTest`: `everyControlIsOnScreenWithoutScrolling` adds `"PPR ▾"` and `"Fantasy"` to its labels. Add:

```kotlin
    @Test
    fun holdingARowAsksToAddThePlayer() {
        val season = catalog.season(2025)
        val events = mutableListOf<GridEvent>()
        val state = ready(GridRequest(season, season.defaultWeeks, StatPack.OPPORTUNITY))
        show(state, onEvent = { events += it })
        val first = state.page!!.rows.first()
        compose.onNodeWithText(first.name).performTouchInput { longClick() }
        assertEquals(listOf<GridEvent>(GridEvent.AddToCompare(first.playerId, first.name)), events)
    }

    @Test
    fun fantasyPackWithTray2025() {
        val season = catalog.season(2025)
        val request = GridRequest(season, season.defaultWeeks, StatPack.FANTASY, positions = PositionFilter.FLEX)
        val page = runBlocking { repo.grid(request, catalog) }
        val tray = page.rows.take(2).map { TraySlotUi(CompareSlot(it.playerId, 2025, season.defaultWeeks), it.name, "2025 · Wk 1–18") }
        show(ready(request).copy(tray = tray.toImmutableList()))
        compose.onNodeWithTag("compareButton").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/5_fantasy_tray_2025.png")
    }

    @Test
    fun fantasyPackWithTrayDark() {
        val season = catalog.latest
        val request = GridRequest(season, season.defaultWeeks, StatPack.FANTASY)
        val page = runBlocking { repo.grid(request, catalog) }
        val row = page.rows.first()
        val tray = listOf(
            TraySlotUi(CompareSlot(row.playerId, season.season, season.defaultWeeks), row.name, "${season.season} · Wk 1–${season.lastWeek}"),
        )
        show(ready(request).copy(tray = tray.toImmutableList()), dark = true)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/6_fantasy_tray_dark.png")
    }
```

In `GridScreenTest`, the frozen-cell node for a player can be found by name text; if long-press on the text doesn't reach the row's `pointerInput`, target the row via its content description (`onNode(hasContentDescription(first.name, substring = true))`).

- [ ] **Step 6: Run the tests and record the screenshots**

Run:

```bash
./gradlew :core:table:testDebugUnitTest :core:ui:assembleDebug :feature:players:testDebugUnitTest :feature:players:recordRoborazziDebug --console=plain
```

Expected: PASS. Open `5_fantasy_tray_2025.png`, `6_fantasy_tray_dark.png` and the updated `1_opportunity_2025.png`. Check that the title bar isn't truncated, the tray doesn't cover the last visible row's content without a way to scroll to it, the dark tray is readable and the heat colors look sensible on the fantasy columns. Describe what you saw in your report.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts core/ui core/table feature/players
git commit -m "players: scoring profile chip, hold a row to compare, compare tray

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NAmSfMG3LfdCw9DC5pxByG"
```

---

### Task 9: Scoring profile editor (`:feature:scoring`)

**Files:**
- Create: `feature/scoring/build.gradle.kts`
- Create: `feature/scoring/src/main/kotlin/dev/gridiron/feature/scoring/{DecimalInput,ScoringListViewModel,ScoringListScreen,ScoringEditViewModel,ScoringEditScreen}.kt`
- Modify: `settings.gradle.kts`
- Test: `feature/scoring/src/test/kotlin/dev/gridiron/feature/scoring/{DecimalInputTest,ScoringListViewModelTest,ScoringEditViewModelTest,ScoringScreenTest}.kt`

**Interfaces:**
- Consumes: Task 7 `ScoringRepository`, `FakePrefsSource`; Task 8 `:core:ui`; Task 3 model.
- Produces: `ScoringListRoute(scoring: ScoringRepository, onEdit: (String) -> Unit, onBack: () -> Unit)`; `ScoringEditRoute(profileId: String, scoring: ScoringRepository, onDone: () -> Unit)`.

- [ ] **Step 1: Module**

`feature/scoring/build.gradle.kts` mirrors `feature/players/build.gradle.kts` with namespace `dev.gridiron.feature.scoring`, dependencies `implementation(projects.core.ui)`, the lifecycle libraries, and the same test dependencies (`projects.core.testing`, coroutines-test, robolectric, roborazzi, roborazzi-compose, compose-ui-test-junit4, `debugImplementation` ui-test-manifest). Add `include(":feature:scoring")` to `settings.gradle.kts`.

- [ ] **Step 2: Write the failing input tests**

`DecimalInputTest.kt`:

```kotlin
package dev.gridiron.feature.scoring

import dev.gridiron.feature.scoring.DecimalInput.Result.Blank
import dev.gridiron.feature.scoring.DecimalInput.Result.Invalid
import dev.gridiron.feature.scoring.DecimalInput.Result.Value
import org.junit.Assert.assertEquals
import org.junit.Test

class DecimalInputTest {
    @Test
    fun parsesWhatPeopleType() {
        assertEquals(Value(6.0), DecimalInput.parse("6"))
        assertEquals(Value(0.04), DecimalInput.parse("0.04"))
        assertEquals(Value(0.04), DecimalInput.parse(".04"))
        assertEquals(Value(-2.0), DecimalInput.parse("-2"))
        assertEquals(Value(1.5), DecimalInput.parse("1,5")) // comma decimal, any locale
        assertEquals(Value(1.0), DecimalInput.parse("1."))
        assertEquals(Value(3.0), DecimalInput.parse(" 3 "))
    }

    @Test
    fun rejectsEverythingElseWithoutThrowing() {
        assertEquals(Blank, DecimalInput.parse(""))
        assertEquals(Blank, DecimalInput.parse("   "))
        for (bad in listOf("-", ".", ",", "1e3", "NaN", "Infinity", "1.2.3", "1,000.5", "abc", "99999", "--1")) {
            assertEquals(bad, Invalid, DecimalInput.parse(bad))
        }
    }

    @Test
    fun formatsWithoutNoise() {
        assertEquals("0.04", DecimalInput.format(0.04))
        assertEquals("6", DecimalInput.format(6.0))
        assertEquals("-0.5", DecimalInput.format(-0.5))
        assertEquals("0", DecimalInput.format(-0.0))
    }
}
```

Run: `./gradlew :feature:scoring:testDebugUnitTest --console=plain` → FAIL (no `DecimalInput`).

- [ ] **Step 3: Implement `DecimalInput`**

```kotlin
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
```

Run the input tests → PASS.

- [ ] **Step 4: Write the failing view model tests**

`ScoringEditViewModelTest.kt` (JUnit 4, `runTest`, `Dispatchers.setMain(StandardTestDispatcher(testScheduler))` in `@Before`, `resetMain` in `@After`, as the existing Grid view-model test does):

```kotlin
    private val prefs = FakePrefsSource()
    private val repo = ScoringRepository(prefs) { "u1" }

    @Test
    fun editingAWeightAndSavingPersistsIt() = runTest {
        repo.duplicate(ScoringPresets.PPR.id, "Mine")
        val vm = ScoringEditViewModel("u1", repo)
        advanceUntilIdle()
        vm.onEvent(EditEvent.WeightChanged(ScoringRule.PASS_TD, "6"))
        vm.onEvent(EditEvent.ReceptionChanged(Position.TE, "1,5"))
        vm.onEvent(EditEvent.BonusAdded)
        val key = (vm.state.value as EditState.Editing).bonuses.single().key
        vm.onEvent(EditEvent.BonusChanged(key, BonusDraft(key, BonusStat.RUSHING_YARDS, "100", "200", "3")))
        vm.onEvent(EditEvent.Save)
        advanceUntilIdle()
        val saved = prefs.current.profiles.single()
        assertEquals(6.0, saved.weight(ScoringRule.PASS_TD), 0.0)
        assertEquals(1.5, saved.receptionWeight(Position.TE), 0.0)
        assertEquals(listOf(YardageBonus(BonusStat.RUSHING_YARDS, 100, 200, 3.0)), saved.yardageBonuses)
        assertTrue((vm.state.value as EditState.Editing).saved)
    }

    @Test
    fun invalidInputBlocksSavingAndNamesTheField() = runTest {
        repo.duplicate(ScoringPresets.PPR.id, "Mine")
        val vm = ScoringEditViewModel("u1", repo)
        advanceUntilIdle()
        vm.onEvent(EditEvent.WeightChanged(ScoringRule.PASS_TD, "-"))
        val s = vm.state.value as EditState.Editing
        assertNull(s.profile)
        assertEquals("Enter a number", s.errors[FieldKey.Weight(ScoringRule.PASS_TD)])
        vm.onEvent(EditEvent.Save)
        advanceUntilIdle()
        assertEquals(4.0, prefs.current.profiles.single().weight(ScoringRule.PASS_TD), 0.0)
    }

    @Test
    fun blankWeightMeansZeroAndBlankReceptionMeansUseTheBaseRule() = runTest {
        repo.duplicate(ScoringPresets.PPR.id, "Mine")
        val vm = ScoringEditViewModel("u1", repo)
        advanceUntilIdle()
        vm.onEvent(EditEvent.WeightChanged(ScoringRule.PASS_TD, ""))
        vm.onEvent(EditEvent.ReceptionChanged(Position.TE, ""))
        val p = (vm.state.value as EditState.Editing).profile!!
        assertEquals(0.0, p.weight(ScoringRule.PASS_TD), 0.0)
        assertEquals(emptyMap<Position, Double>(), p.receptionByPosition)
    }

    @Test
    fun anEmptyBonusRangeIsAnError() = runTest {
        repo.duplicate(ScoringPresets.PPR.id, "Mine")
        val vm = ScoringEditViewModel("u1", repo)
        advanceUntilIdle()
        vm.onEvent(EditEvent.BonusAdded)
        val key = (vm.state.value as EditState.Editing).bonuses.single().key
        vm.onEvent(EditEvent.BonusChanged(key, BonusDraft(key, BonusStat.PASSING_YARDS, "300", "300", "3")))
        val s = vm.state.value as EditState.Editing
        assertNull(s.profile)
        assertEquals("Must be above the minimum", s.errors[FieldKey.BonusMax(key)])
    }

    @Test
    fun presetsOpenReadOnly() = runTest {
        val vm = ScoringEditViewModel(ScoringPresets.PPR.id, repo)
        advanceUntilIdle()
        assertTrue((vm.state.value as EditState.Editing).readOnly)
    }

    @Test
    fun resetToPresetRestoresTheFields() = runTest {
        repo.duplicate(ScoringPresets.PPR.id, "Mine")
        val vm = ScoringEditViewModel("u1", repo)
        advanceUntilIdle()
        vm.onEvent(EditEvent.WeightChanged(ScoringRule.PASS_TD, "6"))
        vm.onEvent(EditEvent.ResetToPreset)
        assertEquals("4", (vm.state.value as EditState.Editing).weights.getValue(ScoringRule.PASS_TD))
    }
```

`ScoringListViewModelTest.kt` (same `prefs`, `repo` with ids `u1, u2, …`, and `Dispatchers.setMain` setup as the editor test):

```kotlin
    @Test
    fun duplicatingOpensTheCopyForEditing() = runTest {
        val vm = ScoringListViewModel(repo)
        advanceUntilIdle()
        vm.onEvent(ListEvent.Duplicate(ScoringPresets.HALF_PPR.id))
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals("u1", s.editRequest)
        assertEquals("Half PPR copy", prefs.current.profiles.single().name)
        vm.onEvent(ListEvent.EditOpened)
        assertNull(vm.state.value.editRequest)
    }

    @Test
    fun deletingTheActiveProfileFallsBackToPpr() = runTest {
        repo.duplicate(ScoringPresets.PPR.id, "Mine")
        repo.setActive("u1")
        val vm = ScoringListViewModel(repo)
        advanceUntilIdle()
        vm.onEvent(ListEvent.Delete("u1"))
        advanceUntilIdle()
        assertEquals(ScoringPresets.PPR.id, vm.state.value.activeId)
    }
```

Run → FAIL (no view models).

- [ ] **Step 5: Implement the view models**

`ScoringEditViewModel.kt`:

```kotlin
package dev.gridiron.feature.scoring

// imports: ViewModel, viewModelScope, viewModelFactory/initializer, ScoringRepository,
// model types, ImmutableList/ImmutableMap + converters, flows, launch

internal data class BonusDraft(val key: Int, val stat: BonusStat, val min: String, val max: String, val points: String)

internal sealed interface FieldKey {
    data object Name : FieldKey
    data class Weight(val rule: ScoringRule) : FieldKey
    data class Reception(val position: Position) : FieldKey
    data class BonusMin(val key: Int) : FieldKey
    data class BonusMax(val key: Int) : FieldKey
    data class BonusPoints(val key: Int) : FieldKey
}

internal sealed interface EditState {
    data object Loading : EditState
    data object NotFound : EditState

    data class Editing(
        val original: ScoringProfile,
        val name: String,
        val weights: ImmutableMap<ScoringRule, String>,
        val reception: ImmutableMap<Position, String>,
        val bonuses: ImmutableList<BonusDraft>,
        val readOnly: Boolean,
        val saved: Boolean = false,
    ) : EditState {
        val errors: Map<FieldKey, String> by lazy { validate().first }
        /** The profile these fields describe, or null while any field is invalid. */
        val profile: ScoringProfile? by lazy { validate().second }
        val dirty: Boolean get() = profile != original
    }
}

internal sealed interface EditEvent {
    data class NameChanged(val name: String) : EditEvent
    data class WeightChanged(val rule: ScoringRule, val text: String) : EditEvent
    data class ReceptionChanged(val position: Position, val text: String) : EditEvent
    data object BonusAdded : EditEvent
    data class BonusChanged(val key: Int, val draft: BonusDraft) : EditEvent
    data class BonusRemoved(val key: Int) : EditEvent
    data object ResetToPreset : EditEvent
    data object Save : EditEvent
}

private const val NUMBER = "Enter a number"

private fun EditState.Editing.validate(): Pair<Map<FieldKey, String>, ScoringProfile?> {
    val errors = mutableMapOf<FieldKey, String>()
    if (name.isBlank()) errors[FieldKey.Name] = "Name the profile"
    val weights = buildMap {
        for ((rule, text) in this@validate.weights) {
            when (val r = DecimalInput.parse(text)) {
                is DecimalInput.Result.Value -> put(rule, r.value)
                DecimalInput.Result.Blank -> put(rule, 0.0)
                DecimalInput.Result.Invalid -> errors[FieldKey.Weight(rule)] = NUMBER
            }
        }
    }
    val reception = buildMap {
        for ((position, text) in this@validate.reception) {
            when (val r = DecimalInput.parse(text)) {
                is DecimalInput.Result.Value -> put(position, r.value)
                DecimalInput.Result.Blank -> Unit // use the base reception rule
                DecimalInput.Result.Invalid -> errors[FieldKey.Reception(position)] = NUMBER
            }
        }
    }
    val bonuses = bonuses.mapNotNull { b ->
        val min = b.min.trim().toIntOrNull()?.takeIf { it in 0..1000 }
        if (min == null) errors[FieldKey.BonusMin(b.key)] = "Whole yards, 0–1000"
        val max = if (b.max.isBlank()) null else b.max.trim().toIntOrNull()
        if (b.max.isNotBlank() && max == null) errors[FieldKey.BonusMax(b.key)] = "Whole yards, or leave blank"
        if (min != null && max != null && max <= min) errors[FieldKey.BonusMax(b.key)] = "Must be above the minimum"
        val points = (DecimalInput.parse(b.points) as? DecimalInput.Result.Value)?.value
        if (points == null) errors[FieldKey.BonusPoints(b.key)] = NUMBER
        if (min == null || points == null || (max != null && max <= min)) null else YardageBonus(b.stat, min, max, points)
    }
    if (errors.isNotEmpty()) return errors to null
    return errors to original.copy(
        name = name.trim(),
        weights = weights.filterValues { it != 0.0 },
        receptionByPosition = reception,
        yardageBonuses = bonuses,
    )
}

internal fun ScoringProfile.toEditing(readOnly: Boolean): EditState.Editing = EditState.Editing(
    original = this,
    name = name,
    weights = ScoringRule.entries.associateWith { DecimalInput.format(weight(it)) }.toImmutableMap(),
    reception = ScoringProfile.RECEPTION_POSITIONS.associateWith { p -> receptionByPosition[p]?.let(DecimalInput::format).orEmpty() }
        .toImmutableMap(),
    bonuses = yardageBonuses.mapIndexed { i, b ->
        BonusDraft(i, b.stat, b.min.toString(), b.maxExclusive?.toString().orEmpty(), DecimalInput.format(b.points))
    }.toImmutableList(),
    readOnly = readOnly,
)

internal class ScoringEditViewModel(private val profileId: String, private val repository: ScoringRepository) : ViewModel() {
    private val _state = MutableStateFlow<EditState>(EditState.Loading)
    val state: StateFlow<EditState> = _state
    private var nextKey = 1000

    init {
        viewModelScope.launch {
            val profile = repository.profiles.first().firstOrNull { it.id == profileId }
            _state.value = profile?.toEditing(readOnly = profile.isPreset) ?: EditState.NotFound
        }
    }

    fun onEvent(event: EditEvent) {
        val s = _state.value as? EditState.Editing ?: return
        if (s.readOnly) return
        _state.value = when (event) {
            is EditEvent.NameChanged -> s.copy(name = event.name)
            is EditEvent.WeightChanged -> s.copy(weights = (s.weights + (event.rule to event.text)).toImmutableMap())
            is EditEvent.ReceptionChanged -> s.copy(reception = (s.reception + (event.position to event.text)).toImmutableMap())
            EditEvent.BonusAdded -> s.copy(bonuses = (s.bonuses + BonusDraft(nextKey++, BonusStat.RUSHING_YARDS, "100", "", "3")).toImmutableList())
            is EditEvent.BonusChanged -> s.copy(bonuses = s.bonuses.map { if (it.key == event.key) event.draft else it }.toImmutableList())
            is EditEvent.BonusRemoved -> s.copy(bonuses = s.bonuses.filterNot { it.key == event.key }.toImmutableList())
            EditEvent.ResetToPreset -> {
                val preset = s.original.basedOn?.let(ScoringPresets::byId) ?: return
                preset.copy(id = s.original.id, name = s.name.ifBlank { s.original.name }, basedOn = s.original.basedOn)
                    .toEditing(readOnly = false).copy(original = s.original)
            }
            EditEvent.Save -> {
                val profile = s.profile ?: return
                viewModelScope.launch {
                    repository.save(profile)
                    _state.value = profile.toEditing(readOnly = false).copy(saved = true)
                }
                return
            }
        }
    }

    companion object {
        fun factory(profileId: String, repository: ScoringRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { ScoringEditViewModel(profileId, repository) }
        }
    }
}
```

`ScoringListViewModel.kt`:

```kotlin
internal data class ListState(
    val profiles: ImmutableList<ScoringProfile> = ScoringPresets.all.toImmutableList(),
    val activeId: String = ScoringPresets.PPR.id,
    /** Set after Duplicate: the screen opens this profile in the editor, then sends [ListEvent.EditOpened]. */
    val editRequest: String? = null,
)

internal sealed interface ListEvent {
    data class SetActive(val id: String) : ListEvent
    data class Duplicate(val id: String) : ListEvent
    data class Delete(val id: String) : ListEvent
    data object EditOpened : ListEvent
}

internal class ScoringListViewModel(private val repository: ScoringRepository) : ViewModel() {
    private val editRequest = MutableStateFlow<String?>(null)

    val state: StateFlow<ListState> =
        combine(repository.profiles, repository.active, editRequest) { profiles, active, edit ->
            ListState(profiles, active.id, edit)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ListState())

    fun onEvent(event: ListEvent) {
        when (event) {
            is ListEvent.SetActive -> viewModelScope.launch { repository.setActive(event.id) }
            is ListEvent.Duplicate -> viewModelScope.launch {
                val source = state.value.profiles.first { it.id == event.id }
                editRequest.value = repository.duplicate(event.id, "${source.name} copy").id
            }
            is ListEvent.Delete -> viewModelScope.launch { repository.delete(event.id) }
            ListEvent.EditOpened -> editRequest.value = null
        }
    }

    companion object {
        fun factory(repository: ScoringRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { ScoringListViewModel(repository) }
        }
    }
}
```

Run the view-model tests → PASS.

- [ ] **Step 6: Build the screens**

Both screens use a root `Surface` (so text inherits the right color in dark mode, as `GridScreen` explains) and `windowInsetsPadding(WindowInsets.safeDrawing)`.

`ScoringListScreen(state: ListState, onEvent, onEdit: (String) -> Unit, onBack: () -> Unit)`:
- Top row: `TextButton("← Back")` and title "Scoring profiles".
- `LaunchedEffect(state.editRequest) { state.editRequest?.let { onEdit(it); onEvent(ListEvent.EditOpened) } }`.
- `LazyColumn` of profiles keyed by id. Each row: `RadioButton(selected = id == activeId, onClick = SetActive)`, name, and a subtitle ("Preset" or "Custom · based on PPR"), then actions: presets get `TextButton("Duplicate")`; user profiles get `TextButton("Edit")` (→ `onEdit(id)`), `TextButton("Duplicate")`, and `TextButton("Delete")`, which opens an `AlertDialog` ("Delete ‹name›?" / Delete / Cancel) before sending `ListEvent.Delete`.
- Test tags: `profile:<id>`, `duplicate:<id>`, `edit:<id>`, `delete:<id>`.

`ScoringEditScreen(state: EditState, onEvent, onBack)`:
- `Loading` → spinner; `NotFound` → "This profile no longer exists." and Back.
- Top row: Back (if `dirty` and not `saved`, show a "Discard changes?" `AlertDialog` first), title, `Button("Save", enabled = profile != null && dirty && !readOnly)`. When `saved` becomes true, call `onBack()` via `LaunchedEffect(state.saved)`.
- Read-only banner for presets: "Presets can't be edited. Duplicate this one from the list to make your own."
- `OutlinedTextField` for the name (tag `field:name`).
- `LazyColumn` with a section per `ScoringGroup` (header = `group.label`), a row per rule in that group: label on the left, a 96 dp `OutlinedTextField` on the right (tag `field:<RULE>`, `KeyboardOptions(keyboardType = KeyboardType.Decimal)`, `isError` + `supportingText` from `state.errors`, `enabled = !readOnly`). In Receiving, after `RECEPTION`, three rows "Reception, RB/WR/TE" (placeholder "same") bound to `ReceptionChanged`.
- Bonuses section: one card per `BonusDraft` with a stat dropdown (`BonusStat.label`), min, max (placeholder "no limit"), points fields and a remove button; then `TextButton("+ Add yardage bonus")`. A short help line: "Awarded once per game when the stat lands in the range. 100 to 200 means 100–199."
- "Reset to preset" `TextButton` when `original.basedOn != null`.

Routes:

```kotlin
@Composable
fun ScoringListRoute(scoring: ScoringRepository, onEdit: (String) -> Unit, onBack: () -> Unit) {
    val vm: ScoringListViewModel = viewModel(factory = ScoringListViewModel.factory(scoring))
    val state by vm.state.collectAsStateWithLifecycle()
    ScoringListScreen(state, vm::onEvent, onEdit, onBack)
}

@Composable
fun ScoringEditRoute(profileId: String, scoring: ScoringRepository, onDone: () -> Unit) {
    val vm: ScoringEditViewModel = viewModel(key = profileId, factory = ScoringEditViewModel.factory(profileId, scoring))
    val state by vm.state.collectAsStateWithLifecycle()
    ScoringEditScreen(state, vm::onEvent, onDone)
}
```

`ScoringScreenTest.kt` (Robolectric, `sdk = [36]`, qualifiers `w412dp-h892dp-xxhdpi`, `GraphicsMode.NATIVE`):
- `listLight`: list with the three presets plus one custom profile active → `build/outputs/roborazzi/scoring_1_list.png`.
- `editorDark`: a custom profile with TE premium 1.5 and a 100–199 rushing bonus, dark theme → `scoring_2_editor_dark.png`.
- `editorShowsAnInlineError`: after entering "-" in PASS_TD, `onNodeWithText("Enter a number").assertIsDisplayed()` and Save is disabled → `scoring_3_error.png`.
- `presetIsReadOnly`: PPR opened → the banner text is displayed and `field:PASS_TD` is not enabled.

- [ ] **Step 7: Run everything and look at the screenshots**

Run: `./gradlew :feature:scoring:testDebugUnitTest :feature:scoring:recordRoborazziDebug --console=plain`
Expected: PASS. Open all three PNGs; check the dark editor is readable, fields aren't clipped at 412 dp, and errors show under the right field. Describe them in your report.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts feature/scoring
git commit -m "scoring: profile list and editor with strict number input

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NAmSfMG3LfdCw9DC5pxByG"
```

---
### Task 10: Charts (`:core:charts`) and slot colors

**Files:**
- Create: `core/charts/build.gradle.kts`
- Create: `core/charts/src/main/kotlin/dev/gridiron/core/charts/{PercentileBarRow,RadarChart,ScatterChart,ScatterScale}.kt`
- Create: `core/designsystem/src/main/kotlin/dev/gridiron/core/designsystem/SlotColors.kt`
- Modify: `settings.gradle.kts`
- Test: `core/charts/src/test/kotlin/dev/gridiron/core/charts/{ScatterScaleTest,ChartsScreenshotTest}.kt`

**Interfaces:**
- Consumes: nothing from other new tasks (plain data in, pixels out), so this runs in wave 1.
- Produces:
  - `@Immutable public data class Bar(val fraction: Float?, val text: String, val color: Color)`; `@Composable public fun PercentileBarRow(label: String, bars: ImmutableList<Bar>, modifier: Modifier = Modifier, labelSlot: (@Composable () -> Unit)? = null)`. `fraction` is 0..1 (a percentile), or null for "not ranked".
  - `@Immutable public data class RadarSeries(val name: String, val values: ImmutableList<Float?>, val color: Color)`; `@Composable public fun RadarChart(axes: ImmutableList<String>, series: ImmutableList<RadarSeries>, contentDescription: String, modifier: Modifier = Modifier)`. Values 0..1; null draws at 0.
  - `@Immutable public data class ScatterPoint(val id: String, val x: Float, val y: Float, val color: Color? = null, val label: String? = null)`; `@Composable public fun ScatterChart(points: ImmutableList<ScatterPoint>, xLabel: String, yLabel: String, aboveLabel: String, belowLabel: String, contentDescription: String, selectedId: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier)`. Points with a `color` are highlighted and labeled; the rest are neutral.
  - `internal object ScatterScale { fun bounds(points): ClosedFloatingPointRange<Float>; fun ticks(bounds): List<Float>; fun nearest(points, toScreen: (ScatterPoint) -> Offset, at: Offset, radiusPx: Float): ScatterPoint? }`.
  - `public object SlotColors { @Composable public fun color(index: Int): Color }`.

- [ ] **Step 1: Module**

`core/charts/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.gridiron.android.library)
    alias(libs.plugins.gridiron.android.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "dev.gridiron.core.charts"
}

// Canvas-drawn charts that take plain data. No charting library, and no
// dependency on the database or domain types.
dependencies {
    api(projects.core.designsystem)
    api(libs.kotlinx.collections.immutable)

    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
```

Add `include(":core:charts")` to `settings.gradle.kts`. Check `core/designsystem/build.gradle.kts` exposes Compose (`api`) the way `:core:table` consumes it, and match that.

- [ ] **Step 2: Write the failing scale tests**

```kotlin
package dev.gridiron.core.charts

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScatterScaleTest {
    private fun p(id: String, x: Float, y: Float) = ScatterPoint(id, x, y)

    @Test
    fun boundsStartAtZeroAndRoundUpToAFive() {
        assertEquals(0f..25f, ScatterScale.bounds(listOf(p("a", 12f, 22.3f), p("b", 20.1f, 8f))))
    }

    @Test
    fun negativeValuesExtendTheLowerBound() {
        assertEquals(-5f..20f, ScatterScale.bounds(listOf(p("a", -1.2f, 3f), p("b", 16f, 18f))))
    }

    @Test
    fun emptyOrFlatDataStillHasARange() {
        assertEquals(0f..20f, ScatterScale.bounds(emptyList()))
        assertEquals(0f..5f, ScatterScale.bounds(listOf(p("a", 0f, 0f))))
    }

    @Test
    fun ticksEveryFiveWhenTheRangeIsSmallAndEveryTenWhenLarge() {
        assertEquals(listOf(0f, 5f, 10f, 15f, 20f, 25f), ScatterScale.ticks(0f..25f))
        assertEquals(listOf(0f, 10f, 20f, 30f, 40f), ScatterScale.ticks(0f..40f))
    }

    @Test
    fun nearestPicksTheClosestPointWithinTheRadius() {
        val pts = listOf(p("a", 1f, 1f), p("b", 2f, 2f))
        val screen = { q: ScatterPoint -> Offset(q.x * 100, q.y * 100) }
        assertEquals("b", ScatterScale.nearest(pts, screen, Offset(190f, 205f), 30f)?.id)
        assertNull(ScatterScale.nearest(pts, screen, Offset(500f, 500f), 30f))
    }
}
```

Run: `./gradlew :core:charts:testDebugUnitTest --console=plain` → FAIL.

- [ ] **Step 3: Implement the scale and colors**

`ScatterScale.kt`:

```kotlin
package dev.gridiron.core.charts

import androidx.compose.ui.geometry.Offset
import kotlin.math.ceil
import kotlin.math.floor

/**
 * One scale for both axes, so the y = x line is a true diagonal and "above the
 * line" means the same thing everywhere on the chart.
 */
internal object ScatterScale {
    private const val STEP = 5f

    fun bounds(points: List<ScatterPoint>): ClosedFloatingPointRange<Float> {
        if (points.isEmpty()) return 0f..20f
        val lo = minOf(0f, points.minOf { minOf(it.x, it.y) })
        val hi = points.maxOf { maxOf(it.x, it.y) }
        val min = floor(lo / STEP) * STEP
        val max = maxOf(ceil(hi / STEP) * STEP, min + STEP)
        return min..max
    }

    fun ticks(bounds: ClosedFloatingPointRange<Float>): List<Float> {
        val step = if (bounds.endInclusive - bounds.start > 30f) 10f else STEP
        val first = ceil(bounds.start / step) * step
        return generateSequence(first) { it + step }.takeWhile { it <= bounds.endInclusive + 1e-3f }.toList()
    }

    fun nearest(points: List<ScatterPoint>, toScreen: (ScatterPoint) -> Offset, at: Offset, radiusPx: Float): ScatterPoint? =
        points.map { it to (toScreen(it) - at).getDistance() }
            .filter { it.second <= radiusPx }
            .minByOrNull { it.second }
            ?.first
}
```

`SlotColors.kt` (Okabe–Ito, distinguishable with color-vision deficiency; the dark set trades the deep blue and vermillion for lighter hues that hold contrast on dark surfaces):

```kotlin
package dev.gridiron.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** One color per compare slot, in slot order. */
public object SlotColors {
    private val light = listOf(Color(0xFF0072B2), Color(0xFFD55E00), Color(0xFF009E73), Color(0xFFCC79A7))
    private val dark = listOf(Color(0xFF56B4E9), Color(0xFFE69F00), Color(0xFF009E73), Color(0xFFCC79A7))

    @Composable
    public fun color(index: Int): Color {
        val set = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) dark else light
        return set[index.mod(set.size)]
    }
}
```

Run the scale tests → PASS.

- [ ] **Step 4: Implement the three charts**

`PercentileBarRow.kt`:

```kotlin
package dev.gridiron.core.charts

// imports: foundation Canvas, layout (Column, Row, Box, Spacer, width, height, fillMaxWidth, padding),
// material3 MaterialTheme/Text, runtime Composable/Immutable, ui (Modifier, Alignment, geometry, graphics,
// semantics, text.style.TextAlign, unit.dp), designsystem.NumberStyle, ImmutableList

@Immutable
public data class Bar(val fraction: Float?, val text: String, val color: Color)

/**
 * One stat across the compared players: a 0–100 percentile bar per player with
 * its value beside it. A dashed outline with no fill means "not ranked".
 */
@Composable
public fun PercentileBarRow(
    label: String,
    bars: ImmutableList<Bar>,
    modifier: Modifier = Modifier,
    labelSlot: (@Composable () -> Unit)? = null,
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val midline = MaterialTheme.colorScheme.outline
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$label: " + bars.joinToString(", ") { b ->
                    b.text + (b.fraction?.let { ", ${(it * 100).toInt()}th percentile" } ?: ", not ranked")
                }
            },
    ) {
        if (labelSlot != null) labelSlot() else Text(label, style = MaterialTheme.typography.labelLarge)
        bars.forEach { bar ->
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.weight(1f).height(10.dp)) {
                    val r = CornerRadius(size.height / 2)
                    drawRoundRect(track, cornerRadius = r)
                    val f = bar.fraction
                    if (f == null) {
                        drawRoundRect(
                            bar.color.copy(alpha = 0.6f), cornerRadius = r,
                            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))),
                        )
                    } else {
                        drawRoundRect(bar.color, size = size.copy(width = size.width * f.coerceIn(0f, 1f)), cornerRadius = r)
                    }
                    // The position median, so "above average" reads at a glance.
                    drawLine(midline, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.dp.toPx())
                }
                Spacer(Modifier.width(8.dp))
                Text(bar.text, Modifier.width(72.dp), style = NumberStyle, textAlign = TextAlign.End, maxLines = 1)
            }
        }
    }
}
```

`RadarChart.kt`:

```kotlin
@Immutable
public data class RadarSeries(val name: String, val values: ImmutableList<Float?>, val color: Color)

/**
 * Percentile "shape" for up to two players. Secondary to the bars: fixed axis
 * order, rings at 25/50/75/100, no more than eight axes.
 */
@Composable
public fun RadarChart(
    axes: ImmutableList<String>,
    series: ImmutableList<RadarSeries>,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    require(axes.size in 3..8) { "a radar needs 3 to 8 axes, got ${axes.size}" }
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(modifier.aspectRatio(1f).padding(32.dp).semantics { this.contentDescription = contentDescription }) {
        val c = center
        val radius = size.minDimension / 2
        fun point(i: Int, v: Float): Offset {
            val a = (-PI / 2 + 2 * PI * i / axes.size).toFloat()
            return c + Offset(cos(a), sin(a)) * (radius * v.coerceIn(0f, 1f))
        }
        for (ring in listOf(0.25f, 0.5f, 0.75f, 1f)) {
            val path = Path().apply {
                axes.indices.forEach { i -> point(i, ring).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                close()
            }
            drawPath(path, grid, style = Stroke(1.dp.toPx()))
        }
        axes.forEachIndexed { i, label ->
            drawLine(grid, c, point(i, 1f), 1.dp.toPx())
            val layout = measurer.measure(label, labelStyle)
            val tip = point(i, 1.12f)
            drawText(layout, topLeft = tip - Offset(layout.size.width / 2f, layout.size.height / 2f))
        }
        series.forEach { s ->
            val path = Path().apply {
                s.values.forEachIndexed { i, v -> point(i, v ?: 0f).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                close()
            }
            drawPath(path, s.color.copy(alpha = 0.18f))
            drawPath(path, s.color, style = Stroke(2.dp.toPx()))
        }
    }
}
```

(Imports: `kotlin.math.PI/cos/sin`, `androidx.compose.ui.graphics.Path/drawscope.Stroke`, `androidx.compose.ui.text.rememberTextMeasurer/drawText`, `aspectRatio`.) Labels near the left and right edges can clip; the 32 dp padding is there for that; confirm in the screenshot.

`ScatterChart.kt`:

```kotlin
@Immutable
public data class ScatterPoint(
    val id: String,
    val x: Float,
    val y: Float,
    val color: Color? = null,
    val label: String? = null,
)

/**
 * Expected against actual. Neutral dots are the population, colored dots the
 * compared players. The diagonal is actual = expected: above it a player is
 * outscoring his opportunity (sell high), below it he's due (buy low).
 * Tap, S Pen hover or mouse hover selects the nearest point.
 */
@Composable
public fun ScatterChart(
    points: ImmutableList<ScatterPoint>,
    xLabel: String,
    yLabel: String,
    aboveLabel: String,
    belowLabel: String,
    contentDescription: String,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bounds = remember(points) { ScatterScale.bounds(points) }
    val ticks = remember(bounds) { ScatterScale.ticks(bounds) }
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val tickStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val noteStyle = MaterialTheme.typography.labelMedium.copy(color = colors.onSurfaceVariant)
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = colors.onSurface)
    val density = LocalDensity.current
    val gutter = with(density) { 36.dp.toPx() }
    var plot by remember { mutableStateOf(Size.Zero) }

    fun toScreen(p: ScatterPoint): Offset {
        val span = bounds.endInclusive - bounds.start
        return Offset(
            gutter + (p.x - bounds.start) / span * (plot.width - gutter),
            (plot.height - gutter) * (1 - (p.y - bounds.start) / span),
        )
    }
    val radius = with(density) { 24.dp.toPx() }

    Canvas(
        modifier
            .aspectRatio(1f)
            .padding(8.dp)
            .onSizeChanged { plot = it.toSize() }
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(points, plot) {
                detectTapGestures { at -> onSelect(ScatterScale.nearest(points, ::toScreen, at, radius)?.id) }
            }
            .pointerInput(points, plot) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        val change = e.changes.firstOrNull() ?: continue
                        val hovering = e.type == PointerEventType.Move && !change.pressed &&
                            (change.type == PointerType.Stylus || change.type == PointerType.Mouse)
                        if (hovering) ScatterScale.nearest(points, ::toScreen, change.position, radius)?.let { onSelect(it.id) }
                    }
                }
            },
    ) {
        val lo = bounds.start
        val hi = bounds.endInclusive
        // grid and tick labels
        ticks.forEach { t ->
            val x = toScreen(ScatterPoint("", t, lo)).x
            val y = toScreen(ScatterPoint("", lo, t)).y
            drawLine(colors.outlineVariant, Offset(x, 0f), Offset(x, size.height - gutter), 1f)
            drawLine(colors.outlineVariant, Offset(gutter, y), Offset(size.width, y), 1f)
            val tl = measurer.measure(DecimalFormat("0").format(t), tickStyle)
            drawText(tl, topLeft = Offset(x - tl.size.width / 2f, size.height - gutter + 4f))
            drawText(tl, topLeft = Offset(gutter - tl.size.width - 6f, y - tl.size.height / 2f))
        }
        // actual = expected
        drawLine(colors.outline, toScreen(ScatterPoint("", lo, lo)), toScreen(ScatterPoint("", hi, hi)), 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        drawText(measurer.measure(aboveLabel, noteStyle), topLeft = Offset(gutter + 8f, 8f))
        val below = measurer.measure(belowLabel, noteStyle)
        drawText(below, topLeft = Offset(size.width - below.size.width - 8f, size.height - gutter - below.size.height - 8f))
        // axis titles
        val xl = measurer.measure(xLabel, tickStyle)
        drawText(xl, topLeft = Offset((size.width + gutter - xl.size.width) / 2, size.height - xl.size.height))
        // (y title: draw rotated with rotate(-90f, pivot) around the left gutter)
        // population first, highlighted players on top
        points.filter { it.color == null }.forEach { drawCircle(colors.onSurfaceVariant.copy(alpha = 0.35f), 3.dp.toPx(), toScreen(it)) }
        points.filter { it.color != null }.forEach { p ->
            val o = toScreen(p)
            drawCircle(p.color!!, 6.dp.toPx(), o)
            p.label?.let { drawText(measurer.measure(it, labelStyle), topLeft = o + Offset(8.dp.toPx(), -8.dp.toPx())) }
        }
        points.firstOrNull { it.id == selectedId }?.let { drawCircle(colors.primary, 9.dp.toPx(), toScreen(it), style = Stroke(2.dp.toPx())) }
    }
}
```

Complete the y-axis title with `rotate(-90f, pivot)` from `androidx.compose.ui.graphics.drawscope.rotate`, placed in the left gutter. `toScreen` reads `plot`, which is the canvas size once measured; the pointer inputs key on `plot` so hit-testing uses current geometry.

- [ ] **Step 5: Screenshot and interaction tests**

`ChartsScreenshotTest.kt` (Robolectric, `sdk = [36]`, qualifiers `w412dp-h892dp-xxhdpi`, `GraphicsMode.NATIVE`), synthetic data only:
- `barsLightAndDark`: a `Column` of four `PercentileBarRow`s with two bars each (fractions 0.92/0.40, 0.05/0.61, null/0.77, 1.0/0.0), colors `SlotColors.color(0)` and `(1)`; captured in light (`charts_1_bars.png`) and dark (`charts_2_bars_dark.png`).
- `radarTwoPlayers`: seven WR-style axes, two series → `charts_3_radar.png`.
- `scatterWithHighlights`: 60 neutral points on a plausible spread (x in 2..20, y = x ± noise from a seeded `Random`), two colored labeled points, one above the diagonal and one below, one of them selected → `charts_4_scatter.png`.
- `tappingNearAPointSelectsIt`: render the scatter at a fixed size, compute a highlighted point's on-screen position with the same formula, `performTouchInput { click(position) }`, and assert `onSelect` received its id; click empty space → `null`.

Run: `./gradlew :core:charts:testDebugUnitTest :core:charts:recordRoborazziDebug --console=plain` → PASS. Open all four PNGs: bars readable in both themes, the dashed "not ranked" bar visible, radar labels unclipped, the scatter diagonal at 45°, and labels not colliding with axis ticks. Describe what you saw.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts core/charts core/designsystem
git commit -m "charts: Canvas percentile bars, radar and xFP scatter; slot colors

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NAmSfMG3LfdCw9DC5pxByG"
```

---

### Task 11: Compare repository (`:core:data`)

**Files:**
- Modify: `core/statquery/src/main/kotlin/dev/gridiron/core/statquery/StatQuerySpec.kt`, `StatQueryBuilder.kt` (`playerIds`)
- Modify: `core/data/src/main/kotlin/dev/gridiron/core/data/SampleThreshold.kt` (`forSample`)
- Create: `core/data/src/main/kotlin/dev/gridiron/core/data/{CompareMetricSets,CompareModels,CompareRepository}.kt`
- Test: add to `core/statquery/.../StatQueryBuilderTest.kt`; create `core/data/src/test/kotlin/dev/gridiron/core/data/{CompareMetricSetsTest,CompareRepositoryTest}.kt`

**Interfaces:**
- Consumes: Tasks 4 and 7.
- Produces:
  - `StatQuerySpec.playerIds: Set<String> = emptySet()`: an outer filter applied after percentiles, like `filters`.
  - `SampleThreshold.forSample(sample: StatColumn, playedWeeks: Int, perGame: Boolean): SampleThreshold?` (`forRequest` delegates to it).
  - `CompareGroup { OPPORTUNITY, EFFICIENCY, SCORING, CONTEXT }` with `label`; `CompareMetricSets.groupsFor(position: Position?): Map<CompareGroup, List<StatColumn>>`, `union(positions: List<Position?>): List<Pair<CompareGroup, List<StatColumn>>>`, `radarAxes(position: Position?): List<StatColumn>`, `qualifier(position: Position?): StatColumn`.
  - `CompareRequest(slots: List<CompareSlot>, scoring: ScoringProfile, perGame: Boolean)`; `CompareRepository(executor: QueryExecutor, locale: Locale = Locale.getDefault())` with `suspend fun compare(request: CompareRequest, catalog: Catalog): ComparePage`.
  - UI models (all `public data class`, lists as `ImmutableList`): `ComparePage(request, slots: ImmutableList<SlotHeader>, groups: ImmutableList<CompareGroupUi>, radar: RadarUi?, scatter: ScatterUi?)`; `SlotHeader(slot, name, detail, status: SlotStatus, position: Position?)`; `enum SlotStatus { OK, SMALL_SAMPLE, NO_GAMES, NO_SEASON, MISSING }`; `CompareGroupUi(group, composite: ImmutableList<Float?>, rows: ImmutableList<CompareRowUi>)`; `CompareRowUi(column, label, info: MetricInfo?, cells: ImmutableList<CompareCellUi>, spread: Float?, best: Int?, diff: String?)`; `CompareCellUi(text, value: Double?, percentile: Float?)` (percentile 0..1); `RadarUi(axes: ImmutableList<String>, values: ImmutableList<ImmutableList<Float?>>)` (one list per slot); `ScatterUi(position: Position, season: Int, weeks: WeekRange, population: ImmutableList<ScatterPointUi>, slots: ImmutableList<ScatterPointUi?>)`; `ScatterPointUi(playerId, name, xfpPerGame: Double, fpPerGame: Double)`.

**How a slot is queried.** Slots that share season, week range and position share one Grid query: the position's stat set plus its qualifier column, `positions = {position}`, the per-position qualifier from `SampleThreshold.forSample`, `includeUnqualified = true`, `percentiles = true`, `playerIds` = those slots' players, and the request's scoring and per-game mode. Percentiles are computed over the whole position before `playerIds` narrows the output, which is exactly the Grid's guarantee. A slot is `OK` when its qualifier column has a percentile, else `SMALL_SAMPLE` (values shown, no percentiles). With per-game on, the threshold's `minGames` floor can drop the slot's player from the population entirely; in that case the slot is re-queried alone with `minGames = 1` and `percentiles = false` and shown as `SMALL_SAMPLE`, so rankings still match the Grid. No row at all is `NO_GAMES`; a season missing from the catalog is `NO_SEASON` ("No 2023 data"); an id missing from `player` is `MISSING`.

- [ ] **Step 1: Write the failing tests**

Add to `StatQueryBuilderTest.kt`:

```kotlin
    @Test
    fun `filtering to players happens after percentiles`() {
        for ((i, id) in listOf("a", "b", "c", "d").withIndex()) {
            db.player(id, "Player $id")
            db.week(id, 1, C.TARGETS to (i + 1) * 3)
        }
        val all = db.grid(spec(TARGETS).copy(percentiles = true)).associateBy { it.playerId }
        val two = db.grid(spec(TARGETS).copy(percentiles = true, playerIds = setOf("b", "d")))
        assertEquals(listOf("d", "b"), two.map { it.playerId })
        for (r in two) assertEquals(all.getValue(r.playerId).percentile(TARGETS)!!, r.percentile(TARGETS)!!, EPS)
    }
```

`CompareMetricSetsTest.kt`:

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.model.Position
import dev.gridiron.core.statquery.StatColumn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompareMetricSetsTest {
    @Test
    fun `each position has all four groups and a fantasy scoring group`() {
        for (p in listOf(Position.QB, Position.RB, Position.WR, Position.TE)) {
            val g = CompareMetricSets.groupsFor(p)
            assertEquals(CompareGroup.entries.toSet(), g.keys, "$p")
            assertTrue(g.getValue(CompareGroup.SCORING).containsAll(listOf(StatColumn.FANTASY_POINTS, StatColumn.EXPECTED_FANTASY_POINTS, StatColumn.FPOE)))
        }
    }

    @Test
    fun `a mixed comparison shows the union in group order without duplicates`() {
        val union = CompareMetricSets.union(listOf(Position.QB, Position.WR))
        assertEquals(CompareGroup.entries, union.map { it.first })
        val opportunity = union.first().second
        assertTrue(StatColumn.DROPBACKS in opportunity && StatColumn.TARGETS in opportunity)
        assertEquals(opportunity.distinct(), opportunity)
    }

    @Test
    fun `radar axes are six to eight and qualifiers follow the spec`() {
        for (p in listOf(Position.QB, Position.RB, Position.WR, Position.TE)) {
            assertTrue(CompareMetricSets.radarAxes(p).size in 6..8)
        }
        assertEquals(StatColumn.DROPBACKS, CompareMetricSets.qualifier(Position.QB))
        assertEquals(StatColumn.CARRIES, CompareMetricSets.qualifier(Position.RB))
        assertEquals(StatColumn.TARGETS, CompareMetricSets.qualifier(Position.WR))
        assertEquals(StatColumn.TARGETS, CompareMetricSets.qualifier(Position.TE))
        assertEquals(CompareMetricSets.groupsFor(Position.RB), CompareMetricSets.groupsFor(Position.FB))
    }
}
```

`CompareRepositoryTest.kt` (real database; skip without it, as `StatsRepositoryTest` does):

```kotlin
package dev.gridiron.core.data

// imports: JUnit 6 (Assumptions.assumeTrue, BeforeEach/AfterEach, Test, Assertions.*), runTest,
// JdbcQueryExecutor, StatsDb, ScoringPresets, CompareSlot, Position, WeekRange, StatColumn, SqlQuery, Locale

class CompareRepositoryTest {
    private lateinit var executor: JdbcQueryExecutor
    private lateinit var stats: StatsRepository
    private lateinit var compare: CompareRepository
    private lateinit var catalog: Catalog

    @BeforeEach
    fun setUp() = runTest {
        assumeTrue(StatsDb.path != null, "GRIDIRON_STATS_DB not set")
        executor = JdbcQueryExecutor(StatsDb.path!!)
        stats = StatsRepository(executor, Locale.US)
        compare = CompareRepository(executor, Locale.US)
        catalog = stats.catalog()
    }

    @AfterEach
    fun tearDown() {
        if (::executor.isInitialized) executor.close()
    }

    private suspend fun topIds(pack: StatPack, filter: PositionFilter, n: Int, season: Int = 2025): List<String> {
        val s = catalog.season(season)
        return stats.grid(GridRequest(s, s.defaultWeeks, pack, filter), catalog).rows.take(n).map { it.playerId }
    }

    private fun request(vararg slots: CompareSlot, perGame: Boolean = false) =
        CompareRequest(slots.toList(), ScoringPresets.PPR, perGame)

    private val season2025 get() = catalog.season(2025).defaultWeeks

    @Test
    fun `two receivers are ranked in their position with composites that average their rows`() = runTest {
        val (a, b) = topIds(StatPack.RECEIVING, PositionFilter.WR, 2)
        val page = compare.compare(request(CompareSlot(a, 2025, season2025), CompareSlot(b, 2025, season2025)), catalog)
        assertEquals(listOf(SlotStatus.OK, SlotStatus.OK), page.slots.map { it.status })
        assertEquals(CompareGroup.entries, page.groups.map { it.group })
        for (g in page.groups) {
            for (slot in 0..1) {
                val ranked = g.rows.mapNotNull { it.cells[slot].percentile }
                val composite = g.composite[slot]
                if (ranked.isEmpty()) assertNull(composite) else assertEquals(ranked.average(), composite!!.toDouble(), 1e-6)
            }
        }
        val targets = page.groups.first().rows.first { it.column == StatColumn.TARGETS }
        assertNotNull(targets.diff)
        assertTrue(targets.cells.all { it.percentile!! in 0f..1f })
    }

    @Test
    fun `a player can be compared with himself across seasons`() = runTest {
        val id = topIds(StatPack.RECEIVING, PositionFilter.WR, 1).single()
        val page = compare.compare(
            request(CompareSlot(id, 2025, season2025), CompareSlot(id, 2025, WeekRange(1, 8))),
            catalog,
        )
        assertEquals(2, page.slots.size)
        assertNotEquals(page.slots[0].detail, page.slots[1].detail)
    }

    @Test
    fun `a light-usage player shows values but no percentiles`() = runTest {
        val light = executor.query(
            SqlQuery(
                "SELECT s.player_id FROM player_week_stat s JOIN player p USING (player_id) " +
                    "WHERE s.metric_id = ? AND s.season = ? AND p.position = ? " +
                    "GROUP BY s.player_id HAVING SUM(s.value) BETWEEN 1 AND 5 LIMIT 1",
                listOf(Bind.Text("targets"), Bind.Integer(2025), Bind.Text("WR")),
            ),
        ) { it.text(0) }.single()
        val star = topIds(StatPack.RECEIVING, PositionFilter.WR, 1).single()
        val page = compare.compare(request(CompareSlot(star, 2025, season2025), CompareSlot(light, 2025, season2025)), catalog)
        assertEquals(SlotStatus.SMALL_SAMPLE, page.slots[1].status)
        val targets = page.groups.first().rows.first { it.column == StatColumn.TARGETS }
        assertNotEquals("—", targets.cells[1].text)
        assertNull(targets.cells[1].percentile)
    }

    @Test
    fun `slots for a season that left the database, or an unknown player, don't break the page`() = runTest {
        val id = topIds(StatPack.RECEIVING, PositionFilter.WR, 1).single()
        val page = compare.compare(
            request(CompareSlot(id, 2025, season2025), CompareSlot(id, 2003, WeekRange(1, 17)), CompareSlot("00-0000000", 2025, season2025)),
            catalog,
        )
        assertEquals(listOf(SlotStatus.OK, SlotStatus.NO_SEASON, SlotStatus.MISSING), page.slots.map { it.status })
        assertEquals("No 2003 data", page.slots[1].detail)
        assertTrue(page.groups.flatMap { it.rows }.all { it.cells[1].text == "—" && it.cells[2].text == "—" })
    }

    @Test
    fun `a quarterback and a receiver show the union with dashes where a stat doesn't apply`() = runTest {
        val qb = topIds(StatPack.PASSING, PositionFilter.QB, 1).single()
        val wr = topIds(StatPack.RECEIVING, PositionFilter.WR, 1).single()
        val page = compare.compare(request(CompareSlot(qb, 2025, season2025), CompareSlot(wr, 2025, season2025)), catalog)
        val rows = page.groups.flatMap { it.rows }.associateBy { it.column }
        assertEquals("—", rows.getValue(StatColumn.DROPBACKS).cells[1].text)
        assertEquals("—", rows.getValue(StatColumn.TARGETS).cells[0].text)
        assertNull(rows.getValue(StatColumn.TARGETS).diff)
    }

    @Test
    fun `the scatter holds the first slot's position and places the compared players`() = runTest {
        val (a, b) = topIds(StatPack.RECEIVING, PositionFilter.WR, 2)
        val page = compare.compare(request(CompareSlot(a, 2025, season2025), CompareSlot(b, 2025, season2025)), catalog)
        val scatter = page.scatter!!
        assertEquals(Position.WR, scatter.position)
        assertTrue(scatter.population.size > 40)
        assertTrue(scatter.population.all { it.fpPerGame in -5.0..45.0 && it.xfpPerGame in 0.0..40.0 })
        assertEquals(a, scatter.slots[0]!!.playerId)
        assertEquals(page.radar!!.axes.size, page.radar!!.values[0].size)
    }
}
```

(`CompareSlot(id, 2003, …)` is a real player id in a season the rolling three-season database doesn't hold.)

Run: `./gradlew :core:statquery:test :core:data:test --console=plain` → FAIL (unresolved symbols).

- [ ] **Step 2: Implement `playerIds` and `forSample`**

`StatQuerySpec`: add `val playerIds: Set<String> = emptySet(),` after `teams` (KDoc: `@property playerIds Only these players. Applied after percentiles, like [filters], so it never moves a percentile.`). `SqlWriter.where`: after the `teams` condition,

```kotlin
        if (spec.playerIds.isNotEmpty()) {
            conditions += "player_id IN (${spec.playerIds.sorted().joinToString(", ") { text(it) }})"
        }
```

`SampleThreshold`: extract the body of `forRequest` after `val sample = …` into `public fun forSample(sample: StatColumn, playedWeeks: Int, perGame: Boolean): SampleThreshold?`, and make `forRequest` return `forSample(sort.sample ?: pack.population ?: return null, playedWeeks, perGame)`.

- [ ] **Step 3: Implement the stat sets**

`CompareMetricSets.kt` (lists copied from the spec's table):

```kotlin
package dev.gridiron.core.data

import dev.gridiron.core.model.Position
import dev.gridiron.core.statquery.StatColumn
import dev.gridiron.core.statquery.StatColumn.*

public enum class CompareGroup(public val label: String) {
    OPPORTUNITY("Opportunity"),
    EFFICIENCY("Efficiency"),
    SCORING("Scoring"),
    CONTEXT("Context"),
}

/**
 * Which stats Compare shows per position, in one table so they can be tuned
 * without touching the UI. From the design spec.
 */
public object CompareMetricSets {
    private val QB_SET = mapOf(
        CompareGroup.OPPORTUNITY to listOf(DROPBACKS, ATTEMPTS, CARRIES, QB_RUSH_INSIDE_5, SNAP_SHARE),
        CompareGroup.EFFICIENCY to listOf(EPA_PER_DROPBACK, CPOE, SACKS_TAKEN, INTERCEPTIONS),
        CompareGroup.SCORING to listOf(FANTASY_POINTS, EXPECTED_FANTASY_POINTS, FPOE, PASSING_TDS, RUSHING_TDS),
        CompareGroup.CONTEXT to listOf(PASSING_YARDS, OFFENSE_SNAPS, TOTAL_EPA),
    )
    private val RB_SET = mapOf(
        CompareGroup.OPPORTUNITY to listOf(CARRIES, CARRY_SHARE, TARGETS, TARGET_SHARE, WEIGHTED_OPPORTUNITIES, RZ_CARRIES, GL_CARRIES, SNAP_SHARE),
        CompareGroup.EFFICIENCY to listOf(RUSH_SUCCESS_RATE, RUSH_EPA_PER_CARRY, CATCH_RATE),
        CompareGroup.SCORING to listOf(FANTASY_POINTS, EXPECTED_FANTASY_POINTS, FPOE, RUSHING_TDS, RECEIVING_TDS),
        CompareGroup.CONTEXT to listOf(RUSHING_YARDS, RECEIVING_YARDS, OFFENSE_SNAPS, TOTAL_EPA),
    )
    private val WR_SET = mapOf(
        CompareGroup.OPPORTUNITY to listOf(TARGETS, TARGET_SHARE, AIR_YARDS_SHARE, WOPR, RZ_TARGETS, EZ_TARGETS, SNAP_SHARE),
        CompareGroup.EFFICIENCY to listOf(ADOT, RACR, CATCH_RATE, YAC),
        CompareGroup.SCORING to listOf(FANTASY_POINTS, EXPECTED_FANTASY_POINTS, FPOE, RECEIVING_TDS),
        CompareGroup.CONTEXT to listOf(RECEIVING_YARDS, RECEPTIONS, OFFENSE_SNAPS, TOTAL_EPA),
    )

    public fun groupsFor(position: Position?): Map<CompareGroup, List<StatColumn>> = when (position) {
        Position.QB -> QB_SET
        Position.RB, Position.FB -> RB_SET
        else -> WR_SET
    }

    public fun union(positions: List<Position?>): List<Pair<CompareGroup, List<StatColumn>>> =
        CompareGroup.entries.map { g -> g to positions.flatMap { groupsFor(it).getValue(g) }.distinct() }

    public fun radarAxes(position: Position?): List<StatColumn> = when (position) {
        Position.QB -> listOf(EPA_PER_DROPBACK, CPOE, DROPBACKS, CARRIES, PASSING_TDS, FPOE)
        Position.RB, Position.FB -> listOf(CARRY_SHARE, TARGET_SHARE, RUSH_SUCCESS_RATE, RUSH_EPA_PER_CARRY, GL_CARRIES, SNAP_SHARE, FPOE)
        else -> listOf(TARGET_SHARE, AIR_YARDS_SHARE, ADOT, RACR, YAC, RZ_TARGETS, FPOE)
    }

    /** Who is ranked at each position: the spec's population qualifiers. */
    public fun qualifier(position: Position?): StatColumn = when (position) {
        Position.QB -> DROPBACKS
        Position.RB, Position.FB -> CARRIES
        else -> TARGETS
    }
}
```

(Use explicit imports if the build's lint forbids star imports; the existing code imports each column explicitly.)

- [ ] **Step 4: Implement the models and repository**

`CompareModels.kt`: the data classes and enum listed under **Interfaces**, each with a one-line KDoc; `CompareRequest` is `public data class CompareRequest(val slots: List<CompareSlot>, val scoring: ScoringProfile, val perGame: Boolean)`.

`CompareRepository.kt`:

```kotlin
package dev.gridiron.core.data

// imports: QueryExecutor, doubleOrNull, textOrNull, GridLayout, StatQueryBuilder, StatQuerySpec,
// ValueMode, StatColumn, Position, CompareSlot, WeekRange, immutable converters, Locale, abs

/** Everything the Compare screen shows, built from one Grid query per group of like slots. */
public class CompareRepository(
    private val executor: QueryExecutor,
    locale: Locale = Locale.getDefault(),
) {
    private val format = StatFormat(locale)

    private data class Found(
        val status: SlotStatus,
        val games: Long = 0,
        val values: Map<StatColumn, Double?> = emptyMap(),
        val percentiles: Map<StatColumn, Float?> = emptyMap(),
    )

    public suspend fun compare(request: CompareRequest, catalog: Catalog): ComparePage {
        val headers = players(request.slots.map { it.playerId })
        val positions = request.slots.map { s -> headers[s.playerId]?.position?.let(Position::fromCode) }
        val found = arrayOfNulls<Found>(request.slots.size)

        // Unknown players and seasons first; the rest grouped so like slots share a query.
        request.slots.forEachIndexed { i, s ->
            when {
                headers[s.playerId] == null -> found[i] = Found(SlotStatus.MISSING)
                catalog.seasons.none { it.season == s.season } -> found[i] = Found(SlotStatus.NO_SEASON)
            }
        }
        request.slots.indices.filter { found[it] == null }
            .groupBy { Triple(request.slots[it].season, request.slots[it].weeks, positions[it]) }
            .forEach { (key, idx) ->
                val (season, weeks, position) = key
                val rows = rankedRows(season, weeks, position, idx.map { request.slots[it].playerId }.toSet(), request, catalog)
                for (i in idx) {
                    found[i] = rows[request.slots[i].playerId]
                        ?: unrankedRow(request.slots[i], position, request)
                        ?: Found(SlotStatus.NO_GAMES)
                }
            }

        val slots = request.slots.mapIndexed { i, s -> header(s, headers[s.playerId], found[i]!!, positions[i], catalog) }
        val usable = request.slots.indices.filter { found[it]!!.status in setOf(SlotStatus.OK, SlotStatus.SMALL_SAMPLE) }
        val groups = CompareMetricSets.union(usable.map { positions[it] }).map { (group, columns) ->
            val rows = columns.map { column -> row(column, request, positions, found.map { it!! }, catalog) }
            CompareGroupUi(
                group,
                request.slots.indices.map { i ->
                    rows.mapNotNull { it.cells[i].percentile }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
                }.toImmutableList(),
                rows.toImmutableList(),
            )
        }
        val radar = usable.firstOrNull()?.let { first ->
            val axes = CompareMetricSets.radarAxes(positions[first])
            RadarUi(
                axes.map { catalog.metrics[it.metricId]?.abbr ?: it.metricId }.toImmutableList(),
                request.slots.indices.map { i -> axes.map { found[i]!!.percentiles[it] }.toImmutableList() }.toImmutableList(),
            )
        }
        val scatter = usable.firstOrNull()?.let { first -> scatter(request.slots[first], positions[first], request, headers) }
        return ComparePage(request, slots.toImmutableList(), groups.toImmutableList(), radar, scatter)
    }
```

Complete the private functions with these contracts:

- `players(ids)`: `StatQueryBuilder.players(ids)` → `Map<String, PlayerHeader>` (same code as `StatsRepository.players`; move it to a shared `internal suspend fun QueryExecutor.playerHeaders(ids)` in `core/data` and call it from both).
- `rankedRows(season, weeks, position, ids, request, catalog): Map<String, Found>`: builds the spec described above (columns = `groupsFor(position)` flattened, plus `qualifier(position)`, deduplicated); `playedWeeks = (min(weeks.last, seasonInfo.lastWeek) - weeks.first + 1).coerceAtLeast(1)`; `threshold = SampleThreshold.forSample(qualifier, playedWeeks, request.perGame)`; `minGames = threshold?.minGames ?: 1`; `mode = if (perGame) PER_GAME else TOTAL`; `limit = ids.size`. Maps each row to `Found(status = if (percentile(qualifier) != null) OK else SMALL_SAMPLE, games, values, percentiles)` keyed by player id.
- `unrankedRow(slot, position, request): Found?`: only when per-game is on: the same columns, `playerIds = {id}`, `minGames = 1`, `percentiles = false`, no qualifiers → `Found(SMALL_SAMPLE, …)` with empty percentiles, or null if no row.
- `header(slot, player, found, position, catalog)`: `name` = player name or the raw id; `detail` = `"No ${slot.season} data"` for `NO_SEASON`, `"Not in the database"` for `MISSING`, otherwise `"${position ?: "–"} · ${team ?: "FA"} · ${describeSlot(slot, catalog)}"`, plus `" · ${games} g"` when `found.games > 0`, plus `" · small sample"` for `SMALL_SAMPLE`, or `" · no games"` for `NO_GAMES`.
- `row(column, request, positions, found, catalog)`: a cell per slot. `"—"` when the slot isn't `OK`/`SMALL_SAMPLE` or the column isn't in `groupsFor(positions[i])`; else `format.format(column, value, request.perGame)` with the percentile. `spread` = max − min of non-null percentiles when at least two exist. `best` = index of the highest value (lowest when `!column.higherIsBetter`) among non-null values when at least two exist and they aren't all equal. `diff`, only with exactly two slots and both values present: `d = v0 - v1`; `"0"` when `format.format(column, abs(d), perGame)` equals the formatted zero, else `"+"` or `"−"` (U+2212) followed by `format.format(column, abs(d), perGame)`. `label` = catalog name or metric id; `info` = catalog entry.
- `scatter(slot, position, request, headers)`: population spec: `columns = [FANTASY_POINTS, EXPECTED_FANTASY_POINTS]`, `positions = {position}` (skip the scatter, returning null, when position is null), `mode = PER_GAME`, the per-game threshold for `qualifier(position)`, `minGames` from it, `limit = MAX_LIMIT`, scoring from the request. Slot points: for each slot with a usable status, a `playerIds = {id}`, `includeUnqualified = true`, `minGames = 1` per-game query over that slot's own season and weeks; `null` for others. `ScatterPointUi(id, name, xfp, fp)`.

- [ ] **Step 5: Run and confirm pass**

Run: `./gradlew :core:statquery:test :core:data:test --console=plain` (with `GRIDIRON_STATS_DB` exported)
Expected: PASS. Time the four-slot compare once (print `System.nanoTime()` deltas in a scratch test or the test log) and put the number in your report.

- [ ] **Step 6: Commit**

```bash
git add core/statquery core/data
git commit -m "data: compare repository with per-position stat sets, composites, radar and scatter

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NAmSfMG3LfdCw9DC5pxByG"
```

---

### Task 12: Compare screen (`:feature:compare`)

**Files:**
- Create: `feature/compare/build.gradle.kts`
- Create: `feature/compare/src/main/kotlin/dev/gridiron/feature/compare/{CompareViewModel,CompareScreen,BarsTab,TableTab,RadarTab,ScatterTab,Summaries}.kt`
- Modify: `settings.gradle.kts`
- Test: `feature/compare/src/test/kotlin/dev/gridiron/feature/compare/{SummariesTest,CompareViewModelTest,CompareScreenTest}.kt`

**Interfaces:**
- Consumes: Task 8 `:core:ui` (`ProfileChip`, `MetricSheet`), Task 10 charts and `SlotColors`, Task 11 `CompareRepository` and models, Task 7 repositories, `StatTable` from `:core:table`.
- Produces: `CompareRoute(stats: StatsRepository, compare: CompareRepository, scoring: ScoringRepository, tray: CompareTrayRepository, onBack: () -> Unit, onEditProfiles: () -> Unit)`.

- [ ] **Step 1: Module**

`feature/compare/build.gradle.kts`: same shape as `feature/players/build.gradle.kts`, namespace `dev.gridiron.feature.compare`, `implementation(projects.core.ui)`, `implementation(projects.core.charts)`, `implementation(projects.core.table)`, lifecycle libraries, and the same test dependencies. `include(":feature:compare")` in `settings.gradle.kts`.

- [ ] **Step 2: Write the failing summary and view model tests**

`Summaries.kt` holds pure functions that build the TalkBack descriptions and the landscape decision, tested without Compose:

```kotlin
class SummariesTest {
    @Test
    fun radarSummaryCountsAxesWon() {
        val radar = RadarUi(
            persistentListOf("TGT%", "AY%", "aDOT", "RACR", "YAC", "RZ TGT", "FPOE"),
            persistentListOf(
                persistentListOf(0.9f, 0.8f, 0.6f, 0.7f, 0.6f, 0.9f, 0.5f),
                persistentListOf(0.5f, 0.6f, 0.4f, 0.3f, 0.2f, 0.95f, null),
            ),
        )
        assertEquals("Radar: Nacua higher on 5 of 7 axes than Adams", radarSummary(radar, listOf("Nacua", "Adams"), 0, 1))
    }

    @Test
    fun scatterSummaryDescribesEachComparedPlayer() {
        val s = scatterSummary(
            listOf(ScatterPointUi("a", "Nacua", 14.0, 17.2), ScatterPointUi("b", "Adams", 13.1, 11.0)),
            population = 88,
        )
        assertEquals(
            "Expected versus actual fantasy points per game for 88 players. " +
                "Nacua: 17.2 per game, 3.2 above expected. Adams: 11.0 per game, 2.1 below expected.",
            s,
        )
    }
}
```

`CompareViewModelTest.kt` (JUnit 4, real database, `FakePrefsSource` seeded with a tray, `Dispatchers.setMain`):

```kotlin
    @Test
    fun loadsTheTrayAndReactsToPerGameAndProfile() = runTest {
        val vm = viewModel(tray = twoReceivers())
        advanceUntilIdle()
        val ready = vm.state.value as CompareUiState.Ready
        assertEquals(2, ready.page.slots.size)
        val fpTotal = ready.page.groups.first { it.group == CompareGroup.SCORING }.rows.first().cells[0].text
        vm.onEvent(CompareEvent.PerGameToggled)
        advanceUntilIdle()
        val fpPerGame = (vm.state.value as CompareUiState.Ready).page.groups.first { it.group == CompareGroup.SCORING }.rows.first().cells[0].text
        assertNotEquals(fpTotal, fpPerGame)
        vm.onEvent(CompareEvent.ProfileSelected(ScoringPresets.STANDARD.id))
        advanceUntilIdle()
        assertEquals(ScoringPresets.STANDARD, (vm.state.value as CompareUiState.Ready).page.request.scoring)
    }

    @Test
    fun fewerThanTwoSlotsShowsTheEmptyState() = runTest {
        val vm = viewModel(tray = twoReceivers().take(1))
        advanceUntilIdle()
        assertEquals(CompareUiState.NeedsPlayers, vm.state.value)
    }

    @Test
    fun removingASlotUpdatesTheTray() = runTest {
        val slots = twoReceivers() + threeMore().take(2) // four: the tray's capacity
        val vm = viewModel(tray = slots)
        advanceUntilIdle()
        vm.onEvent(CompareEvent.RemoveSlot(slots.last()))
        advanceUntilIdle()
        assertEquals(slots.dropLast(1), prefs.current.tray)
    }

    @Test
    fun radarPairDefaultsToTheFirstTwoAndCanChange() = runTest {
        val vm = viewModel(tray = twoReceivers() + threeMore().take(1))
        advanceUntilIdle()
        assertEquals(0 to 1, (vm.state.value as CompareUiState.Ready).radarPair)
        vm.onEvent(CompareEvent.RadarPairChanged(0, 2))
        assertEquals(0 to 2, (vm.state.value as CompareUiState.Ready).radarPair)
    }

    @Test
    fun addingAScatterPointUsesTheFirstSlotsSeasonAndRange() = runTest {
        val slots = twoReceivers()
        val vm = viewModel(tray = slots)
        advanceUntilIdle()
        val other = (vm.state.value as CompareUiState.Ready).page.scatter!!.population.first { p -> slots.none { it.playerId == p.playerId } }
        vm.onEvent(CompareEvent.AddPointToCompare(other.playerId, other.name))
        advanceUntilIdle()
        assertEquals(CompareSlot(other.playerId, slots[0].season, slots[0].weeks), prefs.current.tray.last())
    }
```

`twoReceivers()` and `threeMore()` take the top WR ids from `StatsRepository.grid(...)` for 2025 over the regular season (`threeMore` uses RB and QB ids, so the page mixes positions). `viewModel(tray)` seeds `prefs = FakePrefsSource(UserPrefs.DEFAULT.copy(tray = tray))` and builds `CompareViewModel(stats, compare, ScoringRepository(prefs), CompareTrayRepository(prefs))`.

Run → FAIL.

- [ ] **Step 3: Implement the summaries and the view model**

`Summaries.kt`:

```kotlin
internal fun radarSummary(radar: RadarUi, names: List<String>, a: Int, b: Int): String {
    val pairs = radar.values[a].zip(radar.values[b]).filter { it.first != null && it.second != null }
    val wins = pairs.count { it.first!! > it.second!! }
    return "Radar: ${names[a]} higher on $wins of ${radar.axes.size} axes than ${names[b]}"
}

internal fun scatterSummary(slots: List<ScatterPointUi>, population: Int): String = buildString {
    append("Expected versus actual fantasy points per game for $population players.")
    for (p in slots) {
        val d = p.fpPerGame - p.xfpPerGame
        append(" ${p.name}: ${"%.1f".format(Locale.US, p.fpPerGame)} per game, ")
        append("${"%.1f".format(Locale.US, abs(d))} ${if (d >= 0) "above" else "below"} expected.")
    }
}
```

`CompareViewModel.kt`:

```kotlin
internal enum class CompareTab(val label: String) { BARS("Bars"), TABLE("Table"), RADAR("Radar"), SCATTER("Scatter") }

internal sealed interface CompareUiState {
    data object Loading : CompareUiState
    data object NeedsPlayers : CompareUiState
    data class Failed(val message: String) : CompareUiState
    data class Ready(
        val page: ComparePage,
        val catalog: Catalog,
        val profiles: ImmutableList<ScoringProfile>,
        val tab: CompareTab = CompareTab.BARS,
        val onlyDifferences: Boolean = false,
        val radarPair: Pair<Int, Int> = 0 to 1,
        val selectedPoint: String? = null,
        val refreshing: Boolean = false,
        val message: String? = null,
    ) : CompareUiState
}

internal sealed interface CompareEvent {
    data class TabSelected(val tab: CompareTab) : CompareEvent
    data object PerGameToggled : CompareEvent
    data object OnlyDifferencesToggled : CompareEvent
    data class RadarPairChanged(val a: Int, val b: Int) : CompareEvent
    data class PointSelected(val playerId: String?) : CompareEvent
    data class AddPointToCompare(val playerId: String, val name: String) : CompareEvent
    data class RemoveSlot(val slot: CompareSlot) : CompareEvent
    data class ProfileSelected(val id: String) : CompareEvent
    data object MessageShown : CompareEvent
}
```

The view model loads the catalog once (`stats.catalog()`), then runs `combine(tray.slots, scoring.active, perGame) { … }.mapLatest { compare.compare(CompareRequest(slots, profile, perGame), catalog) }`, emitting `NeedsPlayers` when fewer than two slots remain. It keeps the view-only fields (`tab`, `onlyDifferences`, `radarPair`, `selectedPoint`, `message`) in their own `MutableStateFlow`s so a re-query never resets them, and folds them into `Ready`. `radarPair` is clamped to the current slot count (reset to `0 to 1` when a chosen index no longer exists). Errors from the repository become `Failed(message)` on the first load, or a `message` on later ones, with the last page kept. `AddPointToCompare` builds `CompareSlot(id, firstSlot.season, firstSlot.weeks)` and reports the tray result with the same messages as the Grid ("… added to compare", "… is already in compare", "Compare holds 4 players. Remove one first."). `factory(stats, compare, scoring, tray)` as in the other features.

Run the tests → PASS.

- [ ] **Step 4: Build the screen**

`CompareScreen(state, onEvent, onBack, onEditProfiles)` with a root `Surface` and `safeDrawing` insets:

- **Top bar:** "← Back", title "Compare", `ProfileChip`, and a "Per game" `FilterChip`.
- **Header:** a `Row` with one column per slot (`weight(1f)`), keyed by `slot` (self-comparison puts the same player id in two slots): a 10 dp dot in `SlotColors.color(i)`, the name (`titleSmall`, one line, ellipsis), the detail (`labelSmall`, two lines), and a small ✕ (`contentDescription` "Remove ‹name›") sending `RemoveSlot`. Slots whose status isn't `OK` show their detail in `colorScheme.error` when `NO_SEASON`/`MISSING`/`NO_GAMES`.
- **Tabs:** a `PrimaryTabRow` over `CompareTab.entries`; in landscape (`LocalConfiguration.current.orientation == ORIENTATION_LANDSCAPE`) the first tab is labeled "Bars + Table" and shows both panes in a `Row`, each `weight(1f)`, and `TABLE` is omitted.
- **Bars tab** (`BarsTab.kt`): `LazyColumn`; per group a sticky header with the group label and a `PercentileBarRow` for the composite (label "Overall ‹group›", text = the composite as a whole-number percentile, "—" if null), then a `PercentileBarRow` per row, with bars `Bar(cell.percentile, cell.text, SlotColors.color(i))`. The label uses `labelSlot` wrapped in a Material 3 `TooltipBox` with a `RichTooltip` (title = metric name, text = definition), which opens on long-press and on S Pen or mouse hover; `info == null` falls back to a plain label.
- **Table tab** (`TableTab.kt`): `StatTable` with the frozen column = stat label, one column per slot, plus a "Diff" column when there are exactly two slots. Cells show `text` over a small percentile ("87th") line; the `best` cell's text is `FontWeight.Bold`. A `FilterChip("Only real differences")` above it sends `OnlyDifferencesToggled`; when on, rows with `spread == null || spread <= 0.10f` are hidden. (`spread` is in 0..1 percentile units; 0.10 is the spec's 10 points.) `rowKey` = `column`.
- **Radar tab** (`RadarTab.kt`): if more than two slots, two rows of `FilterChip`s (one per slot, colored dot + name) choose `radarPair`. `RadarChart(axes, [series a, series b], contentDescription = radarSummary(...))`, then a legend and the sentence "Percentile within position. Outer ring = best at the position."
- **Scatter tab** (`ScatterTab.kt`): `ScatterChart(points = population as neutral points + slot points colored with labels, xLabel = "Expected points per game", yLabel = "Points per game", aboveLabel = "Sell high ↑", belowLabel = "Buy low ↓", contentDescription = scatterSummary(...), selectedId, onSelect = PointSelected)`. Below it, when a point is selected, a card with the name, "‹fp› per game · ‹xfp› expected", and a `Button("Add to compare")` sending `AddPointToCompare` (disabled when the tray is full, with the reason as its label). A caption names the population: "‹position›s, ‹season› ‹weeks›, per game".
- `NeedsPlayers`: "Hold players in the Grid to add them here. Compare needs at least two." and Back.
- Snackbar for `message`, then `MessageShown`, as in the Grid.

`CompareRoute(...)` builds the view model from `CompareViewModel.factory(...)` and collects state with `collectAsStateWithLifecycle()`.

- [ ] **Step 5: Screenshot tests**

`CompareScreenTest.kt` (Robolectric, `sdk = [36]`, `GraphicsMode.NATIVE`, real database; build `Ready` states directly from `CompareRepository` like `GridScreenTest` does):
- `barsTwoReceivers` (portrait) → `compare_1_bars.png`
- `barsDark` → `compare_2_bars_dark.png`
- `tableFourMixedOnlyDifferences` (QB, RB, two WRs; toggle on) → `compare_3_table.png`
- `radarChoosesTwoOfThree` → `compare_4_radar.png`
- `scatterWithSelection` → `compare_5_scatter.png`
- `selfComparison` (same WR, full season vs weeks 1–8): assert two header columns render, no crash → `compare_6_self.png`
- `landscapeBarsAndTable` with `@Config(qualifiers = "w892dp-h412dp-xxhdpi-land")` → `compare_7_landscape.png`
- `noSeasonSlot`: a 2003 slot beside a real one shows "No 2003 data" (assert the text) → `compare_8_missing_season.png`
- `holdingAStatLabelShowsItsDefinition`: long-press the "Targets" label, assert the definition text appears.

- [ ] **Step 6: Run and look at every screenshot**

Run: `./gradlew :feature:compare:testDebugUnitTest :feature:compare:recordRoborazziDebug --console=plain`
Expected: PASS. Open all eight PNGs. Check: the header fits four slots at 412 dp; bar colors match the header dots; dark mode is readable; the table's frozen column and diff column line up; radar labels don't clip; scatter labels don't overlap each other badly; landscape really shows two panes. Fix what you find, re-record, and describe the final images in your report.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts feature/compare
git commit -m "compare: bars, head-to-head table, radar and xFP scatter

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NAmSfMG3LfdCw9DC5pxByG"
```

---

### Task 13: Navigation, wiring and release checks (`:app`)

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`
- Create: `app/src/main/kotlin/dev/gridiron/app/{NavKeys,GridironNavHost}.kt`
- Modify: `app/src/main/kotlin/dev/gridiron/app/{GridironApplication,MainActivity}.kt`
- Modify: `README.md`, `docs/superpowers/specs/2026-09-22-compare-and-scoring-design.md` (status line only)
- Test: `app/src/test/kotlin/dev/gridiron/app/NavigationTest.kt`

**Interfaces:**
- Consumes: every earlier task.
- Produces: the installable app.

- [ ] **Step 1: Dependencies**

`libs.versions.toml`: versions `navigation3 = "1.1.7"`; libraries `androidx-navigation3-runtime` (`androidx.navigation3:navigation3-runtime`), `androidx-navigation3-ui` (`androidx.navigation3:navigation3-ui`), `androidx-lifecycle-viewmodel-navigation3` (`androidx.lifecycle:lifecycle-viewmodel-navigation3`, `version.ref = "lifecycle"`). `app/build.gradle.kts`: apply `alias(libs.plugins.kotlin.serialization)`; add those three plus `implementation(libs.kotlinx.serialization.json)` and every feature and core module the app wires (`projects.feature.players`, `feature.compare`, `feature.scoring`, `core.data`, `core.datastore`, `core.database`); set `versionCode = 2`, `versionName = "0.2.0"`. Add the same test dependencies as the feature modules (`projects.core.testing`, robolectric, compose ui test, ui-test-manifest), if `app` doesn't have them.

Check the Navigation 3 1.1.7 API in its sources jar (`~/.gradle/caches/modules-2/files-2.1/androidx.navigation3/`) before writing Step 3: the names `NavDisplay`, `rememberNavBackStack`, `entryProvider`/`entry`, `rememberSaveableStateHolderNavEntryDecorator` and (from lifecycle) `rememberViewModelStoreNavEntryDecorator`. Adapt to what's there.

- [ ] **Step 2: Write the failing navigation test**

`NavigationTest.kt` (Robolectric, real database via `JdbcQueryExecutor`, `FakePrefsSource`): render `GridironNavHost(deps)` with a `Deps` built from test doubles, then:
1. Long-press the first two player rows (by content description), `waitForIdle`.
2. Tap `compareButton`; assert the Compare title and both player names are displayed.
3. Press back (`Espresso.pressBack()` or `compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }`); assert the Grid is back with the tray still holding two players.
4. Open the profile chip, choose "Edit profiles…"; assert "Scoring profiles" is displayed; tap `duplicate:preset:ppr`; assert the editor opens with the name "PPR copy".

Run → FAIL (no `GridironNavHost`).

- [ ] **Step 3: Implement**

`NavKeys.kt`:

```kotlin
package dev.gridiron.app

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object GridKey : NavKey
@Serializable data object CompareKey : NavKey
@Serializable data object ScoringListKey : NavKey
@Serializable data class ScoringEditKey(val profileId: String) : NavKey
```

`GridironNavHost.kt`:

```kotlin
package dev.gridiron.app

/** What the screens need, built by [GridironApplication] or by a test. */
data class Deps(
    val stats: StatsRepository,
    val compare: CompareRepository,
    val scoring: ScoringRepository,
    val tray: CompareTrayRepository,
)

@Composable
fun GridironNavHost(deps: Deps) {
    val backStack = rememberNavBackStack(GridKey)
    val back: () -> Unit = { backStack.removeLastOrNull() }
    NavDisplay(
        backStack = backStack,
        onBack = back,
        // Each destination gets its own saved state and ViewModelStore, so
        // leaving Compare clears its view model and returning rebuilds it.
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        entryProvider = entryProvider {
            entry<GridKey> {
                GridRoute(
                    deps.stats, deps.scoring, deps.tray,
                    onCompare = { backStack.add(CompareKey) },
                    onEditProfiles = { backStack.add(ScoringListKey) },
                )
            }
            entry<CompareKey> {
                CompareRoute(deps.stats, deps.compare, deps.scoring, deps.tray, onBack = back, onEditProfiles = { backStack.add(ScoringListKey) })
            }
            entry<ScoringListKey> {
                ScoringListRoute(deps.scoring, onEdit = { backStack.add(ScoringEditKey(it)) }, onBack = back)
            }
            entry<ScoringEditKey> { key -> ScoringEditRoute(key.profileId, deps.scoring, onDone = back) }
        },
    )
}
```

`GridironApplication.kt`:

```kotlin
/**
 * The app's object graph, by hand. Four repositories over one database
 * connection and one preferences file still don't justify Hilt.
 */
class GridironApplication : Application() {
    // Outlives every screen; the preferences file is written on it.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val executor by lazy {
        DeferredQueryExecutor { SqliteQueryExecutor.openReadOnly(StatsDbInstaller(this).install().path) }
    }
    private val prefs by lazy { UserPrefsStore.create(File(filesDir, "user_prefs.json"), appScope) }

    val deps: Deps by lazy {
        Deps(StatsRepository(executor), CompareRepository(executor), ScoringRepository(prefs), CompareTrayRepository(prefs))
    }
}
```

`MainActivity.onCreate`: `setContent { GridironTheme { GridironNavHost((application as GridironApplication).deps) } }`.

Run the navigation test → PASS.

- [ ] **Step 4: README and spec status**

`README.md`:
- **Status** table: add rows for Compare (tray, bars, table, radar, scatter) and Custom scoring (profiles, editor, FPTS/xFP/FPOE in the Grid), marked done.
- **Modules** table: add `:core:datastore`, `:core:ui`, `:core:charts`, `:feature:scoring`, `:feature:compare` with one-line roles.
- **Spec departures:** (1) `fpoe` stays in the ETL registry, flagged `computed`, with two new computed rows, because columns take their names from the registry; (2) scoring inputs are stored sparse (zeros omitted); (3) the tray bar lives in `:feature:players` and the shared sheets and profile chip in `:core:ui`, so no feature depends on another; (4) the repositories read preferences through a `PrefsSource` interface so view-model tests can run on virtual time.
- **Data sources:** ffopportunity alongside nflverse, with the license found in Task 2.
- **How to use:** hold a player row to add him to compare (up to 4); the chip at the top picks the scoring profile; "Edit profiles…" to make your league's.

In the spec, change the status line to `Status: implemented (see plan docs/superpowers/plans/2026-09-22-compare-and-scoring.md)`.

- [ ] **Step 5: Full verification**

Run from the repository root with both environment variables exported:

```bash
(cd etl && python3 -m pytest -q)
./gradlew test recordRoborazziDebug :app:assembleRelease --console=plain
```

Expected: everything PASSES and `app/build/outputs/apk/release/app-release.apk` exists. Then check the APK:

```bash
APK=app/build/outputs/apk/release/app-release.apk
BT=$(ls -d /opt/android-sdk/build-tools/* | sort -V | tail -1)
$BT/aapt2 dump permissions $APK          # expect: no uses-permission lines
$BT/zipalign -c -P 16 -v 4 $APK | tail -1 # expect: Verification successful
unzip -l $APK | grep -E "assets/stats.db|arm64-v8a/libsqliteJni.so"
$BT/apksigner verify --print-certs $APK | head -3
ls -l $APK
```

Report the APK size (the last one was 33.6 MiB). Open every PNG under `*/build/outputs/roborazzi/` produced in this phase and confirm none regressed.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml app README.md docs/superpowers/specs
git commit -m "app: Navigation 3 with Grid, Compare and scoring screens; v0.2.0

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NAmSfMG3LfdCw9DC5pxByG"
```
