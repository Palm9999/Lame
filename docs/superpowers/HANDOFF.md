# Session Handoff

**How the user wants to work:**
- One fresh session per gap, with `/clear` after each. Keep replies short; ask only when blocked, one line at a time.
- Every session starts by reading this file, runs the next task, updates this file in the same PR, and stops.
- Branch, PR and cost rules: "Working rules" in `CLAUDE.md`.

**History:** executed specs and plans, older handoff logs and per-feature write-ups are in git: `git log --diff-filter=D -- docs/superpowers` finds deleted ones, and the full notes before the 2026-10-05 cleanup are `git show 18eb398:docs/superpowers/HANDOFF.md`. Feature descriptions: `docs/ARCHITECTURE.md` (Features).

## Where things stand

Everything planned is built and pushed: the projection engine (K and D/ST included) with the ESPN blend, props, the Questionable discount and returning players; the accuracy page; NGS and FTN; the Grid, presets and rollups; the Player page; ESPN leagues with My lineup, Trade, Playoff odds, Review, Planner, Value, Start/sit and Draft; Opportunities; Rising roles; Scores; injury, lineup and news alerts; the home-screen widget; the Player page's injury return outlook; My lineup's game day; a bottom bar; shared UI pieces and small charts (`Components.kt`). `INGEST_VERSION` 10, schema 12, `FORECAST_VERSION` 20, prefs `formatVersion` 5, 169 metrics.

**Facts to keep:**
- **Accuracy method:** test on a build with a prior season (`tune.db`, 2021-2025) against a frozen sample of the current model's counted player-weeks (PPR, paired, 2 SE), never the CI gate's 2024 numbers (a 2024-2025 build makes 2024 a cold start). Count missed games as zero when judging availability changes. Harness: "Container notes".
- **The skew trap:** fantasy points are skewed and projected means are calibrated, so shrinking projections (0.9x, half a boost) lowers MAE without being better. Never ship it. The old RB/TE "-1 point" bias was mostly this skew.
- **ESPN blend:** `kona_player_info` on `leaguedefaults/3` (PPR) with an `X-Fantasy-Filter` header for the 700 most-owned QBs, RBs, WRs and TEs, mapped through nflverse's `espn_id` (`EspnProjections.kt`; attempts = ESPN code 0 + sacks code 64). Newest season downloaded every build, finished ones copied. Long TDs keep their share; a stat ESPN leaves out is zero; no row or an all-zero row leaves the model alone. On 2022-2025 it cut PPR MAE at every position in every season (pooled RB -0.168, WR -0.119, QB -0.084, TE -0.068). ESPN's archived projections look pre-game. ESPN's QB passing yards are scaled 0.95 first (`ESPN_QB_PASS_YARDS_SCALE`; actual over ESPN's 0.940-0.954 in every season 2021-2025).
- **ESPN K and D/ST stat codes** (verified against nflverse 2025, unused): K 74/77/80 made 50+/40-49/0-39, 85 missed, 86/88 XP made/missed; D/ST 99 sacks, 95 INT, 96 fumble recoveries, 98 safeties, 93+94+101+102 TDs, 120 points allowed, 127 yards allowed.
- **ESPN-driven rules:** a player ESPN projects for 3+ points plays about 87% of the time (returning players, stashes via `addStash`); ESPN's QB pick was the real starter 97% of the time against 89% for the last listed one. A Tuesday refresh may still miss a returnee or a QB change until ESPN updates near game day: trust a Saturday or Sunday refresh.
- **Questionable discount:** Questionable QB/RB/WR/TE projected 3+ played 70% of the time (healthy 85%); applied to the final projection after ESPN and props; unknown practice counts as limited.
- **Fitted layers, re-checked on the blend:** `SEASON_FORM_WEIGHT` 0.15, `QB_SPREAD` 0.65, `SNAP_SHARE_WEIGHT` 0.25.
- **Ranges:** `RANGE_WIDENING` QB 1.27, RB 1.46, WR 1.38, TE 1.24, D/ST 1.22, K none (PPR, 2022-2025 pooled, smallest factor holding 80%; computed on the phone, so no forecast rebuild). They still hold 79.6-80.2% after the Questionable discount.
- **Odds calibrated on 2022-2025 at 1.0x:** Start/sit (`chanceToLead`), win chance (`winChance`, random lineups; same-team stacks untested), anytime TD. `WEEKLY_CV`: QB 0.47, RB 0.63, WR 0.70, TE 0.77 measured; K 0.52, D/ST 0.85 from the forecast. No schedule rank: future weeks vary only 2-5% by opponent.
- **Injury return outlook** (`InjuryReturns`): Kaplan-Meier over past absences (an Out or Doubtful listing after a game played; QB, RB, WR, TE, K; played = metric `g`). Leaving one season out on 2021-2025 (846 absences), the body part's own curve beat the status-only curve by Brier -0.0061 ±0.0053 for next game and -0.0104 ±0.0080 within two, -0.0031 ±0.0070 within three; trained on only the two seasons before (as a phone with 2-3 seasons is), all within noise but the same sign. So a body part is used only with 30+ cases. Out players: 24% back the next game, 44% within two, 68% within four; Doubtful 32%, 58%, 79%.
- **Not backtested:** live win chance (not checked against a live game), playoff odds, Start/sit's independence, the draft board (FFC fills it in August; 246 of 249 matched in August 2025).
- **ESPN league shapes:** verified against live `leaguedefaults/3?view=mSettings`: `settings.rosterSettings.lineupSlotCounts` (string slot ids, 20 bench, 21 IR), `scheduleSettings` playoff weeks, `acquisitionSettings`. From memory and unverified: teams, rosters, `mMatchup`, `winner`, `playoffTeamCount`, `transactionCounter.acquisitionBudgetSpent`, `mTransactions2` trade proposals, `defaultPositionId` codes (1 QB, 2 RB, 3 WR, 4 TE, 5 K, 16 D/ST).
- **Rollups:** never store what `UNWINDOWED_METRICS` lists (one list in `Schema.kt`, one in `schema.py`; `RealDatabaseContractTest` checks every stored column reads the same from the season window). The user chose this over a per-profile cache. A 2024-2026 `stats.db` is about 102 MB.
- **FTN:** one uncompressed csv per season from 2022; no player ids, so flags are credited through play-by-play (`game_id` + `play_id`); drop rate is per target; a blitz is `n_blitzers > 0`; under 90% of the season's pass attempts warns (normal mid-season); CC BY-SA 4.0, credit "FTN Data via nflverse".
- **NGS:** numbers the Super Bowl one week after play-by-play; publishes only weeks with 15+ attempts, 10+ carries or 5+ targets; the rushing file has no QB rows, the receiving file is WR/TE only.


**Reference, read only when the task needs it:** `docs/superpowers/BACKLOG.md` (tried-and-rejected accuracy ideas: check before any accuracy round; per-round build notes; deferred minors: check before touching a feature; open phone checks).

**Next:** the 2026-10-08 sixth round (2-10 picked; 1, the phone checks, waits on the user) is built or tested (`BACKLOG.md`, Build notes). Nothing is pending: offer the next round as a short pick-list.

## Rulings that still bind

- **Weather is out of scope** (the user, 2026-09-28). Don't propose modeling it.
- **No phone speed problem** (the user, 2026-10-04): don't ask for timings or work on speed unless the user reports something slow.
- **How rounds run** (2026-10-04): the user picks features and accuracy ideas from a short list; accuracy ideas ship only when they beat noise (2 SE) on the 2022-2025 backtest, and every result, shipped or not, is logged in `BACKLOG.md` (Tried and rejected).
- **Points-allowed and yards-allowed tiers** are each profile's own and editable, ESPN's by default; stored as numbers, scored in expectation per game on the phone. Saved profiles were migrated once (prefs `formatVersion` 2 and 3), with no fallback. ESPN's yards table is from memory, unverified.
- **The accuracy gate covers QB, RB, WR, TE, K and D/ST.** If a position loses to the season-to-date average, tune its constants in `ForecastConstants.kt`.
- **The Grid's K and D/ST chips** bring their own packs (Kicking, Defense); every other chip leaves them out.
- **Judgments, not fits:** `MARKET_VARIANCE_RATIO` (0.5), `ONE_SIDED_OVERROUND` (1.08), `DST_YA_K`, `DST_YA_SCRIPT_ELASTICITY`, `DST_YA_CV` (0.25), the kicking scoring defaults (3/4/5, −1, 1, −1). Props and yards can't be backtested.
- **Every team's D/ST is always projected** (the min-points gates skip only kickers).
- **Places read best first** ("1st of 62", never "percentile" wording; 2026-10-03). **My players** are always listed, unranked below the bar (2026-10-03). **Several leagues:** one active league, one shared login, every league's team a roster (2026-10-03). **Matchups:** ESPN's numbers lead, the app's beside them (2026-10-01).

## Container notes

- A fresh container has no Android SDK, `stats.db`, pytest or `local.properties`. Install cmdline-tools from `https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip`, then `sdkmanager --sdk_root=/opt/android-sdk "platforms;android-37.0" "build-tools;36.0.0"`; write `sdk.dir=/opt/android-sdk` to `local.properties` (git-ignored), export `ANDROID_HOME`, `pip install pytest numpy`.
- Build `stats.db` with the Kotlin command (about 5 minutes for 2024-2026): the Python `build` lacks the `game` and projection tables `:core:data` tests need. `etl/build/{stats,accuracy,tune}.db` and `parity/` survive only as long as the container.
- Maven Central 429s for minutes at a time (repo1 too): add a `~/.gradle/init.d` script swapping `repo.maven.apache.org` for `https://maven-central.storage-download.googleapis.com/maven2/`, or loop `./gradlew ... --max-workers=2 -q` with a 40 s pause until the log has no "429" or "Could not resolve". Robolectric fetches its own artifact and can hit the same 429. The `init.d` mirror script can be refused by the session's auto mode; the retry loop always works (8 tries).
- Foreground `sleep` is blocked: run `./gradlew` in a background script and wait with an `until grep -q EXIT log` loop.
- `RealDatabaseContractTest > scoring a full season for every player is fast` can fail on timing here; CI passes it.
- Against a 2024-2025 `accuracy.db`, two `OpportunitiesRepositoryTest` cases and `ProjectionsContractTest > every team's projected week adds up to one game` fail on missing 2026 data: use a 2024-2026 `stats.db`.
- Gradle treats a test as up to date when only an environment variable changed: add `--rerun`. Probe and reforecast runs need `--no-configuration-cache` (otherwise the test JVM keeps the first run's env and every probe writes to the first CSV path).
- **Accuracy harness** (scratch tests, never committed): `tune.db` is a 2021-2025 build with ESPN (about 214 MB, about 10 minutes). `ReforecastScratchTest` in `core/forecast` tests copies `REFORECAST_IN` to `REFORECAST_OUT`, clears the projection tables, creates `player_ros_week` if missing and runs `Forecast.run`, with constants made env-overridable in the working copy only. `BiasProbeTest` in `core/data` tests (env-gated) dumps each counted player-week as CSV; compare pairs in Python. About 2 minutes a configuration. `RangeDumpTest`/`RangeProbeTest` add the phone's floor and ceiling (`projectPoints(..., widening = emptyMap())`, 2000 draws) for calibration checks and range refits (Python bisection on `calibratedRange`'s rule, then check against `AccuracyGateTest` per season).
- Bash sometimes fails with a transient "classifier gave no verdict": retry once, or use Read, Grep and Glob.
- Run `./gradlew test` with `--max-workers=2`: 3 workers got the container killed for memory.
- If a dozen unrelated `:core:data` and forecast contract tests fail at once, check `SELECT count(*) FROM game` in `stats.db`: an empty schedule (a failed download; the build log says "no schedule") breaks them all.
- Keep the accuracy harness's scratch tests out of the tree between runs (the stop hook flags untracked files).
- PRs from this branch go to `claude/dreamy-euler-phbdq1` (every PR so far used that base).
- The user also pushes to the branch: `git pull --no-rebase` before pushing.
