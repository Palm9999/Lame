# Free NFL Data Sources for a No-Paywall Fantasy Football Android App

Research date: **2026-09-22** (NFL 2026 season, Week 3 per `api.sleeper.app/v1/state/nfl`).
All URLs marked **[verified]** were live-tested with `curl` on this date; byte sizes are actual.

---

## 0. Executive summary

| Verdict | Sources |
|---|---|
| **Ship it** (permissive license, safe to redistribute) | nflverse/nflverse-data (CC BY 4.0), api.weather.gov (US public domain), FantasyFootballCalculator ADP, The Odds API (paid-terms, free 500 credits/mo) |
| **Use, but read the terms** (free but non-commercial-only) | Sleeper API, Open-Meteo, MySportsFeeds |
| **Grey-area / unofficial** (works, no license grant, can break or get you blocked) | ESPN hidden endpoints, NFL NGS public endpoints |
| **Per-user OAuth only, no redistribution** | Yahoo Fantasy Sports API |
| **Do not scrape / do not redistribute** | Pro-Football-Reference, PFF, PlayerProfiler, 4for4, FantasyPros (direct) |

**The core recommendation:** build a **GitHub Actions → normalized JSON/SQLite → GitHub Releases** pipeline. The Android client should almost never talk to a raw upstream source. See §3.

---

## 1. Source-by-source catalog

### 1.1 nflverse / nflfastR / nfl_data_py — **the foundation**

The single most important source. `nflverse-data` is a GitHub repo whose *releases* are the data store; GitHub Actions scrape and rebuild them on a schedule. `nflreadr` (R), `nflreadpy`/`nfl_data_py` (Python) are just download clients — **you don't need them**, the release assets are plain HTTP.

**Access pattern (no auth, no key, no rate limit beyond GitHub's CDN):**

```
https://github.com/nflverse/nflverse-data/releases/download/<TAG>/<FILE>.<csv|csv.gz|parquet|rds|qs>
```

**[verified] live asset URLs and real sizes (2026-09-22):**

| Release tag | Example asset URL | HTTP | Size |
|---|---|---|---|
| `pbp` | `.../download/pbp/play_by_play_2026.parquet` | 200 | 2.5 MB (season in progress) |
| `pbp` | `.../download/pbp/play_by_play_2025.parquet` | 200 | 20.3 MB (full season) |
| `injuries` | `.../download/injuries/injuries_2026.csv` | 200 | 49 KB |
| `injuries` | `.../download/injuries/injuries_2025.csv` | 200 | 696 KB |
| `depth_charts` | `.../download/depth_charts/depth_charts_2026.csv` | 200 | **52.3 MB** |
| `snap_counts` | `.../download/snap_counts/snap_counts_2026.csv` | 200 | 269 KB |
| `rosters` | `.../download/rosters/roster_2026.csv` | 200 | 945 KB |
| `weekly_rosters` | `.../download/weekly_rosters/roster_weekly_2025.csv` | 200 | 15.4 MB |
| `players` | `.../download/players/players.csv` | 200 | 7.2 MB |
| `players` | `.../download/players/players.parquet` | 200 | **3.4 MB** |
| `stats_player` | `.../download/stats_player/stats_player_week_2026.csv` | 200 | 992 KB |
| `nextgen_stats` | `.../download/nextgen_stats/ngs_passing.csv.gz` | 200 | 593 KB (all seasons, one file per stat type) |
| `pfr_advstats` | `.../download/pfr_advstats/advstats_week_rec_2025.csv` | 200 | 396 KB |
| `espn_data` | `.../download/espn_data/qbr_week_level.csv` | 200 | 2.5 MB |
| `combine` | `.../download/combine/combine.csv` | 200 | 894 KB |
| `draft_picks` | `.../download/draft_picks/draft_picks.csv` | 200 | 1.7 MB |
| `schedules` | `.../download/schedules/games.csv` | 200 | 2.2 MB (all games since 1999, incl. results, spread_line, total_line, roof, surface, temp, wind) |
| `ftn_charting` | `.../download/ftn_charting/ftn_charting_2025.csv` | 200 | 8.1 MB |
| `stats_team`, `teams`, `trades`, `players_components` | same pattern | 200 | small |

**Gotchas found:**
- `nextgen_stats` is **not** partitioned by season. It is `ngs_passing`, `ngs_rushing`, `ngs_receiving` (all-seasons). `ngs_2025_passing.csv.gz` → **404**.
- `contracts` is not under `nflverse-data`; OverTheCap contract data lives in a separate ffverse/nflverse release. `.../contracts/historical_contracts.csv` → **404**.
- `depth_charts_2026.csv` is 52 MB because post-2024 it emits an ISO8601-timestamped snapshot per team per scrape rather than one row per week. **Never** put this on a phone raw. Dedupe to latest-per-team server-side → a few hundred KB.
- Prefer `.parquet` over `.csv` for your backend job (players: 3.4 MB vs 7.2 MB).

**Update cadence (from nflverse's published automation schedule):**

| Dataset | Cadence |
|---|---|
| `schedules` | **every 5 minutes during the season** (includes live scores/results) |
| `pbp`, `stats_player`, `stats_team` | nightly after each game day + additional in-game runs; raw data within ~15 min of game end |
| `rosters` | daily 07:00 UTC |
| `injuries` | daily 07:00 UTC during season |
| `depth_charts` | daily 07:00 UTC year-round |
| `snap_counts` | 00/06/12/18 UTC during season |
| `pfr_advstats` | 00/06/12/18 UTC during season |
| `ftn_charting` | 00/06/12/18 UTC during season (charted within 48h of game) |
| `nextgen_stats` | nightly 03:00–05:00 ET during season |

**License:**
- Repo-level `LICENSE.md` on `nflverse/nflverse-data` = **CC BY 4.0** (attribution, notice of modification, link to license).
- Certain datasets are documented as **CC BY-SA 4.0** with required attribution to **"FTN Data via nflverse"** — this covers `ftn_charting` and `participation` (2023+; pre-2023 participation is "NFL NextGenStats via nflverse"). CC BY-**SA** is *share-alike*: if you redistribute an adapted version of those specific tables, your redistributed table must also be BY-SA. This does **not** viralize your app's source code (it's a data license, not GPL), but it does mean any derived FTN-based dataset you publish must carry BY-SA.
- The `nflfastR` / `nflreadr` **software** packages are MIT — irrelevant if you only consume the release files.

**Reliability:** Very high. Automated GH Actions, multi-year track record, served off GitHub's release CDN. Failure mode is a stale file (a scraper broke), not a 500. Always read the release asset's `updated_at`/`Last-Modified` and surface staleness in the app.

**Caveat worth flagging (see §2.4):** `snap_counts`, `pfr_advstats`, `combine`, `draft_picks` are **scraped from Pro-Football-Reference**. nflverse relicenses them CC BY; PFR's ToS says you may not build tools on scraped PFR data. You are one step removed, which is the normal industry posture, but it is not a clean chain of title.

---

### 1.2 Sleeper API — **best free live/fantasy-layer source**

**Base:** `https://api.sleeper.app/v1/` — **no API key, no OAuth, read-only.**

**[verified] endpoints:**

| Endpoint | Result |
|---|---|
| `GET /v1/state/nfl` | 200 — `{"week":3,"season":"2026","season_type":"regular",...}` |
| `GET /v1/players/nfl` | 200 — **14.66 MB** (docs claim ~5 MB; it is now ~15 MB) |
| `GET /v1/players/nfl/trending/add?limit=25&lookback_hours=24` | 200 — `[{"count":1643670,"player_id":"11435"},...]` |
| `GET /v1/players/nfl/trending/drop` | 200 |
| `GET /v1/user/<username\|user_id>` | league linking |
| `GET /v1/user/<user_id>/leagues/nfl/<season>` | user's leagues |
| `GET /v1/league/<league_id>` · `/rosters` · `/users` · `/matchups/<week>` · `/transactions/<round>` · `/traded_picks` · `/winners_bracket` · `/losers_bracket` | full league read |
| `GET /v1/league/<league_id>/drafts` · `/v1/draft/<draft_id>` · `/v1/draft/<draft_id>/picks` | draft data |
| `GET /v1/user/<user_id>/drafts/nfl/<season>` | user drafts |

**Rate limit:** documented as "stay under **1000 API calls per minute**, otherwise you risk being IP-blocked." Sleeper explicitly says the players dump should be fetched **at most once per day** and trending **no more than once per day**.

**No ADP endpoint.** Sleeper exposes draft picks, not an aggregated ADP feed. You can compute a pseudo-ADP from public mock drafts, or use FantasyFootballCalculator (§1.10).

**Terms — the single biggest legal caveat:** Sleeper's docs state the API is "**free to use for non-commercial purposes**" and that commercial use requires a direct licensing conversation. A genuinely free, ad-free app is defensible as non-commercial. **The moment you add ads, IAP, affiliate links, or a "pro" tier, you are commercial and must contact Sleeper.** There is no public paid tier — it's an email negotiation.

**Reliability:** Good, but no SLA and no status page commitment. It is a consumer product's backend; it can and does change.

---

### 1.3 ESPN undocumented endpoints

ESPN retired its official public developer API in 2014. Everything below is the reverse-engineered JSON that powers espn.com and the ESPN Fantasy app. **No key, no auth for public data.**

**[verified] live on 2026-09-22:**

| Endpoint | HTTP | Size | Notes |
|---|---|---|---|
| `https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard` | 200 | 280 KB | live scores, odds, situation, win prob |
| `https://site.api.espn.com/apis/site/v2/sports/football/nfl/injuries` | 200 | **9.0 MB** | league-wide injury designations (huge, cache server-side) |
| `https://site.api.espn.com/apis/site/v2/sports/football/nfl/news` | 200 | 53 KB | |
| `https://sports.core.api.espn.com/v3/sports/football/nfl/athletes?limit=100` | 200 | 42 KB | paginated athlete index |
| `https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl/seasons/2026/players?scoringPeriodId=0&view=players_wl` | 200 | 13 KB | fantasy player list |
| `https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl/seasons/2026/segments/0/leaguedefaults/3?view=kona_player_info` | 200 | 214 KB | **ESPN projections + % owned + % started** — `leaguedefaults/3` = standard PPR; drive it with an `x-fantasy-filter` JSON header |
| `https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl/seasons/<season>/segments/0/leagues/<league_id>?view=mTeam&view=mRoster&view=mMatchup` | — | — | private leagues need `espn_s2` + `SWID` cookies |

Note: the old `fantasy.espn.com` v3 host has become unreliable for JSON; **`lm-api-reads.fantasy.espn.com` is the working host in 2026**.

**Rate limits:** none published. Empirically forgiving for low volume; aggressive polling gets WAF/Akamai blocks. Always send a browser-like `User-Agent`, back off on 429/403.

**License/terms:** **None granted.** ESPN's Terms of Use permit personal, non-commercial use of the site and prohibit unauthorized automated extraction and redistribution. There is no developer agreement to sign because there is no public developer program. This is textbook grey area.

**Reliability:** Medium. Schema changes without notice; hosts get renamed (as `lm-api-reads` shows). Never make it a hard dependency.

---

### 1.4 Yahoo Fantasy Sports API

- **Base:** `https://fantasysports.yahooapis.com/fantasy/v2/...`; docs at `developer.yahoo.com/fantasysports/guide/`.
- **Auth:** OAuth 2.0, per-user. You register an app, users log in with Yahoo. No app-level/server-to-server key for league data.
- **Cost:** free.
- **Rate limits:** not published as a number. Yahoo states it monitors usage and may throttle or suspend for excessive use or performance impact.
- **Terms** (`legal.yahoo.com` → Yahoo Fantasy Sports APIs Terms of Use / Yahoo Developer API ToU): license is **worldwide (excluding India, Japan, Taiwan), non-exclusive, non-sublicensable**. Non-sublicensable is the operative word — **you cannot redistribute Yahoo data to third parties or cache it into a public dataset.** You also may not separate the underlying data from the API or reverse engineer it.
- **Verdict:** perfectly fine as a *per-user league import* feature (the user authorizes, you show them their own league). **Not** a source for your projections/stats pipeline.

---

### 1.5 NFL's own endpoints

- `developer.nfl.com` → **401** on public fetch. Not a self-serve program; partner/club access.
- NFL Next Gen Stats: docs at `docs.ngs.nfl.com` (prod API `api.ngs.nfl.com`) and `clubs-docs.ngs.nfl.com`. Onboarding is via the "NGS Support Team" — **invite/partner only, not free self-serve.**
- `nextgenstats.nfl.com` has unauthenticated JSON routes that back the public site (they require a token minted by the page). Same grey-area category as ESPN, but **more** exposed because the NFL is an aggressive trademark/IP enforcer.
- **Practical answer:** you get NGS for free *through nflverse* (`nextgen_stats` release), which is the sanctioned redistribution path. Don't hit NFL directly.

---

### 1.6 FantasyPros

FantasyPros now runs a real tiered API program (`fantasypros.com/api-data/`):

| Tier | Price | What you get | Restriction |
|---|---|---|---|
| Free | $0 | all endpoints against **sample data**, live explorer, docs | **non-production only** |
| Premium | $8.99/mo (bundled with FantasyPros HOF) | production keys: ECR rankings, projections, players, news, injuries (NFL/MLB/NBA/NHL) | **personal use license only** |
| Commercial | custom / contact sales | commercial license **with redistribution rights**, highest limits, historical/bulk | requires signed agreement |

ECR is aggregated from 130+ experts with tiers and best/worst/stdev.

**Scraping:** not permitted for building a product; their ToS and the tier structure make the intent explicit (redistribution rights are literally the thing the Commercial tier sells).

**The back door — and its risk:** `nflreadr::load_ff_rankings()` pulls FantasyPros ECR from **DynastyProcess.com's** GitHub mirror (`github.com/dynastyprocess/data`, e.g. `files/db_fpecr.parquet`, `files/values-players.csv`, `files/db_playerids.csv`), updated weekly. This is free and trivially fetchable. **But DynastyProcess does not hold a redistribution license from FantasyPros** — it's a community mirror of scraped ECR. Shipping it in a public Play Store app is exactly the scenario FantasyPros' Commercial tier exists to prevent. *(Note: this session's network proxy blocked `dynastyprocess/*` so I could not byte-verify those files today; the nflreadr docs confirm the source and weekly cadence.)*

**Recommendation:** treat FantasyPros ECR/ADP as **do-not-ship**. Build your own consensus from free-to-redistribute inputs (see §3.4).

---

### 1.7 Tank01 (RapidAPI)

- `rapidapi.com/tank01/api/tank01-nfl-live-in-game-real-time-statistics-nfl`, plus `tank01-fantasy-stats`.
- **Free (Basic) tier: hard cap 1,000 requests/month, no credit card.**
- Paid: Pro $10/mo (1,000/day), Ultra $25/mo (15,000/day), Mega $100/mo (500,000/day); $0.01 per overage request.
- Data: fantasy projections, play-by-play, betting odds incl. player props, ADP, rosters, schedules, standings, news.
- **Verdict:** 1,000/mo is ~33/day. That is *only* viable if a **single backend job** pulls it, never the client. Even then: a daily projections + odds pull for 32 teams blows the cap fast. Treat as a nice-to-have enrichment on a tight budget, not a primary. RapidAPI terms + Tank01's own terms govern; redistribution of a paid provider's data into a public app is not clearly licensed at the Basic tier.

---

### 1.8 SportsDataIO / MySportsFeeds / API-Sports

| Provider | Free offer | Real limits | Verdict for a shipping free app |
|---|---|---|---|
| **SportsDataIO** | "Free Trial" API key, never expires, no CC | Full endpoint surface but **scoped/limited data** (trial replay data, not live production feeds for every league). Real NFL production data is a paid enterprise contract. | **No.** The free trial is a sales funnel. Production NFL = $$$$. |
| **MySportsFeeds** | Genuinely **free for non-commercial** (developers, students, hobbyists) | XML/JSON/CSV; NFL schedules, scores, boxscores, standings, PBP, lineups, injuries, DFS, odds. Commercial tiers priced by latency (non-live → near-realtime). | **Only if the app stays truly non-commercial.** Same trap as Sleeper: ads/IAP → commercial tier. Good fallback/redundancy source. |
| **API-Sports** (`api-sports.io`, American Football v1) | **100 requests/day**, all endpoints, all competitions (NFL + NCAA), no CC. Resets 00:00 UTC. Logo/image requests don't count. | Games, events, team/player game stats, season player stats, standings. No fantasy projections. | 100/day is backend-only. Usable for a nightly schedule/score reconciliation job. Not a primary. |

---

### 1.9 Weather

| Source | Cost | Limits | License | Verdict |
|---|---|---|---|---|
| **api.weather.gov** (US NWS) | Free, **no key** | "Reasonable rate limits," not published; 429 clears in ~5s. Requires a descriptive `User-Agent` header like `(myapp.com, contact@myapp.com)`. Direct-from-client is fine; **proxies are more likely to hit the limit**. | **US Government work → public domain.** "All of the information presented via the API is intended to be open data, free to use for any purpose." | ✅ **Best choice.** Covers every US stadium. Zero legal risk. |
| **Open-Meteo** | Free tier | <10,000 calls/day, 5,000/hour, 600/minute | Data under **CC BY 4.0**; must link to Open-Meteo next to displayed data | ⚠️ **Free tier is explicitly NON-COMMERCIAL.** Their terms define commercial as "operating websites or apps that have **subscriptions or display advertisements**." A free app with ads → commercial → paid plan required. |
| **Open-Meteo self-hosted** | Free | your infra | Server code is **Apache-2.0** (`github.com/open-meteo/open-meteo`) | Escape hatch if you want Open-Meteo's model blend without the commercial license — but it's real infra, not zero-cost. |

**International games** (London/Munich/Madrid/São Paulo/Mexico City) aren't covered by NWS. For ~5 games/season, either hardcode, use Open-Meteo for just those, or accept "dome/unknown."

**Also free:** nflverse `schedules/games.csv` already carries `roof`, `surface`, `temp`, `wind` for *completed* games — useful for model training, useless for forecasting.

---

### 1.10 Odds / betting lines / implied team totals

| Source | Free tier | Credit accounting | Notes |
|---|---|---|---|
| **The Odds API** (`the-odds-api.com`) | **Starter: 500 credits/month, free, no CC.** Permanent plan, not a trial. | 1 request = 1 sport × 1 market × 1 region, returns all matching games. Player props via per-event endpoints cost more. Use **ETag / `If-None-Match`** → 304 costs **zero credits**. | All 70+ sports, 40+ books (DK, FD, BetMGM, Pinnacle, Bet365), all markets incl. player props on the free plan. **Historical odds snapshots (2020+) are paid-only** ($30/mo 20K credits). Odds refresh as often as every 30s; response carries `updated_at`. |
| **nflverse `schedules/games.csv`** | Free, CC BY 4.0 | n/a | Contains `spread_line` and `total_line` per game — **closing lines, historical + current week.** [verified] 2.2 MB. This alone gives you implied team totals for free with a clean license. |
| Tank01 | within its 1,000/mo | n/a | includes props |

**Budget math for The Odds API free tier:** 1 request/hour for `americanfootball_nfl` h2h+spreads+totals (one region) ≈ 720/month — **over budget**. Poll every 2 hours during the season (≈360/mo), or poll hourly only Thu–Mon and lean on ETags. Player props for 16 games × 1 market = 16 credits per sweep; you can afford roughly one or two full prop sweeps per week. **Plan for ~2h cadence + ETag, and cache everything.**

**Implied team total** = `total/2 ± spread/2`. You do not need a props feed to compute it — `games.csv` suffices.

---

### 1.11 ADP

| Source | Access | Verdict |
|---|---|---|
| **FantasyFootballCalculator** | `https://fantasyfootballcalculator.com/api/v1/adp/<ppr\|standard\|half-ppr\|2qb\|dynasty>?teams=12&year=2026&position=all` — **[verified] 200**, returns `{status, meta:{type,teams,rounds,total_drafts,start_date,end_date}, players:[{player_id,name,position,team,adp,adp_formatted,times_drafted,high,low,stdev,bye}]}`. No key. | ✅ **Best free ADP.** Real 2026 data returned today (112 drafts in the trailing week). Public documented JSON API, no key, generous. Attribute them. Keep volume low (one pull/day). |
| Sleeper | no ADP endpoint; derive from public draft picks | Possible but laborious |
| FantasyPros ADP | paid/redistribution-restricted | ❌ |
| Tank01 ADP | within 1,000/mo | ⚠️ |

---

### 1.12 Paywalled / off-limits

| Source | Situation |
|---|---|
| **Pro-Football-Reference / Sports-Reference** | Rate limit: >**20 requests/minute** → session jailed up to a day (FBref/Stathead: >10/min). Bot-suspected traffic blocked 1 hour. ToU clause 5 prohibits using site content "to create any database, archive, or other data store that competes with or constitutes a material substitute for the services or data stores offered on the Site or by the Site's Data Providers," prohibits automated access that adversely impacts the site, and prohibits use for training/prompting AI models. Their own summary: *"you should not create websites or tools based on data you scrape from Sports Reference."* They also note many datasets are **licensed from third parties and cannot be redistributed at all.** They do concede *"copyright law is clear that facts cannot be copyrighted."* → **Do not scrape directly.** Consume PFR-derived tables only via nflverse, and understand §2.4. |
| **PFF** | API is **not** included in PFF+ consumer subs; API access is a B2B contract (`b2b.pff.com`). PFF Pro users get a CLI/developer API for **Personal Use** only. Premium Stats can be exported as CSV by subscribers. ToU forbids providing PFF data or derivatives to generative AI/ML services. → **Paywalled, no redistribution, do not ship.** |
| **PlayerProfiler** | Subscription product; no public free API; ToS restricts redistribution. → ❌ |
| **4for4** | Subscription; projections/rankings are the paid product. → ❌ |

---

## 2. Licensing and legal reality check

### 2.1 The clean tier — safe to ship, safe to redistribute

| Source | License | Obligation |
|---|---|---|
| nflverse-data (most tables) | **CC BY 4.0** | Credit nflverse, link the license, state that you modified it. Put it in an in-app "Data & Licenses" screen. |
| nflverse `ftn_charting` / `participation` (2023+) | **CC BY-SA 4.0** | Credit **"FTN Data via nflverse."** If you *publish* a derived table from these, that table must also be BY-SA. Using them inside a model whose outputs you ship is fine. |
| api.weather.gov | **Public domain** (US Gov) | Send an identifying `User-Agent`. Courtesy attribution. |
| FantasyFootballCalculator | Public API, no stated license | Attribute; keep volume low. Low risk, but not a written grant — email them for comfort. |
| The Odds API | Commercial ToS, free Starter plan | Paying $0 under their published plan is a licensed use. Read their ToS before redistributing odds as a bulk feed vs. displaying them. |

### 2.2 The "non-commercial" trap — **this is your #1 landmine**

Three key free sources restrict to non-commercial use, and each defines commercial to include **advertising**:

- **Sleeper:** "free to use for non-commercial purposes"; commercial requires direct licensing.
- **Open-Meteo:** commercial = "operating websites or apps that have subscriptions or display advertisements."
- **MySportsFeeds:** free tier is explicitly for non-commercial developers/students/hobbyists.

Your brief says "no paywall." **No paywall ≠ non-commercial.** If the monetization plan is *ever* ads, affiliate links, sponsorships, or a premium tier, you break all three simultaneously. Decide this up front:
- **Truly free, no ads, no IAP** → all three are usable as-is.
- **Any monetization** → replace Open-Meteo with api.weather.gov (free, no restriction), email Sleeper for a license, drop MySportsFeeds.

### 2.3 Grey area — works, but no license and no recourse

ESPN and nfl.com/NGS unofficial endpoints. Realistic risk profile:
- **Likely:** IP block or a silent schema change that breaks your app mid-season.
- **Possible:** cease-and-desist if your app becomes visible and is seen as competing.
- **Unlikely but real:** DMCA/takedown against the Play listing.

Mitigation: proxy through *your* backend (one IP, easy to swap, easy to rate-limit), never ship ESPN URLs in the APK, and make every ESPN-derived field optional so the app degrades rather than breaks.

### 2.4 The PFR chain-of-title problem

nflverse's `snap_counts`, `pfr_advstats`, `combine`, `draft_picks` are scraped from Pro-Football-Reference and relicensed CC BY. PFR's ToU forbids exactly that. **nflverse cannot grant rights it doesn't have.** You are a downstream good-faith recipient of a CC BY dataset, which is the normal posture of the entire fantasy analytics ecosystem — but it is not airtight. Practical stance:
- Snap counts and PFR advanced stats are high-value; accept the risk, it is small and diffuse.
- **Never** hit `pro-football-reference.com` yourself.
- If PFR ever forces nflverse to drop those tables, have a fallback (ESPN/NGS for snap-adjacent usage).

### 2.5 Two risks nobody in your brief mentioned — flagging explicitly

**(a) NFL trademarks — the most likely reason you actually get pulled.**
- Do **not** put "NFL," "Super Bowl," or a team name in the app title, package name, or icon. The NFL enforces aggressively and Play Store trademark complaints are processed fast.
- nflverse's `teams` release conveniently gives you `team_logo_espn` / `team_logo_wikipedia` URLs. **Those are NFL-owned marks hosted by ESPN.** Shipping NFL team logos in a third-party app is trademark infringement and hotlinking someone else's CDN. Use **team abbreviations + team colors** (`team_color`, `team_color2` from the same file — colors aren't protectable as used here) or commission your own generic marks.
- Player names and stats are facts — fine. Player *photographs* are not — do not hotlink ESPN/NFL headshots either (the `players` table has headshot URLs; resist).
- Safe naming pattern: a neutral brand, with "for fantasy football" in the description, plus a clear "not affiliated with or endorsed by the National Football League" disclaimer.

**(b) Google Play's Real-Money Gambling policy — matters only if you add odds + ads.**
Per Play Console policy, a non-gambling app may **not** provide "gambling or real money game, lottery, or tournament support or companion functionality (for example, functionality that assists with wagering, payouts, **sports score/odds/performance tracking**, or management of participation funds)" *if* it also runs gambling ads or simulated-gambling content. And a "dedicated sports odds tracker app containing integrated gambling ads linking to a sports betting site" is explicitly called out as **prohibited**.
- **Displaying spreads/totals for fantasy context, with no real-money wagering and no gambling ads, does not trigger the gambling-app requirements.** The trigger is facilitating real-money wagering for a prize.
- **The combination is what kills you:** odds display + gambling ads (including an AdMob mediation network serving sportsbook creatives) = a policy strike. If you ever monetize with ads, **blocklist gambling ad categories in AdMob.**
- Keep odds framed as fantasy inputs ("implied team total: 24.5") rather than as a betting UI with book logos and deep links to sportsbooks.

### 2.6 Would any of this get the app pulled or sued?

| Risk | Likelihood | Severity |
|---|---|---|
| NFL team logos / "NFL" in the app name | **High** | Listing takedown |
| Gambling ads + odds tracking together | **Medium-high** (if you monetize) | Policy strike → suspension |
| Redistributing FantasyPros ECR via DynastyProcess in a public app | **Medium** | C&D; easy to avoid |
| Sleeper/Open-Meteo non-commercial breach after adding ads | **Medium** | API cutoff; C&D |
| Shipping ESPN-derived data | **Low-medium** | Breakage first, C&D second |
| nflverse PFR-derived tables | **Low** | Diffuse, upstream |
| nflverse core (pbp, stats, rosters, schedules) | **Very low** | CC BY is a real grant |

---

## 3. Recommended data architecture

### 3.1 The answer to the key question: **do not let the Android client hit upstream sources.**

Evidence from today's measurements:

| Raw upstream payload | Size |
|---|---|
| `depth_charts_2026.csv` | 52.3 MB |
| Sleeper `/v1/players/nfl` | 14.7 MB |
| ESPN `/nfl/injuries` | 9.0 MB |
| `players.csv` | 7.2 MB |
| `play_by_play_2025.parquet` | 20.3 MB |

A user on cellular will not download 50 MB of CSV, Android has no good native parquet reader, and CSV parsing of a 52 MB file on a mid-range phone is a multi-second, memory-thrashing operation. Direct client access also means:
- N users × M sources = you get IP-banned by ESPN/Sleeper on their terms, not yours.
- API keys (The Odds API) in the APK = extracted within a day and your 500 credits are gone.
- No ability to fix a source break without shipping a new APK through Play review.

**→ Build a thin ingestion backend. It costs $0.**

### 3.2 Concrete zero-cost stack

```
┌─────────────────────────────────────────────────────────────┐
│ GitHub Actions (public repo = free unlimited standard runners)│
│                                                              │
│  cron: */15 * * * *   live.yml    → scores, injuries, odds   │
│  cron: 30 11 * * *    daily.yml   → rosters, depth, ADP,     │
│                                     snaps, projections        │
│  cron: 0 9 * * TUE    weekly.yml  → pbp, ngs, pfr, history   │
│                                                              │
│  Python: httpx + polars/duckdb                               │
│   1. fetch nflverse .parquet (small) + Sleeper + odds + NWS  │
│   2. join on gsis_id / sleeper_id via players_components     │
│   3. dedupe depth_charts to latest-per-team                  │
│   4. compute derived features (target share, snap%, aDOT,    │
│      implied team total, red-zone rate, model projections)   │
│   5. emit compact artifacts (below)                          │
└──────────────────────────┬──────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────┐
│ Publish to GitHub Releases (tag: `live`, `daily`, `season`)  │
│  - unlimited bandwidth in practice, Fastly-backed, free      │
│  - supports ETag / If-None-Match / Last-Modified             │
│  - versioned by tag; instant rollback                        │
│  Optional: Cloudflare R2 (10 GB + 1M ops/mo free) in front   │
└──────────────────────────┬──────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────┐
│ Android: OkHttp (ETag-aware) → Room/SQLite → Compose UI      │
│  offline-first: render from Room always, refresh in bg       │
│  WorkManager: live 15m (game days only), daily 1×, season 1× │
└─────────────────────────────────────────────────────────────┘
```

**Why GitHub Releases over the alternatives:**

| Option | Free tier | Fit |
|---|---|---|
| **GitHub Releases** | Release asset bandwidth is not metered like Pages; Fastly-backed; ETag support | ✅ **Primary.** Best free static distribution for versioned data blobs. |
| **GitHub Pages** | **100 GB/month soft limit**, 1 GB site size | ⚠️ Fine for a small manifest/JSON, but the 100 GB cap and GitHub's AUP language about CDN-style use make it second choice for bulk. |
| **Cloudflare Workers** | 100,000 requests/day, 10 ms CPU/invocation; KV 1 GB + 100K reads/day; **R2 10 GB + 1M ops/month, zero egress fees** | ✅ **Best companion.** Put a Worker in front for (a) proxying The Odds API so the key stays server-side, (b) request coalescing, (c) serving R2 objects. 100K req/day supports a real user base. |
| **Cloudflare R2** | 10 GB storage, 1M Class-A ops/mo, **no egress charge** | ✅ Ideal object store if you outgrow Releases. |
| **Supabase** | 500 MB DB, 5 GB bandwidth/mo, 1 GB file storage, 50K MAU, **2 active projects, paused after 7 days of no API requests** | ⚠️ The 7-day auto-pause is disqualifying for a seasonal app (Feb–Aug dead period = paused project). Use it only if you need auth/user data, and keep it warm with a cron ping. |
| **GitHub Actions** | Unlimited standard-runner minutes on **public** repos | ✅ Keep the pipeline repo public — that also satisfies CC BY attribution naturally. |

### 3.3 Artifact design — what the client actually downloads

Emit **gzipped JSON** (Android decompresses transparently via OkHttp) or a prebuilt **SQLite file** for the big historical bundle. Target sizes:

| Artifact | Contents | Target size | Refresh |
|---|---|---|---|
| `manifest.json` | version stamps + ETags for every other artifact | < 2 KB | every poll |
| `live.json.gz` | current-week scores, game state, active injury designations (changed only), inactives, kickoff weather, spread/total/implied totals | 30–80 KB | 15 min on game days |
| `players.json.gz` | id-mapped player index: name, team, pos, status, headshot-free | 300–500 KB | daily |
| `week_projections.json.gz` | your model's per-player projections + floor/ceiling + matchup context | 100–200 KB | daily |
| `adp.json.gz` | FFCalculator ADP by format | 40 KB | daily (preseason: hourly) |
| `usage.json.gz` | last-4-week snap %, route %, target share, carry share, RZ looks | 150 KB | daily |
| `history.sqlite.gz` | 3–5 seasons of weekly player stats + schedule + team context, pre-aggregated (**not** raw PBP) | 5–15 MB | weekly, one-time download |
| `depth.json.gz` | deduped latest depth chart per team | 50 KB | daily |

**Never ship raw play-by-play to the phone.** Aggregate server-side. The 52 MB depth chart file becomes 50 KB after deduping; that ratio is the whole argument for a backend.

### 3.4 Building projections without licensed projections

You cannot ship FantasyPros/PFF/4for4 numbers. Build your own from CC BY inputs — this is also a better product story ("our model," not "someone else's numbers"):

- **Volume model:** snap %, route participation, target share, carry share, red-zone touches ← `pbp` + `snap_counts` + `stats_player`
- **Efficiency:** aDOT, YAC over expected, EPA/play, success rate ← `pbp` + `nextgen_stats`
- **Game environment:** implied team total (from `games.csv` `spread_line`/`total_line`, or The Odds API for live lines), pace, pass rate over expected ← `pbp`
- **Availability:** `injuries` + `depth_charts` + Sleeper player `status` + ESPN inactives
- **Weather:** api.weather.gov point forecast for each outdoor stadium at kickoff; `roof` from `games.csv` tells you when to skip
- **Market sanity check:** FFCalculator ADP (preseason), Sleeper trending adds/drops (in-season sentiment)

### 3.5 Android client rules

1. **Offline-first, always.** Room is the source of truth for the UI. Network writes to Room; the UI never awaits the network.
2. **ETag everything.** `If-None-Match` → 304 → zero bytes, zero Odds-API credits.
3. **Poll `manifest.json` only.** Fetch a payload only when its version stamp changed.
4. **WorkManager with constraints:** `NetworkType.CONNECTED`, `requiresBatteryNotLow`. Live tier only during a game window (derive windows from `games.csv` kickoff times — no reason to poll Tuesday at 3am).
5. **No secrets in the APK.** Any keyed API (The Odds API) is proxied by the Cloudflare Worker.
6. **Show staleness.** "Injuries as of 11:42 AM" — nflverse breakages are silent, and a visible timestamp turns a bug into a known state.
7. **Every field nullable.** A source going down should gray out one card, not crash a screen.

---

## 4. Data freshness tiers

| Tier | Cadence | Data | Source | Implementation |
|---|---|---|---|---|
| **T0 — Live** | 1–15 min, game windows only | Scores & game state | nflverse `schedules` (updates **every 5 min in-season**) or ESPN scoreboard | GH Actions `*/15`; client polls manifest |
| | | **Inactives** (90 min pre-kick) | ESPN scoreboard / NGS; nflverse does not carry inactives fast | Highest-value live field for lineup decisions |
| | | Injury designation changes | nflverse `injuries` is **daily 07:00 UTC only** → too slow for game day; supplement with ESPN `/nfl/injuries` | Diff against yesterday, push only deltas |
| | | Kickoff weather | api.weather.gov point forecast | Refresh T-24h, T-3h, T-1h |
| | | Odds / implied totals | The Odds API (every 2h + ETag, to stay in 500 credits) | Worker-proxied |
| **T1 — Daily** | 1×/day, ~11:30 UTC (after nflverse's 07:00 roster/injury run) | Rosters, injury report, depth charts | nflverse `rosters`, `injuries`, `depth_charts` | Dedupe depth charts here |
| | | Snap counts, PFR advanced stats | nflverse `snap_counts`, `pfr_advstats` (updated 00/06/12/18 UTC) | |
| | | Your projections | computed | Rebuild after roster/injury ingest |
| | | ADP | FantasyFootballCalculator | Bump to hourly Aug–early Sep |
| | | Trending adds/drops | Sleeper `/trending/add` + `/drop` (Sleeper says ≤1×/day) | |
| | | Player index | Sleeper `/v1/players/nfl` (14.7 MB — **once daily, backend only**) + nflverse `players` | |
| **T2 — Weekly** | Tue 09:00 UTC (after MNF settles) | Full play-by-play | nflverse `pbp` | Aggregate → never ship raw |
| | | Next Gen Stats | nflverse `nextgen_stats` (nightly 3–5am ET) | |
| | | Weekly player/team stats | nflverse `stats_player`, `stats_team` | |
| | | ESPN QBR | nflverse `espn_data/qbr_week_level.csv` | |
| | | FTN charting | nflverse `ftn_charting` (48h post-game) | BY-SA — attribute FTN |
| | | Model retrain | your pipeline | |
| **T3 — Seasonal** | Preseason / on change | Combine, draft picks, historical seasons, teams | nflverse `combine`, `draft_picks`, `pbp` archives, `teams` | Ship as one `history.sqlite.gz` |

**Note the gap:** the highest-stakes fantasy moment — Sunday 11:30am ET inactives — has **no** clean free source. nflverse injuries refresh once a day at 07:00 UTC. You will need ESPN (grey) or accept being an hour behind. This is the one place where the grey-area dependency is genuinely hard to avoid.

---

## 5. Comparison matrix

| Source | Cost | Auth | Rate limit | License | Redistributable? | Freshness | Reliability | Ship it? |
|---|---|---|---|---|---|---|---|---|
| nflverse-data | $0 | none | GitHub CDN | CC BY 4.0 (BY-SA for FTN/participation) | **Yes, w/ attribution** | 5 min – daily | High | ✅ **Core** |
| Sleeper | $0 | none | 1000/min | Non-commercial only | No | live–daily | Good | ✅ if non-commercial |
| api.weather.gov | $0 | none | unpublished, generous; UA required | Public domain | **Yes** | hourly | High | ✅ |
| FFCalculator ADP | $0 | none | unpublished | none stated | Grey (attribute) | daily | Good | ✅ low volume |
| The Odds API | $0 | key | **500 credits/mo** | Commercial ToS | Display yes, bulk no | 30 s | High | ✅ w/ Worker proxy |
| ESPN hidden API | $0 | none | unpublished | **None** | No | live | Medium | ⚠️ optional fields only |
| Yahoo Fantasy | $0 | OAuth2/user | throttled at Yahoo's discretion | Non-sublicensable | **No** | live | High | ⚠️ user-import only |
| Open-Meteo | $0 | none | 10K/day, 5K/hr, 600/min | CC BY 4.0, **non-commercial tier** | Yes w/ link | hourly | High | ⚠️ no-ads only |
| MySportsFeeds | $0 | key | non-commercial tier | Non-commercial | No | varies | Good | ⚠️ no-ads only |
| API-Sports NFL | $0 | key | **100/day** | Proprietary | No | live | Good | ⚠️ backend only |
| Tank01 (RapidAPI) | $0 | key | **1,000/month** | Proprietary | No | live | Medium | ⚠️ marginal |
| SportsDataIO | trial | key | scoped trial | Proprietary | No | live | High | ❌ paid in practice |
| FantasyPros | $0 free tier | key | free = **sample data, non-prod** | Personal/Commercial tiers | Commercial tier only | daily | High | ❌ at $0 |
| DynastyProcess FP ECR mirror | $0 | none | GitHub | unlicensed mirror | **No** | weekly | Good | ❌ legal risk |
| NFL NGS direct | — | partner | — | Proprietary | No | live | High | ❌ (get via nflverse) |
| Pro-Football-Reference | $0 | none | **20 req/min, jail 1 day** | ToU forbids tool-building | **No** | daily | High | ❌ never scrape |
| PFF | paid | key | B2B contract | Proprietary, personal-use API | **No** | daily | High | ❌ |
| PlayerProfiler / 4for4 | paid | — | — | Proprietary | **No** | daily | High | ❌ |

---

## 6. Attribution block to ship in-app

> **Data sources**
> Play-by-play, player statistics, rosters, injury reports, depth charts, snap counts, Next Gen Stats and schedules from **nflverse** (github.com/nflverse/nflverse-data), licensed **CC BY 4.0**. Data has been modified: aggregated, deduplicated and reformatted.
> Charting data provided by **FTN Data via nflverse**, licensed **CC BY-SA 4.0**.
> Player metadata and trending data from the **Sleeper API** (docs.sleeper.com).
> Average draft position from **FantasyFootballCalculator.com**.
> Weather forecasts from the **U.S. National Weather Service** (api.weather.gov) — public domain.
> Betting lines from **The Odds API** (the-odds-api.com).
>
> This application is not affiliated with, endorsed by, or sponsored by the National Football League, any NFL club, ESPN, Yahoo, or Sleeper.

---

## 7. Sources

- [nflverse/nflverse-data](https://github.com/nflverse/nflverse-data) · [releases](https://github.com/nflverse/nflverse-data/releases) · [LICENSE.md](https://github.com/nflverse/nflverse-data/blob/main/LICENSE.md)
- [nflreadr function reference](https://nflreadr.nflverse.com/reference/index.html) · [update schedule](https://nflreadr.nflverse.com/articles/nflverse_data_schedule.html) · [load_ff_rankings](https://nflreadr.nflverse.com/reference/load_ff_rankings.html) · [load_participation](https://nflreadr.nflverse.com/reference/load_participation.html)
- [nflverse/nfl_data_py](https://github.com/nflverse/nfl_data_py)
- [Sleeper API docs](https://docs.sleeper.com/)
- [ESPN hidden API endpoint list (gist)](https://gist.github.com/nntrn/ee26cb2a0716de0947a0a4e9a157bc1c) · [Public-ESPN-API](https://github.com/pseudo-r/Public-ESPN-API) · [ffscrapr espn_getendpoint](https://ffscrapr.ffverse.com/articles/espn_getendpoint.html)
- [Yahoo Fantasy Sports API guide](https://developer.yahoo.com/fantasysports/guide/) · [Yahoo Developer API Terms of Use](https://legal.yahoo.com/us/en/yahoo/terms/product-atos/apiforydn/index.html)
- [NFL Developer Portal](https://developer.nfl.com/) · [NFL NGS API docs](https://docs.ngs.nfl.com/)
- [FantasyPros API tiers](https://www.fantasypros.com/api-data/)
- [Tank01 NFL on RapidAPI](https://rapidapi.com/tank01/api/tank01-nfl-live-in-game-real-time-statistics-nfl) · [Tank01](https://www.tank01.com/)
- [SportsDataIO free trial](https://sportsdata.io/free-trial) · [SportsDataIO NFL docs](https://sportsdata.io/developers/api-documentation/nfl)
- [MySportsFeeds data feeds](https://www.mysportsfeeds.com/data-feeds/) · [pricing](https://www.mysportsfeeds.com/feed-pricing/)
- [API-Sports NFL](https://api-sports.io/sports/nfl) · [docs](https://api-sports.io/documentation/nfl/v1)
- [Open-Meteo terms](https://open-meteo.com/en/terms) · [licence](https://open-meteo.com/en/licence) · [open-meteo source (Apache-2.0)](https://github.com/open-meteo/open-meteo)
- [NWS API docs](https://www.weather.gov/documentation/services-web-api) · [community FAQs](https://weather-gov.github.io/api/general-faqs)
- [The Odds API](https://the-odds-api.com/) · [NFL odds](https://the-odds-api.com/sports/nfl-odds.html) · [FAQ](https://theoddsapi.com/faq)
- [Sports-Reference: SR and Data Use](https://www.sports-reference.com/data_use.html) · [bot traffic policy](https://www.sports-reference.com/bot-traffic.html) · [429 page](https://www.sports-reference.com/429.html)
- [PFF Developer API](https://developer.pff.com/) · [PFF terms](https://www.pff.com/terms) · [Does API access come with a subscription?](https://profootballfocussupport.zendesk.com/hc/en-us/articles/32094827302163-Does-API-access-come-with-a-subscription)
- [Google Play: Real-Money Gambling, Games, and Contests](https://support.google.com/googleplay/android-developer/answer/9877032?hl=en) · [common violations for gambling apps](https://support.google.com/googleplay/android-developer/answer/13381106?hl=en)
- [GitHub Pages limits](https://docs.github.com/en/pages/getting-started-with-github-pages/github-pages-limits)
- [Cloudflare Workers pricing](https://developers.cloudflare.com/workers/platform/pricing/)
- [Supabase pricing](https://supabase.com/pricing)
