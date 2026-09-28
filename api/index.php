<?php
declare(strict_types=1);
ini_set('display_errors', '0');
ini_set('log_errors', '0');
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');
header('X-Robots-Tag: noindex, nofollow');
try {
    $bootstrap = dirname(__DIR__, 2) . '/relationship-reset-private/current/backend/bootstrap.php';
    if (!is_readable($bootstrap)) {
        throw new RuntimeException('Backend unavailable');
    }
    require $bootstrap;
    RR\Http::run(rr_config());
} catch (Throwable $exception) {
    // Do not reflect/log exception messages, SQL, request data or configuration paths.
    http_response_code(503);
    echo '{"error":"service_unavailable"}';
}
