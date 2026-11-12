"""Entry point.

    crunch-o-meter                   # run with ~/.config/crunch-o-meter/config.toml
    crunch-o-meter -c path.toml      # run with a specific config
    crunch-o-meter gen-token         # print a fresh API token
    crunch-o-meter check             # poll everything once, print what works, exit
"""

from __future__ import annotations

import argparse
import logging
import secrets
import sys

from . import __version__
from .config import ConfigError, load_config


def _check(cfg) -> int:
    """One-shot diagnostics so setup problems show up before running as a service."""
    from .boinc_rpc import BoincClient
    from .gridcoin import GridcoinClient
    from .lact import LactReader
    from .system import SystemSampler

    failures = 0
    projects: list[dict] = []

    def report(name, fn):
        nonlocal failures
        try:
            result = fn()
            print(f"[ ok ] {name}")
            return result
        except Exception as exc:  # report every failure, keep going
            failures += 1
            print(f"[fail] {name}: {exc}")
            return None

    boinc = report("BOINC RPC", BoincClient(cfg.boinc).snapshot)
    if boinc:
        projects = boinc["projects"]
        print(f"       {len(projects)} projects, {len(boinc['active'])} queued, "
              f"{len(boinc['completed'])} completed")
    grc = report("Gridcoin", lambda: GridcoinClient(cfg.gridcoin).snapshot(projects))
    if grc:
        print(f"       source={grc['source']} magnitude={grc['magnitude']}"
              + (f"  (wallet: {grc['wallet_error']})" if grc.get("wallet_error") else ""))
        w = grc.get("wallet")
        if w:
            print(f"       balance={w['balance']} pending_research_reward={w['pending_research_reward']} "
                  f"status={w['researcher_status']!r}")
    sampler = SystemSampler(cfg.system.history_samples)
    sysd = report("CPU/GPU", sampler.sample)
    if sysd:
        print(f"       {sysd['cpu']['model']}, {len(sysd['gpus'])} NVIDIA GPU(s)"
              + (f"  ({sysd['gpu_error']})" if sysd["gpu_error"] else ""))
    sampler.close()
    if cfg.lact.enabled:
        lact = report("LACT", LactReader(cfg.lact).snapshot)
        if lact:
            print(f"       daemon={'up' if lact['daemon_reachable'] else lact['daemon_error']}, "
                  f"{len(lact['gpus'])} GPU config(s)")
    return 1 if failures else 0


def main() -> None:
    parser = argparse.ArgumentParser(prog="crunch-o-meter", description="Crunch-o-meter API server")
    parser.add_argument("command", nargs="?", choices=["serve", "gen-token", "check"], default="serve")
    parser.add_argument("-c", "--config", help="path to config.toml")
    parser.add_argument("--version", action="version", version=f"%(prog)s {__version__}")
    args = parser.parse_args()

    if args.command == "gen-token":
        print(secrets.token_urlsafe(32))
        return

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    try:
        cfg = load_config(args.config)
    except ConfigError as exc:
        print(f"crunch-o-meter: {exc}", file=sys.stderr)
        sys.exit(2)

    if args.command == "check":
        sys.exit(_check(cfg))

    import uvicorn

    from .app import create_app

    logging.getLogger("crunchometer").info(
        "v%s, config %s, listening on %s:%d",
        __version__, cfg.source_path or "(defaults)", cfg.server.bind_host, cfg.server.port,
    )
    uvicorn.run(
        create_app(cfg),
        host=cfg.server.bind_host,
        port=cfg.server.port,
        log_level="warning",       # our own logger reports poll problems; skip per-request noise
        server_header=False,
        proxy_headers=True,
        forwarded_allow_ips="127.0.0.1",
    )


if __name__ == "__main__":
    main()
