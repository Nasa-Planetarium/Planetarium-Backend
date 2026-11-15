"""Data layout of the FireOutlook Planner service (deploy/forecast/README.md has the picture).

The model reads every file from <model root>/data/processed (planetarium.config.PROCESSED_DATA_DIR is hard-coded), so
the image ships <model root>/data as a symlink to a writable link (FORECAST_DATA_LINK, /tmp/pl-data in the pod). At
process start compose() builds a fresh view directory of symlinks: every file of the read-only release, then every file
of ONE published as_of directory of the state dir on top. The view points at <state>/<as_of>/<file>, never through
<state>/current, so a later switch of `current` cannot change what a running process reads; a process picks up a new
as_of only by starting again (server.py re-executes itself).

State dir: <as_of>/ (immutable once published: planner_* files + MANIFEST.json with their sha256), current -> <as_of>
(relative symlink, replaced with rename(2) only after a refresh has fully succeeded and been checked), .tmp-* (work in
progress, never read). Only stdlib here; as_of_of() imports polars lazily (the model venv has it).
"""

from __future__ import annotations

import csv
import hashlib
import json
import os
import shutil
import sys
import time
from datetime import date
from pathlib import Path

CURRENT = "current"
MANIFEST = "MANIFEST.json"
DAILY = "planner_daily_grid.parquet"
MONTHLY = "planner_monthly_grid.parquet"
# Every planner file planetarium.forecast reads (forecast.MONTHLY, DAILY, SVC and planner_sgg_monthly; the test pins it).
# A published as_of directory must hold all of them, so a view never mixes planner files of two as_of values.
PLANNER_REQUIRED = (
    MONTHLY, DAILY, "planner_aoi_weights.npz", "planner_region_calibration.json", "planner_region_skill.json",
    "planner_count_choice.json", "planner_region_counts_sf.parquet", "planner_sgg_choice.json", "planner_day_weights.npz",
    "planner_30d_skill.json", "planner_day_any.npz", "planner_sgg_monthly.parquet",
)
# The other release files planetarium.forecast reads (they come from the release, not from a refresh).
RELEASE_REQUIRED = ("aoi_list.parquet", "aoi_occurrence_nextmonth_latest.parquet", "grid_occurrence_nextmonth_latest.parquet")


class LayoutError(RuntimeError):
    pass


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 22), b""):
            h.update(b)
    return h.hexdigest()


def as_of_of(processed: Path) -> date:
    """forecast()'s default as_of rule: the later as_of_date of the monthly and the daily grid."""
    import polars as pl
    vals = []
    for name in (MONTHLY, DAILY):
        f = processed / name
        if f.exists():
            u = pl.read_parquet(f, columns=["as_of_date"])["as_of_date"].unique().to_list()
            if len(u) != 1:
                raise LayoutError(f"{name}: as_of_date is not one value: {u}")
            vals.append(u[0])
    if not vals:
        raise LayoutError(f"no {MONTHLY} or {DAILY} in {processed}")
    return max(vals)


def release_key(release: Path) -> str:
    return release.resolve().name


def data_version(release: Path, as_of: date | str) -> str:
    """<release_key>#<first 12 hex of sha256(release_manifest.csv)>+planner.<as_of> (fits deploy.sh's data_version regex)."""
    return f"{release_key(release)}#{sha256(release / 'release_manifest.csv')[:12]}+planner.{as_of}"


def release_manifest(release: Path) -> dict[str, str]:
    """{basename: sha256} of release_manifest.csv (paths in it carry models/ or reports/ prefixes; the folder is flat)."""
    with open(release / "release_manifest.csv", newline="", encoding="utf-8") as f:
        return {os.path.basename(r["file"]): r["sha256"] for r in csv.DictReader(f)}


def current_dir(state: Path) -> Path:
    """The published as_of directory `current` points at, resolved once."""
    link = state / CURRENT
    if not link.is_symlink():
        raise LayoutError(f"{link} is missing (seed the state dir first)")
    target = os.readlink(link)
    if os.path.isabs(target) or "/" in target.strip("/") or target.startswith("."):
        raise LayoutError(f"{link} -> {target}: must name a sibling directory")
    return state / target


def check_published(d: Path, verify_sha: bool = True, name: str | None = None) -> dict:
    """A published as_of dir: MANIFEST.json present, every required planner file listed and (optionally) matching,
    MANIFEST as_of = the directory name (name: the name a tmp dir is about to get)."""
    mf = d / MANIFEST
    if not mf.is_file():
        raise LayoutError(f"{d.name}: no {MANIFEST}")
    m = json.loads(mf.read_text(encoding="utf-8"))
    files = m.get("files") or {}
    missing = [f for f in PLANNER_REQUIRED if f not in files or not (d / f).is_file()]
    if missing:
        raise LayoutError(f"{d.name}: missing {missing}")
    extra = sorted(p.name for p in d.iterdir() if p.name != MANIFEST and p.name not in files)
    if extra:
        raise LayoutError(f"{d.name}: files not in {MANIFEST}: {extra}")
    if verify_sha:
        bad = [f for f, h in files.items() if sha256(d / f) != h]
        if bad:
            raise LayoutError(f"{d.name}: sha256 mismatch {bad}")
    if m.get("as_of") != (name or d.name):
        raise LayoutError(f"{d.name}: MANIFEST as_of {m.get('as_of')} does not match the directory name")
    return m


def compose(release: Path, state: Path, data_link: Path, verify_sha: bool = True) -> dict:
    """Build a view (release files + the published as_of dir `current` names, resolved once) and point data_link at
    it with rename(2). Returns {as_of_dir, as_of, view}. Old views next to it are removed."""
    pub = current_dir(state)
    return compose_dir(release, pub, data_link, check_published(pub, verify_sha))


def compose_dir(release: Path, pub: Path, data_link: Path, m: dict) -> dict:
    """compose() for an already checked as_of dir (the refresh uses it on its unpublished tmp dir for a trial)."""
    missing = [f for f in RELEASE_REQUIRED if not (release / f).is_file()]
    if missing:
        raise LayoutError(f"release {release}: missing {missing}")
    views = data_link.parent / (data_link.name + "-views")
    views.mkdir(parents=True, exist_ok=True)
    view = views / f"{pub.name}.{os.getpid()}.{time.monotonic_ns()}"
    proc = view / "processed"
    proc.mkdir(parents=True)
    for f in sorted(release.iterdir()):
        if f.is_file() and not f.name.startswith("."):
            os.symlink(f, proc / f.name)
    for name in m["files"]:
        dst = proc / name
        if dst.is_symlink():
            dst.unlink()
        os.symlink(pub / name, dst)
    tmp = data_link.parent / f".{data_link.name}.{os.getpid()}"
    if tmp.is_symlink():
        tmp.unlink()
    os.symlink(view, tmp)
    os.replace(tmp, data_link)
    for old in views.iterdir():
        if old != view:
            shutil.rmtree(old, ignore_errors=True)
    return {"as_of_dir": pub, "as_of": m["as_of"], "view": view}


def _fsync_dir(d: Path) -> None:
    fd = os.open(d, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def write_manifest(d: Path, as_of: date | str, source: str) -> dict:
    files = {p.name: sha256(p) for p in sorted(d.iterdir()) if p.is_file() and p.name != MANIFEST}
    m = {"as_of": str(as_of), "source": source, "created_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "files": files}
    (d / MANIFEST).write_text(json.dumps(m, indent=1, sort_keys=True), encoding="utf-8")
    for p in d.iterdir():
        fd = os.open(p, os.O_RDONLY)
        os.fsync(fd)
        os.close(fd)
    _fsync_dir(d)
    return m


def publish(state: Path, tmp: Path, as_of: date | str) -> Path:
    """Rename a fully written and checked tmp dir to <state>/<as_of>. Never overwrites a published dir."""
    dst = state / str(as_of)
    if dst.exists():
        raise LayoutError(f"{dst.name} is already published")
    check_published(tmp, name=str(as_of))
    os.rename(tmp, dst)
    _fsync_dir(state)
    return dst


def switch(state: Path, as_of: date | str) -> None:
    """Point `current` at <state>/<as_of> atomically (symlink to a temp name, then rename over `current`)."""
    target = state / str(as_of)
    check_published(target)
    tmp = state / f".{CURRENT}.{os.getpid()}"
    if tmp.is_symlink():
        tmp.unlink()
    os.symlink(str(as_of), tmp)
    os.replace(tmp, state / CURRENT)
    _fsync_dir(state)


def seed(release: Path, state: Path) -> str:
    """Initial state = the release's planner_* files (copied, each checked against release_manifest.csv).
    No-op when `current` exists. Returns a one-line status."""
    if (state / CURRENT).is_symlink():
        pub = current_dir(state)
        check_published(pub, verify_sha=False)
        return f"seed: current -> {pub.name} exists, nothing to do"
    want = release_manifest(release)
    names = sorted(n for n in want if n.startswith("planner_"))
    missing = [n for n in PLANNER_REQUIRED if n not in names]
    if missing:
        raise LayoutError(f"release manifest lacks {missing}")
    tmp = state / f".tmp-seed-{os.getpid()}"
    shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir()
    try:
        for n in names:
            shutil.copyfile(release / n, tmp / n)
            if sha256(tmp / n) != want[n]:
                raise LayoutError(f"{n}: sha256 differs from release_manifest.csv")
        as_of = as_of_of(tmp)
        write_manifest(tmp, as_of, f"release {release_key(release)}")
        dst = state / str(as_of)
        if dst.exists():          # an earlier seed published but died before `current`; keep that copy if it checks
            check_published(dst)
            shutil.rmtree(tmp)
        else:
            publish(state, tmp, as_of)
    except BaseException:
        shutil.rmtree(tmp, ignore_errors=True)
        raise
    switch(state, as_of)
    return f"seed: {len(names)} planner files of {release_key(release)} -> {as_of}, current -> {as_of}"


def main(argv: list[str] | None = None) -> int:
    import argparse
    ap = argparse.ArgumentParser(description="FireOutlook Planner state dir")
    ap.add_argument("step", choices=["seed", "compose", "show"])
    ap.add_argument("--release", type=Path, default=Path(os.environ.get("FORECAST_RELEASE_DIR", "/release")))
    ap.add_argument("--state", type=Path, default=Path(os.environ.get("FORECAST_STATE_DIR", "/state")))
    ap.add_argument("--data-link", type=Path, default=Path(os.environ.get("FORECAST_DATA_LINK", "/tmp/pl-data")))
    a = ap.parse_args(argv)
    try:
        if a.step == "seed":
            print(seed(a.release, a.state), flush=True)
        elif a.step == "compose":
            r = compose(a.release, a.state, a.data_link)
            print(f"compose: as_of {r['as_of']} view {r['view']}", flush=True)
        else:
            pub = current_dir(a.state)
            m = check_published(pub)
            print(json.dumps({"current": pub.name, "as_of": m["as_of"], "files": len(m["files"]),
                              "data_version": data_version(a.release, m["as_of"])}), flush=True)
    except LayoutError as e:
        print(f"layout: {e}", file=sys.stderr, flush=True)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
