"""LACT settings, read-only.

Nothing here can change GPU settings: the daemon socket is only sent 'ping' and
'list_devices', and the overclock values come from parsing LACT's config file.
"""

from __future__ import annotations

import json
import socket
from pathlib import Path

import yaml

from .config import LactConfig

# Shown first, in this order, when present. Everything else follows alphabetically.
PRIORITY = [
    "performance_level", "power_cap", "power_profile_mode_index",
    "clocks_configuration.gpu_clock_offsets", "clocks_configuration.mem_clock_offsets",
    "clocks_configuration.max_core_clock", "clocks_configuration.min_core_clock",
    "clocks_configuration.max_memory_clock", "clocks_configuration.voltage_offset",
    "fan_control_enabled", "fan_control_settings.mode", "fan_control_settings.static_speed",
]


def _flatten(obj, prefix: str = "") -> list[tuple[str, str]]:
    out = []
    if isinstance(obj, dict):
        # Offset maps like {0: 150} read better as one value than as keys.
        if obj and all(isinstance(k, (int, float)) for k in obj) and not any(isinstance(v, (dict, list)) for v in obj.values()):
            if "offset" in prefix:  # per-pstate clock offsets, e.g. P0: +150
                text = ", ".join(f"P{k}: {v:+}" if isinstance(v, (int, float)) else f"P{k}: {v}"
                                 for k, v in sorted(obj.items()))
            else:  # e.g. fan curve temp -> speed
                text = ", ".join(f"{k}: {v}" for k, v in sorted(obj.items()))
            out.append((prefix.rstrip("."), text))
            return out
        for k, v in obj.items():
            out.extend(_flatten(v, f"{prefix}{k}."))
    elif isinstance(obj, list):
        if all(not isinstance(v, (dict, list)) for v in obj):
            out.append((prefix.rstrip("."), ", ".join(map(str, obj))))
        else:
            for i, v in enumerate(obj):
                out.extend(_flatten(v, f"{prefix}{i}."))
    else:
        out.append((prefix.rstrip("."), "—" if obj is None else str(obj)))
    return out


def _sorted_settings(settings: dict) -> list[dict]:
    flat = _flatten(settings)
    rank = {k: i for i, k in enumerate(PRIORITY)}
    flat.sort(key=lambda kv: (rank.get(kv[0], len(PRIORITY)), kv[0]))
    return [{"key": k, "value": v} for k, v in flat]


class LactReader:
    def __init__(self, cfg: LactConfig):
        self.cfg = cfg

    def _rpc(self, command: str) -> dict | None:
        """Send one read-only command; returns parsed JSON or None if unreachable."""
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as s:
            s.settimeout(3)
            s.connect(self.cfg.socket_path)
            s.sendall(json.dumps({"command": command}).encode() + b"\n")
            buf = b""
            while not buf.endswith(b"\n"):
                chunk = s.recv(65536)
                if not chunk:
                    break
                buf += chunk
        return json.loads(buf) if buf.strip() else None

    def snapshot(self) -> dict:
        daemon_ok, daemon_error, names = False, None, {}
        try:
            self._rpc("ping")
            daemon_ok = True
            devices = self._rpc("list_devices") or {}
            for dev in devices.get("data") or []:
                if isinstance(dev, dict) and "id" in dev:
                    names[dev["id"]] = dev.get("name") or dev["id"]
        except FileNotFoundError:
            daemon_error = f"{self.cfg.socket_path} not found (is lactd running?)"
        except PermissionError:
            daemon_error = (f"No permission for {self.cfg.socket_path}; add this user to a "
                            "group in LACT's admin_groups")
        except (OSError, ValueError) as exc:
            daemon_error = f"LACT daemon: {exc}"

        path = Path(self.cfg.config_file).expanduser()
        if not path.is_file():
            raise FileNotFoundError(f"{path} not found")
        raw = yaml.safe_load(path.read_text()) or {}

        # Newer LACT keeps per-profile GPU settings; show the active profile's if set.
        current_profile = raw.get("current_profile")
        gpu_section = raw.get("gpus") or {}
        if current_profile and isinstance(raw.get("profiles"), dict):
            profile = raw["profiles"].get(current_profile) or {}
            gpu_section = profile.get("gpus") or gpu_section

        gpus = []
        for gpu_id, settings in gpu_section.items():
            gpus.append({
                "id": str(gpu_id),
                "name": names.get(gpu_id) or str(gpu_id),
                "settings": _sorted_settings(settings or {}),
            })
        return {
            "daemon_reachable": daemon_ok,
            "daemon_error": daemon_error,
            "current_profile": current_profile,
            "gpus": gpus,
        }
