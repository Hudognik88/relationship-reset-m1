"""Failure-safety checks for the staging updater; no Docker or network access."""
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import stat
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "autodeploy-vps.py"
SPEC = importlib.util.spec_from_file_location("autodeploy_vps_under_test", SCRIPT)
deploy = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = deploy
SPEC.loader.exec_module(deploy)
SHA = "a" * 40
OTHER_SHA = "b" * 40


def ci_run(run_id=100, attempt=1, status="completed", conclusion="success", sha=SHA):
    return {
        "id": run_id, "run_attempt": attempt, "head_sha": sha,
        "workflow_id": 367556683,
        "head_branch": "kotlin-backend-20260926", "event": "push",
        "path": ".github/workflows/kotlin.yml", "status": status,
        "conclusion": conclusion,
        "repository": {"full_name": "Hudognik88/relationship-reset-m1"},
        "head_repository": {"full_name": "Hudognik88/relationship-reset-m1"},
    }


def baseline_tree():
    paths = [
        "backend-kotlin/pom.xml", "backend-kotlin/Dockerfile",
        "backend-kotlin/Dockerfile.dockerignore", "backend-kotlin/compose.yaml",
        "backend-kotlin/Caddyfile", "backend-kotlin/README_RU.md",
        "backend-kotlin/src/main/kotlin/ru/poslesorry/backend/Application.kt",
        "backend-kotlin/scripts/smoke-vps.py", "backend-kotlin/scripts/autodeploy-vps.py",
        "backend/migrations/001_initial.sql", ".github/workflows/kotlin.yml",
    ]
    paths = sorted(set(paths) | deploy.REQUIRED_FROZEN)
    return {path: ("100644", "blob", f"{index:040x}") for index, path in enumerate(paths, 1)}


class CiApprovalTests(unittest.TestCase):
    def test_current_success_can_be_selected(self):
        good = ci_run()
        self.assertEqual(deploy.select_run({"workflow_runs": [good]}, SHA), good)

    def test_latest_failed_run_never_falls_back_to_older_success(self):
        for conclusion in ("failure", "cancelled", "timed_out", "action_required", "skipped", "neutral"):
            with self.subTest(conclusion=conclusion):
                runs = [ci_run(100), ci_run(101, conclusion=conclusion)]
                self.assertIsNone(deploy.select_run({"workflow_runs": runs}, SHA))

    def test_latest_pending_run_never_falls_back_to_older_success(self):
        for status in ("queued", "in_progress", "waiting", "requested", "pending"):
            with self.subTest(status=status):
                runs = [ci_run(100), ci_run(101, status=status, conclusion=None)]
                self.assertIsNone(deploy.select_run({"workflow_runs": runs}, SHA))

    def test_new_rerun_attempt_invalidates_old_success(self):
        for status, conclusion in (("in_progress", None), ("completed", "failure")):
            with self.subTest(status=status):
                runs = [ci_run(100, 1), ci_run(100, 2, status, conclusion)]
                self.assertIsNone(deploy.select_run({"workflow_runs": runs}, SHA))

    def test_success_for_another_commit_does_not_authorize_candidate(self):
        self.assertIsNone(deploy.select_run({"workflow_runs": [ci_run(101, sha=OTHER_SHA)]}, SHA))

    def test_changed_head_is_not_approved(self):
        with patch.object(deploy, "branch_head", return_value=OTHER_SHA):
            self.assertFalse(deploy.ensure_head_unchanged(SHA))


class DeploymentBoundaryTests(unittest.TestCase):
    def test_regular_source_edit_is_allowed(self):
        baseline = baseline_tree()
        candidate = copy.deepcopy(baseline)
        source = "backend-kotlin/src/main/kotlin/ru/poslesorry/backend/Application.kt"
        candidate[source] = ("100644", "blob", "f" * 40)
        selected = deploy.validate_candidate_tree(baseline, candidate)
        self.assertIn(source, selected)

    def test_infrastructure_and_migration_changes_require_manual_release(self):
        for path in ("backend-kotlin/compose.yaml", "backend-kotlin/Dockerfile",
                     "backend-kotlin/Dockerfile.dockerignore", "backend-kotlin/Caddyfile",
                     "backend/migrations/001_initial.sql", ".github/workflows/kotlin.yml",
                     "backend-kotlin/scripts/smoke-vps.py"):
            with self.subTest(path=path):
                baseline = baseline_tree()
                candidate = copy.deepcopy(baseline)
                candidate[path] = ("100644", "blob", "f" * 40)
                with self.assertRaises(deploy.DeployFailure):
                    deploy.validate_candidate_tree(baseline, candidate)

    def test_added_migration_is_not_an_automatic_application_update(self):
        baseline = baseline_tree()
        candidate = copy.deepcopy(baseline)
        candidate["backend/migrations/002_next.sql"] = ("100644", "blob", "f" * 40)
        with self.assertRaises(deploy.DeployFailure):
            deploy.validate_candidate_tree(baseline, candidate)

    def test_symlinks_and_submodules_in_source_are_rejected(self):
        for mode, kind in (("120000", "blob"), ("160000", "commit")):
            with self.subTest(mode=mode):
                baseline = baseline_tree()
                candidate = copy.deepcopy(baseline)
                candidate["backend-kotlin/src/main/kotlin/injected"] = (mode, kind, "f" * 40)
                with self.assertRaises(deploy.DeployFailure):
                    deploy.validate_candidate_tree(baseline, candidate)


class LastMinuteApprovalTests(unittest.TestCase):
    def execute_main(self, approvals, head_unchanged=True):
        with tempfile.TemporaryDirectory() as directory, contextlib.ExitStack() as stack:
            state = Path(directory)
            (state / "source.git").mkdir(mode=0o700)
            values = {
                "STATE": state,
                "read_json": {"enabled": True, "baseline_sha": OTHER_SHA, "infra_sha": OTHER_SHA},
                "validate_host": None, "protected": None, "recover_transaction": False,
                "current_release": OTHER_SHA, "branch_head": SHA, "fetch_commit": None,
                "tree_entries": baseline_tree(), "validate_candidate_tree": [],
                "validate_trusted_assets": None, "image_id": "sha256:" + "1" * 64,
                "verify_release": None, "validate_runtime": None, "build_image": "sha256:" + "2" * 64,
                "private_backup": state / "backup.sql", "ensure_head_unchanged": head_unchanged,
            }
            for name, value in values.items():
                if name == "STATE":
                    stack.enter_context(patch.object(deploy, name, value))
                else:
                    stack.enter_context(patch.object(deploy, name, return_value=value))
            stack.enter_context(patch.object(deploy.sys, "argv", [str(SCRIPT)]))
            stack.enter_context(patch.object(deploy.os, "geteuid", return_value=0))
            stack.enter_context(patch.object(deploy.os, "umask", return_value=0o077))
            stack.enter_context(patch.object(deploy.signal, "signal"))
            stack.enter_context(patch.object(deploy, "run", side_effect=AssertionError("Unexpected command")))
            green = stack.enter_context(patch.object(deploy, "green_run", side_effect=approvals))
            promote = stack.enter_context(patch.object(deploy, "promote"))
            with contextlib.redirect_stdout(io.StringIO()):
                deploy.main()
            return promote.call_count, green.call_count

    def test_head_that_changed_during_build_is_never_promoted(self):
        promotions, _ = self.execute_main([ci_run()], head_unchanged=False)
        self.assertEqual(promotions, 0)

    def test_green_ci_becoming_pending_or_failed_during_build_is_not_promoted(self):
        promotions, approvals = self.execute_main([ci_run(), None])
        self.assertEqual(promotions, 0)
        self.assertEqual(approvals, 2)


class TransactionTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        root = Path(self.temporary.name)
        self.state, self.app = root / "state", root / "app"
        self.state.mkdir(mode=0o700)
        self.app.mkdir(mode=0o700)
        self.previous_image = "sha256:" + "1" * 64
        self.candidate_image = "sha256:" + "2" * 64
        self.stack.enter_context(patch.object(deploy, "STATE", self.state))
        self.stack.enter_context(patch.object(deploy, "APP", self.app))
        # Test transaction behavior without assuming that the CI test user is root.
        self.stack.enter_context(patch.object(deploy, "protected"))
        self.commands, self.compositions, self.verified = [], [], []
        self.run = self.stack.enter_context(patch.object(deploy, "run", side_effect=self.command))
        self.compose = self.stack.enter_context(patch.object(deploy, "compose", side_effect=self.switch))
        self.verify = self.stack.enter_context(patch.object(deploy, "verify_release", side_effect=self.health))
        self.runtime = self.stack.enter_context(patch.object(deploy, "validate_runtime"))
        self.backup = self.stack.enter_context(patch.object(deploy, "private_backup"))
        self.fail_old_health = False
        deploy.write_env(OTHER_SHA)

    def command(self, command, **kwargs):
        self.commands.append(command)
        if command[-1] == self.previous_image and "inspect" in command:
            return (self.previous_image + "\n").encode()
        if "tag" in command:
            return b""
        raise AssertionError("Unexpected command during rollback")

    def switch(self, *arguments):
        self.assertTrue((self.state / "transaction.json").is_file(), "Switch without durable journal")
        self.compositions.append(arguments)
        self.assertEqual(arguments[0], "up")
        self.assertEqual(arguments[-1], "api")
        self.assertIn("--no-deps", arguments)
        self.assertIn("--no-build", arguments)
        self.assertIn("never", arguments)

    def health(self, release):
        self.verified.append(release)
        if release == SHA or self.fail_old_health:
            raise RuntimeError("simulated confidential service diagnostic")

    def promote(self):
        with contextlib.redirect_stdout(io.StringIO()):
            deploy.promote(SHA, OTHER_SHA, self.previous_image, self.candidate_image)

    def test_failed_smoke_restores_recorded_image_and_environment_then_clears_journal(self):
        original_write = deploy.write_env
        writes = []

        def checked_write(release):
            journal = json.loads((self.state / "transaction.json").read_text())
            self.assertEqual(journal["previous_release"], OTHER_SHA)
            self.assertEqual(journal["previous_image"], self.previous_image)
            writes.append(release)
            original_write(release)

        with patch.object(deploy, "write_env", side_effect=checked_write):
            with self.assertRaisesRegex(deploy.DeployFailure, "^candidate_failed_previous_api_restored$"):
                self.promote()
        self.assertEqual(writes, [SHA, OTHER_SHA])
        self.assertEqual(self.verified, [SHA, OTHER_SHA])
        self.assertEqual(deploy.current_release(), OTHER_SHA)
        self.assertEqual(len(self.compositions), 2)
        self.assertFalse((self.state / "transaction.json").exists())
        result = json.loads((self.state / "last-result.json").read_text())
        self.assertEqual(result["status"], "rolled_back")
        self.assertEqual(result["failed_release"], SHA)
        self.assertIn(deploy.DOCKER + ["image", "tag", self.previous_image,
                                     "relationship-reset-api:" + OTHER_SHA], self.commands)
        self.backup.assert_not_called()
        # The only external rollback commands were immutable-image inspect and tag.
        self.assertEqual(len(self.commands), 2)
        self.assertTrue(all("image" in command for command in self.commands))

    def test_failed_rollback_preserves_journal_for_recovery(self):
        self.fail_old_health = True
        with self.assertRaisesRegex(deploy.DeployFailure, "^rollback_incomplete_journal_preserved$"):
            self.promote()
        journal = json.loads((self.state / "transaction.json").read_text())
        self.assertEqual(journal["previous_image"], self.previous_image)
        self.assertFalse((self.state / "last-result.json").exists())

    def test_interrupted_transaction_recovers_previous_release_without_database_restore(self):
        journal = {"version": 1, "phase": "switched", "previous_release": OTHER_SHA,
                   "candidate_release": SHA, "previous_image": self.previous_image,
                   "candidate_image": self.candidate_image}
        deploy.atomic_json(self.state / "transaction.json", journal)
        deploy.write_env(SHA)
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertTrue(deploy.recover_transaction())
        self.assertEqual(deploy.current_release(), OTHER_SHA)
        self.assertEqual(self.verified, [OTHER_SHA])
        self.assertFalse((self.state / "transaction.json").exists())
        self.assertEqual(len(self.compositions), 1)
        self.assertEqual(len(self.commands), 2)
        self.backup.assert_not_called()


class SecretRedactionTests(unittest.TestCase):
    def test_command_failure_does_not_expose_output(self):
        secret = b"private-operator-token-must-never-appear"
        result = subprocess.CompletedProcess(["synthetic-command"], 1, stdout=secret, stderr=secret)
        with patch.object(deploy.subprocess, "run", return_value=result):
            with self.assertRaises(deploy.DeployFailure) as failure:
                deploy.run(["synthetic-command"])
        self.assertEqual(str(failure.exception), "command_failed")
        self.assertNotIn(secret.decode(), str(failure.exception))

    def test_timeout_does_not_expose_secret_output_or_command(self):
        secret = "private-operator-token-must-never-appear"
        error = subprocess.TimeoutExpired([secret], 1, output=secret.encode(), stderr=secret.encode())
        with patch.object(deploy.subprocess, "run", side_effect=error):
            with self.assertRaises(deploy.DeployFailure) as failure:
                deploy.run(["synthetic-command"])
        self.assertEqual(str(failure.exception), "command_unavailable_or_timeout")
        self.assertNotIn(secret, str(failure.exception))


class IsolatedDockerConfigTests(unittest.TestCase):
    def test_build_inspect_compose_backup_and_rollback_avoid_root_home_config(self):
        expected = ["docker", "--config", str(deploy.STATE / "docker-config"),
                    "--host", "unix:///var/run/docker.sock"]
        seen = []
        image = "sha256:" + "1" * 64
        with tempfile.TemporaryDirectory() as directory, contextlib.ExitStack() as stack:
            root = Path(directory)
            for name in ("STATE", "OPS", "APP", "BACKUPS"):
                path = root / name.lower()
                path.mkdir(mode=0o700)
                stack.enter_context(patch.object(deploy, name, path))
            (deploy.OPS / "Dockerfile").write_text("FROM scratch\n")
            stack.enter_context(patch.object(deploy, "protected"))

            def command(argv, **kwargs):
                self.assertEqual(argv[:len(expected)], expected)
                tail = argv[len(expected):]
                seen.append(tuple(tail[:2]))
                if tail[:2] == ["image", "inspect"]:
                    return (image + "\n").encode()
                if tail[0] == "ps":
                    return b"bbbbbbbbbbbb\n" if any("service=mysql" in v for v in tail) else b"aaaaaaaaaaaa\n"
                if tail[0] == "inspect":
                    metadata = {"Config": {"Image": "relationship-reset-api:" + SHA, "Labels": {
                        "com.docker.compose.project.working_dir": str(deploy.APP)}},
                        "Image": image, "State": {"Running": True}, "HostConfig": {"PortBindings": {}},
                        "Mounts": [
                            {"Destination": "/run/secrets/db_root_password", "Type": "bind", "RW": False,
                             "Source": str(deploy.APP / ".secrets/db-root-password")},
                            {"Destination": "/var/lib/mysql", "Type": "volume",
                             "Name": deploy.PROJECT + "_mysql_data"}]}
                    return json.dumps([metadata]).encode()
                if tail[0] == "exec":
                    kwargs["stdout"].write(b"-- synthetic dump\n")
                return b""

            stack.enter_context(patch.object(deploy, "run", side_effect=command))
            source = "backend-kotlin/src/main/kotlin/Application.kt"
            with patch.object(deploy, "blob", return_value=b"fun main() {}\n"), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(deploy.build_image(SHA, {source: ("100644", "blob", SHA)}, [source]), image)
            deploy.compose("up", "--no-deps", "api")
            deploy.validate_runtime(SHA, image)
            self.assertGreater(deploy.private_backup(SHA).stat().st_size, 0)
            with patch.object(deploy, "write_env"), patch.object(deploy, "verify_release"), \
                 patch.object(deploy, "validate_runtime"), patch.object(deploy, "atomic_json"), \
                 patch.object(deploy, "clear_transaction"), contextlib.redirect_stdout(io.StringIO()):
                deploy.rollback_transaction({"version": 1, "phase": "switched", "previous_release": OTHER_SHA,
                    "candidate_release": SHA, "previous_image": image, "candidate_image": "sha256:" + "2" * 64})
        self.assertEqual({part[0] for part in seen}, {"build", "image", "compose", "ps", "inspect", "exec"})
        self.assertIn(("image", "tag"), seen)

    def test_validate_host_requires_private_docker_config_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            config_path = Path(directory) / "docker-config"
            real_protected = deploy.protected

            def validate(path, **kwargs):
                if path == config_path:
                    self.assertEqual(kwargs, {"directory": True, "mode": 0o700})
                    real_protected(path, **kwargs)

            config = {"version": 1, "enabled": True, "baseline_sha": SHA, "infra_sha": SHA}
            with patch.object(deploy, "DOCKER_CONFIG", config_path), \
                 patch.object(deploy, "protected", side_effect=validate), \
                 patch.object(Path, "lstat", return_value=SimpleNamespace(st_uid=0, st_mode=stat.S_IFDIR | 0o755)), \
                 patch.object(deploy, "run") as command:
                with self.assertRaisesRegex(deploy.DeployFailure, "^unexpected_host_permissions$"):
                    deploy.validate_host(config)
                command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
