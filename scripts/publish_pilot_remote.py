#!/usr/bin/env python3
"""Publish exactly one reviewed static interest page; no application execution."""
from __future__ import annotations

import fcntl
import hashlib
from html.parser import HTMLParser
import io
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
from urllib.parse import parse_qsl, urlsplit
import zipfile

MAX_ARCHIVE_BYTES = 256 * 1024
MAX_HTML_BYTES = 128 * 1024
SHA = re.compile(r"[0-9a-f]{40}")
DIGEST = re.compile(r"[0-9a-f]{64}")
PROTOCOL = re.compile(r"rr-publish-pilot ([0-9a-f]{40}) ([0-9a-f]{64})")
TAGS = frozenset("html head title meta style body header nav main footer section article aside div span a p br hr h1 h2 h3 h4 h5 h6 strong em small ul ol li details summary blockquote".split())
ATTRS = frozenset("class id lang aria-label aria-labelledby aria-hidden role style".split())


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def validate_css(value: str) -> None:
    # Deliberately small static CSS surface, including a ban on escape/comment
    # obfuscation. The page needs neither resource loading nor legacy bindings.
    require(not re.search(r"\\|/\*|<|>|@import|(?:url|image-set|image|src|expression)\s*\(|(?<![-\w])behavior\s*:|-moz-binding", value, re.I),
            "External or active CSS is not allowed")


class StaticPage(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.in_style = False
        self.tags: set[str] = set()

    def handle_decl(self, declaration: str) -> None:
        require(declaration.lower() == "doctype html", "Only HTML doctype is allowed")

    def unknown_decl(self, declaration: str) -> None:
        raise ValueError("Unknown HTML declaration")

    def handle_pi(self, data: str) -> None:
        raise ValueError("Processing instructions are not allowed")

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        require(tag in TAGS, "Active or unsupported HTML element")
        self.tags.add(tag)
        require(len({key for key, _ in attrs}) == len(attrs), "Duplicate HTML attribute")
        values = dict(attrs)
        for key, value in attrs:
            allowed = ATTRS | ({"href", "rel"} if tag == "a" else set())
            allowed |= {"name", "content", "charset"} if tag == "meta" else set()
            allowed |= {"open"} if tag == "details" else set()
            require(key in allowed and not key.startswith("on"), "Active or unsupported HTML attribute")
            if key == "style":
                validate_css(value or "")
        if tag == "a":
            href = values.get("href")
            if href is not None:
                require(not any(ord(c) < 32 for c in href), "Control character in link")
                if href.startswith("#"):
                    require(bool(re.fullmatch(r"#[A-Za-z][A-Za-z0-9_-]*", href)), "Invalid page anchor")
                elif href != "https://t.me/relationship_reset":
                    parsed = urlsplit(href)
                    require(parsed.scheme == "mailto" and not parsed.netloc and not parsed.fragment
                            and parsed.path == "moverelationship@gmail.com", "Unapproved external link")
                    query = parse_qsl(parsed.query, keep_blank_values=True, strict_parsing=True)
                    require(len(query) <= 2 and len({key for key, _ in query}) == len(query)
                            and all(key in ("subject", "body") for key, _ in query), "Unapproved mail parameter")
        if tag == "meta":
            if "charset" in values:
                require(set(values) == {"charset"} and (values["charset"] or "").lower() == "utf-8", "Invalid charset")
            else:
                require(set(values) == {"name", "content"}
                        and values["name"] in ("viewport", "theme-color", "description", "robots"), "Unapproved metadata")
        if tag == "style":
            require(not self.in_style, "Nested stylesheet")
            self.in_style = True

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self.handle_starttag(tag, attrs)
        self.handle_endtag(tag)

    def handle_endtag(self, tag: str) -> None:
        require(tag in TAGS, "Active or unsupported HTML element")
        if tag == "style":
            self.in_style = False

    def handle_data(self, data: str) -> None:
        if self.in_style:
            validate_css(data)


def validate_html(html: bytes) -> None:
    require(0 < len(html) <= MAX_HTML_BYTES, "HTML exceeds size limit")
    text = html.decode("utf-8")
    require("\x00" not in text and "<?" not in text, "Invalid HTML content")
    parser = StaticPage()
    parser.feed(text)
    parser.close()
    require({"html", "head", "title", "body"} <= parser.tags and not parser.in_style, "Incomplete static page")


def unique_object(items):
    result = {}
    for key, value in items:
        require(key not in result, "Duplicate manifest key")
        result[key] = value
    return result


def validate_package(payload: bytes, release: str, digest: str) -> bytes:
    require(bool(SHA.fullmatch(release)) and bool(DIGEST.fullmatch(digest)), "Invalid deployment identity")
    require(0 < len(payload) <= MAX_ARCHIVE_BYTES, "Archive exceeds size limit")
    require(hashlib.sha256(payload).hexdigest() == digest, "Archive digest mismatch")
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        items = archive.infolist()
        require(len(items) == 2 and {item.filename for item in items} == {"pilot.html", "manifest.json"}, "Unexpected archive files")
        for item in items:
            mode = item.external_attr >> 16
            require(not item.is_dir() and stat.S_IFMT(mode) in (0, stat.S_IFREG)
                    and not item.flag_bits & 1, "Only unencrypted regular files are allowed")
            require(item.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED), "Unsupported ZIP compression")
            require(item.file_size <= (MAX_HTML_BYTES if item.filename == "pilot.html" else 4096), "Archive member exceeds size limit")
        html = archive.read("pilot.html")
        manifest = json.loads(archive.read("manifest.json"), object_pairs_hook=unique_object)
    expected = {"kind": "pilot-interest-review", "release": release,
                "sha256": hashlib.sha256(html).hexdigest(), "file": "pilot.html",
                "payments": "disabled", "forms": "none", "deployment": "not-performed"}
    require(manifest == expected, "Pilot manifest mismatch")
    validate_html(html)
    return html


def no_symlink(path: Path) -> None:
    for item in (path, *path.parents):
        require(not item.is_symlink(), "Symlink in managed path")


def atomic_write(path: Path, data: bytes, mode: int) -> None:
    descriptor, temporary = tempfile.mkstemp(prefix=".pilot-update-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as output:
            os.fchmod(output.fileno(), mode)
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def publish(site_root: Path, html: bytes, release: str) -> dict:
    require(bool(SHA.fullmatch(release)), "Invalid release SHA")
    validate_html(html)
    site_root = site_root.absolute()
    public = site_root / "public_html"
    private = site_root / "relationship-reset-private"
    target = public / "pilot.html"
    backup_root = private / "pilot-backups"
    lock_path = private / ".deploy.lock"
    for path in (site_root, public, private, target, backup_root, lock_path):
        no_symlink(path)
    require(public.is_dir() and private.is_dir(), "Existing site directories required")
    require(not target.exists() or target.is_file(), "Pilot target is not a regular file")
    with lock_path.open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        # Recheck after acquiring the same lock used by the staging receiver.
        no_symlink(target)
        backup_root.mkdir(mode=0o700, exist_ok=True)
        backup = Path(tempfile.mkdtemp(prefix=release[:12] + "-", dir=backup_root))
        old = target.read_bytes() if target.exists() else None
        old_mode = stat.S_IMODE(target.stat().st_mode) if old is not None else None
        if old is not None:
            atomic_write(backup / "pilot.html", old, 0o600)
        state = {"release": release, "previously_missing": old is None, "previous_mode": old_mode,
                 "sha256": hashlib.sha256(html).hexdigest()}
        atomic_write(backup / "state.json", (json.dumps(state, sort_keys=True) + "\n").encode(), 0o600)
        atomic_write(target, html, 0o644)
    return {"status": "published", "release": release, "sha256": state["sha256"], "backup": backup.name}


def main(release: str, digest: str) -> None:
    here = Path(__file__).absolute().parent
    require(here.name == "deploy" and here.parent.name == "relationship-reset-private", "Unexpected receiver location")
    payload = sys.stdin.buffer.read(MAX_ARCHIVE_BYTES + 1)
    html = validate_package(payload, release, digest)
    print(json.dumps(publish(here.parent.parent, html, release), sort_keys=True))


if __name__ == "__main__":
    match = PROTOCOL.fullmatch(os.environ.get("SSH_ORIGINAL_COMMAND", ""))
    require(match is not None, "Only the exact pilot publication command is allowed")
    main(match[1], match[2])
