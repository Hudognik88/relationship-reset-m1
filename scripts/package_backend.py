#!/usr/bin/env python3
"""Create a deterministic, explicitly allowlisted staging release for Beget."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import re
import tarfile
from pathlib import Path

from package_staging import ROOT, PUBLIC_FILES, make_staging, verify


def allowed_private(name: str) -> bool:
    return name in ("backend/bootstrap.php", "backend/config.example.php") or bool(
        re.fullmatch(r"backend/(?:src/[A-Za-z0-9_/-]+\.php|bin/[A-Za-z0-9_-]+\.php|migrations/[A-Za-z0-9_-]+\.sql)", name)
    )


def collect(release: str) -> dict[str, bytes]:
    if not re.fullmatch(r"[0-9a-f]{40}", release):
        raise ValueError("Release must be a full lowercase Git commit SHA")
    originals = {name: (ROOT / name).read_bytes() for name in PUBLIC_FILES}
    pages = dict(originals)
    for name in PUBLIC_FILES:
        if name.endswith(".html"):
            pages[name] = make_staging(originals[name].decode("utf-8"))[0].encode("utf-8")
    verify(pages, originals)
    files = {"public/" + name: data for name, data in pages.items()}
    for name in ("api/index.php", "api/health.php"):
        path = ROOT / name
        if path.is_symlink():
            raise ValueError("Symlinks cannot enter deployment packages")
        files["public/" + name] = path.read_bytes()
    for path in sorted((ROOT / "backend").rglob("*")):
        if not path.is_file():
            continue
        name = path.relative_to(ROOT).as_posix()
        if allowed_private(name):
            if path.is_symlink():
                raise ValueError("Symlinks cannot enter deployment packages")
            files["private/" + name] = path.read_bytes()
    if "private/backend/bootstrap.php" not in files:
        raise ValueError("Missing backend/bootstrap.php")
    manifest = {
        "format": 1, "mode": "staging", "release": release,
        "files": {name: hashlib.sha256(data).hexdigest() for name, data in sorted(files.items())},
    }
    files["manifest.json"] = (json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()
    return files


def build(output: Path, release: str) -> dict[str, bytes]:
    files = collect(release)
    if output.resolve().is_relative_to(ROOT / "backend") or output.resolve() in {(ROOT / p).resolve() for p in PUBLIC_FILES}:
        raise ValueError("Output cannot overwrite application source")
    payload = io.BytesIO()
    with tarfile.open(fileobj=payload, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        for name, data in sorted(files.items()):
            item = tarfile.TarInfo(name)
            item.size = len(data)
            item.mode = 0o644
            item.mtime = 0
            archive.addfile(item, io.BytesIO(data))
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(gzip.compress(payload.getvalue(), compresslevel=9, mtime=0))
    with tarfile.open(output, "r:gz") as archive:
        assert set(archive.getnames()) == set(files)
        assert all(archive.extractfile(name).read() == content for name, content in files.items())
    return files


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "dist" / "relationship-reset-beget-staging.tar.gz")
    args = parser.parse_args()
    files = build(args.output, args.release)
    print(f"Built {args.output}: {len(files)} files; SHA256={hashlib.sha256(args.output.read_bytes()).hexdigest()}")
    print("Staging only. Configuration, secrets, deployment receiver and tests are excluded.")


if __name__ == "__main__":
    main()
