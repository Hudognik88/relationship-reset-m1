#!/usr/bin/env bash
# Install once outside public_html; set this absolute path as SSH forced command.
set -euo pipefail
receiver_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
exec python3 "$receiver_dir/deploy_beget_remote.py"
