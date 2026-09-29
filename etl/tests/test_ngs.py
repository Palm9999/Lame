"""Next Gen Stats components. Mirrors core/ingest's NgsTest.kt: same rows, same expected numbers."""

import polars as pl

from gridiron_etl import ngs


def passing(**kw) -> dict:
    return {"season": 2025, "week": 3, "team_abbr": "AAA", "player_gsis_id": "QB1", "attempts": 30,
            "avg_time_to_throw": 2.5, "aggressiveness": 20.0, "avg_intended_air_yards": 8.0, **kw}


def rushing(**kw) -> dict:
    return {"season": 2025, "week": 3, "team_abbr": "AAA", "player_gsis_id": "RB1", "rush_attempts": 20,
            "efficiency": 3.5, "percent_attempts_gte_eight_defenders": 25.0,
            "rush_yards_over_expected": 6.5, **kw}


def receiving(**kw) -> dict:
    return {"season": 2025, "week": 3, "team_abbr": "AAA", "player_gsis_id": "WR1", "targets": 10,
            "receptions": 6, "avg_cushion": 6.0, "avg_separation": 3.0,
            "avg_yac_above_expectation": 1.5, **kw}


def frame(rows: list[dict], template) -> pl.DataFrame:
    schema = {k: (pl.Utf8 if isinstance(v, str) else pl.Float64) for k, v in template().items()}
    return pl.DataFrame(rows, schema=schema)


def build(p=(), r=(), c=()) -> pl.DataFrame:
    return ngs.components(frame(list(p), passing), frame(list(r), rushing), frame(list(c), receiving))


def row(df: pl.DataFrame, player: str, week: int = 3) -> dict:
    return df.filter((pl.col("player_id") == player) & (pl.col("week") == week)).to_dicts()[0]


def test_week_zero_season_aggregates_are_skipped():
    df = build(p=[passing(week=0), passing(week=1)])
    assert df["week"].to_list() == [1]


def test_passing_components_are_each_average_times_attempts():
    r = row(build(p=[passing()]), "QB1")
    assert (r["team"], r["season"]) == ("AAA", 2025)
    assert r["ngs_attempts"] == 30.0
    assert r["ngs_ttt_w"] == 75.0
    assert r["ngs_aggr_w"] == 600.0
    assert r["ngs_iay_w"] == 240.0


def test_a_missing_average_stores_nothing_for_it_and_a_zero_weight_nothing_at_all():
    r = row(build(p=[passing(avg_time_to_throw=None)]), "QB1")
    assert r["ngs_ttt_w"] is None
    assert r["ngs_aggr_w"] == 600.0
    assert build(p=[passing(attempts=0)]).height == 0


def test_rushing_stores_ryoe_directly_and_the_other_averages_times_carries():
    r = row(build(r=[rushing()]), "RB1")
    assert r["ngs_carries"] == 20.0
    assert r["ngs_eff_w"] == 70.0
    assert r["ngs_box_w"] == 500.0
    assert r["ngs_ryoe"] == 6.5


def test_a_season_without_a_ryoe_model_keeps_its_other_components():
    r = row(build(r=[rushing(rush_yards_over_expected=None)]), "RB1")
    assert r["ngs_ryoe"] is None
    assert r["ngs_eff_w"] == 70.0


def test_receiving_weights_separation_and_cushion_by_targets_and_yac_by_receptions():
    r = row(build(c=[receiving()]), "WR1")
    assert (r["ngs_targets"], r["ngs_receptions"]) == (10.0, 6.0)
    assert (r["ngs_sep_w"], r["ngs_cush_w"], r["ngs_yacoe_w"]) == (30.0, 60.0, 9.0)


def test_week_23_becomes_22_only_when_the_season_has_no_week_22():
    df = pl.DataFrame({"season": [2025, 2025, 2024, 2023, 2023], "week": [21, 23, 23, 22, 23]})
    assert ngs.remap_postseason_weeks(df)["week"].to_list() == [21, 22, 22, 22, 23]


def test_a_player_in_two_files_becomes_one_row_with_both_sets_of_components():
    df = build(p=[passing(player_gsis_id="X1")], r=[rushing(player_gsis_id="X1")])
    assert df.height == 1
    r = df.to_dicts()[0]
    assert (r["ngs_attempts"], r["ngs_carries"]) == (30.0, 20.0)


def test_an_impossible_average_removes_only_that_sum():
    df = ngs.drop_impossible(build(c=[receiving(avg_separation=-2.0)]))
    r = row(df, "WR1")
    assert r["ngs_sep_w"] is None
    assert r["ngs_targets"] == 10.0
    assert r["ngs_cush_w"] == 60.0
