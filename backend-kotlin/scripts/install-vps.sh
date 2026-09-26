#!/usr/bin/env bash
# Run this reviewed installer as a file, never by sourcing it into an interactive shell.
# The installer revision may change; the application remains pinned to the green release.
set -Eeuo pipefail
umask 077

readonly RR_RELEASE='694c290139b59cfe8174da8af05fbf62a834d0d5'
readonly RR_DOMAIN='api-staging.poslessory.ru'
readonly RR_REPOSITORY='https://github.com/Hudognik88/relationship-reset-m1.git'
readonly RR_INSTALL='/opt/relationship-reset-kotlin-staging'
readonly RR_APP="$RR_INSTALL/backend-kotlin"
readonly RR_PROJECT='relationship-reset-kotlin-staging'
export RR_RELEASE RR_DOMAIN
rr_step='preflight'
trap 'printf "\nInstallation stopped at: %s. Existing secrets and database volumes were preserved.\n" "$rr_step" >&2' ERR
die() { printf 'STOP: %s\n' "$1" >&2; exit 1; }

[[ "$EUID" -eq 0 ]] || die 'Run this script as root on the new Docker VPS.'
[[ "$#" -eq 0 ]] || die 'This installer takes no arguments.'
for rr_command in docker git python3 curl openssl ip ss flock; do
  command -v "$rr_command" >/dev/null || die "Required command is missing: $rr_command"
done
exec 9>/run/lock/relationship-reset-kotlin-staging-install.lock
flock -n 9 || die 'Another installation is running.'
docker_local() { command docker --host unix:///var/run/docker.sock "$@"; }
docker_local info >/dev/null
rr_compose_help=$(docker_local compose up --help)
[[ "$rr_compose_help" == *'--wait '* && "$rr_compose_help" == *'--wait-timeout '* ]] ||
  die 'Docker Compose with --wait and --wait-timeout support is required.'
unset rr_compose_help

# Reject another checkout, modified source, conflicting configuration, or partial secrets.
# No existing secret is replaced, and nothing is printed from configuration files.
python3 - "$RR_INSTALL" "$RR_REPOSITORY" "$RR_RELEASE" "$RR_DOMAIN" <<'PY'
import hashlib, os, pathlib, re, stat, subprocess, sys
root, repository, release, domain = sys.argv[1:]
root = pathlib.Path(root)
def stop(reason):
    sys.exit('STOP: ' + reason)
def protected(path, directory=False):
    s = path.lstat()
    if stat.S_ISLNK(s.st_mode) or s.st_uid != 0 or s.st_mode & 0o022:
        stop('Unexpected ownership, permissions or symlink: ' + str(path))
    if directory and not stat.S_ISDIR(s.st_mode):
        stop('Expected a directory: ' + str(path))
if root.exists() or root.is_symlink():
    protected(root, True)
    if not (root / '.git').is_dir() or (root / '.git').is_symlink():
        stop('Install directory already exists without a recognised Git checkout.')
    def git(*args):
        return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()
    if git('remote', 'get-url', 'origin') != repository:
        stop('Existing repository has an unexpected origin.')
    if git('rev-parse', 'HEAD') != release:
        stop('Existing checkout is not the pinned release; upgrades require a separate procedure.')
    if git('status', '--porcelain', '--untracked-files=all'):
        stop('Existing checkout contains modified or untracked source files.')
    app = root / 'backend-kotlin'
    protected(app, True)
    env = app / '.env'
    if env.exists() or env.is_symlink():
        protected(env)
        expected = f'RR_RELEASE={release}\nRR_DOMAIN={domain}\n'
        if not env.is_file() or env.read_text() != expected:
            stop('Existing .env differs from the pinned release/domain. It was not overwritten.')
    # An extra automatic Compose override could silently change the deployment.
    for pattern in ('compose.override.*', 'docker-compose*', '.env.*'):
        if list(app.glob(pattern)):
            stop('Unexpected local Compose/environment override; review it before installation.')
    secret_dir = app / '.secrets'
    if secret_dir.exists() or secret_dir.is_symlink():
        protected(secret_dir, True)
        if stat.S_IMODE(secret_dir.stat().st_mode) != 0o700:
            stop('Existing .secrets directory must have permissions 0700.')
        names = {'operator-token', 'operator-token.sha256', 'db-password', 'db-root-password'}
        present = {p.name for p in secret_dir.iterdir()}
        if not names.issubset(present) or present - names - {'smoke-fixture.json'}:
            stop('Existing secret set is incomplete or unexpected. No secrets were regenerated.')
        fixture = secret_dir / 'smoke-fixture.json'
        if 'smoke-fixture.json' in present:
            protected(fixture)
            if not fixture.is_file() or stat.S_IMODE(fixture.stat().st_mode) != 0o600:
                stop('Existing smoke fixture must be a regular root-owned file with permissions 0600.')
        values = {}
        for name in names:
            p = secret_dir / name
            protected(p)
            if not p.is_file():
                stop('Secret is not a regular file.')
            expected_mode = 0o600 if name == 'operator-token' else 0o444
            if stat.S_IMODE(p.stat().st_mode) != expected_mode:
                stop('Existing secret permissions do not match the installation runbook.')
            values[name] = p.read_bytes()
        if not re.fullmatch(rb'[A-Za-z0-9_-]{43,128}', values['operator-token']):
            stop('Existing operator token is invalid.')
        if values['operator-token.sha256'].strip() != hashlib.sha256(values['operator-token']).hexdigest().encode():
            stop('Existing operator token/hash do not match.')
        for name in ('db-password', 'db-root-password'):
            if not re.fullmatch(rb'[a-f0-9]{64}', values[name]):
                stop('Existing database password is not a newline-free installer value.')
PY

# Inspect only the local Docker daemon. Never stop another container or host service.
python3 - "$RR_APP" "$RR_PROJECT" "$RR_DOMAIN" <<'PY'
import ipaddress, json, pathlib, re, socket, subprocess, sys
app, project, domain = sys.argv[1:]
def stop(reason):
    sys.exit('STOP: ' + reason)
def output(command):
    return subprocess.check_output(command, text=True)
def docker(*args):
    return output(['docker', '--host', 'unix:///var/run/docker.sock', *args])
def inspect(kind):
    ids = docker(kind, 'ls', '-aq').split() if kind == 'container' else docker(kind, 'ls', '-q').split()
    return json.loads(docker(kind, 'inspect', *ids)) if ids else []
containers, networks, volumes = inspect('container'), inspect('network'), inspect('volume')
known_services = {'api', 'mysql', 'caddy'}
own_caddy_publishing = False
has_existing_resources = False
for c in containers:
    labels = c.get('Config', {}).get('Labels') or {}
    own = labels.get('com.docker.compose.project') == project
    if own:
        has_existing_resources = True
        if labels.get('com.docker.compose.project.working_dir') != app or labels.get('com.docker.compose.service') not in known_services:
            stop('Compose project already belongs to another directory or service.')
    bindings = c.get('HostConfig', {}).get('PortBindings') or {}
    for container_port, entries in bindings.items():
        for binding in entries or []:
            if binding.get('HostPort') in ('80', '443') and c.get('State', {}).get('Running'):
                if not own or labels.get('com.docker.compose.service') != 'caddy' or container_port != binding['HostPort'] + '/tcp':
                    stop('Another container publishes port 80 or 443.')
                own_caddy_publishing = True
listeners = output(['ss', '-H', '-ltnp']).splitlines()
for line in listeners:
    columns = line.split()
    if len(columns) >= 4 and columns[3].rsplit(':', 1)[-1] in ('80', '443'):
        if not own_caddy_publishing or '"docker-proxy"' not in line:
            stop('Port 80 or 443 has an unexpected listener. No service was stopped.')

target = ipaddress.ip_network('172.30.0.0/24')
own_bridge = None
for net in networks:
    labels = net.get('Labels') or {}
    own = labels.get('com.docker.compose.project') == project
    if own:
        has_existing_resources = True
    if net['Name'].startswith(project + '_') and not own:
        stop('A reserved Docker network name belongs to another project.')
    for config in net.get('IPAM', {}).get('Config') or []:
        subnet = config.get('Subnet')
        if not subnet:
            continue
        subnet = ipaddress.ip_network(subnet)
        if subnet.version == 4 and subnet.overlaps(target):
            if not own or labels.get('com.docker.compose.network') != 'proxy' or subnet != target or not net.get('Internal'):
                stop('Docker network overlaps the required private proxy subnet.')
            own_bridge = net.get('Options', {}).get('com.docker.network.bridge.name') or 'br-' + net['Id'][:12]
for route in json.loads(output(['ip', '-j', '-4', 'route', 'show', 'table', 'all'])):
    dst = route.get('dst', 'default')
    if dst == 'default':
        continue
    try:
        subnet = ipaddress.ip_network(dst, strict=False)
    except ValueError:
        stop('Could not safely interpret an IPv4 route.')
    if subnet.overlaps(target) and (not own_bridge or route.get('dev') != own_bridge or not subnet.subnet_of(target)):
        stop('Host route overlaps 172.30.0.0/24. No network was changed.')
for volume in volumes:
    own = (volume.get('Labels') or {}).get('com.docker.compose.project') == project
    if own:
        has_existing_resources = True
    if volume['Name'].startswith(project + '_') and not own:
        stop('A reserved Docker volume name belongs to another project.')
if has_existing_resources and not pathlib.Path(app, '.secrets', 'db-root-password').is_file():
    stop('Existing project resources have no validated secret set. Refusing to generate new passwords.')

addresses = {entry['local'] for iface in json.loads(output(['ip', '-j', 'address', 'show']))
             for entry in iface.get('addr_info', []) if entry.get('scope') == 'global'}
try:
    resolved = {item[4][0] for item in socket.getaddrinfo(domain, 443, type=socket.SOCK_STREAM)}
except socket.gaierror:
    stop('Staging domain does not resolve yet. Retry after DNS propagation.')
if not resolved or not resolved.issubset(addresses):
    stop('Staging A/AAAA records do not all point to this VPS. Check DNS before retrying.')
print('Preflight passed: local Docker, ports, networks, DNS and preserved configuration.')
PY

rr_step='pinned checkout'
if [[ ! -e "$RR_INSTALL" ]]; then
  rr_clone_dir=$(mktemp -d /opt/relationship-reset-kotlin-staging.clone.XXXXXX)
  git clone --quiet --no-checkout "$RR_REPOSITORY" "$rr_clone_dir"
  git -C "$rr_clone_dir" checkout --quiet --detach "$RR_RELEASE"
  [[ $(git -C "$rr_clone_dir" rev-parse HEAD) == "$RR_RELEASE" ]] || die 'Release verification failed.'
  # -T refuses to nest a checkout in a newly appeared existing directory.
  [[ ! -e "$RR_INSTALL" ]] || die 'Install directory appeared during checkout.'
  mv -T "$rr_clone_dir" "$RR_INSTALL"
fi
cd "$RR_APP"

rr_step='private settings'
python3 - "$RR_RELEASE" "$RR_DOMAIN" <<'PY'
import hashlib, os, pathlib, secrets, sys
release, domain = sys.argv[1:]
env = pathlib.Path('.env')
if not env.exists():
    with env.open('x') as f:
        f.write(f'RR_RELEASE={release}\nRR_DOMAIN={domain}\n')
    env.chmod(0o600)
secret_dir = pathlib.Path('.secrets')
if not secret_dir.exists():
    secret_dir.mkdir(mode=0o700)
    token = secrets.token_hex(32).encode()
    values = {'operator-token': token,
              'operator-token.sha256': hashlib.sha256(token).hexdigest().encode(),
              'db-password': secrets.token_hex(32).encode(),
              'db-root-password': secrets.token_hex(32).encode()}
    for name, value in values.items():
        path = secret_dir / name
        with path.open('xb') as f:
            f.write(value)
        path.chmod(0o600 if name == 'operator-token' else 0o444)
print('Private settings ready; secret values are not displayed.')
PY

compose() {
  docker_local compose --project-name "$RR_PROJECT" --project-directory "$RR_APP" \
    --env-file "$RR_APP/.env" --file "$RR_APP/compose.yaml" "$@"
}
compose config --quiet
rr_step='API image build (may take several minutes)'
compose build api
rr_step='private MySQL startup'
compose up -d --wait --wait-timeout 180 mysql
rr_step='explicit database migration'
compose run --rm --no-deps api migrate
rr_step='API and HTTPS startup'
compose up -d --wait --wait-timeout 180 api caddy

# Follow no redirects: an Authorization header must never reach another host.
# urllib validates the certificate; the bearer token stays inside this process.
rr_step='HTTPS and authenticated database readiness'
python3 - "$RR_DOMAIN" "$RR_RELEASE" <<'PY'
import json, pathlib, ssl, sys, time, urllib.error, urllib.request
domain, release = sys.argv[1:]
token = pathlib.Path('.secrets/operator-token').read_text()
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(),
                                    urllib.request.HTTPSHandler(context=ssl.create_default_context()))
def request(path, authorised=False):
    headers = {'Authorization': 'Bearer ' + token} if authorised else {}
    req = urllib.request.Request('https://' + domain + path, headers=headers)
    try:
        with opener.open(req, timeout=8) as response:
            return response.status, json.loads(response.read(8192))
    except urllib.error.HTTPError as exc:
        return exc.code, {}
last = 'HTTPS unavailable'
deadline = time.monotonic() + 180
while time.monotonic() < deadline:
    try:
        health, body = request('/api/health.php')
        anonymous, _ = request('/api/index.php?route=/ready')
        ready, state = request('/api/index.php?route=/ready', True)
        last = f'health={health}, anonymous={anonymous}, readiness={ready}'
        if (health, anonymous, ready) == (200, 401, 200) and body.get('release') == release and body.get('mode') == 'staging' and state.get('status') == 'ready':
            print('HTTPS checks passed: 200 / 401 / 200; pinned release and database ready.')
            break
    except (OSError, ValueError, urllib.error.URLError):
        last = 'HTTPS connection/certificate or JSON response is not ready'
    time.sleep(5)
else:
    sys.exit('STOP: ' + last + '. Containers were left intact. Check DNS/TLS and rerun this installer.')
PY

printf '\nInstalled pinned Kotlin staging API: https://%s/api/health.php\n' "$RR_DOMAIN"
printf 'Release: %s\nInstall directory: %s\n' "$RR_RELEASE" "$RR_INSTALL"
printf 'Health, anonymous denial and database readiness passed.\n'
printf 'Next: synthetic end-to-end check, persistence and backup/restore verification.\n'
printf 'This staging service does not enable customer submissions or payments.\n'
