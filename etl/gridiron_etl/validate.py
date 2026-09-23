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
# passing-yards regression elsewhere still trips at 1.0.
_LATERAL_YARDS_TOLERANCE = 45.0

# Our play-by-play actual -> ffopportunity's actual column for the same stat,
# each with its cross-check tolerance (usually the shared default).
CROSS_CHECK_PAIRS: tuple[tuple[str, str, float], ...] = (
    ("receptions", "receptions", CROSS_CHECK_TOLERANCE),
    ("completions", "pass_completions", CROSS_CHECK_TOLERANCE),
    ("passing_yards", "pass_yards_gained", _LATERAL_YARDS_TOLERANCE),
    ("rushing_yards", "rush_yards_gained", CROSS_CHECK_TOLERANCE),
    ("receiving_yards", "rec_yards_gained", CROSS_CHECK_TOLERANCE),
    ("passing_tds", "pass_touchdown", CROSS_CHECK_TOLERANCE),
    ("rushing_tds", "rush_touchdown", CROSS_CHECK_TOLERANCE),
    ("receiving_tds", "rec_touchdown", CROSS_CHECK_TOLERANCE),
    ("passing_2pt", "pass_two_point_conv", CROSS_CHECK_TOLERANCE),
    ("rushing_2pt", "rush_two_point_conv", CROSS_CHECK_TOLERANCE),
    ("receiving_2pt", "rec_two_point_conv", CROSS_CHECK_TOLERANCE),
    ("passing_first_downs", "pass_first_down", CROSS_CHECK_TOLERANCE),
    ("rushing_first_downs", "rush_first_down", CROSS_CHECK_TOLERANCE),
    ("receiving_first_downs", "rec_first_down", CROSS_CHECK_TOLERANCE),
    ("interceptions", "pass_interception", CROSS_CHECK_TOLERANCE),
)
KEYS = ["player_id", "season", "week"]


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


def cross_check(weekly: pl.DataFrame, ep: pl.DataFrame) -> list[str]:
    """Our play-by-play actuals against ffopportunity's, per player-week.

    Two independent derivations of the same stat from the same plays: a
    mismatch means one of them is wrong, most likely ours.
    """
    joined = _join_sources(weekly, ep)
    problems = []
    for ours, col, tolerance in CROSS_CHECK_PAIRS:
        theirs = _theirs(weekly, col)
        bad = joined.filter(
            (pl.col(ours).fill_null(0) - pl.col(theirs).fill_null(0)).abs() > tolerance
        )
        if bad.height:
            problems.append(
                f"cross-check {ours} vs ffopportunity {col}: {bad.height} player-weeks "
                f"differ by more than {tolerance}, e.g. {_examples(bad, [ours, theirs])}"
            )
    # Ours also counts sack fumbles, so it normally meets or exceeds theirs.
    # Exception, observed in 2024: ffopportunity sometimes attributes a fumble
    # to the wrong offensive player rather than the one play-by-play's
    # fumbled_1_player_id names. ARI wk7: the pass to J.Conner is intercepted
    # (never reaches him) and the *defender* fumbles the return (T.Tart) — ours
    # correctly excludes it (a defender's fumble isn't an offensive stat), but
    # ffopportunity still charges Conner. PIT wk5: a multi-lateral trick play's
    # final fumble belongs to P.Freiermuth, but ffopportunity charges it to
    # G.Pickens, the play's original receiver. ATL wk1: a botched shotgun snap
    # is fumbled by the center (fumbled_1_player_id) but ffopportunity charges
    # the QB (K.Cousins), matching nflverse's rusher_player_id for the play,
    # which ours doesn't credit since the fumbler doesn't match any skill id.
    # Each case is a single isolated fumble, so a player-week is allowed to
    # trail by exactly one rather than loosening the check to uselessness.
    FUMBLE_TOLERANCE = 1
    theirs_fumbles = pl.col("rec_fumble_lost").fill_null(0) + pl.col("rush_fumble_lost").fill_null(0)
    bad = joined.filter(pl.col("fumbles_lost").fill_null(0) < theirs_fumbles - FUMBLE_TOLERANCE)
    if bad.height:
        problems.append(
            f"cross-check fumbles_lost below ffopportunity's rush + receiving fumbles in "
            f"{bad.height} player-weeks, e.g. {_examples(bad, ['fumbles_lost'])}"
        )
    return problems


def _reference_points(receptions, rec_yds, rec_td, rec_2pt, rush_yds, rush_td, rush_2pt,
                      pass_yds, pass_td, pass_2pt, ints) -> pl.Expr:
    """The profile ffopportunity totals use, fumbles excluded."""
    return (
        receptions + 0.1 * rec_yds + 6 * rec_td + 2 * rec_2pt
        + 0.1 * rush_yds + 6 * rush_td + 2 * rush_2pt
        + 0.04 * pass_yds + 4 * pass_td + 2 * pass_2pt - 2 * ints
    )


# Points a total_fantasy_points mismatch may carry before it's flagged.
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
# The file rounds every component (and the total) to two decimals
# independently, so a sum of several components can be off from the reported
# total by more than one 0.005 rounding step. Theoretical worst case for the
# reference profile's 11 weighted components is ~0.13; observed max across
# 2024-2026 is 0.213 (2025), so this carries some margin above that.
EXPECTED_FANTASY_CONTRACT_TOLERANCE = 0.25


def fantasy_contract(weekly: pl.DataFrame, ep: pl.DataFrame) -> list[str]:
    """Our components, scored with ffopportunity's rules, reproduce its totals.

    Fumbles are removed from both sides: ours include sack fumbles, which
    theirs don't.
    """
    joined = _join_sources(weekly, ep)
    c = lambda name: pl.col(name).fill_null(0)  # noqa: E731
    ours = _reference_points(
        c("receptions"), c("receiving_yards"), c("receiving_tds"), c("receiving_2pt"),
        c("rushing_yards"), c("rushing_tds"), c("rushing_2pt"),
        c("passing_yards"), c("passing_tds"), c("passing_2pt"), c("interceptions"),
    )
    theirs = c("total_fantasy_points") + 2 * (c("rec_fumble_lost") + c("rush_fumble_lost"))
    problems = []
    bad = joined.filter((ours - theirs).abs() > FANTASY_CONTRACT_TOLERANCE)
    if bad.height:
        problems.append(
            f"fantasy contract: total_fantasy_points differs from our components in "
            f"{bad.height} player-weeks, e.g. {_examples(bad, ['total_fantasy_points'])}"
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
    bad = x.filter((expected - c("total_fantasy_points_exp")).abs() > EXPECTED_FANTASY_CONTRACT_TOLERANCE)
    if bad.height:
        problems.append(
            f"fantasy contract: total_fantasy_points_exp differs from expected components in "
            f"{bad.height} player-weeks, e.g. {_examples(bad, ['total_fantasy_points_exp'])}"
        )
    return problems
