"""CPU (psutil), NVIDIA GPU (NVML) and AMD GPU (amdgpu sysfs) stats."""

from __future__ import annotations

import os
import platform
import re
from collections import deque
from pathlib import Path

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


AMD_VENDOR = "0x1002"
PCI_IDS = "/usr/share/hwdata/pci.ids"


def _amd_name(device_id: str, pci_ids: str) -> str:
    """Marketing name from pci.ids: "Navi 31 [Radeon RX 7900 XT/...]" -> "AMD Radeon RX 7900 XT/..."."""
    want = device_id.lower().removeprefix("0x")
    try:
        with open(pci_ids, encoding="utf-8", errors="replace") as fh:
            in_amd = False
            for line in fh:
                if line.startswith("#") or not line.strip():
                    continue
                if not line.startswith("\t"):
                    if in_amd:
                        break  # past the AMD vendor block
                    in_amd = line.startswith("1002 ")
                elif in_amd and not line.startswith("\t\t") and line[1:5] == want:
                    name = line[5:].strip()
                    bracket = re.search(r"\[(.+)\]", name)
                    return f"AMD {bracket.group(1)}" if bracket else f"AMD {name}"
    except OSError:
        pass
    return f"AMD GPU (0x{want})"


def _read(path: Path) -> str | None:
    try:
        return path.read_text().strip()
    except OSError:  # missing file, or the driver refuses while the GPU is suspended
        return None


def _read_int(path: Path) -> int | None:
    text = _read(path)
    try:
        return int(text) if text is not None else None
    except ValueError:
        return None


def _max_dpm_mhz(path: Path) -> int | None:
    """Top level of pp_dpm_sclk / pp_dpm_mclk, whose lines look like "2: 2500Mhz *"."""
    text = _read(path)
    levels = re.findall(r"(\d+)\s*mhz", text or "", re.IGNORECASE)
    return int(levels[-1]) if levels else None


class AmdGpu:
    """One amdgpu card under <sysfs>/class/drm/cardN/device."""

    def __init__(self, device: Path, pci_ids: str):
        self.device = device
        self.name = _amd_name(_read(device / "device") or "0x0000", pci_ids)

    def _hwmon(self) -> Path | None:
        # The hwmonN number can change across driver reloads, so look it up each time.
        found = sorted((self.device / "hwmon").glob("hwmon*"))
        return found[0] if found else None

    def sample(self) -> dict:
        d = self.device
        hw = self._hwmon()
        temps: dict[str, float] = {}
        power = power_cap = fan = core_hz = mem_hz = None
        if hw is not None:
            for label_file in hw.glob("temp*_label"):
                milli = _read_int(hw / label_file.name.replace("_label", "_input"))
                if milli is not None:
                    temps[_read(label_file) or ""] = milli / 1000
            # freq1 is sclk (core), freq2 is mclk (memory). Hz.
            core_hz, mem_hz = _read_int(hw / "freq1_input"), _read_int(hw / "freq2_input")
            # Older cards report power1_average, RDNA3 and newer only power1_input. Microwatts.
            power = _read_int(hw / "power1_average")
            if power is None:
                power = _read_int(hw / "power1_input")
            power_cap = _read_int(hw / "power1_cap")
            pwm, pwm_max = _read_int(hw / "pwm1"), _read_int(hw / "pwm1_max")
            if pwm is not None and pwm_max:
                fan = round(pwm * 100 / pwm_max)
        busy = _read_int(d / "gpu_busy_percent")
        mem_busy = _read_int(d / "mem_busy_percent")
        return {
            "name": self.name,
            "vendor": "amd",
            "util_percent": float(busy) if busy is not None else None,
            "mem_util_percent": float(mem_busy) if mem_busy is not None else None,
            "mem_used_bytes": _read_int(d / "mem_info_vram_used"),
            "mem_total_bytes": _read_int(d / "mem_info_vram_total"),
            "temp_c": temps.get("edge"),
            "hotspot_temp_c": temps.get("junction"),
            "power_w": round(power / 1_000_000, 1) if power is not None else None,
            "power_limit_w": round(power_cap / 1_000_000, 1) if power_cap is not None else None,
            "core_clock_mhz": round(core_hz / 1_000_000) if core_hz is not None else None,
            "mem_clock_mhz": round(mem_hz / 1_000_000) if mem_hz is not None else None,
            "max_core_clock_mhz": _max_dpm_mhz(d / "pp_dpm_sclk"),
            "max_mem_clock_mhz": _max_dpm_mhz(d / "pp_dpm_mclk"),
            "fan_percent": fan,
            "pstate": None,
        }


def find_amd_gpus(sysfs_root: str | Path, pci_ids: str) -> list[AmdGpu]:
    cards = []
    for card in Path(sysfs_root, "class/drm").glob("card*"):
        # Skip connector entries such as card0-DP-1; only cardN is a GPU.
        if not re.fullmatch(r"card\d+", card.name):
            continue
        if _read(card / "device/vendor") == AMD_VENDOR:
            cards.append(card)
    cards.sort(key=lambda c: int(c.name[4:]))
    return [AmdGpu(c / "device", pci_ids) for c in cards]


class SystemSampler:
    def __init__(self, history_samples: int, sysfs_root: str | Path = "/sys", pci_ids: str = PCI_IDS):
        self.cpu_history: deque[float] = deque(maxlen=history_samples)
        self.gpu_history: dict[int, deque[float]] = {}
        self.history_samples = history_samples
        self.model = _cpu_model()
        self.nvml_error: str | None = None
        self.amd_gpus = find_amd_gpus(sysfs_root, pci_ids)
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

    def _nvidia_gpus(self) -> list[dict]:
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
                "vendor": "nvidia",
                "util_percent": gpu_util,
                "mem_util_percent": float(util.memory) if util else None,
                "mem_used_bytes": mem.used if mem else None,
                "mem_total_bytes": mem.total if mem else None,
                "temp_c": safe(pynvml.nvmlDeviceGetTemperature, pynvml.NVML_TEMPERATURE_GPU),
                "hotspot_temp_c": None,  # NVML doesn't expose it on consumer cards
                "power_w": round(power / 1000, 1) if power is not None else None,
                "power_limit_w": round(limit / 1000, 1) if limit is not None else None,
                "core_clock_mhz": safe(pynvml.nvmlDeviceGetClockInfo, pynvml.NVML_CLOCK_GRAPHICS),
                "mem_clock_mhz": safe(pynvml.nvmlDeviceGetClockInfo, pynvml.NVML_CLOCK_MEM),
                "max_core_clock_mhz": safe(pynvml.nvmlDeviceGetMaxClockInfo, pynvml.NVML_CLOCK_GRAPHICS),
                "max_mem_clock_mhz": safe(pynvml.nvmlDeviceGetMaxClockInfo, pynvml.NVML_CLOCK_MEM),
                "fan_percent": safe(pynvml.nvmlDeviceGetFanSpeed),
                "pstate": safe(pynvml.nvmlDeviceGetPerformanceState),
            })
        return gpus

    def _gpus(self) -> list[dict]:
        gpus = self._nvidia_gpus()
        # AMD indices continue after NVIDIA's, so history stays keyed by index.
        for amd in self.amd_gpus:
            gpus.append({"index": len(gpus), **amd.sample()})
        for g in gpus:
            hist = self.gpu_history.setdefault(g["index"], deque(maxlen=self.history_samples))
            if g["util_percent"] is not None:
                hist.append(g["util_percent"])
        return gpus

    def gpu_error(self, gpus: list[dict]) -> str | None:
        """Only an error when there's no GPU at all; an AMD-only box doesn't need NVML."""
        if gpus:
            return None
        return self.nvml_error or "no GPU found"

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
            "gpu_error": self.gpu_error(gpus),
            "history": {
                "cpu": list(self.cpu_history),
                "gpu": [list(self.gpu_history.get(g["index"], [])) for g in gpus],
            },
        }
