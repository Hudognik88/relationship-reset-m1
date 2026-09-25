#!/usr/bin/env python3
"""Offline setup safety checks; never contacts Beget or a real database."""
import contextlib
import getpass
import hashlib
import io
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import warnings

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import configure_beget as setup


class ConfigureBegetTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.site = Path(self.temporary.name) / "site"
        (self.site / "public_html").mkdir(parents=True)
        self.api = self.site / "public_html" / "api"
        self.api.mkdir()
        self.htaccess = self.api / ".htaccess"
        self.private = self.site / "relationship-reset-private"
        self.backend = self.private / "releases" / ("a" * 40) / "backend"
        (self.backend / "bin").mkdir(parents=True)
        (self.backend / "bootstrap.php").write_text("<?php\n")
        (self.private / "current").symlink_to("releases/" + "a" * 40)
        self.shared = self.private / "shared"
        self.shared.mkdir(mode=0o700)
        self.config_path = self.shared / "config.php"
        self.token_path = self.shared / "operator-token.txt"
        self.password = secrets.token_urlsafe(24) + "'\\$\n"
        self.calls = []
        self.probe_fails = False
        self.smoke_fails = False
        self.smoke_timeout = False
        self.smoke_status = ""
        original_umask = os.umask(0o077)
        self.addCleanup(os.umask, original_umask)

    def fake_php(self, command, **kwargs):
        # Copy env at call time; the helper later adds the token for smoke only.
        captured = {**kwargs, "env": kwargs["env"].copy()}
        self.calls.append((command[:], captured))
        if command[1] == "-r":
            payload = json.loads(kwargs["input"])
            if self.probe_fails:
                return subprocess.CompletedProcess(command, 1, self.password, self.password)
            # Serialization is exercised separately with real PHP. This fixture
            # deliberately contains the password to catch accidental printing.
            output = "" if payload["resume"] else "<?php /* " + self.password + " */ return [];\n"
        else:
            if self.smoke_timeout and command[1].endswith("/smoke.php"):
                raise subprocess.TimeoutExpired(command, 90)
            if self.smoke_fails and command[1].endswith("/smoke.php"):
                error = self.password + "\n" + self.smoke_status + kwargs["env"]["RR_OPERATOR_TOKEN"]
                return subprocess.CompletedProcess(command, 1, self.password, error)
            output = "successful private step\n"
        return subprocess.CompletedProcess(command, 0, output, "")

    def invoke(self, tty=True, password_effect=None, forward=False):
        output, errors = io.StringIO(), io.StringIO()
        arguments = ["configure_beget.py", str(self.site), "movereed_rrstage"]
        if forward:
            arguments.append("--forward-authorization")
        with mock.patch.object(sys, "argv", arguments), \
             mock.patch.object(sys.stdin, "isatty", return_value=tty), \
             mock.patch.object(setup.getpass, "getpass", return_value=self.password,
                               side_effect=password_effect) as password_prompt, \
             mock.patch.object(setup.subprocess, "run", side_effect=self.fake_php), \
             contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            status = setup.main()
        return status, output.getvalue() + errors.getvalue(), password_prompt.call_count

    def existing_config(self):
        token = secrets.token_urlsafe(32)
        setup.create_private(self.config_path, "EXISTING_CONFIG_MUST_NOT_CHANGE")
        setup.create_private(self.token_path, token + "\n")
        return token

    def test_success_keeps_secrets_private_and_limits_token_to_smoke(self):
        with mock.patch.dict(os.environ, {"RR_OPERATOR_TOKEN": "inherited-token",
                                          "RR_CONFIG_FILE": "/wrong/config.php",
                                          "RR_LOCAL_TEST": "1"}):
            status, output, prompts = self.invoke()
        self.assertEqual(status, 0)
        self.assertEqual(prompts, 1)
        self.assertIn("ГОТОВО", output)
        token = self.token_path.read_text().strip()
        self.assertNotIn(self.password, output)
        self.assertNotIn(token, output)
        self.assertIn(self.password, self.config_path.read_text())
        for path in (self.config_path, self.token_path):
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.shared.stat().st_mode & 0o777, 0o700)
        self.assertEqual(len(self.calls), 3)
        payload = json.loads(self.calls[0][1]["input"])
        self.assertEqual(payload["config"]["database"]["host"], "localhost")
        self.assertEqual(payload["config"]["database"]["user"], "movereed_rrstage")
        self.assertEqual(payload["hash"], hashlib.sha256(token.encode()).hexdigest())
        for command, kwargs in self.calls:
            self.assertNotIn(self.password, " ".join(command))
            self.assertNotIn(token, " ".join(command))
            self.assertEqual(kwargs["env"]["RR_CONFIG_FILE"], str(self.config_path))
            self.assertNotIn("RR_LOCAL_TEST", kwargs["env"])
        for _, kwargs in self.calls[:2]:
            self.assertNotIn("RR_OPERATOR_TOKEN", kwargs["env"])
        self.assertEqual(self.calls[2][1]["env"]["RR_OPERATOR_TOKEN"], token)

    def test_wrong_password_writes_no_credentials_and_hides_php_errors(self):
        self.probe_fails = True
        status, output, _ = self.invoke()
        self.assertEqual(status, 1)
        self.assertFalse(self.config_path.exists())
        self.assertFalse(self.token_path.exists())
        self.assertNotIn(self.password, output)
        self.assertEqual(len(self.calls), 1)

    def test_resume_checks_existing_files_without_prompt_or_overwrite(self):
        token = self.existing_config()
        before = self.config_path.read_bytes(), self.token_path.read_bytes()
        status, output, prompts = self.invoke(tty=False)
        self.assertEqual(status, 0)
        self.assertEqual(prompts, 0)
        self.assertEqual(before, (self.config_path.read_bytes(), self.token_path.read_bytes()))
        payload = json.loads(self.calls[0][1]["input"])
        self.assertTrue(payload["resume"])
        self.assertNotIn("config", payload)
        self.assertEqual(payload["hash"], hashlib.sha256(token.encode()).hexdigest())
        self.assertNotIn(token, output)

    def test_smoke_failure_keeps_valid_configuration_for_retry(self):
        self.smoke_fails = True
        status, output, _ = self.invoke()
        self.assertEqual(status, 1)
        before = self.config_path.read_bytes(), self.token_path.read_bytes()
        self.assertNotIn(self.password, output)
        self.assertNotIn(self.token_path.read_text().strip(), output)
        self.smoke_fails = False
        status, _, prompts = self.invoke(tty=False)
        self.assertEqual(status, 0)
        self.assertEqual(prompts, 0)
        self.assertEqual(before, (self.config_path.read_bytes(), self.token_path.read_bytes()))

    def test_first_setup_requires_tty_and_fails_if_hidden_input_unavailable(self):
        status, _, prompts = self.invoke(tty=False)
        self.assertEqual(status, 1)
        self.assertEqual(prompts, 0)
        def no_hidden_input(_prompt):
            warnings.warn("Cannot control echo", getpass.GetPassWarning)
            return self.password
        status, output, _ = self.invoke(password_effect=no_hidden_input)
        self.assertEqual(status, 1)
        self.assertNotIn(self.password, output)
        self.assertFalse(self.config_path.exists())
        self.assertFalse(self.token_path.exists())
        self.assertEqual(self.calls, [])

    def test_smoke_diagnostic_exposes_only_allowlisted_status_numbers(self):
        self.smoke_fails = True
        self.smoke_status = "Smoke failed: health=200, unauthenticated=401, readiness=401."
        status, output, _ = self.invoke()
        self.assertEqual(status, 1)
        self.assertIn(self.smoke_status, output)
        self.assertNotIn(self.password, output)
        self.assertNotIn(self.token_path.read_text().strip(), output)

    def test_orphan_token_refuses_regeneration(self):
        token = secrets.token_urlsafe(32)
        setup.create_private(self.token_path, token + "\n")
        status, output, prompts = self.invoke()
        self.assertEqual(status, 1)
        self.assertEqual(prompts, 0)
        self.assertFalse(self.config_path.exists())
        self.assertEqual(self.token_path.read_text(), token + "\n")
        self.assertNotIn(token, output)

    def test_symlinked_shared_and_credentials_are_refused(self):
        outside = Path(self.temporary.name) / "outside"
        outside.mkdir()
        self.shared.rmdir()
        self.shared.symlink_to(outside, target_is_directory=True)
        status, _, prompts = self.invoke()
        self.assertEqual((status, prompts), (1, 0))
        self.assertEqual(list(outside.iterdir()), [])
        self.shared.unlink()
        self.shared.mkdir(mode=0o700)
        outside_config = outside / "config.php"
        setup.create_private(outside_config, "DO_NOT_CHANGE")
        self.config_path.symlink_to(outside_config)
        status, _, prompts = self.invoke()
        self.assertEqual((status, prompts), (1, 0))
        self.assertEqual(outside_config.read_text(), "DO_NOT_CHANGE")
        self.assertEqual(self.calls, [])

    def test_create_private_never_overwrites_an_existing_file(self):
        self.existing_config()
        before = self.config_path.read_bytes()
        with self.assertRaises(FileExistsError):
            setup.create_private(self.config_path, "REPLACEMENT")
        self.assertEqual(self.config_path.read_bytes(), before)

    def test_default_does_not_change_host_configuration(self):
        original = b"# unrelated hosting rule\r\nOptions -Indexes\r\n"
        self.htaccess.write_bytes(original)
        self.htaccess.chmod(0o640)
        status, _, _ = self.invoke()
        self.assertEqual(status, 0)
        self.assertEqual(self.htaccess.read_bytes(), original)
        self.assertEqual(self.htaccess.stat().st_mode & 0o777, 0o640)
        self.assertEqual(list(self.shared.glob("api-htaccess-before-*.bak")), [])

    def test_forwarding_preserves_original_bytes_permissions_and_private_backup(self):
        self.existing_config()
        credentials = self.config_path.read_bytes(), self.token_path.read_bytes()
        original = b"# hosting settings\r\nOptions -Indexes"
        self.htaccess.write_bytes(original)
        self.htaccess.chmod(0o640)
        status, output, prompts = self.invoke(forward=True)
        self.assertEqual((status, prompts), (0, 0))
        self.assertIn("ГОТОВО", output)
        self.assertEqual(self.htaccess.read_bytes(), original + b"\n" + setup.AUTH_BLOCK)
        self.assertEqual(self.htaccess.stat().st_mode & 0o777, 0o640)
        backups = list(self.shared.glob("api-htaccess-before-*.bak"))
        self.assertEqual(len(backups), 1)
        self.assertEqual(backups[0].read_bytes(), original)
        self.assertEqual(backups[0].stat().st_mode & 0o777, 0o600)
        self.assertEqual(credentials, (self.config_path.read_bytes(), self.token_path.read_bytes()))
        # A repeated opt-in does not append a second directive or backup.
        before = self.htaccess.read_bytes()
        status, _, _ = self.invoke(forward=True)
        self.assertEqual(status, 0)
        self.assertEqual(self.htaccess.read_bytes(), before)
        self.assertEqual(list(self.shared.glob("api-htaccess-before-*.bak")), backups)

    def test_forwarding_creates_api_file_only_and_is_idempotent(self):
        status, _, _ = self.invoke(forward=True)
        self.assertEqual(status, 0)
        self.assertEqual(self.htaccess.read_bytes(), setup.AUTH_BLOCK)
        self.assertEqual(self.htaccess.stat().st_mode & 0o777, 0o644)
        self.assertFalse((self.site / "public_html" / ".htaccess").exists())
        self.assertEqual(list(self.shared.glob("api-htaccess-before-*.bak")), [])
        status, _, prompts = self.invoke(forward=True)
        self.assertEqual((status, prompts), (0, 0))
        self.assertEqual(self.htaccess.read_bytes(), setup.AUTH_BLOCK)
        # A later unrelated failure must preserve a block that already existed.
        self.smoke_fails = True
        status, _, _ = self.invoke(forward=True)
        self.assertEqual(status, 1)
        self.assertEqual(self.htaccess.read_bytes(), setup.AUTH_BLOCK)

    def test_forwarding_rolls_back_existing_and_new_files_on_401_and_500(self):
        self.existing_config()
        credentials = self.config_path.read_bytes(), self.token_path.read_bytes()
        self.smoke_fails = True
        for response in (401, 500):
            for original in (None, b"Options -Indexes\n"):
                with self.subTest(response=response, existing=original is not None):
                    if original is not None:
                        self.htaccess.write_bytes(original)
                        self.htaccess.chmod(0o640)
                    self.smoke_status = f"Smoke failed: health=200, unauthenticated=401, readiness={response}."
                    status, output, prompts = self.invoke(forward=True)
                    self.assertEqual((status, prompts), (1, 0))
                    self.assertIn("изменение .htaccess отменено", output)
                    if original is None:
                        self.assertFalse(self.htaccess.exists())
                    else:
                        self.assertEqual(self.htaccess.read_bytes(), original)
                        self.assertEqual(self.htaccess.stat().st_mode & 0o777, 0o640)
                        self.htaccess.unlink()
                    self.assertEqual(credentials, (self.config_path.read_bytes(), self.token_path.read_bytes()))
                    self.assertNotIn(self.token_path.read_text().strip(), output)

    def test_forwarding_rolls_back_on_smoke_timeout(self):
        self.smoke_timeout = True
        status, output, _ = self.invoke(forward=True)
        self.assertEqual(status, 1)
        self.assertFalse(self.htaccess.exists())
        self.assertIn("изменение .htaccess отменено", output)
        self.assertTrue(self.config_path.exists())

    def test_forwarding_refuses_symlinked_api_and_nonregular_htaccess(self):
        self.existing_config()
        outside = Path(self.temporary.name) / "outside-api"
        outside.mkdir()
        outside_file = outside / ".htaccess"
        outside_file.write_bytes(b"UNCHANGED")
        self.htaccess.symlink_to(outside_file)
        status, _, _ = self.invoke(forward=True)
        self.assertEqual(status, 1)
        self.assertEqual(outside_file.read_bytes(), b"UNCHANGED")
        self.htaccess.unlink()
        self.htaccess.mkdir()
        status, _, _ = self.invoke(forward=True)
        self.assertEqual(status, 1)
        self.htaccess.rmdir()
        self.api.rmdir()
        self.api.symlink_to(outside, target_is_directory=True)
        status, _, _ = self.invoke(forward=True)
        self.assertEqual(status, 1)
        self.assertEqual(outside_file.read_bytes(), b"UNCHANGED")
        self.assertEqual(list(self.shared.glob("api-htaccess-before-*.bak")), [])

    def test_forwarding_refuses_unexpected_marker_without_modification(self):
        original = setup.AUTH_BEGIN + b"unexpected directive\n" + setup.AUTH_END
        self.htaccess.write_bytes(original)
        status, output, _ = self.invoke(forward=True)
        self.assertEqual(status, 1)
        self.assertIn("изменённый блок", output)
        self.assertEqual(self.htaccess.read_bytes(), original)
        self.assertEqual(list(self.shared.glob("api-htaccess-before-*.bak")), [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
