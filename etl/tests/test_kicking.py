import polars as pl

from gridiron_etl import transform


def kick(**kw) -> dict:
    base = dict(season=2025, week=1, season_type="REG", posteam="AAA", kicker_player_id="K1",
                field_goal_attempt=0, field_goal_result=None, kick_distance=None,
                extra_point_attempt=0, extra_point_result=None)
    base.update(kw)
    return base


def fg(distance, result, **kw) -> dict:
    return kick(field_goal_attempt=1, kick_distance=distance, field_goal_result=result, **kw)


def xp(result, **kw) -> dict:
    return kick(extra_point_attempt=1, extra_point_result=result, **kw)


def rows(plays: list[dict]) -> dict[str, dict]:
    return {r["player_id"]: r for r in transform.kicking_from(pl.LazyFrame(plays)).to_dicts()}


def test_field_goals_land_in_their_distance_bucket_edges_included():
    r = rows([fg(39, "made"), fg(40, "made"), fg(49, "made"), fg(50, "made"), fg(63, "missed")])["K1"]
    assert [r["fg_att_0_39"], r["fg_att_40_49"], r["fg_att_50"]] == [1, 2, 2]
    assert [r["fg_made_0_39"], r["fg_made_40_49"], r["fg_made_50"]] == [1, 2, 1]
    assert r["fg_missed"] == 1
    assert (r["fg_att"], r["fg_made"]) == (5, 4)
    assert r["g"] == 1


def test_a_blocked_kick_is_a_miss_and_no_distance_counts_as_short():
    r = rows([fg(45, "blocked"), fg(None, "made")])["K1"]
    assert (r["fg_att_40_49"], r["fg_made_40_49"], r["fg_missed"]) == (1, 0, 1)
    assert (r["fg_att_0_39"], r["fg_made_0_39"]) == (1, 1)


def test_an_extra_point_is_made_only_when_good():
    r = rows([xp("good"), xp("good"), xp("failed"), xp("blocked"), xp("aborted")])["K1"]
    assert (r["xp_att"], r["xp_made"], r["xp_missed"]) == (5, 2, 3)


def test_preseason_and_kicks_without_a_kicker_do_not_count():
    assert rows([fg(30, "made", season_type="PRE"), fg(30, "made", kicker_player_id=None)]) == {}


def test_each_kickers_week_is_keyed_by_his_team():
    out = rows([fg(30, "made"), fg(30, "made", posteam="BBB", kicker_player_id="K2")])
    assert {k: r["team"] for k, r in out.items()} == {"K1": "AAA", "K2": "BBB"}
