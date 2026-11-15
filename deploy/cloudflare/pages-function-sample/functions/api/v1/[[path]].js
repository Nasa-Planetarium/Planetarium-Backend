// Pages Function for /api/v1/* of the static site: same-origin API, no CORS. Calls the Worker emberline-api through
// the service binding API; falls back to env.API_ORIGIN only when the binding is absent. The client address is sent as
// X-Client-IP from Cloudflare's own CF-Connecting-IP (any X-Client-IP the browser sent is replaced).

const FORWARD = ['accept', 'accept-language', 'if-none-match', 'if-modified-since', 'user-agent'];

function problem(status, title, extra = {}) {
  return new Response(JSON.stringify({ title, status }), {
    status,
    headers: { 'content-type': 'application/problem+json', 'cache-control': 'no-store', ...extra },
  });
}

export async function onRequest({ request, env }) {
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    return problem(405, 'Method Not Allowed', { allow: 'GET, HEAD' });
  }
  const url = new URL(request.url);
  const headers = new Headers();
  for (const name of FORWARD) {
    const v = request.headers.get(name);
    if (v !== null) headers.set(name, v);
  }
  const client = request.headers.get('cf-connecting-ip');
  if (client) headers.set('x-client-ip', client);
  const init = { method: request.method, headers };
  if (env.API && typeof env.API.fetch === 'function') {
    return env.API.fetch(new Request(url.toString(), init));
  }
  if (env.API_ORIGIN) {
    return fetch(new URL(url.pathname + url.search, env.API_ORIGIN).toString(), init);
  }
  return problem(503, 'API not configured');
}
