"""The registry is the contract with the app: ids the Kotlin side reads verbatim."""

from gridiron_etl.metrics import METRICS, sparse_metric_ids

ACTUAL = [
    "passing_first_downs", "rushing_first_downs", "receiving_first_downs",
    "passing_2pt", "rushing_2pt", "receiving_2pt", "fumbles_lost",
    "passing_tds_40", "passing_tds_50", "rushing_tds_40", "rushing_tds_50",
    "receiving_tds_40", "receiving_tds_50",
]
EXPECTED = [
    "x_completions", "x_receptions", "x_passing_yards", "x_rushing_yards",
    "x_receiving_yards", "x_passing_tds", "x_rushing_tds", "x_receiving_tds",
    "x_passing_2pt", "x_rushing_2pt", "x_receiving_2pt", "x_passing_first_downs",
    "x_rushing_first_downs", "x_receiving_first_downs", "x_interceptions",
]
COMPUTED = {"fantasy_points": "FPTS", "expected_fantasy_points": "xFP", "fpoe": "FPOE"}


def test_scoring_inputs_are_internal_sparse_and_not_computed():
    for mid in ACTUAL + EXPECTED:
        m = METRICS[mid]
        assert m.internal, mid
        assert not m.computed, mid
        assert mid in sparse_metric_ids(), mid


def test_fantasy_columns_are_visible_and_computed():
    for mid, abbr in COMPUTED.items():
        m = METRICS[mid]
        assert m.computed and not m.internal, mid
        assert m.abbr == abbr
        assert m.group == "fantasy"
        assert mid not in sparse_metric_ids()


def test_existing_metrics_are_not_sparse():
    # Zeros stay stored for everything the Grid already shows.
    for mid in ("targets", "carries", "interceptions", "g", "team_targets"):
        assert mid not in sparse_metric_ids()


def test_carries_eff_is_an_internal_denominator_like_its_peers():
    # Same treatment as team_carries: internal (never a column), not sparse
    # (zeros stay stored, matching `carries` itself).
    m = METRICS["carries_eff"]
    assert m.internal
    assert not m.computed
    assert "carries_eff" not in sparse_metric_ids()


def test_projected_stats_have_distribution_families():
    from gridiron_etl.metrics import DIST_FAMILIES

    assert METRICS["targets"].dist_family == "negbinom"
    assert METRICS["receptions"].dist_family == "binomial"
    assert METRICS["receiving_yards"].dist_family == "gamma"
    assert METRICS["receiving_tds"].dist_family == "poisson"
    assert METRICS["target_share"].dist_family is None
    for mid, family in DIST_FAMILIES.items():
        assert METRICS[mid].dist_family == family, mid


KICKING_VISIBLE = ["fg_made", "fg_att", "fg_made_50", "xp_made", "xp_att"]
KICKING = KICKING_VISIBLE + ["fg_att_0_39", "fg_att_40_49", "fg_att_50", "fg_made_0_39", "fg_made_40_49",
                             "fg_missed", "xp_missed"]
DEFENSE = ["dst_sacks", "dst_interceptions", "dst_fumble_recoveries", "dst_tds", "dst_safeties"]


def test_kicking_and_defense_metrics_are_sparse_theirs_alone_and_visible_where_the_grid_shows_them():
    for mid in KICKING + DEFENSE:
        m = METRICS[mid]
        assert not m.computed, mid
        assert m.internal == (mid in KICKING and mid not in KICKING_VISIBLE), mid
        assert mid in sparse_metric_ids(), mid
    assert all(METRICS[m].positions == ("K",) for m in KICKING)
    assert all(METRICS[m].positions == ("DST",) for m in DEFENSE + ["points_allowed", "yards_allowed"])
    assert not METRICS["points_allowed"].internal
    assert "points_allowed" not in sparse_metric_ids()
    assert METRICS["points_allowed"].dist_family == "normal"
    assert not METRICS["yards_allowed"].internal
    assert "yards_allowed" not in sparse_metric_ids()
    assert METRICS["yards_allowed"].dist_family == "normal"
    assert not METRICS["yards_allowed"].higher_is_better
    assert not any(mid.startswith("pa_") for mid in METRICS)
    assert len(METRICS) == 122


NGS_VISIBLE = {
    "ngs_time_to_throw": ("QB",), "ngs_aggressiveness": ("QB",), "ngs_intended_air_yards": ("QB",),
    "ngs_ryoe": ("QB", "RB"), "ngs_ryoe_per_att": ("QB", "RB"),
    "ngs_rush_efficiency": ("QB", "RB"), "ngs_stacked_box_pct": ("QB", "RB"),
    "ngs_separation": ("RB", "WR", "TE"), "ngs_cushion": ("RB", "WR", "TE"),
    "ngs_yac_over_expected": ("RB", "WR", "TE"),
}
NGS_INTERNAL = [
    "ngs_attempts", "ngs_carries", "ngs_targets", "ngs_receptions",
    "ngs_ttt_w", "ngs_aggr_w", "ngs_iay_w", "ngs_eff_w", "ngs_box_w", "ngs_sep_w", "ngs_cush_w", "ngs_yacoe_w",
]


def test_ngs_metrics():
    for mid, positions in NGS_VISIBLE.items():
        m = METRICS[mid]
        assert not m.internal and not m.computed and m.tier == "B" and m.group == "ngs", mid
        assert sorted(m.positions) == sorted(positions), mid
        assert mid in sparse_metric_ids(), mid
    assert not METRICS["ngs_rush_efficiency"].higher_is_better
    for mid in NGS_INTERNAL:
        assert METRICS[mid].internal and mid in sparse_metric_ids(), mid

