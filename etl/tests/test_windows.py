"""player_window_stat / window_def: the pre-aggregated windows the Grid reads."""

from gridiron_etl import schema


def _build(tmp_path, season: int, weeks: list[int]):
    conn = schema.create(tmp_path / "s.db")
    conn.executemany(
        "INSERT INTO player_week_stat VALUES ('p1', ?, ?, 'AAA', ?, ?)",
        [row for w in weeks for row in ((season, w, "g", 1.0), (season, w, "targets", float(w)))],
    )
    conn.commit()
    schema.finalize(conn, [season])
    return conn


def _windows(conn, season):
    return {w: (a, b) for w, a, b in conn.execute(
        "SELECT window, first_week, last_week FROM window_def WHERE season = ?", (season,))}


def _sum(conn, season, window, metric):
    row = conn.execute(
        "SELECT value FROM player_window_stat WHERE season = ? AND window = ? AND metric_id = ?",
        (season, window, metric)).fetchone()
    return row and row[0]


def test_windows_match_hand_sums(tmp_path):
    conn = _build(tmp_path, 2025, list(range(1, 11)))
    assert _windows(conn, 2025)["S"] == (1, 10)
    assert _windows(conn, 2025)["L3"] == (8, 10)
    assert _sum(conn, 2025, "S", "targets") == 55.0
    assert _sum(conn, 2025, "L3", "targets") == 27.0
    assert _sum(conn, 2025, "L8", "g") == 8.0


def test_a_last_window_clips_to_the_weeks_played(tmp_path):
    conn = _build(tmp_path, 2025, [1, 2])
    assert _windows(conn, 2025)["L5"] == (1, 2)
    assert _sum(conn, 2025, "L5", "targets") == 3.0


def test_a_bye_week_counts_no_game(tmp_path):
    conn = _build(tmp_path, 2025, [1, 2, 3, 4, 6, 7])
    assert _windows(conn, 2025)["L4"] == (4, 7)
    assert _sum(conn, 2025, "L4", "g") == 3.0
    assert _sum(conn, 2025, "L4", "targets") == 17.0


def test_playoff_weeks_stay_out(tmp_path):
    conn = _build(tmp_path, 2025, [17, 18, 19])
    assert _windows(conn, 2025)["S"] == (1, 18)
    assert _windows(conn, 2025)["L3"] == (16, 18)
    assert _sum(conn, 2025, "S", "targets") == 35.0


def test_a_season_before_2021_ends_at_week_17(tmp_path):
    conn = _build(tmp_path, 2020, [16, 17, 18])
    assert _windows(conn, 2020)["S"] == (1, 17)
