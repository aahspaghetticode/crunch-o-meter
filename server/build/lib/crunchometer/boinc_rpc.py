"""Minimal BOINC GUI RPC client (the same protocol boinccmd and BOINC Manager use).

Protocol: XML requests over TCP port 31416, each message terminated by a 0x03 byte.
Auth is a challenge: auth1 returns a nonce, auth2 sends md5(nonce + password).
"""

from __future__ import annotations

import hashlib
import socket
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from .config import BoincConfig

END = b"\x03"

# RESULT_* states from BOINC's common_defs.h
RESULT_STATES = {
    0: "new",
    1: "downloading",
    2: "ready",
    3: "error",
    4: "uploading",
    5: "uploaded",
    6: "aborted",
    7: "upload failed",
}
# CPU scheduler states for an active task
SCHED_PREEMPTED = 1
SCHED_SCHEDULED = 2


class BoincError(RuntimeError):
    pass


def _flag(el: ET.Element, tag: str) -> bool:
    """BOINC writes booleans as <tag/> or <tag>1</tag>."""
    child = el.find(tag)
    if child is None:
        return False
    text = (child.text or "").strip()
    return text not in ("0", "false")


def _num(el: ET.Element, tag: str, default: float | None = None) -> float | None:
    child = el.find(tag)
    if child is None or child.text is None:
        return default
    try:
        return float(child.text.strip())
    except ValueError:
        return default


def _text(el: ET.Element, tag: str, default: str = "") -> str:
    child = el.find(tag)
    return (child.text or "").strip() if child is not None and child.text else default


class BoincClient:
    def __init__(self, cfg: BoincConfig):
        self.cfg = cfg

    def _password(self) -> str:
        if self.cfg.password is not None:
            return self.cfg.password
        try:
            return Path(self.cfg.password_file).expanduser().read_text().strip()
        except PermissionError as exc:
            raise BoincError(
                f"Can't read {self.cfg.password_file}. Add this user to the 'boinc' group "
                "or set boinc.password in the config."
            ) from exc
        except FileNotFoundError:
            return ""

    # --- transport -------------------------------------------------------
    def _send(self, sock: socket.socket, body: str) -> ET.Element:
        msg = f"<boinc_gui_rpc_request>\n{body}\n</boinc_gui_rpc_request>\n".encode() + END
        sock.sendall(msg)
        buf = bytearray()
        while True:
            chunk = sock.recv(65536)
            if not chunk:
                raise BoincError("BOINC client closed the connection")
            buf += chunk
            if buf.endswith(END):
                break
        raw = bytes(buf[:-1]).decode("utf-8", errors="replace")
        # Some clients emit control characters inside names; strip what XML forbids.
        raw = "".join(ch for ch in raw if ch in "\t\n\r" or ord(ch) >= 0x20)
        try:
            root = ET.fromstring(raw)
        except ET.ParseError as exc:
            raise BoincError(f"Bad XML from BOINC: {exc}") from exc
        if root.find("unauthorized") is not None:
            raise BoincError("BOINC rejected the request (unauthorized)")
        err = root.find("error")
        if err is not None:
            raise BoincError(f"BOINC error: {(err.text or '').strip()}")
        return root

    def _connect(self) -> socket.socket:
        sock = socket.create_connection(
            (self.cfg.host, self.cfg.port), timeout=self.cfg.timeout_seconds
        )
        nonce_reply = self._send(sock, "<auth1/>")
        nonce = _text(nonce_reply, "nonce")
        digest = hashlib.md5((nonce + self._password()).encode()).hexdigest()
        reply = self._send(sock, f"<auth2>\n<nonce_hash>{digest}</nonce_hash>\n</auth2>")
        if reply.find("authorized") is None:
            sock.close()
            raise BoincError("BOINC authentication failed - check the GUI RPC password")
        return sock

    # --- high level ------------------------------------------------------
    def snapshot(self) -> dict:
        with self._connect() as sock:
            projects_xml = self._send(sock, "<get_project_status/>")
            results_xml = self._send(
                sock, "<get_results>\n<active_only>0</active_only>\n</get_results>"
            )
            old_xml = self._send(sock, "<get_old_results/>")

        projects = []
        names: dict[str, str] = {}
        for p in projects_xml.iter("project"):
            url = _text(p, "master_url")
            name = _text(p, "project_name") or url
            names[url] = name
            projects.append(
                {
                    "url": url,
                    "name": name,
                    "user_total_credit": _num(p, "user_total_credit", 0.0),
                    "user_rac": _num(p, "user_expavg_credit", 0.0),
                    "host_total_credit": _num(p, "host_total_credit", 0.0),
                    "host_rac": _num(p, "host_expavg_credit", 0.0),
                    "suspended": _flag(p, "suspended_via_gui"),
                    "no_new_work": _flag(p, "dont_request_more_work"),
                }
            )

        active, completed = [], []
        counts = {"running": 0, "waiting": 0, "suspended": 0, "transferring": 0,
                  "ready_to_report": 0, "errors": 0}
        now = time.time()
        for r in results_xml.iter("result"):
            url = _text(r, "project_url")
            state = int(_num(r, "state", 0) or 0)
            at = r.find("active_task")
            base = {
                "name": _text(r, "name"),
                "project": names.get(url, url),
                "project_url": url,
                "resources": _text(r, "resources") or None,
                "deadline": _num(r, "report_deadline"),
            }

            if _flag(r, "ready_to_report") or state in (3, 5, 6):
                status = {3: "error", 6: "aborted"}.get(state, "ready to report")
                counts["errors" if state in (3, 6) else "ready_to_report"] += 1
                completed.append({
                    **base,
                    "app": None,
                    "status": status,
                    "exit_status": int(_num(r, "exit_status", 0) or 0),
                    "elapsed_s": _num(r, "final_elapsed_time"),
                    "cpu_s": _num(r, "final_cpu_time"),
                    "completed_at": _num(r, "completed_time"),
                })
                continue

            progress = 0.0
            elapsed = None
            if at is not None:
                progress = (_num(at, "fraction_done", 0.0) or 0.0) * 100.0
                elapsed = _num(at, "elapsed_time")
                sched = int(_num(at, "scheduler_state", 0) or 0)
                if _flag(r, "suspended_via_gui"):
                    status = "suspended"
                elif sched == SCHED_SCHEDULED:
                    status = "running"
                else:
                    status = "preempted"
            elif _flag(r, "suspended_via_gui"):
                status = "suspended"
            elif state in (1, 4):
                status = RESULT_STATES[state]
            else:
                status = "waiting to run"

            if status == "running":
                counts["running"] += 1
            elif status in ("downloading", "uploading"):
                counts["transferring"] += 1
            elif status == "suspended":
                counts["suspended"] += 1
            else:
                counts["waiting"] += 1

            remaining = _num(r, "estimated_cpu_time_remaining")
            active.append({
                **base,
                "status": status,
                "progress_percent": round(min(max(progress, 0.0), 100.0), 3),
                "elapsed_s": elapsed,
                "remaining_s": remaining,
                "overdue": bool(base["deadline"] and base["deadline"] < now),
            })

        for o in old_xml.iter("old_result"):
            url = _text(o, "project_url")
            exit_status = int(_num(o, "exit_status", 0) or 0)
            completed.append({
                "name": _text(o, "result_name"),
                "project": names.get(url, url),
                "project_url": url,
                "resources": None,
                "deadline": None,
                "app": _text(o, "app_name") or None,
                "status": "reported" if exit_status == 0 else "error",
                "exit_status": exit_status,
                "elapsed_s": _num(o, "elapsed_time"),
                "cpu_s": _num(o, "cpu_time"),
                "completed_at": _num(o, "completed_time"),
            })

        # running first, then by progress
        order = {"running": 0, "preempted": 1, "waiting to run": 2}
        active.sort(key=lambda t: (order.get(t["status"], 3), -t["progress_percent"]))
        completed.sort(key=lambda t: t["completed_at"] or 0, reverse=True)
        return {"projects": projects, "active": active, "completed": completed, "counts": counts}
