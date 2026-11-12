"""Config loading. TOML file, every key optional except the API token."""

from __future__ import annotations

import logging
import os
import tomllib
from dataclasses import dataclass, field
from pathlib import Path

log = logging.getLogger("crunchometer")

class ConfigError(ValueError):
    """Bad or missing configuration; message is shown to the user as-is."""


def default_paths() -> list[Path]:
    paths = []
    if os.environ.get("CRUNCHOMETER_CONFIG"):
        paths.append(Path(os.environ["CRUNCHOMETER_CONFIG"]))
    paths.append(Path.home() / ".config" / "crunch-o-meter" / "config.toml")
    paths.append(Path("/etc/crunch-o-meter/config.toml"))
    return paths


PLACEHOLDER_TOKENS = {"PASTE_A_TOKEN_HERE", "changeme", "CHANGE_ME"}


@dataclass
class BoincConfig:
    host: str = "127.0.0.1"
    port: int = 31416
    # Password straight in the config, or read from a file (Arch default below).
    password: str | None = None
    password_file: str = "/var/lib/boinc/gui_rpc_auth.cfg"
    poll_seconds: float = 10.0
    timeout_seconds: float = 5.0


@dataclass
class GridcoinConfig:
    # Local wallet JSON-RPC. Needs server=1, rpcuser, rpcpassword in gridcoinresearch.conf.
    enabled: bool = True
    rpc_url: str = "http://127.0.0.1:15715/"
    rpc_user: str | None = None
    rpc_password: str | None = None
    # If user/password are unset they are read from this file.
    conf_file: str = str(Path.home() / ".GridcoinResearch" / "gridcoinresearch.conf")
    poll_seconds: float = 300.0
    # Fallback estimate: magnitude per point of RAC, keyed by BOINC project master URL.
    # See README "Gridcoin estimate" for how to fill these in.
    mag_per_rac: dict[str, float] = field(default_factory=dict)


@dataclass
class SystemConfig:
    poll_seconds: float = 2.0
    history_samples: int = 150  # 150 x 2 s = 5 minutes of sparkline


@dataclass
class LactConfig:
    enabled: bool = True
    socket_path: str = "/run/lactd.sock"
    config_file: str = "/etc/lact/config.yaml"
    poll_seconds: float = 30.0


@dataclass
class ServerConfig:
    # 127.0.0.1 + `tailscale serve` is the recommended setup (HTTPS, tailnet only).
    # Alternatively set this to the host's Tailscale IP (100.x.y.z).
    bind_host: str = "127.0.0.1"
    port: int = 8765
    token: str = ""


@dataclass
class Config:
    server: ServerConfig = field(default_factory=ServerConfig)
    boinc: BoincConfig = field(default_factory=BoincConfig)
    gridcoin: GridcoinConfig = field(default_factory=GridcoinConfig)
    system: SystemConfig = field(default_factory=SystemConfig)
    lact: LactConfig = field(default_factory=LactConfig)
    source_path: Path | None = None


def _apply(dc, values: dict):
    for key, value in values.items():
        if not hasattr(dc, key):
            section = type(dc).__name__.removesuffix("Config").lower()
            raise ConfigError(f"Unknown config key [{section}] {key}")
        setattr(dc, key, value)


def load_config(path: str | Path | None = None) -> Config:
    candidates = [Path(path)] if path else default_paths()
    cfg = Config()
    for candidate in candidates:
        if candidate.is_file():
            try:
                with candidate.open("rb") as fh:
                    raw = tomllib.load(fh)
            except tomllib.TOMLDecodeError as exc:
                raise ConfigError(f"{candidate}: {exc}") from exc
            for section in ("server", "boinc", "gridcoin", "system", "lact"):
                if section in raw:
                    _apply(getattr(cfg, section), raw[section])
            cfg.source_path = candidate
            break
    else:
        if path:
            raise ConfigError(f"Config file not found: {path}")

    env_token = os.environ.get("CRUNCHOMETER_TOKEN")
    if env_token:
        cfg.server.token = env_token
    token = cfg.server.token.strip()
    if token in PLACEHOLDER_TOKENS or len(token) < 24:
        where = cfg.source_path or "~/.config/crunch-o-meter/config.toml"
        raise ConfigError(
            f"Set server.token in {where} (24+ characters). "
            "Generate one with: crunch-o-meter gen-token"
        )
    cfg.server.token = token
    if cfg.server.bind_host in ("0.0.0.0", "::"):
        log.warning("bind_host is %s: the API is reachable on every interface, not just Tailscale",
                    cfg.server.bind_host)
    return cfg
