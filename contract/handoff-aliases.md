# Hand-off aliases (generated from contract/openapi.yaml `x-handoff-alias`)

Served by `io.emberline.shared.web.alias.HandoffAliasForwardFilter` (one forward filter, no alias controllers): the alias request is forwarded to the canonical `/api/v1` route with the same dispatcher and the response gets `Link: <canonical>; rel="canonical"`. Body = canonical body. Aliases are not in springdoc.

Status column: **served** = implemented in `HandoffAliasTable` now (B1); later packets add their rows (B2 calendar/compare/summary/export.csv, B3 hotspots/cells, B4 firms/wms).

| alias (served_at) | hand-off path | canonical operation | canonical path | parameter mapping | status |
|---|---|---|---|---|---|
| `/api/aoi` | `/aoi` | `GET listAois` | `/api/v1/aois` | `level` → level; `parent_id` → parent_id; `q` → q; `include_geometry` → (별칭 전용) true 면 각 항목에 geometry. parent_id 없이 true 면 400 invalid_param | served |
| `/api/aoi` | `/aoi` | `GET getAoiBoundary` | `/api/v1/aois/{aoiId}/boundary` | `include_geometry` → true → 목록 항목마다 geometry (정본은 이 하위 자원) | later packet |
| `/api/calendar` | `/calendar` | `GET getCalendar` | `/api/v1/aois/{aoiId}/calendar` | `aoi_id` → path {aoiId}; `start_year` → start_year; `end_year` → end_year | later packet |
| `/api/export.csv` | `/export.csv` | `GET getCalendar` (CSV) | `/api/v1/aois/{aoiId}/calendar` + `Accept: text/csv` | `type` → calendar; `aoi_id` → path {aoiId}; `start_year` → start_year; `end_year` → end_year | later packet |
| `/api/compare` | `/compare` | `GET getComparison` | `/api/v1/aois/{aoiId}/months/{yearMonth}/comparison` | `aoi_id` → path {aoiId}; `year` → path {yearMonth} 의 YYYY; `month` → path {yearMonth} 의 MM (1–12, 앞 0 없이도 받음) | later packet |
| `/api/summary` | `/summary` | `GET getAoiSummary` | `/api/v1/aois/{aoiId}/summary` | `aoi_id` → path {aoiId}; `start` → start; `end` → end; `freq` → freq | later packet |
| `/api/export.csv` | `/export.csv` | `GET getAoiSummary` (CSV) | `/api/v1/aois/{aoiId}/summary` + `Accept: text/csv` | `type` → summary | later packet |
| `/api/summary` | `/summary` | `GET getBboxSummary` | `/api/v1/summaries` | `bbox` → bbox; `start` → start; `end` → end; `freq` → freq | later packet |
| `/api/summary` | `/summary` | `POST querySummaryByPolygon` | `/api/v1/summary-queries` | `polygon` → body.polygon (별칭 GET 은 쿼리 문자열, URL 8 KB 안에서); `start` → body.start; `end` → body.end; `freq` → body.freq | later packet |
| `/api/hotspots` | `/hotspots` | `GET listHotspots` | `/api/v1/hotspots` | `bbox` → bbox; `aoi_id` → aoi_id; `start` → start; `end` → end; `layer` → layer; `limit` → limit | served |
| `/api/export.csv` | `/export.csv` | `GET listHotspots` (CSV) | `/api/v1/hotspots` + `Accept: text/csv` | `type` → hotspots | later packet |
| `/api/cells` | `/cells` | `GET listCells` | `/api/v1/cells` | `aoi_id` → aoi_id; `bbox` → bbox; `year` → month 의 YYYY; `month` → month 의 MM | later packet |
| `/api/status` | `/status` | `GET getSatelliteStatus` | `/api/v1/satellite-status` | `start` → start; `end` → end | served |
| `/api/method` | `/method` | `GET getCurrentMethod` | `/api/v1/methods/current` | — | served |
| `/api/firms/wms` | `/firms/wms` | `GET getFirmsWmsTile` | `/api/v1/firms/wms-tiles` | `layer` → layer; `bbox` → bbox; `width` → width; `height` → height; `time` → time | later packet |
