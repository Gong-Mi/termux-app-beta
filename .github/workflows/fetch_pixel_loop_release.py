#!/usr/bin/env python3
"""Fetch the latest pixel-loop release asset and its checksum from GitHub.

Usage: fetch_pixel_loop_release.py --asset pixel-loop-x86_64 --out-dir ./

Queries the repo's latest release, downloads the requested asset plus its
.sha256 companion, verifies the checksum, and prints the asset path.
"""
import argparse
import hashlib
import json
import os
import sys
import urllib.request


def api_request(url, token=None):
    req = urllib.request.Request(url)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def download(url, dest, token=None):
    req = urllib.request.Request(url)
    req.add_header("Accept", "application/octet-stream")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=120) as resp, open(dest, "wb") as fh:
        while True:
            chunk = resp.read(64 * 1024)
            if not chunk:
                break
            fh.write(chunk)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        while True:
            chunk = fh.read(64 * 1024)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", "Gong-Mi/termux-app-beta"),
                    help="owner/repo to query")
    ap.add_argument("--asset", default="pixel-loop-x86_64",
                    help="asset name to download")
    ap.add_argument("--out-dir", default=".", help="where to save files")
    ap.add_argument("--token", default=os.environ.get("GITHUB_TOKEN"),
                    help="GitHub token (optional, helps with rate limits)")
    args = ap.parse_args()

    release = api_request(f"https://api.github.com/repos/{args.repo}/releases/latest", args.token)
    tag = release["tag_name"]

    assets = {a["name"]: a for a in release.get("assets", [])}
    if args.asset not in assets:
        print(f"FAIL: asset '{args.asset}' not found in release {tag}", file=sys.stderr)
        print(f"available: {list(assets.keys())}", file=sys.stderr)
        sys.exit(1)

    checksum_name = f"{args.asset}.sha256"
    asset_url = assets[args.asset]["url"]
    asset_path = os.path.join(args.out_dir, args.asset)

    print(f"Downloading {args.asset} from release {tag}...")
    download(asset_url, asset_path, args.token)
    actual_hash = sha256_file(asset_path)

    if checksum_name in assets:
        checksum_path = os.path.join(args.out_dir, checksum_name)
        download(assets[checksum_name]["url"], checksum_path, args.token)
        with open(checksum_path, "r", encoding="utf-8") as fh:
            expected_hash = fh.read().strip().split()[0].lower()
    else:
        # Fall back to the digest returned by the GitHub API asset metadata.
        expected_hash = assets[args.asset].get("digest", "").replace("sha256:", "").lower()
        if not expected_hash:
            print(f"WARN: no .sha256 asset and no API digest for {args.asset}; skipping checksum verification")
            print(asset_path)
            return

    if actual_hash != expected_hash:
        print(f"FAIL: checksum mismatch for {args.asset}", file=sys.stderr)
        print(f"expected: {expected_hash}", file=sys.stderr)
        print(f"actual:   {actual_hash}", file=sys.stderr)
        sys.exit(1)

    print(f"OK {args.asset} {actual_hash} ({os.path.getsize(asset_path)} bytes)")
    print(asset_path)


if __name__ == "__main__":
    main()
