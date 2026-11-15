# contract/ — HTTP 계약

| 파일 | 상태 | 설명 |
|---|---|---|
| `openapi.yaml` | 정본 초안 | OpenAPI 3.1, `/api/v1` 자원 경로(arch2), 핸드오프 8경로+확장 2경로는 `x-handoff-alias`. 스키마 = 핸드오프 열 이름 + release 실사실, 예시 = release 실값. 오류 problem+json + `ErrorNo` 대역 표. |
| `MAPPING.md` | 현행 | 정본 ↔ 핸드오프 ↔ 시제품 v3 ↔ 목업 EM.api 경로·파라미터·필드·코드값·헤더 대응 + 시제품 응답 대조 결과 |
| `ASSUMPTIONS.md` | 현행 | G1–G12 · arch2 결정표 · 모델팀 질문 30개 · release 새 질문 — 항목별 확인됨/반증됨/대체됨/열림/사람 결정 |
| `validation/` | 근거 | `validate.py`(검증기 실행 스크립트) · `validate.out.txt` · `samples/`(시제품이 release 로 낸 응답 14개 + 헤더) |
| `API-v3.md` | 현행(대체 예정) | 핸드오프 경로(접두사 없음) 기준 계약. 2026-11-14 17:55 판 사본(sha256 `5c753668…ee5cc5f`, 원본과 같음을 복사 때 잼). |
| `prototype-plapi/README.md` | 참고 | FastAPI 시제품 서버 README. 코드는 옮기지 않았다. |
| `prototype-plapi/TESTS.md` | 참고 | 시제품 시험 이름 124개(grep). 실행 결과는 안 쟀다. |

## openapi.yaml 상태 (2026-11-14)

- arch2 결정대로 정본 경로를 `/api/v1` 로 썼다. 이 선택(CONFLICTS C1·C2)은 아직 사람 확인 전이다 — 확인되면 API-v3.md 는 「대체됨」으로 옮긴다.
- 문법: openapi-spec-validator 0.7.1 통과, 예시 43/43 이 자기 스키마 통과.
- 시제품 응답 대조: 성공 응답 10개(5경로) PASS, 오류 4개는 `error_no` 없음으로 FAIL(정본이 요구하는 멤버를 시제품이 아직 안 냄). 자세히는 `MAPPING.md` §6.
- 원본 `API-v3.md` 는 다른 작업이 읽는 중이라 이 저장소에서만 다룬다(원본 수정 0).

## openapi.yaml 상태 (2026-11-15, v1.2 · F15)

- 정본 데이터 `data-v1.2-2026-11-15`. 새 경로 9개(+ `/satellite-status` 실제화) — 목록은 `00-decisions.md` F15 경로 목록, 대응은 `MAPPING.md` §8.
- 검증(잰 것): openapi-spec-validator 0.7.1 통과, 예시 74/74 자기 스키마 통과, 음성 대조 — 새 예시 4개에 틀린 enum 을 넣은 사본은 4/4 FAIL. 시제품 표본 오류 4개 FAIL(error_no 없음)은 앞과 같은 알려진 차이.
- 안 잰 것: `harmonized_cell_day_frp` 열(받는 중), 실제 백엔드 응답(아직 구현 없음), 한도 값이 서버에서 감당되는지(부하 시험 금지 — 숫자는 추정).
