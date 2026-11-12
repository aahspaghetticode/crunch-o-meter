"""FastAPI app: background pollers fill a cache, endpoints serve the cache."""

from __future__ import annotations

import asyncio
import hmac
import logging
import time
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Request

from . import __version__
from .boinc_rpc import BoincClient
from .config import Config
from .gridcoin import GridcoinClient
from .lact import LactReader
from .system import SystemSampler

log = logging.getLogger("crunchometer")


def _section(data=None, error: str | None = None) -> dict:
    return {"ok": error is None, "error": error, "updated_at": time.time(), "data": data}


class State:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.boinc = BoincClient(cfg.boinc)
        self.gridcoin = GridcoinClient(cfg.gridcoin)
        self.lact = LactReader(cfg.lact)
        self.system = SystemSampler(cfg.system.history_samples)
        empty = {"ok": False, "error": "not polled yet", "updated_at": None, "data": None}
        self.cache = {k: dict(empty) for k in ("boinc", "gridcoin", "system", "lact")}
        if not cfg.lact.enabled:
            self.cache["lact"]["error"] = "disabled in config"

    async def _loop(self, name: str, interval: float, fn, wait_for: str | None = None):
        if wait_for:
            # The Gridcoin estimate needs BOINC's project list, so give BOINC a head start.
            for _ in range(30):
                if self.cache[wait_for]["data"]:
                    break
                await asyncio.sleep(1)
        while True:
            try:
                data = await asyncio.to_thread(fn)
                if self.cache[name].get("error") and self.cache[name].get("updated_at"):
                    log.info("%s poll recovered", name)
                self.cache[name] = _section(data)
            except Exception as exc:  # keep polling whatever goes wrong
                msg = str(exc) or type(exc).__name__
                if self.cache[name].get("error") != msg:
                    log.warning("%s poll failed: %s", name, msg)
                # Keep last good data so the app doesn't blank out on one hiccup.
                prev = self.cache[name].get("data")
                self.cache[name] = {**_section(prev, msg)}
            await asyncio.sleep(interval)

    def _gridcoin(self):
        projects = (self.cache["boinc"].get("data") or {}).get("projects") or []
        return self.gridcoin.snapshot(projects)

    def tasks(self) -> list[asyncio.Task]:
        c = self.cfg
        loops = [
            self._loop("system", c.system.poll_seconds, self.system.sample),
            self._loop("boinc", c.boinc.poll_seconds, self.boinc.snapshot),
            self._loop("gridcoin", c.gridcoin.poll_seconds, self._gridcoin, wait_for="boinc"),
        ]
        if c.lact.enabled:
            loops.append(self._loop("lact", c.lact.poll_seconds, self.lact.snapshot))
        return [asyncio.create_task(l) for l in loops]


def create_app(cfg: Config) -> FastAPI:
    state = State(cfg)

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        tasks = state.tasks()
        yield
        for t in tasks:
            t.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        state.system.close()

    app = FastAPI(title="Crunch-o-meter", version=__version__, lifespan=lifespan,
                  docs_url=None, redoc_url=None, openapi_url=None)

    def require_token(request: Request):
        header = request.headers.get("authorization", "")
        scheme, _, given = header.partition(" ")
        if scheme.lower() != "bearer" or not hmac.compare_digest(given.encode(), cfg.server.token.encode()):
            raise HTTPException(status_code=401, detail="bad or missing token",
                                headers={"WWW-Authenticate": "Bearer"})

    auth = [Depends(require_token)]

    @app.get("/api/health")
    async def health():
        return {"ok": True, "version": __version__}

    @app.get("/api/summary", dependencies=auth)
    async def summary():
        return {"version": __version__, "generated_at": time.time(), **state.cache}

    for name in ("boinc", "gridcoin", "system", "lact"):
        def make(n):
            async def endpoint():
                return state.cache[n]
            return endpoint
        app.add_api_route(f"/api/{name}", make(name), methods=["GET"], dependencies=auth)

    return app
