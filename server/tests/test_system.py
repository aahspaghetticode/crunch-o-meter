"""AMD GPU stats from a fake amdgpu sysfs tree."""
import pytest

from crunchometer import system
from crunchometer.system import SystemSampler

from . import fakes


@pytest.fixture
def pci_ids(tmp_path):
    p = tmp_path / "pci.ids"
    p.write_text(fakes.PCI_IDS)
    return str(p)


@pytest.fixture
def no_nvml(monkeypatch):
    monkeypatch.setattr(system, "pynvml", None)


def test_amd_gpu_fields(tmp_path, pci_ids, no_nvml):
    fakes.make_amd_card(tmp_path / "sys", 1)
    s = SystemSampler(10, sysfs_root=tmp_path / "sys", pci_ids=pci_ids).sample()
    assert s["gpu_error"] is None  # AMD-only box: no "nvidia-ml-py not installed"
    (g,) = s["gpus"]
    assert g == {
        "index": 0,
        "name": "AMD Radeon RX 7900 XT/7900 XTX/7900 GRE/7900M",
        "vendor": "amd",
        "util_percent": 97.0,
        "mem_util_percent": 41.0,
        "mem_used_bytes": 6 * 2**30,
        "mem_total_bytes": 24 * 2**30,
        "temp_c": 61.0,
        "hotspot_temp_c": 78.5,
        "power_w": 301.2,
        "power_limit_w": 339.0,
        "core_clock_mhz": 2431,
        "mem_clock_mhz": 1249,
        "max_core_clock_mhz": 2500,
        "max_mem_clock_mhz": 1250,
        "fan_percent": 50,
        "pstate": None,
    }
    assert s["history"]["gpu"] == [[97.0]]


def test_power_average_preferred(tmp_path, pci_ids, no_nvml):
    fakes.make_amd_card(tmp_path, 0)
    (tmp_path / "class/drm/card0/device/hwmon/hwmon3/power1_average").write_text("150000000\n")
    (g,) = SystemSampler(10, sysfs_root=tmp_path, pci_ids=pci_ids).sample()["gpus"]
    assert g["power_w"] == 150.0


def test_sparse_card_and_names(tmp_path, pci_ids, no_nvml):
    # Ordered by card number, connectors and other vendors skipped, names from pci.ids.
    fakes.make_amd_card(tmp_path, 10, device="0x7480", full=False)  # no brackets in pci.ids
    fakes.make_amd_card(tmp_path, 2, device="0x73bf", full=False)
    fakes.make_amd_card(tmp_path, 3, device="0x1234", full=False)  # not in pci.ids
    fakes.make_amd_card(tmp_path, 0, vendor="0x8086", full=False)  # Intel iGPU
    gpus = SystemSampler(10, sysfs_root=tmp_path, pci_ids=pci_ids).sample()["gpus"]
    assert [g["name"] for g in gpus] == [
        "AMD Radeon RX 6800/6800 XT / 6900 XT", "AMD GPU (0x1234)", "AMD Navi 33"]
    assert [g["index"] for g in gpus] == [0, 1, 2]
    assert all(g["util_percent"] is None and g["temp_c"] is None for g in gpus)


def test_missing_pci_ids(tmp_path, no_nvml):
    fakes.make_amd_card(tmp_path, 0, full=False)
    (g,) = SystemSampler(10, sysfs_root=tmp_path, pci_ids=str(tmp_path / "nope")).sample()["gpus"]
    assert g["name"] == "AMD GPU (0x744c)"


def test_no_gpu_at_all(tmp_path, no_nvml):
    s = SystemSampler(10, sysfs_root=tmp_path).sample()
    assert s["gpus"] == [] and s["gpu_error"] == "nvidia-ml-py not installed"


class _FakeNvml:
    """Just enough of pynvml for one GPU, to check AMD indices follow NVIDIA's."""
    NVML_TEMPERATURE_GPU = NVML_CLOCK_GRAPHICS = NVML_CLOCK_MEM = 0

    class _Util:
        gpu, memory = 55, 20

    def __getattr__(self, name):
        return lambda *a: None  # every other query: not supported

    def nvmlInit(self): pass
    def nvmlDeviceGetCount(self): return 1
    def nvmlDeviceGetHandleByIndex(self, i): return i
    def nvmlDeviceGetName(self, h): return b"NVIDIA GeForce RTX 4090"
    def nvmlDeviceGetUtilizationRates(self, h): return self._Util()


def test_amd_after_nvidia(tmp_path, pci_ids, monkeypatch):
    monkeypatch.setattr(system, "pynvml", _FakeNvml())
    fakes.make_amd_card(tmp_path, 1)
    s = SystemSampler(10, sysfs_root=tmp_path, pci_ids=pci_ids).sample()
    assert [(g["index"], g["vendor"]) for g in s["gpus"]] == [(0, "nvidia"), (1, "amd")]
    assert s["gpus"][0]["hotspot_temp_c"] is None
    assert s["history"]["gpu"] == [[55.0], [97.0]]
    assert s["gpu_error"] is None
