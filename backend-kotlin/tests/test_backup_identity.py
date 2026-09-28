import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import MagicMock, patch

SPEC = importlib.util.spec_from_file_location('rr_backup', Path(__file__).parents[1] / 'scripts/backup-vps.py')
backup = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(backup)


class BackupIdentityTest(unittest.TestCase):
    def validate(self, *, config_infra='a' * 40, api_release='b' * 40):
        config = MagicMock()
        config.read_text.return_value = json.dumps({'version': 1, 'infra_sha': config_infra})
        container = {'Config': {'Image': 'relationship-reset-api:' + api_release,
            'Env': ['RR_RELEASE=' + api_release],
            'Labels': {'com.docker.compose.project.working_dir': str(backup.APP)}},
            'State': {'Running': True}}
        with patch.object(backup, 'Path', return_value=config), patch.object(backup, 'protected'), \
             patch.object(backup, 'run', side_effect=[b'a' * 40, b'c' * 64,
                                                    json.dumps([container]).encode()]):
            backup.validate_runtime_revision('b' * 40)

    def test_legacy_checkout_matches_runtime(self):
        with patch.object(backup, 'run', return_value=b'a' * 40) as run:
            backup.validate_runtime_revision('a' * 40)
        self.assertEqual(run.call_count, 1)

    def test_stable_infrastructure_and_new_runtime(self):
        self.validate()

    def test_rejects_unrecognised_infrastructure(self):
        with self.assertRaisesRegex(backup.BackupFailure, 'pinned_infrastructure'):
            self.validate(config_infra='d' * 40)

    def test_rejects_different_running_image(self):
        with self.assertRaisesRegex(backup.BackupFailure, 'api_release_identity'):
            self.validate(api_release='d' * 40)


if __name__ == '__main__':
    unittest.main()
