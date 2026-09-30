# Session Handoff

**How the user wants to work:**
- One fresh session per gap, with `/clear` after each. Keep replies short; ask only when blocked, one line at a time.
- Every session starts by reading this file, runs the next task, updates this file in the same PR, and stops.
- Cost and context stay low: see "Working rules" in `CLAUDE.md` (short plans, no docs-only PRs, specs and plans deleted once executed).

**Branch:** `claude/dreamy-euler-phbdq1`, based on `claude/relaxed-hypatia-73hhub` (the repo's main branch). One session at a time on it. After a PR merges, reset it: `git fetch origin claude/relaxed-hypatia-73hhub && git checkout -B claude/dreamy-euler-phbdq1 origin/claude/relaxed-hypatia-73hhub`.

**History:** executed specs and plans, and older handoff logs, are in git: `git log --diff-filter=D -- docs/superpowers` finds them, `git show <sha>^:<path>` reads one. Shipped work is in the merged PRs (#4–#18).

## Where things stand

Built and merged: the projection engine (K and D/ST included), accuracy page, props blend, live data refresh, Player page season stats, D/ST yards-allowed tiers (PR #14), injured players' share to teammates (PR #17) and Next Gen Stats (PR #20) and FTN charting (PR #21). `INGEST_VERSION` 7, `FORECAST_VERSION` 6, prefs `formatVersion` 3.

**Just built: saved Grid presets** (PR for this branch). A Presets chip on the Grid opens a sheet: tap applies, "Save current view…" names one, long-press renames or deletes (undo stays in the sheet until it closes). A preset stores pack, sort, direction, position chip, per-game, teams, snap floor, advanced filters and a weeks rule (whole season or last N weeks), never the season, scoring profile, roster or name search. Stored in the prefs JSON (`gridPresets`, `formatVersion` still 3), at most 30, names unique ignoring case; an entry whose pack or column is gone shows greyed and can only be renamed or deleted. Rulings: the forms live inside the sheet (no dialogs, one window), undo is inline (a snackbar would sit under the sheet's scrim).

**FTN charting** (PR #21; user chose the receiver and QB headline set of ten). Grid packs FTN Passing and FTN Receiving; ingest v7 stores each FTN count beside FTN's own denominator (the plays FTN charted) plus the weekly rate under the metric's own id, none sparse. Grid-only: Compare and the Player page don't use it. Facts to keep: FTN publishes one uncompressed csv per season from 2022 (no `.csv.gz`; earlier seasons 404, so the build never asks); it has no player ids, so a flag is credited through play-by-play (`game_id` + `play_id`) to the target's receiver or the passer; drop rate is per target (FTN's drop flag isn't a subset of its catchable flag); a blitz is `n_blitzers > 0`; interception-worthy is per attempt; a file with under 90% of the season's pass attempts warns, and FTN lags play-by-play mid-season, so that warning is normal then; the license is CC BY-SA 4.0, credit "FTN Data via nflverse", and ShareAlike matters only if the app or its stats.db is ever distributed. NGS facts to keep: NGS numbers the Super Bowl one week after play-by-play and publishes only weeks with 15+ attempts, 10+ carries or 5+ targets; its rushing file has no QB rows, its receiving file is WR/TE only, and efficiency is weighted by rush yards.

**In progress: Grid layout (executing natively; Task 1 of 7 done, commit "feat: save the Grid's row density")** (this branch; user chose "optimize" with UI changes). The spec `docs/superpowers/specs/2026-09-30-grid-layout-design.md` is approved (top bar 3: two slim rows plus a "View & filters" sheet, sliding away on scroll; player cell 1: narrower, no trend line; numbers 1: units in the header, no zebra, sorted-column tint; row height saved as `gridDensity`). The mockups were `https://claude.ai/artifact/Hk8ovnEAFJX8W2YCpdkbR3`. The plan is written and committed (`docs/superpowers/plans/2026-09-30-grid-layout.md`, 7 tasks: density setting, digits-only cells, numbers and player cell, view model, View & filters sheet, two-row bar, screenshots and docs). Next: the user chooses how to run it (native is recommended: the tasks share interfaces), then `executing-plans` (native) or `subagent-driven-development`; delete the spec and plan in the final commit. Row counts in the spec are estimates: measure on the phone.

**After that: season rollups.** Start with brainstorming.

**Deferred minors (presets):** a double-tap on Save can ask to replace the preset just saved; Undo does nothing when its name or last free slot was taken meanwhile, and says nothing; a "last N weeks" view applied in week 8 doesn't move forward when a refresh brings week 9 (until applied again); at 30 presets Save is disabled, so a preset can't be replaced by saving under its name; the `two saves at 29 end at 30` test runs the saves in turn, not at once.

**Deferred minors (FTN):** Kotlin asks for `injuries_2022.csv.gz`, which 404s (nflverse publishes 2022 as `.csv`), so a 2022 build has no injury report and parity differs there (the app builds 2024 on); the Grid's CSV export header credits nflverse only; `FtnRealDatabaseTest` needs the FTN and play-by-play files in the Python download cache, so CI's Kotlin-only job skips it; the FTN screen, RPO, motion, no-huddle and box-count flags aren't stored; the pipeline's coverage warning names no weeks; a brief 404 of a known season's FTN file rebuilds it without FTN and says "no FTN charting yet" (as snaps and injuries do); Kotlin reads the flags case-sensitively ("TRUE") where Python upper-cases, and Python casts play id and `n_blitzers` text straight to an integer where Kotlin goes through a double; per-game FTN totals (DRP, CRT) divide by play-by-play games, so an uncharted week understates them; the FTN index is held through the rest of `crunch`; a renamed-column FTN warning shows once (the version is still recorded).

**Deferred minors (NGS):** an average missing or dropped still leaves its weight in the denominator (2 rows in 2025); "no NGS rows published yet" also fires for 2012-2015; `loadNgs` drops a group silently on a 404; NGS facts carry NGS team codes ("LAR", not "LA"); all seasons' NGS rows stay in memory for a build and the three files are re-downloaded when any season is rebuilt and NGS is unchanged; the Python twin drops all NGS if one file fails; `NgsRealDatabaseTest` needs the Python download cache, so CI's Kotlin-only job skips it; no `predicts` text on the ten metrics.

## Rulings that still bind

- **Weather is out of scope** (the user, 2026-09-28). Don't propose modeling it.
- **Points-allowed and yards-allowed tiers** are each profile's own and editable, ESPN's by default; stored as numbers, scored in expectation per game on the phone. Saved profiles were migrated once (prefs `formatVersion` 2 and 3), with no fallback. ESPN's yards table is from memory, unverified.
- **The accuracy gate covers QB, RB, WR, TE, K and D/ST.** If a position loses to the season-to-date average, tune its constants in `ForecastConstants.kt`.
- **The Grid's K and D/ST chips** bring their own packs (Kicking, Defense); every other chip leaves them out.
- **Judgments, not fits:** `MARKET_VARIANCE_RATIO` (0.5), `ONE_SIDED_OVERROUND` (1.08), `DST_YA_K`, `DST_YA_SCRIPT_ELASTICITY`, `DST_YA_CV` (0.25), the kicking scoring defaults (3/4/5, −1, 1, −1). Props and yards can't be backtested.
- **Every team's D/ST is always projected** (the min-points gates skip only kickers).
- **Deferred minors (yards work):** the 0–800 `yards_allowed` range check hard-fails a build on one out-of-range game (observed 75–647); no dedicated `BacktestTest` case for yards; no refit note beside `RANGE_WIDENING`; the matchup note reads "scores 24.1 pts, 331 yards".

## Open checks on the phone (non-blocking)

- **Presets:** on the Grid, set a position, sort, a filter and a week range, tap Presets → Save current view…, name it and save. Restart the app, open Presets and tap it: the view should return, on whichever season is open. Report anything that reads oddly.
- **FTN:** after the first refresh (it rebuilds every season: ingest v7, and downloads about 8 MB per season more), open the Grid, pick FTN Passing (QB chip) and FTN Receiving (WR or TE chip) and confirm columns fill; percentages should read like PA% about 20-30, CATCH% about 70-80, DRP% under 8. Report the refresh time.
- **NGS:** after the first refresh (it rebuilds every season: ingest v6), open the Grid, pick the NGS Passing, NGS Rushing and NGS Receiving packs and confirm columns fill (QB chip for passing, RB for rushing, WR or TE for receiving). Report the refresh time.
- **Odds API shape:** hand-built fixtures, no key in the container. On the first refresh with the user's key (☰ → Settings → Betting props, then Refresh stats), the toast should say "Props moved N projections".
- **Season stats timing:** open a player with a full season; the section runs up to 20 small queries. Report how long it takes to appear.
- **Yards allowed:** ☰ → Settings → a profile: the "Yards allowed" tier section edits and saves, and a D/ST's Grid points and Player page log include yards after a refresh (the first refresh rebuilds).
- **Accuracy page:** ☰ → Projection accuracy, season 2025. Report how long "Scoring every projected week…" shows; the "held" figures should read about 78–83%.

## Container notes

`etl/build/stats.db` (2024–2026) and `etl/build/accuracy.db` (2024–2025) exist here; rebuild them if the container is fresh (commands in `CLAUDE.md`). `RealDatabaseContractTest > scoring a full season for every player is fast` fails here on timing with or without any change; CI passes it. Bash sometimes fails with a transient "classifier gave no verdict" error: retry once, or use Read, Grep and Glob meanwhile.
