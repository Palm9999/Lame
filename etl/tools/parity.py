"""Compare a Python-built and a Kotlin-built stats.db; exit 1 on any mismatch.

    python etl/tools/parity.py build/py.db build/kt.db

Floats must agree to 1e-9 (absolute or relative): the two builds sum the same
values in a different order, nothing more.
"""

from __future__ import annotations

import math
import sqlite3
import sys

# table -> (key columns, compared columns)
TABLES: dict[str, tuple[list[str], list[str]]] = {
    "player_week_stat": (["player_id", "season", "week", "metric_id"], ["team", "value"]),
    "player": (["player_id"], ["full_name", "search_name", "position", "team", "pfr_player_id"]),
    "metric": (["id"], ["name", "abbr", '"group"', "definition", "formula", "positions", "tier",
                        "predicts", "stability", "higher_is_better", "decimals", "hot", "internal",
                        "computed", "dist_family", "zero_inflated"]),
    "team_week_defense": (["team", "season", "week"],
                          ["points_allowed", "yards_allowed", "sacks", "interceptions",
                           "fumbles_recovered", "defensive_tds"]),
    "injury_report": (["player_id", "season", "week"],
                      ["team", "name", "position", "status", "injury", "practice"]),
}
META_PREFIXES = ("seasons", "expected_through_week:")


def _same(a, b) -> bool:
    if isinstance(a, float) or isinstance(b, float):
        if a is None or b is None:
            return a is b
        return math.isclose(a, b, rel_tol=1e-9, abs_tol=1e-9)
    return a == b


def _rows(conn: sqlite3.Connection, table: str, keys: list[str], values: list[str]) -> dict:
    cols = ", ".join(keys + values)
    return {tuple(r[:len(keys)]): tuple(r[len(keys):])
            for r in conn.execute(f"SELECT {cols} FROM {table}")}


def compare(py_path: str, kt_path: str) -> list[str]:
    py, kt = sqlite3.connect(py_path), sqlite3.connect(kt_path)
    problems: list[str] = []
    for table, (keys, values) in TABLES.items():
        a, b = _rows(py, table, keys, values), _rows(kt, table, keys, values)
        only_py = sorted(a.keys() - b.keys())
        only_kt = sorted(b.keys() - a.keys())
        if only_py:
            problems.append(f"{table}: {len(only_py)} row(s) only in Python, e.g. {only_py[:5]}")
        if only_kt:
            problems.append(f"{table}: {len(only_kt)} row(s) only in Kotlin, e.g. {only_kt[:5]}")
        diffs = [(k, a[k], b[k]) for k in sorted(a.keys() & b.keys())
                 if not all(_same(x, y) for x, y in zip(a[k], b[k]))]
        if diffs:
            problems.append(f"{table}: {len(diffs)} row(s) differ, e.g. {diffs[:5]}")
        print(f"{table}: {len(a)} Python rows, {len(b)} Kotlin rows, {len(diffs)} differing")
    ma = dict(py.execute("SELECT key, value FROM schema_meta"))
    mb = dict(kt.execute("SELECT key, value FROM schema_meta"))
    for key in sorted(k for k in ma.keys() | mb.keys() if k.startswith(META_PREFIXES)):
        if ma.get(key) != mb.get(key):
            problems.append(f"schema_meta {key}: Python {ma.get(key)!r}, Kotlin {mb.get(key)!r}")
    return problems


def main(argv: list[str]) -> int:
    problems = compare(argv[1], argv[2])
    for p in problems:
        print("MISMATCH:", p)
    print("parity: OK" if not problems else f"parity: {len(problems)} mismatch(es)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
