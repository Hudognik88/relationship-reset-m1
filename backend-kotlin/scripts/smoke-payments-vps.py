#!/usr/bin/env python3
"""Check staged payment boundaries in an explicitly expected disabled or demo mode.

The expected mode comes from trusted host configuration, never from a public
response. No signing secret, order, cookie, database write or provider request is
used. A demo-mode PASS is not evidence of a completed provider demo payment.
"""
import argparse
import ipaddress
import json
import re
import socket
import sys
import urllib.error
import urllib.request

DOMAIN = 'api-staging.poslessory.ru'
ORIGIN = 'https://' + DOMAIN
MAX_RESPONSE = 65_536


class SmokeFailure(Exception):
    pass


def require(condition, check):
    if not condition:
        raise SmokeFailure(check)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request(opener, path, method='GET'):
    require(path in ('/api/health.php', '/payments/prodamus/webhook', '/client/orders'), 'fixed_route')
    require(method == 'GET' or (method == 'POST' and path == '/payments/prodamus/webhook'), 'unsigned_callback_only')
    req = urllib.request.Request(ORIGIN + path, method=method,
                                 data=b'{}' if method == 'POST' else None,
                                 headers={'Content-Type': 'application/json'} if method == 'POST' else {})
    try:
        try:
            response = opener.open(req, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            require(response.headers.get('Cache-Control') == 'no-store', 'response_no_store')
            require(response.headers.get('X-Content-Type-Options') == 'nosniff', 'response_nosniff')
            require(not response.headers.get_all('Set-Cookie', []), 'anonymous_cookie')
            raw = response.read(MAX_RESPONSE + 1)
            require(len(raw) <= MAX_RESPONSE, 'response_size')
            data = json.loads(raw.decode('utf-8'))
            require(isinstance(data, dict), 'response_object')
            return response.status, data
    except SmokeFailure:
        raise
    except Exception:
        raise SmokeFailure('payment_route_transport_or_response') from None


def verify(release, expect_mode="disabled"):
    require(expect_mode in ("disabled", "demo"), "expected_payment_mode")
    require(re.fullmatch(r'[a-f0-9]{40}', release) is not None, 'full_release_sha')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    status, body = request(opener, '/api/health.php')
    require(status == 200 and body.get('status') == 'ok' and body.get('mode') == 'staging'
            and body.get('release') == release, 'health_release')
    status, body = request(opener, '/payments/prodamus/webhook', 'POST')
    expected = (503, {'error': 'payments_disabled'}) if expect_mode == 'disabled' else (401, {'error': 'signature_invalid'})
    require((status, body) == expected, 'payment_mode_or_signature_guard')
    status, body = request(opener, '/payments/prodamus/webhook')
    require(status == 405 and body == {'error': 'method_not_allowed'}, 'webhook_method_guard')
    status, body = request(opener, '/client/orders')
    require(status == 401 and body == {'error': 'unauthorized'}, 'orders_require_client_session')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', required=True)
    parser.add_argument('--expect-mode', choices=('disabled', 'demo'), default='disabled')
    # Keep the same invocation contract as other smoke scripts; do not read it.
    parser.add_argument('--secret-dir', help=argparse.SUPPRESS)
    args = parser.parse_args()
    try:
        addresses = socket.getaddrinfo(DOMAIN, 443, type=socket.SOCK_STREAM)
        require(addresses and all(ipaddress.ip_address(row[4][0]).is_global for row in addresses), 'public_dns')
    except SmokeFailure:
        raise
    except Exception:
        raise SmokeFailure('dns_lookup') from None
    verify(args.release, args.expect_mode)
    print('PASS exact release over HTTPS; payment mode=' + args.expect_mode + ', unsigned callbacks rejected, orders private.')
    print('PASS no provider calls, signing secrets or payment writes used by this check.')


if __name__ == '__main__':
    try:
        main()
    except SmokeFailure as error:
        print('FAIL ' + str(error), file=sys.stderr)
        sys.exit(1)
    except Exception:
        print('FAIL unexpected_payment_check_error', file=sys.stderr)
        sys.exit(1)
