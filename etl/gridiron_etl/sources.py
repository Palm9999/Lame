"""nflverse release download with on-disk caching.

All assets live under a single GitHub Releases base URL. Files are cached
locally so repeated runs during development don't re-pull ~100 MB each time.

Two naming traps documented in the spec are encoded here rather than left to
be rediscovered:
  * `nextgen_stats` is NOT season-partitioned (`ngs_passing.csv.gz`).
  * `players` is a single unpartitioned file.
"""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass
from pathlib import Path

import requests

log = logging.getLogger(__name__)

BASE = "https://github.com/nflverse/nflverse-data/releases/download"

# Cache lives outside the repo by default; override for CI.
DEFAULT_CACHE = Path(os.environ.get("GRIDIRON_CACHE", Path.home() / ".cache" / "gridiron"))


@dataclass(frozen=True)
class Source:
    """A single nflverse release asset.

    `partitioned` marks assets that carry a season in the filename. The
    non-partitioned ones (players, next gen stats) 404 if you add a season,
    which is a trap worth encoding in the type rather than the caller.
    """

    key: str
    release: str
    filename: str
    partitioned: bool = True

    def url(self, season: int | None = None) -> str:
        name = self.filename.format(season=season) if self.partitioned else self.filename
        return f"{BASE}/{self.release}/{name}"

    def cache_path(self, cache_dir: Path, season: int | None = None) -> Path:
        name = self.filename.format(season=season) if self.partitioned else self.filename
        return cache_dir / name


SOURCES: dict[str, Source] = {
    "pbp": Source("pbp", "pbp", "play_by_play_{season}.csv"),
    "snap_counts": Source("snap_counts", "snap_counts", "snap_counts_{season}.csv"),
    "weekly_rosters": Source("weekly_rosters", "weekly_rosters", "roster_weekly_{season}.csv"),
    "schedules": Source("schedules", "schedules", "games.csv", partitioned=False),
    # Not season-partitioned — adding a season 404s.
    "players": Source("players", "players", "players.csv", partitioned=False),
    "ngs_receiving": Source("ngs_receiving", "nextgen_stats", "ngs_receiving.csv.gz", partitioned=False),
    "ngs_rushing": Source("ngs_rushing", "nextgen_stats", "ngs_rushing.csv.gz", partitioned=False),
    "ngs_passing": Source("ngs_passing", "nextgen_stats", "ngs_passing.csv.gz", partitioned=False),
}


def fetch(key: str, season: int | None = None, cache_dir: Path | None = None,
          force: bool = False, timeout: int = 300) -> Path:
    """Download a source asset, returning the local path. Cached unless `force`."""
    src = SOURCES[key]
    cache_dir = cache_dir or DEFAULT_CACHE
    cache_dir.mkdir(parents=True, exist_ok=True)
    dest = src.cache_path(cache_dir, season)

    if dest.exists() and not force and dest.stat().st_size > 0:
        log.info("cache hit  %s (%.1f MB)", dest.name, dest.stat().st_size / 1e6)
        return dest

    url = src.url(season)
    log.info("downloading %s", url)
    tmp = dest.with_suffix(dest.suffix + ".part")
    with requests.get(url, stream=True, timeout=timeout) as r:
        r.raise_for_status()
        with tmp.open("wb") as fh:
            for chunk in r.iter_content(chunk_size=1 << 20):
                fh.write(chunk)
    tmp.replace(dest)
    log.info("fetched    %s (%.1f MB)", dest.name, dest.stat().st_size / 1e6)
    return dest
