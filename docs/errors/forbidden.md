# `forbidden`

problem `type`: `https://emberline.example/errors/forbidden` · generated from `ErrorCode` Javadoc (same four cells) and `contract/openapi.yaml` `x-error-catalog`.

## 70302 `FORBIDDEN` — HTTP 403

title: en “Forbidden” · ko “권한 없음”

| why | what | how | opinion |
|---|---|---|---|
| P5 privilege escalation. | AccessDeniedHandler. | 403, recorded by SecurityEventRecorder (REQUIRES_NEW). | only same-agency, insufficient-role cases are 403. |
