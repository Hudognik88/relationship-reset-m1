<?php
declare(strict_types=1);
namespace RR;

final class Http
{
    public const MAX_BODY_BYTES = 16384;

    public static function authenticated(array $server, string $expectedHash): bool
    {
        $header = $server['HTTP_AUTHORIZATION'] ?? $server['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
        if (!is_string($header) || !preg_match('/^Bearer ([A-Za-z0-9_-]{43,128})$/D', $header, $match)) {
            return false;
        }
        return hash_equals($expectedHash, hash('sha256', $match[1]));
    }

    public static function decode(string $raw): mixed
    {
        if (strlen($raw) > self::MAX_BODY_BYTES) {
            throw new Problem(413, 'payload_too_large');
        }
        try {
            return json_decode($raw, false, 32, JSON_THROW_ON_ERROR);
        } catch (\JsonException $error) {
            throw new Problem(400, 'invalid_json');
        }
    }

    public static function run(array $config): void
    {
        try {
            $localTest = PHP_SAPI === 'cli-server' && getenv('RR_LOCAL_TEST') === '1'
                && in_array($_SERVER['REMOTE_ADDR'] ?? '', ['127.0.0.1', '::1'], true);
            if (!$localTest && !in_array(strtolower((string) ($_SERVER['HTTPS'] ?? '')), ['on', '1'], true)) {
                throw new Problem(400, 'https_required');
            }
            if (!self::authenticated($_SERVER, $config['operator_token_sha256'])) {
                header('WWW-Authenticate: Bearer');
                throw new Problem(401, 'unauthorized');
            }
            if (count($_GET) !== 1 || !is_string($_GET['route'] ?? null)) {
                throw new Problem(404, 'not_found');
            }
            $route = $_GET['route'];
            $method = $_SERVER['REQUEST_METHOD'] ?? '';
            $action = null;
            $caseId = '';
            $allowed = '';
            if ($route === '/ready') {
                $action = 'ready';
                $allowed = 'GET';
            } elseif ($route === '/cases') {
                $action = 'create_case';
                $allowed = 'POST';
            } elseif (preg_match('~^/cases/([a-f0-9]{32})(/drafts)?$~D', $route, $match)) {
                $caseId = $match[1];
                $action = isset($match[2]) ? 'create_draft' : 'get_case';
                $allowed = isset($match[2]) ? 'POST' : 'GET';
            } else {
                throw new Problem(404, 'not_found');
            }
            if ($method !== $allowed) {
                header('Allow: ' . $allowed);
                throw new Problem(405, 'method_not_allowed');
            }
            $payload = null;
            if ($method === 'POST') {
                if (!preg_match('~^application/json(?:\s*;\s*charset=utf-8)?$~iD', $_SERVER['CONTENT_TYPE'] ?? '')) {
                    throw new Problem(415, 'json_required');
                }
                if (isset($_SERVER['CONTENT_LENGTH']) && (int) $_SERVER['CONTENT_LENGTH'] > self::MAX_BODY_BYTES) {
                    throw new Problem(413, 'payload_too_large');
                }
                $raw = file_get_contents('php://input', false, null, 0, self::MAX_BODY_BYTES + 1);
                if ($raw === false) {
                    throw new Problem(400, 'invalid_json');
                }
                $body = self::decode($raw);
                $payload = $action === 'create_case' ? Validation::casePayload($body) : Validation::draftPayload($body);
            }
            $store = new Store(\rr_db($config));
            if (!$store->ready()) {
                throw new Problem(503, 'service_unavailable');
            }
            if ($action === 'ready') {
                self::respond(200, ['status' => 'ready', 'mode' => 'staging']);
            } elseif ($action === 'get_case') {
                self::respond(200, $store->getCase($caseId));
            } elseif ($action === 'create_case') {
                [$status, $result] = $store->createCase($payload);
                self::respond($status, $result);
            } else {
                [$status, $result] = $store->createDraft($caseId, $payload);
                self::respond($status, $result);
            }
        } catch (Problem $problem) {
            self::respond($problem->status, ['error' => $problem->error]);
        } catch (\Throwable $error) {
            self::respond(503, ['error' => 'service_unavailable']);
        }
    }

    private static function respond(int $status, array $body): void
    {
        http_response_code($status);
        echo Validation::json($body);
    }
}
