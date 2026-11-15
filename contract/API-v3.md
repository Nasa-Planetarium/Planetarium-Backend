# API v3 — EMBERLINE 핸드오프 경로 (접두사 없음)

작성 2026-11-14 · 기준 문서: 팀 `docs/backend-handoff.md` 초안(데이터 버전 `v1-m2v-2026-11-14`) · 서버 `~/planetarium/api` (v3.0.0)

> **원칙** — 우리 서버를 갈아엎지 않고 **요청 파라미터·경로 이름·코드값·응답 필드 이름·규칙을 핸드오프에 맞춘다.**
> 핸드오프에 없는 우리 것은 지우지 않고 **「확장」** 으로 표시한다(§4). 핸드오프가 말하지 않아 우리가 정한 것은 **「가정」** 이다(§9).
> 핸드오프 자체가 초안이다 — 팀 확정본 `docs/api_contract.md` 가 오면 열 이름이 바뀐 곳만 따라 고친다.

---

## 0. 한 장 요약

| 경로 | 요청 파라미터 | 응답 핵심 | 읽는 파일 |
|---|---|---|---|
| `GET /aoi` | `level`, `parent_id`, `q`, `include_geometry` (+확장 `include_hidden`) | 지역 목록 | `aoi_list`, `aoi_boundaries.geojson` |
| `GET /hotspots` | `bbox=서,남,동,북` **또는** `aoi_id`, `start`, `end`(YYYY-MM-DD), `layer=reference\|all`, `limit` | 점 목록 + 기준 합 | `harmonized_hotspots/` |
| `GET /summary` | `aoi_id` **또는** `bbox` **또는** `polygon`(GeoJSON), `start`, `end`, `freq=day\|month` | 기간별 value·lo·hi·status·value_source | `aoi_month` 또는 `harmonized_hotspots/` |
| `GET /calendar` | `aoi_id`, `start_year`, `end_year` | 연×월 value + verdict·percentile·ratio_to_median·status | `aoi_month`, `aoi_month_compare` |
| `GET /compare` | `aoi_id`, `year`, `month` | 그 달 비교 상세 | `aoi_month_compare`, `aoi_month` |
| `GET /status` | `start`, `end` | 위성별 자료 상태·최신 갱신 시각 | `satellite_day_status` |
| `GET /export.csv` | `type=summary\|calendar\|hotspots` + 그 경로의 파라미터 | CSV | 위와 같음 |
| `GET /method` | — | 모델 이름·계수·성능 (`m2v_meta.json`) | `models/m2v_meta.json` |
| 확장 `GET /cells` | `aoi_id` 또는 `bbox`, `year`, `month` | 0.1° 칸 GeoJSON(+덩어리 열) | `harmonized_cell_month` |
| 확장 `GET /firms/wms` | `layer`, `bbox`, `width`, `height` … | PNG (서버가 MAP_KEY 로 대리) | FIRMS |

- 단위 표기: **「VIIRS(S-NPP) 기준 화재 감지량」** (`unit`). 「산불 건수」가 아니다. 2000–2011 은 추정값 → `value_lo`~`value_hi` 를 함께 보인다.
- **모든 성공 응답에 `method_version`** (JSON 최상위, GeoJSON 은 `properties`, CSV 는 열) + 헤더 `X-Method-Version`. 오류(problem+json)에도 `method_version`(파일이 적재된 경우).
- `/v2/*`·`/v1/*` 는 별칭으로 남고 `Deprecation: true` + `Link: </…>; rel="successor-version"` 를 단다(§7).

---

## 1. 코드값 (핸드오프 §3 그대로)

### 1.1 `value_source` — 값의 출처 (기간은 `date_local` 기준)

| 값 | 기간 | 기준 위성(`is_reference=true`) | 가중치 | 화면 표시 예 |
|---|---|---|---|---|
| `modis_m2v_terra` | 2000-11-01 ~ 2002-06-30 | Terra(+Aqua) | 모델 M2V(Terra) | 추정 (Terra) |
| `modis_m2v` | 2002-07-01 ~ 2011-12-31 (가정: 2012-01-01~19 도 여기) | Terra+Aqua | 모델 M2V | 추정 (MODIS → VIIRS) |
| `snpp_observed` | 2012-01-20 ~ 2026-11-02 | S-NPP (`N`) | 1 | 실측 |
| `n20_bridge` | 2026-11-03 ~ | NOAA-20 (`N20`) | **× 1.013** | 실측 (NOAA-20 환산) |
| `n21_bridge` | NOAA-20 결측일만 | NOAA-21 (`N21`) | **× 1.053** | 대체 위성 |

- 월 단위 `value_source`(aoi_month·cell_month·/summary month) = **그 달에서 가장 많은 날을 차지한 출처**(가정 — 핸드오프에 월 규칙 없음). 2012-01 → `modis_m2v`(19일), 2026-11 → `n20_bridge`. 날별 나뉨은 `/status` `days[]` 와 `/summary?freq=day` 로 본다.
- v0 이름은 받기만 한다(별칭): `modis_harmonized`→`modis_m2v`, `noaa20_bridged`→`n20_bridge`, `modis_harmonized_terra_only`→`modis_m2v_terra`. 응답에는 v1 이름만 나온다.

### 1.2 `status` — 자료 상태

| 값 | 의미 | 화면 처리 | 출처 |
|---|---|---|---|
| `ok` | 정상 | 일반 | 핸드오프 |
| `nrt` | 빠른 처리본, 이후 바뀔 수 있음 | 점선/연한 색 + 안내 | 핸드오프 |
| `partial` | 진행 중인 달 | 「집계 중」 | 핸드오프 |
| `missing` | 위성 자료 부족 | 회색, **0으로 칠하지 않기** (`value=null`) | 핸드오프 |
| `low_sample` | 예년 기록이 너무 적음 | 판정 생략 | 핸드오프 — 단, `aoi_month.status` 에는 안 나온다. 비교 표의 **플래그** `low_sample`(bool, 기준 분포 `baseline_p75 < 1`, 팀 `analysis.py` 기준)로 받고 `/calendar`·`/compare` 에 `low_sample` 필드로 준다 |
| `out_of_range` | 파일에 행이 없는 달·날(2000-11 이전, 마지막 자료일 이후, AOI 에 행 없음) | 회색 + 「범위 밖」 | **확장** (파일에는 못 씀) |

### 1.3 `verdict` — 예년 대비

| 값 | 기준 |
|---|---|
| `above` | 백분위 ≥ 80 |
| `similar` | 20 < 백분위 < 80 |
| `below` | 백분위 ≤ 20 |
| `uncertain` | 비교 연도 < 10, 또는 그 달 status 가 `missing`·`partial`, 또는 `low_sample`(판정 생략), 또는 값 없음, 또는 `hidden` AOI(팀 코드) |

- 기준 분포 = **2001~2025 같은 달, 해당 연도 제외, `status=ok` 인 달만**. `n_baseline_years` = 그 개수.
- 백분위: 중간 순위 `100 × (작은 수 + 0.5 × 같은 수) / n` — 팀 `analysis.py` 식과 같다. 응답은 소수 1자리로 반올림하지만 **판정은 반올림 전 값**으로 한다. `ratio_to_median = value / baseline_median`(중앙값 0 이면 null).
- `low_sample` → `uncertain` 은 핸드오프 「판정 생략」을 따른 것. 팀 `analysis.py` 는 `low_sample` 이어도 값 ≥ 3 이면 `above` — 팀 파일을 받으면 검사기가 「compare 재계산과 다름」 경고만 내고 파일 값을 그대로 쓴다(§11).
- `nrt` 달은 판정한다(핸드오프의 uncertain 조건에 없음).

### 1.4 그 밖

- `data_source`: `SP`(정식) / `NRT`(빠른 처리본). 점 파일에서는 열 이름이 `source`.
- `satellite`: `N`(S-NPP) / `N20` / `N21` / `Terra` / `Aqua`; `instrument`: `VIIRS` / `MODIS`; `confidence`: `l`/`n`/`h`; `daynight`: `D`/`N`.
- `level`: `world` `continent` `country` `adm1` `kr_sido` `kr_sgg` (+ **확장** `group`).
- aoi_id: `KR`, `SIDO_11`, `SGG_11110`, … (+ **확장** `group:gb-north` 경북 북부 11 시군 묶음, `parent_id=SIDO_47`, `hidden=false`).

---

## 2. 규칙 (핸드오프 §4)

1. **0 과 결측**: 칸·월 행이 없으면 0. 그 달이 결측인지는 `aoi_month.status`. API 는 결측 달·날을 `value=null` 로, 0 은 `0` 으로 준다. 점으로 다시 셀 때(`bbox`·`polygon`·달을 자르는 기간)의 달 상태는 팀 `harmonize.py` 순서를 따른다: `partial` > `missing`(기준 위성 자료가 그 기간 날의 50 % 미만) > `nrt` > `ok`. 관측한 날이 하나도 없는 `partial` 달은 0 이 아니라 null.
2. **합계는 `is_reference=true` 점만**: Σ`harmonized_weight` = `value`, Σ`weight_lo`/`weight_hi` = `value_lo`/`value_hi`. `layer=all` 은 지도 표시용 — `/hotspots` 의 `reference_sum` 은 `layer` 와 무관하게 기준 점만 더한다.
3. **날짜는 `date_local`**(현지 태양시 날짜) 로 거른다. `acq_datetime_utc` 는 UTC 시각(응답은 `…Z` 문자열).
4. **`method_version` 은 모든 응답에**.
5. (가정) **AOI 소속 = 칸 중심이 경계 안**. `aoi_id` 로 점을 거를 때도 점 좌표가 아니라 그 점의 `cell_id` 가 AOI 칸인지로 거른다 → `/hotspots?aoi_id` 의 기준 합 = `aoi_month.value` 가 성립한다. `bbox`·`polygon` 은 점 좌표로 거른다.
6. 성능: 점은 요청 기간의 `year=/month=` 폴더만 읽는다. `/hotspots` 는 `limit`(기본 1000, 최대 10000)·기간 ≤ 92일(확장 상한).

---

## 3. 경로

공통 오류: 형식이 틀린 입력 400 `invalid_param`(어느 파라미터인지 `param`), 범위 상한 초과 400 `range_too_large`, 없는 aoi_id 404 `unknown_aoi`, 파일 없음 503 `data_not_loaded`. 날짜는 `YYYY-MM-DD` 만(달력에 없는 날 400), `start > end` 400.

### 3.1 `GET /aoi`

| 파라미터 | 값 | 400/404 |
|---|---|---|
| `level` | §1.4 의 값 | 목록 밖 → 400 |
| `parent_id` | aoi_id | aoi_list 에 없으면 404 |
| `q` | 이름 검색(이름 ko/en·aoi_id 부분 일치, 대소문자 무시, ≤100자) | 100자 넘으면 400 |
| `include_geometry` | `true`/`false`(기본) | 그 밖 400 |
| 확장 `include_hidden` | `true`/`false`(기본) — `hidden=true` 행은 기본으로 숨김 | 그 밖 400 |

```json
GET /aoi?parent_id=SIDO_47
{"method_version": "v1-m2v-fake-2026-11-14", "unit": "VIIRS(S-NPP) 기준 화재 감지량", "count": 12,
 "aois": [{"aoi_id": "SGG_47170", "name_ko": "안동시", "name_en": "Andong", "level": "kr_sgg", "parent_id": "SIDO_47",
           "n_cells": 16, "hidden": false,
           "has_data": true, "first_month": "2000-11", "last_month": "2026-11", "bbox": [128.429, 36.295, 129.001, 36.823]}, …]}
```
`has_data`·`first_month`·`last_month`·`bbox` 는 확장. `include_geometry=true` 면 `geometry`(GeoJSON).

### 3.2 `GET /hotspots`

| 파라미터 | 값 | 400 |
|---|---|---|
| `bbox` 또는 `aoi_id` | 둘 중 **정확히 하나** | 없음·둘 다 → 400 (`param` = `aoi_id` 또는 두 번째 것) |
| `start`, `end` | YYYY-MM-DD, 필수 | 형식·순서·92일 초과 |
| `layer` | `reference`(기본) / `all` | 그 밖 |
| `limit` | 1–10000, 기본 1000 | 범위 밖 |

```json
GET /hotspots?aoi_id=SGG_47170&start=2025-03-22&end=2025-03-23&limit=1
{"aoi_id": "SGG_47170", "start": "2025-03-22", "end": "2025-03-23", "layer": "reference", "limit": 1,
 "count": 1, "total": 9, "truncated": true, "unit": "VIIRS(S-NPP) 기준 화재 감지량",
 "reference_sum": {"value": 9.0, "value_lo": 9.0, "value_hi": 9.0, "n_points": 9},
 "hotspots": [{"latitude": 36.6536, "longitude": 128.61977, "acq_datetime_utc": "2025-03-21T16:43:00Z",
   "date_local": "2025-03-22", "satellite": "N", "instrument": "VIIRS", "frp": 0.9, "confidence": "n", "daynight": "N",
   "cell_id": 4560686, "country": "KOR", "adm1": "SIDO_47", "harmonized_weight": 1.0, "weight_lo": 1.0, "weight_hi": 1.0,
   "weight_source": "snpp_observed", "is_reference": true, "source": "SP", "method_version": "v1-m2v-fake-2026-11-14"}],
 "method_version": "v1-m2v-fake-2026-11-14"}
```
- 점 필드 = 핸드오프 §1-1 열 그대로, 같은 순서. 정렬: `acq_datetime_utc`, 위도, 경도, 위성.
- `count`·`total`·`truncated`·`reference_sum` 은 확장(`reference_sum` 은 `limit` 와 무관하게 조건에 맞는 기준 점 전체 합).
- 위 예의 UTC 시각(3/21 16:43Z)과 `date_local`(3/22)이 다른 것이 규칙 3 이다.

### 3.3 `GET /summary`

| 파라미터 | 값 | 400 |
|---|---|---|
| `aoi_id` / `bbox` / `polygon` | **정확히 하나**. `polygon` = GeoJSON Polygon·MultiPolygon(또는 그 Feature) 문자열, 꼭짓점 ≤ 5000, 유효(자기 교차 없음) | 없음·둘 이상·JSON 아님·Point 등·무효·좌표 범위 밖 |
| `start`, `end` | YYYY-MM-DD, 필수 | `freq=day` 는 ≤ 366일, `month` 는 ≤ 40년 |
| `freq` | `month`(기본) / `day` | 그 밖 |

계산 경로:
- `freq=month` + `aoi_id` + **그 달 전체가 기간 안** → `aoi_month` 행 그대로(`basis: "aoi_month"`). 핸드오프 파일 값과 같다.
- 그 밖(일 단위, `bbox`·`polygon`, 기간이 달을 자르는 경우) → `harmonized_hotspots` 기준 점 합(`basis: "hotspots"`, 달을 자르면 `flags: ["partial_range"]`). 점 파일이 없으면 503.
- 날 상태: 그 날의 기준 위성이 `satellite_day_status` 에서 모두 `missing` → `missing`(value null), `nrt`/NRT → `nrt`, 마지막 자료일 뒤·2000-11 전 → `out_of_range`(확장).

```json
GET /summary?aoi_id=KR&start=2005-03-01&end=2005-03-02&freq=day
{"aoi_id": "KR", "start": "2005-03-01", "end": "2005-03-02", "freq": "day", "unit": "VIIRS(S-NPP) 기준 화재 감지량",
 "series": [{"date": "2005-03-01", "value": 2.084, "value_lo": 1.205, "value_hi": 3.534,
             "value_source": "modis_m2v", "data_source": "SP", "status": "ok"}, …],
 "total": {"value": 4.168, "value_lo": 2.41, "value_hi": 7.068, "n_missing": 0, "n_out_of_range": 0},
 "method_version": "v1-m2v-fake-2026-11-14"}

GET /summary?aoi_id=KR&start=2026-11-01&end=2026-11-30
{"series": [{"date": "2026-11-01", "year": 2026, "month": 11, "value": 15.209, "value_lo": 15.209, "value_hi": 15.209,
             "value_source": "n20_bridge", "data_source": "NRT", "status": "partial",
             "missing_dates": ["2026-11-05", "2026-11-06"], "flags": [], "basis": "aoi_month"}], …}
```
일 항목 필드는 핸드오프 예시와 같다(`date, value, value_lo, value_hi, value_source, status` + `data_source`). 월 항목의 `year`·`month`·`missing_dates`·`flags`·`basis`, 그리고 `total` 은 확장.

### 3.4 `GET /calendar`

| 파라미터 | 값 | 400/404 |
|---|---|---|
| `aoi_id` | 필수 | 없음 400, 모르는 id 404 |
| `start_year`, `end_year` | 2000–2100 정수, 기본 = 그 AOI 자료의 첫·끝 해 | 정수 아님·범위 밖·순서·40년 초과 |

```json
GET /calendar?aoi_id=KR&start_year=2025&end_year=2025
{"aoi_id": "KR", "method_version": "v1-m2v-fake-2026-11-14", "unit": "VIIRS(S-NPP) 기준 화재 감지량",
 "start_year": 2025, "end_year": 2025,
 "months": [{"year": 2025, "month": 1, "value": 21.0, "value_lo": 21.0, "value_hi": 21.0, "raw_modis": 21, "raw_viirs": 21,
             "value_source": "snpp_observed", "data_source": "SP", "status": "ok", "n_cells": 14,
             "verdict": "below", "percentile": 8.3, "ratio_to_median": 0.429,
             "missing_dates": [], "days_covered": 31,
             "n_events_viirs": 21, "event_area_max_km2_viirs": 1.0, "event_days_max_viirs": 1, "event_area_share_max_viirs": 0.07,
             "n_events_modis": 21, "event_area_max_km2_modis": 9.75, "event_days_max_modis": 1, "event_area_share_max_modis": 0.079},
            …],
 "event_columns": ["n_events_viirs", …]}
```
- 요청한 해의 1–12월을 모두 준다. 행이 없는 달은 `status: "out_of_range"`, 값 null(확장).
- `missing_dates`·`days_covered`·덩어리 8열·`event_columns` 는 확장(파일에 있을 때만 값, 없으면 null).

### 3.5 `GET /compare`

`aoi_id`(필수), `year`(2000–2100), `month`(1–12) — 빠지거나 범위 밖이면 400.

```json
GET /compare?aoi_id=group:gb-north&year=2025&month=3
{"aoi_id": "group:gb-north", "year": 2025, "month": 3, "unit": "VIIRS(S-NPP) 기준 화재 감지량",
 "value": 443.0, "baseline_median": 137.542, "baseline_p25": 101.072, "baseline_p75": 178.5,
 "percentile": 100.0, "ratio_to_median": 3.221, "n_baseline_years": 23, "verdict": "above",
 "status": "ok", "value_source": "snpp_observed", "value_lo": 443.0, "value_hi": 443.0,
 "baseline": [{"year": 2001, "value": 131.426, "status": "ok", "value_source": "modis_m2v_terra", "used": true}, …,
              {"year": 2008, "value": null, "status": "missing", "value_source": "modis_m2v", "used": false}, …],
 "rule": {"id": "pct-80-20/n10/ok-only", …}, "method_version": "v1-m2v-fake-2026-11-14"}
```
- `value`~`verdict` 8개 = `aoi_month_compare` 행 그대로. `status`·`value_source`·`value_lo/hi`·`baseline[]`·`rule` 은 확장(`baseline[]` 은 그 행의 `baseline_period`(열이 없는 release 는 2001–2025) 중 해당 연도 뺀 해, `used` = 기준 분포에 들어갔는지). data-v1.2 r3: `baseline_period`·`continuity_ratio`·`continuity_source` 도 행 그대로(MAPPING §8.5).
- 행이 없는 달 → `status: "out_of_range"`, 나머지 null.

### 3.6 `GET /status`

`start`, `end`(YYYY-MM-DD, 선택 — 기본 마지막 자료일까지 30일, ≤ 366일).

```json
GET /status?start=2026-11-04&end=2026-11-07
{"start": "2026-11-04", "end": "2026-11-07", "updated_at": "2026-11-12T03:00:00Z", "through": "2026-11-11",
 "satellites": [{"satellite": "N20", "instrument": "VIIRS", "first_date": "2018-01-05", "last_date": "2026-11-11",
                 "days": {"nrt": 1, "missing": 3}, "missing_dates": ["2026-11-05", "2026-11-06", "2026-11-07"],
                 "nrt_from": "2026-07-01"}, …],
 "days": [{"date": "2026-11-05", "value_source": "n21_bridge", "satellites": ["N21"], "status": "missing", "data_source": null},
          {"date": "2026-11-07", "value_source": "n21_bridge", "satellites": ["N21"], "status": "nrt", "data_source": "NRT"}, …],
 "method_version": "v1-m2v-fake-2026-11-14"}
```
`updated_at` = `satellite_day_status.updated_at` 최댓값(핸드오프 「최신 갱신 시각」). `days[]` 는 날마다 어느 출처·위성이 기준인지(확장).

### 3.7 `GET /export.csv`

`type` 필수(`summary`·`calendar`·`hotspots`, 그 밖 400) + 그 경로의 파라미터(검증도 같다). `Content-Disposition: attachment; filename="emberline_<type>_<area>_<기간>….csv"`.

| type | 열 |
|---|---|
| `summary` | `area, date, value, value_lo, value_hi, value_source, data_source, status, missing_dates, freq, method_version` |
| `calendar` | `aoi_id, year, month, value, value_lo, value_hi, raw_modis, raw_viirs, value_source, data_source, status, verdict, percentile, ratio_to_median, n_cells, missing_dates, method_version` + 덩어리 8열(확장) |
| `hotspots` | 핸드오프 §1-1 점 열 19개(마지막이 `method_version`) |

### 3.8 `GET /method`

```json
{"method_version": "v1-m2v-fake-2026-11-14", "unit": "VIIRS(S-NPP) 기준 화재 감지량", "unit_code": "snpp_equiv_hotspots",
 "meta": { …m2v_meta.json 그대로… },
 "value_sources": [ …§1.1 표… ], "statuses": ["ok", "nrt", "partial", "missing", "low_sample", "out_of_range"],
 "verdict_rule": {"above_if_percentile_ge": 80, "below_if_percentile_le": 20, "uncertain_if_years_lt": 10, …},
 "rules": [ …§2… ], "sources": [{"name": "NASA FIRMS", "credit": "…"}, …],
 "data_version": "…", "files": {"hotspots": true, "aoi_count": 30},
 "ingest": {"loaded_at": "…", "aliases_used": [], "warnings": []}}
```
`meta` 외는 확장. 출처 표시(핸드오프 §4.6)는 `sources` 로 준다.

---

## 4. 확장 (우리 것 — 핸드오프에 없음, 유지)

| 확장 | 어디 | 이유 |
|---|---|---|
| `GET /cells?aoi_id\|bbox&year&month` | 0.1° 칸 GeoJSON, 칸 속성 = `harmonized_cell_month` 열(`value, value_lo, value_hi, raw_modis, raw_viirs, value_source, data_source, status` + 덩어리 8열), `properties.month_status` | 지도 격자(핸드오프 §1 「지도 격자」에 경로가 없음). 칸 없는 곳은 0 |
| 덩어리 열 `n_events_* · event_area_max_km2_* · event_days_max_* · event_area_share_max_*` (`_viirs`·`_modis`) | `harmonized_cell_month`·`aoi_month` 의 **선택 열** → `/calendar`·`/cells`·`/export.csv` 로 그대로 | API-v2 §2.5 규칙 그대로. 없으면 null |
| 선택 열 `active_days`, `frp_sum`, `missing_days`(칸) / `active_days*`, `missing_dates`, `days_covered`(AOI) | 파일 선택 열 | `/v2` 별칭과 `/calendar` 의 결측일 안내 |
| `status=out_of_range` | 응답만 | 파일 밖 달·날을 null 과 구별 |
| `GET /firms/wms` | FIRMS WMS 대리 — 키는 서버 `.env` 에만, 응답·로그·오류에 키 없음, 한국 bbox 로 자름, 15분 캐시, 상류 예산 | 핸드오프 §4.5 「키는 서버에만」 |
| 오류 = `application/problem+json` (`type, title, status, code, param, detail, request_id, method_version`) | 모든 경로 | 500 은 내부 정보 없이 `request_id` 만 |
| `ETag` + `If-None-Match` → 304, `Cache-Control`(nrt·partial 포함 300초, 그 밖 86400초), `X-Data-Version`·`X-Method-Version` | 모든 경로 | |
| `group:gb-north` | aoi_list `level=group`, `parent_id=SIDO_47` | 경북 북부 묶음(검증 범위) |
| `/aoi` `include_hidden`, `has_data`·`first_month`·`last_month`·`bbox` | `/aoi` | |
| `/hotspots` `reference_sum`·`count`·`total`·`truncated`, `/summary` `total`·`basis`·`flags`, `/compare` `baseline[]`·`rule`, `/status` `days[]` | 응답 필드 | 규칙 2·검산용 |
| 상한: `/hotspots` 92일, `/summary` 일 366일·월 40년, `/status` 366일, `/calendar` 40년, polygon 꼭짓점 5000 | 400 `range_too_large` | |

---

## 5. 입력 파일 (핸드오프 §1 — 가짜 생성기·검사기가 이 열로 만든다·본다)

필수 7개가 하나라도 없으면 분석 경로 503. `harmonized_hotspots/` 는 선택(핸드오프: 「다음 작업에서 만들어져요」) — 없으면 `/hotspots`·일 단위 `/summary` 만 503.

| 파일 | 열 (굵게 = 핸드오프, 나머지 확장·선택) | 키 |
|---|---|---|
| `harmonized_hotspots/year=YYYY/month=MM/*.parquet` | **latitude, longitude, acq_datetime_utc, date_local, satellite, instrument, frp, confidence, daynight, cell_id, country, adm1, harmonized_weight, weight_lo, weight_hi, weight_source, is_reference, source, method_version** | 폴더 = date_local 의 연·월 |
| `harmonized_cell_month.parquet` | **cell_id, cell_lat, cell_lon, year, month, value, value_lo, value_hi, raw_modis, raw_viirs, value_source, data_source, status, method_version** + active_days, frp_sum, missing_days, 덩어리 8열 | (cell_id, year, month), 0 아닌 행만(덩어리만 있는 칸은 0 행) |
| `aoi_month.parquet` | **aoi_id, year, month, value, value_lo, value_hi, raw_modis, raw_viirs, value_source, data_source, status, n_cells** + active_days, active_days_modis_raw, active_days_viirs_raw, missing_dates, days_covered, method_version, 덩어리 8열 | (aoi_id, year, month) |
| `aoi_month_compare.parquet` | **aoi_id, year, month, value, baseline_median, baseline_p25, baseline_p75, percentile, ratio_to_median, n_baseline_years, verdict** + status, low_sample (팀 `analysis.py` 가 쓰는 선택 열) + baseline_period, continuity_ratio, continuity_source (data-v1.2 r3 선택 열) | (aoi_id, year, month) |
| `aoi_list.parquet` | **aoi_id, name_ko, name_en, level, parent_id, n_cells, hidden** (`name_en` 이 없으면 경고 후 null — 팀 현재 코드는 `name` 하나) | aoi_id |
| `aoi_boundaries.geojson` | FeatureCollection, `properties` = aoi_list 열 7개(가짜 생성기) — 서버가 쓰는 것은 `aoi_id` 와 (Multi)Polygon 뿐 | aoi_id |
| `satellite_day_status.parquet` | (가정) **satellite, date, status** + data_source, updated_at | (satellite, date) |
| `../../models/m2v_meta.json` | **method_version**(필수) + 나머지 자유(가짜: model, coefficients, interval, performance, value_sources, validation[]) | — |

검사(`scripts/check_model_outputs.py <processed> <models>`, 서버 시작 때 같은 검사): 필수 열·형식·키 중복·코드값 밖(`out_of_range` 포함)·음수·`value_lo ≤ value ≤ value_hi`·백분위 0–100·점 폴더 ≠ date_local·파일 사이 `method_version` 불일치·aoi_list 에 없는 aoi_id → **거부(서버가 안 뜸)**. compare 재계산과 다름·Σ기준 무게 ≠ 칸 값·경계 없는 AOI → **경고**(`/method ingest.warnings`).

옛 이름 별칭(받기만): v0 `value_source` 3개(§1.1), 우리 v2 가정 열 `n_det→value`, `n_det_q10→value_lo`, `n_det_q90→value_hi`, `n_det_modis_raw|count_modis|count_raw_modis→raw_modis`, `n_det_viirs_raw|count_viirs|count_raw_viirs→raw_viirs`, `count_harmonized→value`, `source_flag→data_source`, `model_version→method_version`, `lat→cell_lat`, `lon→cell_lon`. aoi_list 만: 팀 `harmonize.py` 이름 `name→name_ko`, `parent_aoi→parent_id`, level 코드 `kr→country`, `sido→kr_sido`, `sgg→kr_sgg`(+ 확장 level `user` = 팀 `USER_<n>`).

---

## 6. v2 → v3 대응표

### 6.1 경로

| v2 | v3 | 비고 |
|---|---|---|
| `/v2/aoi?country=&geometry=` | `/aoi?level=&parent_id=&q=&include_geometry=` | 나라 필터 → `level`/`parent_id` |
| `/v2/status?aoi=&start=YYYY-MM&end=` | `/status?start=YYYY-MM-DD&end=` (위성별) + 월 상태는 `/calendar`·`/summary` | v2 는 AOI 의 월 상태, v3 는 위성×날 |
| `/v2/calendar?aoi=&start=YYYY-MM&end=&view=&metric=` | `/calendar?aoi_id=&start_year=&end_year=` | `view`·`metric` 없음(원 감지 수는 `raw_modis`·`raw_viirs` 필드) |
| `/v2/compare?aoi=&year=&month=&metric=` | `/compare?aoi_id=&year=&month=` | |
| `/v2/cells?aoi=&year=&month=&view=&include_zero=` | 확장 `/cells?aoi_id\|bbox=&year=&month=` | 0 행은 파일에 없음 |
| `/v2/export.csv?aoi=&start=&end=&view=&metric=` | `/export.csv?type=calendar\|summary\|hotspots&…` | |
| `/v2/method` | `/method` | |
| `/v2/firms/wms` | 확장 `/firms/wms` | 같은 처리기 |
| `/v2/hotspots`(v1 23열 원자료) | `/hotspots`(핸드오프 점, 무게 포함) | 파라미터·필드 모두 다름 |
| — | `/summary` | 새 경로 (임의 기간·bbox·polygon 합) |

### 6.2 파라미터

| v2 | v3 |
|---|---|
| `aoi=group:gb-north` | `aoi_id=group:gb-north` (확장 id 유지) |
| `aoi=country:KOR` | `aoi_id=KR` |
| `aoi=region:KR-11` … `region:KR-49` | `aoi_id=SIDO_11` … (ISO→행정코드: KR-42→`SIDO_51`, KR-45→`SIDO_52`, KR-49→`SIDO_50`, KR-50→`SIDO_36`, 나머지 같은 숫자) |
| `aoi=region:KR-47/andong` 등 | `aoi_id=SGG_47170` 등 (`aoi_ids.py`) |
| `aoi=bbox:w,s,e,n` | `bbox=w,s,e,n` (`/hotspots`·`/summary`·`/cells`) |
| `start=YYYY-MM`, `end=YYYY-MM` (calendar) | `start_year`, `end_year` |
| `start=YYYY-MM`, `end=YYYY-MM` (status) | `start=YYYY-MM-DD`, `end=YYYY-MM-DD` |
| `view=raw_modis\|raw_viirs\|harmonized` | 없음 — 응답의 `value` / `raw_modis` / `raw_viirs` |
| `metric=n_det\|active_days` | 없음 (`active_days` 는 파일 선택 열) |
| `geometry=none\|geojson` | `include_geometry=false\|true` |
| `include_zero` | 없음 |
| — | `level`, `parent_id`, `q`, `layer`, `limit`, `freq`, `polygon`, `type` |

### 6.3 응답 필드

| v2 | v3 |
|---|---|
| `aoi` | `aoi_id` |
| `id`·`name{ko,en}` (aoi 목록) | `aoi_id`·`name_ko`·`name_en`·`level`·`parent_id`·`n_cells`·`hidden` |
| `value` (view 에 따라 다른 값) | `value`(기준 감지량) + `raw_modis`·`raw_viirs` |
| `q10`, `q90` | `value_lo`, `value_hi` (실측 기간도 채워짐 = value) |
| `years[].months[]` (중첩) | `months[]` (평평, 각 항목에 `year`·`month`) |
| `flags[]` | 없음 — `status`·`value_source` 로 (확장 `missing_dates` 유지) |
| `baseline{n, median, p10, p90, …}` | `n_baseline_years`, `baseline_median`, `baseline_p25`, `baseline_p75` (+확장 `baseline[]`) |
| `value{value,q10,q90,status,…}` (compare) | 최상위 `value`, `value_lo`, `value_hi`, `status`, `value_source` |
| `selectable` | 없음 (`status` 가 `partial`·`missing`·`out_of_range` 면 선택 불가로 그대로 판단) |
| `data_version`, `method_version` | `method_version`(모든 응답) + 헤더 `X-Data-Version` |
| `model_version`(파일) | `method_version` |
| `n_det`·`n_det_q10`·`n_det_q90`·`n_det_modis_raw`·`n_det_viirs_raw`(파일) | `value`·`value_lo`·`value_hi`·`raw_modis`·`raw_viirs` |
| `lat`·`lon`(칸 파일) | `cell_lat`·`cell_lon`; `cell_id` 문자열 `c360_1285` → 정수 |
| `unit: "snpp_equiv_hotspots"` | `unit: "VIIRS(S-NPP) 기준 화재 감지량"` (+`/method` `unit_code`) |
| `month_basis: "local_kst"` | 날짜 기준 = `date_local`(현지 태양시) |

### 6.4 코드값

| 종류 | v2 | v3 |
|---|---|---|
| value_source | `modis_harmonized` | `modis_m2v_terra`(~2002-06) / `modis_m2v` |
| value_source | `snpp_observed` | `snpp_observed` |
| value_source | `noaa20_bridged` (×0.94 생성기 상수) | `n20_bridge` (×1.013) |
| value_source | — | `n21_bridge` (×1.053, NOAA-20 결측일) |
| value_source | `mixed` (2012-01·2026-11) | 없음 — 월은 다수 출처(§1.1), 날별은 `/status days[]` |
| 경계 | S-NPP 정지 2026-11-02T13:00Z (순간) | 2026-11-02 까지 S-NPP, 2026-11-03 부터 NOAA-20 (`date_local`) |
| verdict | `more` / `similar` / `less` / `uncertain` / null(partial·범위 밖) | `above` / `similar` / `below` / `uncertain` (partial 도 `uncertain`) |
| 판정 기준 | 백분위 ≥75 / ≤25, n<15, 결측일 ≥10% → uncertain, 기준 분포 = 값 있는 달 | 백분위 ≥80 / ≤20, n<10, 기준 분포 = **status=ok 달만** |
| status | 6종 (같음) | 같음 (`out_of_range` 는 v3 에서 확장으로 표시) |
| 결측일 | NOAA-20 2026-11-05..07 → 3일 결측 | 11-05·06 결측, 11-07 은 NOAA-21 대체(`n21_bridge`) |
| satellite | `S-NPP`·`NOAA-20`·`NOAA-21` (23열 원자료) | `N`·`N20`·`N21` |
| confidence | `low`·`nominal`·`high` | `l`·`n`·`h` |

---

## 7. `/v2`·`/v1` 별칭

- `/v2/*` 는 같은 핸드오프 표에서 계산하되 **API-v2.md 계약 그대로** 답한다: id 를 번역(`region:KR-42` ↔ `SIDO_51` …), `value_source` 는 v2 이름(`modis_harmonized`·`snpp_observed`·`noaa20_bridged`·`mixed`)으로, `q10`/`q90` 은 모델 추정 달만. 판정은 v2 규칙(75/25/n15). aoi 목록은 대응 id 가 있는 30개(KR·시도 17·경북 북부 시군 11·묶음).
- v2 쪽에서 달라진 것(가짜 자료가 바뀌어서): 모든 AOI 가 2000-11 부터, 강원(`region:KR-42`)도 2000-11 부터(이전 후보 2012-01~), 2026-11 결측일 2개(11-07 은 NOAA-21), `/v2/method` `models[1].selected_model = null`(NOAA-20 은 맞춘 모델이 아니라 핸드오프 계수).
- `/v1/*`·`/v2/*` 모든 응답: `Deprecation: true` + `Link: <v3 경로>; rel="successor-version"`.

---

## 8. 프런트 목 어댑터(`api.js`)가 바꿀 것 — 목록만 (적용은 그쪽 작업)

1. 경로 문자열 `"/v2/…"` → `"/aoi"`, `"/calendar"`, `"/compare"`, `"/status"`, `"/export.csv"`, `"/method"`, `"/cells"`, `"/firms/wms"`; 새 `"/summary"`, `"/hotspots"`(핸드오프 점).
2. 파라미터: `aoi`→`aoi_id`(+ id 번역 §6.2), calendar `start/end(YYYY-MM)`→`start_year/end_year`, `view`·`metric`·`include_zero`·`country`·`geometry` 제거, `include_geometry` 추가.
3. `RAW_COL`·`V2_CSV_COLS`·`VIEWS`·`METRICS` 상수 → v3 필드(`value, value_lo, value_hi, raw_modis, raw_viirs`)와 §3.7 CSV 열.
4. `vsOf()`·`REGIMES` → §1.1 다섯 코드, 경계 날짜 2002-07-01·2012-01-20·2026-11-03, `mixed` 제거, NOAA-20 결측일엔 `n21_bridge`.
5. 판정 `RULE`(75/25/n15/결측10%) → 80/20/n10/status=ok 기준 분포, `more/less` → `above/below`, partial 의 verdict null → `uncertain`.
6. 응답 모양: `years[].months[]` → 평평한 `months[]`; compare 의 `value{}`·`baseline{}` → 최상위 필드; `q10/q90` → `value_lo/value_hi`; `flags` 의존(`platform_change`·`terra_only`·`sensor_started_mid_month`) 제거 → `value_source` 로 표시.
7. 단위 문구 → 「VIIRS(S-NPP) 기준 화재 감지량」, 2000–2011 「추정값」 + 범위.
8. `method_version` 을 화면 하단·CSV 에 그대로 표시, `/method` `sources` 로 FIRMS 인정 문구.
9. 목 생성기의 n20 비율 0.94 → 1.013, n21 1.053 추가.

---

## 9. 가정 · 팀 확인 질문

| 가정 (핸드오프에 없어서 정함) | 확인할 것 |
|---|---|
| `satellite_day_status` 열 = `satellite, date, status(ok/nrt/missing…), data_source, updated_at` | 실제 열 이름 |
| 월 `value_source` = 그 달 다수 날의 출처 | 팀 aoi_month 가 경계 달을 어떻게 적는지 |
| 2012-01-01~19 = `modis_m2v` (표에는 ~2011-12 / 2012-01-20~ 사이가 비어 있음) | |
| 백분위 = 중간 순위, 소수 1자리 | 팀 계산식 (다르면 검사기가 경고만 낸다) |
| AOI 소속 = 칸 중심이 경계 안 (팀 harmonize.py 는 칸×AOI `weight` 를 쓰는 것으로 보임 — 코드 읽은 추론) | 부분 소속 무게를 aoi_month 에 반영하는지 |
| `cell_id` = `(floor(lat×10)+900)×3600 + (floor(lon×10)+1800)` | 팀 칸 번호 체계 (서버는 `cell_id`·`cell_lat`·`cell_lon` 을 파일에서 읽으므로 번호 체계에 기대지 않는다) |
| SIDO 코드 = 행정표준코드 앞 2자리(2025-12-31 경계: 강원 51·전북 52) | 팀 `sido_20251231.sidocd` |
| 군위군 `SGG_47720`(우리 경계가 아직 경북) — 팀 2025 경계는 대구 `27720` | 팀 경계로 바꾸면 사라짐 |
| `country` = ISO3(`KOR`), `adm1` = 한국은 `SIDO_xx` | 팀 값 |
| `nrt` 달은 판정. `low_sample` = 비교 표 플래그 `baseline_p75 < 1`(팀 `analysis.py` 에서 가져옴 — 핸드오프에 기준 없음) → `uncertain` | 핸드오프 「판정 생략」과 팀 코드(값 ≥ 3 이면 above) 중 어느 쪽이 확정인지 |
| 달 `missing` = 기준 위성 자료가 그 달 날의 50 % 미만(팀 `harmonize.py`). 가짜 생성기와 `/summary` 의 점 재집계가 같은 규칙 | 핸드오프 「위성 자료 부족」의 수치 기준 |
| `m2v_meta.json` 은 `method_version` 만 필수 | 팀 키 이름 |

---

## 10. 잰 것 · 추론 · 안 잰 것 (2026-11-14)

- **잰 것(검토 후)**: 검토 수리 커밋에서 `pytest` 183 passed(rc 0) — §11.3 참고. 아래 줄은 첫 구현 때 잰 것.
- **잰 것**: 서버 `~/planetarium/api` 커밋 `cdf396c` 에서 `pytest` 179 passed(rc 0), 가짜 생성기 → 검사기 rc 0(경고 0), `systemd-run --unit=pl-api` 로 띄워 핸드오프 8경로 + `/cells`·`/firms/wms`·`/v2`·`/v1` curl 각 1회 → 200(오류 2건 의도대로 400·404), 모든 응답에 `X-Method-Version`, `/v2`·`/v1` 만 `Deprecation: true`; FIRMS 키(32자)가 응답 3개·서비스 로그에 0회. 예시 JSON 은 가짜 파일로 서버가 실제로 낸 응답을 줄인 것.
- **추론**: (팀이 칸×AOI 무게를 씀)는 팀 코드 `harmonize.py` 의 열 이름을 읽은 추론이다. 핸드오프 v1 실제 파일은 안 받았다.
- **안 잰 것**: 팀 v0/v1 실제 파일 적재(받은 파일 없음), 전 세계 규모(점 파일 수천만 행)에서 `/hotspots`·`/summary` 응답 시간, 프런트가 v3 로 옮긴 뒤의 화면.

---

## 11. 핸드오프와 다르게 둔 곳 · 계획서·팀 코드와의 차이 (적대 검토 2026-11-14)

원칙: 핸드오프가 말한 것은 핸드오프를 따른다. 핸드오프가 말하지 않은 것은 팀 코드(`planetarium/*.py`)를 근거로 정하고 「가정」으로 적는다. 우리 계획서(`final-plan.txt`)와 다르면 핸드오프를 따른다.

### 11.1 계획서 ↔ 핸드오프 (핸드오프를 따름)

| 항목 | 계획서 | 핸드오프 → v3 |
|---|---|---|
| status 종류 | 6종, `out_of_range`(지원 범위 밖) 포함 | 5종. `out_of_range` 는 응답에만 쓰는 **확장**으로 남김(파일에 쓰면 검사기가 거부) |
| `low_sample` | 「표본 부족, 판단 어려움」 상태 | 「예년 기록이 너무 적음 → 판정 생략」. 팀 코드는 비교 표 플래그(`baseline_p75 < 1`) → 우리도 플래그로 받음 |
| verdict 이름·경계 | 「평년보다 많음·비슷·적음」, 경계 수치 없음(v2 에서 우리가 75/25·n15·결측일 10 % 로 정함) | `above`/`similar`/`below`/`uncertain`, 80/20, 비교 연도 < 10, 기준 분포 status=ok 만. 결측일 10 % 규칙은 없앰 |
| value_source | `snpp_observed` / `modis_harmonized` / `noaa20_bridged` | 5종 `modis_m2v_terra` / `modis_m2v` / `snpp_observed` / `n20_bridge` / `n21_bridge` (옛 이름은 별칭) |
| NOAA-21 | 「검증용으로만 계산」 | NOAA-20 결측일 대체 `n21_bridge` × 1.053 |
| NOAA-20 연결 | 겹침 기간 지역·월별 비율(가짜 0.94) | 상수 × 1.013 |
| 2026-11 결측 | 11-05~06(NOAA-20 은 07 까지) 「결측」 | 11-05·06 결측, 11-07 은 NOAA-21 대체 |
| S-NPP 끝 | 2026-11-02 13:00 UTC (순간) | `date_local` 2026-11-02 까지 실측, 11-03 부터 `n20_bridge` |
| 단위 문구 | 「S-NPP가 봤다면 셌을 핫스팟 수」「관측 활동」 | 「VIIRS(S-NPP) 기준 화재 감지량」 |
| 불확실성 | 분위수 q10/q90 | `value_lo`/`value_hi` (실측 기간은 = value) |
| 보기 전환 | 원자료 MODIS / VIIRS / 보정 결과(`view`) | 파라미터 없음 — 응답 필드 `raw_modis`·`raw_viirs`·`value` |
| `/status` | `?aoi=&start=&end=` AOI 의 월별 상태 | `?start=&end=` 위성별 상태 + 최신 갱신 시각 (월 상태는 `/calendar`·`/summary`) |
| M1 적용 기간 | 2000-11 ~ 2011-12 | 표는 ~2011-12 / 2012-01-20~ — 그 사이 19일은 `modis_m2v`(팀 코드도 2012-01 전체를 `modis_m2v` 로 씀) |

### 11.2 핸드오프 ↔ 팀 코드 (핸드오프를 따르고 팀 이름은 받기만)

| 항목 | 핸드오프 | 팀 코드 (`planetarium/*.py`, 읽은 것) | v3 |
|---|---|---|---|
| `low_sample` 판정 | 판정 생략 | `analysis.py`: 값 ≥ 3 이면 `above`, 아니면 `uncertain` | `uncertain`. 팀 파일 값은 그대로 응답, 검사기 경고만 |
| hidden AOI 판정 | 목록에서 숨김(판정 언급 없음) | `analysis.py`: 항상 `uncertain` | 따름(재계산·생성기) |
| 비교 표 열 | 11열 | + `status`, `low_sample` | 선택 열로 받음 |
| aoi_list 열 | `name_ko, name_en, parent_id` | `harmonize.py` 문서·코드: `name`, `parent_aoi` | 별칭. `name_en` 없으면 경고 + null |
| level | `country`, `kr_sido`, `kr_sgg` | `kr`, `sido`, `sgg`, `user` | 별칭(`user` 는 확장 level) |
| 달 `missing` 기준 | 「위성 자료 부족」 | 기준 위성 자료 < 그 달 날의 50 %, 순서 partial > missing > nrt > ok | 따름 |
| 2012-01 | `modis_m2v` ~2011-12, `snpp_observed` 2012-01-20~ | 2012-01 전체 `modis_m2v` | 달 이름은 같음(다수 날 = modis_m2v). 날 단위는 핸드오프 표대로 01-20 부터 `snpp_observed` |

### 11.3 이번 검토에서 고친 결함

| # | 결함 | 고침 |
|---|---|---|
| R1 | 가짜 생성기가 MODIS 시기 원 감지 < 3 인 달을 `aoi_month.status=low_sample` 로 써서, 핸드오프 뜻(예년 기록 부족)과 다르고 그 달들이 기준 분포(status=ok)에서 빠졌다 | aoi_month 에 `low_sample` 을 쓰지 않음. 비교 표 플래그로 옮김 |
| R2 | 비교 표에 팀이 쓰는 `status`·`low_sample` 열이 오면 검사기가 `value_lo` 를 찾다 오류(서버가 안 뜸) — 이번에 열을 추가하며 드러남 | 비교 표는 구간 검사 제외, 두 열은 선택 열 |
| R3 | 팀 aoi_list 이름(`name`·`parent_aoi`·`sido`/`sgg`)이면 서버가 안 뜸 | 별칭 |
| R4 | `bbox`·`polygon`·달을 자르는 기간의 달 상태: 하루라도 값이 있으면 `ok`, `partial` 보다 `missing` 이 먼저 → 팀 규칙과 달랐다. 관측 없는 `partial` 달이 0 으로 나왔다 | partial > missing(50 % 미만) > nrt > ok, 관측 없는 partial 은 null |
| R5 | 판정이 반올림한 백분위로 이뤄졌다(79.96 → 80.0 → above) | 반올림 전 값으로 판정. 기준 연도 ≤ 24 에서는 79.95–80 구간 값이 나올 수 없어 지금 자료로는 결과가 바뀌지 않음(계산으로 확인, 시험은 함수 단위) |
| R6 | 가짜 경계 파일 properties 가 aoi_list 7열 중 4열뿐(핸드오프 §1-5 는 같은 열) | 7열 |

