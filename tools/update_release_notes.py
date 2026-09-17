import os
import json
import urllib.request

def get_token():
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        return token
    env_path = os.path.join(os.path.dirname(__file__), "github_token.env")
    if os.path.exists(env_path):
        with open(env_path, "r", encoding="utf-8") as f:
            for line in f:
                if line.strip().startswith("GITHUB_TOKEN="):
                    return line.strip().split("=", 1)[1].strip()
    return ""

TOKEN = get_token()
URL = "https://api.github.com/repos/agungputraa/GameNuke/releases/387803285"

body_text = """Game Nuke Void Edition v2.9.0-Void

Official Standalone Release with Dual ABI Architecture & Realtime Telemetry.

Highlights:
- Ultra-Powerful Touch Macro Spam Engine: Infinite hold-burst spamming for Gloo Wall with real-time priority loop, generous touch slop hit test, and target coordinate redirection.
- Hardware-Bound Refresh Rate Target: Minimalist single-line seekbar & preset switch (90/120/144 FPS) with instant apply, 60 FPS safety lockout on 90Hz+ panels, and anti-unlocker validation.
- Touch Engine Screen-Freeze Fix: Resolved sensitivity X/Y pointer deadlocks and unhandled ACTION_UP queue locks.
- Hardware Touch Driver Integration: Native libwandev.so bridge with GNU hash & Bloom filter verification.
- Android 16 Overlay Controller: Enterprise auto-grant via appops/Shizuku/iAdb and low-RAM bypass guidance.
- Dual ABI Architecture: Added armeabi-v7a alongside arm64-v8a for universal Android 11–16 device coverage.
- Macro Studio Opacity Control: Interactive transparency tuning (20% - 100%) for overlay and reticle pins.
- Process Purge Guardian: Intelligent non-root memory trimming with whitelist protection for active games and recorders.
- Battery Optimization Guard: Realtime system readiness status and direct unrestricted power mode exemption.

Integrity:
- File: GameNuke-v2.9.0-Void.apk
- Size: 40.4 MB (42,384,415 bytes)
- SHA256: D0CAD8AB855C77C0CF17EB2652384F421BE815DCFF72D0A646DB4451A511DDC2"""

payload = {
    "name": "Game Nuke Void Edition v2.9.0-Void",
    "body": body_text
}

req = urllib.request.Request(
    URL,
    data=json.dumps(payload).encode("utf-8"),
    headers={
        "Authorization": f"token {TOKEN}",
        "Accept": "application/vnd.github.v3+json",
        "Content-Type": "application/json"
    },
    method="PATCH"
)

try:
    with urllib.request.urlopen(req) as response:
        print("Release updated:", response.status)
except urllib.error.HTTPError as e:
    print("HTTPError:", e.code, e.read().decode("utf-8"))
