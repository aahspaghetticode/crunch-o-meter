"""Gridcoin earning rate from per-minute samples of your total holdings.

Total = balance + coins locked in a stake + maturing rewards + unconfirmed
        + pending research reward.

When you stake a block, the pending research reward moves into your balance, so
the total stays continuous and only grows by what you actually earn. Coins you
send or receive show up as a sudden jump between two samples; steps bigger than
`jump_threshold` are left out of the rate so a transfer doesn't read as earnings.

Samples are saved to a small JSON file so the rate survives a server restart.
"""

from __future__ import annotations

import json
import logging
import os
import threading
import time
from pathlib import Path

log = logging.getLogger("crunchometer")

TOTAL_FIELDS = ("balance", "staked", "immature", "unconfirmed", "pending_research_reward")

# Don't report a rate until there's at least this much history.
MIN_SPAN_SECONDS = 10 * 60
# Save to disk at most this often.
SAVE_EVERY_SECONDS = 10 * 60


def default_state_file() -> str:
    base = os.environ.get("XDG_STATE_HOME") or str(Path.home() / ".local" / "state")
    return str(Path(base) / "crunch-o-meter" / "gridcoin_samples.json")


def wallet_total(info: dict | None) -> float | None:
    """Sum of everything you hold or are owed. None if the wallet gave no balance."""
    if not info or info.get("balance") is None:
        return None
    return sum(info.get(f) or 0.0 for f in TOTAL_FIELDS)


class EarningsTracker:
    def __init__(self, state_file: str, window_hours: float, jump_threshold: float):
        self.path = Path(state_file).expanduser()
        self.window = window_hours * 3600
        # Keep twice the window so the rate can always use a full window.
        self.keep = self.window * 2
        self.jump_threshold = jump_threshold
        self.samples: list[tuple[float, float]] = []
        self._lock = threading.Lock()
        self._last_save = 0.0
        self._load()

    def _load(self) -> None:
        try:
            raw = json.loads(self.path.read_text())
            self.samples = [(float(t), float(v)) for t, v in raw.get("samples", [])]
            self._prune(time.time())
        except FileNotFoundError:
            pass
        except (OSError, ValueError, TypeError) as exc:
            log.warning("Ignoring unreadable %s: %s", self.path, exc)
            self.samples = []

    def _prune(self, now: float) -> None:
        cutoff = now - self.keep
        self.samples = [s for s in self.samples if s[0] >= cutoff]

    def save(self) -> None:
        with self._lock:
            data = {"samples": [[round(t, 1), round(v, 8)] for t, v in self.samples]}
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.path.with_suffix(".tmp")
            tmp.write_text(json.dumps(data))
            tmp.replace(self.path)  # atomic, so a crash never leaves half a file
            self._last_save = time.time()
        except OSError as exc:
            log.warning("Couldn't save %s: %s", self.path, exc)

    def add(self, total: float, now: float | None = None) -> None:
        now = time.time() if now is None else now
        with self._lock:
            self.samples.append((now, total))
            self._prune(now)
        if now - self._last_save >= SAVE_EVERY_SECONDS:
            self.save()

    def rate(self, now: float | None = None) -> dict | None:
        """GRC/hour and GRC/day over the last `window_hours`, or None if too little data."""
        now = time.time() if now is None else now
        with self._lock:
            window = [s for s in self.samples if s[0] >= now - self.window]
        if len(window) < 2:
            return None
        span = window[-1][0] - window[0][0]
        if span < MIN_SPAN_SECONDS:
            return None
        earned, skipped = 0.0, 0
        for (_, a), (_, b) in zip(window, window[1:]):
            step = b - a
            if abs(step) > self.jump_threshold:
                skipped += 1  # a transfer in or out, not earnings
                continue
            earned += step
        per_hour = earned / (span / 3600)
        return {
            "per_hour": round(per_hour, 6),
            "per_day": round(per_hour * 24, 4),
            "window_hours": round(span / 3600, 2),
            "samples": len(window),
            "skipped_jumps": skipped,
        }
