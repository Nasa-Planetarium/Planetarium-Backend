#!/usr/bin/env node
// Browsing-profile load (pl-pub item 2) with Node built-ins only.
// node tools/load-200.mjs <base-url> [--users 200] [--seconds 300] [--seed 42] [--json]
// Each user arrives within the first 10 s (or the run, if shorter), then views a page every 10 s (6 API calls, 2 of
// them heavy) and pans the map every 15 s from 2 s after arrival (20 FIRMS tiles out of 30 viewports). Open loop:
// the schedule does not wait for answers. Reports requests, status counts, p50/p95/p99 per endpoint group, 429s.

const argv = process.argv.slice(2);
function opt(name, dflt) {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 ? argv[i + 1] : dflt;
}
const base = argv.find((a, i) => !a.startsWith('--') && !argv[i - 1]?.startsWith('--'));
if (!base) {
  console.error('usage: node tools/load-200.mjs <base-url> [--users 200] [--seconds 300] [--seed 42] [--json]');
  process.exit(2);
}
const users = Number(opt('users', 200));
const seconds = Number(opt('seconds', 300));
const json = argv.includes('--json');
let seed = Number(opt('seed', 42)) >>> 0;
function random() { // mulberry32
  seed = (seed + 0x6d2b79f5) >>> 0;
  let t = seed;
  t = Math.imul(t ^ (t >>> 15), t | 1);
  t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
}

const PAGE = [
  ['page', '/api/v1/version'],
  ['page', '/api/v1/aois/KR'],
  ['page', '/api/v1/aois/KR/summary?start=2025-01-01&end=2025-03-31&freq=month'],
  ['page', '/api/v1/satellite-status?start=2025-03-01&end=2025-03-31'],
  ['heavy', '/api/v1/cells?aoi_id=KR&month=2025-03'],
  ['heavy', '/api/v1/hotspots?bbox=126,34,127,35&start=2025-03-01&end=2025-03-02'],
];
function tilePath(v, j) {
  const w = (125 + (v % 6) + (j % 5) * 0.2).toFixed(4);
  const s = (33.2 + Math.floor(v / 6) + Math.floor(j / 5) * 0.2).toFixed(4);
  const e = (Number(w) + 0.2).toFixed(4);
  const n = (Number(s) + 0.2).toFixed(4);
  return `/api/v1/firms/wms-tiles?layer=viirs_noaa20_24h&bbox=${w},${s},${e},${n}&width=256&height=256`;
}

const groups = {};
const statuses = {};
let requests = 0;
const inflight = new Set();
const t0 = performance.now();
const end = t0 + seconds * 1000;

function record(group, status, ms) {
  requests++;
  statuses[status] = (statuses[status] ?? 0) + 1;
  (groups[group] ??= []).push(ms);
}

function hit(group, path) {
  const started = performance.now();
  const p = fetch(new URL(path, base), { signal: AbortSignal.timeout(60_000) })
    .then(async (res) => { await res.arrayBuffer(); record(group, String(res.status), performance.now() - started); })
    .catch((e) => record(group, `error:${e.cause?.code ?? e.name}`, performance.now() - started))
    .finally(() => inflight.delete(p));
  inflight.add(p);
}

function every(firstAt, periodMs, fn) {
  let k = 0;
  const tick = () => {
    const due = firstAt + k * periodMs;
    if (due >= end) return;
    const wait = due - performance.now();
    if (wait > 0) {
      setTimeout(tick, wait);
      return;
    }
    k++;
    fn();
    tick();
  };
  tick();
}

const arrivalWindow = Math.min(10, seconds) * 1000;
for (let u = 0; u < users; u++) {
  const arrival = t0 + random() * arrivalWindow;
  every(arrival, 10_000, () => PAGE.forEach(([g, p]) => hit(g, p)));
  every(arrival + 2_000, 15_000, () => {
    const v = Math.floor(random() * 30);
    for (let j = 0; j < 20; j++) hit('tiles', tilePath(v, j));
  });
}

await new Promise((resolve) => setTimeout(resolve, Math.max(0, end - performance.now())));
await Promise.allSettled([...inflight]);

function pct(sorted, p) {
  if (sorted.length === 0) return null;
  return Math.round(sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)] * 10) / 10;
}
const latency = {};
for (const [g, list] of Object.entries(groups)) {
  const s = [...list].sort((a, b) => a - b);
  latency[g] = { count: s.length, p50_ms: pct(s, 50), p95_ms: pct(s, 95), p99_ms: pct(s, 99) };
}
const summary = { base, users, seconds, requests, statuses, rate_limited_429: statuses['429'] ?? 0, latency };
if (json) {
  console.log(JSON.stringify(summary));
} else {
  console.log(`base ${base} users ${users} seconds ${seconds}`);
  console.log(`requests ${requests}  429 ${summary.rate_limited_429}`);
  console.log(`statuses ${Object.entries(statuses).map(([k, v]) => `${k}=${v}`).join(' ')}`);
  for (const [g, l] of Object.entries(latency)) {
    console.log(`${g.padEnd(6)} n=${l.count} p50=${l.p50_ms}ms p95=${l.p95_ms}ms p99=${l.p99_ms}ms`);
  }
}
