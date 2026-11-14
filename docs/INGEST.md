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
`NULL_VALUE_NOT_MISSING` / `INTERVAL_INVERTED` / `NEGATIVE_VALUE` reject. A rejected release leaves no partitions and
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
| harmonized | `aoi_month_compare` (+ status, low_sample) | 1,580,963 | unique (aoi_id, year, month) |
| observation | `cell_month` (code columns as enums, no per-row method_version) | 51,562,642 | unique (year, month, cell_id), (year, month, cell_lat, cell_lon) |
| provenance | `method_meta` (m2v_meta.json as `json` text, aliases_used, warnings, files) | 1 | unique (method_version) |
| provenance | `month_status` (WORLD rows of aoi_month: value_source, data_source, status) | 313 | unique (year, month) |
| provenance | `satellite_day` (only when satellite_day_status.parquet ships) | 0 | unique (satellite, date) |
| ingest | `release` (state, version, manifest_sha256, data_version, reason_codes/aliases/warnings jsonb), `load_task`, `active_release` (id = 1) | — | unique release_key; partial unique `WHERE state='LOADING'` and `WHERE state='ACTIVE'` |
| platform | `event_publication`, `event_publication_archive` (Spring Modulith 2.0 JDBC, schema v2) | — | Modulith defaults |
