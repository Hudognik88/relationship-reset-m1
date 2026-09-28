#!/usr/bin/env python3
"""Root-only Prodamus DEMO configuration; signing keys are entered invisibly on a TTY.

No live mode, provider request, payment simulation or payment-ledger mutation exists
here. A completed provider checkout must be verified separately.
"""
import argparse
import fcntl
import getpass
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import stat
import sys
import tempfile
import uuid
import warnings

sys.dont_write_bytecode = True
INSTALL = Path('/opt/relationship-reset-kotlin-staging')
APP = INSTALL / 'backend-kotlin'
OPS = Path('/opt/relationship-reset-kotlin-deployer')
STATE = Path('/var/lib/relationship-reset-kotlin-staging-deploy')
CONFIG = Path('/etc/relationship-reset-kotlin-staging/autodeploy.json')
PAYMENT = APP / '.secrets/prodamus-demo.json'
JOURNAL = STATE / 'demo-configuration.json'
UNIT = 'relationship-reset-kotlin-deploy'
SHA = re.compile(r'[a-f0-9]{40}')


class ConfigurationFailure(Exception):
    pass


def require(condition, code):
    if not condition:
        raise ConfigurationFailure(code)


def protected(path, *, directory=False, mode=None):
    info = path.lstat()
    require(info.st_uid == 0 and not stat.S_ISLNK(info.st_mode) and not info.st_mode & 0o022,
            'unsafe_configuration_path')
    require(stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode), 'configuration_path_type')
    if mode is not None:
        require(stat.S_IMODE(info.st_mode) == mode, 'configuration_path_permissions')


def atomic(path, data, mode=0o600):
    if path.exists() or path.is_symlink():
        protected(path)
    descriptor, name = tempfile.mkstemp(prefix='.demo-config-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'wb') as output:
            os.fchmod(output.fileno(), mode)
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(name, path)
        fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def json_write(path, value):
    atomic(path, (json.dumps(value, sort_keys=True) + '\n').encode())


def load_core():
    protected(OPS, directory=True, mode=0o700)
    path = OPS / 'autodeploy-vps.py'
    protected(path, mode=0o700)
    require(path.stat().st_size < 256 * 1024, 'deployer_size')
    spec = importlib.util.spec_from_file_location('rr_demo_trusted_core', path)
    core = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(core)
    require(core.INSTALL == INSTALL and core.OPS == OPS and core.STATE == STATE
            and core.WORKFLOW_ID == 367556683 and core.BRANCH == 'kotlin-backend-20260926'
            and callable(getattr(core, 'payment_document', None)), 'deployer_identity')
    return core


def read_private(core):
    protected(PAYMENT.parent, directory=True, mode=0o700)
    protected(PAYMENT, mode=0o444)
    require(PAYMENT.stat().st_size <= 2048, 'payment_configuration_size')
    with PAYMENT.open("rb") as source:
        raw = source.read(2049)
    core.payment_document(raw)
    return raw


def secret_document(core, sys_name=None):
    require(sys.stdin.isatty() and sys.stdout.isatty(), 'interactive_terminal_required')
    require(sys_name is None or re.fullmatch(r'[A-Za-z0-9_-]{1,64}', sys_name), 'invalid_sys')
    # A failed /dev/tty lookup must never fall back to echoed stdin.
    with warnings.catch_warnings():
        warnings.simplefilter('error', getpass.GetPassWarning)
        first = getpass.getpass('Prodamus DEMO signing key (hidden): ')
        second = getpass.getpass('Repeat signing key (hidden): ')
    require(first == second, 'signing_keys_do_not_match')
    document = {'version': 1, 'mode': 'demo', 'secret': first}
    if sys_name is not None:
        document['sys'] = sys_name
    raw = (json.dumps(document, ensure_ascii=True, sort_keys=True) + '\n').encode()
    core.payment_document(raw)
    return raw


def restart(core):
    # Atomic private-file replacement changes its inode; restart alone is insufficient.
    core.compose('up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--force-recreate',
                 '--wait', '--wait-timeout', '180', 'api')


def checkpoint(core, release, image, active):
    directory = STATE / ('demo-configuration-' + uuid.uuid4().hex)
    directory.mkdir(mode=0o700)
    atomic(directory / 'previous-config.json', read_private(core))
    journal = {'version': 1, 'phase': 'prepared', 'directory': str(directory), 'release': release,
               'image': image, 'timer_active': active}
    json_write(JOURNAL, journal)
    return journal


def read_journal(core):
    protected(JOURNAL, mode=0o600)
    require(JOURNAL.stat().st_size <= 8192, 'configuration_journal_size')
    value = json.loads(JOURNAL.read_text())
    require(isinstance(value, dict) and value.get('version') == 1 and value.get('phase') in
            ('prepared', 'switching', 'verifying', 'committed'), 'configuration_journal_format')
    require(SHA.fullmatch(value.get('release', ''))
            and re.fullmatch(r'sha256:[a-f0-9]{64}', value.get('image', ''))
            and isinstance(value.get('timer_active'), bool), 'configuration_journal_identity')
    directory = Path(value.get('directory', ''))
    require(directory.parent == STATE and re.fullmatch(r'demo-configuration-[a-f0-9]{32}', directory.name),
            'configuration_checkpoint_path')
    protected(directory, directory=True, mode=0o700)
    previous = directory / 'previous-config.json'
    protected(previous, mode=0o600)
    require(previous.stat().st_size <= 2048, 'configuration_checkpoint_size')
    core.payment_document(previous.read_bytes())
    return value


def phase(journal, value):
    journal['phase'] = value
    json_write(JOURNAL, journal)


def finish(journal, outcome):
    json_write(Path(journal['directory']) / 'result.json', dict(journal, outcome=outcome))
    JOURNAL.unlink()
    fd = os.open(STATE, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def restore_timer(core, journal):
    if journal['timer_active']:
        core.run(['systemctl', 'start', UNIT + '.timer'], capture=False)


def rollback(core, journal):
    require(core.current_release() == journal['release'], 'configuration_runtime_changed')
    previous = (Path(journal['directory']) / 'previous-config.json').read_bytes()
    core.payment_document(previous)
    atomic(PAYMENT, previous, 0o444)
    restart(core)
    core.validate_runtime(journal['release'], journal['image'])
    core.verify_release(journal['release'])
    finish(journal, 'rolled_back')
    restore_timer(core, journal)
    print('PASS previous payment configuration restored; mode=' + core.payment_mode(), flush=True)


def apply(core, journal, raw):
    core.payment_document(raw)
    phase(journal, 'switching')
    atomic(PAYMENT, raw, 0o444)
    restart(core)
    phase(journal, 'verifying')
    core.validate_runtime(journal['release'], journal['image'])
    core.verify_release(journal['release'])
    phase(journal, 'committed')
    finish(journal, 'verified')
    restore_timer(core, journal)
    print('PASS payment configuration verified; mode=' + core.payment_mode(), flush=True)
    print('No provider payment was executed by this command.', flush=True)


def interrupted(signum, frame):
    raise ConfigurationFailure('configuration_interrupted')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('enable', 'disable', 'status'))
    parser.add_argument('--sys', dest='sys_name', help='Optional non-secret Prodamus integration identifier')
    args = parser.parse_args()
    require(os.geteuid() == 0, 'root_required')
    require(args.action == 'enable' or args.sys_name is None, 'sys_only_for_enable')
    os.umask(0o077)
    for path in (Path('/opt'), INSTALL, APP, STATE, CONFIG.parent):
        protected(path, directory=True)
    protected(STATE, directory=True, mode=0o700)
    core = load_core()
    if args.action == 'status':
        require(not JOURNAL.exists() and not JOURNAL.is_symlink(), 'configuration_recovery_required')
        for name in ('transaction.json', 'client-upgrade.json', 'workspace-upgrade.json',
                     'payments-upgrade.json', 'demo-config-upgrade.json'):
            require(not (STATE / name).exists() and not (STATE / name).is_symlink(), 'deployment_recovery_required')
        print('mode=' + core.payment_mode())
        return
    active = core.run(['systemctl', 'show', '--property=ActiveState', '--value', UNIT + '.timer']).decode().strip()
    require(active in ('active', 'inactive'), 'timer_state_unexpected')
    core.run(['systemctl', 'stop', UNIT + '.timer'], capture=False)
    descriptor = os.open(STATE / 'deploy.lock', os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    safe_to_resume = True
    try:
        with os.fdopen(descriptor, 'w'):
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            protected(STATE / 'deploy.lock', mode=0o600)
            for item in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
                signal.signal(item, interrupted)
            if JOURNAL.exists() or JOURNAL.is_symlink():
                safe_to_resume = False
                rollback(core, read_journal(core))
                print('Recovered interrupted configuration. Run the requested command again.')
                return
            for name in ('transaction.json', 'client-upgrade.json', 'workspace-upgrade.json',
                         'payments-upgrade.json', 'demo-config-upgrade.json'):
                require(not (STATE / name).exists() and not (STATE / name).is_symlink(), 'deployment_recovery_required')
            config = core.read_json(CONFIG)
            core.validate_host(config)
            core.validate_trusted_assets(core.tree_entries(config['infra_sha']))
            release = core.current_release()
            image = core.image_id(release)
            core.validate_runtime(release, image)
            core.verify_release(release)
            raw = secret_document(core, args.sys_name) if args.action == 'enable' else b'{"version":1,"mode":"disabled"}\n'
            journal = checkpoint(core, release, image, active == 'active')
            safe_to_resume = False
            try:
                apply(core, journal, raw)
            except BaseException:
                if not JOURNAL.exists():
                    raise ConfigurationFailure('verified_configuration_timer_restart_failed') from None
                try:
                    rollback(core, read_journal(core))
                except BaseException:
                    if not JOURNAL.exists():
                        raise ConfigurationFailure('previous_configuration_restored_timer_restart_failed') from None
                    raise ConfigurationFailure('rollback_incomplete_timer_stopped_checkpoint_preserved') from None
                raise ConfigurationFailure('configuration_failed_previous_mode_restored') from None
    finally:
        if safe_to_resume and active == 'active':
            core.run(['systemctl', 'start', UNIT + '.timer'], capture=False)


if __name__ == '__main__':
    try:
        main()
    except ConfigurationFailure as error:
        print('CONFIGURATION STOP: ' + str(error), file=sys.stderr)
        sys.exit(1)
    except (Exception, KeyboardInterrupt):
        print('CONFIGURATION STOP: check_failed; private checkpoint preserved if configuration began.', file=sys.stderr)
        sys.exit(1)
