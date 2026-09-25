#!/usr/bin/env bash
# Client side. No password login; only a restricted key and pre-verified host key.
set -euo pipefail

artifact=${1:?Usage: deploy_beget.sh archive.tar.gz full-commit-sha}
release=${2:?Full commit SHA is required}
[[ "$release" =~ ^[a-f0-9]{40}$ ]] || { echo "Invalid release SHA" >&2; exit 2; }
[[ -f "$artifact" && ! -L "$artifact" ]] || { echo "Missing artifact" >&2; exit 2; }
for name in BEGET_SSH_HOST BEGET_SSH_USER BEGET_SSH_PRIVATE_KEY BEGET_SSH_KNOWN_HOSTS; do
  [[ -n "${!name:-}" ]] || { echo "Missing required setting: $name" >&2; exit 2; }
done
port=${BEGET_SSH_PORT:-22}
[[ "$port" =~ ^[0-9]{1,5}$ && "$port" -gt 0 && "$port" -le 65535 ]] || { echo "Invalid SSH port" >&2; exit 2; }
[[ "$BEGET_SSH_HOST" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*$ ]] || { echo "Invalid SSH host" >&2; exit 2; }
[[ "$BEGET_SSH_USER" =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]*$ ]] || { echo "Invalid SSH user" >&2; exit 2; }
if [[ -n "${BEGET_HEALTH_URL:-}" ]]; then
  [[ "$BEGET_HEALTH_URL" =~ ^https://[a-zA-Z0-9.-]+/api/health\.php$ ]] || { echo "Health URL must be HTTPS /api/health.php without query" >&2; exit 2; }
fi

task_tmp=$(mktemp -d)
trap 'rm -f "$task_tmp/key" "$task_tmp/known_hosts" "$task_tmp/health.json"; rmdir "$task_tmp"' EXIT
chmod 700 "$task_tmp"
printf '%s\n' "$BEGET_SSH_PRIVATE_KEY" > "$task_tmp/key"
printf '%s\n' "$BEGET_SSH_KNOWN_HOSTS" > "$task_tmp/known_hosts"
chmod 600 "$task_tmp/key" "$task_tmp/known_hosts"
# Ensure the pinned host list contains this exact endpoint, including non-default port.
known_host=$BEGET_SSH_HOST
if [[ "$port" != 22 ]]; then known_host="[$BEGET_SSH_HOST]:$port"; fi
ssh-keygen -F "$known_host" -f "$task_tmp/known_hosts" >/dev/null || { echo "No pinned key for SSH host" >&2; exit 2; }
digest=$(sha256sum "$artifact" | cut -d ' ' -f 1)
ssh -T -p "$port" -i "$task_tmp/key" \
  -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes \
  -o "UserKnownHostsFile=$task_tmp/known_hosts" -o GlobalKnownHostsFile=/dev/null \
  -o ConnectTimeout=20 -o ServerAliveInterval=15 -o ServerAliveCountMax=4 \
  "$BEGET_SSH_USER@$BEGET_SSH_HOST" "rr-deploy $release $digest" < "$artifact"

if [[ -n "${BEGET_HEALTH_URL:-}" ]]; then
  curl --fail --silent --show-error --proto '=https' --tlsv1.2 --connect-timeout 15 --max-time 30 \
    "$BEGET_HEALTH_URL" > "$task_tmp/health.json"
  python3 - "$task_tmp/health.json" "$release" <<'PY'
import json, sys
result = json.load(open(sys.argv[1]))
if result.get('status') != 'ok' or result.get('mode') != 'staging' or result.get('release') != sys.argv[2]:
    raise SystemExit('Public HTTP health check did not report the expected staging release')
print('Public HTTPS health check passed.')
PY
else
  echo "Public HTTPS check skipped: BEGET_HEALTH_URL is not set. CLI health was checked by receiver."
fi
