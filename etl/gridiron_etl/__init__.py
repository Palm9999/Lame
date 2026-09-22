"""Gridiron ETL — turns nflverse releases into a compact, pre-indexed SQLite database.

The Android client never touches upstream sources. Raw nflverse files are far too
large for a phone (2025 play-by-play alone is ~98 MB of CSV); everything is
normalized here and shipped as `stats.db`.
"""

__version__ = "0.1.0"
