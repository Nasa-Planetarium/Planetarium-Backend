# 계약 대응표 — 정본 `/api/v1` ↔ 핸드오프 ↔ 시제품 v3 ↔ 목업 EM.api

작성 2026-11-14 · 정본 = `contract/openapi.yaml`(초안 1.0.0-draft.1)

| 열 | 기준 |
|---|---|
| 정본 | `openapi.yaml`. arch2 `/api/v1` 자원 명사 경로(ARCHITECTURE §3.8). 경로 접두사 선택(CONFLICTS C1)은 아직 사람 확인 전 |
| 핸드오프 | `product/backend-handoff.md` §2·§3 (모델팀 초안). 별칭 `/api/<경로>` 로 그대로 받는다 |
| 시제품 v3 | 서버 `~/planetarium/api` 커밋 `a10a535`. 2026-11-14 19:11 KST 에 release 를 읽혀 띄운 응답(`validation/samples/`)으로 확인 |
| 목업 EM.api | scratchpad `planetarium-mockup/api.js`(19:10 수정본, sha256 `a5a09beb…`)를 읽은 것. 다른 작업이 고치는 중이라 이후 판과 다를 수 있다 |

## 1. 경로

| # | 정본 `/api/v1` | 핸드오프 (별칭 `/api/…`) | 시제품 v3 | 목업 EM.api |
|---|---|---|---|---|
| 1 | `GET /aois` · `GET /aois/{aoiId}` · `GET /aois/{aoiId}/boundary` | `GET /aoi` | `GET /aoi` | `aoi(q)` → `/aoi` |
| 2 | `GET /hotspots` | `GET /hotspots` | `GET /hotspots` (release 로는 503) | `hotspots(q)` → `/hotspots` (목: 503) |
| 3a | `GET /aois/{aoiId}/summary` | `GET /summary?aoi_id=` | `GET /summary` | `summary(q)` → `/summary` |
| 3b | `GET /summaries?bbox=` | `GET /summary?bbox=` | `GET /summary` (점 필요 → 503) | 같음 |
| 3c | `POST /summary-queries` (body polygon) | `GET /summary?polygon=` | `GET /summary` (503) | 같음 |
| 4 | `GET /aois/{aoiId}/calendar` | `GET /calendar` | `GET /calendar` | `calendar(q)` → `/calendar` |
| 5 | `GET /aois/{aoiId}/months/{YYYY-MM}/comparison` | `GET /compare` | `GET /compare` | `compare(q)` → `/compare` |
| 6 | `GET /satellite-status` | `GET /status` | `GET /status` (503) | `status(q)` → `/status` (목: 503) |
| 7 | 1·2·3·4 자원에 `Accept: text/csv` 또는 `?format=csv` | `GET /export.csv?type=` | `GET /export.csv` | `exportCSV(q)` → `/export.csv` |
| 8 | `GET /methods/current` · `GET /methods/{methodVersion}` | `GET /method` | `GET /method` | `method()` → `/method` |
| 확장 | `GET /cells?month=YYYY-MM` | (없음) — 별칭 `/api/cells?year&month` | `GET /cells?year&month` | `cells(q)` → `/cells` |
| 확장 | `GET /firms/wms-tiles` | (없음) — 별칭 `/api/firms/wms` | `GET /firms/wms` | `firmsWmsUrl(q)` → `base + /firms/wms` (목: 없음) |
| 신규 | `GET /version` | — | 없음 | 없음 |
| 버림 | — | — | `/v1/*`·`/v2/*` 별칭(옛 목업용) | 안 씀 |

별칭 응답 본문은 정본과 같고 헤더 `Link: <정본>; rel="canonical"` 만 더한다(`Deprecation` 없음, arch2 S5). 시제품 v3 는 핸드오프 경로를 **루트**(`/aoi`)에서 받는다. 정본 배포에서는 `/api/aoi`.

## 2. 파라미터

| 핸드오프 | 정본 | 시제품 v3 | 목업 EM.api |
|---|---|---|---|
| `aoi_id` (calendar·compare·summary) | 경로 `{aoiId}` | `aoi_id` | `aoi_id` |
| `aoi_id` (hotspots·cells) | 쿼리 `aoi_id` | 같음 | 같음 |
| `bbox=서,남,동,북` | 같음 | 같음 | 같음 |
| `polygon` (GeoJSON 문자열) | `POST /summary-queries` 본문 `polygon`(객체) | 쿼리 문자열 | 쿼리 문자열 |
| `start`, `end` (YYYY-MM-DD) | 같음 | 같음 | 같음 |
| `freq=day\|month` | 같음 | 같음 | 같음 |
| `start_year`, `end_year` | 같음 | 같음 | 같음 |
| `year`, `month` (compare) | 경로 `{yearMonth}` = `2025-03` | `year`, `month` | `year`, `month` |
| `year`, `month` (cells, 확장) | `month=2025-03` 하나 | `year`, `month` | `year`, `month` |
| `level`, `parent_id`, `q` | 같음 | 같음 | 같음 |
| `include_geometry` | 별칭에서만. 정본은 `/aois/{aoiId}/boundary` | 있음 | 있음 |
| — | `include_hidden` (확장) | 있음 | 있음 |
| `layer=reference\|all`, `limit` | 같음 (`limit` 1–10,000, 기본 1,000) | 같음 | 같음 |
| — | `cursor` (keyset), 목록 `limit` 1–1,000 | `/hotspots` 만 cursor | 없음 |
| `type=summary\|calendar\|hotspots` | 없음 — 자원 + `Accept: text/csv` / `format=csv` | 있음 | 있음 |
| — (status) `start`, `end` | 같음 | 같음 | 같음 |
| (firms) `layer, bbox, width, height, time` | 같음 | 같음 | `layer, bbox, width, height` (time 없음) |

## 3. 응답 필드

정본 = 핸드오프 열 이름 그대로 + API-v3 확장. 시제품 v3 응답 10개를 스키마에 대어 본 결과는 §6.

| 대상 | 핸드오프 | release 실제 열 | 정본·시제품 v3 응답 | 목업 |
|---|---|---|---|---|
| 지역 이름 | `name_ko` | `name` | `name_ko` (적재기 별칭) | `name_ko` |
| 영문 이름 | `name_en` | **없음** | `name_en: null` | `name_en` 은 목업 사전 `SIDO_EN` 으로 채움(시도 17개만) |
| 상위 지역 | `parent_id` | `parent_aoi` | `parent_id` | `parent_id` |
| level | `country`·`kr_sido`·`kr_sgg` … | `kr`·`sido`·`sgg` … | 핸드오프 값 (`KR` 은 `country`) | 같음 (ingest 문구 `level kr/sido/sgg->country/kr_sido/kr_sgg`) |
| 구간 | `value_lo`, `value_hi` | 같음 | 같음 — value 가 구간 밖일 수 있음(R7) | 같음, **소수 1자리 반올림** |
| 판정 | `verdict`, `percentile`, `ratio_to_median` | 같음 | 파일 값 그대로. 시제품은 percentile 을 반올림하지 않음(예 `8.333333333333332`) — API-v3 §1.3 「소수 1자리」 문장과 다름 | compare 값 그대로 읽고 소수 1자리 반올림 |
| low_sample | status 값 | compare 의 **bool 열** | bool 필드 `low_sample` | bool 필드 |
| 출처 | `value_source` 5종 | 4종 실재, `n21_bridge` 0행 | 5종 enum 유지 | 4종 구간표(`n21_bridge` 없음) |
| 상태 | `status` 5종(low_sample 포함) | ok·nrt·partial·missing | 4종 + `out_of_range`(응답 전용) | 같음 |
| 판 | `method_version` | cell 열·meta 만(aoi_month 에 없음) | 모든 응답 + `X-Method-Version` | 응답 `method_version` |
| 칸 좌표 | `cell_lat`, `cell_lon` | 같음 | 정본: 속성에 선택. 시제품 v3: geometry(Polygon)로만 | 같음 |
| 덩어리 8열 | 없음 | 없음 | 확장, 지금 전부 null | — |
| 점 19열 | §1-1 | 파일 없음 | 정본 스키마 = §1-1 그대로 | 목: 503 |
| 위성 상태 | 「위성별 자료 상태·최신 갱신 시각」 | 파일 없음 | 가정 모양(G1) | 목: 503 |

## 4. 코드값

| 종류 | 핸드오프 | release (잰 것) | 정본 | 시제품 v3 | 목업 |
|---|---|---|---|---|---|
| value_source | `modis_m2v_terra`·`modis_m2v`·`snpp_observed`·`n20_bridge`·`n21_bridge` | 앞 4개, n21 0행 | 5개 | 5개(옛 v0 이름은 받기만) | 4개 |
| 달 경계 | modis_m2v ~2011-12, snpp 2012-01-20~ | modis_m2v ~**2012-01**, snpp 2012-02~ | release 달 경계 (enum 설명) | 같음 | 같음 (`2012-02-01` 부터 snpp) |
| status | ok·nrt·partial·missing·low_sample | ok·nrt·partial·missing | ok·nrt·partial·missing·out_of_range | 같음 | 같음 |
| verdict | above·similar·below·uncertain | 같음 (+ low_sample&above 5,250행) | 같음, 파일 그대로 | 같음 | 같음 |
| level | world·continent·country·adm1·kr_sido·kr_sgg | world·continent·country·adm1·kr·sido·sgg | 핸드오프 + 확장 `group` | 같음 | 같음 |
| aoi_id | `KR`·`SIDO_11`·`SGG_11110` | `WORLD`·`CONT_*`·`ADM0_<ISO3>`·`ADM1_*`·`KR`·`SIDO_*`·`SGG_*` | release 모양 | 같음 | 같음 (+`group:gb-north` 파생) |
| data_source | SP·NRT | 같음 | 같음 | 같음 | 같음 |

## 5. 헤더·오류

| 항목 | 정본 | 시제품 v3 (잰 것) | 목업 |
|---|---|---|---|
| `Cache-Control` 데이터 | `public, max-age=0, must-revalidate` (arch2 §3.8, critique D6) | `public, max-age=86400` 7개 표본 · `/aoi` `3600` 3개 | — |
| `Cache-Control` 오류 | `no-store` | `no-store` 4개 표본 | — |
| `ETag` / 304 | 있음 (미디어 타입 포함) | 있음 | 기억(memo)만 |
| `X-Method-Version` · `X-Data-Version` | 있음 | 있음 | 응답 본문 method_version 으로 기억 무효화 |
| `Vary` | `Accept, Accept-Language` | 없음 | — |
| problem+json 멤버 | `type, title, status, code, error_no, request_id` 필수 + `detail, instance, param, method_version, errors[]` | `error_no`·`instance` **없음** (표본 4개 전부) | `code`·`status` 로 분기 |
| 문자열 code | `invalid_param` 등 20개 (openapi `ErrorCode`) | 같은 문자열 | `data_not_loaded`·`network` 만 따로 문구 |
| 번호 `error_no` | `DSSNN` 31개 (openapi `ErrorNo.x-error-catalog`) | 없음 | 없음 |

## 6. 시제품 응답 ↔ 정본 스키마 대조 (잰 것, 2026-11-14 19:12–19:16 KST)

서버 `systemd-run --user --unit=pl-spec-sample -p MemoryMax=8G`, `EMBERLINE_PROCESSED_DIR` = release 심볼릭 링크 5개, `EMBERLINE_WARN_RULES=interval,cell_missing_null`, `EMBERLINE_DUCKDB_MEMORY=4GB`, 포트 8799(127.0.0.1). 끝나고 정지(`inactive` 확인).
검증기: `uv run --no-project --python 3.12 --with openapi-spec-validator==0.7.1 --with jsonschema==4.23.0 --with referencing==0.35.1 --with PyYAML==6.0.2` — 스크립트 `validation/validate.py`, 출력 `validation/validate.out.txt`.

| 표본 | 정본 스키마 | 결과 |
|---|---|---|
| `/aoi?parent_id=SIDO_47` · `?level=kr_sido` · `?level=world` · `?q=KR` | AoiList | 4/4 PASS |
| `/calendar?aoi_id=KR` 2025 · 2001(결측 6월 포함) | Calendar | 2/2 PASS |
| `/compare` SIDO_47 2025-03 · ADM1_AZE-1699 2022-11(low_sample & above) | Comparison | 2/2 PASS |
| `/summary?aoi_id=KR&…&freq=month` | Summary | PASS |
| `/cells?aoi_id=SGG_47170&year=2025&month=3` | CellCollection | PASS |
| `/status` 503 · `/hotspots` 503 · 400 · 404 | Problem | 4/4 FAIL — 같은 하나: `error_no` 없음 (정본이 요구, 시제품에 없음 — 알려진 차이) |

- 음성 대조(검사가 실제로 거르는지): cal 표본에 `verdict: "more"`·`q10` 을 넣고 cmp 에 `status: "low_sample"` 을 넣은 사본 → 각각 FAIL(잰 것). 즉 「PASS」 는 필드 이름·enum·null 허용까지 본 결과다.
- 대조하지 않은 것: CSV 열 순서(표본은 받았으나 스키마 검사 대상 아님 — 눈으로 보면 `x-csv-columns` + 덩어리 8열), `/method`(스키마가 느슨해 의미 없음), 별칭 경로 `/api/*`(시제품에 없음), 점·위성 상태 200 응답(release 에 파일 없음).

## 7. 목업이 정본으로 옮길 때 바꿀 것 (목록만)

1. 경로: 루트 핸드오프 경로 → `/api/v1` 정본(표 §1). 또는 기간 동안 `/api/<핸드오프>` 별칭을 쓴다.
2. compare 의 `year`·`month` → `{yearMonth}`, cells 의 `year`·`month` → `month=YYYY-MM`.
3. `/export.csv?type=` → 자원 + `format=csv`.
4. `/method` → `/methods/current`, `/firms/wms` → `/firms/wms-tiles`, `/status` → `/satellite-status`.
5. 오류 분기는 지금처럼 문자열 `code` (번호는 운영용).
6. 값 반올림(소수 1자리)은 표시 단계에서만 — 응답 값은 파일 그대로.

## 8. v1.2 새 층 — 파일·열 ↔ 응답 필드 (F15, 2026-11-15)

기준: release `data-v1.2-2026-11-15`(서버 `~/planetarium/release/v1.2-2026-11-15`), 모델팀 `product/team/v1.2/api_contract.md`.
열 이름·행 수는 2026-11-15 서버에서 parquet 를 읽어 잰 것(polars 1.12.0, MemoryMax 6G). 적대 검토(같은 날, pandas/pyarrow, MemoryMax 8G, 다 받은 뒤 `.done` 확인)에서 `harmonized_cell_day_frp` 열과 아래 §8.4 대응을 다시 쟀다.

### 8.1 경로

| 정본 `/api/v1` | 모델팀 경로(별칭 `/api/…`) | 파일 | 범위(잰 것) |
|---|---|---|---|
| `GET /occurrence/grid?bbox&min_p_any` | `/occurrence?unit=grid&bbox` | `grid_occurrence_nextmonth_latest` | 52,855칸 · 대상 2026-12 · as_of 2026-11-14 · persistent 259 |
| `GET /occurrence/aois?parent_id` | (없음, 확장) | `aoi_occurrence_nextmonth_latest` | 한국 시군구 252 · suspected_industrial 4 |
| `GET /aois/{aoiId}/occurrence` | `/occurrence?aoi` | 위 둘 + `aoi_boundaries` | 시군구 = 파일 행, 그 밖 = 격자 요약 |
| `GET /fire-events?aoi_id\|bbox&start&end&min_det&sort&limit&cursor` | `/events?aoi&start&end` | `fire_events` | 1,240,978행 (KR 12,397 · ARG 1,228,581), 2000-11-01 ~ 2026-11-14 |
| `GET /fire-events/{eventId}` | (없음, 확장) | `fire_events` | 위와 같음 |
| `GET /aois/{aoiId}/fwi?date\|start&end` | `/fwi?aoi&date` | `fwi_aoi_day` (+`fwi_clim_aoi_month`) | 295 AOI, 2012-01-01 ~ 2026-11-11 |
| `GET /spread-nowcast?aoi_id\|bbox&min_level` | `/spread?aoi\|bbox` | `spread_nowcast_latest` | 10,189칸 (KR 147 · ARG 10,042), 기준 2026-11-13 → 목표 11-14 |
| `GET /aois/{aoiId}/fire-regime?start&end` | `/regime?aoi` | `aoi_month_fire_regime` | 92,335행, 295 AOI, 2000~2026 |
| `GET /cells/frp?bbox\|aoi_id&start&end&freq` | (없음 — quickstart 표에 파일만) | `harmonized_cell_day_frp` | 97,791,096행 · 800 row group · date_local 2000-11-01 ~ 2026-11-15 (잼) |
| `GET /satellite-status?start&end&freq&satellite` (v1 경로 유지, 503 → 실제) | `/status` | `satellite_day_status`, `satellite_month_status` | 28,316행 · 934행 |

파라미터 이름: 모델팀 `api_contract.md` 는 `aoi`, `backend-quickstart.md` 는 `aoi_id` 를 쓴다(같은 팀 두 문서가 다름). 별칭은 둘 다 받고 둘 다 오면 400(40003). 정본은 v1 관례대로 경로 `{aoiId}` 또는 쿼리 `aoi_id`.

### 8.2 열 ↔ 필드

| 파일 | 응답에 싣는 열 (이름 그대로) | 서버가 붙이는 것 | 싣지 않는 열 |
|---|---|---|---|
| grid_occurrence | cell_id · lat · lon · target_month · p_any · p_none · baseline_p · persistent_source · model · as_of_date · adm0 · continent · rate_5y · lag1_projected | `vs_normal` (±0.10 규칙), `coverage`, `notice` | p_model (52,855/52,855 행 p_any 와 같음 — 잼) |
| aoi_occurrence | aoi_id · target_month · p_any · p_none · baseline_p · model · as_of_date · suspected_industrial · rate_5y · n_years(float → 정수) · lag1_projected | `name_ko`(aoi_list.name), `vs_normal`, `rank`(의심 제외), `coverage` | p_model · rate_all · lag1_value · occ_share · seasonal_cv (모델 내부 특성) |
| fire_events | event_id · region · start_date · end_date · duration_days · n_det · sum_weight · frp_sum · frp_max · centroid_lat/lon · lat_min/max · lon_min/max · burned_area_km2 · burn_confirmed · instrument · n_viirs · n_modis · country · adm1_code · sido_nm · sgg_cd · sgg_nm · sgg_all | `burned_area_label`, `coverage` | burned_area_km2_near(모델팀 「화면에 쓰지 않음」) · pixel_area_km2 · pixel_area_nominal_share |
| fwi_aoi_day | date · fwi_mean · fwi_max · fwi_class · fwi_pct · fwi_rel_class · isi_mean · bui_mean · ffmc_mean · dc_mean · n_cells · name | `as_of_date`(그 AOI 의 max date), `badge: fwi_rel_class`, `notice` | level(aoi_list 와 같은 정보) |
| spread_nowcast | region · cell_lat · cell_lon · cell_size_deg · p_spread · active_today · fire_event_days · n_det_today_7x7 (칸) / as_of_date · target_date · model · source_satellite · weather_available · weather_lag_days (머리) | `level` (<0.05 / ≤0.20 / >0.20), `notice` · 칸에 `cand_adm0` 도 싣는다(KR 상자의 JPN 47칸 구분) | near_active_km · wind_align(전부 null) · weather_date(전부 null) |
| aoi_month_fire_regime | year · month · fire_regime · W · W_large · share_large · n_events · n_large_events · top_event_id · top_event_w · name | `coverage` | level |
| harmonized_cell_day_frp | cell_id(int64) · date_local(date32) → `date` · frp_sum(double) · frp_snpp_equiv_sum(double) · frp_snpp_equiv_max(float) · n_points(int32) — 6열 전부, null 0 (잼) | `cell_lat/lon`(CellId 식 — 파일에 위경도 없음), 월 묶음 Σ/max, `-0.0` → 0 | — |
| satellite_day_status | satellite · date_utc · status · n_det_global · coverage · ratio_to_seasonal · note | — | n_det_ea · n_det_status · status_basis · baseline · ratio_to_baseline · n_sats_low_same_day · n_other_sats · status_raw · status_r1 · status_r2 · seasonal_baseline · seasonal_years |
| satellite_month_status | 전 열(11개) | — | — |

### 8.3 「자료 없음」 표현

| 경우 | 응답 | 화면 |
|---|---|---|
| 층이 그 지역을 다루지 않음(KR·ARG 밖 AOI, 상자가 범위와 안 겹침) | 200 · 배열 `[]`/값 null · `coverage.available=false, reason=out_of_coverage` | 「이 지역 자료 없음」 한 줄 |
| AOI 안에 0.5° 격자 없음 | 200 · `coverage.available=false, reason=no_cells` | 같음 |
| 범위 안이지만 해당 없음(오늘 감지 없음, 그 기간 사건 없음) | 200 · `[]` · `coverage.available=true` | 「해당 기간 사건 없음」 등 — 자료 없음과 구분 |
| FWI `date > as_of_date` | 200 · `day: null` · `as_of_date` | 「11월 11일 기준」 값으로 안내 |
| 파일 자체 없음(적재 전) | 503 `data_not_loaded` 50502 `LAYER_NOT_LOADED` | 「준비 중」 |
| 없는 aoi_id / event_id | 404 `unknown_aoi` 20401 / `not_found` 40402 | 오류 |

### 8.4 잰 것에서 나온 주의 (적대 검토 2026-11-15 로 다시 씀)

- **fire_events 시군구 거르기.** `sgg_all` 은 쉼표 구분 이름(213종, 전부 aoi_list 시군구 이름에 있음). aoi_list 안의 동명 시군구는 7개(강서구·고성군·남구·동구·북구·서구·중구).
  앞 판에 적은 「18개 이름이 둘 이상의 `sgg_cd`」는 사건 행의 `sgg_nm`→`sgg_cd` 를 센 값이고, 그중 11개(파주시·나주시 등)는 동명이 아니라 **`sgg_cd` 와 `sgg_nm` 이 어긋난 행**(16건, 예 41800 ↔ 파주시) 때문이다. `sgg_cd` 와 `sido_nm` 이 어긋난 행도 4건.
  여러 시군구에 걸친 사건 95건(이름 193개) 중 25개 이름은 사건의 `sido_nm` 과 다른 시·도에 있다(시·도 경계를 넘는 사건).
  규칙 비교(시군구 소속 건수): 모델팀 「이름만」 14,314(동명 구 섞임) · 앞 판 「이름 + sido_nm」 12,022(경계 넘는 24건 놓침) · **정본 「이름을 aoi_list 로 풀기: 유일 이름 → 그대로, 동명 → sido_nm 으로」 12,047 중 못 푼 것 2**.
  `sgg_cd` 는 230종 전부 `SGG_<cd>` 로 aoi_list 에 있지만 대표 하나만 담고 어긋난 행이 있어 거르기에 쓰지 않는다. 사건이 하나도 없는 시군구 22개(서울·부산 도심 구 등).
- **fire_events 나라.** AOI `KR` = `country == 'KOR'` 11,943건(region KR 12,397 = KOR 11,943 · PRK 453 · null 1). `ADM0_ARG` = `country == 'ARG'` 1,223,123건(region ARG 1,228,581 = ARG · PRY 3,557 · BOL 721 · BRA 699 · URY 232 · CHL 221 · null 28). 이웃 행은 bbox 로만 나온다.
  `adm1_code` 52종 전부 `ADM1_<code>` 로 aoi_list 에 있다. `sido_nm` 17종 전부 aoi_list 시도 이름과 같다.
- **fire_events 기타.** 날짜는 현지 날짜. `instrument` 는 MODIS 348,949 · VIIRS 892,029 뿐(섞인 사건 0). `burned_area_km2` 0 ⇔ `burn_confirmed=false`(741,204) · 양수 ⇔ true(120,425) · null 379,349(시작이 2012~2025 밖 379,343 + 안 6). 사건 일련은 전부 4자리. 한국 사건 감지 1개 79.6%(모델팀 문서 81%), 3개 미만 92.4%. `sum_weight = 0` 사건 368건.
- **범위 상자.** 사건 감지 점 상자: KR 위도 33.21~38.60 · 경도 124.63~130.90, ARG 위도 −54.90~−21.81 · 경도 −73.18~−53.70. 판정 상자 KR `[124.5, 33.0, 132.0, 38.7]` 는 quickstart 예시 상자를 쓴 **추론**(모델팀 확인 필요, F15-Q5), ARG `[-73.5, -55.2, -53.4, -21.5]` 는 잰 범위 + 0.3° 로 우리가 정함.
- **spread KR 상자에 일본이 들어 있다.** region KR 147칸 = `cand_adm0` KOR 98 · JPN 47 · null 2. ARG 10,042 = ARG 9,941 · PRY 39 · BRA 27 · URY 21 · BOL 14. 지금 KR 칸 범위(위도 33.19~36.11, 경도 129.29~131.71)는 그날 불 위치라 상자 정의가 아니다. 날짜는 현지 날짜.
- **FWI.** 295 AOI 전부 마지막 날 2026-11-11. `fwi_pct` null 92,925 = 2026년 행 전부(그 밖 0). `fwi_rel_class` null 0. 날짜는 NASA POWER LST 날. 한국은 절대 FWI 의 AUC(0.634)가 상대(0.596)보다 높다(fwi_meta) — 배지 기본은 모델팀대로 상대.
- **불 유형.** 295 AOI × 313달(2000-11~2026-11). `top_event_id` 14,916종 전부 fire_events 에 있음. `none` 인데 `top_event_id` 가 있는 달 52개(W = 0 사건).
- **FRP.** 위경도 열 없음 · `cell_id` 순 정렬이고 row group 마다 날짜가 전 기간에 걸침 → 적재 때 (date_local, cell_id) 색인 필요. 전 세계 하루 약 14,896행(2025-03-20~31 평균). 통계 최솟값 `-0.0`.
- **응답 크기(파일로 직렬화해 잼).** `/occurrence/grid` 전 세계: full 11.34 MB / gzip 0.65 MB, map 6.58 MB / gzip 0.47 MB, 반올림 없으면 12.56 / 0.93 MB. `/spread-nowcast` 전체 1.57 / 0.13 MB. `/fire-events` 1,000건 0.53 / 0.06 MB.
- **위성 상태.** satellite 5종, S-NPP 2012-01-20 ~ 2026-11-02, 나머지 끝 2026-11-14. `coverage` 값 global 하나. month_status complete 927 · partial 7.

### 8.5 aoi_month_compare 교체 — 예년 비교 기준 기간 열 (data-v1.2-2026-11-15 r3, 2026-11-15, CMD-61bf1e310aff)

기준: 모델팀 계약 `docs/api_contract.md`(모델 저장소 `b062154`) 「예년 비교 기준 기간 — `baseline_period`, `continuity_ratio` (2026-11-15)」
81–95행·241행, `docs/release_v1.2-2026-11-15.md` 68–71행·284행. 파일 = r3 `aoi_month_compare.parquet`(sha256 `4b108682…e5be814b`,
1,580,963행 — 행 수 그대로, 열 3개 더함). 값은 파일 그대로 싣고 다시 계산하지 않는다(`verdict` 포함). 명세 사본(`spec/contract`)에는 읽은 때
이 열들이 없었다(docs/DEVIATIONS.md F15-CMP-SPEC).

| 열(파일 이름 그대로) | 응답 필드 | 실리는 곳 | 모델팀 계약 | 잰 것 (r3) |
|---|---|---|---|---|
| `baseline_period` (VARCHAR) | `baseline_period`: `"2001-2025"` \| `"2012-2025"` \| null | `/aois/{aoiId}/months/{yearMonth}/comparison`(별칭 `/api/compare`), `/aois/{aoiId}/calendar` 달마다(별칭 `/api/calendar`), 캘린더 CSV(별칭 `/api/export.csv?type=calendar`) | api_contract 86행(0.85~1.15 → `"2001-2025"`, 밖이면 `"2012-2025"`), 89행(2012–2025 지역의 2012년 이전 달 `uncertain`), 241행(다음 달 평년도 이 기간) | `2001-2025` 950,268행 / 3,036 AOI · `2012-2025` 630,695행 / 2,015 AOI, null 0. 한 AOI 안에서 세 열 값은 하나 |
| `continuity_ratio` (DOUBLE) | `continuity_ratio`: number \| null (반올림 안 함) | 위와 같음 | api_contract 85행(= (통합값 2013~2021 ÷ 2003~2011) ÷ (MODIS 원자료 2013~2021 ÷ 2003~2011)) | 0.0157 ~ 5.376, null 0. KR 0.6814 · ADM0_ARG 1.0459 · WORLD 1.0175 |
| `continuity_source` (VARCHAR) | `continuity_source`: `"own"` \| `"parent:<aoi_id>"` \| null | 위와 같음 | api_contract 87행(두 기간 MODIS 원자료 각각 50건 미만 → 상위 지역 비율, `"parent:<id>"`) | own 926,793행 · parent 654,170행, 155종, parent id 전부 aoi_list 에 있음(`parent:CONT_*`·`parent:WORLD` 포함). 예: SIDO_11 `parent:KR`, SGG_31140 `parent:SIDO_31` |

- `/comparison` 의 확장 `baseline` 목록은 그 행의 `baseline_period` 안의 다른 해만 보인다(KR 2024-08: 2012–2025 중 13개, `used` 13 = `n_baseline_years`). 열이 없는 release 는 2001–2025 그대로.
- 그 밖에 aoi_month_compare 를 읽는 응답: `/aois/{aoiId}/summary` 는 같은 묶음(aoi_month ⟕ aoi_month_compare)을 읽지만 비교 열을 싣지 않는다. `/summaries`(bbox)는 이 묶음을 읽지 않는다.
- 「다음 달 평년 수준」(api_contract 241행) 블록은 서버에 없다 — 기간 규칙을 붙일 곳이 없어 바꾸지 않았다.
- 새 `reports/` 3개(`compare_continuity_ratios.csv`, `compare_continuity_validation.csv`, `projection_method_validation.csv`)는 release_manifest 에 있고 적재하지 않는다(있는지만 본다).

## 9. F17 — `POST /forecast` (FireOutlook Planner 30일 전망, 2026-11-15)

기준: 모델팀 계약 `docs/api_contract.md` 「`POST /forecast`」(모델 레포 main `c43368c`) — 사본 `$HOME/planetarium/spec/product/team/v1.2/api_contract-v2.md`(아래 「계약 l.N」은 이 사본의 줄), 함수 `planetarium/forecast.py`(같은 커밋, 「코드 l.N」), 결정 `00-decisions.md` F17. 값은 모델 함수가 낸다 — 이 서버는 검증·전달·캐시만 하고 응답 본문을 다시 쓰지 않는다(F17 「재구현·값 재계산 금지」).

### 9.1 경로

| 정본 `/api/v1` | 모델팀 경로(별칭 `/api/…`) | 받는 쪽 | 근거 |
|---|---|---|---|
| `POST /forecast` (본문 JSON) | `POST /forecast` → 별칭 `POST /api/forecast`(본문 그대로 forward) | 서버 안 forecast 서비스 `POST /forecast` (§9.3) | 계약 l.314, l.338–341; 결정 F17 「경로: POST 이지만 읽기 전용 계산」 |

공개 경로(Pages Function → Worker → 터널 8090)에서 허용되는 POST 는 이것 하나다(DEVIATIONS F17-PUB). 다른 POST·PUT·PATCH·DELETE 는 계속 405.

### 9.2 요청 필드·규칙 ↔ 원문

| 필드·규칙 | 이 서버 | 원문 | 어기면 |
|---|---|---|---|
| 본문 | JSON 객체 하나, 같은 멤버 이름 두 번 안 됨, 뒤에 다른 토큰 안 됨, ≤ `forecast_body_bytes` (131,072) | 계약 l.338–339 (JSON 본문) | 400 `invalid_param` 10001 / 413 `range_too_large` 40110 |
| 멤버 | `region`, `horizon_days`, `start`, `end` 만. JSON `null` = 없음. 함수 인자 `as_of`·`today` 는 받지 않음 | 계약 l.338–345 (요청 예에 넷뿐); 코드 l.398 (`as_of`, `today` 는 시험용 인자, l.563 「오늘 날짜 고정 (시험용)」) | 400 10001 `param`=멤버 이름 |
| `region` 문자열 | aoi_id 형식(`Patterns.AOI_ID`, D4)만. 있는지는 서비스가 답함 | 계약 l.340 「aoi_id(aoi_list 5,051개)」; 코드 l.130–133 | 400 `invalid_param` 40005 / 서비스 404 → §9.4 |
| `region` 배열 | 유한한 수 4개 `[서, 남, 동, 북]`, -180 ≤ 서 < 동 ≤ 180, -90 ≤ 남 < 북 ≤ 90 | 계약 l.340 「[서, 남, 동, 북]」; 코드 l.137 `box(*region)` | 400 40005 |
| `region` 객체 | 멤버 `type`(`Polygon`·`MultiPolygon`)·`coordinates` 만(Feature 아님). 링마다 위치 ≥ 4, 처음 = 끝, 위치 = 유한한 [경도, 위도] 2개 (±180/±90), 모든 링 위치 합 ≤ `forecast_polygon_vertices` (2,000), JTS valid(자기 교차·겹친 다각형 없음). 위치 순서는 그대로 | 계약 l.340 「GeoJSON Polygon/MultiPolygon」; 코드 l.137 `shape(...)`, l.141 `intersection` | 꼴 400 40005 / 꼭짓점 400 `polygon_too_complex` 40108 / 도형 400 `invalid_param` 40001 |
| 기간 형식 | 정확히 하나: `horizon_days`(선택 `start`) 또는 `start`+`end` | 계약 l.344–345; 코드 l.408–415 | 400 40006 (`param` 빠진 쪽, `horizon_days`+`end` 는 `end`) |
| `horizon_days` | JSON 정수 1 ~ `forecast_horizon_days_max` (30). `30.0`·`"30"`·`true` 안 받음 | 계약 l.344 「horizon_days: 30」; 요청 (CMD-2132dcdf433a) 「horizon_days 1–30」; 코드 l.408·411 (`if horizon_days:` · `int(...)`) | 400 40006 |
| `start`·`end` | `YYYY-MM-DD` 이고 있는 날짜, `end ≥ start`, 양 끝 포함 ≤ `forecast_span_days` (400)일 | 계약 l.338 예; 코드 l.416–417 | 400 40006 / 400 `range_too_large` 40109 |
| 기간이 as_of 다음 날부터인지, 기준 달 + 12개월 안인지 | 검사하지 않음 — 모델이 판단 | 계약 l.341 「기준일 다음 날부터, 기준 달에서 12개월 뒤 달까지」; 코드 l.417·463 | 서비스 400 → §9.4 60001 |
| `horizon_days` 의 시작일 | 보낸 형식 그대로 넘김 — 모델이 오늘(KST)·as_of 로 정함 | 계약 l.344–345 「오늘(KST) ~ 오늘 + 29일 … 오늘이 기준일(as_of) 이하이면 as_of + 1일부터」; 코드 l.392–396·408–411 | — |

서비스로 보내는 본문 = 위 검증을 지난 값을 한 가지 꼴로 다시 쓴 JSON(`ForecastQuery.json()`): 멤버 이름 순(`end`, `horizon_days`, `region`, `start`), 공백 없음, 수는 서비스가 읽을 double 의 가장 짧은 십진 표기(`37` → `37.0`, `-0.0` → `0.0`, `3.8e1` → `38.0`). 모델 CLI 와 같은 값이 나오는지는 F17-B 스모크가 대조한다(요청 4).

### 9.3 내부 인터페이스 (F17-B 서비스 ↔ Spring)

F17-B 가 만드는 서비스(K3s 안, 기본 `http://planetarium-forecast:8000`, `EMBERLINE_FORECAST_URL`)의 HTTP 모양 — 이 꼴로 부른다.

| 요청 | 성공 | 실패 |
|---|---|---|
| `POST /forecast`, `Content-Type: application/json`, 본문 = §9.2 정규형 | 200, JSON 객체 = `forecast(**body)` 반환값 (`json.dumps`) | 400 `{"error": "<code>", "message": "..."}` (함수의 `ValueError` — 기간·12개월 넘음), 404 같은 꼴 (`모르는 aoi_id`, 코드 l.133) |
| `GET /healthz` | 200 `{"as_of": "YYYY-MM-DD", "data_version": "...", "warm": true}` (`warmup()` 뒤) | 그 밖 = 상태 모름 |

- `/healthz` 의 `as_of` 는 `forecast()` 가 응답 `as_of` 에 넣는 값과 같아야 한다(인자 없이 부를 때 = 월·일 단위 파일 기준일 중 늦은 쪽, 코드 l.404–407), `data_version` 은 비어 있지 않은 문자열. 다르면 Spring 은 넘기기만 하고 저장하지 않는다(§9.5 저장 조건) — 오류 없이 캐시가 꺼진다.
- 타임아웃: 연결 `emberline.forecast.connect-timeout` 1 s, 요청 전체(헤더 + 본문) `emberline.forecast.timeout` 10 s (계약 l.403: warmup 뒤 30일 질의 ≤ 0.26 s; F17-B 측정: 13개월 WORLD·전 지구 폴리곤 질의 최대 6.7 s). 응답 ≤ `emberline.forecast.max-response-bytes` 4 MiB. HTTP/1.1, 리다이렉트 안 따름.
- 성공 본문은 받은 바이트 그대로 `application/json` 으로 낸다. JSON 객체인지와 `as_of` 만 읽는다(캐시 판단).

### 9.4 오류 대응

| 서비스 응답 | 우리 응답 | `error_no` | `detail` |
|---|---|---|---|
| 400 + `{"error": 문자열, ...}` | 400 `invalid_param` | 60001 `FORECAST_REJECTED` | 서비스 `message` (경로·점 찍힌 클래스 이름·제어 문자 없고 ≤ 300자일 때), 아니면 고정 문구 |
| 404 + `{"error": 문자열, ...}` | 404 `unknown_aoi`, `param=region` | 60401 `FORECAST_UNKNOWN_REGION` | 같음 |
| 연결 실패·타임아웃·5xx·그 밖 4xx(422·405·413 …)·`error` 없는 400/404(HTML 등)·200 인데 JSON 객체 아님·4 MiB 넘음 | 503 `upstream_unavailable`, `Retry-After: 30` | 60503 `FORECAST_UNAVAILABLE` | 고정 문구 (서비스 본문은 내보내지도 로그에 남기지도 않음) |

모든 오류는 problem+json + `no-store` (§5 규약 그대로).

### 9.5 캐시

- 서버 안 Redis(redis-cache, 공유 layer 캐시 `RedisLayerCache`, route `LayerRoute.FORECAST` — 새 캐시 클래스 없음): 키 `em:{env}:c:layer:forecast:s1:{data_version}@{as_of}:[kst{YYYY-MM-DD}:]{sha256(정규형 질의)}`, 값 = 서비스 본문 그대로(UTF-8), TTL `emberline.forecast.cache-ttl` 48 h.
- `data_version`·`as_of` = 서비스 `GET /healthz`, `emberline.forecast.status-ttl` (60 s) 동안 재사용 → 매일 갱신(F17-B 요청 2, as_of 로 판 구분) 뒤 1분 안에 새 키.
- `horizon_days` 질의는 키에 KST 날짜(모델의 「오늘」, 코드 l.392–396)가 들어간다 → 30일 질의를 날을 넘겨 내주지 않는다. 저장은 호출 전후 ±`emberline.forecast.day-skew` (2 min) 동안 KST 날짜가 같을 때만.
- 저장 조건: 응답 `as_of` = 키의 as_of (갱신 중 바뀐 판을 옛 키에 넣지 않음), 본문이 올바른 UTF-8. `/healthz` 가 안 되면 읽지도 쓰지도 않고 그냥 넘긴다. 오류는 저장하지 않는다. Redis 장애 = miss (F15 와 같음).
- HTTP: `Cache-Control: no-store` (200 포함) — ETag·`X-Data-Version` 없음. POST 는 CDN·nginx proxy_cache 가 저장하지 않는다(`proxy_cache_methods GET HEAD`).
- 속도 제한: scope `forecast` (`emberline.ratelimit.forecast.*`, 기본 10/분, 공개 1,800/분/주소), 경로 `/api/v1/forecast`·`/api/forecast` 가 같은 통을 쓴다. 넘으면 429 `rate_limited` 10301 (본문을 읽기 전).

### 9.6 응답 필드 (모델 반환값 그대로 — 스키마 `Forecast`, `additionalProperties: true`)

| 필드 | 원문 |
|---|---|
| `product`, `region`, `region_group`, `start`, `end`, `as_of`, `lead_days_from_as_of{first,last}` | 계약 l.351–352; 코드 l.506–507 |
| `p_any`, `expected_count`, `expected_count_q10`, `expected_count_q90`, `expected_fire_days`, `baseline{p_any,count,expected_fire_days,definition}` | 계약 l.353–354, l.418–419 |
| `skill`, `count_skill`, `skill_basis`, `value_source` (`model_calibrated`·`baseline`·`model`), `display_hint` | 계약 l.355–356, l.380–386, l.453–454 |
| `models`, `horizons`, `pieces[]` (조각별 `route`·`fraction_*`) | 계약 l.418–420 |
| `daily[]` (`date`, `lead_days_from_as_of`, `p_day`, `base_p_day`, `expected_count_day`, `skill`, `source`) | 계약 l.405–416 |
| `persistent_source_cells{count,area_share,note}`, `suspected_industrial_sgg`, `unit`, `notice` | 계약 l.418–420, l.455–457 |
