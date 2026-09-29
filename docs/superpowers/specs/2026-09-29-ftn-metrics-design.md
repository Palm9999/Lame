# FTN charting metrics: design

**Goal.** Add ten FTN charting metrics to the Grid, built by the phone's Kotlin ingest and matched by the Python ETL. Display only: the forecast doesn't use them, so no forecast or accuracy-gate change. Second half of HANDOFF's "more metrics" (NGS shipped in PR #20).

**Out of scope.** Screen, RPO, motion, no-huddle, box-count and hash flags; Compare and the Player page; any forecast use; seasons before 2022 (FTN doesn't publish them).

## Source facts (checked against the published 2025 file)

- One file per season, 2022 onward: `nflverse-data/releases/download/ftn_charting/ftn_charting_<season>.csv`, about 8 MB, **uncompressed** (a `.csv.gz` 404s). Earlier seasons 404.
- One row per play (47,316 in 2025), keyed by `nflverse_game_id` + `nflverse_play_id`, which are play-by-play's `game_id` + `play_id`. **No player ids**: flags attribute through play-by-play (`receiver_player_id`, `passer_player_id`).
- Join coverage, 2025: every one of the 19,819 pass attempts has an FTN row, and no FTN row is left unmatched. Rates are sane (drops 758 of 17,582 targets, play-action 23% of dropbacks, blitzed 29%).
- Booleans are `TRUE`/`FALSE`. `is_drop` is not a subset of `is_catchable_ball` (744 of 766 drops are also catchable), so rates use targets as the denominator.
- The file is rewritten weekly (`date_pulled`), and may lag play-by-play mid-season.
- License CC BY-SA 4.0: credit "FTN Data via nflverse".

## Attribution of flags to players

Same play set and same eligibility as the play-by-play aggregator: scrimmage plays in REG or POST, no two-point tries, kneels and spikes excluded. Only plays that have an FTN row count, for numerators and denominators alike.

- **Receiver** (`receiver_player_id`, on a play that is a target): `ftn_targets` += 1; `ftn_catchable`, `ftn_contested`, `ftn_drops`, `ftn_created_rec` += 1 when the matching flag is TRUE.
- **Passer** (`passer_player_id`): a play with dropback weight w = pass attempt + sack + scramble (the same weight the `dropbacks` component uses): `ftn_dropbacks` += w; `ftn_pa_db`, `ftn_blitz_db`, `ftn_oop_db`, `ftn_throwaway` += w when `is_play_action`, `n_blitzers > 0`, `is_qb_out_of_pocket`, `is_throw_away` is TRUE. On a pass attempt: `ftn_attempts` += 1 and `ftn_int_worthy` += 1 when `is_interception_worthy`.

## Storage: FTN's own denominators, non-sparse

Rates are recomputed over a range from stored counts, never averaged. The denominators are FTN's own (counted only on plays with an FTN row), so a week play-by-play has but FTN doesn't yet leaves the rate untouched instead of dragging it toward 0. Nothing here is sparse: a week with zero drops is a real 0, stored whenever the player has a denominator that week. Each visible rate is also stored weekly (count / denominator), because the contract test needs every visible metric's weekly value.

Components (all internal unless marked): `ftn_targets`, `ftn_catchable`, `ftn_contested`, `ftn_dropbacks`, `ftn_attempts`, `ftn_pa_db`, `ftn_blitz_db`, `ftn_oop_db`, `ftn_throwaway`, `ftn_int_worthy`. Visible counts: `ftn_drops`, `ftn_created_rec`.

## The ten metrics (tier B, group "ftn")

| Metric id | Abbr | Positions | Aggregate | Better |
|---|---|---|---|---|
| `ftn_catchable_rate` | CATCH% | RB, WR, TE | `ftn_catchable / ftn_targets` | higher |
| `ftn_drop_rate` | DRP% | RB, WR, TE | `ftn_drops / ftn_targets` | lower |
| `ftn_contested_rate` | CTD% | RB, WR, TE | `ftn_contested / ftn_targets` | neutral |
| `ftn_drops` | DRP | RB, WR, TE | Total | lower |
| `ftn_created_rec` | CRT | RB, WR, TE | Total | higher |
| `ftn_play_action_rate` | PA% | QB | `ftn_pa_db / ftn_dropbacks` | neutral |
| `ftn_blitz_rate` | BLZ% | QB | `ftn_blitz_db / ftn_dropbacks` | neutral |
| `ftn_out_of_pocket_rate` | OOP% | QB | `ftn_oop_db / ftn_dropbacks` | neutral |
| `ftn_throwaway_rate` | TA% | QB | `ftn_throwaway / ftn_dropbacks` | lower |
| `ftn_int_worthy_rate` | IW% | QB | `ftn_int_worthy / ftn_attempts` | lower |

Rates are stored as fractions (0-1) and shown as percentages, like the other shares (`StatFormat`'s PERCENT set). Neutral metrics are `higherIsBetter = true` in the registry, as with NGS. Every definition says FTN charting starts in 2022.

## Pipeline

- **Kotlin** (`:core:ingest`): `Input.FTN` (per season, "FTN charting", URL above) joins `SEASON_INPUTS`, so the existing per-season reuse rule (validators per season, unchanged seasons copied) applies unchanged. `play_id` is added to `PBP_COLUMNS` and `Play`. New `Ftn.kt`: `readFtn` (the two ids plus the eleven flags, required) into an index keyed by (game id, play id), and `FtnAggregator(index)` fed by the same play loop as the other aggregators, emitting one `PlayerWeek` per player-week with a denominator. Facts go through `toFacts`. `INGEST_VERSION` 6 to 7.
- **Missing or bad file:** a 2022+ season with no file warns "no FTN charting yet"; earlier seasons say nothing. An unreadable file or a missing column leaves FTN out of that season with a warning. The build never fails on FTN.
- **Validation:** one coverage warning per season when FTN covers under 90% of pass attempts. Counts can't exceed their denominators by construction.
- **Python** (`etl/gridiron_etl/ftn.py`, `sources.py`, `metrics.py`, `build.py`): the same join and attribution in polars; the parity job holds the two builds to identical facts.
- **Statquery/Data:** `Components`, ten `StatColumn`s (`sample`: QB rates on `DROPBACKS`, INT-worthy on `ATTEMPTS`, receiver metrics on `TARGETS`), `StatFormat`, and two packs: `FTN_PASSING` (PA%, BLZ%, OOP%, TA%, IW%, dropbacks; leads with PA%) and `FTN_RECEIVING` (CATCH%, DRP%, CTD%, DRP, CRT, targets; leads with CATCH%). A pack's default sort is its lead column, which `StatsRepositoryTest` requires.
- **Credit:** `SOURCE_NOTE` in the db, README "Attribution", CLAUDE.md "Data attribution": "FTN Data via nflverse (CC BY-SA 4.0)". ShareAlike matters only if the app or its stats.db is ever distributed (it is a personal app); HANDOFF records that.

## Tests

- **Kotlin unit:** `FtnTest` (reader parses TRUE/FALSE, requires every column; aggregator credits the target and the passer; kneels, spikes and two-point tries ignored; plays with no FTN row count nowhere; a zero-drop week stores zeros; dropback weight; INT-worthy only on attempts); `MetricsTest`, `StatsDbWriterTest` and `test_registry.py` counts (123 to 143); a statquery test that a two-week range is the count-weighted rate, not the mean of weekly rates.
- **Pipeline** (`FakeFetcher`): FTN unchanged and season unchanged (copied with its FTN facts); FTN changed rebuilds only that season; a 2022+ season with no file warns and builds; a 2019 season with no file is silent; a renamed column leaves FTN out with a warning; low coverage warns.
- **Python:** `test_ftn.py` mirroring the Kotlin cases with the same numbers.
- **Real database:** a Grid column over weeks 1-8 equals the ratio computed straight from the FTN CSV joined to play-by-play, for a named QB and receiver (skipped without the download cache, like `NgsRealDatabaseTest`).
- **CI:** parity job passes with FTN facts; accuracy gate unchanged.

## Docs

`HANDOFF.md`, `docs/ARCHITECTURE.md` (source list), `CLAUDE.md` (Known Gaps line, attribution), `README.md` (attribution).

## Open judgments

- Drop rate is per target, not per catchable target (FTN's drop flag isn't a subset of its catchable flag).
- A blitz is `n_blitzers > 0`; FTN's count is not used beyond that.
- Neutral metrics get percentile shading like the rest, as with NGS.
