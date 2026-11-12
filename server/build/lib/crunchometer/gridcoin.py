"""Gridcoin magnitude.

1. Preferred: ask the wallet running on this same host over its local JSON-RPC
   (never exposed off the box; the phone only talks to this server).
2. Fallback: estimate from BOINC per-project RAC x a configured magnitude-per-RAC
   factor for each project. Clearly labelled as an estimate.
"""

from __future__ import annotations

import base64
import json
import urllib.request
from pathlib import Path

from .config import GridcoinConfig


class GridcoinError(RuntimeError):
    pass


def _read_conf(path: str) -> dict[str, str]:
    out: dict[str, str] = {}
    p = Path(path).expanduser()
    if not p.is_file():
        return out
    for line in p.read_text(errors="replace").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip()
    return out


def _find_magnitude(obj, path: str = "") -> tuple[str, float] | None:
    """Wallet output shapes differ between releases, so search for a numeric
    field whose key mentions magnitude (but not 'unit' or 'network' totals)."""
    if isinstance(obj, dict):
        # Prefer the exact/obvious keys first.
        for key, val in obj.items():
            k = key.lower().replace(" ", "_")
            if k in ("magnitude", "current_magnitude", "my_magnitude") and isinstance(val, (int, float)):
                return f"{path}{key}", float(val)
        for key, val in obj.items():
            k = key.lower()
            if "magnitude" in k and not any(bad in k for bad in ("unit", "network", "total", "average")):
                if isinstance(val, (int, float)) and not isinstance(val, bool):
                    return f"{path}{key}", float(val)
        for key, val in obj.items():
            hit = _find_magnitude(val, f"{path}{key}.")
            if hit:
                return hit
    elif isinstance(obj, list):
        for i, val in enumerate(obj):
            hit = _find_magnitude(val, f"{path}[{i}].")
            if hit:
                return hit
    return None


def _find_cpid(obj) -> str | None:
    if isinstance(obj, dict):
        for key, val in obj.items():
            if key.lower() == "cpid" and isinstance(val, str) and len(val) == 32:
                return val
        for val in obj.values():
            hit = _find_cpid(val)
            if hit:
                return hit
    elif isinstance(obj, list):
        for val in obj:
            hit = _find_cpid(val)
            if hit:
                return hit
    return None


def _num(v) -> float | None:
    if isinstance(v, bool):
        return None
    if isinstance(v, (int, float)):
        return float(v)
    if isinstance(v, str):
        try:
            return float(v.replace("GRC", "").strip())
        except ValueError:
            return None
    return None


def _find_pending_reward(obj) -> tuple[str, float] | None:
    """Accrued-but-unpaid research reward. Older wallets call it BoincRewardPending;
    newer ones word it differently, so match on 'pending'/'accru' plus a reward-ish word."""
    if isinstance(obj, dict):
        for key, val in obj.items():
            k = key.lower().replace(" ", "_")
            if ("pending" in k or "accru" in k) and any(w in k for w in ("reward", "research", "boinc", "accru")):
                n = _num(val)
                if n is not None:
                    return key, n
        for key, val in obj.items():
            hit = _find_pending_reward(val)
            if hit:
                return hit
    return None


class GridcoinClient:
    def __init__(self, cfg: GridcoinConfig):
        self.cfg = cfg

    def _call(self, method: str, params: list | None = None):
        conf = _read_conf(self.cfg.conf_file)
        user = self.cfg.rpc_user or conf.get("rpcuser")
        password = self.cfg.rpc_password or conf.get("rpcpassword")
        if not user or not password:
            raise GridcoinError(
                "No wallet RPC credentials (set rpcuser/rpcpassword and server=1 "
                "in gridcoinresearch.conf, or gridcoin.rpc_user/rpc_password)"
            )
        body = json.dumps({"jsonrpc": "1.0", "id": "crunch-o-meter",
                           "method": method, "params": params or []}).encode()
        req = urllib.request.Request(self.cfg.rpc_url, data=body, method="POST")
        token = base64.b64encode(f"{user}:{password}".encode()).decode()
        req.add_header("Authorization", f"Basic {token}")
        req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                payload = json.loads(resp.read())
        except urllib.error.HTTPError as exc:
            # Bitcoin-derived wallets return JSON errors with HTTP 500.
            try:
                payload = json.loads(exc.read())
            except Exception:
                raise GridcoinError(f"Wallet RPC HTTP {exc.code}") from exc
        except OSError as exc:
            raise GridcoinError(f"Wallet RPC unreachable: {exc}") from exc
        if payload.get("error"):
            raise GridcoinError(f"Wallet RPC {method}: {payload['error'].get('message')}")
        return payload.get("result")

    def from_wallet(self) -> dict:
        errors = []
        cpid = None
        for method in ("magnitude", "getmininginfo"):
            try:
                result = self._call(method)
            except GridcoinError as exc:
                errors.append(str(exc))
                continue
            cpid = cpid or _find_cpid(result)
            hit = _find_magnitude(result)
            if hit:
                field, value = hit
                return {"source": "wallet", "magnitude": value, "cpid": cpid,
                        "note": f"From wallet RPC '{method}' field '{field}'."}
        raise GridcoinError("; ".join(errors) or "Wallet returned no magnitude field")

    def wallet_info(self) -> dict:
        """Balance and rewards. Each call is separate so one unsupported RPC doesn't hide the rest."""
        info: dict = {"balance": None, "staked": None, "immature": None, "unconfirmed": None,
                      "pending_research_reward": None, "researcher_status": None}
        try:
            w = self._call("getwalletinfo") or {}
            info["balance"] = _num(w.get("balance"))
            info["staked"] = _num(w.get("stake"))
            info["immature"] = _num(w.get("newmint"))
        except GridcoinError:
            try:
                info["balance"] = _num(self._call("getbalance"))
            except GridcoinError:
                pass
        try:
            info["unconfirmed"] = _num(self._call("getunconfirmedbalance"))
        except GridcoinError:
            pass
        try:
            m = self._call("getmininginfo") or {}
            hit = _find_pending_reward(m)
            if hit:
                info["pending_research_reward"] = hit[1]
            status = m.get("researcher_status") if isinstance(m, dict) else None
            info["researcher_status"] = status if isinstance(status, str) else None
        except GridcoinError:
            pass
        if all(v is None for v in info.values()):
            raise GridcoinError("wallet returned no balance information")
        return info

    def estimate(self, boinc_projects: list[dict]) -> dict:
        rows, total = [], 0.0
        for proj in boinc_projects:
            factor = self.cfg.mag_per_rac.get(proj["url"])
            if factor is None:
                # tolerate missing trailing slash
                factor = self.cfg.mag_per_rac.get(proj["url"].rstrip("/"))
            mag = proj["user_rac"] * factor if factor is not None else None
            if mag is not None:
                total += mag
            rows.append({"name": proj["name"], "url": proj["url"], "rac": proj["user_rac"],
                         "mag_per_rac": factor, "magnitude": mag})
        configured = sum(1 for r in rows if r["mag_per_rac"] is not None)
        note = ("Estimate: your RAC per project x magnitude-per-RAC factors from config. "
                f"{configured}/{len(rows)} projects have a factor.")
        return {"source": "estimate" if configured else "none",
                "magnitude": round(total, 2) if configured else None,
                "cpid": None, "per_project": rows, "note": note}

    def snapshot(self, boinc_projects: list[dict]) -> dict:
        est = self.estimate(boinc_projects)
        if not self.cfg.enabled:
            return est
        try:
            wallet_info = self.wallet_info()
        except GridcoinError:
            wallet_info = None
        try:
            result = self.from_wallet()
            result["per_project"] = est["per_project"]
            result["estimate"] = est["magnitude"]
        except GridcoinError as exc:
            result = est
            result["wallet_error"] = str(exc)
        result["wallet"] = wallet_info
        return result
