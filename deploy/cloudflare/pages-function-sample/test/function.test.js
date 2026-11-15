import { test } from 'node:test';
import assert from 'node:assert/strict';
import { onRequest } from '../functions/api/v1/[[path]].js';

const req = (path, init = {}) => new Request(`https://emberline.pages.dev${path}`, init);

test('uses the service binding API and sets X-Client-IP from CF-Connecting-IP', async () => {
  const calls = [];
  const API = { fetch: async (r) => { calls.push(r); return new Response('ok'); } };
  const res = await onRequest({ request: req('/api/v1/aois?level=country', { headers: { 'cf-connecting-ip': '203.0.113.9', 'x-client-ip': '6.6.6.6', cookie: 'a=b', 'if-none-match': '"e"' } }), env: { API, API_ORIGIN: 'https://unused.example' } });
  assert.equal(await res.text(), 'ok');
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, 'https://emberline.pages.dev/api/v1/aois?level=country');
  assert.equal(calls[0].headers.get('x-client-ip'), '203.0.113.9');
  assert.equal(calls[0].headers.get('cookie'), null);
  assert.equal(calls[0].headers.get('if-none-match'), '"e"');
});

test('falls back to API_ORIGIN only without the binding', async () => {
  const seen = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (u, init) => { seen.push([u, init]); return new Response('fallback'); };
  try {
    const res = await onRequest({ request: req('/api/v1/version'), env: { API_ORIGIN: 'https://api.example' } });
    assert.equal(await res.text(), 'fallback');
    assert.equal(seen[0][0], 'https://api.example/api/v1/version');
  } finally {
    globalThis.fetch = realFetch;
  }
  assert.equal((await onRequest({ request: req('/api/v1/version'), env: {} })).status, 503);
});

test('other methods are 405', async () => {
  const res = await onRequest({ request: req('/api/v1/aois', { method: 'POST', body: '{}' }), env: { API: { fetch: () => assert.fail('called') } } });
  assert.equal(res.status, 405);
  assert.equal(res.headers.get('allow'), 'GET, HEAD');
});

// ---- the one public POST ----

import { readFileSync } from 'node:fs';
import { FORECAST_MAX_BODY } from '../functions/api/v1/[[path]].js';

const FORECAST = '{"region":"SIDO_51","horizon_days":30}';

test('POST /api/v1/forecast goes through the binding with its JSON body', async () => {
  const calls = [];
  const API = { fetch: async (r) => { calls.push(r); return new Response('{"p_any":0.619}', { headers: { 'content-type': 'application/json' } }); } };
  const res = await onRequest({ request: req('/api/v1/forecast', { method: 'POST', body: FORECAST, headers: { 'content-type': 'application/json;charset=UTF-8', 'cf-connecting-ip': '203.0.113.9', cookie: 'a=b' } }), env: { API } });
  assert.equal(await res.text(), '{"p_any":0.619}');
  assert.equal(calls.length, 1);
  assert.equal(calls[0].method, 'POST');
  assert.equal(calls[0].url, 'https://emberline.pages.dev/api/v1/forecast');
  assert.equal(calls[0].headers.get('content-type'), 'application/json');
  assert.equal(calls[0].headers.get('x-client-ip'), '203.0.113.9');
  assert.equal(calls[0].headers.get('cookie'), null);
  assert.equal(await calls[0].text(), FORECAST);
});

test('POST /api/v1/forecast via API_ORIGIN keeps the body', async () => {
  const seen = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (u, init) => { seen.push([u, init]); return new Response('ok'); };
  try {
    await onRequest({ request: req('/api/v1/forecast', { method: 'POST', body: FORECAST, headers: { 'content-type': 'application/json' } }), env: { API_ORIGIN: 'https://api.example' } });
    assert.equal(seen[0][0], 'https://api.example/api/v1/forecast');
    assert.equal(seen[0][1].method, 'POST');
    assert.equal(new TextDecoder().decode(seen[0][1].body), FORECAST);
  } finally {
    globalThis.fetch = realFetch;
  }
});

test('POST /api/v1/forecast: 413 over the cap (with and without Content-Length), 415 without JSON', async () => {
  const API = { fetch: () => assert.fail('called') };
  const over = new Uint8Array(FORECAST_MAX_BODY + 1).fill(0x20);
  assert.equal((await onRequest({ request: req('/api/v1/forecast', { method: 'POST', body: over, headers: { 'content-type': 'application/json', 'content-length': String(over.length) } }), env: { API } })).status, 413);
  const stream = new ReadableStream({ start(c) { c.enqueue(over); c.close(); } });
  assert.equal((await onRequest({ request: req('/api/v1/forecast', { method: 'POST', body: stream, duplex: 'half', headers: { 'content-type': 'application/json' } }), env: { API } })).status, 413);
  assert.equal((await onRequest({ request: req('/api/v1/forecast', { method: 'POST', body: FORECAST, headers: { 'content-type': 'text/plain' } }), env: { API } })).status, 415);
});

test('every other POST, and PUT/PATCH/DELETE on the forecast path, is 405', async () => {
  const API = { fetch: () => assert.fail('called') };
  for (const path of ['/api/v1/forecast/', '/api/v1/forecasts', '/api/v1/summary-queries', '/api/v1/aois']) {
    const res = await onRequest({ request: req(path, { method: 'POST', body: FORECAST, headers: { 'content-type': 'application/json' } }), env: { API } });
    assert.equal(res.status, 405, path);
    assert.equal(res.headers.get('allow'), 'GET, HEAD');
  }
  for (const method of ['PUT', 'PATCH', 'DELETE']) {
    const res = await onRequest({ request: req('/api/v1/forecast', { method, body: FORECAST }), env: { API } });
    assert.equal(res.status, 405, method);
    assert.equal(res.headers.get('allow'), 'GET, HEAD');
  }
});

test('the Pages Function body cap is emberline.limits.forecast-body-bytes', () => {
  const yml = readFileSync(new URL('../../../../src/main/resources/application.yml', import.meta.url), 'utf8');
  assert.equal(Number(/forecast-body-bytes:\s*(\d+)/.exec(yml)[1]), FORECAST_MAX_BODY);
});
