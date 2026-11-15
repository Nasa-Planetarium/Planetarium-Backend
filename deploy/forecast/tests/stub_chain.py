"""Stand-in for fetch_nrt --with-planner in the refresh tests: writes planner outputs into ./data/processed.
Usage: stub_chain.py <mode> <served as_of dir>   modes: ok, fail, same, missing, noiso, badp, corrupt"""

import json
import shutil
import sys
from datetime import timedelta
from pathlib import Path

import polars as pl

mode, served = sys.argv[1], Path(sys.argv[2])
if mode == "fail":
    print("stub: chain failed", flush=True)
    sys.exit(1)
out = Path("data/processed")
out.mkdir(parents=True, exist_ok=True)
for f in served.glob("planner_*"):
    shutil.copyfile(f, out / f.name)
if mode != "same":
    d = pl.read_parquet(served / "planner_daily_grid.parquet")
    d = d.with_columns(pl.col("date") + timedelta(1), pl.col("as_of_date") + timedelta(1))
    if mode == "badp":
        d = d.with_columns(pl.when(pl.col("g") == d["g"][0]).then(1.5).otherwise(pl.col("p_any")).cast(pl.Float32).alias("p_any"))
    d.write_parquet(out / "planner_daily_grid.parquet")
if mode == "missing":
    (out / "planner_day_any.npz").unlink()
if mode == "noiso":
    sk = json.loads((out / "planner_30d_skill.json").read_text(encoding="utf-8"))
    for e in sk.values():
        e["iso"] = None
    (out / "planner_30d_skill.json").write_text(json.dumps(sk), encoding="utf-8")
if mode == "corrupt":
    (out / "planner_aoi_weights.npz").write_bytes(b"not an npz")
print(f"stub: {mode} done", flush=True)
