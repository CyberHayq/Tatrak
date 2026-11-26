"""Trust only a pinned local QA server certificate in an active Appium session."""
import argparse
import json
import re
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("session")
parser.add_argument("sha256", help="Expected certificate SHA-256 from the local Prosody container")
args = parser.parse_args()
base = f"http://127.0.0.1:4723/session/{args.session}"


def call(path, data=None):
    request = Request(base + path, data=None if data is None else json.dumps(data).encode(),
                      headers={"Content-Type": "application/json"})
    with urlopen(request, timeout=20) as response:
        return json.load(response)["value"]


root = ET.fromstring(call("/source"))
texts = "\n".join(node.get("text", "") for node in root.iter())
if "Accept Unknown Certificate?" not in texts or "CN=10.0.2.2" not in texts:
    raise RuntimeError("Expected the isolated QA server's certificate dialog")
match = re.search(r"SHA-256:\s*([0-9a-fA-F:]+)", texts)
expected = args.sha256.replace(":", "").lower()
if not match or match.group(1).replace(":", "").lower() != expected:
    raise RuntimeError("Certificate fingerprint differs from the pinned QA server")
element = call("/element", {"using": "id", "value": "android:id/button1"})
identifier = element["element-6066-11e4-a52e-4f735466cecf"]
call(f"/element/{identifier}/click", {})
print("Trusted the exact pinned local QA certificate")
