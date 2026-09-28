#!/usr/bin/env python3
"""One-time owner-run installation of the reviewed pilot-only receiver extension."""
from __future__ import annotations

import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import stat
import tempfile

from publish_pilot_remote import atomic_write, no_symlink, require

BASELINE_SHA256 = "f9579530f7650576c9a67ddc3cf43df03d656ed90850888925af9c0034f64569"
# The installed pilot module from reviewed commit 36894ada0253b4a49556dad823c6098498aa8eca.
# Permit only its Python 3.10 compatibility correction with an unchanged dispatcher.
PREVIOUS_PILOT_SHA256 = "9f260b6036b96ec3428f66d3e9dec1e413327734b26d100685cfb3d52937876a"
EXPECTED_SITE = Path("/home/m/movereed/movereed.beget.tech")
NAMES = ("publish_pilot_remote.py", "deploy_beget_remote.py")
DISPATCH = b'''    pilot = re.fullmatch(r"rr-publish-pilot ([0-9a-f]{40}) ([0-9a-f]{64})", os.environ.get("SSH_ORIGINAL_COMMAND", ""))
    if pilot:
        from publish_pilot_remote import main as publish_pilot
        publish_pilot(pilot[1], pilot[2])
        return
'''


def upgrade(source_dir: Path, site_root: Path) -> dict:
    source_dir, site_root = source_dir.absolute(), site_root.absolute()
    private = site_root / "relationship-reset-private"
    destination = private / "deploy"
    lock_path = private / ".deploy.lock"
    backup_root = private / "receiver-upgrade-backups"
    for path in (source_dir, site_root, site_root / "public_html", private, destination, lock_path, backup_root):
        no_symlink(path)
    require((site_root / "public_html").is_dir() and destination.is_dir(), "Existing installed site required")
    desired = {}
    for name in NAMES:
        source, target = source_dir / name, destination / name
        no_symlink(source)
        no_symlink(target)
        require(source.is_file() and source.stat().st_size <= 128 * 1024, "Missing or oversized receiver source")
        require(not target.exists() or target.is_file(), "Receiver target must be a regular file")
        desired[name] = source.read_bytes()
        compile(desired[name], name, "exec")
    receiver = desired["deploy_beget_remote.py"]
    require(receiver.count(DISPATCH) == 1 and b"def main() -> None:\n" + DISPATCH in receiver,
            "Unexpected pilot dispatch extension")
    require(hashlib.sha256(receiver.replace(DISPATCH, b"", 1)).hexdigest() == BASELINE_SHA256,
            "Staging receiver behavior must remain unchanged")
    with lock_path.open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        for name in NAMES:
            no_symlink(destination / name)
        old = {name: (destination / name).read_bytes() if (destination / name).exists() else None for name in NAMES}
        if all(old[name] == desired[name] for name in NAMES):
            return {"status": "unchanged"}
        initial_install = (old["deploy_beget_remote.py"] is not None and
                           hashlib.sha256(old["deploy_beget_remote.py"]).hexdigest() == BASELINE_SHA256 and
                           old["publish_pilot_remote.py"] is None)
        reviewed_correction = (old["deploy_beget_remote.py"] == desired["deploy_beget_remote.py"] and
                               old["publish_pilot_remote.py"] is not None and
                               hashlib.sha256(old["publish_pilot_remote.py"]).hexdigest() == PREVIOUS_PILOT_SHA256)
        require(initial_install or reviewed_correction,
                "Installed receiver differs from the reviewed baseline or pilot module")
        old_modes = {name: stat.S_IMODE((destination / name).stat().st_mode)
                     for name in NAMES if old[name] is not None}
        backup_root.mkdir(mode=0o700, exist_ok=True)
        backup = Path(tempfile.mkdtemp(prefix="pilot-", dir=backup_root))
        for name in NAMES:
            if old[name] is not None:
                atomic_write(backup / name, old[name], 0o600)
        atomic_write(backup / "state.json", (json.dumps({"previous_modes": old_modes,
                     "previously_missing": [name for name in NAMES if old[name] is None]}, sort_keys=True) + "\n").encode(), 0o600)
        changed = []
        try:
            # The module is installed before the dispatch begins referring to it.
            for name in NAMES:
                atomic_write(destination / name, desired[name], old_modes.get(name, 0o700))
                changed.append(name)
        except BaseException:
            for name in reversed(changed):
                if old[name] is None:
                    (destination / name).unlink()
                else:
                    atomic_write(destination / name, old[name], old_modes[name])
            raise
    return {"status": "upgraded", "backup": backup.name,
            "scope": "public_html/pilot.html only; existing staging unchanged"}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("site", type=Path)
    args = parser.parse_args()
    require(args.site.absolute() == EXPECTED_SITE, "Unexpected site path")
    print(json.dumps(upgrade(Path(__file__).absolute().parent, args.site), sort_keys=True))


if __name__ == "__main__":
    main()
