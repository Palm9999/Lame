# Session Handoff

**How the user wants to work:**
- One fresh session per gap, with `/clear` after each. Keep replies short; ask only when blocked, one line at a time.
- Every session starts by reading this file, runs the next task, updates this file in the same PR, and stops.
- Branch, PR and cost rules: "Working rules" in `CLAUDE.md`.

**History:** executed specs and plans, older handoff logs and per-feature write-ups are in git: `git log --diff-filter=D -- docs/superpowers` finds deleted ones, and the full notes before the 2026-10-05 cleanup are `git show 18eb398:docs/superpowers/HANDOFF.md`. Feature descriptions: `docs/ARCHITECTURE.md` (Features).

## Where things stand

Everything planned is built and pushed: the projection engine (K and D/ST included) with the ESPN blend, props, the Questionable discount and returning players; the accuracy page; NGS and FTN; the Grid, presets and rollups; the Player page; ESPN leagues with My lineup, Trade, Playoff odds, Review, Planner, Value, Start/sit and Draft; Opportunities; Rising roles; Scores; injury, lineup and news alerts; the home-screen widget; the Player page's injury return outlook; My lineup's game day; a bottom bar; shared UI pieces and small charts (`Components.kt`). `INGEST_VERSION` 8, schema 12, `FORECAST_VERSION` 18, prefs `formatVersion` 4.

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

**Tried and rejected** (PPR MAE change on the frozen sample, ± 2 SE; don't retry without a new angle):
- ESPN's QB rushing TDs scaled 0.9 or 1.1 (2026-10-07): QB +0.001 ±0.004 and −0.000 ±0.004
- TE handcuff: a listed-out TE's target share given to his team's other TEs at 0.5 or 1.0 before normalizing (2026-10-07): TE −0.008 ±0.011 and −0.003 ±0.018
- Game script at half strength, weaker matchup ratings, stronger pass-TD and receiving-yards shrinkage, extra early-season form weight, a usage-trend share term (2026-10-03): no gain
- Re-tuning `QB_SPREAD` 0.5-1.0, `SEASON_FORM_WEIGHT` 0-0.25, `SNAP_SHARE_WEIGHT` 0-0.35 on the blend: within noise (snap share 0 costs TE +0.018 ±0.013, so it stays)
- `ESPN_WEIGHT` offsets early or late season (±0.1-0.3): within noise; per-stat ESPN weights or scales: pooled -0.016 to +0.076, no better than one weight per position
- ESPN's K and D/ST projections blended at 0.3: K +0.000, D/ST -0.016 ±0.017, not worth the ingest
- `SHARE_K_GAMES` 2-25 (7-15 about -0.003, 2024 the other way), `SHARE_HALF_LIFE` 3 or 7: within noise
- ESPN QB passing TDs scaled 0.95: -0.007 ±0.007 (1.05: +0.010); QB interceptions 0.93: +0.003 ±0.003
- Treating a player ESPN projected earlier but not this week as out: +0.019 ±0.006 (only 17% of them play)
- Route participation (`pbp_participation`, about 49 MB a season): residual correlation -0.04 to 0.07; nudging targets toward it raised WR and TE target MAE
- TE receiving scale 1.15: +0.041 ±0.027 (1.3: +0.125 ±0.053); red-zone role for TDs: within ±0.005; first-game-back trim 0.8-0.95: best -0.077 ±0.167; rookie draft capital: +0.089 ±0.046
- A Questionable player's teammates taking his share: affected +0.013 ±0.007, pooled +0.004 ±0.002; they don't beat their projections anyway (RB -0.06 ±0.78, pass catchers +0.13 ±0.33)
- Backup-QB edges: D/STs +0.94 ±0.88 against +0.40 versus starters, pass catchers within noise; the lines and the starter already price it
- TE handcuffs (team-wide rule): backup TE under-projected by +1.90 ±1.59 (43 played cases)
- Start/sit spread 0.8-1.6x and anytime TD 0.9x or 1.1x: no better calibrated
- Win-max lineup (start riskier players as the underdog, safer as the favorite, on each player's range): in simulated 12-team leagues from 2022-2025's counted player-weeks it changed the lineup in 6 of 1,764 matchups (0.3%), for +0.02 points of win chance; not built
- Props weight: kept at 0.5 (`MARKET_VARIANCE_RATIO`); the blend cut the model's prop-stat error 1.5-12%, implying 0.51-0.57, too small to move a judgment

**Next:** the 2026-10-07 second round: the user asked for the pick-list (1 Ask Gridiron with the user's own Anthropic key, 2 game-day refresh, 3 TD regression board, 4 player comps, 5 mock draft simulator, 6 usage trend charts, 7 matchup preview) and said start where I want; (2) game-day refresh is built (below). (1) Ask Gridiron was written (Claude Opus 5.5 through the Java SDK `com.anthropic:anthropic-java`, a `run_sql` tool over the read-only `stats.db`, the key entered on its own screen) but never compiled: Maven Central answered Gradle's SDK downloads with 429 for over an hour in the container (curl got 200), and the user chose to leave it out of the build (2026-10-07); the code is not in the repo. (3) TD regression board, (4) player comps (Similar seasons), (5) mock draft and (6) usage trend charts are built (below). (7) matchup preview already existed (My lineup's "Matchup preview", commit 345fd67), so nothing was built for it; the user then asked for (8) owner tags and the Free agents chip on TD regression (Where we differ already had them), built. Nothing is pending: offer the next round as a short pick-list.

**Just built: Usage by week** (Player page, under Points by week): a column chart per share, 0-100% scale, from the game log's weekly query (`PlayerStatSets.usageColumns`: QB snap share; RB snap, carry, target share; WR/TE snap, target, air yards share; none for K or D/ST).

**Just built: Mock draft** (☰ → Draft → Mock): a snake draft against bots on the same FFC board, the user's slot chosen first; each bot takes one of `DraftAdvice`'s top three for its own roster, weighted 0.6/0.25/0.15 (`MockDraft.BOT_WEIGHTS`, judgment). Nothing saved; the real draft's picks are untouched.

**Just built: Similar seasons** (Player page): the five nearest player-seasons by other players at his position, per-game counting stats standardized within the position, every season in `stats.db`.

**Just built: TD regression** (More → Players, or ☰): touchdowns against ffopportunity's expected touchdowns through its last processed week, Running hot and Running cold, 25 each, by position.

**Just built: Game-day refresh** (Settings → Alerts, on by default, prefs `gameDayRefresh`): `GameDayRefreshWorker` (WorkManager, needs a network) runs `RefreshCoordinator.refreshAndWait` Saturday 10 pm and Sunday 9 am local, then appends the next run; a run Android kills is retried by WorkManager. Pure `nextGameDayRefresh` is tested.

**Just built: Rest-of-season injury discount** (`FORECAST_VERSION` 18): a player nflverse lists Out or Doubtful this week keeps rest of season from the healthy roster, each coming game times the chance such a player has played by then (`returnCurve` in `:core:forecast`, every past listing in the database, 8 games deep, pooled statuses and positions). Proxy backtest on 2022-2025 (no past rest-of-season rows are stored): his next four games' PPR points, missed ones zero, against season average × games: MAE 23.8 → 11.0 (−12.8 ±1.5, 553 listings, leave-one-season-out curve), bias +22.3 → +0.8. Checked on the real 2026 build with a simulated Out listing: Puka Nacua's weekly receiving yards 95 → 27, 49, 67, 70, then about 80. A Tuesday build has no week's listings yet, so it changes nothing until Wednesday-Friday.

**Fixed: schedule download** (2026-10-07): nflverse dropped `schedules/games.csv` (404) and serves only `games.csv.gz`; every refresh had ended "no schedule" with no projections. `Sources` and the kept copy now use the gzip name (the Python ETL never reads the schedule).

**Just built: Quiet hours** (Settings → Alerts, off by default, prefs `quietAlerts`): 10 pm–8 am local the injury and news checks wait, so the first run after 8 sends what changed overnight; lineup checks and the summary still come.

**Just built: Daily waiver snapshot**: the two-hourly alert job calls `WaiverTrendsRepository.snapshotDaily` (one fetch a UTC day, skipped once today has rows), so Waiver trends' weekly change no longer needs the screen opened daily.

**Just built: Return outlook on the Injury report**: each Out, Doubtful or IR row gets the Player page's "Played again by" line, filled in after the list shows.

**Just built: Dynasty values in Trade and on the Player page**: FantasyCalc's value (league format, active profile) beside each Trade player, the trade's send/get totals under the verdict, and a "Dynasty value" section on the Player page (value, place overall and at position, redraft value). Shown in every league, not only dynasty ones.

**Just built: Grid and Compare share cards**: View & filters → Share image (top 10, first four columns) and Compare's Share (first 12 stats, a column per player), both through `StatTableCard`; `SharePreview`, `ImageShare` and `ShareCardFrame` moved to `core/ui/.../ShareImage.kt`.

**Just built: Claim plan** (My lineup, bottom): up to five FAAB claims in priority order with bids that never overspend what could all win, shared drops marked conditional, and where your FAAB ranks in the league.

**Just built: Alert switches** (Settings → Alerts): injury changes, roster news, lineup checks and the Tuesday summary each switch on their own; the two-hourly worker runs while any is on; prefs `alerts` (`AlertSwitches`), an older file's single `injuryAlerts` value sets all four.

**Just built: Tuesday summary** (rides the injury-alert switch): one "Your week" notification on Tuesdays from 9 local.

**Just built: Where we differ** (More → Players, or ☰): the app's final projection against ESPN's stored weekly projection, both under the active profile, top 15 gaps each way.

**Just built: Trade partners** (Projections → Trade, bottom: Best partners). Fit is the plain gain of each position's best bench player over the weakest starter he'd replace, both ways; the design's "need against the league's typical starter" was dropped as it changed nothing on the cases tried. Placed after Suggested trades so the existing Trade layout doesn't shift.

**Just built: League activity** (More → League, or ☰): every executed add, drop and trade this season from `mTransactions2` (shape unverified), a trade graded by each side's rest-of-season points in less out.

**Just built: Shareable cards**: Share on Review's recap and report card, the Player page's This week and a graded trade; a preview, then a PNG to the share sheet (no upload).

**Just built: League history** (More → League, or ☰): Seasons, All-time, Head-to-head, Records. Every ESPN shape it reads is from memory and unverified (`status.previousSeasons`, `primaryOwner`, `rankCalculatedFinal`, `playoffSeed`, `schedule[].winner`/`playoffTierType`, the pre-2018 `leagueHistory` array); a wrong guess shows as missing seasons or no champions. Finished seasons are cached once as files; delete them (clear app storage) to refetch after a fix.

**Just built: Report card** (Projections → Review, under the recap; the user approved the five categories with Luck counted in the overall). Places read "3rd of 12" (the places ruling); a tie shares the better place; overall is the average place, ties by all-play. It adds one `mDraftDetail` read per Review open; without a draft, Draft and Moves are left out and the section says why.

**Just built: Dynasty & keepers** (More → League, or ☰). The user chose market values (FantasyCalc) over an app age-curve model, and keepers priced by the round drafted. FantasyCalc: about 420 players plus 24 rookie picks (dropped), 27 players without an `espnId` (shown, not linked), `overallRank` counts the picks (so ranks are recounted). A rostered player's cost is his own pick whoever made it (a traded player keeps his round). ESPN's `mDraftDetail` shape (`draftDetail.picks[]`: `playerId`, `roundId`, `teamId`, `keeper`) is from memory: unverified until the user's keeper league is synced.

**Just built: Waiver trends** (More → League, or ☰). ESPN's `kona_player_info` on `leaguedefaults/3` with a filter for the 1,000 most rostered and one stat line each (without it the list is about 20 MB; with it about 600 KB compressed; `view=players_wl` is 35 KB but has no `percentChange`); the 1,000th player is at 0.0%, so the list covers everyone rostered. Each fetch saves the day's roster % in `live.db` (`roster_pct`); a 7-10-day-old snapshot turns on the weekly change (the user chose snapshots over ESPN's figure alone). ESPN's `percentChange` window is unknown: on a Monday its biggest riser was +1.7, so it looks daily, not weekly. A player with no NFL team has `proTeamId` 0 (no team shown).

## Rulings that still bind

- **Weather is out of scope** (the user, 2026-09-28). Don't propose modeling it.
- **No phone speed problem** (the user, 2026-10-04): don't ask for timings or work on speed unless the user reports something slow.
- **How rounds run** (2026-10-04): the user picks features and accuracy ideas from a short list; accuracy ideas ship only when they beat noise (2 SE) on the 2022-2025 backtest, and every result, shipped or not, is logged here.
- **Points-allowed and yards-allowed tiers** are each profile's own and editable, ESPN's by default; stored as numbers, scored in expectation per game on the phone. Saved profiles were migrated once (prefs `formatVersion` 2 and 3), with no fallback. ESPN's yards table is from memory, unverified.
- **The accuracy gate covers QB, RB, WR, TE, K and D/ST.** If a position loses to the season-to-date average, tune its constants in `ForecastConstants.kt`.
- **The Grid's K and D/ST chips** bring their own packs (Kicking, Defense); every other chip leaves them out.
- **Judgments, not fits:** `MARKET_VARIANCE_RATIO` (0.5), `ONE_SIDED_OVERROUND` (1.08), `DST_YA_K`, `DST_YA_SCRIPT_ELASTICITY`, `DST_YA_CV` (0.25), the kicking scoring defaults (3/4/5, −1, 1, −1). Props and yards can't be backtested.
- **Every team's D/ST is always projected** (the min-points gates skip only kickers).
- **Places read best first** ("1st of 62", never "percentile" wording; 2026-10-03). **My players** are always listed, unranked below the bar (2026-10-03). **Several leagues:** one active league, one shared login, every league's team a roster (2026-10-03). **Matchups:** ESPN's numbers lead, the app's beside them (2026-10-01).

## Deferred minors

**Deferred minors (similar seasons):** per-game stats only, no age, team context, efficiency or shares (rates aren't averaged); the pool is whatever seasons the phone built (2-3), so comps rarely reach far back; metrics weigh equally; the same player's own other seasons are left out; K and D/ST get none; the pool is re-read on every page open.

**Deferred minors (TD regression):** the 2-touchdown floor is a judgment; it isn't wired to the Player page or Trade; passing touchdowns count with rushing and receiving for a QB, so a QB's gap is mostly passing.

**Deferred minors (game-day refresh):** fixed times (no picker), every week of the year (offseason runs too); no notification when it finishes (the Finished toast shows only if the app is open); a Thursday or Monday game gets no refresh of its own; it lives under Alerts though it isn't one.

**Deferred minors (claim plan):** the adds are each valued alone (two adds' combined value isn't checked); bids use the same judgment curve as before; a drop needed for roster room on an open spot counts as distinct; the section sits at the very bottom of My lineup.

**Deferred minors (Tuesday summary):** it shares the injury alerts' switch (no own toggle); the report card place reads every finished week from ESPN (one request a week, plus the draft) once a Tuesday; win chance uses each roster's best lineup, not the lineup set in ESPN; a phone off all Tuesday skips the week.

**Deferred minors (where we differ):** K and D/ST aren't compared (ESPN's aren't stored); the gap is against the blended number, not the model alone (the pre-blend projection isn't stored); a Questionable discount sits in the app's number, not ESPN's.

**Deferred minors (trade partners):** season totals, not weekly (byes ignored); K and D/ST left out; a one-way fit (you sell only) ranks with two-way ones; the section sits at the bottom of a long list.

**Deferred minors (league activity):** a trade is graded with today's projections, not the rosters or projections at the time; every week is read on each open past the 15-minute hold (one request a week); lineup moves and failed claims are hidden; a player `player_xref` can't match shows as "a player".

**Deferred minors (shareable cards):** cards use the phone's theme (a dark phone shares a dark card); the image is the preview's size on screen, not a fixed pixel size; Robolectric doesn't test the capture itself (the PNG writer and each card's text are tested).

**Deferred minors (league history):** the screen doesn't reload on a league switch until reopened; seasons are read one after another on first open (one request each); a cached season with a wrong guess stays cached until the files are deleted; co-owners count under the primary owner only; consolation games are dropped entirely.

**Deferred minors (report card):** points count only in starting lineups and by ESPN's scores; a traded player's points before the trade count as the other team's move; Draft credits the drafting team even for weeks another team started him (by design); the categories are equally weighted; Luck is in the overall although it isn't skill (the user approved).

**Deferred minors (dynasty & keepers):** the draft is read every time the screen opens (no cache); keeper costs ignore how many years a player was kept; a negative-surplus player is still marked Keep when he is in the top N; the 6-hour value cache is a judgment; K and D/ST have no worth; a pick ESPN makes for a player later released and re-added still prices at that pick; the Grid doesn't use dynasty values; Trade and the Player page show them in every league (no dynasty-league check) and Trade's totals add values without FantasyCalc's package adjustment.

**Deferred minors (waiver trends):** the background snapshot runs only while an alert is on (it rides the alert job); days are UTC; the 25-row cut and the 15-minute memory cache are judgments; a player `player_xref` can't match isn't tappable and has no points; the snapshot is ESPN's public leagues, not the user's league; ESPN's `percentChange` is shown as is.

**Deferred minors (lineup alerts, Questionable):** before inactives post, a Questionable starter still projects at his discount (ESPN's badge can't tell an active Q from an undecided one), so an early-Sunday My lineup may suggest sitting him; the pre-game lineup alert itself doesn't lift the discount; Projections reads kickoffs when it loads and when My lineup opens, so a week list or Start/sit left open across the cutoff lifts only after reopening; the Player page card reads them each time the page loads; the waterfall and the Grid's projection columns read the stored final, so they keep the discount (a confirmed-active player's card can read above the waterfall it opens). Review's last week is the later of the stats build's and ESPN's.

**Deferred minors (rest-of-season discount):** the forecast's curve pools Out and Doubtful and every position, and ignores the body part and IR (the phone's `InjuryReturns` uses them); an ESPN-only Out with no nflverse listing isn't discounted; a returning player's teammates' rest of season doesn't shrink to make room; Trade, Planner and the Player page show the discounted totals with no note saying so.

**Deferred minors (injury returns):** the body part is nflverse's latest named one this season, so an ESPN-only Out with no nflverse listing reads every injury; a suspension isn't covered; positions are pooled; an IR player is assumed to have the 4-game minimum still to serve when he's missed fewer; a game in progress counts as not yet played; the outlook doesn't feed projections; the Injury report computes each row's outlook in turn on every open (a few queries each).

**Deferred minors (look):** the Grid itself wasn't restyled (its heat cells are the visual); the other Projections modes (Trade, Playoff odds, Review, Planner) keep their old rows apart from section headers; charts have direct labels but no tap-for-detail; the Questionable badge uses Material's default tertiary (pink), the theme sets no tertiary.

**Deferred minors (bottom bar):** tab screens keep their own "← Back" (to the Grid); a tab always opens the current season, so a past season's screens are reached from the Grid's ☰; the selected tab is the screen just above the Grid, so a Player page opened from Projections still lights Projections.

**Deferred minors (game day):** a pivot must fit the Questionable starter's own slot (a FLEX reshuffle that frees a slot isn't found); a bye or a team ESPN gives no kickoff is left out; kickoffs are read when My lineup opens, so a window that starts while it is open stays until it reloads; times show in the phone's zone.

**Deferred minors (injury alerts):** ESPN's news feed is fetched with the injuries every run (one extra request); a player ESPN doesn't match to an app id is never alerted; quiet hours are fixed at 10 pm–8 am (no picker) and a status that changes twice overnight sends only the latest; Robolectric doesn't run the worker (the diff and the checker are unit-tested; the scheduling isn't).

**Deferred minors (trades):** cuts go by rest-of-season total unless the user picks one (their side only; the other team's cut and the ideas' cuts are the lowest); `BENCH_WEIGHT` and the FAAB curve are judgments; a player with no projection still counts against roster size (so is cut first); IR slots count as roster spots; two teams with the same name collide; the playoff line uses the user's league's weeks for both sides.

**Deferred minors (returning players):** rest of season starts from the healthy roster, where a usual starter nflverse lists Out or Doubtful this week stays the starter (ESPN's doubt is about this week), so the backup starting this week gets no rest-of-season row at all, this week included (as before); a player ESPN doesn't rank among its 700 most-owned can't return this way; ESPN's 3-5 point band plays only about 62-84%, so some returnees are projected for a game they miss.

**Deferred minors (ESPN blend):** a player ESPN projects nothing for (it expects him out) keeps the full model projection, which doesn't move MAE but affects My lineup; 784 frozen player-weeks fell under the 5-point counting floor with the blend; the weights were fitted on PPR totals and apply to every profile; a player with no `espn_id` in nflverse's player file is dropped; about 8 MB a season; CI's parity job downloads ESPN too (a failure there only warns).

**Deferred minors (season form, QB spread, snap share):** season form dampens an injured teammate's share by 15% and counts a traded player's old-team games; team targets and carries no longer sum exactly to team volume (engine tests pin the blended sums, team carries to 0.05); one season-form weight for RB, WR and TE; the typical QB's carry share includes backups' games; a spot-starter QB is compressed like a veteran; snap share counts offense only and skips missed games in its 3-game window.

**Deferred minors (rising roles):** the lift is partly mechanical (shared baseline); "teammates out" can lag nflverse's injury report; the Grid column shows blank for QBs; no Compare use; the table is rebuilt in full each forecast; the window includes the previous season's playoff weeks early in a season.

**Deferred minors (several leagues):** a league with no team chosen hides the My lineup chip (the league chips stay); Matchups has no league chip (switch on Projections or the ESPN leagues screen); "Save and sync" with a blank id syncs the active league; the opponent and matchups fetches use whichever league is active when they run, so a quick switch can show the old league's week until the next reload.

**Deferred minors (opportunities):** the uptick uses the forecast, which already moves a share to teammates only for players nflverse lists Out or Doubtful, so an ESPN-only status (or a newly Questionable one) can understate it; a starter who missed most of the four-game window may rank below his real role; only the top two (QB1) count, so a WR3 stepping up isn't flagged; the usage window is the last four played weeks league-wide, so a team's bye counts as a missed week; no depth chart from ESPN is used.

**Deferred minors (free agents, my lineup, opponent, pickups):** each pickup list values one horizon (this week, or rest of season), not both at once, don't know your roster size or who is droppable (a bye-week player isn't projected, so is never suggested as a drop), and each pickup is rated alone;  the opponent comparison uses the snapshot's roster, so a pickup since the last sync is missed;  free agents come from the last sync, so a pickup since then still shows (or hides) until the next one; free agents is Grid-only and not saved in presets; kickoff locks come from ESPN's scoreboard when My lineup opens (no scoreboard, no locks), and waiver gains still let your own locked players move; the auto-sync's failure is silent on My lineup (ESPN leagues shows it); a player on a bye or with no projection can't be placed (listed apart).

**Deferred minors (rollups):** early in a season every L window equals `S` and stores the same sums again; `S` uses the newest `g` row, so a season with only playoff weeks has no window; the Python and Kotlin builders each carry their own 17/18-week rule.

**Deferred minors (Grid layout):** the bar's second row scrolls sideways for pack and positions when they don't fit (More can sit half off-screen on a 412 dp phone); the search icon is an emoji; at 200% font Android's nonlinear scaling gives 54 dp rows, so the two-line player cell may be tight; the hide threshold (24 dp) and row counts are estimates; Roborazzi images are build outputs, not committed.

**Deferred minors (presets):** a double-tap on Save can ask to replace the preset just saved; Undo does nothing when its name or last free slot was taken meanwhile, and says nothing; a "last N weeks" view applied in week 8 doesn't move forward when a refresh brings week 9 (until applied again); at 30 presets Save is disabled, so a preset can't be replaced by saving under its name; the `two saves at 29 end at 30` test runs the saves in turn, not at once.

**Deferred minors (FTN):** the Grid's CSV export header credits nflverse only; `FtnRealDatabaseTest` needs the FTN and play-by-play files in the Python download cache, so CI's Kotlin-only job skips it; the FTN screen, RPO, motion, no-huddle and box-count flags aren't stored; the pipeline's coverage warning names no weeks; a brief 404 of a known season's FTN file rebuilds it without FTN and says "no FTN charting yet" (as snaps and injuries do); Kotlin reads the flags case-sensitively ("TRUE") where Python upper-cases, and Python casts play id and `n_blitzers` text straight to an integer where Kotlin goes through a double; per-game FTN totals (DRP, CRT) divide by play-by-play games, so an uncharted week understates them; the FTN index is held through the rest of `crunch`; a renamed-column FTN warning shows once (the version is still recorded).

**Deferred minors (NGS):** an average missing or dropped still leaves its weight in the denominator (2 rows in 2025); "no NGS rows published yet" also fires for 2012-2015; `loadNgs` drops a group silently on a 404; NGS facts carry NGS team codes ("LAR", not "LA"); all seasons' NGS rows stay in memory for a build and the three files are re-downloaded when any season is rebuilt and NGS is unchanged; the Python twin drops all NGS if one file fails; `NgsRealDatabaseTest` needs the Python download cache, so CI's Kotlin-only job skips it; no `predicts` text on the ten metrics.

**Deferred minors (yards work):** the 0–800 `yards_allowed` range check hard-fails a build on one out-of-range game (observed 75–647); no dedicated `BacktestTest` case for yards; the matchup note reads "scores 24.1 pts, 331 yards".

## Open checks on the phone

- **Similar seasons:** a Player page lists five similar seasons with three per-game numbers each; tapping one opens that player.
- **TD regression:** More → TD regression: Running hot lists players with more touchdowns than expected (+ gaps), Running cold the reverse; the note's week should be a week or so behind the current one.
- **Game-day refresh:** don't refresh by hand after Friday; Sunday morning, a player nflverse listed Out on Friday should have no projection on Projections' week list (the app has no build-time display, so this is the tell).
- **Quiet hours:** Settings → Alerts → Quiet hours on: nothing from injuries or news between 10 pm and 8 am, then the overnight changes arrive after 8.
- **Daily waiver snapshot:** with alerts on, don't open Waiver trends for a week; then open it: the note should read "over the last week".
- **Injury report outlook:** More → Injury report: Out, Doubtful and IR rows show "Played again by: wk N …" a moment after the list.
- **Dynasty values:** Projections → Trade: each player shows "dynasty N", and a picked trade reads "Dynasty value (FantasyCalc): send … · get …"; a Player page shows a Dynasty value section.
- **Grid and Compare cards:** Grid → View & filters → Share image, and Compare → Share: a preview of the table, then the share sheet.
- **Claim plan:** in a FAAB league, My lineup's bottom lists claims #1-#5 with bids and drops; the bids together never exceed what's left, and "You have $N, Xth most of 12" should match ESPN's FAAB standings.
- **Alert switches:** Settings → Alerts: four switches; turn one off and its notifications stop while the others continue.
- **Tuesday summary:** with alerts on and a league synced, a "Your week" notification should arrive Tuesday morning (the first two-hourly run after 9) with last week's score, your report card place and this week's win chance, once.
- **Where we differ:** More → Where we differ: Above and Below ESPN should list a week's gaps of a few points; an empty screen saying no ESPN projections means the build has none for that week.
- **Trade partners:** Projections → Trade, scroll to Best partners: the top teams should read as deep where you're thin; tap one and its roster loads.
- **League activity:** More → League activity with your league synced: this season's adds, drops and trades should list by week; if it says "no moves yet" in a league with moves, ESPN's transaction shape differs (report it).
- **Shareable cards:** tap Share on Review (recap, report card), a Player page and a graded trade; the preview should look like the card on screen, and the share sheet should offer the image to Messages and others.
- **League history:** More → League history with your league synced: past seasons should list with champions; check All-time against what you remember (titles, records) and Head-to-head against a rival. Report any season named as "Couldn't read".
- **Report card:** Projections → Review: under the recap, every manager has a place and five category places; tap one for the numbers. With a draft found, Draft and Moves show.
- **Dynasty & keepers:** More → Dynasty & keepers: the Dynasty tab fills in your league's format (Superflex if it starts an OP); on Keepers, check each player's cost round against your league's real draft (if every player reads the undrafted round, ESPN's draft shape differs: report it), set Keep, Penalty and Undrafted round, and override one cost.
- **Waiver trends:** More → Waiver trends: Most added and Most dropped fill, the position chips narrow them, a row opens the Player page, and with a league synced the Free agents chip and owner tags show. Open it once a day for a week: from day 7 the note should read "over the last week".

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
