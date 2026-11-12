"""CPU (psutil) and NVIDIA GPU (NVML) stats."""

from __future__ import annotations

import os
import platform
from collections import deque

import psutil

try:
    import pynvml  # provided by the `nvidia-ml-py` package
except ImportError:  # pragma: no cover - lets the server run on a box without NVIDIA
    pynvml = None


def _cpu_model() -> str:
    try:
        with open("/proc/cpuinfo") as fh:
            for line in fh:
                if line.startswith("model name"):
                    return line.split(":", 1)[1].strip()
    except OSError:
        pass
    return platform.processor() or "CPU"


def _cpu_temp() -> float | None:
    try:
        temps = psutil.sensors_temperatures()
    except (AttributeError, OSError):
        return None
    # AMD: k10temp Tctl/Tdie. Intel: coretemp "Package id 0".
    for chip, wanted in (("k10temp", ("Tctl", "Tdie")), ("coretemp", ("Package id 0",)),
                         ("zenpower", ("Tdie", "Tctl"))):
        for entry in temps.get(chip, []):
            if entry.label in wanted:
                return entry.current
    for entries in temps.values():
        if entries:
            return entries[0].current
    return None


class SystemSampler:
    def __init__(self, history_samples: int):
        self.cpu_history: deque[float] = deque(maxlen=history_samples)
        self.gpu_history: dict[int, deque[float]] = {}
        self.history_samples = history_samples
        self.model = _cpu_model()
        self.nvml_error: str | None = None
        psutil.cpu_percent(percpu=True)  # prime the counters
        if pynvml is None:
            self.nvml_error = "nvidia-ml-py not installed"
        else:
            try:
                pynvml.nvmlInit()
            except Exception as exc:  # NVMLError subclasses
                self.nvml_error = f"NVML init failed: {exc}"

    def close(self) -> None:
        if pynvml is not None and self.nvml_error is None:
            try:
                pynvml.nvmlShutdown()
            except Exception:
                pass

    def _gpus(self) -> list[dict]:
        if self.nvml_error:
            return []
        gpus = []
        for i in range(pynvml.nvmlDeviceGetCount()):
            h = pynvml.nvmlDeviceGetHandleByIndex(i)

            def safe(fn, *args, default=None):
                try:
                    return fn(h, *args)
                except Exception:
                    return default

            util = safe(pynvml.nvmlDeviceGetUtilizationRates)
            mem = safe(pynvml.nvmlDeviceGetMemoryInfo)
            power = safe(pynvml.nvmlDeviceGetPowerUsage)  # milliwatts
            limit = safe(pynvml.nvmlDeviceGetEnforcedPowerLimit)
            name = safe(pynvml.nvmlDeviceGetName, default="NVIDIA GPU")
            if isinstance(name, bytes):
                name = name.decode()
            gpu_util = float(util.gpu) if util else None
            gpus.append({
                "index": i,
                "name": name,
                "util_percent": gpu_util,
                "mem_util_percent": float(util.memory) if util else None,
                "mem_used_bytes": mem.used if mem else None,
                "mem_total_bytes": mem.total if mem else None,
                "temp_c": safe(pynvml.nvmlDeviceGetTemperature, pynvml.NVML_TEMPERATURE_GPU),
                "power_w": round(power / 1000, 1) if power is not None else None,
                "power_limit_w": round(limit / 1000, 1) if limit is not None else None,
                "core_clock_mhz": safe(pynvml.nvmlDeviceGetClockInfo, pynvml.NVML_CLOCK_GRAPHICS),
                "mem_clock_mhz": safe(pynvml.nvmlDeviceGetClockInfo, pynvml.NVML_CLOCK_MEM),
                "max_core_clock_mhz": safe(pynvml.nvmlDeviceGetMaxClockInfo, pynvml.NVML_CLOCK_GRAPHICS),
                "max_mem_clock_mhz": safe(pynvml.nvmlDeviceGetMaxClockInfo, pynvml.NVML_CLOCK_MEM),
                "fan_percent": safe(pynvml.nvmlDeviceGetFanSpeed),
                "pstate": safe(pynvml.nvmlDeviceGetPerformanceState),
            })
            hist = self.gpu_history.setdefault(i, deque(maxlen=self.history_samples))
            if gpu_util is not None:
                hist.append(gpu_util)
        return gpus

    def sample(self) -> dict:
        per_core = psutil.cpu_percent(percpu=True)
        usage = sum(per_core) / len(per_core) if per_core else 0.0
        self.cpu_history.append(round(usage, 1))
        freq = psutil.cpu_freq()
        vm = psutil.virtual_memory()
        gpus = self._gpus()
        return {
            "hostname": platform.node(),
            "cpu": {
                "model": self.model,
                "usage_percent": round(usage, 1),
                "per_core": [round(c, 1) for c in per_core],
                "freq_mhz": round(freq.current) if freq else None,
                "temp_c": _cpu_temp(),
                "load_avg": list(os.getloadavg()),
                "mem_used_bytes": vm.total - vm.available,
                "mem_total_bytes": vm.total,
            },
            "gpus": gpus,
            "gpu_error": self.nvml_error,
            "history": {
                "cpu": list(self.cpu_history),
                "gpu": [list(self.gpu_history.get(g["index"], [])) for g in gpus],
            },
        }
