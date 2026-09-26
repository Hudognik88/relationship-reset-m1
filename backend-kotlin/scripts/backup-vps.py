#!/usr/bin/env python3
"""Back up the pinned local staging MySQL and verify restoration in a new database."""
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import uuid

INSTALL = Path("/opt/relationship-reset-kotlin-staging")
APP = INSTALL / "backend-kotlin"
BACKUPS = Path("/var/backups/relationship-reset-kotlin-staging")
PROJECT = "relationship-reset-kotlin-staging"
DOMAIN = "api-staging.poslessory.ru"
MYSQL_IMAGE = "mysql:8.4@sha256:0744ee5ef89ce6ccfa13de3e579fe6b9e27f93dd70da9c06d2c908b1b193fb8d"
DATABASE = "rr_kotlin_stage"
TABLES = ("rr_cases", "rr_drafts", "rr_schema_migrations")
DOCKER = ["docker", "--host", "unix:///var/run/docker.sock"]

# The password never enters host arguments, output, or host-side Python memory.
# A bounded client command lets the shell run its cleanup even on SQL failure.
CREDENTIAL_WRAPPER = r'''
set -eu
umask 077
command -v timeout >/dev/null
rr_private=$(mktemp -d /tmp/rr-vps-backup.XXXXXXXX)
cleanup() { rm -f "$rr_private/client.cnf"; rmdir "$rr_private"; }
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
rr_password=$(cat /run/secrets/db_root_password)
[ "${#rr_password}" -eq 64 ]
case "$rr_password" in *[!a-f0-9]*) exit 1;; esac
printf '[client]\nuser=root\npassword=%s\n' "$rr_password" > "$rr_private/client.cnf"
unset rr_password
rr_client=$1
shift
case "$rr_client" in mysql|mysqldump) ;; *) exit 1;; esac
timeout --signal=TERM --kill-after=10s 180s "$rr_client" \
  --defaults-extra-file="$rr_private/client.cnf" "$@"
'''


class BackupFailure(Exception):
    pass


def require(condition, code):
    if not condition:
        raise BackupFailure(code)


def protected(path, directory=False, mode=None):
    info = path.lstat()
    require(info.st_uid == 0 and not stat.S_ISLNK(info.st_mode)
            and not info.st_mode & 0o022, "private_path")
    require(stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode), "path_type")
    if mode is not None:
        require(stat.S_IMODE(info.st_mode) == mode, "path_permissions")


def run(command, *, stdin=None, stdout=subprocess.PIPE):
    # Do not inherit remote daemon/context or TLS selection from the caller.
    env = {key: value for key, value in os.environ.items()
           if not key.startswith(("DOCKER_", "COMPOSE_"))}
    try:
        result = subprocess.run(command, stdin=stdin, stdout=stdout,
                                stderr=subprocess.DEVNULL, env=env, timeout=220)
    except (OSError, subprocess.TimeoutExpired):
        raise BackupFailure("command_unavailable_or_timeout") from None
    require(result.returncode == 0, "command_failed")
    return result.stdout


def identify_container():
    ids = run(DOCKER + ["ps", "-aq", "--filter", "label=com.docker.compose.project=" + PROJECT,
                        "--filter", "label=com.docker.compose.service=mysql"]).decode().split()
    require(len(ids) == 1 and re.fullmatch(r"[a-f0-9]{12,64}", ids[0]), "mysql_container_identity")
    metadata = json.loads(run(DOCKER + ["inspect", ids[0]]))
    require(isinstance(metadata, list) and len(metadata) == 1, "mysql_container_metadata")
    item = metadata[0]
    labels = item["Config"]["Labels"]
    require(labels.get("com.docker.compose.project") == PROJECT
            and labels.get("com.docker.compose.service") == "mysql"
            and labels.get("com.docker.compose.project.working_dir") == str(APP), "mysql_container_labels")
    require(item["Config"]["Image"] == MYSQL_IMAGE and item["State"]["Running"], "mysql_container_state")
    require(not item["HostConfig"].get("PortBindings"), "mysql_unpublished")
    mounts = {mount["Destination"]: mount for mount in item["Mounts"]}
    secret = mounts.get("/run/secrets/db_root_password", {})
    require(secret.get("Type") == "bind" and secret.get("Source") == str(APP / ".secrets/db-root-password")
            and secret.get("RW") is False, "mysql_secret_mount")
    data = mounts.get("/var/lib/mysql", {})
    require(data.get("Type") == "volume" and data.get("Name") == PROJECT + "_mysql_data", "mysql_data_volume")
    return item["Id"]


def validate_runtime_revision(release):
    infra = run(["git", "-C", str(INSTALL), "rev-parse", "HEAD"]).decode().strip()
    if infra == release:
        return
    # Pull deployment changes only the API image; the audited infrastructure
    # checkout remains fixed. Validate both identities instead of checking out code.
    config_path = Path("/etc/relationship-reset-kotlin-staging/autodeploy.json")
    protected(config_path.parent, directory=True, mode=0o700)
    protected(config_path, mode=0o600)
    config = json.loads(config_path.read_text())
    require(config.get("version") == 1 and config.get("infra_sha") == infra,
            "pinned_infrastructure")
    ids = run(DOCKER + ["ps", "-q", "--filter", "label=com.docker.compose.project=" + PROJECT,
                        "--filter", "label=com.docker.compose.service=api"]).decode().split()
    require(len(ids) == 1 and re.fullmatch(r"[a-f0-9]{12,64}", ids[0]), "api_container_identity")
    api = json.loads(run(DOCKER + ["inspect", ids[0]]))[0]
    environment = dict(item.split("=", 1) for item in api["Config"]["Env"] if "=" in item)
    require(api["Config"]["Image"] == "relationship-reset-api:" + release
            and environment.get("RR_RELEASE") == release
            and api["Config"]["Labels"].get("com.docker.compose.project.working_dir") == str(APP)
            and api["State"]["Running"], "api_release_identity")


class Database:
    def __init__(self, container):
        self.container = container

    def execute(self, client, *args, stdin=None, stdout=subprocess.PIPE):
        return run(DOCKER + ["exec", "-i", self.container, "sh", "-c", CREDENTIAL_WRAPPER,
                            "rr-backup", client, *args], stdin=stdin, stdout=stdout)

    def query(self, sql):
        return self.execute("mysql", "--batch", "--skip-column-names", "--execute", sql)

    def require_tables(self, name):
        require(name == DATABASE or re.fullmatch(r"rr_restore_test_[a-f0-9]{24}", name), "database_name")
        result = self.query("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='"
                            + name + "' ORDER BY TABLE_NAME")
        require(result.decode().splitlines() == list(TABLES), "expected_three_tables")

    def require_plain_schema(self):
        # This pinned release has no programmable SQL objects. Reject unexpected
        # objects before restoring, so an event/trigger cannot target staging.
        for catalog, column in (("ROUTINES", "ROUTINE_SCHEMA"), ("TRIGGERS", "TRIGGER_SCHEMA"),
                                ("EVENTS", "EVENT_SCHEMA")):
            count = self.query("SELECT COUNT(*) FROM information_schema." + catalog
                               + " WHERE " + column + "='" + DATABASE + "'")
            require(count.strip() == b"0", "unexpected_programmable_schema_objects")

    def fingerprint(self, name, directory):
        # Deterministic data-only SQL includes every column/value, ordered by PK.
        # These temporary files are private and removed even if a command fails.
        with tempfile.TemporaryFile(dir=directory) as output:
            self.execute("mysqldump", "--single-transaction", "--no-create-info", "--skip-triggers",
                         "--skip-comments", "--compact", "--skip-extended-insert", "--complete-insert",
                         "--order-by-primary", "--hex-blob", "--no-tablespaces", "--set-gtid-purged=OFF",
                         "--skip-add-locks", "--skip-disable-keys", "--tz-utc", name, *TABLES, stdout=output)
            require(output.tell() > 0, "nonempty_data_snapshot")
            output.seek(0)
            digest = hashlib.sha256()
            for chunk in iter(lambda: output.read(1024 * 1024), b""):
                digest.update(chunk)
            return digest.hexdigest()


def verify_backup(db, directory, restore):
    require(re.fullmatch(r"rr_restore_test_[a-f0-9]{24}", restore), "restore_name")
    require(restore != DATABASE, "restore_separate")
    db.require_tables(DATABASE)
    db.require_plain_schema()
    before = db.fingerprint(DATABASE, directory)
    partial = directory / "staging.sql.partial"
    dump = directory / "staging.sql"
    with partial.open("xb") as output:
        db.execute("mysqldump", "--single-transaction", "--routines", "--triggers", "--events",
                   "--no-tablespaces", "--set-gtid-purged=OFF", "--hex-blob", "--tz-utc",
                   DATABASE, stdout=output)
        output.flush()
        os.fsync(output.fileno())
    require(partial.stat().st_size > 0, "nonempty_backup")
    partial.rename(dump)
    # No IF NOT EXISTS: a collision must stop before any restore, not reuse a DB.
    db.query("CREATE DATABASE `" + restore + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
    with dump.open("rb") as source:
        db.execute("mysql", "--binary-mode", restore, stdin=source)
    db.require_tables(restore)
    restored = db.fingerprint(restore, directory)
    after = db.fingerprint(DATABASE, directory)
    require(before == after, "staging_changed_during_backup_retry_when_idle")
    require(before == restored, "restored_values_differ")
    # Retain the restore DB for inspection; there is deliberately no DROP command.
    return dump


def main():
    require(len(sys.argv) == 1 and os.geteuid() == 0, "root_no_arguments_required")
    os.umask(0o077)
    for directory in (Path("/opt"), INSTALL, APP):
        protected(directory, directory=True)
    protected(APP / ".env", mode=0o600)
    settings = re.fullmatch(r"RR_RELEASE=([a-f0-9]{40})\nRR_DOMAIN=" + re.escape(DOMAIN) + r"\n",
                            (APP / ".env").read_text())
    require(settings is not None, "pinned_settings")
    release = settings.group(1)
    protected(APP / ".secrets", directory=True, mode=0o700)
    protected(APP / ".secrets/db-root-password", mode=0o444)
    validate_runtime_revision(release)
    for directory in (Path("/var"), Path("/var/backups")):
        protected(directory, directory=True)
    if not BACKUPS.exists():
        BACKUPS.mkdir(mode=0o700)
    protected(BACKUPS, directory=True, mode=0o700)
    lock = BACKUPS / ".backup.lock"
    fd = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "w"):
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        protected(lock, mode=0o600)
        db = Database(identify_container())
        directory = Path(tempfile.mkdtemp(prefix=datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ-"), dir=BACKUPS))
        restore = "rr_restore_test_" + uuid.uuid4().hex[:24]
        metadata = directory / "verification.json"
        state = {"verified": False, "release": release, "restore_database": restore}
        metadata.write_text(json.dumps(state) + "\n")
        try:
            dump = verify_backup(db, directory, restore)
            state["verified"] = True
            metadata.write_text(json.dumps(state) + "\n")
        except BaseException:
            print("Backup verification stopped. Preserved private artifacts: " + str(directory), file=sys.stderr)
            print("Possible retained restore database: " + restore, file=sys.stderr)
            raise
        print("PASS private backup restored; every value in all three tables matches; staging unchanged.")
        print("Backup: " + str(dump))
        print("Restore database retained: " + restore)


if __name__ == "__main__":
    try:
        main()
    except BackupFailure as error:
        print("FAIL " + str(error), file=sys.stderr)
        sys.exit(1)
    except (Exception, KeyboardInterrupt):
        print("FAIL backup_check_error; no staging database or volumes were removed.", file=sys.stderr)
        sys.exit(1)
