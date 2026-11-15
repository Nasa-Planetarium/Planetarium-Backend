import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { execFile } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const TOOLS = join(dirname(fileURLToPath(import.meta.url)), '..');

function run(script, args) {
  return new Promise((resolve) => {
    execFile(process.execPath, [join(TOOLS, script), ...args], { timeout: 60_000 }, (err, stdout, stderr) =>
      resolve({ code: err ? err.code : 0, stdout, stderr }));
  });
}

/** A fake tunnel listener with the allowlist semantics; `leak` serves one blocked path. */
function fakeOrigin({ leak = null, limitEvery = 0 } = {}) {
  const seen = { requests: 0, limited: 0 };
  const allowed = [/^\/api\/v1\/(version|aois|summaries|hotspots|cells|satellite-status|methods\/current|firms\/wms-tiles)$/,
    /^\/api\/v1\/aois\/[^/]+(\/(boundary|calendar|summary|months\/\d{4}-\d{2}\/comparison))?$/, /^\/api\/v1\/methods\/[^/]+$/];
  const server = createServer((req, res) => {
    seen.requests++;
    const path = new URL(req.url, 'http://x').pathname;
    if (path === leak) {
      res.writeHead(200, { 'content-type': 'application/json' }).end('{"status":"UP"}');
    } else if (req.method === 'POST' && path === '/api/v1/forecast') {
      res.writeHead(400, { 'content-type': 'application/problem+json' }).end('{"code":"invalid_param","error_no":40006}');
    } else if (req.method !== 'GET' && req.method !== 'HEAD') {
      res.writeHead(405).end();
    } else if (!allowed.some((r) => r.test(path))) {
      res.writeHead(404).end();
    } else if (limitEvery && seen.requests % limitEvery === 0) {
      seen.limited++;
      res.writeHead(429, { 'retry-after': '1' }).end('{}');
    } else {
      res.writeHead(200, { 'content-type': 'application/json' }).end(path === '/api/v1/version' ? '{"method_version":"v1-x"}' : '{}');
    }
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve({ server, seen, base: `http://127.0.0.1:${server.address().port}` })));
}

test('public-smoke passes against an allowlist origin, one line per row', async () => {
  const { server, base } = await fakeOrigin();
  try {
    const r = await run('public-smoke.mjs', [base]);
    assert.equal(r.code, 0, r.stdout);
    const lines = r.stdout.trim().split('\n');
    assert.equal(lines.at(-1), 'SMOKE OK');
    assert.ok(lines.slice(0, -1).every((l) => l.startsWith('PASS ')), r.stdout);
    assert.ok(lines.some((l) => l.startsWith('PASS GET /api/v1/methods/v1-x ')));
    assert.ok(lines.some((l) => l.startsWith('PASS POST /api/v1/aois → 405')));
    assert.ok(lines.some((l) => l.startsWith('PASS GET /actuator → 404')));
    assert.ok(lines.some((l) => l.startsWith('PASS POST /api/v1/forecast (no period) → 400')));
    assert.ok(lines.some((l) => l.startsWith('PASS POST /api/v1/forecast/ → 405')));
  } finally {
    server.close();
  }
});

test('public-smoke exits 1 when a blocked path answers', async () => {
  const { server, base } = await fakeOrigin({ leak: '/actuator/health' });
  try {
    const r = await run('public-smoke.mjs', [base]);
    assert.equal(r.code, 1);
    assert.match(r.stdout, /^FAIL GET \/actuator\/health → 200 \(want 404\)$/m);
    assert.match(r.stdout, /SMOKE FAILED: 1 row\(s\)/);
  } finally {
    server.close();
  }
});

test('load-200 with 5 users for 5 s reports requests, statuses, percentiles per group and 429s', async () => {
  const { server, seen, base } = await fakeOrigin({ limitEvery: 7 });
  try {
    const started = Date.now();
    const r = await run('load-200.mjs', [base, '--users', '5', '--seconds', '5', '--json']);
    assert.equal(r.code, 0, r.stderr);
    assert.ok(Date.now() - started >= 5000);
    const s = JSON.parse(r.stdout.trim().split('\n').at(-1));
    assert.equal(s.users, 5);
    assert.equal(s.requests, seen.requests);
    assert.ok(s.requests >= 5 * 6, `every user views a page: ${s.requests}`);
    assert.equal(s.rate_limited_429, seen.limited);
    assert.equal(Object.values(s.statuses).reduce((a, b) => a + b, 0), s.requests);
    assert.equal((s.statuses['200'] ?? 0) + (s.statuses['429'] ?? 0), s.requests);
    for (const g of ['page', 'heavy']) {
      assert.ok(s.latency[g].count > 0, g);
      assert.ok(s.latency[g].p50_ms <= s.latency[g].p95_ms && s.latency[g].p95_ms <= s.latency[g].p99_ms, g);
    }
    assert.equal(s.latency.page.count, s.latency.heavy.count * 2);
  } finally {
    server.close();
  }
});
