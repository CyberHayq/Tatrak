"""Download an official SDK archive in validated, resumable HTTP ranges."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
from pathlib import Path
import re
import socket
import time
from urllib.request import Request, urlopen
import urllib3
urllib3.util.connection.allowed_gai_family = lambda: socket.AF_INET


parser = argparse.ArgumentParser()
parser.add_argument("url")
parser.add_argument("destination", type=Path)
parser.add_argument("--sha256")
parser.add_argument("--workers", type=int, default=8)
parser.add_argument("--chunk-mb", type=int, default=4)
parser.add_argument("--size", type=int, help="Expected size from the official SDK repository metadata")
args = parser.parse_args()
total = args.size
for attempt in range(6):
    if total:
        break
    try:
        with urlopen(Request(args.url, headers={"Range": "bytes=0-0"}), timeout=30) as response:
            match = re.fullmatch(r"bytes 0-0/(\d+)", response.headers.get("Content-Range", ""))
            if not match:
                raise RuntimeError("Server does not provide exact HTTP ranges")
            total = int(match.group(1))
        break
    except Exception:
        if attempt == 5:
            raise
        time.sleep(2)

parts = args.destination.with_suffix(args.destination.suffix + ".parts")
parts.mkdir(parents=True, exist_ok=True)
chunk_size = args.chunk_mb * 1024 * 1024
pool = urllib3.PoolManager(maxsize=args.workers, block=True,
                           timeout=urllib3.Timeout(connect=20, read=45), retries=False)


def download(start):
    end = min(total - 1, start + chunk_size - 1)
    path = parts / str(start)
    if path.exists() and path.stat().st_size == end - start + 1:
        return path
    for attempt in range(4):
        try:
            response = pool.request("GET", args.url, headers={"Range": f"bytes={start}-{end}"})
            if response.headers.get("Content-Range") != f"bytes {start}-{end}/{total}":
                raise RuntimeError("Unexpected Content-Range")
            data = response.data
            if len(data) != end - start + 1:
                raise RuntimeError("Incomplete range")
            path.write_bytes(data)
            return path
        except Exception as exc:
            print(f"Range {start}: attempt {attempt + 1} failed: {exc}", flush=True)
            if attempt == 3:
                raise
            time.sleep(1 + attempt)


starts = list(range(0, total, chunk_size))
failures = []
with ThreadPoolExecutor(max_workers=args.workers) as executor:
    for count, future in enumerate(as_completed([executor.submit(download, start) for start in starts]), 1):
        try:
            future.result()
        except Exception as exc:
            failures.append(str(exc))
        print(f"{count}/{len(starts)} ranges complete", flush=True)
if failures:
    raise RuntimeError(f"{len(failures)} ranges failed; rerun to resume: {failures[:3]}")

digest = hashlib.sha256()
with args.destination.open("wb") as output:
    for start in starts:
        data = (parts / str(start)).read_bytes()
        digest.update(data)
        output.write(data)
actual = digest.hexdigest()
if args.sha256 and actual != args.sha256:
    raise RuntimeError(f"SHA-256 mismatch: {actual}")
print(f"Downloaded {total} bytes; sha256={actual}", flush=True)
