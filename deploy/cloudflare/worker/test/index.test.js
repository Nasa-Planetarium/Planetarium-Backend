import { test } from 'node:test';
import assert from 'node:assert/strict';
import worker, { ipLiteral } from '../src/index.js';

const BASE = 'http://pl-web-tunnel.planetarium.svc.cluster.local:8090';

function fakeOrigin(respond = () => new Response('{"ok":true}', { status: 200, headers: { 'content-type': 'application/json', etag: '"e1"', 'cache-control': 'public, max-age=60, s-maxage=600', 'set-cookie': 'a=b', connection: 'close' } })) {
  const calls = [];
  return { calls, fetch: async (req) => { calls.push(req); return respond(req); } };
}

function env(origin) {
  return { ORIGIN: origin, ORIGIN_BASE: BASE };
}

const req = (path, init = {}) => new Request(`https://emberline-api.internal${path}`, init);

test('GET /api/v1/* goes to ORIGIN at the tunnel listener with path and query', async () => {
  const origin = fakeOrigin();
  const res = await worker.fetch(req('/api/v1/aois/KR/summary?start=2025-01-01&freq=month'), env(origin));
  assert.equal(res.status, 200);
  assert.equal(origin.calls.length, 1);
  assert.equal(origin.calls[0].url, `${BASE}/api/v1/aois/KR/summary?start=2025-01-01&freq=month`);
  assert.equal(origin.calls[0].method, 'GET');
  assert.equal(res.headers.get('etag'), '"e1"');
  assert.equal(res.headers.get('cache-control'), 'public, max-age=60, s-maxage=600');
  assert.equal(res.headers.get('set-cookie'), null);
  assert.deepEqual(await res.json(), { ok: true });
});

test('HEAD is forwarded as HEAD', async () => {
  const origin = fakeOrigin();
  const res = await worker.fetch(req('/api/v1/version', { method: 'HEAD' }), env(origin));
  assert.equal(res.status, 200);
  assert.equal(origin.calls[0].method, 'HEAD');
});

test('X-Client-IP from the Pages Function is passed on when it is one IP literal', async () => {
  for (const ip of ['203.0.113.7', '2001:db8::1']) {
    const origin = fakeOrigin();
    await worker.fetch(req('/api/v1/aois', { headers: { 'x-client-ip': ip, 'cf-connecting-ip': '198.51.100.1' } }), env(origin));
    assert.equal(origin.calls[0].headers.get('x-client-ip'), ip);
    assert.equal(origin.calls[0].headers.get('cf-connecting-ip'), null);
  }
  const origin = fakeOrigin();
  await worker.fetch(req('/api/v1/aois', { headers: { 'x-client-ip': '1.2.3.4, 5.6.7.8', 'cf-connecting-ip': '198.51.100.1' } }), env(origin));
  assert.equal(origin.calls[0].headers.get('x-client-ip'), '198.51.100.1');
  const none = fakeOrigin();
  await worker.fetch(req('/api/v1/aois', { headers: { 'x-client-ip': 'evil' } }), env(none));
  assert.equal(none.calls[0].headers.get('x-client-ip'), null);
});

test('hop-by-hop, auth and forwarding headers are stripped; cache validators pass', async () => {
  const origin = fakeOrigin();
  await worker.fetch(req('/api/v1/aois', {
    headers: {
      authorization: 'Bearer x', cookie: 'sid=1', 'proxy-authorization': 'Basic x', 'cf-access-jwt-assertion': 'jwt',
      'x-forwarded-for': '6.6.6.6', 'x-real-ip': '6.6.6.6', 'keep-alive': '5', te: 'trailers', upgrade: 'h2c',
      'if-none-match': '"e1"', accept: 'application/json', 'accept-language': 'ko',
    },
  }), env(origin));
  const h = origin.calls[0].headers;
  for (const name of ['authorization', 'cookie', 'proxy-authorization', 'cf-access-jwt-assertion', 'x-forwarded-for',
    'x-real-ip', 'keep-alive', 'te', 'upgrade']) {
    assert.equal(h.get(name), null, name);
  }
  assert.equal(h.get('if-none-match'), '"e1"');
  assert.equal(h.get('accept'), 'application/json');
  assert.equal(h.get('accept-language'), 'ko');
});

test('other methods are 405 with Allow, without calling ORIGIN', async () => {
  for (const method of ['POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS']) {
    const origin = fakeOrigin();
    const res = await worker.fetch(req('/api/v1/aois', { method }), env(origin));
    assert.equal(res.status, 405, method);
    assert.equal(res.headers.get('allow'), 'GET, HEAD');
    assert.equal(origin.calls.length, 0);
  }
});

test('paths outside /api/v1/ are 404 without calling ORIGIN', async () => {
  for (const path of ['/', '/actuator/health', '/api/aoi', '/api/v2/aois', '/v3/api-docs', '/swagger-ui/index.html', '/api/v1']) {
    const origin = fakeOrigin();
    const res = await worker.fetch(req(path), env(origin));
    assert.equal(res.status, 404, path);
    assert.equal(origin.calls.length, 0, path);
  }
});

test('origin status codes pass through; an unreachable origin is 502', async () => {
  const notModified = fakeOrigin(() => new Response(null, { status: 304, headers: { etag: '"e1"' } }));
  assert.equal((await worker.fetch(req('/api/v1/aois', { headers: { 'if-none-match': '"e1"' } }), env(notModified))).status, 304);
  const limited = fakeOrigin(() => new Response('{}', { status: 429, headers: { 'retry-after': '7' } }));
  const r = await worker.fetch(req('/api/v1/aois'), env(limited));
  assert.equal(r.status, 429);
  assert.equal(r.headers.get('retry-after'), '7');
  const down = { fetch: async () => { throw new Error('tunnel down'); } };
  const res = await worker.fetch(req('/api/v1/aois'), env(down));
  assert.equal(res.status, 502);
  assert.equal(res.headers.get('cache-control'), 'no-store');
});

test('ipLiteral', () => {
  assert.equal(ipLiteral('255.255.255.255'), '255.255.255.255');
  assert.equal(ipLiteral('256.1.1.1'), null);
  assert.equal(ipLiteral('::1'), '::1');
  assert.equal(ipLiteral('2001:db8::1::2'), null);
  assert.equal(ipLiteral(null), null);
});

// ---- the one public POST ----

import { readFileSync } from 'node:fs';
import { FORECAST_MAX_BODY } from '../src/index.js';

const FORECAST = '{"region":"SIDO_51","horizon_days":30}';
const chunked = (bytes) => new ReadableStream({
  start(c) {
    for (let i = 0; i < bytes.length; i += 4096) c.enqueue(bytes.subarray(i, i + 4096));
    c.close();
  },
});

test('POST /api/v1/forecast forwards the JSON body with Content-Type application/json', async () => {
  const origin = fakeOrigin(() => new Response('{"p_any":0.619,"notice":"위성"}', { status: 200, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } }));
  const res = await worker.fetch(req('/api/v1/forecast', {
    method: 'POST', body: FORECAST,
    headers: { 'content-type': 'application/json; charset=utf-8', 'x-client-ip': '203.0.113.7', cookie: 'a=b', 'accept-language': 'ko' },
  }), env(origin));
  assert.equal(res.status, 200);
  assert.equal(await res.text(), '{"p_any":0.619,"notice":"위성"}');
  assert.equal(res.headers.get('cache-control'), 'no-store');
  assert.equal(origin.calls.length, 1);
  const sent = origin.calls[0];
  assert.equal(sent.method, 'POST');
  assert.equal(sent.url, `${BASE}/api/v1/forecast`);
  assert.equal(sent.headers.get('content-type'), 'application/json');
  assert.equal(sent.headers.get('x-client-ip'), '203.0.113.7');
  assert.equal(sent.headers.get('cookie'), null);
  assert.equal(sent.headers.get('accept-language'), 'ko');
  assert.equal(await sent.text(), FORECAST);
});

test('POST /api/v1/forecast: origin errors pass through (problem+json, 429 Retry-After)', async () => {
  const limited = fakeOrigin(() => new Response('{"code":"rate_limited","error_no":10301}', { status: 429, headers: { 'content-type': 'application/problem+json', 'retry-after': '6' } }));
  const res = await worker.fetch(req('/api/v1/forecast', { method: 'POST', body: FORECAST, headers: { 'content-type': 'application/json' } }), env(limited));
  assert.equal(res.status, 429);
  assert.equal(res.headers.get('retry-after'), '6');
  assert.equal((await res.json()).error_no, 10301);
});

test('POST /api/v1/forecast body cap: Content-Length over, chunked over → 413; exactly the cap is forwarded', async () => {
  const over = new Uint8Array(FORECAST_MAX_BODY + 1).fill(0x20);
  for (const init of [
    { method: 'POST', body: over, headers: { 'content-type': 'application/json', 'content-length': String(over.length) } },
    { method: 'POST', body: chunked(over), duplex: 'half', headers: { 'content-type': 'application/json' } },
  ]) {
    const origin = fakeOrigin();
    const res = await worker.fetch(req('/api/v1/forecast', init), env(origin));
    assert.equal(res.status, 413);
    assert.equal(res.headers.get('content-type'), 'application/problem+json');
    assert.equal(origin.calls.length, 0);
  }
  const exact = new Uint8Array(FORECAST_MAX_BODY).fill(0x20);
  exact.set(new TextEncoder().encode(FORECAST));
  const origin = fakeOrigin();
  const res = await worker.fetch(req('/api/v1/forecast', { method: 'POST', body: chunked(exact), duplex: 'half', headers: { 'content-type': 'application/json' } }), env(origin));
  assert.equal(res.status, 200);
  assert.equal((await origin.calls[0].arrayBuffer()).byteLength, FORECAST_MAX_BODY);
});

test('POST /api/v1/forecast without a JSON Content-Type is 415', async () => {
  for (const type of [null, 'text/plain', 'application/x-www-form-urlencoded', 'application/jsonx']) {
    const origin = fakeOrigin();
    const headers = type ? { 'content-type': type } : {};
    const res = await worker.fetch(req('/api/v1/forecast', { method: 'POST', body: FORECAST, headers }), env(origin));
    assert.equal(res.status, 415, String(type));
    assert.equal(origin.calls.length, 0);
  }
});

test('every other POST, and PUT/PATCH/DELETE on the forecast path, is 405 Allow: GET, HEAD', async () => {
  for (const path of ['/api/v1/forecast/', '/api/v1/forecasts', '/api/v1/forecast/x', '/api/forecast', '/api/v1/summary-queries',
    '/api/v1/aois', '/api/v1/cells/frp', '/actuator/health', '/api/v1/forecast/..', '/api/v1/forecast%2F']) {
    const origin = fakeOrigin();
    const res = await worker.fetch(req(path, { method: 'POST', body: FORECAST, headers: { 'content-type': 'application/json' } }), env(origin));
    assert.equal(res.status, 405, path);
    assert.equal(res.headers.get('allow'), 'GET, HEAD', path);
    assert.equal(origin.calls.length, 0, path);
  }
  for (const method of ['PUT', 'PATCH', 'DELETE', 'OPTIONS']) {
    const origin = fakeOrigin();
    const res = await worker.fetch(req('/api/v1/forecast', { method, body: method === 'OPTIONS' ? undefined : FORECAST }), env(origin));
    assert.equal(res.status, 405, method);
    assert.equal(res.headers.get('allow'), 'GET, HEAD');
    assert.equal(origin.calls.length, 0);
  }
});

test('the Worker body cap is emberline.limits.forecast-body-bytes', () => {
  const yml = readFileSync(new URL('../../../../src/main/resources/application.yml', import.meta.url), 'utf8');
  assert.equal(Number(/forecast-body-bytes:\s*(\d+)/.exec(yml)[1]), FORECAST_MAX_BODY);
});
