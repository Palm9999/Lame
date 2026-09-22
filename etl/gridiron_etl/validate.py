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

    for p in problems:
        log.error("VALIDATION: %s", p)
    if problems and strict:
        raise ValidationError(f"{len(problems)} validation failure(s); first: {problems[0]}")
    if not problems:
        log.info("validation passed — %d checks", len(RANGE_CHECKS) + 4)
    return problems
