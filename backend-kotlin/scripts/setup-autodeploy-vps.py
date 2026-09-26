#!/usr/bin/env python3
"""Install the reviewed pull deployer on the existing, verified Kotlin VPS."""
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import uuid

INSTALL = Path('/opt/relationship-reset-kotlin-staging')
APP = INSTALL / 'backend-kotlin'
OPS = Path('/opt/relationship-reset-kotlin-deployer')
CONFIG_DIR = Path('/etc/relationship-reset-kotlin-staging')
STATE = Path('/var/lib/relationship-reset-kotlin-staging-deploy')
BACKUP_ROOT = Path('/var/backups/relationship-reset-kotlin-staging')
REPOSITORY = 'https://github.com/Hudognik88/relationship-reset-m1.git'
DOMAIN = 'api-staging.poslessory.ru'
UNIT = 'relationship-reset-kotlin-deploy'
FILES = {
    'autodeploy-vps.py': 'backend-kotlin/scripts/autodeploy-vps.py',
    'smoke-vps.py': 'backend-kotlin/scripts/smoke-vps.py',
    'smoke-client-vps.py': 'backend-kotlin/scripts/smoke-client-vps.py',
    'client-admin-vps.py': 'backend-kotlin/scripts/client-admin-vps.py',
    'backup-vps.py': 'backend-kotlin/scripts/backup-vps.py',
    'Dockerfile': 'backend-kotlin/Dockerfile',
    'Dockerfile.dockerignore': 'backend-kotlin/Dockerfile.dockerignore',
    'compose.yaml': 'backend-kotlin/compose.yaml',
}

SERVICE = '''[Unit]
Description=Verified Relationship Reset Kotlin staging deployment
After=network-online.target docker.service
Wants=network-online.target
Requires=docker.service

[Service]
Type=oneshot
User=root
UMask=0077
WorkingDirectory=/opt/relationship-reset-kotlin-deployer
Environment=PYTHONDONTWRITEBYTECODE=1
ExecStart=/usr/bin/python3 /opt/relationship-reset-kotlin-deployer/autodeploy-vps.py
TimeoutStartSec=30min
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
StandardOutput=journal
StandardError=journal
'''
TIMER = '''[Unit]
Description=Check verified Kotlin staging releases every five minutes

[Timer]
OnBootSec=2min
OnUnitInactiveSec=5min
RandomizedDelaySec=15s
AccuracySec=1s
Unit=relationship-reset-kotlin-deploy.service

[Install]
WantedBy=timers.target
'''


def require(condition, reason):
    if not condition:
        raise RuntimeError(reason)


def run(*command):
    env = {k: v for k, v in os.environ.items()
           if not k.startswith(('DOCKER_', 'COMPOSE_', 'GIT_'))}
    env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL='/dev/null', GIT_TERMINAL_PROMPT='0')
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                            env=env, timeout=180)
    require(result.returncode == 0, 'Command failed: ' + command[0])
    return result.stdout


def protected(path, directory=False, mode=None):
    s = path.lstat()
    require(s.st_uid == 0 and not stat.S_ISLNK(s.st_mode) and not s.st_mode & 0o022,
            'Unexpected ownership or permissions: ' + str(path))
    require(stat.S_ISDIR(s.st_mode) if directory else stat.S_ISREG(s.st_mode),
            'Unexpected file type: ' + str(path))
    if mode is not None:
        require(stat.S_IMODE(s.st_mode) == mode, 'Unexpected mode: ' + str(path))


def git(*args):
    return run('git', '-C', str(INSTALL), *args)


def blob(sha, path):
    record = git('ls-tree', sha, '--', path).decode().strip()
    require(record.startswith(('100644 blob ', '100755 blob ')) and record.endswith('\t' + path),
            'Missing regular reviewed source: ' + path)
    return git('show', sha + ':' + path)


def install_file(path, data, mode):
    if path.exists() or path.is_symlink():
        protected(path, mode=mode)
        require(path.read_bytes() == data, 'Existing managed file differs; review update separately: ' + str(path))
        return
    temporary = path.with_name('.rr-setup-' + uuid.uuid4().hex)
    try:
        with temporary.open('xb') as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        temporary.chmod(mode)
        os.link(temporary, path)  # Atomic create; never overwrite a raced destination.
    finally:
        if temporary.exists():
            temporary.unlink()


def main():
    require(os.geteuid() == 0 and len(sys.argv) == 2, 'Usage: root python3 setup-autodeploy-vps.py reviewed-full-SHA')
    baseline = sys.argv[1]
    require(re.fullmatch('[a-f0-9]{40}', baseline), 'Full reviewed commit SHA required')
    os.umask(0o077)
    for path in (Path('/opt'), INSTALL, APP, Path('/etc'), Path('/var/lib'), Path('/etc/systemd/system')):
        protected(path, directory=True)
    protected(APP / '.env', mode=0o600)
    protected(APP / '.secrets', directory=True, mode=0o700)
    require(git('remote', 'get-url', 'origin').decode().strip() == REPOSITORY, 'Unexpected repository')
    require(not git('status', '--porcelain', '--untracked-files=all').strip(), 'Live infrastructure checkout must be clean')
    infra = git('rev-parse', 'HEAD').decode().strip()
    require(re.fullmatch('[a-f0-9]{40}', infra), 'Invalid infrastructure revision')
    settings = re.fullmatch('RR_RELEASE=([a-f0-9]{40})\nRR_DOMAIN=' + re.escape(DOMAIN) + '\n',
                            (APP / '.env').read_text())
    require(settings is not None, 'Unexpected live configuration')
    # Initial setup only; a running image-only deployment keeps the infrastructure SHA fixed.
    if not (CONFIG_DIR / 'autodeploy.json').exists():
        require(settings.group(1) == infra, 'Initial runtime and infrastructure revisions differ')
    run('docker', '--host', 'unix:///var/run/docker.sock', 'info')
    run('systemctl', 'show', '--property=Version')
    git('fetch', '--no-tags', 'origin', baseline)

    for path in ('backend-kotlin/Dockerfile', 'backend-kotlin/Dockerfile.dockerignore',
                 'backend-kotlin/compose.yaml', 'backend-kotlin/Caddyfile', 'backend-kotlin/pom.xml'):
        require(blob(baseline, path) == blob(infra, path), 'Infrastructure requires manual update: ' + path)
    require(git('rev-parse', baseline + ':backend/migrations') == git('rev-parse', infra + ':backend/migrations'),
            'Schema changes require a separate migration')
    client_migrations = 'backend-kotlin/src/main/resources/client-migrations'
    require(git('ls-tree', baseline, '--', client_migrations) == git('ls-tree', infra, '--', client_migrations),
            'Client schema changes require a separate migration')
    sources = {name: blob(baseline, path) for name, path in FILES.items()}
    for name, source in sources.items():
        if name.endswith('.py'):
            compile(source, name, 'exec')

    protected(Path('/var/backups'), directory=True)
    for path in (OPS, CONFIG_DIR, STATE, STATE / 'docker-config', BACKUP_ROOT, BACKUP_ROOT / 'autodeploy'):
        if not path.exists():
            path.mkdir(mode=0o700)
        protected(path, directory=True, mode=0o700)
    config = json.dumps({'version': 1, 'baseline_sha': baseline, 'infra_sha': infra, 'enabled': True},
                        sort_keys=True, indent=2).encode() + b'\n'
    # Validate every existing destination before changing any managed file.
    writes = [(OPS / name, data, 0o700 if name.endswith('.py') else 0o600) for name, data in sources.items()]
    writes += [(CONFIG_DIR / 'autodeploy.json', config, 0o600),
               (Path('/etc/systemd/system') / (UNIT + '.service'), SERVICE.encode(), 0o644),
               (Path('/etc/systemd/system') / (UNIT + '.timer'), TIMER.encode(), 0o644)]
    for path, data, mode in writes:
        if path.exists() or path.is_symlink():
            protected(path, mode=mode)
            require(path.read_bytes() == data, 'Managed file differs; stop and review: ' + str(path))
    for path, data, mode in writes:
        install_file(path, data, mode)
    run('systemd-analyze', 'verify', '/etc/systemd/system/' + UNIT + '.service',
        '/etc/systemd/system/' + UNIT + '.timer')
    run('systemctl', 'daemon-reload')
    run('systemctl', 'enable', '--now', UNIT + '.timer')
    print('Pull deployment timer installed and enabled; checks run every five minutes.')
    print('Application secrets, database volumes and public site were preserved.')
    print('Inspect: systemctl status ' + UNIT + '.timer')
    print('Run once: systemctl start ' + UNIT + '.service')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('SETUP STOP: ' + str(error), file=sys.stderr)
        sys.exit(1)
