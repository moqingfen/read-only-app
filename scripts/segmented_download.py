#!/usr/bin/env python3
import argparse
import os
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed


def content_length(url: str) -> int:
    result = subprocess.run(
        ["curl", "--http1.1", "-L", "-I", "-s", url],
        check=True,
        text=True,
        capture_output=True,
    )
    lengths = []
    for line in result.stdout.splitlines():
        if line.lower().startswith("content-length:"):
            value = line.split(":", 1)[1].strip()
            if value.isdigit():
                lengths.append(int(value))
    if not lengths:
        raise RuntimeError("Content-Length missing")
    return lengths[-1]


def download_part(url: str, part_path: str, start: int, end: int) -> None:
    if os.path.exists(part_path) and os.path.getsize(part_path) == end - start + 1:
        return
    cmd = [
        "curl",
        "--http1.1",
        "-L",
        "--fail",
        "--retry",
        "3",
        "--connect-timeout",
        "20",
        "-r",
        f"{start}-{end}",
        "-o",
        part_path,
        url,
    ]
    subprocess.run(cmd, check=True)
    actual = os.path.getsize(part_path)
    expected = end - start + 1
    if actual != expected:
        raise RuntimeError(f"{part_path} size {actual}, expected {expected}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("url")
    parser.add_argument("output")
    parser.add_argument("--parts", type=int, default=12)
    args = parser.parse_args()

    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
    size = content_length(args.url)
    part_dir = args.output + ".parts"
    os.makedirs(part_dir, exist_ok=True)
    chunk = (size + args.parts - 1) // args.parts

    ranges = []
    for index in range(args.parts):
        start = index * chunk
        if start >= size:
            break
        end = min(size - 1, start + chunk - 1)
        ranges.append((index, start, end))

    print(f"Downloading {size} bytes in {len(ranges)} parts: {args.output}")
    with ThreadPoolExecutor(max_workers=min(args.parts, len(ranges))) as executor:
        futures = [
            executor.submit(download_part, args.url, os.path.join(part_dir, f"part-{index:03d}"), start, end)
            for index, start, end in ranges
        ]
        for future in as_completed(futures):
            future.result()

    tmp = args.output + ".tmp"
    with open(tmp, "wb") as output:
        for index, _, _ in ranges:
            with open(os.path.join(part_dir, f"part-{index:03d}"), "rb") as part:
                output.write(part.read())
    os.replace(tmp, args.output)
    print(f"Done: {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
