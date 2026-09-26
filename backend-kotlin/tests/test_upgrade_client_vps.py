import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET


SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


upgrade = module('upgrade_under_test', 'upgrade-client-vps.py')
backup = module('backup_under_test', 'backup-vps.py')


class BuildAndSchemaBoundaryTest(unittest.TestCase):
    def test_only_resource_addition_is_accepted(self):
        old = b'''<project xmlns="http://maven.apache.org/POM/4.0.0"><build><resources>
          <resource><directory>../backend</directory></resource></resources>
          <plugins><plugin><version>1</version></plugin></plugins></build></project>'''
        new = old.replace(b'</resources>', b'<resource><directory>src/main/resources</directory>'
                          b'<filtering>false</filtering></resource></resources>')
        upgrade.validate_pom(old, new)
        for changed in (new.replace(b'<version>1', b'<version>2'),
                        new.replace(b'<filtering>false', b'<filtering>true'),
                        new.replace(b'src/main/resources', b'/root')):
            with self.assertRaises(upgrade.UpgradeFailure):
                upgrade.validate_pom(old, changed)

    def test_three_seven_and_partial_additive_tables_are_preserved(self):
        db = backup.Database('test-only')
        for tables in (backup.TABLES, backup.TABLES + backup.CLIENT_TABLES,
                       backup.TABLES + backup.CLIENT_TABLES[:1]):
            expected = tuple(sorted(tables))
            with patch.object(db, 'query', return_value=('\n'.join(expected) + '\n').encode()):
                self.assertEqual(expected, db.require_tables(backup.DATABASE))
                self.assertEqual(expected, db.require_tables('rr_restore_test_' + 'a' * 24, expected))
        for tables in (backup.TABLES + ('unknown_private_table',), backup.TABLES[:2]):
            with patch.object(db, 'query', return_value=('\n'.join(sorted(tables)) + '\n').encode()):
                with self.assertRaises(backup.BackupFailure):
                    db.require_tables(backup.DATABASE)

    def test_changed_restore_table_set_is_rejected(self):
        db = backup.Database('test-only')
        with patch.object(db, 'query', return_value=('\n'.join(backup.TABLES) + '\n').encode()):
            with self.assertRaises(backup.BackupFailure):
                db.require_tables('rr_restore_test_' + 'a' * 24,
                                  tuple(sorted(backup.TABLES + backup.CLIENT_TABLES)))

    def test_value_difference_with_same_tables_is_rejected(self):
        class FakeDB:
            def __init__(self):
                self.results = iter(('same', 'changed-one-client-value', 'same'))
                self.names = []

            def require_tables(self, name, expected=None):
                return tuple(sorted(backup.TABLES + backup.CLIENT_TABLES))

            def require_plain_schema(self):
                pass

            def fingerprint(self, name, directory, tables):
                assert len(tables) == 7
                return next(self.results)

            def execute(self, client, *args, stdin=None, stdout=None):
                if stdout:
                    stdout.write(b'private synthetic dump')

            def query(self, sql):
                self.names.append(sql)

        with tempfile.TemporaryDirectory() as directory:
            db = FakeDB()
            with self.assertRaisesRegex(backup.BackupFailure, 'restored_values_differ'):
                backup.verify_backup(db, Path(directory), 'rr_restore_test_' + 'a' * 24)
            self.assertTrue((Path(directory) / 'staging.sql').exists())
            self.assertTrue(all('DROP' not in sql and backup.DATABASE not in sql for sql in db.names))


class UpgradeOperationOrderTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.app, self.ops, self.state = root / 'app', root / 'ops', root / 'state'
        for path in (self.app, self.ops, self.state):
            path.mkdir(mode=0o700)
        self.config = root / 'config.json'
        targets = {name: self.ops / name for name in upgrade.SOURCES}
        targets.update(environment=self.app / '.env', config=self.config)
        self.constants = patch.multiple(upgrade, APP=self.app, OPS=self.ops, STATE=self.state,
                                       CONFIG=self.config, JOURNAL=self.state / 'client-upgrade.json', TARGETS=targets)
        self.constants.start()
        self.boundary = patch.object(upgrade, 'protected')
        self.boundary.start()  # Paths are disposable test fixtures, not production paths.
        self.secrets = patch.object(upgrade, 'secret_hashes', return_value={'unchanged': 'synthetic-hash'})
        self.secrets.start()
        self.old = {}
        for name, path in targets.items():
            if name in ('smoke-client-vps.py', 'client-admin-vps.py'):
                continue
            value = ('old ' + name).encode()
            path.write_bytes(value)
            path.chmod(0o700 if name.endswith('.py') else 0o600)
            self.old[name] = value
        self.events = []
        self.head = upgrade.EXPECTED_INFRA
        self.candidate = 'b' * 40
        self.image = 'sha256:' + 'c' * 64
        self.new_image = 'sha256:' + 'd' * 64
        self.sources = {name: ('new ' + name).encode() for name in upgrade.SOURCES}
        test = self

        class Core:
            DOCKER = ['docker', '--config', '/private/docker-config', '--host', 'unix:///var/run/docker.sock']
            PROJECT = 'relationship-reset-kotlin-staging'

            def run(self, command, **kwargs):
                if 'candidate-backup.py' in ' '.join(command):
                    test.events.append('pre-backup')
                elif str(test.ops / 'backup-vps.py') in command:
                    test.events.append('post-backup')
                elif str(test.ops / 'smoke-client-vps.py') in command:
                    test.events.append('client-smoke')
                elif 'inspect' in command:
                    return test.image.encode()
                return b''

            def ensure_head_unchanged(self, candidate):
                return True

            def green_run(self, candidate):
                return {'success': True}

            def write_env(self, candidate):
                test.events.append('environment')
                upgrade.atomic(test.app / '.env', candidate.encode())

            def atomic_json(self, path, content):
                upgrade.json_write(path, content)

            def validate_runtime(self, release, image):
                test.events.append('runtime-check')

            def verify_release(self, release):
                test.events.append('legacy-smoke')

            def validate_trusted_assets(self, entries):
                pass

            def read_json(self, path):
                return json.loads(path.read_text())

            def validate_host(self, config):
                assert config['baseline_sha'] == test.candidate

            def tree_entries(self, candidate):
                return {}

        self.core = Core()

        def live_git(core, *args):
            if args[0] == 'checkout':
                self.events.append('checkout')
                self.head = args[-1]
            if args[0] == 'rev-parse':
                return self.head.encode()
            return b''

        self.git = patch.object(upgrade, 'live_git', side_effect=live_git)
        self.git.start()
        self.journal = upgrade.checkpoint(self.core, self.candidate, self.new_image,
                                          self.image, True, self.sources)

    def tearDown(self):
        self.git.stop()
        self.secrets.stop()
        self.boundary.stop()
        self.constants.stop()
        self.temp.cleanup()

    def test_backup_precedes_schema_and_switch_and_both_smokes_precede_commit(self):
        with patch.object(upgrade, 'run_migration', side_effect=lambda *a: self.events.append('migration')), \
                patch.object(upgrade, 'restart_services', side_effect=lambda *a: self.events.append('restart')), \
                patch.object(upgrade, 'load_core', return_value=self.core), \
                patch.object(upgrade, 'restore_timer', side_effect=lambda *a: self.events.append('timer')):
            upgrade.apply(self.core, self.journal, self.sources)
        sequence = ['pre-backup', 'migration', 'checkout', 'environment', 'restart',
                    'runtime-check', 'legacy-smoke', 'client-smoke', 'post-backup', 'timer']
        self.assertEqual(sequence, self.events)
        self.assertFalse(upgrade.JOURNAL.exists())
        self.assertEqual(json.loads(self.config.read_text())['infra_sha'], self.candidate)
        self.assertEqual((self.ops / 'client-admin-vps.py').read_bytes(), self.sources['client-admin-vps.py'])

    def test_backup_failure_stops_before_migration_and_preserves_checkpoint(self):
        with patch.object(self.core, 'run', side_effect=RuntimeError('synthetic backup failure')), \
                patch.object(upgrade, 'run_migration') as migration:
            with self.assertRaises(RuntimeError):
                upgrade.apply(self.core, self.journal, self.sources)
        migration.assert_not_called()
        self.assertTrue(upgrade.JOURNAL.exists())
        self.assertEqual(self.head, upgrade.EXPECTED_INFRA)

    def test_rollback_restores_snapshot_and_reloads_old_smoke_before_timer(self):
        for name, content in self.sources.items():
            upgrade.atomic(self.ops / name, content, 0o700 if name.endswith('.py') else 0o600)
        self.head = self.candidate
        with patch.object(upgrade, 'cleanup_migration', side_effect=lambda *a: self.events.append('clean-oneoff')), \
                patch.object(upgrade, 'load_core', side_effect=lambda: self.core) as load, \
                patch.object(upgrade, 'restart_services', side_effect=lambda *a: self.events.append('restart')), \
                patch.object(upgrade, 'restore_timer', side_effect=lambda *a: self.events.append('timer')):
            upgrade.rollback(self.core, self.journal)
        load.assert_called_once()
        self.assertEqual(self.head, upgrade.EXPECTED_INFRA)
        for name, content in self.old.items():
            self.assertEqual(upgrade.TARGETS[name].read_bytes(), content)
        self.assertFalse((self.ops / 'client-admin-vps.py').exists())
        self.assertFalse((self.ops / 'smoke-client-vps.py').exists())
        self.assertFalse(upgrade.JOURNAL.exists())
        self.assertLess(self.events.index('legacy-smoke'), self.events.index('timer'))


if __name__ == '__main__':
    unittest.main()
