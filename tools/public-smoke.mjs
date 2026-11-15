#!/usr/bin/env node
// Public allowlist smoke (pl-pub item 5) against a live base URL. Node 22 built-in fetch only.
//   node tools/public-smoke.mjs <base-url> [--pages]
// <base-url> = the tunnel listener (e.g. a port-forward to pl-web-tunnel:8090) or the public site with --pages.
// One line per row; exit 1 on any mismatch. With --pages, paths outside /api/v1/ belong to the static site, so for
// them the row checks that no API, actuator or OpenAPI content comes back instead of a 404.

const args = process.argv.slice(2);
const pages = args.includes('--pages');
const base = args.find((a) => !a.startsWith('--'));
if (!base) {
  console.error('usage: node tools/public-smoke.mjs <base-url> [--pages]');
  process.exit(2);
}

const OK = [200];
const OK_OR_UNAVAILABLE = [200, 503]; // release-absent route / FIRMS key or upstream
export const PUBLIC = [
  ['/api/v1/version', OK],
  ['/api/v1/aois?level=country', OK],
  ['/api/v1/aois/KR', OK],
  ['/api/v1/aois/KR/boundary', OK],
  ['/api/v1/aois/KR/calendar?start_year=2025&end_year=2025', OK],
  ['/api/v1/aois/KR/months/2025-03/comparison', OK],
  ['/api/v1/aois/KR/summary?start=2025-01-01&end=2025-03-31&freq=month', OK],
  ['/api/v1/summaries?bbox=126,34,127,35&start=2025-03-01&end=2025-03-31&freq=month', OK_OR_UNAVAILABLE],
  ['/api/v1/hotspots?bbox=126,34,127,35&start=2025-03-01&end=2025-03-02', OK],
  ['/api/v1/cells?aoi_id=KR&month=2025-03', OK],
  ['/api/v1/satellite-status?start=2025-03-01&end=2025-03-31', OK],
  ['/api/v1/methods/current', OK],
  ['/api/v1/methods/{methodVersion}', OK],
  ['/api/v1/firms/wms-tiles?layer=viirs_noaa20_24h&bbox=126,34,130,38&width=256&height=256', OK_OR_UNAVAILABLE],
];
export const BLOCKED_API = ['/api/v1/openapi.json', '/api/v1/summary-queries', '/api/v1/admin', '/api/v1/ingest',
  '/api/v1/load', '/api/v1/releases', '/api/v1/actuator/health', '/api/v1/swagger-ui/index.html', '/api/v1/'];
export const BLOCKED_OTHER = ['/', '/actuator', '/actuator/health', '/actuator/prometheus', '/v3/api-docs',
  '/swagger-ui/index.html', '/swagger', '/openapi.json', '/admin', '/ingest', '/api/aoi', '/api/export.csv?type=calendar&aoi_id=KR',
  '/api/v2/aois'];
const API_CONTENT = /"openapi"\s*:|"status"\s*:\s*"UP"|"_links"\s*:|"error_no"\s*:|"release_key"\s*:/;

let failures = 0;
function row(ok, text) {
  console.log(`${ok ? 'PASS' : 'FAIL'} ${text}`);
  if (!ok) failures++;
}

async function call(method, path, body, headers = {}) {
  try {
    const res = await fetch(new URL(path, base), { method, body, headers, redirect: 'manual', signal: AbortSignal.timeout(30_000) });
    return { status: res.status, text: method === 'HEAD' ? '' : await res.text() };
  } catch (e) {
    return { status: 0, text: String(e.cause?.code ?? e.message) };
  }
}

let methodVersion = 'v1-m2v-2026-11-14';
const version = await call('GET', '/api/v1/version');
try {
  methodVersion = JSON.parse(version.text).method_version ?? methodVersion;
} catch { /* row below reports the status */ }

for (const [template, expected] of PUBLIC) {
  const path = template.replace('{methodVersion}', encodeURIComponent(methodVersion));
  const r = await call('GET', path);
  row(expected.includes(r.status), `GET ${path} → ${r.status} (want ${expected.join('|')})`);
}
const head = await call('HEAD', '/api/v1/version');
row(head.status === 200, `HEAD /api/v1/version → ${head.status} (want 200)`);
for (const path of BLOCKED_API) {
  const r = await call('GET', path);
  row(r.status === 404, `GET ${path} → ${r.status} (want 404)`);
}
for (const path of BLOCKED_OTHER) {
  const r = await call('GET', path);
  if (pages) {
    row(r.status !== 0 && !API_CONTENT.test(r.text), `GET ${path} → ${r.status} (want static site, no API content)`);
  } else {
    row(r.status === 404, `GET ${path} → ${r.status} (want 404)`);
  }
}
for (const [method, path] of [['POST', '/api/v1/aois'], ['POST', '/api/v1/summary-queries'], ['PUT', '/api/v1/aois/KR'],
  ['DELETE', '/api/v1/aois/KR'], ['PATCH', '/api/v1/version']]) {
  const r = await call(method, path, method === 'DELETE' ? undefined : '{}');
  row(r.status === 405, `${method} ${path} → ${r.status} (want 405)`);
}
// the one public POST reaches Spring — an invalid body (no period) is Spring's 400 40006, so the forecast
// service is never called by the smoke; a near-miss path stays 405
const forecast = await call('POST', '/api/v1/forecast', '{"region":"SIDO_51"}', { 'content-type': 'application/json' });
row(forecast.status === 400 && /"error_no"\s*:\s*40006/.test(forecast.text),
  `POST /api/v1/forecast (no period) → ${forecast.status} (want 400 error_no 40006 from the app)`);
const nearMiss = await call('POST', '/api/v1/forecast/', '{}', { 'content-type': 'application/json' });
row(nearMiss.status === 405, `POST /api/v1/forecast/ → ${nearMiss.status} (want 405)`);
console.log(failures === 0 ? 'SMOKE OK' : `SMOKE FAILED: ${failures} row(s)`);
process.exit(failures === 0 ? 0 : 1);
