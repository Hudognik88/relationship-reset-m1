#!/usr/bin/env python3
"""Verify the approved synthetic staging API and retain a restart-check fixture."""
import argparse
import copy
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import stat
import sys
import urllib.error
import urllib.request
import uuid


DOMAIN = "api-staging.poslessory.ru"
MAX_RESPONSE = 65_536


class SmokeFailure(Exception):
    pass


def require(condition, check, status=None):
    if not condition:
        suffix = "" if status is None else f" (HTTP {int(status)})"
        raise SmokeFailure(check + suffix)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--domain", default=DOMAIN)
    parser.add_argument("--release", required=True)
    parser.add_argument("--secret-dir", type=Path, default=Path(".secrets"))
    args = parser.parse_args()
    require(args.domain == DOMAIN, "approved_domain")
    require(re.fullmatch(r"[a-f0-9]{40}", args.release), "release_format")
    try:
        addresses = socket.getaddrinfo(args.domain, 443, type=socket.SOCK_STREAM)
        require(addresses and all(ipaddress.ip_address(row[4][0]).is_global
                                  for row in addresses), "public_dns")
    except (OSError, ValueError):
        raise SmokeFailure("public_dns") from None

    secret_dir = args.secret_dir
    token_path = secret_dir / "operator-token"
    fixture_path = secret_dir / "smoke-fixture.json"
    try:
        require(secret_dir.is_dir() and not secret_dir.is_symlink(), "secret_directory")
        require(stat.S_IMODE(secret_dir.stat().st_mode) & 0o077 == 0, "secret_directory_permissions")
        require(token_path.is_file() and not token_path.is_symlink(), "token_file")
        require(stat.S_IMODE(token_path.stat().st_mode) & 0o077 == 0, "token_file_permissions")
        require(token_path.stat().st_size <= 130, "token_format")
        token = token_path.read_text(encoding="ascii").rstrip("\r\n")
    except (OSError, UnicodeError):
        raise SmokeFailure("private_configuration") from None
    require(re.fullmatch(r"[A-Za-z0-9_-]{43,128}", token), "token_format")

    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(check, route, payload=None, authenticated=True):
        headers = {"Authorization": "Bearer " + token} if authenticated else {}
        data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        if data is not None:
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request("https://" + args.domain + route, data=data, headers=headers)
        try:
            try:
                response = opener.open(req, timeout=20)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                status = response.status
                require(response.headers.get("Cache-Control") == "no-store", check + "_no_store", status)
                require(response.headers.get("X-Content-Type-Options") == "nosniff", check + "_nosniff", status)
                raw = response.read(MAX_RESPONSE + 1)
                require(len(raw) <= MAX_RESPONSE, check + "_response_size", status)
                try:
                    body = json.loads(raw.decode("utf-8"))
                except (ValueError, UnicodeError):
                    raise SmokeFailure(check + "_json" + f" (HTTP {status})") from None
                require(isinstance(body, dict), check + "_object", status)
                return status, body
        except SmokeFailure:
            raise
        except Exception:
            raise SmokeFailure(check + "_transport") from None

    api = "/api/index.php?route="
    status, health = request("health", "/api/health.php", authenticated=False)
    require(status == 200 and health.get("status") == "ok" and health.get("mode") == "staging",
            "health", status)
    require(health.get("release") == args.release, "health_release", status)
    status, body = request("anonymous", api + "/ready", authenticated=False)
    require((status, body) == (401, {"error": "unauthorized"}), "anonymous", status)
    status, body = request("readiness", api + "/ready")
    require((status, body) == (200, {"status": "ready", "mode": "staging"}), "readiness", status)

    try:
        if fixture_path.exists() or fixture_path.is_symlink():
            require(fixture_path.is_file() and not fixture_path.is_symlink(), "fixture_file")
            require(stat.S_IMODE(fixture_path.stat().st_mode) & 0o077 == 0, "fixture_permissions")
            require(fixture_path.stat().st_size <= MAX_RESPONSE, "fixture_size")
            fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
            require(isinstance(fixture, dict) and fixture.get("version") == 1
                    and fixture.get("domain") == args.domain, "fixture_identity")
        else:
            fixture = {"version": 1, "domain": args.domain, "payload": {
                "schema_version": "m1-cis-v1", "synthetic": True,
                "client_request_id": str(uuid.uuid4()), "questionnaire": {
                    "age": "adult", "stage": "1to3y", "safety": "no", "boundary": "space",
                    "timing": "yesterday", "partner": "space", "user": "messages",
                    "recurrence": "sometimes", "helpful": "listen", "failureType": "messages",
                    "goal": "understand", "situation": "Вымышленная проверка VPS 😀.",
                    "success": "", "failure": ""}}, "draft_payload": {
                        "synthetic": True, "client_request_id": str(uuid.uuid4()),
                        "text": "Вымышленный черновик для проверки сохранности данных."}}
        payload = fixture["payload"]
        draft_payload = fixture["draft_payload"]
        require(payload["synthetic"] is True and draft_payload["synthetic"] is True, "fixture_synthetic")
        require(isinstance(payload["questionnaire"], dict), "fixture_questionnaire")
    except (OSError, ValueError, UnicodeError, KeyError, TypeError):
        raise SmokeFailure("fixture_configuration") from None

    def save():
        temporary = fixture_path.with_name(".smoke-fixture-" + uuid.uuid4().hex + ".tmp")
        try:
            with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600),
                           "w", encoding="utf-8") as output:
                json.dump(fixture, output, ensure_ascii=False)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, fixture_path)
        except OSError:
            raise SmokeFailure("fixture_save") from None
        finally:
            if temporary.exists():
                temporary.unlink()

    save()  # Persist request IDs before any write; an interrupted run can replay safely.
    persisted = "case" in fixture and "draft" in fixture
    status, case = request("case_create", api + "/cases", payload)
    require(status in ((200,) if "case" in fixture else (200, 201)), "case_create", status)
    require(isinstance(case.get("id"), str) and re.fullmatch(r"[a-f0-9]{32}", case["id"]), "case_id", status)
    require(case.get("synthetic") is True and case.get("schema_version") == "m1-cis-v1"
            and case.get("questionnaire") == payload["questionnaire"]
            and case.get("status") == "stored_for_rehearsal", "case_content", status)
    if "case" in fixture:
        require(case == fixture["case"], "case_persistence", status)
    fixture["case"] = case
    save()
    status, body = request("case_read", api + "/cases/" + case["id"])
    require((status, body) == (200, case), "case_read", status)
    status, body = request("case_replay", api + "/cases", payload)
    require((status, body) == (200, case), "case_replay", status)
    conflict = copy.deepcopy(payload)
    conflict["questionnaire"]["situation"] += " Изменено."
    status, body = request("case_conflict", api + "/cases", conflict)
    require((status, body) == (409, {"error": "idempotency_conflict"}), "case_conflict", status)

    draft_route = api + "/cases/" + case["id"] + "/drafts"
    status, draft = request("draft_create", draft_route, draft_payload)
    require(status in ((200,) if "draft" in fixture else (200, 201)), "draft_create", status)
    require(isinstance(draft.get("id"), str) and re.fullmatch(r"[a-f0-9]{32}", draft["id"]), "draft_id", status)
    require(draft.get("case_id") == case["id"] and draft.get("synthetic") is True
            and draft.get("reviewed") is False and draft.get("status") == "draft_requires_human_review",
            "draft_content", status)
    if "draft" in fixture:
        require(draft == fixture["draft"], "draft_persistence", status)
    fixture["draft"] = draft
    save()
    status, body = request("draft_replay", draft_route, draft_payload)
    require((status, body) == (200, draft), "draft_replay", status)
    conflict = dict(draft_payload, text=draft_payload["text"] + " Изменено.")
    status, body = request("draft_conflict", draft_route, conflict)
    require((status, body) == (409, {"error": "idempotency_conflict"}), "draft_conflict", status)
    print("PASS HTTPS health=200 anonymous=401 ready=200; case/read/replay/conflict; draft/replay/conflict.")
    print("PASS saved case and draft unchanged." if persisted else "PASS synthetic restart fixture saved.")


if __name__ == "__main__":
    try:
        main()
    except SmokeFailure as error:
        print("FAIL " + str(error), file=sys.stderr)
        sys.exit(1)
    except Exception:
        print("FAIL unexpected_check_error", file=sys.stderr)
        sys.exit(1)
