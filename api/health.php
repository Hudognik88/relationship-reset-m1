<?php
declare(strict_types=1);
ini_set('display_errors', '0');
ini_set('log_errors', '0');
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');
header('X-Robots-Tag: noindex, nofollow');
if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'GET') {
    http_response_code(405);
    header('Allow: GET');
    echo '{"error":"method_not_allowed"}';
    exit;
}
if (PHP_VERSION_ID < 80300 || !extension_loaded('pdo_mysql')) {
    http_response_code(503);
    echo '{"error":"service_unavailable"}';
    exit;
}
$body = ['status' => 'ok', 'mode' => 'staging'];
$releaseFile = dirname(__DIR__, 2) . '/relationship-reset-private/current/backend/release.json';
if (is_readable($releaseFile)) {
    $metadata = json_decode((string) file_get_contents($releaseFile), true);
    if (is_array($metadata) && is_string($metadata['release'] ?? null)
        && preg_match('/^[a-f0-9]{40}$/D', $metadata['release'])) {
        $body['release'] = $metadata['release'];
    }
}
echo json_encode($body, JSON_UNESCAPED_SLASHES);
