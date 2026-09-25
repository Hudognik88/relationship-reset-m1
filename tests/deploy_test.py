#!/usr/bin/env python3
"""Offline deploy safety tests: archive validation and rollback, no network."""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import package_backend
import deploy_beget_remote as remote


def tar_bytes(files, symlink=None):
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w:gz") as archive:
        for name, body in files.items():
            item = tarfile.TarInfo(name)
            item.size = len(body)
            archive.addfile(item, io.BytesIO(body))
        if symlink:
            item = tarfile.TarInfo(symlink)
            item.type = tarfile.SYMTYPE
            item.linkname = "../../outside"
            archive.addfile(item)
    return stream.getvalue()


class DeployTests(unittest.TestCase):
    SHA = "a" * 40

    def package(self):
        files = package_backend.collect(self.SHA)
        payload = tar_bytes(files)
        return remote.unpack(payload, self.SHA, hashlib.sha256(payload).hexdigest())

    def test_package_deterministic_and_secrets_excluded(self):
        with tempfile.TemporaryDirectory() as temporary:
            first, second = Path(temporary) / "a.tgz", Path(temporary) / "b.tgz"
            package_backend.build(first, self.SHA)
            package_backend.build(second, self.SHA)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            with tarfile.open(first) as archive:
                names = archive.getnames()
                self.assertFalse(any(".env" in name or name.endswith("/config.php") for name in names))
                self.assertTrue(all(name == "manifest.json" or name.startswith(("public/", "private/backend/")) for name in names))

    def test_traversal_symlink_and_unknown_files_rejected(self):
        for name in ("../secret", "/absolute", "public/api/../../config.php", "public/config.php"):
            payload = tar_bytes({name: b"bad"})
            with self.assertRaises(ValueError):
                remote.unpack(payload, self.SHA, hashlib.sha256(payload).hexdigest())
        payload = tar_bytes({}, symlink="public/api/index.php")
        with self.assertRaises(ValueError):
            remote.unpack(payload, self.SHA, hashlib.sha256(payload).hexdigest())

    def test_manifest_and_digest_tampering_rejected(self):
        files = package_backend.collect(self.SHA)
        files["public/app.js"] += b"tamper"
        payload = tar_bytes(files)
        with self.assertRaises(ValueError):
            remote.unpack(payload, self.SHA, hashlib.sha256(payload).hexdigest())
        with self.assertRaises(ValueError):
            remote.unpack(payload, self.SHA, "0" * 64)

    def fixture(self, directory):
        site = Path(directory) / "site"
        private = site / "relationship-reset-private"
        public = site / "public_html"
        public.mkdir(parents=True)
        private.mkdir()
        (private / "shared").mkdir()
        (private / "shared" / "config.php").write_text("PRIVATE_CONFIGURATION_SENTINEL")
        (public / "index.html").write_text("OLD_HOME")
        (public / "unrelated.txt").write_text("KEEP")
        php = Path(directory) / "fake-php"
        php.write_text("#!/usr/bin/env python3\nimport sys,json,os\nfrom pathlib import Path\nif sys.argv[1]=='-l' or (sys.argv[1]=='-r' and len(sys.argv)==3): sys.exit(0)\np=Path(sys.argv[-1]);r=json.loads((p.parents[2]/'relationship-reset-private/current/backend/release.json').read_text())\nprint(json.dumps(dict(status='failed' if os.environ.get('RR_TEST_FAIL') else 'ok',mode='staging',release=r['release'])))\n")
        php.chmod(0o700)
        return private, public, php

    def test_success_preserves_unmanaged_files_and_config(self):
        files, manifest = self.package()
        with tempfile.TemporaryDirectory() as directory:
            private, public, php = self.fixture(directory)
            backup = remote.install(private, files, manifest, str(php))
            self.assertEqual((public / "unrelated.txt").read_text(), "KEEP")
            self.assertEqual((private / "shared/config.php").read_text(), "PRIVATE_CONFIGURATION_SENTINEL")
            self.assertEqual((private / "backups" / backup / "public/index.html").read_text(), "OLD_HOME")
            self.assertEqual(os.readlink(private / "current"), "releases/" + self.SHA)

    def test_health_failure_restores_exact_previous_files_and_link(self):
        files, manifest = self.package()
        with tempfile.TemporaryDirectory() as directory:
            private, public, php = self.fixture(directory)
            old_release = "b" * 40
            (private / "releases" / old_release).mkdir(parents=True)
            (private / "current").symlink_to("releases/" + old_release)
            os.environ["RR_TEST_FAIL"] = "1"
            try:
                with self.assertRaises(RuntimeError):
                    remote.install(private, files, manifest, str(php))
            finally:
                del os.environ["RR_TEST_FAIL"]
            self.assertEqual((public / "index.html").read_text(), "OLD_HOME")
            self.assertFalse((public / "api/index.php").exists())
            self.assertEqual(os.readlink(private / "current"), "releases/" + old_release)

    def test_symlink_or_old_index_php_refused_before_mutation(self):
        files, manifest = self.package()
        with tempfile.TemporaryDirectory() as directory:
            private, public, php = self.fixture(directory)
            (public / "api").symlink_to(private)
            with self.assertRaises(ValueError):
                remote.install(private, files, manifest, str(php))
            self.assertEqual((public / "index.html").read_text(), "OLD_HOME")
            (public / "api").unlink()
            (public / "index.php").write_text("BEGET_PLACEHOLDER")
            with self.assertRaises(ValueError):
                remote.install(private, files, manifest, str(php))
            self.assertEqual((public / "index.php").read_text(), "BEGET_PLACEHOLDER")


if __name__ == "__main__":
    unittest.main(verbosity=2)
