"""The six-layer projections pipeline: volume cascade, shrinkage, matchup,
game script, market blend, distribution assembly. See
docs/superpowers/specs/2026-09-23-projections-design.md for the architecture
and docs/research/research-prediction-models.md for the methodology.

Each stage is a pure function; `build_projections()` (added in Task 11) wires
them in order.
"""

from __future__ import annotations
