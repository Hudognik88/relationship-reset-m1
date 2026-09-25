#!/usr/bin/env bash
# Run manually in Beget terminal after uploading this file and the two receivers.
set -euo pipefail
source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
site_dir=${1:?Usage: bash install_beget_receiver.sh /absolute/site/directory}
[[ "$site_dir" =~ ^/[a-zA-Z0-9_./-]+$ && "$site_dir" != *'/../'* && "$site_dir" != *'/./'* ]] || { echo "Use a simple absolute site path" >&2; exit 2; }
[[ -d "$site_dir/public_html" && ! -L "$site_dir/public_html" ]] || { echo "Existing public_html directory required" >&2; exit 2; }
site_dir=$(cd -- "$site_dir" && pwd -P)
private_dir="$site_dir/relationship-reset-private"
[[ ! -L "$private_dir" ]] || { echo "Private directory cannot be a symlink" >&2; exit 2; }
command -v python3 >/dev/null || { echo "Python 3 must be available on the hosting account" >&2; exit 2; }
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' || { echo "Python 3.8 or newer required" >&2; exit 2; }
command -v php8.3 >/dev/null || { echo "Select/install PHP 8.3 CLI in Beget first" >&2; exit 2; }
php8.3 -r 'exit(PHP_VERSION_ID >= 80300 && extension_loaded("pdo_mysql") ? 0 : 1);' || { echo "PHP 8.3 with PDO MySQL required" >&2; exit 2; }
umask 077
mkdir -p "$private_dir"
for directory in deploy releases backups shared; do
  [[ ! -L "$private_dir/$directory" ]] || { echo "Managed directory cannot be a symlink" >&2; exit 2; }
  mkdir -p "$private_dir/$directory"
done
for name in receive_beget.sh deploy_beget_remote.py; do
  [[ -f "$source_dir/$name" && ! -L "$source_dir/$name" ]] || { echo "Missing receiver source file" >&2; exit 2; }
  [[ ! -e "$private_dir/deploy/$name" ]] || { echo "Receiver already installed: review and update it manually" >&2; exit 2; }
done
cp "$source_dir/receive_beget.sh" "$private_dir/deploy/receive_beget.sh"
cp "$source_dir/deploy_beget_remote.py" "$private_dir/deploy/deploy_beget_remote.py"
chmod 700 "$private_dir/deploy/receive_beget.sh" "$private_dir/deploy/deploy_beget_remote.py"
printf 'Receiver installed. Add your dedicated public key as one authorized_keys line:\nrestrict,command="%s/deploy/receive_beget.sh" ssh-ed25519 REPLACE_WITH_PUBLIC_KEY github-beget-staging\n' "$private_dir"
echo "No database, SSH account settings or authorized_keys were changed."
