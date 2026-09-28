import importlib.util
from email.message import Message
from types import SimpleNamespace
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


upgrade = module('payments_upgrade_under_test', 'upgrade-payments-vps.py')
backup = module('payments_backup_under_test', 'backup-vps.py')
smoke = module('payments_smoke_under_test', 'smoke-payments-vps.py')
deploy = module('payments_deploy_under_test', 'autodeploy-vps.py')


class BuildAndSchemaBoundaryTest(unittest.TestCase):
    def candidate_fixture(self):
        import hashlib
        blobs = {}

        def entry(content):
            oid = hashlib.sha1(content).hexdigest()
            blobs[oid] = content
            return ('100644', 'blob', oid)

        baseline = {path: entry(b'pass\n' if name.endswith('.py') else name.encode())
                    for name, path in upgrade.SOURCES.items() if name != 'smoke-payments-vps.py'}
        baseline['backend-kotlin/pom.xml'] = entry(b'<project>unchanged</project>')
        baseline['backend/migrations/001_initial.sql'] = entry(b'initial schema, unchanged')
        baseline['backend-kotlin/src/main/resources/client-migrations/002_client_rehearsal.sql'] = entry(b'client schema, unchanged')
        baseline['backend-kotlin/src/main/resources/client-migrations/003_client_workspace.sql'] = entry(b'workspace schema, unchanged')
        baseline['backend-kotlin/scripts/upgrade-workspace-vps.py'] = entry(b'# prior upgrader, unchanged')
        baseline['backend-kotlin/Caddyfile'] = entry(b'@api path /owner /owner/*\n')
        baseline['backend-kotlin/src/main/kotlin/Application.kt'] = entry(b'// synthetic build source')
        candidate = dict(baseline)
        candidate['backend-kotlin/Caddyfile'] = entry(b'@api path /owner /owner/* /payments/prodamus/webhook\n')
        candidate[upgrade.SOURCES['smoke-payments-vps.py']] = entry(b'pass\n')
        candidate[upgrade.MIGRATION] = entry(b'\n'.join(
            ('CREATE TABLE IF NOT EXISTS ' + table + ' (id CHAR(32));').encode()
            for table in backup.PAYMENT_TABLES))

        class Core:
            def blob(self, item):
                return blobs[item[2]]

        return Core(), baseline, candidate, entry

    def test_source_boundary_accepts_only_new_additive004(self):
        core, baseline, candidate, entry = self.candidate_fixture()
        sources, paths = upgrade.validate_sources(core, baseline, candidate)
        self.assertIn('smoke-payments-vps.py', sources)
        self.assertIn(upgrade.MIGRATION, paths)
        changes = [
            ('backend/migrations/001_initial.sql', b'changed001'),
            ('backend-kotlin/src/main/resources/client-migrations/002_client_rehearsal.sql', b'changed002'),
            ('backend-kotlin/src/main/resources/client-migrations/003_client_workspace.sql', b'changed003'),
            ('backend-kotlin/scripts/upgrade-workspace-vps.py', b'changed prior upgrader'),
            ('backend-kotlin/pom.xml', b'changed pom'),
            ('backend-kotlin/Caddyfile', b'reverse_proxy all_routes'),
            ('backend-kotlin/compose.yaml', b'changed compose'),
            ('backend-kotlin/src/main/resources/client-migrations/005_extra.sql', b'CREATE TABLE another (id INT);'),
            (upgrade.MIGRATION, b'DROP TABLE rr_cases;'),
        ]
        for path, content in changes:
            with self.subTest(path=path):
                changed = dict(candidate, **{path: entry(content)})
                with self.assertRaises(upgrade.UpgradeFailure):
                    upgrade.validate_sources(core, baseline, changed)

    def test_source_boundary_rejects_checkout_secret_paths_and_links(self):
        core, baseline, candidate, entry = self.candidate_fixture()
        for path, value in (
                ('backend-kotlin/.secrets/operator-token', entry(b'not a real secret')),
                ('backend-kotlin/.env', entry(b'override')),
                ('backend-kotlin/src/link', ('120000', 'blob', entry(b'/etc')[2]))):
            with self.subTest(path=path):
                with self.assertRaises(upgrade.UpgradeFailure):
                    upgrade.validate_sources(core, baseline, dict(candidate, **{path: value}))

    def test_pom_must_be_byte_identical(self):
        old = b'<project>already reviewed resources</project>'
        upgrade.validate_pom(old, old)
        with self.assertRaises(upgrade.UpgradeFailure):
            upgrade.validate_pom(old, old + b'\n')

    def test_eleven_fifteen_and_partial_payment_tables_are_preserved(self):
        db = backup.Database('test-only')
        for tables in (backup.TABLES + backup.CLIENT_TABLES + backup.WORKSPACE_TABLES,
                       backup.TABLES + backup.CLIENT_TABLES + backup.WORKSPACE_TABLES + backup.PAYMENT_TABLES,
                       backup.TABLES + backup.CLIENT_TABLES + backup.WORKSPACE_TABLES + backup.PAYMENT_TABLES[:1]):
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
                                  tuple(sorted(backup.TABLES + backup.CLIENT_TABLES + backup.WORKSPACE_TABLES + backup.PAYMENT_TABLES)))

    def test_value_difference_with_same_tables_is_rejected(self):
        class FakeDB:
            def __init__(self):
                self.results = iter(('same', 'changed-one-client-value', 'same'))
                self.names = []

            def require_tables(self, name, expected=None):
                return tuple(sorted(backup.TABLES + backup.CLIENT_TABLES + backup.WORKSPACE_TABLES + backup.PAYMENT_TABLES))

            def require_plain_schema(self):
                pass

            def fingerprint(self, name, directory, tables):
                assert len(tables) == 15
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
                                       CONFIG=self.config, JOURNAL=self.state / 'payments-upgrade.json', TARGETS=targets)
        self.constants.start()
        self.boundary = patch.object(upgrade, 'protected')
        self.boundary.start()  # Paths are disposable test fixtures, not production paths.
        self.secrets = patch.object(upgrade, 'secret_hashes', return_value={'unchanged': 'synthetic-hash'})
        self.secrets.start()
        self.old = {}
        for name, path in targets.items():
            if name == 'smoke-payments-vps.py':
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
                    assert command[-1] == '--require-payments'
                    test.events.append('post-backup')
                elif str(test.ops / 'smoke-payments-vps.py') in command:
                    test.events.append('payment-smoke')
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
                test.events.extend(('legacy-smoke', 'client-smoke', 'workspace-smoke'))

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

    def test_backup_precedes_schema_and_switch_and_four_smokes_precede_commit(self):
        with patch.object(upgrade, 'run_migration', side_effect=lambda *a: self.events.append('migration')), \
                patch.object(upgrade, 'restart_services', side_effect=lambda *a: self.events.append('restart')), \
                patch.object(upgrade, 'load_core', return_value=self.core), \
                patch.object(upgrade, 'restore_timer', side_effect=lambda *a: self.events.append('timer')):
            upgrade.apply(self.core, self.journal, self.sources)
        sequence = ['pre-backup', 'migration', 'checkout', 'environment', 'restart',
                    'runtime-check', 'legacy-smoke', 'client-smoke', 'workspace-smoke', 'payment-smoke', 'post-backup', 'timer']
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
        self.assertTrue((self.ops / 'client-admin-vps.py').exists())
        self.assertTrue((self.ops / 'smoke-client-vps.py').exists())
        self.assertTrue((self.ops / 'smoke-workspace-vps.py').exists())
        self.assertFalse((self.ops / 'smoke-payments-vps.py').exists())
        self.assertFalse(upgrade.JOURNAL.exists())
        self.assertLess(self.events.index('workspace-smoke'), self.events.index('timer'))


class PaymentDeploymentBoundaryTest(unittest.TestCase):
    def test_new_payment_helper_and_schema_cannot_change_in_ordinary_deploy(self):
        baseline = {path: ('100644', 'blob', 'a' * 40) for path in deploy.REQUIRED_FROZEN}
        baseline.update({
            'backend/migrations/001_initial.sql': ('100644', 'blob', 'b' * 40),
            upgrade.MIGRATION: ('100644', 'blob', 'c' * 40),
            'backend-kotlin/src/main/kotlin/Application.kt': ('100644', 'blob', 'd' * 40),
        })
        for path in (upgrade.MIGRATION, 'backend-kotlin/scripts/smoke-payments-vps.py',
                     'backend-kotlin/scripts/client-admin-vps.py'):
            with self.subTest(path=path):
                changed = dict(baseline, **{path: ('100644', 'blob', 'e' * 40)})
                with self.assertRaises(deploy.DeployFailure):
                    deploy.validate_candidate_tree(baseline, changed)

    def test_migration_is_explicit_oneoff_without_ports_or_payment_configuration(self):
        journal = {'directory': str(upgrade.STATE / ('payments-upgrade-' + 'a' * 32)),
                   'candidate_release': 'b' * 40}
        core = SimpleNamespace(DOCKER=['docker', '--config', '/private/config', '--host', 'unix:///var/run/docker.sock'],
                               DOMAIN='api-staging.poslessory.ru', PROJECT='relationship-reset-kotlin-staging',
                               clean_environment=lambda: {'PATH': '/bin'}, run=lambda *a, **kw: b'')
        with patch.object(upgrade.subprocess, 'run', return_value=SimpleNamespace(returncode=0)) as call, \
                patch.object(upgrade, 'cleanup_migration') as cleanup:
            upgrade.run_migration(core, journal)
        command = call.call_args.args[0]
        environment = call.call_args.kwargs['env']
        self.assertEqual(command[-2:], ['api', 'migrate-payments'])
        self.assertIn('--no-deps', command)
        self.assertNotIn('--service-ports', command)
        self.assertEqual(environment, {'PATH': '/bin', 'RR_RELEASE': 'b' * 40, 'RR_DOMAIN': core.DOMAIN})
        cleanup.assert_called_once_with(core, journal)

    def test_migration_failure_still_cleans_its_own_disposable_container(self):
        journal = {'directory': str(upgrade.STATE / ('payments-upgrade-' + 'a' * 32)),
                   'candidate_release': 'b' * 40}
        core = SimpleNamespace(DOCKER=['docker'], DOMAIN='api-staging.poslessory.ru', PROJECT='test',
                               clean_environment=lambda: {}, run=lambda *a, **kw: b'')
        with patch.object(upgrade.subprocess, 'run', return_value=SimpleNamespace(returncode=1)), \
                patch.object(upgrade, 'cleanup_migration') as cleanup:
            with self.assertRaisesRegex(upgrade.UpgradeFailure, 'payments_migration_failed'):
                upgrade.run_migration(core, journal)
        cleanup.assert_called_once_with(core, journal)


class DisabledPaymentSmokeTest(unittest.TestCase):
    def expected(self):
        return [(200, {'status': 'ok', 'mode': 'staging', 'release': 'b' * 40}),
                (503, {'error': 'payments_disabled'}),
                (405, {'error': 'method_not_allowed'}),
                (401, {'error': 'unauthorized'})]

    def test_smoke_only_reads_or_probes_disabled_callback(self):
        with patch.object(smoke.urllib.request, 'build_opener', return_value=object()), \
                patch.object(smoke, 'request', side_effect=self.expected()) as request:
            smoke.verify('b' * 40)
        self.assertEqual([call.args[1:] for call in request.call_args_list], [
            ('/api/health.php',), ('/payments/prodamus/webhook', 'POST'),
            ('/payments/prodamus/webhook',), ('/client/orders',)])

    def test_enabled_callback_wrong_release_and_public_orders_are_rejected(self):
        for index, unexpected in ((0, (200, {'status': 'ok', 'mode': 'staging', 'release': 'c' * 40})),
                                  (1, (200, {'paid': True})), (2, (200, {})),
                                  (3, (200, {'order': {'id': 'private'}}))):
            with self.subTest(index=index):
                responses = self.expected()
                responses[index] = unexpected
                with patch.object(smoke.urllib.request, 'build_opener', return_value=object()), \
                        patch.object(smoke, 'request', side_effect=responses), self.assertRaises(smoke.SmokeFailure):
                    smoke.verify('b' * 40)

    def test_smoke_rejects_order_write_and_other_destination_before_network(self):
        opener = SimpleNamespace(open=lambda *a, **kw: self.fail('Unexpected network call'))
        for path, method in (('/client/orders', 'POST'), ('/client/orders', 'DELETE'),
                             ('/payments/prodamus/webhook?key=unsafe', 'POST'),
                             ('https://example.invalid', 'GET')):
            with self.subTest(path=path, method=method), self.assertRaises(smoke.SmokeFailure):
                smoke.request(opener, path, method)

    def test_cookie_and_oversized_reply_are_rejected_without_reflecting_body(self):
        class Response:
            status = 503

            def __init__(self, cookie=False, oversized=False):
                self.headers = Message()
                self.headers['Cache-Control'] = 'no-store'
                self.headers['X-Content-Type-Options'] = 'nosniff'
                if cookie:
                    self.headers['Set-Cookie'] = 'unexpected=synthetic-secret'
                self.oversized = oversized

            def __enter__(self):
                return self

            def __exit__(self, *args):
                pass

            def read(self, limit):
                return b'x' * (smoke.MAX_RESPONSE + 1) if self.oversized else b'{"error":"payments_disabled"}'

        for response, code in ((Response(cookie=True), 'anonymous_cookie'),
                               (Response(oversized=True), 'response_size')):
            opener = SimpleNamespace(open=lambda *a, **kw: response)
            with self.assertRaisesRegex(smoke.SmokeFailure, code):
                smoke.request(opener, '/payments/prodamus/webhook', 'POST')
        self.assertIsNone(smoke.NoRedirect().redirect_request(None, None, 302, 'redirect', {}, 'https://example.invalid'))


if __name__ == '__main__':
    unittest.main()
