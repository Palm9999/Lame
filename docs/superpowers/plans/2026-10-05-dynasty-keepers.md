# Dynasty & keepers Implementation Plan

> Run with `executing-plans` in this session (CLAUDE.md). Steps per task: failing test, run, implement, run, commit.

**Goal:** More → Dynasty & keepers: FantasyCalc dynasty values for every player, and keeper picks by draft-round cost for your team.

**Architecture:** `DynastyRepository` (`:core:data`) fetches FantasyCalc per format; `EspnFantasyParser.draft` reads the league's picks; pure `Keepers` (`:core:projections`) prices and ranks; keeper settings live on `EspnLeagueEntry`; one screen with two tabs in `:feature:projections`.

**Spec:** `docs/superpowers/specs/2026-10-05-dynasty-keepers-design.md`

## Global Constraints

- FantasyCalc URL: `https://api.fantasycalc.com/values/current?isDynasty=true&numQbs=Q&numTeams=T&ppr=P`; memory cache 6 hours per format.
- Prefs `formatVersion` stays 4; new league fields default (keepers 2, penalty 1, undrafted round null = roster size).
- Kotlin test names ASCII only (a `·` broke the compiler's class file path).
- Android modules test with `testDebugUnitTest`; Gradle through the scratchpad `retry.sh` (Maven 429s).

## Review Focus

- A league synced before this change (snapshot without roster size): undrafted round falls back to 16. Test in Task 2.
- A drafted player who is now on another team, or a pick for a player you since dropped: only your current roster is priced. Test in Task 3.
- FantasyCalc returns rookie picks (`position` "PICK") and players without `espnId`: dropped / kept unlinked. Test in Task 1.
- A cost override for a player no longer rostered stays stored but unused. Test in Task 3.
- Fewer valued players than `keepers`: Keep marks only valued ones, never a player without worth. Test in Task 3.

---

### Task 1: FantasyCalc values

**Files:** create `core/data/.../DynastyRepository.kt`, `core/data/src/test/.../DynastyRepositoryTest.kt`, `core/data/src/test/resources/fantasycalc/values.json` (trimmed real response, about 30 players incl. one PICK).

**Produces:**
- `data class DynastyFormat(val teams: Int, val qbs: Int, val ppr: Double)`; `fun dynastyFormat(league: FantasyLeague?, profile: ScoringProfile): DynastyFormat` (teams from league else 12; qbs 2 if `"OP"` in `lineupSlots`; ppr 1/0.5/0 by `profile.receptionWeight(Position.WR)` ≥0.75/≥0.25).
- `data class DynastyValue(val espnId: String, val playerId: String?, val name: String, val position: String, val team: String?, val age: Double?, val value: Int, val redraftValue: Int, val rank: Int, val positionRank: Int, val trend30: Int)`
- `data class DynastyResult(val values: List<DynastyValue>, val error: String?)`
- `class DynastyRepository(http: HttpGet, players: PlayerDirectory, clock)` with `suspend fun load(format: DynastyFormat): DynastyResult`.

**Tests:** `parses the recorded values`, `rookie picks are dropped`, `a player without an ESPN id stays unlinked`, `the URL follows the format`, `format from league and profile`, `six hours of cache per format`, `a failure keeps the last list and says why`, `not JSON is a format change`.

### Task 2: League roster size and draft picks

**Files:** modify `core/data/.../live/FantasyLeague.kt` (+ its test).

**Produces:**
- `FantasyLeague.rosterSize: Int = 0` parsed from `lineupSlotCounts` summed without IR (21); `val FantasyLeague.undraftedRound: Int get() = rosterSize.takeIf { it > 0 } ?: 16`.
- `data class DraftPick(val espnId: String, val playerId: String?, val round: Int, val teamId: Int, val keeper: Boolean)`; `EspnFantasyParser.draftUrl(leagueId, season)` (`?view=mDraftDetail`), `EspnFantasyParser.draft(text): List<DraftPick>` (empty when `draftDetail` or `picks` missing; non-JSON throws `LiveFormatException`).
- `FantasyLeagueRepository.draft(season): DraftResult(picks: List<DraftPick>, error: String?)`, ids linked like rosters (D/ST via `dstPlayerId`), cached per league until the next sync.

**Tests:** `roster size sums the slots without IR`, `an old snapshot falls back to 16 rounds`, `draft picks parse round, team and keeper`, `no draft yet is an empty list`.

### Task 3: Keeper pricing

**Files:** create `core/projections/.../Keepers.kt`, `KeepersTest.kt`; modify `core/datastore/.../UserPrefs.kt`, `UserPrefsJson.kt` (+ round-trip test); `FantasyLeagueRepository.setKeeperRule(...)`, `keeperRule: Flow<KeeperRule?>`.

**Produces:**
- `data class KeeperRule(val keepers: Int = 2, val penalty: Int = 1, val undraftedRound: Int? = null, val overrides: Map<String, Int> = emptyMap())` on `EspnLeagueEntry.keeperRule`.
- `object Keepers { fun cost(playerId, picks: List<DraftPick-like>, myTeamId, rule, undrafted): Int; fun rank(roster, costs, redraftRanks, dynasty, teams, keepers): List<KeeperRow> }`, `data class KeeperRow(playerId, cost: Int, worth: Int?, surplus: Int?, dynasty: Int?, keep: Boolean)`. `Keepers` takes plain inputs (`KeeperPick(playerId, round, keeper)`), not ESPN types.

**Tests:** `drafted player costs his round`, `a keeper pick costs a round less, never below 1`, `undrafted costs the undrafted round`, `an override wins`, `an override for someone gone is ignored`, `worth round is redraft rank over teams rounded up`, `top N by surplus keep, ties by dynasty value`, `players without worth list last and never keep`, `the rule round-trips through prefs`.

### Task 4: Screen and wiring

**Files:** create `feature/projections/.../DynastyScreen.kt`, `DynastyViewModel.kt`, `DynastyScreenTest.kt`; modify `app/.../NavKeys.kt` (`DynastyKey(season)`), `GridironNavHost.kt` (More "League", ☰), `GridironApplication.kt` (`dynasty = DynastyRepository(UrlConnectionHttpGet(), players)`).

**Interfaces:** `DynastyRoute(season, load: suspend (ScoringProfile) -> DynastyResult, keepers: suspend (Int) -> KeepersState, setRule: suspend (KeeperRule) -> Unit, scoring, onPlayer, onBack, league, myTeam)`.

**Tests:** `dynasty tab ranks and filters by position`, `free agents chip narrows`, `keepers tab marks keep and shows cost and worth`, `no league says sync one`, `no draft found says every player costs the undrafted round`, `editing penalty reprices`.

### Task 5: Docs

ARCHITECTURE Features (Dynasty & keepers), CLAUDE.md data attribution (FantasyCalc), HANDOFF (built, unverified ESPN draft shape, phone check, deferred minors); delete this plan and the spec; push.
