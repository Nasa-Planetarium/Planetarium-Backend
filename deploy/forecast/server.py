"""FireOutlook Planner HTTP wrapper: the model team's planetarium.forecast, unchanged, behind two routes.

POST /forecast  {region, start?, end?, horizon_days?, as_of?} -> forecast(...)'s dict as JSON, unchanged
                (json.dumps like the model CLI: ensure_ascii=False, default=str). Headers X-Forecast-As-Of, X-Data-Version.
GET  /healthz   {as_of, data_version, warm}; 200 when warm, 503 before (K8s probes read only the status).
Errors          {"error": "<code>", "message": "..."}: 400 invalid_json | invalid_request | invalid_query (the model's
                ValueError and friends), 404 unknown_aoi | not_found, 405 method_not_allowed, 411 length_required,
                413 body_too_large, 503 warming_up | busy, 500 internal | non_finite.
Every number comes from forecast(); this file only checks the request's shape, maps errors and serializes.

Start: compose the data view (layout.py), forecast.warmup() once, serve. forecast() keeps module state (_REGION_CTX,
lru caches), so calls run one at a time under a lock. A watcher polls <state>/current (and the release manifest); when
it names another published as_of (or the release changed), the server stops accepting, lets in-flight requests finish and re-executes itself with the listening socket
inherited (connections wait in the backlog), so the new process composes a fresh view and warms up again. A process
never reads two as_of values: its view links <state>/<as_of>/..., never <state>/current.
"""

from __future__ import annotations

import json
import os
import socket
import sys
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MODEL_ROOT = Path(os.environ.get("FORECAST_MODEL_ROOT", "/opt/model"))
sys.path[:0] = [str(Path(__file__).resolve().parent), str(MODEL_ROOT)]
import layout  # noqa: E402

RELEASE = Path(os.environ.get("FORECAST_RELEASE_DIR", "/release"))
STATE = Path(os.environ.get("FORECAST_STATE_DIR", "/state"))
DATA_LINK = Path(os.environ.get("FORECAST_DATA_LINK", "/tmp/pl-data"))
HOST = os.environ.get("FORECAST_HOST", "0.0.0.0")
PORT = int(os.environ.get("FORECAST_PORT", "8000"))
MAX_BODY = int(os.environ.get("FORECAST_MAX_BODY", str(256 * 1024)))      # bytes; Spring's own cap must be lower
POLL_SECONDS = float(os.environ.get("FORECAST_POLL_SECONDS", "30"))
MAX_CONNECTIONS = int(os.environ.get("FORECAST_MAX_CONNECTIONS", "32"))
WARM_WAIT_SECONDS = float(os.environ.get("FORECAST_WARM_WAIT_SECONDS", "30"))
DRAIN_SECONDS = float(os.environ.get("FORECAST_DRAIN_SECONDS", "20"))
READ_TIMEOUT_SECONDS = float(os.environ.get("FORECAST_READ_TIMEOUT_SECONDS", "10"))
LISTEN_FD_ENV = "FORECAST_LISTEN_FD"

ALLOWED_KEYS = {"region", "start", "end", "horizon_days", "as_of"}
GEOJSON_TYPES = {"Polygon", "MultiPolygon"}


class _State:
    warm = threading.Event()
    lock = threading.Lock()            # one forecast() at a time (module-level state in planetarium.forecast)
    active = 0                         # requests being handled
    active_cv = threading.Condition()
    as_of: str | None = None
    data_version: str | None = None
    as_of_dir: str | None = None
    release_stamp: tuple | None = None   # release_manifest.csv as data_version was computed from it
    aoi_ids: frozenset = frozenset()
    forecast = None
    input_errors: tuple = (ValueError, TypeError, KeyError, OverflowError)


S = _State()


def log(msg: str) -> None:
    print(f"{time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())} {msg}", file=sys.stderr, flush=True)


class BadRequest(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


def _reject_constant(c: str):
    raise ValueError(f"non-finite number {c} is not JSON")


def _is_num(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def parse_body(raw: bytes) -> dict:
    """Shape checks only (value limits are Spring's): region = aoi_id | [w, s, e, n] | GeoJSON (Multi)Polygon."""
    try:
        body = json.loads(raw.decode("utf-8"), parse_constant=_reject_constant)
    except (UnicodeDecodeError, ValueError) as e:
        raise BadRequest(400, "invalid_json", f"body is not JSON: {e}") from None
    if not isinstance(body, dict):
        raise BadRequest(400, "invalid_request", "body must be a JSON object")
    extra = sorted(set(body) - ALLOWED_KEYS)
    if extra:
        raise BadRequest(400, "invalid_request", f"unknown field(s): {', '.join(extra)}")
    if "region" not in body:
        raise BadRequest(400, "invalid_request", "region is required")
    region = body["region"]
    if isinstance(region, str):
        pass
    elif isinstance(region, list):
        if len(region) != 4 or not all(_is_num(v) for v in region):
            raise BadRequest(400, "invalid_request", "bbox region must be [west, south, east, north] numbers")
    elif isinstance(region, dict):
        g = region.get("geometry") if region.get("type") == "Feature" else region
        if not isinstance(g, dict) or g.get("type") not in GEOJSON_TYPES:
            raise BadRequest(400, "invalid_request", "GeoJSON region must be a Polygon or MultiPolygon (or a Feature of one)")
    else:
        raise BadRequest(400, "invalid_request", "region must be an aoi_id, a bbox or a GeoJSON polygon")
    for k in ("start", "end", "as_of"):
        if k in body and body[k] is not None and not isinstance(body[k], str):
            raise BadRequest(400, "invalid_request", f"{k} must be a YYYY-MM-DD string")
    hd = body.get("horizon_days")
    if hd is not None and (not isinstance(hd, int) or isinstance(hd, bool)):
        raise BadRequest(400, "invalid_request", "horizon_days must be an integer")
    return body


def run_forecast(body: dict) -> dict:
    region = body["region"]
    if isinstance(region, str) and region not in S.aoi_ids:
        raise BadRequest(404, "unknown_aoi", f"unknown aoi_id: {region}")
    with S.lock:
        try:
            return S.forecast(region, start=body.get("start"), end=body.get("end"), as_of=body.get("as_of"),
                              horizon_days=body.get("horizon_days"))
        except S.input_errors as e:
            raise BadRequest(400, "invalid_query", str(e)) from None


class Handler(BaseHTTPRequestHandler):
    server_version = "planetarium-forecast"
    sys_version = ""
    timeout = READ_TIMEOUT_SECONDS

    def log_message(self, format, *args):  # noqa: A002 (one line per request is written by _send)
        pass

    def _send(self, status: int, payload: bytes, headers: dict | None = None) -> None:
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(payload)
        log(f"{self.command} {self.path} {status} {len(payload)}B {1000 * (time.perf_counter() - self._t0):.1f}ms")

    def _error(self, status: int, code: str, message: str) -> None:
        self._send(status, json.dumps({"error": code, "message": message}, ensure_ascii=False).encode("utf-8"))

    def _route(self, method: str) -> None:
        self._t0 = time.perf_counter()
        with S.active_cv:
            S.active += 1
        try:
            path = self.path.split("?", 1)[0]
            if path == "/healthz" and method == "GET":
                self._healthz()
            elif path == "/forecast" and method == "POST":
                self._forecast()
            elif path in ("/healthz", "/forecast"):
                self.close_connection = True
                self._error(405, "method_not_allowed", f"{method} is not allowed on {path}")
            else:
                self.close_connection = True
                self._error(404, "not_found", f"no route {path}")
        except BadRequest as e:
            self._error(e.status, e.code, e.message)
        except (BrokenPipeError, ConnectionResetError, socket.timeout):
            self.close_connection = True
        except Exception:  # noqa: BLE001
            log("500 " + traceback.format_exc().replace("\n", " | "))
            self._error(500, "internal", "internal error")
        finally:
            with S.active_cv:
                S.active -= 1
                S.active_cv.notify_all()

    def do_GET(self):
        self._route("GET")

    def do_POST(self):
        self._route("POST")

    def do_HEAD(self):
        self._route("HEAD")

    def do_PUT(self):
        self._route("PUT")

    def do_DELETE(self):
        self._route("DELETE")

    def do_PATCH(self):
        self._route("PATCH")

    def do_OPTIONS(self):
        self._route("OPTIONS")

    def _healthz(self) -> None:
        warm = S.warm.is_set()
        body = {"as_of": S.as_of, "data_version": S.data_version, "warm": warm}
        self._send(200 if warm else 503, json.dumps(body).encode("utf-8"))

    def _read_body(self) -> bytes:
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            self.close_connection = True
            raise BadRequest(411, "length_required", "chunked bodies are not accepted; send Content-Length")
        cl = self.headers.get("Content-Length")
        if cl is None:
            self.close_connection = True
            raise BadRequest(411, "length_required", "Content-Length is required")
        try:
            n = int(cl)
        except ValueError:
            self.close_connection = True
            raise BadRequest(400, "invalid_request", "bad Content-Length") from None
        if n < 0:
            self.close_connection = True
            raise BadRequest(400, "invalid_request", "bad Content-Length")
        if n > MAX_BODY:
            self.close_connection = True          # the body is not read
            raise BadRequest(413, "body_too_large", f"body is {n} bytes, the limit is {MAX_BODY}")
        raw = self.rfile.read(n)
        if len(raw) != n:
            self.close_connection = True
            raise BadRequest(400, "invalid_request", "body shorter than Content-Length")
        return raw

    def _forecast(self) -> None:
        body = parse_body(self._read_body())
        if not S.warm.wait(WARM_WAIT_SECONDS):
            raise BadRequest(503, "warming_up", "the service is still warming up")
        r = run_forecast(body)
        try:
            payload = json.dumps(r, ensure_ascii=False, default=str, allow_nan=False).encode("utf-8")
        except ValueError:
            log("500 non-finite number in forecast() output for " + json.dumps(body, ensure_ascii=False)[:200])
            raise BadRequest(500, "non_finite", "the forecast contains a non-finite number") from None
        self._send(200, payload, {"X-Forecast-As-Of": str(r.get("as_of")), "X-Data-Version": S.data_version or ""})


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 64
    allow_reuse_address = True

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self._slots = threading.BoundedSemaphore(MAX_CONNECTIONS)

    def process_request(self, request, client_address):
        if not self._slots.acquire(blocking=False):
            try:
                body = b'{"error": "busy", "message": "too many connections"}'
                request.sendall(b"HTTP/1.0 503 Service Unavailable\r\nContent-Type: application/json\r\nContent-Length: "
                                + str(len(body)).encode() + b"\r\nConnection: close\r\n\r\n" + body)
            except OSError:
                pass
            self.shutdown_request(request)
            return
        super().process_request(request, client_address)

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._slots.release()


def make_server() -> Server:
    fd = os.environ.pop(LISTEN_FD_ENV, None)
    if fd is None:
        return Server((HOST, PORT), Handler)
    sock = socket.socket(fileno=int(fd))
    os.set_inheritable(sock.fileno(), False)
    srv = Server(sock.getsockname()[:2], Handler, bind_and_activate=False)
    srv.socket.close()
    srv.socket = sock
    srv.server_address = sock.getsockname()[:2]
    return srv


def release_stamp() -> tuple:
    st = (RELEASE / "release_manifest.csv").stat()
    return st.st_ino, st.st_size, st.st_mtime_ns


def warm_up(view: Path, published_as_of: str) -> None:
    from planetarium import config, forecast
    try:
        from shapely.errors import ShapelyError
        S.input_errors = S.input_errors + (ShapelyError,)
    except ImportError:
        pass
    want = (view / "processed").resolve()
    if Path(config.PROCESSED_DATA_DIR).resolve() != want:
        raise SystemExit(f"PROCESSED_DATA_DIR {config.PROCESSED_DATA_DIR} does not resolve to the view {want}")
    t0 = time.perf_counter()
    forecast.warmup()
    S.forecast = forecast.forecast
    S.aoi_ids = frozenset(forecast._aoi_w()[1])
    as_of = str(layout.as_of_of(view / "processed"))
    if as_of != published_as_of:
        raise SystemExit(f"as_of of the files {as_of} differs from the published as_of {published_as_of}")
    S.release_stamp = release_stamp()
    S.as_of, S.data_version = as_of, layout.data_version(RELEASE, as_of)
    S.warm.set()
    log(f"warm: as_of {S.as_of} data_version {S.data_version} ({time.perf_counter() - t0:.2f}s)")


def watch_and_reexec(srv: Server) -> None:
    """Poll <state>/current and the release manifest; when current names another valid published as_of, or the
    release changed (data_version is derived from it): stop accepting, drain, re-exec with the socket."""
    while True:
        time.sleep(POLL_SECONDS)
        try:
            if release_stamp() != S.release_stamp:
                why = "release_manifest.csv changed"
            else:
                pub = layout.current_dir(STATE)
                if pub.name == S.as_of_dir:
                    continue
                layout.check_published(pub)
                why = f"current -> {pub.name}"
        except (layout.LayoutError, OSError, ValueError) as e:
            log(f"watch: not usable, keep serving {S.as_of_dir}: {e}")
            continue
        log(f"watch: {why} (serving {S.as_of_dir}); draining and re-executing")
        srv.shutdown()                              # stop accept(); the socket stays open, new connections queue
        deadline = time.monotonic() + DRAIN_SECONDS
        with S.active_cv:
            while S.active and time.monotonic() < deadline:
                S.active_cv.wait(0.2)
        with S.lock:                                # no forecast() in flight
            fd = srv.socket.fileno()
            os.set_inheritable(fd, True)
            os.environ[LISTEN_FD_ENV] = str(fd)
            sys.stderr.flush()
            os.execv(sys.executable, [sys.executable, *sys.argv])


def main() -> None:
    info = layout.compose(RELEASE, STATE, DATA_LINK)
    S.as_of_dir = info["as_of_dir"].name
    log(f"view {info['view']} as_of_dir {S.as_of_dir}")
    srv = make_server()
    threading.Thread(target=srv.serve_forever, name="http", daemon=True).start()
    log(f"listening on {srv.server_address[0]}:{srv.server_address[1]}")
    try:
        warm_up(info["view"], info["as_of"])
    except BaseException:
        log("warmup failed " + traceback.format_exc().replace("\n", " | "))
        os._exit(3)
    watch_and_reexec(srv)


if __name__ == "__main__":
    main()
