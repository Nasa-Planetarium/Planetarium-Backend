# `polygon_too_complex`

problem `type`: `https://emberline.example/errors/polygon_too_complex` · generated from `ErrorCode` Javadoc (same four cells) and `contract/openapi.yaml` `x-error-catalog`.

## 40102 `POLYGON_TOO_COMPLEX` — HTTP 400

title: en “Polygon too complex” · ko “다각형이 너무 복잡함”

| why | what | how | opinion |
|---|---|---|---|
| P2 polygon bomb. | polygon converter (vertices over 5,000 or rings over 50), counted while parsing. | 400 with `param=polygon`. | same code as prototype v3; count before JTS isValid. |

## 40108 `FORECAST_POLYGON_TOO_COMPLEX` — HTTP 400

title: en “Polygon too complex” · ko “다각형이 너무 복잡함”

| why | what | how | opinion |
|---|---|---|---|
| P2 polygon bomb on the forecast route. | `POST /forecast` GeoJSON region with more positions (all rings of all polygons) than `Version.limits` forecast_polygon_vertices. | 400 with `param=region`, the limit in `detail`. | separate from 40102 — a different limit on a different route; counted before any geometry is built. |
