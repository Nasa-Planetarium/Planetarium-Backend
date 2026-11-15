"""Owner fixture core-v12-layers: a small, real-valued subset of release data-v1.2-2026-11-15, for the v1.2 new layers
Values are copied, never recomputed.

Whole files (small): grid_occurrence_nextmonth_latest, aoi_occurrence_nextmonth_latest, spread_nowcast_latest,
aoi_month_fire_regime, fwi_clim_aoi_month, satellite_day_status, satellite_month_status, aoi_list, every *_meta.json.
Slices:
- fire_events: every row with region KR, plus ARG rows starting 2025-01-01..2025-01-31;
- fwi_aoi_day: every AOI from 2026-10-01, plus aoi_id KR / SGG_31140 / ADM0_ARG for all dates;
- harmonized_cell_day_frp: the cell_ids harmonized_cell_month places in the KR box [124.5, 33, 132, 38.7], dates 2025-03-01..2025-03-31 and 2026-11-01..2026-11-15;
- aoi_boundaries.geojson: KR, SIDO_*, SGG_*, ADM0_ARG features.
release_manifest.csv is regenerated for the fixture files (file,rows,bytes,sha256). Deterministic: sorted, single thread.
"""
import csv, hashlib, json, pathlib, sys
import duckdb

src = pathlib.Path(sys.argv[1]); out = pathlib.Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
con = duckdb.connect()
con.execute("SET memory_limit='8GB'")
con.execute("SET threads=1")


def copy(q, name, params=()):
    con.execute(f"COPY ({q}) TO '{out / name}' (FORMAT parquet, COMPRESSION zstd)", list(params))


def p(name):
    return str(src / name)


whole = {
    "grid_occurrence_nextmonth_latest.parquet": "cell_id",
    "aoi_occurrence_nextmonth_latest.parquet": "aoi_id",
    "spread_nowcast_latest.parquet": "region, cell_lat, cell_lon",
    "aoi_month_fire_regime.parquet": "aoi_id, year, month",
    "fwi_clim_aoi_month.parquet": "aoi_id, month",
    "satellite_day_status.parquet": "satellite, date_utc",
    "satellite_month_status.parquet": "satellite, year, month",
    "aoi_list.parquet": "aoi_id",
}
for name, order in whole.items():
    copy(f"SELECT * FROM read_parquet(?) ORDER BY {order}", name, [p(name)])
copy("SELECT * FROM read_parquet(?) WHERE region = 'KR' OR (region = 'ARG' AND start_date BETWEEN DATE '2025-01-01' "
     "AND DATE '2025-01-31') ORDER BY event_id", "fire_events.parquet", [p("fire_events.parquet")])
copy("SELECT * FROM read_parquet(?) WHERE date >= DATE '2026-10-01' OR aoi_id IN ('KR', 'SGG_31140', 'ADM0_ARG') "
     "ORDER BY aoi_id, date", "fwi_aoi_day.parquet", [p("fwi_aoi_day.parquet")])
kr_cells = ("cell_id IN (SELECT DISTINCT cell_id FROM read_parquet('" + p("harmonized_cell_month.parquet") + "') "
            "WHERE cell_lat BETWEEN 33 AND 38.7 AND cell_lon BETWEEN 124.5 AND 132)")
copy(f"SELECT * FROM read_parquet(?) WHERE {kr_cells} AND (date_local BETWEEN DATE '2025-03-01' AND DATE '2025-03-31' "
     "OR date_local BETWEEN DATE '2026-11-01' AND DATE '2026-11-15') ORDER BY date_local, cell_id",
     "harmonized_cell_day_frp.parquet", [p("harmonized_cell_day_frp.parquet")])
gj = json.loads((src / "aoi_boundaries.geojson").read_text())
keep = lambda a: a in ("KR", "ADM0_ARG") or a.startswith("SIDO_") or a.startswith("SGG_")
gj["features"] = sorted([f for f in gj["features"] if keep(f["properties"]["aoi_id"])], key=lambda f: f["properties"]["aoi_id"])
(out / "aoi_boundaries.geojson").write_text(json.dumps(gj, ensure_ascii=False, separators=(",", ":")))
for meta in sorted(src.glob("*_meta.json")):
    (out / meta.name).write_bytes(meta.read_bytes())
rows = []
for f in sorted(x for x in out.iterdir() if x.name not in ("release_manifest.csv", "SHA256SUMS")):
    n = con.execute("SELECT count(*) FROM read_parquet(?)", [str(f)]).fetchall()[0][0] if f.suffix == ".parquet" else ""
    rows.append([f.name, n, f.stat().st_size, hashlib.sha256(f.read_bytes()).hexdigest()])
with open(out / "release_manifest.csv", "w", newline="") as fh:
    w = csv.writer(fh, lineterminator="\n"); w.writerow(["file", "rows", "bytes", "sha256"]); w.writerows(rows)
for r in rows:
    print(r[0], r[1], r[2])
