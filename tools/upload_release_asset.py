import os
import sys
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
RELEASE_ID = "388301250"
REPO = "agungputraa/GameNuke"
APK_PATH = r"c:\ProyekAndroid\GameNukePrem\release-apk\GameNuke-v3.2.1-Spectra.apk"
ASSET_NAME = "GameNuke-v3.2.1-Spectra.apk"

headers = {
    "Authorization": f"token {TOKEN}",
    "Accept": "application/vnd.github.v3+json",
}

# 1. Get existing assets
print("Checking existing assets...")
get_assets_url = f"https://api.github.com/repos/{REPO}/releases/{RELEASE_ID}/assets"
req = urllib.request.Request(get_assets_url, headers=headers)

try:
    with urllib.request.urlopen(req) as resp:
        assets = json.loads(resp.read().decode("utf-8"))
        for asset in assets:
            if asset.get("name") == ASSET_NAME:
                asset_id = asset.get("id")
                print(f"Deleting existing asset id: {asset_id} ({asset.get('name')})...")
                del_url = f"https://api.github.com/repos/{REPO}/releases/assets/{asset_id}"
                del_req = urllib.request.Request(del_url, headers=headers, method="DELETE")
                with urllib.request.urlopen(del_req) as del_resp:
                    print(f"Deleted old asset: status {del_resp.status}")
except Exception as e:
    print(f"Error checking/deleting assets: {e}")

# 2. Upload new asset
print(f"Uploading {APK_PATH} as {ASSET_NAME}...")
upload_url = f"https://uploads.github.com/repos/{REPO}/releases/{RELEASE_ID}/assets?name={ASSET_NAME}"

with open(APK_PATH, "rb") as f:
    apk_data = f.read()

upload_headers = {
    "Authorization": f"token {TOKEN}",
    "Content-Type": "application/vnd.android.package-archive",
    "Content-Length": str(len(apk_data)),
}

upload_req = urllib.request.Request(upload_url, data=apk_data, headers=upload_headers, method="POST")

try:
    with urllib.request.urlopen(upload_req) as resp:
        res = json.loads(resp.read().decode("utf-8"))
        print(f"Successfully uploaded asset! ID: {res.get('id')}, Size: {res.get('size')} bytes")
except urllib.error.HTTPError as e:
    print(f"HTTP Error {e.code}: {e.read().decode('utf-8')}")
    sys.exit(1)
except Exception as e:
    print(f"Upload failed: {e}")
    sys.exit(1)
