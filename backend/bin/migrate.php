<?php
declare(strict_types=1);
if (PHP_SAPI !== 'cli') {
    http_response_code(404);
    exit;
}
require dirname(__DIR__) . '/bootstrap.php';
$pdo = null;
$locked = false;
try {
    $pdo = rr_db(rr_config());
    $locked = (int) $pdo->query("SELECT GET_LOCK('rr_schema_migrations', 10)")->fetchColumn() === 1;
    if (!$locked) {
        throw new RuntimeException('Migration lock unavailable');
    }
    $pdo->exec('CREATE TABLE IF NOT EXISTS rr_schema_migrations (
        version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
        checksum CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
        applied_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci');
    $path = dirname(__DIR__) . '/migrations/001_initial.sql';
    $checksum = hash_file('sha256', $path);
    $find = $pdo->prepare('SELECT checksum FROM rr_schema_migrations WHERE version = ?');
    $find->execute(['001_initial']);
    $existing = $find->fetchColumn();
    if ($existing !== false) {
        if (!hash_equals($checksum, $existing)) {
            throw new RuntimeException('Migration checksum mismatch');
        }
        fwrite(STDOUT, "Schema already current.\n");
    } else {
        // Initial migration is additive and rerunnable after interrupted MySQL DDL.
        foreach (explode(';', (string) file_get_contents($path)) as $statement) {
            if (trim($statement) !== '') {
                $pdo->exec($statement);
            }
        }
        $insert = $pdo->prepare('INSERT INTO rr_schema_migrations (version, checksum) VALUES (?, ?)');
        $insert->execute(['001_initial', $checksum]);
        fwrite(STDOUT, "Applied 001_initial.\n");
    }
    if (!(new RR\Store($pdo))->ready()) {
        throw new RuntimeException('Schema unavailable');
    }
} catch (Throwable $error) {
    fwrite(STDERR, "Migration failed. Check the private configuration, DB permissions and migration checksum locally; no sensitive details were logged.\n");
    exit(1);
} finally {
    if ($locked && $pdo instanceof PDO) {
        $pdo->query("SELECT RELEASE_LOCK('rr_schema_migrations')");
    }
}
