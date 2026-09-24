import pytest

from gridiron_etl import odds


def test_devig_proportional_method():
    # -150/+130: p_raw_over = 150/250 = 0.60, p_raw_under = 100/230 ≈ 0.4348
    # fair_over = 0.60 / (0.60 + 0.4348) ≈ 0.5798
    p = odds.devig(-150, 130)
    assert p == pytest.approx(0.5798, abs=0.001)


def test_devig_is_symmetric_for_even_money_both_sides():
    assert odds.devig(-110, -110) == pytest.approx(0.5, abs=1e-9)


def test_parse_player_props_extracts_receptions_market():
    event = {
        "id": "evt1",
        "bookmakers": [{
            "key": "draftkings",
            "markets": [{
                "key": "player_receptions",
                "outcomes": [
                    {"name": "Over", "description": "Justin Jefferson", "point": 6.5, "price": -115},
                    {"name": "Under", "description": "Justin Jefferson", "point": 6.5, "price": -105},
                ],
            }],
        }],
    }
    df = odds.parse_player_props(event)
    assert df.height == 1
    row = df.row(0, named=True)
    assert row["player_name"] == "Justin Jefferson"
    assert row["market"] == "player_receptions"
    assert row["line"] == 6.5
    assert 0.5 < row["fair_prob"] < 0.55


def test_parse_player_props_skips_one_sided_markets():
    # Missing the Under side — can't de-vig, must be skipped, not crash or divide by zero.
    event = {
        "id": "evt1",
        "bookmakers": [{
            "key": "draftkings",
            "markets": [{
                "key": "player_reception_yds",
                "outcomes": [
                    {"name": "Over", "description": "Justin Jefferson", "point": 75.5, "price": -110},
                ],
            }],
        }],
    }
    assert odds.parse_player_props(event).height == 0
