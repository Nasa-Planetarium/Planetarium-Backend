# Ingest and database layout (B1)

## Commands (real release `v1-m2v-2026-11-14`)

Environment only — no files with secrets:

```
export SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/<db>
export SPRING_DATASOURCE_USERNAME=<user>
export SPRING_DATASOURCE_PASSWORD=<password>
export EMBERLINE_RELEASE_ROOT=/path/to/releases          # contains v1-m2v-2026-11-14/ with the 6 files + SHA256SUMS

java -jar build/libs/emberline.jar --spring.profiles.active=flyway                       # exit 0; idempotent
java -Xmx1536m -jar build/libs/emberline.jar --spring.profiles.active=ingest stage v1-m2v-2026-11-14
sha256sum "$EMBERLINE_RELEASE_ROOT/v1-m2v-2026-11-14/SHA256SUMS"                          # the separate-channel hash
java -jar build/libs/emberline.jar --spring.profiles.active=ingest activate v1-m2v-2026-11-14 --expect-sha256 <hex>
java -jar build/libs/emberline.jar --spring.profiles.active=ingest show v1-m2v-2026-11-14
java -jar build/libs/emberline.jar                                                         # API :8080, actuator :8081
```

Exit codes: 0 ok · 3 RELEASE_INVALID / RELEASE_KEY_INVALID · 4 RELEASE_HASH_MISMATCH · 5 RELEASE_STATE_CONFLICT · 1 unexpected.
The last stdout line is JSON: `{release_key, state, reason_codes[], aliases_used[], warnings[], method_version, data_version, rows{}, exit_code}`.
Rollback = `activate <older key> --expect-sha256 <its SHA256SUMS hash>`; running API pods switch within the poll interval
(`emberline.release.poll-interval`, default 10 s, ≤ 30 s).

Tuning (`--emberline.ingest.*`): `copy-workers` (default 4 parallel COPY streams for the cell table),
`duckdb-memory-limit` (default `1GB`, off-heap), `duckdb-threads` (default 4). Logs per table: `COPY <partition> rows=…
seconds=… rows_per_s=…` and `INDEX+ATTACH <partition> seconds=…`. Measured in the dev pod (embedded PG, fsync off):
a synthetic 10M-row cell file → COPY 11 s with 2 workers (0.9M rows/s), indexes + attach 5 s, whole stage 18 s,
JVM heap ≈ 240 MB. The real file (51.5M rows) was not run here.

## What stage checks

Key `^[a-z0-9][a-z0-9._-]{0,63}$` (no `..`), folder inside the root (also after symlinks), `SHA256SUMS` present and
parsable, every needed file present (required) and listed with a matching sha256, `m2v_meta.json` `method_version`,
columns and kinds (aliases `name→name_ko`, `parent_aoi→parent_id`, v0 column names), code values (level
`kr/sido/sgg` → `country/kr_sido/kr_sgg`; value_source v0 names → v1, reported in `aliases_used`), duplicate keys
(cells: by the unique index after COPY), and data cases through `DataCaseRegistry` (one handler class per case):
`MISSING_MONTH_NULL_VALUE` keep NULL (E2) · `MISSING_MONTH_WITH_VALUE` keep · `VALUE_OUTSIDE_INTERVAL` keep (E3) ·
`NEGATIVE_VALUE` (value / value_lo / value_hi < 0, v1.2) keep as stored, warning `NEGATIVE_VALUE:<file>:<n> rows kept as
stored; drawn as 0` · `INTERVAL_INVERTED` (value_lo > value_hi, both bounds finite, any size; v1.2: 8,162 cell-month and
8 aoi-month rows, at most 1.7e-5) keep as stored, warning `INTERVAL_INVERTED:<file>:<n> rows kept as stored`; the read
side serves value / value_lo / value_hi as stored, never reordered, clamped or nulled (E3 `interval_out_of_range` only
flags a value outside its band), and the display rule (lo = min(lo, hi, value), hi = max(lo, hi,
value), no band when lo is null) belongs to the front · `NULL_VALUE_NOT_MISSING` / `INTERVAL_INVERTED_NOT_FINITE`
(value_lo > value_hi with a NaN or infinite bound) / `NEGATIVE_COUNT` (raw_modis, raw_viirs,
n_cells < 0) reject. A rejected release leaves no partitions and
does not touch the active release. Values are copied as the files have them (A1).

## Schemas, tables, partitions, indexes

Flyway: one instance per schema (`db/migration/<ctx>`, history `<ctx>.flyway_schema_history`), profile `flyway` only.
Release tables are `PARTITION BY LIST (release_id)`; the loader creates `<table>_r<release_id>` (LIKE parent),
COPYs, builds the indexes, adds `CHECK (release_id = id)`, ANALYZEs and ATTACHes. Parents carry no indexes.

| schema | table | rows (v1 release) | partition indexes |
|---|---|---|---|
| catalog | `aoi` (aoi_list + level_file, level_rank, has_data, first/last month, bbox from JTS) | 5,051 | unique (aoi_id), (parent_id) |
| catalog | `aoi_boundary` (geometry jsonb as in the GeoJSON, geometry_type) | 5,038 | unique (aoi_id) |
| harmonized | `aoi_month` | 1,580,963 | unique (aoi_id, year, month) |
| harmonized | `aoi_month_compare` (+ status, low_sample; data-v1.2 r3 + baseline_period, continuity_ratio, continuity_source, optional) | 1,580,963 | unique (aoi_id, year, month) |
| observation | `cell_month` (code columns as enums, no per-row method_version) | 51,562,642 | unique (year, month, cell_id), (year, month, cell_lat, cell_lon) |
| provenance | `method_meta` (m2v_meta.json as `json` text, aliases_used, warnings, files) | 1 | unique (method_version) |
| provenance | `month_status` (WORLD rows of aoi_month: value_source, data_source, status) | 313 | unique (year, month) |
| provenance | `satellite_day` (only when satellite_day_status.parquet ships; v1.2 `date_utc` → `date` by alias, `note` kept, status ok/low/missing/partial/nrt/low_sample) | 0 (v1.2: 28,316) | unique (satellite, date) |
| provenance | `satellite_month` (migration provenance V2; only when satellite_month_status.parquet ships, v1.2; month_status complete/partial) | 0 (v1.2: 934) | unique (satellite, year, month) |
| outlook | `grid_occurrence` (migration outlook V1; only when grid_occurrence_nextmonth_latest.parquet ships, v1.2; the MAPPING §8.2 served columns, probabilities as double precision = the file's float32 widened exactly, `p_model` not loaded) | 0 (v1.2: 52,855) | unique (cell_id) |
| outlook | `aoi_occurrence` (only when aoi_occurrence_nextmonth_latest.parquet ships, v1.2; `n_years` float → integer, `p_model`, `rate_all`, `lag1_value`, `occ_share`, `seasonal_cv` not loaded) | 0 (v1.2: 252) | unique (aoi_id) |
| ingest | `release` (state, version, manifest_sha256, data_version, reason_codes/aliases/warnings jsonb), `load_task`, `active_release` (id = 1) | — | unique release_key; partial unique `WHERE state='LOADING'` and `WHERE state='ACTIVE'` |
| platform | `event_publication`, `event_publication_archive` (Spring Modulith 2.0 JDBC, schema v2) | — | Modulith defaults |

## Release v1.2 (`v1.2-2026-11-15`)

Same folder layout as v1.1 plus:

- `satellite_day_status.parquet`, `satellite_month_status.parquet` — listed in `SHA256SUMS`, optional (absent → as v1.1:
  `FILE_ABSENT` warning for the day file, nothing for the month file, no `satellite_month` partition and no
  `provenance.satellite_month` key in `rows`). The day file's extra columns (`n_det_*`, `coverage`, `baseline`, `status_r*`,
  `seasonal_*` …) are not loaded; `data_source` / `updated_at` stay optional (`COLUMN_ABSENT` warnings). The month table is
  loaded but no endpoint reads it yet (the v1.2 contract asks for no month-level satellite status).
- `harmonized_hotspots_static_flags/year=YYYY.parquet` (2000–2026, not in `SHA256SUMS`, not staged): read at query time by
  `/hotspots` (page, totals, AOI weights, CSV) — points matching a flag row with `is_static_source = true` on
  (`satellite`, `latitude`, `longitude`, `acq_datetime_utc`) are left out (anti-join, exact equality). Only the flag files of
  the request's `date_local` years ± 1 are read. Without the folder the queries are unchanged.
- `harmonized_cell_month` / `harmonized_cell_day` extra columns `static_removed`, `static_removed_lo`, `static_removed_hi`,
  `static_n_points` are ignored (not loaded, not served). Cell values below 0 are kept (see `NEGATIVE_VALUE` above).

- `grid_occurrence_nextmonth_latest.parquet`, `aoi_occurrence_nextmonth_latest.parquet` (F15 outlook, optional, listed in
  `SHA256SUMS` when present) → `outlook.grid_occurrence` / `outlook.aoi_occurrence`, `rows` keys
  `outlook.grid_occurrence` / `outlook.aoi_occurrence`. Rejected: a missing served column (`COLUMN_MISSING`), a null in a
  served non-null column (`NULL_VALUE`), NaN or infinity (`NOT_FINITE`), more than one `target_month` / `as_of_date`
  (both files) or `model` (grid) (`HEAD_NOT_UNIQUE`), a non-integral `n_years` (`NOT_INTEGRAL:…:n_years <n> rows`), a
  duplicate `cell_id` / `aoi_id`. A `cell_id` that breaks `floor((lat+90)/0.5)×720 + floor((lon+180)/0.5)` is kept with a
  `CELL_ID_FORMULA` warning. Without the files: no partition, no `rows` key, no warning; the outlook routes answer 503
  `data_not_loaded` 50502.

A v1.1 folder (none of these files, no negative values) stages with the same rows and warnings as before.

### `release_manifest.csv` (v1.2)

Checked once per stage next to `SHA256SUMS` (same per-file sha256). Every entry resolves at its listed path in the folder
(`models/…`, `reports/…` included): a missing one rejects `MANIFEST_FILE_MISSING:<file>`; an entry a loader reads must
match its bytes, sha256 and parquet rows (`MANIFEST_BYTES_MISMATCH` / `MANIFEST_SHA256_MISMATCH` / `MANIFEST_ROWS_MISMATCH`).
A loader file that the manifest lists only under a subdirectory and that sits at the top of the folder — today
`m2v_meta.json`, listed as `models/m2v_meta.json` — is that entry's copy: it is checked against the entry by base name and
its reasons name the top-level file (`MANIFEST_SHA256_MISMATCH:m2v_meta.json`). Other top-level files the manifest does not
list are warnings (`MANIFEST_UNLISTED:<file>`). The release identity stays `SHA256SUMS` (`data_version`,
`activate --expect-sha256`).

### Assembling a v1.2 stage folder (`deploy/release/assemble.sh`)

The model team's download is flat (the 50 `models/…` / `reports/…` entries sit at the top) and has no `SHA256SUMS`, so it
cannot be staged as is. The owner assembles the stage folder from it by hardlinks (the download is never written):

```
deploy/release/assemble.sh --hotspots-from "$EMBERLINE_RELEASE_ROOT/<previous stage folder>" \
    [--extra-from <folder of re-published files>] <download folder> "$EMBERLINE_RELEASE_ROOT/<new key>"
sha256sum "$EMBERLINE_RELEASE_ROOT/<new key>/SHA256SUMS"                                  # the activate hash
```

1. every `release_manifest.csv` entry is linked at its listed path (top level, `models/`, `reports/`), plus the manifest;
   an entry the download lacks is taken from `--extra-from` (by listed path or base name), else the run fails;
2. each loader file the manifest lists only under a subdirectory is linked at the top as well (`LOADER_TOP_LEVEL` in the
   script: `m2v_meta.json`);
3. `harmonized_hotspots/` and `harmonized_hotspots_static_flags/` are linked (`cp -al`) from `--hotspots-from` (the
   download ships them only as tar files);
4. `SHA256SUMS` = `sha256sum` of every top-level file in `LC_ALL=C` order (`<hex>␠␠<name>` lines);
5. every `SHA256SUMS` line (a top-level entry, a top-level copy by base name, or the manifest itself) and every manifest
   `bytes` value — plus the sha256 of each subdirectory entry, which `SHA256SUMS` does not cover — must equal the manifest;
   only then is `<key>.tmp` moved to `<key>`.

An existing target (or `<key>.tmp`) is refused, a download without `.done` is refused, and a failed run removes its own
`<key>.tmp`. Download and stage folder must be on one filesystem. Reproduced on 2026-11-15: the 19:18 download state with
`--hotspots-from v1.2-2026-11-15-r1` gives the owner's `v1.2-2026-11-15-r2` `SHA256SUMS` byte for byte (sha256 `15893a77…`);
the re-published download (new `aoi_month_compare.parquet`, 3 new `reports/` entries) with `--extra-from
v1.2-2026-11-15-add` gives `v1.2-2026-11-15-r3`'s (sha256 `dba6de4f…`). Tests: `tools/test/assemble.test.mjs`.
