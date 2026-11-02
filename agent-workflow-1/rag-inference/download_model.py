"""Download only pinned BGE-M3 dense artifacts from the official repository.

Run in a Python container with /models mounted; weights survive container rebuilds.
"""
import hashlib
import json
import os
from pathlib import Path
import time
import urllib.request

REVISION = "5617a9f61b028005a4858fdac845db406aefb181"
ROOT = Path("/models/bge-m3")
FILES = ["config.json", "tokenizer.json", "tokenizer_config.json", "special_tokens_map.json",
         "sentencepiece.bpe.model", "pytorch_model.bin"]


def main():
    ROOT.mkdir(parents=True, exist_ok=True)
    manifest = {"model": "BAAI/bge-m3", "revision": REVISION, "files": {}}
    for name in FILES:
        target = ROOT / name
        if not target.exists():
            partial = target.with_suffix(target.suffix + ".part")
            for attempt in range(8):
                try:
                    offset = partial.stat().st_size if partial.exists() else 0
                    url = f"https://huggingface.co/BAAI/bge-m3/resolve/{REVISION}/{name}?download=true"
                    request = urllib.request.Request(url, headers={"Range": f"bytes={offset}-"} if offset else {})
                    with urllib.request.urlopen(request, timeout=60) as response:
                        append = offset > 0 and response.status == 206
                        expected = int(response.headers.get("Content-Length", "0"))
                        received = 0
                        with partial.open("ab" if append else "wb") as stream:
                            while block := response.read(1024 * 1024):
                                stream.write(block)
                                received += len(block)
                        if expected and received != expected:
                            raise OSError("incomplete transfer")
                    partial.replace(target)
                    print("downloaded", name, target.stat().st_size, flush=True)
                    break
                except Exception as exc:
                    print("retry", name, attempt + 1, type(exc).__name__, flush=True)
                    if attempt == 7:
                        raise
                    time.sleep(min(20, 2 ** attempt))
        with target.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        manifest["files"][name] = {"size": target.stat().st_size, "sha256": digest}
        os.chmod(target, 0o644)
    (ROOT / "manifest.json").write_text(json.dumps(manifest, indent=2))
    (ROOT / ".complete").write_text(REVISION)
    os.chmod(ROOT, 0o755)
    print("Pinned BGE-M3 download complete", flush=True)


if __name__ == "__main__":
    main()

