#!/usr/bin/env python3
"""Pull only green, application-only Kotlin releases onto the fixed staging VPS.

The reviewed host copy is installed outside the application checkout. Candidate
Git content is build input, never a host command or a Compose configuration.
"""
import fcntl
import json
import os
from pathlib import Path, PurePosixPath
import re
import signal
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

REPOSITORY = "Hudognik88/relationship-reset-m1"
GIT_URL = "https://github.com/" + REPOSITORY + ".git"
BRANCH = "kotlin-backend-20260926"
WORKFLOW_ID = 367556683
WORKFLOW_PATH = ".github/workflows/kotlin.yml"
DOMAIN = "api-staging.poslessory.ru"
PROJECT = "relationship-reset-kotlin-staging"
INSTALL = Path("/opt/relationship-reset-kotlin-staging")
APP = INSTALL / "backend-kotlin"
OPS = Path("/opt/relationship-reset-kotlin-deployer")
CONFIG = Path("/etc/relationship-reset-kotlin-staging/autodeploy.json")
STATE = Path("/var/lib/relationship-reset-kotlin-staging-deploy")
BACKUPS = Path("/var/backups/relationship-reset-kotlin-staging/autodeploy")
DOCKER_CONFIG = STATE / "docker-config"
# Buildx writes state beside Docker's config file. A fixed private directory
# works with systemd ProtectHome=true without exposing /root or inheriting a
# caller's Docker context, credentials store, or remote daemon configuration.
DOCKER = ["docker", "--config", str(DOCKER_CONFIG), "--host", "unix:///var/run/docker.sock"]
SHA = re.compile(r"[a-f0-9]{40}")
IMAGE_ID = re.compile(r"sha256:[a-f0-9]{64}")
FROZEN = frozenset({WORKFLOW_PATH, ".dockerignore", "backend-kotlin/.dockerignore",
                    "backend-kotlin/Dockerfile", "backend-kotlin/Dockerfile.dockerignore",
                    "backend-kotlin/compose.yaml", "backend-kotlin/Caddyfile",
                    "backend-kotlin/pom.xml", "backend-kotlin/scripts/smoke-vps.py",
                    "backend-kotlin/scripts/smoke-client-vps.py", "backend-kotlin/scripts/client-admin-vps.py",
                    "backend-kotlin/scripts/autodeploy-vps.py", "backend-kotlin/scripts/backup-vps.py",
                    "backend-kotlin/scripts/setup-autodeploy-vps.py"})
REQUIRED_FROZEN = FROZEN - {".dockerignore", "backend-kotlin/.dockerignore",
                           "backend-kotlin/Dockerfile.dockerignore", "backend-kotlin/scripts/backup-vps.py",
                           "backend-kotlin/scripts/setup-autodeploy-vps.py"}
MAX_CONTEXT_BYTES = 64 * 1024 * 1024
MAX_BLOB_BYTES = 8 * 1024 * 1024


class DeployFailure(Exception):
    """A fixed diagnostic code, never subprocess output or private content."""


def require(condition, code):
    if not condition:
        raise DeployFailure(code)


def protected(path, *, directory=False, mode=None):
    info = path.lstat()
    require(info.st_uid == 0 and not stat.S_ISLNK(info.st_mode)
            and not info.st_mode & 0o022, "unsafe_host_path")
    require(stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode),
            "unexpected_host_path_type")
    if mode is not None:
        require(stat.S_IMODE(info.st_mode) == mode, "unexpected_host_permissions")


def clean_environment():
    return {"PATH": "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG": "C.UTF-8", "GIT_TERMINAL_PROMPT": "0",
            "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": "/dev/null"}


def run(command, *, timeout=120, capture=True, stdin=None, stdout=None):
    try:
        result = subprocess.run(command, stdin=stdin,
                                stdout=stdout if stdout is not None else
                                (subprocess.PIPE if capture else subprocess.DEVNULL),
                                stderr=subprocess.DEVNULL, env=clean_environment(),
                                timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise DeployFailure("command_unavailable_or_timeout") from None
    require(result.returncode == 0, "command_failed")
    if capture and stdout is None:
        require(len(result.stdout) <= MAX_CONTEXT_BYTES, "command_output_too_large")
        return result.stdout
    return b""


def atomic_json(path, value):
    atomic_bytes(path, (json.dumps(value, sort_keys=True) + "\n").encode())


def atomic_bytes(path, value):
    if path.exists() or path.is_symlink():
        protected(path, mode=0o600)
    descriptor, name = tempfile.mkstemp(prefix=".write-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as output:
            os.fchmod(output.fileno(), 0o600)
            output.write(value)
            output.flush()
            os.fsync(output.fileno())
        os.replace(name, path)
        descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def read_json(path):
    protected(path, mode=0o600)
    require(path.stat().st_size <= 64 * 1024, "state_too_large")
    value = json.loads(path.read_text())
    require(isinstance(value, dict), "invalid_state")
    return value


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def github_json(path):
    require(path.startswith("/repos/" + REPOSITORY + "/"), "github_path")
    request = urllib.request.Request("https://api.github.com" + path, headers={
        "Accept": "application/vnd.github+json", "User-Agent": "relationship-reset-staging-deployer",
        "X-GitHub-Api-Version": "2026-03-10"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(request, timeout=20) as response:
            require(response.status == 200, "github_unavailable")
            raw = response.read(2 * 1024 * 1024 + 1)
        require(len(raw) <= 2 * 1024 * 1024, "github_response_too_large")
        value = json.loads(raw)
        require(isinstance(value, dict), "github_response_invalid")
        return value
    except (OSError, ValueError, urllib.error.URLError):
        raise DeployFailure("github_unavailable") from None


def branch_head():
    value = github_json("/repos/" + REPOSITORY + "/git/ref/heads/" + BRANCH)
    obj = value.get("object", {})
    require(value.get("ref") == "refs/heads/" + BRANCH and obj.get("type") == "commit"
            and isinstance(obj.get("sha"), str) and SHA.fullmatch(obj["sha"]), "branch_identity")
    return obj["sha"]


def select_run(data, release):
    runs = data.get("workflow_runs")
    require(isinstance(runs, list), "workflow_response_invalid")
    matching = []
    for item in runs:
        if not isinstance(item, dict):
            continue
        if (item.get("head_sha") == release and item.get("head_branch") == BRANCH
                and item.get("event") == "push" and item.get("workflow_id") == WORKFLOW_ID):
            require(isinstance(item.get("id"), int) and item["id"] > 0
                    and isinstance(item.get("run_attempt"), int) and item["run_attempt"] > 0,
                    "workflow_run_identity")
            matching.append(item)
    if not matching:
        return None
    # Do not request status=success: a failed/running rerun must hide old success.
    latest = max(matching, key=lambda item: (item["id"], item["run_attempt"]))
    require(latest.get("repository", {}).get("full_name", "").lower() == REPOSITORY.lower()
            and latest.get("head_repository", {}).get("full_name", "").lower() == REPOSITORY.lower()
            and latest.get("path", "").split("@", 1)[0] == WORKFLOW_PATH,
            "workflow_repository_identity")
    return latest if latest.get("status") == "completed" and latest.get("conclusion") == "success" else None


def green_run(release):
    query = urllib.parse.urlencode({"head_sha": release, "branch": BRANCH,
                                   "event": "push", "per_page": 100})
    return select_run(github_json("/repos/" + REPOSITORY + "/actions/workflows/"
                                + str(WORKFLOW_ID) + "/runs?" + query), release)


def ensure_head_unchanged(release):
    return branch_head() == release


def git(*arguments):
    return run(["git", "--git-dir=" + str(STATE / "source.git"),
                "-c", "core.hooksPath=/dev/null", "-c", "protocol.file.allow=never",
                "-c", "protocol.ext.allow=never", "-c", "gc.auto=0", *arguments])


def fetch_commit(release):
    require(isinstance(release, str) and SHA.fullmatch(release), "commit_format")
    git("fetch", "--quiet", "--no-tags", "--depth=1", GIT_URL, release)
    require(git("rev-parse", release + "^{commit}").decode().strip() == release, "fetched_commit_identity")


def tree_entries(release):
    result = {}
    for record in git("ls-tree", "-rz", "--full-tree", release).split(b"\0"):
        if not record:
            continue
        metadata, raw_path = record.split(b"\t", 1)
        mode, kind, oid = metadata.decode("ascii").split(" ")
        path = raw_path.decode("utf-8")
        normalized = PurePosixPath(path)
        require(not normalized.is_absolute() and ".." not in normalized.parts
                and str(normalized) == path and not any(ord(c) < 32 for c in path), "git_path_invalid")
        require(SHA.fullmatch(oid) is not None, "git_object_invalid")
        require(path not in result, "git_path_duplicate")
        result[path] = (mode, kind, oid)
    require(len(result) <= 10000, "source_tree_too_large")
    return result


def frozen_entries(entries):
    return {path: value for path, value in entries.items()
            if path in FROZEN or path.startswith("backend/migrations/")
            or path.startswith("backend-kotlin/src/main/resources/client-migrations/")}


def validate_candidate_tree(baseline, candidate):
    require(REQUIRED_FROZEN.issubset(baseline), "baseline_incomplete")
    require(any(path.startswith("backend/migrations/") for path in baseline), "baseline_migrations_missing")
    require(frozen_entries(baseline) == frozen_entries(candidate), "manual_infrastructure_update_required")
    for entry in candidate.values():
        require(isinstance(entry, tuple) and len(entry) == 3
                and entry[0] in ("100644", "100755") and entry[1] == "blob", "source_link_or_submodule")
    paths = sorted(path for path in candidate
                   if path == "backend-kotlin/pom.xml" or path.startswith("backend-kotlin/src/")
                   or path.startswith("backend/migrations/"))
    require(any(path.startswith("backend-kotlin/src/main/") for path in paths), "application_source_missing")
    return paths


def blob(entry):
    require(entry[0] in ("100644", "100755") and entry[1] == "blob", "source_blob_required")
    size = int(git("cat-file", "-s", entry[2]).strip())
    require(0 <= size <= MAX_BLOB_BYTES, "source_blob_too_large")
    content = git("cat-file", "blob", entry[2])
    require(len(content) == size, "source_blob_size")
    return content


def validate_trusted_assets(baseline):
    for name, source in (("Dockerfile", "backend-kotlin/Dockerfile"),
                         ("compose.yaml", "backend-kotlin/compose.yaml"),
                         ("smoke-vps.py", "backend-kotlin/scripts/smoke-vps.py"),
                         ("smoke-client-vps.py", "backend-kotlin/scripts/smoke-client-vps.py"),
                         ("autodeploy-vps.py", "backend-kotlin/scripts/autodeploy-vps.py"),
                         ("backup-vps.py", "backend-kotlin/scripts/backup-vps.py")):
        protected(OPS / name)
        require(source in baseline and (OPS / name).read_bytes() == blob(baseline[source]), "trusted_helper_changed")
    ignore = "backend-kotlin/Dockerfile.dockerignore"
    if ignore in baseline:
        protected(OPS / "Dockerfile.dockerignore")
        require((OPS / "Dockerfile.dockerignore").read_bytes() == blob(baseline[ignore]), "trusted_ignore_changed")
    else:
        require(not (OPS / "Dockerfile.dockerignore").exists(), "unexpected_trusted_ignore")
    for name in ("compose.yaml", "Caddyfile"):
        protected(APP / name)
        require((APP / name).read_bytes() == blob(baseline["backend-kotlin/" + name]), "live_infrastructure_changed")


def build_image(release, candidate, paths):
    with tempfile.TemporaryDirectory(prefix="build-", dir=STATE) as temporary:
        context = Path(temporary)
        total = 0
        for name in paths:
            content = blob(candidate[name])
            total += len(content)
            require(total <= MAX_CONTEXT_BYTES, "build_context_too_large")
            target = context / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
            target.chmod(0o600)
        # Trusted recipe is copied into the otherwise sanitized source context.
        recipe = context / "Dockerfile"
        recipe.write_bytes((OPS / "Dockerfile").read_bytes())
        if (OPS / "Dockerfile.dockerignore").exists():
            (context / "Dockerfile.dockerignore").write_bytes((OPS / "Dockerfile.dockerignore").read_bytes())
        print("INFO building verified candidate " + release, flush=True)
        run(DOCKER + ["build", "--file", str(recipe), "--label", "org.opencontainers.image.revision=" + release,
                      "--tag", "relationship-reset-api:" + release, str(context)],
            timeout=900, capture=False)
    return image_id(release)


def image_id(release):
    require(SHA.fullmatch(release) is not None, "image_release_format")
    value = run(DOCKER + ["image", "inspect", "--format", "{{.Id}}",
                         "relationship-reset-api:" + release]).decode().strip()
    require(IMAGE_ID.fullmatch(value) is not None, "image_identity")
    return value


def compose(*arguments):
    return run(DOCKER + ["compose", "--project-name", PROJECT, "--project-directory", str(APP),
                        "--env-file", str(APP / ".env"), "--file", str(OPS / "compose.yaml"),
                        *arguments], timeout=240, capture=False)


def current_release():
    protected(APP / ".env", mode=0o600)
    match = re.fullmatch(r"RR_RELEASE=([a-f0-9]{40})\nRR_DOMAIN=" + re.escape(DOMAIN) + r"\n",
                         (APP / ".env").read_text())
    require(match is not None, "live_environment_changed")
    return match.group(1)


def write_env(release):
    require(SHA.fullmatch(release) is not None, "environment_release_format")
    atomic_bytes(APP / ".env", ("RR_RELEASE=" + release + "\nRR_DOMAIN=" + DOMAIN + "\n").encode())


def verify_release(release):
    run(["python3", str(OPS / "smoke-vps.py"), "--release", release,
         "--secret-dir", str(APP / ".secrets")], timeout=120, capture=False)
    run(["python3", str(OPS / "smoke-client-vps.py"), "--release", release,
         "--secret-dir", str(APP / ".secrets")], timeout=180, capture=False)


def validate_runtime(release, expected_image):
    containers = run(DOCKER + ["ps", "-q", "--filter", "label=com.docker.compose.project=" + PROJECT,
                              "--filter", "label=com.docker.compose.service=api"]).decode().split()
    require(len(containers) == 1 and re.fullmatch(r"[a-f0-9]{12,64}", containers[0]), "api_identity")
    metadata = json.loads(run(DOCKER + ["inspect", containers[0]]))[0]
    require(metadata["Config"]["Labels"].get("com.docker.compose.project.working_dir") == str(APP)
            and metadata["Config"]["Image"] == "relationship-reset-api:" + release
            and metadata["Image"] == expected_image and metadata["State"]["Running"]
            and not metadata["HostConfig"].get("PortBindings"), "api_runtime_identity")


def private_backup(release):
    """Dump only; restore verification is a separate, explicitly run operation."""
    protected(BACKUPS, directory=True, mode=0o700)
    containers = run(DOCKER + ["ps", "-q", "--filter", "label=com.docker.compose.project=" + PROJECT,
                              "--filter", "label=com.docker.compose.service=mysql"]).decode().split()
    require(len(containers) == 1 and re.fullmatch(r"[a-f0-9]{12,64}", containers[0]), "mysql_identity")
    metadata = json.loads(run(DOCKER + ["inspect", containers[0]]))[0]
    require(metadata["Config"]["Labels"].get("com.docker.compose.project.working_dir") == str(APP)
            and not metadata["HostConfig"].get("PortBindings"), "mysql_boundary")
    mounts = {item["Destination"]: item for item in metadata["Mounts"]}
    secret = mounts.get("/run/secrets/db_root_password", {})
    data = mounts.get("/var/lib/mysql", {})
    require(secret.get("Type") == "bind" and secret.get("Source") == str(APP / ".secrets/db-root-password")
            and secret.get("RW") is False and data.get("Type") == "volume"
            and data.get("Name") == PROJECT + "_mysql_data", "mysql_mount_boundary")
    wrapper = r'''
set -eu
umask 077
rr_private=$(mktemp -d /tmp/rr-deploy-backup.XXXXXXXX)
trap 'rm -f "$rr_private/client.cnf"; rmdir "$rr_private"' EXIT
rr_password=$(cat /run/secrets/db_root_password)
[ "${#rr_password}" -eq 64 ]
case "$rr_password" in *[!a-f0-9]*) exit 1;; esac
printf '[client]\nuser=root\npassword=%s\n' "$rr_password" > "$rr_private/client.cnf"
unset rr_password
timeout --signal=TERM --kill-after=10s 180s mysqldump --defaults-extra-file="$rr_private/client.cnf" \
  --single-transaction --routines --triggers --events --no-tablespaces \
  --set-gtid-purged=OFF --hex-blob --tz-utc rr_kotlin_stage
'''
    descriptor, name = tempfile.mkstemp(prefix=time.strftime("%Y%m%dT%H%M%SZ-", time.gmtime()) + release[:12] + "-",
                                       suffix=".sql.partial", dir=BACKUPS)
    path = Path(name)
    with os.fdopen(descriptor, "wb") as output:
        os.fchmod(output.fileno(), 0o600)
        run(DOCKER + ["exec", containers[0], "sh", "-c", wrapper], timeout=220, stdout=output)
        output.flush()
        os.fsync(output.fileno())
    require(path.stat().st_size > 0, "backup_empty")
    final = path.with_suffix("")
    path.rename(final)
    return final


def validate_transaction(transaction):
    require(transaction.get("version") == 1 and transaction.get("phase") in ("prepared", "switched"), "journal_invalid")
    for key in ("previous_release", "candidate_release"):
        require(isinstance(transaction.get(key), str) and SHA.fullmatch(transaction[key]), "journal_release_invalid")
    for key in ("previous_image", "candidate_image"):
        require(isinstance(transaction.get(key), str) and IMAGE_ID.fullmatch(transaction[key]), "journal_image_invalid")


def clear_transaction():
    path = STATE / "transaction.json"
    if path.exists() or path.is_symlink():
        protected(path, mode=0o600)
        path.unlink()
    descriptor = os.open(STATE, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def rollback_transaction(transaction):
    validate_transaction(transaction)
    previous = transaction["previous_release"]
    immutable = transaction["previous_image"]
    # Keep the recorded previous image, even if a tag was unexpectedly moved.
    found = run(DOCKER + ["image", "inspect", "--format", "{{.Id}}", immutable]).decode().strip()
    require(found == immutable, "rollback_image_missing")
    run(DOCKER + ["image", "tag", immutable, "relationship-reset-api:" + previous], capture=False)
    write_env(previous)
    compose("up", "-d", "--no-deps", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "180", "api")
    validate_runtime(previous, immutable)
    verify_release(previous)
    atomic_json(STATE / "last-result.json", {"version": 1, "status": "rolled_back",
                "release": previous, "failed_release": transaction["candidate_release"], "at": int(time.time())})
    clear_transaction()
    print("PASS previous API restored; database and secrets preserved.", flush=True)


def recover_transaction():
    path = STATE / "transaction.json"
    if not path.exists() and not path.is_symlink():
        return False
    print("INFO recovering interrupted deployment.", flush=True)
    rollback_transaction(read_json(path))
    return True


def promote(release, previous_release, previous_image, candidate_image):
    transaction = {"version": 1, "phase": "prepared", "previous_release": previous_release,
                   "previous_image": previous_image, "candidate_release": release,
                   "candidate_image": candidate_image}
    validate_transaction(transaction)
    atomic_json(STATE / "transaction.json", transaction)
    try:
        write_env(release)
        transaction["phase"] = "switched"
        atomic_json(STATE / "transaction.json", transaction)
        compose("up", "-d", "--no-deps", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "180", "api")
        validate_runtime(release, candidate_image)
        verify_release(release)
        atomic_json(STATE / "last-result.json", {"version": 1, "status": "deployed", "release": release,
                    "image": candidate_image, "at": int(time.time())})
        clear_transaction()
    except Exception:
        try:
            rollback_transaction(transaction)
        except Exception:
            raise DeployFailure("rollback_incomplete_journal_preserved") from None
        raise DeployFailure("candidate_failed_previous_api_restored") from None
    print("PASS automatically deployed " + release + "; HTTPS, authentication and saved fixture verified.", flush=True)


def validate_host(config):
    require(config.get("version") == 1 and isinstance(config.get("enabled"), bool), "autodeploy_configuration_invalid")
    for key in ("baseline_sha", "infra_sha"):
        require(isinstance(config.get(key), str) and SHA.fullmatch(config[key]), "configuration_sha")
    for path in (Path("/opt"), INSTALL, APP, OPS, CONFIG.parent, STATE, BACKUPS):
        protected(path, directory=True)
    protected(STATE, directory=True, mode=0o700)
    protected(DOCKER_CONFIG, directory=True, mode=0o700)
    protected(BACKUPS, directory=True, mode=0o700)
    protected(OPS / "compose.yaml")
    protected(OPS / "smoke-vps.py")
    protected(OPS / "smoke-client-vps.py")
    protected(OPS / "Dockerfile")
    protected(APP / ".secrets", directory=True, mode=0o700)
    for name, mode in (("operator-token", 0o600), ("operator-token.sha256", 0o444),
                       ("db-password", 0o444), ("db-root-password", 0o444), ("smoke-fixture.json", 0o600)):
        protected(APP / ".secrets" / name, mode=mode)
    for pattern in ("compose.override.*", "docker-compose*", ".env.*"):
        require(not list(APP.glob(pattern)), "unexpected_local_override")
    require(run(["git", "-C", str(INSTALL), "rev-parse", "HEAD"]).decode().strip() == config["infra_sha"],
            "live_infrastructure_checkout_changed")
    require(run(["git", "-C", str(INSTALL), "status", "--porcelain", "--untracked-files=all"]).strip() == b"",
            "live_infrastructure_checkout_dirty")


def interrupted(signum, frame):
    raise DeployFailure("deployment_interrupted")


def main():
    require(len(sys.argv) == 1 and os.geteuid() == 0, "root_no_arguments_required")
    os.umask(0o077)
    config = read_json(CONFIG)
    validate_host(config)
    lock = STATE / "deploy.lock"
    descriptor = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "w"):
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print("WAIT another deployment is active.")
            return
        protected(lock, mode=0o600)
        for name in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
            signal.signal(name, interrupted)
        recover_transaction()
        if config["enabled"] is False:
            print("WAIT autodeploy disabled.")
            return
        previous = current_release()
        release = branch_head()
        if release == previous:
            print("PASS current release already matches branch.")
            return
        last_path = STATE / "last-result.json"
        if last_path.exists():
            last = read_json(last_path)
            if last.get("status") == "rolled_back" and last.get("failed_release") == release:
                print("WAIT candidate previously failed; push a corrected commit before retrying.")
                return
        if green_run(release) is None:
            print("WAIT exact branch commit has no successful latest push workflow.")
            return
        cache = STATE / "source.git"
        if not cache.exists():
            run(["git", "init", "--bare", "--quiet", str(cache)])
            cache.chmod(0o700)
        protected(cache, directory=True, mode=0o700)
        fetch_commit(config["baseline_sha"])
        fetch_commit(release)
        baseline, candidate = tree_entries(config["baseline_sha"]), tree_entries(release)
        paths = validate_candidate_tree(baseline, candidate)
        validate_trusted_assets(baseline)
        previous_image = image_id(previous)
        validate_runtime(previous, previous_image)
        verify_release(previous)
        candidate_image = build_image(release, candidate, paths)
        private_backup(previous)
        if not ensure_head_unchanged(release):
            print("WAIT branch advanced during build; live API unchanged.")
            return
        if green_run(release) is None:
            print("WAIT latest workflow attempt changed during build; live API unchanged.")
            return
        promote(release, previous, previous_image, candidate_image)


if __name__ == "__main__":
    try:
        main()
    except DeployFailure as exc:
        # All DeployFailure strings are fixed codes created by this module.
        print("FAIL " + str(exc) + "; private settings and database volumes preserved.", file=sys.stderr)
        sys.exit(1)
    except Exception:
        print("FAIL unexpected_deployment_error; private settings and database volumes preserved.", file=sys.stderr)
        sys.exit(1)
