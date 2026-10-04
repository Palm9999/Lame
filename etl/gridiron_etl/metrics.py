"""Metric registry.

Every metric the app knows about is declared here once, with the metadata that
drives the UI. Adding a metric is a data change, not a schema migration — the
fact table is long/narrow, so nothing here alters SQLite's shape.

`stability` is the year-over-year or week-over-week correlation where a published
figure exists. It drives how hard the projection model shrinks the metric, and is
surfaced to the user so an unstable stat can't be mistaken for a reliable one.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, replace
from typing import Literal

Tier = Literal["A", "B", "C", "D"]
Group = Literal["volume", "efficiency", "fantasy", "context", "passing", "usage", "kicking", "defense", "ngs", "ftn"]


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
    # Internal components are never shown as columns. They exist so rate metrics
    # can be recomputed correctly over a week range: target share over weeks
    # 1-8 is sum(targets) / sum(team_targets), not the mean of eight weekly
    # shares, and that requires the denominators to be stored.
    internal: bool = False
    # Computed on the device from other components and the user's scoring
    # profile. Registered for display metadata only; never stored as facts.
    computed: bool = False
    # Zero values are not stored: absent means zero. For scoring inputs, which
    # are zero for most player-weeks. An ETL concern only; not in the schema.
    sparse: bool = False
    # Distribution family for on-device Monte Carlo: 'negbinom' | 'binomial' |
    # 'gamma' | 'poisson'. None for metrics that aren't projected.
    dist_family: str | None = None
    zero_inflated: bool = False


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
           "Rushing attempts, QB kneels included, matching the box score.",
           positions=("QB", "RB", "WR"), decimals=0, hot=True),
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
           "Carries plus targets weighted by their relative PPR value. QB kneels "
           "are not opportunities and are excluded.",
           formula="carries (kneels excluded) + 2.6 * targets", positions=("RB",),
           predicts="PPR fantasy points", decimals=1, hot=True),
    Metric("opportunity_share", "Opportunity Share", "OPP%", "volume",
           "Share of the team's backfield carries and targets. Above 70% is a bellcow.",
           formula="(carries + targets) / (team_rb_carries + team_rb_targets)",
           positions=("RB",), stability=0.66, decimals=3),
    Metric("rz_carries", "Red Zone Carries", "RZ CAR", "usage",
           "Carries starting inside the opponent 20, QB kneels excluded.",
           positions=("QB", "RB"), decimals=0, hot=True),
    Metric("gz_carries", "Green Zone Carries", "GZ CAR", "usage",
           "Carries starting inside the opponent 10, QB kneels excluded. Roughly "
           "74% of rushing touchdowns originate here.",
           positions=("QB", "RB"), predicts="Rushing touchdowns", decimals=0, hot=True),
    Metric("gl_carries", "Goal Line Carries", "GL CAR", "usage",
           "Carries starting inside the opponent 5, QB kneels excluded. Roughly "
           "68% of rushing touchdowns originate here.",
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
           "Designed QB runs inside the opponent 5 (no scrambles or kneels). The "
           "biggest single source of QB fantasy separation.",
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

    # ---------------- Fantasy (computed on the device) ----------------
    Metric("fantasy_points", "Fantasy Points", "FPTS", "fantasy",
           "Points under the active scoring profile, scored game by game so "
           "per-game bonuses apply to single games.", decimals=1, computed=True),
    Metric("expected_fantasy_points", "Expected Fantasy Points", "xFP", "fantasy",
           "Points an average player would score from the same opportunities: "
           "the active profile applied to the opportunity model's expected "
           "receptions, yards, touchdowns and first downs.",
           predicts="Future fantasy points, better than past points do",
           decimals=1, computed=True),
    Metric("fpoe", "Fantasy Points Over Expected", "FPOE", "fantasy",
           "Actual fantasy points minus expected. Positive is a sell-high "
           "signal, negative a buy-low signal. Long plays and fumbles have no "
           "expectation, so they land here.",
           formula="fantasy_points - expected_fantasy_points",
           predicts="Negative regression when high", stability=0.12,
           decimals=1, computed=True),
    Metric("total_epa", "Total EPA", "EPA", "efficiency",
           "Expected points added across all touches.", decimals=2),
    Metric("rising_roles", "Rising Roles", "RISE", "usage",
           "How fast a role is growing, 0 to 100, entering the week after the range: usage and expected points over "
           "the last four games against the eight before, plus teammates who are out. Written by the forecast; "
           "it says the role is growing, not that points will follow.",
           positions=("RB", "WR", "TE"), predicts="Whether the role keeps growing over the next four games",
           decimals=0, computed=True),

    # ---------------- Scoring inputs (internal, sparse) ----------------
    *[
        Metric(mid, name, mid.upper(), "fantasy", definition,
               decimals=dec, internal=True, sparse=True)
        for mid, name, definition, dec in [
            ("passing_first_downs", "Passing First Downs", "First downs gained by completions, credited to the passer.", 0),
            ("rushing_first_downs", "Rushing First Downs", "First downs gained on carries.", 0),
            ("receiving_first_downs", "Receiving First Downs", "First downs gained on receptions.", 0),
            ("passing_2pt", "Passing 2-pt Conversions", "Successful two-point passes.", 0),
            ("rushing_2pt", "Rushing 2-pt Conversions", "Successful two-point runs.", 0),
            ("receiving_2pt", "Receiving 2-pt Conversions", "Successful two-point catches.", 0),
            ("fumbles_lost", "Fumbles Lost", "Fumbles lost by the ball carrier, including sack fumbles.", 0),
            ("passing_tds_40", "40+ Yd Passing TDs", "Passing touchdowns of at least 40 yards.", 0),
            ("passing_tds_50", "50+ Yd Passing TDs", "Passing touchdowns of at least 50 yards.", 0),
            ("rushing_tds_40", "40+ Yd Rushing TDs", "Rushing touchdowns of at least 40 yards.", 0),
            ("rushing_tds_50", "50+ Yd Rushing TDs", "Rushing touchdowns of at least 50 yards.", 0),
            ("receiving_tds_40", "40+ Yd Receiving TDs", "Receiving touchdowns of at least 40 yards.", 0),
            ("receiving_tds_50", "50+ Yd Receiving TDs", "Receiving touchdowns of at least 50 yards.", 0),
            ("x_completions", "Expected Completions", "Opportunity-model expected completions.", 2),
            ("x_receptions", "Expected Receptions", "Opportunity-model expected receptions.", 2),
            ("x_passing_yards", "Expected Passing Yards", "Opportunity-model expected passing yards.", 2),
            ("x_rushing_yards", "Expected Rushing Yards", "Opportunity-model expected rushing yards.", 2),
            ("x_receiving_yards", "Expected Receiving Yards", "Opportunity-model expected receiving yards.", 2),
            ("x_passing_tds", "Expected Passing TDs", "Opportunity-model expected passing touchdowns.", 2),
            ("x_rushing_tds", "Expected Rushing TDs", "Opportunity-model expected rushing touchdowns.", 2),
            ("x_receiving_tds", "Expected Receiving TDs", "Opportunity-model expected receiving touchdowns.", 2),
            ("x_passing_2pt", "Expected Passing 2-pt", "Opportunity-model expected two-point passes.", 2),
            ("x_rushing_2pt", "Expected Rushing 2-pt", "Opportunity-model expected two-point runs.", 2),
            ("x_receiving_2pt", "Expected Receiving 2-pt", "Opportunity-model expected two-point catches.", 2),
            ("x_passing_first_downs", "Expected Passing First Downs", "Opportunity-model expected passing first downs.", 2),
            ("x_rushing_first_downs", "Expected Rushing First Downs", "Opportunity-model expected rushing first downs.", 2),
            ("x_receiving_first_downs", "Expected Receiving First Downs", "Opportunity-model expected receiving first downs.", 2),
            ("x_interceptions", "Expected Interceptions", "Opportunity-model expected interceptions thrown.", 2),
        ]
    ],

    # ---------------- Kicking (sparse; the Grid's Kicking pack shows five) ----------------
    *[
        Metric(mid, name, abbr or mid.upper(), "kicking", definition,
               positions=("K",), decimals=0, internal=abbr is None, sparse=True)
        for mid, abbr, name, definition in [
            ("fg_att", "FGA", "FG Attempts", "Field goal tries, any distance, blocked kicks included."),
            ("fg_made", "FGM", "FGs Made", "Field goals made, any distance."),
            ("fg_att_0_39", None, "FG Attempts 0-39", "Field goal tries from 39 yards or closer, blocked kicks included."),
            ("fg_att_40_49", None, "FG Attempts 40-49", "Field goal tries from 40 to 49 yards, blocked kicks included."),
            ("fg_att_50", None, "FG Attempts 50+", "Field goal tries from 50 yards or farther, blocked kicks included."),
            ("fg_made_0_39", None, "FGs Made 0-39", "Field goals made from 39 yards or closer."),
            ("fg_made_40_49", None, "FGs Made 40-49", "Field goals made from 40 to 49 yards."),
            ("fg_made_50", "FG50", "FGs Made 50+", "Field goals made from 50 yards or farther."),
            ("fg_missed", None, "FGs Missed", "Field goals missed or blocked, any distance."),
            ("xp_att", "XPA", "XP Attempts", "Extra point kicks tried."),
            ("xp_made", "XPM", "XPs Made", "Extra point kicks made."),
            ("xp_missed", None, "XPs Missed", "Extra point kicks missed, blocked or aborted."),
        ]
    ],

    # ---------------- Team defense (D/ST pseudo-players; the Grid's Defense pack) ----------------
    *[
        Metric(mid, name, abbr, "defense", definition,
               positions=("DST",), decimals=0, sparse=True)
        for mid, abbr, name, definition in [
            ("dst_sacks", "SACK", "D/ST Sacks", "Sacks by the team's defense."),
            ("dst_interceptions", "DINT", "D/ST Interceptions", "Passes the team's defense intercepted."),
            ("dst_fumble_recoveries", "FR", "D/ST Fumble Recoveries", "Opponent fumbles the team recovered."),
            ("dst_tds", "DTD", "D/ST TDs", "Touchdowns by the defense or on a return: interceptions, fumbles, punts, kickoffs and blocked kicks."),
            ("dst_safeties", "SAF", "D/ST Safeties", "Safeties the team's defense scored."),
        ]
    ],
    Metric("points_allowed", "Points Allowed", "PA", "defense",
           "Points the opponent scored, however it scored them.",
           positions=("DST",), higher_is_better=False, decimals=0),
    Metric("yards_allowed", "Yards Allowed", "YA", "defense",
           "Net yards the opponent gained: rushing plus passing, sacks subtracted.",
           positions=("DST",), higher_is_better=False, decimals=0),

    # ---------------- Internal range-aggregation components ----------------
    *[
        Metric(mid, name, mid.upper(), "context", definition,
               positions=("QB", "RB", "WR", "TE"), decimals=dec, internal=True)
        for mid, name, definition, dec in [
            ("g", "Games", "1 for each week the player recorded a play. Summed "
             "over a range it gives games played.", 0),
            ("team_targets", "Team Targets",
             "Team targets in games this player appeared in.", 0),
            ("team_air_yards", "Team Air Yards",
             "Team air yards in games this player appeared in.", 0),
            ("team_carries", "Team Carries",
             "Team carries in games this player appeared in.", 0),
            ("carries_eff", "Efficiency Carries",
             "Carries excluding QB kneels and spikes — the denominator behind "
             "carry_share, rush_success_rate and rush_epa_per_carry, so those "
             "rates recompute correctly over a range instead of drifting once "
             "a kneel enters the box-score carries total. Weighted "
             "opportunities are built on it too.", 0),
            ("team_offense_snaps", "Team Offensive Snaps",
             "Team offensive snaps in games this player appeared in.", 0),
            ("rush_successes", "Rush Successes", "Carries with positive EPA.", 0),
            ("rush_epa", "Rush EPA", "Summed EPA on carries.", 3),
            ("rec_epa", "Receiving EPA", "Summed EPA on targets.", 3),
            ("pass_epa", "Pass EPA", "Summed EPA on dropbacks.", 3),
            ("cpoe_sum", "CPOE Sum", "Summed per-attempt CPOE.", 3),
            ("cpoe_n", "CPOE Attempts", "Attempts with a CPOE value.", 0),
        ]
    ],
    # ---------------- Next Gen Stats (tier B; core/ingest's Ngs.kt is the twin) ----------------
    # NGS publishes per-week averages. Each is stored as average x weight beside its
    # weight, so a range recomputes as sum(avg x weight) / sum(weight), never a mean of means.
    # The weekly average is stored too, under the metric's own id. None is sparse: a 0% stacked-box week is real.
    *[
        Metric(mid, name, abbr, "ngs", definition, positions=positions, tier="B",
               higher_is_better=better, decimals=dec)
        for mid, abbr, name, positions, better, dec, definition in [
            ("ngs_time_to_throw", "TTT", "Time to Throw", ("QB",), True, 2,
             "Average seconds from snap to release on attempts, sacks excluded. Under 2.5 is a quick game; over 3.0 holds the ball. Only weeks with 15 or more attempts are published."),
            ("ngs_aggressiveness", "AGG%", "Aggressiveness", ("QB",), True, 1,
             "Share of attempts thrown into tight windows, with a defender within a yard of the receiver at the catch point. A risk profile, not a quality measure. Only weeks with 15 or more attempts are published."),
            ("ngs_intended_air_yards", "IAY", "Intended Air Yards", ("QB",), True, 1,
             "Average air yards on attempts, measured by player tracking. Only weeks with 15 or more attempts are published."),
            ("ngs_ryoe", "RYOE", "Rush Yards Over Expected", ("RB",), True, 1,
             "Actual rushing yards minus the tracking model's expectation from blocker and defender positions. Only weeks with 10 or more carries are published."),
            ("ngs_ryoe_per_att", "RYOE/A", "RYOE per Carry", ("RB",), True, 2,
             "Rush yards over expected per carry. Only weeks with 10 or more carries are published."),
            ("ngs_rush_efficiency", "EFF", "Rushing Efficiency", ("RB",), False, 2,
             "Yards a rusher travels per rushing yard gained. Lower is a more direct, north-south runner. Only weeks with 10 or more carries are published."),
            ("ngs_stacked_box_pct", "8+ BOX%", "Carries vs 8+ in the Box", ("RB",), True, 1,
             "Share of carries with eight or more defenders in the box: the front the runner faced, not his skill. Only weeks with 10 or more carries are published."),
            ("ngs_separation", "SEP", "Average Separation", ("RB", "WR", "TE"), True, 2,
             "Average yards between the receiver and the nearest defender when the pass arrives. Only weeks with 5 or more targets are published."),
            ("ngs_cushion", "CUSH", "Average Cushion", ("RB", "WR", "TE"), True, 2,
             "Average yards between the receiver and the nearest defender at the snap. Only weeks with 5 or more targets are published."),
            ("ngs_yac_over_expected", "YACOE", "YAC Over Expected", ("RB", "WR", "TE"), True, 2,
             "Average yards after catch above the tracking model's expectation, per reception. Only weeks with 5 or more targets are published."),
        ]
    ],
    *[
        Metric(mid, name, mid.upper(), "ngs", definition, positions=("QB", "RB", "WR", "TE"),
               tier="B", decimals=dec, internal=True)
        for mid, name, definition, dec in [
            ("ngs_attempts", "NGS Attempts", "Attempts in NGS's passing file: the weight behind its passing averages.", 0),
            ("ngs_carries", "NGS Carries", "Carries in NGS's rushing file: the weight behind its rushing averages.", 0),
            ("ngs_rush_yards", "NGS Rush Yards", "Rushing yards in NGS's rushing file: the weight behind efficiency.", 0),
            ("ngs_targets", "NGS Targets", "Targets in NGS's receiving file: the weight behind separation and cushion.", 0),
            ("ngs_receptions", "NGS Receptions", "Receptions in NGS's receiving file: the weight behind YAC over expected.", 0),
            ("ngs_ttt_w", "NGS Time to Throw x Attempts", "Weekly average time to throw times attempts.", 3),
            ("ngs_aggr_w", "NGS Aggressiveness x Attempts", "Weekly aggressiveness times attempts.", 3),
            ("ngs_iay_w", "NGS Intended Air Yards x Attempts", "Weekly average intended air yards times attempts.", 3),
            ("ngs_eff_w", "NGS Efficiency x Carries", "Weekly rushing efficiency times carries.", 3),
            ("ngs_box_w", "NGS 8+ Box x Carries", "Weekly share of carries against eight or more defenders, times carries.", 3),
            ("ngs_sep_w", "NGS Separation x Targets", "Weekly average separation times targets.", 3),
            ("ngs_cush_w", "NGS Cushion x Targets", "Weekly average cushion times targets.", 3),
            ("ngs_yacoe_w", "NGS YAC Over Expected x Receptions", "Weekly average YAC over expected times receptions.", 3),
        ]
    ],
    # ---------------- FTN charting (tier B; core/ingest's Ftn.kt is the twin) ----------------
    # FTN charts every play from 2022; flags are attributed to the target receiver and the
    # passer through play-by-play. Counts sit beside FTN's own denominators (plays FTN charted),
    # so a range recomputes as sum(count) / sum(denominator), never a mean of weekly rates.
    # None is sparse: a week with no drops is a real zero.
    *[
        Metric(mid, name, abbr, "ftn", definition, positions=positions, tier="B",
               higher_is_better=better, decimals=dec)
        for mid, abbr, name, positions, better, dec, definition in [
            ("ftn_catchable_rate", "CATCH%", "Catchable Target Rate", ("RB", "WR", "TE"), True, 1,
             "Share of targets FTN's charters marked catchable: a target the receiver could have caught. Charted from 2022."),
            ("ftn_drop_rate", "DRP%", "Drop Rate", ("RB", "WR", "TE"), False, 1,
             "Drops per target, as charted by FTN. Charted from 2022."),
            ("ftn_contested_rate", "CTD%", "Contested Target Rate", ("RB", "WR", "TE"), True, 1,
             "Share of targets FTN charted as contested: a defender close enough to affect the catch. A role profile, not a quality measure. Charted from 2022."),
            ("ftn_drops", "DRP", "Drops", ("RB", "WR", "TE"), False, 0,
             "Passes FTN charted as dropped. Charted from 2022."),
            ("ftn_created_rec", "CRT", "Created Receptions", ("RB", "WR", "TE"), True, 0,
             "Receptions FTN charted as created by the receiver: a catch the throw did not make easy. Charted from 2022."),
            ("ftn_play_action_rate", "PA%", "Play-Action Rate", ("QB",), True, 1,
             "Share of dropbacks that were play-action, as charted by FTN. A scheme profile. Charted from 2022."),
            ("ftn_blitz_rate", "BLZ%", "Blitz Rate Faced", ("QB",), True, 1,
             "Share of dropbacks where FTN charted at least one blitzer. Charted from 2022."),
            ("ftn_out_of_pocket_rate", "OOP%", "Out-of-Pocket Rate", ("QB",), True, 1,
             "Share of dropbacks where the quarterback left the pocket, as charted by FTN. Charted from 2022."),
            ("ftn_throwaway_rate", "TA%", "Throwaway Rate", ("QB",), False, 1,
             "Share of dropbacks ended with an intentional throwaway, as charted by FTN. Charted from 2022."),
            ("ftn_int_worthy_rate", "IW%", "Interception-Worthy Rate", ("QB",), False, 1,
             "Share of pass attempts FTN charted as interception-worthy, whether or not the defense caught them. Charted from 2022."),
        ]
    ],
    *[
        Metric(mid, name, mid.upper(), "ftn", definition, positions=positions,
               tier="B", decimals=0, internal=True)
        for mid, name, definition, positions in [
            ("ftn_targets", "FTN Targets", "Targets on plays FTN charted: the denominator of the receiver rates.", ("RB", "WR", "TE")),
            ("ftn_catchable", "FTN Catchable Targets", "Targets FTN marked catchable.", ("RB", "WR", "TE")),
            ("ftn_contested", "FTN Contested Targets", "Targets FTN marked contested.", ("RB", "WR", "TE")),
            ("ftn_dropbacks", "FTN Dropbacks", "Dropbacks (attempts, sacks and scrambles) on plays FTN charted: the denominator of the QB rates.", ("QB",)),
            ("ftn_attempts", "FTN Attempts", "Pass attempts on plays FTN charted: the denominator of the interception-worthy rate.", ("QB",)),
            ("ftn_pa_db", "FTN Play-Action Dropbacks", "Charted dropbacks that were play-action.", ("QB",)),
            ("ftn_blitz_db", "FTN Blitzed Dropbacks", "Charted dropbacks against at least one blitzer.", ("QB",)),
            ("ftn_oop_db", "FTN Out-of-Pocket Dropbacks", "Charted dropbacks where the quarterback left the pocket.", ("QB",)),
            ("ftn_throwaway", "FTN Throwaways", "Charted dropbacks ended with a throwaway.", ("QB",)),
            ("ftn_int_worthy", "FTN Interception-Worthy Throws", "Charted pass attempts FTN marked interception-worthy.", ("QB",)),
        ]
    ],
]

# The distribution the phone's floor/ceiling simulation draws each projected
# stat from. Mirrors core/ingest's Metrics.kt DIST_FAMILIES; the parity job
# compares the metric table's dist_family column between the two builds.
DIST_FAMILIES: dict[str, str] = {
    **{m: "negbinom" for m in ("attempts", "carries", "targets")},
    **{m: "binomial" for m in ("completions", "receptions")},
    **{m: "gamma" for m in ("passing_yards", "rushing_yards", "receiving_yards")},
    **{m: "poisson" for m in (
        "passing_tds", "passing_tds_40", "passing_tds_50", "interceptions", "sacks_taken",
        "passing_first_downs", "passing_2pt",
        "rushing_tds", "rushing_tds_40", "rushing_tds_50", "rushing_first_downs", "rushing_2pt",
        "receiving_tds", "receiving_tds_40", "receiving_tds_50", "receiving_first_downs",
        "receiving_2pt", "fumbles_lost",
    )},
    **{m: "poisson" for m in (
        "fg_att", "fg_made", "fg_att_0_39", "fg_att_40_49", "fg_att_50", "fg_made_0_39", "fg_made_40_49",
        "fg_made_50", "fg_missed", "xp_att", "xp_made", "xp_missed",
        "dst_interceptions", "dst_fumble_recoveries", "dst_tds", "dst_safeties",
    )},
    "dst_sacks": "negbinom",
    "points_allowed": "normal",
    "yards_allowed": "normal",
}

METRICS: dict[str, Metric] = {
    m.id: replace(m, dist_family=DIST_FAMILIES.get(m.id, m.dist_family)) for m in _M
}


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
    return [m.id for m in METRICS.values() if m.hot and not m.internal]


def sparse_metric_ids() -> frozenset[str]:
    """Metrics whose zero values are not stored."""
    return frozenset(m.id for m in METRICS.values() if m.sparse)
