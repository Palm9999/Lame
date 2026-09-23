"""Post-build validation.

The build is only useful if the numbers are right, and wrong numbers here are
silent — a bad share still inserts cleanly and still renders in a table. These
checks run as part of every build and fail it loudly.

Expected ranges encode real football, not convenient assumptions. Air yards
share deliberately permits values outside [0, 1]; see `transform.weekly_player_stats`.
"""

from __future__ import annotations

import logging
import sqlite3
from dataclasses import dataclass

import polars as pl

from . import transform

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class RangeCheck:
    metric_id: str
    lo: float
    hi: float
    note: str = ""


RANGE_CHECKS: tuple[RangeCheck, ...] = (
    RangeCheck("target_share", 0.0, 1.0),
    RangeCheck("carry_share", 0.0, 1.0),
    RangeCheck("catch_rate", 0.0, 1.0),
    RangeCheck("snap_share", 0.0, 1.0),
    RangeCheck("rush_success_rate", 0.0, 1.0),
    RangeCheck("wopr", 0.0, 2.2, "clamped shares, so bounded by 1.5 + 0.7"),
    # Intentionally wide: negative air yards are real.
    RangeCheck("air_yards_share", -3.0, 3.0, "screens produce negative air yards"),
    # Single-target weeks inherit play-level air yards, observed -19..64.
    RangeCheck("adot", -20.0, 65.0, "one-target weeks can equal a single screen"),
    RangeCheck("cpoe", -100.0, 100.0),
    RangeCheck("receptions", 0.0, 30.0),
    RangeCheck("targets", 0.0, 35.0),
    RangeCheck("carries", 0.0, 50.0),
    RangeCheck("passing_yards", -50.0, 800.0),
    RangeCheck("receiving_yards", -50.0, 400.0),
    RangeCheck("rushing_yards", -50.0, 400.0),
    RangeCheck("fumbles_lost", 0.0, 6.0),
    RangeCheck("passing_first_downs", 0.0, 40.0),
    RangeCheck("rushing_first_downs", 0.0, 30.0),
    RangeCheck("receiving_first_downs", 0.0, 20.0),
    RangeCheck("passing_2pt", 0.0, 4.0),
    RangeCheck("rushing_2pt", 0.0, 3.0),
    RangeCheck("receiving_2pt", 0.0, 3.0),
)


@dataclass(frozen=True)
class CoherenceCheck:
    """A per player-week relationship between two stored metrics.

    Range checks can't see a denominator that has drifted from its numerator;
    these can. `predicate` is a SQL expression over `a` and `b` that must hold.
    """
    name: str
    a: str
    b: str
    predicate: str


COHERENCE_CHECKS: tuple[CoherenceCheck, ...] = (
    CoherenceCheck("targets within team targets", "targets", "team_targets", "a <= b"),
    CoherenceCheck("carries within team carries", "carries", "team_carries", "a <= b"),
    CoherenceCheck("snaps within team snaps", "offense_snaps", "team_offense_snaps", "a <= b"),
    CoherenceCheck("cpoe attempts within attempts", "cpoe_n", "attempts", "a <= b"),
    # Team snaps are solved to agree with the published percentages, so derived
    # and published share may differ by at most one 0.01 rounding step.
    #
    # Why not tighter: the source is occasionally self-inconsistent. 2024 TB
    # week 19 lists 44 snaps at 0.91, but 44/D rounds to 0.91 only for D in
    # (48.09, 48.62], which contains no integer. A handful of player-weeks per
    # season are irreconcilable upstream.
    #
    # Why not looser: the earlier max-snaps shortcut was off by 0.02-0.036, and
    # this bound must keep catching that class of error.
    CoherenceCheck("snap share matches its components", "offense_snaps", "team_offense_snaps",
                   "b = 0 OR ABS(a / b - (SELECT value FROM player_week_stat x "
                   "WHERE x.player_id = pw.player_id AND x.season = pw.season "
                   "AND x.week = pw.week AND x.metric_id = 'snap_share')) <= 0.011"),
    CoherenceCheck("50+ passing TDs within 40+", "passing_tds_50", "passing_tds_40", "a <= b"),
    CoherenceCheck("40+ passing TDs within passing TDs", "passing_tds_40", "passing_tds", "a <= b"),
    CoherenceCheck("50+ rushing TDs within 40+", "rushing_tds_50", "rushing_tds_40", "a <= b"),
    CoherenceCheck("40+ rushing TDs within rushing TDs", "rushing_tds_40", "rushing_tds", "a <= b"),
    CoherenceCheck("50+ receiving TDs within 40+", "receiving_tds_50", "receiving_tds_40", "a <= b"),
    CoherenceCheck("40+ receiving TDs within receiving TDs", "receiving_tds_40", "receiving_tds", "a <= b"),
    CoherenceCheck("receiving first downs within receptions", "receiving_first_downs", "receptions", "a <= b"),
    CoherenceCheck("rushing first downs within carries", "rushing_first_downs", "carries", "a <= b"),
)


class ValidationError(RuntimeError):
    pass


def validate(conn: sqlite3.Connection, strict: bool = True) -> list[str]:
    """Run all checks. Returns the list of failures; raises if `strict`."""
    problems: list[str] = []

    for chk in RANGE_CHECKS:
        row = conn.execute(
            "SELECT MIN(value), MAX(value), COUNT(*) FROM player_week_stat WHERE metric_id = ?",
            (chk.metric_id,),
        ).fetchone()
        lo, hi, n = row
        if n == 0:
            log.warning("no rows for metric %s", chk.metric_id)
            continue
        if lo < chk.lo or hi > chk.hi:
            problems.append(
                f"{chk.metric_id}: observed [{lo:.3f}, {hi:.3f}] "
                f"outside expected [{chk.lo}, {chk.hi}]"
                + (f" ({chk.note})" if chk.note else "")
            )

    for chk in COHERENCE_CHECKS:
        bad = conn.execute(
            f"""SELECT COUNT(*) FROM (
                  SELECT player_id, season, week,
                         MAX(CASE WHEN metric_id = ? THEN value END) AS a,
                         MAX(CASE WHEN metric_id = ? THEN value END) AS b
                  FROM player_week_stat
                  WHERE metric_id IN (?, ?)
                  GROUP BY player_id, season, week
                ) pw
                WHERE a IS NOT NULL AND b IS NOT NULL AND NOT ({chk.predicate})""",
            (chk.a, chk.b, chk.a, chk.b),
        ).fetchone()[0]
        if bad:
            problems.append(f"coherence: {chk.name} violated in {bad} player-weeks")

    # Every fact must reference a known player and a registered metric.
    orphan_players = conn.execute(
        "SELECT COUNT(*) FROM player_week_stat s "
        "LEFT JOIN player p USING(player_id) WHERE p.player_id IS NULL"
    ).fetchone()[0]
    if orphan_players:
        problems.append(f"{orphan_players} facts reference unknown player ids")

    orphan_metrics = conn.execute(
        "SELECT COUNT(*) FROM player_week_stat s "
        "LEFT JOIN metric m ON m.id = s.metric_id WHERE m.id IS NULL"
    ).fetchone()[0]
    if orphan_metrics:
        problems.append(f"{orphan_metrics} facts reference unregistered metrics")

    nulls = conn.execute(
        "SELECT COUNT(*) FROM player_week_stat WHERE value IS NULL"
    ).fetchone()[0]
    if nulls:
        problems.append(f"{nulls} facts have a null value (should be filtered out)")

    # A week with no target share at all means the share join silently failed.
    empty_weeks = conn.execute(
        "SELECT season, week FROM player_week_stat GROUP BY season, week "
        "HAVING SUM(CASE WHEN metric_id='target_share' THEN 1 ELSE 0 END) = 0"
    ).fetchall()
    if empty_weeks:
        problems.append(f"weeks with no target_share rows: {empty_weeks[:5]}")

    # A 50+ count with no 40+ row is a violation the pairwise coherence query
    # can't see (the 40+ side is absent, since sparse metrics drop zeros).
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

    for p in problems:
        log.error("VALIDATION: %s", p)
    if problems and strict:
        raise ValidationError(f"{len(problems)} validation failure(s); first: {problems[0]}")
    if not problems:
        log.info("validation passed — %d checks",
                 len(RANGE_CHECKS) + len(COHERENCE_CHECKS) + 8)
    return problems


KEYS = ["player_id", "season", "week"]


@dataclass(frozen=True)
class ComparisonPolicy:
    """The shared three-tier policy every ffopportunity comparison follows.

    `.github/workflows/etl.yml` builds on a schedule, daily and more often
    in season. A build failure stops the app's data updates, so a single
    mismatched player-week must never fail it by itself — ffopportunity's own
    data has real, occasionally-changing quirks (backward laterals, one-off
    parser hiccups on plays with a trailing penalty, misattributed fumbles;
    see the tolerances and the fumble-pattern notes below, each traced to a
    specific real play). But a build that silently accepts *many* mismatches,
    or one wildly wrong row, is exactly the "wrong numbers are otherwise
    silent" failure mode this module exists to catch. Three tiers balance
    that: `magnitude` below is a comparison-specific "how wrong is this row"
    quantity (always >= 0; a fumble-trailing check clips the "ours exceeds
    theirs" direction to 0, since that direction is never a problem).

      - `magnitude <= tight`: agreement. No outlier, no warning.
      - `tight < magnitude <= hard_cap`, and the season's outlier count is
        `<= season_budget`: an outlier. Logged as a WARNING with the
        player-week key and both sides' values — visible for a human to
        look at, but does not fail the build.
      - `magnitude > hard_cap` on any single row, OR more than
        `season_budget` outliers in one season: a build failure. The cap
        catches one badly wrong row (a dropped or doubled scoring category);
        the budget catches many small, individually-tolerable mismatches
        that add up to systematic drift — e.g. a `_fumbles()` regression
        that silently drops crediting on every fumble, not just the ~10
        isolated per-play upstream misattributions documented below.
    """
    name: str
    tight: float
    hard_cap: float
    season_budget: int


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


def _apply_policy(joined: pl.DataFrame, magnitude: pl.Expr, policy: ComparisonPolicy,
                   value_cols: list[str]) -> list[str]:
    """The one shared implementation of `ComparisonPolicy`, used by every
    ffopportunity comparison below. `magnitude` is that comparison's
    already-computed, non-negative "how wrong is this row" expression.

    Every row beyond `policy.tight` is logged as a warning (this is the only
    place that happens — a build-failing problem is returned only for a
    hard-cap or season-budget breach, never for an outlier by itself).
    """
    outliers = joined.filter(magnitude > policy.tight)
    if not outliers.height:
        return []

    for row in outliers.select(KEYS + value_cols).iter_rows(named=True):
        log.warning("ffopportunity outlier [%s]: %s", policy.name, row)

    problems: list[str] = []
    over_cap = outliers.filter(magnitude > policy.hard_cap)
    if over_cap.height:
        problems.append(
            f"{policy.name}: {over_cap.height} player-week(s) exceed the hard cap of "
            f"{policy.hard_cap} (tight tolerance {policy.tight}), e.g. "
            f"{_examples(over_cap, value_cols)}"
        )

    per_season = outliers.group_by("season").agg(pl.len().alias("n")).sort("season")
    for season, n in per_season.filter(pl.col("n") > policy.season_budget).rows():
        problems.append(
            f"{policy.name}: season {season} has {n} outliers beyond tolerance "
            f"{policy.tight}, exceeding the season budget of {policy.season_budget} "
            f"— likely systematic drift rather than isolated upstream quirks"
        )
    return problems


CROSS_CHECK_TOLERANCE = 1.0
# Backward-lateral plays: nflverse's own `passing_yards` column credits the
# passer with the play's full net yardage, including any advance gained after
# a completion is laterally pitched to a teammate (matching the official
# gamebook convention — the whole play is scored as one pass play). ffopport-
# unity's `pass_yards_gained` instead stops at the completion spot, so it
# excludes the lateral's extra yards, which nobody else is credited with
# either (this codebase doesn't touch `lateral_receiving_yards`). About 20
# plays a season carry a lateral; observed max extra yardage is 41 (2024 wk17,
# 00-0033106/J.Goff to A.St. Brown, lateraled to Ja.Williams for a TD) and 33
# (2025). Widened just for this pair, not the shared default, so a real
# passing-yards regression elsewhere still counts as an outlier at 1.0.
_LATERAL_YARDS_TOLERANCE = 45.0

# Hard caps, grouped by stat shape rather than one per pair. Each is sized to
# catch a dropped or doubled scoring category while clearing, with room to
# spare, the largest mismatch ever observed for any of these pairs in 3
# seasons of real 2024-2026 data — which is 0: no cross-check pair has ever
# produced a single outlier (a row beyond its own tight tolerance above).
_COUNT_HARD_CAP = 3.0      # TDs, 2-pt conversions, interceptions: usually 0-1/game.
_VOLUME_HARD_CAP = 5.0     # receptions, completions, first downs: more frequent.
_YARDAGE_HARD_CAP = 50.0   # rushing/receiving yards.
_LATERAL_HARD_CAP = 100.0  # passing_yards: well above the tight tolerance's
                            # traced lateral noise (41 yd max observed), still
                            # far under any real quarterback's weekly total.
# With zero outliers ever observed for any of these 15 pairs, there's no
# "worst season" to scale from; a small fixed budget (about 0.1% of a
# season's ~5,600 player-weeks) leaves room for a first, isolated upstream
# oddity in a future season without permitting real drift to pass quietly.
_DEFAULT_SEASON_BUDGET = 5


def _pair(ours: str, theirs: str, tolerance: float, hard_cap: float) -> tuple[str, str, ComparisonPolicy]:
    name = f"cross-check {ours} vs ffopportunity {theirs}"
    return ours, theirs, ComparisonPolicy(name, tolerance, hard_cap, _DEFAULT_SEASON_BUDGET)


# Our play-by-play actual -> ffopportunity's actual column for the same stat,
# each with its own policy (tolerance usually the shared default).
CROSS_CHECK_PAIRS: tuple[tuple[str, str, ComparisonPolicy], ...] = (
    _pair("receptions", "receptions", CROSS_CHECK_TOLERANCE, _VOLUME_HARD_CAP),
    _pair("completions", "pass_completions", CROSS_CHECK_TOLERANCE, _VOLUME_HARD_CAP),
    _pair("passing_yards", "pass_yards_gained", _LATERAL_YARDS_TOLERANCE, _LATERAL_HARD_CAP),
    _pair("rushing_yards", "rush_yards_gained", CROSS_CHECK_TOLERANCE, _YARDAGE_HARD_CAP),
    _pair("receiving_yards", "rec_yards_gained", CROSS_CHECK_TOLERANCE, _YARDAGE_HARD_CAP),
    _pair("passing_tds", "pass_touchdown", CROSS_CHECK_TOLERANCE, _COUNT_HARD_CAP),
    _pair("rushing_tds", "rush_touchdown", CROSS_CHECK_TOLERANCE, _COUNT_HARD_CAP),
    _pair("receiving_tds", "rec_touchdown", CROSS_CHECK_TOLERANCE, _COUNT_HARD_CAP),
    _pair("passing_2pt", "pass_two_point_conv", CROSS_CHECK_TOLERANCE, _COUNT_HARD_CAP),
    _pair("rushing_2pt", "rush_two_point_conv", CROSS_CHECK_TOLERANCE, _COUNT_HARD_CAP),
    _pair("receiving_2pt", "rec_two_point_conv", CROSS_CHECK_TOLERANCE, _COUNT_HARD_CAP),
    _pair("passing_first_downs", "pass_first_down", CROSS_CHECK_TOLERANCE, _VOLUME_HARD_CAP),
    _pair("rushing_first_downs", "rush_first_down", CROSS_CHECK_TOLERANCE, _VOLUME_HARD_CAP),
    _pair("receiving_first_downs", "rec_first_down", CROSS_CHECK_TOLERANCE, _VOLUME_HARD_CAP),
    _pair("interceptions", "pass_interception", CROSS_CHECK_TOLERANCE, _COUNT_HARD_CAP),
)

# Fumbles: ours also counts sack fumbles, so it may exceed theirs freely —
# only trailing is a problem, and any amount of trailing is an outlier
# (tight=0). Real per-play misattributions, each traced against
# `fumbled_1_player_id` in that season's play-by-play, fall into two
# recurring patterns rather than being random:
#
#   Pattern A — interception-return fumble (5 traced instances: 2024 ARI
#   wk7/00-0033553/J.Conner intercepted by 90-T.Tart, who fumbles the return;
#   2025 MIN wk13/00-0038994/J.Addison by 27-T.Woolen; 2025 TEN
#   wk5/00-0034837/C.Ridley by 42-D.Taylor-Demerson; 2025 ARI
#   wk4/00-0039849/M.Harrison by 8-C.Bryant; 2025 PHI wk14/00-0035676/A.Brown
#   by 91-D.Hand): the pass is intercepted — the named player never touches
#   the ball — and the *defender* returning it fumbles; ffopportunity still
#   charges the original intended receiver. Ours correctly excludes it (a
#   defender's fumble isn't an offensive stat — see `_fumbles`'s
#   docstring/tests in transform.py).
#
#   Pattern B — multi-lateral chain (3 instances: 2024 PIT
#   wk5/00-0037247/G.Pickens, chain ends with 88-P.Freiermuth fumbling; 2025
#   CHI wk1/00-0039919/R.Odunze, ends with 2-D.Moore; 2025 CAR
#   wk18/00-0039491/J.Coker, ends with 4-T.McMillan; 2025 IND
#   wk18/00-0038997/J.Downs, ends with the QB 15-R.Leonard on a second
#   lateral back to himself): a completed pass is laterally advanced one or
#   more times; the fumble belongs to whoever was carrying it at the end of
#   the chain, but ffopportunity charges the play's *original* receiver.
#
#   Plus one distinct case: 2024 ATL wk1/00-0029604/K.Cousins, a botched
#   shotgun snap ("Aborted") fumbled by the center (fumbled_1_player_id =
#   00-0036957/D.Dalman), but ffopportunity charges the QB, matching
#   nflverse's own rusher_player_id for the play — ours doesn't credit
#   Cousins since the fumbler doesn't match any of the play's named
#   skill-position ids.
#
# 10 outliers total across 2024 (3) and 2025 (7); 0 in 2026 so far. The hard
# cap (3) would catch a single player-week absurdly trailing by 3+ fumbles —
# never observed (every traced case trails by exactly 1). The season budget
# (20, about 3x the worst season's 7) is what actually catches a real
# regression: the reviewer's case of ours reporting 0 everywhere theirs has
# 1+ would produce far more than 20 outliers in a single season.
FUMBLE_POLICY = ComparisonPolicy("cross-check fumbles_lost vs ffopportunity", 0.0, 3.0, 20)


def cross_check(weekly: pl.DataFrame, ep: pl.DataFrame) -> list[str]:
    """Our play-by-play actuals against ffopportunity's, per player-week.

    Two independent derivations of the same stat from the same plays: a
    mismatch means one of them is wrong, most likely ours — but see
    `ComparisonPolicy` for why a mismatch alone doesn't fail the build.
    """
    joined = _join_sources(weekly, ep)
    problems: list[str] = []
    for ours, col, policy in CROSS_CHECK_PAIRS:
        theirs = _theirs(weekly, col)
        magnitude = (pl.col(ours).fill_null(0) - pl.col(theirs).fill_null(0)).abs()
        problems += _apply_policy(joined, magnitude, policy, [ours, theirs])

    c = lambda name: pl.col(name).fill_null(0)  # noqa: E731
    theirs_fumbles = c("rec_fumble_lost") + c("rush_fumble_lost")
    # Only the "ours trails theirs" direction is a problem; exceeding (sack
    # fumbles) is clipped to 0 so it never counts as an outlier.
    trailing = (theirs_fumbles - c("fumbles_lost")).clip(lower_bound=0.0)
    problems += _apply_policy(joined, trailing, FUMBLE_POLICY, ["fumbles_lost"])
    return problems


def _reference_points(receptions, rec_yds, rec_td, rec_2pt, rush_yds, rush_td, rush_2pt,
                      pass_yds, pass_td, pass_2pt, ints) -> pl.Expr:
    """The profile ffopportunity totals use, fumbles excluded."""
    return (
        receptions + 0.1 * rec_yds + 6 * rec_td + 2 * rec_2pt
        + 0.1 * rush_yds + 6 * rush_td + 2 * rush_2pt
        + 0.04 * pass_yds + 4 * pass_td + 2 * pass_2pt - 2 * ints
    )


# Points a total_fantasy_points mismatch may carry before it's an outlier.
# 0.01 covers ordinary float slack. Two documented ffopportunity quirks need
# more, individually well under this bound: the lateral-yardage convention
# above (up to 45 * 0.04 = 1.8 points), and Super Bowl LIX (2024 wk22,
# 00-0033873/P.Mahomes): play-by-play shows two successful two-point passes
# (to T.Watson and D.Hopkins — "ATTEMPT SUCCEEDS" in the play text), but
# ffopportunity's total reflects only one; the successful Watson conversion is
# immediately followed by a defensive penalty "enforced between downs", which
# appears to have thrown off their parser. Ours (2) is what actually happened.
# Observed max across 2024-2026, from either cause alone: 2.0 points.
FANTASY_CONTRACT_TOLERANCE = 2.05
# Just under a single missing touchdown's 6 points, so a fully dropped
# scoring category trips it; the two documented quirks above (max 2.0) and
# ordinary float slack stay well clear. No player-week has ever exceeded the
# tolerance above at all in 3 seasons, let alone this.
FANTASY_CONTRACT_HARD_CAP = 5.5
# No outlier has ever been observed for this comparison; a small fixed
# budget (a fraction of a percent of a season) leaves room for a first one.
FANTASY_CONTRACT_SEASON_BUDGET = 5
FANTASY_CONTRACT_POLICY = ComparisonPolicy(
    "fantasy contract: total_fantasy_points vs our components",
    FANTASY_CONTRACT_TOLERANCE, FANTASY_CONTRACT_HARD_CAP, FANTASY_CONTRACT_SEASON_BUDGET,
)

# Traced, not a rounding bound: the file's per-category *_fantasy_points_exp
# columns (pass/rec/rush) are each their own model output, not a recomputation
# of our formula from the published x_* components — so the two don't have to
# reconcile, and by a bit more than 2-decimal rounding could explain on its
# own. Worst observed case, 2025 wk7, 00-0039910 (Jayden Daniels): the file's
# rush_fantasy_points_exp is 9.42, but 0.1*42.44 + 6*0.70 + 2*0.39 = 9.224 from
# its own x_rushing_yards/tds/2pt columns — a 0.196 residual on the rush leg
# alone. The pass leg adds another 0.017 (file's pass_fantasy_points_exp 9.02
# vs. 0.04*184.07 + 4*0.63 - 2*0.44 = 9.0028), summing to the observed
# total_fantasy_points_exp residual of 0.2132 (18.44 file vs. 18.2268 computed;
# rec leg is 0 here). Set to the traced max (0.2132) plus a small margin.
EXPECTED_FANTASY_CONTRACT_TOLERANCE = 0.22
# Roughly a whole missing expected touchdown (6 points) or ~150 missing
# expected passing yards (0.04 * 150 = 6) — sized well above the traced
# model-inconsistency residual (0.2132) that motivates the tolerance above.
EXPECTED_FANTASY_CONTRACT_HARD_CAP = 6.0
EXPECTED_FANTASY_CONTRACT_SEASON_BUDGET = 5
EXPECTED_FANTASY_CONTRACT_POLICY = ComparisonPolicy(
    "fantasy contract: total_fantasy_points_exp vs expected components",
    EXPECTED_FANTASY_CONTRACT_TOLERANCE, EXPECTED_FANTASY_CONTRACT_HARD_CAP,
    EXPECTED_FANTASY_CONTRACT_SEASON_BUDGET,
)


def fantasy_contract(weekly: pl.DataFrame, ep: pl.DataFrame) -> list[str]:
    """Our components, scored with ffopportunity's rules, reproduce its totals.

    Fumbles are removed from both sides: ours include sack fumbles, which
    theirs don't. See `ComparisonPolicy` for why a mismatch alone doesn't
    fail the build.
    """
    joined = _join_sources(weekly, ep)
    c = lambda name: pl.col(name).fill_null(0)  # noqa: E731
    ours = _reference_points(
        c("receptions"), c("receiving_yards"), c("receiving_tds"), c("receiving_2pt"),
        c("rushing_yards"), c("rushing_tds"), c("rushing_2pt"),
        c("passing_yards"), c("passing_tds"), c("passing_2pt"), c("interceptions"),
    )
    theirs = c("total_fantasy_points") + 2 * (c("rec_fumble_lost") + c("rush_fumble_lost"))
    joined = joined.with_columns(_our_points=ours)
    magnitude = (pl.col("_our_points") - theirs).abs()
    problems = _apply_policy(
        joined, magnitude, FANTASY_CONTRACT_POLICY, ["total_fantasy_points", "_our_points"]
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
    x = x.with_columns(_our_expected_points=expected)
    magnitude = (pl.col("_our_expected_points") - c("total_fantasy_points_exp")).abs()
    problems += _apply_policy(
        x, magnitude, EXPECTED_FANTASY_CONTRACT_POLICY,
        ["total_fantasy_points_exp", "_our_expected_points"],
    )
    return problems
