# 계약 대응표 — 정본 `/api/v1` ↔ 핸드오프 ↔ 시제품 v3 ↔ 목업 EM.api

작성 2026-11-14 · 정본 = `contract/openapi.yaml`(초안 1.0.0-draft.1)

| 열 | 기준 |
|---|---|
| 정본 | `openapi.yaml`. arch2 `/api/v1` 자원 명사 경로(ARCHITECTURE §3.8). 경로 접두사 선택(CONFLICTS C1)은 아직 사람 확인 전 |
| 핸드오프 | `product/backend-handoff.md` §2·§3 (모델팀 초안). 별칭 `/api/<경로>` 로 그대로 받는다 |
| 시제품 v3 | aibox `~/planetarium/api` 커밋 `a10a535`. 2026-11-14 19:11 KST 에 release 를 읽혀 띄운 응답(`validation/samples/`)으로 확인 |
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

aibox `systemd-run --user --unit=pl-spec-sample -p MemoryMax=8G`, `EMBERLINE_PROCESSED_DIR` = release 심볼릭 링크 5개, `EMBERLINE_WARN_RULES=interval,cell_missing_null`, `EMBERLINE_DUCKDB_MEMORY=4GB`, 포트 8799(127.0.0.1). 끝나고 정지(`inactive` 확인).
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
