"""Failure-safety checks for private demo activation; no provider, Docker or network."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import stat
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


deploy = module('demo_deploy_test', 'autodeploy-vps.py')
configure = module('demo_configuration_test', 'configure-prodamus-demo-vps.py')
upgrade = module('demo_upgrade_test', 'upgrade-demo-config-vps.py')
smoke = module('demo_smoke_test', 'smoke-payments-vps.py')
DISABLED = b'{"version":1,"mode":"disabled"}\n'
DEMO = b'{"version":1,"mode":"demo","secret":"synthetic-test-key-123"}\n'
SHA = 'a' * 40
IMAGE = 'sha256:' + 'b' * 64


class PrivateConfigurationTests(unittest.TestCase):
    def test_strict_modes_and_no_secret_in_diagnostics(self):
        self.assertEqual(deploy.payment_document(DISABLED)['mode'], 'disabled')
        self.assertEqual(deploy.payment_document(DEMO)['mode'], 'demo')
        for raw in (b'{"version":1,"mode":"live","secret":"synthetic-test-key-123"}',
                    b'{"version":true,"mode":"disabled"}',
                    b'{"version":1.0,"mode":"disabled"}',
                    b'{"version":1,"mode":"disabled","mode":"demo"}',
                    b'{"version":1,"mode":"disabled","secret":"synthetic-test-key-123"}',
                    b'{"version":1,"mode":"demo","secret":"short"}',
                    b'{"version":1,"mode":"demo","secret":"synthetic-test-key-123","sys":null}',
                    b'{"version":1,"mode":"demo","secret":"synthetic-test-key-123","url":"https://evil.invalid"}',
                    b'\xff', b'x' * 2049, '{"version":1,"mode":"disabled","é":1}'.encode('utf-8')):
            with self.subTest(raw=raw), self.assertRaises(deploy.DeployFailure) as failure:
                deploy.payment_document(raw)
            self.assertNotIn('synthetic-test-key-123', str(failure.exception))
            self.assertNotIn('https://evil.invalid', str(failure.exception))

    def test_noninteractive_input_never_reads_key(self):
        core = SimpleNamespace(payment_document=deploy.payment_document)
        with patch.object(configure.sys.stdin, 'isatty', return_value=False), \
                patch.object(configure.getpass, 'getpass') as prompt:
            with self.assertRaisesRegex(configure.ConfigurationFailure, 'interactive_terminal_required'):
                configure.secret_document(core)
        prompt.assert_not_called()

    def test_hidden_confirmation_and_warning_never_fall_back_to_echo(self):
        core = SimpleNamespace(payment_document=deploy.payment_document)
        output = io.StringIO()
        with patch.object(configure.sys.stdin, 'isatty', return_value=True), \
                patch.object(configure.sys.stdout, 'isatty', return_value=True), \
                patch.object(configure.getpass, 'getpass', side_effect=['synthetic-test-key-123'] * 2) as prompt:
            raw = configure.secret_document(core, 'own_merchant')
        self.assertEqual(prompt.call_count, 2)
        self.assertEqual(deploy.payment_document(raw)['sys'], 'own_merchant')
        with patch.object(configure.sys.stdin, 'isatty', return_value=True), \
                patch.object(configure.sys.stdout, 'isatty', return_value=True), \
                patch.object(configure.getpass, 'getpass', side_effect=['one', 'two']):
            with self.assertRaisesRegex(configure.ConfigurationFailure, 'signing_keys_do_not_match'):
                configure.secret_document(core)
        def warning(*args):
            import warnings
            warnings.warn('synthetic no tty', configure.getpass.GetPassWarning)
        with patch.object(configure.sys.stdin, 'isatty', return_value=True), \
                patch.object(configure.sys.stdout, 'isatty', return_value=True), \
                patch.object(configure.getpass, 'getpass', side_effect=warning):
            with self.assertRaises(configure.getpass.GetPassWarning):
                configure.secret_document(core)

    def test_root_private_parent_and_readonly_file_required(self):
        with tempfile.TemporaryDirectory() as directory:
            app = Path(directory)
            secret = app / '.secrets'
            secret.mkdir(mode=0o700)
            path = secret / 'prodamus-demo.json'
            path.write_bytes(DISABLED)
            path.chmod(0o444)
            real_lstat = Path.lstat
            def root_stat(item):
                value = real_lstat(item)
                return SimpleNamespace(st_uid=0, st_mode=value.st_mode)
            with patch.object(deploy, 'APP', app), patch.object(Path, 'lstat', root_stat):
                self.assertEqual(deploy.payment_mode(), 'disabled')
                path.chmod(0o644)
                with self.assertRaisesRegex(deploy.DeployFailure, 'unexpected_host_permissions'):
                    deploy.payment_mode()
                path.chmod(0o444)
                secret.chmod(0o755)
                with self.assertRaisesRegex(deploy.DeployFailure, 'unexpected_host_permissions'):
                    deploy.payment_mode()

    def test_recreate_reopens_atomic_file_mount(self):
        core = SimpleNamespace(compose=lambda *args: None)
        with patch.object(core, 'compose') as compose:
            configure.restart(core)
        self.assertIn('--force-recreate', compose.call_args.args)
        self.assertIn('--no-deps', compose.call_args.args)
        self.assertIn('--no-build', compose.call_args.args)
        self.assertEqual(compose.call_args.args[-1], 'api')


class ExplicitModeSmokeTests(unittest.TestCase):
    def responses(self, enabled):
        return [(200, {'status': 'ok', 'mode': 'staging', 'release': SHA}),
                (401, {'error': 'signature_invalid'}) if enabled else (503, {'error': 'payments_disabled'}),
                (405, {'error': 'method_not_allowed'}), (401, {'error': 'unauthorized'})]

    def test_demo_requires_signature_guard_and_disabled_stays_disabled(self):
        for mode, enabled in (('demo', True), ('disabled', False)):
            with patch.object(smoke, 'request', side_effect=self.responses(enabled)) as request:
                smoke.verify(SHA, mode)
            self.assertEqual(request.call_args_list[1].args[1:], ('/payments/prodamus/webhook', 'POST'))
            with patch.object(smoke, 'request', side_effect=self.responses(not enabled)):
                with self.assertRaises(smoke.SmokeFailure):
                    smoke.verify(SHA, mode)

    def test_unsigned_success_and_public_orders_never_pass(self):
        for index, response in ((1, (200, {'accepted': True})), (3, (200, {'order': {'id': 'private'}}))):
            replies = self.responses(True)
            replies[index] = response
            with patch.object(smoke, 'request', side_effect=replies), self.assertRaises(smoke.SmokeFailure):
                smoke.verify(SHA, 'demo')

    def test_deployer_passes_private_expected_mode_not_secret(self):
        with patch.object(deploy, 'payment_mode', return_value='demo'), patch.object(deploy, 'run') as run:
            deploy.verify_release(SHA)
        command = run.call_args.args[0]
        self.assertEqual(command[-2:], ['--expect-mode', 'demo'])
        self.assertNotIn('synthetic-test-key-123', repr(run.call_args_list))


class ConfigurationTransactionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        root = Path(self.temp.name)
        self.state = root / 'state'
        self.state.mkdir(mode=0o700)
        self.payment = root / 'prodamus-demo.json'
        self.payment.write_bytes(DISABLED)
        self.payment.chmod(0o444)
        self.stack.enter_context(patch.multiple(configure, STATE=self.state, PAYMENT=self.payment,
                                               JOURNAL=self.state / 'demo-configuration.json'))
        self.stack.enter_context(patch.object(configure, 'protected'))
        self.events = []
        test = self
        class Core:
            payment_document = staticmethod(deploy.payment_document)
            def payment_mode(self):
                return self.payment_document(test.payment.read_bytes())['mode']
            def current_release(self):
                return SHA
            def compose(self, *args):
                test.events.append('recreate-' + self.payment_mode())
                assert '--force-recreate' in args
            def validate_runtime(self, release, image):
                assert (release, image) == (SHA, IMAGE)
                test.events.append('runtime')
            def verify_release(self, release):
                test.events.append('smoke-' + self.payment_mode())
            def run(self, command, **kw):
                assert command == ['systemctl', 'start', configure.UNIT + '.timer']
                test.events.append('timer')
        self.core = Core()
        self.journal = configure.checkpoint(self.core, SHA, IMAGE, True)

    def test_key_activation_is_verified_before_timer_and_output_is_redacted(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            configure.apply(self.core, self.journal, DEMO)
        self.assertEqual(self.events, ['recreate-demo', 'runtime', 'smoke-demo', 'timer'])
        self.assertFalse(configure.JOURNAL.exists())
        self.assertEqual(stat.S_IMODE(self.payment.stat().st_mode), 0o444)
        self.assertNotIn('synthetic-test-key-123', output.getvalue())
        self.assertEqual((Path(self.journal['directory']) / 'previous-config.json').read_bytes(), DISABLED)

    def test_failed_verification_can_restore_previous_private_file(self):
        with patch.object(self.core, 'verify_release', side_effect=RuntimeError('synthetic sensitive diagnostic')):
            with self.assertRaises(RuntimeError):
                configure.apply(self.core, self.journal, DEMO)
        self.assertTrue(configure.JOURNAL.exists())
        self.assertEqual(self.core.payment_mode(), 'demo')
        with contextlib.redirect_stdout(io.StringIO()):
            configure.rollback(self.core, configure.read_journal(self.core))
        self.assertEqual(self.payment.read_bytes(), DISABLED)
        self.assertFalse(configure.JOURNAL.exists())
        self.assertEqual(self.events[-4:], ['recreate-disabled', 'runtime', 'smoke-disabled', 'timer'])

    def test_interrupted_enable_restores_config_without_database_restore(self):
        configure.atomic(self.payment, DEMO, 0o444)
        with contextlib.redirect_stdout(io.StringIO()):
            configure.rollback(self.core, configure.read_journal(self.core))
        self.assertEqual(self.payment.read_bytes(), DISABLED)
        self.assertTrue(all('database' not in event for event in self.events))

    def test_failed_rollback_keeps_journal_and_timer_stopped(self):
        configure.atomic(self.payment, DEMO, 0o444)
        with patch.object(self.core, 'verify_release', side_effect=RuntimeError('synthetic fail')):
            with self.assertRaises(RuntimeError):
                configure.rollback(self.core, self.journal)
        self.assertTrue(configure.JOURNAL.exists())
        self.assertNotIn('timer', self.events)

    def test_checkpoint_recovery_rejects_changed_release_or_untrusted_path(self):
        with patch.object(self.core, 'current_release', return_value='c' * 40):
            with self.assertRaisesRegex(configure.ConfigurationFailure, 'configuration_runtime_changed'):
                configure.rollback(self.core, self.journal)
        altered = dict(self.journal, directory='/tmp/outside-configuration-boundary')
        configure.json_write(configure.JOURNAL, altered)
        with self.assertRaisesRegex(configure.ConfigurationFailure, 'configuration_checkpoint_path'):
            configure.read_journal(self.core)


class DemoUpgradeSourceTests(unittest.TestCase):
    def fixture(self):
        blobs = {}
        def entry(data):
            key = hashlib.sha1(data).hexdigest()
            blobs[key] = data
            return ('100644', 'blob', key)
        baseline = {path: entry(b'pass\n' if name.endswith('.py') else name.encode())
                    for name, path in upgrade.SOURCES.items() if name != 'configure-prodamus-demo-vps.py'}
        compose = (SCRIPTS.parent / 'compose.yaml').read_bytes()
        old_compose = compose.replace(b'      RR_PRODAMUS_CONFIG_FILE: /run/secrets/prodamus_demo\n', b'') \
            .replace(b'secrets: [operator_token_sha256, db_password, prodamus_demo]',
                     b'secrets: [operator_token_sha256, db_password]') \
            .removesuffix(b'  prodamus_demo:\n    file: ./.secrets/prodamus-demo.json\n')
        baseline['backend-kotlin/compose.yaml'] = entry(old_compose)
        baseline['backend-kotlin/pom.xml'] = entry(b'unchanged-pom')
        baseline['backend-kotlin/Caddyfile'] = entry(b'unchanged-proxy')
        baseline['.github/workflows/kotlin.yml'] = entry(b'unchanged-ci')
        baseline['backend/migrations/001_initial.sql'] = entry(b'unchanged-001')
        for name in ('002_client_rehearsal.sql', '003_client_workspace.sql', '004_client_payments.sql'):
            baseline['backend-kotlin/src/main/resources/client-migrations/' + name] = entry(name.encode())
        baseline['backend-kotlin/src/main/kotlin/Config.kt'] = entry(b'old config')
        baseline['backend-kotlin/scripts/upgrade-payments-vps.py'] = entry(b'prior upgrade unchanged')
        candidate = dict(baseline)
        candidate['backend-kotlin/compose.yaml'] = entry(compose)
        candidate['backend-kotlin/scripts/configure-prodamus-demo-vps.py'] = entry(b'pass\n')
        candidate['backend-kotlin/src/main/kotlin/Config.kt'] = entry(b'new config')
        return SimpleNamespace(blob=lambda item: blobs[item[2]]), baseline, candidate, entry

    def test_only_exact_mount_delta_and_unchanged_schema_are_allowed(self):
        core, baseline, candidate, entry = self.fixture()
        sources, paths = upgrade.validate_sources(core, baseline, candidate)
        self.assertIn('configure-prodamus-demo-vps.py', sources)
        self.assertIn('backend-kotlin/src/main/kotlin/Config.kt', paths)
        for path, value in (
            ('backend-kotlin/compose.yaml', b'ports: [3306:3306]'),
            ('backend-kotlin/Caddyfile', b'reverse_proxy anywhere'),
            ('backend-kotlin/pom.xml', b'changed build'),
            ('.github/workflows/kotlin.yml', b'disabled verification'),
            ('backend-kotlin/scripts/upgrade-payments-vps.py', b'changed recovery'),
            ('backend/migrations/001_initial.sql', b'changed legacy schema'),
            ('backend-kotlin/src/main/resources/client-migrations/004_client_payments.sql', b'changed payments'),
            ('backend-kotlin/src/main/resources/client-migrations/005_new.sql', b'CREATE TABLE new_table'),
            ('backend-kotlin/.secrets/prodamus-demo.json', DEMO),
        ):
            with self.subTest(path=path), self.assertRaises(upgrade.UpgradeFailure):
                upgrade.validate_sources(core, baseline, dict(candidate, **{path: entry(value)}))

    def test_pending_manual_journal_blocks_routine_recovery_and_deployment(self):
        for name in ('demo-configuration.json', 'demo-config-upgrade.json'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory, contextlib.ExitStack() as stack:
                state = Path(directory)
                (state / name).write_text('{}')
                stack.enter_context(patch.object(deploy, 'STATE', state))
                stack.enter_context(patch.object(deploy, 'read_json', return_value={'enabled': True}))
                stack.enter_context(patch.object(deploy, 'validate_host'))
                stack.enter_context(patch.object(deploy, 'protected'))
                stack.enter_context(patch.object(deploy.os, 'geteuid', return_value=0))
                stack.enter_context(patch.object(deploy.signal, 'signal'))
                stack.enter_context(patch.object(deploy.sys, 'argv', ['autodeploy-vps.py']))
                recover = stack.enter_context(patch.object(deploy, 'recover_transaction'))
                head = stack.enter_context(patch.object(deploy, 'branch_head'))
                with self.assertRaisesRegex(deploy.DeployFailure, 'manual_recovery_required'):
                    deploy.main()
                recover.assert_not_called()
                head.assert_not_called()

    def test_new_configure_helper_is_frozen_for_routine_deployment(self):
        path = 'backend-kotlin/scripts/configure-prodamus-demo-vps.py'
        self.assertIn(path, deploy.REQUIRED_FROZEN)
        baseline = {key: ('100644', 'blob', SHA) for key in deploy.REQUIRED_FROZEN}
        baseline['backend/migrations/001_initial.sql'] = ('100644', 'blob', SHA)
        with self.assertRaisesRegex(deploy.DeployFailure, 'manual_infrastructure_update_required'):
            deploy.validate_candidate_tree(baseline, dict(baseline, **{path: ('100644', 'blob', 'c' * 40)}))


class DemoUpgradeTransactionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        root = Path(self.temp.name)
        self.app, self.ops, self.state = root / 'app', root / 'ops', root / 'state'
        for path in (self.app, self.ops, self.state):
            path.mkdir(mode=0o700)
        (self.app / '.secrets').mkdir(mode=0o700)
        self.config = root / 'autodeploy.json'
        targets = {name: self.ops / name for name in upgrade.SOURCES}
        targets.update(environment=self.app / '.env', config=self.config,
                       payment=self.app / '.secrets/prodamus-demo.json')
        self.stack.enter_context(patch.multiple(upgrade, APP=self.app, OPS=self.ops, STATE=self.state,
            CONFIG=self.config, JOURNAL=self.state / 'demo-config-upgrade.json', TARGETS=targets))
        self.stack.enter_context(patch.object(upgrade, 'protected'))
        self.stack.enter_context(patch.object(upgrade, 'secret_hashes', return_value={'unchanged': 'synthetic'}))
        self.old = {}
        for name, path in targets.items():
            if name in upgrade.NEW_TARGETS:
                continue
            value = ('old ' + name).encode()
            path.write_bytes(value)
            path.chmod(0o700 if name.endswith('.py') else 0o600)
            self.old[name] = value
        self.sources = {name: ('new ' + name).encode() for name in upgrade.SOURCES}
        self.events = []
        self.head = upgrade.EXPECTED_INFRA
        test = self
        class Core:
            DOCKER = ['docker', '--config', '/private/config', '--host', 'unix:///var/run/docker.sock']
            def run(self, command, **kwargs):
                if 'candidate-backup.py' in ' '.join(command):
                    assert command[-1] == '--require-payments'
                    test.events.append('pre-backup15')
                elif str(test.ops / 'backup-vps.py') in command:
                    assert command[-1] == '--require-payments'
                    test.events.append('post-backup15')
                elif 'inspect' in command:
                    return IMAGE.encode()
                return b''
            def ensure_head_unchanged(self, candidate):
                return True
            def green_run(self, candidate):
                return {'success': True}
            def write_env(self, candidate):
                test.events.append('environment')
                upgrade.atomic(test.app / '.env', candidate.encode())
            def atomic_json(self, path, value):
                upgrade.json_write(path, value)
            def validate_runtime(self, release, image):
                test.events.append('runtime')
            def verify_release(self, release):
                test.events.extend(('legacy-smoke', 'client-smoke', 'workspace-smoke', 'payment-smoke'))
            def payment_mode(self):
                return deploy.payment_document(upgrade.TARGETS['payment'].read_bytes())['mode']
            def validate_trusted_assets(self, entries):
                pass
            def read_json(self, path):
                return json.loads(path.read_text())
            def validate_host(self, config):
                assert config['baseline_sha'] == SHA
            def tree_entries(self, candidate):
                return {}
        self.core = Core()
        def git(core, *args):
            if args[0] == 'checkout':
                self.events.append('checkout')
                self.head = args[-1]
            if args[0] == 'rev-parse':
                return self.head.encode()
            return b''
        self.stack.enter_context(patch.object(upgrade, 'live_git', side_effect=git))
        self.stack.enter_context(patch.object(upgrade, 'load_core', return_value=self.core))
        self.stack.enter_context(patch.object(upgrade, 'restart_services',
            side_effect=lambda *args: self.events.append('restart')))
        self.stack.enter_context(patch.object(upgrade, 'restore_timer',
            side_effect=lambda *args: self.events.append('timer')))
        self.journal = upgrade.checkpoint(self.core, SHA, 'sha256:' + 'c' * 64, IMAGE, True, self.sources)

    def test_fifteen_table_backup_then_disabled_config_and_verification_before_timer(self):
        with contextlib.redirect_stdout(io.StringIO()):
            upgrade.apply(self.core, self.journal, self.sources)
        self.assertEqual(self.events, ['pre-backup15', 'checkout', 'environment', 'restart', 'runtime',
            'legacy-smoke', 'client-smoke', 'workspace-smoke', 'payment-smoke', 'post-backup15', 'timer'])
        self.assertEqual(upgrade.TARGETS['payment'].read_bytes(), DISABLED)
        self.assertEqual(stat.S_IMODE(upgrade.TARGETS['payment'].stat().st_mode), 0o444)
        self.assertFalse(upgrade.JOURNAL.exists())
        self.assertEqual(json.loads(self.config.read_text())['infra_sha'], SHA)
        self.assertEqual(json.loads((self.state / 'last-result.json').read_text())['kind'], 'manual_demo_config_upgrade')

    def test_prebackup_failure_leaves_previous_checkout_and_no_new_private_file(self):
        with patch.object(self.core, 'run', side_effect=RuntimeError('synthetic backup failure')), \
                contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(RuntimeError):
                upgrade.apply(self.core, self.journal, self.sources)
        self.assertEqual(self.head, upgrade.EXPECTED_INFRA)
        self.assertFalse(upgrade.TARGETS['payment'].exists())
        self.assertTrue(upgrade.JOURNAL.exists())
        self.assertNotIn('restart', self.events)

    def test_rollback_removes_only_new_files_and_reloads_old_core(self):
        for name, data in self.sources.items():
            upgrade.atomic(self.ops / name, data, 0o700 if name.endswith('.py') else 0o600)
        upgrade.atomic(upgrade.TARGETS['payment'], DISABLED, 0o444)
        self.head = SHA
        with patch.object(upgrade, 'load_core', return_value=self.core) as load, \
                contextlib.redirect_stdout(io.StringIO()):
            upgrade.rollback(self.core, upgrade.read_journal())
        load.assert_called_once()
        self.assertEqual(self.head, upgrade.EXPECTED_INFRA)
        for name, value in self.old.items():
            self.assertEqual(upgrade.TARGETS[name].read_bytes(), value)
        self.assertFalse(upgrade.TARGETS['payment'].exists())
        self.assertFalse((self.ops / 'configure-prodamus-demo-vps.py').exists())
        self.assertTrue((self.ops / 'smoke-payments-vps.py').exists())
        self.assertFalse(upgrade.JOURNAL.exists())
        self.assertLess(self.events.index('payment-smoke'), self.events.index('timer'))

    def test_incomplete_rollback_preserves_checkpoint_and_timer_stop(self):
        with patch.object(self.core, 'verify_release', side_effect=RuntimeError('synthetic failure')):
            with self.assertRaises(RuntimeError):
                upgrade.rollback(self.core, upgrade.read_journal())
        self.assertTrue(upgrade.JOURNAL.exists())
        self.assertNotIn('timer', self.events)


if __name__ == '__main__':
    unittest.main()
