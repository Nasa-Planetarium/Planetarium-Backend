"""Fixtures for the wrapper tests. Run with the model repo's venv (polars, numpy, shapely) and pytest:
  cd <model copy> && uv run --frozen --no-sync --with pytest pytest <repo>/deploy/forecast/tests --basetemp <scratch>/pytest
Env: FORECAST_TEST_MODEL = a copy of the model repo at c43368c (git archive) with its venv,
     FORECAST_TEST_RELEASE = the release folder (default $HOME/planetarium/release/v1.2-2026-11-15), only read.
"""

from __future__ import annotations

import http.client
import json
import os
import shutil
import socket
import subprocess
import sys
import time
from datetime import timedelta
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
FORECAST = HERE.parent
sys.path.insert(0, str(FORECAST))
import layout  # noqa: E402

MODEL = Path(os.environ["FORECAST_TEST_MODEL"]).resolve()
RELEASE = Path(os.environ.get("FORECAST_TEST_RELEASE", str(Path.home() / "planetarium/release/v1.2-2026-11-15")))
OLD, NEW = "2026-11-14", "2026-11-15"


def shift_daily(src: Path, dst: Path, days: int = 1) -> None:
    """A second as_of for the tests only: the release planner set with the daily grid moved `days` later."""
    import polars as pl
    dst.mkdir(parents=True)
    for f in src.iterdir():
        if f.name != layout.MANIFEST:
            shutil.copyfile(f, dst / f.name)
    d = pl.read_parquet(src / layout.DAILY)
    d.with_columns(pl.col("date") + timedelta(days), pl.col("as_of_date") + timedelta(days)).write_parquet(dst / layout.DAILY)


@pytest.fixture(scope="session")
def base_state(tmp_path_factory) -> Path:
    """Seeded once from the release (OLD), plus an unpublished NEW set next to it (.fixture-NEW)."""
    st = tmp_path_factory.mktemp("base") / "state"
    st.mkdir()
    layout.seed(RELEASE, st)
    shift_daily(st / OLD, st.parent / f"fixture-{NEW}")
    layout.write_manifest(st.parent / f"fixture-{NEW}", NEW, "test fixture")
    return st


@pytest.fixture
def state(base_state, tmp_path) -> Path:
    """A per-test copy of the seeded state (hard links: published files are never modified)."""
    st = tmp_path / "state"
    shutil.copytree(base_state, st, symlinks=True, copy_function=os.link)
    return st


@pytest.fixture
def new_set(base_state) -> Path:
    return base_state.parent / f"fixture-{NEW}"


def publish_copy(state: Path, src: Path, as_of: str) -> Path:
    tmp = state / f".tmp-test-{as_of}"
    shutil.copytree(src, tmp, copy_function=os.link)
    try:
        return layout.publish(state, tmp, as_of)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def model_root(tmp: Path) -> tuple[Path, Path]:
    """A private model root: a byte-identical copy of the package, data -> <tmp>/pl-data (the view link)."""
    root = tmp / "root"
    shutil.copytree(MODEL / "planetarium", root / "planetarium", ignore=shutil.ignore_patterns("__pycache__"))
    link = tmp / "pl-data"
    os.symlink(link, root / "data")
    return root, link


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Client:
    def __init__(self, port: int):
        self.port = port

    def request(self, method: str, path: str, body: bytes | str | dict | None = None, headers: dict | None = None,
                timeout: float = 60):
        if isinstance(body, dict):
            body = json.dumps(body)
        if isinstance(body, str):
            body = body.encode("utf-8")
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=timeout)
        try:
            c.request(method, path, body=body, headers=headers or {})
            r = c.getresponse()
            raw = r.read()
            return r.status, dict(r.getheaders()), (json.loads(raw) if raw else None)
        finally:
            c.close()


class ServerProc:
    def __init__(self, tmp: Path, state: Path, **env):
        self.root, self.link = model_root(tmp)
        self.port = free_port()
        self.log = open(tmp / "server.log", "w+", encoding="utf-8")
        e = {**os.environ, "FORECAST_MODEL_ROOT": str(self.root), "FORECAST_RELEASE_DIR": str(RELEASE),
             "FORECAST_STATE_DIR": str(state), "FORECAST_DATA_LINK": str(self.link), "FORECAST_HOST": "127.0.0.1",
             "FORECAST_PORT": str(self.port), "FORECAST_POLL_SECONDS": "0.5", "PYTHONDONTWRITEBYTECODE": "1",
             **{k: str(v) for k, v in env.items()}}
        e.pop("PYTHONPATH", None)
        self.p = subprocess.Popen([sys.executable, str(FORECAST / "server.py")], env=e, stdout=self.log, stderr=subprocess.STDOUT)
        self.client = Client(self.port)

    def wait_warm(self, timeout: float = 120) -> dict:
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if self.p.poll() is not None:
                raise AssertionError(f"server exited {self.p.returncode}: {self.logs()}")
            try:
                st, _, body = self.client.request("GET", "/healthz", timeout=2)
                if st == 200:
                    return body
            except OSError:
                pass
            time.sleep(0.2)
        raise AssertionError(f"server not warm: {self.logs()}")

    def logs(self) -> str:
        self.log.flush()
        self.log.seek(0)
        return self.log.read()[-4000:]

    def stop(self):
        self.p.terminate()
        try:
            self.p.wait(10)
        except subprocess.TimeoutExpired:
            self.p.kill()
        self.log.close()


@pytest.fixture
def server(tmp_path, state):
    s = ServerProc(tmp_path, state)
    s.wait_warm()
    yield s
    s.stop()
