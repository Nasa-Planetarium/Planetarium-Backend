"""Daily FireOutlook Planner refresh (CronJob pl-forecast-refresh): run the model team's chain in a writable model
working tree, check what it produced, publish it as a new per-as_of directory of the state dir and only then switch
`current`. Any failure leaves `current` (and so the served as_of) alone and exits non-zero.

  1. preflight   the working tree holds every input of the chain (REFRESH_INPUTS; none is invented or retrained here)
  2. run         python -m planetarium.fetch_nrt --fetch --days 2 --append --with-planner (cwd = working tree root,
                 FIRMS_MAP_KEY from the environment, never printed)
  3. check       every planner file present, as_of newer than current, daily grid = 7 days after as_of with the same
                 row count as the served one, probabilities in [0, 1], 30-day skill keeps its isotonic curves
  4. stage       copy the planner_* files into <state>/.tmp-<as_of>-<pid>/ with MANIFEST.json (sha256)
  5. trial       forecast() on a view of release + the staged dir, in a fresh process; as_of must match
  6. publish     rename to <state>/<as_of>, then rename a new `current` symlink over the old one

Usage: refresh.py [--release R] [--state S] [--work W] [--model /opt/model] [--command ...] [--keep N]
The working tree W/model is a writable copy of the model repo: planetarium/ is replaced from the image each run, models/
and reports/ are copied once, data/ (processed, interim, raw, external) belongs to the model team's pipeline and must
be provided; this job does not create it.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import layout  # noqa: E402

FETCH = ["-m", "planetarium.fetch_nrt", "--fetch", "--days", "2", "--append", "--with-planner"]
# Inputs the chain fetch_nrt --append → planner_publish.refresh → planner_daily build/samples/final → publish reads
# before it writes anything of its own (deploy/forecast/README.md "Refresh inventory" has the full list and sources).
REFRESH_INPUTS = (
    "data/processed/harmonized_cell_day.parquet", "data/processed/harmonized_cell_day_frp.parquet",
    "data/processed/harmonized_cell_month.parquet", "data/processed/harmonized_residual_cell_month.parquet",
    "data/processed/aoi_month.parquet", "data/processed/aoi_month_compare.parquet", "data/processed/aoi_list.parquet",
    "data/processed/satellite_day_status.parquet", "data/processed/satellite_month_status.parquet",
    "data/processed/cell_month_0.1deg.parquet", "data/processed/cell_day_0.1deg", "data/processed/grid_cells_0.1deg.parquet",
    "data/processed/world_cells_0.1deg.parquet", "data/processed/world_cell_adm1_weights_0.1deg.parquet",
    "data/processed/korea_cell_sgg_weights_0.1deg.parquet", "data/processed/static_source_cells_0.01deg.parquet",
    "data/processed/harmonized_hotspots", "data/processed/harmonized_hotspots_static_flags",
    "data/interim/firms_hotspots", "data/interim/firms_coverage.parquet", "data/interim/harmonized_background",
    "data/interim/grid_occurrence/cells.parquet", "data/interim/planner/daily/selection.json",
    "data/interim/planner/monthly/region_calibration.json", "data/interim/planner/count_choice.json",
    "data/interim/planner/region_sf_service_small.parquet", "data/interim/planner/sgg/choice.json",
    "data/interim/planner/d30/iso30.json", "models/n20_bridge.parquet",
    "reports/planner_region_metrics.csv", "reports/planner_30d_validation.csv",
    *(f"data/processed/{f}" for f in layout.PLANNER_REQUIRED if f != layout.DAILY),
)
TRIAL = r"""
import json, math, sys
from planetarium.forecast import forecast, warmup
warmup()
want, start = sys.argv[1], sys.argv[2]
for region in ("SIDO_51", [126.5, 37.0, 127.5, 38.0]):
    r = forecast(region, start=start, horizon_days=30)
    assert r["as_of"] == want, (region, r["as_of"], want)
    assert len(r["daily"]) == 30 and math.isfinite(r["p_any"]) and 0 <= r["p_any"] <= 1, region
    json.dumps(r, default=str, allow_nan=False)
print("trial ok", want)
"""


class RefreshError(RuntimeError):
    pass


def log(msg: str) -> None:
    print(f"[refresh] {msg}", flush=True)


def prepare_tree(model: Path, root: Path) -> None:
    """Writable model root: code from the image (byte-identical copy), models/ and reports/ once, data/ as provided."""
    root.mkdir(parents=True, exist_ok=True)
    code = root / "planetarium"
    tmp = root / ".planetarium.new"
    shutil.rmtree(tmp, ignore_errors=True)
    shutil.copytree(model / "planetarium", tmp, ignore=shutil.ignore_patterns("__pycache__"))
    shutil.rmtree(code, ignore_errors=True)
    os.rename(tmp, code)
    for d in ("models", "reports", "references"):
        if not (root / d).exists() and (model / d).exists():
            shutil.copytree(model / d, root / d)


def preflight(root: Path) -> None:
    missing = [p for p in REFRESH_INPUTS if not (root / p).exists()]
    links = [p for p in REFRESH_INPUTS if (root / p).is_symlink()]
    if missing:
        raise RefreshError(f"{len(missing)} input(s) of the chain are missing in the working tree: {', '.join(missing)}")
    if links:   # the chain rewrites these in place; a link could point into the read-only release
        raise RefreshError(f"inputs must be regular files of the working tree, not links: {', '.join(links)}")


def run(cmd: list[str], root: Path, timeout: float | None) -> None:
    env = {**os.environ, "PYTHONPATH": str(root), "PYTHONUNBUFFERED": "1"}
    log(f"run: {' '.join(cmd[1:])} (cwd {root})")
    try:
        rc = subprocess.run(cmd, cwd=root, env=env, timeout=timeout).returncode
    except subprocess.TimeoutExpired:
        raise RefreshError(f"chain timed out after {timeout}s") from None
    if rc != 0:
        raise RefreshError(f"chain exited {rc}")


def check_outputs(processed: Path, served: Path) -> date:
    """→ the new as_of. Raises when anything is missing or off."""
    import polars as pl
    missing = [f for f in layout.PLANNER_REQUIRED if not (processed / f).is_file() or (processed / f).is_symlink()]
    if missing:
        raise RefreshError(f"planner outputs missing (or links, not files): {missing}")
    as_of = layout.as_of_of(processed)
    old = layout.as_of_of(served)
    if as_of <= old:
        raise RefreshError(f"as_of {as_of} is not newer than the served {old}")
    d = pl.read_parquet(processed / layout.DAILY)
    ref = pl.read_parquet(served / layout.DAILY, columns=["g"])
    if d.height != ref.height:
        raise RefreshError(f"{layout.DAILY}: {d.height} rows, the served one has {ref.height}")
    want = {as_of + timedelta(i) for i in range(1, 8)}
    got = set(d["date"].unique().to_list())
    if got != want:
        raise RefreshError(f"{layout.DAILY}: dates {sorted(got)} are not as_of+1..+7")
    if d["as_of_date"].n_unique() != 1 or d["as_of_date"][0] != as_of:
        raise RefreshError(f"{layout.DAILY}: as_of_date is not {as_of}")
    for c in ("p_any", "base_p", "p_cum"):
        s = d[c]
        if s.null_count() or s.is_nan().any() or s.min() < 0 or s.max() > 1:
            raise RefreshError(f"{layout.DAILY}: {c} has nulls, NaN or values outside [0, 1]")
    for c in ("mu", "mu_cum", "base_count"):
        s = d[c]
        if s.null_count() or s.is_nan().any() or s.is_infinite().any():
            raise RefreshError(f"{layout.DAILY}: {c} has nulls or non-finite values")
    if set(d["g"].unique().to_list()) != set(ref["g"].unique().to_list()):
        raise RefreshError(f"{layout.DAILY}: grid cells differ from the served one")
    # publish() writes iso: null for every unit when interim/planner/d30/iso30.json is absent, which silently turns
    # off the 30-day calibration: every unit calibrated in the served file must stay calibrated.
    iso = lambda f: {u for u, e in json.loads(f.read_text(encoding="utf-8")).items() if (e.get("iso") or {}).get("x")}  # noqa: E731
    lost = sorted(iso(served / "planner_30d_skill.json") - iso(processed / "planner_30d_skill.json"))
    if lost:
        raise RefreshError(f"planner_30d_skill.json lacks isotonic curves for {lost}")
    for f in ("planner_monthly_grid.parquet",):
        if not math.isfinite(pl.read_parquet(processed / f, columns=["p_any"])["p_any"].sum()):
            raise RefreshError(f"{f}: non-finite p_any")
    return as_of


def stage(processed: Path, state: Path, as_of: date) -> Path:
    tmp = state / f".tmp-{as_of}-{os.getpid()}"
    shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir()
    for f in sorted(processed.glob("planner_*")):
        if f.is_file() and not f.is_symlink():
            shutil.copyfile(f, tmp / f.name)
    layout.write_manifest(tmp, as_of, "refresh fetch_nrt --with-planner")
    layout.check_published(tmp, name=str(as_of))
    return tmp


def trial(model: Path, release: Path, staged: Path, as_of: date, python: str) -> None:
    """forecast() on release + staged files in a fresh process with its own model root (copy of the package)."""
    with tempfile.TemporaryDirectory(prefix="pl-trial-") as td:
        root = Path(td)
        shutil.copytree(model / "planetarium", root / "planetarium", ignore=shutil.ignore_patterns("__pycache__"))
        link = root / "pl-data"
        m = layout.check_published(staged, name=str(as_of))
        layout.compose_dir(release, staged, link, m)
        os.symlink(link, root / "data")
        env = {**os.environ, "PYTHONPATH": str(root), "PYTHONDONTWRITEBYTECODE": "1"}
        r = subprocess.run([python, "-c", TRIAL, str(as_of), str(as_of + timedelta(1))], cwd=root, env=env,
                           capture_output=True, text=True, timeout=600)
        if r.returncode != 0:
            raise RefreshError(f"trial forecast failed: {(r.stderr or r.stdout).strip().splitlines()[-1:]}")
        log(r.stdout.strip())


def prune(state: Path, keep: int) -> None:
    cur = layout.current_dir(state).name
    pubs = sorted(p for p in state.iterdir() if p.is_dir() and not p.name.startswith(".") and p.name != cur)
    for p in pubs[:max(0, len(pubs) - (keep - 1))]:
        shutil.rmtree(p, ignore_errors=True)
        log(f"pruned {p.name}")


def refresh(release: Path, state: Path, work: Path, model: Path, cmd: list[str], keep: int = 7,
            timeout: float | None = None, python: str = sys.executable) -> int:
    served = layout.current_dir(state)
    layout.check_published(served)
    log(f"serving {served.name}")
    root = work / "model"
    prepare_tree(model, root)
    preflight(root)
    run(cmd, root, timeout)
    processed = root / "data" / "processed"
    try:
        as_of = check_outputs(processed, served)
    except RefreshError as e:
        if "is not newer" in str(e) and layout.as_of_of(processed) == layout.as_of_of(served):
            log(f"no new as_of ({e}); current stays {served.name}")
            return 0
        raise
    tmp = stage(processed, state, as_of)
    try:
        trial(model, release, tmp, as_of, python)
        layout.publish(state, tmp, as_of)
    except BaseException:
        shutil.rmtree(tmp, ignore_errors=True)
        raise
    layout.switch(state, as_of)
    log(f"current -> {as_of} (was {served.name})")
    prune(state, keep)
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="FireOutlook Planner daily refresh")
    ap.add_argument("--release", type=Path, default=Path(os.environ.get("FORECAST_RELEASE_DIR", "/release")))
    ap.add_argument("--state", type=Path, default=Path(os.environ.get("FORECAST_STATE_DIR", "/state")))
    ap.add_argument("--work", type=Path, default=Path(os.environ.get("FORECAST_WORK_DIR", "/work")))
    ap.add_argument("--model", type=Path, default=Path(os.environ.get("FORECAST_MODEL_ROOT", "/opt/model")))
    ap.add_argument("--keep", type=int, default=7, help="published as_of dirs to keep, current included")
    ap.add_argument("--timeout", type=float, default=None, help="seconds for the chain")
    ap.add_argument("--preflight-only", action="store_true", help="list missing inputs and stop (no network)")
    ap.add_argument("--command", nargs=argparse.REMAINDER, help="replace the chain (tests); default fetch_nrt")
    a = ap.parse_args(argv)
    cmd = [sys.executable, *FETCH] if not a.command else a.command
    try:
        if a.preflight_only:
            root = a.work / "model"
            prepare_tree(a.model, root)
            preflight(root)
            log("preflight ok")
            return 0
        return refresh(a.release, a.state, a.work, a.model, cmd, a.keep, a.timeout)
    except (RefreshError, layout.LayoutError) as e:
        log(f"FAILED, current unchanged: {e}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
