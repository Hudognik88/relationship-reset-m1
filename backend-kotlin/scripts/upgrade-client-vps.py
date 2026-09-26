#!/usr/bin/env python3
"""Manually promote one reviewed client-rehearsal commit on the existing staging VPS.

This is a one-time infrastructure/schema upgrade, not the ordinary pull deployer.
An interrupted run is rolled back on the next invocation before another upgrade.
"""
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import tempfile
import uuid
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
INSTALL = Path('/opt/relationship-reset-kotlin-staging')
APP = INSTALL / 'backend-kotlin'
OPS = Path('/opt/relationship-reset-kotlin-deployer')
STATE = Path('/var/lib/relationship-reset-kotlin-staging-deploy')
CONFIG = Path('/etc/relationship-reset-kotlin-staging/autodeploy.json')
JOURNAL = STATE / 'client-upgrade.json'
UNIT = 'relationship-reset-kotlin-deploy'
EXPECTED_INFRA = '5504aba9644f9ca38583b63e69b1a3d414b2a7ef'
EXPECTED_RUNTIME = '8f4255b4cf1badac1a7d4295cbddd01a4d4c1c34'
SHA = re.compile(r'[a-f0-9]{40}')
IMAGE = re.compile(r'sha256:[a-f0-9]{64}')
MIGRATION = 'backend-kotlin/src/main/resources/client-migrations/002_client_rehearsal.sql'
SOURCES = {
    'autodeploy-vps.py': 'backend-kotlin/scripts/autodeploy-vps.py',
    'smoke-vps.py': 'backend-kotlin/scripts/smoke-vps.py',
    'smoke-client-vps.py': 'backend-kotlin/scripts/smoke-client-vps.py',
    'client-admin-vps.py': 'backend-kotlin/scripts/client-admin-vps.py',
    'backup-vps.py': 'backend-kotlin/scripts/backup-vps.py',
    'Dockerfile': 'backend-kotlin/Dockerfile',
    'Dockerfile.dockerignore': 'backend-kotlin/Dockerfile.dockerignore',
    'compose.yaml': 'backend-kotlin/compose.yaml',
}
TARGETS = {name: OPS / name for name in SOURCES}
TARGETS.update(environment=APP / '.env', config=CONFIG)
SECRET_NAMES = ('operator-token', 'operator-token.sha256', 'db-password', 'db-root-password')


class UpgradeFailure(Exception):
    pass


def require(condition, code):
    if not condition:
        raise UpgradeFailure(code)


def protected(path, *, directory=False, mode=None):
    value = path.lstat()
    require(value.st_uid == 0 and not stat.S_ISLNK(value.st_mode)
            and not value.st_mode & 0o022, 'unsafe_upgrade_path')
    require(stat.S_ISDIR(value.st_mode) if directory else stat.S_ISREG(value.st_mode), 'upgrade_path_type')
    if mode is not None:
        require(stat.S_IMODE(value.st_mode) == mode, 'upgrade_path_permissions')


def atomic(path, data, mode=0o600):
    if path.exists() or path.is_symlink():
        protected(path)
    descriptor, temporary = tempfile.mkstemp(prefix='.upgrade-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'wb') as output:
            os.fchmod(output.fileno(), mode)
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def json_write(path, value):
    atomic(path, (json.dumps(value, sort_keys=True) + '\n').encode())


def load_core():
    protected(OPS, directory=True, mode=0o700)
    path = OPS / 'autodeploy-vps.py'
    protected(path, mode=0o700)
    require(path.stat().st_size < 256 * 1024, 'deployer_size')
    spec = importlib.util.spec_from_file_location('rr_trusted_deployer', path)
    core = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(core)
    require(core.INSTALL == INSTALL and core.OPS == OPS and core.STATE == STATE
            and core.WORKFLOW_ID == 367556683 and core.BRANCH == 'kotlin-backend-20260926'
            and core.DOCKER == ['docker', '--config', str(STATE / 'docker-config'),
                                '--host', 'unix:///var/run/docker.sock'], 'deployer_identity')
    return core


def live_git(core, *arguments):
    return core.run(['git', '-C', str(INSTALL), '-c', 'core.hooksPath=/dev/null',
                     '-c', 'protocol.file.allow=never', '-c', 'protocol.ext.allow=never', *arguments])


def secret_hashes():
    protected(APP / '.secrets', directory=True, mode=0o700)
    result = {}
    for name in SECRET_NAMES:
        path = APP / '.secrets' / name
        protected(path, mode=0o600 if name == 'operator-token' else 0o444)
        require(path.stat().st_size < 256, 'secret_size')
        result[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def xml_shape(element):
    return (element.tag, tuple(sorted(element.attrib.items())), (element.text or '').strip(),
            tuple(xml_shape(child) for child in element))


def validate_pom(previous, candidate):
    """Only the explicitly reviewed resource directory is a build-recipe change."""
    old, new = ET.fromstring(previous), ET.fromstring(candidate)
    namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}
    resources = new.find('m:build/m:resources', namespace)
    require(resources is not None, 'candidate_resources_missing')
    added = [item for item in resources
             if item.findtext('m:directory', namespaces=namespace) == 'src/main/resources']
    require(len(added) == 1, 'candidate_resource_change')
    expected = ET.fromstring('<resource xmlns="http://maven.apache.org/POM/4.0.0">'
                            '<directory>src/main/resources</directory><filtering>false</filtering></resource>')
    require(xml_shape(added[0]) == xml_shape(expected), 'candidate_resource_settings')
    resources.remove(added[0])
    require(xml_shape(old) == xml_shape(new), 'unreviewed_pom_change')


def validate_sources(core, baseline, candidate):
    for path, item in candidate.items():
        require(item[0] in ('100644', '100755') and item[1] == 'blob', 'candidate_symlink_or_submodule')
        require(path != 'backend-kotlin/.env' and not path.startswith(('backend-kotlin/.env.',
                'backend-kotlin/.secrets/')), 'candidate_private_path')
    for path in ('backend-kotlin/Dockerfile', 'backend-kotlin/Dockerfile.dockerignore',
                 'backend-kotlin/compose.yaml', '.gitignore', 'backend-kotlin/.gitignore',
                 '.gitattributes', 'backend-kotlin/.gitattributes'):
        require(baseline.get(path) == candidate.get(path), 'unexpected_recipe_or_layout_change')
    before_sql = {p: v for p, v in baseline.items() if p.startswith('backend/migrations/')}
    after_sql = {p: v for p, v in candidate.items() if p.startswith('backend/migrations/')}
    require(before_sql and before_sql == after_sql, 'existing_schema_changed')
    require(MIGRATION in candidate, 'client_migration_missing')
    statements = [sql.strip() for sql in core.blob(candidate[MIGRATION]).decode('utf-8').split(';') if sql.strip()]
    names = [re.match(r'CREATE TABLE IF NOT EXISTS (rr_client_[a-z]+)\s*\(', sql) for sql in statements]
    require(len(names) == 4 and all(names) and {item.group(1) for item in names} ==
            {'rr_client_invitations', 'rr_client_sessions', 'rr_client_cases', 'rr_client_reviews'},
            'non_additive_client_migration')
    validate_pom(core.blob(baseline['backend-kotlin/pom.xml']), core.blob(candidate['backend-kotlin/pom.xml']))
    sources = {name: core.blob(candidate[path]) for name, path in SOURCES.items()}
    for name, content in sources.items():
        if name.endswith('.py'):
            compile(content, name, 'exec')
    build_paths = sorted(p for p in candidate if p == 'backend-kotlin/pom.xml'
                         or p.startswith(('backend-kotlin/src/', 'backend/migrations/')))
    require(any(p.startswith('backend-kotlin/src/main/') for p in build_paths), 'candidate_source_missing')
    return sources, build_paths


def checkpoint(core, candidate, image, old_image, timer_active, sources):
    directory = STATE / ('client-upgrade-' + uuid.uuid4().hex)
    directory.mkdir(mode=0o700)
    snapshots = {}
    for name, path in TARGETS.items():
        if path.exists() or path.is_symlink():
            protected(path)
            data = path.read_bytes()
            mode = stat.S_IMODE(path.stat().st_mode)
            atomic(directory / name, data)
            snapshots[name] = {'existed': True, 'mode': mode, 'hash': hashlib.sha256(data).hexdigest()}
        else:
            require(name in ('smoke-client-vps.py', 'client-admin-vps.py'), 'missing_managed_file')
            snapshots[name] = {'existed': False}
    atomic(directory / 'candidate-backup.py', sources['backup-vps.py'], 0o700)
    journal = {'version': 1, 'phase': 'prepared', 'directory': str(directory),
               'previous_infra': EXPECTED_INFRA, 'previous_release': EXPECTED_RUNTIME,
               'candidate_release': candidate, 'previous_image': old_image, 'candidate_image': image,
               'timer_active': timer_active, 'snapshots': snapshots, 'secrets': secret_hashes()}
    json_write(JOURNAL, journal)
    return journal


def read_journal():
    protected(JOURNAL, mode=0o600)
    require(JOURNAL.stat().st_size <= 64 * 1024, 'upgrade_journal_size')
    journal = json.loads(JOURNAL.read_text())
    require(journal.get('version') == 1 and journal.get('previous_infra') == EXPECTED_INFRA
            and journal.get('previous_release') == EXPECTED_RUNTIME, 'upgrade_journal_identity')
    require(SHA.fullmatch(journal.get('candidate_release', '')) is not None
            and IMAGE.fullmatch(journal.get('previous_image', '')) is not None
            and IMAGE.fullmatch(journal.get('candidate_image', '')) is not None, 'upgrade_journal_revisions')
    require(journal.get('phase') in ('prepared', 'migrating', 'switching', 'verifying', 'committed'), 'upgrade_journal_phase')
    directory = Path(journal.get('directory', ''))
    require(directory.parent == STATE and re.fullmatch(r'client-upgrade-[a-f0-9]{32}', directory.name), 'upgrade_checkpoint_path')
    protected(directory, directory=True, mode=0o700)
    require(set(journal.get('snapshots', {})) == set(TARGETS), 'upgrade_snapshot_set')
    for name, item in journal['snapshots'].items():
        require(isinstance(item.get('existed'), bool), 'upgrade_snapshot_format')
        if item['existed']:
            path = directory / name
            protected(path, mode=0o600)
            require(item.get('mode') in (0o600, 0o700)
                    and hashlib.sha256(path.read_bytes()).hexdigest() == item.get('hash'), 'upgrade_snapshot_integrity')
        else:
            require(name in ('smoke-client-vps.py', 'client-admin-vps.py'), 'upgrade_snapshot_absent')
    require(isinstance(journal.get('timer_active'), bool), 'upgrade_timer_state')
    require(secret_hashes() == journal.get('secrets'), 'existing_secret_changed')
    return journal


def set_phase(journal, phase):
    journal['phase'] = phase
    json_write(JOURNAL, journal)


def archive_journal(journal, outcome):
    directory = Path(journal['directory'])
    json_write(directory / 'result.json', dict(journal, outcome=outcome))
    JOURNAL.unlink()
    descriptor = os.open(STATE, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def restore_timer(core, journal):
    if journal['timer_active']:
        core.run(['systemctl', 'start', UNIT + '.timer'], capture=False)


def restart_services(core):
    core.compose('up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '180', 'api')
    # Caddyfile is a bind mount; recreate so it sees the new checkout's inode.
    core.compose('up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--force-recreate',
                 '--wait', '--wait-timeout', '180', 'caddy')


def rollback(core, journal):
    cleanup_migration(core, journal)
    require(secret_hashes() == journal['secrets'], 'rollback_secret_changed')
    require(live_git(core, 'rev-parse', 'HEAD').decode().strip() in
            (EXPECTED_INFRA, journal['candidate_release']), 'rollback_checkout_unexpected')
    require(not live_git(core, 'status', '--porcelain', '--untracked-files=all').strip(), 'rollback_checkout_dirty')
    live_git(core, 'checkout', '--quiet', '--detach', EXPECTED_INFRA)
    for name, item in journal['snapshots'].items():
        path = TARGETS[name]
        if item['existed']:
            atomic(path, (Path(journal['directory']) / name).read_bytes(), item['mode'])
        elif path.exists() or path.is_symlink():
            protected(path)
            path.unlink()  # Only newly introduced OPS helpers, never client data.
    # Recovery may have imported the new deployer. Reload the restored version
    # before checking an old release, whose smoke does not know the client API.
    core = load_core()
    image = journal['previous_image']
    found = core.run(core.DOCKER + ['image', 'inspect', '--format', '{{.Id}}', image]).decode().strip()
    require(found == image, 'rollback_image_missing')
    core.run(core.DOCKER + ['image', 'tag', image, 'relationship-reset-api:' + EXPECTED_RUNTIME], capture=False)
    restart_services(core)
    core.validate_runtime(EXPECTED_RUNTIME, image)
    core.verify_release(EXPECTED_RUNTIME)
    require(secret_hashes() == journal['secrets'], 'rollback_secret_changed')
    require(live_git(core, 'rev-parse', 'HEAD').decode().strip() == EXPECTED_INFRA, 'rollback_infra_mismatch')
    core.atomic_json(STATE / 'last-result.json', {'version': 1, 'status': 'rolled_back',
                     'release': EXPECTED_RUNTIME, 'failed_release': journal['candidate_release']})
    archive_journal(journal, 'rolled_back_additive_schema_retained')
    restore_timer(core, journal)
    print('PASS previous image, checkout, Caddy and deploy configuration restored; additive tables retained.', flush=True)


def migration_name(journal):
    return 'rr-client-migration-' + Path(journal['directory']).name.removeprefix('client-upgrade-')


def cleanup_migration(core, journal):
    name = migration_name(journal)
    require(re.fullmatch(r'rr-client-migration-[a-f0-9]{32}', name), 'migration_container_name')
    ids = core.run(core.DOCKER + ['ps', '-aq', '--filter', 'name=^/' + name + '$']).decode().split()
    require(len(ids) <= 1, 'migration_container_identity')
    if ids:
        item = json.loads(core.run(core.DOCKER + ['inspect', ids[0]]))[0]
        labels = item['Config']['Labels']
        require(item.get('Name') == '/' + name and labels.get('rr.client-upgrade') == name
                and labels.get('com.docker.compose.project') == core.PROJECT
                and labels.get('com.docker.compose.service') == 'api'
                and item['Config']['Image'] == 'relationship-reset-api:' + journal['candidate_release'],
                'migration_container_ownership')
        # Only this operation's disposable one-off JVM; no named volume removal.
        core.run(core.DOCKER + ['rm', '--force', ids[0]], timeout=60, capture=False)


def run_migration(core, journal):
    candidate = journal['candidate_release']
    # Compose shell variables override --env-file, without editing the live .env.
    environment = core.clean_environment()
    environment.update(RR_RELEASE=candidate, RR_DOMAIN=core.DOMAIN)
    command = core.DOCKER + ['compose', '--project-name', core.PROJECT, '--project-directory', str(APP),
                            '--env-file', str(APP / '.env'), '--file', str(OPS / 'compose.yaml'),
                            'run', '--rm', '--no-deps', '--pull', 'never', '--name', migration_name(journal),
                            '--label', 'rr.client-upgrade=' + migration_name(journal), 'api', 'migrate-client']
    require(not core.run(core.DOCKER + ['ps', '-aq', '--filter', 'name=^/' + migration_name(journal) + '$']).strip(),
            'migration_container_already_exists')
    try:
        result = subprocess.run(command, env=environment, stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL, timeout=240)
        require(result.returncode == 0, 'client_migration_failed')
    finally:
        cleanup_migration(core, journal)


def apply(core, journal, sources):
    candidate = journal['candidate_release']
    directory = Path(journal['directory'])
    print('INFO verifying a private database backup before schema changes.', flush=True)
    atomic(directory / 'pre-backup.txt', core.run(['python3', str(directory / 'candidate-backup.py')], timeout=900))
    require(core.ensure_head_unchanged(candidate) and core.green_run(candidate) is not None, 'candidate_ci_or_head_changed')
    set_phase(journal, 'migrating')
    print('INFO applying the additive client-rehearsal migration.', flush=True)
    run_migration(core, journal)
    set_phase(journal, 'switching')
    live_git(core, 'checkout', '--quiet', '--detach', candidate)
    require(live_git(core, 'rev-parse', 'HEAD').decode().strip() == candidate, 'checkout_candidate_mismatch')
    require(secret_hashes() == journal['secrets'], 'existing_secret_changed')
    for name, content in sources.items():
        atomic(OPS / name, content, 0o700 if name.endswith('.py') else 0o600)
    core.write_env(candidate)
    core.atomic_json(CONFIG, {'version': 1, 'baseline_sha': candidate, 'infra_sha': candidate, 'enabled': True})
    restart_services(core)
    set_phase(journal, 'verifying')
    core.validate_runtime(candidate, journal['candidate_image'])
    core.verify_release(candidate)
    core.run(['python3', str(OPS / 'smoke-client-vps.py'), '--release', candidate,
              '--secret-dir', str(APP / '.secrets')], timeout=240, capture=False)
    atomic(directory / 'post-backup.txt', core.run(['python3', str(OPS / 'backup-vps.py')], timeout=900))
    require(secret_hashes() == journal['secrets'], 'existing_secret_changed')
    require(not live_git(core, 'status', '--porcelain', '--untracked-files=all').strip(), 'candidate_checkout_dirty')
    new_core = load_core()
    new_core.validate_host(new_core.read_json(CONFIG))
    new_core.validate_trusted_assets(new_core.tree_entries(candidate))
    core.atomic_json(STATE / 'last-result.json', {'version': 1, 'status': 'deployed', 'release': candidate,
                     'image': journal['candidate_image'], 'kind': 'manual_client_upgrade'})
    set_phase(journal, 'committed')
    archive_journal(journal, 'verified')
    restore_timer(core, journal)
    print('PASS reviewed client rehearsal deployed; both smoke checks and full backup/restore passed.', flush=True)
    print('Release: ' + candidate)
    print('Private upgrade checkpoint: ' + str(directory))


def interrupted(signum, frame):
    raise UpgradeFailure('upgrade_interrupted')


def main():
    require(os.geteuid() == 0 and len(sys.argv) == 2 and SHA.fullmatch(sys.argv[1]), 'root_and_reviewed_full_sha_required')
    candidate = sys.argv[1]
    require(candidate not in (EXPECTED_RUNTIME, EXPECTED_INFRA), 'new_reviewed_commit_required')
    os.umask(0o077)
    for path in (Path('/opt'), INSTALL, APP, STATE, CONFIG.parent):
        protected(path, directory=True)
    protected(STATE, directory=True, mode=0o700)
    protected(STATE / 'docker-config', directory=True, mode=0o700)
    core = load_core()
    active = core.run(['systemctl', 'show', '--property=ActiveState', '--value', UNIT + '.timer']).decode().strip()
    require(active in ('active', 'inactive'), 'timer_state_unexpected')
    core.run(['systemctl', 'stop', UNIT + '.timer'], capture=False)
    descriptor = os.open(STATE / 'deploy.lock', os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    journal = None
    safe_to_resume = True
    try:
        with os.fdopen(descriptor, 'w'):
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            protected(STATE / 'deploy.lock', mode=0o600)
            for name in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
                signal.signal(name, interrupted)
            if JOURNAL.exists() or JOURNAL.is_symlink():
                safe_to_resume = False
                previous = read_journal()
                rollback(core, previous)
                print('Recovered the interrupted upgrade. Rerun the reviewed command to try a new upgrade.')
                return
            require(not (STATE / 'transaction.json').exists(), 'ordinary_deploy_recovery_required')
            config = core.read_json(CONFIG)
            core.validate_host(config)
            require(config == {'version': 1, 'baseline_sha': EXPECTED_RUNTIME,
                               'infra_sha': EXPECTED_INFRA, 'enabled': True}, 'expected_old_deployment_required')
            require(core.current_release() == EXPECTED_RUNTIME, 'expected_old_runtime_required')
            require(live_git(core, 'remote', 'get-url', 'origin').decode().strip() == core.GIT_URL, 'repository_identity')
            require(core.branch_head() == candidate and core.green_run(candidate) is not None, 'exact_green_branch_head_required')
            core.fetch_commit(EXPECTED_RUNTIME)
            core.fetch_commit(candidate)
            baseline, entries = core.tree_entries(EXPECTED_RUNTIME), core.tree_entries(candidate)
            core.validate_trusted_assets(baseline)
            sources, paths = validate_sources(core, baseline, entries)
            live_git(core, 'fetch', '--quiet', '--no-tags', 'origin', candidate)
            old_image = core.image_id(EXPECTED_RUNTIME)
            core.validate_runtime(EXPECTED_RUNTIME, old_image)
            core.verify_release(EXPECTED_RUNTIME)
            candidate_image = core.build_image(candidate, entries, paths)
            journal = checkpoint(core, candidate, candidate_image, old_image, active == 'active', sources)
            safe_to_resume = False
            try:
                apply(core, journal, sources)
                journal = None
            except BaseException:
                # All mutable phases have the same recorded rollback. Schema is
                # deliberately not restored over the live database or removed.
                if not JOURNAL.exists() and journal['phase'] == 'committed':
                    raise UpgradeFailure('verified_upgrade_timer_restart_failed') from None
                try:
                    if JOURNAL.exists():
                        rollback(core, read_journal())
                    else:
                        raise UpgradeFailure('upgrade_finished_timer_restart_failed')
                except BaseException:
                    if not JOURNAL.exists():
                        raise UpgradeFailure('previous_deployment_restored_timer_restart_failed') from None
                    raise UpgradeFailure('rollback_incomplete_timer_stopped_checkpoint_preserved') from None
                raise UpgradeFailure('candidate_failed_previous_deployment_restored') from None
    finally:
        if safe_to_resume and active == 'active':
            core.run(['systemctl', 'start', UNIT + '.timer'], capture=False)


if __name__ == '__main__':
    try:
        main()
    except UpgradeFailure as error:
        print('UPGRADE STOP: ' + str(error), file=sys.stderr)
        sys.exit(1)
    except (Exception, KeyboardInterrupt):
        print('UPGRADE STOP: check_failed; existing secrets and database volumes were preserved.', file=sys.stderr)
        sys.exit(1)
