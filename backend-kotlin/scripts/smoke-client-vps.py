#!/usr/bin/env python3
"""Exercise two isolated synthetic client sessions on the approved staging origin."""
import argparse
import copy
from datetime import datetime, timezone
import hashlib
import http.cookiejar
from http.cookies import SimpleCookie
import ipaddress
import json
from pathlib import Path
import re
import socket
import stat
import sys
import urllib.error
import urllib.request
import uuid


DOMAIN = "api-staging.poslessory.ru"
ORIGIN = "https://" + DOMAIN
COOKIE = "__Host-rr_client"
MAX_RESPONSE = 65_536


class SmokeFailure(Exception):
    pass


def require(condition, check, status=None):
    if not condition:
        raise SmokeFailure(check + ("" if status is None else f" (HTTP {int(status)})"))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Client:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                                 urllib.request.HTTPCookieProcessor(self.jar), NoRedirect())
        self.csrf = None
        self.authenticated = False

    def request(self, check, route, method="GET", payload=None, *, csrf=None,
                origin=ORIGIN, operator=None, cookie=None):
        headers = {}
        if method != "GET" and origin is not None:
            headers["Origin"] = origin
        if csrf is not None:
            headers["X-CSRF-Token"] = csrf
        if operator is not None:
            headers["Authorization"] = "Bearer " + operator
        if cookie is not None:
            headers["Cookie"] = COOKIE + "=" + cookie
        data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(ORIGIN + route, data=data, headers=headers, method=method)
        try:
            try:
                response = self.opener.open(request, timeout=20)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                status = response.status
                require(response.headers.get("Cache-Control") == "no-store", check + "_no_store", status)
                require(response.headers.get("X-Content-Type-Options") == "nosniff", check + "_nosniff", status)
                raw = response.read(MAX_RESPONSE + 1)
                require(len(raw) <= MAX_RESPONSE, check + "_size", status)
                try:
                    body = json.loads(raw.decode("utf-8"))
                except (ValueError, UnicodeError):
                    raise SmokeFailure(check + "_json" + f" (HTTP {status})") from None
                require(isinstance(body, dict), check + "_object", status)
                return status, body, response.headers
        except SmokeFailure:
            raise
        except Exception:
            raise SmokeFailure(check + "_transport") from None


def expected(client, check, route, method="GET", payload=None, *, status=200, body=None, **kwargs):
    actual, result, headers = client.request(check, route, method, payload, **kwargs)
    require(actual == status and (body is None or body == result), check, actual)
    return result, headers


def expiry(value, seconds, check):
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        require(parsed.tzinfo is not None, check)
        remaining = (parsed - datetime.now(timezone.utc)).total_seconds()
        require(0 < remaining <= seconds + 60, check)
    except (TypeError, ValueError, AttributeError):
        raise SmokeFailure(check) from None


def accept_session(client, invitation):
    body, headers = expected(client, "session_exchange", "/client/session", "POST",
                             {"invitation": invitation, "synthetic": True}, status=201)
    client.authenticated = True  # Keep cleanup possible if a response assertion fails.
    csrf = body.get("csrf_token")
    require(body.get("authenticated") is True and isinstance(csrf, str)
            and re.fullmatch(r"[a-f0-9]{64}", csrf), "session_response")
    client.csrf = csrf
    expiry(body.get("expires_at"), 86400, "session_expiry")
    raw = headers.get_all("Set-Cookie", [])
    require(len(raw) == 1, "session_cookie_count")
    parsed = SimpleCookie()
    parsed.load(raw[0])
    require(set(parsed) == {COOKIE}, "session_cookie_name")
    cookie = parsed[COOKIE]
    require(re.fullmatch(r"[A-Za-z0-9_-]{43}", cookie.value), "session_cookie_format")
    require(cookie["secure"] and cookie["httponly"] and cookie["samesite"].lower() == "strict"
            and cookie["path"] == "/" and not cookie["domain"] and cookie["max-age"] == "86400",
            "session_cookie_flags")
    require(csrf == hashlib.sha256(("csrf:" + cookie.value).encode()).hexdigest(), "session_csrf_binding")
    current, _ = expected(client, "session_read", "/client/session")
    require(current.get("authenticated") is True and current.get("csrf_token") == csrf, "session_read_content")
    expiry(current.get("expires_at"), 86400, "session_read_expiry")
    # JDBC DATETIME(6) can round the issuing Instant's sub-microsecond precision.
    issued_expiry = datetime.fromisoformat(body["expires_at"].replace("Z", "+00:00"))
    stored_expiry = datetime.fromisoformat(current["expires_at"].replace("Z", "+00:00"))
    require(abs((issued_expiry - stored_expiry).total_seconds()) < 1, "session_expiry_unchanged")
    return cookie.value


def payload(request_id):
    return {"schema_version": "m1-cis-v1", "synthetic": True, "client_request_id": request_id,
            "questionnaire": {
                "age": "adult", "stage": "1to3y", "safety": "no", "boundary": "space",
                "timing": "yesterday", "partner": "space", "user": "messages",
                "recurrence": "sometimes", "helpful": "listen", "failureType": "messages",
                "goal": "understand", "situation": "Вымышленная проверка клиентской репетиции 😀.",
                "success": "", "failure": ""}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", required=True)
    parser.add_argument("--secret-dir", type=Path, default=Path(".secrets"))
    args = parser.parse_args()
    require(re.fullmatch(r"[a-f0-9]{40}", args.release), "release_format")
    try:
        addresses = socket.getaddrinfo(DOMAIN, 443, type=socket.SOCK_STREAM)
        require(addresses and all(ipaddress.ip_address(row[4][0]).is_global for row in addresses), "public_dns")
    except (OSError, ValueError):
        raise SmokeFailure("public_dns") from None
    token_path = args.secret_dir / "operator-token"
    try:
        require(args.secret_dir.is_dir() and not args.secret_dir.is_symlink(), "secret_directory")
        require(stat.S_IMODE(args.secret_dir.stat().st_mode) & 0o077 == 0, "secret_directory_permissions")
        require(token_path.is_file() and not token_path.is_symlink() and token_path.stat().st_size <= 130, "token_file")
        require(stat.S_IMODE(token_path.stat().st_mode) & 0o077 == 0, "token_file_permissions")
        token = token_path.read_text(encoding="ascii").rstrip("\r\n")
    except (OSError, UnicodeError):
        raise SmokeFailure("private_configuration") from None
    require(re.fullmatch(r"[A-Za-z0-9_-]{43,128}", token), "token_format")
    operator, anonymous, a, b = Client(), Client(), Client(), Client()
    clients = (a, b)
    health, _ = expected(anonymous, "health", "/api/health.php")
    require(health.get("release") == args.release and health.get("mode") == "staging"
            and health.get("status") == "ok", "health_release")
    expected(anonymous, "anonymous_session", "/client/session", status=401, body={"error": "unauthorized"})
    expected(anonymous, "anonymous_case", "/client/case", status=401, body={"error": "unauthorized"})
    completed = False
    try:
        invitations = []
        for _ in clients:
            body, _ = expected(operator, "issue_invitation", "/client/operator/invitations", "POST",
                               {"synthetic": True}, status=201, operator=token)
            invitation = body.get("invitation")
            require(isinstance(invitation, str) and re.fullmatch(r"[A-Za-z0-9_-]{43}", invitation), "invitation_format")
            expiry(body.get("expires_at"), 3600, "invitation_expiry")
            invitations.append(invitation)
        a_cookie = accept_session(a, invitations[0])
        expected(anonymous, "invitation_single_use", "/client/session", "POST",
                 {"invitation": invitations[0], "synthetic": True}, status=401, body={"error": "invitation_invalid"})
        expected(a, "existing_session", "/client/session", "POST",
                 {"invitation": invitations[1], "synthetic": True}, status=409, body={"error": "session_exists"})
        accept_session(b, invitations[1])
        require(a.csrf != b.csrf, "independent_sessions")
        empty = {"case": None, "review": None}
        expected(a, "empty_a", "/client/case", body=empty)
        expected(b, "empty_b", "/client/case", body=empty)
        case_payload = payload(str(uuid.uuid4()))
        expected(a, "csrf_missing", "/client/case", "POST", case_payload,
                 status=403, body={"error": "csrf_invalid"})
        expected(a, "csrf_other_session", "/client/case", "POST", case_payload, csrf=b.csrf,
                 status=403, body={"error": "csrf_invalid"})
        expected(a, "origin_missing", "/client/case", "POST", case_payload, csrf=a.csrf, origin=None,
                 status=403, body={"error": "origin_forbidden"})
        expected(a, "origin_foreign", "/client/case", "POST", case_payload, csrf=a.csrf, origin="https://example.invalid",
                 status=403, body={"error": "origin_forbidden"})
        rejected = copy.deepcopy(case_payload)
        rejected["questionnaire"]["age"] = "minor"
        expected(a, "minor_gate", "/client/case", "POST", rejected, csrf=a.csrf,
                 status=422, body={"error": "adult_required"})
        rejected = copy.deepcopy(case_payload)
        rejected["questionnaire"]["safety"] = "yes_unsure"
        expected(a, "safety_gate", "/client/case", "POST", rejected, csrf=a.csrf,
                 status=422, body={"error": "safety_not_supported"})
        rejected = dict(case_payload, synthetic=False)
        expected(a, "synthetic_gate", "/client/case", "POST", rejected, csrf=a.csrf,
                 status=422, body={"error": "synthetic_case_required"})
        expected(a, "rejected_cases_not_stored", "/client/case", body=empty)
        case_a, _ = expected(a, "create_a", "/client/case", "POST", case_payload, csrf=a.csrf, status=201)
        require(isinstance(case_a.get("id"), str) and re.fullmatch(r"[a-f0-9]{32}", case_a["id"])
                and case_a.get("synthetic") is True and case_a.get("questionnaire") == case_payload["questionnaire"],
                "created_case_content")
        expected(a, "case_replay", "/client/case", "POST", case_payload, csrf=a.csrf, body=case_a)
        conflict = copy.deepcopy(case_payload)
        conflict["questionnaire"]["situation"] += " Изменено."
        expected(a, "case_conflict", "/client/case", "POST", conflict, csrf=a.csrf,
                 status=409, body={"error": "idempotency_conflict"})
        conflict = dict(case_payload, client_request_id=str(uuid.uuid4()))
        expected(a, "one_case_per_session", "/client/case", "POST", conflict, csrf=a.csrf,
                 status=409, body={"error": "case_exists"})
        expected(b, "a_case_not_visible_to_b", "/client/case", body=empty)
        case_b, _ = expected(b, "same_request_id_other_session", "/client/case", "POST", case_payload,
                             csrf=b.csrf, status=201)
        require(case_b.get("id") != case_a["id"] and case_b.get("questionnaire") == case_payload["questionnaire"],
                "case_isolation")
        expected(a, "unpublished_review", "/client/case", body={"case": case_a, "review": None})
        review_text = "Вымышленный результат: соблюдайте согласованную паузу. Это проверка доставки текста."
        expected(a, "client_cannot_publish", "/client/operator/reviews", "POST",
                 {"case_id": case_a["id"], "text": review_text, "synthetic": True}, csrf=a.csrf,
                 status=401, body={"error": "unauthorized"})
        expected(operator, "publish_review", "/client/operator/reviews", "POST",
                 {"case_id": case_a["id"], "text": review_text, "synthetic": True}, operator=token,
                 body={"published": True, "case_id": case_a["id"]})
        envelope, _ = expected(a, "published_review", "/client/case")
        require(envelope.get("case") == case_a and isinstance(envelope.get("review"), dict)
                and envelope["review"].get("text") == review_text
                and isinstance(envelope["review"].get("published_at"), str), "published_review_content")
        expected(b, "review_isolation", "/client/case", body={"case": case_b, "review": None})
        expected(a, "delete_csrf", "/client/case", "DELETE", status=403, body={"error": "csrf_invalid"})
        expected(a, "delete_a", "/client/case", "DELETE", csrf=a.csrf, body={"deleted": True})
        expected(a, "deleted_case_and_review", "/client/case", body=empty)
        expected(b, "delete_isolation", "/client/case", body={"case": case_b, "review": None})
        expected(a, "logout_csrf", "/client/session", "DELETE", status=403, body={"error": "csrf_invalid"})
        expected(a, "logout_a", "/client/session", "DELETE", csrf=a.csrf, body={"authenticated": False})
        a.authenticated = False
        require(not any(cookie.name == COOKIE for cookie in a.jar), "logout_clears_cookie")
        expected(anonymous, "revoked_cookie_session", "/client/session", cookie=a_cookie,
                 status=401, body={"error": "unauthorized"})
        expected(anonymous, "revoked_cookie_case", "/client/case", cookie=a_cookie,
                 status=401, body={"error": "unauthorized"})
        expected(b, "cleanup_b_case", "/client/case", "DELETE", csrf=b.csrf, body={"deleted": True})
        expected(b, "cleanup_b_empty", "/client/case", body=empty)
        expected(b, "cleanup_b_session", "/client/session", "DELETE", csrf=b.csrf, body={"authenticated": False})
        b.authenticated = False
        completed = True
    finally:
        cleanup_failed = False
        for client in clients:
            issued_cookie = next((cookie.value for cookie in client.jar if cookie.name == COOKIE), None)
            if client.authenticated or issued_cookie is not None:
                if client.csrf is None and issued_cookie and re.fullmatch(r"[A-Za-z0-9_-]{43}", issued_cookie):
                    client.csrf = hashlib.sha256(("csrf:" + issued_cookie).encode()).hexdigest()
                if client.csrf is None:
                    cleanup_failed = True
                    continue
                try:
                    expected(client, "cleanup_case", "/client/case", "DELETE", csrf=client.csrf, body={"deleted": True})
                    expected(client, "cleanup_session", "/client/session", "DELETE", csrf=client.csrf,
                             body={"authenticated": False})
                    client.authenticated = False
                except Exception:
                    cleanup_failed = True
        if cleanup_failed:
            print("WARN synthetic client cleanup incomplete.", file=sys.stderr)
        require(not (completed and cleanup_failed), "synthetic_cleanup")
    print("PASS client HTTPS, single-use invitations, private cookies, CSRF/Origin and adult/safety gates.")
    print("PASS isolated cases, replay/conflict, operator publication, deletion and revoked-session rejection.")
    print("PASS created synthetic cases removed and sessions logged out; existing operator fixture untouched.")


if __name__ == "__main__":
    try:
        main()
    except SmokeFailure as error:
        print("FAIL " + str(error), file=sys.stderr)
        sys.exit(1)
    except Exception:
        print("FAIL unexpected_client_check_error", file=sys.stderr)
        sys.exit(1)
