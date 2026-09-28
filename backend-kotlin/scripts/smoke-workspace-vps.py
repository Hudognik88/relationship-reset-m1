#!/usr/bin/env python3
"""Verify synthetic client re-entry and the isolated owner workspace over HTTPS."""
import argparse
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
MAX_RESPONSE = 65_536


class SmokeFailure(Exception):
    pass


def require(condition, check, status=None):
    if not condition:
        raise SmokeFailure(check + ("" if status is None else f" (HTTP {int(status)})"))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Browser:
    def __init__(self, role="client", group=None):
        self.role, self.group = role, group
        self.cookie_name = "__Host-rr_owner" if role == "owner" else "__Host-rr_client"
        self.session_path = "/owner/api/session" if role == "owner" else "/client/session"
        self.jar = http.cookiejar.CookieJar()
        self.csrf = None
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                                 urllib.request.HTTPCookieProcessor(self.jar), NoRedirect())

    def cookie(self):
        return next((item.value for item in self.jar if item.name == self.cookie_name), None)

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
            headers["Cookie"] = self.cookie_name + "=" + cookie
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


def expected(browser, check, route, method="GET", payload=None, *, status=200, body=None, **kwargs):
    actual, result, headers = browser.request(check, route, method, payload, **kwargs)
    require(actual == status and (body is None or result == body), check, actual)
    return result, headers


def error(browser, check, route, code, status, method="GET", payload=None, **kwargs):
    return expected(browser, check, route, method, payload, status=status, body={"error": code}, **kwargs)


def expiry(value, seconds, check):
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        require(parsed.tzinfo is not None, check)
        remaining = (parsed - datetime.now(timezone.utc)).total_seconds()
        require(0 < remaining <= seconds + 60, check)
    except (TypeError, ValueError, AttributeError):
        raise SmokeFailure(check) from None


def secret(value, check):
    require(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_-]{43}", value), check)
    return value


def csrf_for(browser, token):
    prefix = "owner-csrf:" if browser.role == "owner" else "csrf:"
    return hashlib.sha256((prefix + token).encode()).hexdigest()


def session(browser, route, payload, has_access_key=None):
    body, headers = expected(browser, "session_create", route, "POST", payload, status=201)
    csrf = body.get("csrf_token")
    require(body.get("authenticated") is True and isinstance(csrf, str)
            and re.fullmatch(r"[a-f0-9]{64}", csrf), "session_response")
    browser.csrf = csrf
    lifetime = 3600 if browser.role == "owner" else 86400
    expiry(body.get("expires_at"), lifetime, "session_expiry")
    raw = headers.get_all("Set-Cookie", [])
    require(len(raw) == 1, "session_cookie_count")
    parsed = SimpleCookie()
    parsed.load(raw[0])
    require(set(parsed) == {browser.cookie_name}, "session_cookie_name")
    cookie = parsed[browser.cookie_name]
    token = secret(cookie.value, "session_cookie_format")
    require(cookie["secure"] and cookie["httponly"] and cookie["samesite"].lower() == "strict"
            and cookie["path"] == "/" and not cookie["domain"] and cookie["max-age"] == str(lifetime),
            "session_cookie_flags")
    require(csrf == csrf_for(browser, token), "session_csrf_binding")
    if has_access_key is not None:
        require(body.get("has_access_key") is has_access_key and "access_key" not in body, "session_access_key_state")
    return token


def questionnaire():
    return {"schema_version": "m1-cis-v1", "synthetic": True, "client_request_id": str(uuid.uuid4()),
            "questionnaire": {
                "age": "adult", "stage": "1to3y", "safety": "no", "boundary": "space",
                "timing": "yesterday", "partner": "space", "user": "messages",
                "recurrence": "sometimes", "helpful": "listen", "failureType": "messages",
                "goal": "understand", "situation": "Вымышленная проверка повторного входа в тестовый кабинет.",
                "success": "", "failure": ""}}


def own_case(browser, check, case, review_text=None):
    result, _ = expected(browser, check, "/client/case")
    require(result.get("case") == case, check + "_ownership")
    if review_text is None:
        require(result.get("review") is None, check + "_unpublished")
    else:
        require(isinstance(result.get("review"), dict) and result["review"].get("text") == review_text,
                check + "_review")


def read_operator(secret_dir):
    path = secret_dir / "operator-token"
    try:
        require(secret_dir.is_dir() and not secret_dir.is_symlink(), "secret_directory")
        require(stat.S_IMODE(secret_dir.stat().st_mode) & 0o077 == 0, "secret_directory_permissions")
        require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 130, "operator_file")
        require(stat.S_IMODE(path.stat().st_mode) & 0o077 == 0, "operator_file_permissions")
        token = path.read_text(encoding="ascii").rstrip("\r\n")
    except (OSError, UnicodeError):
        raise SmokeFailure("private_configuration") from None
    require(re.fullmatch(r"[A-Za-z0-9_-]{43,128}", token), "operator_token_format")
    return token


def cleanup(browsers, deleted_groups):
    failed = False
    for browser in reversed(browsers):
        token = browser.cookie()
        if token is None:
            continue
        try:
            secret(token, "cleanup_cookie")
            csrf = csrf_for(browser, token)
            if browser.role == "client" and browser.group not in deleted_groups:
                status, body, _ = browser.request("cleanup_case", "/client/case", "DELETE", csrf=csrf)
                if status == 401 and body == {"error": "unauthorized"}:
                    browser.jar.clear()
                    continue
                require((status, body) == (200, {"deleted": True}), "cleanup_case", status)
                deleted_groups.add(browser.group)
            status, body, _ = browser.request("cleanup_session", browser.session_path, "DELETE", csrf=csrf)
            require((status, body) in ((200, {"authenticated": False}), (401, {"error": "unauthorized"})),
                    "cleanup_session", status)
            browser.jar.clear()
        except Exception:
            failed = True
    return not failed


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
    operator_token = read_operator(args.secret_dir)
    operator, anonymous = Browser(), Browser(group="a")
    owner_probe = Browser("owner")
    owner, a, b = Browser("owner"), Browser(group="a"), Browser(group="b")
    returned, observer, rotated = Browser(group="a"), Browser(group="a"), Browser(group="a")
    browsers = [owner, a, b, returned, observer, rotated, anonymous, owner_probe]
    deleted_groups = set()
    health, _ = expected(anonymous, "health", "/api/health.php")
    require(health.get("release") == args.release and health.get("mode") == "staging"
            and health.get("status") == "ok", "health_release")
    error(anonymous, "owner_anonymous", "/owner/api/session", "unauthorized", 401)
    completed = False
    try:
        invitation, _ = expected(operator, "client_invitation", "/client/operator/invitations", "POST",
                                 {"synthetic": True}, operator=operator_token, status=201)
        a_cookie = session(a, "/client/session", {"invitation": secret(invitation.get("invitation"), "client_invitation_format"),
                                                "synthetic": True}, has_access_key=False)
        invitation, _ = expected(operator, "owner_invitation", "/client/operator/owner-invitations", "POST",
                                 {"synthetic": True}, operator=operator_token, status=201)
        owner_invite = secret(invitation.get("invitation"), "owner_invitation_format")
        expiry(invitation.get("expires_at"), 600, "owner_invitation_expiry")
        owner_cookie = session(owner, "/owner/api/session", {"invitation": owner_invite, "synthetic": True})
        error(owner_probe, "owner_invitation_single_use", "/owner/api/session", "owner_invitation_invalid", 401,
              "POST", {"invitation": owner_invite, "synthetic": True})
        error(a, "client_cookie_not_owner", "/owner/api/cases", "unauthorized", 401)
        error(owner, "owner_cookie_not_client", "/client/case", "unauthorized", 401)
        error(owner, "owner_csrf", "/owner/api/invitations", "csrf_invalid", 403, "POST", {"synthetic": True})
        error(owner, "owner_origin", "/owner/api/invitations", "origin_forbidden", 403, "POST", {"synthetic": True},
              csrf=owner.csrf, origin="https://example.invalid")
        invitation, _ = expected(owner, "owner_issues_client_invitation", "/owner/api/invitations", "POST",
                                 {"synthetic": True}, csrf=owner.csrf, status=201)
        session(b, "/client/session", {"invitation": secret(invitation.get("invitation"), "owner_client_invitation_format"),
                                       "synthetic": True}, has_access_key=False)
        case_a, _ = expected(a, "create_a", "/client/case", "POST", questionnaire(), csrf=a.csrf, status=201)
        case_b, _ = expected(b, "create_b", "/client/case", "POST", questionnaire(), csrf=b.csrf, status=201)
        require(isinstance(case_a.get("id"), str) and re.fullmatch(r"[a-f0-9]{32}", case_a["id"])
                and isinstance(case_b.get("id"), str) and re.fullmatch(r"[a-f0-9]{32}", case_b["id"])
                and case_a["id"] != case_b["id"] and case_a.get("synthetic") is True and case_b.get("synthetic") is True,
                "independent_cases")
        error(a, "access_key_csrf", "/client/access-key", "csrf_invalid", 403, "POST", {"synthetic": True})
        error(a, "access_key_origin", "/client/access-key", "origin_forbidden", 403, "POST", {"synthetic": True},
              csrf=a.csrf, origin=None)
        access, _ = expected(a, "issue_access_key", "/client/access-key", "POST", {"synthetic": True}, csrf=a.csrf)
        key = secret(access.get("access_key"), "access_key_format")
        state, _ = expected(a, "access_key_state", "/client/session")
        require(state.get("has_access_key") is True and "access_key" not in state, "access_key_not_reexposed")
        expected(a, "logout_before_reentry", "/client/session", "DELETE", csrf=a.csrf, body={"authenticated": False})
        error(anonymous, "old_logged_out_cookie", "/client/case", "unauthorized", 401, cookie=a_cookie)
        session(returned, "/client/login", {"access_key": key, "synthetic": True}, has_access_key=True)
        own_case(returned, "reentry_restores_case", case_a)
        session(observer, "/client/login", {"access_key": key, "synthetic": True}, has_access_key=True)
        own_case(observer, "second_session_same_case", case_a)
        access, _ = expected(returned, "rotate_access_key", "/client/access-key", "POST", {"synthetic": True}, csrf=returned.csrf)
        new_key = secret(access.get("access_key"), "rotated_key_format")
        require(new_key != key, "access_key_changed")
        own_case(returned, "rotation_preserves_current_session", case_a)
        error(observer, "rotation_revokes_other_session", "/client/case", "unauthorized", 401)
        error(anonymous, "rotation_revokes_old_key", "/client/login", "access_key_invalid", 401,
              "POST", {"access_key": key, "synthetic": True})
        session(rotated, "/client/login", {"access_key": new_key, "synthetic": True}, has_access_key=True)
        own_case(rotated, "new_key_restores_same_case", case_a)
        listing, _ = expected(owner, "owner_cases", "/owner/api/cases")
        require(isinstance(listing.get("cases"), list) and {case_a["id"], case_b["id"]}.issubset(
            {item.get("id") for item in listing["cases"] if isinstance(item, dict)}), "owner_case_list")
        details, _ = expected(owner, "owner_case_detail", "/owner/api/cases/" + case_a["id"])
        require(details.get("case") == case_a and details.get("review") is None
                and details.get("review_version") == "unpublished", "owner_unpublished_case")
        review = {"case_id": case_a["id"], "text": "Вымышленный проверенный разбор: соблюдайте согласованную паузу.",
                  "synthetic": True, "reviewed": True, "expected_version": details["review_version"]}
        error(owner, "owner_explicit_review_required", "/owner/api/reviews", "review_required", 422, "POST",
              dict(review, reviewed=False), csrf=owner.csrf)
        published, _ = expected(owner, "owner_publish", "/owner/api/reviews", "POST", review, csrf=owner.csrf)
        version = published.get("review_version")
        require(published.get("published") is True and published.get("case_id") == case_a["id"]
                and isinstance(version, str) and re.fullmatch(r"[a-f0-9]{64}", version), "published_version")
        own_case(rotated, "client_receives_review", case_a, review["text"])
        own_case(b, "review_not_visible_to_b", case_b)
        changed = dict(review, text="Вымышленный уточнённый разбор: продолжайте соблюдать выбранные границы.")
        error(owner, "stale_review_rejected", "/owner/api/reviews", "review_conflict", 409, "POST", changed, csrf=owner.csrf)
        details, _ = expected(owner, "stale_write_preserved_review", "/owner/api/cases/" + case_a["id"])
        require(details.get("review_version") == version and isinstance(details.get("review"), dict)
                and details["review"].get("text") == review["text"], "stale_write_no_overwrite")
        changed["expected_version"] = version
        published, _ = expected(owner, "current_version_update", "/owner/api/reviews", "POST", changed, csrf=owner.csrf)
        require(published.get("published") is True and published.get("review_version") != version, "review_version_changes")
        own_case(rotated, "client_receives_updated_review", case_a, changed["text"])
        for browser in (rotated, b):
            expected(browser, "delete_fixture", "/client/case", "DELETE", csrf=browser.csrf, body={"deleted": True})
            own_case(browser, "fixture_removed", None)
            deleted_groups.add(browser.group)
        expected(owner, "owner_logout", "/owner/api/session", "DELETE", csrf=owner.csrf, body={"authenticated": False})
        require(owner.cookie() is None, "owner_logout_clears_cookie")
        error(owner_probe, "owner_revoked_cookie", "/owner/api/cases", "unauthorized", 401, cookie=owner_cookie)
        completed = True
    finally:
        clean = cleanup(browsers, deleted_groups)
        if not clean:
            print("WARN synthetic workspace cleanup incomplete.", file=sys.stderr)
        require(not (completed and not clean), "synthetic_cleanup")
    print("PASS durable re-entry, key rotation, old-key and other-session revocation, client isolation.")
    print("PASS owner cookie/CSRF/Origin isolation, single-use invitation, reviewed publication and stale-write rejection.")
    print("PASS synthetic cases removed and sessions closed; existing operator fixture untouched.")


if __name__ == "__main__":
    try:
        main()
    except SmokeFailure as failure:
        print("FAIL " + str(failure), file=sys.stderr)
        sys.exit(1)
    except Exception:
        print("FAIL unexpected_workspace_check_error", file=sys.stderr)
        sys.exit(1)
