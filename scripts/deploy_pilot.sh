#!/usr/bin/env bash
# Publish a static pilot page through the reviewed restricted SSH receiver.
set -euo pipefail

artifact=${1:?Usage: deploy_pilot.sh pilot-interest-review.zip full-commit-sha}
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

task_tmp=$(mktemp -d)
trap 'rm -f "$task_tmp/key" "$task_tmp/known_hosts" "$task_tmp/pilot.html"; rmdir "$task_tmp"' EXIT
chmod 700 "$task_tmp"
printf '%s\n' "$BEGET_SSH_PRIVATE_KEY" > "$task_tmp/key"
printf '%s\n' "$BEGET_SSH_KNOWN_HOSTS" > "$task_tmp/known_hosts"
chmod 600 "$task_tmp/key" "$task_tmp/known_hosts"
known_host=$BEGET_SSH_HOST
if [[ "$port" != 22 ]]; then known_host="[$BEGET_SSH_HOST]:$port"; fi
ssh-keygen -F "$known_host" -f "$task_tmp/known_hosts" >/dev/null || { echo "No pinned key for SSH host" >&2; exit 2; }
digest=$(sha256sum "$artifact" | cut -d ' ' -f 1)
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
# Validate the exact artifact before either the upload or the public check.
html_digest=$(PYTHONPATH="$script_dir" python3 - "$artifact" "$release" "$digest" <<'PY'
import hashlib, pathlib, sys
from publish_pilot_remote import validate_package
html = validate_package(pathlib.Path(sys.argv[1]).read_bytes(), sys.argv[2], sys.argv[3])
print(hashlib.sha256(html).hexdigest())
PY
)
ssh -T -p "$port" -i "$task_tmp/key" \
  -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes \
  -o "UserKnownHostsFile=$task_tmp/known_hosts" -o GlobalKnownHostsFile=/dev/null \
  -o ConnectTimeout=20 -o ServerAliveInterval=15 -o ServerAliveCountMax=4 \
  "$BEGET_SSH_USER@$BEGET_SSH_HOST" "rr-publish-pilot $release $digest" < "$artifact"

# Fixed known origin; redirects and caller-selected hosts are not followed.
http_code=$(curl --silent --show-error --proto '=https' --tlsv1.2 \
  --connect-timeout 15 --max-time 30 --max-filesize 131072 \
  --header 'Cache-Control: no-cache' --output "$task_tmp/pilot.html" --write-out '%{http_code}' \
  "https://poslessory.ru/pilot.html?release=$release")
[[ "$http_code" == 200 ]] || { echo "Pilot HTTPS verification did not return 200; inspect the published page before retrying." >&2; exit 1; }
actual_digest=$(sha256sum "$task_tmp/pilot.html" | cut -d ' ' -f 1)
[[ "$actual_digest" == "$html_digest" ]] || { echo "Pilot HTTPS content differs from the validated artifact; publication may have completed." >&2; exit 1; }
echo "Pilot publication verified over HTTPS: $release"
