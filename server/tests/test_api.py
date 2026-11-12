import json
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from crunchometer.app import create_app
from crunchometer.config import ConfigError, load_config

from . import fakes

TOKEN = "test-token-0123456789abcdefgh"
SAMPLE_OUT = Path(__file__).resolve().parents[2] / "android/app/src/test/resources/sample_summary.json"

LACT_YAML = """\
version: 5
daemon:
  log_level: info
  admin_groups: [wheel]
gpus:
  10DE:2684-1043:889D-0000:01:00.0:
    fan_control_enabled: true
    fan_control_settings:
      mode: curve
      static_speed: 0.5
      curve: {40: 0.3, 60: 0.5, 80: 1.0}
    power_cap: 400.0
    clocks_configuration:
      max_core_clock: 2800
      gpu_clock_offsets: {0: 150}
      mem_clock_offsets: {0: 1000}
"""


@pytest.fixture(scope="module")
def client(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("crunch")
    svc = fakes.start_all(tmp)
    (tmp / "lact.yaml").write_text(LACT_YAML)
    (tmp / "gc.conf").write_text("server=1\nrpcuser=u\nrpcpassword=p\n")
    cfg_path = tmp / "config.toml"
    cfg_path.write_text(f"""
[server]
token = "{TOKEN}"
[boinc]
port = {svc['boinc_port']}
password = "{svc['password']}"
poll_seconds = 1
[gridcoin]
rpc_url = "http://127.0.0.1:{svc['wallet_port']}/"
conf_file = "{tmp / 'gc.conf'}"
poll_seconds = 2
[gridcoin.mag_per_rac]
"https://einsteinathome.org/" = 0.002
[system]
poll_seconds = 0.5
[lact]
socket_path = "{svc['lact_socket']}"
config_file = "{tmp / 'lact.yaml'}"
poll_seconds = 1
""")
    app = create_app(load_config(cfg_path))
    with TestClient(app) as c:
        deadline = time.time() + 10
        while time.time() < deadline:
            d = c.get("/api/summary", headers={"Authorization": f"Bearer {TOKEN}"}).json()
            if all(d[k]["data"] for k in ("boinc", "gridcoin", "system", "lact")):
                break
            time.sleep(0.2)
        yield c


def summary(client):
    r = client.get("/api/summary", headers={"Authorization": f"Bearer {TOKEN}"})
    assert r.status_code == 200
    return r.json()


def test_auth_required(client):
    assert client.get("/api/summary").status_code == 401
    assert client.get("/api/summary", headers={"Authorization": "Bearer nope"}).status_code == 401
    assert client.get("/api/health").status_code == 200


def test_all_sections_ok(client):
    d = summary(client)
    for name in ("boinc", "gridcoin", "system", "lact"):
        assert d[name]["ok"], (name, d[name]["error"])


def test_boinc_tasks(client):
    b = summary(client)["boinc"]["data"]
    running = b["active"][0]
    assert running["status"] == "running"
    assert running["progress_percent"] == pytest.approx(42.315)
    assert running["remaining_s"] == pytest.approx(1800.5)
    queued = next(t for t in b["active"] if t["name"] == "MCM1_queued_2")
    assert queued["overdue"] is True
    statuses = {t["name"]: t["status"] for t in b["completed"]}
    assert statuses == {"MCM1_done_3": "ready to report", "old_einstein_9": "reported", "MCM1_bad_4": "error"}
    assert b["counts"]["running"] == 1 and b["counts"]["errors"] == 1


def test_gridcoin_wallet_with_estimate(client):
    g = summary(client)["gridcoin"]["data"]
    assert g["source"] == "wallet"
    assert g["magnitude"] == pytest.approx(187.43)
    assert g["cpid"] == "0123456789abcdef0123456789abcdef"
    assert g["estimate"] == pytest.approx(50.0, abs=0.01)


def test_gridcoin_balance_and_pending(client):
    w = summary(client)["gridcoin"]["data"]["wallet"]
    assert w["balance"] == pytest.approx(1234.5678)
    assert w["staked"] == pytest.approx(2000.0)
    assert w["immature"] == pytest.approx(12.5)
    assert w["unconfirmed"] == pytest.approx(3.25)
    assert w["pending_research_reward"] == pytest.approx(42.1875)
    assert w["researcher_status"] == "Eligible for Research Rewards"


def test_lact_settings_readable(client):
    gpu = summary(client)["lact"]["data"]["gpus"][0]
    assert gpu["name"] == "NVIDIA GeForce RTX 4090"
    settings = {s["key"]: s["value"] for s in gpu["settings"]}
    assert settings["clocks_configuration.gpu_clock_offsets"] == "P0: +150"
    assert settings["fan_control_settings.curve"] == "40: 0.3, 60: 0.5, 80: 1.0"
    assert gpu["settings"][0]["key"] == "power_cap"  # priority ordering


def test_write_sample_for_android(client):
    """Saves a real response for the Android unit test that checks the Kotlin models."""
    SAMPLE_OUT.parent.mkdir(parents=True, exist_ok=True)
    SAMPLE_OUT.write_text(json.dumps(summary(client), indent=1))


@pytest.mark.parametrize("token", ["", "PASTE_A_TOKEN_HERE", "short"])
def test_rejects_bad_tokens(tmp_path, token, monkeypatch):
    monkeypatch.delenv("CRUNCHOMETER_TOKEN", raising=False)
    p = tmp_path / "c.toml"
    p.write_text(f'[server]\ntoken = "{token}"\n')
    with pytest.raises(ConfigError):
        load_config(p)


def test_rejects_unknown_key(tmp_path):
    p = tmp_path / "c.toml"
    p.write_text(f'[server]\ntoken = "{TOKEN}"\n[boinc]\nprot = 1\n')
    with pytest.raises(ConfigError, match=r"\[boinc\] prot"):
        load_config(p)
