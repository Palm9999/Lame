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
