#!/usr/bin/env python3
"""Exercise the packaged JVM server against an explicitly disposable local test DB."""
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid


def main():
    env = os.environ.copy()
    db_name = env["RR_TEST_DB_NAME"]
    db_host = env.get("RR_TEST_DB_HOST", "127.0.0.1")
    if not db_name.endswith("_test") or db_host not in ("127.0.0.1", "localhost"):
        raise RuntimeError("Smoke requires a disposable local database ending in _test")
    jar = Path(__file__).resolve().parents[1] / "target/relationship-reset-api.jar"
    token = secrets.token_urlsafe(48)
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    for key in list(env):
        if key.startswith("RR_") and not key.startswith("RR_TEST_"):
            del env[key]
    env.update({
        "RR_ENVIRONMENT": "staging", "RR_HOST": "127.0.0.1", "RR_PORT": str(port),
        "RR_ALLOW_LOOPBACK_HTTP": "true",
        "RR_OPERATOR_TOKEN_SHA256": hashlib.sha256(token.encode()).hexdigest(),
        "RR_DB_HOST": db_host, "RR_DB_PORT": env.get("RR_TEST_DB_PORT", "3306"),
        "RR_DB_NAME": db_name, "RR_DB_USER": env["RR_TEST_DB_USER"],
        "RR_DB_PASSWORD": env["RR_TEST_DB_PASSWORD"],
        "RR_DB_SSL_MODE": env.get("RR_TEST_DB_SSL_MODE", "REQUIRED"),
    })
    command = ["java", "-Xmx192m", "-jar", str(jar)]
    subprocess.run(command + ["migrate"], env=env, check=True, timeout=30,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    server = subprocess.Popen(command, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(path, body=None, authenticated=True):
        headers = {"Authorization": "Bearer " + token} if authenticated else {}
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        if data is not None:
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(f"http://127.0.0.1:{port}" + path, data=data, headers=headers)
        try:
            response = opener.open(req, timeout=5)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            assert response.headers.get("Cache-Control") == "no-store"
            return response.status, json.load(response)

    try:
        for _ in range(100):
            if server.poll() is not None:
                raise RuntimeError("Packaged server exited before becoming healthy")
            try:
                assert request("/api/health.php", authenticated=False)[0] == 200
                break
            except urllib.error.URLError:
                time.sleep(0.1)
        else:
            raise RuntimeError("Packaged server readiness timed out")
        endpoint = "/api/index.php?route="
        anonymous = request(endpoint + "/ready", authenticated=False)
        assert anonymous == (401, {"error": "unauthorized"}), anonymous
        assert request(endpoint + "/ready")[0] == 200
        payload = {"schema_version": "m1-cis-v1", "synthetic": True,
                   "client_request_id": str(uuid.uuid4()), "questionnaire": {
                       "age": "adult", "stage": "1to3y", "safety": "no", "boundary": "space",
                       "timing": "yesterday", "partner": "space", "user": "messages",
                       "recurrence": "sometimes", "helpful": "listen", "failureType": "messages",
                       "goal": "understand", "situation": "Вымышленная JVM-репетиция 😀.",
                       "success": "", "failure": ""}}
        status, case = request(endpoint + "/cases", payload)
        assert status == 201
        assert request(endpoint + "/cases", payload) == (200, case)
        assert request(endpoint + "/cases/" + case["id"]) == (200, case)
        draft = {"synthetic": True, "client_request_id": str(uuid.uuid4()), "text": "Вымышленный черновик."}
        status, result = request(endpoint + "/cases/" + case["id"] + "/drafts", draft)
        assert status == 201 and result["reviewed"] is False
        assert request(endpoint + "/cases/" + case["id"] + "/drafts", draft) == (200, result)
        print("Packaged JVM smoke passed: health, auth, database, case, draft, replay.")
    finally:
        server.terminate()
        try:
            server.wait(timeout=10)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait()


if __name__ == "__main__":
    main()
