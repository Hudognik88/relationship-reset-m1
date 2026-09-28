import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('rr_setup', Path(__file__).parents[1] / 'scripts/setup-autodeploy-vps.py')
setup = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(setup)


class SetupWriteTest(unittest.TestCase):
    def test_new_file_is_complete_and_private(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'config.json'
            setup.install_file(path, b'{"enabled":true}\n', 0o600)
            self.assertEqual(path.read_bytes(), b'{"enabled":true}\n')
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertEqual(list(Path(directory).iterdir()), [path])

    def test_identical_rerun_preserves_inode_and_mtime(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'config.json'
            setup.install_file(path, b'initial', 0o600)
            before = path.stat()
            with patch.object(setup, 'protected'):
                setup.install_file(path, b'initial', 0o600)
            self.assertEqual((path.stat().st_ino, path.stat().st_mtime_ns),
                             (before.st_ino, before.st_mtime_ns))

    def test_existing_different_content_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'config.json'
            path.write_bytes(b'owner configuration')
            with patch.object(setup, 'protected'), self.assertRaises(RuntimeError):
                setup.install_file(path, b'new configuration', 0o600)
            self.assertEqual(path.read_bytes(), b'owner configuration')

    def test_atomic_create_failure_leaves_no_partial_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'config.json'
            with patch.object(os, 'link', side_effect=OSError('disk failure')):
                with self.assertRaises(OSError):
                    setup.install_file(path, b'new configuration', 0o600)
            self.assertEqual(list(Path(directory).iterdir()), [])


if __name__ == '__main__':
    unittest.main()
