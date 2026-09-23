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
