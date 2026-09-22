"""Metric registry.

Every metric the app knows about is declared here once, with the metadata that
drives the UI. Adding a metric is a data change, not a schema migration — the
fact table is long/narrow, so nothing here alters SQLite's shape.

`stability` is the year-over-year or week-over-week correlation where a published
figure exists. It drives how hard the projection model shrinks the metric, and is
surfaced to the user so an unstable stat can't be mistaken for a reliable one.
"""

from __future__ import annotations

from dataclasses import dataclass, asdict
from typing import Literal

Tier = Literal["A", "B", "C", "D"]
Group = Literal["volume", "efficiency", "fantasy", "context", "passing", "usage"]


@dataclass(frozen=True)
class Metric:
    id: str
    name: str
    abbr: str
    group: Group
    definition: str
    formula: str | None = None
    positions: tuple[str, ...] = ("QB", "RB", "WR", "TE")
    # A = free from play-by-play, B = free auxiliary feed,
    # C = proprietary but rebuildable in-house, D = licensed only.
    tier: Tier = "A"
    predicts: str | None = None
    stability: float | None = None
    higher_is_better: bool = True
    decimals: int = 1
    # Hot metrics become real indexed SQLite columns; the rest live in a JSON blob.
    hot: bool = False


_M: list[Metric] = [
    # ---------------- Receiving volume ----------------
    Metric("targets", "Targets", "TGT", "volume",
           "Pass attempts thrown in this player's direction, including incompletions.",
           positions=("RB", "WR", "TE"), predicts="Receiving production floor",
           stability=0.70, decimals=0, hot=True),
    Metric("receptions", "Receptions", "REC", "volume",
           "Completed catches.", positions=("RB", "WR", "TE"), decimals=0, hot=True),
    Metric("receiving_yards", "Receiving Yards", "REC YDS", "volume",
           "Total yards gained on receptions.", positions=("RB", "WR", "TE"),
           decimals=0, hot=True),
    Metric("receiving_tds", "Receiving TDs", "REC TD", "volume",
           "Touchdowns scored as a receiver.", positions=("RB", "WR", "TE"),
           predicts="Weak — TD rate regresses hard", stability=0.28,
           decimals=0, hot=True),
    Metric("air_yards", "Air Yards", "AY", "volume",
           "Total distance the ball travelled in the air on all targets, caught or not.",
           positions=("RB", "WR", "TE"), predicts="Opportunity independent of catch outcome",
           stability=0.62, decimals=0, hot=True),
    Metric("target_share", "Target Share", "TGT%", "volume",
           "Share of the team's targets that went to this player.",
           formula="player_targets / team_targets",
           positions=("RB", "WR", "TE"),
           predicts="Most predictive raw receiving stat", stability=0.72,
           decimals=3, hot=True),
    Metric("air_yards_share", "Air Yards Share", "AY%", "volume",
           "Share of the team's air yards directed at this player. Can fall "
           "below 0 or above 1 in a single week: about 18% of pass attempts "
           "carry negative air yards, so a screen-heavy role produces a "
           "negative share and a teammate can exceed the team total.",
           formula="player_air_yards / team_air_yards",
           positions=("RB", "WR", "TE"), stability=0.65, decimals=3, hot=True),
    Metric("wopr", "Weighted Opportunity Rating", "WOPR", "volume",
           "Composite of target share and air yards share. The best single "
           "opportunity number for pass catchers; above 0.70 is elite.",
           formula="1.5 * target_share + 0.7 * air_yards_share",
           positions=("RB", "WR", "TE"),
           predicts="Receiving fantasy points", stability=0.71, decimals=3, hot=True),
    Metric("adot", "Average Depth of Target", "aDOT", "efficiency",
           "Mean air yards per target. A role classifier, not a quality measure — "
           "it makes catch rate interpretable.",
           formula="air_yards / targets", positions=("RB", "WR", "TE"),
           stability=0.76, decimals=1, hot=True),
    Metric("racr", "Receiver Air Conversion Ratio", "RACR", "efficiency",
           "Receiving yards produced per air yard thrown. Pairs with aDOT to "
           "separate deep threats from YAC producers.",
           formula="receiving_yards / air_yards", positions=("RB", "WR", "TE"),
           stability=0.35, decimals=2),
    Metric("yac", "Yards After Catch", "YAC", "efficiency",
           "Yards gained after the catch.", positions=("RB", "WR", "TE"), decimals=0),
    Metric("catch_rate", "Catch Rate", "CTCH%", "efficiency",
           "Receptions per target. Only interpretable alongside aDOT.",
           formula="receptions / targets", positions=("RB", "WR", "TE"), decimals=3),
    Metric("rz_targets", "Red Zone Targets", "RZ TGT", "usage",
           "Targets on snaps starting inside the opponent 20.",
           positions=("RB", "WR", "TE"), predicts="Touchdown opportunity",
           decimals=0, hot=True),
    Metric("ez_targets", "End Zone Targets", "EZ TGT", "usage",
           "Targets thrown to or beyond the goal line. Worth roughly 3.0 expected "
           "points versus 1.8 for a target from the 19.",
           formula="targets where air_yards >= yardline_100",
           positions=("RB", "WR", "TE"), predicts="Touchdown opportunity", decimals=0),

    # ---------------- Rushing ----------------
    Metric("carries", "Carries", "CAR", "volume",
           "Rushing attempts.", positions=("QB", "RB", "WR"), decimals=0, hot=True),
    Metric("rushing_yards", "Rushing Yards", "RUSH YDS", "volume",
           "Total rushing yards.", positions=("QB", "RB", "WR"), decimals=0, hot=True),
    Metric("rushing_tds", "Rushing TDs", "RUSH TD", "volume",
           "Rushing touchdowns.", positions=("QB", "RB", "WR"),
           stability=0.30, decimals=0, hot=True),
    Metric("carry_share", "Carry Share", "CAR%", "volume",
           "Share of the team's carries taken by this player.",
           formula="player_carries / team_carries", positions=("RB",),
           stability=0.68, decimals=3, hot=True),
    Metric("weighted_opportunities", "Weighted Opportunities", "WO", "volume",
           "Carries plus targets weighted by their relative PPR value.",
           formula="carries + 2.6 * targets", positions=("RB",),
           predicts="PPR fantasy points", decimals=1, hot=True),
    Metric("opportunity_share", "Opportunity Share", "OPP%", "volume",
           "Share of the team's backfield carries and targets. Above 70% is a bellcow.",
           formula="(carries + targets) / (team_rb_carries + team_rb_targets)",
           positions=("RB",), stability=0.66, decimals=3),
    Metric("rz_carries", "Red Zone Carries", "RZ CAR", "usage",
           "Carries starting inside the opponent 20.",
           positions=("QB", "RB"), decimals=0, hot=True),
    Metric("gz_carries", "Green Zone Carries", "GZ CAR", "usage",
           "Carries starting inside the opponent 10. Roughly 74% of rushing "
           "touchdowns originate here.",
           positions=("QB", "RB"), predicts="Rushing touchdowns", decimals=0, hot=True),
    Metric("gl_carries", "Goal Line Carries", "GL CAR", "usage",
           "Carries starting inside the opponent 5. Roughly 68% of rushing "
           "touchdowns originate here.",
           positions=("QB", "RB"), predicts="Rushing touchdowns", decimals=0),
    Metric("rush_success_rate", "Rush Success Rate", "RSR", "efficiency",
           "Share of carries producing positive EPA. Far more stable than yards "
           "per carry, which is notoriously noisy.",
           formula="rushes with epa > 0 / carries", positions=("QB", "RB"),
           stability=0.44, decimals=3),
    Metric("rush_epa_per_carry", "Rush EPA per Carry", "EPA/CAR", "efficiency",
           "Expected points added per rushing attempt.",
           positions=("QB", "RB"), decimals=3),

    # ---------------- Passing ----------------
    Metric("attempts", "Pass Attempts", "ATT", "passing",
           "Pass attempts.", positions=("QB",), decimals=0, hot=True),
    Metric("completions", "Completions", "CMP", "passing",
           "Completed passes.", positions=("QB",), decimals=0),
    Metric("passing_yards", "Passing Yards", "PASS YDS", "passing",
           "Total passing yards.", positions=("QB",), decimals=0, hot=True),
    Metric("passing_tds", "Passing TDs", "PASS TD", "passing",
           "Passing touchdowns.", positions=("QB",), stability=0.32,
           decimals=0, hot=True),
    Metric("interceptions", "Interceptions", "INT", "passing",
           "Interceptions thrown.", positions=("QB",),
           higher_is_better=False, decimals=0, hot=True),
    Metric("sacks_taken", "Sacks Taken", "SK", "passing",
           "Times sacked.", positions=("QB",), higher_is_better=False, decimals=0),
    Metric("dropbacks", "Dropbacks", "DB", "passing",
           "Pass attempts plus sacks plus scrambles.", positions=("QB",), decimals=0),
    Metric("epa_per_dropback", "EPA per Dropback", "EPA/DB", "efficiency",
           "Expected points added per dropback. The single best QB quality measure.",
           positions=("QB",), stability=0.55, decimals=3, hot=True),
    Metric("cpoe", "Completion % Over Expected", "CPOE", "efficiency",
           "Completion percentage above what the throw's difficulty predicts.",
           positions=("QB",), stability=0.47, decimals=2, hot=True),
    Metric("qb_rush_inside_5", "QB Carries Inside 5", "QB GL", "usage",
           "Designed QB runs inside the opponent 5. The biggest single source of "
           "QB fantasy separation.",
           positions=("QB",), predicts="QB rushing touchdowns", decimals=0),

    # ---------------- Snaps (tier B — auxiliary feed) ----------------
    Metric("offense_snaps", "Offensive Snaps", "SNAP", "volume",
           "Offensive snaps played.", tier="B", decimals=0, hot=True),
    Metric("snap_share", "Snap Share", "SNAP%", "volume",
           "Share of the team's offensive snaps played. The earliest reliable "
           "signal of a role change, and the strongest waiver indicator.",
           formula="offense_snaps / team_offense_snaps", tier="B",
           predicts="Role change before box score reflects it",
           stability=0.79, decimals=3, hot=True),

    # ---------------- Derived fantasy ----------------
    Metric("fpoe", "Fantasy Points Over Expected", "FPOE", "fantasy",
           "Actual fantasy points minus opportunity-expected points. Positive is "
           "a sell-high signal, negative a buy-low signal.",
           formula="actual_fp - expected_fp", predicts="Negative regression when high",
           stability=0.12, decimals=1),
    Metric("total_epa", "Total EPA", "EPA", "efficiency",
           "Expected points added across all touches.", decimals=2),
]

METRICS: dict[str, Metric] = {m.id: m for m in _M}


def metric_rows() -> list[dict]:
    """Registry as plain dicts, ready for insertion into the metadata table."""
    rows = []
    for m in METRICS.values():
        d = asdict(m)
        d["positions"] = ",".join(m.positions)
        rows.append(d)
    return rows


def hot_metric_ids() -> list[str]:
    """Metrics that get real indexed columns in the wide view."""
    return [m.id for m in METRICS.values() if m.hot]
