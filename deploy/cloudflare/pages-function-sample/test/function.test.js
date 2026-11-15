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
