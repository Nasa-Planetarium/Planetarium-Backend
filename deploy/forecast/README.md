# FireOutlook Planner service

The model team's `planetarium.forecast` (model repo `c43368c`, unchanged) as an internal HTTP service in K3s.
Spring (`POST /api/v1/forecast`, the API side) validates the public request and calls this service; every number comes
from `forecast()`.

| File | Role |
|---|---|
| `Dockerfile`, `build.sh` | image `planetarium-forecast:c43368c`: `git archive c43368c` + `uv sync --frozen --no-dev` (Python 3.13, the model team's) + the wrapper |
| `server.py` | `POST /forecast`, `GET /healthz`; `forecast.warmup()` once at start; re-exec on a new as_of |
| `layout.py` | state dir, per-process data view, seed / publish / switch (shared by the server, the refresh, the tests, the smoke) |
| `refresh.py` | daily refresh (CronJob `pl-forecast-refresh`, suspended, see "Refresh inventory") |
| `smoke.sh` | offline smoke: the image vs the model CLI, 5 queries |
| `tests/` | pytest for the wrapper (run with the model venv) |
| `../k8s/planetarium-forecast.yaml` | PV/PVC, Deployment, Service, NetworkPolicies, CronJob |

## Internal HTTP interface (for FC-A)

`POST http://planetarium-forecast.planetarium.svc:8000/forecast`, body `{region, start?, end?, horizon_days?, as_of?}`
(exactly these keys; `Content-Length` required, at most `FORECAST_MAX_BODY` = 256 KiB, so Spring's own cap must be
lower). `region` = aoi_id string | `[west, south, east, north]` numbers | GeoJSON `Polygon` / `MultiPolygon` (or a
`Feature` of one). The wrapper checks only these shapes; value limits (vertices, ranges, horizon 1–30) are Spring's.

- 200: `forecast()`'s dict as JSON (`json.dumps(..., ensure_ascii=False, default=str)`, the CLI's arguments without
  the indent), headers `X-Forecast-As-Of`, `X-Data-Version`, `Cache-Control: no-store`.
- Errors: `{"error": "<code>", "message": "..."}` with `Content-Type: application/json`:

| status | error | when |
|---|---|---|
| 400 | `invalid_json` | body not UTF-8 JSON (NaN/Infinity literals refused) |
| 400 | `invalid_request` | not an object, unknown key, no `region`, wrong shape/type of a field, bad `Content-Length` |
| 400 | `invalid_query` | the model's `ValueError`, `TypeError`, `KeyError`, `OverflowError`, shapely errors (message = the model's, Korean) |
| 404 | `unknown_aoi` | `region` string not among the 5,051 aoi_ids of `planner_aoi_weights.npz` |
| 404 / 405 | `not_found` / `method_not_allowed` | other paths / methods |
| 411 | `length_required` | no `Content-Length`, or chunked |
| 413 | `body_too_large` | `Content-Length` over the cap (the body is not read) |
| 503 | `warming_up` / `busy` | not warm after 30 s / more than 32 connections |
| 500 | `internal` / `non_finite` | anything else (traceback only in the pod log) / a NaN in the output (never seen) |

`GET /healthz` → `{"as_of", "data_version", "warm"}`, 200 when warm, else 503. `as_of` = what the process answers
from (forecast()'s rule: the later `as_of_date` of the monthly and the daily grid). `data_version` =
`<release_key>#<first 12 hex of sha256(release_manifest.csv)>+planner.<as_of>`, e.g.
`v1.2-2026-11-15#7691aa4020b8+planner.2026-11-14` (fits `deploy.sh`'s data_version regex; the release folder has no
`SHA256SUMS`, so the manifest stands in). A cache key of `data_version` + normalized query covers a refresh (as_of
changes) and a release edit (manifest changes; the server re-executes and the value changes).

Notes for the Spring side:
- Cache key: `forecast()` resolves `horizon_days` without `start` from today (KST), and as_of stays 2026-11-14 while
  the refresh is suspended. A key of `data_version` + normalized query alone would serve one day's 30-day window on
  the next day. Key on the resolved KST date too (or on the response's `start`/`end`).
- Use this service's `data_version` (`X-Data-Version` / `/healthz`), not the SHA256SUMS-based data_version of the
  Spring release.
- `error_no` mappings are needed for 411, 413, 503 `warming_up` / `busy` and 500 `non_finite`, besides 400/404.

**Freshness cliff (as_of frozen at 2026-11-14).** The 30-day summary uses the holdout skill only when the start is at
most as_of + 3 (`forecast.py` line 478). With a start of "today KST", checked with `today=`:

| today (KST) | SIDO_51 | SGG_47730 | daily-model days |
|---|---|---|---|
| 11-15 – 11-17 | `skill=high`, `value_source=model_calibrated` | `value_source=baseline` | 7 → 5 |
| from 11-18 | `skill=none`, `value_source=model` | `value_source=model` | 4 → 1 |
| from 11-22 | same | same | 0 |

The live smoke's checks (SIDO_51 high, SGG_47730 baseline) therefore hold only on or before 11-17 KST, or with an
explicit `start` = as_of + 1 (as the offline smoke sends), or once the refresh runs.

Latency (warm, offline): 30-day aoi/bbox/polygon 0.01–0.04 s, WORLD 30 days 1.1 s, global bbox 30 days 1.9 s,
13-month WORLD / global polygon 4–7 s. Calls run one at a time (the model keeps module state), so Spring's timeout
should be ≥ 10 s and its rate limit is what bounds the queue.

## Data layout

```
release (read-only, PVC `release` = $HOME/planetarium/release at /release)
  /release/v1.2-2026-11-15/* the model team's release folder, flat, never written
state (PVC `forecast-state` = $HOME/planetarium/forecast-state at /state; read-only in the service)
  /state/2026-11-14/planner_* MANIFEST.json one published as_of: all 27 planner files + their sha256 (immutable)
  /state/2026-11-15/... the next one, written by the refresh
  /state/current -> 2026-11-14 relative symlink; replaced with rename(2) after a checked refresh
  /state/.tmp-* in progress, never read
image
  /opt/model model repo c43368c + .venv; PYTHONPATH=/opt/model
  /opt/model/data -> /tmp/pl-data baked link: PROCESSED_DATA_DIR = /opt/model/data/processed
pod /tmp (emptyDir)
  /tmp/pl-data -> /tmp/pl-data-views/<as_of>.<pid>.<n> swapped with rename(2) at process start
  /tmp/pl-data-views/<as_of>.<pid>.<n>/processed/<file> -> /release/v1.2-2026-11-15/<file> (every release file)
                                                  planner_* -> /state/<as_of>/planner_* (overrides)
```

- The view links `/state/<as_of>/…`, never `/state/current/…`. `forecast()` reads `aoi_list.parquet` and
  `aoi_occurrence_nextmonth_latest.parquet` on every call, so a link through `current` would mix two as_of values
  after a switch. A process reads one as_of for its whole life.
- `current` is resolved once at start. The watcher polls it every 30 s. When it names another published, checked
  as_of, or `release_manifest.csv` changed, the server stops accepting, lets in-flight requests finish, and re-executes
  itself with the listening socket inherited (connections wait in the backlog, the pid and port stay). The new process
  composes a new view and runs `warmup()` again; requests that arrive meanwhile wait for warm (about 1.5 s). In the test
  with a poller running through the switch: about 600 answers, no error, every answer before the switch from 2026-11-14,
  every one after from 2026-11-15.
- Initial state = the release's 27 `planner_*` files, copied and checked against `release_manifest.csv` by
  `layout.py seed` (the Deployment's initContainer; a no-op once `current` exists). as_of 2026-11-14.

## Refresh inventory (item 2)

The chain `fetch_nrt --fetch --days 2 --append --with-planner` → `planner_publish.refresh("auto")` →
`planner_daily.build / samples / final` → `publish()` reads (R) and writes (W) the files below, relative to the model
root. Static reading of the code at `c43368c`; "release" = present in `v1.2-2026-11-15`, "repo" = tracked in the model
repo.

| Path | R/W | Provided by | Present? |
|---|---|---|---|
| `.env` or env `FIRMS_MAP_KEY` | R | Secret `pl-firms` | yes (env) |
| `data/raw/firms/api/<today>/*.csv` | W | the fetch (FIRMS area API, 3 calls) | written |
| `data/interim/firms_hotspots/*.parquet` (all FIRMS points, archive + NRT + API) | R/W | model team working data | **missing** |
| `data/interim/firms_coverage.parquet`, `data/interim/api_responses.parquet` | R/W | model team working data | **missing** |
| `data/interim/converted/api/**` | W | converter | written |
| `data/processed/cell_day_0.1deg/*.parquet` | R/W | model team working data | **missing** |
| `data/processed/static_source_cells_0.01deg.parquet` | R | model team working data | **missing** |
| `data/processed/satellite_day_status.parquet`, `satellite_month_status.parquet` | R/W | release | yes (rewritten in place) |
| `data/processed/cell_month_0.1deg.parquet` | R/W | model team working data | **missing** |
| `data/processed/harmonized_cell_month.parquet`, `harmonized_residual_cell_month.parquet` | R/W | release | yes (rewritten in place) |
| `data/interim/harmonized_background/<year>.parquet` | R/W | model team working data | **missing** |
| `data/processed/grid_cells_0.1deg.parquet`, `world_cells_0.1deg.parquet`, `world_cell_adm1_weights_0.1deg.parquet`, `korea_cell_sgg_weights_0.1deg.parquet` | R | model team working data | **missing** |
| `data/processed/panel_common_0.1deg/apply_<year>.parquet` | R (if present) | model team working data | missing (optional) |
| `models/n20_bridge.parquet` | R | model team working data | **missing** |
| `data/processed/harmonized_hotspots/{reference,other}/year=/month=/` | R/W | release has `.tar` packs only (2026 in v1.2, 2000–2025 in `hotspots/data-v1.1-…`) | **missing** as a tree |
| `data/processed/harmonized_hotspots_static_flags/year=*.parquet` | R/W | release has a `.tar` only | **missing** as a tree |
| `data/interim/static_exclusion/**` | R/W | model team working data | missing |
| `data/processed/harmonized_cell_day.parquet` | R/W | release | yes (rewritten in place) |
| `data/processed/aoi_month.parquet`, `aoi_month_compare.parquet`, `aoi_list.parquet`, `aoi_boundaries.geojson` | R/W | release | yes |
| `reports/fetch_nrt_append_<date>_*.csv`, `hotspots_*.csv`, `static_exclusion_*.csv`, `aoi_weighting_compare.csv`, `*_run.log` | W | the chain (needs a writable `reports/`) | written |
| `data/processed/harmonized_cell_day_frp.parquet` | R (planner build) | release | yes (not refreshed by `--append`; FRP features of new days come from the release copy) |
| `data/interim/grid_occurrence/cells.parquet` | R (build, samples, final) | model team working data | **missing** |
| `data/interim/planner/daily/{P,V,R}.npy`, `samples/year=*.parquet`, `origin.parquet` | W then R | build, samples | written |
| `data/interim/planner/daily/selection.json` | R (final) | written only by `planner_daily select` (needs the fold OOFs = retraining) | **missing** |
| `data/processed/planner_daily_grid.parquet` | W | final | written |
| `data/processed/planner_aoi_weights.npz` | R (exists → kept) | release | yes |
| `data/interim/planner/monthly/region_calibration.json` | R (publish copies it) | model team working data | **missing** |
| `data/interim/planner/count_choice.json`, `region_sf_service_small.parquet`, `sgg/choice.json` | R (publish copies them) | model team working data | **missing** |
| `data/interim/planner/d30/iso30.json` | R (publish; optional) | model team working data | **missing**, and publish would then write `planner_30d_skill.json` with `iso: null`, silently dropping the 30-day calibration |
| `reports/planner_region_metrics.csv`, `reports/planner_30d_validation.csv` | R (publish) | repo (and release) | yes |
| `data/processed/planner_monthly_grid.parquet` | R (publish) | release | yes |
| `data/processed/planner_{region_calibration,count_choice,region_counts_sf,sgg_choice,region_skill,30d_skill,meta}.*` | W (publish) | publish | written |

So the probe `planner_publish refresh --last 2026-11-14` was **not run**. Its first missing input,
`data/interim/grid_occurrence/cells.parquet`, is needed by `build`. `final` also needs `selection.json`, and `publish`
needs five `data/interim/planner/**` files. None of these is in the release or the repo, and producing them means the
model team's selection/training runs. The CronJob is therefore shipped with `suspend: true`; the service keeps serving
the release as_of 2026-11-14. `refresh.py --preflight-only` checks the inputs of the list above without any network
call.

Before the CronJob can be unsuspended the model team has to provide a working tree for
`$HOME/planetarium/forecast-work/model/data` (PVC `forecast-work`) with every **missing** input above, as regular files
(`--append` rewrites them in place). The job copies `planetarium/` from the image into `forecast-work/model/` each run,
copies `models/`, `reports/` and `references/` once, runs the chain there, and touches `/state` only through
`layout.publish` + `layout.switch`. Checks before the switch:
- every planner file is present;
- as_of is newer than the served one (the same as_of is a no-op);
- the daily grid has the same cells and row count, dates as_of+1..+7, and probabilities in [0, 1];
- every unit calibrated in the served `planner_30d_skill.json` keeps its isotonic curve;
- a trial `forecast()` in a fresh process (SIDO_51 and a bbox) answers from the new as_of.

## Resources

| | requests | limits | why |
|---|---|---|---|
| service | 250m, 1Gi | 2 CPU, 1536Mi | measured offline: 0.52 GiB after warmup; 0.80 GiB peak (cgroup `memory.peak`) over WORLD and global bbox 30-day queries and 13-month WORLD / global bbox / global polygon queries. One `forecast()` at a time; polars may use more than one core inside a call |
| seed initContainer | 100m, 256Mi | 1 CPU, 512Mi | copies 60 MB and reads one parquet column |
| refresh CronJob | 2 CPU, 8Gi | 8 CPU, 20Gi | not measurable here (inputs missing); the model team quotes about 18 GB peak for `--append` and 17 GB for the planner refresh, about 8 + 9 minutes on their machine. `planner_daily` asks XGBoost for `device: cuda`, and the pod has no GPU |

## Owner steps (deploy)

```bash
# 1. image (any checkout of the model repo that has c43368c; it is only read)
PL_MODEL_REPO=<model repo> deploy/forecast/build.sh c43368c
docker save planetarium-forecast:c43368c | sudo -n k3s ctr images import -
# 2. host dirs (hostPath type Directory: they must exist; the pods run as uid 10001)
install -d -m 0755 "$HOME/planetarium/forecast-state" && sudo chown 10001:10001 "$HOME/planetarium/forecast-state"
install -d -m 0755 "$HOME/planetarium/forecast-work" && sudo chown 10001:10001 "$HOME/planetarium/forecast-work"
# 3. apply (namespace, release PV/PVC and pl-app come from planetarium.yaml / deploy.sh)
deploy/render.sh deploy/k8s/planetarium-forecast.yaml | kubectl apply -f -
kubectl -n planetarium rollout status deployment/pl-forecast
kubectl -n planetarium exec deploy/pl-forecast -- python -c "import urllib.request as u; print(u.urlopen('http://127.0.0.1:8000/healthz').read().decode())"
# 4. then FC-A (Spring + Worker allow-list) and the live smoke through /api/v1/forecast
```

Rebuilding with the same tag and re-importing needs `kubectl -n planetarium rollout restart deployment/pl-forecast`.
Each build also carries a `planetarium-forecast:c43368c-<sha8>` tag for rollback.

## Tests and smoke (offline, on this host)

```bash
S=<private scratch dir>; git -C <model repo> archive c43368c | tar -x -C $S/model
(cd $S/model && uv sync --frozen --no-dev --python 3.13)
(cd $S/model && FORECAST_TEST_MODEL=$S/model uv run --frozen --no-sync --with pytest \
   pytest <repo>/deploy/forecast/tests --basetemp $S/pytest -p no:cacheprovider)
deploy/forecast/smoke.sh $S/model $S/smoke # STATE=<seeded state dir> to reuse one
```
