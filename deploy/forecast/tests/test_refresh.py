"""refresh.py switches `current` only after the chain succeeded and its output passed the checks and a trial forecast.
The chain is replaced by stub_chain.py (the real one needs the model team's working data, see README)."""

from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest
from conftest import HERE, MODEL, NEW, OLD, RELEASE

import layout
import refresh

STUB = HERE / "stub_chain.py"


@pytest.fixture
def run(state, tmp_path, monkeypatch):
    monkeypatch.setattr(refresh, "REFRESH_INPUTS", ())       # the stub needs no chain inputs
    work = tmp_path / "work"

    def go(mode: str) -> int:
        cmd = [sys.executable, str(STUB), mode, str(state / OLD)]
        return refresh.refresh(RELEASE, state, work, MODEL, cmd, keep=7)
    return go


def published(state: Path) -> list[str]:
    return sorted(p.name for p in state.iterdir() if p.name != layout.CURRENT)


@pytest.mark.parametrize("mode, reason", [
    ("fail", "chain exited 1"),
    ("missing", "planner outputs missing"),
    ("noiso", "lacks isotonic curves"),
    ("badp", "outside [0, 1]"),
    ("corrupt", "trial forecast failed"),
])
def test_refuses_to_switch(run, state, mode, reason):
    before = published(state)
    with pytest.raises(refresh.RefreshError, match=reason.replace("[", r"\[").replace("]", r"\]")):
        run(mode)
    assert os.readlink(state / layout.CURRENT) == OLD                  # current untouched
    assert published(state) == before                                 # no new dir, no .tmp left behind
    layout.check_published(state / OLD)


def test_same_as_of_is_a_no_op(run, state):
    assert run("same") == 0
    assert os.readlink(state / layout.CURRENT) == OLD and published(state) == [OLD]


def test_switches_after_success(run, state):
    assert run("ok") == 0
    assert os.readlink(state / layout.CURRENT) == NEW
    m = layout.check_published(state / NEW)
    assert m["as_of"] == NEW and set(layout.PLANNER_REQUIRED) <= set(m["files"])
    assert published(state) == [OLD, NEW]
    assert str(layout.as_of_of(state / NEW)) == NEW
    # a second run the same day produces the as_of already served: nothing is published or switched
    assert run("ok") == 0
    assert os.readlink(state / layout.CURRENT) == NEW and published(state) == [OLD, NEW]


def test_preflight_lists_missing_inputs(tmp_path):
    work = tmp_path / "work"
    refresh.prepare_tree(MODEL, work / "model")
    with pytest.raises(refresh.RefreshError) as e:
        refresh.preflight(work / "model")
    msg = str(e.value)
    for p in ("data/interim/planner/daily/selection.json", "data/interim/grid_occurrence/cells.parquet",
              "data/interim/planner/d30/iso30.json", "models/n20_bridge.parquet"):
        assert p in msg
    assert "reports/planner_region_metrics.csv" not in msg            # the repo provides the reports
