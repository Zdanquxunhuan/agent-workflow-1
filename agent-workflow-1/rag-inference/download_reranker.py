"""Resumable official downloads with pinned size/hash verification; no alternate weights."""
import json
import os
import time
import urllib.request
import shutil
from concurrent.futures import ThreadPoolExecutor

from reranker_config import MODEL, REVISION, ROOT, FILES, verify


def parallel_download(name, spec, target, workers):
    """Optional bounded range downloads; validate every range and the final pinned hash."""
    prefix = target.with_suffix(target.suffix + '.part')
    offset = prefix.stat().st_size if prefix.exists() else 0
    if offset > spec[0]:
        raise RuntimeError('Existing partial artifact exceeds pinned size')
    base = f'https://huggingface.co/{MODEL}/resolve/{REVISION}/{name}?download=true'
    segment_size = 64 * 1024 * 1024
    ranges = [(start, min(start + segment_size, spec[0]) - 1) for start in range(offset, spec[0], segment_size)]

    def fetch(bounds):
        start, end = bounds
        segment = ROOT / f'{name}.range-{start}-{end}'
        for attempt in range(12):
            try:
                received = segment.stat().st_size if segment.exists() else 0
                if received == end - start + 1:
                    return segment
                if received > end - start + 1:
                    raise RuntimeError('Range artifact exceeds expected size')
                current = start + received
                request = urllib.request.Request(base + f'&range={current}-{end}',
                                                  headers={'Range': f'bytes={current}-{end}'})
                with urllib.request.urlopen(request, timeout=45) as response:
                    if response.status != 206 or response.headers.get('Content-Range') != f'bytes {current}-{end}/{spec[0]}':
                        raise OSError('Server did not honor the exact requested range')
                    with segment.open('ab') as stream:
                        while block := response.read(1024 * 1024):
                            received += len(block)
                            if received > end - start + 1:
                                raise OSError('Response exceeds requested range')
                            stream.write(block)
                if received != end - start + 1:
                    raise OSError('Incomplete range')
                print('range complete', start, end, flush=True)
                return segment
            except Exception as exc:
                print('range retry', start, attempt + 1, type(exc).__name__, flush=True)
                if attempt == 11:
                    raise
                time.sleep(min(20, 2 ** attempt))

    print('parallel download', workers, 'workers; retained prefix', offset, flush=True)
    with ThreadPoolExecutor(max_workers=workers) as executor:
        segments = list(executor.map(fetch, ranges))
    assembled = ROOT / (name + '.assembled')
    with assembled.open('wb') as output:
        if offset:
            with prefix.open('rb') as source:
                shutil.copyfileobj(source, output)
        for segment in segments:
            with segment.open('rb') as source:
                shutil.copyfileobj(source, output)
    if not verify(assembled, spec):
        raise RuntimeError('Pinned model checksum mismatch; refusing to publish')
    assembled.replace(target)
    for segment in segments:
        segment.unlink()
    prefix.unlink(missing_ok=True)
    print('verified parallel artifact', name, flush=True)


def main():
    ROOT.mkdir(parents=True, exist_ok=True)
    for name, spec in FILES.items():
        target = ROOT / name
        if verify(target, spec):
            print("verified cached", name, flush=True)
            continue
        workers = min(8, max(1, int(os.environ.get('DOWNLOAD_WORKERS', '1'))))
        if workers > 1 and spec[0] > 64 * 1024 * 1024:
            parallel_download(name, spec, target, workers)
            os.chmod(target, 0o644)
            continue
        part = target.with_suffix(target.suffix + ".part")
        for attempt in range(12):
            try:
                offset = part.stat().st_size if part.exists() else 0
                if offset == spec[0] and verify(part, spec):
                    part.replace(target)
                    break
                if offset >= spec[0]:
                    part.unlink()  # Exact incomplete artifact inside this model directory only.
                    offset = 0
                headers = {"Range": f"bytes={offset}-"} if offset else {}
                url = f"https://huggingface.co/{MODEL}/resolve/{REVISION}/{name}?download=true"
                with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=45) as response:
                    append = offset > 0 and response.status == 206
                    if append and not response.headers.get("Content-Range", "").startswith(f"bytes {offset}-"):
                        raise OSError("invalid resume range")
                    received = offset if append else 0
                    next_report = received + 64 * 1024 * 1024
                    with part.open("ab" if append else "wb") as stream:
                        while block := response.read(1024 * 1024):
                            received += len(block)
                            if received > spec[0]:
                                raise OSError("response larger than pinned artifact")
                            stream.write(block)
                            if received >= next_report:
                                print(name, received, "/", spec[0], flush=True)
                                next_report += 64 * 1024 * 1024
                if not verify(part, spec):
                    raise OSError("download incomplete or checksum mismatch")
                part.replace(target)
                print("verified", name, spec[0], flush=True)
                break
            except Exception as exc:
                print("retry", name, attempt + 1, type(exc).__name__, flush=True)
                if attempt == 11:
                    raise
                time.sleep(min(20, 2 ** attempt))
        os.chmod(target, 0o644)
    (ROOT / "manifest.json").write_text(json.dumps({"model": MODEL, "revision": REVISION, "files": FILES}, indent=2))
    (ROOT / ".complete").write_text(REVISION)
    os.chmod(ROOT, 0o755)
    print("Pinned reranker download and integrity checks complete", flush=True)


if __name__ == "__main__":
    main()
