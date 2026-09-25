<?php
declare(strict_types=1);
if (PHP_SAPI !== 'cli') {
    http_response_code(404);
    exit;
}
// Read-only smoke check: never creates a case, order, payment or external message.
$base = rtrim($argv[1] ?? '', '/');
$token = getenv('RR_OPERATOR_TOKEN');
$url = parse_url($base);
if (!is_array($url) || ($url['scheme'] ?? '') !== 'https' || empty($url['host'])
    || isset($url['user']) || isset($url['pass']) || isset($url['query']) || isset($url['fragment'])
    || !is_string($token) || !preg_match('/^[A-Za-z0-9_-]{43,128}$/D', $token)) {
    fwrite(STDERR, "Usage: set RR_OPERATOR_TOKEN privately; php backend/bin/smoke.php https://your-domain/api\n");
    exit(1);
}
function smoke_request(string $url, ?string $token = null): array
{
    $headers = ['Accept: application/json'];
    if ($token !== null) {
        $headers[] = 'Authorization: Bearer ' . $token;
    }
    $context = stream_context_create(['http' => [
        'method' => 'GET', 'header' => implode("\r\n", $headers),
        'timeout' => 10, 'ignore_errors' => true, 'follow_location' => 0,
    ], 'ssl' => ['verify_peer' => true, 'verify_peer_name' => true]]);
    $body = @file_get_contents($url, false, $context, 0, 4096);
    $status = 0;
    foreach ($http_response_header ?? [] as $line) {
        if (preg_match('~^HTTP/\S+ (\d{3})~', $line, $match)) {
            $status = (int) $match[1];
        }
    }
    return [$status, is_string($body) ? json_decode($body, true) : null];
}
[$healthStatus, $health] = smoke_request($base . '/health.php');
[$unauthStatus, $unauth] = smoke_request($base . '/index.php?route=%2Fready');
[$readyStatus, $ready] = smoke_request($base . '/index.php?route=%2Fready', $token);
if ($healthStatus !== 200 || ($health['status'] ?? '') !== 'ok' || ($health['mode'] ?? '') !== 'staging'
    || $unauthStatus !== 401 || ($unauth['error'] ?? '') !== 'unauthorized'
    || $readyStatus !== 200 || ($ready['status'] ?? '') !== 'ready') {
    fwrite(STDERR, "Smoke failed: health={$healthStatus}, unauthenticated={$unauthStatus}, readiness={$readyStatus}. No response bodies logged.\n");
    exit(1);
}
fwrite(STDOUT, "Staging health, denied anonymous access and authenticated DB readiness passed. No data created.\n");
