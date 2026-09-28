<?php
declare(strict_types=1);
// Real HTTP + PDO integration against a separate disposable database. No external calls.
require dirname(__DIR__) . '/backend/bootstrap.php';
require __DIR__ . '/backend-fixture.php';
$sourceConfig = rr_config();
$token = getenv('RR_OPERATOR_TOKEN');
if (!is_string($token) || !RR\Http::authenticated(['HTTP_AUTHORIZATION' => 'Bearer ' . $token], $sourceConfig['operator_token_sha256'])) {
    fwrite(STDERR, "Integration requires RR_OPERATOR_TOKEN matching the private test config.\n");
    exit(1);
}
$root = dirname(__DIR__);
$temporary = sys_get_temp_dir() . '/rr-backend-' . bin2hex(random_bytes(8));
$site = $temporary . '/site';
mkdir($site . '/public_html/api', 0700, true);
mkdir($site . '/relationship-reset-private', 0700, true);
symlink($root, $site . '/relationship-reset-private/current');
copy($root . '/api/index.php', $site . '/public_html/api/index.php');
copy($root . '/api/health.php', $site . '/public_html/api/health.php');
$testConfig = $temporary . '/config.php';
function write_test_config(array $config): void
{
    global $testConfig;
    file_put_contents($testConfig, "<?php\nreturn " . var_export($config, true) . ";\n");
    chmod($testConfig, 0600);
}
write_test_config($sourceConfig);
$env = getenv();
$env['RR_CONFIG_FILE'] = $testConfig;
$env['RR_LOCAL_TEST'] = '1';
$server = null;
$pdo = null;
$createdIds = [];
$passed = 0;
function integration_check(bool $condition, string $name): void
{
    global $passed;
    if (!$condition) {
        throw new RuntimeException('Check failed: ' . $name);
    }
    $passed++;
}
function http_test(string $path, string $method = 'GET', mixed $body = null, bool $auth = true, string $contentType = 'application/json'): array
{
    global $base, $token;
    $headers = ['Accept: application/json', 'Content-Type: ' . $contentType];
    if ($auth) {
        $headers[] = 'Authorization: Bearer ' . $token;
    }
    $options = ['http' => ['method' => $method, 'timeout' => 8, 'ignore_errors' => true, 'follow_location' => 0, 'header' => implode("\r\n", $headers)]];
    if ($body !== null) {
        $options['http']['content'] = is_string($body) ? $body : RR\Validation::json($body);
    }
    $raw = @file_get_contents($base . $path, false, stream_context_create($options));
    $status = 0;
    foreach ($http_response_header ?? [] as $header) {
        if (preg_match('~^HTTP/\S+ (\d{3})~', $header, $match)) {
            $status = (int) $match[1];
        }
    }
    return [$status, is_string($raw) ? json_decode($raw, true) : null, $raw, $http_response_header ?? []];
}
function route(string $route): string
{
    return '/index.php?route=' . rawurlencode($route);
}
function remove_test_tree(string $path): void
{
    if (is_link($path) || is_file($path)) {
        unlink($path);
        return;
    }
    foreach (scandir($path) ?: [] as $entry) {
        if ($entry !== '.' && $entry !== '..') {
            remove_test_tree($path . '/' . $entry);
        }
    }
    rmdir($path);
}
try {
    for ($attempt = 0; $attempt < 2; $attempt++) {
        $pipes = [];
        $migration = proc_open([PHP_BINARY, $root . '/backend/bin/migrate.php'], [0 => ['pipe', 'r'], 1 => ['pipe', 'w'], 2 => ['pipe', 'w']], $pipes, $root, $env);
        if (!is_resource($migration)) {
            throw new RuntimeException('Migration process unavailable');
        }
        fclose($pipes[0]);
        stream_get_contents($pipes[1]);
        stream_get_contents($pipes[2]);
        fclose($pipes[1]);
        fclose($pipes[2]);
        integration_check(proc_close($migration) === 0, 'migration and repeat are successful');
    }
    $pdo = rr_db($sourceConfig);
    $socket = stream_socket_server('tcp://127.0.0.1:0', $errno, $errstr);
    if ($socket === false) {
        throw new RuntimeException('Loopback unavailable');
    }
    $address = stream_socket_get_name($socket, false);
    fclose($socket);
    $base = 'http://' . $address . '/api';
    $server = proc_open([PHP_BINARY, '-S', $address, '-t', $site . '/public_html'], [0 => ['file', '/dev/null', 'r'], 1 => ['file', '/dev/null', 'w'], 2 => ['file', '/dev/null', 'w']], $unused, $root, $env);
    if (!is_resource($server)) {
        throw new RuntimeException('Test server unavailable');
    }
    $online = false;
    for ($i = 0; $i < 40; $i++) {
        usleep(50000);
        [$status, $body] = http_test('/health.php', 'GET', null, false);
        if ($status === 200) {
            $online = true;
            break;
        }
    }
    integration_check($online && ($body['status'] ?? '') === 'ok' && ($body['mode'] ?? '') === 'staging', 'public generic health');
    [$status, $body] = http_test('/health.php', 'POST', null, false);
    integration_check($status === 405, 'health read-only');
    [$status, $body, $raw, $headers] = http_test(route('/ready'), 'GET', null, false);
    integration_check($status === 401 && $body === ['error' => 'unauthorized'], 'anonymous denied');
    integration_check(!preg_grep('/Access-Control-Allow-Origin:/i', $headers), 'no CORS');
    integration_check((bool) preg_grep('/Cache-Control: no-store/i', $headers), 'sensitive responses not cached');
    [$status, $body] = http_test(route('/ready'));
    integration_check($status === 200 && $body === ['status' => 'ready', 'mode' => 'staging'], 'authenticated ready');
    [$status, $body] = http_test(route('/not-real'));
    integration_check($status === 404, 'unknown route');
    [$status] = http_test(route('/cases'));
    integration_check($status === 405, 'wrong method');
    $fixture = backend_fixture();
    [$status] = http_test(route('/cases'), 'POST', $fixture, false);
    integration_check($status === 401, 'anonymous cannot create');
    $bad = $fixture;
    $bad['synthetic'] = false;
    [$status] = http_test(route('/cases'), 'POST', $bad);
    integration_check($status === 422, 'no real-case toggle');
    $bad = $fixture;
    $bad['questionnaire']['email'] = 'fiction@example.invalid';
    [$status] = http_test(route('/cases'), 'POST', $bad);
    integration_check($status === 422, 'strict fields');
    [$status] = http_test(route('/cases'), 'POST', $fixture, true, 'text/plain');
    integration_check($status === 415, 'only JSON accepted');
    [$status] = http_test(route('/cases'), 'POST', str_repeat(' ', 16385));
    integration_check($status === 413, 'HTTP body bound');
    [$status] = http_test(route('/cases'), 'POST', '{');
    integration_check($status === 400, 'JSON parse error generic');
    [$status, $created] = http_test(route('/cases'), 'POST', $fixture);
    integration_check($status === 201 && preg_match('/^[a-f0-9]{32}$/D', $created['id'] ?? '') === 1, 'create case random identifier');
    $caseId = $created['id'];
    $createdIds[] = $caseId;
    integration_check($created['questionnaire'] === $fixture['questionnaire'] && $created['synthetic'] === true, 'Russian data round trip');
    [$status, $retry] = http_test(route('/cases'), 'POST', $fixture);
    integration_check($status === 200 && $retry['id'] === $caseId, 'idempotent retry');
    $changed = $fixture;
    $changed['questionnaire']['goal'] = 'calm';
    [$status, $body] = http_test(route('/cases'), 'POST', $changed);
    integration_check($status === 409 && $body === ['error' => 'idempotency_conflict'], 'changed replay conflicts');
    [$status, $stored] = http_test(route('/cases/' . $caseId));
    integration_check($status === 200 && $stored === $created, 'read saved case');
    [$status] = http_test(route('/cases/' . str_repeat('0', 32)));
    integration_check($status === 404, 'missing random case');
    $draft = ['synthetic' => true, 'client_request_id' => backend_uuid(), 'text' => 'Вымышленный черновик для проверки человеком. Не отправлять клиенту.'];
    [$status, $draftCreated] = http_test(route('/cases/' . $caseId . '/drafts'), 'POST', $draft);
    integration_check($status === 201 && $draftCreated['reviewed'] === false, 'draft remains unreviewed');
    [$status, $draftRetry] = http_test(route('/cases/' . $caseId . '/drafts'), 'POST', $draft);
    integration_check($status === 200 && $draftRetry === $draftCreated, 'draft retry stable');
    $draft['text'] .= ' Дополнение.';
    [$status] = http_test(route('/cases/' . $caseId . '/drafts'), 'POST', $draft);
    integration_check($status === 409, 'changed draft conflicts');
    $draft['reviewed'] = true;
    [$status] = http_test(route('/cases/' . $caseId . '/drafts'), 'POST', $draft);
    integration_check($status === 422, 'cannot set reviewed true');
    $query = $pdo->prepare('SELECT COUNT(*) FROM rr_cases WHERE client_request_id = ?');
    $query->execute([$fixture['client_request_id']]);
    integration_check((int) $query->fetchColumn() === 1, 'DB contains one idempotent case');
    $badConfig = $sourceConfig;
    $badConfig['database']['password'] = 'intentionally-wrong-local-only-' . bin2hex(random_bytes(16));
    write_test_config($badConfig);
    [$status, $body, $raw] = http_test(route('/ready'));
    integration_check($status === 503 && $raw === '{"error":"service_unavailable"}', 'DB failure reveals no details');
    [$status, $body] = http_test(route('/ready'), 'GET', null, false);
    integration_check($status === 401, 'auth denial precedes failed DB access');
    write_test_config([]);
    [$status, $body, $raw] = http_test(route('/ready'));
    integration_check($status === 503 && $raw === '{"error":"service_unavailable"}', 'missing config fails closed');
    [$status, $body] = http_test('/health.php', 'GET', null, false);
    integration_check($status === 200 && $body['mode'] === 'staging', 'liveness independent of private config');
    write_test_config($sourceConfig);
    fwrite(STDOUT, "Backend real HTTP/MySQL integration checks passed: {$passed}. Synthetic rows cleaned up.\n");
} catch (Throwable $error) {
    // Test names are static; do not print raw PDO exception messages or test config.
    $message = str_starts_with($error->getMessage(), 'Check failed:') ? $error->getMessage() : 'Integration failed before a named check; no sensitive details logged.';
    fwrite(STDERR, $message . "\n");
    $failed = true;
} finally {
    if (is_resource($server)) {
        proc_terminate($server);
        proc_close($server);
    }
    if ($pdo instanceof PDO) {
        $remove = $pdo->prepare('DELETE FROM rr_cases WHERE id = ?');
        foreach ($createdIds as $id) {
            $remove->execute([$id]);
        }
    }
    remove_test_tree($temporary);
}
exit(isset($failed) ? 1 : 0);
