"""Fake BOINC GUI RPC, LACT daemon and Gridcoin wallet, so tests run without the real things."""
import hashlib, json, os, socket, socketserver, threading
from http.server import BaseHTTPRequestHandler, HTTPServer

PASSWORD = "secretpw"
NONCE = "1731420000.123"

PROJECTS = """<boinc_gui_rpc_reply><projects>
<project><master_url>https://einsteinathome.org/</master_url><project_name>Einstein@Home</project_name>
<user_total_credit>1234567.5</user_total_credit><user_expavg_credit>25000.5</user_expavg_credit>
<host_total_credit>500000</host_total_credit><host_expavg_credit>20000</host_expavg_credit></project>
<project><master_url>https://www.worldcommunitygrid.org/</master_url><project_name>World Community Grid</project_name>
<user_total_credit>99999</user_total_credit><user_expavg_credit>3000</user_expavg_credit>
<host_total_credit>99999</host_total_credit><host_expavg_credit>3000</host_expavg_credit><dont_request_more_work/></project>
</projects></boinc_gui_rpc_reply>"""

RESULTS = """<boinc_gui_rpc_reply><results>
<result><name>h1_0123_O3_task_1</name><wu_name>h1_0123</wu_name><project_url>https://einsteinathome.org/</project_url>
<state>2</state><report_deadline>1999999999</report_deadline><estimated_cpu_time_remaining>1800.5</estimated_cpu_time_remaining>
<resources>1 CPU + 1 NVIDIA GPU</resources>
<active_task><active_task_state>1</active_task_state><scheduler_state>2</scheduler_state>
<fraction_done>0.42315</fraction_done><elapsed_time>1300.2</elapsed_time></active_task></result>
<result><name>MCM1_queued_2</name><project_url>https://www.worldcommunitygrid.org/</project_url>
<state>2</state><report_deadline>1000</report_deadline><estimated_cpu_time_remaining>9000</estimated_cpu_time_remaining></result>
<result><name>MCM1_done_3</name><project_url>https://www.worldcommunitygrid.org/</project_url>
<state>5</state><ready_to_report/><final_cpu_time>8000</final_cpu_time><final_elapsed_time>8100</final_elapsed_time>
<exit_status>0</exit_status><completed_time>1731400000</completed_time></result>
<result><name>MCM1_bad_4</name><project_url>https://www.worldcommunitygrid.org/</project_url>
<state>3</state><exit_status>-161</exit_status></result>
</results></boinc_gui_rpc_reply>"""

OLD = """<boinc_gui_rpc_reply><old_results>
<old_result><project_url>https://einsteinathome.org/</project_url><result_name>old_einstein_9</result_name>
<app_name>einsteinbinary_BRP7</app_name><exit_status>0</exit_status><elapsed_time>3600</elapsed_time>
<cpu_time>300</cpu_time><completed_time>1731300000</completed_time><create_time>1731299000</create_time></old_result>
</old_results></boinc_gui_rpc_reply>"""


class BoincHandler(socketserver.BaseRequestHandler):
    def handle(self):
        authed = False
        buf = b""
        while True:
            data = self.request.recv(4096)
            if not data:
                return
            buf += data
            while b"\x03" in buf:
                msg, buf = buf.split(b"\x03", 1)
                m = msg.decode()
                if "<auth1/>" in m:
                    out = f"<boinc_gui_rpc_reply><nonce>{NONCE}</nonce></boinc_gui_rpc_reply>"
                elif "<auth2>" in m:
                    want = hashlib.md5((NONCE + PASSWORD).encode()).hexdigest()
                    authed = want in m
                    out = "<boinc_gui_rpc_reply><authorized/></boinc_gui_rpc_reply>" if authed else \
                          "<boinc_gui_rpc_reply><unauthorized/></boinc_gui_rpc_reply>"
                elif not authed:
                    out = "<boinc_gui_rpc_reply><unauthorized/></boinc_gui_rpc_reply>"
                elif "get_project_status" in m:
                    out = PROJECTS
                elif "get_old_results" in m:
                    out = OLD
                elif "get_results" in m:
                    out = RESULTS
                else:
                    out = "<boinc_gui_rpc_reply><error>unrecognized op</error></boinc_gui_rpc_reply>"
                self.request.sendall(out.encode() + b"\x03")


class WalletHandler(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_POST(self):
        req = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if req["method"] == "magnitude":
            res = {"result": None, "error": {"code": -32601, "message": "Method not found"}}
            code = 500
        elif req["method"] == "getwalletinfo":
            res = {"result": {"walletversion": 60000, "balance": 1234.5678, "newmint": 12.5,
                              "stake": 2000.0, "staking": True}, "error": None}
            code = 200
        elif req["method"] == "getunconfirmedbalance":
            res = {"result": 3.25, "error": None}
            code = 200
        else:
            res = {"result": {"blocks": 3000000, "CPID": "0123456789abcdef0123456789abcdef",
                              "Magnitude Unit": 0.25, "current_magnitude": 187.43,
                              "BoincRewardPending": 42.1875,
                              "researcher_status": "Eligible for Research Rewards"}, "error": None}
            code = 200
        body = json.dumps(res).encode()
        self.send_response(code); self.send_header("Content-Length", str(len(body))); self.end_headers()
        self.wfile.write(body)


def lact_server(path):
    if os.path.exists(path):
        os.remove(path)
    srv = socket.socket(socket.AF_UNIX); srv.bind(path); srv.listen(4)
    def run():
        while True:
            c, _ = srv.accept()
            line = b""
            while not line.endswith(b"\n"):
                line += c.recv(4096)
            cmd = json.loads(line)["command"]
            if cmd == "ping":
                out = {"status": "ok", "data": None}
            elif cmd == "list_devices":
                out = {"status": "ok", "data": [{"id": "10DE:2684-1043:889D-0000:01:00.0", "name": "NVIDIA GeForce RTX 4090"}]}
            else:
                out = {"status": "error", "data": "nope"}
            c.sendall(json.dumps(out).encode() + b"\n"); c.close()
    threading.Thread(target=run, daemon=True).start()


def start_all(tmpdir) -> dict:
    """Start fake BOINC, wallet and LACT services on free ports. Returns their addresses."""
    boinc = socketserver.ThreadingTCPServer(("127.0.0.1", 0), BoincHandler)
    boinc.daemon_threads = True
    threading.Thread(target=boinc.serve_forever, daemon=True).start()
    wallet = HTTPServer(("127.0.0.1", 0), WalletHandler)
    threading.Thread(target=wallet.serve_forever, daemon=True).start()
    sock = os.path.join(str(tmpdir), "lactd.sock")
    lact_server(sock)
    return {"boinc_port": boinc.server_address[1], "wallet_port": wallet.server_address[1],
            "lact_socket": sock, "password": PASSWORD}


PCI_IDS = """\
# fake pci.ids, trimmed to the shape of the real one
1002  Advanced Micro Devices, Inc. [AMD/ATI]
\t73bf  Navi 21 [Radeon RX 6800/6800 XT / 6900 XT]
# a comment inside the vendor block
\t744c  Navi 31 [Radeon RX 7900 XT/7900 XTX/7900 GRE/7900M]
\t\t1002 0e3b  RX 7900 XTX / RX 7900 GRE [XFX]
\t7480  Navi 33
10de  NVIDIA Corporation
\t744c  Not an AMD card
"""


def make_amd_card(root, n: int, device: str = "0x744c", vendor: str = "0x1002",
                  full: bool = True) -> None:
    """A fake <root>/class/drm/card<n> laid out like amdgpu's sysfs. `full=False` leaves out
    hwmon and the optional files, the way a suspended or very old card looks."""
    from pathlib import Path
    drm = Path(root, "class/drm")
    dev = drm / f"card{n}" / "device"
    (drm / f"card{n}-DP-1").mkdir(parents=True, exist_ok=True)  # connector, must be skipped
    dev.mkdir(parents=True)
    (dev / "vendor").write_text(vendor + "\n")
    (dev / "device").write_text(device + "\n")
    if not full:
        return
    files = {
        "gpu_busy_percent": "97",
        "mem_busy_percent": "41",
        "mem_info_vram_used": str(6 * 2**30),
        "mem_info_vram_total": str(24 * 2**30),
        "pp_dpm_sclk": "0: 500Mhz\n1: 1900Mhz *\n2: 2500Mhz\n",
        "pp_dpm_mclk": "0: 96Mhz\n1: 456Mhz\n2: 1250Mhz *\n",
    }
    hwmon = {
        "temp1_label": "edge", "temp1_input": "61000",
        "temp2_label": "junction", "temp2_input": "78500",
        "temp3_label": "mem", "temp3_input": "70000",
        "power1_input": "301250000", "power1_cap": "339000000",  # RDNA3: no power1_average
        "freq1_label": "sclk", "freq1_input": "2431000000",
        "freq2_label": "mclk", "freq2_input": "1249000000",
        "pwm1": "128", "pwm1_max": "255",
    }
    for name, text in files.items():
        (dev / name).write_text(text)
    hw = dev / "hwmon" / f"hwmon{n + 3}"
    hw.mkdir(parents=True)
    for name, text in hwmon.items():
        (hw / name).write_text(text + "\n")
