"""The wrapper over HTTP: /healthz, the error mapping, the body cap, the `current` switch.
Most tests run a real server.py process on the release data; the mapping of exceptions the model cannot be made to
raise (500 internal, non-finite) and the not-yet-warm state are checked in-process with a stand-in forecast()."""

from __future__ import annotations

import json
import os
import re
import socket
import threading
import time
from pathlib import Path

import pytest
from conftest import NEW, OLD, RELEASE, Client, publish_copy

import layout

DV = re.compile(r"^[A-Za-z0-9._:#+-]{1,96}$")      # deploy/deploy.sh accepts this data_version shape
Q30 = {"region": "SIDO_51", "horizon_days": 30, "start": "2026-11-16"}


def test_healthz(server):
    st, h, body = server.client.request("GET", "/healthz")
    assert st == 200 and h["Content-Type"].startswith("application/json")
    assert set(body) == {"as_of", "data_version", "warm"}
    assert body["as_of"] == OLD and body["warm"] is True
    assert DV.match(body["data_version"])
    assert body["data_version"] == layout.data_version(RELEASE, OLD)
    assert body["data_version"].startswith("v1.2-2026-11-15#") and body["data_version"].endswith(f"+planner.{OLD}")


def test_forecast_ok(server):
    st, h, body = server.client.request("POST", "/forecast", {"region": "SIDO_51", "horizon_days": 30, "start": "2026-11-15"})
    assert st == 200, body
    assert h["X-Forecast-As-Of"] == OLD and h["X-Data-Version"] == layout.data_version(RELEASE, OLD)
    assert body["as_of"] == OLD and body["start"] == "2026-11-15" and body["end"] == "2026-12-14"
    assert body["skill"] == "high" and body["p_any"] == 0.619 and len(body["daily"]) == 30
    st, _, body = server.client.request("POST", "/forecast", {"region": [126.5, 37, 127.5, 38], "start": "2026-12-01", "end": "2026-12-31"})
    assert st == 200 and body["region"] == "bbox" and body["value_source"] == "model"


BAD = [
    ("{", 400, "invalid_json"),
    ('{"region": [NaN, 0, 1, 1], "horizon_days": 30}', 400, "invalid_json"),
    (b"\xff\xfe", 400, "invalid_json"),
    ("[]", 400, "invalid_request"),
    ({}, 400, "invalid_request"),
    ({"region": "SIDO_51", "horizon_days": 30, "today": "2026-11-15"}, 400, "invalid_request"),
    ({"region": [1, 2, 3], "horizon_days": 30}, 400, "invalid_request"),
    ({"region": [1, 2, True, 4], "horizon_days": 30}, 400, "invalid_request"),
    ({"region": ["a", "b", "c", "d"], "horizon_days": 30}, 400, "invalid_request"),
    ({"region": {"type": "Point", "coordinates": [127, 37]}, "horizon_days": 30}, 400, "invalid_request"),
    ({"region": {"type": "FeatureCollection", "features": []}, "horizon_days": 30}, 400, "invalid_request"),
    ({"region": 5, "horizon_days": 30}, 400, "invalid_request"),
    ({"region": None, "horizon_days": 30}, 400, "invalid_request"),
    ({"region": "SIDO_51", "horizon_days": "30"}, 400, "invalid_request"),
    ({"region": "SIDO_51", "horizon_days": True}, 400, "invalid_request"),
    ({"region": "SIDO_51", "horizon_days": 30.5}, 400, "invalid_request"),
    ({"region": "SIDO_51", "start": 20261115, "end": "2026-12-01"}, 400, "invalid_request"),
    # the model's own ValueError and friends
    ({"region": "SIDO_51"}, 400, "invalid_query"),
    ({"region": "SIDO_51", "start": "2026-11-10", "end": "2026-11-20"}, 400, "invalid_query"),
    ({"region": "SIDO_51", "start": "2026-11-15", "end": "2028-01-01"}, 400, "invalid_query"),
    ({"region": "SIDO_51", "start": "bad", "end": "2026-12-01"}, 400, "invalid_query"),
    ({"region": "SIDO_51", "horizon_days": 30, "as_of": "zz"}, 400, "invalid_query"),
    ({"region": "SIDO_51", "horizon_days": -3}, 400, "invalid_query"),
    ({"region": "SIDO_51", "horizon_days": 10 ** 9}, 400, "invalid_query"),                       # OverflowError
    ({"region": {"type": "Polygon", "coordinates": [[[0, 0], [1, 1]]]}, "horizon_days": 30}, 400, "invalid_query"),
    ({"region": {"type": "Polygon", "coordinates": "x"}, "horizon_days": 30}, 400, "invalid_query"),
    ({"region": {"type": "Polygon"}, "horizon_days": 30}, 400, "invalid_query"),                  # KeyError
    ({"region": "NOPE", "horizon_days": 30}, 404, "unknown_aoi"),
    ({"region": "", "horizon_days": 30}, 404, "unknown_aoi"),
    ({"region": "sido_51", "horizon_days": 30}, 404, "unknown_aoi"),
]


def test_error_mapping(server):
    for body, status, code in BAD:
        st, h, r = server.client.request("POST", "/forecast", body)
        assert (st, r and r.get("error")) == (status, code), (body, st, r)
        assert set(r) == {"error", "message"} and r["message"], r
        assert h["Content-Type"].startswith("application/json")
    # the model's message is passed through
    _, _, r = server.client.request("POST", "/forecast", {"region": "SIDO_51"})
    assert "horizon_days" in r["message"]
    # the server keeps answering after every error
    assert server.client.request("POST", "/forecast", Q30)[0] == 200


def test_routes(server):
    c = server.client
    assert c.request("GET", "/forecast")[0:3:2] == (405, {"error": "method_not_allowed", "message": "GET is not allowed on /forecast"})
    assert c.request("POST", "/healthz", {})[2]["error"] == "method_not_allowed"
    assert c.request("PUT", "/forecast", Q30)[0] == 405
    assert c.request("DELETE", "/forecast")[0] == 405
    assert c.request("GET", "/nope")[0:3:2] == (404, {"error": "not_found", "message": "no route /nope"})
    assert c.request("POST", "/forecast/x", Q30)[2]["error"] == "not_found"


def _raw(port: int, data: bytes) -> bytes:
    with socket.create_connection(("127.0.0.1", port), timeout=10) as s:
        s.sendall(data)
        out = b""
        while chunk := s.recv(65536):
            out += chunk
    return out


def test_body_cap(server):
    big = json.dumps({"region": {"type": "Polygon", "coordinates": [[[126.5, 37.0]] * 40000]}, "horizon_days": 30})
    assert len(big) > 256 * 1024
    st, _, r = server.client.request("POST", "/forecast", big)
    assert (st, r["error"]) == (413, "body_too_large")
    # Content-Length over the cap is refused before any body byte is read
    out = _raw(server.port, b"POST /forecast HTTP/1.1\r\nHost: x\r\nContent-Length: 999999999\r\n\r\n")
    assert out.startswith(b"HTTP/1.0 413") and b"body_too_large" in out
    out = _raw(server.port, b"POST /forecast HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
    assert out.startswith(b"HTTP/1.0 411") and b"length_required" in out
    out = _raw(server.port, b"POST /forecast HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n5\r\n{}{}{\r\n0\r\n\r\n")
    assert out.startswith(b"HTTP/1.0 411")
    out = _raw(server.port, b"POST /forecast HTTP/1.1\r\nHost: x\r\nContent-Length: abc\r\n\r\n")
    assert out.startswith(b"HTTP/1.0 400") and b"invalid_request" in out
    # a body just under the cap is read and answered
    ok = json.dumps({**Q30, "region": "SIDO_51"}).ljust(256 * 1024 - 1)
    assert server.client.request("POST", "/forecast", ok)[0] == 200


def test_body_cap_setting(tmp_path, state):
    from conftest import ServerProc
    s = ServerProc(tmp_path, state, FORECAST_MAX_BODY=64)
    try:
        s.wait_warm()
        assert s.client.request("POST", "/forecast", {"region": "SIDO_51", "horizon_days": 30, "start": "2026-11-15", "x": "y" * 40})[2]["error"] == "body_too_large"
        assert s.client.request("POST", "/forecast", {"region": "SIDO_51", "horizon_days": 30})[0] == 200
    finally:
        s.stop()


def test_switch(tmp_path, state, new_set, server):
    """old as_of until the switch, new after; one process never answers from two; a broken `current` is ignored."""
    c = server.client
    pid = server.p.pid
    assert c.request("GET", "/healthz")[2]["as_of"] == OLD
    seen, errors, stop = [], [], threading.Event()

    def poll():
        cl = Client(server.port)
        while not stop.is_set():
            try:
                st, h, b = cl.request("POST", "/forecast", Q30, timeout=30)
                if st != 200:
                    errors.append((st, b))
                    continue
                daily = [r for r in b["daily"] if r["source"] == "daily model"]
                seen.append((b["as_of"], h["X-Forecast-As-Of"], h["X-Data-Version"], [r["date"] for r in daily]))
            except OSError as e:
                errors.append(repr(e))

    t = threading.Thread(target=poll)
    t.start()
    try:
        time.sleep(1.5)
        # a `current` naming a directory that is not a valid published set is ignored
        bad = state / "2026-11-20"
        bad.mkdir()
        tmp = state / ".cur-bad"
        os.symlink("2026-11-20", tmp)
        os.replace(tmp, state / "current")
        time.sleep(2)
        assert c.request("GET", "/healthz")[2]["as_of"] == OLD
        assert "not usable, keep serving" in server.logs()
        os.symlink(OLD, tmp)
        os.replace(tmp, state / "current")
        bad.rmdir()
        time.sleep(1)
        publish_copy(state, new_set, NEW)
        time.sleep(1)
        assert c.request("GET", "/healthz")[2]["as_of"] == OLD         # published but not current: still old
        layout.switch(state, NEW)
        end = time.monotonic() + 60
        while c.request("GET", "/healthz")[2]["as_of"] != NEW:
            assert time.monotonic() < end, server.logs()
            time.sleep(0.2)
        time.sleep(1.5)
    finally:
        stop.set()
        t.join()
    assert server.p.pid == pid and server.p.poll() is None              # re-exec keeps the process (and the port)
    assert not errors, errors[:3]
    olds = [s for s in seen if s[0] == OLD]
    news = [s for s in seen if s[0] == NEW]
    assert olds and news and len(olds) + len(news) == len(seen)
    assert [s[0] for s in seen] == [OLD] * len(olds) + [NEW] * len(news)   # never back to old after new
    for as_of, hdr, dv, days in seen:
        assert hdr == as_of and dv.endswith(f"+planner.{as_of}")
        first = {"2026-11-14": "2026-11-16", "2026-11-15": "2026-11-16"}[as_of]
        last = {"2026-11-14": "2026-11-21", "2026-11-15": "2026-11-22"}[as_of]
        assert days[0] == first and days[-1] == last, (as_of, days)   # the daily grid of that as_of, not the other
    # every planner file of the new view links into the NEW published dir, the rest into the release
    view = Path(os.readlink(server.link)) / "processed"
    for f in view.iterdir():
        target = Path(os.readlink(f))
        want = state / NEW if f.name.startswith("planner_") else RELEASE
        assert target.parent == want, (f.name, target)
    assert len(list(view.parent.parent.iterdir())) == 1                   # the old view is gone


# ---- in-process: what a real model cannot be made to do -------------------------------------------------------------

@pytest.fixture
def inproc(monkeypatch):
    import server as srv
    monkeypatch.setattr(srv, "WARM_WAIT_SECONDS", 0.2)
    monkeypatch.setattr(srv, "MAX_BODY", 1024)
    monkeypatch.setattr(srv.S, "warm", threading.Event())
    monkeypatch.setattr(srv.S, "aoi_ids", frozenset({"SIDO_51"}))
    monkeypatch.setattr(srv.S, "as_of", None)
    monkeypatch.setattr(srv.S, "data_version", None)
    monkeypatch.setattr(srv, "HOST", "127.0.0.1")
    monkeypatch.setattr(srv, "PORT", 0)
    s = srv.make_server()
    threading.Thread(target=s.serve_forever, daemon=True).start()
    yield srv, Client(s.server_address[1])
    s.shutdown()
    s.server_close()


def test_not_warm(inproc):
    srv, c = inproc
    st, _, b = c.request("GET", "/healthz")
    assert (st, b) == (503, {"as_of": None, "data_version": None, "warm": False})
    st, _, b = c.request("POST", "/forecast", Q30)
    assert (st, b["error"]) == (503, "warming_up")


def test_mapping_inproc(inproc, monkeypatch):
    srv, c = inproc
    srv.S.as_of, srv.S.data_version = OLD, "dv"
    srv.S.warm.set()
    calls = []
    errors = {"ValueError": ValueError, "TypeError": TypeError, "KeyError": KeyError, "boom": RuntimeError}

    def raising(region, **kw):
        calls.append((region, kw))
        if kw.get("start") in errors:
            raise errors[kw["start"]]("boom")
        if kw.get("start") == "nan":
            return {"p": float("nan")}
        return {"as_of": OLD, "v": 1.5, "d": "한"}

    monkeypatch.setattr(srv.S, "forecast", raising)
    for start, want in (("ValueError", (400, "invalid_query")), ("TypeError", (400, "invalid_query")),
                        ("KeyError", (400, "invalid_query")), ("boom", (500, "internal")), ("nan", (500, "non_finite"))):
        st, _, b = c.request("POST", "/forecast", {"region": "SIDO_51", "start": start})
        assert (st, b["error"]) == want, (start, b)
        if want[0] == 500:
            assert "boom" not in b["message"]                             # no internals in a 500
    st, h, raw = c.request("POST", "/forecast", {"region": "SIDO_51", "horizon_days": 30})
    assert st == 200 and raw == {"as_of": OLD, "v": 1.5, "d": "한"} and h["X-Data-Version"] == "dv"
    assert calls[-1] == ("SIDO_51", {"start": None, "end": None, "as_of": None, "horizon_days": 30})
    assert c.request("POST", "/forecast", {"region": "x" * 2000})[2]["error"] == "body_too_large"


def test_release_change_reexec(tmp_path, state):
    """data_version comes from release_manifest.csv: when the release changes, the server re-composes and re-warms."""
    import shutil
    from conftest import ServerProc
    farm = tmp_path / "rel" / RELEASE.name
    farm.mkdir(parents=True)
    for f in RELEASE.iterdir():
        if f.is_file() and not f.name.startswith("."):
            (shutil.copyfile(f, farm / f.name) if f.name == "release_manifest.csv" else os.symlink(f, farm / f.name))
    s = ServerProc(tmp_path, state, FORECAST_RELEASE_DIR=farm)
    try:
        dv1 = s.wait_warm()["data_version"]
        with open(farm / "release_manifest.csv", "a", encoding="utf-8") as fh:
            fh.write("extra.csv,,1,0\n")
        dv2 = layout.data_version(farm, OLD)
        assert dv2 != dv1
        end = time.monotonic() + 60
        while (s.client.request("GET", "/healthz")[2] or {}).get("data_version") != dv2:
            assert time.monotonic() < end, s.logs()
            time.sleep(0.2)
        assert "release_manifest.csv changed" in s.logs() and s.p.poll() is None
        st, h, b = s.client.request("POST", "/forecast", Q30)
        assert st == 200 and h["X-Data-Version"] == dv2 and b["as_of"] == OLD
    finally:
        s.stop()
