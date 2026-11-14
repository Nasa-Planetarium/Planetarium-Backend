# `invalid_param`

problem `type`: `https://emberline.example/errors/invalid_param` · generated from `ErrorCode` Javadoc (same four cells) and `contract/openapi.yaml` `x-error-catalog`.

## 10001 `INVALID_PARAM` — HTTP 400

title: en “Invalid parameter” · ko “잘못된 매개변수”

| why | what | how | opinion |
|---|---|---|---|
| bad input or a P4 injection attempt. | converters, Bean Validation, exactly-one rules, impossible dates, start after end. | 400 with `param`; not retryable; INFO log without stack. | 400 rather than 422 — the existing contract and the front end branch on 400. |

## 40001 `INVALID_GEOMETRY` — HTTP 400

title: en “Invalid geometry” · ko “잘못된 도형”

| why | what | how | opinion |
|---|---|---|---|
| self-intersection, NaN, or coordinates beyond ±180 / ±90. | GeoPolygon constructor. | 400 with `param=polygon`. | — |

## 50001 `RELEASE_INVALID` — no HTTP · CLI exit 3

title: en “Release rejected” · ko “release 검사 실패”

| why | what | how | opinion |
|---|---|---|---|
| operator mistake or a broken release folder. | release validator (files, columns, code values, duplicate keys, per-file sha256, method_version). | CLI exit 3 with reason_codes; the release becomes REJECTED. | the active release stays as it is. |

## 50003 `RELEASE_KEY_INVALID` — no HTTP · CLI exit 3

title: en “Invalid release key” · ko “잘못된 release 키”

| why | what | how | opinion |
|---|---|---|---|
| path escape attempt. | release key regex `^[a-z0-9][a-z0-9._-]{0,63`$} plus normalize().startsWith(root). | CLI exit 3, no directory is created. | checked before any file system access. |
