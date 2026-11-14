# 가정·결정·질문 한 장 — API-v3 G · arch2 결정표 · 모델팀 질문 30개

작성 2026-11-14 · 판정 기준 release `data-v1-m2v-2026-11-14`(`data/release-inspect/INSPECT.md`, 이하 INSPECT) · 시제품 응답 `contract/validation/samples/`

상태
- **확인됨** — release(또는 meta)가 답했다. 근거 칸에 어디서 잰 것인지.
- **반증됨** — release 가 가정과 다르게 나왔다(계약은 release 를 따름).
- **대체됨** — 질문의 전제(옛 가정·우리 calib)가 사라졌다.
- **열림** — 모델팀에 물어야 한다. → `questions/model-team-open.md`
- **사람 결정** — 우리 쪽(사용자·팀)이 정한다.

## A. API-v3 가정 G1–G12 (원문 §9; G12 가 있어 12개)

| # | 가정 | 상태 | 근거 / 남은 것 |
|---|---|---|---|
| G1 | `satellite_day_status` 열 = satellite, date, status, data_source, updated_at | **열림** | release 에 파일 없음(INSPECT §2 「없음」). `/satellite-status` 503 (시제품 표본 `status.json`) |
| G2 | 월 `value_source` = 그 달 다수 날의 출처 | **확인됨**(결과 일치) | 2012-01 = `modis_m2v`, 2026-11 = `n20_bridge` 한 가지(INSPECT §2 코드값). 단 meta 는 「다수 날」이 아니라 2012-01 을 명시 규칙으로 적음 — 결과만 같다 |
| G3 | 2012-01-01~19 = `modis_m2v` | **확인됨** | meta `harmonization.2012-01` 「m2v_xgb 값만 사용 … value_source=modis_m2v」(시제품 `/method` 응답) — 달 전체가 modis_m2v |
| G4 | 백분위 = 중간 순위, 소수 1자리 | **열림**(식) · **반증됨**(반올림) | 파일 percentile 은 반올림 안 됨(`8.333333333333332`, 표본 `cal.json`). 식은 재계산 대조가 SKIP 이라 안 쟀다 |
| G5 | AOI 소속 = 칸 중심이 경계 안 | **반증됨** | 중심 기준 Σ칸 ≠ aoi 값: KR VIIRS +11.8 %, MODIS +57.1 % (INSPECT §3). SIDO_47 2025-03 value 2,352.95 vs raw 2,353 → 면적 무게로 보임(추론). 팀 방식 확인 필요 → 열림 Q-M4 |
| G6 | cell_id 공식 | **확인됨** | 51,562,642행 불일치 0 (INSPECT §2) |
| G7 | SIDO 코드(강원 51·전북 52) | **확인됨** | INSPECT §2 aoi_list |
| G8 | 군위 `SGG_47720` | **반증됨** | release = `SGG_27720`(부모 SIDO_27 대구). 목업은 이미 27720 |
| G9 | 점 `country` = ISO3, `adm1` 한국 = SIDO_xx | **열림** | 점 파일 없음 |
| G10 | `low_sample` = 비교 표 bool(`baseline_p75<1`) → uncertain | **확인됨**(모양) · **반증됨**(판정) | bool 열, `low_sample ⇔ baseline_p75<1` 불일치 0. 그러나 low_sample & above 5,250행(팀 규칙 값≥3 → above). 계약은 파일 그대로 → 핸드오프 「판정 생략」과 어느 쪽이 확정인지 열림(X1) |
| G11 | `m2v_meta.json` 은 method_version 만 필수 | **확인됨** | meta 에 `method_version: v1-m2v-2026-11-14` (시제품 `/method`) |
| G12 | 달 missing = 기준 위성 자료 < 그 달 날의 50 % | **열림** | release 결측 달은 2001-06 하나뿐(모든 AOI), 기준 수치는 파일·meta 에 없음 |

## B. arch2 결정표 (ARCHITECTURE §9 — S·K·X·F·D 21행 + CONFLICTS C1)

| id | 질문 | 권장(arch2) | 상태 | 비고 |
|---|---|---|---|---|
| S1 | 로그인 뒤 기능 범위 | (a) 해커톤엔 공개 읽기만 | **사람 결정** | openapi 는 공개 읽기 경로만 정의(인증 경로는 오류 번호만 예약) |
| S2 | 비용 상한 값 | 꼭짓점 5,000·all 31일·reference 92일 | **사람 결정** | openapi `Version.limits` 예시에 권장값 |
| S3 | 점 저장소 | DuckDB 로 시작 | **사람 결정** | 점 파일이 와야 잴 수 있음 |
| S4 | OMB 검사 이미지 확장 | 확장 | **사람 결정**(OMB 소유자) | |
| S5 | 핸드오프 별칭 유지 기간 | 영구 | **사람 결정** | openapi 는 영구 가정(`x-handoff-alias`) |
| S6 | 큰 polygon | `POST /summary-queries` 병행 | **사람 결정** | openapi 에 반영 |
| S7 | JPA 도입 시점 | B5 부터 | **사람 결정** | 계약 무관 |
| S8 | 계약 정본 소유 | `contract/`(contract-first) | **사람 결정**(이 파일로 초안 적용) | `contract/openapi.yaml` 이 그 초안 |
| K1 | Spring Boot 판 | 4.0.x (B0 RED 통과 조건) | **사람 결정** | |
| X1 | `low_sample` 표현 | 불린 필드 | **확인됨**(모양) · **열림**(판정 규칙) | release = bool 열. verdict 규칙은 모델팀 확정 필요 |
| X3 | release 에 sds·hotspots·manifest 포함 | 포함 요청 | **열림** | 이 release 에 sds·hotspots 없음. `SHA256SUMS`·`.done` 은 있음(manifest 는 없음) |
| X7 | bbox·polygon·day 합계의 status 출처 | 모델팀이 정함 | **열림** | 점 파일·sds 가 와야 의미 있음 |
| X9 | 핸드오프 vs `api_contract.md` 우선 | `api_contract.md` 가 오면 대체 | **열림** | 아직 안 옴 |
| F1 | 프런트 렌더링 | 정적 내보내기 | **사람 결정** | |
| F2 | 한국 DEM 음영 | 유지 | **사람 결정** | |
| F6 | 로그인 화면 | 아이디·비밀번호 폼 | **사람 결정** | |
| F7 | 쿼리 정규화 규칙 위치 | 계약 `x-normalization` | **열림**(우리) | openapi 에 아직 `x-normalization` 없음 — 다음 판 |
| F8 | 시각 회귀 기준선 | aibox 고정 이미지 | **사람 결정** | |
| F9 | `aoi`→`aoi_id`, 옛 해시 링크 | 해커톤 끝까지 | **사람 결정** | 목업은 이미 `aoi_id` |
| D5 | 공개 배포 위치 | 조직 계정 정적 | **사람 결정** | |
| D9 | GitHub ↔ OMB bare 동기 | GitHub 정본 | **사람 결정** | |
| (C1) | 경로 접두사 `/api/v1` vs 접두사 없음 | `/api/v1` + `/api/<핸드오프>` 별칭 | **사람 결정** | openapi 는 arch2 대로 썼다. CONFLICTS C1·C2 를 닫으려면 확인 필요 |

## C. 모델팀 질문 30개 (`questions/model-team-questions.md`)

### C-A. API-v2 가정 확인용 21개

| # | 요지 | 상태 | 근거 |
|---|---|---|---|
| A1 | aoi_id 값·검증된 곳·경계 | **확인됨** | aoi_list 5,051개·경계 5,038개(INSPECT §2). meta `validation_summary` 에 korea·world·대륙·argentina |
| A2 | cell_id·중심/모서리·lat/lon 열 | **확인됨** | 공식 불일치 0, 중심 = 모서리+0.05, `cell_lat`/`cell_lon` 열 있음 |
| A3 | 칸 범위 | **확인됨** | 전 세계(칸 1,088,534개) |
| A4 | raw_viirs·보정 값이 S-NPP 만인지, NOAA-21 섞임 | **확인됨** | meta `snpp_observed` 정의, 2026-11 은 `n20_bridge`, `n21_bridge` 0행 |
| A5 | 구간 위치 | **확인됨** | 칸·AOI 모두 `value_lo/hi`. 새 문제: value 가 구간 밖 441,269행 → 열림 Q-M5 |
| A6 | 보정 활동 일수 | **확인됨**(없음) | 그런 열 없음 |
| A7 | `n_det_conf` | **대체됨** | 그 열 이름 자체가 없음 |
| A8 | 0 칸 행·결측 기준 | **확인됨**(0 칸 행 없음) · **열림**(결측 기준·null 행) | 0 칸 행 없음, 그러나 2001-06 null 칸 14,502행 → Q-M6. 결측 기준 → Q-M7 |
| A9 | 2026-11 나눔·mixed | **확인됨** | 달 전체 `n20_bridge`, `mixed` 없음 |
| A10 | 월 기준 KST/UTC | **열림** | 파일·meta 에 없음 (CONFLICTS R15) |
| A11 | 결측 날짜 형식·시간대 | **열림** | sds 없음 |
| A12 | 상태 겹침·low_sample 조건 | **확인됨**(low_sample = p75<1) · **열림**(겹침 순서·판정) | |
| A13 | validation_summary 모양 | **확인됨** | `{지역: {metric: pooled_month_total_MAE, mean}}`, `final_method: m2v_xgb` |
| A14 | 판정 문턱 | **확인됨** | 핸드오프 80/20·n<10·status=ok, 파일 경계 위반은 E5 2행뿐 |
| A15 | model_version 위치 | **확인됨** | `method_version` = cell 열 + meta, aoi_month 에는 없음 |
| A16 | 2000-11~12·Terra 시기 값·구간 | **확인됨** | AOI 값·구간 있음(표본 `cal2001.json`), 칸 구간은 null(meta `cell_interval`) |
| A17 | 파일 위치·갱신 주기·이름 | **확인됨**(위치·이름) · **열림**(주기) | GitHub release asset, `SHA256SUMS` |
| A18 | 2012-01-01~19 | **확인됨** | G3 |
| A19 | 결측 문턱 | **열림** | G12 |
| A20 | 덩어리 열 | **열림**(낮음) | release 에 없음 |
| A21 | `events.parquet` | **열림**(낮음) | release 에 없음 |

### C-B. 실자료 분석 질문 9개

| # | 요지 | 상태 | 근거 |
|---|---|---|---|
| B1 | 경북 북부 정의(문경·군위) | **사람 결정** | release 는 현행 행정구역(군위 = 대구). `group:gb-north` 는 우리 확장 |
| B2 | VIIRS 기준 S-NPP 단독, NOAA-20 처리 | **확인됨** | 핸드오프: S-NPP 기준, NOAA-20 × 1.013 (우리 ÷1.09 아님) |
| B3 | 2012 이전 k 방식 | **대체됨** | 모델 m2v_xgb 확정(meta `final_method`) |
| B4 | 낮/밤 분리 보정 | **대체됨** | meta 「추가 튜닝 없이 확정」 |
| B5 | 불확실성 띠·사건 달 과소 추정 표시 | **사람 결정** | 화면 표시는 우리 몫. 값은 `value_lo/hi` |
| B6 | 4개국 9개 연도 추가 다운로드 | **대체됨** | release 사용(A3 결정) |
| B7 | 2026 부분 연도 | **확인됨** | release 2026-11 까지(NRT 2026-08~) |
| B8 | 합성 자료 수정·위성 이름 표기 | **사람 결정** | 코드값은 `N`/`N20`/`N21`(핸드오프). 화면 문구는 우리 |
| B9 | GBN 기울기 3.168 vs 3.178 | **대체됨** | calib 안 씀(A3) |

## D. release 에서 새로 나온 질문 (CONFLICTS R 줄)

| # | 내용 | 상태 |
|---|---|---|
| R3 | aoi_list `name`·`parent_aoi`, `name_en` 없음, level `kr/sido/sgg` — `api_contract.md` 에서 어느 이름이 확정인지 | **열림** |
| R5 | AOI 5,051 × 313개월 (arch2 셈 5,050 × 324) | **확인됨**(release 값으로 계약 예시 고침) |
| R7 | value 가 구간 밖 441,269행 | **열림** |
| R6 | 2001-06 null 칸 행 | **열림** |
| R12 | meta `regional_only` 문구 ≠ 파일 | **열림** |
| R13 | AOI 합 ≠ Σ칸 | **열림**(G5 와 같은 질문) |
| R14 | 경계 없는 AOI 13개 | **사람 결정**(계약: 200 + geometry null 로 정함) |
| R16 | 검사기 메모리 9.5 GB | **확인됨 해소**(시제품 `a10a535` 칸 표를 DuckDB 뷰로 — 이번에 8G 유닛에서 release 로 기동, 응답 확인. 최대 RSS 는 안 쟀다) |

## 셈

| 묶음 | 항목 | 확인됨(부분 포함) | 반증됨 | 대체됨 | 열림 | 사람 결정 |
|---|---|---|---|---|---|---|
| G | 12 | 6 | 4 | 0 | 6 | 0 |
| arch2 + C1 | 22 | 1 | 0 | 0 | 5 | 17 |
| 모델팀 30 | 30 | 17 | 0 | 5 | 8 | 3 |
| R 새 질문 | 8 | 2 | 0 | 0 | 5 | 1 |

한 항목이 「확인됨 · 열림」처럼 둘이면 두 칸에 다 세었다(그래서 가로 합이 항목 수보다 클 수 있다). 모델팀에 물을 것만 중복 없이 추리면 `questions/model-team-open.md` 의 14개.
