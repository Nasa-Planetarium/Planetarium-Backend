"""layout.py: the file list matches what planetarium.forecast reads, views never mix as_of values, publish/switch."""

from __future__ import annotations

import json
import os
import re
import sys

import pytest
from conftest import MODEL, NEW, OLD, RELEASE, publish_copy

import layout


def test_required_files_match_forecast_py():
    sys.path.insert(0, str(MODEL))
    try:
        from planetarium import forecast       # constants only; nothing is read at import
    finally:
        sys.path.remove(str(MODEL))
    names = {forecast.MONTHLY.name, forecast.DAILY.name, *(p.name for p in forecast.SVC.values())}
    names |= set(re.findall(r'PROCESSED_DATA_DIR / "([^"]+)"', (MODEL / "planetarium/forecast.py").read_text(encoding="utf-8")))
    assert set(layout.PLANNER_REQUIRED) | set(layout.RELEASE_REQUIRED) == names
    assert all(n.startswith("planner_") for n in layout.PLANNER_REQUIRED)
    assert not any(n.startswith("planner_") for n in layout.RELEASE_REQUIRED)


def test_seed_matches_release_manifest(state):
    m = layout.check_published(state / OLD)
    want = layout.release_manifest(RELEASE)
    planner = {n: h for n, h in want.items() if n.startswith("planner_")}
    assert len(planner) == 27 and m["files"] == planner
    assert os.readlink(state / layout.CURRENT) == OLD
    assert layout.seed(RELEASE, state).endswith("nothing to do")


def test_compose_one_as_of(tmp_path, state, new_set):
    link = tmp_path / "pl-data"
    v1 = layout.compose(RELEASE, state, link)
    assert v1["as_of"] == OLD
    publish_copy(state, new_set, NEW)
    layout.switch(state, NEW)
    v2 = layout.compose(RELEASE, state, link)
    assert v2["as_of"] == NEW and os.readlink(link) == str(v2["view"]) and not v1["view"].exists()
    proc = v2["view"] / "processed"
    names = {p.name for p in proc.iterdir()}
    assert names == {p.name for p in RELEASE.iterdir() if p.is_file() and not p.name.startswith(".")}
    for p in proc.iterdir():
        t = os.readlink(p)
        assert t.startswith(str(state / NEW) + "/") if p.name.startswith("planner_") else t.startswith(str(RELEASE) + "/"), (p.name, t)
        assert "/current/" not in t
    assert str(layout.as_of_of(proc)) == NEW


def test_check_published_rejects(state, tmp_path):
    d = state / OLD
    bad = tmp_path / OLD
    import shutil
    shutil.copytree(d, bad)
    layout.check_published(bad)
    (bad / "planner_day_any.npz").write_bytes(b"x")
    with pytest.raises(layout.LayoutError, match="sha256 mismatch"):
        layout.check_published(bad)
    (bad / "planner_day_any.npz").unlink()
    with pytest.raises(layout.LayoutError, match="missing"):
        layout.check_published(bad)
    shutil.copyfile(d / "planner_day_any.npz", bad / "planner_day_any.npz")
    (bad / "stray.txt").write_text("x")
    with pytest.raises(layout.LayoutError, match="not in MANIFEST"):
        layout.check_published(bad)
    (bad / "stray.txt").unlink()
    with pytest.raises(layout.LayoutError, match="does not match"):
        layout.check_published(bad, name=NEW)
    (bad / layout.MANIFEST).unlink()
    with pytest.raises(layout.LayoutError, match="no MANIFEST"):
        layout.check_published(bad)


def test_switch_and_publish(state, new_set):
    with pytest.raises(layout.LayoutError):
        layout.switch(state, NEW)                       # not published yet
    assert os.readlink(state / layout.CURRENT) == OLD
    publish_copy(state, new_set, NEW)
    with pytest.raises(layout.LayoutError, match="already published"):
        publish_copy(state, new_set, NEW)
    layout.switch(state, NEW)
    assert os.readlink(state / layout.CURRENT) == NEW    # relative, so it means the same inside and outside the pod
    assert not [p for p in state.iterdir() if p.name.startswith(".")]


@pytest.mark.parametrize("target", ["/abs/2026-11-14", "../x", ".tmp-x", "a/b"])
def test_current_must_be_a_sibling(state, target):
    os.unlink(state / layout.CURRENT)
    os.symlink(target, state / layout.CURRENT)
    with pytest.raises(layout.LayoutError, match="sibling"):
        layout.current_dir(state)


def test_data_version_shape():
    dv = layout.data_version(RELEASE, OLD)
    assert re.fullmatch(r"v1\.2-2026-11-15#[0-9a-f]{12}\+planner\.2026-11-14", dv)
    assert json.dumps(dv)
