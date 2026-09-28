#!/usr/bin/env python3
"""Offline tests for the deliberately narrow public pilot publisher."""
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest import mock
import warnings
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import publish_pilot_remote as remote
import upgrade_pilot_receiver as installer


def digest(body):
    return hashlib.sha256(body).hexdigest()


def zip_bytes(entries):
    stream = io.BytesIO()
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", UserWarning)
        with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, body, mode in entries:
                item = zipfile.ZipInfo(name)
                item.create_system = 3
                item.external_attr = mode << 16
                item.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(item, body)
    return stream.getvalue()


class PilotReceiverTests(unittest.TestCase):
    SHA = "a" * 40

    @classmethod
    def setUpClass(cls):
        cls.html = (ROOT / "pilot.html").read_bytes()

    def manifest(self, html=None):
        html = self.html if html is None else html
        return {
            "kind": "pilot-interest-review",
            "release": self.SHA,
            "sha256": digest(html),
            "file": "pilot.html",
            "payments": "disabled",
            "forms": "none",
            "deployment": "not-performed",
        }

    def package(self, html=None, manifest=None, extra=()):
        html = self.html if html is None else html
        manifest = self.manifest(html) if manifest is None else manifest
        return zip_bytes([
            ("pilot.html", html, stat.S_IFREG | 0o644),
            ("manifest.json", json.dumps(manifest).encode(), stat.S_IFREG | 0o644),
            *extra,
        ])

    def validate(self, payload):
        return remote.validate_package(payload, self.SHA, digest(payload))

    def test_valid_package_returns_exact_html(self):
        self.assertEqual(self.validate(self.package()), self.html)

    def test_actual_pilot_page_is_valid_on_host_python(self):
        remote.validate_html(self.html)

    def test_plain_mailto_without_query_is_valid_on_host_python(self):
        html = b'<!doctype html><html><head><title>Pilot</title></head><body><a href="mailto:moverelationship@gmail.com">Email</a></body></html>'
        # Python 3.10 strict parse_qsl rejects an empty query; do not pass it one.
        with mock.patch.object(remote, "parse_qsl", side_effect=ValueError("bad query field: ''")) as query_parser:
            self.assertEqual(self.validate(self.package(html=html)), html)
            query_parser.assert_not_called()

    def test_archive_and_manifest_are_bound_to_release_and_content(self):
        payload = self.package()
        with self.assertRaises(ValueError):
            remote.validate_package(payload, self.SHA, "0" * 64)
        for key, value in (("release", "b" * 40), ("sha256", "0" * 64),
                           ("file", "index.html"), ("payments", "enabled"),
                           ("forms", "enabled"), ("kind", "staging"),
                           ("deployment", "published")):
            with self.subTest(key=key):
                manifest = self.manifest()
                manifest[key] = value
                with self.assertRaises(ValueError):
                    self.validate(self.package(manifest=manifest))

    def test_duplicate_unmanaged_and_traversal_members_rejected(self):
        for name in ("pilot.html", "manifest.json", "index.html", "../pilot.html",
                     "/pilot.html", "public/pilot.html", "a/../pilot.html",
                     "public\\pilot.html", "relationship-reset-private/config.php"):
            with self.subTest(name=name):
                with self.assertRaises(ValueError):
                    self.validate(self.package(extra=[(name, b"bad", stat.S_IFREG | 0o644)]))

    def test_symlink_member_rejected(self):
        payload = zip_bytes([
            ("pilot.html", b"../../outside", stat.S_IFLNK | 0o777),
            ("manifest.json", json.dumps(self.manifest(b"../../outside")).encode(), stat.S_IFREG | 0o644),
        ])
        with self.assertRaises(ValueError):
            self.validate(payload)

    def test_compressed_oversize_html_rejected(self):
        html = self.html + b" " * (129 * 1024)
        with self.assertRaises(ValueError):
            self.validate(self.package(html=html))

    def test_executable_collection_and_unapproved_outbound_content_rejected(self):
        for fragment in (b"<script>alert(1)</script>", b"<form></form>",
                         b'<div onclick="alert(1)">x</div>',
                         b'<a href="https://example.com">external</a>',
                         b'<img src="https://example.com/pixel">',
                         b'<iframe srcdoc="hello"></iframe>',
                         b'<meta http-equiv="refresh" content="0;url=https://example.com">',
                         b'<style>@import "https://example.com/style.css";</style>',
                         b'<style>body{background:url(https://example.com/pixel)}</style>',
                         b'<style>body{background:image-set("https://example.com/pixel" 1x)}</style>',
                         b'<style>body{background:-webkit-image-set("https://example.com/pixel" 1x)}</style>',
                         b'<style>body{background:image("https://example.com/pixel")}</style>'):
            with self.subTest(fragment=fragment):
                html = self.html.replace(b"</body>", fragment + b"</body>")
                with self.assertRaises(ValueError):
                    self.validate(self.package(html=html))

    def fixture(self, directory):
        site = Path(directory) / "site"
        public = site / "public_html"
        private = site / "relationship-reset-private"
        public.mkdir(parents=True)
        (private / "deploy").mkdir(parents=True)
        (private / "shared").mkdir()
        (public / "index.html").write_bytes(b"STAGING_HOME")
        (public / "app.js").write_bytes(b"STAGING_APP")
        (private / "shared" / "config.php").write_bytes(b"PRIVATE_CONFIGURATION")
        return site, public, private

    def assert_unmanaged_unchanged(self, public, private):
        self.assertEqual((public / "index.html").read_bytes(), b"STAGING_HOME")
        self.assertEqual((public / "app.js").read_bytes(), b"STAGING_APP")
        self.assertEqual((private / "shared" / "config.php").read_bytes(), b"PRIVATE_CONFIGURATION")

    def test_publication_only_replaces_pilot_and_retains_previous_version(self):
        with tempfile.TemporaryDirectory() as directory:
            site, public, private = self.fixture(directory)
            (public / "pilot.html").write_bytes(b"PREVIOUS_PILOT")
            result = remote.publish(site, self.html, self.SHA)
            self.assertIsInstance(result, dict)
            self.assertEqual((public / "pilot.html").read_bytes(), self.html)
            self.assert_unmanaged_unchanged(public, private)
            self.assertTrue(any(path.is_file() and path.read_bytes() == b"PREVIOUS_PILOT"
                                for path in private.rglob("*")))

    def test_publication_refuses_symlink_targets_without_changing_outside(self):
        for target in ("public_html", "pilot.html", "relationship-reset-private"):
            with self.subTest(target=target), tempfile.TemporaryDirectory() as directory:
                site, public, private = self.fixture(directory)
                outside = Path(directory) / "outside"
                outside.mkdir()
                sentinel = outside / "pilot.html"
                sentinel.write_bytes(b"OUTSIDE_SENTINEL")
                if target == "pilot.html":
                    (public / target).symlink_to(sentinel)
                else:
                    path = site / target
                    path.rename(site / (target + "-original"))
                    path.symlink_to(outside, target_is_directory=True)
                with self.assertRaises(ValueError):
                    remote.publish(site, self.html, self.SHA)
                self.assertEqual(sentinel.read_bytes(), b"OUTSIDE_SENTINEL")

    def test_failed_atomic_replace_keeps_previous_pilot(self):
        with tempfile.TemporaryDirectory() as directory:
            site, public, private = self.fixture(directory)
            target = public / "pilot.html"
            target.write_bytes(b"PREVIOUS_PILOT")
            replace = os.replace

            def fail_pilot_replace(source, destination, *args, **kwargs):
                if Path(destination) == target:
                    raise OSError("Injected publication failure")
                return replace(source, destination, *args, **kwargs)

            with mock.patch.object(remote.os, "replace", side_effect=fail_pilot_replace):
                with self.assertRaises(OSError):
                    remote.publish(site, self.html, self.SHA)
            self.assertEqual(target.read_bytes(), b"PREVIOUS_PILOT")
            self.assert_unmanaged_unchanged(public, private)

    def install_baseline(self, private):
        desired = (ROOT / "scripts" / "deploy_beget_remote.py").read_bytes()
        self.assertEqual(desired.count(installer.DISPATCH), 1)
        original = desired.replace(installer.DISPATCH, b"", 1)
        self.assertEqual(digest(original), installer.BASELINE_SHA256)
        destination = private / "deploy"
        (destination / "deploy_beget_remote.py").write_bytes(original)
        (destination / "deploy_beget_remote.py").chmod(0o700)
        (destination / "receive_beget.sh").write_bytes(b"UNCHANGED_FORCED_COMMAND")
        return destination, original

    def test_upgrade_retains_baseline_backup_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            site, public, private = self.fixture(directory)
            destination, original = self.install_baseline(private)
            result = installer.upgrade(ROOT / "scripts", site)
            self.assertEqual(result["status"], "upgraded")
            backup = private / "receiver-upgrade-backups" / result["backup"]
            self.assertEqual((backup / "deploy_beget_remote.py").read_bytes(), original)
            for name in installer.NAMES:
                self.assertEqual((destination / name).read_bytes(), (ROOT / "scripts" / name).read_bytes())
            self.assertEqual((destination / "receive_beget.sh").read_bytes(), b"UNCHANGED_FORCED_COMMAND")
            self.assert_unmanaged_unchanged(public, private)
            self.assertEqual(installer.upgrade(ROOT / "scripts", site)["status"], "unchanged")
            self.assertEqual(len(list((private / "receiver-upgrade-backups").iterdir())), 1)

    def previous_pilot_module(self):
        current = (ROOT / "scripts" / "publish_pilot_remote.py").read_bytes()
        corrected = b'query = parse_qsl(parsed.query, keep_blank_values=True, strict_parsing=True) if parsed.query else []'
        previous = b'query = parse_qsl(parsed.query, keep_blank_values=True, strict_parsing=True)'
        self.assertEqual(current.count(corrected), 1)
        original = current.replace(corrected, previous, 1)
        self.assertEqual(digest(original), installer.PREVIOUS_PILOT_SHA256)
        return original

    def install_previous_pilot(self, private):
        destination, _ = self.install_baseline(private)
        dispatcher = (ROOT / "scripts" / "deploy_beget_remote.py").read_bytes()
        (destination / "deploy_beget_remote.py").write_bytes(dispatcher)
        original = self.previous_pilot_module()
        (destination / "publish_pilot_remote.py").write_bytes(original)
        (destination / "publish_pilot_remote.py").chmod(0o700)
        return destination, original

    def test_reviewed_pilot_correction_preserves_dispatcher_and_previous_module(self):
        with tempfile.TemporaryDirectory() as directory:
            site, public, private = self.fixture(directory)
            destination, original = self.install_previous_pilot(private)
            dispatcher = (destination / "deploy_beget_remote.py").read_bytes()
            result = installer.upgrade(ROOT / "scripts", site)
            self.assertEqual(result["status"], "upgraded")
            backup = private / "receiver-upgrade-backups" / result["backup"]
            self.assertEqual((backup / "publish_pilot_remote.py").read_bytes(), original)
            self.assertEqual((destination / "deploy_beget_remote.py").read_bytes(), dispatcher)
            self.assertEqual((destination / "publish_pilot_remote.py").read_bytes(),
                             (ROOT / "scripts" / "publish_pilot_remote.py").read_bytes())
            self.assertEqual(installer.upgrade(ROOT / "scripts", site)["status"], "unchanged")
            self.assert_unmanaged_unchanged(public, private)

    def test_pilot_correction_refuses_unknown_installed_module(self):
        with tempfile.TemporaryDirectory() as directory:
            site, public, private = self.fixture(directory)
            destination, _ = self.install_previous_pilot(private)
            target = destination / "publish_pilot_remote.py"
            target.write_bytes(b"UNKNOWN_PILOT_MODULE")
            with self.assertRaises(ValueError):
                installer.upgrade(ROOT / "scripts", site)
            self.assertEqual(target.read_bytes(), b"UNKNOWN_PILOT_MODULE")
            self.assert_unmanaged_unchanged(public, private)

    def test_pilot_correction_failure_restores_previous_module(self):
        with tempfile.TemporaryDirectory() as directory:
            site, public, private = self.fixture(directory)
            destination, original = self.install_previous_pilot(private)
            dispatcher = (destination / "deploy_beget_remote.py").read_bytes()
            atomic_write = installer.atomic_write

            def fail_dispatch(path, data, mode):
                if path == destination / "deploy_beget_remote.py":
                    raise OSError("Injected dispatch installation failure")
                return atomic_write(path, data, mode)

            with mock.patch.object(installer, "atomic_write", side_effect=fail_dispatch):
                with self.assertRaises(OSError):
                    installer.upgrade(ROOT / "scripts", site)
            self.assertEqual((destination / "publish_pilot_remote.py").read_bytes(), original)
            self.assertEqual((destination / "deploy_beget_remote.py").read_bytes(), dispatcher)
            self.assert_unmanaged_unchanged(public, private)

    def test_upgrade_failure_rolls_back_new_module_before_dispatch(self):
        with tempfile.TemporaryDirectory() as directory:
            site, public, private = self.fixture(directory)
            destination, original = self.install_baseline(private)
            atomic_write = installer.atomic_write

            def fail_dispatch(path, data, mode):
                if path == destination / "deploy_beget_remote.py":
                    raise OSError("Injected dispatch installation failure")
                return atomic_write(path, data, mode)

            with mock.patch.object(installer, "atomic_write", side_effect=fail_dispatch):
                with self.assertRaises(OSError):
                    installer.upgrade(ROOT / "scripts", site)
            self.assertFalse((destination / "publish_pilot_remote.py").exists())
            self.assertEqual((destination / "deploy_beget_remote.py").read_bytes(), original)
            self.assertEqual((destination / "receive_beget.sh").read_bytes(), b"UNCHANGED_FORCED_COMMAND")
            self.assert_unmanaged_unchanged(public, private)

    def test_upgrade_refuses_unknown_receiver_and_symlink_destination(self):
        for use_symlink in (False, True):
            with self.subTest(symlink=use_symlink), tempfile.TemporaryDirectory() as directory:
                site, public, private = self.fixture(directory)
                destination, original = self.install_baseline(private)
                target = destination / "deploy_beget_remote.py"
                if use_symlink:
                    outside = Path(directory) / "outside.py"
                    outside.write_bytes(original)
                    target.unlink()
                    target.symlink_to(outside)
                else:
                    target.write_bytes(b"UNREVIEWED_RECEIVER")
                old = target.read_bytes()
                with self.assertRaises(ValueError):
                    installer.upgrade(ROOT / "scripts", site)
                self.assertEqual(target.read_bytes(), old)
                self.assertFalse((destination / "publish_pilot_remote.py").exists())
                self.assert_unmanaged_unchanged(public, private)


if __name__ == "__main__":
    unittest.main(verbosity=2)
