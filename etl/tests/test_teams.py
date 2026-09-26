import polars as pl

from gridiron_etl import teams


def test_team_defense_counts_what_each_defense_allowed_and_took_away():
    base = {"season": 2025, "week": 1, "season_type": "REG", "game_id": "g1",
            "home_team": "KC", "away_team": "BUF"}
    plays = pl.LazyFrame([
        {**base, "defteam": "KC", "play_type": "pass", "yards_gained": 20, "sack": 0,
         "interception": 0, "fumble_lost": 0, "touchdown": 0, "td_team": None,
         "total_home_score": 0, "total_away_score": 7},
        {**base, "defteam": "KC", "play_type": "pass", "yards_gained": 0, "sack": 0,
         "interception": 1, "fumble_lost": 0, "touchdown": 1, "td_team": "KC",
         "total_home_score": 7, "total_away_score": 7},
        {**base, "defteam": "BUF", "play_type": "run", "yards_gained": -3, "sack": 1,
         "interception": 0, "fumble_lost": 1, "touchdown": 0, "td_team": None,
         "total_home_score": 10, "total_away_score": 7},
    ])
    out = {r["team"]: r for r in teams.team_defense_from(plays).to_dicts()}

    assert out["KC"]["points_allowed"] == 7
    assert out["KC"]["yards_allowed"] == 20
    assert out["KC"]["interceptions"] == 1
    assert out["KC"]["defensive_tds"] == 1
    assert out["BUF"]["points_allowed"] == 10
    assert out["BUF"]["sacks"] == 1
    assert out["BUF"]["fumbles_recovered"] == 1
