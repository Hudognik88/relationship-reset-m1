<?php
declare(strict_types=1);

// This directory is deployed outside the document root.
ini_set('display_errors', '0');
ini_set('log_errors', '0'); // Never send payloads, credentials or raw exceptions to hosting logs.
date_default_timezone_set('UTC');

require_once __DIR__ . '/src/Http.php';
require_once __DIR__ . '/src/Validation.php';
require_once __DIR__ . '/src/Store.php';

function rr_config(): array
{
    $path = getenv('RR_CONFIG_FILE');
    if ($path === false || $path === '') {
        // Resolve current -> releases/<sha>; shared is two levels above the release.
        $path = dirname(__DIR__, 3) . '/shared/config.php';
    }
    if (!is_file($path) || !is_readable($path)) {
        throw new RuntimeException('Configuration unavailable');
    }
    $config = require $path;
    if (!is_array($config) || ($config['environment'] ?? null) !== 'staging'
        || !is_string($config['operator_token_sha256'] ?? null)
        || !preg_match('/^[a-f0-9]{64}$/D', $config['operator_token_sha256'])
        || $config['operator_token_sha256'] === str_repeat('0', 64)
        || !is_array($config['database'] ?? null)) {
        throw new RuntimeException('Configuration unavailable');
    }
    $db = $config['database'];
    foreach (['host', 'name', 'user', 'password'] as $key) {
        if (!is_string($db[$key] ?? null) || $db[$key] === '') {
            throw new RuntimeException('Configuration unavailable');
        }
    }
    if (!preg_match('/^[A-Za-z0-9_.-]+$/D', $db['host'])
        || !preg_match('/^[A-Za-z0-9_]+$/D', $db['name'])
        || !is_int($db['port'] ?? null) || $db['port'] < 1 || $db['port'] > 65535) {
        throw new RuntimeException('Configuration unavailable');
    }
    return $config;
}

function rr_db(array $config): PDO
{
    $db = $config['database'];
    $pdo = new PDO(
        'mysql:host=' . $db['host'] . ';port=' . $db['port'] . ';dbname=' . $db['name'] . ';charset=utf8mb4',
        $db['user'],
        $db['password'],
        [
            PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            PDO::ATTR_EMULATE_PREPARES => false,
            PDO::ATTR_TIMEOUT => 5,
            PDO::MYSQL_ATTR_MULTI_STATEMENTS => false,
        ]
    );
    $pdo->exec("SET time_zone = '+00:00'");
    return $pdo;
}
