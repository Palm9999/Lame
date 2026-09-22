# NFL Fantasy Stat Catalog — Definitive Research Document

**Purpose:** backbone spec for an Android fantasy football app whose core promise is *"extensive stats with powerful sort and filter, nothing paywalled."*
**Date:** 2026-09-22
**Scope:** every fantasy-relevant metric worth shipping, its definition, formula, position relevance, predictive value, and whether it is computable from free public data.

---

## 0. How to read this document

### Computability legend

| Symbol | Meaning | Source |
|---|---|---|
| **A** | Free, fully computable from public play-by-play + box scores | `nflverse` / `nflfastR` / `nfl_data_py` PBP, ESPN/NFL box scores |
| **B** | Free, but needs an auxiliary free feed (snap counts, participation, NGS weekly, depth charts, injuries, schedules, odds) | `nflreadr::load_snap_counts` (PFR), `load_participation`, `load_nextgen_stats`, `load_depth_charts`, `load_injuries`, `load_schedules`, odds APIs |
| **C** | Requires manual charting or a proprietary model — approximable with a home-grown model on free data | PFF, Fantasy Points Data, FTN charting, ESPN win-rate metrics, SIS |
| **D** | Proprietary, not reproducible; can only be displayed if licensed or omitted | PFF grades, DVOA, ESPN PBWR/RBWR, NGS tracking-only fields |

> **Product implication:** the app's "nothing paywalled" promise is achievable for ~85% of this catalog because A + B covers nearly everything that actually moves fantasy decisions. Tier C items should be *approximated with clearly-labeled in-house models* (e.g. "xFP (our model)") rather than omitted or licensed.

### Core free data stack (recommended)

| Feed | Contents | Cadence | Notes |
|---|---|---|---|
| `nflfastR` / `nfl_data_py` PBP | Every play 1999–present with EPA, WP, WPA, CPOE, air yards, YAC, xpass, success | Live-ish in-season (~15 min), finalized Tue | The single most important dependency. ~400 columns/play. |
| `load_snap_counts()` | Offense/defense/ST snaps + snap % per player per game (from Pro Football Reference) | Weekly, Wed | **Critical** — snap share is the #1 volume signal. |
| `load_participation()` | Personnel groupings, players on field per play, defenders in box (NGS pre-2023, FTN 2023+) | Delayed (FTN posts post-season for some years) | Enables routes-run approximation and light/stacked box splits. |
| `load_nextgen_stats()` | Weekly NGS: CPOE, time to throw, aggressiveness, separation, RYOE, expected YAC, efficiency | Weekly | Player-week granularity, free. |
| `load_ftn_charting()` | Manual charting 2022+: is_screen, is_play_action, is_rpo, read_thrown, n_pass_rushers, is_qb_pressured | Within 48h of games | Unlocks pressure rate, play-action rate, screen rate for free. |
| `load_depth_charts()` / `load_rosters(weekly)` | Weekly depth chart position and status | Weekly | Depth chart change detection. |
| `load_injuries()` | Official injury report: practice status Wed/Thu/Fri + game designation | Weekly | Practice-participation trend. |
| `load_schedules()` | Schedule + **Vegas spread, total, moneyline**, roof, surface, temp, wind | Weekly | Implied team totals, weather, dome/outdoor — all free here. |
| `load_contracts()` | Contract value, years, guarantees (OverTheCap) | Periodic | Dynasty contract-year signal. |
| `load_draft_picks()` / `load_combine()` | Draft capital + combine testing | Annual | Rookie profiles, athletic scores. |
| `load_players()` | Height, weight, birthdate, college, entry year | Continuous | Age curves. |

---

## 1. Core box-score foundation (must-have baseline, all tier A)

These are table stakes. Every one needs season totals, per-game, and per-week views.

### 1.1 Quarterback

| Metric | Abbr | Definition / Formula | Predicts | Tier |
|---|---|---|---|---|
| Pass attempts | ATT | Dropbacks resulting in a throw | Volume floor; single best QB fantasy input alongside rush attempts | A |
| Completions | CMP | — | — | A |
| Completion % | CMP% | CMP / ATT | Weakly predictive alone; use CPOE | A |
| Passing yards | PaYd | — | 1 pt/25 yds in most formats | A |
| Passing TDs | PaTD | — | Highly variance-prone; regress toward xPaTD | A |
| Interceptions | INT | — | Negative points; ~sticky at low level | A |
| Sacks taken | SK | — | Yardage loss + injury risk | A |
| Rush attempts (QB) | RuATT | Designed runs + scrambles + kneels (exclude kneels) | **The single biggest QB fantasy differentiator** | A |
| Rush yards / TDs | RuYd/RuTD | — | Rushing TDs are the most valuable QB scoring source per attempt | A |
| Yards per attempt | Y/A | PaYd / ATT | Efficiency baseline | A |
| Adjusted yards per attempt | AY/A | (PaYd + 20×PaTD − 45×INT) / ATT | Better single-number efficiency | A |
| Passer rating | RTG | NFL formula | Legacy; low analytic value | A |
| Total QBR | QBR | ESPN win-probability-weighted QB value | Real-life value; weak fantasy predictor | D |
| Fumbles / fumbles lost | FUM/FL | — | Negative points; fumbles-lost is ~random, total fumbles is sticky | A |
| Games with 300+ / 4 TD | — | Threshold counts | Bonus-scoring formats | A |

### 1.2 Running back

| Metric | Abbr | Definition | Predicts | Tier |
|---|---|---|---|---|
| Carries | ATT | Rush attempts | Primary volume | A |
| Rush yards / TDs | RuYd / RuTD | — | — | A |
| Yards per carry | YPC | RuYd / ATT | **Notoriously unstable year-to-year**; do not over-weight | A |
| Targets / receptions | TGT / REC | — | PPR floor; receptions are the most stable RB fantasy input | A |
| Receiving yards / TDs | ReYd / ReTD | — | — | A |
| Total touches | TCH | ATT + REC | Workload | A |
| Total opportunities | OPP | ATT + TGT | Better than touches — a target is an opportunity even if uncaught | A |
| Scrimmage yards | — | RuYd + ReYd | — | A |
| Fumbles | FUM | — | Coach-benching risk, negative pts | A |
| Goal-line carries | GL ATT | Carries from inside the 5 | ~68% of rushing TDs come from inside the 5 | A |

### 1.3 Wide receiver / Tight end

| Metric | Abbr | Definition | Predicts | Tier |
|---|---|---|---|---|
| Targets | TGT | — | **The single most predictive raw receiving stat** | A |
| Receptions | REC | — | PPR floor | A |
| Receiving yards / TDs | ReYd / ReTD | — | — | A |
| Yards per reception | Y/R | ReYd / REC | Role descriptor, not skill | A |
| Yards per target | Y/TGT | ReYd / TGT | Efficiency; ~1.7–2.0 avg, 2.5+ is elite | A |
| Catch rate | CTCH% | REC / TGT | Confounded by aDOT — always pair with aDOT | A |
| Air yards | AY | Sum of pass-depth on all targets (intended, regardless of completion) | Opportunity ceiling | A |
| Yards after catch | YAC | ReYd − completed air yards | Role + skill mix | A |
| End-zone targets | EZ TGT | Targets where the ball lands in the end zone | Most valuable single target type (≈3.0 fantasy pts avg) | A |
| Rush attempts (WR) | — | Jet sweeps, end-arounds | Small but real for some profiles | A |
| Return yards/TDs | — | KR/PR | Matters in return-yardage formats | A |

### 1.4 Kicker (commonly neglected — a differentiator)

| Metric | Abbr | Definition | Predicts | Tier |
|---|---|---|---|---|
| FG made/attempted by distance bucket | FGM 0-19/20-29/30-39/40-49/50+ | — | Distance-weighted scoring formats | A |
| FG% by bucket | — | FGM/FGA per bucket | Kicker skill (very noisy) | A |
| Extra points made/attempted | XPM/XPA | — | — | A |
| FG attempts per game | FGA/G | — | **Kicker volume >> kicker accuracy for fantasy** | A |
| Team red zone TD rate (inverse) | — | Low RZ TD% + high scoring = more FGA | The actual kicker predictor | A |
| Dome/indoor share of remaining schedule | — | — | Meaningful kicker edge | B |

### 1.5 Team defense / special teams (D/ST)

| Metric | Definition | Tier |
|---|---|---|
| Sacks, INTs, fumble recoveries, safeties, defensive TDs, return TDs, blocked kicks | Standard D/ST scoring inputs | A |
| Points allowed / yards allowed | Tiered scoring input | A |
| Opponent implied team total | Vegas-derived; **the best single D/ST streaming predictor** | B |
| Opponent turnover-worthy play rate, sack rate allowed, pressure rate faced | Predictive of sack/INT production | A/C |
| Opponent giveaway rate, opponent PROE | Volume of chances | A |

---

## 2. Volume / Opportunity metrics

> **Governing principle:** opportunity is far more stable year-over-year and week-over-week than efficiency. A player's *share of his team's opportunity* is the most predictive family of metrics in fantasy football. Build the app around these first.

### 2.1 Snaps and participation

| Metric | Abbr | Formula | Positions | Predicts | Tier |
|---|---|---|---|---|---|
| Offensive snaps | SNP | Count of offensive plays on field | All | Raw workload | B |
| **Snap share** | SNP% | Player offensive snaps / team offensive snaps | All | Role security; the earliest leading indicator of a change in usage. RB snap share >65% = clear bellcow; WR >85% = full-time X/Z | B |
| Snap share trend (3/5-week rolling delta) | ΔSNP% | SNP%(last 3) − SNP%(prior 3) | All | Breakout/decline detection — **the #1 waiver-wire signal** | B |
| Snap share after injury return | — | SNP% in games 1/2/3 post-return | All | Ramp-up risk for start/sit | B |
| **Route participation** | RTE% | Routes run / team dropbacks while player active | WR/TE/RB | Passing-game role. WR RTE% >80% = full-time; RB RTE% >50% = true passing-down back | B (from participation) / C (charted) |
| **Routes run** | RR | Count of pass plays on which the player ran a route | WR/TE/RB | Denominator for YPRR/TPRR — the most important derived denominator in receiving analysis | B/C |
| Slot rate | SLOT% | Slot snaps / pass-play snaps | WR/TE | Matchup exposure (slot CBs are weaker); PPR-friendly role | C |
| Wide/out-wide rate | — | Outside alignment snaps / pass snaps | WR | Deep/boundary profile | C |
| Inline vs slot vs wide split (TE) | — | — | TE | Inline TEs run fewer routes; "move" TEs are better fantasy assets | C |
| Personnel grouping rate (11/12/21) | — | % of snaps in each grouping | All | 11 personnel → 3-WR sets → WR3 relevance; 12 personnel → TE2 relevance | B |
| Two-minute drill snaps/routes | — | Snaps in final 2 min of half with score within one score | All | Hurry-up pass volume; identifies the true "closer" WR | A/B |
| Hurry-up / no-huddle rate | — | % of team plays no-huddle | Team | Pace and volume boost | A |

### 2.2 Target-based opportunity (WR/TE/RB)

| Metric | Abbr | Formula | Predicts | Tier |
|---|---|---|---|---|
| **Target share** | TS / TGT% | Player targets / team targets | Single best receiving opportunity metric. >25% = alpha; >30% = league-winning | A |
| Targets per game | TPG | TGT / games played | Volume normalized for missed time | A |
| **Targets per route run** | TPRR | TGT / routes run | Separates role from talent — "how often does he earn the ball when he runs?" >22% elite for WR, >20% elite for TE | B/C |
| Hog rate | — | TGT / offensive snaps | Identifies efficient part-time receivers (high target rate on low snaps) | B |
| **Air yards** | AY | Σ pass depth on all targets | Ceiling proxy; uncaught deep targets still count as opportunity | A |
| **Air yards share** | AY% | Player air yards / team air yards | Downfield opportunity concentration | A |
| **WOPR (Weighted Opportunity Rating)** | WOPR | `1.5 × target share + 0.7 × air yards share` | Composite opportunity. Created by Josh Hermsmeyer. Elite WRs >0.70; 0.50+ = weekly starter; correlates ~0.8 with fantasy points | A |
| **aDOT (average depth of target)** | aDOT | Air yards / targets | Role classifier. **Highly sticky year to year.** <7 = slot/checkdown; 8–12 = balanced; >14 = deep threat | A |
| Deep target rate | — | Targets ≥20 air yards / total targets | Boom-week source; best-ball relevance | A |
| Deep targets per game | — | — | Ceiling driver | A |
| Short target rate (<5 aDOT) | — | — | PPR floor, YAC dependence | A |
| Unrealized air yards | — | Air yards on incompletions | Regression-positive signal (targets earned, points not yet received) | A |
| Target quality | — | Catchable targets weighted by depth | Separates QB-hurt receivers from bad ones | C |
| Catchable target rate | — | Catchable targets / targets | QB accuracy tax on a receiver | C |
| Target premium | — | (Player FP/target − team-mate avg FP/target) / team-mate avg | Isolates receiver quality from QB quality (PlayerProfiler) | A (derivable) |
| Target competition index | — | Σ target share of teammates at same position group weighted by talent | Roster-context signal for buy-low | A (derivable) |
| **Red zone targets** | RZ TGT | Targets from inside the opp 20 | TD equity — a target from the 10 is worth ~2.2 pts vs ~1.8 from the 19 | A |
| Red zone target share | RZ TGT% | Player RZ targets / team RZ targets | Best single TD-regression indicator for pass catchers | A |
| **Green zone targets (inside 10)** | GZ TGT | Targets inside the opp 10 | 43.8% of passing TDs come from inside the 10 | A |
| End zone targets | EZ TGT | Ball thrown into the end zone | ~3.0 fantasy pts of expected value per target | A |
| Third-down target share | — | — | Role stickiness; conversion involvement | A |
| Two-minute target share | — | — | Identifies the trusted option in hurry-up | A |

### 2.3 Carry-based opportunity (RB/QB)

| Metric | Abbr | Formula | Predicts | Tier |
|---|---|---|---|---|
| Carries per game | ATT/G | — | Baseline | A |
| **Carry share / rush share** | ATT% | Player carries / team RB carries (or team total carries) | Backfield control | A |
| **Opportunity share** | OPP% | (Carries + targets) / (team RB carries + team RB targets) | PlayerProfiler's headline RB metric — >70% = bellcow | A |
| **Weighted opportunities** | WO | `carries × 1 + targets × k`, where k ≈ 2.5–2.7 in PPR (ratio of avg fantasy pts per target to per carry) | Corrects "touches" for the fact that a target is worth ~2.5× a carry in PPR | A |
| Weighted opportunity share | WO% | Player WO / team RB WO | Best single RB volume metric | A |
| **Red zone carries / share** | RZ ATT | Carries inside the opp 20 | TD equity | A |
| **Green zone carries (inside 10)** | GZ ATT | 73.9% of rushing TDs come from inside the 10 | The TD-regression workhorse metric | A |
| **Goal-line carries (inside 5)** | GL ATT | 68% of rushing TDs come from inside the 5 | Highest-leverage single usage stat in fantasy | A |
| Two-minute-drill carries | — | — | Negative signal (means the RB is off the field) | A |
| Snap-weighted game script | — | Avg score differential when player is on field | Is he the lead-protecting back or the hurry-up back? | A |
| High-value touches | HVT | Targets + carries inside the 10 | Combines the two most valuable touch types | A |
| Pass-block snaps / rate | — | — | Role security (pass-pro trust keeps a back on the field) | C |
| Carries vs light / base / stacked box | — | Box counts: ≤6 / 7 / ≥8 defenders | Efficiency context; stacked-box rate correlates with poor YPC | B (participation) |

### 2.4 Team volume and pace

| Metric | Abbr | Formula | Predicts | Tier |
|---|---|---|---|---|
| Plays per game | PPG | Team offensive plays / games | Everyone's volume ceiling | A |
| **Situation-neutral pace** | sec/play | Avg seconds per play with WP between 20–80% and score within one score | Removes garbage-time distortion | A |
| Pace of play | — | Plays / minutes of possession | PlayerProfiler style: >2.50 fast, <2.00 slow | A |
| Team pass attempts / rush attempts per game | — | — | Positional volume split | A |
| Pass rate | — | Pass plays / total plays | Raw tendency | A |
| **Neutral-script pass rate** | — | Pass rate in Q1–Q3 with score within 7 (or WP 20–80%) | Coach intent stripped of game script | A |
| **Pass rate over expectation** | PROE | Actual pass rate − expected pass rate from the `xpass` model (down, distance, field position, score, time) | **Better than neutral pass rate** — keeps the full sample and controls for every situation. Positive = pass-first identity | A (`xpass` ships in nflfastR) |
| PROE+ | — | PROE combined with neutral pace | Identifies fast + pass-heavy = maximum fantasy environment | A |
| Seconds per play in two-minute drill | — | — | Late-half volume | A |
| Team drives per game | — | — | Opportunity count | A |
| Team no-huddle rate | — | — | Volume boost | A |

---

## 3. Efficiency metrics

> **Governing principle:** efficiency is less stable than opportunity, but it (a) explains *why* a player's opportunity may change and (b) identifies regression candidates. Always display efficiency **next to** its volume denominator, and always gate leaderboards behind a minimum-sample filter.

### 3.1 Receiving efficiency

| Metric | Abbr | Formula | Positions | Predicts | Tier |
|---|---|---|---|---|---|
| **Yards per route run** | YPRR | Receiving yards / routes run | WR/TE/RB | **The best single receiving efficiency metric.** Combines separation, target earning, and YAC. WRs top-20 in YPRR scored ~154% more fantasy points the following season than bottom-20. Elite WR >2.50; elite TE >2.00 | B/C |
| Fantasy points per route run | FP/RR | Fantasy points / routes run | WR/TE | Scoring-format-aware version of YPRR | B/C |
| Yards per target | Y/TGT | ReYd / TGT | WR/TE/RB | Efficiency per opportunity | A |
| **RACR (Receiver Air Conversion Ratio)** | RACR | Receiving yards / air yards (equivalently Y/TGT ÷ aDOT) | WR/TE | How efficiently air-yard opportunity converts to real yards. >1.0 = beating his target depth. Low-aDOT YAC receivers post high RACR; deep threats post low RACR | A |
| Catch rate | CTCH% | REC / TGT | All | Must be read against aDOT | A |
| True catch rate | — | REC / catchable targets | All | Removes QB inaccuracy | C |
| Drop rate | DROP% | Drops / catchable targets | All | Sticky-ish skill; affects coach trust | C |
| Contested catch rate | CC% | Contested receptions / contested targets | WR/TE | Red zone and deep-ball conversion; league avg ~45% | C |
| Contested target rate | — | Contested targets / targets | WR/TE | Separation deficiency proxy | C |
| **Yards after catch** | YAC | ReYd − completed air yards | All | Role + elusiveness | A |
| YAC per reception | YAC/R | YAC / REC | All | — | A |
| **YAC over expected** | YACOE / xYAC+/− | Actual YAC − NGS Expected YAC (model uses receiver speed, separation, defender positions at catch point) | All | Isolates post-catch skill from scheme. Sticky for elite YAC creators | B (NGS weekly) |
| YAC success rate | — | % of receptions exceeding expected YAC | All | Consistency of YAC creation | B |
| Average separation | SEP | Avg yards from nearest defender at pass arrival | WR/TE | NGS; route-running proxy | B |
| Route separation (all routes) | — | Avg separation across all routes, not just targets | WR/TE | Better than target separation — unbiased by target selection | C |
| Burn rate / win rate vs man & zone | — | Routes on which the receiver won | WR/TE | Scheme-specific matchup exploitation | C |
| Missed/broken tackles forced (rec) | MTF | Tackles evaded after the catch | All | YAC engine | C |
| Yards after contact (rec) | — | — | All | — | C |
| Target rate on routes vs man/zone | — | — | WR/TE | Scheme matchup edge | C |
| **Fantasy points over expected** | FPOE | Actual fantasy points − expected fantasy points (xFP) | All | **Primary regression flag.** Large positive FPOE on small samples → sell high | A (own model) / C |
| TD rate per target | — | ReTD / TGT | All | Very noisy; regress to expected TDs | A |
| Expected receiving TDs | xReTD | Σ per-target TD probability given field position and depth | All | The correct TD baseline | A (own model) |

### 3.2 Rushing efficiency

| Metric | Abbr | Formula | Positions | Predicts | Tier |
|---|---|---|---|---|---|
| Yards per carry | YPC | RuYd / ATT | RB/QB | Weakly predictive; long runs dominate the average | A |
| True yards per carry | — | YPC with all runs >10 yards capped at 10 | RB | Rewards consistency; strips long-run luck | A |
| **Rush yards over expected** | RYOE | Actual rush yards − NGS Expected Rushing Yards (models blocker/defender location, speed, direction) | RB/QB | Isolates back from blocking. Moderately sticky | B (NGS) |
| RYOE per attempt | RYOE/ATT | RYOE / carries | RB | Rate version — the usable one | B |
| **Rushing success rate** | — | % of carries with EPA > 0 | RB/QB | Floor/consistency; drives coach trust | A |
| EPA per rush | — | Σ EPA on rushes / rushes | RB/QB | Overall value | A |
| Yards after contact | YACO | Yards gained past first contact | RB | Tackle-breaking + vision | C |
| Yards after contact per attempt | YACO/ATT | — | RB | Elite >3.0 | C |
| Missed/broken tackles forced (rush) | MTF | — | RB | Elusiveness | C |
| **Juke rate** | — | Evaded tackles / total touches (carries + receptions) | RB | PlayerProfiler elusiveness metric | C |
| Stuffed run rate | — | Carries for ≤0 yards / carries | RB | Negative-play risk; partly O-line | A |
| Breakaway rate / explosive run rate | — | Runs ≥15 yards / carries (or yards on 15+ runs / total yards) | RB | Ceiling driver, best-ball relevance | A |
| 10+ yard run rate | — | — | RB | — | A |
| Yards created | — | Yards above blocked yards, after first evaded tackle | RB | Graham Barfield metric; pure back contribution | C |
| Yards before contact per attempt | — | — | RB | Blocking quality proxy | C |
| Expected rushing TDs | xRuTD | Σ per-carry TD probability given yard line | RB/QB | TD regression | A (own model) |
| Goal-line conversion rate | — | Rush TDs / carries inside the 5 | RB | Small sample; regress hard | A |
| Rush attempts vs stacked box rate | — | % of carries vs 8+ in box | RB | Context for YPC | B |

### 3.3 Quarterback efficiency

| Metric | Abbr | Formula | Predicts | Tier |
|---|---|---|---|---|
| **EPA per dropback** | EPA/DB | Σ EPA on dropbacks (incl. sacks & scrambles) / dropbacks | Best single QB value metric. Elite >0.20 | A |
| **CPOE** | CPOE | Actual completion % − NGS/nflfastR expected completion % (models air yards, receiver separation, pressure distance, throw-on-run, time to throw) | Sticky QB accuracy signal; more predictive than raw CMP% | A (nflfastR ships `cpoe`) / B (NGS) |
| EPA + CPOE composite | — | Standardized blend (RBSDM style) | Best all-round QB quality ranking | A |
| Success rate | — | % of plays with EPA > 0 | Consistency | A |
| **Time to throw** | TT | Avg snap-to-throw seconds (sacks excluded) | <2.5s = quick game (low aDOT, high CMP%); >3.0s = holds ball (sack risk, deep shots) | B (NGS) |
| **Pressure rate** | PRS% | Pressures faced / dropbacks | Sack and turnover risk; O-line + QB blend | B (FTN charting `is_qb_pressured`) / C |
| Pressure-to-sack rate | — | Sacks / pressures | QB-controlled portion of sacks | B/C |
| Sack rate | SK% | Sacks / dropbacks | Fantasy-negative in sack-penalty formats; injury risk | A |
| Time to pressure (allowed) | — | Avg seconds until first pressure | O-line quality | B |
| **Scramble rate** | — | Scrambles / dropbacks | Underrated rushing-fantasy driver separate from designed runs | A |
| Designed rush rate | — | Designed QB runs / team plays | The sticky part of QB rushing | A |
| Rush attempts inside the 5 | — | — | The QB "tush push"/sneak signal — enormous fantasy value | A |
| **Deep ball rate** | — | Attempts ≥20 air yards / attempts | Offense identity; WR ceiling driver | A |
| Deep ball completion % | — | Completions ≥20 AY / attempts ≥20 AY | Highly variable; regress | A |
| Deep ball accuracy / on-target rate | — | On-target deep throws / deep attempts | Better than deep CMP% | C |
| **Aggressiveness** | AGG% | Attempts into tight windows (defender ≤1 yd) / attempts | Risk profile; correlates with WR1 boom weeks | B (NGS) |
| Intended air yards per attempt | IAY/A | Air yards / attempts (QB aDOT) | Offense vertical identity | A |
| Completed air yards % | CAY% | Completed air yards / total passing yards | How much of the yardage the QB generates vs YAC help | A |
| Play-action rate & EPA split | — | — | Scheme; PA boosts efficiency ~0.1 EPA/play | B (FTN) |
| Screen rate | — | — | Inflates CMP% and RB receiving volume | B (FTN) |
| RPO rate | — | — | Suppresses aDOT | B (FTN) |
| Turnover-worthy play rate | TWP% | Interceptable throws / dropbacks | Better INT predictor than raw INTs | C |
| Big-time throw rate | BTT% | — | Upside passing | D (PFF) |
| Expected passing TDs | xPaTD | Σ per-attempt TD probability | TD regression | A (own model) |
| Adjusted net yards per attempt | ANY/A | (PaYd + 20×TD − 45×INT − SkYds) / (ATT + SK) | Complete efficiency single number | A |
| Clean-pocket vs pressured splits | — | CMP%, EPA, aDOT under each | Matchup projection vs high-pressure defenses | B/C |
| Blitz rate faced & EPA vs blitz | — | — | Matchup; blitz-beaters get ceiling games | B (FTN `n_pass_rushers`) |

---

## 4. Fantasy-specific metrics

### 4.1 Scoring and rate

| Metric | Abbr | Formula | Notes | Tier |
|---|---|---|---|---|
| Fantasy points (PPR / half / standard) | FP | Format-dependent sum | Must be **recomputed live from a user-defined scoring profile**, not stored as three fixed columns | A |
| Fantasy points per game | FPPG | FP / games played | Choose denominator carefully: games *active* vs games *with an opportunity* (PlayerProfiler uses the latter) | A |
| Points per snap | FP/SNP | FP / offensive snaps | Efficiency of role | B |
| Points per touch | FP/TCH | FP / (carries + receptions) | RB/WR skill per touch | A |
| **Points per opportunity** | FP/OPP | FP / (carries + targets) | Better than per-touch; the standard RB efficiency measure | A |
| Points per target | FP/TGT | FP / targets | WR/TE efficiency; ~1.7 league avg PPR | A |
| Points per route run | FP/RR | FP / routes run | Best WR/TE efficiency | B/C |
| Points per dropback / attempt | FP/ATT | — | QB | A |
| **Expected fantasy points** | xFP | Σ over plays of expected fantasy value given play context (down, distance, yard line, play type, aDOT, catch probability, TD probability) | The opportunity-only baseline; strips talent and luck | A (own model) / C |
| xFP per game | xFP/G | — | **Better week-to-week predictor than actual FPPG** | A |
| **Fantasy points over expected** | FPOE | FP − xFP | Positive = outperforming opportunity (possible sell-high); negative = buy-low | A |
| FPOE per opportunity | — | FPOE / opportunities | Rate version | A |
| Opportunity-adjusted fantasy points | OFP | ESPN's variant of xFP | Same concept | D |
| Market share of team fantasy points | — | Player FP / team skill-position FP | Offense concentration | A |
| Production premium | — | Play outcomes vs league-average outcome in identical situations | PlayerProfiler situation-agnostic efficiency | A (approximable via EPA) |

### 4.2 Consistency, floor, ceiling

| Metric | Definition / Formula | Use | Tier |
|---|---|---|---|
| Standard deviation of weekly FP | σ | Raw volatility | A |
| Coefficient of variation | CV = σ / mean | Volatility normalized for scoring level — comparable across positions | A |
| Weekly volatility index | PlayerProfiler scale: >8.0 significant oscillation, >10.0 extreme boom/bust | Best-ball vs redraft sorting | A |
| Consistency score | mean − σ (or mean − 1σ) | Rank-friendly single number | A |
| **Floor (p10 / p25)** | 10th or 25th percentile of weekly scores | Cash games, must-win weeks | A |
| **Ceiling (p90 / p75)** | 90th or 75th percentile of weekly scores | Best ball, GPPs, chasing | A |
| Median weekly score | p50 | More honest than mean for skewed distributions | A |
| **Boom rate** | % of games ≥ a threshold. Best definitions: (a) weekly positional top-12 finish for QB/TE, top-24 for RB/WR; (b) ≥ 2× positional weekly median; (c) fixed thresholds (e.g. WR ≥20 PPR) | Upside frequency | A |
| **Bust rate** | % of games ≤ threshold (e.g. < 50% of the player's own season average, or below the positional replacement line) | Downside frequency | A |
| **Start-worthy rate** | % of games finishing inside the startable window (QB1/RB2/WR3/TE1 by week) | The most *practically useful* consistency stat — "how often did starting him work?" | A |
| Positional finish-rate distribution | % of weeks finishing top-3 / top-12 / top-24 / top-36 at the position | Full weekly outcome shape in one row | A |
| Weeks as team's top scorer | — | Alpha identification | A |
| Spike-week count | Games ≥ a high bar (e.g. WR ≥25 PPR, RB ≥22, TE ≥18, QB ≥28) | **The core best-ball metric** | A |
| Best-ball points added | Points contributed above the weekly lineup-qualification threshold | Directly models best-ball value | A |
| Dud rate | Games <5 (WR/TE/RB) or <10 (QB) fantasy points | Roster-killer frequency | A |
| Games missed / availability rate | Games played / team games | Availability is a skill; heavily under-weighted | A/B |

### 4.3 Value, scarcity and market

| Metric | Formula | Notes | Tier |
|---|---|---|---|
| **VORP / VBD** | Projected FP − replacement-level FP at that position | Replacement baseline must be **league-settings aware** (team count, starters, flex, superflex) | A |
| VOLS (Value Over Last Starter) | Projected FP − FP of the last starter at that position | Aggressive baseline; good for early rounds | A |
| VONA (Value Over Next Available) | Projected FP − FP of the best player likely available at your next pick | Live-draft baseline | A |
| Positional scarcity curve | FP dropoff between consecutive positional ranks | Drives tier breaks | A |
| Tier breaks | Cluster weekly-projection distributions (k-means or gap statistic) | Better draft UX than raw ranks | A |
| **ADP** | Average draft position across platforms | Market price | B (scrape/licence) |
| ADP vs ECR gap | ECR rank − ADP rank | Positive = experts like him more than the room = value | B |
| **Value over ADP (VoADP)** | Actual positional finish − ADP positional rank | Hit-rate history | B |
| ADP standard deviation | Spread of draft positions | Where to actually draft him vs the risk of waiting | B |
| Auction value | VORP scaled to the league's auction budget: `$ = (VORP / Σ VORP of drafted players) × (total budget − $1 per roster spot) + 1` | Must respond live to budget/roster settings | A |
| Rest-of-season value | Projected remaining FP over replacement | Trade/waiver engine | A |
| **Buy-low index** | Composite z-score: high opportunity share + negative FPOE + declining points but stable/rising snap & route share + soft ROS schedule | The flagship "insight" feature | A |
| **Sell-high index** | Inverse: unsustainable TD rate vs xTD, high positive FPOE, declining snap/route trend, tough ROS schedule | — | A |
| Trade value chart / dynasty value | Market-consensus point value | Requires community data or own model | B/C |
| Waiver priority / FAAB recommendation | Projected ROS VORP gain vs current roster | Actionable output | A |

### 4.4 Regression-flag pack (high product value, all tier A)

| Flag | Trigger |
|---|---|
| TD regression (positive) | RZ/GZ opportunity share well above TD share; actual TDs < expected TDs |
| TD regression (negative) | TD rate per touch >2σ above positional mean on <40 opportunities |
| Efficiency regression | YPC or Y/TGT >1.5σ above career mean with flat opportunity |
| Volume breakout | Snap% and route% rising 3 weeks straight while target share lags — targets usually follow |
| Volume collapse | Snap% down >10 pts over 3 weeks |
| Unrealized air yards | High air-yards share with low reception yardage → positive regression |
| Catch-rate regression | Catch rate far below aDOT-adjusted expectation |
| Schedule flip | Last-4 opponent DvP rank vs next-4 opponent DvP rank diverging by >10 |

---

## 5. Matchup and context metrics

### 5.1 Defense vs position and defensive quality

| Metric | Abbr | Formula / Definition | Caveats | Tier |
|---|---|---|---|---|
| **Fantasy points allowed per game to position** | FPA | Σ opponent FP by position / games | The raw, naive version | A |
| **Defense vs Position (adjusted)** | DvP | FPA adjusted for the strength of the offenses faced; often expressed as a % inflation/deflation (e.g. +10% = boosts opposing production 10%) | Naive FPA is badly confounded by schedule; **always schedule-adjust.** Also blind to injuries and personnel changes | A (own adjustment) |
| DvP by scoring format | — | Recompute under user's scoring | Critical for TE-premium and non-PPR | A |
| Opponent-adjusted FPA (SAFPA) | — | Regress FPA on opponent offense quality; residual = true defensive effect | The correct methodology | A |
| Defensive EPA per play (overall/pass/rush) | — | Σ EPA allowed / plays | Better than yards allowed | A |
| Defensive success rate allowed | — | — | Consistency of defense | A |
| Defensive DVOA (pass/rush, and vs WR1/WR2/slot/TE/RB) | — | Opponent-adjusted value over average | **The canonical position-split defense metric** — but proprietary | D (approximate with own EPA-by-target-type splits) |
| EPA allowed by target type | — | Split by aDOT band, by alignment (slot/wide), by position targeted | Free DVOA substitute — build this | A + B |
| Yards per route run allowed by alignment | — | — | Slot vs boundary matchup edge | C |
| Pressure rate generated | — | Pressures / opponent dropbacks | Suppresses QB and deep passing | B/C |
| Blitz rate | — | — | Matchup for blitz-beating QBs and checkdown RBs | B (FTN) |
| Man vs zone coverage rate | — | — | Man-heavy → target the WR who wins vs man; zone-heavy → boosts TEs and possession WRs | C |
| Light/base/stacked box rate | — | % of snaps with ≤6 / 7 / ≥8 in box | RB efficiency context | B |
| Adjusted line yards allowed (defense) | ALY | — | Run-defense front quality | C |
| Red zone TD rate allowed | — | Opp RZ TDs / opp RZ trips | TD matchup | A |
| Points/drive allowed, yards/drive allowed | — | — | Scoring environment | A |
| Time of possession allowed, plays faced per game | — | — | Volume the defense concedes | A |
| Pace faced | — | — | — | A |

### 5.2 Cornerback and individual matchups

| Metric | Definition | Notes | Tier |
|---|---|---|---|
| **Shadow probability** | Likelihood the opposing CB1 travels with a given WR | Only ~8–10 CBs shadow regularly; shadow coverage measurably suppresses WR fantasy output | C (charting + manual tags) |
| CB coverage grade / coverage rating | Composite: target rate allowed, catch rate allowed, yards/coverage snap, PBUs, FP allowed per snap | The usable single CB number | C |
| Yards per coverage snap allowed | Receiving yards allowed / coverage snaps | Best free-ish CB metric if charting available | C |
| Passer rating allowed when targeted | — | Legacy but widely understood | C |
| Burn rate | % of targets where the receiver gained >5 yards of separation | PlayerProfiler | C |
| Slot CB vs perimeter CB split | — | Directs whether the slot or boundary WR benefits | C |
| **WR/CB matchup matrix** | Per-game grid pairing each WR with his projected primary coverage defender, with a grade | High-value UX artifact (FTN, Fantasy Points both ship one) | C |
| Alignment-vs-alignment mismatch | Player slot rate × opponent slot coverage weakness | Derivable proxy when CB charting is unavailable | B |

### 5.3 Offensive line

| Metric | Abbr | Definition | Notes | Tier |
|---|---|---|---|---|
| **Adjusted line yards** | ALY | Rushing yards credited to the line: 0–4 yds ×100%, 5–10 ×50%, 11+ ×0%, losses ×120%, then adjusted for down/distance/opponent/situation | **Strongest O-line→RB fantasy link found in research** (explains ~29% of RB half-PPR production; r ≈ 0.31) | C (formula is public; rebuild on free PBP) |
| Second-level yards / open-field yards | — | Yards 5–10 / 11+ per carry, attributed to the back | Splits back from line | C (rebuildable) |
| Stuffed rate allowed | — | % of carries stopped at or behind LOS | Line failure rate | A |
| Power success rate | — | Conversion rate on 3rd/4th & ≤2 and goal-to-go inside 2 | Short-yardage TD environment | A |
| **Pass block win rate** | PBWR | % of pass-blocking snaps where the blocker holds his block ≥2.5 seconds | ESPN/NGS proprietary; weak direct correlation with RB fantasy, real for QB | D |
| **Run block win rate** | RBWR | % of run-block snaps where the blocker beats his assignment | Surprisingly weak RB fantasy correlation — prefer ALY | D |
| Pressure rate allowed | — | Pressures allowed / dropbacks | QB matchup | B/C |
| Sack rate allowed | — | — | — | A |
| Time to pressure allowed | — | — | Deep-shot viability | B |
| O-line continuity / games with same 5 | — | Consecutive starts of the same unit | Meaningful predictor of line quality | B |
| O-line injury flags | — | Starter out | Weekly matchup adjustment | B |

### 5.4 Game environment (all tier A/B and free — a big differentiator)

| Metric | Formula | Fantasy meaning | Tier |
|---|---|---|---|
| Game total (O/U) | Vegas | Scoring environment | B |
| Point spread | Vegas | Game-script direction | B |
| **Implied team total** | `(game total − team spread) / 2` (spread negative for favorites) | **The single best free weekly context number.** Drives QB/RB/WR/K/D-ST projections | B |
| Opponent implied total | — | D/ST streaming and shootout detection | B |
| Implied total percentile within slate | — | Fast relative read | B |
| Projected game script | Spread-derived: favorites run more late; big underdogs pass more and abandon the run | RB volume down for big underdogs; WR volume up | B |
| Pace-adjusted projected plays | Blend of both teams' neutral pace and PROE, adjusted by spread | Volume projection | A/B |
| Shootout index | Total × (1 − |spread|/total) | Both-sides-relevant games | B |
| Home/away splits | Per-player and per-team FP splits | Modest effect but users demand it | A |
| **Roof: dome / retractable / outdoors** | From `load_schedules()` | Dome games raise passing and kicking; measurable | B |
| Surface: turf vs grass | From schedules | Slight speed/injury implications | B |
| **Wind speed** | From schedules / weather API | **The biggest weather factor.** Effects begin around 15 mph; passing efficiency starts to decline ~1.6 pp in completion %; at 20+ mph fantasy passing output drops ~15–20%. Key thresholds: 15 / 20 / 25 mph. Rushing attempts rise. Kickers hurt most | B |
| Temperature | — | Extreme cold modestly suppresses passing; correlates with wind | B |
| Precipitation | Weather API | Less impactful than wind; raises fumble rate slightly | B |
| Rest days | Days since last game | Thu (short week) suppresses offense; post-bye modest boost | A |
| Travel distance and time-zone change | Haversine between stadiums + TZ delta | West→East 1pm ET games historically underperform | A (derivable) |
| Altitude (Denver) | — | Minor, deep-ball friendly | A |
| Primetime / standalone flag | — | User-requested split | A |
| Divisional game flag | — | Familiarity → slightly lower scoring | A |
| Revenge/scheme-familiarity flags | Coach/coordinator prior team | Nice-to-have narrative filter | B |

### 5.5 Strength of schedule

| Metric | Definition | Notes | Tier |
|---|---|---|---|
| Season SOS by position | Avg opponent DvP rank across all weeks | Preseason draft tool | A |
| **Rest-of-season SOS by position** | Avg opponent DvP over remaining weeks | The in-season version — far more useful | A |
| **Playoff SOS (weeks 15–17)** | Same, restricted to the league's playoff weeks (user-configurable: 14–16, 15–17, or 15–16+17) | Tie-breaker between similar players; heavily used | A |
| Next-4-weeks SOS | Rolling short-horizon | Trade-deadline decisions | A |
| Schedule-adjusted FPA (SAFPA) | Opponent-quality normalized FPA | Correct methodology | A |
| SOS matrix heat grid | Team × week color grid of matchup quality per position | The canonical UX for this | A |
| Bye week | — | Roster construction | B |
| Opponent-quality-weighted player splits | Player FP vs top-10 / bottom-10 defenses | "Is he matchup-proof?" | A |

---

## 6. Team-level metrics

| Metric | Formula | Fantasy relevance | Tier |
|---|---|---|---|
| Offensive EPA per play (total / pass / rush) | Σ EPA / plays | Overall offense quality | A |
| Defensive EPA per play allowed | — | Matchup input | A |
| Net EPA per play | Off EPA/play − Def EPA/play | Team strength single number | A |
| Early-down EPA per play | 1st & 2nd down only | Removes 3rd-down noise; more stable | A |
| Offensive success rate | % of plays EPA>0 | Sustained-drive proxy | A |
| **PROE** | Actual pass rate − xpass rate | Play-calling identity | A |
| Neutral-situation pass rate | WP 20–80%, score within 7 | Intent | A |
| Situation-neutral pace (sec/play) | — | Volume | A |
| Plays per game | — | Volume | A |
| Points per drive | Points / drives | Scoring environment | A |
| Yards per drive | — | — | A |
| Drive success rate | % of drives gaining ≥1 first down (or reaching FG range) | Sustained offense | A |
| Three-and-out rate | — | Drive failure | A |
| Red zone trips per game | — | TD opportunity volume | A |
| **Red zone TD rate** | RZ TDs / RZ trips | TD environment; league avg ~55% | A |
| Goal-to-go TD rate | — | RB TD environment | A |
| Third-down conversion rate | — | Drive extension | A |
| Fourth-down attempt rate & go-rate over expected | Actual vs analytics-optimal | Aggressive coaches boost RB/QB TD equity | A |
| Time of possession | — | Weak but demanded | A |
| Turnover rate (giveaways/drive) | — | Drive-ending | A |
| Takeaway rate | — | D/ST scoring | A |
| Penalty rate / pre-snap penalties | — | Drive killers | A |
| Team pass attempts / rush attempts per game | — | Positional volume split | A |
| Team target distribution by position group (WR/TE/RB) | — | Where the passing pie goes | A |
| Team air yards per game | — | Offense verticality | A |
| Team aDOT | — | — | A |
| Scoring environment index | Implied totals + points per drive + pace + PROE composite | "Which offenses do I want shares of?" | A/B |
| Offensive coordinator / play-caller change flag | — | Regime-change alert | B |
| Team snap counts, injuries to starters at other positions | — | Cascading usage changes | B |

---

## 7. Situational / roster / profile metrics

### 7.1 Injury and availability

| Metric | Definition | Notes | Tier |
|---|---|---|---|
| Game designation | Out / Doubtful / Questionable / (historically Probable) | Questionable is ~50/50 but skews toward playing in the modern era | B |
| **Practice participation Wed/Thu/Fri** | DNP / Limited / Full | Wednesday is noisiest, **Friday is the most actionable**. DNP→LP→FP is trending up; the reverse trends toward inactive | B |
| Practice trend arrow | Derived from the 3-day sequence | Ship this as a first-class field, not buried text | B |
| Injury type and body part | From the report | Soft-tissue (hamstring) reinjury risk is high; ankle/knee suppress efficiency for weeks | B |
| Weeks since return | — | Snap ramp typically takes 2–3 games | B |
| **Snap share in games 1/2/3 after return** | — | The concrete way to model a ramp | B |
| Games missed (career and last 3 seasons) | — | Durability prior | A |
| Injury-adjusted projection | Projection × play probability | Better than binary in/out | B |
| IR / PUP / NFI status and eligible-to-return week | — | Roster management | B |
| Suspension games and return week | — | — | B |
| Contract holdout / hold-in flag | — | Preseason | B |

### 7.2 Depth chart, competition and team context

| Metric | Definition | Tier |
|---|---|---|
| Depth chart position and weekly change | From `load_depth_charts()` | B |
| Teammate target share concentration (HHI) | Σ of squared target shares — high = concentrated offense | A |
| Target competition added/removed | Free-agent signings, draft picks, injuries at the same position | B |
| Vacated targets / carries / air yards from prior season | Departed players' share | A |
| Backfield committee index | 1 − (lead back's opportunity share) | A |
| Handcuff mapping and handcuff value | Projected FP of the backup if the starter misses time | A |
| QB change flag and QB quality delta | Change in supporting QB's EPA/CPOE | A |
| New scheme / coordinator flag | — | B |
| Bye week | — | B |

### 7.3 Rookie and prospect profile

| Metric | Formula | Predicts | Tier |
|---|---|---|---|
| **Draft capital** | Round and overall pick; best expressed as draft-pick trade value points | **The single most predictive rookie variable** — teams invest opportunity in their investments | B |
| **College dominator rating** | Avg of (% of team receiving yards) and (% of team receiving TDs); for RB use % of total team offensive production | 35%+ = WR1 potential; 20–35% mid; <20% red flag | B (CFB data) |
| **Breakout age** | Age at the start of the first college season with dominator ≥20% (WR), ≥15% (RB/TE); QB: first season with QBR ≥50 on 20+ action plays/game | Younger breakout → markedly higher NFL hit rate | B |
| Final-season dominator / market share | — | — | B |
| Yards per team pass attempt (college) | — | Better than raw receiving yards for scheme-adjusting | B |
| Age at draft / age-adjusted production | — | Every year younger is worth a lot | B |
| 40-yard dash | — | WR/RB ≤4.50 fast; QB/TE <4.70 | B (combine data free) |
| **Speed score** | (weight × 200) / (40-time)⁴ | RB size-adjusted speed | B |
| Height-adjusted speed score | Speed score ÷ (avg positional height: 73.0" WR, 76.4" TE) | WR/TE | B |
| Burst score | Vertical jump + broad jump (normalized to equal weight) | Explosiveness | B |
| Agility score | 20-yd shuttle + 3-cone | Short-area quickness, tackle avoidance | B |
| Catch radius | Composite of 40, shuttle, 3-cone, height, arm length, vertical | Contested-catch proxy | B (derivable) |
| BMI | 703 × weight(lb) / height(in)² | RB sturdiness | B |
| SPARQ-x / Relative Athletic Score (RAS) | Composite athleticism percentile | RAS is free and widely used | B |
| Athleticism score | 40 + burst + agility normalized for size | — | B |
| College level of competition | Strength of opposing defenses faced | Context for production | C |
| College teammate score | Draft capital of teammates | Production context | C |
| Landing-spot score | Vacated opportunity + offense quality + PROE + implied totals | Where he lands matters as much as who he is | A/B |
| Rookie-year snap ramp | Snap% by week | In-season rookie tracking | B |

### 7.4 Age and career-stage

| Position | Peak window | Decline onset | Notes |
|---|---|---|---|
| QB | 28–33, very flat 27–35 | Late and abrupt | Longest usable window; rushing QBs decline earlier in rushing production |
| RB | 24–26 (peak often Year 3) | 27; ~15% annual decline in prime | Dynasty sell window is 25–26 |
| WR | 26–28, broad plateau to 30 | 31–32; ~10% annual decline | Flatter and later than RB |
| TE | 27–29, can hold to 32–33 | Latest of the skill positions | Slowest to break out (often Year 3) and slowest to decline |

Derived fields worth shipping: **age (to the tenth of a year), years of experience, career-stage tag (pre-breakout / ascending / prime / declining), age-adjusted production percentile, projected remaining prime years.**

---

## 8. IDP (individual defensive player) metrics

| Metric | Abbr | Notes | Tier |
|---|---|---|---|
| Solo tackles | SOLO | Typically 1.5–2.0 pts; **the IDP volume backbone** | A |
| Assisted tackles | AST | 0.75–1.0 pts | A |
| Combined tackles | TOT | SOLO + AST | A |
| **Tackles for loss** | TFL | Big-play bonus (often 1–3 pts) | A |
| Sacks | SK | 2–6 pts depending on platform (ESPN 4, Sleeper default 2) — **sack value swings DL rankings more than any other setting** | A |
| QB hits | QBH | 1–2 pts; smooths DL scoring | A |
| QB pressures / hurries | PRS | Best sack predictor; **sacks are extremely volatile, pressure rate is sticky** | C |
| Pass deflections / passes defended | PD | 1.5–3.0 pts; the widest scoring disparity between platforms | A |
| Interceptions | INT | 4–6 pts | A |
| Forced fumbles / fumble recoveries | FF / FR | 3–4 pts | A |
| Defensive TDs, safeties, blocked kicks | — | — | A |
| **Defensive snap share** | — | The IDP equivalent of offensive snap share — the #1 IDP volume metric | B |
| Coverage snaps vs pass-rush snaps split | — | Role identification for LB/S | C |
| Box snap rate (safeties) | — | **Box safeties are tackle machines; deep safeties are not.** Biggest IDP role signal at S | C |
| Run-defense snap share, 3rd-down/nickel usage | — | LBs who leave the field on passing downs have a hard ceiling | B/C |
| Tackle opportunity rate | Tackles / opponent plays | Volume rate | A |
| Missed tackle rate | — | Efficiency, playing-time risk | C |
| Pass-rush win rate | — | Sack predictor | D |
| Pressure rate | Pressures / pass-rush snaps | Best free-ish DL metric if charted | C |
| Opponent context for IDP | Opponent plays per game, pass rate, sack rate allowed, rushing volume | Matchup streaming | A |
| Team defensive scheme (3-4 vs 4-3, base vs nickel rate) | — | Determines LB/DL snap counts | B |

**Key IDP insight:** the IDP market is badly underserved by mainstream apps, and nearly every IDP stat is tier A (already in standard box scores). This is a cheap, high-differentiation feature — ship full IDP with snap shares and scoring-aware rankings.

---

## 9. Format-specific stat needs

### 9.1 Format → metric emphasis matrix

| Format | Metrics that gain importance | Metrics that lose importance | Unique fields to ship |
|---|---|---|---|
| **Redraft / season-long** | FPPG, start-worthy rate, consistency, ROS SOS, snap trend | College profile, age | Weekly start/sit confidence, playoff SOS |
| **Best ball** | **Spike weeks, ceiling (p90), boom rate, weekly volatility, deep target rate, air yards, TD equity, best-ball points added** | Floor, bust rate, consistency, start-worthy rate (no lineup decisions) | Spike-week count, ceiling-per-ADP, weekly correlation with teammates (stacking), Week 15–17 advance-round emphasis |
| **Dynasty** | **Age, draft capital, contract years remaining, breakout age, college dominator, years-of-team-control, age-adjusted production, landing spot** | Single-week matchup | Age curve position, projected remaining prime years, contract expiry year, guaranteed money, rookie-pick value chart, "win-now vs rebuild" toggle on valuations |
| **Superflex / 2QB** | **QB VORP (replacement level collapses), QB rushing floor, QB games-started security, backup QB depth, QB age curve (flat and long)** | RB scarcity | Superflex-specific VORP and trade values; QB scarcity index; "QB in the top 24 overall" reordering |
| **IDP** | Solo tackles, defensive snap share, box-safety rate, pressure rate, scheme | Everything offensive | Full defensive player pages + scoring-profile-aware IDP rankings |
| **DFS** | **Salary, value per $1k, projected ownership, leverage, ceiling, correlation/stacking, optimal-lineup %, boom probability** | Season-long consistency | Salary by site (DK/FD/Yahoo), pOWN, leverage score, game stacks |
| **Auction** | VORP-derived dollar values, inflation-adjusted budget tracking, positional budget allocation | ADP rank order | Live auction budget tracker, $ remaining per roster slot, inflation rate |
| **TE Premium (+0.5 / +1.0 / +1.5 PPR)** | TE reception volume, TE target share, TE routes run; TE VORP rises sharply | TE yards-per-catch profiles | Scoring toggle must recompute all rankings and VORP — a 7-catch TE in +1.5 banks 10.5 bonus points a WR never sees |
| **Standard (non-PPR)** | Yards per touch, TD equity, RZ/GZ usage, aDOT | Reception volume, slot/checkdown roles | — |
| **First-down bonus (PPFD)** | **First downs gained, first-down rate per target/carry, third-down conversion involvement, chain-moving rate** | Deep-ball TD dependence | First downs is a real box-score stat (tier A) and almost nobody surfaces it — ship `first downs`, `1D per target`, `1D per carry`, `1D share` |
| **Yardage bonuses (100/150/200, 300/400 pass)** | Ceiling and spike-week metrics; threshold hit-rate columns | Median | "% of games ≥100 rush+rec yards" style threshold columns |
| **Negative points (INT/fumble)** | Turnover-worthy play rate, fumble rate per touch, QB sack rate | — | Turnovers per game, fumble rate, and a "negative-points-adjusted" scoring profile |
| **Return yardage formats** | KR/PR yards, return attempts, return TDs | — | Return usage per game (sticky role) |
| **Big-play / explosive bonuses (40+ yd TD)** | Deep target rate, breakaway run rate, aDOT | — | Long-play frequency |

### 9.2 The scoring-profile engine (an architectural requirement)

Because scoring formats change *every* derived metric (FP, FPPG, boom/bust, VORP, DvP, auction values), the app must treat scoring as configuration, not as columns:

```
ScoringProfile {
  pass_yd: 0.04,  pass_td: 4,  pass_int: -2,  pass_2pt: 2,
  pass_300_bonus: 0, pass_400_bonus: 0, pass_40yd_td_bonus: 0,
  rush_yd: 0.1,   rush_td: 6,  rush_100_bonus: 0,
  rec: 1.0, rec_te_bonus: 0.0, rec_rb_bonus: 0.0, rec_wr_bonus: 0.0,
  rec_yd: 0.1, rec_td: 6, rec_100_bonus: 0,
  first_down: 0.0, fumble_lost: -2, fumble: 0, sack_taken: 0,
  idp: { solo: 1.5, asst: 0.75, sack: 4, tfl: 1, qb_hit: 1, pd: 1.5, int: 5, ff: 4, fr: 4, def_td: 6, safety: 2 },
  kicker: { fg_0_39: 3, fg_40_49: 4, fg_50p: 5, fg_miss: -1, xp: 1 },
  dst: { sack: 1, int: 2, fr: 2, td: 6, safety: 2, pa_tiers: [...] },
  league: { teams: 12, starters: {qb:1, rb:2, wr:3, te:1, flex:1, superflex:0, k:1, dst:1, idp:0} }
}
```

Every leaderboard, comparison and projection recomputes against the active profile, with league presets (ESPN/Yahoo/Sleeper/NFL/CBS defaults, PPR/half/standard, Superflex, TE-P, IDP, DFS site scoring) plus a custom editor. Importing a league from Sleeper's public API (free) is the highest-leverage onboarding feature available.

---

## 10. Comparison UX patterns — what works and what the good sites actually do

### 10.1 What the reference sites do well

| Site | Signature comparison artifact | What works | What to copy / avoid |
|---|---|---|---|
| **PlayerProfiler** | Single-page player "snapshot" where *every* metric carries a **0–100 percentile rank in parentheses**, color-coded green→red; mobile-optimized by design | Percentile ranks make 60+ unfamiliar metrics instantly legible without knowing any metric's scale. Best-comparable-player feature gives an instant mental anchor | **Copy the percentile-everywhere pattern — this is the single most transferable idea in the industry.** Their metric density can overwhelm; use progressive disclosure |
| **RotoViz Screener** | A query builder: filter on anything, group, compare players, build regressions, compare arbitrary week ranges | Power-user ceiling is enormous; "show me all WRs with >20% target share and <8 aDOT in weeks 5–12" | Copy the **saved-query** concept. Avoid the desktop-only, spreadsheet-dense presentation on mobile |
| **4for4 Stats Browser** | Targets/touches/points/end-zone opportunity, filterable by week range, specific weeks, team, position | Simple, fast, and **week-range filtering is the killer feature** | Copy week-range filtering as a first-class global control |
| **FantasyPros** | Consensus ranks + ECR-vs-ADP value, boom/bust reports, points-allowed matchup tables, VORP/VOLS/VONA rankings | Turning analysis into a single actionable rank; boom/bust report framing | Copy the boom/bust report layout and the ECR-vs-ADP value column |
| **Fantasy Points Data** | WR/CB matchup report, route-level data, splits | Charted data presented as an *actionable weekly matchup grid* rather than a raw table | Copy the matchup-matrix layout even if the underlying grade is your own model |
| **FTN** | Shadow-coverage matrix, ownership projections, air yards leaderboards | Single-purpose tools that answer one question completely | Copy the "one screen answers one question" discipline |
| **Sharp Football** | xFP leaderboard, implied team totals tool, red-zone-stats-vs-expectation | **Expected vs actual framing** is the clearest way to present regression | Copy the expected-vs-actual paired column presentation everywhere |
| **PFF / Football Outsiders** | Grades, DVOA, premium splits | Brand-trusted single numbers | Can't copy the data; can copy the idea of a single trusted composite |

### 10.2 Recommended comparison surfaces (ranked by value/effort)

1. **Percentile rank bars (horizontal, sorted) — the workhorse.**
   For every metric on a player page, render a labeled horizontal bar showing the player's percentile within his position (among players above a snap/route minimum). Show the raw value *and* the percentile. Research consensus in sports analytics is that a **sorted bar strip beats a radar chart**: encoding is linear (a 90th-percentile bar is exactly twice a 45th), non-adjacent values are directly comparable, and the reader cannot be misled by axis ordering. Group bars into sections (Opportunity / Efficiency / Scoring / Context) with section-level composite scores.

2. **Head-to-head comparison table (2–4 players), metric rows, players as columns.**
   Diff column, best-value highlighting, and a "show only metrics where they differ by >1 decile" toggle to cut noise. Sticky first column (metric name) with horizontal paging between players on phone. Supports comparing a player to *himself* across seasons or week ranges — an underrated and cheap feature.

3. **Radar / spider chart — include it, but as a secondary "shape" view, never the primary.**
   Radars communicate *profile shape* at a glance (deep threat vs YAC slot vs volume bellcow) and users emotionally expect them. Mitigate the known flaws: cap at **6–8 axes**, use **percentile scales only**, keep **axis order fixed and semantically grouped**, label every axis with the actual value, and never overlay more than 2 players. Offer a one-tap switch between radar and bar-strip on the same metric set.

4. **Scatter plot: opportunity (x) vs efficiency (y).**
   The highest-insight chart in fantasy football. Defaults worth shipping:
   - WR/TE: target share × YPRR (or TPRR × YPRR)
   - RB: opportunity share × FP per opportunity (or weighted-opportunity share × RYOE/att)
   - QB: dropbacks/game × EPA per dropback
   - Regression view: xFP/game (x) vs actual FP/game (y) with a y=x line — points above the line are sell-high, below are buy-low. **This one chart explains the entire regression concept without words.**
   Quadrant shading with labels ("high volume, inefficient"), bubble size = snap share, color = team or tier, tap-to-pin, lasso-to-filter feeding the table below.

5. **Trend sparklines in every table row.**
   A 30×12 dp inline sparkline of the last 6 weeks for the sorted column. Cheap to render, enormously increases the information density of a leaderboard. Tapping expands to a full weekly chart with opponent labels.

6. **Leaderboards with multi-column sort and transposable metrics.**
   Sticky player-name column, sortable headers with an explicit sort-priority chip row ("Sorted by: Target Share ↓, then YPRR ↓"), configurable column sets, and a per-cell percentile color wash (a diverging blue↔orange ramp, never red/green alone — ~8% of men have red-green color deficiency, and this is a male-skewed audience).

7. **Matchup matrix / heat grid.**
   Team (rows) × week (columns) colored by positional matchup quality. The canonical SOS artifact. Also use for WR/CB matchups (WR rows × projected coverage defender) and for a player's weekly game log colored by outcome.

8. **Correlation and distribution views.**
   - Weekly score distribution: box/violin or a dot strip per player showing floor, median, ceiling and each individual week — far more honest than a mean.
   - Stack correlation matrix (QB–WR–TE same team, and vs opposing game stack) for best ball and DFS.
   - Metric-to-metric correlation explorer ("which stats actually predict next-season points?") as an education feature.

9. **Saved filter presets / "screens".**
   Named, shareable, re-runnable. Ship 10–15 curated presets out of the box: "Buy-low WRs", "Bellcow RBs", "Green-zone hogs", "Route share risers", "Soft playoff schedules", "High-ceiling best-ball WRs", "Streaming QBs this week", "Box-safety IDP tackle machines". Presets are the bridge that makes a 200-column dataset usable by a casual user on day one.

10. **Player card + "similar players" anchor.**
    Nearest-neighbour in percentile space, plus historical comps. Gives instant meaning to unfamiliar numbers.

### 10.3 Android-specific presentation rules

- **Never a raw wide table as the default mobile view.** Default to a card/list with 3 key metrics + sparkline; offer an explicit "Table mode" with horizontal scroll and a frozen name column, and a landscape-optimized table.
- Sticky header row + frozen first column; column widths sized to content, numbers right-aligned and tabular-figure aligned.
- Row tap → player detail; row long-press → add to comparison tray. A persistent bottom "compare tray" showing 0–4 selected players is the single best multi-select pattern on mobile.
- Respect dark mode; define percentile color ramps as tokens that work in both themes.
- Virtualized lists (`LazyColumn` with keys) — with 200+ columns and 600+ players, server-side or Room-backed paging plus client-side sort on the loaded slice.
- Offline-first: cache the week's dataset in Room so leaderboards and comparisons work without a connection.
- Every metric name is tappable → a definition sheet with formula, what it predicts, and the league distribution. **Inline education is what separates a "stats app" from a "stats app people keep."**

---

## 11. Filter and sort design for a 200+ column dataset

### 11.1 Dimensions users actually want

| Dimension | Control | Notes |
|---|---|---|
| Position | Segmented chips (QB/RB/WR/TE/FLEX/K/DST/IDP subpositions) | Always visible; drives which columns are shown |
| Team / division / conference | Multi-select | — |
| **Week range** | Dual-handle slider + presets: Last 3, Last 5, Since bye, Weeks 1–4, Full season, Playoff weeks | **The most requested and most under-shipped filter.** Must recompute every rate metric over the selected range, not just sum |
| Specific weeks (non-contiguous) | Multi-select chips | Power-user; enables "with QB X starting" |
| Season / multi-season | Year picker with multi-year aggregation | Dynasty and trend work |
| Home / away / neutral | Tri-state | — |
| Roof (dome / outdoor / retractable) | Chips | — |
| Wind / temperature band | Range slider | Differentiator |
| Opponent quality (opponent DvP rank band) | Range slider: vs top-10 / 11–22 / bottom-10 defenses | "Is he matchup-proof?" |
| Specific opponent | Picker | — |
| **Game script** | Chips: leading / trailing / within one score / blowout; or score-differential range | Requires drive-level filtering of PBP — high value, rarely offered |
| Down / distance / field zone | Chips: 1st/2nd/3rd/4th; red zone / green zone / goal line / own territory | Powers "RZ-only leaderboards" |
| **Minimum snap / route / target / carry threshold** | Numeric slider with sensible defaults per position | **Mandatory** — without it every efficiency leaderboard is topped by a 3-target fluke |
| Minimum games played | Slider | — |
| Age / experience | Range slider | Dynasty |
| Draft capital / rookie flag | Chips | — |
| Injury status | Chips: Healthy / Questionable / Out / IR | — |
| Roster status | Free agent / on my roster / on a rival roster (requires league import) | Turns stats into decisions |
| Bye week | Multi-select | — |
| Scoring format | Global profile selector | Recomputes everything |
| Per-game vs total vs per-opportunity | Global toggle | Changes the meaning of every numeric column |
| Rank vs raw value vs percentile vs z-score | Global display toggle | Cheap to implement, transforms comprehension |

### 11.2 Keeping 200+ columns navigable — concrete recommendations

1. **Column groups with a "stat pack" selector.** Never show 200 columns. Ship named packs: *Basic*, *Volume*, *Opportunity Share*, *Efficiency*, *Advanced/Next Gen*, *Red Zone*, *Consistency*, *Matchup*, *Dynasty*, *DFS*, *IDP*. One tap swaps the whole column set. Packs are the primary navigation device.
2. **Custom views.** Let users build and name their own column sets with drag-to-reorder and pin-to-left. Persist and allow export/share by link.
3. **Column search.** A search field over metric names *and* their aliases/abbreviations ("air yards", "AY", "WOPR") that adds the column to the current view.
4. **Progressive disclosure on player pages.** Six collapsed sections, each opening to its full metric list; the collapsed header shows a section composite percentile.
5. **Two-tier filtering.** *Quick filters* (position, week range, team, min-snaps) apply instantly in a persistent bar. *Advanced filters* open a sheet, support AND/OR conditions per column with type-appropriate operators, and apply on an explicit "Apply" button with a visible count of matching players.
6. **Filter chips with removal.** Every active filter appears as a dismissible chip above the table; a "Clear all" and a "Save as preset" sit alongside.
7. **Sort UX.** Tap a header to sort; long-press (or a sort sheet) to add a secondary/tertiary sort. Display the active sort stack as chips. Always offer ascending/descending and "sort by percentile" for composite fairness.
8. **Result count and empty-state guidance.** Show "37 players match" live; when zero, name the filter that is excluding everyone and offer to relax it.
9. **Performance architecture.** Client-side sort/filter for the loaded slice (<1,000 rows is fine on modern Android with Room + `LazyColumn`); server-side or SQLite-indexed query for cross-season, week-range recomputation. Precompute the common aggregations (season, last-3, last-5, home/away) nightly; compute arbitrary week ranges on demand from a weekly fact table.
10. **Data model.** Store one **long/narrow** weekly fact table (`player_id, season, week, metric_id, value`) plus a metric-metadata table (`metric_id, name, abbr, definition, formula, higher_is_better, format, position_scope, pack, tier`). This makes adding metrics a data change rather than a schema migration, and makes percentile/z-score computation uniform. Materialize wide views per pack for fast table rendering.
11. **Metric metadata is a product feature.** `definition`, `formula`, `what it predicts`, `stability (year-over-year r)`, and `league distribution` shipped alongside every metric powers the info sheets, the empty states, the onboarding, and the search — and is what makes 200 columns feel like a library instead of a wall.

---

## 12. The top ~30 metrics (build these first)

Ranked by (predictive value × user comprehensibility × free computability).

| # | Metric | Positions | Why it earns a slot | Tier |
|---|---|---|---|---|
| 1 | Snap share (+ 3-week trend) | All | Earliest, most reliable signal of a usage change | B |
| 2 | Route participation / routes run | WR/TE/RB | The denominator that makes receiving analysis honest | B/C |
| 3 | Target share | WR/TE/RB | Most predictive raw receiving opportunity metric | A |
| 4 | Targets per route run (TPRR) | WR/TE | Separates role from talent | B/C |
| 5 | Air yards + air yards share | WR/TE | Ceiling and downfield opportunity | A |
| 6 | WOPR = 1.5×TS + 0.7×AY% | WR/TE | Best single composite opportunity number | A |
| 7 | aDOT | WR/TE/QB | Sticky role classifier; contextualizes catch rate | A |
| 8 | Yards per route run (YPRR) | WR/TE | Best single receiving efficiency metric | B/C |
| 9 | Opportunity share (carries+targets) | RB | Bellcow identification | A |
| 10 | Weighted opportunities / share | RB | Corrects touches for target value | A |
| 11 | Green-zone (inside 10) and goal-line (inside 5) carries | RB/QB | 74% / 68% of rushing TDs originate there | A |
| 12 | Red-zone + end-zone target share | WR/TE | Best pass-catcher TD-equity signal | A |
| 13 | Expected fantasy points (xFP) per game | All | Opportunity-only baseline; beats actual FPPG as a predictor | A |
| 14 | Fantasy points over expected (FPOE) | All | The regression flag that drives buy-low/sell-high | A |
| 15 | Fantasy points per opportunity / per route run | RB / WR-TE | Efficiency per chance | A/B |
| 16 | EPA per dropback | QB | Best QB value metric | A |
| 17 | CPOE | QB | Sticky accuracy; beats completion % | A |
| 18 | QB designed rush attempts + scramble rate + rushes inside the 5 | QB | The biggest QB fantasy differentiator | A |
| 19 | RACR | WR/TE | Air-yard conversion efficiency; pairs with aDOT | A |
| 20 | YAC over expected (YACOE) | WR/TE/RB | Isolates post-catch skill | B |
| 21 | Rush yards over expected per attempt (RYOE/att) | RB | Separates back from blocking | B |
| 22 | Rushing success rate | RB/QB | Consistency and coach trust | A |
| 23 | Team PROE | Team | Play-calling identity; drives all passing volume | A |
| 24 | Situation-neutral pace + plays per game | Team | Volume environment | A |
| 25 | Implied team total (from spread + total) | Team | Best free weekly context number | B |
| 26 | Schedule-adjusted DvP by position + ROS/playoff SOS | Matchup | The matchup layer users expect | A |
| 27 | Boom rate / bust rate / start-worthy rate + floor(p10)/ceiling(p90) | All | Turns a season average into a decision | A |
| 28 | Spike weeks / best-ball points added | All (best ball) | Correct value framing for the fastest-growing format | A |
| 29 | VORP with league-settings-aware replacement level | All | Makes rankings format-correct (superflex, TE-P) | A |
| 30 | Adjusted line yards (rebuilt on free PBP) | RB context | Strongest documented O-line→RB fantasy link | C→A |
| 31 | Practice participation trend (DNP→LP→FP) | All | Highest-value non-stat field in the app | B |
| 32 | Wind speed + roof | Matchup | Only weather variable that reliably matters; free in schedules | B |
| 33 | Defensive snap share + box-safety rate (IDP) | IDP | The underserved-market differentiator | B/C |

---

## 13. Suggested build order

**Phase 1 — Credible core (all tier A/B):** box scores, snap share, target/carry share, air yards, WOPR, aDOT, RZ/GZ/GL usage, FPPG under a configurable scoring profile, weekly game logs, basic leaderboards with sort + position/week-range/min-snaps filters, percentile bars on player pages.

**Phase 2 — Analytics layer:** EPA/success/CPOE, xFP and FPOE (own model), boom/bust/floor/ceiling/start-worthy, team PROE/pace/points-per-drive, implied totals + weather + roof, schedule-adjusted DvP and ROS/playoff SOS, opportunity-vs-efficiency scatter, sparklines.

**Phase 3 — Comparison and personalization:** head-to-head compare tray, radar/bar toggle, saved presets, custom column views, league import (Sleeper API), VORP/auction values under league settings, buy-low/sell-high index, trade evaluator.

**Phase 4 — Format depth:** full IDP, best-ball spike-week suite, dynasty age/contract/draft-capital and rookie profiles, DFS salary/value/ownership/leverage, DFS and best-ball correlation tools.

**Phase 5 — Charting-dependent (build own models rather than license):** routes run and YPRR from participation data, pressure rate from FTN charting, adjusted line yards rebuilt from PBP, WR/CB matchup matrix, man/zone splits.

---

## 14. Known pitfalls to design around

- **Per-game denominators.** "Games played" vs "games active" vs "games with an opportunity" produce materially different FPPG. Expose the choice; default to games with ≥1 opportunity.
- **Rate stats without minimums.** Always enforce a min-opportunity filter on efficiency leaderboards, and surface the sample size in the row.
- **Catch rate without aDOT** and **YPC without box counts** are actively misleading. Pair them by default.
- **Naive fantasy-points-allowed** is dominated by schedule. Always ship the schedule-adjusted version, and label the unadjusted one clearly.
- **Small-sample TD rates** drive most bad fantasy takes. Put expected TDs next to actual TDs everywhere.
- **Snap counts lag** (PFR posts Wednesday). Design the weekly refresh cadence around it and timestamp the data.
- **Participation data availability varies by season** (NGS pre-2023, FTN 2023+, sometimes delayed). Route-based metrics need a graceful "not yet available" state.
- **Scoring format leakage.** Any cached derived metric must be keyed by scoring profile or recomputed; stale PPR-only boom rates in a half-PPR league destroy trust.
- **Red/green color coding** fails for a large share of a male-skewed audience. Use a blue↔orange diverging ramp plus position-in-bar encoding.
- **Radar charts** mislead when axes are reordered or scales are mixed; use percentile-only axes in a fixed order, or default to bar strips.

---

## 15. Sources

- [PlayerProfiler — Advanced Stats Glossary of Terms](https://www.playerprofiler.com/terms-glossary/)
- [Action Network — Weighted Opportunity Rating explained](https://www.actionnetwork.com/education/weighted-opportunity-rating-definition-how-find-boosted-receiver-production-with-this-stat)
- [Football Nation USA — What Is WOPR in Fantasy Football?](https://www.footballnationusa.com/post/wopr-fantasy-football-weighted-opportunity-rating)
- [PFF — Metrics that Matter: Yards per route run](https://www.pff.com/news/fantasy-football-metrics-that-matter-yards-per-route-run)
- [FantasyPros — What is Yards Per Route Run (YPRR)?](https://www.fantasypros.com/2023/06/yards-per-route-run-yprr-fantasy-football/)
- [4for4 — Air Yards Explained](https://www.4for4.com/2018/preseason/air-yards-explained)
- [NFL Analytic — Air Yards, aDOT & YAC Explained](https://nflanalytic.com/explainer-air-yards.html)
- [NFL Next Gen Stats — Glossary](https://nextgenstats.nfl.com/glossary)
- [4for4 — Understanding NFL Next Gen Stats in 2026](https://www.4for4.com/2026/preseason/understanding-nfl-next-gen-stats-2026)
- [NFL.com — Next Gen Stats: Intro to Expected Rushing Yards](https://www.nfl.com/news/next-gen-stats-intro-to-expected-rushing-yards)
- [NFL.com — Next Gen Stats: Intro to Expected Yards After Catch](https://www.nfl.com/news/next-gen-stats-intro-to-expected-yards-after-catch-0ap3000000983644)
- [nfelo — Over Expected Metrics Explained: CPOE, RYOE, YACOE](https://www.nfeloapp.com/analysis/over-expected-explained-what-are-cpoe-ryoe-and-yacoe/)
- [Brad Congelio — Introduction to NFL Analytics with R: Analytics Dictionary](https://bradcongelio.com/nfl-analytics-with-r-book/a1-nfl-analytics-dictionary.html)
- [nflreadr — nflverse Data Update and Availability Schedule](https://nflreadr.nflverse.com/articles/nflverse_data_schedule.html)
- [nflverse/nfl_data_py on GitHub](https://github.com/nflverse/nfl_data_py)
- [Establish The Run — Pass Rate Over Expectation](https://establishtherun.com/pass-rate-over-expectation/)
- [Fantasy Life — What is Pass Rate Over Expectation?](https://www.fantasylife.com/articles/fantasy/what-is-pass-rate-over-expectation-how-to-use-proe-in-fantasy-football)
- [Sharp Football — Expected Fantasy Points Explained](https://www.sharpfootballanalysis.com/fantasy/expected-fantasy-points/)
- [Sharp Football — Expected Fantasy Points Tool](https://www.sharpfootballanalysis.com/fantasy/expected-fantasy-points-tool/)
- [ESPN — Introducing OFP: Opportunity-adjusted fantasy points](https://www.espn.com/fantasy/football/story/_/id/24318831/fantasy-football-introducing-ofp-opportunity-adjusted-fantasy-points-forp-fantasy-points-replacement-player)
- [PFF — The fantasy impact of carries in the 'green zone'](https://www.pff.com/news/fantasy-football-the-fantasy-impact-of-carries-in-the-green-zone)
- [Sharp Football — NFL Red-Zone Stats Vs. Expectation](https://www.sharpfootballanalysis.com/fantasy/nfl-red-zone-stats-expectations-wide-receivers/)
- [Sharp Football — NFL Implied Team Totals Tool](https://www.sharpfootballanalysis.com/fantasy/nfl-implied-team-totals-tool/)
- [The Game Snap — NFL Implied Team Totals: Formula, Examples, Fantasy Uses](https://thegamesnap.com/articles/how-to-use-vegas-implied-team-totals-a-2026-fantasy-and-betting-playbook)
- [Advanced Football Analytics — Weather Effects on Passing](http://www.advancedfootballanalytics.com/2012/01/weather-effects-on-passing.html)
- [Fantasy Life — Does Wind Matter in Fantasy Football?](https://www.fantasylife.com/articles/best-ball/does-wind-matter-in-fantasy-football)
- [PFF — The Factors: Wind's influence on completion percentage](https://www.pff.com/news/fantasy-football-the-factors-week-14-2017)
- [Draft Sharks — Does Offensive Line Performance Impact Fantasy Football Production?](https://www.draftsharks.com/article/offensive-line-performance-fantasy-production)
- [ESPN — Introducing run block win rate and run stop win rate](https://www.espn.com/nfl/story/_/id/29813062/introducing-new-nfl-run-blocking-run-stopping-stats-how-run-block-win-rate-run-stop-win-rate-work)
- [ESPN — New NFL blocking and pass-rush win rate formulas](https://www.espn.com/nfl/story/_/id/49672562/nfl-new-pass-block-push-rush-win-rates-formula-takeaways-analytics)
- [PFF — The effect of shadow coverage on fantasy football](https://www.pff.com/news/fantasy-football-the-effect-of-shadow-coverage-on-fantasy-football)
- [FTN — Cornerback Shadow Coverage Matrix](https://ftnfantasy.com/nfl/shadow-coverage-matrix)
- [Fantasy Points — WR/CB Matchup Report](https://www.fantasypoints.com/nfl/reports/wr-cb-matchups)
- [Establish The Run — Defense vs. Position Rankings](https://establishtherun.com/establish-the-run-nfl-dvp/)
- [FantasyPros — Fantasy Football Strength of Schedule](https://www.fantasypros.com/nfl/strength-of-schedule.php)
- [FantasyTeamAdvice — NFL Playoff Strength of Schedule](https://fantasyteamadvice.com/nfl/strength-of-schedule/playoff)
- [FantasyPros — Value-Based Drafting: VORP, VOLS, VONA](https://www.fantasypros.com/2025/06/fantasy-football-draft-strategy-value-based-drafting-vorp-vols-vona/)
- [Subvertadown — Guide to VBD baselines: VOLS vs VORP vs Man-games](https://subvertadown.com/article/guide-to-understanding-the-different-baselines-in-value-based-drafting-vols-vs-vorp-vs-man-games-and-beer-)
- [FantasyPros — Boom or Bust reports](https://www.fantasypros.com/nfl/reports/boom-bust-qb.php)
- [Establish The Run — Best Ball 101](https://establishtherun.com/best-ball-101/)
- [Draft Sharks — Best Ball Draft Strategy](https://www.draftsharks.com/kb/best-ball-draft-strategy)
- [PFF — Aging curves by position](https://www.pff.com/news/fantasy-football-metrics-that-matter-aging-curves-by-position)
- [4for4 — Production Curves: Positional Breakouts, Prime Years, and Falloffs by Age](https://www.4for4.com/2025/preseason/production-curves-positional-breakouts-prime-years-and-falloffs-age)
- [The Dynasty Edge — NFL Age Curve Study: EPA Trends by Position](https://thedynastyedge.com/2025/06/21/nfl-age-curve-study-epa-trends-by-position-rb-wr-te-qb-fantasy-football-analysis-2014-2024/)
- [Fantasy Six Pack — IDP Scoring Systems Explained](https://fantasysixpack.net/idp-scoring-systems-explained/)
- [Athlon — Understanding Advanced Stats for IDP Leagues](https://athlonsports.com/fantasy/fantasy-football-idp-leagues-advanced-stats-explained)
- [IDP Guru — What Is IDP Fantasy Football?](https://idpguru.com/2026/05/what-is-idp-fantasy-football/)
- [Stokastic — NFL DFS Leverage: The Game Theory Behind Big GPP Wins](https://www.stokastic.com/articles/nfl-dfs/nfl-dfs-leverage-game-theory)
- [FTN — NFL Ownership Projections](https://ftnfantasy.com/dfs/nfl/ownership-projections)
- [Pro Football Network — How To Play Superflex TE Premium Leagues](https://www.profootballnetwork.com/how-to-play-superflex-te-premium-leagues/)
- [Fantasy Strategy Guide — TE Premium League Strategy](https://fantasystrategyguide.com/te-premium-strategy)
- [RotoViz — The RotoViz Screener](https://www.rotoviz.com/the-rotoviz-screener/)
- [RotoViz — Tools](https://www.rotoviz.com/tools-2/)
- [4for4 — Fantasy Football Tools](https://www.4for4.com/fantasy-football-tools)
- [PyMC Labs — Radar Plots Must Die](https://www.pymc-labs.com/blog-posts/radar-plots-must-die)
- [StatsBomb — Revisiting Radars](https://blogarchive.statsbomb.com/articles/soccer/revisiting-radars/)
- [Opta Analyst — Introducing Opta Player Radars](https://theanalyst.com/articles/introducing-opta-radars-compare-players)
- [Pencil & Paper — Data Table Design UX Patterns & Best Practices](https://www.pencilandpaper.io/articles/ux-pattern-analysis-enterprise-data-tables)
- [UX Planet — Best Practices for Usable and Efficient Data Tables](https://uxplanet.org/best-practices-for-usable-and-efficient-data-table-in-applications-4a1d1fb29550)
- [RotoWire — Decoding Injury Reports](https://www.rotowire.com/football/article/injury-analysis-decoding-injury-reports-as-week-1-begins-132952)
- [Fantasy Injury Report Authority — Injury Report Accuracy and Reliability](https://fantasyinjuryreportauthority.com/injury-report-accuracy-and-reliability/)
- [FTN — Guide To Football Analytics](https://ftnfantasy.com/nfl/guide-to-football-analytics)
- [FantasyPros — Fantasy Football Stats Analysis Glossary](https://www.fantasypros.com/fantasy-football-stats-analysis-glossary/)
