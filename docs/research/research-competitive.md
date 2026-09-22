# Competitive Analysis — Free, Stat-Dense NFL Fantasy Prediction App (Android)

**Researched:** 2026-09-22 · **Method:** web search + direct fetch of vendor pricing/policy pages
**Confidence key:** ✅ verified on vendor/policy page · 🟡 secondary source (review site, blog) · ⚠️ inference / needs re-check

> **Caveat up front:** Reddit was unreachable from this environment (both `reddit.com` WebFetch and the `.json` API returned hard blocks / HTTP 403). Section 3's feature-gap findings are therefore built from Play Store / App Store review aggregators, vendor changelogs, and review blogs rather than primary Reddit threads. Everything in §3 marked 🟡 should be re-validated with a manual Reddit pass before it drives roadmap decisions.

---

## 0. The single biggest landscape change: NFL Fantasy is dead ✅

On **July 16, 2026**, the NFL and ESPN jointly announced that **the NFL no longer operates a season-long fantasy game**. ESPN is now "the official Fantasy game of the NFL." The NFL Fantasy app is sunsetting and users are being migrated to ESPN via `espn.com/importnfl`. This is the final step of the 2025 deal where ESPN acquired NFL Network + the NFL's fantasy assets and the league took an equity stake in ESPN.

**Implications for this product:**
1. The host-platform field is now effectively **four**: ESPN, Yahoo, Sleeper, CBS (+ the enthusiast tier: MyFantasyLeague, Fleaflicker).
2. ESPN just absorbed millions of displaced users into an app whose Android rating is the *worst* of the majors (~3.8) 🟡. There is a large, freshly-irritated user base right now.
3. Do **not** build an NFL.com league-sync integration. Build ESPN, Yahoo, Sleeper.
4. Migration churn = a natural acquisition moment for a companion app.

Sources: ESPN Press Room / Walt Disney Company joint FAQ; Scoutcast summary.

---

## 1. Competitor Landscape

### 1a. Master comparison table

| Product | Category | Core features | Free tier | Paid price (verified) | Android quality | Biggest weakness |
|---|---|---|---|---|---|---|
| **Sleeper** | Platform | League hosting, best-in-class draft UX, in-app chat, dynasty/best-ball, picks/DFS-style games | Hosting fully free; cosmetics paid | Cosmetics only; wagering products separate | ~4.5 Play 🟡 — best of the majors | Thin *analysis* layer: "little to no player outlooks, rarely game recaps"; hard-to-find waiver order; no custom draft-board positions; commissioners can't see real names; recent builds "slow and clunky"; notifications broken/duplicated 🟡 |
| **ESPN Fantasy** | Platform | Hosting, ESPN content integration, now official NFL game | Hosting free, ad-supported | Some editorial (e.g. Love/Hate) behind **ESPN+** | ~3.8 Play 🟡 — weakest major | Game-day crashes and live-scoring lag; "ads are too much"; app slow to open; UI described as user-unfriendly; no public API at all |
| **Yahoo Fantasy** | Platform | Hosting, deep league formats, long-tenured tools | Hosting free, ad-supported | Paid contests; "pay-to-play" drift complaints 🟡 | ~4.1 Play 🟡 | Redesigns that removed information "in favor of appearance"; intrusive full-screen ads; scoring delays; login failures reported during playoffs 🟡 |
| **NFL Fantasy** | Platform | — | — | — | ~3.9 Play 🟡 | **Shutting down (2026).** Historic complaints: scoring errors, thin toolset |
| **CBS Sports Fantasy** | Platform | Strongest commissioner controls, serious-league features | Basic hosting free | **League hosting fees reported >$100**, charged non-obviously; leagues "expire" mid-season pending payment 🟡 | ~3.9 Play 🟡 | Dated UI, steep learning curve, live-scoring freezes, surprise billing |
| **FantasyPros** (My Playbook / Draft Wizard) | Analytics/tools | Expert-consensus rankings (ECR), league sync, Start/Sit, trade & waiver analysis, Draft Assistant, DFS optimizer, Coach AI | Basic ECR + some tools | ✅ **PRO** $3.99/mo annual → $11.99/mo monthly (2 leagues, no Draft Assistant); **MVP** $5.99–$16.99/mo (10 leagues, Draft Assistant w/ live sync, dynasty/keeper); **HOF** $8.99–$22.99/mo (50 leagues, Coach AI, Waiver Planner, DFS optimizer, **API access**); Betting add-on +$6.99/mo | Mixed → poor 🟡 | "Incredibly buggy," "not optimized for phones" (bottom-of-screen taps collide with nav bar), login loops, "clunky… website version is superior," features that "used to be free" now paywalled 🟡. **This is the incumbent to attack.** |
| **PlayerProfiler** | Analytics | Advanced/athletic metrics, college data, dynasty rankings, player profiles | Articles, podcasts, player profile pages — **with ads** | ✅ **$135/season** All-In; **$45/season** per module (Dynasty Deluxe / DFS Dominator / Draft Kit / Data Analysis / Player Rankings). Ad-free only with paid | Web-first; no strong native Android story | Modular pricing means the "full picture" is $135; heavy ads on free tier; desktop-oriented data density |
| **4for4** | Analytics | Paulsen's projections, Draft Hero, LeagueSync, start/sit, IDP, salary-cap values | Marketing pages only | ✅ **Lite $39/season**, **Pro $59/season**, **DFS $149/season**; betting tiers $349–$649/yr | Web-first | Almost nothing free; no meaningful mobile product |
| **RotoViz** | Analytics | Evidence-based apps/screeners (Best Ball Win Rate Explorer, GLSP, etc.) | Articles | ⚠️ Pricing page returned 403; historically ~$50–$120/season — **re-verify** | Web-only | Power-user tools that are borderline unusable on a phone; near-total paywall |
| **Fantasy Points Data** | Analytics/data | Charting-derived data suite (routes, coverage, pressure), projections | Free articles / newsletter; occasional "free week" promos | ✅ **Basic $60** ($48 early bird); **Fantasy Pro $299** ($239 EB); **Fantasy Bundle $499** ($399 EB). Betting split into its own sub for 2026 | Web-only | Pricing is pro-analyst tier; effectively inaccessible to casual users |
| **FantasyCalc** | Dynasty values | Trade values fit by **regression to trades that actually closed** in Sleeper/Fleaflicker/MFL — a *market* price, not a consensus price | ✅ Free site + **public API, no key** | Free | Web/PWA | ⚠️ API is **non-commercial**, must not substantially replace the site, requires attribution link (per secondary sources — direct ToS page did not render; **verify before depending on it**) |
| **KeepTradeCut (KTC)** | Dynasty values | Crowdsourced keep/trade/cut voting → dynasty & redraft values; trade DB | ✅ Free site | Free | Web | **No API, and scraping is "expressly forbidden by our Terms and Conditions."** Highest-recognition dynasty number is also the least legally available one |
| **DynastyProcess** | Dynasty values | Free Trade Calculator, open data repo (`github.com/dynastyprocess/data`) | ✅ Fully free + open data | Free | Web | Values derived from FantasyPros dynasty ranks (consensus, slower-moving); low brand awareness |
| **Dynasty Daddy** | Dynasty tools | Sleeper league sync + KTC-derived values, power rankings, trade calc, player comparison, Elo | ✅ Free web app, source on GitHub (`G-Sher/dynasty-daddy`) | Free | Angular web app | Sleeper-only; depends on **scraping KTC** (see KTC ToS above) — a structural legal/continuity-of-service risk; license not declared in README ⚠️ |
| **Dynasty Nerds** (DynastyGM) | Dynasty | Trade calc, rankings, league sync, Data Hub, player shares, trade browser, film room, podcast | ✅ Real free tier: **3 preview trades/day, preview-only rankings, 1 league sync, top-5 results** in Data Hub/Player Shares/Trade Browser/Free Agents | ✅ **$6.99/mo or $69.99/yr** (one sub covers app + rankings + podcast + tools) | Native Android app exists | Free tier is deliberately crippled to a teaser; hard paywall on the actual numbers |
| **Footballguys** | Analytics/content | Draft Dominator, League Dominator, rankings, dynasty/IDP/auction, DFS optimizers | Free articles | ✅ **PRO $59.99/yr** ($12.99/mo); **ELITE $89.99/yr** ($19.99/mo); **ALL-ACCESS $199.99/yr** | Web-first | Content-heavy, tool-light by modern standards; dated presentation |
| **Rotoworld / NBC** | News | Player news blurbs — historically *the* free industry utility | Free | — | — | **Effectively gone.** Rebranded NBC Sports Edge (2021), Edge branding dropped, content folded into NBC Sports. **The canonical free "player news blurb" feed no longer exists — this is an unclaimed gap.** |
| **PFF** | Analytics | Player grades, premium stats, fantasy tools, mock draft sim, draft kit | Free articles + some rankings | ✅ **PFF+ $9.99/mo** or **$99.99–$119.99/yr**; **PFF Pro $199.99/yr** (adds API/CLI, extra data) | Web-first, app thin | Grades are proprietary and *cannot be replicated or licensed cheaply*; everything meaningful is gated |
| **Establish The Run** | Analytics/DFS | Projections, in-season content, best-ball, DFS | Free articles | ⚠️ Pricing not captured this pass — historically ~$99–$399/yr tiers; **re-verify** | Web-first | Subscription-only, analyst-brand dependent |
| **Sharp Football** | Analytics/betting | Warren Sharp's draft kit, packages, betting analysis | Free articles | ⚠️ Package pricing page not captured; sells per-package draft kits + subs; **re-verify** | Web-only | Betting-leaning; no fantasy app |
| **RotoGrinders** | DFS | LineupHQ optimizer (150 lineups), ownership projections, SimLabs, premium content | Free contests/forums historically | ✅ **$74.99/mo or $425/yr** (NFL Premium) | Web-first | Cheapest *serious* DFS option and still $425/yr |
| **Stokastic** | DFS | Contest simulation (Sims), optimizers, ownership | Limited free Sims trial | ✅ **NFL Core $149.95/mo · $549.95/yr**; **Max $229.95/mo · $749.95/yr**; **MVP $349.95/mo · $1,299.95/yr** (annuals rising to $649.95/$899.95/$1,499.95) | Web-first | Price is brutal even by DFS standards |
| **SaberSim** | DFS | Simulation-based optimizer | $7 / 7-day trial | ✅ **Starter $97/mo · Pro $197 · Ultimate $297** | Web | Pure pro-grinder tool |
| **FantasyCruncher** | DFS | Optimizer, late swap, historical slates | Limited | ✅ **$29 / $59 / $99 per month** | Web | Dated UI |
| **DFS Army** | DFS | Optimizers, sims, cheat sheets, coaching/Discord | Content teasers | ⚠️ Not captured; membership-club model | Web/Discord | Community-gated, opaque pricing |

### 1b. Genuinely free / open-source in this space ✅

This is a short list, and it's the raw material for the product:

| Project | What it gives you | License / terms |
|---|---|---|
| **nflverse / nflfastR / nflreadr** | Play-by-play back to **1999**, rosters, schedules, IDs, weekly player stats — the single most important free dataset in football analytics | Open source, free, permissive; releases distributed as data assets |
| **ffverse: `ffscrapr`** | Unified R client for **MFL, Sleeper, Fleaflicker, ESPN** league APIs; handles auth, rate limiting, caching | Open source — also the best available *documentation* of how each platform's league API actually behaves |
| **ffverse: `ffopportunity`** | Expected Fantasy Points (xFP) via xgboost on nflverse PBP | Open source — **a free, credible "advanced metric" you can ship without licensing anyone** |
| **ffverse: `ffsimulator`** | Bootstrap season simulation from historical rankings + nflfastR | Open source — free playoff-odds / season-sim engine |
| **DynastyProcess `data`** | Open dynasty values, player ID crosswalks | Open data repo |
| **Dynasty Daddy** | Working reference implementation of Sleeper sync + value tooling | Source public; license not declared ⚠️ |
| **Sleeper API** | Users, leagues, rosters, drafts, transactions, players, trending | No token needed; **free for non-commercial only** (see §4) |
| **Fantasy Football Calculator ADP API** | Free ADP, **explicitly free for personal AND commercial use**, attribution requested | The one ADP source you can use commercially without negotiation |

**Key insight:** nflverse + ffopportunity + ffscrapr + FFC ADP is enough to build a genuinely stat-dense product with *zero* data licensing cost. What you cannot get free: PFF grades, Fantasy Points charting data, Next Gen Stats, real-time injury/news wire, and reliable live in-game stat feeds.

---

## 2. The Paywall Map

### 2a. Almost universally paywalled (high-value targets)

| Capability | Who gates it | Typical price to unlock |
|---|---|---|
| **DFS lineup optimizer / simulations** | FantasyPros (HOF only), 4for4 (DFS $149), RotoGrinders ($425/yr), Stokastic ($550–$1,300/yr), SaberSim ($97–$297/mo), FantasyCruncher ($29–$99/mo), Footballguys (ELITE) | **$150 – $1,300/yr** |
| **Multi-league sync beyond 1–2 leagues** | FantasyPros (2 → 10 → 50 leagues by tier), Dynasty Nerds (1 free), 4for4 (Pro), Draft Sharks | **$48 – $204/yr** |
| **Live draft assistant with sync** | FantasyPros (MVP+, *explicitly excluded from PRO*), 4for4 (Pro), Footballguys, PFF Pro, Draft Sharks | **$60 – $200/yr** |
| **Dynasty / keeper support at all** | FantasyPros (MVP+), Footballguys (ELITE), Dynasty Nerds, PlayerProfiler (Dynasty Deluxe $45) | **$45 – $90/yr** |
| **Advanced/charted metrics** (YPRR, routes, pressure, aDOT, dominator, athletic scores) | PlayerProfiler ($45–135), Fantasy Points ($60–$499), PFF ($100–$200), RotoViz | **$45 – $499/yr** |
| **Historical / multi-season data & backtesting** | RotoViz, Fantasy Points, PFF Pro, FantasyCruncher | **$100 – $500/yr** |
| **API / data export** | FantasyPros (HOF tier only), PFF (Pro tier only) | **$108 – $200/yr** |
| **Ad-free experience** | PlayerProfiler (paid only), 4for4 ("No Ads" is a *listed Lite benefit*), most analytics sites | **$39+/yr** |
| **Waiver-wire planning / FAAB guidance** | FantasyPros (Waiver Planner = HOF), Footballguys, Dynasty Nerds | **$60 – $108/yr** |
| **Trade analyzer with your actual rosters** | FantasyPros (PRO+), Dynasty Nerds (3/day free), Fantasy Life | **$48 – $70/yr** |
| **Salary-cap / auction values** | 4for4 (Pro), FantasyPros (MVP), Footballguys (ELITE) | **$59 – $90/yr** |
| **IDP** | 4for4 (Noonan's IDP = Pro), Footballguys (ELITE) | **$59 – $90/yr** |

**Stacked cost of a "complete" toolkit today:** FantasyPros MVP ($72) + PlayerProfiler All-In ($135) + Fantasy Points Basic ($60) + Dynasty Nerds ($70) ≈ **$337/yr**, and that still excludes DFS and PFF.

### 2b. Free essentially everywhere (no differentiation available)

- Basic league hosting (ESPN, Yahoo, Sleeper, CBS basic)
- Expert consensus rankings at a shallow depth (FantasyPros ECR top-N)
- Box-score/basic stats, standings, schedules
- Articles, podcasts, newsletters, YouTube — the content layer is a commodity
- ADP from Fantasy Football Calculator
- Crowdsourced dynasty values from KTC (free to *view*, not to *use programmatically*)
- FantasyCalc trade values (free, incl. API — non-commercially)
- DynastyProcess trade calculator and open data

### 2c. Where a free app creates real, felt value

Ranked by (perceived price of the paywalled version) × (feasibility with free data):

1. **Unlimited league sync** — FantasyPros charges tier-by-tier for league *count*. Free-unlimited is a pure, legible win. **(Highest value, highest legal risk — see §4.)**
2. **Advanced metrics, free** — xFP/opportunity share/target share/route participation computed from nflverse are legitimately competitive with paid "advanced stats" pages, at $0 licensing.
3. **Full historical data + backtesting** — 1999→present PBP is free; nobody exposes it in a mobile UI.
4. **Dynasty trade values** — buildable free from FantasyCalc + DynastyProcess (with attribution), no KTC scraping.
5. **Ad-free-feeling experience** — merely *not* running Yahoo/ESPN-grade interstitials is a differentiator users will name in reviews.
6. **Export (CSV/JSON)** — gated behind FantasyPros HOF and PFF Pro. Trivially free to offer.
7. **Full custom scoring** — every user has a weird league; most tools support "standard / half / full PPR" and stop.

---

## 3. Feature Gap Analysis 🟡

*(Reddit inaccessible — see caveat. Sourced from Play/App Store review aggregators, vendor changelogs, review blogs.)*

### Gap 1 — "I need five different sites" (the aggregation gap)
The §2c table *is* the evidence: rankings live at FantasyPros, advanced metrics at PlayerProfiler/Fantasy Points, dynasty values at KTC/FantasyCalc, ADP at FFC, your actual roster at ESPN/Sleeper. Nobody joins them. **A single sortable table where "my roster" and "advanced metrics" and "trade value" and "ADP" are columns in the same grid does not exist on Android.**

### Gap 2 — Mobile tables are genuinely broken
- FantasyPros Android: "not optimized for mobile phones," users literally **cannot tap items at the bottom of the screen** because controls collide with the system nav bar 🟡.
- PlayerProfiler, RotoViz, Fantasy Points, 4for4, Footballguys: web-first, desktop-density tables. Wide stat grids on a 390px viewport are the sector's universal failure.
- NFL Fantasy shipped a changelog entry fixing "a bug with sorting on stat pages" — sorting was broken in a shipping major-platform app.
- **This is the most under-defended surface in the entire market.** Frozen first column, horizontal scroll with momentum, long-press column header to sort, pinned comparison row, column chooser — all table stakes on desktop, absent on mobile.

### Gap 3 — Ads are the #1 named complaint on the biggest apps
- ESPN: "the ads are too much" 🟡
- Yahoo Sports family: ads that "jump to full screen and scroll back to the top" 🟡
- PlayerProfiler: ads on the entire free tier, removed only by paying
**"No interstitials, ever" is a marketable promise**, not just an absence.

### Gap 4 — Reliability at exactly the wrong moment
ESPN/CBS/Yahoo all show the same pattern: 1★/2★ reviews **spike every Sunday in season** 🟡 — crashes, live scoring freezing, scores contradicting the TV, stat corrections hours late. A companion app that is a *static, fast, cached* reference during game windows inherits trust the platforms are losing.

### Gap 5 — Sleeper's analysis vacuum
Sleeper has the best UX and the worst content layer: "little to no player outlooks, rarely game recaps" 🟡. Sleeper users are the most design-literate, most engaged segment, have a **free public API**, and have no native analysis tool. **Sleeper users are the ideal beachhead.**

### Gap 6 — The Rotoworld-shaped hole
The free, terse, universally-trusted player-news blurb feed died with NBC Sports Edge. Nothing free has replaced it at that quality. (Note: news *aggregation* has its own IP issues — write your own summaries, link out, never republish.)

### Gap 7 — Comparison is always 1-vs-1, never N-vs-N
Every trade analyzer compares two *packages*. Almost nothing lets you put 4–6 players side by side across 20 columns and sort. The stated differentiator ("deep player and team comparison") targets a real void.

### Gap 8 — Scoring customization is shallow
Tools broadly support standard/half/full PPR. Real leagues have TE premium, first-down points, return yards, bonuses at yardage thresholds, per-position IDP tiers, decimal scoring. A tool that doesn't recompute *every projection and ranking* under the user's actual scoring is giving wrong advice, and users notice.

### Gap 9 — No export
Gated at FantasyPros HOF and PFF Pro. The spreadsheet-building segment is small but loud and highly evangelical.

### Gap 10 — Dark mode / density controls
Inconsistent across the majors; Fleaflicker and MFL advertise theming as a *feature*, which tells you the majors don't have it well. Cheap to ship, disproportionately mentioned in reviews.

### Gap 11 — Forced login / forced league sync
Most analytics tools require an account before showing anything. **Full value before signup** is a conversion advantage and a Data Safety advantage simultaneously.

---

## 4. League Sync Reality ✅ (read this section twice)

| Platform | Official public API? | Auth mechanism | Practical difficulty | Legal/ToS posture |
|---|---|---|---|---|
| **Sleeper** | ✅ **Yes** — documented at `docs.sleeper.com` | **None.** No token, read-only | **Easy.** Public leagues, rosters, drafts, transactions, users, trending | ⚠️ **"Free to use for non-commercial purposes… For commercial use of the Sleeper API, please reach out to us directly to discuss licensing."** An ad-supported or donation-supported app is plausibly commercial. **This is the #1 thing to resolve before launch.** Rate guidance: stay **under 1,000 calls/min**; `/players` is ~5MB — fetch **at most once daily** and cache |
| **Yahoo** | ✅ Yes, but **now gated** | **OAuth 2.0** | **Medium → blocked.** The self-service path is gone: the create-app form at `developer.yahoo.com/apps/create/` **no longer offers Fantasy Sports as a selectable scope**. New access requires applying at `sports.yahoo.com/developer/access/`, where "our team reviews every submission," and agreeing to the API Access and Use Agreement. Existing legacy apps reportedly started receiving **403 "This application is not authorized" on all endpoints from ~2026-07-22** | Cleanest ToS *if approved* — a real, signed agreement. But approval is now a gate you do not control, and legacy-key revocation is an active risk |
| **ESPN** | ❌ **No official API** | **Cookie scraping:** `SWID` + `espn_s2`, pulled from browser DevTools after login | **Hard and user-hostile.** Public leagues work unauthenticated via the v3 endpoints; **private leagues require the user to manually extract two cookies from a desktop browser.** This **"cannot be done programmatically"** — no headless login path | ⚠️ **Worst posture.** Endpoints are internal, undocumented, unsupported, can change without notice. Community consensus: personal/educational use is low risk; **"for commercial or public projects, legal and licensing risks should be carefully reviewed."** Asking users to paste session cookies into a third-party app is also a **security and Play Data Safety problem** — those cookies are full account credentials |
| **NFL.com** | Moot | — | — | **Sunset 2026.** Do not build |
| **MyFantasyLeague** | ✅ Yes, long-standing developer API | API key | Easy | Most developer-friendly of any host; small but hardcore user base |
| **Fleaflicker** | ✅ Informal public API | None | Easy | Supported by `ffscrapr`; small user base |

### Recommendation on league sync

**Ship a tiered sync, in this order:**

1. **v1 — Sleeper only, after getting written clarification from Sleeper.** Email them, describe the app (free, ad-supported or donation-supported, read-only, caching `/players` daily), and get the non-commercial/commercial question answered in writing. This is a short email that de-risks the entire product. Sleeper is also your beachhead audience (Gap 5) and has the cleanest technical path.
2. **v1 — Manual league setup as a first-class path, not a fallback.** Let the user type/paste a roster and pick scoring settings, with **zero login**. This sidesteps every ToS and Data Safety problem, works for *every* platform including CBS and MFL, and makes "full value before signup" real (Gap 11). Most tools treat manual entry as a sad consolation prize; make it 60 seconds and delightful and it stops being one.
3. **v2 — Yahoo, via the official application.** Apply at `sports.yahoo.com/developer/access/` early; review takes time and may be denied. OAuth 2.0, tokens in Android Keystore, declared in Data Safety.
4. **Do NOT ship ESPN cookie sync in v1.** Reasons, in order of seriousness:
   - You would be instructing users to extract and hand over **live session credentials** for an account tied to a Disney identity. That is a credential-harvesting pattern, and it will read that way to a Play reviewer and to security-minded users.
   - Data Safety would require declaring collection of credentials/auth tokens — a red flag category.
   - Zero contractual basis; ESPN can break or block it at any time, taking your headline feature with it.
   - The UX (desktop browser → DevTools → copy two cookies → paste into phone) will lose most users anyway.
   - **Interim:** support **public** ESPN leagues read-only (works unauthenticated) and offer manual entry for private ones. Revisit only with counsel.

**Build the integration layer behind an adapter interface** (`LeagueProvider`) so ESPN/Yahoo can be enabled or killed without touching the UI. Use `ffscrapr`'s source as the reference for each platform's real-world endpoint behavior.

---

## 5. Monetization Without a Paywall

### 5a. Options, ranked by fit

| Option | Realistic revenue | Risk | Verdict |
|---|---|---|---|
| **Non-intrusive ads** (banner / native in-feed, **no interstitials**) | US sports traffic is a premium vertical. Banners blend ~$0.35–$1.15 eCPM at low impression density; native/in-feed higher. ⚠️ Rough model: **10k DAU × 6 impressions/day × $1.50 eCPM ≈ $2.7k/mo in-season**, collapsing ~70–80% Feb–Aug | Low — but **only if you avoid interstitials** | ✅ **Primary.** And say so in the store listing: "banner ads only, never a full-screen ad" |
| **Rewarded video (opt-in)** | Highest eCPM ($5–8 interstitial-class) and **explicitly exempt** from Google Play's Better Ads Experiences restrictions | Low — as long as the reward is cosmetic/convenience, **never a stat or a feature** (that's a paywall with extra steps) | ✅ Secondary. Reward = theme packs, "remove banners for 24h" |
| **Tip jar / donations** | Small but real; typical conversion 0.1–1% of engaged users | ⚠️ **Play requires Play Billing for in-app digital purchases.** A "remove ads" or "supporter" IAP must go through Play (15–30% cut). External donation links (Ko-fi/GitHub Sponsors) are safer *outside* the app or under narrow Play exceptions — verify current Payments policy | ✅ Yes, as a Play Billing "Supporter" one-time IAP that removes banners. This is not a paywall — no feature is gated |
| **Sportsbook affiliate links** | Lucrative ($50–$500 CPA) | 🔴 **Highest risk in this document.** Google Play's Real-Money Gambling policy states apps "must not promote or direct users to gambling or real money games, lotteries, or tournament services" and must not provide "companion functionality (for example, functionality that assists with wagering, payouts, **sports score/odds/performance tracking**, or management of participation funds)." A dedicated sports-odds tracker with integrated gambling ad links is cited as a violating pattern | ❌ **Do not do this.** See §6 |
| **Open-source sponsorship** (GitHub Sponsors / OpenCollective) | Modest; strong for credibility | None | ✅ Yes if you open-source. Pairs well with the free thesis and earns organic distribution |
| **Affiliate — non-gambling** (merch, league-dues apps, books) | Small | Low | 🟡 Optional |
| **Anonymized aggregate data / B2B** | Speculative | Privacy + ToS (you'd be redistributing platform data) | ❌ Avoid; contradicts the trust position |

### 5b. Hosting cost estimate ⚠️ (modeled, not measured)

**Recommended architecture — "precompute and ship files."** Because the data is inherently batch (weekly stats, daily news, nightly projections), do all computation in a nightly/weekly job and publish **static JSON/SQLite bundles to object storage behind a CDN**. The Android client filters and sorts locally. This makes cost scale with *bandwidth*, not compute, and bandwidth on R2-class storage has **zero egress fees**.

| Scale | Monthly cost | Composition |
|---|---|---|
| **< 1,000 DAU** | **$0 – $5** | Cloudflare Workers free tier (100k req/day), R2 free tier, GitHub Actions for the nightly ETL, Supabase free (500MB DB) if you need one at all |
| **10,000 DAU** | **$10 – $30** | Workers Paid ($5/mo min + $0.30/M requests). ~10k × 20 req/day ≈ 6M req/mo ≈ $2. R2 storage ~5–20GB ≈ $0.30. Egress $0 |
| **100,000 DAU** | **$50 – $200** | ~60M req/mo ≈ $18 + R2 + a small Postgres ($25 Supabase Pro) for user prefs/sync tokens + monitoring. ⚠️ **Watch Supabase bandwidth: $0.09/GB overage past 250GB is the classic surprise bill** — which is exactly why user-facing data should be served from R2, not the DB |
| **1,000,000 DAU** | **$500 – $2,500** | Request volume dominates; add read replicas, per-region caching, a paid error/analytics tier. Still trivial relative to ad revenue at that scale |

**The real cost is not hosting — it's data.** nflverse/ffverse/FFC-ADP keep licensing at **$0**. The moment you want *live in-game* stats or a *real-time injury wire*, you're looking at SportsDataIO / Sportradar / FantasyData contracts in the **$500–$5,000+/mo** range. **Design v1 so that nothing breaks if you never buy a live feed** — be the best *between-games* app, not a live-scoring app (and note from Gap 4 that live scoring is exactly what the incumbents are bad at and what you'd be worst positioned to beat).

---

## 6. Play Store Compliance Risks

### 🔴 Blocker-class: gambling

The Real-Money Gambling, Games, and Contests policy is broader than "do you take bets." Per the policy text, non-RMG apps **must not provide gambling "support or companion functionality (for example, functionality that assists with wagering, payouts, sports score/odds/performance tracking, or management of participation funds)"** and **must not "promote or direct users to gambling or real money games."** Google's own common-violations guidance cites a **dedicated sports odds tracker with integrated gambling ads linking to sportsbooks** as a violating pattern.

There is real ambiguity here — ESPN and Yahoo display odds — but they are large publishers with negotiated relationships, adult content ratings, and legal teams. **A new indie app does not get the benefit of the doubt in automated review.**

**Recommendation:**
- ❌ **No sportsbook affiliate links.** Non-negotiable.
- ❌ **No gambling ad network / no betting ads in mediation.** Explicitly block gambling categories in AdMob.
- ⚠️ **No raw Vegas lines in v1.** If you later want the analytical value of betting markets, the defensible framing is **derived, unbranded, unlinked**: show "implied team total: 24.5" and "projected game script" as model inputs, with **no sportsbook names, no odds in American/decimal format, no links, no bet-slip anything**. Even then, get counsel, and expect to need an 18+ content rating.
- The upside: **this is a fantasy advice app, not a DFS app**, so the heavy DFS requirements (per-jurisdiction gambling licenses, AO rating, geo-gating, no Play Billing, approval application) **do not apply** — *as long as you never host contests, entry fees, prizes, or wagering.* Keep it that way; it's a large regulatory moat you get for free by not crossing the line.

### 🟡 High-attention: Data Safety

The most common rejection cause is a **mismatch between the declared Data Safety form, the privacy policy, and what the app actually does** — including third-party SDKs you forgot about. The April 2025 update reclassified **Android ID as a device identifier that must be declared under "Device or other IDs,"** and tightened what counts as "sharing."

**Do:**
- Declare the **AdMob SDK's** collection (Device/other IDs, approximate location, app activity) — this is where indie apps get caught.
- Declare **crash/analytics** SDKs.
- If you ever ship ESPN cookie sync, you must declare collection of **credentials** — another reason not to.
- Publish a privacy policy that **matches the form line-for-line**.
- Best posture: **no account required, no PII, league data cached on-device.** Then the form is nearly empty and there's nothing to mismatch.

### 🟡 Intellectual property / third-party sports data

- **Facts are not copyrightable** (scores, yardage, stat lines) — US law is settled on this. **Presentation, logos, and names are.**
- **Do not** use NFL team logos, NFL/team wordmarks, "NFL" in the app title or icon, or official team color-marks as branding. Google rejects for "Unauthorized Use of Brand or Trademark," and the appeal path wants **written authorization you will not have**. Use **city + nickname text** or your own generic marks.
- **Do not** embed player headshots scraped from ESPN/NFL — those are licensed images (Getty/AP). Ship silhouettes or nothing.
- **Do not republish** news blurbs verbatim. Write your own or link out.
- Attribute your free sources: **Fantasy Football Calculator** requests attribution; **FantasyCalc** requires an attribution link ⚠️(verify); nflverse asks for citation.
- **Never scrape KeepTradeCut** — their terms expressly forbid it, and Dynasty Daddy's dependence on it is a cautionary example, not a precedent.

### 🟡 Ads policy (Better Ads Experiences)

Effective since Sept 30, 2022 and still in force:
- ❌ Full-screen interstitials **before the app's loading/splash screen**
- ❌ Interstitials at **unexpected moments** — only at natural breaks
- ❌ Any full-screen interstitial **not closeable after 15 seconds**
- ✅ **Opt-in rewarded ads are exempt**
- ✅ Non-full-screen ads that don't disrupt function are unaffected

Since the product thesis is anti-friction, this constraint is free to satisfy: **banners + opt-in rewarded only.**

### 🟢 Lower-risk items
- **User-generated content:** if you ship comments/chat, you need reporting, blocking, and moderation. **Don't ship UGC in v1** — pure cost and risk with no fit against the stat-density thesis.
- **Content rating:** without gambling content and without UGC, this rates **Everyone / Teen**. Keep it there.
- **Target API level / 16KB page size:** routine but hard deadlines; keep the toolchain current.

---

## 7. Positioning

### Positioning statement

> **The whole stat sheet, free. Every metric the paid sites charge for — on one screen, on your phone, sortable, comparable, and never behind a paywall.**
>
> For the fantasy manager who already knows the names and wants the numbers: one grid where your roster, advanced efficiency metrics, dynasty values, ADP, and 25 years of history are **columns you can sort, filter, and compare side by side.** No subscription. No interstitial ads. No login to look.

**The wedge, in one line for the store listing:** *"Everything FantasyPros charges $72–$276/year for, plus the advanced stats PlayerProfiler charges $135 for — free, and actually usable on a phone."*

### The 5 features that make someone switch

1. **The Grid — a genuinely good mobile stat table.**
   Frozen player column, horizontal momentum scroll, long-press header to sort, a **column chooser with 60+ metrics**, saved views, multi-condition filters ("WRs, target share > 20%, opponent ranked bottom-10 vs slot"), and CSV/JSON export. *Why it wins:* this is the market's universal failure (Gap 2), it's a pure engineering win requiring no licensed data, and it's instantly legible in a screenshot — which is how you win Play Store listing conversion.

2. **N-way comparison, not 2-way.**
   Put 2–6 players (or 2–4 teams) side by side across every selected metric, with per-row deltas, percentile bars, sparklines by week, and a **schedule-strength overlay for the rest of the season**. *Why it wins:* every competitor does 1-vs-1 trade math; nobody does "show me these five flex options across twenty columns" (Gap 7).

3. **Your scoring, applied to everything.**
   Full custom scoring — TE premium, first-down points, yardage-threshold bonuses, decimal scoring, per-position IDP — that **recomputes every projection, ranking, and historical stat line in the app**, not just a PPR toggle. Plus **unlimited leagues, free** (Gap 8, attacking FantasyPros' per-league-count tiering directly).

4. **Free advanced metrics, honestly sourced.**
   Expected fantasy points (xFP) and efficiency-vs-expectation, opportunity share, target share, air yards, aDOT, route participation, red-zone and green-zone usage, snap share — computed from **nflverse + ffopportunity**, with **25 seasons of history** (1999→present) behind every player, and a visible "how this is calculated" link on every metric. *Why it wins:* this is the $45–$499/yr tier given away, it costs $0 in licensing, and **transparency about methodology is itself a differentiator** in a market where every projection is a black box (Gap 1, Gap 3).

5. **Zero-friction entry: no login, no interstitials, instant.**
   Full app value before any account. Sleeper sync in one tap when you want it; **60-second manual roster entry** when you don't. Banner ads only — never a full-screen ad — with an optional one-time Supporter purchase to remove them. Offline-capable, dark mode by default, opens instantly on Sunday when ESPN is crashing. *Why it wins:* it directly answers the three loudest complaints about the incumbents (ads, Sunday reliability, forced signup — Gaps 3, 4, 11), and it makes the free promise feel true rather than claimed.

### What NOT to build (v1)
- Live in-game scoring (you'll lose to platforms; needs an expensive feed; it's their job)
- League hosting (Sleeper has won this)
- DFS optimizers (regulatory adjacency, tiny audience, high compute)
- Any betting/odds surface (§6)
- Chat / UGC (moderation burden, no thesis fit)
- News aggregation at scale (IP risk; link out instead)

---

## Open items to verify before committing
1. ✅→⚠️ **Sleeper commercial-use licensing.** Email Sleeper. Blocking.
2. ⚠️ **Yahoo developer access approval.** Apply now; lead time unknown, denial possible.
3. ⚠️ **FantasyCalc API terms** — the ToS page did not render; secondary sources say non-commercial + attribution. Read it directly.
4. ⚠️ **RotoViz, Establish The Run, Sharp Football, DFS Army pricing** — not captured (403s / paywalled pages).
5. ⚠️ **Play Store ratings** are from review-aggregator secondary sources; the Play listing pages did not render cleanly. Re-check directly.
6. 🟡 **Reddit primary research** — blocked this pass. Do a manual pass on r/fantasyfootball, r/DynastyFF, r/fantasyfootballadvice before locking the roadmap; §3 is the section most likely to shift.
7. ⚠️ **Play Billing vs. external donation links** — confirm current Payments policy before shipping any tip jar.
