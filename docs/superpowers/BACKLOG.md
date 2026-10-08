# Backlog and reference

Read on demand only (not at session start). `HANDOFF.md` is the entry point. Edit in place; delete what is fixed or confirmed.

## Tried and rejected

(PPR MAE change on the frozen sample, ± 2 SE; don't retry without a new angle):
- D/ST interceptions with the opponent's interception factor shrunk toward 1 (2026-10-08, the lead from the row below): half the factor +0.0005 ±0.0065, none of it +0.0025 ±0.013 on 2,046 counted D/ST weeks (2022-2025, `tune.db` reforecast); two points an interception is too little to move points either way
- Ranges widened in high over/under games (2026-10-08): coverage by the game's total in terciles (under 42, 42-46, 46 and up) on 16,451 counted player-weeks: 79.8%, 80.1%, 81.3% (high − low +1.6 ±1.5, the wrong way for widening); no position differs beyond noise. Misses split evenly above and below in both
- Pass rate with past game script taken out (2026-10-08): a team's usual pass rate raised by `PASS_RATE_PER_POINT` × its EWMA favored-by, on a `tune.db` reforecast, PPR MAE on 13,396 counted player-weeks: full weight +0.0005 ±0.0003, half +0.0003 ±0.0001 (RB worst). The residual check had shown a signal (t 4.0 on team pass rate), but the ESPN blend already prices it
- Team pace from the last two games (2026-10-08): residual of team plays on (last two games − season average) slope −0.013 ±0.043; the 4-game half-life already covers it
- D/ST interceptions from the opposing QB's FTN interception-worthy rate (2026-10-08): residual slope −2.4 ±1.4 per unit rate, the wrong sign. The opposing QB's actual INT rate has slope −8.3 ±2.1: the model already over-reacts to it, a lead for stronger shrinkage of the opponent's interception factor
- ESPN-only Out discount (2026-10-07): can't be backtested. No ESPN injury status is stored historically, and ESPN's all-zero rows aren't stored, so an ESPN-only Out looks like a player ESPN doesn't rank; the nearest proxy (projected earlier, no row this week) is already rejected below. Not built
- Teammates' rest of season sharing a listed Out/Doubtful player's games (2026-10-07, proxy on `tune.db` 2022-2025, each teammate's next four games, missed ones zero, leave-one-season-out status curve): today's healthy-roster baseline against the mixture (1 − back-by-then) × absent-roster share + back-by-then × healthy: +0.008 ±0.043 (915 events), lifted teammates +0.114 ±0.046; against the absent-roster share it would have helped (−0.33 ±0.05), which is why rest of season already starts from the healthy roster. A stash ESPN projects back (3+ points) shrinking his teammates 0.87 of the way to their healthy share: −0.001 ±0.130 (551 events). Not built
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

## Build notes by round

**Just built (sixth round, 2026-10-08):**
- **Quick look in the Grid**: the Grid's long press opens the quick look when the app provides one, its Add to Compare adding over the Grid's weeks (`LocalPlayerPeek` takes the screen's own compare action); without one (tests) it adds straight away as before.
- **Headshots on disk**: `RemoteCircleImage` keeps each image in `cacheDir/images` (hashed URL), so a restart reads them back without the network.
- **Home share card** (`HomeShareCard`, Home's Share): team, week, live or projected score, win chance meter, players to watch.
- **Game lines** (More → Players, `LinesScreen.kt`): each upcoming game's line split into implied points ((total ± spread) / 2) beside the season so far's (a side's points scored over the other side's points allowed, times the league average: the forecast's own matchup step, which it can't show because a posted line replaces it), biggest gaps first, 3+ points called a lean. `ProjectionsRepository.seasonGames`.
- **League drop alerts** (Settings → Alerts → League drops, prefs `dropAlerts`, on): the two-hourly job reads League activity; a player another team dropped, still a free agent, who would lift your rest of season by 8+ points (`Lineups.pickups` on rest-of-season totals) gets one notification (`DropAlertChecker`, `noBackupFilesDir/drop-alerts.txt`; the first check only remembers).
- **Draft room**: team stripes on every row and a Best available card (headshot, team chip, ADP, I took him / Someone took him; in the mock, Draft him).
- **Accuracy**: interception shrinkage and total-dependent ranges tested and rejected (Tried and rejected).
- **Clean-up**: older "Just built" notes folded into one list of facts; deferred items fixed since dropped.

**Just built (design round, 2026-10-08):**
- **Home tab** (`HomeScreen.kt`, `HomeKey`): with a league (`deps.league`), the app opens on Home and the Grid becomes a tab over it; without one (and in tests) the Grid stays first. Cards: matchup (projected or live score, win chance `Meter`, the week's win-chance line), next kickoff (`gameDay`'s first window), your players to watch (O, D, Q, IR, SUSP), best pickup (`waiverPickups`' first), news on your players. "My lineup" opens Projections on My lineup (`ProjectionListKey(season, lineup = true)`). News moves from the bottom bar into More when Home is there (five tabs).
- **Live win-chance line**: Home asks `LiveWin.estimate` each minute while a game is on, as My lineup does; each new chance (and the pre-game one) is kept in `WinLineStore` (`noBackupFilesDir/winline.txt`, one week at a time) and drawn as `ShareLine`. The live "both lineups" view is Matchups' existing lineup screen (Home links to it); no new screen.
- **Player page header** (`PlayerHero`): a wash of the team's color, ESPN's headshot (`espnImageUrl`: `a.espncdn.com/i/headshots/nfl/players/full/{espn_id}.png`, a D/ST's team logo; `PlayerDirectory.espnId`), `TeamChip`, status badge, this week in large type over a `RangeBar`. Images load with `HttpURLConnection` into an in-memory `LruCache` (`RemoteCircleImage`), no image library.
- **Team colors** (`TeamColors`, primary by nflverse code): `TeamStripe` on Grid rows and every projection list row, `TeamChip` on the header and quick look.
- **Trend sparklines** (`Sparkline`): the Grid's player cell (the range's last six weeks) and My lineup's rows (the six weeks before this one), from `StatsRepository.recentPoints` (one single-week Grid query per week, fantasy points under the profile; null for a week with no row).
- **Spread chart** (`SpreadChart`, `projectedSpread` in `:core:projections`): the This week card and the quick look draw 24 bins of 4,000 simulated outcomes, each widened like `calibratedRange` (so about 80% fall between the floor and ceiling lines); `simulate` now calls `drawPoints`.
- **Compare radar**: "All N players" overlays every compared player (`radar:all`); the pair picker stays for two.
- **Quick look** (`PlayerPeekSheet`, `LocalPlayerPeek`, `Modifier.playerClick`): a long press on any player row outside the Grid opens a sheet with header, this week, spread, rest of season, two headlines, Open page and Add to Compare. The Grid's long press still adds to Compare.
- **Look** (Settings, prefs `wallpaperColors`, `trueBlack`; no format bump, missing reads off): wallpaper colors (Android 12+, `dynamicColorScheme`) and true black dark surfaces. Settings now scrolls as one page.
- **Widget**: rounded dark card, a win-chance bar and a "Watch:" line from Home (`WidgetStore.home`); My lineup's summary drops the bar (its chance isn't stored). Two cells tall by default.

**Just built (UI round, 2026-10-08):** in `Components.kt` unless named.
- **`ScreenBar`** on every screen (back arrow from `GridironIcons.Back`, title, actions), replacing each "← Back" button; tests click tag `back`. Matchups' open lineup goes back with the same arrow (title "Matchup").
- **Bottom bar:** a tab straight over the Grid has no back arrow (`tabBack` in `GridironNavHost`; Projections, Scores and News take a nullable `onBack`); only the top screen lights its tab, so a Player page lights none.
- **`LoadingRows`** (pulsing placeholder rows) replace the centered spinner on list screens. The Grid's first open, the accuracy page and Compare keep theirs: they say what they're doing.
- **`EmptyState`** (icon, message, optional Try again): app's `Message` now wraps it; Grid "No players match", every `Unavailable` state, TD regression. Try again on Scores, Matchups, News and Injury report.
- **`PullToRefresh`** (material3 `PullToRefreshBox`): Grid (starts a stats refresh, shown in the refresh bar), News and Injury report (`live.refresh()`), Scores and Matchups (their reload).
- **Grid:** `GridironIcons.Search` instead of the emoji; the table header casts a shadow once rows scroll under it (`StatTable`). Row heights already scale with font (sp), so nothing changed there.
- **Charts:** "Tap a bar for detail" hint until a bar is picked; the pick survives new values (dropped if its bar empties); bars grow in and `Meter` animates its fill.
- **`FoldHeader`:** Claim plan moved up after the adds, Best partners above Suggested trades, both folded closed by default so nothing under them moves.
- **Motion:** screens slide in a tenth of the width with a fade (`NAV_MS` 220), reversed on back.
- **Share cards** lay out at 360 dp on a density that makes them 1080 px (`FitWidth` in `ShareImage.kt` shrinks the preview), so the PNG is drawn at full size, not upscaled.

**Just built (fifth round, 2026-10-08):**
- **Pre-game lineup check lifts the Questionable discount** (`liftQuestionable`): once a starter's team has posted inactives and ESPN doesn't list him Out, IR, Doubtful or suspended, the check ranks him at full value.
- **Waiver trends: My league** chip: adds and drops in your league over the last 7 days, from League activity's `mTransactions2` reads (`leagueTrends`). A move with no processing time is left out.
- **League activity keeps each trade's first grade** (`TradeGradeStore`, `noBackupFilesDir/trade-grades.txt`, per scoring profile). A trade from before the app first opened the screen is graded when first seen, not when made, so it still carries some hindsight.
- **Trade partners by week**: the fit is summed week by week (a bye leaves a hole), with K and D/ST included. The suggested players still come from season totals.
- **Similar seasons**: rates (QB yards and TDs per attempt; RB yards per carry, carry and target share; WR/TE yards per target, target and air-yards share; K FG%) and age on September 1 join the per-game stats. K and D/ST get comps. `player.birth_date` is new in both builds (schema 13 / Python 8), parity 0 differences on 2025, 651 of 683 players have one.
- **Playoff odds, checked** (League history → Records): the simulator replays each finished season from weeks 6, 9 and 12, with each team's scores so far standing in for projections, and is scored by Brier against ESPN's seeds, beside a chance baseline. It checks the simulation, not the app's projections, which aren't stored for past league seasons.

**Just built (fourth round, 2026-10-07):**
- **Where we differ: K and D/ST** against ESPN's own (a second ESPN request, `Input.ESPN_KICKERS_DEFENSE`, slots 16/17, about 90 players, codes per "Facts to keep"; stored in `espn_projection`, never blended; D/ST ids through `EspnTeams` in `:core:model`, moved out of `FantasyLeague.kt`). Under Model alone they keep the app's number (it is the model alone).
- **Injury-discount note**: Trade tags "Out: misses counted", Planner names your discounted players, the Player page's card says "Out this week: counts each game times his chance of being back by then" (`ProjectionsRepository.rosDiscounted`: nflverse Out or Doubtful in the forecast's upcoming week).
- **Matchups league chip** (two or more leagues) and **League history reloads** on a league switch (`HistoryRoute(leagueId)`).
- **Tap-for-detail on charts**: `ColumnChart` bars select on tap (label, fade, a detail line). The Charted by week table is a table, so it has none.
- **Grid Dynasty value pack**: `StatColumn.DYNASTY_VALUE` with `Aggregate.External`; FantasyCalc values bound into the SQL as `VALUES` rows (`StatQuerySpec.external`, at most 5,000), so sort, filters, percentiles and presets work unchanged. Metric `dynasty_value` (computed) in both builds.
- **Claim plan pairs**: each claim valued on top of the claims above it that could win with it (`rosterValue`), so a second back for one open slot bids for bench depth only.
- **More FTN** (`INGEST_VERSION` 10, 169 metrics, both builds, parity 0 differences on 2025): `ftn_shotgun_rate` (per dropback), `ftn_first_read_rate` (throws to read 1 of attempts with a charted read: `read_thrown` not 0 or blank; values 1, 2, CHK, DES, SD), `ftn_avg_rushers` (per dropback FTN counted rushers). FTN has no read-option column; "read" meant `read_thrown`. 2025 averages: shotgun 79%, first read 57%, 4.35 rushers.
- **Game-day refresh times** per day (Settings, `TimePickerDialog`; prefs `gameDayTimes`, a bad entry falls back to its day's default) and **no offseason runs** (March-August waits for September 1).
- **UI**: amber tertiary (Questionable badge), share cards always light and 1080 px wide (`ImageShare.fixedWidth`), Trade/Playoff odds/Review/Planner on `SectionHeader`, Trade's verdict in a `SummaryCard`, a `Meter` per team's playoff chance.

**Third round (2026-10-07), for reference:** game-day refresh Thursday and Monday too, with a finish notification; refresh under WorkManager (`RefreshWorker`, `stats.db.refreshing`); TD regression on the Player page and Trade; Charted by week; FTN screen, RPO, no-huddle, motion and box; the accuracy page's in-memory backtest cache; Where we differ's Model alone (`FORECAST_VERSION` 19); D/ST blocked kicks (prefs `formatVersion` 5); the return curve by status and body part (`FORECAST_VERSION` 20, rest-of-season MAE −0.27 ±0.22 on the proxy).

**Older features, facts still worth having** (descriptions: `docs/ARCHITECTURE.md`; full notes in git history):
- **Rest-of-season injury discount** (`FORECAST_VERSION` 18): proxy backtest on 2022-2025, a listed player's next four games' PPR points (missed ones zero) against season average × games: MAE 23.8 → 11.0 (−12.8 ±1.5, 553 listings), bias +22.3 → +0.8. A Tuesday build has no week's listings yet.
- **Schedule download**: nflverse serves only `schedules/games.csv.gz` (the plain csv 404s since 2026-10-07).
- **Mock draft** bots take one of `DraftAdvice`'s top three, weighted 0.6/0.25/0.15 (`MockDraft.BOT_WEIGHTS`, judgment).
- **Trade partners**: the design's "need against the league's typical starter" was dropped; it changed nothing on the cases tried.
- **League history**: every ESPN shape it reads is from memory (`status.previousSeasons`, `primaryOwner`, `rankCalculatedFinal`, `playoffSeed`, `schedule[].winner`/`playoffTierType`, pre-2018 `leagueHistory`); finished seasons are cached as files (clear app storage to refetch after a fix).
- **Report card**: the user approved the five categories with Luck in the overall; a tie shares the better place; overall is the average place, ties by all-play.
- **Dynasty & keepers**: the user chose FantasyCalc's market values over an age-curve model, and keepers priced by the round drafted. FantasyCalc lists about 420 players plus 24 rookie picks (dropped; ranks recounted). ESPN's `mDraftDetail` (`draftDetail.picks[]`: `playerId`, `roundId`, `teamId`, `keeper`) is from memory.
- **Waiver trends**: `kona_player_info` on `leaguedefaults/3`, filtered to the 1,000 most rostered (about 600 KB compressed, 20 MB unfiltered; `players_wl` has no `percentChange`); the user chose day snapshots (`live.db` `roster_pct`, a 7-10-day-old one turns on the weekly change) over ESPN's figure, whose window looks daily.

## Deferred minors

**Deferred minors (fourth-round FTN):** first-read rate counts CHK, DES and SD as charted reads that aren't the first (their meaning is FTN's, unverified); shotgun excludes pistol; none of the three is in Compare's metric sets or the Player page's Charted by week.

**Deferred minors (sixth round):** Game lines' season numbers ignore home field and injuries and start from week 1 with no prior; drop alerts value rest of season only (not this week), use season totals rather than weekly (byes ignored) and name no drop; the draft's best-available card shows no headshot in the mock; the image cache has no size cap of its own.

**Deferred minors (design round):** Home appears only with a league configured (not just synced: with no team picked it says so); its win-chance line keeps one point per change, not per minute, and starts again each week; a new install with a league lands on Home, so the Grid's first-open message moved one tap away; headshots need ESPN's CDN once (then the app's cache folder, which Android may clear); team colors are primaries only (no secondary, dark-mode contrast unchecked for navy teams on dark surfaces); the Grid's sparkline reads six extra queries per page; the spread chart's bins ignore the Questionable lift's effect on shape (it scales the axis only); the quick look's Add to Compare outside the Grid adds the whole current season; wallpaper colors also recolor the heat scale's surroundings (the heat colors themselves are fixed); the widget's bar shows only after Home has been opened.

**Deferred minors (UI round):** pull to refresh on Scores and Matchups doesn't hold its spinner (the week reloads to its skeleton or stays until replaced); the Grid's pull starts a full stats rebuild, so an accidental pull at the table's top starts one (the refresh bar shows it, and a second is refused); folded sections don't remember being opened across app restarts; the Grid's first-open spinner, the accuracy page's and Compare's stay spinners; the Player page's bar has no title (the page's header names him); Robolectric doesn't check the share card's pixel size.

**Deferred minors (similar seasons):** no team context; the pool is whatever seasons the phone built (2-3), so comps rarely reach far back; metrics weigh equally; the same player's own other seasons are left out; the pool is re-read on every page open.

**Deferred minors (TD regression):** the 2-touchdown floor is a judgment; passing touchdowns count with rushing and receiving for a QB, so a QB's gap is mostly passing.

**Deferred minors (game-day refresh):** the four days are fixed (only their times change; a day can't be turned off); the offseason is calendar months, not the schedule (a late-February game or an early-September Thursday is missed); it lives under Alerts though it isn't one; the time picker is the platform dialog, untested by Robolectric (the scheduling and the prefs are tested).

**Deferred minors (claim plan):** claims are ordered by each add's gain alone, then valued on top of those above (a better order could exist); two adds on open spots both count as winnable even with one open spot; bids use the same judgment curve as before; a drop needed for roster room on an open spot counts as distinct; the section is folded by default.

**Deferred minors (Tuesday summary):** the report card place reads every finished week from ESPN (one request a week, plus the draft) once a Tuesday; win chance uses each roster's best lineup, not the lineup set in ESPN; a phone off all Tuesday skips the week.

**Deferred minors (where we differ):** a Questionable discount sits in the app's number, not ESPN's; ESPN's D/ST blocked kicks aren't mapped (code unverified), so the app's D/ST carries a little the ESPN number doesn't; a finished season copied from an older build has no ESPN K or D/ST rows (only the upcoming week is shown, so it doesn't matter yet).

**Deferred minors (trade partners):** a one-way fit (you sell only) ranks with two-way ones; the section is folded by default.

**Deferred minors (league activity):** a trade is graded with today's projections, not the rosters or projections at the time; every week is read on each open past the 15-minute hold (one request a week); lineup moves and failed claims are hidden; a player `player_xref` can't match shows as "a player".

**Deferred minors (shareable cards):** the preview still shows in the phone's dialog colors around the light card; Robolectric doesn't test the capture itself (the PNG writer and each card's text are tested).

**Deferred minors (league history):** no league chip of its own (switch on Projections or Matchups and it reloads); seasons are read one after another on first open (one request each); a cached season with a wrong guess stays cached until the files are deleted; co-owners count under the primary owner only; consolation games are dropped entirely.

**Deferred minors (report card):** points count only in starting lineups and by ESPN's scores; a traded player's points before the trade count as the other team's move; Draft credits the drafting team even for weeks another team started him (by design); the categories are equally weighted; Luck is in the overall although it isn't skill (the user approved).

**Deferred minors (dynasty & keepers):** the draft is read every time the screen opens (no cache); keeper costs ignore how many years a player was kept; a negative-surplus player is still marked Keep when he is in the top N; the 6-hour value cache is a judgment; K and D/ST have no worth; a pick ESPN makes for a player later released and re-added still prices at that pick; the Grid's dynasty column lists only players with stats in the range (a rookie who hasn't played is missing), is fetched with each query that needs it (the 6-hour cache saves the network) and says nothing when FantasyCalc fails (blank column); Trade and the Player page show them in every league (no dynasty-league check) and Trade's totals add values without FantasyCalc's package adjustment.

**Deferred minors (waiver trends):** the background snapshot runs only while an alert is on (it rides the alert job); days are UTC; the 25-row cut and the 15-minute memory cache are judgments; a player `player_xref` can't match isn't tappable and has no points; the snapshot is ESPN's public leagues (the My league chip reads the user's league's moves instead); ESPN's `percentChange` is shown as is.

**Deferred minors (lineup alerts, Questionable):** before inactives post, a Questionable starter still projects at his discount (ESPN's badge can't tell an active Q from an undecided one), so an early-Sunday My lineup may suggest sitting him; the pre-game lineup alert itself doesn't lift the discount; Projections reads kickoffs when it loads and when My lineup opens, so a week list or Start/sit left open across the cutoff lifts only after reopening; the Player page card reads them each time the page loads; the waterfall and the Grid's projection columns read the stored final, so they keep the discount (a confirmed-active player's card can read above the waterfall it opens). Review's last week is the later of the stats build's and ESPN's.

**Deferred minors (rest-of-season discount):** the forecast's curve pools Out and Doubtful and every position, and ignores the body part and IR (the phone's `InjuryReturns` uses them); an ESPN-only Out with no nflverse listing isn't discounted (can't be backtested, see Tried and rejected); a returning player's teammates' rest of season doesn't shrink to make room (tested, no gain); the discount note reads nflverse's listing for the upcoming week, so a Tuesday build has none.

**Deferred minors (injury returns):** the body part is nflverse's latest named one this season, so an ESPN-only Out with no nflverse listing reads every injury; a suspension isn't covered; positions are pooled; an IR player is assumed to have the 4-game minimum still to serve when he's missed fewer; a game in progress counts as not yet played; the outlook doesn't feed projections; the Injury report computes each row's outlook in turn on every open (a few queries each).

**Deferred minors (look):** the Grid itself wasn't restyled (its heat cells are the visual); Trade, Playoff odds, Review and Planner rows themselves are unchanged (headers, the verdict card and the odds meter are new); the Charted by week table has no tap-for-detail (it's a table).

**Deferred minors (bottom bar):** a tab always opens the current season, so a past season's screens are reached from the Grid's ☰.

**Deferred minors (game day):** a pivot must fit the Questionable starter's own slot (a FLEX reshuffle that frees a slot isn't found); a bye or a team ESPN gives no kickoff is left out; kickoffs are read when My lineup opens, so a window that starts while it is open stays until it reloads; times show in the phone's zone.

**Deferred minors (injury alerts):** ESPN's news feed is fetched with the injuries every run (one extra request); a player ESPN doesn't match to an app id is never alerted; quiet hours are fixed at 10 pm–8 am (no picker) and a status that changes twice overnight sends only the latest; Robolectric doesn't run the worker (the diff and the checker are unit-tested; the scheduling isn't).

**Deferred minors (trades):** cuts go by rest-of-season total unless the user picks one (their side only; the other team's cut and the ideas' cuts are the lowest); `BENCH_WEIGHT` and the FAAB curve are judgments; a player with no projection still counts against roster size (so is cut first); IR slots count as roster spots; two teams with the same name collide; the playoff line uses the user's league's weeks for both sides.

**Deferred minors (returning players):** rest of season starts from the healthy roster, where a usual starter nflverse lists Out or Doubtful this week stays the starter (ESPN's doubt is about this week), so the backup starting this week gets no rest-of-season row at all, this week included (as before); a player ESPN doesn't rank among its 700 most-owned can't return this way; ESPN's 3-5 point band plays only about 62-84%, so some returnees are projected for a game they miss.

**Deferred minors (ESPN blend):** a player ESPN projects nothing for (it expects him out) keeps the full model projection, which doesn't move MAE but affects My lineup; 784 frozen player-weeks fell under the 5-point counting floor with the blend; the weights were fitted on PPR totals and apply to every profile; a player with no `espn_id` in nflverse's player file is dropped; about 8 MB a season; CI's parity job downloads ESPN too (a failure there only warns).

**Deferred minors (season form, QB spread, snap share):** season form dampens an injured teammate's share by 15% and counts a traded player's old-team games; team targets and carries no longer sum exactly to team volume (engine tests pin the blended sums, team carries to 0.05); one season-form weight for RB, WR and TE; the typical QB's carry share includes backups' games; a spot-starter QB is compressed like a veteran; snap share counts offense only and skips missed games in its 3-game window.

**Deferred minors (rising roles):** the lift is partly mechanical (shared baseline); "teammates out" can lag nflverse's injury report; the Grid column shows blank for QBs; no Compare use; the table is rebuilt in full each forecast; the window includes the previous season's playoff weeks early in a season.

**Deferred minors (several leagues):** a league with no team chosen hides the My lineup chip (the league chips stay); "Save and sync" with a blank id syncs the active league; the opponent and matchups fetches use whichever league is active when they run, so a quick switch can show the old league's week until the next reload.

**Deferred minors (opportunities):** the uptick uses the forecast, which already moves a share to teammates only for players nflverse lists Out or Doubtful, so an ESPN-only status (or a newly Questionable one) can understate it; a starter who missed most of the four-game window may rank below his real role; only the top two (QB1) count, so a WR3 stepping up isn't flagged; the usage window is the last four played weeks league-wide, so a team's bye counts as a missed week; no depth chart from ESPN is used.

**Deferred minors (free agents, my lineup, opponent, pickups):** each pickup list values one horizon (this week, or rest of season), not both at once, don't know your roster size or who is droppable (a bye-week player isn't projected, so is never suggested as a drop), and each pickup is rated alone;  the opponent comparison uses the snapshot's roster, so a pickup since the last sync is missed;  free agents come from the last sync, so a pickup since then still shows (or hides) until the next one; free agents is Grid-only and not saved in presets; kickoff locks come from ESPN's scoreboard when My lineup opens (no scoreboard, no locks), and waiver gains still let your own locked players move; the auto-sync's failure is silent on My lineup (ESPN leagues shows it); a player on a bye or with no projection can't be placed (listed apart).

**Deferred minors (rollups):** early in a season every L window equals `S` and stores the same sums again; `S` uses the newest `g` row, so a season with only playoff weeks has no window; the Python and Kotlin builders each carry their own 17/18-week rule.

**Deferred minors (Grid layout):** the bar's second row scrolls sideways for pack and positions when they don't fit (More can sit half off-screen on a 412 dp phone); at 200% font Android's nonlinear scaling gives 54 dp rows, so the two-line player cell may be tight; the hide threshold (24 dp) and row counts are estimates; Roborazzi images are build outputs, not committed.

**Deferred minors (presets):** a double-tap on Save can ask to replace the preset just saved; Undo does nothing when its name or last free slot was taken meanwhile, and says nothing; a "last N weeks" view applied in week 8 doesn't move forward when a refresh brings week 9 (until applied again); at 30 presets Save is disabled, so a preset can't be replaced by saving under its name; the `two saves at 29 end at 30` test runs the saves in turn, not at once.

**Deferred minors (FTN):** the Grid's CSV export header credits nflverse only; `FtnRealDatabaseTest` needs the FTN and play-by-play files in the Python download cache, so CI's Kotlin-only job skips it; the pipeline's coverage warning names no weeks; a brief 404 of a known season's FTN file rebuilds it without FTN and says "no FTN charting yet" (as snaps and injuries do); Kotlin reads the flags case-sensitively ("TRUE") where Python upper-cases, and Python casts play id and `n_blitzers` text straight to an integer where Kotlin goes through a double; per-game FTN totals (DRP, CRT) divide by play-by-play games, so an uncharted week understates them; the FTN index is held through the rest of `crunch`; a renamed-column FTN warning shows once (the version is still recorded).

**Deferred minors (NGS):** an average missing or dropped still leaves its weight in the denominator (2 rows in 2025); "no NGS rows published yet" also fires for 2012-2015; `loadNgs` drops a group silently on a 404; NGS facts carry NGS team codes ("LAR", not "LA"); all seasons' NGS rows stay in memory for a build and the three files are re-downloaded when any season is rebuilt and NGS is unchanged; the Python twin drops all NGS if one file fails; `NgsRealDatabaseTest` needs the Python download cache, so CI's Kotlin-only job skips it; no `predicts` text on the ten metrics.

**Deferred minors (yards work):** the 0–800 `yards_allowed` range check hard-fails a build on one out-of-range game (observed 75–647); no dedicated `BacktestTest` case for yards; the matchup note reads "scores 24.1 pts, 331 yards".

## Open checks on the phone

- **Sixth round:** long-press a Grid row and the quick look opens (Add to Compare adds over the Grid's weeks); headshots show offline after one view; Home → Share; More → Game lines lists the week's games with leans; a drop alert arrives after another team drops a useful player; Draft shows a Best available card.
- **Design round:** with a league set, the app opens on Home showing your matchup, win chance and players to watch; during a game the chance updates each minute and a line grows; a Player page shows a headshot on a team-colored band; Grid rows show a team stripe and a small trend line; the This week card shows a bar chart of outcomes; long-press a player in Projections, My lineup or another list (not the Grid) and a sheet opens; Settings → Look switches wallpaper colors and true black; the widget shows a win-chance bar after Home opens.
- **UI round:** every screen has a back arrow at the top left, except Projections, Scores, News and More opened from the bottom bar; open a Player page from Projections and no tab is lit; pull down on News or the Injury report and it reloads; pull down on the Grid and the refresh bar appears; Waiver trends opening shows grey placeholder rows first; My lineup's Claim plan and Trade's Best partners open on a tap; a shared card looks sharp when zoomed.

- **Fifth round:** a Questionable starter whose team posted inactives gets no "Lineup:" alert for being Questionable; Waiver trends shows a My league chip with a synced league; League activity's trades read "when first seen"; Trade's Best partners can name a team for a kicker or D/ST; a Player page's Similar seasons line starts "Age N"; League history → Records shows "Playoff odds, checked".

- **Third round:** Thursday 3 pm a "Stats refreshed" notification arrives; a Player page shows "Touchdowns vs expected" for a TD regression player and "Charted by week" for a WR; Trade rows show "TDs ±N vs exp"; the Grid's FTN Rushing pack lists box counts; Where we differ's Model alone chip shows "Model N · ESPN N"; a D/ST's Grid row has a BLK column and Settings → Scoring lists Blocked kick at 2; the accuracy page reopens instantly.

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
