"""Owner fixture: a small, real-valued subset of release data-v1-m2v-2026-11-14 (values copied, never recomputed).

core = Korea (KR, SIDO_*, SGG_*) + WORLD + CONT_Asia + ADM0_JPN, all 313 months; cells inside the KR aoi bbox
(lat 33..39, lon 124..132) for all months. aoi_list is copied whole. Deterministic: sorted output.
"""
import hashlib, json, pathlib, sys
import duckdb

src = pathlib.Path(sys.argv[1]); out = pathlib.Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
con = duckdb.connect()
con.execute("SET threads=8")
aoi_sql = ("SELECT aoi_id FROM read_parquet(?) WHERE aoi_id IN ('KR','WORLD','CONT_Asia','ADM0_JPN') "
           "OR aoi_id LIKE 'SIDO\\_%' ESCAPE '\\' OR aoi_id LIKE 'SGG\\_%' ESCAPE '\\'")
ids = [r[0] for r in con.execute(aoi_sql, [str(src / "aoi_list.parquet")]).fetchall()]
con.execute("CREATE TEMP TABLE ids(aoi_id VARCHAR)"); con.executemany("INSERT INTO ids VALUES (?)", [(i,) for i in ids])
def copy(q, name, params):
    con.execute(f"COPY ({q}) TO '{out / name}' (FORMAT parquet, COMPRESSION zstd)", params)
copy("SELECT * FROM read_parquet(?) ORDER BY aoi_id", "aoi_list.parquet", [str(src / "aoi_list.parquet")])
for f in ("aoi_month.parquet", "aoi_month_compare.parquet"):
    copy("SELECT t.* FROM read_parquet(?) t JOIN ids USING (aoi_id) ORDER BY aoi_id, year, month", f, [str(src / f)])
copy("SELECT * FROM read_parquet(?) WHERE cell_lat BETWEEN 33 AND 39 AND cell_lon BETWEEN 124 AND 132 "
     "ORDER BY year, month, cell_id", "harmonized_cell_month.parquet", [str(src / "harmonized_cell_month.parquet")])
gj = json.loads((src / "aoi_boundaries.geojson").read_text())
keep = set(ids)
gj["features"] = sorted([f for f in gj["features"] if f["properties"]["aoi_id"] in keep],
                        key=lambda f: f["properties"]["aoi_id"])
(out / "aoi_boundaries.geojson").write_text(json.dumps(gj, ensure_ascii=False, separators=(",", ":")))
(out / "m2v_meta.json").write_bytes((src / "m2v_meta.json").read_bytes())
names = ["aoi_boundaries.geojson", "aoi_list.parquet", "aoi_month.parquet", "aoi_month_compare.parquet",
         "harmonized_cell_month.parquet", "m2v_meta.json"]
(out / "SHA256SUMS").write_text("".join(f"{hashlib.sha256((out / n).read_bytes()).hexdigest()}  {n}\n" for n in names))
(out / ".done").write_text("DONE\n")
for n in names:
    rows = "" if not n.endswith(".parquet") else con.execute("SELECT count(*) FROM read_parquet(?)", [str(out / n)]).fetchone()[0]
    print(n, (out / n).stat().st_size, rows)
print("aoi_ids", len(ids), "features", len(gj["features"]))
